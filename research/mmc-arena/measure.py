"""Read the floor under two MMC Sumo countdown spawns directly from captured 1.8 block-state packets."""
import argparse
import base64
from collections import Counter,deque
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct

REMOTE=Path.home()/'Library/Application Support/minecraft/config/pvp-practice'
AIRLIKE={0,6,8,9,10,11,27,28,30,31,32,37,38,39,40,50,51,55,59,63,65,66,68,69,70,72,75,76,77,78,83,104,105,106,111,115,127,131,132,140,141,142,143,147,148,149,150,157,171,175}
TERRAIN=(b'S21PacketChunkData',b'S26PacketMapChunkBulk',b'S22PacketMultiBlockChange',b'S23PacketBlockChange',b'S07PacketRespawn')
def var(data,at):
    out=0
    for i in range(5):
        b=data[at+i];out|=(b&127)<<(7*i)
        if b<128:return out,at+i+1
    raise ValueError('Overlong Minecraft VarInt')
def parts(base,record,i):
    name=record['capture']
    if Path(name).name!=name or not name.endswith('-events.jsonl.gz'):raise ValueError('Unexpected capture name')
    return base/'captures'/(name if not i else name.replace('-events.jsonl',f'-events-part{i}.jsonl'))
def props(path):
    return dict(line.split('=',1) for line in path.read_text().splitlines() if line and line[0]!='#' and '=' in line)
def positions(base,r):
    start=int(r['start']);i=int(r['part']);poses=[]
    # The mod's bookmark starts a few seconds before the countdown teleport.
    for j in range(max(0,i-1),i+9):
        p=parts(base,r,j)
        if not p.is_file():continue
        with gzip.open(p,'rb') as stream:
            for line in stream:
                if b'"tick_end"' not in line:continue
                e=json.loads(line);t=e.get('time_ms',0)
                if t<start:continue
                if t>min(start+4000,int(r['finish'])):break
                d=e['data'];me=d.get('self') or {};other=next((a for a in d.get('entities',[]) if a.get('name','').casefold()==r['opponent'].casefold()),{})
                if not me.get('ground') or not other.get('pos') or not me.get('pos'):continue
                a=me['pos'];b=other['pos']
                if abs(a[1]-round(a[1]))>.001 or abs(a[1]-b[1])>1.5:continue
                if not 3<math.dist(a[::2],b[::2])<19:continue
                if any(abs((v-.5)-round(v-.5))>.04 for v in (a[0],a[2],b[0],b[2])):continue
                poses.append((tuple(round(v*2)/2 for v in (a[0],a[2],b[0],b[2])),round(a[1]),t,j))
    if not poses:raise ValueError('No paired stationary countdown spawns')
    (pair,top),howmany=Counter((c[0],c[1]) for c in poses).most_common(1)[0]
    if howmany<2:raise ValueError('Opposing spawn observed only once')
    snapshot=next((t,j) for q,y,t,j in poses if (q,y)==(pair,top))
    return pair,top,snapshot

