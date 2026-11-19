"""Count scheduled MMC fixtures by immutable request, including untouched and conditional stages."""
import argparse
from collections import Counter
import gzip
import json
import os
from pathlib import Path
import tempfile
import sys
import time

from boxing_breadth import FOES as BOXING_FOES, trial_plan as boxing_plan
from history_cup import mirror_plan
from mmc_breadth import paired
from mmc_league import WORK, EVIDENCE, FROZEN, contest_plan, groups
from run import HERE, ROOT


def read(path):
    return json.loads(path.read_text())


def schedules():
    plans = {}
    for tag in ('opening','confirmation','final','transfer'):
        spec = read(EVIDENCE/tag/'schedule.json')
        plans['mmc13-'+tag] = contest_plan(tag,spec['arms'],spec['opponents'],spec['repeats'],spec['profile'])
    for tag, path in [('actor','actor-confirmation.json'),('pairs','pairs-confirmation.json'),
                      ('trade-confirm','mmc13-trades/confirmation.json'),('phase-confirm','mmc13-phase-trades/confirmation.json'),
                      ('click-confirm','mmc13-click-bias/confirmation.json'),('contact-confirm','mmc13-contact-gate/confirmation.json'),
                      ('edge-confirm','mmc13-edge-fit/confirmation.json')]:
        spec_path = FROZEN/path
        if spec_path.exists():
            spec = read(spec_path)
            plans['mmc13-'+tag] = contest_plan(tag,spec['arms'],spec.get('opponents',spec['repeats']),spec['repeats'])
    for tag in ('confirm','box','final','actor','pairs','trade-confirm','phase-confirm','click-confirm','contact-confirm','edge-confirm'):
        if tag in ('contact-confirm','edge-confirm') and 'mmc13-'+tag not in plans:continue
        name='mmc13-'+tag
        plans[name+'-clocks'] = mirror_plan(name)
    plans['box-breadth-native'] = boxing_plan()
    for name in ['m13floor0','m13floor1','m13floor2','box-student-native','boxing100-native']:
        plans[name] = read(WORK/(name+'.json'))['plan']
    archive = read(EVIDENCE/'archive/roster.json')
    title = EVIDENCE/'final/title.json'
    final = read(title)['winner'] if title.exists() else None
    extra = final and final not in archive['arms']
    for round_no in (0,1):
        for offset in range(0,len(archive['order']),8):
            opponents = archive['order'][offset:offset+8]
            index=f'{round_no+1}-{offset//8+1:02d}'
            name='mmc13-slice-'+index
            plans[name]=paired(round_no,name,archive['arms'],archive['opponents'],opponents,offset)
            if extra:
                name='mmc13-archive-final-'+index
                plans[name]=paired(round_no,name,[final],archive['opponents'],opponents,offset)
    folders=[FROZEN,EVIDENCE,HERE/'evidence/boxing100',HERE/'evidence/boxing-distilled',HERE/'evidence/boxing-breadth']
    transfer=read(EVIDENCE/'final-profile/protocol.json')['extra_fixtures_by_possible_winner']
    if final:
        from mmc_final_profile import NAME,native_schedule
        _,additional=native_schedule(final)
        if len(additional)!=transfer[final]:raise ValueError('Changed alternative-preset conditional schedule')
        plans[NAME]=additional
    model_stages=sorted(str(p.relative_to(HERE)) for folder in folders for p in folder.rglob('*.jsonl.gz'))
    return plans,dict(alternative_preset_new_fixtures_by_possible_champion=transfer,archive_final_winner=final,winner_archive_comparison_cells=4*len(archive['order']),
                      extra_physical_fixtures=(4*len(archive['order']) if extra else 0) if final else None,
                      extra_physical_if_winner_new=4*len(archive['order']),completed_model_files=model_stages)


