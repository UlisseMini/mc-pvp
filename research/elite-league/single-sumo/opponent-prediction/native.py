"""Evaluate frozen predictions on previously recorded Forge bouts."""

import argparse
from bisect import bisect_right
from collections import Counter
import gzip
from math import hypot, sqrt

import numpy as np
import orjson
import torch

from prepare import HORIZON, MAX_HEADS, HERE, ROOT, OUT, load, save
from selective import Selective
from train import Panel, Predictor, measure, summary

ALIASES = {'research-wtap20':'wtap','hold-w':'hold'}
KINDS = {b'"native_input_head"',b'"duel_network"',b'"legacy_head"',b'"click"',b'"engine_attack"'}


def register(setup):
    fixtures = []
    for path in sorted((ROOT/'runtime/results').glob('m*.json')):
        m = path.stem
        if not m[1:].isdigit() or int(m[1:])<8700:
            continue
        d = load(path)
        arena = d.get('sumo_arena') or {}
        if (d.get('mode')!='sumo' or arena.get('shape')!='mmc_recorded_13_v1'
                or arena.get('floor_blocks')!=137 or d.get('reason') not in ('ring_out','timeout','simultaneous_ring_out')
                or d.get('combat_profile',{}).get('knockback_model')!='mmc_recorded_range_v4'
                or d.get('ping_changes') or not all(d.get(f'click_cps_{s}')==7
                   and d.get(f'attack_reach_{s}')==3 for s in 'ab')):
            continue
        if not all(len(list((ROOT/'runtime/pov'/m).glob(f'{s}-client-*-events.jsonl.gz')))==1 for s in 'ab'):
            continue
        names = {s:ALIASES.get(d[f'policy_{s}'],d[f'policy_{s}']) for s in 'ab'}
        for s,o in ('ab','ba'):
            if names[s] in setup['actors'] and names[o] in setup['foes']:
                fixtures.append(dict(match=m,side=s,actor=names[s],foe=names[o]))
    return fixtures


