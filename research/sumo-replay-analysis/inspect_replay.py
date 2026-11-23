"""Inspect archived MMC-footprint sumo duels and branch their recorded local movement."""
import argparse
import base64
import bisect
from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
import math
import re
import struct
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.match_ledger import rows as file_rows, varint

ROWS = re.findall(r'"([.sbmg]{13})"', (ROOT / 'src/lab/SumoFootprint.java').read_text())

HERE = Path(__file__).resolve().parent
ZERO = (0., 0.)
TOL = 2e-5


def add(u, v):
    return tuple(a + b for a, b in zip(u, v))


def sub(u, v):
    return tuple(a - b for a, b in zip(u, v))


def mul(u, s):
    return tuple(a*s for a in u)


def xz(u):
    return (u[0], u[2])


def dot(u, v):
    return sum(a*b for a, b in zip(u, v))


def stamp(event):
    return event['monotonic_ns']


def read(path):
    warnings = []
    for event, _ in file_rows(path,warnings):
        yield event
    if warnings:
        raise ValueError('; '.join(warnings))


def ordered_capture(path):
    events = list(read(path))
    footer = events[-1] if events else {}
    if (footer.get('type') != 'capture_end' or not footer.get('writer_complete') or
            any(footer.get(field) for field in ('dropped','size_capped','continues'))):
        raise ValueError(f'Incomplete capture: {path}')
    return sorted((r for r in events if 'monotonic_ns' in r),key=stamp)


def velocity(payload):
    wire = base64.b64decode(payload)
    entity,offset = varint(wire,1)
    return entity, tuple(component/8000 for component in struct.unpack('>hhh',wire[offset:]))


class Floor:
    def __init__(self, spawn_x, radius):
        center = 4096 * round(spawn_x/4096)
        self.boxes = [(center+x-6-radius, center+x-5+radius, z-6-radius, z-5+radius)
                      for z,row in enumerate(ROWS) for x,block in enumerate(row) if block != '.']

    def covers(self, pos):
        x,z = pos
        return any(x0 < x < x1 and z0 < z < z1 for x0,x1,z0,z1 in self.boxes)

    def margin(self, pos, ray):
        """Distance to first loss of footprint overlap along a unit ray."""
        intervals = []
        for x0,x1,z0,z1 in self.boxes:
            low, high = -math.inf, math.inf
            for x,d,a,b in zip(pos,ray,(x0,z0),(x1,z1)):
                if abs(d) < 1e-12:
                    if not a < x < b:
                        low, high = 1, 0
                        break
                    continue
                entry, exit = sorted(((a-x)/d,(b-x)/d))
                low, high = max(low, entry), min(high, exit)
            if high > max(0,low):
                intervals.append((low, high))
        distance = 0.
        if self.covers(pos):
            for low,high in sorted(intervals):
                if low > distance + 1e-9:
                    break
                distance = max(distance, high)
        return distance


@dataclass
class Move:
    ns: int
    tick: int
    start: tuple
    finish: tuple
    velocity: tuple
    drag: float
    ground: bool
    valid: bool
    w: bool
    sprint: bool

    @property
    def delta(self):
        return sub(xz(self.finish),xz(self.start))


@dataclass
class Press:
    event: dict
    after: dict
    kind: str
    victim: bool
    counter: int
    near: float | None
    key_up_ms: float | None
    w_held: bool | None


def nearest(data, enemy, offset=ZERO):
    entity = next((e for e in data.get('entities', []) if e.get('name') == enemy), None)
    if not entity or 'box' not in entity:
        return None
    player = data['self']
    partial = data.get('partial_ticks', 1)
    eye = [old+(new-old)*partial for old,new in zip(player['previous'],player['pos'])]
    eye[0] += offset[0]
    eye[2] += offset[1]
    eye[1] += player['eye_height']
    box = entity['box']
    border = entity.get('border', .1)
    # The selection uses the local replica's expanded box. This is a closest-point
    # distance, useful as a reach exclusion. It does not predict the aimed ray.
    return math.sqrt(sum(max(0,box[i]-border-eye[i],eye[i]-box[i+3]-border)**2 for i in range(3)))