def model_progress(plans):
    # Count complete recorded evaluation bouts. Training sweeps, physics forks and
    # prospective native fixtures have different denominators and are kept apart.
    expected={}
    for tag in ('opening','confirmation','final','transfer','actor','pairs','trade-confirm','phase-confirm','click-confirm','contact-confirm','edge-confirm'):
        if 'mmc13-'+tag not in plans:continue
        tasks=plans['mmc13-'+tag].values()
        pairs={(r['arm'],r['opponent']) for r in tasks}
        expected[EVIDENCE/tag/'model.jsonl.gz']=32*len(pairs)
    portable={n for field in groups().values() for n in field}
    final=read(EVIDENCE/'final/schedule.json')
    expected[EVIDENCE/'archive/model-development.jsonl.gz']=32*len(portable)*len(read(FROZEN/'confirmation.json')['arms'])
    expected[EVIDENCE/'final-profile/model.jsonl.gz']=32*len(final['opponents'])*len(final['arms'])
    expected[EVIDENCE/'archive-final/model.jsonl.gz']=32*len(portable|set(final['opponents']))*len(final['arms'])
    expected[HERE/'evidence/boxing100/boxing.jsonl.gz']=8*4*2*4
    expected[HERE/'evidence/boxing-distilled/boxing.jsonl.gz']=8*2*4
    expected[HERE/'evidence/boxing-breadth/prospective.jsonl.gz']=8*len(BOXING_FOES)*4
    counts={}
    for p,n in expected.items():
        completed=0
        if p.is_file():
            with gzip.open(p,'rb') as f:completed=sum(1 for _ in f)
            if completed!=n:raise ValueError(f'{p} has {completed} evaluated rows, expected {n}')
        counts[str(p.relative_to(HERE))]=dict(scheduled=n,completed=completed,pending=n-completed)
    return dict(totals={k:sum(v[k] for v in counts.values()) for k in ('scheduled','completed','pending')},files=counts)


def current_status():
    status=read(ROOT/'runtime/status.json')
    if abs(time.time()-status['time']/1000)>8:
        raise RuntimeError('Main server status is stale')
    return status


