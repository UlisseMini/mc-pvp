"""Fixed-sample tournament analysis. Match points = win + half a draw."""
import csv
import json
import statistics
from collections import Counter,defaultdict
from pathlib import Path
import numpy as np
from scipy.stats import beta,t
from study import D,ROOT,BUILD,NEW,OLD,FOES,WT,read,freeze,sha
from native_opening import extract,impacts
from live import native_name
F=D/'specialist'
O=D/'opening'
POINTS={'W':1.,'L':0.,'D':.5}
MEAN=statistics.mean

def interval(values):
    vals=np.asarray(values,dtype=float)
    avg=float(vals.mean())
    if len(vals)<2:return {'n':len(vals),'mean':avg}
    rad=float(t.ppf(.975,len(vals)-1)*vals.std(ddof=1)/np.sqrt(len(vals)))
    boot=np.random.default_rng(777519).choice(vals,(20000,len(vals))).mean(axis=1)
    return dict(n=len(vals),mean=avg,ci95=[avg-rad,avg+rad],bootstrap95=np.quantile(boot,[.025,.975]).tolist())

def classify(contrast):
    lo,hi=contrast['ci95']
    return dict(direction='higher' if lo>0 else 'lower' if hi<0 else 'unresolved',
                within_5pp=lo>=-.05 and hi<=.05,
                exceeds_10pp='higher' if lo>.10 else 'lower' if hi<-.10 else 'not established')

def summary(rows):
    c=Counter(r['outcome'] for r in rows);n=sum(c.values())
    if not n:return dict(n=0)
    blocks=defaultdict(list)
    for r in rows:blocks[r['block']].append(POINTS[r['outcome']])
    result=dict(n=n,W=c['W'],L=c['L'],D=c['D'],score=(c['W']+.5*c['D'])/n,blocks=interval([MEAN(v) for v in blocks.values()]),
        raw_win_blocks=interval([MEAN(y==1 for y in v) for v in blocks.values()]))
    if c['W'] in (0,n):
        result['exact_raw_win_ci_iid_games']=[float(beta.ppf(.025,n,1)),1.] if c['W']==n else [0.,float(beta.ppf(.975,1,n))]
        if c['W']==n or c['L']==n:
            k=len(blocks);result['conservative_points_bounds_independent_blocks']=[float(beta.ppf(.025,k,1)),1.] if c['W']==n else [0.,float(beta.ppf(.975,1,k))]
    return result

def contrast(rows,a,b,func=lambda r:True):
    groups=defaultdict(lambda:defaultdict(list))
    for r in rows:
        if r['arm'] in (a,b) and func(r):groups[r['block']][r['arm']].append(POINTS[r['outcome']])
    if not groups or any(a not in v or b not in v for v in groups.values()):raise ValueError('Unpaired comparison')
    return interval([MEAN(v[a])-MEAN(v[b]) for v in groups.values()])

def validate_fixture(row,trial):
    record=read(ROOT/'runtime/results'/(row['mid']+'.json'))
    assert record['combat_profile']['knockback_model']==trial['knockback_model']
    for seat in 'ab':
        label=record['policy_'+seat]
        expected=trial['arm'] if seat==trial['side'] else trial['opponent']
        assert label==expected and record['ping_'+seat]==trial['ping_'+seat]
        assert record['client_'+seat]['bridge_sha256']==read(D/'pins.json')['bridge']
        params=record['parameters_'+seat]
        assert float(params['click_cps'])==trial.get('cps_cap',20) and float(params['attack_reach'])==3 and params['aim_input']=='mouse'
        assert all(float(params[k])==0 for k in ('reaction_ms','aim_delay_ms','aim_noise_deg','aim_turn_rate_dps'))
        if label==WT:
            assert params['kind']=='wtap' and not params.get('duel_network')
        else:
            source=D/'frozen'/(label+'.net')
            if label.startswith('sumo-cap-'):
                chosen=read(D/'curriculum/ready.json');id=chosen['methods'][label.removeprefix('sumo-cap-')]['id']
                source=F/'fits'/(id+'.net');assert sha(source)==chosen['weights'][id]
            elif label=='sumo-seven-first':
                pilot=read(D/'seven/pilot/release.json');source=F/'fits'/(pilot['checkpoint']+'.net')
                assert sha(source)==pilot['weights']
            elif label.startswith('sumo-seven-'):
                chosen=read(D/'seven/ready.json');id=chosen['methods'][label.removeprefix('sumo-seven-')]['id']
                source=F/'fits'/(id+'.net');assert sha(source)==chosen['weights'][id]
            if not source.exists():
                short=label.removeprefix('sumo-frozen-')
                chosen=read((D/'branches' if short=='branches' else O if short in ('cue','gate') else F)/'ready.json')
                id=chosen['methods'][short]['id'];source=F/'fits'/(id+'.net')
                assert sha(source)==chosen['weights'][id]
            assert params['duel_network']==source.read_text().strip()

