#!/usr/bin/env python3
"""Reject historical experiments with frozen or inconsistent remote replication."""
import json
from pathlib import Path
from analyze_views import analyze
from experiments import combat_metrics,OUT

def audit():
    cache={};changed=[]
    for path in sorted(OUT.glob('*.json')):
        document=json.loads(path.read_text())
        if not isinstance(document,dict) or not isinstance(document.get('results'),list):continue
        for r in document['results']:
            if 'id' not in r or not (Path('runtime/replays')/(r['id']+'.jsonl')).exists():continue
            identifier=r['id']
            if identifier not in cache:
                data=analyze(identifier);issues=[]
                for s in ('a','b'):
                    if data[s]['frozen_remote']:issues.append(f'{s}: frozen opponent replication')
                    if data[s]['best']['samples']>=20 and data[s]['best']['median_squared_residual']>.25:issues.append(f'{s}: large opponent replication residual')
                cache[identifier]=({s:{k:v for k,v in data[s].items() if k!='fits'} for s in ('a','b')},issues)
            replication,issues=cache[identifier];r['replication']=replication
            v=r.setdefault('validation',{});v.setdefault('pre_replication_audit_passed',v.get('passed',False));v['replication_passed']=not issues
            v['issues']=sorted(set(v.get('issues',[])+issues))
            if issues:v['passed']=False
            r['combat_metrics']=combat_metrics(r)
        document.setdefault('metadata',{})['replication_audit']='Post-hoc remote-motion consistency; frozen/large-residual runs rejected'
        path.write_text(json.dumps(document,indent=2)+'\n');changed.append(path.name)
    rejected={key:issues for key,(_,issues) in cache.items() if issues}
    output={'unique_matches_audited':len(cache),'rejected':rejected,'updated_reports':changed,'criterion':'frozen remote 3D extent with moving true target, or median squared horizontal alignment residual >0.25 m²'}
    (OUT/'replication-audit.json').write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps(output,indent=2))

if __name__=='__main__':audit()
