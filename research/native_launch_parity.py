#!/usr/bin/env python3
"""Independent vanilla melee-to-S12 calculation, checked against real server events.

Scope: sword/fist with no knockback enchant or knockback resistance; accepted
fresh hits with noncoincident XZ positions. Inputs are measured pre-attack server
state. This validates the launch map, not a whole counterfactual fight.
"""
import gzip,json,math,struct,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
F=lambda x:struct.unpack('f',struct.pack('f',x))[0]
SIN=[F(math.sin(i*math.pi*2/65536)) for i in range(65536)]

def impulse(a,b,horizontal=1.,vertical=1.):
    dx=a['posX']-b['posX'];dz=a['posZ']-b['posZ']
    length=F(math.sqrt(dx*dx+dz*dz))
    if length<.0001:raise ValueError('Coincident/random knockback branch')
    x=b['motionX']/2-dx/length*F(.4)
    y=min(b['motionY']/2+F(.4),float(F(.4)))
    z=b['motionZ']/2-dz/length*F(.4)
    if a['sprint']:
        radians=F(F(F(a['rotationYaw'])*F(math.pi))/F(180))
        sine=SIN[int(F(radians*F(10430.378)))&65535]
        cosine=SIN[int(F(F(radians*F(10430.378))+F(16384)))&65535]
        x+=F(-sine*F(.5));y+=.1;z+=F(cosine*F(.5))
    return [x*horizontal,y*vertical,z*horizontal]

def packet(v):return [int(max(-3.9,min(3.9,x))*8000)/8000 for x in v]

def audit(mid):
    game=json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text())
    path=ROOT/'research/traces'/f'{mid}-server.jsonl.gz'
    f=gzip.open(path,'rt') if path.exists() else (ROOT/'runtime/replays'/f'{mid}.jsonl').open()
    profile=game.get('combat_profile',{});threshold=profile.get('fresh_hit_ticks',10)
    rows=[];failures=[]
    with f:
        for line in f:
            e=json.loads(line)
            if e['type']!='science_server_attack' or e['score_before']==e['score_after'] or e['target_before']['hurtResistantTime']>threshold:continue
            if 'velocity_packet' not in e:continue
            predicted=packet(impulse(e['attacker_before'],e['target_before'],profile.get('kb_horizontal',1),profile.get('kb_vertical',1)))
            ok=predicted==e['velocity_packet']
            rows.append(ok)
            if not ok:failures.append({'t_ms':e['time']-game['start'],'predicted':predicted,'native':e['velocity_packet']})
    return {'match':mid,'fresh_launches':len(rows),'exact_packet_matches':sum(rows),'failures':failures}

if __name__=='__main__':
    report=[audit(mid) for mid in sys.argv[1:]]
    out=ROOT/'research/native-launch-parity-20261101.json';out.write_text(json.dumps(report,indent=2)+'\n')
    for r in report:print(r)
