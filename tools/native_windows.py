"""Time-weighted overlap of each real client's independently measured reach."""

def overlap(a,b,start,finish,max_age_ms=150,include_windows=False):
    events=sorted([(e['time'],0,bool(e['in_range'])) for e in a]+[(e['time'],1,bool(e['in_range'])) for e in b])
    latest=[None,None];values=[False,False];durations={'a_only':0,'b_only':0,'both':0,'neither':0}
    longest={'a_only':0,'b_only':0};active=None;run=0;previous_end=None
    denied=[[],[]];one_sided=[[],[]]
    for index,(time,side,value) in enumerate(events):
        latest[side]=time;values[side]=value
        if any(t is None for t in latest):continue
        next_time=events[index+1][0] if index+1<len(events) else finish
        left=max(start,time);right=min(finish,next_time,latest[0]+max_age_ms,latest[1]+max_age_ms)
        if right<=left:continue
        key='both' if all(values) else 'a_only' if values[0] else 'b_only' if values[1] else 'neither'
        dt=right-left;durations[key]+=dt
        for attacker in (0,1):
            if values[1-attacker]:continue
            if values[attacker]:
                strict=one_sided[attacker]
                if strict and strict[-1]['end']==left:strict[-1]['end']=right
                else:strict.append({'start':left,'end':right})
            windows=denied[attacker]
            if windows and windows[-1]['end']==left:
                windows[-1]['end']=right;windows[-1]['attacker_can_hit_ms']+=dt if values[attacker]else 0
            else:windows.append({'start':left,'end':right,'attacker_can_hit_ms':dt if values[attacker]else 0})
        run=run+dt if active==key and previous_end==left else dt
        if key in longest:longest[key]=max(longest[key],run)
        active=key;previous_end=right
    paired=sum(durations.values())
    result={'paired_time_ms':paired,'match_duration_ms':finish-start,
            'paired_coverage_fraction':paired/(finish-start) if finish>start else None,'durations_ms':durations,
            'clock':'shared host wall-clock milliseconds',
            'sampling':'native raycast probes; step interpolation between samples, maximum sample age 150 ms'}
    for side in ('a','b'):
        one=durations[side+'_only'];denominator=one+durations['both']
        result[side]={'attacker_can_hit_ms':denominator,'attacker_can_hit_defender_cannot_ms':one,
                      'one_sided_fraction_given_attacker_can_hit':one/denominator if denominator else None,
                      'longest_one_sided_window_ms':longest[side+'_only']}
        windows=denied[0 if side=='a'else 1]
        result[side]['longest_defender_denied_ms']=max((w['end']-w['start']for w in windows),default=0)
        if include_windows:
            result[side]['defender_denial_windows']=windows
            result[side]['one_sided_windows']=one_sided[0 if side=='a'else 1]
    return result


def sustained_combo(windows,contacts,returns,max_gap_ms=1000):
    """Fresh-contact chain during measured reach denial; None removes the gap cutoff."""
    best={'duration_ms':0,'fresh_contacts':0,'max_contact_gap_ms':None}
    for window in windows:
        boundaries=[window['start']]+sorted(t for t in returns if window['start']<t<window['end'])+[window['end']]
        for left,right in zip(boundaries,boundaries[1:]):
            chain=[]
            for t in sorted(contacts):
                if not left<=t<right:continue
                if chain and max_gap_ms is not None and t-chain[-1]>max_gap_ms:chain=[]
                chain.append(t)
                if len(chain)>=3 and t-chain[0]>best['duration_ms']:
                    best={'duration_ms':t-chain[0],'fresh_contacts':len(chain),'start':chain[0],'end':t,
                          'max_contact_gap_ms':max(b-a for a,b in zip(chain,chain[1:]))}
    gap_rule=f'contact gaps at most{max_gap_ms}ms' if max_gap_ms is not None else 'no contact-gap cutoff; inspect fresh_contacts and max_contact_gap_ms'
    best['definition']=f'At least three fresh contacts inside continuous native defender-reach denial; no accepted return attack, {gap_rule}. Both-out-of-range intervals may remain inside the lock.'
    return best
