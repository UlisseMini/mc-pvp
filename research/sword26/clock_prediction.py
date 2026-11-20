"""Compare fixed and varying virtual clocks from the opening observed by each ordinary Java client."""
import argparse
import csv
import io
import json
from pathlib import Path
import subprocess
import tempfile

from first_ko import correction
from season import hash_policy
from setup import SRC, compile_sim, jdk


def compare(run,dest,poll_cadence=False):
    meta=json.loads((run/'metadata.json').read_text())
    if meta['clock'] is not None:raise ValueError('Expected an ordinary Java session')
    paths={hash_policy(p):p for p in SRC.rglob('*.net')}
    arms={'wtap':'wtap','learned':paths[meta['weights_sha256']]}
    arms.update({f'learned-{i}':paths[p['sha256']] for i,p in enumerate(meta['extra_weights'],1)})
    rounds=json.loads((run/'results.json').read_text())
    for m in rounds:m.update(correction(run,m["id"],m["seat"],m["hp"],m["opponent_hp"],m["ticks"]))
    with tempfile.TemporaryDirectory() as td:
        spec=Path(td)/'rounds.tsv';rows=[]
        for m in rounds:
            rival=paths[meta['opponent_hashes'][m['rival']]] if m['rival'] in meta['opponent_hashes'] else m['rival']
            lineup=[rival,rival];lineup[m['seat']]=arms[m['policy']]
            rows.append('\t'.join(map(str,[run,m['id'],m['rtt'],m['seat'],m['angle'],*lineup])))
        spec.write_text('\n'.join(rows)+'\n')
        output=subprocess.check_output(['nice','-n','15',jdk()/'java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx384m','-Dsword.pollForecast='+str(poll_cadence).lower(),
                                       '-Dsword.aimProfile='+meta.get('aim_profile','tournament'),'-cp',compile_sim(),'sword.ClockPrediction',spec],text=True)
    results=list(csv.DictReader(io.StringIO(output)))
    for row,m in zip(results,rounds,strict=True):
        if row['id']!=m['id']:raise ValueError('Unexpected round ordering')
        row.update(rtt=m['rtt'],seat=m['seat'],opening=m['pair'],policy=m['policy'],opponent=m['rival'],
                   java_score=1. if m['opponent_hp']==0 else 0. if m['hp']==0 else .5)
    with dest.open('w') as f:
        w=csv.DictWriter(f,fieldnames=results[0],lineterminator='\n');w.writeheader();w.writerows(results)
    return results


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('run',type=Path);p.add_argument('csv',type=Path)
    p.add_argument('--poll-cadence',action='store_true',help='Also repeat the first 20 native input and server intervals.')
    a=p.parse_args();compare(a.run,a.csv,a.poll_cadence)
