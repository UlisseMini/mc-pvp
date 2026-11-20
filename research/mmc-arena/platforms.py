"""Recover distinct recorded MMC fighting surfaces and reserve disjoint geometry groups for transfer."""
import base64
import collections
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import tempfile
from measure import AIRLIKE,REMOTE,TERRAIN,packet,parts,positions,props

HERE=Path(__file__).resolve().parent
OUT=HERE/'evidence/platforms'
DEFAULT='fb302faee0f03c27bcaac437a64752fa'
VALIDATION={129,155,177,221}
SEALED={165,193,201,223}
# The envelope contains the jump apex plus body height and the blocks immediately under the playable surface.
LEVELS=range(-2,5)
def record(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        old=path.read_bytes()
        if old!=data and not (path.suffix=='.gz' and gzip.decompress(old)==gzip.decompress(data)):
            raise ValueError('Frozen file changed: '+str(path))
        return
    with tempfile.NamedTemporaryFile(dir=path.parent,delete=False) as f:f.write(data)
    os.replace(f.name,path)
def save(path,value):record(path,(json.dumps(value,sort_keys=True,indent=2)+'\n').encode())
def digest(data):return hashlib.sha256(data).hexdigest()

def normalized_key(d):
    x,z,ox,oz=d['spawns'];cx,cz=d['map_center']
    return tuple(d['mask']),tuple(sorted(((x-cx,z-cz),(ox-cx,oz-cz)))),tuple(sorted((int(k),v) for k,v in d['blocks'].items()))

def register():
    records=[json.loads(l) for l in gzip.open(HERE/'evidence/measurements.jsonl.gz','rt')]
    groups=collections.defaultdict(list)
    for r in records:
        if 'mask' in r:groups[normalized_key(r)].append(r)
    areas=collections.defaultdict(list)
    for k,g in groups.items():areas[g[0]['cells']].append((k,g))
    entries=[]
    for area,variants in sorted(areas.items()):
        # A full-cube capture is the geometry representative whenever one was recorded.
        def order(v):
            _,g=v;ids={int(n)>>4 for n in g[0]['blocks']}
            partial=bool(ids&{44,53,67,108,109,128,134,135,156,163})
            return (not any(q['clip']==DEFAULT for q in g),partial,-len(g),tuple(sorted(ids)))
        variants.sort(key=order)
        for j,(k,g) in enumerate(variants):
            clips=sorted(g,key=lambda r:(r['clip']!=DEFAULT,r['last_segment']-r['first_segment'],r['clip']))
            exemplar=clips[0]
            check=next((r for r in clips[1:] if r['session']!=exemplar['session']),clips[1] if len(clips)>1 else None)
            name=f'mmc-{area}'+(f'-{j+1:02}' if j else '')
            entries.append(dict(name=name,area=area,shape_group=area,split='validation' if area in VALIDATION else 'sealed' if area in SEALED else 'training geometry',
                                representative=j==0,exemplar=exemplar['clip'],crosscheck=check['clip'] if check else None,
                                rounds=len(g),sessions=len({d['session'] for d in g}),gap=exemplar['gap']))
    protocol=dict(source='measured MMC protocol-47 countdowns in the frozen 430-bookmark survey',entries=entries,
                  validation=sorted(VALIDATION),sealed=sorted(SEALED),training='137 blocks / eight-block opening; every palette sharing this outline is a single geometry group',
                  scope='local fighting volumes, original X/Z directions, material states and exact spawn cells; translated to lab altitude. Districts with the same area grouped after checking masks.',
                  selection='geometries assigned before evaluating any policy on them; final sealed geometry used once per frozen confirmatory campaign; continue training on default only',
                  missing=[r['clip'] for r in records if 'mask' not in r])
    assert len({tuple(d['mask']) for d in records if 'mask' in d})==len(areas)
    save(OUT/'split.json',protocol)
    return entries,{r['clip']:r for r in records}

def capture(d):
    r=props(REMOTE/'sumo-replays'/(d['clip']+'.properties'))
    pair,top,(sample,last)=positions(REMOTE,r)
    if list(pair)!=d['spawns'] or sample!=d['snapshot_ms'] or top-1!=d['floor_y']:raise ValueError('Countdown changed')
    with gzip.open(parts(REMOTE,r,0),'rb') as f:
        first=next(json.loads(line)['data'] for line in f if b'"capture_start"' in line and json.loads(line).get('type')=='capture_start')
    domain=first['server_host'].lower().split(':')[0].rstrip('.')
    if int(first['protocol'])!=47 or not any(domain==s or domain.endswith('.'+s) for s in ('minemen.club','mineman.club')):raise ValueError('Incorrect server or protocol')
    cx,cz=map(math.floor,d['map_center']);lo,lz,hi,hz=d['box'];lo-=1;lz-=1;hi+=1;hz+=1
    chunks={(x//16,z//16) for x in range(lo,hi+1) for z in range(lz,hz+1)}
    layers={v:{} for v in LEVELS}
    for i in range(d['first_segment'],last+1):
        with gzip.open(parts(REMOTE,r,i),'rb') as f:
            for line in f:
                if b'"packet_wire"' not in line or not any(name in line for name in TERRAIN):continue
                ev=json.loads(line)
                if ev['time_ms']>sample or ev['data']['packet']['direction']!='in':continue
                raw=base64.b64decode(ev['data']['base64'],validate=True)
                for dy,layer in layers.items():packet(raw,layer,top-1+dy,chunks)
    def state(y,x,z):
        return layers[y][x//16,z//16][16*(z%16)+x%16]
    grid=[[[state(dy,x,z) for x in range(lo,hi+1)] for z in range(lz,hz+1)] for dy in LEVELS]
    surface=grid[-LEVELS.start]
    mask=[''.join('.' if s>>4 in AIRLIKE else '#' for s in row[1:-1]) for row in surface[1:-1]]
    if mask!=d['mask']:raise ValueError('Recovered floor differs from earlier survey')
    spawns=sorted([[int(math.floor(x)-cx),int(math.floor(z)-cz)] for x,z in (pair[:2],pair[2:])])
    if any(state(0,cx+x,cz+z)!=41<<4 for x,z in spawns):raise ValueError('Countdown spawn is not gold')
    return dict(offset=[lo-cx,lz-cz],width=hi-lo+1,depth=hz-lz+1,levels=list(LEVELS),spawns=spawns,states=grid)

def run():
    entries,records=register();catalog=[]
    for entry in entries:
        p=OUT/(entry['name']+'.json.gz')
        if p.exists():saved=json.loads(gzip.decompress(p.read_bytes()))
        else:
            volume=capture(records[entry['exemplar']]);second=capture(records[entry['crosscheck']]) if entry['crosscheck'] else None
            def h(v):return digest(json.dumps(v,separators=(',',':')).encode())
            surface=volume['states'][2]
            saved=dict(**entry,volume=volume,volume_sha256=h(volume),surface_sha256=h(surface),
                       crosscheck_same_surface=second is not None and second['states'][2]==surface,
                       crosscheck_same_volume=second==volume,source_box=records[entry['exemplar']]['box'],
                       source_spawns=records[entry['exemplar']]['spawns'])
            record(p,gzip.compress(json.dumps(saved,separators=(',',':')).encode(),mtime=0))
        catalog.append({k:v for k,v in saved.items() if k!='volume'})
        print(entry['name'],entry['area'],saved['crosscheck_same_surface'],saved['crosscheck_same_volume'],flush=True)
    save(OUT/'catalog.json',catalog)
if __name__=='__main__':run()
