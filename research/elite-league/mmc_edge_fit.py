"""Distill useful matched near-edge corrections into just the two existing MLP strafe readouts."""
from pathlib import Path
import gzip
import json
import subprocess
import tempfile

import champions
import focus
from adaptation import BLOCK, GATE, dump, freeze, sha
from history_cup import mirrors
from mechanism import encode, unpack
from mmc_click_bias import ranking,PARENT
from mmc_contact_gate import SPLIT as CONTACT
from mmc_league import EVIDENCE, FROZEN, WORK, contest, setup
from mmc_trade_fit import ACTOR, PREVIOUS, pool
from run import calendars, compile_model, java
from tournament import roster


STAGE='mmc13-edge-fit'
SPLIT=FROZEN/STAGE
BASE='mmc13-contact-16'
RADII=(3.5,5.)
PARAMETERS=[(w,t) for w in (1,4,16,64) for t in (.65,1.15,2.)]


def inputs(x,weights):
    result=[1.]
    for i in range(8):
        start=i*23;v=weights[start]
        for m,f in zip(weights[start+1:start+23],x,strict=True):v+=m*f
        result.append(v/(1+abs(v)))
    return result


def raw_score(x,w):return sum(a*b for a,b in zip(x,w,strict=True))


def solve(rows,anchor_weight,target):
    # Fit an affine change to the nine strafe parameters. The ridge on the change
    # retains the original predictor where none of the examples constrains it.
    n=9
    A=[[.001*int(i==j) for j in range(n)]+[0.] for i in range(n)]
    for hidden,desired,old,weight,anchor in rows:
        mass=weight*(anchor_weight if anchor else 1.)
        delta=0. if anchor else desired*target-old
        for i in range(n):
            v=mass*hidden[i]
            for j in range(n):A[i][j]+=v*hidden[j]
            A[i][n]+=v*delta
    for i in range(n):
        pivot=max(range(i,n),key=lambda j:abs(A[j][i]));A[i],A[pivot]=A[pivot],A[i]
        divisor=A[i][i]
        if abs(divisor)<1e-12:raise ValueError('Singular strafe fit')
        A[i]=[v/divisor for v in A[i]]
        for j in range(n):
            if j!=i:
                f=A[j][i];A[j]=[v-f*u for v,u in zip(A[j],A[i])]
    return [row[-1] for row in A]


def examples(field,foes,clocks):
    compile_model()
    for rad in RADII:
        dst=SPLIT/f'examples-{rad}.jsonl.gz'
        if dst.exists():continue
        with tempfile.TemporaryDirectory(dir=WORK) as d:
            d=Path(d);roster(d/'arms',{'wtap':None,BASE:field[BASE]});roster(d/'foes',foes)
            with (d/'rows').open('w') as out:subprocess.run(java('EdgeRecoveryStudy',d/'arms',d/'foes',rad,'--learn',*clocks),stdout=out,check=True)
            rows=list(map(json.loads,(d/'rows').read_text().splitlines()))
            keys=[(r['arm'],r['clock'],r['rtt'],r['side'],r['opponent']) for r in rows]
            if len(set(keys))!=len(rows) or len(rows)!=len(clocks)*len(foes)*8:raise ValueError('Incomplete training cells')
            freeze(dst,gzip.compress((d/'rows').read_bytes(),mtime=0))


