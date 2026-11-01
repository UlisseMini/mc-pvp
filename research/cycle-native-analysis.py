#!/usr/bin/env python3
"""Compare prescribed local hit slots with actual native contact/launch outcomes."""
import bisect,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from science import client_events,server_events
from trade_return_windows import link_clicks
from combo_survival import analyze
from fresh_window_analysis import analyze as windows

def audit(mid,side='a'):
    g=json.loads((ROOT/'runtime/results'/f'{mid}.json').read_text())
    c=client_events(g,side);s=server_events(mid);t0=g['start']
    plans={e['native_tick']:e for e in c if e['type']=='cycle_plan'}
    rows,links,linkage=link_clicks(s,c,g,side)
    out=[]
    for row in rows:
        p=plans.get(row['native_tick'],{});idx=row.get('server_attack_index');e=s[idx] if idx is not None else {}
        out.append(dict(t=row['consumption_ms'],tick=row['native_tick'],phase=p.get('phase'),coast=p.get('coast_ticks'),
                        range=row['current_box_min_distance'],outcome=row['outcome'],
                        sprint=e.get('attacker_before',{}).get('sprint'),H=row['exact_server_victim_H'],
                        server_height=e.get('target_before',{}).get('posY',4)-4,
                        impulse=e.get('velocity_packet'),local_sprint=row['local_sprint']))
    r={'match':mid,'linkage':linkage,'contacts':out,'combo':analyze(g),'readiness':windows(g,side)}
    path=ROOT/'research'/f'{mid}-cycle-audit.json';path.write_text(json.dumps(r,indent=2)+'\n')
    print('Match',mid,'score',g['hits_a'],g['hits_b'],'longest fresh',r['combo']['sides'][side]['longest_fresh_run'],flush=True)
    print('Readiness',r['readiness']['readiness_class_counts'],'best run',r['readiness']['longest_certified_ready_window_run'])
    for row in out[:24]:print(row)
    print(path)
    return r

if __name__=='__main__':audit(sys.argv[1],sys.argv[2] if len(sys.argv)>2 else 'a')