class Client:
    def __init__(self, file, name, enemy):
        self.name = name
        self.enemy = enemy
        rows = ordered_capture(file)
        self.moves, self.presses, self.samples, self.pushes, self.outgoing = [], [], [], [], []
        ticks, wire, pending = defaultdict(dict), {}, {}
        up, w_held = None, None
        for event in rows:
            kind, data = event['type'], event.get('data', {})
            body = data.get('self')
            if kind == 'key' and data.get('key') in (17, 'W') and not data['repeat']:
                w_held = data['down']
                if not w_held:
                    up = stamp(event)
            elif kind in ('tick_start','tick_end','motion_travel_before','motion_travel_after') and body:
                tick = ticks[event['client_tick']]
                if kind in tick:
                    tick['duplicate'] = True
                tick[kind] = event
            elif kind == 'render_view' and body and data.get('screen') is None:
                self.samples.append(event)
            elif kind == 'attack_click_before' and body:
                pending[event['client_tick']] = (event, up, w_held)
            elif kind == 'attack_click_after' and body and event['client_tick'] in pending:
                before, was_up, held = pending.pop(event['client_tick'])
                original = before['data']
                actor = original['self']
                target = original.get('ray', {}).get('entity')
                blocked = original['left_click_counter']
                v = xz(actor['motion'])
                after = xz(body['motion'])
                is_cut = actor['sprinting'] and not body['sprinting'] and not blocked and v != ZERO and math.dist(mul(v,.6),after) < 1e-9
                self.presses.append(Press(before,event,'brake' if is_cut else 'timer' if blocked else 'other',
                                          target is not None,blocked,None,
                                          (stamp(before)-was_up)/1e6 if was_up is not None and stamp(before)-was_up<=250e6 else None,held))
            elif kind == 'packet_wire':
                p = data.get('packet', {})
                if p.get('direction') == 'in' and p.get('kind') in ('S12PacketEntityVelocity','S08PacketPlayerPosLook'):
                    wire[p['packet_id']] = (p['kind'],data['base64'])
                if p.get('direction') == 'out' and p.get('kind') == 'C02PacketUseEntity':
                    payload = base64.b64decode(data['base64'])
                    if payload[-1:] == b'\x01':
                        self.outgoing.append((stamp(event),data['base64']))
            elif kind == 'packet_handler_return' and data.get('main_thread') and data.get('packet_id') in wire:
                packet_kind, payload = wire[data['packet_id']]
                self.pushes.append((event,payload,velocity(payload) if packet_kind == 'S12PacketEntityVelocity' else None))
        self.sample_ns = list(map(stamp,self.samples))
        self.ids = {e['name']:e['id'] for s in self.samples for e in s['data'].get('entities', [])
                    if e.get('name') in (name,enemy)}
        self.pushes = [p for p in self.pushes if p[2] is None or p[2][0] == self.ids.get(name)]
        for press in self.presses:
            data = press.event['data']
            press.victim = enemy in self.ids and data.get('ray',{}).get('entity') == self.ids[enemy]
            before = self.sample(stamp(press.event))
            press.near = nearest(before['data'],enemy) if before else None
            if press.kind == 'other':
                press.kind = ('selected' if data['self']['sprinting'] else 'sprint_off') if press.victim else 'miss'
        for index, tick in ticks.items():
            required = ('tick_start','motion_travel_before','motion_travel_after','tick_end')
            if not all(k in tick for k in required) or tick.get('duplicate'):
                continue
            start, before, after, end = (tick[k] for k in required)
            p, u, v, q = (e['data']['self'] for e in (start,before,after,end))
            if p.get('name') != name or q.get('name') != name:
                continue
            drag = .54600006 if u['ground'] else .9100000262260437
            observed = sub(xz(q['pos']),xz(p['pos']))
            valid = not v['isCollidedHorizontally'] and math.dist(mul(observed,drag),xz(v['motion'])) < TOL
            self.moves.append(Move(stamp(before),index,p['pos'],q['pos'],u['motion'],drag,u['ground'],
                                  valid,q.get('movement_input',{}).get('moveForward',0)>0,u['sprinting']))
        self.moves.sort(key=lambda m:m.ns)
        self.move_ns = [m.ns for m in self.moves]

    def sample(self, ns):
        index = bisect.bisect_right(self.sample_ns,ns)-1
        return self.samples[index] if index>=0 and ns-self.sample_ns[index]<=60e6 else None


@dataclass
class Cut:
    ns: int
    motion: tuple
    actual: float
    edited: float
    label: str


def vec(v):
    return [round(x, 6) for x in v]


