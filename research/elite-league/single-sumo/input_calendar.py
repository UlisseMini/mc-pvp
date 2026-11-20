"""Vary recorded click polls and movement sends independently on the exact original donor matches."""
import fcntl
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
from statistics import mean

from dedicated import _train_target, budgets, extension
from id_evaluate import make_panel
from id_native import native_name
from learner import CLOCKS, HERE, INCUMBENT, ROOT, Worker, atomic, freeze, write_json
from league_tournament import stats
from long_specialist import RUN
from native_league import WT, alias, fair_cohort, fixtures

PARENT = RUN/'incumbent'
OUT = PARENT/'recorded-input-times'
BEGIN, END = 32768, 65536
MODES = ('original','polls','movement','both')
POLL = {'POLL_FRAME','POLL_NATIVE'}


def read(path):
    return json.loads(path.read_bytes())


def register():
    state = PARENT/'milestones'/f'n{BEGIN}.pt'
    write_json(OUT/'protocol.json',dict(start=BEGIN,stop=END,cap=7,opponent=INCUMBENT,start_adam=hashlib.sha256(state.read_bytes()).hexdigest(),
        diagnostic='Score the frozen 32k actor and plain W-tap on four reconstructions of the same eight development recordings. Compare click poll opportunities and movement packet send times independently.',
        fitting='One changes only the timestamp representation of its 16 existing training calendars. The control continues the identical 32k optimizer, opponent, matches, RTTs, actors, RNG seeds, PPO updates and default MMC floor with the original clocks.',
        test='Freeze the 65,536-fight weights. Compare both continuations, the original 32k weights and W-tap against frozen best-sumo and W-tap. Use new 16 chronologically spaced donors, each in both seats at four RTT pairs and eight action-seed replications.',
        native='64 independently played main-Minecraft bouts across the four actors, two opponents, two seats and four RTT pairs. Audit both client controls and physical seven-CPS caps.',
        selection='Fixed budget, no model selection on the new timers, named validation floors or sealed maps.'),fixed=True)
    path=OUT/'assay.json'
    if not path.exists():
        latest=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())
        write_json(path,dict(first_future_id=latest+8,calendar_count=16,minimum_gap_ms=60000,
                   rule='bot-vs-bot sumo, first eligible completion after minimum gap, stored in ascending match ID'),fixed=True)
    if not (OUT/'optimizer.pt').exists():atomic(OUT/'optimizer.pt',state.read_bytes())


def sections(text):
    groups={}
    for line in text.splitlines():
        words=line.split()
        if len(words)==3:
            header=line
            if header in groups:raise ValueError('Duplicate timing stream')
            groups[header]=[]
        else:groups[header].append((int(words[0]),words[1]))
    return groups


def clock_path(source,mode):
    return source if mode=='original' else str((OUT/'timing'/mode/Path(source).name).relative_to(ROOT))


