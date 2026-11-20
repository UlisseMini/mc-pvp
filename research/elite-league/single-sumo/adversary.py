"""One 65,536-bout best-response search per frozen opponent, shared by league and winner searches."""
import fcntl
import hashlib
import json
import subprocess
import sys
from pathlib import Path

from id_evaluate import make_panel
from league_tournament import stats, interval
from learner import CLOCKS, EVIDENCE, HERE, ROOT, Worker, write_json
from run_training import train
from train import configure

BUDGET = 65536
UPDATES = BUDGET // 64
STORE = EVIDENCE / 'fixed-adversaries'


def read(path):
    return json.loads(path.read_text())


def ref(path):
    return dict(reference=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def passes(wins, games):
    if games <= 0 or not 0 <= wins <= games:
        raise ValueError('Invalid ring-out counts')
    return 5*wins > 3*games


def close(target, actor, checkpoint, candidate, plain, source, fixture_path):
    counts = [candidate[k] for k in ('wins', 'draws', 'losses')]
    if sum(counts) != 512 or sum(plain[k] for k in ('wins','draws','losses')) != 512:
        raise ValueError('The budget gate requires 512 matched games per arm')
    result = dict(target=target, policy=actor, checkpoint=str(checkpoint.relative_to(ROOT)),
                  budget=BUDGET, games=512, confirmed=passes(counts[0],512),
                  challenger_result=candidate, wtap=plain, donor_bounds=interval(candidate['clock']),
                  source=source, fixtures=str(fixture_path.relative_to(ROOT)))
    write_json(STORE/target['sha256']/'result.json', result, fixed=True)
    return result


def import_originals():
    from long_specialist import RUN
    from league_tournament import historical
    for key in ('incumbent','seven','g1'):
        p = RUN/key
        proto = read(p/'protocol.json')
        target = dict(reference=proto['target'], sha256=proto['target_sha'])
        actual = ref(ROOT/target['reference']) if target['reference'].endswith('.net') else historical()[target['reference']]
        if actual != target: raise ValueError('Original target changed')
        if (STORE/target['sha256']/'result.json').exists():continue
        close(target, ref(p/f'n{BUDGET}.net'), p/'milestones'/f'n{BUDGET}.pt',
              read(p/'development'/str(BUDGET)/'target.json')['aggregate'],
              read(p/'development/wtap/target.json')['aggregate'], 'existing 65k specialist', p/'development')


def choose(field, preferred):
    import_originals()
    unique = list(dict.fromkeys(preferred))
    for name in unique:
        if name in field and not (STORE/field[name]['sha256']/'result.json').exists():
            return name
    return unique[0]


def assess(folder):
    spec=read(folder/'protocol.json')
    from league_tournament import pin_model
    actor=pin_model(folder)
    panel=make_panel(CLOCKS['dev'],8)
    w=Worker()
    try:
        for name,source in [('candidate',actor['reference']),('wtap','wtap')]:
            target=spec['target']['reference']
            dest=folder/'evaluation'/(name+'.json')
            if dest.exists():continue
            plan=[[source,target,c,s,a,b,x,y] for _,_,s,a,b,c,x,y in panel]
            raw=[entry for k in range(0,len(plan),32) for entry in w.run(plan[k:k+32],False)]
            rows=[[i,r,s,a,b,rw,end] for (i,r,s,a,b,*_), (rw,end,_) in zip(panel,raw,strict=True)]
            write_json(dest,dict(aggregate=stats(rows),games=rows),fixed=True)
    finally:w.close()
    return close(spec['target'],actor,folder/'trainer.pt',read(folder/'evaluation/candidate.json')['aggregate'],
                 read(folder/'evaluation/wtap.json')['aggregate'],'single-target MLP PPO',folder/'evaluation')


def obtain(target, warm, seed):
    import_originals()
    from league_tournament import historical
    actual=ref(ROOT/target['reference']) if target['reference'].endswith('.net') else historical()[target['reference']]
    if actual!=target:raise ValueError('Target changed before training')
    folder=STORE/target['sha256']
    folder.mkdir(parents=True,exist_ok=True)
    with (folder/'search.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        result=folder/'result.json'
        if not result.exists():
            spec=folder/'protocol.json'
            if not spec.exists():
                write_json(spec,dict(target=target,initial=str(warm.relative_to(ROOT)),
                                    initial_sha256=hashlib.sha256(warm.read_bytes()).hexdigest(),
                                    seed=seed,episodes=BUDGET,updates=UPDATES,cap=7,
                                    assessment='512 development bouts, eight donors, both seats, four RTT pairs, common action seeds and matched plain W-tap. Strictly more than 60% outright ring-outs. Reuse this verdict for identical opponent weights.'),fixed=True)
            cfg=read(spec)
            if cfg['target']['sha256']!=target['sha256']:raise ValueError('Target changed')
            warm=ROOT/cfg['initial']
            if hashlib.sha256(warm.read_bytes()).hexdigest()!=cfg['initial_sha256']:raise ValueError('Initializer changed')
            from remote_fit import enabled,delegate
            # Old in-flight jobs retain their original optimizer, host, and absolute config.
            old=folder/'config.json'
            existing=read(old) if old.exists() else None
            remote=enabled() and (not existing or not Path(existing['warm']).is_absolute())
            configure(folder,'mlp','exploiter',cfg['seed'],cfg['seed']+13513,cfg['target']['reference'],'wtap',{},
                      warm=(existing['warm'] if existing else warm.relative_to(ROOT) if remote else warm))
            if remote:delegate(folder,UPDATES)
            else:train(folder,UPDATES,policy_lane=True)
            if read(folder/'progress.json')['duels']!=BUDGET:raise ValueError('Unexpected final training budget')
            subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,'assess',folder],check=True)
        outcome=read(result)
        if ref(ROOT/outcome['policy']['reference'])!=outcome['policy']:raise ValueError('Registered actor changed')
        return outcome


