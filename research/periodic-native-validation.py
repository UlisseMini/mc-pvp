#!/usr/bin/env python3
"""Held-out server C03 latent-motion check for the periodic plant; no live game."""
import gzip,json,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA='/opt/homebrew/opt/openjdk@17/bin/java'

def audit(mid,classes):
    game=json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text());start=game['start']
    events=[json.loads(l) for l in gzip.open(ROOT/'research/traces'/f'{mid}-server.jsonl.gz','rt')]
    initial=next(e for e in events if e['type']=='science_server_attack' and e['attacker']==game['a'] and e['score_before']!=e['score_after'])
    finish=next(e['time'] for e in events if e['type']=='science_server_attack' and e['attacker']==game['b'] and e['score_before']!=e['score_after'] and e['time']>initial['time'])
    q=initial['target_before'];assert abs(q['rotationYaw']-90)<.01
    lines=['\t'.join(map(str,['INIT',q['posX'],q['posY'],q['motionX'],q['motionY'],str(q['ground']).lower()]))]
    expected=[None];seen=False;move_count=0
    for e in events:
        if e is initial:seen=True;continue
        if not seen or e.get('time',0)>=finish:continue
        if e['type']=='replication_server_input' and e.get('player')==game['b'] and 'moving' in e:
            assert abs(e.get('yaw',90)-90)<.01 or not e.get('rotating',False)
            lines.append('\t'.join(map(str,['MOVE',e['x'],e['y'],str(e['onGround']).lower(),str(e['moving']).lower()])))
            expected.append(None);move_count+=1
        elif e['type']=='science_server_attack' and e['target']==game['b']:
            lines.append('READ');expected.append((e['time']-start,'attack_before',e['target_before']))
        elif e['type']=='tick':
            lines.append('READ');expected.append((e['time']-start,'tick',e['b']))
    result=subprocess.run([JAVA,'-cp',classes,'PeriodicComboServerReplay'],input='\n'.join(lines)+'\n',text=True,capture_output=True,check=True)
    rows=result.stdout.splitlines();assert len(rows)==len(lines)
    comparisons=[]
    for line,e in zip(rows,expected):
        if e is None:continue
        t,kind,q=e;vx,vy,ground,x,y=line.split('\t');p=[float(vx),float(vy)]
        comparisons.append({'t_ms':t,'kind':kind,'predicted':p,'native':[q['motionX'],q['motionY']],
                            'absolute_error':[abs(p[0]-q['motionX']),abs(p[1]-q['motionY'])],
                            'position_matches':float(x)==q['posX'] and float(y)==q['posY'],
                            'ground_matches':(ground=='true')==q['ground']})
    return {'match':mid,'start_ms':initial['time']-start,'stop_before_return_ms':finish-start,'moves':move_count,
            'comparisons':len(comparisons),'max_absolute_vx_error':max(e['absolute_error'][0] for e in comparisons),
            'max_absolute_vy_error':max(e['absolute_error'][1] for e in comparisons),'position_mismatches':sum(not e['position_matches'] for e in comparisons),
            'ground_mismatches':sum(not e['ground_matches'] for e in comparisons),'rows':comparisons}

if __name__=='__main__':
    with tempfile.TemporaryDirectory(prefix='periodic-server-')as classes:
        subprocess.run([JAVA.replace('/java','/javac'),'-proc:none','-d',classes,*[str(ROOT/p)for p in ('src/lab/ExactTravel.java','src/lab/Json.java','tests/PeriodicComboFightModel.java','tests/PeriodicComboServerReplay.java','tests/PeriodicComboTransport.java')]],check=True,capture_output=True)
        data=[audit('m1170',classes)]
    (ROOT/'research/periodic-native-server-motion.json').write_text(json.dumps(data,indent=2)+'\n')
    print(json.dumps([{k:v for k,v in d.items() if k!='rows'}for d in data],indent=2))
