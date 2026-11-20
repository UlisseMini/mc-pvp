"""Delegate a centrally registered PPO run to the owner's simulator host and verify its frozen return."""
import fcntl
import hashlib
import json
import shlex
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from learner import BUILD, ROOT, atomic, freeze, write_json
from league_tournament import pin_model
from train import fit

HOST='uli@hetzner1'
REMOTE='mc-sumo-league/runs/robust-001'
SCRIPT='research/elite-league/single-sumo/remote_fit.py'
SSH=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=12',HOST]
RSYNC=['rsync','-az','-e','ssh -o BatchMode=yes -o ConnectTimeout=12']


def read(path):return json.loads(path.read_text())
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def enabled():return (BUILD/'hetzner/enabled.json').is_file()


def execute(relative,total):
    path=ROOT/relative
    with (path/'remote-execution.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if (path/'remote-result.json').exists():return
        fit(path,total)
        if read(path/'progress.json')['duels']!=64*total:raise ValueError('Remote PPO overshot or missed its budget')
        frozen=pin_model(path)
        write_json(path/'remote-result.json',dict(policy=frozen,updates=total,config_sha256=digest(path/'config.json'),
                    checkpoint_sha256=digest(path/'trainer.pt'),source='hetzner1'),fixed=True)


def invoke(args):
    for retry in range(4):
        result=subprocess.run(args,text=True,capture_output=True)
        if result.returncode==0:return result.stdout.strip()
        if retry==3:raise RuntimeError(result.stderr.strip() or str(args[:2]))
        time.sleep(2**retry)


def delegate(path,total):
    relative=path.resolve().relative_to(ROOT)
    if not str(relative).startswith('research/elite-league/single-sumo/evidence/'):raise ValueError(relative)
    cfg=read(path/'config.json')
    inputs={relative/'config.json',Path(SCRIPT)}
    sources=[cfg['incumbent'],cfg['counter'],*cfg.get('threats',[]),*[n for a in cfg['families'].values() for n in a]]
    inputs.update(Path(n) for n in sources if n.endswith('.net'))
    if cfg['warm']:inputs.add(Path(cfg['warm']))
    if any(p.is_absolute() or '..' in p.parts or not (ROOT/p).is_file() for p in inputs):
        raise ValueError('Remote job requires repository-relative frozen inputs')
    unit='mc-sumo-fit-'+hashlib.sha256((str(relative)+':'+str(total)+':'+digest(path/'config.json')).encode()).hexdigest()[:16]
    with tempfile.TemporaryDirectory(dir=BUILD) as tmp:
        tmp=Path(tmp);manifest=tmp/'inputs'
        manifest.write_text(''.join(str(p)+'\n' for p in sorted(inputs)))
        checks=tmp/(unit+'.sha256');checks.write_text(''.join(f'{digest(ROOT/p)}  {p}\n' for p in sorted(inputs)))
        invoke([*RSYNC,'--files-from='+str(manifest),str(ROOT)+'/',HOST+':'+REMOTE+'/'])
        invoke([*RSYNC,str(checks),HOST+':'+REMOTE+'/'])
        remote_dir='/home/uli/'+REMOTE
        q=shlex.quote
        launch=['sudo','-n','systemd-run','--quiet','--unit='+unit,'--uid=uli','--working-directory='+remote_dir,
                 '-p','Restart=on-failure','-p','RestartSec=10','-p','StartLimitBurst=3','-p','StartLimitIntervalSec=120',
                 '--setenv=OPENBLAS_NUM_THREADS=1','--setenv=OMP_NUM_THREADS=1','--setenv=MKL_NUM_THREADS=1',
                 '/home/uli/mc-sumo-league/venv/bin/python','-u',SCRIPT,'worker',str(relative),str(total)]
        done=str(relative/'remote-result.json')
        registered='cd '+q(remote_dir)+' && sha256sum --quiet -c '+q(checks.name)
        recover='if systemctl is-failed --quiet '+q(unit)+'; then sudo -n systemctl reset-failed '+q(unit)+'; fi'
        command=registered+' && if ! test -f '+q(done)+' && ! systemctl is-active --quiet '+q(unit)+'; then '+recover+'; '+shlex.join(launch)+'; fi'
        invoke([*SSH,command])
        receipt=path/'delegation.json'
        frozen_inputs={str(p):digest(ROOT/p) for p in sorted(inputs) if str(p)!=SCRIPT}
        if not receipt.exists():
            write_json(receipt,dict(host=HOST,unit=unit,updates=total,inputs={**frozen_inputs,SCRIPT:digest(ROOT/SCRIPT)}),fixed=True)
        else:
            old=read(receipt)
            if old['unit']!=unit or any(old['inputs'].get(p)!=v for p,v in frozen_inputs.items()):
                raise ValueError('Registered training assets have changed')
        while True:
            status=invoke([*SSH,'cd '+q(remote_dir)+' && if test -f '+q(done)+'; then echo complete; else systemctl show -p ActiveState --value '+q(unit)+'; fi'])
            if status=='complete':break
            if status in ('failed','inactive'):raise RuntimeError('Remote PPO stopped before writing its receipt: '+unit)
            time.sleep(20)
        outputs=['final.net','trainer.pt','progress.json','remote-result.json']
        manifest.write_text(''.join(n+'\n' for n in outputs))
        invoke([*RSYNC,'--files-from='+str(manifest),HOST+':'+REMOTE+'/'+str(relative)+'/',str(tmp)+'/'])
        receipt=read(tmp/'remote-result.json')
        if receipt['updates']!=total or receipt['checkpoint_sha256']!=digest(tmp/'trainer.pt') or receipt['config_sha256']!=digest(path/'config.json') or receipt['policy']['sha256']!=digest(tmp/'final.net'):
            raise ValueError('Remote result does not match the registered run')
        for n in ('trainer.pt','progress.json'):atomic(path/n,(tmp/n).read_bytes())
        atomic(path/'current.net',(tmp/'final.net').read_bytes())
        freeze(path/'final.net',(tmp/'final.net').read_bytes())
        freeze(path/'remote-result.json',(tmp/'remote-result.json').read_bytes())


if __name__=='__main__':
    if sys.argv[1]=='worker':execute(Path(sys.argv[2]),int(sys.argv[3]))
    else:raise ValueError(sys.argv[1])
