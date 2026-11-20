"""Compare random phases and independent, archived native clocks on announced duels."""
import argparse
import csv
import io
import json
import math
import random
import statistics
import subprocess
import tempfile
from itertools import islice
from pathlib import Path

from season import EVIDENCE, SRC
from setup import compile_sim, jdk

BANK = EVIDENCE / 'perfect-aim/cadence-bank-028.tsv'


def write_csv(path, rows, delimiter=','):
    temporary = path.with_suffix('.writing')
    with temporary.open('w') as out:
        writer = csv.DictWriter(out, rows[0], delimiter=delimiter, lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
    temporary.replace(path)


def make_bank(run):
    rows = []
    for match in json.loads((run / 'results.json').read_text()):
        clocks = []
        for suffix in ('A', 'B', 'server'):
            with (run / f"{match['id']}-{suffix}.tsv").open() as f:
                stamps = (int(r['epochMs']) for r in csv.DictReader(f, delimiter='\t'))
                if suffix == 'server':
                    stamps = (t for t in stamps if t >= origin)
                clocks.append(list(islice(stamps, 61)))
            if suffix == 'B':
                origin = min(c[0] for c in clocks)
        if any(len(c) != 61 for c in clocks):
            raise ValueError(f"Short native clock in {match['id']}")
        rows.append(dict(source=match['id'], rtt=match['rtt'], **{
            k: ','.join(str(t-origin) for t in c) for k, c in zip(('A','B','server'), clocks, strict=True)}))
    write_csv(BANK, rows, '\t')


def schedule(reg):
    rng = random.Random(reg['seed'])
    for ms in reg['rtts']:
        for opening in range(reg['pairs']):
            angle, gap = rng.uniform(-math.pi, math.pi), rng.uniform(3.5, 8)
            arms = [(seat, a, b) for b in reg['opponents'] for seat in (0,1) for a in [*reg['actors'], 'wtap']]
            rng.shuffle(arms)
            for seat, actor, rival in arms:
                yield dict(rtt=ms, opening=opening, seat=seat, angle=angle, gap=gap, actor=actor, rival=rival)


def forecast(panel):
    target = panel / 'cadence-forecast.csv'
    if target.exists():
        return
    reg = json.loads((panel / 'registration.json').read_text())
    if reg['aim_profile'] != 'perfect' or reg['clock'] != 'ordinary TCP' or reg.get('openings'):
        raise ValueError('Expected fresh ordinary perfect-aim openings')
    rows = list(schedule(reg))
    with tempfile.TemporaryDirectory() as d:
        tmp = Path(d)
        spec = []
        for i, r in enumerate(rows):
            stem = f'round-{i:03d}'
            r['id'] = stem
            angle, gap = r['angle'], r['gap']
            x, z = round(math.cos(angle)*gap/2, 4), round(math.sin(angle)*gap/2, 4)
            for side, sign in [('A', -1), ('B', 1)]:
                yaw = math.degrees(angle) + 90*sign
                values = [0, sign*x, 101, sign*z, -sign*x, 101, -sign*z, 0, -.08*0.9800000190734863, 0,
                          'true', 'false', 1, 20, 0, 0, 0, 0, 'false', 'false', 'false', round(yaw, 2), 0, 0]
                (tmp / f'{stem}-{side}.tsv').write_text('announced spawn\n'+'\t'.join(map(str, values))+'\n')
            lineup = [SRC/r['rival']]*2
            lineup[r['seat']] = 'wtap' if r['actor']=='wtap' else SRC/r['actor']
            spec.append('\t'.join(map(str, (tmp, stem, r['rtt'], r['seat'], angle, *lineup))))
        specfile = tmp / 'rounds.tsv'
        specfile.write_text('\n'.join(spec)+'\n')
        output = subprocess.check_output(['nice','-n','18',jdk()/'java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC',
            '-Xmx280m','-Dsword.aimProfile=perfect','-Dsword.serverPacketBatch=true',f'-Dsword.cadenceBank={BANK}',
            '-cp',compile_sim(),'sword.ClockPrediction',specfile], text=True)
    for r, p in zip(rows, csv.DictReader(io.StringIO(output)), strict=True):
        if r['id'] != p['id']:
            raise ValueError('Unexpected forecast order')
        r.update(fixed=p['fixed_win_score'], phase=p['phase_win_score'], empirical=p['poll_win_score'])
    write_csv(target, rows)


def score(panel):
    panel_data=json.loads((panel/'panel.json').read_text())
    with (panel/'cadence-forecast.csv').open() as f:
        forecasts=list(csv.DictReader(f))
    from setup import BUILD
    native=json.loads((BUILD/panel_data['run']/'results.json').read_text())
    scores=panel_data['scores']
    cells=[]
    for p, n, s in zip(forecasts,native,scores,strict=True):
        if (p['id'],int(p['rtt']),int(p['opening']),int(p['seat']),float(p['angle']),float(p['gap']),p['actor'],p['rival']) != (
            n['id'],n['rtt'],n['pair'],n['seat'],n['angle'],n['gap'],s['actor'],s['rival']):
            raise ValueError('Native opened a different bout: '+p['id'])
        if s['qualified']:
            cells.append(p | {'points':s['score']})
    actors=list(dict.fromkeys(r['actor'] for r in cells if r['actor']!='wtap'))
    rows=[]
    for actor in actors:
        for ms in ('0','100'):
            for clock in ('fixed','phase','empirical'):
                row=dict(actor=actor,rtt=ms,clock=clock)
                for label, arm in [('learned',actor),('wtap','wtap')]:
                    cohort=[r for r in cells if r['actor']==arm and r['rtt']==ms]
                    row[label+'_games']=len(cohort)
                    row[label+'_java_points']=sum(r['points'] for r in cohort)
                    row[label+'_expected_points']=round(sum(float(r[clock]) for r in cohort),5)
                    row[label+'_mse']=round(statistics.mean((float(r[clock])-r['points'])**2 for r in cohort),6)
                rows.append(row)
    write_csv(panel/'cadence-scores.csv',rows)
    print(panel.name,len(cells),{c:round(statistics.mean((float(r[c])-r['points'])**2 for r in cells),6) for c in ('fixed','phase','empirical')})


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('make-bank','forecast','score'))
    parser.add_argument('directory',type=Path)
    args=parser.parse_args()
    {'make-bank':make_bank,'forecast':forecast,'score':score}[args.action](args.directory)
