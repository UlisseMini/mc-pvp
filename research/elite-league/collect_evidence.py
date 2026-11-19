"""Keep a self-contained copy of the prospective calendars, model results and matched score tables."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
from comparison import records,summarize

PANELS={
 'challenge':('br-clocks','br-validation'),
 'boxing':('br-clocks','boxing-validation'),
 'robust':('robust-clocks','robust-validation'),
 'ladder':('ladder-clocks','ladder-validation'),
 'confirmation':('confirmation-clocks','confirmation-validation'),
 'boxing100':('ladder-clocks','boxing100-validation'),
 'human-informed':('ladder-clocks','human-validation'),
 'router':('ladder-clocks','router-validation'),
 'arena':('ladder-clocks','arena-validation'),
 'boxing-distilled':('box-student-clocks','box-student-validation'),
 'novel':('novel-clocks','novel-validation'),
 'click-gate':('robust-clocks','click-gate-validation'),
 'empirical-game':('novel-clocks','empirical-validation'),
 'final-challenge':('final-clocks','final-validation')}

def compact(d):return gzip.compress(json.dumps(d,separators=(',',':')).encode(),mtime=0)
def put(p,b):
    if p.exists() and p.read_bytes()!=b:raise ValueError('Would replace different evidence '+str(p))
    if not p.exists():p.write_bytes(b)

def archive(tag,work,evidence):
    clockdir,model=PANELS[tag];source=work/model;d=evidence/tag;d.mkdir(exist_ok=True,parents=True)
    mirrors=work/(clockdir+'.json')
    meta=json.loads(mirrors.read_text())
    if meta['plan'].keys()!=meta['matches'].keys() or len(meta['matches'])!=8:raise ValueError('Eight complete fresh mirrors required')
    clocks=d/'clocks';clocks.mkdir(exist_ok=True)
    expected=set(meta['matches'].values());found=set()
    files={d/'native.json.gz',d/'native-paired.json.gz',clocks/'donors.json.gz'}
    for p in sorted((work/clockdir).glob('*.txt.gz')):
        found.add(p.name.split('.')[0]);put(clocks/p.name,p.read_bytes());files.add(clocks/p.name)
    if found!=expected:raise ValueError('Tape IDs differ from the frozen mirror manifest')
    put(clocks/'donors.json.gz',compact(meta))
    n=d/'native.json.gz';put(d/'native-paired.json.gz',compact(summarize(records(n))))
    for p in sorted(source.glob('*.jsonl.gz')):
        put(d/p.name,p.read_bytes());files.add(d/p.name)
        if p.name=='prospective.jsonl.gz' and tag=='router':continue
        if p.name.startswith('disk-'):
            disk=int(p.name.split('.')[0].split('-')[1]);rows=[dict(r,diameter=disk) for r in records(p)]
        else:rows=records(p)
        if rows and rows[0].get('mode','sumo')=='sumo' and 'arm' in rows[0]:
            paired=d/(p.name.split('.')[0]+'-paired.json.gz')
            put(paired,compact(summarize(rows)));files.add(paired)
    hashes={str(p.relative_to(d)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}
    put(d/'collected.json',(json.dumps(hashes,indent=2)+'\n').encode())

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('tag',choices=PANELS);p.add_argument('work',type=Path);p.add_argument('evidence',type=Path)
    a=p.parse_args();archive(a.tag,a.work,a.evidence)
