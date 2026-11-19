"""Save immutable, directly duelable tournament champions in the main server policy store."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from run import HERE, ROOT

POLICIES = ROOT / 'policies'
BUILD = ROOT / 'build/elite-league'
BASE = {'wtap': 'research-wtap20', 'hold': 'research-hold20'}
REFERENCES = {'human-trained': HERE/'human-challenge-frozen/human.net',
              'reference': HERE/'robust-frozen/population.net', 'v1-hybrid': HERE/'frozen/hybrid.net'}

def read(path):
    if path.suffix == '.gz':
        with gzip.open(path, 'rt') as f: return json.load(f)
    return json.loads(path.read_text())

def install(alias, source, title, stage, track='winner', mode='sumo'):
    edition=os.environ.get('ELITE_EDITION','')
    if edition:
        alias=edition+'-'+alias;stage=edition+'-'+stage
    if not re.fullmatch(r'[a-z0-9_-]{1,64}', alias):
        raise ValueError('Invalid duel name ' + alias)
    policy = source.suffix == '.properties'
    base = source if policy else POLICIES/('research-wtap20.properties' if mode=='boxing' else 'research-hold20.properties')
    lines = [line for line in base.read_text().splitlines() if not line.startswith(('description=', 'league_'))]
    if not policy:
        lines.extend(['duel_network=' + source.read_text().strip(), 'duel_network_landmark='+('3' if mode=='boxing' else '0')])
    lines.extend(['description=' + title, 'league_stage=' + stage, 'league_track=' + track,
                  'league_mode=' + mode, 'league_source_sha256=' + hashlib.sha256(source.read_bytes()).hexdigest()])
    data = ('\n'.join(lines) + '\n').encode()
    dest = POLICIES / (alias + '.properties')
    # The referee can reload policies at any time. Make a whole file visible in one step.
    with tempfile.NamedTemporaryFile(dir=POLICIES) as tmp:
        tmp.write(data); tmp.flush()
        try: os.link(tmp.name, dest)
        except FileExistsError:
            if dest.read_bytes() != data:
                raise ValueError('A published winner is immutable. Choose a new series name: ' + alias)
    return alias

def resolve(name, folder, picks):
    if name in BASE: return POLICIES / (BASE[name] + '.properties')
    if name in REFERENCES: return REFERENCES[name]
    for track, checkpoint in picks.items():
        if checkpoint == name and (folder/(track+'.net')).is_file(): return folder/(track+'.net')
    return folder/'checkpoints'/(name+'.net')

def publish_round(folder, result, series):
    if series == 'season':
        number=result['season']; picks=result['selected']; best=result['ranking'][0]['name']
        label='Mixed league season'
    else:
        number=int(folder.name.split('-')[-1]); picks=result['picks']; best=result['ranks'][0]['name']
        label='Empirical game round'
    prefix=f'{series}-{number:02d}'; title=f'{label} {number}'
    install(prefix, resolve(best,folder,picks), f'{title} winner ({best})', prefix)
    for t in ('explicit','neural'):
        install(prefix+'-'+t,resolve(picks[t],folder,picks),f'{title} {t} winner ({picks[t]})',prefix,t)

def population(folder):
    index=int(folder.name[5:7])+1; d=read(folder/'stage-selection.json')
    name=d['winner']; alias=f'population-{index:02d}'
    install(alias,folder/(name+'.net'),f'Population round {index} winner ({folder.name[8:]})',alias)

def backfill():
    for folder in sorted((BUILD/'robust-fit/population').glob('epoch*')):
        population(folder)
    for series, top, pattern, result in [('season','ladder','season-*','results.json'),
                                         ('game','empirical-game','round-*','response.json')]:
        for folder in sorted((BUILD/top).glob(pattern)):
            publish_round(folder,read(folder/result),series)
    for alias,source,title in [
        ('league-genesis',HERE/'frozen/hybrid.net','First architecture tournament winner'),
        ('league-robust',HERE/'robust-frozen/champion.net','Population and PPO cross-play winner'),
        ('league-mixed',resolve_common(),'Twelve-season common tournament winner'),
        ('league-game',resolve_empirical(),'Six-round empirical-game development winner')]:
        install(alias,source,title,alias)
    for alias,source,title,group in [
        ('division-hold-counter',HERE/'challenge-frozen/anti-hold.net','Hold-W counter tournament winner','counter'),
        ('division-hybrid-counter',HERE/'challenge-frozen/anti-hybrid.net','Hybrid counter tournament winner','counter'),
        ('division-human-trained',HERE/'human-challenge-frozen/human.net','Human-informed training division winner','model'),
        ('division-control-trained',HERE/'human-challenge-frozen/control.net','Matched control training division winner','model')]:
        install(alias,source,title,'specialists',group)
    # The boxing specialist turns on only at its registered landmark. Preserve that playing configuration.
    install('division-boxing',POLICIES/'elite-boxing.properties','Established-combo training winner','specialists','boxing','boxing')

def resolve_common():
    d=read(BUILD/'ladder-selection/finalists.json');name=d['ranked'][0]['name']
    if name in REFERENCES or name in BASE: return resolve(name,BUILD,{})
    if re.fullmatch(r's\d\d-(rule|table|search|neural)',name):
        return BUILD/'ladder'/('season-'+name[1:3])/(name[4:]+'.net')
    rows=(BUILD/'ladder-selection/finalists-arms.txt').read_text().splitlines()
    source=next(row.split()[1] for row in rows if row.split()[0]==name)
    return Path(source)

def resolve_empirical():
    name=read(HERE/'empirical-game-frozen/selection.json')['ranking'][0]['name']
    if re.fullmatch(r'q\d\d-(explicit|neural)',name):
        return BUILD/'empirical-game'/('round-'+name[1:3])/(name[4:]+'.net')
    return resolve(name,BUILD,{})

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest='command',required=True)
    commands.add_parser('backfill')
    commands.add_parser('population').add_argument('folder',type=Path)
    args=parser.parse_args()
    if args.command=='backfill':backfill()
    else:population(args.folder)
