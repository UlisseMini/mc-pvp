"""Find comparable traded openings followed by two fresh hits from one fighter, using development fights only."""
from pathlib import Path
import collections
import json
import math
import sys
from human_inputs import HERE,ROOT


def scan(match_ids, identity):
    by_winner={True:[],False:[]}
    for mid in match_ids:
        record=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())
        side=next((s for s in 'ab' if (record[s] if identity=='Nomophobia' else record['policy_'+s])==identity),None)
        if side is None:continue
        human=record[side];other='b' if side=='a' else 'a'
        with (ROOT/'runtime/replays'/(mid+'.jsonl')).open() as f:
            events=[r for l in f if (r:=json.loads(l))['type'] in ('hit','mmc_knockback')]
        hits=[e for e in events if e['type']=='hit' and not e['rod']]
        impulses=[e for e in events if e['type']=='mmc_knockback']
        fresh=[h for h in hits if not h['damage_upgrade']]
        if len(impulses)==len(fresh):
            for h,k in zip(fresh,impulses):
                if h['target']!=k['target'] or abs(h['time']-k['time'])>30:raise ValueError('Ambiguous impulse '+mid)
                h['velocity_packet']=[int(x*8000)/8000 for x in k['velocity']]

        for i in range(1,len(hits)-2):
            a,b,c,d=hits[i-1:i+3]
            if any(r['damage_upgrade'] for r in (a,b,c,d)):continue
            if a['attacker']==b['attacker'] or b['time']-a['time']>150:continue
            if c['attacker']!=d['attacker'] or d['time']-c['time']>700 or c['time']-b['time']>750:continue
            won=c['attacker']==human
            def summarize(h):
                attacker=h['attacker_state'];target=h['target_state']
                # The emitted velocity packet is a target impulse, in blocks per tick.
                impulse=h.get('velocity_packet')
                return dict(t=h['time']-record['start'],swing_by_subject=h['attacker']==human,server_tick=h['server_tick'],
                    attacker_sprint=attacker['sprint'],center_gap=math.hypot(attacker['posX']-target['posX'],attacker['posZ']-target['posZ']),
                    relative_y=attacker['posY']-target['posY'],horizontal_kb=math.hypot(impulse[0],impulse[2]) if impulse else None)
            by_winner[won].append(dict(mid=mid,subject=identity,side=side,opponent=record['policy_'+other],
                opponent_cps=record['click_cps_'+other],opening_order=i,sequence=[summarize(h) for h in (a,b,c,d)]))
    return by_winner


def add_human_inputs(event):
    # Summarize actual actions after each combat-log timestamp, only from the human's own client.
    import gzip
    p=ROOT/'runtime/pov'/event['mid'];meta=json.loads((p/'manifest.json').read_text())
    path,=p.glob(event['side']+'-client-*-events.jsonl.gz');seq=event['sequence']
    rows=[]
    with gzip.open(path,'rt') as f:
        for line in f:
            row=json.loads(line);t=row.get('time_ms',0)-meta['start_ms'];d=row.get('data',{})
            if not seq[0]['t']-75<=t<=seq[-1]['t']+50:continue
            if row['type']=='tick_end' and not d.get('screen') and d.get('self'):
                m=d['self']['movement_input'];rows.append([t,'keys',m['moveForward'],m['moveStrafe'],m['jump'],d['self']['ground']])
            elif row['type']=='mouse' and d.get('button')==0 and d.get('down'):rows.append([t,'left-down'])
    event['own_inputs']=rows


if __name__=='__main__':
    plan=json.loads((HERE/'human-development.json').read_text())
    human=scan(plan['fit'],'Nomophobia')
    # Earlier published native development matches; never select later prospective validation fights for examples.
    bots=scan(['m2413','m2430']+json.loads((HERE/'protocol.json').read_text())['training_clocks'],'research-wtap20')
    out={}
    for source,group in [('human',human),('wtap',bots)]:
        for val,rows in group.items():
            if not rows:continue
            picks=[];seen=set()
            for r in rows:
                if r['mid'] in seen:continue
                if source=='human':add_human_inputs(r)
                picks.append(r);seen.add(r['mid'])
                if len(picks)==3:break
            out[source+('-won' if val else '-lost')]=dict(eligible_windows=len(rows),first_unique_fights=picks)
    Path(sys.argv[1]).write_text(json.dumps(out,indent=2)+'\n')
