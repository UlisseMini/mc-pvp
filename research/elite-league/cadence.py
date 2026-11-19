"""Isolate dwell time and hysteresis after the matched-state opponent switch diagnosis."""
import argparse
import json
from pathlib import Path
from adaptation import fields,ranking,unpack,encode,freeze,dump
from history_cup import FIELD,FROZEN,CUP,execute
from ladder_transfer import WT,HOLD,plan
from run import HERE,compile_model,calendars
from tournament import grid
import champions

def source():return FROZEN/'challenger.net'

def train(out):
    base=source();family,w=unpack(base.read_text())
    if family!='PAIR' or w[0]!=2:raise ValueError('Expected the frozen adaptive selector')
    out.mkdir(parents=True);nets=out/'nets';nets.mkdir()
    controls={'wtap':None,'hold':None,'adaptive':base,'pure-population':HERE/'robust-frozen/population.net','pure-table':HERE/'empirical-game-frozen/explicit.net'}
    pool={}
    for dwell in (1,2,4,6,9,14,20):
        for j,factor in enumerate((.5,1,1.5,2,3,6)):
            v=w.copy();v[6]=dwell;v[7]*=factor
            n=f'd{dwell:02d}-m{j}';p=nets/(n+'.net');freeze(p,(encode(family,v)+'\n').encode())
            if p.read_bytes()!=base.read_bytes():pool[n]=p
    field=fields(FIELD,'training')
    for i in range(1,5):
        for t in ('pair','recurrent'):field[f'adapt-{i:02d}-{t}']=FROZEN/f'round-{i:02d}/{t}.net'
    compile_model()
    top=ranking(grid(out,'training',controls|pool,field,calendars('training_clocks'),mode='mouse'))
    screened=[r['name'] for r in top if r['name'] in pool][:6]
    candidates=controls|{n:pool[n] for n in screened}
    ranks=ranking(grid(out,'development',candidates,field,calendars('development_selection_clocks'),mode='mouse'))
    winner=ranks[0]['name'];contender=next(r for r in ranks if r['name'] in pool)
    orig=next(r for r in ranks if r['name']=='adaptive')
    dest=HERE/'cadence-frozen';dest.mkdir(exist_ok=True)
    freeze(dest/'contender.net',pool[contender['name']].read_bytes());freeze(dest/'development.jsonl.gz',(out/'development.jsonl.gz').read_bytes())
    dump(dest/'selection.json',dict(winner=winner,contender=contender['name'],eligible=contender['merit']>orig['merit'],screened=screened,ranking=ranks))
    champions.install('league-cadence',candidates[winner] or champions.POLICIES/({'wtap':WT,'hold':HOLD}[winner]+'.properties'),
                      'Expert timing development tournament winner','league-cadence')
    champions.install('division-cadence',dest/'contender.net','Expert timing search division winner','league-cadence','cadence')
    print(winner,ranks[0]['merit'],'original',orig['merit'],'new',contender['merit'],flush=True)

def study(work):
    d=json.loads((HERE/'cadence-frozen/selection.json').read_text())
    opponent_paths={f'shift-sealed-{i:02d}':FIELD/f'nets/shift-sealed-{i:02d}.net' for i in range(1,5)}
    a={WT:None,'adapt-04-pair':source(),'elite-population':HERE/'robust-frozen/population.net','elite-game-explicit':HERE/'empirical-game-frozen/explicit.net'}
    if d['eligible']:a['division-cadence']=HERE/'cadence-frozen/contender.net'
    donors=sorted((work/'history-clocks').glob('*.txt.gz'))
    if len(donors)!=8:raise ValueError('The frozen historical mirror cohort is required')
    scores=execute('cadence',a,opponent_paths,plan('ecad',list(a),list(opponent_paths),2),donors,work)['native']
    winner=min(scores,key=lambda n:(-scores[n]['merit'],-scores[n]['mean'],n))
    champions.install('league-switch-cup',champions.POLICIES/(winner+'.properties'),'Native switching-opponent tournament winner','league-switch-cup')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['train','study']);p.add_argument('out',type=Path);a=p.parse_args()
    (train if a.mode=='train' else study)(a.out)