def train():
    setup();SPLIT.mkdir(exist_ok=True)
    field=pool();field[PARENT]=FROZEN/'mmc13-trades'/(PARENT+'.net');field[BASE]=CONTACT/(BASE+'.net')
    clocks=calendars('training_clocks');development=calendars('development_selection_clocks')
    foes={n:field[n] for n in ('wtap',ACTOR,PREVIOUS,'continuation-02-fit')}
    dump(SPLIT/'protocol.json',dict(parent=sha(field[BASE]),source=sha(EVIDENCE/'edge-recovery/protocol.json'),
        training=[p.name for p in clocks[:12]],selection=[p.name for p in clocks[12:]],development=[p.name for p in development],
        foes=list(foes),landmarks=RADII,parameters=PARAMETERS,
        algorithm='At each eligible complete fork compare six centerward strafe heads against the original and primed plain W-tap. On the twelve fitting donors, imitate the six actual modified heads only when they strictly improve the final ring-out outcome. Retain original outputs on other eligible states and in a systematic sample of up to 64 heads per full match. Fit nine strafe readout weights per expert with regularized least squares; copy the same changes into both existing experts. Only the selected strafe key can change directly.',
        tournaments='Select against the four fitting opponents on the four other training donors. Use the standard W-tap win gate and robust mean/bottom-five/minimum ranking. Test one fitted contender, its parent, the previous trade winner, and W-tap across the broad development field. Confirm those four on eight fresh donor clocks and two native repeats per seat and RTT against W-tap, the previous hard actor and the new weakest distinct opponent.'))
    examples(field,foes,clocks)
    family,original=unpack(field[BASE].read_text());kind,actor=unpack(field[ACTOR].read_text())
    p=8+GATE;strafe=len(actor)-9
    if family!='PAIR' or kind!='MLP' or original[p:p+len(actor)]!=actor:raise ValueError('Unexpected base actor')
    train_ids={p.name.split('.')[0] for p in clocks[:12]};data=[];summary={}
    for radius in RADII:
        with gzip.open(SPLIT/f'examples-{radius}.jsonl.gz','rt') as f:rows=list(map(json.loads,f))
        gains=0
        for r in rows:
            if r['arm']!=BASE or r['clock'] not in train_ids:continue
            def add(batch,anchor,scale=1.):
                for sample in batch:
                    x=inputs(sample[:-1],actor);old=raw_score(x,actor[strafe:])
                    if anchor and int(old>.45)-int(old<-.45)!=int(sample[-1]):raise ValueError('Original strafe readout differs from the recorded input')
                    data.append((x,sample[-1],old,scale/max(1,len(batch)),anchor))
            if radius==RADII[0]:add(r['anchors'],True)
            if not r.get('branches'):continue
            improve=r['branches']['center-strafe-6'][0]>r['branches']['unchanged'][0]
            gains+=int(improve)
            add(r['examples']['center-strafe-6' if improve else 'unchanged'],not improve)
        summary[str(radius)]=gains
    dump(SPLIT/'examples.json',dict(positive_forks=summary,weighted_input_rows=len(data)))
    controls={n:field[n] for n in ('wtap',BASE,PARENT)};options={}
    for i,(anch,t) in enumerate(PARAMETERS):
        delta=solve(data,anch,t);w=original.copy()
        for off in (p,p+BLOCK):
            for j,d in enumerate(delta):w[off+strafe+j]+=d
        n=f'mmc13-edge-{i:02d}';options[n]=SPLIT/(n+'.net');freeze(options[n],(encode('PAIR',w)+'\n').encode())
    allarms=controls|options;sel=SPLIT/'selection-clocks.jsonl.gz'
    focus.play_model(sel,allarms,foes,clocks[12:]);ranks=ranking(sel,allarms,foes,4);win=ranks[0]['name']
    champions.install(STAGE+'-filter',focus.source(win,allarms[win]),f'Edge recovery selection winner ({win})',STAGE+'-filter')
    contender=next(r['name'] for r in ranks if r['name'] in options);champions.install(contender,options[contender],f'Edge strafe finalist ({contender})',STAGE,'adaptive')
    arms=controls|{contender:options[contender]}
    dev=SPLIT/'development.jsonl.gz';focus.play_model(dev,arms,field,development);ranking_dev=ranking(dev,arms,field,8);winner=ranking_dev[0]['name']
    champions.install(STAGE,focus.source(winner,arms[winner]),f'Edge recovery development winner ({winner})',STAGE)
    weak=next(o for o in next(r for r in ranking_dev if r['name']==contender)['weakest'] if focus.identity(o,field[o])!=sha(options[contender]))
    dump(SPLIT/'selection.json',dict(winner=winner,contender=contender,weakest_distinct=weak,filter=ranks,development=ranking_dev))
    foes={n:field[n] for n in dict.fromkeys(['wtap',PREVIOUS,weak])};reps={n:2 for n in foes}
    dump(SPLIT/'confirmation.json',dict(arms={n:focus.identity(n,p) for n,p in arms.items()},opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=reps))
    print(winner,contender,weak,flush=True)
    contest('edge-confirm',arms,foes,reps,mirrors('mmc13-edge-confirm',WORK))


if __name__=='__main__':train()
