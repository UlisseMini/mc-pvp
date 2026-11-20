"""Export exogenous host opportunities. No hit, position, outcome or packet payload enters a tape."""
import argparse
import gzip
import json
from pathlib import Path
import tempfile
import time
from captures import events as capture_events

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CLIENT = {'native_input_head':'TICK_HEAD', 'native_attack_loop':'CONSUME',
          'science_move':'MOVE', 'science_interpolation':'INTERP', 'tick_end':'FLUSH',
          'replication_frame':'FRAME_ENTRY'}
SERVER = {'drain_start':'SERVER_DRAIN','entity_tick_start':'SERVER_ENTITY',
          'tracker_tick_start':'SERVER_TRACK','tick_end':'SERVER_END'}
MOVEMENT_WIRE = {'C03PacketPlayer', 'C04PacketPlayerPosition', 'C05PacketPlayerLook', 'C06PacketPlayerPosLook'}

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

def timeline(mid, side, game, explicit_polls=False, movement_flush=False):
    result = []
    if side == 's':
        with (ROOT/'runtime/replays'/f'{mid}.jsonl').open() as stream:
            for e in map(json.loads, stream):
                if (kind := server_stage(e)) and game['start'] <= e.get('time',e.get('time_ms',0)) < game['finish']:
                    result.append((e['monotonic_ns'], kind))
        return sorted(result)

    path = next((ROOT/'runtime/pov'/mid).glob(f'{side}-client-*-events.jsonl.gz'))
    for e in capture_events(path, {*CLIENT, 'client_task_drain', 'native_cap_poll', 'packet_wire'}):
        typ = e['type']
        kind = CLIENT.get(typ)
        if typ=='client_task_drain' and e['data']['phase']=='start':
            kind='CLIENT_DRAIN'
        if explicit_polls and typ=='native_cap_poll' and e.get('match')==mid:
            kind = {'frame':'POLL_FRAME', 'native_tick':'POLL_NATIVE'}[e['source']]
        if movement_flush and typ=='tick_end':
            kind = None
        if movement_flush and typ=='packet_wire':
            packet = e['data'].get('packet', e['data'])
            if packet['direction']=='out' and packet['kind'] in MOVEMENT_WIRE:
                kind = 'FLUSH'
        if kind and game['start'] <= e.get('time',e.get('time_ms',0)) < game['finish']:
            at = e['poll_ns'] if kind in ('POLL_FRAME', 'POLL_NATIVE') else e['monotonic_ns']
            result.append((at,kind))
    return sorted(result)

def tape(mid, *, maximum_ticks=160, explicit_polls=False, movement_flush=False):
    game = json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text())
    streams = {s:timeline(mid,s,game,explicit_polls,movement_flush) for s in 'abs'}
    heads = {s:[t for t,k in stream if k==('SERVER_DRAIN' if s=='s' else 'TICK_HEAD')]
             for s,stream in streams.items()}
    available = min(map(len,heads.values())) - 1
    n = available if maximum_ticks is None else min(maximum_ticks,available)
    if n<5: raise ValueError(f'{mid}: incomplete native clocks')
    origin = min(h[0] for h in heads.values())
    lines = [f"# tracker_first {tracker_first(mid,game)}"]
    for s,stream in streams.items():
        start,end = heads[s][0],heads[s][n]
        events = [(t-start,k) for t,k in stream if start<=t<end]
        for key in (('SERVER_DRAIN','SERVER_ENTITY','SERVER_TRACK','SERVER_END') if s=='s' else ('TICK_HEAD','MOVE','CONSUME','FLUSH')):
            if sum(k==key for _,k in events)!=n: raise ValueError((mid,s,key,n))
        if s!='s' and explicit_polls and not {'POLL_FRAME','POLL_NATIVE'}.issubset(k for _,k in events):
            raise ValueError((mid,s,'incomplete native poll capture'))
        if s!='s' and movement_flush:
            moves = [t for t,k in events if k=='MOVE']
            flushes = [t for t,k in events if k=='FLUSH']
            if not all(move <= flush < after for move,flush,after in zip(moves,flushes,moves[1:]+[end-start])):
                raise ValueError((mid,s,'movement wires do not follow their native move steps'))
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
