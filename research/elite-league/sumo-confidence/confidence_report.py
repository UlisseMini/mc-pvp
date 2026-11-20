"""Render the complete scorecard from the checked results and frozen study."""
from study import D,OLD,NEW,WT,read
from statistics import mean

def rate(x):return f'{100*x:.1f}%'
def signed(x):return f'{100*x:+.1f}'
def iv(r):return f"{signed(r['ci95'][0])} to {signed(r['ci95'][1])} pp"
def wld(r):
    if not r.get('n'):return 'unavailable'
    return f"{r['W']}–{r['L']}–{r['D']} ({rate(r['score'])})"
def scoreci(r):
    bounds=r.get('conservative_points_bounds_independent_blocks',r['blocks']['ci95'])
    return f"{rate(bounds[0])} to {rate(bounds[1])}"
def table(headers,rows):
    return ['| '+' | '.join(headers)+' |','|'+'|'.join(['---']*len(headers))+'|',*['| '+' | '.join(map(str,r))+' |' for r in rows], '']
def render():
    data=read(D/'assessment.json');s=data['specialist'];g=data['general']
    primary='sumo-frozen-'+s['selection']['primary'];a=s['native_frozen'][primary]['overall']
    ref=s['native_frozen'][WT]['overall'];d=g['field_delta']
    lines=['# Can a learned specialist beat frozen best-sumo?','',
        f"All {data['native_games']:,} eligible duels ran in Minecraft 1.8.9 with mouse aim, three-block reach, a 20 CPS ceiling on each client and the 137-block MMC replica. The target was the immutable v4-ppo-g0496. Draws earn half a point. Records below are wins–losses–draws. Intervals use scheduling blocks in Minecraft and timing recordings in the simulator.",'',
        '## Best responses against the frozen champion','',
        f"The preregistered PPO/evolution primary was `{primary}`. Its primary native result was **{wld(a)}**, with a 95% interval of **{scoreci(a)}**. Plain W-tap scored **{wld(ref)}** in the same trial. The separate 128-bout W-tap reference obtained by reversing the incumbent's field fixtures scored {wld(g['wtap_vs_old_reversed'])}.",'']
    alltests=[('PPO primary',primary,s['native_frozen'],g['simulated_asymmetric_vs_frozen'])]
    if 'opening_search' in data:
        o=data['opening_search'];name='sumo-frozen-'+o['selection']['primary'];alltests.append(('Opening/continuation',name,o['native'],o['simulated']))
    if 'counterfactual' in data:
        o=data['counterfactual'];alltests.append(('Counterfactual learner','sumo-frozen-branches',o['native'],o['model']))
    e=data['equal_rtt'];alltests.append(('Equal RTT',e['actor'],e['native'],e['model']))
    lines+=table(['Native trial','Duel bot','Candidate W–L–D','95% points','Plain W-tap W–L–D'],
        [(title,name,wld(n[name]['overall']),scoreci(n[name]['overall']),wld(n[WT]['overall'])) for title,name,n,m in alltests])
    if 'identical_weights_combined' in s:
        r=s['identical_weights_combined']['score'];lines += [f"The evolutionary and PPO selections were byte-identical. Including the 96 independently run alternate-name replicates gives {wld(r)}, with a block interval of {scoreci(r)}. The primary test above retains its original sample size.",'']
    lines+=['### Starting seat and latency','',*table(['Trial','Seat and own/opponent added RTT (ms)','Candidate W–L–D','Plain W-tap W–L–D'],
        [(title,k,wld(v),wld(n[WT]['conditions'].get(k,{}))) for title,name,n,m in alltests for k,v in n[name]['conditions'].items()]),
        '### Prospective simulator transfer','',
        'The table uses 31/109 and 109/31 ms only for the original PPO comparison, all four link pairs for the opening and counterfactual studies, and equal links only in the equal-RTT study. Full condition counts are in assessment.json. The simulator reuses recorded input and server opportunities. Native repeats are independent games.', '',
        *table(['Panel','Candidate against frozen','Plain W-tap against frozen'],
            [(label,wld(m[name]['overall']),wld(m[WT]['overall'])) for label,name,n,m in alltests])]
    lines+=['### Clicks, opening and jump timing','',
        'CPS here is physical presses divided by total regulation seconds, including approach time and stalemates. The minimum interval uses individual consecutive presses from monotonic timestamps.', '']
    acts=[('PPO',primary,s['actions'],s['openings'],s['simulator_interventions'])]
    if 'opening_search' in data:
        o=data['opening_search'];acts.append(('Opening','sumo-frozen-'+o['selection']['primary'],o['actions'],o['openings'],o['simulator_interventions']))
    if 'counterfactual' in data:
        o=data['counterfactual'];acts.append(('Branches','sumo-frozen-branches',o['actions'],o['openings'],o['simulator_interventions']))
    lines+=table(['Native trial','Actor / frozen actual CPS','Minimum interval, actor / frozen','Plain W-tap / frozen actual CPS'],
        [(label,' / '.join(f'{x:.2f}' for x in act[name]['presses_per_regulation_second']),
          ' / '.join(f'{x:.3f} ms' for x in act[name]['minimum_interval_ms']),
          ' / '.join(f'{x:.2f}' for x in act[WT]['presses_per_regulation_second'])) for label,name,act,op,m in acts])
    for label,name,act,op,m in acts:
        r=op[name];w=op[WT];q=m[name]
        lines += [f"**{name}** scored first in {r['first_scorer']['True']['n']}/{r['n']} native bouts. Its record when scoring first was {wld(r['first_scorer']['True'])}. When the frozen champion scored first it was {wld(r['first_scorer']['False'])}. W-tap scored first in {w['first_scorer']['True']['n']}/{w['n']}. Before either local hurt cue, the frozen champion withheld its click gate on {r['target_gate_closed_in_reach']}/{r['target_pre_feedback_in_reach_samples']} in-reach observations against this candidate. These observations repeat within games.",'',
            f"In matched simulation, replacing only the candidate’s opening forward/strafe inputs with W-tap changed the original advantage by {signed(q['original_minus_wtap_opening']['mean'])} points (original minus replacement). Forcing only the frozen champion’s opening click gate open produced an original-minus-intervention difference of {signed(q['original_minus_target_open_gate']['mean'])} points.",'']
        if q['selected_jumps']:
            dx=q['mean_change_outward_blocks']
            lines += [f"Suppressing one grounded takeoff within 150 ms of an incoming fresh hit changed {q['jump_improved_outcome']} results in favor of jumping and {q['jump_worsened_outcome']} against it, out of {q['selected_jumps']} eligible simulations. Across {q['complete_displacement']} complete 250 ms windows, the jumping continuation moved {dx:+.3f} blocks outward relative to suppressing that jump." if dx is not None else f"{q['selected_jumps']} simulations supplied an eligible jump, with no complete displacement window.",'']
        else:lines += ['There were no qualifying near-hit takeoffs in this simulator panel.','']
    lines+=['## Generalist comparison','',
        f"Across the fixed four-opponent native field, sumo-vote-final minus the incumbent was **{signed(d['mean'])} percentage points** (95% interval **{iv(d)}**). The block-bootstrap sensitivity interval was {signed(d['bootstrap95'][0])} to {signed(d['bootstrap95'][1])} points. Directly against the incumbent it scored {wld(g['direct'])} (95% points interval {scoreci(g['direct'])}). W-tap scored {wld(g['wtap_vs_old_reversed'])} directly against the same incumbent in the reverse fixtures.",'',
        *table(['Native opponent','sumo-vote-final','Frozen best-sumo','Plain W-tap'],[(f,wld(r[NEW]),wld(r[OLD]),wld(r[WT])) for f,r in g['native_field'].items()]),
        *table(['Simulator opponent','sumo-vote-final','Frozen best-sumo','PPO specialist','Plain W-tap'],[(f,wld(r[NEW]),wld(r[OLD]),wld(r[primary]),wld(r[WT])) for f,r in g['simulated_by_opponent'].items()]),
        'The four field opponents are fixed. Neither tournament grades independent elite human play. A specialist score measures its fixed target. All physical press checks, weight hashes, per-seat results, invalid attempts and the intervention outcomes are in assessment.json.', '']
    (D/'README.md').write_text('\n'.join(lines))
if __name__=='__main__':render()
