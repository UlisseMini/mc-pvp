"""Time-weighted overlap of each real client's independently measured reach."""

def overlap(a,b,start,finish,max_age_ms=150):
    events=sorted([(e['time'],0,bool(e['in_range'])) for e in a]+[(e['time'],1,bool(e['in_range'])) for e in b])
    latest=[None,None];values=[False,False];durations={'a_only':0,'b_only':0,'both':0,'neither':0}
    longest={'a_only':0,'b_only':0};active=None;run=0;previous_end=None
    for index,(time,side,value) in enumerate(events):
        latest[side]=time;values[side]=value
        if any(t is None for t in latest):continue
        next_time=events[index+1][0] if index+1<len(events) else finish
        left=max(start,time);right=min(finish,next_time,latest[0]+max_age_ms,latest[1]+max_age_ms)
        if right<=left:continue
        key='both' if all(values) else 'a_only' if values[0] else 'b_only' if values[1] else 'neither'
        dt=right-left;durations[key]+=dt
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
    return result