def branch(moves, cuts, floor, axis, checks=(), overwrite=None, impulse_shift=ZERO):
    """Replay horizontal differences and stop at the first changed floor collision."""
    cuts = sorted(cuts,key=lambda c:c.ns)
    i, j = 0, 0
    delta_x, delta_v = ZERO, impulse_shift
    rows, reach_checks = [], []
    status = 'window_complete'
    for move in moves:
        if not move.valid:
            status = 'unverified_motion'
            break
        # Compare before the movement. A later velocity packet overwrites velocity
        # difference, while the position difference survives it.
        events = [(c.ns,'cut',c) for c in cuts[i:] if c.ns < move.ns]
        i += len(events)
        upcoming = [(ns,'reach',fn) for ns,fn in checks[j:] if ns < move.ns]
        j += len(upcoming)
        prev_ns = rows[-1]['ns'] if rows else -math.inf
        if overwrite is not None and prev_ns <= overwrite < move.ns:
            events.append((overwrite,'overwrite',None))
        for ns,kind,item in sorted(events+upcoming,key=lambda e:(e[0],e[1])):
            if kind == 'overwrite':
                delta_v = ZERO
            elif kind == 'cut':
                delta_v = add(mul(delta_v,item.edited),mul(item.motion,item.edited-item.actual))
            elif delta_x != ZERO:
                result = item(delta_x)
                if result:
                    reach_checks.append(result)
                    if result.get('lost_reach'):
                        status = 'recorded_contact_may_change'
                        return pack(rows,reach_checks,status,axis)
        # Vanilla clamps small residual horizontal motion before travel. A changed
        # crossing is a small model error. Report observed residual separately.
        before_x = delta_x
        delta_x = add(delta_x,delta_v)
        delta_v = mul(delta_v,move.drag)
        factual = xz(move.finish)
        edited = add(factual,delta_x)
        factual_support, edited_support = floor.covers(factual),floor.covers(edited)
        possible_landing = move.velocity[1] <= 0 and move.start[1] >= 12.-1e-6 and move.start[1]+move.velocity[1] <= 12.+1e-6
        rows.append(dict(ns=move.ns,tick=move.tick,actual=vec(factual),edited=vec(edited),shift=vec(delta_x),
                         before_shift=vec(before_x),actual_y=round(move.finish[1],5),factual_support=factual_support,
                         edited_support=edited_support,possible_landing=possible_landing,
                         actual_progress=dot(sub(factual,xz(moves[0].start)),axis),
                         edited_progress=dot(sub(edited,xz(moves[0].start)),axis)))
        if possible_landing and factual_support != edited_support:
            status = 'landing_diverges'
            break
    return pack(rows,reach_checks,status,axis)


def pack(rows, checks, status, axis):
    first = rows[:6]
    savings = (max([0.]+[r['actual_progress'] for r in first])
               - max([0.]+[r['edited_progress'] for r in first])) if first else None
    cross = next((dict((k,r[k]) for k in ('ns','factual_support','edited_support','actual_y','possible_landing'))
                  for r in rows if r['factual_support'] != r['edited_support']),None)
    landing = next((r['ns'] for r in rows if r['possible_landing'] and r['factual_support'] != r['edited_support']),None)
    return dict(status=status,n=len(rows),saving=round(savings,3) if savings is not None else None,
                floor_difference=cross,landing_divergence_ns=landing,
                final_shift=vec(rows[-1]['shift']) if rows else None,
                final_inward=round(-dot(rows[-1]['shift'],axis),3) if rows else None,
                checks=checks,rows=rows)


class Server:
    def __init__(self, path):
        self.sent, self.received = defaultdict(list), defaultdict(list)
        active, complete = {}, {}
        for event in ordered_capture(path):
            kind = event['type']
            packet = event.get('data',{})
            if kind == 'packet_wire' and 'base64' in packet:
                payload = base64.b64decode(packet['base64'])
                if payload[:1] == b'\x12' and packet['direction'] == 'out':
                    self.sent[event['player']].append((event,packet['base64'],velocity(packet['base64'])))
                if payload[:1] == b'\x02' and payload[-1:] == b'\x01' and packet['direction']=='in':
                    active[packet['packet_id']] = (event,packet['base64'])
            elif kind == 'server_packet_handler_enter' and event.get('main_thread') and event['packet_id'] in active:
                complete[event['packet_id']] = event
            elif kind == 'server_packet_handler_return' and event.get('main_thread') and event['packet_id'] in complete:
                wire, content = active.pop(event['packet_id'])
                self.received[event['player']].append((wire,content,complete.pop(event['packet_id']),event))

    def launch(self, hit, impulse, client):
        hits = [(sent,payload) for sent,payload,decoded in self.sent[client.name]
                if -1 <= sent['time_ms']-hit['time'] <= 16 and
                math.dist(decoded[1],impulse)<.00024 and decoded[0]==client.ids.get(client.name)]
        if len(hits) != 1:
            return None, None
        sent, payload = hits[0]
        equivalents = [stamp(p[0]) for p in client.pushes if p[1]==payload and 0 <= stamp(p[0])-stamp(sent) <= 400e6]
        return (equivalents[0],stamp(sent)) if len(equivalents)==1 else (None,stamp(sent))

    def press(self, press, client, hits):
        after = stamp(press.after)
        t = stamp(press.event)
        candidates = [(ns,content) for ns,content in client.outgoing if t-3e6<=ns<=after+4e6]
        if len(candidates)!=1:
            return None
        ns, content = candidates[0]
        duplicate = min((n for n,p in client.outgoing if n>ns and p==content),default=math.inf)
        packets = [(entered,returned) for wire,payload,entered,returned in self.received[client.name]
                   if content==payload and ns<=stamp(wire)<min(duplicate,ns+350e6)]
        if len(packets)!=1:
            return None
        entered, returned = packets[0]
        bracketed = [h['hit_index'] for h in hits if h['attacker']==client.name and
                     h['server_tick']==entered['server_tick'] and entered['time']<=h['time']<=returned['time']]
        return dict(tick=entered['server_tick'], accepts=bracketed, local_to_entry_ms=round((stamp(entered)-t)/1e6,1))


