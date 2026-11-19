"""Audit the eligible sprint bonus and released-forward movement of each participant."""
import argparse, json
import captures
from pathlib import Path
from run import ROOT

def read(path):
    return json.loads(path.read_text())

def fight(mid,setup):
    g=read(ROOT/f'runtime/results/{mid}.json')
    counters={s:dict(fresh=0,bonus=0,followup=0,followup_bonus=0,travel=0,coast=0)
              for s in ('a','b')}
    by_name={g[s]:s for s in counters}
    with (ROOT/f'runtime/replays/{mid}.jsonl').open() as stream:
        for line in stream:
            e=json.loads(line)
            if e['type']!='science_server_attack' or e['score_before']==e['score_after']:continue
            if e['target_before']['hurtResistantTime']>g['combat_profile']['fresh_hit_ticks']:continue
            cell=counters[by_name[e['attacker']]]
            bonus=int(e['attacker_before']['sprint'])
            if cell['fresh']>0:
                cell['followup']+=1
                cell['followup_bonus']+=bonus
            cell['fresh']+=1;cell['bonus']+=bonus
    for s,cell in counters.items():
        path=next((ROOT/f'runtime/pov/{mid}').glob(f'{s}-client-*-events.jsonl.gz'))
        for e in captures.events(path):
            if e['type']=='science_move' and g['start']<=e['time']<=g['finish']:
                cell['travel']+=1;cell['coast']+=e['forward']==0
    side=setup['side']
    return dict(mid=mid,arm=setup['arm'],opponent=setup['opponent'],rtt=setup['rtt'],side=side,
                ours=counters[side],theirs=counters['b' if side=='a' else 'a'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('manifest',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();m=read(a.manifest)
    if m['plan'].keys()!=m['matches'].keys():p.error('Incomplete cohort')
    rows=[fight(mid,m['plan'][req]) for req,mid in m['matches'].items()
          if m['plan'][req]['mode']=='sumo' and m['plan'][req]['profile']=='mmc']
    with a.output.open('x') as f:json.dump(rows,f,separators=(',',':'))
