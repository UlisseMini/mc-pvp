"""Export exogenous host opportunities. No hit, position, outcome or packet payload enters a tape."""
import argparse
import gzip
import json
from pathlib import Path
import tempfile
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CLIENT = {'native_input_head':'TICK_HEAD', 'native_attack_loop':'CONSUME',
          'science_move':'MOVE', 'science_interpolation':'INTERP', 'tick_end':'FLUSH',
          'replication_frame':'FRAME_ENTRY'}
SERVER = {'drain_start':'SERVER_DRAIN','entity_tick_start':'SERVER_ENTITY',
          'tracker_tick_start':'SERVER_TRACK','tick_end':'SERVER_END'}

def server_stage(e):
    if e['type']!='replication_server_clock':return None
    kind=SERVER.get(e['phase'])
    if kind in ('SERVER_ENTITY','SERVER_TRACK') and e['world_ordinal']!=0:return None
    return kind

def tracker_first(mid,game):
    # EntityTracker iterates its hash set; the player with the lower slot may tick second.
    with (ROOT/'runtime/replays'/f'{mid}.jsonl').open() as stream:
        for line in stream:
            e=json.loads(line)
            if e['type']=='replication_tracker_before' and e['tracker']['player'] in (game['a'],game['b']):
                return 'a' if e['tracker']['player']==game['a'] else 'b'
    raise ValueError(f'No tracker order for {mid}')

def timeline(mid, side, game):
    path = (ROOT/'runtime/replays'/f'{mid}.jsonl' if side=='s' else
            next((ROOT/'runtime/pov'/mid).glob(f'{side}-client-*-events.jsonl.gz')))
    reader = gzip.open if path.suffix == '.gz' else open
    result = [];footer=None
    with reader(path,'rt') as stream:
        for line in stream:
            e = json.loads(line)
            typ = e['type']
            if typ=='capture_end':footer=e
            if side=='s':
                kind = server_stage(e)
            else:
                kind = CLIENT.get(typ)
                if typ=='client_task_drain' and e['data']['phase']=='start':
                    kind='CLIENT_DRAIN'
            if kind and game['start'] <= e.get('time',e.get('time_ms',0)) < game['finish']:
                result.append((e['monotonic_ns'],kind))
    if side!='s' and (not footer or not footer['writer_complete'] or any(footer[k] for k in ('dropped','size_capped','continues'))):
        raise ValueError(f'{mid}/{side}: incomplete recorded POV')
    return sorted(result)

def tape(mid):
    game = json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text())
    streams = {s:timeline(mid,s,game) for s in 'abs'}
    heads = {s:[t for t,k in stream if k==('SERVER_DRAIN' if s=='s' else 'TICK_HEAD')]
             for s,stream in streams.items()}
    n = min(160,min(map(len,heads.values()))-1)
    if n<5: raise ValueError(f'{mid}: incomplete native clocks')
    origin = min(h[0] for h in heads.values())
    lines = [f"# tracker_first {tracker_first(mid,game)}"]
    for s,stream in streams.items():
        start,end = heads[s][0],heads[s][n]
        events = [(t-start,k) for t,k in stream if start<=t<end]
        for key in (('SERVER_DRAIN','SERVER_ENTITY','SERVER_TRACK','SERVER_END') if s=='s' else ('TICK_HEAD','MOVE','CONSUME','FLUSH')):
            if sum(k==key for _,k in events)!=n: raise ValueError((mid,s,key,n))
        lines.append(f'{s} {start-origin} {end-start}')
        lines.extend(f'{dt} {k}' for dt,k in events)
    return '\n'.join(lines)+'\n'

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path)
    p.add_argument('--frozen-manifest',type=Path)
    a=p.parse_args()
    if a.frozen_manifest:
        m=json.loads(a.frozen_manifest.read_text())
        if m['matches'].keys()!=m['plan'].keys() or not (HERE/'fit.json').exists():
            p.error('Freeze fit and complete calendar collection first')
        mids=sorted(m['matches'].values())
    else:
        mids=json.loads((HERE/'protocol.json').read_text())['development_clock_matches']
    a.out.mkdir(parents=True,exist_ok=True)
    for mid in mids:
        path=a.out/(mid+'.txt.gz')
        # Results precede the asynchronous capture writer's footer. Wait for the original
        # scheduled match, including its last POV block, without changing the donor roster.
        finish=json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text())['finish']/1000
        while True:
            try:
                encoded=gzip.compress(tape(mid).encode(),mtime=0);break
            except (StopIteration,FileNotFoundError,EOFError,json.JSONDecodeError,ValueError):
                if time.time()-finish>60:raise
                time.sleep(1)
        if path.exists() and gzip.decompress(path.read_bytes())!=gzip.decompress(encoded):
            raise ValueError(f'{mid}: previously exported clock has changed')
        if not path.exists():
            with tempfile.NamedTemporaryFile(dir=a.out,delete=False) as f:f.write(encoded)
            Path(f.name).replace(path)
        print(mid,flush=True)