def native_blocks(letter,n):
    rows=[];invalid=[]
    for i in range(1,n+1):
        name=f'{letter}{i:02}';plan=read(D/'native'/(name+'.json'));v=read(D/'verified'/(name+'.json'))
        if v['rows'].keys()!=plan['plan'].keys():raise ValueError(name)
        invalid+=v['invalid']
        for q,r in v['rows'].items():
            assert r['valid'] and (r['click_min_ms'] is None or r['click_min_ms']>=50)
            validate_fixture(r,plan['plan'][q])
            rows.append(dict(r,block=name,request=q))
    assert len({r['mid'] for r in rows})==len(rows)
    return rows,invalid

def model_rows(path):
    rows=[]
    for r in csv.DictReader(path.open()):
        y=float(r['reward'])
        rows.append(dict(arm=native_name(r['arm']),foe=native_name(r['foe']),block=r['calendar'],seat=r['seat'].lower(),
            own=int(r['own_rtt']),other=int(r['opponent_rtt']),outcome={0.:'L',.5:'D',1.:'W'}[y]))
    return rows

def table(rows,arms,foes):
    return {f:{a:summary([r for r in rows if r['arm']==a and r['foe']==f]) for a in arms} for f in foes}

def breakdown(rows):
    return {f'{s}-{p}/{q}':summary([r for r in rows if (r['seat'],r['own'],r['other'])==(s,p,q)])
            for s,p,q in sorted({(r['seat'],r['own'],r['other']) for r in rows})}

def reverse(r):
    return dict(r,arm=r['foe'],foe=r['arm'],own=r['other'],other=r['own'],seat={'a':'b','b':'a'}[r['seat']],
                outcome={'W':'L','L':'W','D':'D'}[r['outcome']])

def physical(rows):
    seconds=0;press=[0,0];min_gap=[None,None];heads=takeoffs=0
    for r in rows:
        v=read(BUILD/'scores'/(r['mid']+'.json'));seconds+=v['duration_ms']/1000;heads+=r['checked_heads']
        takeoffs+=v['confirmed_takeoffs']
        for i,s in enumerate((r['seat'],'b' if r['seat']=='a' else 'a')):
            n,dt=v['press'][s];press[i]+=n
            if dt is not None:min_gap[i]=min(dt,dt if min_gap[i] is None else min_gap[i])
    return dict(n=len(rows),seconds=seconds,physical_presses=press,presses_per_regulation_second=[x/seconds for x in press],
        minimum_interval_ms=min_gap,verified_heads_both=heads,actor_confirmed_takeoffs=takeoffs)

