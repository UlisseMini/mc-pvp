"""Retrieve only completed immutable artifacts from the registered remote experiments."""
import time

from remote_import import retrieve
from remote_league import OUT
from specialist_diagnosis import DEST

PREFIX=str(DEST.relative_to(OUT))+'/'


def groups():
    yield ['single-duel/dev.json','ablation/dev.json','new-opponents/results.json']
    for seed in (5213,5214,5215):
        for size in ((1,2,8) if seed==5213 else (8,)):
            yield [f'dev/decoder-{seed}-n{size}.json']
    for group in ('small','seven'):
        for seed in (22819,22820,22821):
            for step in ((128,512) if group=='small' else (128,512,1024)):
                folder=f'expert-population/{group}/seed-{seed}/'
                yield [folder+role+f'/u{step}.pt' for role in ('memory','blind')]+[folder+f'dev-u{step}.json']
    yield ['expert-population/small/second-game.json']
    yield ['expert-population/single-bout/dev.json']
    for size in (1,10):
        for ch in (9,12):
            sub=f'single-duel/visible-target-motion/n{size}-c{ch}/'
            yield [sub+f'h{h}.pt' for h in (8,24,48)]+[sub+'result.json']
    for m in ('mmc13','mmc-221'):
        yield [f'expert-population/fresh-seeds-and-map/{m}/result.json']
    for mode in ('joint','router-only'):
        for step in (0,64,256):
            prefix=f'expert-population/joint/{mode}/u{step:03}'
            yield [prefix+f'-expert-{j}.net' for j in range(3)]+[prefix+'-router.pt',prefix+'.json']
    for mode in ('joint-local', 'joint-slow', 'joint-local-slow'):
        prefix=f'expert-population/joint/{mode}/u064'
        yield [prefix+f'-expert-{j}.net' for j in range(3)]+[prefix+'-router.pt',prefix+'.json']
    for step in (64,256):yield [f'expert-population/joint/diagnosis/u{step:03}.json']
    for group in ('small','seven'):
        kinds=['q','q-blind']+(['short','short-floor','full-floor'] if group=='seven' else [])
        for kind in kinds:
            for seed in ((22819,22820,22821) if kind=='q' else (22819,)):
                for step in ((512,2048) if group=='seven' and kind=='q' else (512,)):
                    sub=f'expert-population/reward-debug/{group}/{kind}-{seed}/u{step}'
                    yield [sub+'.pt',sub+'.json']
    for method in ('ppo', 'value'):
        for seed in (22819, 22820):
            for step in (512, 2048):
                folder=f'expert-population/full-field/{method}-{seed}/'
                yield [folder+f'u{step}.pt',folder+f'dev-u{step}.json']
    for group, step in [('small', 512), ('seven', 2048)]:
        for seed in (22819, 22820):
            path=f'expert-population/changed-opponent/reward-training/{group}/seed-{seed}/'
            yield [path+f'u{step}.pt', path+'dev.json']
    for mode in ('joint-broad', 'joint-broad-blind'):
        for step in (0, 64, 256, 410, 1024):
            prefix=f'expert-population/joint/{mode}/u{step:03}'
            yield [prefix+f'-expert-{j}.net' for j in range(3)]+[prefix+'-router.pt',prefix+'.json']
    for count in (7,27):yield [f'expanded-library/crossplay/covered-{count:02}.json',f'expanded-library/crossplay/routed-{count:02}.json']


def run():
    pending=[paths for paths in groups() if not all((DEST/p).is_file() for p in paths)]
    # A single remote directory listing avoids a new SSH handshake per checkpoint.
    import subprocess
    from remote_import import HOST,REMOTE,REL,OPTIONS
    while pending:
        remote=REMOTE+'/'+REL+'/'+PREFIX
        listing=subprocess.run(['ssh',*OPTIONS,HOST,'cd '+remote+' && find . -type f'],capture_output=True,text=True)
        if listing.returncode==0:
            ready={v.removeprefix('./') for v in listing.stdout.splitlines()}
            selected=[g for g in pending if all(p in ready for p in g)]
            if selected:
                try:
                    retrieve([PREFIX+p for g in selected for p in g if not (DEST/p).is_file()])
                except subprocess.CalledProcessError:
                    # The immutable source can be retrieved again after a transport interruption.
                    pass
                else:
                    pending=[g for g in pending if g not in selected]
        if pending:time.sleep(45)


if __name__=='__main__':run()
