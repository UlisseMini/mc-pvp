import time
from seven_pilot import D,P,NAME,PRIOR,WT,OLD,freeze
from confidence_analysis import model_rows,native_blocks,physical,summary,breakdown,interval,mechanism,MEAN,POINTS
from opening_geometry import one as geometry
from seven_report import mechanics,mechanism_contrasts,matched
from confidence_report import table,wld,iv,signed
from statistics import median
def run():
    while not (D/'verified/h08.json').exists() or not (P/'mechanisms-20.jsonl').exists():time.sleep(4)
    data={'native':{},'simulator':{}};rows=[]
    for tag,cap in [('q',7),('h',20)]:
        native,invalid=native_blocks(tag,8);assert len(native)==48
        game=[dict(r,block=r['block'][1:],cap=cap) for r in native];rows+=game
        model=[r for r in model_rows(P/f'transfer-{cap}.csv') if r['foe']==OLD and r['own']!=r['other']]
        for label,events in [('native',game),('simulator',model)]:
            scores={}
            for a in (NAME,PRIOR,WT):
                sub=[r for r in events if r['arm']==a];scores[a]=dict(overall=summary(sub),cells=breakdown(sub))
                if label=='native':
                    pos=[geometry(r) for r in sub];fresh=[v for v in pos if 'first_ms' in v]
                    scores[a].update(controls=physical(sub),mechanics=mechanics(sub),
                        opening=dict(scored_first=sum(x['first_own'] for x in fresh),any_hit=len(fresh),
                            median_first_hit_ms=median(x['first_ms'] for x in fresh) if fresh else None,
                            median_self_radius=median(x['first_own_radius'] for x in fresh) if fresh else None,
                            median_opponent_radius=median(x['first_other_radius'] for x in fresh) if fresh else None,
                            median_clicks_before_feedback=median(x['presses_before_first_feedback'] for x in pos)))
            data[label][str(cap)]=scores
    data['native_cap_differences']={a:matched(rows,a,7,a,20) for a in (NAME,PRIOR,WT)}
    data['native_impact_differences']={a:mechanism_contrasts(rows,a) for a in (NAME,PRIOR,WT)}
    data['sim_interventions']={str(c):mechanism(P/f'mechanisms-{c}.jsonl') for c in (7,20)}
    freeze(P/'cap-transfer.json',data)
    lines=['# Does the exact 32–0 bot transfer back to 20 CPS?','',
        *table(['Matched cap and environment',NAME+' W–L–D',PRIOR+' W–L–D','Plain W-tap W–L–D'],
            [(f'{k} at {c} CPS',*[wld(data[k][str(c)][a]['overall']) for a in (NAME,PRIOR,WT)]) for c in (7,20) for k in ('native','simulator')]),
        'Both caps apply to both players. The exact weights, game physics and target stayed fixed. Native bouts used independent clock realizations. The simulators reused the same eight clocks at both caps.','']
    (P/'cap-transfer-results.md').write_text('\n'.join(lines))
if __name__=='__main__':run()
