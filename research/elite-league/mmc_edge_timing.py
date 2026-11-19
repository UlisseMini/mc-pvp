"""Compare two legal intervention times from the same regulation starts on development clocks."""
from collections import defaultdict
import gzip
import json
from statistics import median

from adaptation import dump, sha
from mmc_league import EVIDENCE
from mmc_scorecard import record

DEST = EVIDENCE / 'edge-recovery'
DIMENSIONS = ('arm', 'opponent', 'clock', 'side', 'rtt')


def load(path):
    with gzip.open(path, 'rt') as stream:
        rows = list(map(json.loads, stream))
    indexed = {tuple(row[k] for k in DIMENSIONS): row for row in rows}
    if len(indexed) != len(rows) or len(rows) != 384:
        raise ValueError('Incomplete original bout grid')
    return indexed


def score(row, name):
    return row['branches'][name][0] if row.get('branches') else row['base']


def summarize(rows):
    records = {arm: record([r[arm] for r in rows]) for arm in
               ('plain_wtap', 'original', 'early_strafe', 'late_strafe', 'early_wtap', 'late_wtap')}
    diffs = [r['early_strafe'] - r['late_strafe'] for r in rows]
    lags = [r['gap_ms'] for r in rows if r['gap_ms'] is not None]
    return dict(games=len(rows), outcomes=records, early_over_late=dict(
        better=sum(v > 0 for v in diffs), tied=diffs.count(0), worse=sum(v < 0 for v in diffs),
        points=sum(diffs)), observed_delay_ms_min_median_max=[min(lags), median(lags), max(lags)] if lags else [])


def run():
    files = [DEST / f'radius-{r}.jsonl.gz' for r in (3.5, 5.0)]
    early, late = map(load, files)
    if early.keys() != late.keys():
        raise ValueError('Different schedules')
    cells = defaultdict(list)
    for k, e in early.items():
        arm, foe, clock, side, rtt = k
        l = late[k]
        if any(e[field] != l[field] for field in ('base','end','fresh')):
            raise ValueError('Different untreated trajectory')
        e_hit = bool(e.get('branches'))
        l_hit = bool(l.get('branches'))
        if l_hit and (not e_hit or l['at'] < e['at']):
            raise ValueError('Later crossing preceded the early landmark')
        if l_hit and l['at'] == e['at'] and (l['local'] != e['local'] or l['branches'] != e['branches']):
            raise ValueError('Same input produced different continuations')
        status = 'both' if l_hit else 'early_only' if e_hit else 'neither'
        values = dict(plain_wtap=early['wtap',foe,clock,side,rtt]['base'], original=e['base'],
                      early_strafe=score(e,'center-strafe-6'), late_strafe=score(l,'center-strafe-6'),
                      early_wtap=score(e,'wtap'), late_wtap=score(l,'wtap'),
                      gap_ms=l['at']-e['at'] if l_hit else None)
        for cohort in ('all',status):
            for split in ('all',f'{rtt}/{side}',f'clock-{clock}'):
                cells[f'{arm}/{foe}/{cohort}/{split}'].append(values)
    dump(DEST/'early-vs-late.json', dict(sources={p.name:sha(p) for p in files}, comparisons=(
        'Each pair starts from the same full fight. Each rule plays the original until its own observed '
        'trigger, or the whole regulation bout if it never triggers. For both, early_only and neither, '
        'membership is defined by the one unmodified trajectory. Plain W-tap plays the same calendar, seat '
        'and RTT from spawn. Early/late W-tap take over with primed history at the respective landmark. '
        'Scores from eight exposed development clocks. No native timing intervention in this study.'),
        cells={k:summarize(v) for k,v in sorted(cells.items())}))


if __name__ == '__main__':
    run()
