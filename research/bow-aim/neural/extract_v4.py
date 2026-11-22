"""Extract BowNeural v4 dataset from ALL Hypixel BedWars + SkyWars captures on disk:
- Strictly preserves the exact frozen sealed test split (game-09..12 + sw-1795338233539-19..23)
  so v4 is compared 100% apples-to-apples against v1, v2, and v3!
- Mines all newly recorded BedWars & SkyWars sessions into train/val!
- Dynamic block tracking from S23/S22 packets:
    * Fine 9x7x9 solid mask (71 bytes packed) + recently-placed mask (71 bytes packed, placed within 120t)
    * Wide 25x25 x 4 top-down map (int8): [floor_y_rel, head_wall, is_void, recently_placed]
    * Per-tick recent block-place vector (4 floats: count, dx, dy, dz of blocks placed near feet in last 8t)
    * Match block-placement event list (tick, x, y, z) so train_v4.py can compute exact future-block-placement
      targets (7x5x7 over next 1..24 ticks) on the fly without huge disk bloat!
"""
import argparse
import base64
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
import gzip
import json
import math
from pathlib import Path
import re
import sys
import time

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'bedwars-forecast'))
from extract import read_varint
from extract_terrain import read_chunks, block_updates

CAPTURES = Path.home() / 'Library/Application Support/minecraft/config/pvp-practice/captures'
COLOR_RE = re.compile(r'§.')
PART_RE = re.compile(r'(?:-part(\d+))?\.jsonl\.gz$')
SNAP_TIME_RE = re.compile(rb'"time_ms":(\d+)')

PASSABLE_IDS = {
    0, 6, 8, 9, 10, 11, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59,
    63, 65, 66, 68, 69, 75, 76, 77, 83, 90, 93, 94, 104, 105, 106, 111, 115, 119,
    127, 131, 132, 141, 142, 143, 147, 148, 149, 150, 157, 175, 176, 177,
}
SOLID_LUT = np.ones(4096, dtype=np.uint8)
SOLID_LUT[list(PASSABLE_IDS)] = 0
SWORD_IDS = {267, 268, 272, 276, 283}

SEALED_SW_IDS = {
    'sw-1795338233539-19', 'sw-1795338233539-20', 'sw-1795338233539-21',
    'sw-1795338233539-22', 'sw-1795338233539-23',
}
VAL_SW_IDS = {
    'sw-1795338233539-16', 'sw-1795338233539-17', 'sw-1795338233539-18',
}
RECENT_PLACE_HORIZON = 120  # 6 seconds


def chat_text(node):
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return ''.join(chat_text(x) for x in node)
    if isinstance(node, dict):
        return node.get('text', '') + chat_text(node.get('extra', []))
    return ''


def parse_chat_packet(raw):
    ln, pos = read_varint(raw, 1)
    payload = raw[pos:pos + ln].decode('utf-8', 'ignore')
    return COLOR_RE.sub('', chat_text(json.loads(payload))).strip()


