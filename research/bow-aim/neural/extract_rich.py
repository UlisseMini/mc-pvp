"""Extract rich tick-level remote player trajectories + causal 9x7x9 solid-voxel patches
from ALL local Hypixel BedWars and SkyWars captures (zero sim-to-real, zero future leakage).
"""
import argparse
import base64
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
import gzip
import json
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

# Exact Minecraft 1.8.9 non-colliding block IDs (matches arena_maps.py and WorldClient collision)
PASSABLE_IDS = {
    0, 6, 8, 9, 10, 11, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59,
    63, 65, 66, 68, 69, 75, 76, 77, 83, 90, 93, 94, 104, 105, 106, 111, 115, 119,
    127, 131, 132, 141, 142, 143, 147, 148, 149, 150, 157, 175, 176, 177,
}
SOLID_LUT = np.ones(4096, dtype=np.uint8)
SOLID_LUT[list(PASSABLE_IDS)] = 0
SWORD_IDS = {267, 268, 272, 276, 283}

PATCH_RX = 4  # 9 wide (-4..+4)
PATCH_RY = 3  # 7 tall (-3..+3)
PATCH_BITS = (2 * PATCH_RX + 1) * (2 * PATCH_RY + 1) * (2 * PATCH_RX + 1)  # 567
PATCH_BYTES = (PATCH_BITS + 7) // 8  # 71


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


class LiveWorld:
    """Causal client-side chunk occupancy tracker (uint8 solid mask per 16x256x16 column)."""

    def __init__(self):
        self.cols = {}  # (cx, cz) -> np.ndarray shape (256, 16, 16) uint8 {0, 1}

    def clear(self):
        self.cols.clear()

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

    def apply_block_packet(self, raw):
        try:
            for x, y, z, state in block_updates(raw):
                if not (0 <= y < 256):
                    continue
                cx, bx = divmod(x, 16)
                cz, bz = divmod(z, 16)
                solid = int(SOLID_LUT[state >> 4])
                col = self.cols.get((cx, cz))
                if col is None:
                    if not solid:
                        continue
                    col = np.zeros((256, 16, 16), dtype=np.uint8)
                    self.cols[(cx, cz)] = col
                col[y, bz, bx] = solid
        except Exception:
            pass

    def sample_patch_bits(self, bx, by, bz):
        """Extract 9x7x9 solid occupancy around (bx, by, bz) packed into 71 bytes (order: dy, dz, dx)."""
        patch = np.zeros((7, 9, 9), dtype=np.uint8)
        y0 = by - 3
        y1 = by + 4
        sy0 = max(0, y0)
        sy1 = min(256, y1)
        if sy0 >= sy1:
            return np.packbits(patch.reshape(-1))
        py0 = sy0 - y0
        py1 = py0 + (sy1 - sy0)
        x0, x1 = bx - 4, bx + 5
        z0, z1 = bz - 4, bz + 5
        cx0, cx1 = x0 >> 4, (x1 - 1) >> 4
        cz0, cz1 = z0 >> 4, (z1 - 1) >> 4
        for cx in range(cx0, cx1 + 1):
            wx0 = max(x0, cx * 16)
            wx1 = min(x1, (cx + 1) * 16)
            lx0, lx1 = wx0 - cx * 16, wx1 - cx * 16
            px0, px1 = wx0 - x0, wx1 - x0
            for cz in range(cz0, cz1 + 1):
                col = self.cols.get((cx, cz))
                if col is None:
                    continue
                wz0 = max(z0, cz * 16)
                wz1 = min(z1, (cz + 1) * 16)
                lz0, lz1 = wz0 - cz * 16, wz1 - cz * 16
                pz0, pz1 = wz0 - z0, wz1 - z0
                patch[py0:py1, pz0:pz1, px0:px1] = col[sy0:sy1, lz0:lz1, lx0:lx1]
        return np.packbits(patch.reshape(-1))

    def ray_clear(self, x0, y0, z0, x1, y1, z1):
        """Fast 3D raycast for solid blocks between (x0,y0,z0) and (x1,y1,z1)."""
        dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
        dist = (dx * dx + dy * dy + dz * dz) ** 0.5
        steps = max(2, int(dist * 3.0))
        inv = 1.0 / steps
        for s in range(1, steps):
            t = s * inv
            wx = int(np.floor(x0 + dx * t))
            wy = int(np.floor(y0 + dy * t))
            wz = int(np.floor(z0 + dz * t))
            if not (0 <= wy < 256):
                continue
            col = self.cols.get((wx >> 4, wz >> 4))
            if col is not None and col[wy, wz & 15, wx & 15]:
                return False
        return True

    def snapshot_solid_blocks(self, center_points, margin=24):
        """Export compact int16 (x,y,z) array of all solid blocks near observed player positions."""
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


