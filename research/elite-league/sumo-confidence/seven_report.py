"""Crossover outcomes and replay measurements for both equal caps."""
import statistics
from collections import defaultdict
from study import D,ROOT,OLD,WT,read,freeze
from confidence_analysis import native_blocks,model_rows,summary,breakdown,physical,interval,POINTS,MEAN
from seven_study import S,PRIOR
from seven_metrics import one
from confidence_report import table,wld,iv,signed,scoreci

def matched(rows,arm1,c1,arm2,c2):
    pairs=defaultdict(lambda:[[],[]])
    for r in rows:
        for i,(arm,cap) in enumerate([(arm1,c1),(arm2,c2)]):
            if r['arm']==arm and r['cap']==cap and r['foe']==OLD:pairs[r['block']][i].append(POINTS[r['outcome']])
    assert pairs and all(len(a)==len(b) and a for a,b in pairs.values())
    return interval([MEAN(a)-MEAN(b) for a,b in pairs.values()])

def adjusted_gain(rows,new):
    values=defaultdict(lambda:defaultdict(list))
    for r in rows:
        if r['foe']==OLD:
            values[r['block']][(r['arm'],r['cap'])].append(POINTS[r['outcome']])
    return interval([(MEAN(v[new,7])-MEAN(v[WT,7]))-(MEAN(v[PRIOR,20])-MEAN(v[WT,20])) for v in values.values()])

def mechanics(rows):
    ms=[one(r) for r in rows];out={}
    for seat in ('actor','frozen'):
        xs=[x[seat] for x in ms];report={}
        for k in ('server_horizontal','outward_travel','physical_presses_after_velocity','entity_contacts_after_velocity','brakes_after_velocity'):
            vals=[r[k] for r in xs if r[k] is not None]
            report[k]=dict(games=len(vals),mean=MEAN(vals) if vals else None)
        for k in ('incoming_fresh','unique_server_velocity','matched_client_velocity','complete_recoil_windows','uninterrupted_client_windows'):
            report[k]=sum(r[k] for r in xs)
        out[seat]=report
    return out

def mechanism_contrasts(rows,arm):
    cells=defaultdict(dict)
    for r in rows:
        if r['arm']==arm and r['foe']==OLD:cells[(r['block'],r['seat'],r['own'])][r['cap']]=one(r)
    out={}
    for side in ('actor','frozen'):
        out[side]={}
        for k in ('server_horizontal','outward_travel','physical_presses_after_velocity','brakes_after_velocity'):
            block=defaultdict(list)
            for (b,s,p),pair in cells.items():
                a,c=pair[7][side][k],pair[20][side][k]
                if a is not None and c is not None:block[b].append(a-c)
            out[side][k]=dict(included_cells=sum(map(len,block.values())),
                              delta_7_minus_20=interval([MEAN(v) for v in block.values()]) if block else None)
    return out