def gate(outcome, name, target):
    return dict(incumbent=target,challenger=name,challenger_result=outcome['challenger_result'],
                wtap=outcome['wtap'],donor_bounds=outcome['donor_bounds'],confirmed=outcome['confirmed'],
                target_sha256=outcome['target']['sha256'],budget=BUDGET,scope='512 development simulation games; outright wins/512 > 0.60')


def native_check(outcome):
    from id_native import native_name
    from native_league import WT, alias, fair_cohort, fixtures
    from pathlib import Path
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    folder=STORE/outcome['target']['sha256']/'native'
    actor=native_name(ROOT/outcome['policy']['reference'])
    target=outcome['target']['reference']
    foe=native_name(ROOT/target) if target.endswith('.net') else alias(target)
    tag='adv65-'+outcome['target']['sha256'][:12]
    plan=fixtures(tag,[actor,WT],list(dict.fromkeys([foe,WT])),8,7)
    bridge,release=assessment_bridge()
    results=fair_cohort(folder,plan,bridge,server=release['server'])
    def tally(qs):
        v=[results[q]['score'] for q in qs]
        return dict(wins=v.count(1),draws=v.count(.5),losses=v.count(0))
    from learner import RTTS
    report={}
    for name in (actor,WT):
        report[name]={}
        for enemy in (foe,WT):
            qs=[q for q,t in plan.items() if t['arm']==name and t['opponent']==enemy]
            report[name][enemy]=dict(**tally(qs),seat={s:tally([q for q in qs if plan[q]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':tally([q for q in qs if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
    write_json(folder/'summary.json',report,fixed=True)


if __name__=='__main__':
    from pathlib import Path
    if sys.argv[1]=='assess':assess(Path(sys.argv[2]))
    elif sys.argv[1]=='import':import_originals()
    elif sys.argv[1]=='respond':
        from long_specialist import RUN
        key=sys.argv[2] if len(sys.argv)>2 else 'incumbent'
        if key not in ('incumbent','seven','g1'):raise ValueError(key)
        target=ref(RUN/key/f'n{BUDGET}.net')
        if key=='g1':
            parent=EVIDENCE/'g001/frontier-generalists/response'
            panel=read(EVIDENCE/'robustness-001/starting-field/native-design.json')
            field=read(EVIDENCE/'g002/generation.json')['field']
            if ref(parent/'final.net')!=field[panel['weakest']] or panel['actor']!='sg003-long-g1':
                raise ValueError('The observed hardest opponent changed')
            initial=parent/'trainer.pt';seed=1996409
        else:
            initial=EVIDENCE/'g001/mlp/trainer.pt';seed=1840291 if key=='incumbent' else 1870327
        native_check(obtain(target,initial,seed))
    else:raise ValueError(sys.argv[1])
