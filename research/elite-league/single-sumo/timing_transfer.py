"""Replay the wider specialist test with only its timing recordings changed."""
import json
from statistics import mean, stdev
from scipy.stats import t
from learner import CLOCKS, write_json
from long_confirm import read, score
from midpoint_check import DEST, SEED


def main():
    case = DEST/'incumbent'
    out = case/'clock-only'
    reference = read(DEST/'protocol.json')
    fixture_count = 1536
    clocks = CLOCKS['dev'] * 3
    if len(clocks) != len(reference['donors']) or set(clocks) & set(reference['donors']):
        raise ValueError('Timing panels must use disjoint recordings at matching fixture indices')
    write_json(out/'protocol.json',dict(original=reference['donors'],replacement=clocks,
        held_fixed='Frozen 16k and 32k specialists, original training floor, opponent, both RTTs, actor seats, action seeds and repetitions at seven CPS.',
        comparison='The 24 previously exposed recordings are replaced by three copies of eight development recordings. Pair corresponding donor slots across capture periods for descriptive intervals.',
        scope='Retrospective model diagnosis of timing transfer, without changing training or opening sealed maps.'),fixed=True)
    score('incumbent',32768,clocks,SEED,4,out)
    report={}
    for arm in ('32768','16384','wtap'):
        report[arm]={}
        for foe in ('target','wtap'):
            a=read(out/arm/(foe+'.json'))
            b=read(case/arm/(foe+'.json'))
            rows=list(zip(a['games'],b['games'],strict=True))
            if len(rows)!=fixture_count or any(left[:6]!=right[:6] for left,right in rows):
                raise ValueError('Different seats, RTTs or repetition indices')
            delta=[int(right[6]==1)-int(left[6]==1) for left,right in rows]
            donors=[mean(v for v,(row,_) in zip(delta,rows) if row[0]%8==index) for index in range(8)]
            diff=mean(donors)
            half=float(t.ppf(.975,7))*stdev(donors)/8**.5
            count=lambda r:{k:r[k] for k in ('wins','draws','losses')}
            report[arm][foe]=dict(development=count(a),wider_clocks=count(b),win_change=diff,
                                  paired_slots=donors,approximate_95_percent_interval=[diff-half,diff+half],
                                  by_period=[mean(v for v,(row,_) in zip(delta,rows) if row[0]//8==i) for i in range(3)])
    write_json(out/'comparison.json',report,fixed=True)
    print(json.dumps({arm:r['target'] for arm,r in report.items()}),flush=True)


if __name__=='__main__':
    main()
