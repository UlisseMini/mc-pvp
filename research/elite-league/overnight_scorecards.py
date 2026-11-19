"""Report scoreable native subsets at admission close, retaining unmatched and unaudited counts."""
from collections import defaultdict
import gzip
import json
from pathlib import Path
import re

from mmc_league import DIGEST, EVIDENCE, VERIFIED_STAGES, WORK, WT, contest_plan, native_name
from mmc_scorecard import points, record


def read(p):return json.loads(p.read_text())


def referee(mid, request, task):
    g=read(WORK.parents[2]/'runtime/results'/(mid+'.json'))
    side=task['side'];other='a' if side=='b' else 'b';arena=g['sumo_arena']
    if (g['request'],g['id'],g['policy_'+side],g['policy_'+other],g['practice_preset'])!=(
            request,mid,task['arm'],task['opponent'],task['profile']):raise ValueError('Wrong native fixture '+mid)
    if (arena['observed_floor_sha256'],arena['diameter'],arena['start_gap'],arena['floor_blocks'])!=(DIGEST,13,8,137):
        raise ValueError('Different ring '+mid)
    for s in 'ab':
        p=g['parameters_'+s]
        if (g['ping_'+s],float(p['attack_reach']),float(p['click_cps']),float(p['reaction_ms']),p['aim_input'],
            float(p['aim_noise_deg']),float(p['aim_delay_ms']),float(p['aim_turn_rate_dps']))!=(task['rtt'],3,20,0,'mouse',0,0,0):
            raise ValueError('Different native controls '+mid)
    if g['reason'] not in ('ring_out','double_ring_out','timeout_draw'):raise ValueError('Non-regulation native result '+mid)
    return .5 if not g['winner'] else int(g['winner']==g[side])


def pair_card(candidate, baseline, repeats):
    overlap=sorted(candidate.keys() & baseline.keys())
    groups={f'{rtt}/{side}':[k for k in overlap if k[:2]==(rtt,side)] for rtt in (0,80) for side in 'ab'}
    blocks={rep:[k for k in overlap if k[2]==rep] for rep in range(repeats)}
    balanced=[k for rep,ks in blocks.items() if len(ks)==4 for k in ks]
    def wdls(keys):
        return dict(candidate=record([candidate[k] for k in keys]),plain_wtap=record([baseline[k] for k in keys]))
    return dict(scheduled_per_arm=4*repeats,checked_candidate=len(candidate),checked_plain_wtap=len(baseline),
                jointly_observed=len(overlap),overlap=wdls(overlap),
                by_seat_rtt={k:wdls(v) for k,v in groups.items()},
                complete_repeat_ids=[rep for rep,v in blocks.items() if len(v)==4],
                balanced=wdls(balanced),by_complete_repeat={str(rep):wdls(v) for rep,v in blocks.items() if len(v)==4})


def partial(stage, snapshot):
    d=EVIDENCE/stage;spec=read(d/'schedule.json');name='mmc13-'+stage
    plan=contest_plan(stage,spec['arms'],spec['opponents'],spec['repeats'],spec['profile'])
    arms={native_name(n):v for n,v in spec['arms'].items()}
    foes={native_name(n):v for n,v in spec['opponents'].items()}
    repeats={native_name(n):v for n,v in spec['repeats'].items()}
    scores=defaultdict(dict);selected={};unchecked=[];excluded=[]
    for q,r in snapshot['raw_native'].items():
        base=re.split('-(?:input|view)-retry[0-9]+',q,maxsplit=1)[0]
        if base not in plan:continue
        mid=r['mid'];t=plan[base]
        if q in snapshot['known_invalid']:
            excluded.append([q,mid,snapshot['known_invalid'][q]]);continue
        path=WORK/(name+'-scores')/'movement'/(mid+'.json')
        if not path.exists():unchecked.append([q,mid]);continue
        audit=read(path)
        if (audit['mid'],audit['request'])!=(mid,q):raise ValueError('Wrong movement audit '+mid)
        if not audit['valid']:excluded.append([q,mid,'physical inputs']);continue
        if base in selected:raise ValueError('Multiple eligible attempts at '+base)
        result=referee(mid,q,t);selected[base]=mid
        scores[t['arm'],t['opponent']][t['rtt'],t['side'],t['rep']]=result
    model=d/'model.jsonl.gz';sim=defaultdict(dict)
    if model.exists():
        with gzip.open(model,'rt') as f:
            for r in map(json.loads,f):
                k=r['clock'],r['rtt'],r['side'];c=sim[native_name(r['arm']),native_name(r['opponent'])]
                if k in c:raise ValueError('Duplicate model cell')
                c[k]=r['score']
    out={}
    for a in arms:
        cells={}
        for o in foes:
            cell=pair_card(scores[a,o],scores[WT,o],repeats[o])
            for s in (a,WT):
                if model.exists() and (len(sim[s,o])!=32 or sim[s,o].keys()!=sim[WT,o].keys()):
                    raise ValueError('Incomplete matched simulation')
            if model.exists():
                cell['model']=dict(candidate=record(list(sim[a,o].values())),plain_wtap=record(list(sim[WT,o].values())))
                cell['model_by_seat_rtt']={f'{t}/{s}':{
                    label:record([v for (_,r,seat),v in sim[who,o].items() if r==t and seat==s])
                    for label,who in [('candidate',a),('plain_wtap',WT)]} for t in (0,80) for s in 'ab'}
            cells[o]=cell
        distinct=[o for o in foes if foes[o]!=arms[a]]
        def weakest(mode):
            rates={o:points(cells[o][mode]['candidate']) for o in distinct if mode in cells[o] and cells[o][mode]['candidate']['games']}
            return sorted(o for o,v in rates.items() if v==min(rates.values()))
        out[a]=dict(weakest_observed_distinct_balanced_native=weakest('balanced'),weakest_distinct_model=weakest('model'),
                    missing_balanced_native=[o for o in distinct if not cells[o]['complete_repeat_ids']],opponents=cells)
    return dict(planned=len(plan),physically_checked_fixtures=len(selected),selected_matches=selected,
                pending_physical_audit=unchecked,excluded_attempts=excluded,arms=out)


def build(snapshot):
    stages={n:partial(n,snapshot) for n in ('opening','final',*VERIFIED_STAGES) if (EVIDENCE/n/'schedule.json').exists()}
    return dict(as_of=snapshot['as_of'],rule='Only referee results finished by the snapshot time and subsequently verified for both physical players. Pair each candidate with W-tap against the same opponent in the same seat, added RTT, and repeat index. Distinct native games have independent timing. Weakest observed native uses complete four-seat/RTT repeat blocks only. Opponents missing even one full block are named explicitly. Native rows in unfinished tournaments are descriptive and confer no title. No simulation or partial native score changes the common final.',stages=stages)
