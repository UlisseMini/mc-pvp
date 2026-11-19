"""Give a later empirical-game winner an independently frozen chance to replace the original incumbent."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
from empirical_game import NEURAL,EXPLICIT
from ladder_transfer import WT,HOLD,native_install,plan
from native_trials import run_batch
from run import HERE,compile_model
from tournament import grid
from novel_field import target
import champions

RETEST=(WT,HOLD,'elite-br-hold','probe-two-beat','elite-m04-neural')

def weights():
    inc,ip=target(HERE/'ladder-frozen')
    return inc,{inc:ip,NEURAL:HERE/'empirical-game-frozen/neural.net',EXPLICIT:HERE/'empirical-game-frozen/explicit.net'}

def read_scores(source,expected):
    with gzip.open(source,'rt') as f:e=json.load(f)
    if e['manifest']['plan']!=expected or e['manifest']['matches'].keys()!=expected.keys() or e['summary']['binding_disagreements']:
        raise ValueError('Native schedule or movement replay differs')
    rows={}
    for r in e['matches']:
        key=r['arm'],r['opponent']
        rows.setdefault(key,[]).append(1 if r['win'] else .5 if r['draw'] else 0)
    return rows

def scores(rows):
    by={}
    for (arm,opp),a in rows.items():by.setdefault(arm,{})[opp]=sum(a)/len(a)
    result={}
    for arm,field in by.items():
        mean=sum(field.values())/len(field);worst=min(field.values())
        result[arm]=dict(mean=mean,worst=worst,merit=.6*mean+.4*worst,field=field)
    return result

def nominate(source):
    inc,paths=weights();arms=list(dict.fromkeys([WT,HOLD,inc,NEURAL,EXPLICIT]));opps=list(dict.fromkeys([WT,HOLD,inc,'probe-hit-select','probe-targeted']))
    ranks=scores(read_scores(source,plan('egame',arms,opps,3)))
    eligible=[a for a in (NEURAL,EXPLICIT) if ranks[a]['merit']>max(ranks[inc]['merit'],ranks[WT]['merit']) and
              ranks[a]['mean']-ranks[inc]['mean']>=.05-1e-12 and ranks[a]['field'][inc]>=.5]
    selected=min(eligible,key=lambda a:(-ranks[a]['merit'],-ranks[a]['mean'],a)) if eligible else None
    return dict(incumbent=inc,nominee=selected,ranking=ranks,source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                sha256={k:hashlib.sha256(p.read_bytes()).hexdigest() for k,p in paths.items() if p})

def study(decision,out,clocks):
    inc,paths=weights();n=decision['nominee']
    if not n or inc!=decision['incumbent']:raise ValueError('No independently frozen challenge')
    for k,h in decision['sha256'].items():
        if hashlib.sha256(paths[k].read_bytes()).hexdigest()!=h:raise ValueError('Contender changed')
    a={WT:None,HOLD:None,inc:paths[inc],n:paths[n]}
    o={WT:None,HOLD:None,'elite-br-hold':HERE/'challenge-frozen/anti-hold.net',
       'probe-two-beat':HERE/'novel-frozen/probe-two-beat.net','elite-m04-neural':HERE/'ladder-frozen/elite-m04-neural.net'}
    if clocks:
        compile_model();out.mkdir(parents=True);ts=sorted(clocks.glob('*.txt.gz'))
        if len(ts)!=8:raise ValueError('Expected eight new mirror donors')
        def aliases(field):return {{WT:'wtap',HOLD:'hold'}.get(k,k):p for k,p in field.items()}
        grid(out,'regulation',aliases(a),aliases(o),ts,mode='mouse')
    else:native_install({k:p for k,p in (a|o).items() if p});run_batch(plan('echamp',list(a),list(o),4),out)

def choose(decision,source,simulation):
    inc,n=decision['incumbent'],decision['nominee']
    actual=scores(read_scores(source,plan('echamp',list(dict.fromkeys([WT,HOLD,inc,n])),RETEST,4)))
    rows={}
    with gzip.open(simulation,'rt') as f:
        for r in map(json.loads,f):
            arm={'wtap':WT,'hold':HOLD}.get(r['arm'],r['arm']);rows.setdefault((arm,r['opponent']),[]).append(r['score'])
    sim=scores(rows);a=actual[n]
    passed=a['merit']>max(actual[inc]['merit'],actual[WT]['merit']) and a['mean']>max(actual[inc]['mean'],actual[WT]['mean']) and sim[n]['merit']>sim[WT]['merit'] and a['field'][WT]>.5
    winner=n if passed else inc
    _,paths=weights()
    source=paths[winner] if paths[winner] else champions.POLICIES/(winner+'.properties')
    champions.install('league-native-final',source,'Independent Minecraft title match winner','league-native-final')
    return dict(winner=winner,advanced=passed,native=actual,model=sim,evidence=hashlib.sha256(source.read_bytes()).hexdigest())

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='cmd',required=True)
    k=s.add_parser('nominate');k.add_argument('native',type=Path);k.add_argument('out',type=Path)
    k=s.add_parser('study');k.add_argument('decision',type=Path);k.add_argument('out',type=Path);k.add_argument('--clocks',type=Path)
    k=s.add_parser('choose');k.add_argument('decision',type=Path);k.add_argument('native',type=Path);k.add_argument('model',type=Path);k.add_argument('out',type=Path)
    a=p.parse_args()
    if a.cmd=='study':study(json.loads(a.decision.read_text()),a.out,a.clocks)
    else:
        result=nominate(a.native) if a.cmd=='nominate' else choose(json.loads(a.decision.read_text()),a.native,a.model)
        with a.out.open('x') as f:json.dump(result,f,indent=2)