def extract():
    from calendars_holdout import tape
    from calendars import timeline
    write_json(OUT/'wire-mapping.json',dict(reason='The simulator serializes the current movement at its send opportunity. Some old Netty wire writes follow more than one subsequent move. Donor m2193 seat A exhibited this before training began.',
        rule='Pair ordered movement sends and moves when the counts match. Use measured wire time if that movement is still current. Otherwise retain its original tick-end time. If counts differ, retain that entire stream of tick-end times. Always use the actual click polls in the polls and both arms.',
        audit='For each donor and seat record the exact fallback tick indices. Preserve all 16 training and eight development calendars with the same periods.'),fixed=True)
    names={}
    for role in ('train','dev'):
        for source in CLOCKS[role]:
            mid=Path(source).name.split('.')[0]
            if mid in names:raise ValueError('Overlapping training and evaluation recording')
            legacy=gzip.open(ROOT/source,'rt').read()
            full=tape(mid,maximum_ticks=160,explicit_polls=True,movement_flush=False)
            a,b=sections(legacy),sections(full)
            if list(a)!=list(b):raise ValueError('Changed clock periods or tracker order for '+mid)
            game=read(ROOT/'runtime/results'/(mid+'.json'))
            changes={}
            for header,events in b.items():
                side=header.split()[0]
                if side not in ('a','b'):continue
                period=int(header.split()[2])
                raw=timeline(mid,side,game,False,True)
                start=next(ts for ts,kind in raw if kind=='TICK_HEAD')
                wires=[ts-start for ts,kind in raw if kind=='FLUSH' and start<=ts<start+period]
                moves=[ts for ts,kind in events if kind=='MOVE']
                legacy_sends=[ts for ts,kind in events if kind=='FLUSH']
                if len(legacy_sends)!=len(moves):raise ValueError('Legacy stream does not send on each move')
                usable=len(wires)==len(moves)
                fallback=[];selected=[]
                for i,(move,following,old) in enumerate(zip(moves,moves[1:]+[period],legacy_sends,strict=True)):
                    if usable and move<=wires[i]<following:selected.append(wires[i])
                    else:
                        if not move<=old<following:raise ValueError('Historical movement order cannot be represented')
                        selected.append(old);fallback.append(i)
                changes[side]=dict(moves=len(moves),wire_messages=len(wires),fallback=fallback)
                b[header]=sorted([r for r in events if r[1]!='FLUSH']+[(ts,'FLUSH') for ts in selected])
            names[mid]=dict(role=role,legacy=source,versions={},movement=changes)
            for mode in MODES[1:]:
                lines=[]
                for header,old in a.items():
                    new=b[header]
                    keep=lambda row:row[1]!='FLUSH' and row[1] not in POLL
                    if list(filter(keep,old))!=list(filter(keep,new)):raise ValueError('Unmatched game events for '+mid)
                    if any(row[1] in POLL for row in old):raise ValueError('Expected historical fallback polls')
                    if header.startswith(('a ','b ')) and not POLL.issubset({row[1] for row in new}):
                        raise ValueError('Missing measured click polls for '+mid)
                    merged=[*filter(keep,old),*(r for r in (new if mode in ('movement','both') else old) if r[1]=='FLUSH')]
                    if mode in ('polls','both'):merged.extend(r for r in new if r[1] in POLL)
                    merged.sort()
                    lines.extend([header,*[f'{ts} {kind}' for ts,kind in merged]])
                data=('\n'.join(lines)+'\n').encode()
                if mode=='both' and sections(data.decode())!=b:raise ValueError('Failed to reconstruct observed schedule '+mid)
                dest=ROOT/clock_path(source,mode)
                freeze(dest,gzip.compress(data,mtime=0))
                names[mid]['versions'][mode]=hashlib.sha256(data).hexdigest()
    write_json(OUT/'timing/manifest.json',names,fixed=True)


def adjusted(target,start,count):
    rows=extension(target,start,count)
    for row in rows:row[1]=clock_path(row[1],'both')
    return rows


def fit():
    early,matched,original=budgets(INCUMBENT)
    _train_target(INCUMBENT,16,early,matched,original,END,OUT,False,fixture_extension=adjusted)


def arms(final):
    actor=PARENT/f'n{BEGIN}.net'
    if not final:return {'start':actor,'wtap':'wtap'}
    return {'recorded':OUT/f'n{END}.net','original':PARENT/f'n{END}.net','start':actor,'wtap':'wtap'}


def model(split):
    final=split=='prospective'
    for mode in (('both',) if final else MODES):
        clocks=read(OUT/'calendars/manifest.json')['selected'] if final else [clock_path(p,mode) for p in CLOCKS['dev']]
        cells=make_panel(clocks,8)
        if final:
            import random
            new=[]
            for i,c in enumerate(cells):
                rng=random.Random(728913+7919*i)
                new.append((*c[:6],rng.getrandbits(60),rng.getrandbits(60)))
            cells=new
        worker=Worker()
        try:
            for label,actor in arms(final).items():
                for foe in (INCUMBENT,'wtap'):
                    dest=OUT/split/mode/label/(foe+'.json')
                    if dest.is_file():continue
                    plan=[[actor,foe,c,seat,a,b,x,y] for _,_,seat,a,b,c,x,y in cells]
                    raw=[v for j in range(0,len(plan),32) for v in worker.run(plan[j:j+32],False)]
                    rows=[[*cell[:5],v[0],v[1]] for cell,v in zip(cells,raw,strict=True)]
                    if mode=='original' and not final:
                        src=PARENT/'development'/('32768' if label=='start' else 'wtap')/(('target' if foe==INCUMBENT else 'wtap')+'.json')
                        if rows!=read(src)['games']:raise ValueError('Historical endpoint failed to reproduce')
                    summary=stats(rows)
                    summary['clock']=[mean(r[5] for r in rows if r[0]==i) for i in range(len(clocks))]
                    write_json(dest,dict(games=rows,summary=summary),fixed=True)
        finally:worker.close()


def native():
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    bridge,version=assessment_bridge()
    tags={k:native_name(p) for k,p in arms(True).items()}
    plan=fixtures('input-clock-65k',list(tags.values()),[alias(INCUMBENT),WT],8,7)
    rows=fair_cohort(OUT/'native',plan,bridge,server=version['server'])
    result={key:{foe:[[t['side'],t['rtt'],t['opponent_rtt'],rows[q]['mid'],rows[q]['score']] for q,t in plan.items()
                     if t['arm']==arm and t['opponent']==alias(foe)] for foe in (INCUMBENT,'wtap')} for key,arm in tags.items()}
    write_json(OUT/'native/outcomes.json',result,fixed=True)


