"""Start or resume the registered independent PPO and evaluation workers on the simulator host."""
import subprocess
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


if __name__=='__main__':
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
