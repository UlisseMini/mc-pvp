"""Train direct-control actors against the corrected MMC sumo physics and their weakest opponents."""
from pathlib import Path
import json
import subprocess
import tempfile
import gzip

from adaptation import dump,freeze,sha
import champions
import focus
from ladder import roster_fields
from mmc_click_bias import ranking,PARENT
from mmc_trade_fit import pool,ACTOR,PREVIOUS
from mmc_league import FROZEN,HERE,WORK
from run import ROOT,compile_model,java,calendars
from tournament import grid

DEST=HERE/'recorded-mmc-frozen'
TEMP=ROOT/'build/elite-league/recorded-mmc'
PROFILE='mmc-recorded'

def evaluate(path,arms,foes,clocks,profile=PROFILE):
    if not path.exists():
        with tempfile.TemporaryDirectory(dir=TEMP) as d:
            freeze(path,grid(Path(d),'crossplay',arms,foes,clocks,'mouse',profile).read_bytes())
    with gzip.open(path,'rt') as f:
        for row in map(json.loads,f):
            if (row['profile'],row['diameter'],row['start_gap'],row['aim_input'])!=(profile,13,8,'mouse'):raise ValueError('Unexpected calibrated fixture')
    return ranking(path,arms,foes,len(clocks))

def publish(alias,arm,field):
    champions.install(alias,focus.source(arm,field[arm]),'Recorded MMC sumo tournament winner ('+arm+')',alias)

def train():
    TEMP.mkdir(parents=True,exist_ok=True);DEST.mkdir(exist_ok=True)
    field=pool()|focus.BASE
    field.update({PARENT:FROZEN/'mmc13-trades'/(PARENT+'.net'),'mmc13-contact-16':FROZEN/'mmc13-contact-gate/mmc13-contact-16.net'})
    entrants={n:field[n] for n in ('wtap','elite-population','continuation-03-fit','continuation-01-fit',ACTOR,PARENT,'mmc13-contact-16')}
    target_names=('wtap','hold','adapt-01-pair','adapt-04-pair','continuation-02-fit','continuation-03-fit',PREVIOUS,ACTOR,PARENT,'elite-population')
    foes={n:field[n] for n in target_names}
    train=calendars('training_clocks');dev=calendars('development_selection_clocks')
    dump(DEST/'protocol.json',dict(physics=sha(HERE.parent/'mmc-knockback/fit.json'),fixture=PROFILE,
        candidates={n:focus.identity(n,p) for n,p in entrants.items()},opponents={n:focus.identity(n,p) for n,p in foes.items()},
        train=[p.name for p in train],dev=[p.name for p in dev],
        objective='Regulation ring-out. Give every incumbent a common ten-opponent cross-play. Include plain W-tap in all panels. Select by W-tap win floor then robust mean, tail and worst score. Train on distinct clocks with self-play, previous champions, W-tap, and the selected two weakest distinct opponents each round. Every fit and broad tournament winner is published. Partition evaluation clocks by donor. Human elite performance requires independent participants.',
        optimization='Two 384-update PPO rounds from independent eight-unit MLP starts, each with 24 scenario pairs per update. All five action heads, ordinary mouse. Per generation both seats and matched W-tap and Hold-W controls. One-third of scenario variety comes from perturbed openings and asymmetric latency. Round 2 includes round 1 graduates.'))
    compile_model()
    ranks=evaluate(DEST/'incumbents.jsonl.gz',entrants,foes,dev)
    dump(DEST/'incumbents.json',ranks);publish('minemen-v3-incumbents',ranks[0]['name'],entrants)
    print('Old field, measured law:',ranks[0]['name'],flush=True)
    for number,source in enumerate((ACTOR,'elite-population'),1):
        d=DEST/f'round-{number:02d}';d.mkdir(exist_ok=True)
        tmp=TEMP/f'round-{number:02d}';tmp.mkdir(exist_ok=True);updates=tmp/'updates'
        weakness=[n for n in ranks[0]['weakest'] if focus.identity(n,foes[n])!=focus.identity(ranks[0]['name'],entrants[ranks[0]['name']])][:2]
        league=field|entrants
        separate={'wtap',*weakness,ranks[0]['name']}
        strata={n:(n if n in separate else 'script' if p is None else 'switch' if p.read_text().startswith('PAIR.') else 'other learned') for n,p in league.items()}
        dump(d/'fit-protocol.json',dict(start=source,weak=weakness,seed=769711+number,groups=strata))
        if not (updates/'g0383.net').exists():
            with tempfile.TemporaryDirectory(dir=tmp) as t:
                t=Path(t);roster_fields(t/'field',league,{n:g.replace(' ','-') for n,g in strata.items()},{})
                subprocess.run(java('PPO',t/'updates',384,24,769711+number,field[source],HERE/'frozen',HERE/'challenge-frozen',
                    '--recorded-mouse-league',t/'field',*train),cwd=ROOT,check=True)
                (t/'updates').rename(updates)
        checkpoints={p.stem:p for p in sorted(updates.glob('*.net'))}
        small={n:foes[n] for n in dict.fromkeys(['wtap',*weakness,'hold',PARENT])}
        screen=entrants|checkpoints
        sr=evaluate(d/'screen.jsonl.gz',screen,small,train[::2]);screen_winner=sr[0]['name']
        publish(f'minemen-v3-ppo-{number:02d}-screen',screen_winner,screen)
        shortlist=[r['name'] for r in sr if r['name'] in checkpoints][:4]
        for n in shortlist:
            name=f'v3-ppo-{number:02d}-{n}';p=d/(name+'.net');freeze(p,checkpoints[n].read_bytes());entrants[name]=p
        ranks=evaluate(d/'development.jsonl.gz',entrants,foes,dev)
        winner=ranks[0]['name'];best=next(r['name'] for r in ranks if r['name'].startswith(f'v3-ppo-{number:02d}-'))
        champions.install(best,entrants[best],'Direct PPO trained on recorded MMC impulses',best,'direct')
        publish(f'minemen-v3-ppo-{number:02d}',winner,entrants)
        dump(d/'selection.json',dict(winner=winner,student=best,ranks=ranks,screen=sr,selection_clocks=[p.name for p in dev]))
        dump(d/'training.json',json.loads((updates/'training.jsonl').read_text().splitlines()[-1]))
        entrants={n:p for n,p in entrants.items() if n not in {f'v3-ppo-{number:02d}-{q}' for q in shortlist} or n in (best,winner)}
        foes[best]=entrants[best];field[best]=entrants[best]
        print('PPO round',number,winner,best,flush=True)

if __name__=='__main__':train()