class LiveWorldV4:
    def __init__(self):
        self.cols = {}
        self.recent_placed = {}  # (x, y, z) -> tick placed
        self.place_log = []      # list of (tick, x, y, z) for active match

    def clear(self):
        self.cols.clear()
        self.recent_placed.clear()
        self.place_log.clear()

    def reset_match_log(self):
        self.place_log = []

    def apply_chunk_packet(self, raw):
        try:
            for cx, cz, full, sections in read_chunks(raw):
                data = dict(sections)
                if full and not data:
                    self.cols.pop((cx, cz), None)
                    continue
                col = self.cols.get((cx, cz))
                if col is None:
                    if not any(np.any(sec) for sec in data.values()):
                        continue
                    col = np.zeros((256, 16, 16), dtype=np.uint8)
                    self.cols[(cx, cz)] = col
                elif full:
                    col.fill(0)
                for sec_idx, blocks in data.items():
                    base_y = sec_idx * 16
                    col[base_y:base_y + 16] = SOLID_LUT[blocks >> 4]
        except Exception:
            pass

    def apply_block_packet(self, raw, cur_tick, record_log=False):
        try:
            for x, y, z, state in block_updates(raw):
                if not (0 <= y < 256):
                    continue
                cx, bx = divmod(x, 16)
                cz, bz = divmod(z, 16)
                solid = int(SOLID_LUT[state >> 4])
                col = self.cols.get((cx, cz))
                prev = 0
                if col is None:
                    if not solid:
                        continue
                    col = np.zeros((256, 16, 16), dtype=np.uint8)
                    self.cols[(cx, cz)] = col
                else:
                    prev = int(col[y, bz, bx])
                col[y, bz, bx] = solid
                if solid == 1 and prev == 0:
                    self.recent_placed[(x, y, z)] = cur_tick
                    if record_log:
                        self.place_log.append((cur_tick, x, y, z))
                elif solid == 0 and prev == 1:
                    self.recent_placed.pop((x, y, z), None)
        except Exception:
            pass

    def prune_recent(self, cur_tick):
        if len(self.recent_placed) > 256:
            cutoff = cur_tick - RECENT_PLACE_HORIZON
            self.recent_placed = {k: t for k, t in self.recent_placed.items() if t >= cutoff}

    def sample_box(self, x0, x1, y0, y1, z0, z1):
        out = np.zeros((y1 - y0, z1 - z0, x1 - x0), dtype=np.uint8)
        sy0, sy1 = max(0, y0), min(256, y1)
        if sy0 >= sy1:
            return out
        py0, py1 = sy0 - y0, sy1 - y0
        cx0, cx1 = x0 >> 4, (x1 - 1) >> 4
        cz0, cz1 = z0 >> 4, (z1 - 1) >> 4
        for cx in range(cx0, cx1 + 1):
            wx0, wx1 = max(x0, cx * 16), min(x1, (cx + 1) * 16)
            lx0, lx1 = wx0 - cx * 16, wx1 - cx * 16
            px0, px1 = wx0 - x0, wx1 - x0
            for cz in range(cz0, cz1 + 1):
                col = self.cols.get((cx, cz))
                if col is None:
                    continue
                wz0, wz1 = max(z0, cz * 16), min(z1, (cz + 1) * 16)
                lz0, lz1 = wz0 - cz * 16, wz1 - cz * 16
                pz0, pz1 = wz0 - z0, wz1 - z0
                out[py0:py1, pz0:pz1, px0:px1] = col[sy0:sy1, lz0:lz1, lx0:lx1]
        return out

    def sample_v4_spatial(self, bx, by, bz, cur_tick):
        """Sample:
        1) fine 9x7x9 solid bits (71 bytes)
        2) fine 9x7x9 recently-placed bits (71 bytes, placed within 120 ticks)
        3) wide 25x25 x 4 top-down int8 map ([-12..+12] XZ):
             ch0: floor_y_rel in [-7..+4] encoded as int8 (-127 = no floor in [by-6..by+4])
             ch1: head_wall (0 or 100)
             ch2: is_void (0 or 100 if no solid in [by-12..by+4])
             ch3: recent_placed_strength (0..100, exp(-age/60)*100)
        4) recent_blk4 (4 floats): [count/4, mean_dx, mean_dy, mean_dz] of blocks placed within 3b in last 10t
        """
        # One box lookup for the 25x17x25 region: y in [by-12..by+5), z,x in [-12..+13)
        cube = self.sample_box(bx - 12, bx + 13, by - 12, by + 5, bz - 12, bz + 13)  # (17, 25, 25)
        # Fine 9x7x9: y in [by-3..by+4) => idx 9..16; z,x in [bx-4..bx+5) => idx 8..17
        fine_solid = cube[9:16, 8:17, 8:17]
        fine_placed = np.zeros((7, 9, 9), dtype=np.uint8)

        # Top-down 25x25 x 4
        # cube y-index 0 = by-12, ..., y-index 11 = by-1 (standing floor), ..., y-index 16 = by+4
        top = np.zeros((4, 25, 25), dtype=np.int8)
        # Any solid in entire 17-block vertical column -> not void
        any_solid = cube.any(axis=0)  # (25, 25)
        top[2] = np.where(any_solid, 0, 100).astype(np.int8)
        # Head wall at by+1 (idx 13) or by+2 (idx 14)
        head_w = (cube[13] | cube[14]) > 0
        top[1] = np.where(head_w, 100, 0).astype(np.int8)
        # Highest solid in [by-6..by+4] => cube indices [6..17) (11 slices)
        sub = cube[6:17]  # (11, 25, 25), index 0 = by-6, index 5 = by-1 (normal floor), index 10 = by+4
        has_floor = sub.any(axis=0)
        # argmax on reversed y gives highest solid
        hi_from_top = np.argmax(sub[::-1], axis=0)
        rel_y = (4 - hi_from_top)  # relative to by: +4 down to -6; normal floor by-1 gives -1 -> shift +1 => 0!
        # Encode (wy - (by - 1)) * 20 clamped to [-120, 100]; -127 if no floor
        floor_enc = np.where(has_floor, np.clip((rel_y + 1) * 20, -120, 100), -127).astype(np.int8)
        top[0] = floor_enc

        # Populate recently placed channels & local 10-tick placement summary
        cnt = 0
        sdx = sdy = sdz = 0.0
        cutoff = cur_tick - RECENT_PLACE_HORIZON
        for (px, py, pz), pt in self.recent_placed.items():
            if pt < cutoff:
                continue
            dx, dy, dz = px - bx, py - by, pz - bz
            if -12 <= dx <= 12 and -12 <= dz <= 12 and -4 <= dy <= 3:
                age = max(0, cur_tick - pt)
                strength = int(round(100.0 * math.exp(-age / 60.0)))
                tz, tx = dz + 12, dx + 12
                if strength > top[3, tz, tx]:
                    top[3, tz, tx] = strength
                if -4 <= dx <= 4 and -4 <= dz <= 4 and -3 <= dy <= 3:
                    fine_placed[dy + 3, dz + 4, dx + 4] = 1
                if age <= 10 and -3 <= dx <= 3 and -3 <= dz <= 3 and -2 <= dy <= 2:
                    cnt += 1
                    sdx += dx
                    sdy += dy
                    sdz += dz

        if cnt > 0:
            blk4 = (min(1.0, cnt / 4.0), sdx / cnt, sdy / cnt, sdz / cnt)
        else:
            blk4 = (0.0, 0.0, 0.0, 0.0)

        return (
            np.packbits(fine_solid.reshape(-1)),
            np.packbits(fine_placed.reshape(-1)),
            top,
            blk4,
        )

    def ray_clear(self, x0, y0, z0, x1, y1, z1):
        dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
        dist = (dx * dx + dy * dy + dz * dz) ** 0.5
        steps = max(2, int(dist * 3.0))
        inv = 1.0 / steps
        for s in range(1, steps):
            t = s * inv
            wx = int(math.floor(x0 + dx * t))
            wy = int(math.floor(y0 + dy * t))
            wz = int(math.floor(z0 + dz * t))
            if not (0 <= wy < 256):
                continue
            col = self.cols.get((wx >> 4, wz >> 4))
            if col is not None and col[wy, wz & 15, wx & 15]:
                return False
        return True

    def snapshot_solid_blocks(self, center_points, margin=28):
        if not center_points:
            return np.empty((0, 3), dtype=np.int16)
        pts = np.asarray(center_points)
        min_x, max_x = int(pts[:, 0].min()) - margin, int(pts[:, 0].max()) + margin
        min_z, max_z = int(pts[:, 2].min()) - margin, int(pts[:, 2].max()) + margin
        min_y = max(0, int(pts[:, 1].min()) - 12)
        max_y = min(255, int(pts[:, 1].max()) + 24)
        chunks = []
        for (cx, cz), col in self.cols.items():
            if (cx + 1) * 16 < min_x or cx * 16 > max_x or (cz + 1) * 16 < min_z or cz * 16 > max_z:
                continue
            sub = col[min_y:max_y + 1]
            if not sub.any():
                continue
            ys, zs, xs = np.nonzero(sub)
            chunks.append(np.column_stack((
                xs.astype(np.int16) + cx * 16,
                ys.astype(np.int16) + min_y,
                zs.astype(np.int16) + cz * 16,
            )))
        return np.concatenate(chunks, axis=0) if chunks else np.empty((0, 3), dtype=np.int16)


