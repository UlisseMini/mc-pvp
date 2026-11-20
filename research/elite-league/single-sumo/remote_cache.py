"""Import the common tournament cells after validating their fixtures and a fresh local replay."""
import json
import subprocess
import sys
import time
from itertools import combinations_with_replacement

from learner import BUILD, EVIDENCE, HERE, Worker, freeze, write_json
from league_tournament import schedule
from remote_import import HOST, OPTIONS, REMOTE
from remote_league import OUT, read
from remote_round import entrants


def pull():
    field,_,_=entrants()
    pairs=list(combinations_with_replacement(sorted(field),2))
    names={f'{a}_{field[a]["sha256"][:10]}--{b}_{field[b]["sha256"][:10]}.json':(a,b) for a,b in pairs}
    base=REMOTE+'/research/elite-league/single-sumo/evidence/dev-games/'
    # The final cell is generated in the same sorted order by the remote tournament.
    # Some cells were carried from the earlier local league and can already exist.
    while True:
        files=subprocess.run(['ssh',*OPTIONS,HOST,'ls -1 '+base],capture_output=True,text=True)
        if files.returncode==0 and set(names)<=set(files.stdout.splitlines()):break
        time.sleep(20)
    cache=EVIDENCE/'dev-games';folder=BUILD/'hetzner/round-cache'
    folder.mkdir(parents=True,exist_ok=True)
    manifest=folder/'files.txt';manifest.write_text(''.join(n+'\n' for n in names))
    subprocess.run(['rsync','-az','--files-from='+str(manifest),'-e','ssh -o BatchMode=yes -o ConnectTimeout=12',HOST+':'+base,str(folder)+'/'],check=True)
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','--terrain','nice','-n','8',sys.executable,__file__,'check'],check=True)
    for n in names:freeze(cache/n,(folder/n).read_bytes())


def check():
    field,_,_=entrants();folder=BUILD/'hetzner/round-cache';common=0
    checks={tuple(sorted(x)) for x in [('wtap','sg003-long-incumbent'),('legacy-wtap-clickphase','sg003-long-seven'),('sg003-long-g1','sg003-long-seven')]}
    size=0;rows=0;w=Worker()
    try:
        for a,b in combinations_with_replacement(sorted(field),2):
            name=f'{a}_{field[a]["sha256"][:10]}--{b}_{field[b]["sha256"][:10]}.json'
            src=folder/name;cache=EVIDENCE/'dev-games'/name
            games=json.loads(src.read_text());plan,keys=schedule(a,b,field)
            if len(games)!=256 or any(r[:5]!=k or r[5] not in (0,.5,1) or not 0<r[6]<=120100 for r,k in zip(games,keys,strict=True)):
                raise ValueError('Incomplete or incompatible remote cell '+name)
            if cache.exists():
                if src.read_bytes()!=cache.read_bytes():raise ValueError('Previously scored cell differs '+name)
                common+=1
            if (a,b) in checks:
                actual=[r for i in range(0,len(plan),32) for r in w.run(plan[i:i+32],False)]
                if any(r[5:7]!=list(actual[j][:2]) for j,r in enumerate(games)):raise ValueError('Mac replay mismatch '+name)
            size+=1;rows+=len(games)
    finally:w.close()
    receipt=OUT/'common-cache.json'
    if not receipt.exists():write_json(receipt,dict(matchups=size,fixtures=rows,already_local=common,
                fresh_mac_replays=256*len(checks),replayed=[list(k) for k in sorted(checks)],
                scope='Frozen opponents shared by the generation-two and remote tournaments, plus the three fixed-budget specialists; evaluated with the same Java, calendar, seed, seat and RTT protocol.'),fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['check']:check()
    else:pull()
