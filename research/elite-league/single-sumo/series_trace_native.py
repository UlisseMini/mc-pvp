"""Apply the opening-match observer to its own real Minecraft view before choosing a rematch policy."""
import math
import random
import sys

import numpy as np
import torch

from learner import ROOT,RTTS,write_json
from matched_native import live
from native_league import fair_cohort,fixture
from remote_import import wait_for
from remote_league import OUT,read
from series_eval import series,tally
from series_public import messages,observed
from series_trace import DEST,LIB,load_actor,summarize


def local_features(o):
    x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,own_age,other_age,_=o
    d=math.hypot(tx-x,tz-z);fx=(tx-x)/max(1e-8,d);fz=(tz-z)/max(1e-8,d)
    def cue(age):return -1. if age<0 else 1-age/300
    return np.clip([(d-3)/2,ty-y,2.5*(vx*fx+vz*fz),2.5*(vx*fz-vz*fx),2*vy,
                    1. if ground else -1.,1. if sprint else -1.,cue(own_age),cue(other_age)],-1.,1.).astype(np.float32)


def own_trace(mid,seat):
    sys.path.insert(0,str(ROOT/'research/sumo-rl'))
    import captures
    file,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
    end=next(ts for ts,_,line in messages(mid,seat) if line.startswith('Finished '+mid+' [sumo]'))
    rows=[local_features(e['observation']) for e in captures.events(file,{'duel_network'})
          if e.get('match')==mid and e['time']<=end]
    return summarize(np.stack(rows)),rows[-1],len(rows)


@torch.no_grad()
def choose_table(models,trace,last,view):
    points=int(2*view['score'])
    fb=torch.tensor([[int(points==i) for i in range(3)]+[math.log1p(view['displayed_duration_ms']/1000)/5,
                    view['own_ping']/150,view['opponent_ping']/150]],dtype=torch.float32)
    choices={}
    for role,m in models.items():
        x=last[None,:] if role=='last' else trace
        q=m(torch.from_numpy(x[None]),fb,role)[0][0]
        choices[role]={f'{a}/{b}':int(q[2*int(a>=70)+int(b>=70)].argmax()) for a,b in RTTS}
    return choices


def run():
    prefix=str(DEST.relative_to(OUT))+'/'
    files=[prefix+role+'/'+name for role in ('feedback','last','trace') for name in ('trained.json','actor.pt')]
    wait_for([*files,prefix+'dev/result.json'])
    spec=read(LIB/'protocol.json');experts=list(spec['experts']);actors={k:live(v) for k,v in spec['experts'].items()}
    foes=spec['field'];na=len(experts);nf=len(foes)
    models={role:load_actor(role,na,nf) for role in ('feedback','last','trace')}
    report=read(DEST/'dev/result.json')['scores']['trace']['opponents']
    weakest=min(report,key=lambda n:(report[n]['series'][0],report[n]['games'][0],n))
    targets=list(dict.fromkeys(('wtap',spec['target'],weakest)));modes=['trace','feedback','fixed','wtap']
    dest=DEST/'native';opener='original-mixture';wt=live(foes['wtap'])
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    bridge,stamp=assessment_bridge()
    write_json(dest/'design.json',dict(opponents=targets,arms=modes,replicates=2,
        design='Play the original broad controller in game one, shared by the trace, feedback-only and fixed-broad arms. Run plain W-tap separately from spawn. Before the second game, commit to the frozen learner’s responses for every displayed RTT pair using only the broad bot’s own first-game decisions and public finish messages. Start every later bout in a new world. Rotate both seat and pings and play all ten rounds.'),fixed=True)
    results={(r,foe,mode):[] for r in range(2) for foe in targets for mode in modes};tables={}
    for g in range(10):
        plan={};context={};committed={}
        for r in range(2):
            for t,foe in enumerate(targets):
                own,other=RTTS[(g+t)%len(RTTS)];seat='ab'[(g+r+t)%2]
                for arm in (('opening','wtap') if g==0 else modes):
                    key=f'series-trace-r{r}-t{t}-g{g}-{arm}'
                    if arm in ('trace','feedback'):
                        table=tables[r,foe][arm];actor=actors[experts[table[f'{own}/{other}']]];committed[key]=table
                    else:actor=wt if arm=='wtap' else actors[opener]
                    v=fixture.trial(key,actor,live(foes[foe]),seat,own,other)
                    v.update(cps_cap=7,command=v['command'].replace(' cps=20 ',' cps=7 '),block=g)
                    plan[key]=v;context[key]=r,foe,arm,seat,own,other
        keys=list(plan);random.Random(7193+g).shuffle(keys)
        write_json(dest/f'g{g:02}/before-game.json',committed,fixed=True)
        played=fair_cohort(dest/f'g{g:02}',{k:plan[k] for k in keys},bridge,server=stamp['server'])
        witness={}
        for key,game in played.items():
            r,foe,arm,seat,own,other=context[key];view=observed(game['mid'],seat)
            actual=read(ROOT/'runtime/results'/(game['mid']+'.json'))
            if (view['own_ping'],view['opponent_ping'])!=(own,other) or view['countdown_received_ms']>actual['start']:
                raise ValueError('Public pre-fight ping changed '+game['mid'])
            if view['score']!=game['score'] or abs(view['displayed_duration_ms']-1000*game['duration'])>51:
                raise ValueError('Recorded owner and offline referee disagree')
            if arm=='opening':
                summary,last,heads=own_trace(game['mid'],seat);tables[r,foe]=choose_table(models,summary,last,view)
                for mode in ('trace','feedback','fixed'):results[r,foe,mode].append(view['score'])
                witness[key]=dict(match=game['mid'],seat=seat,own_heads=heads,tables=tables[r,foe],**view)
            else:
                results[r,foe,arm].append(view['score']);witness[key]=dict(match=game['mid'],seat=seat,**view)
        write_json(dest/f'g{g:02}/public-observations.json',witness,fixed=True)
    report={}
    for mode in modes:
        report[mode]={}
        for t,foe in enumerate(targets):
            row=np.array([results[r,foe,mode] for r in range(2)])
            seats=(np.arange(2)[:,None]+np.arange(10)[None,:]+t)%2
            report[mode][foe]=dict(series=tally(series(row)),games=tally(row),seat={s:tally(row[seats==j]) for j,s in enumerate('ab')},
                rtt={f'{a}/{b}':tally(row[:,(np.arange(10)+t)%4==j]) for j,(a,b) in enumerate(RTTS)})
    write_json(dest/'results.json',dict(scores=report),fixed=True)


if __name__=='__main__':run()