def move_span(client, beginning, limit, count=24):
    i = bisect.bisect_right(client.move_ns,beginning)
    end = bisect.bisect_left(client.move_ns,limit)
    result = []
    for move in client.moves[i:min(end,i+count)]:
        if result and (move.tick != result[-1].tick+1 or math.dist(move.start,result[-1].finish)>TOL or
                       move.ns-result[-1].ns>120e6):
            break
        result.append(move)
    return result


def frame_at(client, ns):
    i = bisect.bisect_left(client.move_ns,ns)
    return client.moves[i].start if i<len(client.moves) else None


def reach_fn(sample, press, client, reach, receipt):
    prior = nearest(sample['data'],client.enemy)
    if prior is None:
        return None

    def compare(offset):
        updated = nearest(sample['data'],client.enemy,offset)
        lost = press.victim and prior<=reach and updated>reach+.10
        gained = press.kind=='miss' and prior>reach+.10 and updated<reach-.10
        if lost or gained:
            return dict(ms=round((stamp(press.event)-receipt)/1e6,1), before=round(prior,3), after=round(updated,3),
                        lost_reach=lost, gains_geometric_reach=gained)
        return None

    return compare


def episode(box, victim, client, attacker, server, report, floor, hits, launch_rows):
    hit, physical, receipt, send = box
    impulse = physical['velocity']
    speed = math.hypot(*xz(impulse))
    axis = mul(xz(impulse),1/speed)
    t0 = hit['time']
    arena_top = report['sumo_arena']['top_y']
    target = hit['target_state']
    origin = target['posX'],target['posZ']
    summary = dict(i=hit['hit_index'], t=t0-report['start'], victim=victim, tick=hit['server_tick'],
                   sprint=physical['pre_attack_server_sprint'], speed=round(speed,4),
                   target_y=round(target['posY'],3), server_floor=floor.covers(origin),
                   server_margin=round(floor.margin(origin,axis),3), scope='missing_unique_delivery', attacker=hit['attacker'], target=hit['target'])
    if receipt is None:
        return summary
    after = [stamp(p[0]) for p in client.pushes if stamp(p[0])>receipt]
    finish_ns = min((m.ns for m in client.moves if receipt<m.ns and m.ns-receipt>1.25e9),default=math.inf)
    next_push = min(after,default=math.inf)
    after_next = min((q for q in after if q>next_push),default=math.inf)
    next_record = next((confirmed for confirmed in launch_rows if confirmed[2]==next_push),None)
    if next_record:
        finish_ns = min(finish_ns,after_next,next_push+650e6)
        summary['next_incoming_hit'] = next_record[0]['hit_index']
    else:
        finish_ns = min(finish_ns,next_push)
    # Match end is measured on the same host. The monotonic stamp is used for
    # ordering within and across both client captures and the finer server archive.
    finish_ns = min(finish_ns,send+(report['finish']-t0+1)*1e6)
    moves = move_span(client,receipt,finish_ns)
    if not moves:
        summary['scope']='no_complete_movement'
        return summary
    # The lab's paired POVs share the host's monotonic clock. Retain packet receipt
    # time even when the hit came outside the client's finished movement prefix.
    summary.update(receipt=receipt,delay_ms=round((receipt-send)/1e6,1), scope='local',
                   client_margin=round(floor.margin(xz(moves[0].start),axis),3),
                   client_origin=vec(moves[0].start), first_ground=moves[0].ground,
                   first_moves=[[round((m.ns-receipt)/1e6,1),round(dot(m.delta,axis),3),m.ground,m.w,m.sprint,m.valid]
                                for m in moves[:6]])
    reach_limit = report['attack_reach_'+victim]
    limit = min(next_push,finish_ns)
    return_check = None
    if next_record:
        return_check = next_attack_view(next_record[0], attacker, server, hits, next_record[3], report['attack_reach_'+('b' if victim=='a' else 'a')])
    presses = [p for p in client.presses if receipt<stamp(p.event)<finish_ns]
    checks, factual_cuts = [], []
    for p in presses:
        t = stamp(p.event)
        sample = client.sample(t)
        if sample:
            compare = reach_fn(sample,p,client,reach_limit,receipt)
            if compare:
                checks.append((t,compare))
        if p.kind=='brake' and p.victim:
            factual_cuts.append(Cut(t,xz(p.event['data']['self']['motion']),.6,.6,'recorded'))
    baseline = branch(moves,[],floor,axis)
    summary['baseline_steps'] = baseline.pop('rows')
    summary['baseline_status'] = baseline['status']
    first = [r for r in summary['baseline_steps'][:6] if r['ns'] < limit]
    if not first:
        summary['scope']='unverified_motion'
        return summary
    cutoff = first[-1]['ns']
    summary['first_n'] = len(first)
    summary['slide'] = round(max([0.]+[r['actual_progress'] for r in first]),3)
    summary['first_uncovered_ms'] = next((round((r['ns']-receipt)/1e6,1) for r in summary['baseline_steps']
                                         if r['ns'] < limit and not r['factual_support']),None)
    summary['first_falling_ms'] = next((round((r['ns']-receipt)/1e6,1) for r in summary['baseline_steps']
                                       if r['ns'] < limit and r['actual_y'] < arena_top-.001),None)
    options, press_lines = [], []
    for p in presses:
        ns = stamp(p.event)
        if ns>=cutoff:
            break
        local = p.event['data']['self']
        motion = xz(local['motion'])
        press_lines.append(dict(ms=round((ns-receipt)/1e6,1),kind=p.kind,on_target=p.victim,timer=p.counter,
                              sprint=local['sprinting'],outward=round(dot(motion,axis),4),
                              near=round(p.near,3) if p.near is not None else None,
                              last_w_up=round(p.key_up_ms,1) if p.key_up_ms is not None else None,w_held=p.w_held,
                              server=server.press(p,client,hits)))
        if dot(motion,axis)<=.04:
            continue
        if p.kind=='brake' and p.victim:
            options.append(Cut(ns,motion,.6,1.,'remove brake'))
        elif p.kind=='sprint_off':
            options.append(Cut(ns,motion,1.,.6,'sprint on at click'))
        elif p.kind=='timer' and p.victim and local['sprinting']:
            options.append(Cut(ns,motion,1.,.6,'timer clear at selected click'))
        elif p.kind=='miss' and local['sprinting'] and p.near is not None and p.near < reach_limit-.12:
            options.append(Cut(ns,motion,1.,.6,'aim correction if the click selects'))
        elif p.kind=='miss' and not local['sprinting'] and p.near is not None and p.near < reach_limit-.12:
            options.append(Cut(ns,motion,1.,.6,'aim and sprint both fixed'))
        elif p.kind=='timer' and p.victim:
            options.append(Cut(ns,motion,1.,.6,'timer and sprint both fixed'))
    summary['presses'] = press_lines

    # A rendered ray is evidence at one time. Prefer the earliest demonstrated
    # opening per head. Avoid making hypothetical extra clicks in a head that
    # already had a click or brake.
    occupied = {bisect.bisect_right(client.move_ns,stamp(p.event)) for p in presses}
    inserted = set()
    for s in client.samples[bisect.bisect_right(client.sample_ns,receipt):bisect.bisect_left(client.sample_ns,cutoff)]:
        t, d = stamp(s), s['data']
        if d.get('ray',{}).get('entity')!=client.ids.get(attacker.name) or d['left_click_counter'] or not d['self']['sprinting']:
            continue
        head = bisect.bisect_right(client.move_ns,t)
        motion = xz(d['self']['motion'])
        if head in occupied or head in inserted or dot(motion,axis)<=.04:
            continue
        inserted.add(head)
        options.append(Cut(t,motion,1.,.6,'click at recorded aimed sprint sample'))
    summary['candidates'] = []
    next_axis = None
    if next_record:
        next_velocity = xz(next_record[1]['velocity'])
        if math.hypot(*next_velocity) > .001:
            next_axis = mul(next_velocity, 1/math.hypot(*next_velocity))

    def apply(edits, label, ns, impulse_shift=ZERO, edited_speed=None):
        edited_times = {c.ns for c in edits}
        pieces = [c for c in factual_cuts if c.ns not in edited_times] + list(edits)
        replay = branch(moves,pieces,floor,axis,checks,
                        overwrite=next_push if next_record else None,impulse_shift=impulse_shift)
        pose = frame_at(client,next_push) if next_axis else None
        store = summarize_branch(replay,receipt,cutoff,next_push,axis,floor,pose,next_axis,return_check)
        store.update(edit=label,at_ms=round((ns-receipt)/1e6,1))
        if edits:
            store['speed'] = round(dot(edits[0].motion,axis),4)
        if edited_speed is not None:
            store['edited_speed'] = round(edited_speed,4)
        summary['candidates'].append(store)

    for option in sorted(options,key=lambda c:c.ns):
        apply([option],option.label,option.ns)
    removed = [o for o in options if o.label=='remove brake']
    if len(removed)>1:
        apply(removed,'remove all observed early brakes',removed[0].ns)
    inserted = sorted((o for o in options if o.label.startswith('click at')), key=lambda c:c.ns)
    sprint_off = next((o for o in options if o.label=='sprint on at click'),None)
    if inserted and sprint_off and inserted[0].ns<sprint_off.ns:
        apply([inserted[0],sprint_off],'early sampled click plus sprint on at later click',inserted[0].ns)
    impulse_edit = alternate_impulse(hit,physical,report['combat_profile'])
    if impulse_edit is not None:
        apply([],'same hit without sprint bonus' if physical['pre_attack_server_sprint'] else
              'same hit with sprint bonus',receipt,
              sub(quantized(impulse_edit),quantized(xz(impulse))), math.hypot(*impulse_edit))
    return summary


