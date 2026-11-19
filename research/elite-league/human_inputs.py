"""Extract causal movement examples and physical click labels from authorized local player POVs."""
import argparse
import base64
import collections
import gzip
import json
import math
import statistics
import struct
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def sign(value):
    return (value > 0) - (value < 0)


def describe(values):
    if not values:
        return {}
    values = sorted(values)
    return dict(count=len(values), mean=statistics.mean(values), median=statistics.median(values),
                p10=values[int(.1 * (len(values)-1))], p90=values[int(.9 * (len(values)-1))])


def extract(mid, player):
    result = json.loads((ROOT / 'runtime/results' / (mid + '.json')).read_text())
    folder = ROOT / 'runtime/pov' / mid
    meta = json.loads((folder / 'manifest.json').read_text())
    side, = [s for s,p in meta['players'].items() if p['name'].casefold() == player.casefold() and p['role'] == 'human']
    other = 'b' if side == 'a' else 'a'
    assert meta['players'][other]['role'] == 'bot' and result['mode'] == 'sumo'
    assert result['combat_profile']['knockback_model'] == 'mmc_pre_attack_sprint_v2'
    target_name = meta['players'][other]['name']
    begin, end = meta['start_ms'], meta['finish_ms']
    source, = folder.glob(side + '-client-*-events.jsonl.gz')
    starts, ends, clicks, calls, packets, returns = {}, {}, collections.defaultdict(list), collections.Counter(), {}, []
    screens = collections.Counter()
    footer = False
    drops = 0
    with gzip.open(source, 'rt') as f:
        for line in f:
            # The export adds synthetic archive records with another schema. Ignore those.
            row = json.loads(line)
            kind, d, t, seq = row['type'], row.get('data', {}), row.get('time_ms'), row.get('sequence')
            if kind == 'capture_end':
                footer = True
            if kind == 'capture_health':
                drops = max(drops, int(d.get('writer_drops',0)))
            if t is None or seq is None:
                continue
            tick = row.get('client_tick')
            if kind == 'packet_wire' and d['packet']['kind'] == 'S19PacketEntityStatus':
                raw = base64.b64decode(d['base64'], validate=True)
                if len(raw) != 6 or raw[0] != 0x1a:
                    raise ValueError(f'{mid}: unexpected entity status format')
                if raw[-1] == 2:
                    packets[d['packet']['packet_id']] = struct.unpack('>i',raw[1:5])[0]
            elif kind == 'packet_handler_enter' and d.get('main_thread') and d['kind'] == 'S19PacketEntityStatus':
                returns.append((seq, t, d['packet_id']))
            if not begin <= t <= end:
                continue
            if kind == 'tick_start':
                screens[d.get('screen') or 'game'] += 1
            if kind == 'tick_start' and d.get('self') and not d.get('screen'):
                target = next((e for e in d['entities'] if e.get('name') == target_name), None)
                if target:
                    starts[tick] = (seq,t,d['self'],target)
            elif kind == 'tick_end' and d.get('self') and not d.get('screen'):
                ends[tick] = (seq,t,d['self'])
            elif kind == 'mouse' and d.get('button') == 0 and d.get('down'):
                clicks[tick].append((seq,t,d.get('event_ns')))
            elif kind == 'attack_click_before':
                calls[tick] += 1
    if not footer or drops:
        raise ValueError(f'{mid}: capture missing footer or writer dropped events ({drops})')
    hurt = sorted((seq,t,packets[p]) for seq,t,p in returns if p in packets)
    last = {}
    cursor = 0
    rows, yaw_errors, pitch_errors, clicks_real = [], [], [], []
    skips = collections.Counter()
    for tick, (seq,t,own,opp) in sorted(starts.items()):
        while cursor < len(hurt) and hurt[cursor][0] < seq:
            _,event_t,entity = hurt[cursor]
            last[entity] = event_t
            cursor += 1
        if tick not in ends:
            skips['missing_end'] += 1
            continue
        end_seq, end_t, after = ends[tick]
        if end_seq <= seq or end_t < t or after['id'] != own['id']:
            raise ValueError(f'{mid}: invalid tick pair')
        if not all(seq < n < end_seq for n,_,_ in clicks[tick]):
            # A GUI event outside this tick's actionable input interval is an ambiguous label.
            skips['outside_input'] += 1
            continue
        movement = after['movement_input']
        if movement['sneak']:
            skips['sneaking'] += 1
            continue
        x,y,z = own['pos']
        tx,ty,tz = opp['pos']
        gap = math.hypot(tx-x,tz-z)
        # All input vectors are preceding observations. Aim offsets are diagnostics only.
        ideal_yaw = math.degrees(math.atan2(-(tx-x),tz-z))
        eye = y+own['eye_height']
        aim_y = min(max(eye,ty+.15),ty+1.65)
        ideal_pitch = -math.degrees(math.atan2(aim_y-eye,max(1e-9,gap)))
        age = lambda entity: max(0,t-last[entity]) if entity in last else -1
        # Ctrl binding is unobserved. The legal emulator always requests sprint when walking forward.
        forward, lateral = sign(movement['moveForward']),sign(movement['moveStrafe'])
        key = 2 if forward>0 else 3 if forward<0 else 0
        jump = int(movement['jump'] and own['ground'])
        click = int(bool(clicks[tick]))
        action = key+4*(lateral+1)+12*click+24*jump
        rows.append([mid,tick,*own['pos'],*opp['pos'],*own['motion'],int(own['ground']),
                     int(own['sprinting']),age(own['id']),age(opp['id']),result['initial_ping_'+side],action])
        yaw_errors.append(abs((own['yaw']-ideal_yaw+180)%360-180))
        pitch_errors.append(abs(own['pitch']-ideal_pitch))
        clicks_real.extend(ns/1e6 if ns is not None else None for _,_,ns in clicks[tick])
    return rows, dict(mid=mid, opponent=result['policy_'+other], opponent_cps=result['click_cps_'+other],
        rtt=result['initial_ping_'+side], duration_ms=end-begin, eligible_ticks=len(rows), skips=skips, screens=screens,
        click_presses=sum(map(len,clicks.values())), game_attack_calls=sum(calls.values()),
        eligible_physical_presses=len(clicks_real), multi_press_ticks=sum(len(v)>1 for v in clicks.values()),
        click_intervals_ms=[b-a for a,b in zip(clicks_real,clicks_real[1:]) if a is not None and b is not None],
        absolute_yaw_error=describe(yaw_errors), absolute_pitch_error=describe(pitch_errors),
        statuses_before_input=sum(1 for _,t,_ in hurt if begin<=t<=end), action_counts=collections.Counter(row[-1] for row in rows))


def main(dest):
    plan=json.loads((HERE/'human-development.json').read_text())
    dest.mkdir(exist_ok=False,parents=True)
    stats={}
    for split in ('fit','select','later'):
        summary=[]
        with (dest/(split+'.txt')).open('x') as f:
            for mid in plan[split]:
                rows, desc = extract(mid,plan['player'])
                for row in rows:
                    f.write(' '.join(map(str,row))+'\n')
                summary.append(desc)
        stats[split]=summary
    (dest/'coverage.json').write_text(json.dumps(stats,indent=2)+'\n')
    for split,items in stats.items():
        print(split,len(items),'bouts',sum(x['eligible_ticks'] for x in items),'heads',
              sum(x['eligible_physical_presses'] for x in items),'physical presses',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination',type=Path)
    main(parser.parse_args().destination)