def pack_flags(e):
    held_id = int(e.get('held_item_id') or 0)
    flags = 0
    if e.get('sneaking'):
        flags |= 1
    if 1 <= held_id <= 255:
        flags |= 2
    if held_id in SWORD_IDS:
        flags |= 4
    if held_id == 261:
        flags |= 8
    if e.get('using_item') or e.get('blocking'):
        flags |= 16
    if int(e.get('hurtTime') or 0) > 0:
        flags |= 32
    if e.get('isSwingInProgress') or float(e.get('swingProgress') or 0) > 0:
        flags |= 64
    return flags


def compute_relational(target_idx, valid_players):
    t = valid_players[target_idx]
    tx, ty, tz = t[1], t[2], t[3]
    best_near = None
    best_near_d2 = 32.0 * 32.0
    best_ranged = None
    best_ranged_score = -1.0

    for j, o in enumerate(valid_players):
        if j == target_idx:
            continue
        dx, dy, dz = o[1] - tx, o[2] - ty, o[3] - tz
        d2 = dx * dx + dy * dy + dz * dz
        if d2 < 0.01:
            continue
        dist = math.sqrt(d2)
        oyaw = math.radians(o[4])
        hdist = max(0.1, math.hypot(dx, dz))
        look_at_t = (-math.sin(oyaw) * (-dx) + math.cos(oyaw) * (-dz)) / hdist
        ofl = o[6]

        if d2 < best_near_d2:
            best_near_d2 = d2
            threat = (0.4 if (ofl & 4) else 0.0) + (0.35 if (ofl & 64) else 0.0) + (0.25 if look_at_t > 0.7 else 0.0)
            best_near = (dx, dy, dz, dist, threat)

        if 5.0 <= dist <= 56.0:
            is_bow = bool(ofl & 8)
            is_draw = bool(is_bow and (ofl & 16))
            score = (3.0 if is_draw else (1.5 if is_bow else 0.3)) * max(0.1, look_at_t + 0.3) / (1.0 + dist / 24.0)
            if score > best_ranged_score:
                best_ranged_score = score
                r_threat = (1.0 if is_draw else (0.5 if is_bow else 0.15)) * max(0.0, look_at_t)
                best_ranged = (dx, dy, dz, dist, r_threat)

    out = [0.0] * 10
    if best_near is not None:
        dx, dy, dz, dist, threat = best_near
        out[0], out[1], out[2] = dx, dy, dz
        out[3] = 1.0 / (1.0 + dist / 6.0)
        out[4] = threat
    if best_ranged is not None:
        dx, dy, dz, dist, r_threat = best_ranged
        out[5], out[6], out[7] = dx, dy, dz
        out[8] = 1.0 / (1.0 + dist / 16.0)
        out[9] = r_threat
    return out


