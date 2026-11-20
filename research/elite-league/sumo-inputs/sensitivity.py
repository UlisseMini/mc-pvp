"""Summarize paired model bouts and key changes on actual recorded client observations."""
import csv
import statistics
from collections import defaultdict
from live import D, WT, ping, read
from report import grouped, native, pct, pt, score, wld
from train import ARMS, SEEDS


def by_foe(models, arm, foe):
    return [value for seed in SEEDS for key, value in models[f'{arm}-{seed}'].items() if key[0] == foe]


def average_ranks(values):
    points = sorted(values)
    return [1 + (points.index(v) + len(points) - 1 - points[::-1].index(v))/2 for v in values]


def main():
    models = grouped(csv.DictReader((D / 'transfer.csv').open()))
    old, blind = models[ping.OLD], models['incumbent-blind']
    out = ['# What the ablations changed', '', '## Which matchups need motion and hurt information?', '',
           'Each trained cell pools the three matched seeds on six clocks and both seats at four link pairs. Scores give half credit for draws. The control columns use a single fixed policy on the same settings.', '',
           '| Foe | Base | Without own velocity | Without visible opponent displacement | Without hurt ages | Incumbent | Plain W-tap |',
           '|---|---:|---:|---:|---:|---:|---:|']
    for foe in sorted({k[0] for k in old}):
        trained = [pct(score(by_foe(models, arm, foe))) for arm in ('base8', 'noself8', 'nofoe8', 'nohurt8')]
        controls = [pct(score([v for k,v in models[n].items() if k[0]==foe])) for n in (ping.OLD, 'wtap')]
        out.append('| ' + ' | '.join([foe, *trained, *controls]) + ' |')

    out += ['', '## Can the incumbent use its own RTT reading?', '',
            'Both copies play each identical physical scenario. The neutral copy always receives the feature value for 80 ms. Therefore the 80/80 cell is an exact negative control. Records are wins–losses–draws. Improved and worsened include win–draw and draw–loss transitions.', '',
            '| Added RTT, self / foe | Accurate reading | Neutral reading | Score difference | Improved / same / worsened | Plain W-tap |',
            '|---|---:|---:|---:|---:|---:|']
    for own, other in [(0,0),(80,80),(31,109),(109,31)]:
        keys = [k for k in old if k[3:] == (own,other)]
        a, b, wt = [[model[k] for k in keys] for model in (old, blind, models['wtap'])]
        transitions = [sum((x>y)-(x<y)==sign for x,y in zip(a,b)) for sign in (1,0,-1)]
        out.append('| ' + ' | '.join([f'{own} / {other}', wld(a), wld(b), pt(score(a)-score(b)),
                                    ' / '.join(map(str,transitions)), wld(wt)]) + ' |')
    assert all(old[k] == blind[k] for k in old if k[3] == 80)
    by_calendar = [statistics.mean(old[k]-blind[k] for k in old if k[1]==c) for c in sorted({k[1] for k in old})]
    out += ['', 'Across the six simulator clock donors, the changes in points for an accurate own RTT reading were '+
            ', '.join(pt(x) for x in by_calendar)+'. Physical clocks and link values were unchanged within each pair.']

    out += ['', '## Which keys change when a reading disappears?', '',
            'These are counterfactual decisions on the actual, unmodified native trajectory. Each row holds the observed positions, physical inputs so far, and previous applied action fixed. Only the named feature group changes at inference. An action can change on multiple heads. This counts a jump only when it would be requested on the ground.', '',
            '| Native actor | Reading disabled | Decisions | Any key | Forward / sprint | Strafe | Click gate | Jump | Matched W-tap screen record |',
            '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    totals = defaultdict(lambda:[0]*6)
    for r in csv.DictReader((D / 'action-interventions.csv').open()):
        v = totals[r['arm'],r['disabled'],r['phase']]
        for i, col in enumerate(('decisions','changed','forward_or_sprint','strafe','click','jump')):
            v[i] += int(r[col])
    scr = native('screen')
    wtap_native = wld(list(scr[WT].values()))
    for (arm, group, phase),v in sorted(totals.items()):
        if phase != 'all': continue
        assert all(sum(x[i] for (a,g,p),x in totals.items() if a==arm and g==group and p!='all')==v[i] for i in range(6))
        out.append('| '+' | '.join([arm, group, f'{v[0]:,}', *[pct(x/v[0]) for x in v[1:]], wtap_native])+' |')
    out += ['', 'The base8 actor used its velocity differently in the 150 ms after a visible hurt cue.', '']
    for phase, label in [('own_recent','only own hurt was recent'),('opponent_recent','only opponent hurt was recent'),
                         ('both_recent','both hurt cues were recent'),('neither_recent','neither hurt cue was recent')]:
        n, change, *_ = totals['sumo-input-base8','own-motion',phase]
        if n: out.append(f'- When {label}, {change:,} of {n:,} decisions changed ({pct(change/n)}).')

    names = read(D / 'entrants.json')['representatives']
    model_points, native_points = [], []
    wt_pairs = ('wtap','shift-check-v4-4')
    for alias,model in names.items():
        model_points.append(score([v for k,v in models[model].items() if k[0] in wt_pairs and k[3] in (31,109)]))
        native_points.append(score(list(scr[alias].values())))
    similarity = statistics.correlation(average_ranks(model_points), average_ranks(native_points))
    out += ['', '## Simulator ordering and Minecraft ordering','',
            f'Across the {len(names)} preselected trained entrants, the Spearman correlation between simulator and Minecraft scores on W-tap and shift-check was {similarity:.2f}. Both scores use the same opponents, physical RTTs and seats. The simulator uses six independent clock donors, while Minecraft has one new bout per opponent, link and seat. The correlation describes this small screen.','']
    (D / 'sensitivity.md').write_text('\n'.join(out))

if __name__ == '__main__':
    main()