def packet(data,terrain,y,relevant):
    pid,at=var(data,0);section=y//16
    def chunk(x,z,mask,full,offset):
        key=(x,z)
        if key not in relevant:return
        if mask&(1<<section):
            n=(mask&((1<<section)-1)).bit_count()*8192+(y%16)*512
            terrain[key]=list(struct.unpack_from('<256H',data,offset+n))
        elif full:terrain[key]=[0]*256
    if pid==0x26:
        sky=data[at]!=0;n,at=var(data,at+1)
        hdr=[struct.unpack_from('>iiH',data,at+10*i) for i in range(n)];at+=10*n
        for x,z,mask in hdr:
            chunk(x,z,mask,True,at);at+=mask.bit_count()*(10240+2048*sky)+256
        if at!=len(data):raise ValueError('Bulk chunk data size differs')
    elif pid==0x21:
        x,z,full,mask=struct.unpack_from('>ii?H',data,at);size,at=var(data,at+11)
        if at+size!=len(data):raise ValueError('Chunk size differs')
        if not mask and full:terrain.pop((x,z),None)
        else:chunk(x,z,mask,full,at)
    elif pid==0x23:
        packed=struct.unpack_from('>Q',data,at)[0];x=packed>>38;z=packed&0x3ffffff;yy=(packed>>26)&0xfff
        if x>=1<<25:x-=1<<26
        if z>=1<<25:z-=1<<26
        val,at=var(data,at+8)
        if at!=len(data):raise ValueError('Unexpected block-change packet')
        if yy==y and (x//16,z//16) in terrain:terrain[x//16,z//16][(z%16)*16+x%16]=val
    elif pid==0x22:
        x,z=struct.unpack_from('>ii',data,at);n,at=var(data,at+8)
        for _ in range(n):
            loc=struct.unpack_from('>H',data,at)[0];val,at=var(data,at+2)
            if loc%256==y and (x,z) in terrain:terrain[x,z][(loc>>8&15)*16+(loc>>12)]=val
        if at!=len(data):raise ValueError('Unexpected multi-block packet')
    elif pid==7:terrain.clear()
    else:raise ValueError('Packet type and wire ID differ')

def measure(base,bookmark,with_plan=False):
    r=props(bookmark);pair,top,(sample,last)=positions(base,r)
    with gzip.open(parts(base,r,0),'rb') as f:
        first=next((json.loads(line)['data'] for line in f if b'"capture_start"' in line and json.loads(line).get('type')=='capture_start'),None)
    if first is None:raise ValueError('Missing capture-start server identity')
    server=first['server_host'].lower().partition(':')[0].rstrip('.')
    if not any(server==x or server.endswith('.'+x) for x in ('minemen.club','mineman.club')):raise ValueError('Recording has a different server')
    if int(first['protocol'])!=47:raise ValueError('Expected Minecraft 1.8 wire format')
    cx,cz=(pair[0]+pair[2])/2,(pair[1]+pair[3])/2
    relevant={(x,z) for x in range(math.floor((cx-24)/16),math.floor((cx+24)/16)+1)
                   for z in range(math.floor((cz-24)/16),math.floor((cz+24)/16)+1)}
    origin=int(r['part']);terrain={}
    def block(x,z):
        chunk=terrain.get((x//16,z//16));return None if chunk is None else chunk[(z%16)*16+x%16]
    def connected():
        start=(math.floor(pair[0]),math.floor(pair[1]));seen=set();queue=deque([start]);border=set()
        while queue:
            x,z=queue.popleft()
            if (x,z) in seen or (x,z) in border:continue
            val=block(x,z)
            if val is None:return None
            if val>>4 in AIRLIKE:border.add((x,z));continue
            if abs(x+.5-cx)>23 or abs(z+.5-cz)>23:raise ValueError('Floor continues outside the local sumo area')
            seen.add((x,z));queue.extend(((x-1,z),(x+1,z),(x,z-1),(x,z+1)))
        if (math.floor(pair[2]),math.floor(pair[3])) not in seen:return None
        return seen,border
    # Typical fresh arenas arrive at countdown. For a rematch, replay the earlier chunk updates in their original order.
    for span in (5,24,100,last+1):
        terrain.clear()
        source_start=max(0,min(origin,last)-span)
        for j in range(source_start,last+1):
            path=parts(base,r,j)
            if not path.is_file():
                terrain.clear();source_start=j+1;continue
            with gzip.open(path,'rb') as stream:
                for line in stream:
                    if b'"packet_wire"' not in line or not any(k in line for k in TERRAIN):continue
                    e=json.loads(line)
                    if e['time_ms']>sample:continue
                    wire=e['data']
                    if wire['packet']['direction']=='in':packet(base64.b64decode(wire['base64'],validate=True),terrain,top-1,relevant)
        found=connected()
        if found:break
        if source_start==0:raise ValueError('No continuous loaded terrain under both spawns')
    if found is None:raise ValueError("Insufficient continuous terrain coverage")
    seen,border=found
    lo=min(x for x,z in seen);hi=max(x for x,z in seen);low=min(z for x,z in seen);high=max(z for x,z in seen)
    mask=[''.join('#' if (x,z) in seen else '.' for x in range(lo,hi+1)) for z in range(low,high+1)]
    result=dict(clip=bookmark.stem,session=hashlib.sha256(r['capture'].encode()).hexdigest()[:16],start=int(r['start']),spawns=pair,
                floor_y=top-1,snapshot_ms=sample,first_segment=source_start,last_segment=last,box=[lo,low,hi,high],
                gap=math.dist(pair[:2],pair[2:]),cells=len(seen),mask=mask,blocks=dict(Counter(block(*pos) for pos in seen)),
                perimeter=dict(Counter(block(*pos) for pos in border)),map_center=[(lo+hi+1)/2,(low+high+1)/2])
    if with_plan:
        levels={dy:{} for dy in (1,2)}
        for j in range(source_start,last+1):
            with gzip.open(parts(base,r,j),'rb') as stream:
                for line in stream:
                    if b'"packet_wire"' not in line or not any(k in line for k in TERRAIN):continue
                    e=json.loads(line)
                    if e['time_ms']>sample or e['data']['packet']['direction']!='in':continue
                    raw=base64.b64decode(e['data']['base64'],validate=True)
                    for dy,level in levels.items():packet(raw,level,top-1+dy,relevant)
        result['surface_states']=[[block(x,z) for x in range(lo,hi+1)] for z in range(low,high+1)]
        result['overhead_states']={dy:dict(Counter(level[x//16,z//16][16*(z%16)+x%16] for x,z in seen)) for dy,level in levels.items()}
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('bookmark',type=Path,nargs='+');p.add_argument('--base',type=Path,default=REMOTE);p.add_argument('--plan',action='store_true')
    a=p.parse_args()
    for f in a.bookmark:
        d=measure(a.base,f,a.plan);print(json.dumps(d,separators=(',',':')),flush=True)
