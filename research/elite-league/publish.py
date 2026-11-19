"""Publish a replay-checked alias for the challenger frozen by the main-lab selection tournament."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
from ladder_transfer import WT,HOLD,REFERENCE,CONFIRMATION,plan,read
from run import HERE,ROOT

def publish(frozen,evidence,dest,receipt):
    decision=read(frozen/'native-choice.json')
    with gzip.open(evidence,'rt') as f:d=json.load(f)
    arm=decision['winner']
    arms=list(dict.fromkeys([WT,HOLD,REFERENCE,arm]))
    expected=plan('efin',arms,CONFIRMATION,6)
    m=d['manifest']
    if m['plan']!=expected or m['matches'].keys()!=expected.keys():raise ValueError('Confirmation protocol differs')
    if d['summary']['binding_disagreements']:raise ValueError('Native binding disagreement')
    rows=[g for g in d['matches'] if g['arm']==arm]
    if len(rows)!=len(CONFIRMATION)*24:raise ValueError('Incomplete confirmation')
    source=ROOT/'policies'/(arm+'.properties')
    parsed=dict(l.split('=',1) for l in source.read_text().splitlines() if '=' in l)
    if 'duel_network' in parsed:
        weights=hashlib.sha256((parsed['duel_network']+'\n').encode()).hexdigest()
    else:weights=hashlib.sha256(source.read_bytes()).hexdigest()
    if weights!=decision['sha256']:raise ValueError('Selected playing weights have changed')
    data='\n'.join(['description=Sumo main-lab tournament selection. Frozen ordinary-control policy.' if l.startswith('description=') else l for l in source.read_text().splitlines()])+'\n'
    if dest.exists() and dest.read_text()!=data:raise ValueError('Existing elite-sumo alias differs')
    dest.write_text(data)
    record=dict(arm=arm,weights_sha256=weights,confirmation_games=len(rows),alias=dest.name,
                alias_sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),confirmation_sha256=hashlib.sha256(evidence.read_bytes()).hexdigest())
    if receipt.exists() and read(receipt)!=record:raise ValueError('Prior receipt differs')
    receipt.write_text(json.dumps(record,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('frozen',type=Path);p.add_argument('evidence',type=Path);p.add_argument('policy',type=Path);p.add_argument('receipt',type=Path)
    a=p.parse_args();publish(a.frozen,a.evidence,a.policy,a.receipt)