def quantized(v):
    return tuple(int(x*8000)/8000 for x in v)


def alternate_impulse(hit, physical, profile):
    # v4 uses the same blended direction and same vertical impulse when the
    # extra level changes with sprint. Qualify the archived amplitude first.
    if profile.get('knockback_model')!='mmc_recorded_range_v4' or profile['sprint_vertical']!=0:
        return None
    extra = physical['extra_levels']
    sprint = physical['pre_attack_server_sprint']
    if extra!=int(sprint):
        return None
    a, b = hit['attacker_state'], hit['target_state']
    length = math.hypot(a['posX']-b['posX'],a['posZ']-b['posZ'])

    def magnitude(level):
        loss = min(.4,.35*max(0,length-3)) if level else 0.
        return (profile['mmc_base_horizontal']+level*profile['mmc_sprint_horizontal'])*(1-loss)

    base = magnitude(extra)
    if base<=0.:
        return None
    original = xz(physical['velocity'])
    return mul(original,magnitude(1-extra)/base)


def summarize_branch(result, receipt, cutoff, next_push, axis, floor, next_pose, next_axis, return_check):
    rows = result.pop('rows')
    local = [r for r in rows if r['ns']<=cutoff]
    actual_peak = max([0.]+[r['actual_progress'] for r in local])
    edited_peak = max([0.]+[r['edited_progress'] for r in local])
    result['saving'] = round(actual_peak-edited_peak,3) if local else None
    result['measured_steps'] = len(local)
    prior = [r for r in rows if r['ns']<next_push]
    if prior and next_pose and rows[-1]['ns']>next_push:
        shift = prior[-1]['shift']
        origin = xz(next_pose)
        result['at_next'] = dict(inward=round(-dot(shift,axis),3),shift=shift,
                                 actual_margin=round(floor.margin(origin,next_axis),3),
                                 edited_margin=round(floor.margin(add(origin,shift),next_axis),3))
        following = [r for r in rows if r['ns']>next_push][:6]
        if following:
            result['at_next']['following_moves'] = len(following)
            result['at_next']['observed_followup_peak'] = round(max(
                [0.] + [dot(sub(r['actual'],origin),next_axis) for r in following]),3)
            result['at_next']['edited_followup_peak'] = round(max(
                [0.] + [dot(sub(r['edited'],add(origin,shift)),next_axis) for r in following]),3)
    if return_check:
        sample, ns, opponent, limit = return_check
        nearby = [r['shift'] for r in rows if ns-150e6 <= r['ns'] < ns]
        if len(nearby)>=2 and rows[-1]['ns']>=ns:
            distances = [nearest(sample['data'],opponent,mul(shift,-1)) for shift in nearby]
            actual = nearest(sample['data'],opponent)
            if actual is not None:
                result['opponent_press_geometry'] = dict(ms_since_origin=round((ns-receipt)/1e6,1),actual=round(actual,3),
                    range=[round(min(distances),3),round(max(distances),3)],reach=limit)
    if result['floor_difference']:
        cross = result['floor_difference']
        cross['at_ms'] = round((cross.pop('ns')-receipt)/1e6,1)
    if result['landing_divergence_ns'] is not None:
        result['landing_divergence_ms'] = round((result['landing_divergence_ns']-receipt)/1e6,1)
        last = rows[-1]
        result['landing'] = dict(actual=last['actual'],edited=last['edited'],
                                 actual_y=last['actual_y'],actual_margin=round(floor.margin(last['actual'],axis),3),
                                 edited_margin=round(floor.margin(last['edited'],axis),3),
                                 actual_support=last['factual_support'],edited_support=last['edited_support'])
    del result['landing_divergence_ns']
    return result


