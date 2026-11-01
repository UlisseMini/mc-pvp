#!/usr/bin/env python3
"""Verify combo chat links and their seek behavior with a native spectator client."""
import hashlib
import itertools
import json
import re
import statistics

from lab import BUILD, RUNTIME
from replay_check import capture


def recorded_combos(match):
    result=json.loads((RUNTIME/'results'/f'{match}.json').read_text())
    events=[json.loads(line) for line in (RUNTIME/'replays'/f'{match}.jsonl').read_text().splitlines()]
    damage={e['hit_index']:e for e in events if e['type']=='melee_damage'}
    hits=[e for e in events if e['type']=='hit' and not e.get('rod') and e['time']>=result['start']
          and ((e['attacker'],e['target']) in [(result['a'],result['b']),(result['b'],result['a'])])
          and (result['mode']=='boxing' or damage.get(e.get('hit_index',e['hits_a']+e['hits_b']),{}).get('health_loss',1)>0)]
    combos=[]
    for attacker,run in itertools.groupby(hits,key=lambda e:e['attacker']):
        run=list(run)
        if len(run)<3:continue
        side='a' if attacker==result['a'] else 'b';other='b' if side=='a' else 'a'
        label=lambda s:result[s] if result['policy_'+s]=='human' else result['policy_'+s]
        combos.append({'attacker':label(side),'victim':label(other),'hits':len(run),
                       'first_ms':run[0]['time']-result['start'],'last_ms':run[-1]['time']-result['start']})
    return combos


def run():
    match='m0930';expected=recorded_combos(match)
    if len(expected)<2:raise RuntimeError('The replay fixture needs at least two combos')
    script=(f'1500:/replay {match}|5000:/replay pause|5500:/replay speed 0.25|6000:/replay a|'
            f'6500:/replay combo {match} 2|7800:/replay pause|8000:/replay combo {match} 1|'
            f'8000:/replay pause|9000:/replay combos|9500:/replay combo m0729 1|'
            '10000:/replay combos 999|11500:/replay stop')
    out,digest,samples,client,log,returncode=capture(script,13000,'replay-combos')
    plain=re.sub(r'§[0-9a-fk-or]','',log,flags=re.I)
    origin=statistics.median(e['time']-e['elapsed_ms'] for e in client) if client else 0
    sessions=[(s['time']-origin,r) for s in samples for r in s.get('replays',[]) if r['viewer']=='LabObserver']
    buttons={(b['action'],b['value']) for e in client for b in e.get('chat_buttons',[])}
    playing=[r for t,r in sessions if 6900<=t<=7500]
    paused=[r for t,r in sessions if 8300<=t<=11000]
    first_seek=max(0,expected[0]['first_ms']-750);second_seek=max(0,expected[1]['first_ms']-750)
    checks={
        'native_client_clean_exit':returncode==0 and 'at lab.' not in log,
        'build_unchanged':hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()==digest,
        'all_combo_rows_auto_shown':all(f"{c['attacker']} comboed {c['victim']}: {c['hits']} hits" in plain for c in expected),
        'each_combo_has_correct_click_command':all(('RUN_COMMAND',f'/replay combo {match} {i+1}') in buttons for i in range(len(expected))),
        'combo_list_can_be_reopened':plain.count(f'Combos 1–{len(expected)} of {len(expected)}')>=2,
        'combo_click_resumes_with_speed_and_camera':bool(playing) and all(not r['paused'] and r['speed']==.25 and r['camera']=='a' for r in playing),
        'second_combo_has_lead_in':bool(playing) and all(second_seek+40<=r['time_ms']<=second_seek+400 for r in playing),
        # The scripted client sends pause on its next 50ms tick after the jump.
        'first_combo_seek_and_stale_link_safety':bool(paused) and all(first_seek<=r['time_ms']<=first_seek+30 and r['paused'] and r['camera']=='a' for r in paused),
        'stale_match_link_explained':'That combo belongs to m0729' in plain,
        'invalid_page_explained':'Use /replay combos <page> between 1 and 1' in plain,
        'replay_session_cleaned':not any(r['viewer']=='LabObserver' for r in samples[-1].get('replays',[])),
    }
    report={'passed':all(checks.values()),'checks':checks,'match':match,'expected_combos':expected,'agent_sha256':digest,'script':script}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    (out/'server-samples.json').write_text(json.dumps(samples)+'\n')
    (out/'client-samples.json').write_text(json.dumps(client)+'\n')
    print(json.dumps({'directory':str(out),**report},indent=2),flush=True)
    if not report['passed']:raise RuntimeError('Native replay combo verification failed: '+str(out))


if __name__=='__main__':run()
