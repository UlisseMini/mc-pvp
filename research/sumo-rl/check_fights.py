"""Audit the first available prospective fights in each named policy/opponent cell by numeric match ID."""
import argparse, json, subprocess, sys
from pathlib import Path
from run import ROOT,HERE

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('manifest',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--per-cell',type=int,default=2);args=p.parse_args()
    manifest=json.loads(args.manifest.read_text());groups={}
    for req,mid in sorted(manifest['matches'].items(),key=lambda kv:int(kv[1][1:])):
        row=manifest['plan'][req]
        if row['mode']!='sumo' or row['profile']!='mmc' or row['arm']=='research-hold20':continue
        groups.setdefault((row['opponent'],row['arm']),[]).append(mid)
    planned={(r['opponent'],r['arm']) for r in manifest['plan'].values()
             if r['profile']=='mmc' and r['mode']=='sumo' and r['arm']!='research-hold20'}
    if groups.keys()!=planned or any(len(v)<args.per_cell for v in groups.values()):
        p.error('Every declared opponent/arm cell needs the requested number of completed fights')
    mids=[mid for ids in groups.values() for mid in ids[:args.per_cell]]
    args.output.mkdir(parents=True,exist_ok=True)
    if len(set(mids))!=len(mids):raise ValueError("Duplicate match selected")
    paired=[]
    for mid in mids:
        recorded=json.loads((ROOT/f'runtime/results/{mid}.json').read_text())
        if all(recorded['parameters_'+s].get('science_trace')=='1' for s in 'ab'):paired.append(mid)
    (args.output/'availability.json').write_text(json.dumps({'selected':mids,'two_detailed_povs':paired,
        'without_both_detailed_povs':[mid for mid in mids if mid not in paired]},indent=2))
    for name,cmd in [
            ('travel',[HERE/'validate.py',args.output/'travel.json',*mids]),
            ('strict',[ROOT/'research/periodic-clock-replay.py',*paired,'--frames','--transport-trace','--seconds','8','--output',args.output/'strict.json'])]:
        if name=='strict' and not paired:continue
        if (args.output/(name+'.json')).exists():continue
        with (args.output/(name+'.log')).open('w') as f:
            subprocess.run([sys.executable,*map(str,cmd)],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,check=True)
