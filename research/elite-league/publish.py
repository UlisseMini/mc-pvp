"""Publish a replay-checked alias for the challenger frozen by the main-lab selection tournament."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
from ladder_transfer import WT,HOLD,REFERENCE,CONFIRMATION,plan,read
from run import HERE,ROOT
import champions

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
    data+='aim_input=mouse\n'
    if dest.exists() and dest.read_text()!=data:raise ValueError('Existing elite-sumo alias differs')
    dest.write_text(data)
    champions.install("league-native-confirmed",source,"Native selection winner after independent confirmation","league-native-confirmed")
    record=dict(arm=arm,weights_sha256=weights,confirmation_games=len(rows),alias=dest.name,
                alias_sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),confirmation_sha256=hashlib.sha256(evidence.read_bytes()).hexdigest())
    if receipt.exists() and read(receipt)!=record:raise ValueError('Prior receipt differs')
    receipt.write_text(json.dumps(record,indent=2)+'\n')
    from history_cup import counts
    from reigning import publish as title
    model=ROOT/'build/elite-league/confirmation-validation/regulation.jsonl.gz'
    with gzip.open(model,'rt') as f:simulation=counts(list(map(json.loads,f)),True)
    actual=counts(d['matches'])
    best=min(actual,key=lambda n:(-actual[n]['merit'],-actual[n]['mean'],n))
    champions.install('league-confirmation',ROOT/'policies'/(best+'.properties'),'Independent seven-opponent confirmation tournament winner','league-confirmation')
    competitive=(actual[arm]['merit']>=actual[REFERENCE]['merit'] and actual[arm]['mean']>=actual[REFERENCE]['mean']
                 and simulation[arm]['merit']>=simulation[REFERENCE]['merit'])
    if arm!=WT and competitive and actual[arm]['merit']>actual[WT]['merit'] and simulation[arm]['merit']>simulation[WT]['merit'] and actual[arm]['opponents'][WT]['points']>12:
        title('best-sumo',arm,'Sumo title holder from the confirmed Minecraft selection',evidence)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('frozen',type=Path);p.add_argument('evidence',type=Path);p.add_argument('policy',type=Path);p.add_argument('receipt',type=Path)
    a=p.parse_args();publish(a.frozen,a.evidence,a.policy,a.receipt)
