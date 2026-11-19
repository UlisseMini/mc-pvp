"""Archive the executed tournament schedule, training populations and per-bout comparisons."""
import argparse
import gzip
import json
import shutil
from pathlib import Path
from run import HERE

def compressed(path,value):
    with path.open('xb') as f:f.write(gzip.compress(json.dumps(value,separators=(',',':')).encode(),mtime=0))

def archive(top,common,out):
    out.mkdir(parents=True,exist_ok=False)
    seasons=[]
    for p in sorted(top.glob('season-*')):
        r=json.loads((p/'results.json').read_text())
        field=[l.split()[:3] for l in (p/'field.txt').read_text().splitlines()]
        stage=out/p.name;stage.mkdir()
        r['opponent_sampling_weights']=field
        compressed(stage/'results.json.gz',r)
        shutil.copyfile(p/'tournament.jsonl.gz',stage/'tournament.jsonl.gz')
        compressed(stage/'training.json.gz',[json.loads(l) for l in (p/'checkpoints/training.jsonl').open()])
        for track in ('rule','table','search','neural'):shutil.copyfile(p/(track+'.net'),stage/(track+'.net'))
        seasons.append({k:r[k] for k in ('season','family','selected','training_bouts','tournament_bouts','feedback_rows')})
    if len(seasons)!=12:raise ValueError('Expected twelve completed mixed tournaments')
    shutil.copyfile(common/'finalists.jsonl.gz',out/'common-tournament.jsonl.gz')
    compressed(out/'common-results.json.gz',json.loads((common/'finalists.json').read_text()))
    (out/'seasons.json').write_text(json.dumps(seasons,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('top',type=Path);p.add_argument('common',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();archive(a.top,a.common,a.out)
