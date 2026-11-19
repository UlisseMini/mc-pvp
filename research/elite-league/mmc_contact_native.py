"""Compare actual click-selection and contact with the prospective MMC transfer predictions."""
import argparse
from collections import defaultdict
import gzip
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from adaptation import dump, freeze, sha
from audit import captures
from mmc_contact_gate import SPLIT
from mmc_league import WORK, EVIDENCE, contest_plan, native_name
from mmc_trade_fit import ACTOR, pool
from results import outcome
from run import ROOT, class_path, compile_model, java
from tournament import roster

DEST=EVIDENCE/'contact-timing'
STAGE=EVIDENCE/'contact-confirm'
PROTOCOL=DEST/'native-protocol.json'


def read(p):return json.loads(p.read_text())


def register():
    spec=read(SPLIT/'confirmation.json')
    original=contest_plan('contact-confirm',spec['arms'],spec['opponents'],spec['repeats'])
    dump(PROTOCOL,dict(spec=sha(SPLIT/'confirmation.json'),candidate=read(SPLIT/'selection.json')['contender'],
         fixtures={q:[t['arm'],t['opponent'],t['rtt'],t['side'],t['rep']] for q,t in original.items()},
         rules='Every scheduled seat, RTT and repeat. Resolve referee-invalid replacements through the normal scored bundle. Re-evaluate the frozen PPO and selector from the same own observations, and feed both the movement actually applied at each head. Record the first extra click decision, its observed center distance, timing relative to first fresh hit, opening contact and ring-out. Include all never-triggered bouts. Compare ordinary W-tap in both environments. Preserve native and model samples separately; the bouts are not identical state forks.'))


def model():
    protocol=read(PROTOCOL)
    spec=read(SPLIT/'confirmation.json')
    if sha(SPLIT/'confirmation.json')!=protocol['spec']:raise ValueError('Changed prospective controls')
    arm=protocol['candidate'];field=pool();cast={n:field[n] for n in spec['opponents']}
    clocks=sorted((WORK/'mmc13-contact-confirm-clocks').glob('*.txt.gz'))
    if len(clocks)!=8:raise ValueError('Incomplete clock panel')
    with tempfile.TemporaryDirectory(dir=WORK) as tmp:
        tmp=Path(tmp);roster(tmp/'arms',{arm:SPLIT/(arm+'.net'),ACTOR:field[ACTOR]});roster(tmp/'foes',cast)
        compiled=java('ContactTimingStudy',tmp/'arms',tmp/'foes','--observe',*clocks)
        with (tmp/'model').open('w') as out:subprocess.run(compiled,stdout=out,check=True)
        rows=list(map(json.loads,(tmp/'model').read_text().splitlines()))
    def key(r):return tuple(r[k] for k in ('opponent','clock','rtt','side'))
    with gzip.open(STAGE/'model.jsonl.gz','rt') as f:expected={(r['arm'],key(r)):r for r in map(json.loads,f) if r['arm'] in (arm,'wtap')}
    if len(rows)!=len(clocks)*4*len(cast) or len(expected)!=2*len(rows) or len(set(map(key,rows)))!=len(rows):raise ValueError('Lost model fixture')
    for r in rows:
        old=expected[arm,key(r)]
        if [old[k] for k in ('score','end_ms','fresh','incoming')]!=[r['original'][k] for k in ('score','end','fresh','incoming')] or expected['wtap',key(r)]['score']!=r['plain_wtap']:
            raise ValueError('Different registered prospective model outcome')
    freeze(DEST/'model-transfer.json.gz',gzip.compress(json.dumps(rows,separators=(',',':')).encode(),mtime=0))


