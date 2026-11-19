"""Three unseen, hand-programmed timing controllers and an independently trained targeted adversary."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from ladder_transfer import WT,HOLD,REFERENCE,models,plan,native_install
from mechanism import encode
from native_trials import run_batch
from run import HERE,ROOT,compile_model,java,calendars
from tournament import grid,read

STYLES=('probe-hit-select','probe-edge-pivot','probe-two-beat')

def explicit(style):
    # Hidden rows are signed soft thresholds on local observations, then optional recurrent inputs.
    # Movement is positive W, below zero coast, below -.65 back. Strafe uses +/-.45.
    # Output order is movement, Ctrl, click, jump, strafe.
    hidden=[
        (-4.8,{10:8},{}),                  # recent incoming cue, <120 ms
        (-5.3,{11:8},{}),                  # recent outgoing cue, <101 ms
        (-2.4,{11:-8},{}),                 # nearing fresh damage, >390 ms or no cue
        (-.5,{2:-7},{}),                   # recoiling away
        (-1.8,{0:-8},{}),                  # within about 2.55 center blocks
        (-2.4,{14:6},{}),                  # away from center, radius >3.5
        (0,{13:8}, {6:.7}),                # toward center on the local left/right axis
        (.05,{10:.3,11:-.3},{7:-3.5})]    # flip tempo each input head, synchronized by the trade
    if style=='probe-hit-select':
        out=[(.65,{1:-1.35,3:.65}),(.1,{1:-1,2:.4,3:1}),(-.55,{0:1.6,3:.9,4:1.2,1:.75}),
             (-.7,{0:1.2,4:.4}),(.05,{6:.2})]
    elif style=='probe-edge-pivot':
        out=[(.45,{4:-.9,3:.8,5:.4}),(.2,{1:-1.1,3:.55}),(1.1,{3:1,2:.4}),(-1,{}),(0,{6:1.2,7:.25})]
    elif style=='probe-two-beat':
        out=[(.55,{1:-1.25,7:.43}),(.12,{7:.9,1:-.5}),(.1,{7:1.25,0:.75,3:.65,2:.5}),
             (-.85,{0:1,4:.35}),(.05,{7:.95,6:.45})]
    else:raise KeyError(style)
    w=[]
    for bias,x,h in hidden:w.extend([bias,*[x.get(i,0) for i in range(22)],*[h.get(i,0) for i in range(8)]])
    for bias,h in out:w.extend([bias,*[h.get(i,0) for i in range(8)]])
    return encode('RECURRENT',w)+'\n'

def freeze_explicit(out):
    out.mkdir(exist_ok=False,parents=True)
    for n in STYLES:(out/(n+'.net')).write_text(explicit(n))

def target(frozen):
    d,paths=models(frozen);winner=json.loads((frozen/'native-choice.json').read_text())['winner']
    return winner,paths.get(winner)
def fit(frozen,out):
    out.mkdir(exist_ok=False,parents=True);winner,path=target(frozen)
    target_arg=path if path else 'wtap' if winner==WT else 'hold'
    for fam,seed,warm in [('MLP',881057,HERE/'robust-frozen/population.net'),('RECURRENT',410119,HERE/'challenge-frozen/anti-hybrid.net')]:
        subprocess.run(java('BestResponse',out/fam.lower(),fam,seed,192,56,16,3,target_arg,warm,*calendars('training_clocks')),check=True)
    pool={p.parent.name+'-'+p.stem:p for p in sorted(out.glob('*/*.net'))}
    field={'wtap':None,'hold':None}
    key='wtap' if winner==WT else 'hold' if winner==HOLD else 'chosen'
    if path is not None:field[key]=path
    assert len(pool)==50
    rows=read(grid(out,'selection',field|pool,field,calendars('development_selection_clocks')))
    scores={n:{o:sum(r['score'] for r in rows if r['arm']==n and r['opponent']==o) for o in field} for n in field|pool}
    chosen=min(pool,key=lambda n:(-scores[n][key],-scores[n]['wtap']-scores[n]['hold'],n))
    dest=HERE/'novel-frozen/probe-targeted.net';shutil.copyfile(pool[chosen],dest)
    spec=dict(target=winner,challenger=chosen,sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),points_per_32=scores)
    with (dest.parent/'targeted-selection.json').open('x') as f:json.dump(spec,f,indent=2)

def study(frozen,out,clocks):
    winner,path=target(frozen);_,files=models(frozen)
    new={n:HERE/'novel-frozen'/(n+'.net') for n in (*STYLES,'probe-targeted')}
    if hashlib.sha256(new['probe-targeted'].read_bytes()).hexdigest()!=json.loads((HERE/'novel-frozen/targeted-selection.json').read_text())['sha256']:raise ValueError('Targeted opponent differs')
    arms=list(dict.fromkeys([WT,HOLD,REFERENCE,winner]))
    if clocks:
        out.mkdir(exist_ok=False,parents=True)
        aliases={WT:'wtap',HOLD:'hold'};roster={aliases.get(n,n):files.get(n) for n in arms}
        grid(out,'novel',roster,new,sorted(clocks.glob('*.txt.gz')),mode='mouse')
    else:
        native_install(new);run_batch(plan('enov',arms,list(new),4),out)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=('explicit','fit','study'));p.add_argument('out',type=Path);p.add_argument('--clocks',type=Path)
    a=p.parse_args();f=HERE/'ladder-frozen'
    if a.mode=='explicit':freeze_explicit(a.out)
    elif a.mode=='fit':compile_model();fit(f,a.out)
    else:
        if a.clocks:compile_model()
        study(f,a.out,a.clocks)
