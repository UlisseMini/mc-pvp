#!/usr/bin/env python3
"""Native packet-pipeline measurements, not a replacement fight simulator.

Payloads are matched FIFO across the two ends of each TCP connection. Main-thread
handler IDs link client receipt to application without nearest-time guessing.
Times use this host's monotonic clock; wall/monotonic offsets are reported to
check the shared-clock assumption rather than assuming arbitrary JVMs share it.
"""
import argparse
import base64
import bisect
import collections
import gzip
import json
import math
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
MOVEMENT = {3, 4, 5, 6}
INBOUND = MOVEMENT | {2, 11}
OUTBOUND = {0x12, 0x14, 0x15, 0x16, 0x17, 0x18, 0x1c}
NAMES = {2: 'attack', 3: 'move', 4: 'move', 5: 'move', 6: 'move', 11: 'action'}


def read(path):
    with gzip.open(path, 'rt') if str(path).endswith('.gz') else open(path) as f:
        return [json.loads(line) for line in f]


def archive(mid, side):
    prefix = side + '-client' if side in ('a', 'b') else 'server'
    paths = sorted((ROOT / 'runtime/pov' / mid).glob(prefix + '*-events.jsonl.gz'))
    if not paths:
        raise FileNotFoundError((mid, side))
    events = [e for path in paths for e in read(path)]
    return sorted(events, key=lambda e: e.get('monotonic_ns', 0))


class Reader:
    def __init__(self, raw):
        self.raw, self.at = raw, 0

    def number(self, fmt):
        n = struct.calcsize('>' + fmt)
        v, = struct.unpack_from('>' + fmt, self.raw, self.at)
        self.at += n
        return v

    def varint(self):
        value = 0
        for i in range(5):
            b = self.number('B')
            value |= (b & 127) << (7 * i)
            if not b & 128:
                return value
        raise ValueError('Invalid VarInt')


def decode(payload, direction):
    """Protocol47 fields used in these fixtures; preserve raw payload separately."""
    r = Reader(base64.b64decode(payload))
    pid = r.varint()
    out = {'id': pid}
    if direction == 'c2s':
        if pid in MOVEMENT:
            out.update(moving=pid in (4, 6), rotating=pid in (5, 6))
            out.update(x=0., y=0., z=0., yaw=0., pitch=0.)
            if out['moving']:
                out.update(zip(('x', 'y', 'z'), (r.number('d') for _ in range(3))))
            if out['rotating']:
                out.update(yaw=r.number('f'), pitch=r.number('f'))
            out['onGround'] = bool(r.number('B'))
        elif pid == 2:
            out.update(entity_id=r.varint(), action=r.varint())
        elif pid == 11:
            out.update(entity_id=r.varint(), action=r.varint(), aux=r.varint())
    elif pid in OUTBOUND:
        out['entity_id'] = r.varint()
        if pid == 0x12:
            out['velocity'] = [r.number('h') / 8000 for _ in range(3)]
        elif pid in (0x15, 0x17):
            out['delta'] = [r.number('b') / 32 for _ in range(3)]
        elif pid == 0x18:
            out['position'] = [r.number('i') / 32 for _ in range(3)]
        if pid in (0x16, 0x17, 0x18):
            out['yaw'], out['pitch'] = r.number('b'), r.number('b')
        if pid in (0x15, 0x16, 0x17, 0x18):
            out['onGround'] = bool(r.number('B'))
        if pid == 0x1c:
            # Entity flags are metadata index0/type0. Skip other vanilla fields.
            while (header := r.number('B')) != 127:
                typ, index = header >> 5, header & 31
                if typ in (0, 1, 2, 3):
                    value = r.number(('b', 'h', 'i', 'f')[typ])
                    if typ == 0 and index == 0:
                        out['flags'] = value
                        out['sprinting'] = bool(value & 8)
                elif typ == 4:
                    size = r.varint()
                    r.at += size
                elif typ == 6:
                    r.at += 12
                elif typ == 7:
                    r.at += 12
                else:
                    # Slots have variable NBT. No relevant flags may be inferred
                    # after unsupported fields. Keep that limitation explicit.
                    out['metadata_decode_stopped_at'] = index
                    break
    return out


def wire(events, direction, player=None):
    rows = []
    for e in events:
        if e['type'] != 'packet_wire' or player and e.get('player') != player:
            continue
        d = e['data']; meta = d.get('packet', d)
        if meta['direction'] != direction:
            continue
        flow = 'c2s' if (player is None and direction == 'out') or (player and direction == 'in') else 's2c'
        p = decode(d['base64'], flow)
        if p['id'] not in (INBOUND if flow == 'c2s' else OUTBOUND):
            continue
        rows.append({'event': e, 'ns': e['monotonic_ns'], 'payload': d['base64'], 'packet': p,
                     'packet_id': meta.get('packet_id')})
    return rows