def snapshot(as_of=None):
    captured=time.time()
    as_of=captured if as_of is None else as_of
    status=current_status()
    active={row['request']:row['id'] for row in status['matches'] if row.get('request')}
    plans, conditional=schedules()
    scheduled_retries=set()
    persisted={}
    for p in sorted(WORK.glob('*.json')):
        if '.partial.' in p.name:
            name=p.name.removesuffix('.partial.json')
        else:
            name=p.stem
        if name.endswith(('-eligible','-verified','-input-audit','-preview')) or name.endswith('.build'):
            continue
        j=read(p)
        if not isinstance(j,dict) or not {'plan','matches'}<=j.keys():continue
        if name not in plans:
            if not any(k in name for k in ('-binding-retry','-input-retry','-view-retry')):
                raise ValueError(f'Unregistered native cohort {name}')
            scheduled_retries.add(name)
            plans[name]=j['plan']
        if j['plan']!=plans[name]:
            raise ValueError(f'Settings differ in {p}')
        for req,mid in j['matches'].items():
            if req in persisted and persisted[req]!=mid:
                raise ValueError(f'Different match in partial and primary manifests for {req}')
            persisted[req]=mid
    cells={}
    for name,plan in plans.items():
        for req in plan:
            if req in cells:raise ValueError(f'Reused request in two registered cohorts: {req}')
            cells[req]=name
    referee={}
    for p in (ROOT/'runtime/results').glob('m*.json'):
        g=read(p)
        q=g.get('request')
        if q not in cells:continue
        if q in referee:raise ValueError(f'Duplicate native result for {q}')
        referee[q]=dict(mid=g['id'],reason=g['reason'],start=g['start'],finish=g['finish'])
    for q,mid in persisted.items():
        if referee.get(q,{}).get('mid')!=mid:raise ValueError(f'No referee result for recorded match {q}')
    settled={q:r for q,r in referee.items() if r['finish']<=as_of*1000}
    for q,r in referee.items():
        if r['start']<=as_of*1000<r['finish']:active[q]=r['mid']
    invalid={}
    for path in sorted((HERE/'evidence/boxing100').glob('*-movement.json')):
        for q,row in read(path).items():
            if not row['valid']:invalid[q]='physical inputs' if row.get('sides') else row['reason']
    for path in WORK.glob('mmc13-*-scores/movement/m*.json'):
        audit=read(path)
        if not audit['valid']:invalid[audit['request']]=audit.get('reason','physical inputs')
    for q,r in settled.items():
        if r['reason']=='invalid_client_view':invalid[q]=r['reason']
    totals=Counter()
    stages={}
    for name,plan in plans.items():
        counts=Counter()
        for q in plan:
            counts['scheduled']+=1
            if q in settled:
                counts['finished']+=1
                if q in invalid:counts['known_invalid']+=1
            else:
                counts['pending']+=1
                counts['active' if q in active else 'unstarted']+=1
        if counts['finished']+counts['pending']!=len(plan):raise AssertionError(name)
        stages[name]=dict(counts)
        totals.update(counts)
    active_ours={q:m for q,m in active.items() if q in cells and q not in settled}
    others={q:m for q,m in active.items() if q not in cells}
    return dict(as_of=as_of,captured=captured,server_sample=status['time']/1000,finished_scan=time.time(),
        deadline=read(WORK/'admission-deadline.json'),totals=dict(totals),stages=stages,
        scheduled_model_evaluations=model_progress(plans),conditional=conditional,retry_cohorts=sorted(scheduled_retries),known_invalid=invalid,
        active_ours=active_ours,other_active=others,raw_native=settled,
        pending_ids={name:[q for q in plan if q not in settled] for name,plan in plans.items() if plan.keys()-settled.keys()})


def save(path, data):
    packed=gzip.compress(json.dumps(data,separators=(',',':')).encode(),mtime=0)
    if path.exists():return
    with tempfile.NamedTemporaryFile(dir=path.parent) as temp:
        temp.write(packed);temp.flush()
        os.link(temp.name,path)


def watch(close_now=False):
    deadline=read(WORK/'admission-deadline.json')['unix_time']
    while time.time()<deadline:
        time.sleep(min(30,max(0,deadline-time.time())))
    if not close_now:
        # Load the latest registered experiments when the clock reaches admission close.
        os.execv(sys.executable,[sys.executable,__file__,'--capture-cutoff'])
    dest=EVIDENCE/'morning'
    dest.mkdir(exist_ok=True)
    boundary=dest/'admission-close.json.gz'
    if not boundary.exists():save(boundary,snapshot(deadline))
    with gzip.open(boundary,'rt') as f:initial=json.load(f)
    outstanding=set(initial['active_ours'])
    # Capture duels queued just before closing even if their referee countdown appeared a moment later.
    pending={q for group in initial['pending_ids'].values() for q in group}
    quiet_since=None
    while True:
        status=current_status()
        live={m['request'] for m in status['matches']} & pending
        outstanding.update(live)
        if live:quiet_since=None
        elif quiet_since is None:quiet_since=time.monotonic()
        elif time.monotonic()-quiet_since>12:
            last=snapshot()
            if outstanding<=last['raw_native'].keys() and not last['active_ours']:
                save(dest/'drained.json.gz',last)
                print(last['totals'],flush=True)
                return
        time.sleep(2)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--watch-cutoff',action='store_true')
    parser.add_argument('--capture-cutoff',action='store_true')
    parser.add_argument('--save',type=Path)
    args=parser.parse_args()
    if args.watch_cutoff or args.capture_cutoff:watch(args.capture_cutoff)
    else:
        progress=snapshot()
        if args.save:save(args.save,progress)
        print(progress['totals'],progress['active_ours'])
