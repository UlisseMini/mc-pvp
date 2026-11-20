"""A two-game native transfer test of the specific public cue found in the archive drill."""
import random
import sys

from learner import ROOT,RTTS,write_json
from matched_native import live
from native_league import fair_cohort,fixture
from remote_league import read
from series_bank import FOLDER
from series_eval import load_models,tally
from series_learn import choose
from series_public import observed


def run():
    import numpy as np
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    source=FOLDER/'archive-reversal';study=source/'hidden-score';dest=study/'native-second-game'
    spec=read(source/'protocol.json');foes=list(spec['field']);experts=list(spec['experts'])
    models=load_models(2,9,len(experts),len(foes),study)
    wt=live(read(FOLDER/'protocol.json')['field']['wtap'])
    broad=live(read(FOLDER/'protocol.json')['experts']['original-mixture'])
    bridge,stamp=assessment_bridge()
    rounds=8
    write_json(dest/'design.json',dict(opponents=foes,replicates=rounds,actor=read(study/'learn-02/h9-s4101/trained.json')['sha256'],
        design='Play one genuine opening against each opponent in each of eight matched seat/RTT blocks. In game two choose from the learned observer’s own opening, or the corresponding opening against the other opponent. Supply the same seat and pings to the two donor openers. Precommit all choices for all displayed RTT pairs and read only the participant’s chat. Play fresh independent native duels with each selected expert, the unrestricted broad bot and plain W-tap. Swapping public history across opponents is an offline intervention.',
        scoring='Compare game-two ring-outs separately against each fixed opponent. Each game starts anew, so W-tap and the broad control need no opener. Keep actual chat readings and report both seats and RTT pairs. This is an independent native sample and has no identical full-state forks.'),fixed=True)
    def arrange(g):
        plan={};conditions={};committed={}
        for r in range(rounds):
            own,other=RTTS[(r//2+g)%len(RTTS)];side='ab'[(r+g)%2]
            for j,foe in enumerate(foes):
                for arm in (('opening',) if g==0 else ('real','other','wtap','broad')):
                    key=f'series-cue-native-r{r}-o{j}-{arm}'
                    events=[] if g==0 else [history[r,1-j if arm=='other' else j]]
                    table={f'{a}/{b}':choose(models,events,a,b,len(experts)) for a,b in RTTS} if arm not in ('wtap','broad') else {}
                    committed[key]=table;action=table.get(f'{own}/{other}',0)
                    actor=wt if arm=='wtap' else broad if arm=='broad' else live(spec['experts'][experts[action]])
                    v=fixture.trial(key,actor,live(spec['field'][foe]),side,own,other)
                    v.update(cps_cap=7,command=v['command'].replace(' cps=20 ',' cps=7 '),block=r)
                    plan[key]=v;conditions[key]=(r,j,arm,side,own,other,action)
        keys=list(plan);random.Random(752360+g).shuffle(keys)
        write_json(dest/f'g{g}/before-game-choices.json',committed,fixed=True)
        matches=fair_cohort(dest/f'g{g}',{k:plan[k] for k in keys},bridge,server=stamp['server'])
        events={};results={}
        for key,record in matches.items():
            r,j,arm,side,own,other,a=conditions[key];view=observed(record['mid'],side)
            start=read(ROOT/'runtime/results'/(record['mid']+'.json'))['start']
            if (view['own_ping'],view['opponent_ping'])!=(own,other) or view['countdown_received_ms']>start:
                raise ValueError('Missing pre-fight public pings for '+record['mid'])
            if record['score']!=view['score'] or abs(1000*record['duration']-view['displayed_duration_ms'])>51:
                raise ValueError('Participant feedback and referee disagree for '+record['mid'])
            events[r,j,arm]=(a,view['score'],view['displayed_duration_ms'],own,other)
            results[key]=dict(foe=foes[j],role=arm,rep=r,seat=side,mid=record['mid'],**view)
        write_json(dest/f'g{g}/public-results.json',results,fixed=True)
        return events,results
    opening,_=arrange(0);history={(r,j):event for (r,j,arm),event in opening.items()}
    for r in range(rounds):
        if history[r,0][0]!=history[r,1][0]:raise ValueError('Opening differed before feedback')
    _,games=arrange(1)
    summary={}
    for arm in ('real','other','wtap','broad'):
        rows=[v for v in games.values() if v['role']==arm]
        def score(subset):return tally(np.array([v['score'] for v in subset]))
        summary[arm]=dict(total=score(rows),opponent={f:score([v for v in rows if v['foe']==f]) for f in foes},
            seat={s:score([v for v in rows if v['seat']==s]) for s in 'ab'},
            rtt={f'{a}/{b}':score([v for v in rows if (v['own_ping'],v['opponent_ping'])==(a,b)]) for a,b in RTTS})
    write_json(dest/'results.json',dict(second_games_per_arm=rounds*len(foes),scores=summary),fixed=True)


if __name__=='__main__':run()
