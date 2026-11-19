"""Separate the effects of own sprint metadata and velocity delivery on recorded physical-input replays."""
import gzip
import json

from cpu_limit import supervise_script
from fidelity_panel import HERE, ROOT, compare, compile_model


METHODS = ['fixed', 'velocity_drain', 'sprint_metadata_drain', 'both_drains']
SETTINGS = [(.35, .35), 'observed-velocity-drains', 'observed-sprint-drains',
            'observed-velocity-and-sprint-drains']
EVIDENCE = HERE / 'evidence/mmc13/opening'


def freeze(path, data):
    if path.exists():
        if path.read_bytes() != data:
            raise ValueError(f'Existing evidence differs at {path}')
    else:
        with path.open('xb') as f:
            f.write(data)


def run():
    old = json.loads(gzip.decompress((EVIDENCE / 'fidelity.json.gz').read_bytes()))
    original = old['selection']
    path = ROOT / 'build/elite-league/mmc13/mmc13-opening.json'
    manifest = json.loads(path.read_text())
    if manifest['plan'].keys() != manifest['matches'].keys():
        raise ValueError('Opening tournament incomplete')
    # Choose without consulting any results, by a rule disjoint from the original earliest-ID panel.
    replication = {}
    for req, mid in sorted(manifest['matches'].items(), key=lambda item: int(item[1][1:]), reverse=True):
        g = manifest['plan'][req]
        cell = f"{g['arm']} {g['rtt']} {g['side']}"
        if cell in original and mid not in original.values():
            replication.setdefault(cell, mid)
    if original.keys() != replication.keys():
        raise ValueError('Missing comparison cell')
    panels = dict(previous=original, additional=replication)
    design = dict(panels=panels, source='Same 96-bout recorded-floor opening tournament. The additional panel takes the latest different match ID in each original arm, seat and RTT cell. These are retrospective development diagnostics.',
                  inputs='Real keys, integer mouse counts, clicks and host stages. Only the target drain start crosses the diagnostic boundary. The simulation computes when to emit a packet and its payload. Own metadata is matched by same-side flags-bearing wire packet ordinal. Health-only S1C is excluded. Impossible, missing or unused ordinals are recorded.',
                  outcomes='Each two-second opening is exact only if all position and motion components match on every move by both players. Report all failed or unscorable replays, compare all four interventions, verify the original fixed and velocity-only results exactly.')
    protocol = EVIDENCE / 'sprint-drain-protocol.json'
    freeze(protocol, (json.dumps(design, indent=2)+'\n').encode())
    compile_model()
    rows = {}
    for panel, cells in panels.items():
        for cell, mid in cells.items():
            try:
                results = compare(mid, SETTINGS, 'recorded', True)
                if not isinstance(results, list):
                    raise ValueError(str(results))
            except (KeyError, ValueError, IndexError, StopIteration) as e:
                rows[mid] = dict(cell=cell, panel=panel, unscored=repr(e))
                print(mid, 'unscored', repr(e), flush=True)
                continue
            if panel == 'previous':
                earlier = old['first_two_seconds'][mid]['forecasts']
                # Native contact ledgers use Python tuples in memory and JSON arrays on disk.
                controls = json.loads(json.dumps(results[:2]))
                if controls != [earlier['fixed'], earlier['oracle_velocity_delivery']]:
                    raise AssertionError(f'Frozen comparison changed for {mid}')
            forecasts = dict(zip(METHODS, results, strict=True))
            rows[mid] = dict(cell=cell, panel=panel, forecasts=forecasts)
            print(mid, [r['exact'] for r in results], flush=True)
    output = EVIDENCE / 'sprint-drain-fidelity.json.gz'
    freeze(output, gzip.compress(json.dumps(rows, sort_keys=True, separators=(',', ':')).encode(), mtime=0))


if __name__ == '__main__':
    supervise_script()
    run()
