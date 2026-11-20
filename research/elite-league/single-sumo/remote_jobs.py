"""Start or resume the registered independent PPO and evaluation workers on the simulator host."""
import json
import subprocess
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
SCRIPTS=Path('research/elite-league/single-sumo')
PYTHON=Path.home()/'mc-sumo-league/venv/bin/python'
OUT=ROOT/SCRIPTS/'evidence/robustness-001'


def start(name,script,args,receipt):
    unit='mc-sumo-r001-'+name
    if (OUT/receipt).is_file():return
    if subprocess.run(['systemctl','is-active','--quiet',unit]).returncode==0:return
    if subprocess.run(['systemctl','is-failed','--quiet',unit]).returncode==0:
        subprocess.run(['sudo','-n','systemctl','reset-failed',unit],check=True)
    subprocess.run(['sudo','-n','systemd-run','--quiet','--unit='+unit,'--uid='+Path.home().name,
                  '--working-directory='+str(ROOT),'-p','Restart=on-failure','-p','RestartSec=8','-p','StartLimitBurst=3',
                  '-p','StartLimitIntervalSec=120','--setenv=OMP_NUM_THREADS=1','--setenv=OPENBLAS_NUM_THREADS=1',
                  '--setenv=MKL_NUM_THREADS=1',str(PYTHON),'-u',str(SCRIPTS/script),*args],check=True)


def frontier(role,split):
    folder=Path('stationary-field')
    if role=='strong-router':folder=folder/'strong-opening'
    if split=='prospective':
        if role in ('router','strong-router'):folder=folder/'prospective'
        elif role.endswith('-main'):folder=folder/'successors'/role/'prospective'
        else:folder=folder/'ppo-prospective'
    if role=='controls':receipt=folder/'frontier-controls/done.json'
    else:
        if role not in ('router','strong-router'):folder=folder/'continuous'/role
        receipt=folder/'frontier-check/result.json'
    start('fixed-grade-'+role+'-'+split,'stationary_frontier.py',[role,split],receipt)


