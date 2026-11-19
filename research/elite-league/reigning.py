"""Maintain a friendly playable title while keeping every tournament winner under its original name."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
from run import HERE,ROOT

def publish(alias,source,reason,evidence):
    dest=ROOT/'policies'/(alias+'.properties');original=ROOT/'policies'/(source+'.properties')
    stage={'confirmation':1,'history-cup':2}.get(evidence.parent.name,0)
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
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['opening','historical']);a=p.parse_args()
    if a.stage=='opening':
        publish('best-sumo','elite-v1-hybrid','Sumo title holder from the first native transfer',HERE/'evidence/v1/native.json.gz')
        publish('best-boxing','research-wtap20','Boxing W-tap reference after the native maintenance trials',HERE/'evidence/boxing/native.json.gz')
    else:
        evidence=HERE/'evidence/history-cup/title.json';d=json.loads(evidence.read_text())
        publish('best-sumo',d['recommended'],'Sumo title holder after the historical tournament and confirmation',evidence)
