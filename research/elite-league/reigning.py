"""Maintain a friendly playable title while keeping every tournament winner under its original name."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
from run import HERE,ROOT

def publish(alias,source,reason,evidence):
    evidence=Path(evidence).resolve()
    dest=ROOT/'policies'/(alias+'.properties');original=ROOT/'policies'/(source+'.properties')
    stage={'robust':1,'confirmation':2,'history-cup':3}.get(evidence.parent.name,0)
    if evidence.parent.parent.name=='mmc13':
        stage={'opening':4,'final':5}.get(evidence.parent.name,stage)
    if evidence.parent.name=='v4-unified-title' and evidence.parent.parent.name=='recorded-mmc-frozen':stage=6
    if dest.exists():
        previous=dict(s.split('=',1) for s in dest.read_text().splitlines() if '=' in s)
        if int(previous.get('title_stage','0'))>stage:return
    lines=[s for s in original.read_text().splitlines() if not s.startswith(('description=','league_','title_source=','title_stage=','aim_input='))]
    if stage:lines.append('title_stage='+str(stage))
    payload=('\n'.join(lines+[f'description={reason}',f'title_source={source}','aim_input=mouse'])+'\n').encode()
    if dest.exists() and 'title_source=' not in dest.read_text():raise ValueError('Existing policy name belongs to a different bot')
    receipt=HERE/'evidence/titles';receipt.mkdir(exist_ok=True)
    name=receipt/(alias+'-'+hashlib.sha256(evidence.read_bytes()).hexdigest()[:12]+'.json')
    entry=dict(alias=alias,source=source,reason=reason,evidence=str(evidence.relative_to(HERE)),
               evidence_sha256=hashlib.sha256(evidence.read_bytes()).hexdigest(),policy_sha256=hashlib.sha256(payload).hexdigest())
    if name.exists() and json.loads(name.read_text())!=entry:raise ValueError('Existing publication receipt differs')
    with tempfile.NamedTemporaryFile(dir=dest.parent,delete=False) as f:f.write(payload)
    os.replace(f.name,dest);name.write_text(json.dumps(entry,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['opening','robust','historical']);a=p.parse_args()
    if a.stage=='opening':
        publish('best-sumo','elite-v1-hybrid','Sumo title holder from the first native transfer',HERE/'evidence/v1/native.json.gz')
        publish('best-boxing','research-wtap20','Boxing W-tap reference after the native maintenance trials',HERE/'evidence/boxing/native.json.gz')
    elif a.stage=='robust':
        import gzip
        evidence=HERE/'evidence/robust/native.json.gz'
        with gzip.open(evidence,'rt') as f:bundle=json.load(f)
        if len(bundle['matches'])!=496 or bundle['summary']['binding_disagreements']:raise ValueError('Incomplete cross-play cohort')
        per={}
        for name in ('research-wtap20','elite-population'):
            rows=[r for r in bundle['matches'] if r['arm']==name and r['profile']=='mmc' and r['rtt']==r.get('opponent_rtt',r['rtt'])]
            if len(rows)!=80:raise ValueError('Expected ten matched opponents')
            groups={}
            for r in rows:groups.setdefault(r['opponent'],[]).append(r['win']+.5*r['draw'])
            per[name]=groups
        for n,c in per['elite-population'].items():
            if len(c)!=8 or sum(c)<sum(per['research-wtap20'][n]) or sum(c)<7:raise ValueError('Historical score qualification failed')
        publish('best-sumo','elite-population','Sumo title holder from the ten-opponent native tournament',evidence)
    else:
        evidence=HERE/'evidence/history-cup/title.json' ;d=json.loads(evidence.read_text())
        publish('best-sumo',d['recommended'],'Sumo title holder after the historical tournament and confirmation',evidence)
