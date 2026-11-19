"""Transfer the historical league and train a new direct-control actor on the distance-calibrated sumo rules."""
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from collections import Counter,defaultdict

from adaptation import dump,freeze
import champions
import focus
from ladder import roster_fields
from recorded_archive import field
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from run import HERE,ROOT,calendars,compile_model,java

def read(p):return json.loads(p.read_text())
def roster():
    all_bots,students=field()
    for n in ('v3-memory-27','v3-feed-39'):all_bots[n]=DEST/'memory'/(n+'.net')
    for track in ('fixed','jitter'):
        n=next(r['name'] for r in read(DEST/'transport-training/results.json')['ranks'] if r['name'].startswith('v3-link-'+track))
        all_bots[n]=DEST/'transport-training'/(n+'.net')
    for n in ('v3-exchange-1','v3-exchange-2'):all_bots[n]=DEST/'trade-fit'/(n+'.net')
    return all_bots,students

def main():
    out=DEST/'range-model';out.mkdir(exist_ok=True)
    tmp=TEMP/'range';tmp.mkdir(exist_ok=True)
    pool,students=roster();parent='v3-feed-39';rnn='v3-memory-27'
    entrants={n:pool[n] for n in ['wtap',parent,rnn,*students,'mmc13-trade-2-fit','continuation-03-fit','v3-exchange-1','v3-exchange-2']}
    foes={n:pool[n] for n in ['wtap','hold','population-09','adapt-01-recurrent','adapt-02-recurrent','mmc13-trade-2-fit','continuation-03-fit',*students,parent,rnn]}
    train=calendars('training_clocks');dev=calendars('development_selection_clocks')
    dump(out/'protocol.json',dict(profile='mmc-range',physics=read(HERE.parent/'mmc-knockback/range-fit.json')['selection'],
        fit_donors=[p.name for p in train],development=[p.name for p in dev],entrants=list(entrants),foes=list(foes),
        design='Determine the weakest distinct matchup under revised impulse geometry, then relearn all action heads starting from the feed-forward challenger. 512 PPO updates, 32 paired scenarios per update. Separate development ranks from a 251+ historical opponent stress test. Native matched controls use the new independently tagged law.'))
    compile_model()
    ranks=evaluate(out/'incumbents.jsonl.gz',entrants,foes,dev,'mmc-range')
    incumbent=ranks[0]['name'];publish('minemen-v4-incumbents',incumbent,entrants)
    weak=[n for n in ranks[0]['weakest'] if n!=incumbent][:3]
    league=focus.unique(pool);groups={n:(n if n in ['wtap',*weak,incumbent,rnn,parent,*students] else 'legacy-rule' if p is None else 'archive') for n,p in league.items()}
    with tempfile.TemporaryDirectory(dir=tmp) as name:
        path=Path(name)/'opponents';roster_fields(path,league,groups,{})
        freeze(tmp/'opponents',path.read_bytes())
    updates=tmp/'updates'
    if not updates.is_dir():
        with tempfile.TemporaryDirectory(dir=tmp) as name:
            t=Path(name)/'updates';subprocess.run(java('PPO',t,512,32,859103,pool[parent],HERE/'frozen',HERE/'challenge-frozen','--range-mouse-league',tmp/'opponents',*train),cwd=ROOT,check=True);t.rename(updates)
    freeze(out/'training.jsonl',(updates/'training.jsonl').read_bytes())
    candidates={f.stem:f for f in sorted(updates.glob('*.net'))};short_foes={n:foes[n] for n in dict.fromkeys(['wtap',*weak,rnn,'hold'])}
    screen=evaluate(out/'screen.jsonl.gz',entrants|candidates,short_foes,train[::2],'mmc-range')
    publish('minemen-v4-ppo-screen',screen[0]['name'],entrants|candidates)
    for r in [r for r in screen if r['name'] in candidates][:6]:
        n='v4-ppo-'+r['name'];entrants[n]=out/(n+'.net');freeze(entrants[n],candidates[r['name']].read_bytes())
    ranks=evaluate(out/'development.jsonl.gz',entrants,foes,dev,'mmc-range')
    student=next(r['name'] for r in ranks if r['name'].startswith('v4-ppo-'));winner=ranks[0]['name']
    champions.install(student,entrants[student],'PPO with measured Minemen distance knockback',student,'direct')
    publish('minemen-v4-ppo',winner,entrants)
    dump(out/'selection.json',dict(incumbent=incumbent,winner=winner,student=student,weakest_targeted=weak,ranks=ranks))
    arms={n:entrants[n] for n in dict.fromkeys(['wtap',winner,student,incumbent,students[-1]])}
    archive=focus.unique(pool)
    ranks=evaluate(out/'archive.jsonl.gz',arms,archive,dev,'mmc-range')
    publish('minemen-v4-archive',ranks[0]['name'],arms)
    dump(out/'archive-ranks.json',ranks)
    print('Distance-calibrated PPO',student,'cup',winner,'archive',ranks[0]['name'],len(archive),flush=True)
if __name__=='__main__':main()
