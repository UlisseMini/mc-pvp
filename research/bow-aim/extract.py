"""Extract consecutive client tick positions from the frozen BedWars recordings."""
import argparse
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
import gzip
import json
from pathlib import Path
import re
import struct

CAPTURES = Path.home() / 'Library/Application Support/minecraft/config/pvp-practice/captures'
SNAPSHOT = re.compile(rb'"time_ms":(\d+)')
MAGIC = b'BOW1'


def session(job):
    caps, files, games = job
    results = {game['id']: defaultdict(list) for game in games}
    game_at = 0
    for name in files:
        with gzip.open(caps / name, 'rb') as stream:
            for line in stream:
                if not line.startswith(b'{"type":"tick_end"'):
                    continue
                now = int(SNAPSHOT.search(line)[1])
                while game_at < len(games) and now >= games[game_at]['end_ms']:
                    game_at += 1
                if game_at == len(games):
                    return results
                game = games[game_at]
                if now < game['start_ms'] + 2000:
                    continue
                row = json.loads(line)
                state = row['data']
                own = state.get('self') or {}
                tick = int(row['client_tick'])
                for entity in state.get('entities', []):
                    uid = entity.get('uuid', '')
                    if (len(uid) != 36 or uid[14] != '4' or uid == own.get('uuid') or 'pos' not in entity
                            or entity.get('noClip') or entity.get('health', 20) <= 0):
                        continue
                    results[game['id']][uid].append((tick, *entity['pos']))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--captures', type=Path, default=CAPTURES)
    args = parser.parse_args()
    with gzip.open(args.manifest, 'rt') as source:
        manifest = json.load(source)
    groups = defaultdict(list)
    for game in manifest['matches']:
        start = game['start_ms']
        end_ms = start + (max(map(int, game['frames'])) + 1) * 1000
        groups[game['capture']].append(dict(id=game['id'], start_ms=start, end_ms=end_ms))
    jobs = [(args.captures, files, sorted(groups[files[0].split('-')[2]], key=lambda g: g['start_ms']))
            for files in manifest['source_files'] if files[0].split('-')[2] in groups]
    with ProcessPoolExecutor(max_workers=4) as pool, gzip.open(args.output, 'wb') as out:
        results = {}
        for result in pool.map(session, jobs):
            results.update(result)
            print('Extracted', ', '.join(result), flush=True)
        out.write(MAGIC + struct.pack('>i', len(results)))
        for game, players in sorted(results.items()):
            filtered = [data for data in players.values() if len(data) >= 40]
            if not filtered or any(len({row[0] for row in data}) < 2 for data in filtered):
                raise ValueError(f'Missing tick sequence in {game}')
            print(game, len(filtered), sum(map(len, filtered)), flush=True)
            out.write(struct.pack('>ii', int(game.split('-')[1]), len(filtered)))
            for data in filtered:
                out.write(struct.pack('>i', len(data)))
                for tick, x, y, z in data:
                    out.write(struct.pack('>i3f', tick, x, y, z))


if __name__ == '__main__':
    main()
