"""Build an explicit file list for simulator workers, including only registered clocks and frozen policies."""
import hashlib
import json
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
LAB=Path('research/elite-league/single-sumo')
BUILD=Path('build/elite-league/single-sumo')
OUT=LAB/'evidence/robustness-001'


def read(p):return json.loads((ROOT/p).read_text())


def bundle():
    files={LAB/name for name in ('remote_league.py','learner.py','train.py','build.py','league_tournament.py',
                                 'assay.py','remote_analysis.py','remote_round.py','remote_jobs.py','remote_fit.py','remote_reversal.py') if (ROOT/LAB/name).is_file()}
    files.add(LAB.parent/'stage_runtime.py')
    files.add(Path('policies/wtap-clickphase.properties'))
    for pin in ('training-runtime.json','sim-current.json'):
        files.add(BUILD/pin)
        folder=ROOT/BUILD/read(BUILD/pin)['directory']
        files.update(p.relative_to(ROOT) for p in folder.rglob('*') if p.is_file())
    for manifest,clock in [('sumo-inputs/archive-field.tsv',False),('ping-ppo/clocks.tsv',True)]:
        p=LAB.parent/manifest
        files.add(p)
        for row in (ROOT/p).read_text().splitlines():
            cols=row.split('\t')
            if len(cols)>1 and (not clock or cols[0] in ('train','dev')):files.add(Path(cols[1]))
    field_path=LAB/'evidence/g002/generation.json'
    files.add(field_path)
    files.update(Path(v['reference']) for v in read(field_path)['field'].values() if v['reference'].endswith('.net'))
    protocol=read(OUT/'protocol.json')
    files.add(OUT/'protocol.json')
    for role in protocol['roles']:
        p=OUT/role/'config.json';files.add(p)
        files.add(Path(read(p)['warm']))
    for group in ('initial','entrants'):
        files.update(Path(v['reference']) for v in protocol[group].values())
    # Each path is relative to the project, and each checksum is verified again on the receiving host.
    assert all(not p.is_absolute() and '..' not in p.parts and (ROOT/p).is_file() for p in files)
    return {str(p):hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sorted(files)}


if __name__=='__main__':
    files=bundle();dest=ROOT/BUILD/'hetzner';dest.mkdir(parents=True,exist_ok=True)
    (dest/'files.txt').write_text(''.join(p+'\n' for p in files))
    (dest/'files.sha256').write_text(''.join(v+'  '+p+'\n' for p,v in files.items()))
    print(len(files),'files')
