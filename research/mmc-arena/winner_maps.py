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
    stage=f'league-{g:03d}'
    path=OUT/'tournaments'/stage
    rows=read(OUT/'split.json')['validation']
    write_json(path/'protocol.json',dict(stage=stage,published_winner=released['duel_alias'],opponent_name=weakest,
        contestants={'winner':dict(actor=resolve(field[champion]),sha256=released['sha256'],
                                   target=resolve(field[weakest]),target_sha=field[weakest]['sha256'])},
        maps=['default','mmc-137',*['mmc-'+str(n) for n in rows]],model_repetitions=2,native_blocks=8,
        opponent_rule='Lowest score in the entire frozen development field excluding own weights and plain W-tap. Alphabetical tie-break. Plain W-tap also plays both opposing policies.',
        purpose='Post-publication transfer on reusable validation floors. Sealed floors stay reserved for the frozen specialist test. These matches do not choose the tournament winner.',
        regulation='Seven CPS for both players, MMC preset, three-block reach, mouse aim, 120 seconds, same eight mirrored seat and RTT settings in Minecraft.',
        controls='Common action seeds and clocks across simulator arms and floors. Independent fully audited Minecraft matches in the same seat and RTT blocks.'),fixed=True)
    return stage


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
            if mode=='model':
                subprocess.run([sys.executable,LEAGUE.parent/'cpu_limit.py','--terrain','nice','-n','8',
                                sys.executable,'-u',__file__,'score',stage],check=True)
            else:native(stage)
            g+=1


if __name__=='__main__':
    if sys.argv[1]=='score':simulate(sys.argv[2])
    elif sys.argv[1] in ('model','native'):run(sys.argv[1])
    else:raise SystemExit('Usage: winner_maps.py model|native')