def report():
    def cell(row):
        return '/'.join(str(row[k]) for k in ('wins','draws','losses'))
    def counts(values):
        return dict(zip(('wins','draws','losses'),(values.count(v) for v in (1,.5,0))))
    lines=['# Measuring the timing of actual click polls and movement packets','',
       'The 32k specialist played frozen best-sumo on the original 137-block arena at seven CPS. Each row uses exactly the same 512 development fixtures. Plain W-tap played both opponents under the same conditions. Entries are wins/draws/losses.','',
       '| Timing in the simulator | Specialist vs best-sumo | Plain W-tap vs best-sumo | Specialist vs W-tap | W-tap mirror |',
       '| --- | --- | --- | --- | --- |']
    titles={'original':'Frame/tick opportunities and tick-end sends','polls':'Recorded click polls','movement':'Recorded movement sends','both':'Recorded polls and sends'}
    for mode in MODES:
        rows=[cell(read(OUT/'development'/mode/arm/(foe+'.json'))['summary']) for foe in (INCUMBENT,'wtap') for arm in ('start','wtap')]
        lines.append('| '+' | '.join([titles[mode],*rows])+' |')
    manifest=read(OUT/'timing/manifest.json')
    counts_by_role={k:[x for entry in manifest.values() if entry['role']==k for x in entry['movement'].values()] for k in ('train','dev')}
    train=sum(s['moves'] for s in counts_by_role['train'])
    kept=sum(len(s['fallback']) for s in counts_by_role['train'])
    lines.extend(['',f'All 16 training and eight development donor recordings include the actual click-poll timestamps. The training reconstruction retained the old tick-end time on {kept} of {train:,} movement sends. Those writes reached the wire after the next move had begun. Every development movement send used its measured wire time.',
                  '', 'Both 65,536-fight arms start from the same 32,768-fight optimizer. Training changes only the click-poll and movement-send timestamps of the same 16 recordings. A separate prospective panel and 64 main-server matches compare the two finished policies.'])
    for kind,title in [('prospective','Prospective simulator'),('native','Main Minecraft server')]:
        path=OUT/'native/outcomes.json' if kind=='native' else OUT/'prospective/both/wtap/wtap.json'
        lines.extend(['','## '+title,''])
        if not path.exists():
            lines.append('Pending.');continue
        native_rows=read(path) if kind=='native' else None
        def get(arm,foe):
            if native_rows is not None:return counts([r[-1] for r in native_rows[arm][foe]])
            return read(OUT/'prospective/both'/arm/(foe+'.json'))['summary']
        lines.extend(['| Opponent | Recorded timing at 65k | Original timing at 65k | Original 32k | Plain W-tap |','| --- | --- | --- | --- | --- |'])
        for foe in (INCUMBENT,'wtap'):
            lines.append('| '+' | '.join([foe,*[cell(get(arm,foe)) for arm in ('recorded','original','start','wtap')]])+' |')
    lines.extend(['','[Frozen comparison](protocol.json) · [Donor and send audit](timing/manifest.json) · [Wider timing comparison](../../midpoint-timing/README.md)'])
    atomic(OUT/'README.md',('\n'.join(lines)+'\n').encode())


def governed(task,lane):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py',lane,'nice','-n','8',sys.executable,'-u',__file__,task],check=True)


def independent(task):
    with (OUT/(task+'.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if task=='capture':
            from calendars_holdout import collect
            collect(OUT)
        else:
            governed('development','--terrain')
            report()


def run():
    register()
    with (OUT/'supervisor.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        capture=subprocess.Popen([sys.executable,'-u',__file__,'capture'])
        if not (OUT/'timing/manifest.json').is_file():governed('extract','--terrain')
        diagnosis=subprocess.Popen([sys.executable,'-u',__file__,'diagnosis'])
        while not (OUT/f'n{END}.net').exists():governed('train','--policy')
        if capture.wait() or diagnosis.wait():raise RuntimeError('Calendar collection or development diagnosis failed')
        while not (PARENT/f'n{END}.net').exists():time.sleep(10)
        governed('prospective','--terrain')
        report()
        native()
        report()


if __name__=='__main__':
    cmd=sys.argv[1] if len(sys.argv)>1 else 'run'
    if cmd in ('register','run','extract','train','report'): {'register':register,'run':run,'extract':extract,'train':fit,'report':report}[cmd]()
    elif cmd in ('capture','diagnosis'):independent(cmd)
    elif cmd in ('development','prospective'):model(cmd)
    else:raise ValueError(cmd)
