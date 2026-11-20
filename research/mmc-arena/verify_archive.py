"""Check every measured bookmark for alternate block arrangements hidden by equal material histograms."""
import gzip
import json
import hashlib
from pathlib import Path
from build_maps import BUILD
from platforms import OUT,REMOTE,capture,normalized_key,record,register,save

def run():
    entries,records=register()
    names={normalized_key(records[e['exemplar']]):e['name'] for e in entries}
    cache=BUILD/'volume-survey';cache.mkdir(exist_ok=True,parents=True)
    known={e['name']:json.loads(gzip.decompress((OUT/(e['name']+'.json.gz')).read_bytes()))['volume_sha256'] for e in entries}
    results=[]
    for clip,r in sorted(records.items()):
        if 'mask' not in r:continue
        p=cache/(clip+'.json');name=names[normalized_key(r)]
        if not p.exists():
            v=capture(r)
            h=hashlib.sha256(json.dumps(v,separators=(',',':')).encode()).hexdigest()
            save(p,dict(clip=clip,registered=name,volume_sha256=h,matches=h==known[name]))
            if h!=known[name]:record(cache/(h+'.json.gz'),gzip.compress(json.dumps(v,separators=(',',':')).encode(),mtime=0))
        results.append(json.loads(p.read_text()))
    data=dict(measured=len(results),matches=sum(r['matches'] for r in results),unique_volumes=len(set(r['volume_sha256'] for r in results)),
        source_bookmarks=len(records),unmeasured=[r['clip'] for r in records.values() if 'mask' not in r],
        additional_bookmarks=sorted({f.stem for f in (REMOTE/'sumo-replays').glob('*.properties')}-set(records)),results=results)
    record(OUT/'all-volumes.json.gz',gzip.compress(json.dumps(data,separators=(',',':')).encode(),mtime=0))
    # A placed falling block schedules a later world update. Check its real substrate.
    gravity={}
    for entry in entries:
        name=entry['name']
        v=json.load(gzip.open(OUT/(name+'.json.gz')))['volume']
        x0,z0=v['offset']
        cells=[(x,z) for z,row in enumerate(v['states'][0]) for x,state in enumerate(row) if state>>4 in (12,13,145)]
        if not cells:continue
        clips=[r['clip'] for r in results if r['registered']==name]
        support=None
        for clip in clips:
            layer=capture(records[clip],range(-3,5))['states'][0]
            observed=[[x+x0,z+z0,layer[z][x]] for x,z in cells]
            if support is None:support=observed
            if observed!=support:raise ValueError('Substrate differs in '+clip)
        gravity[name]=dict(source_clips=clips,support_y=-3,x_z_state=support)
    save(OUT/'falling-block-supports.json',gravity)
    print({k:v for k,v in data.items() if k!='results'},flush=True)
if __name__=='__main__':run()