if __name__=='__main__':
    if sys.argv[1:2]==['frontier']:
        frontier(*sys.argv[2:])
        sys.exit(0)
    for role in ('mlp','gru','gru-aux'):
        start(role,'remote_league.py',[role],role+'/result.json')
        start('field-'+role,'remote_round.py',[role],role+'/common-field.json')
    start('reversal','remote_reversal.py',[],'response-reversal/result.json')
    start('payoff-pair','payoff_pair.py',[],'payoff-pair/parity.json')
    if (OUT/'payoff-pair/development.json').is_file():
        start('payoff-oracle','payoff_oracle.py',[],'payoff-pair/oracle-development.json')
    start('reversal-head24','remote_reversal.py',['head24'],'response-reversal-head24/result.json')
    start('tournament','remote_round.py',[],'tournament-result.json')
    for role in ('parent','gru','gru-aux'):
        start('history-'+role,'remote_analysis.py',[role],'history/'+role+'/result.json')
    ready=OUT/'prospective/calendars/manifest.json'
    if ready.is_file():
        for role in ('gru','gru-aux'):
            start('prospective-'+role,'remote_analysis.py',[role,'prospective'],'history-prospective/'+role+'/result.json')
    if (OUT/'prospective-head24/calendars/manifest.json').is_file():
        for role in ('gru','gru-aux'):
            start('head24-'+role,'remote_analysis.py',[role,'prospective-head24'],'history-prospective-head24/'+role+'/result.json')
    if (OUT/'payoff-pair/prospective/calendars/manifest.json').is_file():
        start('payoff-fresh','payoff_pair.py',['prospective'],'payoff-pair/prospective-result.json')
    if (OUT/'weak-matchups/entrants.json').is_file():
        start('weak-curriculum-field','weak_field.py',['evaluate'],'weak-matchups/model.json')
    if (OUT/'stationary-field/protocol.json').is_file():
        strong=Path('stationary-field/strong-opening')
        if (OUT/strong/'protocol.json').is_file():
            start('strong-opening','stationary_field.py',['run','--strong-opening'],strong/'dev-result.json')
            if (OUT/strong/'prospective/calendars/manifest.json').is_file():
                start('strong-fresh','stationary_field.py',['prospective','--strong-opening'],strong/'prospective-result.json')
        if (OUT/'stationary-field/frontier-protocol.json').is_file():frontier('controls','dev')
        if (OUT/'stationary-field/opening-timing/protocol.json').is_file():
            start('opening-timing','stationary_headroom.py',[],'stationary-field/opening-timing/result.json')
        series=Path('stationary-field/series-ten')
        if (OUT/series/'protocol.json').is_file():
            for split,reps in [('train',16),('dev',4)]:
                start('series-bank-'+split,'series_bank.py',[split],series/split/f'bank-{reps:02}.json')
            if (OUT/series/'training.json').is_file():
                for horizon in (-1,0,1,3,9):
                    start('series-fit-'+str(horizon),'series_learn.py',[str(horizon)],series/'learn-16'/f'h{horizon}-s4103/trained.json')
                start('series-eval','series_eval.py',['dev'],series/'dev/evaluation-16.json')
                if (ROOT/SCRIPTS/'series_pair.py').is_file():
                    start('series-pair','series_pair.py',[],series/'pair-drill/dev/evaluation-02.json')
                if (OUT/series/'crossing-drill/selection-rule.json').is_file():
                    start('series-crossing','series_crossing.py',[],series/'crossing-drill/dev/unrestricted-controls.json')
                if (OUT/series/'memory-tests.json').is_file():
                    for task in ('field','pair-drill','crossing-drill'):
                        folder=series if task=='field' else series/task
                        receipt='whole-memory-16.json' if task=='field' else 'whole-memory-02.json'
                        start('series-memory-'+task,'series_memory.py',[task],folder/'dev'/receipt)
                reversal=series/'archive-reversal'
                if (OUT/reversal/'protocol.json').is_file():
                    start('series-archive','series_archive.py',[],reversal/'dev/whole-memory-16.json')
                    if (OUT/reversal/'prospective/calendars/manifest.json').is_file():
                        start('series-archive-fresh','series_archive.py',['fresh'],reversal/'fresh/unrestricted.json')
                if (OUT/series/'belief/protocol.json').is_file():
                    start('series-belief','series_bayes.py',['dev'],series/'belief/dev/evaluation-16.json')
                if (OUT/series/'prospective/calendars/manifest.json').is_file():
                    start('series-bank-fresh','series_bank.py',['fresh'],series/'fresh/bank-04.json')
                    start('series-eval-fresh','series_eval.py',['fresh'],series/'fresh/evaluation-16.json')
                    if (OUT/series/'belief/protocol.json').is_file():
                        start('series-belief-fresh','series_bayes.py',['fresh'],series/'belief/fresh/evaluation-16.json')
        start('stationary-router','stationary_field.py',['run'],'stationary-field/dev-result.json')
        for role in ('controls','parent','gru','gru-aux'):
            end='done.json' if role=='controls' else 'result.json'
            start('stationary-'+role,'stationary_ppo.py',[role],'stationary-field/continuous/'+role+'/'+end)
        if (OUT/'stationary-field/ppo-prospective/calendars/manifest.json').is_file():
            roles=json.loads((OUT/'stationary-field/ppo-prospective/assay.json').read_text())['roles']
            for role in ('controls',*roles):
                end='done.json' if role=='controls' else 'result.json'
                start('stationary-fresh-'+role,'stationary_ppo.py',[role,'prospective'],'stationary-field/ppo-prospective/continuous/'+role+'/'+end)
        if (OUT/'stationary-field/prospective/calendars/manifest.json').is_file():
            start('stationary-transfer','stationary_field.py',['prospective'],'stationary-field/prospective-result.json')
        for pair in sorted((OUT/'stationary-field/successors').glob('g*-main/pair.json')):
            role=pair.parent.name
            start('stationary-'+role,'stationary_ppo.py',[role],'stationary-field/continuous/'+role+'/result.json')
            if (pair.parent/'prospective/calendars/manifest.json').is_file():
                receipt=str((pair.parent/'prospective/continuous'/role/'result.json').relative_to(OUT))
                start('stationary-fresh-'+role,'stationary_ppo.py',[role,'prospective'],receipt)
