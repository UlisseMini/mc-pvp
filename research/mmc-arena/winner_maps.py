"""Measure every published sumo league winner on the four reusable geometry holdouts."""
import fcntl
import hashlib
from pathlib import Path
import subprocess
import sys
import time

from validation import BUILD, EVIDENCE, LEAGUE, OUT, ROOT, native, read, simulate, write_json


def resolve(ref):
    name=ref['reference']
    for line in (LEAGUE.parent/'sumo-inputs/archive-field.tsv').read_text().splitlines():
        fields=line.split('\t')
        if fields[0]==name and len(fields)>1:
            name=fields[1]
            break
    if name.endswith('.net') and hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=ref['sha256']:
        raise ValueError('Tournament weights changed for '+name)
    return name


def register(g):
    generation=EVIDENCE/f'g{g:03d}'
    released=read(generation/'published-winner/result.json')
    tournament=read(generation/'tournament.json')
    field=tournament['field']
    champion=tournament['ranking'][0]['name']
    if champion!=released['winner'] or field[champion]['sha256']!=released['sha256']:
        raise ValueError('The server winner differs from the tournament result')
    matrix=tournament['matrix'][champion]
    eligible=[name for name in matrix if field[name]['sha256'] not in (field[champion]['sha256'],field['wtap']['sha256'])]
    weakest=min(eligible,key=lambda name:(matrix[name]['score'],name))
    return register_field(f'league-{g:03d}',released['duel_alias'],field[champion],weakest,field[weakest])


def register_field(stage,alias,actor,weakest,opponent,generalist=False):
    path=OUT/'tournaments'/stage
    rows=read(OUT/'split.json')['validation']
    role='generalist' if generalist else 'winner'
    publication={'published_policy':alias} if generalist else {'published_winner':alias}
    write_json(path/'protocol.json',dict(stage=stage,**publication,opponent_name=weakest,
        contestants={role:dict(actor=resolve(actor),sha256=actor['sha256'],
                               target=resolve(opponent),target_sha=opponent['sha256'])},
        maps=['default','mmc-137',*['mmc-'+str(n) for n in rows]],model_repetitions=2,native_blocks=8,
        opponent_rule='Lowest outright wins in the common development field, then score and name. Plain W-tap plays both opponents.' if generalist else 'Lowest score in the entire frozen development field excluding own weights and plain W-tap. Alphabetical tie-break. Plain W-tap also plays both opposing policies.',
        purpose='Post-publication transfer on reusable validation floors. Sealed floors stay reserved for the frozen specialist test. These matches do not choose the tournament winner.',
        regulation='Seven CPS for both players, MMC preset, three-block reach, mouse aim, 120 seconds, same eight mirrored seat and RTT settings in Minecraft.',
        controls='Common action seeds and clocks across simulator arms and floors. Independent fully audited Minecraft matches in the same seat and RTT blocks.'),fixed=True)
    return stage


def assess(mode,stage):
    if mode=='model':
        subprocess.run([sys.executable,LEAGUE.parent/'cpu_limit.py','--terrain','nice','-n','8',
                        sys.executable,'-u',__file__,'score',stage],check=True)
    else:native(stage)


def remote(mode):
    from remote_league import OUT as source
    from remote_round import entrants
    from native_league import props
    field,_,_=entrants()
    result=read(source/'mlp/common-field.json')
    policy=read(source/'mlp/result.json')
    name=read(source/'mlp/native/design.json')['published']
    if props(name)['league_source_sha256']!=policy['sha256']:raise ValueError('Published generalist changed')
    weak=result['weakest']
    stage=register_field('remote-001-generalist',name,policy,weak,field[weak],True)
    assess(mode,stage)
    while not (source/'published.json').is_file():time.sleep(30)
    released=read(source/'published.json');rr=read(source/'tournament.json')
    champ=rr['winner'];field=rr['field'];actor=field[champ]
    if released['winner']!=champ or released['policy']!=actor or props(released['name'])['league_source_sha256']!=actor['sha256']:
        raise ValueError('Remote winner differs from its published controls')
    candidates=[n for n in field if field[n]['sha256'] not in (actor['sha256'],field['wtap']['sha256'])]
    weak=min(candidates,key=lambda n:(rr['matrix'][champ][n]['score'],n))
    assess(mode,register_field('remote-001-winner',released['name'],actor,weak,field[weak]))


def run(mode):
    with (BUILD/('league-maps-'+mode+'.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        g=1
        while True:
            done=EVIDENCE/f'g{g:03d}'/'published-winner/result.json'
            while not done.exists():
                stop=EVIDENCE/'stop.json'
                if stop.is_file() and read(stop)['generation']<g:return
                time.sleep(20)
            stage=register(g)
            assess(mode,stage)
            g+=1


if __name__=='__main__':
    if sys.argv[1]=='score':simulate(sys.argv[2])
    elif sys.argv[1] in ('remote-model','remote-native'):remote(sys.argv[1].split('-')[1])
    elif sys.argv[1] in ('model','native'):run(sys.argv[1])
    else:raise SystemExit('Usage: winner_maps.py model|native')