def physical(game,arm):
    mid=game['mid'];side=game['side']
    record=read(ROOT/'runtime/results'/(mid+'.json'))
    g=dict(mid=mid,arm=game['arm'],opponent=game['opponent'],rtt=game['rtt'],side=side,rep=game['rep'],
           score=1 if game['win'] else .5 if game['draw'] else 0)
    with (ROOT/'runtime/replays'/(mid+'.jsonl')).open() as f:
        hits=[e for e in (json.loads(l) for l in f if '"hit"' in l) if e['type']=='hit' and not e['rod'] and not e['damage_upgrade'] and record['start']<=e['time']<=record['finish']]
    g['first_fresh']=None if not hits else [hits[0]['time']-record['start'],hits[0]['attacker']==record[side]]
    if game['arm']!=arm:return g
    path,=(ROOT/'runtime/pov'/mid).glob(side+'-client-*-events.jsonl.gz')
    heads=[e for e in captures.events(path,{'duel_network'}) if e.get('match')==mid]
    if not heads:raise ValueError('Missing candidate input heads')
    lines=[]
    for h in heads:
        v=h['observation'];lines.append(' '.join(map(str,[*v[:9],int(v[9]),int(v[10]),*v[11:],h['proposed'],h['action']])))
    cmd=['java','-Xms12m','-Xmx48m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-cp',str(class_path()),
         'ContactTimingStudy','--native-heads',str(SPLIT/(arm+'.net')),str(pool()[ACTOR])]
    values=subprocess.run(cmd,input='\n'.join(lines)+'\n',text=True,capture_output=True,check=True).stdout.splitlines()
    extra=[e for e,s in zip(heads,values,strict=True) if s=='1' and e['released'] and record['start']<=e['time']<=record['finish']]
    g['extra_heads']=len(extra);g['first_extra']=None
    if extra:
        e=extra[0];v=e['observation'];g['first_extra']=dict(time=e['time']-record['start'],gap=math.hypot(v[0]-v[3],v[2]-v[5]),action=e['action'],own_hurt_age=v[11],target_hurt_age=v[12])
    return g


def native():
    protocol=read(PROTOCOL);arm=protocol['candidate']
    with gzip.open(STAGE/'native.json.gz','rt') as f:bundle=json.load(f)
    if bundle['summary']['binding_disagreements']:raise ValueError('Native control audit failed')
    validated=set();rows=[]
    for game in bundle['matches']:
        q=read(ROOT/'runtime/results'/(game['mid']+'.json'))['request'].split('-view-retry')[0].split('-input-retry')[0]
        if q in validated or [game[k] for k in ('arm','opponent','rtt','side','rep')]!=protocol['fixtures'][q]:raise ValueError('Fixture differs from original')
        validated.add(q);rows.append(physical(game,arm))
    if validated!=set(protocol['fixtures']):raise ValueError('Incomplete native transfer')
    with gzip.open(DEST/'model-transfer.json.gz','rt') as f:models=json.load(f)
    cells=defaultdict(list)
    def add(source,opponent,who,rtt,side,score):
        for split in ('all',f'{rtt}/{side}'):cells[source,opponent,who,split].append(score)
    for g in rows:add('native',g['opponent'],g['arm'],g['rtt'],g['side'],g['score'])
    for r in models:
        for who,score in ((arm,r['original']['score']),(native_name('wtap'),r['plain_wtap'])):
            add('model',native_name(r['opponent']),who,r['rtt'],r['side'],score)
    dump(DEST/'native-transfer.json',dict(protocol=sha(PROTOCOL),native_bundle=sha(STAGE/'native.json.gz'),
         model_file=sha(DEST/'model-transfer.json.gz'),records=rows,score={','.join(k):outcome(v) for k,v in cells.items()}))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=['register','wait','model','native']);stage=p.parse_args().stage
    if stage=='register':register()
    elif stage=='wait':
        deadline=read(WORK/'admission-deadline.json')['unix_time']
        for action,required in [('model','model.jsonl.gz'),('native','native.json.gz')]:
            while not (STAGE/required).exists():
                if time.time()>deadline+180:sys.exit(0)
                time.sleep(10)
            subprocess.run([sys.executable,__file__,action],check=True)
    else:
        from cpu_limit import supervise_script
        supervise_script()
        compile_model()
        (model if stage=='model' else native)()