def process_session(job):
    session_name, part_paths, bw_manifest_games, out_dir = job
    world = LiveWorld()
    matches = []  # finalized match dicts
    active = None  # current match dict
    bw_by_start = sorted(bw_manifest_games, key=lambda g: g['start_ms'])
    bw_idx = 0

    def close_active(save_terrain=False):
        nonlocal active
        if active is None:
            return
        # Keep players with >= 40 ticks
        kept = {}
        all_pts = []
        for uid, rec in active['players'].items():
            if len(rec['tick']) >= 40:
                kept[uid] = rec
                if save_terrain:
                    all_pts.extend(rec['pos'][::10])
        if kept:
            saved_meta = {
                'mode': active['mode'],
                'match_id': active['match_id'],
                'session': session_name,
                'start_ms': active['start_ms'],
                'players': len(kept),
                'ticks': sum(len(r['tick']) for r in kept.values()),
            }
            fname = f"{active['match_id']}.npz"
            arrays = {}
            for p_i, (uid, rec) in enumerate(sorted(kept.items())):
                arrays[f'p{p_i}_tick'] = np.asarray(rec['tick'], dtype=np.int32)
                arrays[f'p{p_i}_pos'] = np.asarray(rec['pos'], dtype=np.float32)
                arrays[f'p{p_i}_rot'] = np.asarray(rec['rot'], dtype=np.float32)
                arrays[f'p{p_i}_flags'] = np.asarray(rec['flags'], dtype=np.uint8)
                arrays[f'p{p_i}_vox'] = np.asarray(rec['vox'], dtype=np.uint8)
                arrays[f'p{p_i}_self'] = np.asarray(rec['self'], dtype=np.float32)
                arrays[f'p{p_i}_los'] = np.asarray(rec['los'], dtype=np.uint8)
            arrays['num_players'] = np.asarray([len(kept)], dtype=np.int32)
            if save_terrain and all_pts:
                arrays['terrain_xyz'] = world.snapshot_solid_blocks(all_pts, margin=28)
            np.savez_compressed(out_dir / fname, **arrays)
            saved_meta['file'] = fname
            matches.append(saved_meta)
        active = None

    sw_counter = 0
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
                        if active is not None and active['mode'] == 'skywars':
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
                        if txt.startswith('Sending you to ') or txt == 'Bed Wars' or txt.startswith('You died! Want to play again?'):
                            if active is not None and active['mode'] == 'skywars':
                                close_active(save_terrain=True)
                        elif 'Cages opened! FIGHT!' in txt or txt.startswith('Gather resources and equipment on your island'):
                            if active is not None and active['mode'] == 'skywars':
                                close_active(save_terrain=True)
                            if active is None:
                                sw_counter += 1
                                active = {
                                    'mode': 'skywars',
                                    'match_id': f'sw-{session_name[9:22]}-{sw_counter:02d}',
                                    'start_ms': ms,
                                    'end_ms': ms + 12 * 60 * 1000,
                                    'players': defaultdict(lambda: {
                                        'tick': [], 'pos': [], 'rot': [], 'flags': [],
                                        'vox': [], 'self': [], 'los': [],
                                    }),
                                }
                elif line.startswith(b'{"type":"tick_end"'):
                    m = SNAP_TIME_RE.search(line[:260])
                    if m is None:
                        continue
                    now_ms = int(m[1])
                    # Check BedWars manifest windows first (exact match to frozen BW split)
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
                                active = {
                                    'mode': 'bedwars',
                                    'match_id': g['id'],
                                    'start_ms': g['start_ms'],
                                    'end_ms': g['end_ms'],
                                    'players': defaultdict(lambda: {
                                        'tick': [], 'pos': [], 'rot': [], 'flags': [],
                                        'vox': [], 'self': [], 'los': [],
                                    }),
                                }
                    if active is None:
                        continue
                    if now_ms < active['start_ms'] + 1500:
                        continue
                    if now_ms >= active['end_ms']:
                        close_active(save_terrain=(active['mode'] == 'skywars'))
                        continue
                    row = json.loads(line)
                    state = row['data']
                    own = state.get('self') or {}
                    own_uid = own.get('uuid', '')
                    own_pos = own.get('pos') or [0.0, 0.0, 0.0]
                    tick = int(row['client_tick'])
                    for e in state.get('entities', []):
                        uid = e.get('uuid', '')
                        if (len(uid) != 36 or uid[14] != '4' or uid == own_uid
                                or 'pos' not in e or e.get('noClip') or e.get('health', 20) <= 0):
                            continue
                        px, py, pz = e['pos']
                        yaw = float(e.get('rotationYawHead', e.get('yaw', 0.0)))
                        pitch = float(e.get('pitch', 0.0))
                        bx, by, bz = int(np.floor(px)), int(np.floor(py)), int(np.floor(pz))
                        vox = world.sample_patch_bits(bx, by, bz)
                        los = 1 if world.ray_clear(own_pos[0], own_pos[1] + 1.62, own_pos[2],
                                                   px, py + 1.1, pz) else 0
                        rec = active['players'][uid]
                        rec['tick'].append(tick)
                        rec['pos'].append((px, py, pz))
                        rec['rot'].append((yaw, pitch))
                        rec['flags'].append(pack_flags(e))
                        rec['vox'].append(vox)
                        rec['self'].append((own_pos[0], own_pos[1], own_pos[2]))
                        rec['los'].append(los)
    if active is not None:
        close_active(save_terrain=True)
    return session_name, matches


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, default=Path('runtime/bedwars-forecast/advanced/tracks.json.gz'))
    p.add_argument('--captures', type=Path, default=CAPTURES)
    p.add_argument('--out', type=Path, default=Path('runtime/bow-aim/neural-data'))
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

    # Group all Hypixel capture sessions
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
        # Skip the 4 early non-BW/SW Hypixel sessions (0 BW, 0 SW) to save CPU
        if cap_key in {'1793562478355', '1793564578406', '1793564783162'}:
            continue
        ordered = [parts[k] for k in sorted(parts)]
        jobs.append((name, ordered, bw_by_cap.get(cap_key, []), args.out))

    print(f'Extracting {len(jobs)} Hypixel sessions across {args.workers} workers...', flush=True)
    all_matches = []
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        for session_name, found in pool.map(process_session, jobs):
            all_matches.extend(found)
            print(f'  {session_name[:32]}: {len(found)} matches, '
                  f'{sum(m["ticks"] for m in found)} ticks', flush=True)

    # Freeze airtight whole-match split
    bw = sorted([m for m in all_matches if m['mode'] == 'bedwars'], key=lambda m: int(m['match_id'].split('-')[1]))
    sw = sorted([m for m in all_matches if m['mode'] == 'skywars'], key=lambda m: (m['start_ms'], m['match_id']))
    for m in bw:
        num = int(m['match_id'].split('-')[1])
        m['split'] = 'train' if num <= 7 else ('val' if num == 8 else 'test')
    n_sw = len(sw)
    n_sw_test = max(4, n_sw // 6) if n_sw >= 8 else max(1, n_sw // 4)
    n_sw_val = max(3, n_sw // 8) if n_sw >= 8 else max(1, n_sw // 4)
    for i, m in enumerate(sw):
        if i < n_sw - n_sw_val - n_sw_test:
            m['split'] = 'train'
        elif i < n_sw - n_sw_test:
            m['split'] = 'val'
        else:
            m['split'] = 'test'

    summary = {
        'created_ms': int(time.time() * 1000),
        'patch_shape': [7, 9, 9],
        'patch_bytes': PATCH_BYTES,
        'bedwars_count': len(bw),
        'skywars_count': len(sw),
        'total_ticks': sum(m['ticks'] for m in all_matches),
        'splits': {
            sp: {
                'matches': sum(1 for m in all_matches if m['split'] == sp),
                'ticks': sum(m['ticks'] for m in all_matches if m['split'] == sp),
                'bedwars': [m['match_id'] for m in bw if m['split'] == sp],
                'skywars': [m['match_id'] for m in sw if m['split'] == sp],
            }
            for sp in ('train', 'val', 'test')
        },
        'matches': bw + sw,
    }
    (args.out / 'manifest.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'Done in {time.monotonic() - t0:.1f}s: {len(bw)} BW + {len(sw)} SW matches, '
          f'{summary["total_ticks"]:,} total remote-player ticks!', flush=True)
    for sp in ('train', 'val', 'test'):
        s = summary['splits'][sp]
        print(f"  {sp:5s}: {s['matches']:2d} matches, {s['ticks']:7,} ticks "
              f"(BW={len(s['bedwars'])}, SW={len(s['skywars'])})", flush=True)


if __name__ == '__main__':
    main()
