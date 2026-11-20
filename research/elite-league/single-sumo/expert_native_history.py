"""Audit the finished, matched native cells for the already-played dedicated combat policies."""
import sys

from expert_population import STUDY
from learner import EVIDENCE, ROOT, write_json
from native_league import WT, certify
from remote_league import read

DEST=STUDY/'archived-native-controls'
SOURCES={'seven':('seven/native-65536','idlab-cd5443f19665','sumo-seven-first',4),
         'incumbent':('incumbent/recorded-input-times/native','idlab-c1bc00a1e5f6','v4-ppo-g0496',3)}


def register():
    cells=[]
    for group,(location,expert,target,count) in SOURCES.items():
        folder=EVIDENCE/'long-specialists'/location
        attempt=read(folder/'attempt-0.json')
        bridge=read(folder/'protocol.json')['bridge']
        relevant={q:t for q,t in attempt['plan'].items() if t['block']<count and t['arm'] in (WT,expert) and t['opponent'] in (WT,target)}
        if len(relevant)!=4*count:
            raise ValueError('Incomplete matched design')
        for q,t in relevant.items():
            mid=attempt['matches'][q]
            if int(mid[1:])>10032:raise ValueError('Later replay outside the registered historical cutoff')
            cells.append(dict(group=group,mid=mid,trial=t,bridge=bridge,request=q))
    write_json(DEST/'fixtures.json',dict(cells=cells,design='Retrospective first fully acquired matched blocks up to m10032 of the two already running native campaigns for the exact same frozen button policies. Excludes other maps, other checkpoints and incomplete blocks. Audits do not launch duels or change original campaigns. These matches test the fixed experts, not the rematch router.'),fixed=True)


def run():
    cells=read(DEST/'fixtures.json')['cells']
    records=[]
    for v in cells:
        f=DEST/'audits'/(v['mid']+'.json')
        if not f.exists():
            accepted=certify(v['mid'],v['trial'],v['bridge'])
            write_json(f,accepted,fixed=True)
        audit=read(f)
        t=v['trial']
        records.append(dict(group=v['group'],mid=v['mid'],block=t['block'],side=t['side'],own=t['rtt'],
            other=t['opponent_rtt'],arm=t['arm'],foe=t['opponent'],valid=audit['valid'],score=audit.get('score'),reason=audit.get('reason')))
    scores={}
    def tally(cases):
        return [sum(r['valid'] and r['score']==z for r in cases) for z in (1,.5,0)]
    for group,(_,expert,target,count) in SOURCES.items():
        for foe in (target,WT):
            for arm in (expert,WT):
                matches=[r for r in records if (r['group'],r['arm'],r['foe'])==(group,arm,foe)]
                scores[f'{group}/{arm}/{foe}']=dict(wdl=tally(matches),invalid=sum(not r['valid'] for r in matches),
                    seats={s:tally([r for r in matches if r['side']==s]) for s in 'ab'},
                    ping={f"{r['own']}/{r['other']}":tally([v for v in matches if (v['own'],v['other'])==(r['own'],r['other'])]) for r in matches})
    write_json(DEST/'results.json',dict(scores=scores,ledger=records),fixed=True)


if __name__=='__main__':
    if sys.argv[1:] == ['register']:register()
    else:run()
