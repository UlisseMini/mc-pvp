"""Distill paired midtrade counterfactuals into a playable local-observation response and test from the opening."""
import gzip
import json
from pathlib import Path
import subprocess
import tempfile
import time

from adaptation import dump,freeze
import champions
from recorded_archive import field
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from run import ROOT,calendars,compile_model,java
from trade_response import WORDS

def main():
    src=DEST/'trade-branches';out=DEST/'trade-fit';out.mkdir(exist_ok=True)
    while not (src/'exchange-2-summary.json').exists():time.sleep(5)
    models,students=field();parent=students[-1];mem=json.loads((DEST/'memory/results.json').read_text())
    for n in {*mem['tracks'].values(),mem['winner']}:
        if n not in models:models[n]=DEST/'memory'/(n+'.net')
    train=calendars('training_clocks');fit={p.name.split('.')[0] for p in train[:12]};held={p.name.split('.')[0] for p in train[12:]}
    foes={n:models[n] for n in ['wtap','hold','population-09','adapt-01-recurrent','adapt-02-recurrent',students[0],'mmc13-actor-2-fit','mmc13-trade-2-fit','continuation-03-fit']}
    arms={n:models[n] for n in ['wtap',parent,*sorted(set(mem['tracks'].values()))]}
    dump(out/'protocol.json',dict(parent=parent,training_donors=sorted(fit),threshold_donors=sorted(held),
        development=[p.name for p in calendars('development_selection_clocks')],field=list(foes),
        problem='Against population-09, an oracle rescued 23 of 24 first-trade losses in the training clocks. Always releasing W gained only 1.5 net points over 64 openings, so the question is whether the right time is observable.',
        policy='At the first or second locally observed exchange, use a nearest-prototype rule to choose three legal input heads from eight alternatives or continue unchanged. Freeze the PPO weights. Return to the primed PPO immediately afterward. No opponent identity, native state or future is supplied to the playable controller.'))
    compile_model()
    for exchange in (1,2):
        name='v3-exchange-'+str(exchange);target=out/(name+'.net');evidence=out/(name+'.json')
        if not target.exists():
            rows=[[],[]]
            with gzip.open(src/f'exchange-{exchange}.jsonl.gz','rt') as f:
                for r in map(json.loads,f):
                    which=0 if r['clock'] in fit else 1 if r['clock'] in held else None
                    if which is None:raise ValueError('Unregistered training origin')
                    cs=r.get('continuations');rec=[r['opponent'],r['base'],cs['wtap'][0] if cs else r['base']]
                    if cs:rec.extend(r['x']);rec.extend(cs[n][0] for n in WORDS)
                    rows[which].append(' '.join(map(str,rec)))
            if list(map(len,rows))!=[240,80]:raise ValueError('Missing causal training cases')
            with tempfile.TemporaryDirectory(dir=TEMP) as temp:
                t=Path(temp);files=[t/'fit',t/'validation']
                for p,rs in zip(files,rows):p.write_text('\n'.join(rs)+'\n')
                dest=t/'policy';subprocess.run(java('TradeFit',*files,models[parent],dest,exchange),cwd=ROOT,check=True)
                freeze(target,dest.with_suffix('.net').read_bytes());freeze(evidence,dest.with_suffix('.json').read_bytes())
        arms[name]=target;champions.install(name,target,'Three-input conditional midtrade response '+str(exchange),name,'adaptive')
    ranks=evaluate(out/'development.jsonl.gz',arms,foes,calendars('development_selection_clocks'))
    winner=ranks[0]['name'];student=next(r['name'] for r in ranks if r['name'].startswith('v3-exchange-'))
    publish('minemen-v3-trades',winner,arms);dump(out/'results.json',dict(winner=winner,student=student,ranks=ranks))
    print(winner,student,flush=True)

if __name__=='__main__':main()
