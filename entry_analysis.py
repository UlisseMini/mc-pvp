#!/usr/bin/env python3
"""Analyze a preregistered stationary-to-approaching input intervention."""
import json
from lab import ROOT
from science import client_events,server_events
from native_windows import overlap,sustained_combo


def analyze(game):
    records={s:client_events(game,s) for s in ('a','b')}
    moves=[e for e in records['b'] if e['type']=='science_move' and e['forward']>.01]
    if not moves:raise ValueError('No observed W onset')
    onset=moves[0]['time']
    if abs(onset-game['start']-8000)>150:raise ValueError('W onset differs from the declared8s intervention')
    probes={s:[e for e in records[s] if e['type']=='reach_probe'] for s in records}
    raw=server_events(game['id']);hits=[e for e in raw if e['type']=='hit']
    fresh=[e['time'] for e in hits if e['attacker']==game['a'] and e['target_state']['hurtResistantTime']==20]
    returns=[e['time'] for e in hits if e['attacker']==game['b']]
    result={'match':game['id'],'rtt':[game['ping_a'],game['ping_b']],
            'defender':game['policy_b'],'native_w_onset_after_start_ms':onset-game['start'],'phases':{}}
    for phase,start,end in [('stationary',game['start'],onset),('approaching',onset,game['finish'])]:
        windows=overlap(probes['a'],probes['b'],start,end,include_windows=True)
        result['phases'][phase]={
            'duration_ms':end-start,'paired_coverage_fraction':windows['paired_coverage_fraction'],
            'defender_could_hit_ms':windows['b']['attacker_can_hit_ms'],
            'score':[sum(start<=e['time']<end and e['attacker']==game[s] for e in hits) for s in ('a','b')],
            'denial_sequence':sustained_combo(windows['a']['defender_denial_windows'],fresh,returns,max_gap_ms=None)}
    first=next((e['time'] for e in probes['b'] if e['time']>=onset and e['in_range']),None)
    result['first_observed_return_opportunity_after_w_ms']=first-onset if first is not None else None
    full=overlap(probes['a'],probes['b'],game['start'],game['finish'],include_windows=True)
    window=next((w for w in full['a']['defender_denial_windows'] if w['start']<=onset<w['end']),None)
    pre_contacts=[t for t in fresh if window and window['start']<=t<onset]
    result['acquired_at_switch']={'fresh_contacts_in_current_denial_window':len(pre_contacts),
        'sampled_denial_before_switch_ms':onset-window['start'] if window else 0,
        'no_accepted_return_in_window':bool(window) and not any(window['start']<=t<onset for t in returns),
        'established_three_contact_denial':len(pre_contacts)>=3 and not any(window['start']<=t<onset for t in returns)}
    return result


if __name__=='__main__':
    source=ROOT/'research/native-entry-maintenance.json';document=json.loads(source.read_text())
    if document['stage']!='complete':raise ValueError('Wait for the predeclared suite to complete')
    rows=[analyze(row['game']) for row in document['results'] if row['game']['policy_b'].startswith('science-wake-')]
    output={'scope':'Finite native input intervention. Opportunities are sampled at approximately50ms, not continuous-time certificates. Score is accepted sword attacks, including upgrades.', 'results':rows}
    path=ROOT/'research/entry-maintenance-analysis.json';path.write_text(json.dumps(output,indent=2)+'\n')
    lines=['# Acquisition versus maintenance','',output['scope'],'',
           'B stands for8s, then holds W+Ctrl for the rest of the30s trial. A is the same W-tap policy throughout. No position or velocity is forced after setup.','',
           '| Match | Added RTT A/B | B clicks? | Score before W | Score after W | Fresh hits in denial window at switch | B reach time before / after | First observed return opportunity after W |',
           '|---|---|---|---|---|---|---|---|']
    for r in rows:
        before=r['phases']['stationary'];after=r['phases']['approaching'];delay=r['first_observed_return_opportunity_after_w_ms']
        lines.append(f'| {r["match"]} | {r["rtt"][0]:g}/{r["rtt"][1]:g} | {"yes" if r["defender"].endswith("click") else "no"} | {before["score"][0]}–{before["score"][1]} | {after["score"][0]}–{after["score"][1]} | {r["acquired_at_switch"]["fresh_contacts_in_current_denial_window"]} | {before["defender_could_hit_ms"]/1000:.3f}s / {after["defender_could_hit_ms"]/1000:.3f}s | {str(delay)+"ms" if delay is not None else "none observed"} |')
    incomplete=[r['match'] for r in rows if not r['acquired_at_switch']['established_three_contact_denial']]
    lines+=['','An established denial sequence at the switch requires at least three fresh contacts in the current sampled denial window and no accepted return. These rows fail that acquisition criterion: '+(', '.join(incomplete) or 'none')+'. They cannot demonstrate maintenance failure from an established lock. Each condition has one trial; this is a causal diagnostic, not a population estimate or impossibility proof.']
    path.with_suffix('.md').write_text('\n'.join(lines)+'\n');print('\n'.join(lines))