def run():
    sel=read(S/'ready.json');name='sumo-seven-'+sel['primary'];actors=[name,PRIOR,WT]
    all_rows=[];invalid=[]
    for tag in 'kl':
        rows,bad=native_blocks(tag,16);invalid+=bad
        for r in rows:
            trial=read(D/'native'/(r['block']+'.json'))['plan'][r['request']]
            r['cap']=trial['cps_cap'];r['block']=r['block'][1:]
            assert r['click_min_ms'] is None or r['click_min_ms']>=((1_000_000_000+r['cap']-1)//r['cap'])/1e6
        all_rows+=rows
    assert len(all_rows)==416 and len({r['mid'] for r in all_rows})==416
    model=[]
    for cap in (7,20):model += [dict(r,cap=cap) for r in model_rows(S/'model'/f'transfer-{cap}.csv')]
    assert len({r['block'] for r in model})==16
    result=dict(selection=sel,invalid=invalid,native_games=len(all_rows))
    for key,events in [('simulation',[r for r in model if r['own']!=r['other']]),('minecraft',all_rows)]:
        out={}
        for cap in (7,20):
            out[str(cap)]={}
            for a in actors:
                sub=[r for r in events if r['cap']==cap and r['arm']==a and r['foe']==OLD]
                cell=dict(overall=summary(sub),conditions=breakdown(sub))
                if key=='minecraft':cell.update(actions=physical(sub),mechanics=mechanics(sub))
                out[str(cap)][a]=cell
        out['contrasts']={'training_gain_at_7':matched(events,name,7,PRIOR,7),'same_weights_cap_effect':matched(events,PRIOR,7,PRIOR,20),
            'same_new_weights_cap_effect':matched(events,name,7,name,20),'best_response_difference':matched(events,name,7,PRIOR,20),
            'wtap_cap_effect':matched(events,WT,7,WT,20),'response_gain_over_wtap_7_minus_20':adjusted_gain(events,name)}
        result[key]=out
    result['mechanism_contrasts']={a:mechanism_contrasts(all_rows,a) for a in actors}
    result['native_field']={f:{a:summary([r for r in all_rows if r['arm']==a and r['foe']==f]) for a in (name,WT)} for f in (WT,'sumo-own-ping')}
    result['model_all_links']={str(c):{a:{f:summary([r for r in model if r['arm']==a and r['foe']==f and r['cap']==c])
        for f in sorted({r['foe'] for r in model})} for a in actors} for c in (7,20)}
    freeze(S/'assessment.json',result)
    def ci_str(r):return signed(r['mean'])+' pp ('+iv(r)+')'
    lines=['# Seven versus twenty CPS in MMC sumo','',f'The 7-CPS training winner is `{name}`. Duel it with `/duel {name} sumo preset=mmc cps=7`. Both clients used the same physical cap in every bout. A win earns one point and a draw earns half.', '']
    for key,label in [('minecraft','Native Minecraft'),('simulation','Prospective simulator')]:
        d=result[key];lines += ['## '+label,'',
            *table(['Both CPS caps',name+' W–L–D',PRIOR+' W–L–D','Plain W-tap W–L–D'],[(c,*[wld(d[str(c)][a]['overall']) for a in actors]) for c in (7,20)]),
            *table(['Paired comparison','Estimate (95% interval)','Plain W-tap, 7 minus 20'],
                   [(k.replace('_',' '),ci_str(v),ci_str(d['contrasts']['wtap_cap_effect'])) for k,v in d['contrasts'].items() if k!='wtap_cap_effect'])]
    d=result['minecraft']
    lines += ['## Physical inputs and knockback','',
        'These are measured from the native client and server replays. Actual CPS includes time before contact. Velocity is the horizontal length of the vector assigned by the server. Outward travel is displacement along the initial line away from the hitter at the first server sample 250–320 ms after damage. Brakes count confirmed contact reductions to 60% of the player’s outward horizontal velocity in the first 250 ms after the matching received packet. The per-game means include games with an observed eligible hit. Recoil windows may change when the cap changes.', '']
    def formatm(d):return f"{d['mean']:.3f}" if d['mean'] is not None else 'unobserved'
    for metric in ('server_horizontal','outward_travel','physical_presses_after_velocity','brakes_after_velocity'):
        lines+=table(['Measured '+metric.replace('_',' '),name,PRIOR,'Plain W-tap'],[(str(c)+' CPS',*[formatm(d[str(c)][a]['mechanics']['actor'][metric]) for a in actors]) for c in (7,20)])
    lines+=table(['Input cap','Actual CPS '+name,'Actual CPS '+PRIOR,'Actual CPS W-tap'],[(c,*[f"{d[str(c)][a]['actions']['presses_per_regulation_second'][0]:.2f}" for a in actors]) for c in (7,20)])
    lines+=['Full opponent measurements, per-seat and ping outcomes, physical minima, raw evidence coverage and independent block intervals are in assessment.json. Held-out human matches remain ungraded.', '']
    (S/'README.md').write_text('\n'.join(lines))
if __name__=='__main__':run()
