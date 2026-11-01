#!/usr/bin/env python3
"""Native wiring check, not a performance comparison or a tuned controller."""
import asyncio,json,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from experiments import match,status
from lab_runtime import send
from parity import archive
from science import server_events
from native_launch_parity import audit

async def profile(command,expected):
    send('lab combat '+command)
    for _ in range(40):
        if status().get('next_scenario_combat',{}).get('fresh_hit_ticks')==expected:return
        await asyncio.sleep(.25)
    raise RuntimeError('Profile command did not apply')

async def main():
    results=[]
    try:
        for command,ticks in [('vanilla',10),('kb_horizontal=0.95 kb_vertical=0.95 hit_ticks=9',9)]:
            await profile(command,ticks)
            g=await match('combo-hold-w20','research-still20',20,20,duration_ms=10000,experimental_combat=True)
            archive(g);r=audit(g['id']);assert r['fresh_launches']>5 and not r['failures'],r
            events=server_events(g['id']);scales=[e for e in events if e['type']=='combat_velocity_scale']
            assert bool(scales)==(ticks!=10)
            fresh=[e for e in events if e['type']=='hit' and not e.get('damage_upgrade')]
            assert all(e['target_state']['hurtResistantTime']==2*ticks for e in fresh)
            results.append({'game':g,'launch_parity':r,'scale_events':len(scales),'fresh_hits':len(fresh)})
            (ROOT/'research/combat-profile-native-check-20261101.json').write_text(json.dumps(results,indent=2)+'\n')
            print(json.dumps({'match':g['id'],'profile':g['combat_profile'],'launch_parity':r,'scale_events':len(scales)}),flush=True)
    finally:await profile('vanilla',10)

if __name__=='__main__':asyncio.run(main())
