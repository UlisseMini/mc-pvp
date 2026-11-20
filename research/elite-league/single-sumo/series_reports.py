"""Collect completed series scorecards without copying the large simulator banks back to the lab."""
import shlex
import time

from learner import ROOT,atomic
from remote_fit import SSH,REMOTE,invoke
from remote_import import retrieve
from remote_league import OUT,read
from series_bank import BUDGETS,FOLDER


def planned():
    files=[]
    for b in BUDGETS:
        files.extend((f'train/bank-{b:02}.json',f'dev/evaluation-{b:02}.json',f'dev/whole-memory-{b:02}.json',f'belief/dev/evaluation-{b:02}.json'))
    for name in ('pair-drill','crossing-drill'):
        files.extend(f'{name}/dev/{f}' for f in ('evaluation-02.json','whole-memory-02.json'))
    for b in (2,8):
        files.extend(f'diverse-experts/dev/{f}-{b:02}.json' for f in ('evaluation','whole-memory','library'))
    for b in (2,16):
        files.extend(f'archive-reversal/dev/{f}-{b:02}.json' for f in ('evaluation','whole-memory'))
    files.extend(f'{prefix}ringout-reward/dev/reward-comparison.json' for prefix in ('','archive-reversal/'))
    files.extend(f'{prefix}hidden-score/dev/result.json' for prefix in ('','archive-reversal/'))
    files.append('archive-reversal/hidden-score/fresh/result.json')
    files.extend(f'{prefix}hidden-score/{split}/public-controls.json' for prefix in ('','archive-reversal/') for split in ('dev','fresh'))
    files.extend(f'{prefix}hidden-score/{split}/second-game-cues.json' for prefix in ('','archive-reversal/') for split in ('dev','fresh'))
    files.extend(['archive-reversal/dev/unrestricted.json','archive-reversal/fresh/evaluation-16.json',
                  'archive-reversal/fresh/whole-memory-16.json','archive-reversal/fresh/unrestricted.json',
                  'archive-reversal/ringout-reward/fresh/reward-comparison.json'])
    for prefix,last in [('',16),('archive-reversal/',16),('diverse-experts/',8)]:
        files.extend(f'{prefix}dev/displayed-clock-{b:02}.json' for b in (2,last))
    return files


def report():
    lines=['# Ten-game series results','',
        'Counts are outright series wins / ties / losses. Each comparison plays the same ten-game schedules, with seven CPS on both sides. The fixed W-tap column uses those same opponents and fixtures. The broad and restricted experiments have different opponent sets.',
        '', '| Study and checkpoint | Series | Nine-game GRU | Scoreboard only | Blind | Ping lookup | Plain W-tap |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    fmt=lambda values:'/'.join(f'{n:,}' for n in values)
    groups=[('', '27 opponents, eight experts',BUDGETS),('diverse-experts/','27 opponents, ten experts',(2,8)),
            ('pair-drill/','Two opponents, eight experts',(2,)),('crossing-drill/','Two recently trained experts',(2,)),
            ('archive-reversal/','Two historical experts',(2,16)),('targeted-response/','27 opponents with new counter',(2,8)),
            ('targeted-response/hard-curriculum/','27 opponents with new counter and extra hard-opponent practice',(2,8))]
    for prefix,name,budgets in groups:
        for b in budgets:
            path=FOLDER/prefix/'dev'/f'evaluation-{b:02}.json'
            if not path.is_file():continue
            d=read(path);row=[fmt(d['scores'][r]['series']) for r in ('memory-9','memory-0','blind','train-ping-lookup','wtap')]
            lines.append('| ['+name+f', {b} training schedules]('+str(path.relative_to(FOLDER))+f') | {d["series_per_arm"]} | '+' | '.join(row)+' |')
    lines.extend(['','The detailed scorecards also contain individual ring-outs, matchup results, both seats, RTT pairs and timing-recording intervals. Native ring-outs are recorded under each study’s native directory. Independent elite human games have their own grade.',''])
    atomic(FOLDER/'results.md','\n'.join(lines).encode())


def run():
    prefix=str(FOLDER.relative_to(OUT))+'/'
    expected=planned()
    previous=None;remote_root=REMOTE+'/'+str(FOLDER.relative_to(ROOT))
    while True:
        missing=[n for n in expected if not (FOLDER/n).is_file()]
        if not missing:report();return
        command='cd '+shlex.quote(remote_root)+' && for file in '+shlex.join(missing)+'; do if test -f "$file"; then echo "$file"; fi; done'
        ready=invoke([*SSH,command]).splitlines()
        if any(n not in missing for n in ready):raise ValueError('Unexpected scorecard')
        if ready:retrieve([prefix+n for n in ready])
        observed=frozenset(n for n in expected if (FOLDER/n).is_file())
        if observed!=previous:report();previous=observed
        time.sleep(45)


if __name__=='__main__':run()
