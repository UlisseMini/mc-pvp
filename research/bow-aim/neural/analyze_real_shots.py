"""Extract and analyze EVERY real bow shot the user actually fired across all Hypixel captures!
Evaluates:
  - User's own raw aim (and actual server hit confirmation via S29 random.successful_hit / target hurt)
  - Linear, Shared (ridge), BowNeural v2 (full aim AND capped 3-deg / 8-deg nudge!)
  - Ping-lead sensitivity (0ms, 40ms, 80ms, 120ms) on REAL Hypixel server shots!
  - Concrete failure-mode diagnosis of every miss so we know exactly how to improve next!
"""
import argparse
import base64
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
import gzip
import json
import math
from pathlib import Path
import re
import time

import numpy as np
import torch
from torch.nn import functional as F

from extract_v2 import (
    CAPTURES, PART_RE, LiveWorld, pack_flags, compute_relational,
)
from train_v2 import (
    BowNeuralNetV2, HORIZONS, MAX_H, NUM_MODES,
    build_features_v2, contiguous_runs,
)
from eval_v2 import (
    interp_modes_v2, solve_aim_v2, shot_from_angles, shot_prob_v2,
)
from eval_and_calibrate import (
    DRAG, GRAVITY, OFFSET, SPREAD,
    travel, fall, intercept, intersects_box, simulate_arrow_hit,
    load_bow_motion_weights, linear_baseline_path, bow_motion_shared_path,
    apply_calibration,
)


def bow_speed(charge_ticks):
    draw = charge_ticks / 20.0
    power = (draw * draw + 2.0 * draw) / 3.0
    return 0.0 if power < 0.1 else 3.0 * min(1.0, power)


def wrap_deg(a):
    a = a % 360.0
    return a - 360.0 if a >= 180.0 else a


def angle_diff_deg(y1, p1, y2, p2):
    return math.hypot(wrap_deg(y1 - y2), p1 - p2)


def clamp_nudge_shot(sx, eye_y, sz, speed, raw_yaw_deg, raw_pitch_deg, ideal_shot, limit_deg):
    if ideal_shot is None:
        return None
    iy = math.degrees(ideal_shot[0])
    ip = math.degrees(ideal_shot[1])
    dy = wrap_deg(iy - raw_yaw_deg)
    dp = ip - raw_pitch_deg
    mag = math.hypot(dy, dp)
    if mag > limit_deg and mag > 1e-9:
        scale = limit_deg / mag
        dy *= scale
        dp *= scale
    ny = math.radians(raw_yaw_deg + dy)
    np_ = math.radians(max(-90.0, min(90.0, raw_pitch_deg + dp)))
    return shot_from_angles(sx, eye_y, sz, speed, ny, np_, ideal_shot[2])