def pair_payloads(sent, received):
    """FIFO per exact payload; discard only impossible old boundary records."""
    by_payload = collections.defaultdict(collections.deque)
    for r in received:
        by_payload[r['payload']].append(r)
    pairs, unmatched, old = [], [], []
    for s in sent:
        q = by_payload[s['payload']]
        while q and q[0]['ns'] < s['ns']:
            old.append(q.popleft())
        if q:
            pairs.append((s, q.popleft()))
        else:
            unmatched.append(s)
    remaining = old + [r for q in by_payload.values() for r in q]
    return pairs, unmatched, remaining


def summary(values):
    v = sorted(values)
    if not v:
        return {'n': 0}
    return {'n': len(v), 'min': v[0], 'median': v[len(v)//2],
            'p95': v[math.ceil(.95*len(v))-1], 'max': v[-1]}


def signature(p):
    pid = p['id']
    if pid in MOVEMENT:
        return (pid,) + tuple(p[k] for k in ('x', 'y', 'z', 'yaw', 'pitch', 'onGround'))
    return (pid, p['action'])


def input_signature(e):
    kind = e['packet_kind']
    if kind == 'ip' or kind.startswith('ip$'):
        pid = 6 if e['moving'] and e['rotating'] else 4 if e['moving'] else 5 if e['rotating'] else 3
        return (pid,) + tuple(e[k] for k in ('x', 'y', 'z', 'yaw', 'pitch', 'onGround'))
    if kind == 'in':
        return (2, {'INTERACT': 0, 'ATTACK': 1, 'INTERACT_AT': 2}[e['action']])
    if kind == 'is':
        return (11, {'START_SNEAKING': 0, 'STOP_SNEAKING': 1, 'STOP_SLEEPING': 2,
                     'START_SPRINTING': 3, 'STOP_SPRINTING': 4,
                     'RIDING_JUMP': 5, 'OPEN_INVENTORY': 6}[e['action']])
    raise ValueError(kind)


def client_drains(events):
    intervals=[];start=None
    for e in events:
        if e['type']!='client_task_drain':continue
        if e['data']['phase']=='start':
            if start is not None:raise ValueError('Nested/missing client drain end')
            start=e['monotonic_ns']
        elif start is not None:
            intervals.append((start,e['monotonic_ns']));start=None
    return intervals


def audit(mid):
    game = json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text())
    server = archive(mid, 'server')
    science = read(ROOT/'research/traces'/f'{mid}-server.jsonl.gz')
    science.sort(key=lambda e: e.get('monotonic_ns', 0))
    def active(e):
        return game['start'] <= e.get('time_ms', e.get('time', 0)) <= game['finish']
    report = {'match': mid, 'rtt_ms': [game['ping_a'], game['ping_b']], 'sides': {},
              'scope': 'Observed pipeline timings, conditional on recorded packets; not autonomous model accuracy.'}
    clocks=collections.defaultdict(dict)
    for e in science:
        if e['type']=='replication_server_clock' and e['phase'] in ('tick_start','drain_start','drain_end','tick_end'):
            clocks[e['server_tick']][e['phase']]=e['monotonic_ns']
    if clocks:
        widths=[];tail=[];outside=[];drains=sorted((v['drain_start'],k) for k,v in clocks.items() if 'drain_start' in v)
        for tick,c in clocks.items():
            if all(p in c for p in ('drain_start','drain_end','tick_end')):
                widths.append((c['drain_end']-c['drain_start'])/1e6)
                tail.append((c['tick_end']-c['drain_end'])/1e6)
        for e in science:
            if e['type']=='replication_server_input' and active(e):
                c=clocks.get(e['server_tick'],{})
                if not c.get('drain_start',math.inf)<=e['monotonic_ns']<=c.get('drain_end',-math.inf):outside.append(e['server_tick'])
        report['server_clock']={'recorded_ticks':len(clocks),'drain_duration_ms':summary(widths),
                                'drain_end_to_tick_end_ms':summary(tail),'inputs_outside_recorded_drain':outside,
                                'note':'Buffered observer records sorted by their captured monotonic timestamp. Tick labels precede the referee tail increment.'}
    for side in ('a', 'b'):
        client = archive(mid, side); player = game[side]
        outgoing = wire(client, 'out'); inbound = wire(server, 'in', player)
        c2s, missed_c, missed_s = pair_payloads(outgoing, inbound)
        queue = collections.defaultdict(collections.deque)
        for s, r in c2s:
            queue[signature(r['packet'])].append((s, r))
        stages = collections.defaultdict(list); missing_inputs = []
        for e in science:
            if e['type'] != 'replication_server_input' or e['player'] != player:
                continue
            sig = input_signature(e); q = queue[sig]
            if not q or q[0][1]['ns'] > e['monotonic_ns']:
                if active(e): missing_inputs.append(e)
                continue
            s, r = q.popleft()
            if not active(e): continue
            family = NAMES[sig[0]]
            stages[family+'_wire_transport_ms'].append((r['ns']-s['ns'])/1e6)
            stages[family+'_server_queue_ms'].append((e['monotonic_ns']-r['ns'])/1e6)
            stages[family+'_client_send_to_server_handler_ms'].append((e['monotonic_ns']-s['ns'])/1e6)
            if clocks:
                j=bisect.bisect_right(drains,(r['ns'],math.inf))
                if j<len(drains):
                    predicted_tick=drains[j][1]
                    stages[family+'_actual_tick_minus_first_drain_after_wire_receipt'].append(e['server_tick']-predicted_tick)
        sent = wire(server, 'out', player); received = wire(client, 'in')
        s2c, missing_out, missing_in = pair_payloads(sent, received)
        handlers = {e['data']['packet_id']: e for e in client if e['type']=='packet_handler_enter' and e['data']['main_thread'] and e['data']['kind'].startswith('S')}
        queued = {e['data']['packet_id']:e for e in client if e['type']=='packet_task_enqueued'}
        client_queue_intervals=client_drains(client)
        client_queue_starts=[d[0] for d in client_queue_intervals]
        client_queue_checks=collections.Counter();client_queue_failures=[]
        if client_queue_intervals:
            for pid,h in handlers.items():
                if not active(h):continue
                q=queued.get(pid)
                if q is None:client_queue_checks['handler_missing_enqueue']+=1;continue
                k=bisect.bisect_left(client_queue_starts,q['monotonic_ns'])
                client_queue_checks['compared']+=1
                if k<len(client_queue_intervals) and client_queue_intervals[k][0]<=h['monotonic_ns']<=client_queue_intervals[k][1]:
                    client_queue_checks['correct_first_drain']+=1
                else:
                    client_queue_failures.append({'packet_id':pid,'kind':h['data']['kind'],'enqueue_ns':q['monotonic_ns'],'handler_ns':h['monotonic_ns']})
        starts = [e['monotonic_ns'] for e in client if e['type']=='tick_start']
        ends = [e['monotonic_ns'] for e in client if e['type']=='tick_end']
        moves = [e['monotonic_ns'] for e in client if e['type']=='science_move']
        between = collections.Counter(); totals = collections.Counter(); self_changes = []
        self_id = next(e['data']['self']['id'] for e in client if e['type']=='tick_start' and active(e))
        for s, r in s2c:
            if not active(r['event']): continue
            h = handlers.get(r['packet_id'])
            if h is None: continue
            pid = s['packet']['id']; family = 'velocity' if pid==0x12 else 'metadata' if pid==0x1c else 'replica'
            apply_ns = h['monotonic_ns']
            stages[family+'_wire_transport_ms'].append((r['ns']-s['ns'])/1e6)
            stages[family+'_client_queue_ms'].append((apply_ns-r['ns'])/1e6)
            stages[family+'_server_send_to_client_handler_ms'].append((apply_ns-s['ns'])/1e6)
            next_move = bisect.bisect_left(moves, apply_ns)
            if next_move < len(moves): stages[family+'_handler_to_next_move_ms'].append((moves[next_move]-apply_ns)/1e6)
            si = bisect.bisect_right(starts, apply_ns)-1
            ei = bisect.bisect_right(ends, apply_ns)-1
            totals[family] += 1
            if ei >= 0 and (si < 0 or ends[ei] > starts[si]): between[family] += 1
            if pid==0x1c and s['packet']['entity_id']==self_id and 'sprinting' in s['packet']:
                self_changes.append({'ms': h['time_ms']-game['start'], 'sprint': s['packet']['sprinting']})
        offsets = {}
        for name, events in [('client',client),('server',server)]:
            offsets[name] = summary([e.get('time_ms',e.get('time',0))-e['monotonic_ns']/1e6
                                     for e in events if e.get('monotonic_ns') and e['type']=='packet_wire'])
        counts = {family: {'applies': totals[family], 'between_client_ticks': between[family]} for family in totals}
        report['sides'][side] = {'stages_ms': {k:summary(v) for k,v in stages.items()},
                                'packet_application': counts, 'own_sprint_metadata': self_changes,
                                'client_task_queue':{'checks':dict(client_queue_checks),'failures':client_queue_failures,'drains':len(client_queue_intervals)},
                                'clock_wall_minus_mono_ms': offsets,
                                'unmatched_active_c2s_sends': sum(active(x['event']) for x in missed_c),
                                'unmatched_active_c2s_receipts': sum(active(x['event']) for x in missed_s),
                                'unmatched_active_server_inputs': len(missing_inputs),
                                'unmatched_active_s2c_sends': sum(active(x['event']) for x in missing_out),
                                'unmatched_active_s2c_receipts': sum(active(x['event']) for x in missing_in)}
    return report


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('matches',nargs='+');p.add_argument('--output',type=Path)
    args=p.parse_args();result=[audit(m) for m in args.matches]
    if args.output: args.output.write_text(json.dumps(result,indent=2)+'\n')
    else: print(json.dumps(result,indent=2))
