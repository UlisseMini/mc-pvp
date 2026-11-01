"""Native fail-closed/provenance check; requires isolated forge,legacy slots on a nonzero port offset."""
import json,time,sys,gzip,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from lab_runtime import send
from runtime_provenance import artifact_hashes,client_matches,client_provenance
root=ROOT;expected=artifact_hashes()
manager=json.loads((root/'runtime/manager.json').read_text())
assert manager.get('port_offset',0)>0 and manager.get('bot_backends')==['forge','legacy'], "Use an isolated mixed-backend test instance only"
tag="migration-invalid-"+uuid.uuid4().hex[:8];recovery_tag=tag+"-recovered"
def status():
 try:return json.loads((root/'runtime/status.json').read_text())
 except (OSError,ValueError):return {'players':[],'matches':[]}
end=time.time()+120
while time.time()<end:
 if len(status()['players'])==2:break
 time.sleep(.2)
else:raise RuntimeError('mixed clients not ready')
time.sleep(1)
identities={}
for i in range(2):
 h=json.loads((root/f'runtime/bots/{i}/heartbeat.json').read_text());assert client_matches(h,expected),(i,h,expected);identities[str(i)]=client_provenance(h)
send('lab scenario wtap hold-w 20 80 12000 '+tag)
while True:
 game=next((m for m in status()['matches'] if m.get('request')==tag),None)
 if game:break
 time.sleep(.2)
wait=max(0,(game['start']+3000)/1000-time.time());time.sleep(wait)
p=root/'runtime/bots/0/control.properties';saved=p.read_text();bad=saved+'\nkind=invalid-test-strategy\n'
def write(value):
 temporary=p.with_suffix('.check-tmp');temporary.write_text(value);temporary.replace(p)
invalid=int(time.time()*1000)
try:write(bad);time.sleep(1)
finally:restored=int(time.time()*1000);write(saved)
while not (root/f'runtime/results/{game["id"]}.json').exists():time.sleep(.2)
time.sleep(1.5)
side='a' if game['a']=='LabSlot0' else 'b'
path=next((root/'runtime/pov'/game['id']).glob(side+'-client*-events.jsonl.gz'))
rows=[json.loads(l) for l in gzip.open(path,'rt')]
window=[r for r in rows if invalid+250<=r.get('time_ms',r.get('time',0))<restored]
keys=[r['data']['self']['movement_input'] for r in window if r.get('type')=='tick_end']
assert len(keys)>=5,keys
assert all(k['moveForward']==0 and k['moveStrafe']==0 for k in keys),keys
assert not any(r.get('type') in ('click','engine_attack') for r in window)
assert json.loads((root/f'runtime/results/{game["id"]}.json').read_text())['reason']=='invalid_client_view'
# Referee correctly rejects the stale/invalid match. Recovery is a new valid duel.
send('lab scenario wtap hold-w 0 0 5000 '+recovery_tag)
while True:
 recovered=next((json.loads(p.read_text()) for p in (root/'runtime/results').glob('*.json') if json.loads(p.read_text()).get('request')==recovery_tag),None)
 if recovered:break
 time.sleep(.2)
assert recovered['reason']=='scenario_completed'
assert recovered['hits_a']>0 and recovered['hits_b']>0
header=rows[0];assert header['metadata']['bridge_sha256']==expected['bridge_sha256'],header['metadata']
assert header['metadata']['module_sha256']==expected['module_sha256']
result={'passed':True,'match':game['id'],'identities':identities,'header_identity':{k:header['metadata'].get(k) for k in ('role','backend','bridge_sha256','module_sha256')},'invalid_ms':invalid,'restored_ms':restored,'released_native_input_samples':len(keys),'no_clicks_or_attacks_while_invalid':True,'fresh_duel_recovered':recovered['id'],'expected_policy_errors':json.loads((root/'runtime/bots/0/heartbeat.json').read_text())['policy_errors']}
(root/'experiments/forge-migration-20261101/fail-closed-provenance.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
send('reload-bots')
