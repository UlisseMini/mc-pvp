"""Continuous seven-CPS Hetzner league, starting with the first remote tournament."""
import argparse
import fcntl
import hashlib
import json
import subprocess
import sys
import time

from development_panel import make_panel
from league_tournament import pin_model, stats, tournament
from learner import CLOCKS, ROOT, Worker, write_json
from remote_league import OUT, read
from train import configure, fit

LEAGUE = OUT/'continuous-league'
ROLES = ('counter-mlp','counter-gru','main-mlp','main-gru')
FIGHTS = 65536


def folder(g):
    return LEAGUE/f'g{g:03d}'


def name(g, role):
    return f'remote-{g:03d}-{role}'


def prior(g):
    return read((OUT if g==2 else folder(g-1))/'tournament.json')


def weakest(t, actor):
    return min((n for n in t['field'] if n!='wtap' and t['field'][n]['sha256']!=t['field'][actor]['sha256']),
               key=lambda n:(t['matrix'][actor][n]['wins'],t['matrix'][actor][n]['score'],n))


def initialize(g):
    old=prior(g)
    leader=old['winner']
    warm={}
    for kind in ('mlp','gru'):
        # Only same-architecture learned entrants with an actual optimizer checkpoint qualify.
        for row in old['ranking']:
            ref=old['field'][row['name']]['reference']
            if not ref.endswith('.net'):
                continue
            d=(ROOT/ref).parent
            if (d/'trainer.pt').is_file() and (d/'config.json').is_file() and read(d/'config.json')['kind']==kind and (d/'final.net').is_file() and (ROOT/ref).read_bytes()==(d/'final.net').read_bytes():
                warm[kind]=str((d/'trainer.pt').relative_to(ROOT))
                break
        if kind not in warm:
            raise ValueError('No trained parent for '+kind)
    features=dict(generation=g,champion=leader,champion_policy=old['field'][leader],weakest=weakest(old,leader),
        warm=warm,training_fights_each=FIGHTS,cps=7,floor=137,seed=19411+g*2213,
        field=old['field'],families=old['families'],
        rule='Two independent 65,536-fight counter searches against the exact last champion; require >60% outright wins on their development cells for targeted sampling. Then train recurrent and feed-forward successors for 65,536 fights each against the champion, threats and historical archive. Promote the simulator champion by the full round robin using mean and worst-family score. Native human-duel title has its own gate. Repeat until stopped.')
    write_json(folder(g)/'protocol.json',features,fixed=True)
    write_json(folder(g)/'counter-test.json',dict(games_per_arm=512,minimum_wins=308,
        development_clocks=8,repetitions=8,seats=['a','b'],rtt_pairs=[[31,31],[109,109],[31,109],[109,31]],
        comparator='plain wtap',action_seeds='development_panel.make_panel'),fixed=True)
    return features