def openings(rows):
    pairs=[(r,extract(r)) for r in rows]
    wins={};data=[m for r,m in pairs];total=sum(x['target']['gated_in_reach_samples'] for x in data)
    for first in (True,False,None):wins[str(first)]=summary([r for r,x in pairs if x.get('first_own')==first])
    observed=[impacts(r) for r in rows]
    kb={}
    for group in ('with_near_takeoff','without_near_takeoff'):
        ins=[v[group] for v in observed if group in v]
        kb[group]=dict(n=len(ins),median_sent_horizontal=statistics.median(x['sent_horizontal'] for x in ins) if ins else None,
            median_outward_travel=statistics.median(x['outward_travel'] for x in ins) if ins else None)
    return dict(n=len(data),first_scorer=wins,first_incoming_per_jump_group=kb,median_first_horizontal_gap=statistics.median([m['first_gap'] for m in data if 'first_gap' in m]) if any('first_gap' in m for m in data) else None,
        target_pre_feedback_in_reach_samples=total,
        target_gate_closed_in_reach=sum(x['target']['gate_closed_in_reach'] for x in data),
        matches_target_closed_gate_in_reach=sum(x['target']['gate_closed_in_reach']>0 for x in data),
        matches_target_observed_in_reach=sum(x['target']['gated_in_reach_samples']>0 for x in data),
        matches_with_actor_takeoff=sum(x['takeoffs']>0 for x in data))

def mechanism(path):
    rows=[json.loads(line) for line in path.open()];out={}
    for a in sorted({r['arm'] for r in rows}):
        part=[r for r in rows if r['arm']==a];blocks=defaultdict(list)
        for r in part:blocks[r['clock']].append(r)
        jumps=[r for r in part if 'jump_score_without' in r]
        measured=[r for r in jumps if r['jump_displacement_window_complete']]
        def delta(r):return r['away_with_jump']-r['away_without_jump']
        out[a]=dict(n=len(part),score=MEAN(r['score'] for r in part),
            original_minus_wtap_opening=interval([MEAN(r['score']-r['own_wtap_opening_movement'] for r in rs) for rs in blocks.values()]),
            original_minus_target_open_gate=interval([MEAN(r['score']-r['target_open_gate'] for r in rs) for rs in blocks.values()]),
            candidate_scored_first=sum(bool(r.get('scored_first')) for r in part),games_with_any_fresh=sum('scored_first' in r for r in part),
            selected_jumps=len(jumps),complete_displacement=len(measured),same_hit_without_jump=sum(r['same_hit_timestamp_without_jump'] for r in jumps),
            original_minus_suppressed_jump=MEAN(r['score']-r['jump_score_without'] for r in jumps) if jumps else None,
            mean_change_outward_blocks=MEAN(delta(r) for r in measured) if measured else None,
            same_hit_mean_change_outward_blocks=MEAN(delta(r) for r in measured if r['same_hit_timestamp_without_jump']) if any(r['same_hit_timestamp_without_jump'] for r in measured) else None,
            jump_improved_outcome=sum(r['score']>r['jump_score_without'] for r in jumps),
            jump_worsened_outcome=sum(r['score']<r['jump_score_without'] for r in jumps))
    return out

def arm_target(rows,arms):
    return {a:{'overall':summary(sub),'conditions':breakdown(sub)}
            for a in arms if (sub:=[r for r in rows if r['arm']==a and r['foe']==OLD])}

