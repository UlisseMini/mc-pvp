"""Extract observed bridging cues and nearby placements from the archived BedWars captures."""
import argparse
import base64
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
import gzip
import json
from pathlib import Path
import re
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'bedwars-forecast'))
from extract_terrain import block_updates

STAMP = re.compile(rb'"time_ms":(\d+)')
CAPTURES = Path.home() / 'Library/Application Support/minecraft/config/pvp-practice/captures'
BUILD_BLOCKS = {1, 3, 4, 5, 12, 17, 19, 20, 24, 35, 45, 49, 57, 79, 80, 82, 87, 89, 95,
                98, 112, 121, 133, 155, 159, 162, 165, 166, 168, 169, 172, 173, 174, 179}


def scan(job):
    caps, files, games = job
    tracks = {g['id']: defaultdict(list) for g in games}
    placements = {g['id']: defaultdict(list) for g in games}
    at = 0
    for part in files:
        with gzip.open(caps / part, 'rb') as stream:
            for line in stream:
                snapshot = line.startswith(b'{"type":"tick_end"')
                update = not snapshot and line.startswith(b'{"type":"packet_wire"') and (
                    b'S23PacketBlockChange' in line or b'S22PacketMultiBlockChange' in line)
                if not snapshot and not update:
                    continue
                time = int(STAMP.search(line)[1])
                while at < len(games) and time >= games[at]['stop']:
                    at += 1
                if at == len(games):
                    return tracks, placements
                game = games[at]
                if time < game['start'] + 2000:
                    continue
                row = json.loads(line)
                tick, state = row['client_tick'], row['data']
                if update:
                    raw = base64.b64decode(state['base64'])
                    if raw and raw[0] in (0x22, 0x23):
                        for x,y,z,block in block_updates(raw):
                            if block >> 4 in BUILD_BLOCKS:
                                placements[game['id']][tick].append((x,y,z))
                    continue
                own = (state.get('self') or {}).get('uuid')
                for entity in state.get('entities', []):
                    uid = entity.get('uuid', '')
                    if len(uid) != 36 or uid[14] != '4' or uid == own or entity.get('noClip') or entity.get('health', 20) <= 0:
                        continue
                    pitch = (entity.get('pitch',0) + 180) % 360 - 180
                    bits = int(entity.get('sneaking', False))
                    if pitch >= 34 and entity.get('held_item_id',0) in BUILD_BLOCKS:
                        bits |= 2
                    tracks[game['id']][uid].append((tick,*entity['pos'],bits))
    return tracks, placements


def is_near(closest, tick, x, y, z):
    return any((x - (px + .5))**2 + (z - (pz + .5))**2 < 3.4**2 and y-3.0 <= py <= y+.8
               for t in range(tick-14,tick+19) for px,py,pz in closest.get(t, ()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--captures', type=Path, default=CAPTURES)
    a = p.parse_args()
    with gzip.open(a.manifest,'rt') as f:
        manifest = json.load(f)
    game_groups = defaultdict(list)
    for g in manifest['matches']:
        game_groups[g['capture']].append(dict(id=int(g['id'].split('-')[1]), start=g['start_ms'],
                        stop=g['start_ms']+(max(map(int,g['frames']))+1)*1000))
    jobs = [(a.captures, parts, sorted(game_groups[parts[0].split('-')[2]], key=lambda x:x['start']))
          for parts in manifest['source_files'] if parts[0].split('-')[2] in game_groups]
    with ProcessPoolExecutor(max_workers=4) as worker, gzip.open(a.output,'wb') as out:
        out.write(b'BRG1'+struct.pack('>i',len(manifest['matches'])))
        for tracks, placements in worker.map(scan,jobs):
            for game, players in sorted(tracks.items()):
                data = [points for points in players.values() if len(points)>=40]
                out.write(struct.pack('>ii',game,len(data)))
                labeled = 0
                for records in data:
                    out.write(struct.pack('>i',len(records)))
                    for tick,x,y,z,cues in records:
                        if cues & 2 and is_near(placements[game],tick,x,y,z):
                            cues |= 4
                            labeled += 1
                        out.write(struct.pack('>i3fB',tick,x,y,z,cues))
                print(f'game-{game:02} {len(data)} players and {labeled:,} build-cued ticks near placement',flush=True)


if __name__ == '__main__':
    main()