def read_side(m, s, d):
    heads, actions = {}, {}
    presses, contacts = [], []
    path, = (ROOT/'runtime/pov'/m).glob(f'{s}-client-*-events.jsonl.gz')
    with gzip.open(path,'rb') as stream:
        for line in stream:
            if not any(k in line[:70] for k in KINDS):
                continue
            e = orjson.loads(line)
            if e.get('match')!=m or not d['start']<=e['time']<=d['finish']+50:
                continue
            k = e['type']
            if k=='native_input_head':
                heads[e['native_tick']] = e['decision_ns']
            elif k in ('legacy_head','duel_network'):
                actions[e['tick']] = e
            elif k=='click':
                presses.append(e['monotonic_ns'])
            elif k=='engine_attack' and e.get('target_is_canonical'):
                contacts.append(e['monotonic_ns'])
    if len(heads)<4 or any(t not in actions for t in heads):
        raise ValueError('Missing own-view decision rows')
    presses.sort(); contacts.sort()
    if any(b-a<142_857_143 for a,b in zip(presses,presses[1:])):
        raise ValueError('Recorded physical click cap failed')
    rows = []
    floor = d['sumo_arena']['top_y']
    lane = d['sumo_arena']['center_offset']
    cx,cz = 4096*(d['arena']+1)+lane,lane
    for tick,at in sorted(heads.items(),key=lambda p:p[1]):
        node = actions[tick]
        if node['type']=='duel_network':
            u = node['observation']; a = node['action']
            action = (a%4,a//4%3-1,a>=24)
            row = (*u[:11], *(1500 if v<0 else v for v in u[11:13]))
        else:
            x,y,z,vx,vy,vz,ground,sprint = node['own']
            w,ctrl,side,_ = node['keys']
            action = (2 if w and ctrl else int(w),-int(np.sign(side)),False)
            row = (x,y,z,*node['target'][:3],vx,vy,vz,ground,sprint,
                   *(node['observed']-v if v>0 else 1500 for v in node['cues']))
        x,y,z,tx,ty,tz = row[:6]
        in_arena = (hypot(x-cx,z-cz)<18 and hypot(tx-cx,tz-cz)<18 and
                    y>=floor-5 and ty>=floor-5)
        if not in_arena:
            if rows:
                break
            continue
        rows.append((at,row,action))
    if len(rows)<4:
        raise ValueError('Missing in-arena decision rows')
    return rows,presses,contacts


def native_features(rows,presses,contacts,d,side):
    floor = d['sumo_arena']['top_y']
    center = d['sumo_arena']['center_offset']
    # InputStudy simulates arena zero at (4096.5, 12, .5). Translate native
    # lanes to that same world frame before evaluating the frozen features.
    cx,cz = 4096*(d['arena']+1)+center-4096.5,center-.5
    last = None
    previous = (0,0,False)
    observations = []
    for at,row,action in rows[:MAX_HEADS]:
        x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,own_hurt,target_hurt = row
        x-=cx; tx-=cx; z-=cz; tz-=cz
        dx,dz = tx-x,tz-z
        gap = hypot(dx,dz)
        norm = max(.001,gap)
        elapsed = 50 if last is None else max(8,(at-last[0])/1e6)
        mx,my,mz = (0,0,0) if last is None else [(a-b)*50/elapsed for a,b in zip((tx,ty,tz),last[1:])]
        nearest = sqrt(max(0,abs(dx)-.4)**2+max(0,abs(dz)-.4)**2+
                       max(0,ty-.1-y-1.62,y+1.62-ty-1.9)**2)

        def seen_age(events):
            i = bisect_right(events,at-1)-1
            return min(1.5,(at-events[i])/1e9) if i>=0 else 1.5

        key,lateral,jump = previous
        observations.append([gap,nearest,y-floor+8,ty-floor+8,(dx*vx+dz*vz)/norm,vy,(dz*vx-dx*vz)/norm,
            ground,sprint,(dx*mx+dz*mz)/norm,my,(dz*mx-dx*mz)/norm,hypot(x,z),hypot(tx,tz),
            (dx*x+dz*z)/norm,(dz*x-dx*z)/norm,np.clip(own_hurt/1000,0,1.5),np.clip(target_hurt/1000,0,1.5),
            seen_age(contacts),seen_age(presses),min(.25,elapsed/1000),d[f'initial_ping_{side}']/1000,
            key==0,key==1,key==2,lateral,jump])
        last = at,tx,ty,tz
        previous = action
    return observations


def capture(setup):
    folder = OUT/'native'
    folder.mkdir(exist_ok=True)
    manifest = folder/'fixtures.json'
    frozen = HERE/'native-fixtures.json'
    if not manifest.exists():
        save(manifest,load(frozen) if frozen.exists() else register(setup))
    if frozen.exists() and load(manifest)!=load(frozen):
        raise ValueError('Cached native fixtures differ from the frozen panel')
    if (OUT/'native.npz').exists():
        return
    ids, donor, actor, cuts, xx, yy, tt = [], [], [], [0], [], [], []
    rejected = Counter()
    active = ''
    for fixture in load(manifest):
        m,s = fixture['match'],fixture['side']
        if active!=m:
            active = m
            d = load(ROOT/'runtime/results'/f'{m}.json')
            try:
                streams = {k:read_side(m,k,d) for k in 'ab'}
            except (KeyError,ValueError) as error:
                rejected[str(error)] += 1
                streams = None
        if streams is None:
            continue
        ours,presses,contacts = streams[s]
        theirs,attacks,_ = streams['b' if s=='a' else 'a']
        features = native_features(ours,presses,contacts,d,s)
        times = [t for t,_,_ in theirs]
        start = min(ours[0][0],theirs[0][0])
        coverage = min(ours[-1][0],theirs[-1][0])
        labels = []
        for at,_,_ in ours[:MAX_HEADS]:
            end = at+HORIZON*1_000_000
            i,j = bisect_right(times,at),bisect_right(times,end)
            click = bisect_right(attacks,at)
            if end>coverage or i==j:
                labels.append([-1]*4)
            else:
                key,lateral,_ = theirs[i][2]
                labels.append([key,lateral+1,click<len(attacks) and attacks[click]<=end,
                               any(r[2][2] for r in theirs[i:j])])
        if not any(r[0]>=0 for r in labels):
            rejected['No fully observed forecast windows'] += 1
            continue
        xx.extend(features); yy.extend(labels); tt.extend((t-start)/1e6 for t,_,_ in ours[:MAX_HEADS])
        cuts.append(len(xx)); ids.append(setup['foes'].index(fixture['foe']))
        donor.append(int(m[1:])); actor.append(setup['actors'].index(fixture['actor']))
    _,donor=np.unique(donor,return_inverse=True)
    np.savez_compressed(OUT/'native.npz',x=np.asarray(xx,dtype=np.float32),y=np.asarray(yy,dtype=np.int8),
                       time=np.asarray(tt),cuts=cuts,foe=ids,donor=donor,actor=actor)
    save(folder/'coverage.json',dict(povs=len(ids),matches=len(np.unique(donor)),rejected=dict(rejected),
                                    opponents=dict(Counter(setup['foes'][i] for i in ids))))
    print('Native traces:',len(ids),'perspectives;',len(np.unique(donor)),'matches',flush=True)


def evaluate(setup):
    panel = Panel('native',setup)
    ids = np.arange(len(panel.foe))
    for seed in setup['seeds']:
        stats = {k:[np.asarray(i,dtype=np.float32) for i in v]
                 for k,v in load(OUT/f'normalization-{seed}.json').items()}
        predictions = {}
        trained = {}
        for name,kind in [('unseen_mlp','mlp'),('unseen_gru','gru'),('matched_mlp','mlp'),
                          ('matched_gru','gru'),('matched_id','id')]:
            unseen = name.startswith('unseen')
            model = Predictor(kind,panel.x.shape[1],len(setup['foes']))
            model.load_state_dict(torch.load(OUT/'weights'/f'{seed}-{name}.pt',weights_only=True)['weights'])
            trained[name] = model
            take = ids[panel.unseen] if unseen else ids
            mean,scale = stats['seen' if unseen else 'all']
            predictions[name] = measure(model,panel,take,mean,scale,store=True)
            if name=='unseen_gru':
                predictions['unseen_reset'] = measure(model,panel,take,mean,scale,store=True,reset=True)
        selective = Selective(trained['unseen_mlp'],trained['unseen_gru'])
        predictions['unseen_selective'] = measure(selective,panel,ids[panel.unseen],*stats['seen'],store=True)
        predictions['unseen_selective_reset'] = measure(selective,panel,ids[panel.unseen],*stats['seen'],
                                                        store=True,reset=True)
        specialist = tuple(np.full_like(array,np.nan) for array in predictions['matched_id'])
        for i in np.unique(panel.foe):
            model = Predictor('mlp',panel.x.shape[1],len(setup['foes']))
            model.load_state_dict(torch.load(OUT/'weights'/f'{seed}-specialist-{i}.pt',weights_only=True)['weights'])
            values = measure(model,panel,ids[panel.foe==i],*stats['all'],store=True)
            for dest,value in zip(specialist,values):
                valid = np.isfinite(value[:,0])
                dest[valid] = value[valid]
        predictions['specialist'] = specialist
        save(OUT/'native'/f'results-{seed}.json',summary(panel,predictions,setup))


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-only',action='store_true')
    args = parser.parse_args()
    setup = load(OUT/'protocol.json')
    capture(setup)
    if not args.prepare_only:
        evaluate(setup)
