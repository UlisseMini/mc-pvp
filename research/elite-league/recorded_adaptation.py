"""Can fast and slow memory of a changing opponent beat fixed champion policies on the measured range law?"""
import json
from pathlib import Path
import subprocess
import tempfile

from adaptation import dump,freeze,pair_model
from recorded_mmc_league import DEST,TEMP,evaluate,publish
from recorded_range_responses import OUT,prepare
from run import ROOT,calendars,compile_model,java
from tournament import roster
import champions
import focus

D=DEST/'range-adaptation'
def read(path):return json.loads(path.read_text())
def main():
    D.mkdir(exist_ok=True);pool,students,incumbent=prepare();common=read(OUT/'common/results.json')
    for t in ('counter','reply'):
        n=common[t];pool[n]=OUT/t/(n+'.net')
    reply,counter=common['reply'],common['counter']
    tracks=[(reply,incumbent),(reply,counter),('v3-feed-39',incumbent),(counter,'v3-memory-27')]
    # The withheld switch pairs differ in identities AND order from every training example.
    changes={'fit':[(reply,'v3-feed-39'),(incumbent,'v3-memory-27'),(counter,students[-1]),(students[-1],reply)],
             'transfer':[('v3-feed-39',counter),('v3-memory-27',reply),(incumbent,students[-1]),(reply,incumbent)]}
    trigger=[(1,2,0),(2,3,9),(0,16,0),(1,5,12)]
    assert not set(changes['fit'])&set(changes['transfer'])
    d=D/'switches';d.mkdir(exist_ok=True);styles={}
    for split,rows in changes.items():
        styles[split]=[]
        for i,(a,b) in enumerate(rows):
            n=('shift-train-v4-' if split=='fit' else 'shift-check-v4-')+str(i+1);styles[split].append(n)
            pool[n]=d/(n+'.net');freeze(pool[n],pair_model(pool[a],pool[b],1,*trigger[i],0))
            champions.install(n,pool[n],'Range-preset changing opponent '+a+' then '+b,n,'sparring')
    fixed=list(dict.fromkeys(['wtap','hold',reply,counter,incumbent,'v3-feed-39','v3-memory-27','legacy-trade-wtap-late',*students]))
    dump(D/'protocol.json',dict(expert_pairs=tracks,opponent_changes=changes,trigger_head_delay_repeat=trigger,training=styles['fit']+fixed,
        prospective_switches=styles['transfer'],training_clocks=[p.name for p in calendars('training_clocks')],development_clocks=[p.name for p in calendars('development_selection_clocks')],
        method='Freeze four new opponent switch-pair identities before search. Fit a 37-weight switch gate plus minimum dwell and hysteresis, or a recurrent direct-control network, against the same perturbations and strong changing opponents. Both experts see every observation and the physically applied last action. The selector sees only its own 22 features plus exponential averages. Rank fixed incumbents alongside all graduates. Report novel switch styles separately; development clock IDs repeat.'))
    tmp=TEMP/'range-adaptation';tmp.mkdir(exist_ok=True);saved=tmp/'checkpoints'
    for i,(a,b) in enumerate(tracks):
        n='gate-start-'+str(i);pool[n]=d/(n+'.net');freeze(pool[n],pair_model(pool[a],pool[b],2,0,0,0,9911+i))
    warm={n:pool[n] for n in list(pool) if n.startswith('gate-start-') or n=='v3-memory-27'}
    train={n:pool[n] for n in styles['fit']+fixed};compile_model()
    if not saved.exists():
        with tempfile.TemporaryDirectory(dir=tmp) as sub:
            t=Path(sub);roster(t/'train',train);roster(t/'warm',warm)
            subprocess.run(java('AdaptationFit',t/'saved',981134,t/'train',t/'warm','--mmc-range',*calendars('training_clocks')),check=True)
            (t/'saved').rename(saved)
    freeze(D/'training.jsonl',(saved/'training.jsonl').read_bytes())
    arms={n:pool[n] for n in ('wtap',reply,incumbent,counter,'v3-memory-27')}
    for f in sorted(saved.glob('*.net')):
        name='v4-adapt-'+f.stem;arms[name]=D/(name+'.net');freeze(arms[name],f.read_bytes())
    dev=calendars('development_selection_clocks')
    ranks=evaluate(D/'development.jsonl.gz',arms,train,dev,'mmc-range')
    selected=[next(r['name'] for r in ranks if r['name'].startswith('v4-adapt-'+family)) for family in ('pair','recurrent')]
    for n in selected:champions.install(n,arms[n],'Changing-opponent range candidate',n,'adaptive')
    publish('minemen-v4-adapt-fit',ranks[0]['name'],arms)
    for n in selected:publish('minemen-v4-'+('gate' if '-pair-' in n else 'recurrent')+'-fit',n,arms)
    finalists={n:arms[n] for n in ('wtap',reply,incumbent,counter,'v3-memory-27',*selected)}
    refs=list(dict.fromkeys(['wtap',incumbent,counter,reply,*styles['transfer']]))
    dump(D/'sealed-lineup.json',dict(finalists={n:focus.identity(n,p) for n,p in finalists.items()},opponents=refs))
    transfer=evaluate(D/'novel-switches.jsonl.gz',finalists,{n:pool[n] for n in refs},dev,'mmc-range')
    win=transfer[0]['name'];publish('minemen-v4-adaptation-cup',win,finalists)
    dump(D/'results.json',dict(winner=win,development=ranks,novel=transfer,pair=selected[0],recurrent=selected[1]))
    print('Changing-opponent winner',win,'new candidates',selected,flush=True)
if __name__=='__main__':main()
