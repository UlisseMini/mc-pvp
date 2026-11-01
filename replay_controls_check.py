#!/usr/bin/env python3
"""Check arbitrary speeds and scrubbing through a real native spectator client."""
import hashlib
import json
import re
import statistics

from lab import BUILD
from replay_check import capture


def run():
    script=('1500:/replay m0930|5000:/replay pause|5500:/replay seek 12.5|6500:/replay a|'
            '7500:/replay back|8500:/replay forward 1.25|9500:/replay seek -0.5|'
            '10500:/replay seek +2|11500:/replay back 0.05|12500:/replay forward|'
            '13500:/replay controls|14500:/replay speed 0.1|15000:/replay play|19000:/replay pause|'
            '19500:/replay seek 5|20000:/replay speed 0.75|20500:/replay play|24500:/replay pause|'
            '25000:/replay seek 5|25500:/replay speed 3|26000:/replay play|28000:/replay pause|'
            '28500:/replay speed 0.001|29000:/replay play|31000:/replay pause|'
            '31500:/replay back 9999|32500:/replay forward 9999|33500:/replay back 2|'
            '34500:/replay seek 5|35000:/replay speed 0|36000:/replay seek NaN|'
            '37000:/replay free|38000:/replay stop')
    out,digest,samples,client,log,returncode=capture(script,40000,'replay-controls')
    origin=statistics.median(e['time']-e['elapsed_ms'] for e in client) if client else 0
    sessions=[(s['time']-origin,r) for s in samples for r in s.get('replays',[]) if r['viewer']=='LabObserver']
    plain=re.sub(r'§[0-9a-fk-or]','',log,flags=re.I)
    checks={
        'native_client_clean_exit':returncode==0 and 'at lab.' not in log,
        'build_unchanged':hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()==digest,
    }
    # Inspect the middle of each paused interval, away from command delivery boundaries.
    for name,lo,expected in [('absolute_seek',5500,12500),('default_back_five',7500,7500),
                            ('fractional_forward',8500,8750),('signed_back',9500,8250),
                            ('signed_forward',10500,10250),('fifty_ms_back',11500,10200),
                            ('default_forward_five',12500,15200),('clamp_at_start',31500,0)]:
        rows=[r for t,r in sessions if lo+250<=t<=lo+850]
        checks[name]=bool(rows) and all(r['time_ms']==expected and r['paused'] for r in rows)
    paused=[r for t,r in sessions if 7800<=t<=14000]
    checks['scrubbing_preserves_follow_camera']=bool(paused) and all(r['camera']=='a' for r in paused)
    native_follow=[e for e in client if 7800<=e['elapsed_ms']<=14000]
    actor=paused[0]['actor_ids'][0] if paused else -1
    checks['native_camera_stays_on_actor_while_scrubbing']=bool(native_follow) and all(e['camera']==actor for e in native_follow)
    slopes={}
    for speed in (.1,.75,3,.001):
        rows=sorted({(t,r['time_ms']) for t,r in sessions if r['speed']==speed and not r['paused']})
        slope=(rows[-1][1]-rows[0][1])/(rows[-1][0]-rows[0][0]) if len(rows)>2 else None
        slopes[str(speed)]=slope
        checks[f'speed_{speed}_clock']=slope is not None and abs(slope-speed)<max(.001,speed*.06)
    checks['small_speed_label_visible']=any('0.001x' in e['action_bar'] for e in client)
    end=[r for t,r in sessions if 32800<=t<=33200]
    checks['clamp_at_end']=bool(end) and all(r['time_ms']==r['duration_ms'] and r['paused'] for r in end)
    back=[r for t,r in sessions if 33800<=t<=34200]
    checks['rewind_after_end']=bool(back) and all(r['time_ms']==r['duration_ms']-2000 and r['paused'] for r in back)
    invalid=[r for t,r in sessions if 36300<=t<=36700]
    checks['invalid_input_preserves_session']=bool(invalid) and all(r['time_ms']==5000 and r['speed']==.001 and r['paused'] for r in invalid)
    checks['invalid_input_explained']='Replay speed must be a positive number' in plain and 'Seek needs a finite number' in plain
    buttons={(b['action'],b['value']) for e in client for b in e.get('chat_buttons',[])}
    for action,value in [('RUN_COMMAND','back 5'),('RUN_COMMAND','back 1'),('RUN_COMMAND','forward 5'),
                         ('RUN_COMMAND','forward 1'),('RUN_COMMAND','pause'),('RUN_COMMAND','play'),
                         ('RUN_COMMAND','speed 0.1'),('RUN_COMMAND','speed 1'),
                         ('SUGGEST_COMMAND','speed '),('SUGGEST_COMMAND','seek ')]:
        checks['clickable_'+action.lower()+'_'+value.strip()]=(action,'/replay '+value) in buttons
    checks['controls_can_be_reopened']=plain.count('Replay controls')>=2
    checks['native_ghosts_removed_on_exit']=any(38500<=e['elapsed_ms']<=39500 and e['camera']==e['self'] and len(e['players'])==1 for e in client)
    checks['replay_session_cleaned']=not any(r['viewer']=='LabObserver' for r in samples[-1].get('replays',[]))
    report={'passed':all(checks.values()),'checks':checks,'measured_speed_multipliers':slopes,
            'agent_sha256':digest,'script':script,'match':'m0930'}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    (out/'server-samples.json').write_text(json.dumps(samples)+'\n')
    (out/'client-samples.json').write_text(json.dumps(client)+'\n')
    print(json.dumps({'directory':str(out),**report},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Replay control verification failed: '+str(out))


if __name__=='__main__':run()