def extract_shots_from_session(job):
    session_name, part_paths = job
    world = LiveWorld()
    # Keep rolling 160-tick history of all remote players + pending shots waiting for 45 future ticks
    tracks = {}  # uid -> dict of lists (trimmed periodically)
    pending_shots = []
    finished_shots = []

    prev_using_bow = False
    prev_charge = 0
    prev_self_yaw = 0.0
    prev_self_pitch = 0.0
    prev_self_pos = None
    prev_self_eye = 1.62
    prev_tick = -999
    bow_release_events = {}  # tick -> bow_aim_release dict

    for part in part_paths:
        with gzip.open(part, 'rb') as stream:
            for line in stream:
                if line.startswith(b'{"type":"packet_wire"'):
                    head = line[:420]
                    if b'"direction":"in"' not in head:
                        continue
                    if b'S21PacketChunkData' in head or b'S26PacketMapChunkBulk' in head:
                        row = json.loads(line)
                        b64 = row['data'].get('base64')
                        if b64:
                            world.apply_chunk_packet(base64.b64decode(b64))
                    elif b'S22PacketMultiBlockChange' in head or b'S23PacketBlockChange' in head:
                        row = json.loads(line)
                        b64 = row['data'].get('base64')
                        if b64:
                            world.apply_block_packet(base64.b64decode(b64))
                    elif b'S01PacketJoinGame' in head or b'S07PacketRespawn' in head:
                        world.clear()
                        tracks.clear()
                        pending_shots.clear()
                        prev_using_bow = False
                        prev_charge = 0
                    elif b'S29PacketSoundEffect' in head:
                        # Check for arrow ding sound ("random.successful_hit")
                        row = json.loads(line)
                        b64 = row['data'].get('base64')
                        if b64:
                            raw = base64.b64decode(b64)
                            if b'successful_hit' in raw:
                                ms = int(row['time_ms'])
                                for ps in pending_shots:
                                    if 40 <= ms - ps['time_ms'] <= 2400:
                                        ps['server_ding'] = True
                elif line.startswith(b'{"type":"bow_aim_release"'):
                    row = json.loads(line)
                    bow_release_events[int(row.get('client_tick', -1))] = row.get('data', {})
                elif line.startswith(b'{"type":"tick_end"'):
                    row = json.loads(line)
                    tick = int(row['client_tick'])
                    ms = int(row['time_ms'])
                    state = row['data']
                    own = state.get('self') or {}
                    own_uid = own.get('uuid', '')
                    own_pos = own.get('pos')
                    if own_pos is None:
                        continue
                    opx, opy, opz = float(own_pos[0]), float(own_pos[1]), float(own_pos[2])
                    own_eye = float(own.get('eye_height', 1.62))
                    own_yaw = float(own.get('yaw', 0.0))
                    own_pitch = float(own.get('pitch', 0.0))
                    held_id = int(own.get('held_item_id') or 0)
                    using_bow = bool(own.get('using_item')) and held_id == 261
                    charge = int(own.get('use_elapsed_ticks') or 0)
                    if using_bow and charge == 0:
                        ut = int(own.get('use_ticks') or 0)
                        if ut > 0:
                            charge = max(1, 72000 - ut)
                        elif prev_using_bow:
                            charge = prev_charge + 1
                        else:
                            charge = 1

                    # Gather living players
                    valid_players = []
                    if not own.get('noClip') and own.get('health', 20) > 0:
                        valid_players.append((
                            '__self__', opx, opy, opz,
                            float(own.get('rotationYawHead', own_yaw)), own_pitch,
                            pack_flags(own), (opx, opy, opz), True,
                        ))
                    for e in state.get('entities', []):
                        uid = e.get('uuid', '')
                        if (len(uid) != 36 or uid[14] != '4' or uid == own_uid
                                or 'pos' not in e or e.get('noClip') or e.get('health', 20) <= 0):
                            continue
                        px, py, pz = float(e['pos'][0]), float(e['pos'][1]), float(e['pos'][2])
                        spx, spy, spz = e.get('serverPosX'), e.get('serverPosY'), e.get('serverPosZ')
                        if spx is not None and spy is not None and spz is not None and (spx != 0 or spy != 0 or spz != 0):
                            sx, sy, sz = spx / 32.0, spy / 32.0, spz / 32.0
                            if abs(sx - px) > 3.5 or abs(sy - py) > 3.5 or abs(sz - pz) > 3.5:
                                sx, sy, sz = px, py, pz
                        else:
                            sx, sy, sz = px, py, pz
                        valid_players.append((
                            uid, px, py, pz,
                            float(e.get('rotationYawHead', e.get('yaw', 0.0))),
                            float(e.get('pitch', 0.0)),
                            pack_flags(e), (sx, sy, sz), False,
                        ))

                    # Update rolling tracks for remote players
                    present_uids = set()
                    for idx_p, vp in enumerate(valid_players):
                        uid, px, py, pz, yaw, pitch, fl, spos, is_self = vp
                        if is_self:
                            continue
                        present_uids.add(uid)
                        tr = tracks.get(uid)
                        if tr is None or (tr['tick'] and (tick - tr['tick'][-1] <= 0 or tick - tr['tick'][-1] > 3)):
                            tr = {'tick': [], 'pos': [], 'spos': [], 'rot': [], 'flags': [], 'rel': []}
                            tracks[uid] = tr
                        tr['tick'].append(tick)
                        tr['pos'].append((px, py, pz))
                        tr['spos'].append(spos)
                        tr['rot'].append((yaw, pitch))
                        tr['flags'].append(fl)
                        tr['rel'].append(compute_relational(idx_p, valid_players))
                        if len(tr['tick']) > 200:
                            for k in tr:
                                del tr[k][:40]

                    # Detect bow release! (was drawing bow >= 4 ticks on previous tick, now stopped)
                    released = (prev_using_bow and not using_bow and prev_charge >= 4
                                and tick == prev_tick + 1 and prev_self_pos is not None)
                    if released:
                        speed = bow_speed(prev_charge)
                        # Check if a bow_aim_release event logged raw_yaw/raw_pitch
                        ev = bow_release_events.get(tick) or bow_release_events.get(prev_tick)
                        syaw = float(ev['raw_yaw']) if (ev and ev.get('raw_yaw') is not None) else prev_self_yaw
                        spitch = float(ev['raw_pitch']) if (ev and ev.get('raw_pitch') is not None) else prev_self_pitch
                        # Find the remote target the user was aiming closest to (within 18 deg and 4..62 blocks)
                        best_uid = None
                        best_ang = 18.0
                        best_dist = 999.0
                        for vp in valid_players:
                            if vp[8]:  # self
                                continue
                            uid, px, py, pz = vp[0], vp[1], vp[2], vp[3]
                            tr = tracks.get(uid)
                            if tr is None or len(tr['tick']) < 6:
                                continue
                            dx = px - prev_self_pos[0]
                            dy = (py + 0.95) - (prev_self_pos[1] + prev_self_eye)
                            dz = pz - prev_self_pos[2]
                            dist = math.sqrt(dx * dx + dy * dy + dz * dz)
                            if not (4.0 <= dist <= 62.0):
                                continue
                            if not world.ray_clear(prev_self_pos[0], prev_self_pos[1] + prev_self_eye, prev_self_pos[2],
                                                   px, py + 1.1, pz):
                                continue
                            bearing = math.degrees(math.atan2(-dx, dz))
                            elev = -math.degrees(math.atan2(dy, max(0.1, math.hypot(dx, dz))))
                            ang = angle_diff_deg(syaw, spitch, bearing, elev)
                            if ang < best_ang:
                                best_ang = ang
                                best_uid = uid
                                best_dist = dist
                        if best_uid is not None:
                            tr = tracks[best_uid]
                            p_now = tr['pos'][-1]
                            bx, by, bz = int(math.floor(p_now[0])), int(math.floor(p_now[1])), int(math.floor(p_now[2]))
                            fine, coarse = world.sample_multiscale_bits(bx, by, bz)
                            pending_shots.append({
                                'session': session_name[:25],
                                'tick': tick,
                                'time_ms': ms,
                                'uid': best_uid,
                                'charge': prev_charge,
                                'speed': speed,
                                'shooter_pos': (prev_self_pos[0], prev_self_pos[1] + prev_self_eye, prev_self_pos[2]),
                                'user_yaw': syaw,
                                'user_pitch': spitch,
                                'aim_err_deg': best_ang,
                                'dist': best_dist,
                                'hist_tick': list(tr['tick']),
                                'hist_pos': list(tr['pos']),
                                'hist_spos': list(tr['spos']),
                                'hist_rot': list(tr['rot']),
                                'hist_flags': list(tr['flags']),
                                'hist_rel': list(tr['rel']),
                                'fine': fine,
                                'coarse': coarse,
                                'future_pos': [p_now],
                                'future_spos': [tr['spos'][-1]],
                                'future_hurt': [],
                                'server_ding': False,
                                'was_mod_aim': bool(ev),
                            })

                    # Advance pending shots with observed future target positions
                    still_pending = []
                    for ps in pending_shots:
                        dt = tick - ps['tick']
                        if dt == 0:
                            still_pending.append(ps)
                            continue
                        tr = tracks.get(ps['uid'])
                        if tr is not None and tr['tick'] and tr['tick'][-1] == tick:
                            ps['future_pos'].append(tr['pos'][-1])
                            ps['future_spos'].append(tr['spos'][-1])
                            ps['future_hurt'].append(1 if (tr['flags'][-1] & 32) else 0)
                        else:
                            # Target disappeared / died / went out of view
                            if len(ps['future_pos']) >= 18:
                                finished_shots.append(ps)
                            continue
                        if len(ps['future_pos']) >= MAX_H + 5:
                            finished_shots.append(ps)
                        else:
                            still_pending.append(ps)
                    pending_shots = still_pending

                    prev_using_bow = using_bow
                    prev_charge = charge if using_bow else 0
                    prev_self_yaw = own_yaw
                    prev_self_pitch = own_pitch
                    prev_self_pos = (opx, opy, opz)
                    prev_self_eye = own_eye
                    prev_tick = tick

    return finished_shots


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--model-dir', type=Path, default=Path('runtime/bow-aim/neural-model-v2'))
    p.add_argument('--workers', type=int, default=6)
    args = p.parse_args()

    groups = defaultdict(dict)
    for f in CAPTURES.glob('a-client-*-events*.jsonl.gz'):
        m = PART_RE.search(f.name)
        if m:
            groups[f.name[:m.start()]][int(m[1] or '1')] = f

    jobs = []
    for name, parts in sorted(groups.items()):
        if 1 not in parts:
            continue
        is_hyp = False
        try:
            with gzip.open(parts[1], 'rb') as s:
                for i, line in enumerate(s):
                    if i > 128:
                        break
                    if b'"type":"capture_start"' in line and b'"server_host":"hypixel.net"' in line:
                        is_hyp = True
                        break
        except Exception:
            pass
        if is_hyp:
            jobs.append((name, [parts[k] for k in sorted(parts)]))

    t0 = time.monotonic()
    print(f'Scanning {len(jobs)} Hypixel sessions for EVERY real bow shot you fired...', flush=True)
    shots = []
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        for res in pool.map(extract_shots_from_session, jobs):
            shots.extend(res)
    print(f'Extracted {len(shots)} complete real bow shots with full target futures in {time.monotonic()-t0:.1f}s!', flush=True)
    if not shots:
        return

    # Load BowNeural v2 winner + old ridge weights
    device = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
    ckpt = torch.load(args.model_dir / 'winner.pt', map_location='cpu', weights_only=True)
    model = BowNeuralNetV2(NUM_MODES).to(device)
    model.load_state_dict(ckpt['state_dict'])
    model.eval()
    calib = ckpt['calib']
    old_weights = load_bow_motion_weights()

    # Evaluate every shot across methods & ping leads!
    arms = ['user_raw', 'linear', 'shared', 'v2_nudge3', 'v2_nudge8', 'v2_full_0ms', 'v2_full_60ms', 'v2_full_100ms']
    hits = {a: 0 for a in arms}
    hits_by_dist = {a: defaultdict(lambda: [0, 0]) for a in arms}
    server_dings = sum(1 for s in shots if s['server_ding'])
    charge_buckets = Counter()
    miss_reasons = Counter()
    miss_examples = []

    for idx_s, s in enumerate(shots):
        pos = np.asarray(s['hist_pos'], dtype=np.float32)
        spos = np.asarray(s['hist_spos'], dtype=np.float32)
        rot = np.asarray(s['hist_rot'], dtype=np.float32)
        flags = np.asarray(s['hist_flags'], dtype=np.uint8)
        rel = np.asarray(s['hist_rel'], dtype=np.float32)
        ticks = np.asarray(s['hist_tick'], dtype=np.int32)
        past, _ = contiguous_runs(ticks, pos)
        i = len(ticks) - 1
        plen = int(past[i])
        if plen < 4:
            continue

        # Build dummy arrays at index i for vox
        fine_arr = np.zeros((len(ticks), len(s['fine'])), dtype=np.uint8)
        coarse_arr = np.zeros((len(ticks), len(s['coarse'])), dtype=np.uint8)
        fine_arr[i] = s['fine']
        coarse_arr[i] = s['coarse']
        h, c, vf, vc, q = build_features_v2(pos, spos, rot, flags, rel, fine_arr, coarse_arr, i, plen)
        with torch.no_grad():
            lo, me, ls, rh = model(torch.from_numpy(h[None]).to(device),
                                   torch.from_numpy(c[None]).to(device),
                                   torch.from_numpy(vf[None]).to(device),
                                   torch.from_numpy(vc[None]).to(device))
            pi = F.softmax(lo, dim=-1)[0].cpu().numpy()
            means = me[0].cpu().numpy(); stds = ls.exp()[0].cpu().numpy(); rhos = rh[0].cpu().numpy()

        p0 = pos[i].astype(np.float64)
        paths, std_p, rho_p = interp_modes_v2(p0, q, means, stds, rhos)
        lin_p = linear_baseline_path(pos, i, plen)
        sh_p = bow_motion_shared_path(pos, i, plen, old_weights)

        sx, eye_y, sz = s['shooter_pos']
        spd = s['speed']
        charge_buckets['full (>=20t)' if s['charge'] >= 20 else 'partial (<20t)'] += 1

        # Candidate shots
        sh_user = shot_from_angles(sx, eye_y, sz, spd, math.radians(s['user_yaw']), math.radians(s['user_pitch']), MAX_H)
        sh_lin = intercept(sx, eye_y, sz, spd, lin_p, 0.90)
        sh_sh = intercept(sx, eye_y, sz, spd, sh_p, 0.90)
        sh_v2_0, p_v2_0 = solve_aim_v2(sx, eye_y, sz, spd, pi, paths, std_p, rho_p, 0.90, lead_shift=0.0, refine=True)
        sh_v2_60, _ = solve_aim_v2(sx, eye_y, sz, spd, pi, paths, std_p, rho_p, 0.90, lead_shift=1.2, refine=True)
        sh_v2_100, _ = solve_aim_v2(sx, eye_y, sz, spd, pi, paths, std_p, rho_p, 0.90, lead_shift=2.0, refine=True)
        sh_n3 = clamp_nudge_shot(sx, eye_y, sz, spd, s['user_yaw'], s['user_pitch'], sh_v2_0, 3.0)
        sh_n8 = clamp_nudge_shot(sx, eye_y, sz, spd, s['user_yaw'], s['user_pitch'], sh_v2_0, 8.0)

        fut_truth = np.asarray(s['future_spos'], dtype=np.float32)
        dist = s['dist']
        dbin = 'short (<16b)' if dist < 16 else ('mid (16-32b)' if dist < 32 else 'long (32b+)')
        # Average hit probability over 16 native arrow-spread draws so results have zero RNG noise!
        for name, cand in (
            ('user_raw', sh_user), ('linear', sh_lin), ('shared', sh_sh),
            ('v2_nudge3', sh_n3), ('v2_nudge8', sh_n8),
            ('v2_full_0ms', sh_v2_0), ('v2_full_60ms', sh_v2_60), ('v2_full_100ms', sh_v2_100),
        ):
            # Shift truth by ping for ping-shifted arms? Wait: compare ALL arms against BOTH 0-tick and 1-tick truth!
            h_cnt = sum(1 for k in range(16)
                        if simulate_arrow_hit(cand, fut_truth, np.random.default_rng(idx_s * 997 + k)))
            prob_hit = h_cnt / 16.0
            hits[name] += prob_hit
            hits_by_dist[name][dbin][0] += prob_hit
            hits_by_dist[name][dbin][1] += 1

        # Diagnose misses of v2_full_0ms!
        if sh_v2_0 is not None:
            h_v2 = sum(1 for k in range(16)
                       if simulate_arrow_hit(sh_v2_0, fut_truth, np.random.default_rng(idx_s * 997 + k))) / 16.0
            if h_v2 < 0.25:
                t_hit = min(len(fut_truth) - 1, max(1, int(round(sh_v2_0[2]))))
                tr = travel(sh_v2_0[2])
                ax = sh_v2_0[3] + sh_v2_0[6] * tr
                ay = sh_v2_0[4] + sh_v2_0[7] * tr - fall(sh_v2_0[2])
                az = sh_v2_0[5] + sh_v2_0[8] * tr
                act = fut_truth[t_hit]
                err_xz = math.hypot(ax - act[0], az - act[2])
                err_y = (ay - (act[1] + 0.90))
                # Classify WHY it missed!
                if abs(err_y) > 1.15 and err_xz < 1.2:
                    reason = 'vertical_jump_or_drop'
                elif s['charge'] < 20:
                    reason = 'partial_draw_slow_arrow'
                elif any(s['future_hurt'][:t_hit]):
                    reason = 'target_took_kb_midflight'
                else:
                    # Did target reverse direction mid-flight?
                    v_pre = pos[i] - pos[max(0, i - 5)]
                    v_post = fut_truth[t_hit] - fut_truth[0]
                    dot_v = float(v_pre[0] * v_post[0] + v_pre[2] * v_post[2])
                    if dot_v < -0.2:
                        reason = 'midflight_reversal_or_dodge'
                    elif float(np.hypot(v_post[0], v_post[2])) < 0.4 and float(np.hypot(v_pre[0], v_pre[2])) > 0.5:
                        reason = 'sudden_stop_midflight'
                    elif float(np.hypot(v_post[0], v_post[2])) > 1.5 and float(np.hypot(v_pre[0], v_pre[2])) < 0.3:
                        reason = 'sudden_sprint_from_standstill'
                    elif err_xz >= 1.2:
                        reason = 'lateral_curve_or_strafe'
                    else:
                        reason = 'near_miss_spread'
                miss_reasons[reason] += 1
                if len(miss_examples) < 12:
                    miss_examples.append({
                        'dist': round(dist, 1), 'charge': s['charge'], 't_hit': round(sh_v2_0[2], 1),
                        'pred_p': round(apply_calibration(p_v2_0, calib), 2),
                        'err_xz': round(err_xz, 2), 'err_y': round(err_y, 2), 'reason': reason,
                    })

    n_eval = sum(v[1] for v in hits_by_dist['v2_full_0ms'].values())
    print(f'\n=== REAL REPLAY BOW SHOTS ANALYSIS ({n_eval} valid shots, {server_dings} actual server dings = {100*server_dings/max(1,len(shots)):.1f}%) ===')
    print('Charge breakdown:', dict(charge_buckets))
    print(f'{"Arm":15s} | {"Short <16b":>11s} | {"Mid 16-32b":>11s} | {"Long 32b+":>11s} | {"OVERALL":>10s}')
    print('-' * 70)
    for a in arms:
        cols = []
        for b in ('short (<16b)', 'mid (16-32b)', 'long (32b+)'):
            h, n = hits_by_dist[a][b]
            cols.append(f'{100*h/max(1,n):5.1f}% ({n:3d})')
        print(f'{a:15s} | ' + ' | '.join(cols) + f' | {100*hits[a]/max(1,n_eval):6.2f}%')

    print('\n=== FAILURE-MODE BREAKDOWN OF v2 MISSES ===')
    tot_m = sum(miss_reasons.values())
    for k, v in miss_reasons.most_common():
        print(f'  {k:32s}: {v:4d} ({100*v/max(1,tot_m):5.1f}%)')

    report = {
        'n_shots': n_eval,
        'server_dings': server_dings,
        'charge_buckets': dict(charge_buckets),
        'overall_hit_rate': {a: round(hits[a] / max(1, n_eval), 4) for a in arms},
        'by_dist': {a: {b: [round(v[0], 2), v[1]] for b, v in hits_by_dist[a].items()} for a in arms},
        'miss_reasons': dict(miss_reasons.most_common()),
        'sample_misses': miss_examples,
    }
    (args.model_dir / 'real-shots-analysis.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