def make_empty_rec():
    return {
        'tick': [], 'pos': [], 'spos': [], 'rot': [], 'flags': [],
        'rel': [], 'vox': [], 'pvox': [], 'top': [], 'blk4': [], 'self': [], 'los': [],
    }


def process_session(job):
    session_name, part_paths, bw_manifest_games, out_dir = job
    world = LiveWorldV4()
    matches = []
    active = None
    bw_by_start = sorted(bw_manifest_games, key=lambda g: g['start_ms'])
    bw_idx = 0
    cur_tick = 0

    def close_active(save_terrain=False):
        nonlocal active
        if active is None:
            return
        kept = {}
        all_pts = []
        for uid, rec in active['players'].items():
            if len(rec['tick']) >= 40:
                kept[uid] = rec
                if save_terrain and uid != '__self__':
                    all_pts.extend(rec['pos'][::10])
        if kept:
            remote_uids = [u for u in sorted(kept) if u != '__self__']
            has_self = '__self__' in kept
            tot_remote = sum(len(kept[u]['tick']) for u in remote_uids)
            if tot_remote >= 200 or has_self:
                saved_meta = {
                    'mode': active['mode'],
                    'match_id': active['match_id'],
                    'session': session_name,
                    'start_ms': active['start_ms'],
                    'players': len(remote_uids),
                    'ticks': tot_remote,
                    'self_ticks': len(kept['__self__']['tick']) if has_self else 0,
                    'block_places': len(world.place_log),
                }
                fname = f"{active['match_id']}.npz"
                arrays = {}
                for p_i, uid in enumerate(remote_uids):
                    rec = kept[uid]
                    arrays[f'p{p_i}_tick'] = np.asarray(rec['tick'], dtype=np.int32)
                    arrays[f'p{p_i}_pos'] = np.asarray(rec['pos'], dtype=np.float32)
                    arrays[f'p{p_i}_spos'] = np.asarray(rec['spos'], dtype=np.float32)
                    arrays[f'p{p_i}_rot'] = np.asarray(rec['rot'], dtype=np.float32)
                    arrays[f'p{p_i}_flags'] = np.asarray(rec['flags'], dtype=np.uint8)
                    arrays[f'p{p_i}_rel'] = np.asarray(rec['rel'], dtype=np.float32)
                    arrays[f'p{p_i}_vox'] = np.asarray(rec['vox'], dtype=np.uint8)
                    arrays[f'p{p_i}_pvox'] = np.asarray(rec['pvox'], dtype=np.uint8)
                    arrays[f'p{p_i}_top'] = np.asarray(rec['top'], dtype=np.int8)
                    arrays[f'p{p_i}_blk4'] = np.asarray(rec['blk4'], dtype=np.float32)
                    arrays[f'p{p_i}_self'] = np.asarray(rec['self'], dtype=np.float32)
                    arrays[f'p{p_i}_los'] = np.asarray(rec['los'], dtype=np.uint8)
                arrays['num_players'] = np.asarray([len(remote_uids)], dtype=np.int32)
                if has_self:
                    rec = kept['__self__']
                    arrays['self_tick'] = np.asarray(rec['tick'], dtype=np.int32)
                    arrays['self_pos'] = np.asarray(rec['pos'], dtype=np.float32)
                    arrays['self_rot'] = np.asarray(rec['rot'], dtype=np.float32)
                    arrays['self_flags'] = np.asarray(rec['flags'], dtype=np.uint8)
                    arrays['self_rel'] = np.asarray(rec['rel'], dtype=np.float32)
                    arrays['self_vox'] = np.asarray(rec['vox'], dtype=np.uint8)
                    arrays['self_pvox'] = np.asarray(rec['pvox'], dtype=np.uint8)
                    arrays['self_top'] = np.asarray(rec['top'], dtype=np.int8)
                    arrays['self_blk4'] = np.asarray(rec['blk4'], dtype=np.float32)
                if world.place_log:
                    arrays['place_log'] = np.asarray(world.place_log, dtype=np.int32)
                else:
                    arrays['place_log'] = np.empty((0, 4), dtype=np.int32)
                if save_terrain and all_pts:
                    arrays['terrain_xyz'] = world.snapshot_solid_blocks(all_pts, margin=28)
                np.savez_compressed(out_dir / fname, **arrays)
                saved_meta['file'] = fname
                matches.append(saved_meta)
        active = None
        world.reset_match_log()

    sw_counter = 0
    bw_auto_counter = 0
    has_bw_manifest = len(bw_by_start) > 0

    def safe_gz_lines(path):
        try:
            with gzip.open(path, 'rb') as stream:
                for ln in stream:
                    yield ln
        except (EOFError, OSError, gzip.BadGzipFile):
            return

    for part in part_paths:
        if True:
            for line in safe_gz_lines(part):
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
                            world.apply_block_packet(base64.b64decode(b64), cur_tick, record_log=(active is not None))
                    elif b'S01PacketJoinGame' in head or b'S07PacketRespawn' in head:
                        if active is not None and active['auto']:
                            close_active(save_terrain=True)
                        world.clear()
                    elif b'S02PacketChat' in head:
                        row = json.loads(line)
                        b64 = row['data'].get('base64')
                        if not b64:
                            continue
                        try:
                            txt = parse_chat_packet(base64.b64decode(b64))
                        except Exception:
                            continue
                        ms = int(row['time_ms'])
                        if (txt.startswith('Sending you to ')
                                or txt.startswith('You died! Want to play again?')
                                or txt.startswith('Reward Summary')):
                            if active is not None and active['auto']:
                                close_active(save_terrain=True)
                        elif 'Cages opened! FIGHT!' in txt or txt.startswith('Gather resources and equipment on your island'):
                            if active is not None and active['auto']:
                                close_active(save_terrain=True)
                            if active is None:
                                sw_counter += 1
                                world.reset_match_log()
                                active = {
                                    'mode': 'skywars',
                                    'match_id': f'sw-{session_name[9:22]}-{sw_counter:02d}',
                                    'start_ms': ms,
                                    'end_ms': ms + 12 * 60 * 1000,
                                    'players': defaultdict(make_empty_rec),
                                    'auto': True,
                                }
                        elif (not has_bw_manifest) and ('Protect your bed and destroy the enemy beds.' in txt):
                            if active is not None and active['auto']:
                                close_active(save_terrain=True)
                            if active is None:
                                bw_auto_counter += 1
                                world.reset_match_log()
                                active = {
                                    'mode': 'bedwars',
                                    'match_id': f'bw-{session_name[9:22]}-{bw_auto_counter:02d}',
                                    'start_ms': ms,
                                    'end_ms': ms + 25 * 60 * 1000,
                                    'players': defaultdict(make_empty_rec),
                                    'auto': True,
                                }
                elif line.startswith(b'{"type":"tick_end"'):
                    m = SNAP_TIME_RE.search(line[:260])
                    if m is None:
                        continue
                    now_ms = int(m[1])
                    while bw_idx < len(bw_by_start) and now_ms >= bw_by_start[bw_idx]['end_ms']:
                        if active is not None and active['mode'] == 'bedwars' and active['match_id'] == bw_by_start[bw_idx]['id']:
                            close_active(save_terrain=(int(active['match_id'].split('-')[1]) >= 8))
                        bw_idx += 1
                    if bw_idx < len(bw_by_start):
                        g = bw_by_start[bw_idx]
                        if g['start_ms'] + 2000 <= now_ms < g['end_ms']:
                            if active is None or active['match_id'] != g['id']:
                                if active is not None:
                                    close_active(save_terrain=False)
                                world.reset_match_log()
                                active = {
                                    'mode': 'bedwars',
                                    'match_id': g['id'],
                                    'start_ms': g['start_ms'],
                                    'end_ms': g['end_ms'],
                                    'players': defaultdict(make_empty_rec),
                                    'auto': False,
                                }
                    if active is None:
                        continue
                    if now_ms < active['start_ms'] + 1500:
                        continue
                    if now_ms >= active['end_ms']:
                        close_active(save_terrain=active['auto'])
                        continue
                    row = json.loads(line)
                    state = row['data']
                    own = state.get('self') or {}
                    own_uid = own.get('uuid', '')
                    own_pos = own.get('pos')
                    tick = int(row['client_tick'])
                    cur_tick = tick
                    if tick % 40 == 0:
                        world.prune_recent(tick)

                    valid_players = []
                    if own_pos and not own.get('noClip') and own.get('health', 20) > 0:
                        valid_players.append((
                            '__self__',
                            float(own_pos[0]), float(own_pos[1]), float(own_pos[2]),
                            float(own.get('rotationYawHead', own.get('yaw', 0.0))),
                            float(own.get('pitch', 0.0)),
                            pack_flags(own),
                            (float(own_pos[0]), float(own_pos[1]), float(own_pos[2])),
                            True,
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
                            pack_flags(e),
                            (sx, sy, sz),
                            False,
                        ))

                    opx, opy, opz = (own_pos[0], own_pos[1], own_pos[2]) if own_pos else (0.0, 0.0, 0.0)
                    for idx_p, vp in enumerate(valid_players):
                        uid, px, py, pz, yaw, pitch, fl, spos, is_self = vp
                        bx, by, bz = int(math.floor(px)), int(math.floor(py)), int(math.floor(pz))
                        fine, pfine, top, blk4 = world.sample_v4_spatial(bx, by, bz, tick)
                        rel = compute_relational(idx_p, valid_players)
                        rec = active['players'][uid]
                        rec['tick'].append(tick)
                        rec['pos'].append((px, py, pz))
                        rec['spos'].append(spos)
                        rec['rot'].append((yaw, pitch))
                        rec['flags'].append(fl)
                        rec['rel'].append(rel)
                        rec['vox'].append(fine)
                        rec['pvox'].append(pfine)
                        rec['top'].append(top)
                        rec['blk4'].append(blk4)
                        if not is_self:
                            los = 1 if (own_pos and world.ray_clear(opx, opy + 1.62, opz, px, py + 1.1, pz)) else 0
                            rec['self'].append((opx, opy, opz))
                            rec['los'].append(los)
    if active is not None:
        close_active(save_terrain=True)
    return session_name, matches


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, default=Path('runtime/bedwars-forecast/advanced/tracks.json.gz'))
    p.add_argument('--captures', type=Path, default=CAPTURES)
    p.add_argument('--out', type=Path, default=Path('runtime/bow-aim/neural-data-v4'))
    p.add_argument('--workers', type=int, default=6)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    t0 = time.monotonic()

    with gzip.open(args.manifest, 'rt') as f:
        bw_manifest = json.load(f)
    bw_by_cap = defaultdict(list)
    for g in bw_manifest['matches']:
        start = g['start_ms']
        end_ms = start + (max(map(int, g['frames'])) + 1) * 1000
        bw_by_cap[g['capture']].append(dict(id=g['id'], start_ms=start, end_ms=end_ms))

    groups = defaultdict(dict)
    for f in args.captures.glob('a-client-*-events*.jsonl.gz'):
        m = PART_RE.search(f.name)
        if m:
            groups[f.name[:m.start()]][int(m[1] or '1')] = f

    jobs = []
    for name, parts in sorted(groups.items()):
        if 1 not in parts:
            continue
        is_hypixel = False
        try:
            with gzip.open(parts[1], 'rb') as s:
                for i, line in enumerate(s):
                    if i > 128:
                        break
                    if b'"type":"capture_start"' in line and b'"server_host":"hypixel.net"' in line:
                        is_hypixel = True
                        break
        except Exception:
            pass
        if not is_hypixel:
            continue
        cap_key = name.split('-')[2]
        if cap_key in {'1793562478355', '1793564578406', '1793564783162'}:
            continue
        ordered = [parts[k] for k in sorted(parts)]
        jobs.append((name, ordered, bw_by_cap.get(cap_key, []), args.out))

    print(f'Extracting v4 (dynamic blocks + 25x25 topmap + future block log) from {len(jobs)} sessions...', flush=True)
    all_matches = []
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        for session_name, found in pool.map(process_session, jobs):
            all_matches.extend(found)
            print(f'  {session_name[:32]}: {len(found)} matches, '
                  f'{sum(m["ticks"] for m in found):,} remote + {sum(m["self_ticks"] for m in found):,} self, '
                  f'{sum(m["block_places"] for m in found):,} block places', flush=True)

    # Strictly preserve the EXACT frozen v1/v2/v3 sealed test & val splits!
    # All newly extracted matches go into train (except 2 new BedWars/SkyWars matches held out for prospective val if desired,
    # keeping sealed test 100% IDENTICAL to v1/v2/v3 for exact apples-to-apples comparisons!).
    for m in all_matches:
        mid = m['match_id']
        if mid.startswith('game-'):
            num = int(mid.split('-')[1])
            m['split'] = 'train' if num <= 7 else ('val' if num == 8 else 'test')
        elif mid in SEALED_SW_IDS:
            m['split'] = 'test'
        elif mid in VAL_SW_IDS:
            m['split'] = 'val'
        else:
            m['split'] = 'train'

    all_matches.sort(key=lambda m: (m['mode'], m['start_ms'], m['match_id']))
    summary = {
        'version': 4,
        'created_ms': int(time.time() * 1000),
        'fine_shape': [7, 9, 9],
        'top_shape': [4, 25, 25],
        'total_matches': len(all_matches),
        'bedwars_count': sum(1 for m in all_matches if m['mode'] == 'bedwars'),
        'skywars_count': sum(1 for m in all_matches if m['mode'] == 'skywars'),
        'total_remote_ticks': sum(m['ticks'] for m in all_matches),
        'total_self_ticks': sum(m['self_ticks'] for m in all_matches),
        'total_block_places': sum(m['block_places'] for m in all_matches),
        'splits': {
            sp: {
                'matches': sum(1 for m in all_matches if m['split'] == sp),
                'ticks': sum(m['ticks'] for m in all_matches if m['split'] == sp),
                'self_ticks': sum(m['self_ticks'] for m in all_matches if m['split'] == sp),
                'block_places': sum(m['block_places'] for m in all_matches if m['split'] == sp),
            }
            for sp in ('train', 'val', 'test')
        },
        'matches': all_matches,
    }
    (args.out / 'manifest.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'Done v4 extraction in {time.monotonic() - t0:.1f}s: '
          f'{summary["total_matches"]} matches ({summary["bedwars_count"]} BW + {summary["skywars_count"]} SW), '
          f'{summary["total_remote_ticks"]:,} remote + {summary["total_self_ticks"]:,} self ticks, '
          f'{summary["total_block_places"]:,} real block placements!', flush=True)


if __name__ == '__main__':
    main()
