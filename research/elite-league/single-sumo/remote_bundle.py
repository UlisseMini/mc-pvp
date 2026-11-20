"""Build an explicit file list for simulator workers, including only registered clocks and frozen policies."""
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
LAB=Path('research/elite-league/single-sumo')
BUILD=Path('build/elite-league/single-sumo')
OUT=LAB/'evidence/robustness-001'


def read(p):return json.loads((ROOT/p).read_text())


def build_head24_overlay():
    source=ROOT/LAB/'MemoryAssay.java'
    base=read(BUILD/'sim-current.json')['directory']
    tag=hashlib.sha256(source.read_bytes()+(ROOT/BUILD/'sim-current.json').read_bytes()).hexdigest()
    dest=ROOT/BUILD/('head24-diagnostic-'+tag[:16])
    if not (dest/'MemoryAssay$Witness.class').exists():
        with tempfile.TemporaryDirectory(dir=ROOT/BUILD) as temp:
            classes=Path(temp)/'classes';classes.mkdir()
            subprocess.run(['javac','--release','17','-J-XX:ActiveProcessorCount=1','-proc:none',
                            '-cp',str(ROOT/BUILD/base),'-d',str(classes),str(source)],check=True)
            classes.rename(dest)
    spec=dict(directory=dest.name,source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),base=base)
    (ROOT/BUILD/'head24-diagnostic.json').write_text(json.dumps(spec,sort_keys=True)+'\n')


def bundle():
    build_head24_overlay()
    files={LAB/name for name in ('remote_league.py','learner.py','train.py','build.py','league_tournament.py',
                                 'assay.py','remote_analysis.py','remote_round.py','remote_jobs.py','remote_fit.py','remote_reversal.py','payoff_pair.py','payoff_oracle.py','recognition.py','opponent_id.py','router_export.py','stationary_field.py','stationary_ppo.py','stationary_frontier.py','stationary_headroom.py','weak_field.py','check_stationary.py') if (ROOT/LAB/name).is_file()}
    files.add(LAB.parent/'stage_runtime.py')
    files.add(Path('policies/wtap-clickphase.properties'))
    for pin in ('training-runtime.json','sim-current.json','head24-diagnostic.json'):
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
    stationary=OUT/'stationary-field/protocol.json'
    if (ROOT/stationary).is_file():
        files.add(stationary)
        files.update(Path(v['reference']) for v in read(stationary)['field'].values() if v['reference'].endswith('.net'))
    frontier=stationary.parent/'frontier-protocol.json'
    if (ROOT/frontier).is_file():
        files.add(frontier)
        files.update(Path(v['reference']) for v in read(frontier)['controls'].values())
    timing=stationary.parent/'opening-timing/protocol.json'
    if (ROOT/timing).is_file():files.add(timing)
    strong=stationary.parent/'strong-opening/protocol.json'
    if (ROOT/strong).is_file():files.add(strong)
    series=stationary.parent/'series-ten/protocol.json'
    if (ROOT/series).is_file():
        files.add(series)
        training=series.with_name('training.json')
        if (ROOT/training).is_file():files.add(training)
        belief=series.parent/'belief/protocol.json'
        if (ROOT/belief).is_file():files.add(belief)
        for script in ('series_bank.py','series_learn.py','series_eval.py','series_pair.py','series_bayes.py','check_series.py'):
            if (ROOT/LAB/script).exists():files.add(LAB/script)
    # Each path is relative to the project, and each checksum is verified again on the receiving host.
    assert all(not p.is_absolute() and '..' not in p.parts and (ROOT/p).is_file() for p in files)
    return {str(p):hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sorted(files)}


if __name__=='__main__':
    files=bundle();dest=ROOT/BUILD/'hetzner';dest.mkdir(parents=True,exist_ok=True)
    (dest/'files.txt').write_text(''.join(p+'\n' for p in files))
    (dest/'files.sha256').write_text(''.join(v+'  '+p+'\n' for p,v in files.items()))
    print(len(files),'files')
