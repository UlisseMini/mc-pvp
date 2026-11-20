"""Import frozen ten-game policies and play real successive matches using only public round feedback."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import sys


from learner import ROOT,RTTS,write_json
from matched_native import live
from native_league import fair_cohort,fixture
from remote_import import wait_for,send_future_clocks
from remote_league import OUT,read
from series_bank import BUDGETS,FOLDER,ROUNDS
from series_eval import load_models,tally
from series_learn import HORIZONS,SEEDS,choose


def collect(budget,study=FOLDER):
    prefix=str(study.relative_to(OUT))+'/'
    paths=[]
    for h in HORIZONS:
        for s in SEEDS if budget==BUDGETS[-1] else SEEDS[:1]:
            sub=f'learn-{budget:02}/h{h}-s{s}/'
            paths.extend(prefix+sub+n for n in ('actor.pt','trained.json'))
    wait_for([*paths,prefix+f'dev/evaluation-{budget:02}.json'])
    for p in paths[::2]:
        path=OUT/p
        expected=read(path.with_name('trained.json')).get('sha256')
        if expected is None:
            from remote_fit import SSH,REMOTE,invoke
            expected=invoke([*SSH,'cd '+REMOTE+' && sha256sum '+str(path.relative_to(ROOT))]).split()[0]
        if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise ValueError('Exported series policy hash differs')
    if study==FOLDER:wait_for([prefix+f'belief/learn-{budget:02}.npz',prefix+f'belief/dev/evaluation-{budget:02}.json'])


def native(budget,study=FOLDER):
    import numpy as np
    from series_bayes import choose_public
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    archive=study!=FOLDER
    spec=read(study/'protocol.json');foes=dict(spec['field']);experts=list(spec['experts'])
    field_size=len(foes)
    if archive:foes['wtap']=read(FOLDER/'protocol.json')['field']['wtap']
    actors={n:live(ref) for n,ref in spec['experts'].items()};wt=live(foes['wtap'])
    report=read(study/'dev'/f'evaluation-{budget:02}.json')['scores']['memory-9']['opponents']
    weakest=min(report,key=lambda n:(report[n]['series'][0],report[n]['ringouts'][0],n))
    if archive:
        targets=['wtap',*spec['field']]
        modes=['memory','scoreboard','last-game','wtap','broad',*experts]
        broad=live(read(FOLDER/'protocol.json')['experts']['original-mixture'])
    else:
        badge=read(FOLDER/'belief/dev'/f'evaluation-{budget:02}.json')['scores']['lookahead']['opponents']
        belief_weak=min(badge,key=lambda n:(badge[n]['series'][0],badge[n]['ringouts'][0],n))
        targets=list(dict.fromkeys(('wtap',weakest,belief_weak,'sumo-seven-first')))
        with np.load(FOLDER/'belief'/f'learn-{budget:02}.npz') as saved:edges,probs=saved['edges'],saved['probabilities']
        modes=['memory','scoreboard','fixed','wtap','belief','belief-reset']
    bridge,stamp=assessment_bridge();folder=study/f'native-{budget:02}'
    paired=2 if budget!=BUDGETS[-1] else 8
    models={n:load_models(budget,h,len(experts),field_size,study) for n,h in [('memory',9),('scoreboard',0),('last-game',1)] if n in modes}
    records={}
    for rep in range(paired):
        for target in targets:
            for arm in modes:records[rep,target,arm]=[]
    write_json(folder/'series.json',dict(opponents=targets,arms=modes,replicates=paired,expert_order=experts,
        scope='Ten consecutive logical games against the same policy; fresh native regulation worlds and both players reset each time. Adaptive arms carry only public results, elapsed fight time, pings and their own selected expert.'),fixed=True)
    for g in range(ROUNDS):
        plan={};rows={}
        for (rep,target,arm),events in records.items():
            t=targets.index(target);j=modes.index(arm)
            side='ab'[(rep+g+t)%2];own,other=RTTS[(g+rep//2+t)%len(RTTS)]
            a=choose(models[arm],events,own,other,len(experts)) if arm in models else experts.index(arm) if arm in experts else 0
            if arm=='fixed':a=experts.index('original-mixture')
            if arm.startswith('belief'):a=choose_public(edges,probs,events,own,other,arm=='belief')
            tag='series10-archive' if archive else 'series10'
            key=f'{tag}-{budget}-r{rep}-f{t}-a{j}-g{g}'
            player=wt if arm=='wtap' else broad if arm=='broad' else actors[experts[a]]
            trial=fixture.trial(key,player,live(foes[target]),side,own,other)
            trial.update(cps_cap=7,command=trial['command'].replace(' cps=20 ',' cps=7 '),block=g)
            plan[key]=trial;rows[key]=(rep,target,arm,a,own,other)
        # Rotate the queue so one arm does not always play earlier on the server.
        keys=list(plan);np.random.default_rng(31078+g).shuffle(keys)
        audits=fair_cohort(folder/f'g{g:02}',{k:plan[k] for k in keys},bridge,server=stamp['server'])
        for k,(rep,target,arm,a,own,other) in rows.items():
            game=audits[k]
            records[rep,target,arm].append([a,float(game['score']),1000*game['duration'],own,other])
    result={}
    for arm in modes:
        result[arm]={}
        for foe in targets:
            played=[records[i,foe,arm] for i in range(paired)]
            games=np.array([[r[1] for r in event] for event in played]);points=games.sum(1)
            rates=np.array([p[3:] for e in played for p in e])
            sides=np.array([[(i+g+targets.index(foe))%2 for g in range(ROUNDS)] for i in range(paired)])
            result[arm][foe]=dict(series=tally(.5+.5*np.sign(points-5)),ringouts=tally(games),
                by_round=[tally(v) for v in games.T],seat={k:tally(games[sides==i]) for i,k in enumerate('ab')},
                rtt={f'{a}/{b}':tally(games.flatten()[(rates==[a,b]).all(1)]) for a,b in RTTS})
    write_json(folder/'summary.json',dict(scores=result,scope='independent native games within each ten-game series'),fixed=True)


def follow():
    with ThreadPoolExecutor(max_workers=2) as pool:
        native_jobs=[]
        for budget in BUDGETS:
            collect(budget)
            if budget in (BUDGETS[0],BUDGETS[-1]):native_jobs.append(pool.submit(native,budget))
        result=read(FOLDER/'dev'/f'evaluation-{BUDGETS[-1]:02}.json')
        belief=read(FOLDER/'belief/dev/evaluation-16.json')
        if result['passed'] or belief['passed']:
            send_future_clocks(FOLDER/'prospective',dict(ppo_qualified=result['passed'],belief_qualified=belief['passed'],
                                policy='Frozen three-seed PPO ensemble and learned belief model. Bonferroni over both on fresh clocks.'))
            for f in ('fresh/evaluation-16.json','belief/fresh/evaluation-16.json'):
                wait_for([str(FOLDER.relative_to(OUT))+'/'+f])
        for job in native_jobs:job.result()


def follow_archive():
    study=FOLDER/'archive-reversal';prefix=str(study.relative_to(OUT))+'/'
    with ThreadPoolExecutor(max_workers=2) as pool:
        jobs=[]
        for budget in (2,16):
            collect(budget,study)
            wait_for([prefix+f'dev/whole-memory-{budget:02}.json'])
            wait_for([prefix+'dev/unrestricted.json'])
            jobs.append(pool.submit(native,budget,study))
        # These opponents were discovered on the old development clocks; collect independent clocks even if the grade is negative.
        send_future_clocks(study/'prospective',dict(experts=read(study/'protocol.json')['experts'],budgets=[16],
              purpose='Independent confirmation of whether cross-game adaptation transfers in the two-opponent mechanism test.'))
        wait_for([prefix+'fresh/evaluation-16.json',prefix+'fresh/whole-memory-16.json',prefix+'fresh/unrestricted.json'])
        for job in jobs:job.result()


if __name__=='__main__':
    if sys.argv[1:]==['archive']:follow_archive()
    else:follow()