def next_attack_view(hit, attacker, server, hits, send_ns, reach):
    if send_ns is None:
        return None
    for press in reversed(attacker.presses):
        ns = stamp(press.event)
        if ns>send_ns or not press.victim or press.counter:
            continue
        if ns<send_ns-400e6:
            break
        link = server.press(press,attacker,hits)
        if link and link['accepts']==[hit['hit_index']]:
            sample = attacker.sample(ns)
            if sample and nearest(sample['data'],attacker.enemy) is not None:
                return sample, stamp(sample), attacker.enemy, reach
    return None


def evidence(paths, verify):
    chosen = paths['sources']
    if verify:
        for name, digest in chosen.items():
            with (ROOT/name).open('rb') as stream:
                if hashlib.file_digest(stream,'sha256').hexdigest()!=digest:
                    raise ValueError(f"Archive changed: {name}")
    result = json.loads(next((ROOT/p).read_text() for p in chosen if '/results/' in p))
    arena = result.get('sumo_arena',{})
    if arena.get('shape')!='mmc_recorded_13_v1' or arena.get('top_y')!=12. or result['weapon']!='fist':
        raise ValueError("Expected a bare-fist Sumo match on the recorded 13×13 MMC platform")
    human = next(s for s in 'ab' if result['policy_'+s]=='human')
    raw = next(ROOT/p for p in chosen if '/replays/' in p)
    timeline = list(read(raw))
    other = {'a':'b','b':'a'}
    clients = {s:Client(next(ROOT/p for p in chosen if '/'+s+'-client-' in p),result[s],result[other[s]]) for s in 'ab'}
    server = Server(next(ROOT/p for p in chosen if '/server-' in p))
    floor = Floor(4096*(result['arena']+1),.3000000119)
    hits = [r for r in timeline if r['type']=='hit']
    launches, pending = [], None
    for row in timeline:
        if row['type']=='hit':
            pending = row if not row['damage_upgrade'] and not row['rod'] else None
        if row['type']=='mmc_knockback' and pending and pending['target']==row['target'] and 0<=row['time']-pending['time']<=8:
            client = clients[next(s for s in 'ab' if result[s]==row['target'])]
            receipt, send = server.launch(pending,row['velocity'],client)
            launches.append((pending,row,receipt,send))
            pending = None
    records = []
    for launch in launches:
        s = next(seat for seat in 'ab' if result[seat]==launch[0]['target'])
        records.append(episode(launch,s,clients[s],clients[other[s]],server,result,floor,hits,
                               [p for p in launches if p[0]['target']==result[s]]))
    loser = next(s for s in 'ab' if result[s]!=result['winner'])
    ticks = [r for r in timeline if r['type']=='tick' and result['start']<=r['time']<=result['finish']]
    outside = []
    for r in reversed(ticks):
        if floor.covers((r[loser]['posX'],r[loser]['posZ'])):
            break
        outside.append(r)
    tail_ms = max(result['start'],result['finish']-1300)
    server_tail = []
    for event in ticks:
        if event['time'] < tail_ms:
            continue
        bodies = {}
        for side in 'ab':
            state = event[side]
            pos = [state[k] for k in ('posX','posY','posZ')]
            bodies[side] = dict(pos=vec(pos),supported=floor.covers(xz(pos)),sprint=state['sprint'])
        server_tail.append(dict(ms=event['time']-result['start'],**bodies))
    return dict(id=result['id'], human=human, opponent=result['policy_'+other[human]],
                outcome=paths['outcome'],duration=paths['duration_ms'],loser=loser,profile=result['combat_profile'],
                n_hit=len(hits), n_fresh=len(launches), server_tail=server_tail,last_server_support_ms=(
                    ticks[-len(outside)-1]['time']-result['start'] if len(ticks)>len(outside) else None),
                first_final_server_no_support_ms=outside[-1]['time']-result['start'] if outside else None,
                end=[(r['time']-result['start'],[vec([r[s]['posX'],r[s]['posY'],r[s]['posZ']]) for s in 'ab']) for r in ticks[-9:]],
                launches=records)