def train_one(g,role):
    home=folder(g)
    cfg=read(home/'protocol.json')
    kind=role.split('-')[1]
    old=prior(g)
    families={k:[old['field'][n]['reference'] for n in v] for k,v in old['families'].items()}
    threats=[old['field'][cfg['weakest']]['reference']]
    if role.startswith('main'):
        for side in ROLES[:2]:
            counter=read(home/side/'result.json')
            if counter['confirmed']:
                threats.append(counter['policy']['reference'])
        families['current counters']=[read(home/v/'result.json')['policy']['reference'] for v in ROLES[:2]]
    path=home/role
    if (path/'result.json').exists():
        return
    number=ROLES.index(role);seed=cfg['seed']+503*number
    configure(path,kind,'exploiter' if number<2 else 'main',seed,seed+8839,
              cfg['champion_policy']['reference'],threats[0],families,
              scores=old['ranking'][0]['family'],warm=cfg['warm'][kind],threats=threats)
    fit(path,FIGHTS//64)
    if read(path/'progress.json')['duels']!=FIGHTS:
        raise ValueError('Incorrect training budget')
    ref=pin_model(path)
    result=dict(policy=ref,fights=FIGHTS)
    if number<2:
        panel=make_panel(CLOCKS['dev'],8)
        worker=Worker()
        try:
            for label,actor in (('challenger',ref['reference']),('wtap','wtap')):
                fixture=path/(label+'-512.json')
                if not fixture.exists():
                    plan=[(actor,cfg['champion_policy']['reference'],clock,seat,a,b,x,y) for _,_,seat,a,b,clock,x,y in panel]
                    rows=[]
                    for offset in range(0,len(plan),32):
                        rows.extend(worker.run(plan[offset:offset+32],False))
                    scored=[[*cell[:5],row[0],row[1]] for cell,row in zip(panel,rows,strict=True)]
                    write_json(fixture,dict(games=scored,aggregate=stats(scored)),fixed=True)
                result[label]=read(fixture)['aggregate']
            if len(panel)!=512:
                raise ValueError('Unexpected fixed-counter fixture count')
            result.update(target=cfg['champion'],confirmed=result['challenger']['wins']>=308,gate_fights=512)
        finally:
            worker.close()
    write_json(path/'result.json',result,fixed=True)


def pair(g,roles):
    running=[subprocess.Popen([sys.executable,'-u',__file__,'fit',str(g),r]) for r in roles
             if not (folder(g)/r/'result.json').exists()]
    codes=[p.wait() for p in running]
    if any(codes):
        raise RuntimeError(f'Generation {g} training failed with codes {codes}')


def run_round(g):
    home=folder(g);cfg=initialize(g)
    for roles in (ROLES[:2],ROLES[2:]):
        pair(g,roles)
    field=dict(cfg['field']);families={k:list(v) for k,v in cfg['families'].items()}
    for role in ROLES:
        n=name(g,role)
        field[n]=read(home/role/'result.json')['policy']
        families.setdefault('league counters' if role.startswith('counter') else 'league generalists',[]).append(n)
    if not (home/'tournament.json').exists():
        worker=Worker()
        try:
            tournament(worker,field,families,cfg['champion'],home/'tournament.json')
        finally:
            worker.close()
    t=read(home/'tournament.json');champ=t['winner'];hard=weakest(t,champ)
    write_json(home/'result.json',dict(generation=g,winner=champ,policy=field[champ],incumbent=cfg['champion'],
        weakest=hard,opponents={x:field[x] for x in dict.fromkeys(('wtap',hard,cfg['champion']))},
        games_per_cell=256,entrants=len(field),merit=t['ranking'][0]['merit'],
        against_wtap=t['matrix'][champ]['wtap'],against_weakest=t['matrix'][champ][hard],
        wtap_against_weakest=t['matrix']['wtap'][hard],wtap_mirror=t['matrix']['wtap']['wtap']),fixed=True)


def supervise():
    LEAGUE.mkdir(exist_ok=True,parents=True)
    with (LEAGUE/'runner.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        g=2
        while not (LEAGUE/'STOP').exists():
            if not (folder(g)/'result.json').exists():
                write_json(LEAGUE/'current.json',dict(generation=g,phase='training or tournament'))
                run_round(g)
            g+=1


def receive(g):
    from remote_import import available,retrieve
    from native_league import publish
    dest=folder(g)
    rel=str(dest.relative_to(OUT))+'/'
    if not available(rel+'result.json'):
        return False
    retrieve([rel+'result.json',rel+'protocol.json',rel+'counter-test.json',rel+'tournament.json']+
             [rel+r+'/'+file for r in ROLES for file in ('result.json','final.net','config.json','progress.json')])
    t=read(dest/'tournament.json');result=read(dest/'result.json')
    if t['ranking'][0]['name']!=result['winner'] or t['field'][result['winner']]!=result['policy']:
        raise ValueError('Winner receipt disagrees with full tournament')
    for role in ROLES:
        entry=read(dest/role/'result.json')
        if entry['fights']!=FIGHTS or read(dest/role/'progress.json')['duels']!=FIGHTS:
            raise ValueError('Incomplete training receipt: '+role)
        if entry['policy']!=t['field'][name(g,role)] or hashlib.sha256((dest/role/'final.net').read_bytes()).hexdigest()!=entry['policy']['sha256']:
            raise ValueError('Tournament entrant has different weights: '+role)
    for ref in [result['policy'],*result['opponents'].values()]:
        if ref['reference'].endswith('.net'):
            path=ROOT/ref['reference']
            if not path.exists():
                retrieve([str(path.relative_to(OUT))])
            if hashlib.sha256(path.read_bytes()).hexdigest()!=ref['sha256']:
                raise ValueError('Received incorrect weights')
    duel=publish(f'sumo-league-remote-{g:03d}',result['policy'],f'Seven-CPS Hetzner league tournament winner, generation {g}')
    write_json(dest/'published.json',dict(name=duel,policy=result['policy'],winner=result['winner']),fixed=True)
    print('Published',duel,flush=True)
    return True


def follow(native):
    # Native latency cannot hold up the independent winner publisher or the next remote round.
    LEAGUE.mkdir(exist_ok=True,parents=True)
    with (LEAGUE/('native.lock' if native else 'publication.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        g=2
        while True:
            dest=folder(g)
            if (dest/('native/summary.json' if native else 'published.json')).exists():
                g+=1
                continue
            try:
                if native and (dest/'published.json').is_file():
                    from matched_native import assess
                    p=read(dest/'published.json');r=read(dest/'result.json')
                    assess(dest/'native',f'hetzner-g{g:03d}',dict(winner=dict(**p['policy'],native_alias=p['name']),wtap=r['opponents']['wtap']),r['opponents'])
                    continue
                if not native and receive(g):
                    continue
            except (OSError,subprocess.SubprocessError,RuntimeError,ValueError) as e:
                print(f'Generation {g} {"native comparison" if native else "publication"} waiting: {e}',flush=True)
            time.sleep(60)


if __name__=='__main__':
    args=argparse.ArgumentParser()
    args.add_argument('mode',choices=['run','fit','publish','native'])
    args.add_argument('generation',type=int,nargs='?')
    args.add_argument('role',choices=ROLES,nargs='?')
    a=args.parse_args()
    if a.mode=='run': supervise()
    elif a.mode=='fit': train_one(a.generation,a.role)
    else: follow(a.mode=='native')
