"""Compare adaptation with the two newer, 131072-fight feed-forward generalists on identical fixtures."""
import hashlib
import sys
import time

import numpy as np

from assay import lower_contrast
from learner import CLOCKS,ROOT,write_json
from remote_league import OUT,read
from stationary_field import DEST
from stationary_ppo import count,ordinary


def register():
    controls=read(OUT/'weak-matchups/entrants.json')
    for ref in controls.values():
        raw=(ROOT/ref['reference']).read_bytes()
        if not raw.startswith(b'LEAGUE_MLP180.') or hashlib.sha256(raw).hexdigest()!=ref['sha256']:
            raise ValueError('Invalid feed-forward control')
    write_json(DEST/'frontier-protocol.json',dict(controls=controls,
        training='Two memoryless generalists, both trained for 65536 fights from the same 65536-fight broad MLP. Register both finished arms before the adaptive grades.',
        grade='Supplement the original frozen tests. Also require positive paired recording-level lower bounds versus each contemporary generalist on the exact same stationary-opponent fixtures, in development and again on fresh timings. Keep native ring-outs and elite human matches separate.'),fixed=True)


def locations(role,split):
    if role=='strong-router':
        where=DEST/'strong-opening'
        return where/'prospective',where if split=='dev' else where/'prospective'
    later=role.endswith('-main')
    transfer=DEST/'prospective' if role=='router' else DEST/'successors'/role/'prospective' if later else DEST/'ppo-prospective'
    where=DEST if split=='dev' else transfer
    folder=where if role=='router' else where/'continuous'/role
    return transfer,folder


def run(role,split='dev'):
    spec=read(DEST/'protocol.json')
    transfer,folder=locations(role,split)
    common=DEST/'frontier-controls' if split=='dev' else transfer/'frontier-controls'
    if split!='dev':spec=dict(spec,prospective_manifest=str(transfer/'calendars/manifest.json'))
    controls=read(DEST/'frontier-protocol.json')['controls']
    if role=='controls':
        for name,ref in controls.items():ordinary(common,spec,name,ref['reference'],split)
        write_json(common/'done.json',dict(controls=controls),fixed=True)
        return
    router=role in ('router','strong-router')
    root=DEST/'strong-opening' if role=='strong-router' else DEST
    result=root/(split+'-result.json') if router else folder/'result.json'
    while not result.is_file():time.sleep(15)
    score=read(result)
    if router:actor=root/'actors/history.net'
    elif role=='parent':actor=read(OUT/'protocol.json')['initial']['gru']['reference']
    elif role.endswith('-main'):actor=read(DEST/'successors'/role/'pair.json')['gru']['reference']
    else:actor=read(OUT/role/'result.json')['reference']
    output=folder/'frontier-check'
    legal=ordinary(output,spec,'challenger',actor,split)
    key='history' if router else role
    if count(legal)!=score['totals'][key]:raise ValueError('Stationary comparison changed the challenger results')
    wt=ordinary(common,spec,'wtap',spec['field']['wtap']['reference'],split)
    if count(wt)!=score['totals']['wtap']:raise ValueError('Stationary comparison changed the W-tap results')
    donor_count=len(CLOCKS['dev']) if split=='dev' else len(read(transfer/'calendars/manifest.json')['selected'])
    roles=len(read(transfer/'assay.json')['roles']) if split=='prospective' and not router else 1
    effects={};cells={};totals={}
    for name,ref in controls.items():
        v=ordinary(common,spec,name,ref['reference'],split)
        diff=(legal==1).astype(float)-(v==1)
        donor=diff.reshape(donor_count,-1).mean(axis=1)
        effects[name]=dict(mean=float(donor.mean()),bounds=lower_contrast(donor,.05/(roles*len(controls))),timing_recordings=donor.tolist())
        totals[name]=count(v)
        cells[name]={foe:count(v[:,i]) for i,foe in enumerate(spec['field'])}
    write_json(output/'result.json',dict(gains=effects,challenger=count(legal),wtap=count(wt),controls=totals,
        opposing_field={**cells,'challenger':{foe:count(legal[:,i]) for i,foe in enumerate(spec['field'])},
                        'wtap':{foe:count(wt[:,i]) for i,foe in enumerate(spec['field'])}},
        qualified_against_contemporary_baselines=all(e['bounds'][0]>0 for e in effects.values())),fixed=True)


if __name__=='__main__':
    if sys.argv[1:]==['register']:register()
    else:run(sys.argv[1],sys.argv[2] if len(sys.argv)>2 else 'dev')