def compact(data, hit_ids):
    print(data['id'],data['opponent'],data['outcome'],data['duration'],
          'hits',data['n_fresh'],'support countdown',data['last_server_support_ms'],data['first_final_server_no_support_ms'])
    print("hit time victim speed floor slide early moves | actual clicks (ms, type, near, speed) | branches")
    for row in data['launches']:
        if hit_ids and row['i'] not in hit_ids:
            continue
        print('H{:<3} {:>5.3f}s {} {:.3f} F{:>5} S{:>5} {} '.format(row['i'],row['t']/1000,row['victim'],row['speed'],
              row.get('client_margin',row['server_margin']),row.get('slide','?'),row.get('first_n','-')),end='')
        details = ' '.join(f"{p['ms']:g}:{p['kind'][:4]}/{p['near']}/{p['outward']:.2f}"+('!' if p['last_w_up'] is not None else '')
                           for p in row.get('presses', []))
        print('|', details,end=' | ')
        for c in row.get('candidates',[]):
            print(f"{c['at_ms']:g}:{c['edit']} {c['saving']} ({c['measured_steps']}m)"+
                  (f" next+{c['at_next']['inward']}" if c.get('at_next') else '')+
                  (f" landing@{c['landing_divergence_ms']}" if c.get('landing') else '')+
                  (f" reach{c['checks'][:1]}" if c.get('checks') else ''),end='; ')
        print()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('match', nargs='*', help='Archived match IDs. With no IDs, inspect the cohort.')
    parser.add_argument('--cohort',type=Path,default=HERE/'cohort.json')
    parser.add_argument('--out',type=Path,help='Write full evidence as JSON files.')
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument('--hit',nargs='+',type=int,help='Print selected hit indices.')
    scope.add_argument('--finish',action='store_true',help='Print the exchanges immediately around the final floor loss.')
    parser.add_argument('--from-json',type=Path,help='Print evidence already extracted into this directory.')
    args = parser.parse_args()
    if args.out:
        args.out.mkdir(parents=True,exist_ok=True)
    cohort = json.loads(args.cohort.read_text())
    frozen = {r['id']:r for group in cohort.values() if isinstance(group,list) for r in group}
    for mid in args.match or frozen:
        if not (mid.startswith('m') and mid[1:].isdigit()):
            parser.error(f'Invalid archived match ID: {mid}')
        if args.from_json:
            values = json.loads((args.from_json/(mid+'.json')).read_text())
        else:
            if mid in frozen:
                values = evidence(frozen[mid],True)
            else:
                report = ROOT/'runtime/results'/f'{mid}.json'
                meta = json.loads(report.read_text())
                human = next(s for s in 'ab' if meta['policy_'+s]=='human')
                paths = [report, ROOT/'runtime/replays'/f'{mid}.jsonl', *(ROOT/'runtime/pov'/mid).glob('*-events.jsonl.gz')]
                if len(paths)!=5:
                    parser.error('Choose a match with complete archives or pass a manifest')
                values = evidence(dict(sources={str(p.relative_to(ROOT)):None for p in paths},
                                       outcome='won' if meta['winner']==meta[human] else 'lost',
                                       duration_ms=meta['finish']-meta['start']),False)
        if args.out:
            (args.out/(mid+'.json')).write_text(json.dumps(values,indent=2)+'\n')
        hit_ids = args.hit
        if args.finish:
            edge = values['first_final_server_no_support_ms'] or values['duration']
            before = {s:[h['i'] for h in values['launches'] if h['victim']==s and h['t']<=edge] for s in 'ab'}
            after = [h['i'] for h in values['launches'] if h['t']>edge]
            hit_ids = before[values['loser']][-4:] + before['b' if values['loser']=='a' else 'a'][-2:] + after[:2]
        compact(values,hit_ids)


if __name__=='__main__':
    main()
