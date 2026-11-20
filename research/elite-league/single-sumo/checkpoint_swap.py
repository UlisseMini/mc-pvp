"""Factorial, common-random-number diagnosis of the 16k to 32k Seven improvement."""
from itertools import combinations
from math import comb
from pathlib import Path
import json
import os
import subprocess
import tempfile
import numpy as np
from scipy.stats import t
from build import sim_paths
from id_evaluate import make_panel
from learner import BUILD,CLOCKS,HERE,ROOT,write_json
from long_specialist import RUN,sources


def run():
    base=RUN/'seven'
    dest=base/'checkpoint-intervention'
    keys=['W/S and sprint','A/D','click permission','space']
    write_json(dest/'protocol.json',dict(controls=keys,method='All 16 assignments of controls to the 16k and 32k checkpoints on the same current own POV and actual prior applied buttons. Both policies use the same initial action RNG seed. Endpoints must reproduce every original winner and terminal time.',
               cases='512 fixed development games against Sumo Seven, regulation 7/7 CPS, original MMC 137 only; plain W-tap uses identical fixtures',
               contrast='Average marginal change in ring-out wins across all orders of replacing controls (Shapley decomposition). Approximate paired 95% intervals use the eight donor means. Descriptive development diagnosis.'),fixed=True)
    cells=make_panel(CLOCKS['dev'],8)
    cp=sim_paths(training=True)[0]
    _,_,_,foe=sources('seven')
    with tempfile.TemporaryDirectory(dir=BUILD) as td:
        folder=Path(td);plan=folder/'fixtures.tsv'
        plan.write_text(''.join('\t'.join(map(str,[clock,s,a,b,x,y]))+'\n' for _,_,s,a,b,clock,x,y in cells))
        subprocess.run(['javac','-proc:none','-cp',cp,'-d',folder,HERE/'CheckpointSwap.java'],check=True)
        out=subprocess.check_output(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx700m','-cp',
            os.pathsep.join(map(str,[folder,cp])),'CheckpointSwap',base/'n16384.net',base/'n32768.net',foe,plan],cwd=ROOT,text=True)
    scores=np.full((len(cells),16),np.nan);times=np.full_like(scores,np.nan)
    for line in out.splitlines():
        i,mask,s,end=line.split('\t');scores[int(i),int(mask)]=float(s);times[int(i),int(mask)]=np.float32(end)
    if not np.isfinite(scores).all():raise ValueError('Missing intervention')
    for mask,stage in [(0,'16384'),(15,'32768')]:
        rows=json.loads((base/'development'/stage/'target.json').read_text())['games']
        if not np.array_equal(scores[:,mask],[r[-2] for r in rows]) or not np.array_equal(times[:,mask],[r[-1] for r in rows]):
            raise ValueError('Intervention endpoint changed the original policy outcome or timing')
    win=(scores==1).astype(float);parts={}
    for i,key in enumerate(keys):
        contribution=np.zeros(len(cells))
        for size in range(4):
            for subset in combinations([j for j in range(4) if j!=i],size):
                m=sum(1<<j for j in subset)
                contribution+=(win[:,m|(1<<i)]-win[:,m])/(4*comb(3,size))
        by_donor=np.array([np.mean([v for v,c in zip(contribution,cells) if c[0]==j]) for j in range(8)])
        mean=float(by_donor.mean());half=float(t.ppf(.975,7)*by_donor.std(ddof=1)/np.sqrt(8))
        parts[key]=dict(win_change=mean,donors=by_donor.tolist(),interval=[mean-half,mean+half])
    if not np.isclose(sum(x['win_change'] for x in parts.values()),(win[:,15]-win[:,0]).mean()):raise ValueError('Win decomposition did not add up')
    wtap=json.loads((base/'development/wtap/target.json').read_text())['aggregate']
    counts={str(m):dict(wins=int(win[:,m].sum()),draws=int((scores[:,m]==.5).sum()),losses=int((scores[:,m]==0).sum())) for m in range(16)}
    write_json(dest/'results.json',dict(controls=keys,parts=parts,masks=counts,wtap=wtap,endpoint_fixtures_exact=len(cells)*2,
               scores=scores.tolist()),fixed=True)

if __name__=='__main__':run()