def run():
    gr,gi=native_blocks('g',32);sp,si=native_blocks('s',24);m=model_rows(D/'model/transfer.csv')
    assert len(gr)==1536 and len(sp)==432 and len(m)==12800
    sel=read(F/'ready.json');pri='sumo-frozen-'+sel['primary'];alt='sumo-frozen-'+sel['alternate']
    raw=[r for r in gr if r['foe'] in FOES];direct=[r for r in gr if r['foe']==OLD]
    delta=contrast(raw,NEW,OLD);head=interval([v-.5 for v in [MEAN(POINTS[r['outcome']] for r in direct if r['block']==b) for b in sorted({r['block'] for r in direct})]])
    assert len(direct)==384
    ref=[reverse(r) for r in gr if r['arm']==OLD and r['foe']==WT]
    asym=[r for r in m if r['own']!=r['other']]
    general=dict(native_field=table(raw,[NEW,OLD,WT],FOES),field_delta=delta,classification=classify(delta),
        native_overall={a:summary([r for r in raw if r['arm']==a]) for a in [NEW,OLD,WT]},
        direct_vs_even=head,direct_classification=classify(head),direct=summary(direct),direct_cells=breakdown(direct),
        wtap_vs_old_reversed=summary(ref),wtap_vs_old_reversed_cells=breakdown(ref),
        native_field_cells={a:breakdown([r for r in raw if r['arm']==a]) for a in [NEW,OLD,WT]},
        simulated_field_difference_asymmetric=contrast(asym,NEW,OLD,lambda r:r['foe'] in FOES),
        simulated_by_opponent=table(m,[NEW,OLD,WT,pri,alt],sorted({r['foe'] for r in m})),
        simulated_asymmetric_vs_frozen=arm_target(asym,[NEW,OLD,WT,pri,alt]),simulated_all_links_vs_frozen=arm_target(m,[NEW,OLD,WT,pri,alt]))
    # Field mirrors are isolated by fixture role, even if an alias coincides with a common opponent.
    spdirect=[r for r in sp if r['foe']==OLD]
    specialist=dict(selection=sel,native_frozen=arm_target(spdirect,[pri,alt,OLD,WT]),
        field=table([r for r in sp if r['foe'] in FOES]+[r for r in raw if r['arm']==WT],[pri,WT],FOES),
        actions={a:physical([r for r in spdirect if r['arm']==a]) for a in [pri,alt,WT,OLD]},
        openings={a:openings([r for r in spdirect if r['arm']==a]) for a in [pri,alt,WT,OLD]},
        simulator_interventions=mechanism(D/'model/mechanisms.jsonl'))
    if sel['weights'][sel['methods'][sel['primary']]['id']]==sel['weights'][sel['methods'][sel['alternate']]['id']]:
        both=[r for r in spdirect if r['arm'] in (pri,alt)]
        specialist['identical_weights_combined']=dict(score=summary(both),conditions=breakdown(both),actions=physical(both))
    report=dict(general=general,specialist=specialist,native_games=len(gr)+len(sp),invalid=gi+si,
                test_calendars=len({r['block'] for r in m}),frozen=read(D/'pins.json'),human='ungraded')
    o=read(O/'ready.json')
    if o['run']:
        native,invalid=native_blocks('o',16);assert len(native)==160
        om=model_rows(O/'model/transfer.csv');alts=['sumo-frozen-'+m for m in o['methods']]
        aa='sumo-frozen-'+o['primary']
        report['opening_search']=dict(selection=o,native=arm_target(native,[aa,pri,WT]),simulated=arm_target(om,[*alts,pri,WT,OLD]),
            actions={a:physical([r for r in native if r['arm']==a]) for a in [aa,pri,WT]},
            openings={a:openings([r for r in native if r['arm']==a]) for a in [aa,pri,WT]},
            simulator_interventions=mechanism(O/'model/mechanisms.jsonl'))
        report['native_games']+=len(native);report['invalid']+=invalid
    equal,invalid=native_blocks('e',12);eq=read(D/'equal/plan.json')['actor'];assert len(equal)==144
    sim=om if o['run'] and eq in alts else m
    report['equal_rtt']=dict(actor=eq,native=arm_target(equal,[eq,WT]),
        model=arm_target([r for r in sim if r['own']==r['other']],[eq,WT]),
        actions={a:physical([r for r in equal if r['arm']==a]) for a in [eq,WT]})
    report['native_games']+=len(equal);report['invalid']+=invalid
    branch=read(D/'branches/ready.json')
    if branch['run']:
        br,invalid=native_blocks('c',16);assert len(br)==128
        folder=D/'branches/model';brm=model_rows(folder/'transfer.csv')
        arms=['sumo-frozen-branches','sumo-frozen-ppo',WT]
        report['counterfactual']=dict(selection=branch,native=arm_target(br,arms),model=arm_target(brm,arms),
            actions={a:physical([r for r in br if r['arm']==a]) for a in arms},
            openings={a:openings([r for r in br if r['arm']==a]) for a in arms},simulator_interventions=mechanism(folder/'mechanisms.jsonl'))
        report['native_games']+=len(br);report['invalid']+=invalid
    freeze(D/'assessment.json',report)
if __name__=='__main__':run()
