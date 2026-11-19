"""Retrain and validate sumo on the measured MMC surface. Prior disk results have their own evidence."""
import argparse
from collections import defaultdict
import gzip
import json
import random
import tempfile
from pathlib import Path
import champions
import focus
from adaptation import dump,freeze,sha,properties
from audit import collect
from breadth import field
from history_cup import counts,mirrors
from ladder_transfer import native_install,plan,WT,REFERENCE
from native_trials import run_batch
from results import native
from run import ROOT,HERE,calendars
from tournament import grid

WORK=ROOT/'build/elite-league/mmc13'
FROZEN=HERE/'mmc13-frozen'
EVIDENCE=HERE/'evidence/mmc13'
DIGEST='89f9197f91ca4b33e1e00ef751b22ab2ba476885608c528b51bfdb32cb6c123a'
OLD_TARGET=focus.TARGET
CURRENT=focus.CURRENT

def groups():
    original=field()
    for i in (2,3):
        for kind in ('explicit','neural'):
            original['modern'][f'focus-{i:02d}-{kind}']=HERE/f'focus-frozen/round-{i:02d}/{kind}.net'
    for i in (1,2):
        original['modern'][f'breadth-{i:02d}-fit']=HERE/f'breadth-frozen/breadth-{i:02d}/contender.net'
    return original

def setup():
    for folder in (WORK,FROZEN,EVIDENCE):folder.mkdir(exist_ok=True,parents=True)
    focus.SERIES='mmc13';focus.DEST=FROZEN;focus.all_groups=groups

def rates(path):
    by=defaultdict(lambda:defaultdict(list))
    with gzip.open(path,'rt') as f:
        for r in map(json.loads,f):
            if (r['diameter'],r['start_gap'],r['aim_input'])!=(13,8,'mouse'):raise ValueError('Different model layout')
            by[r['arm']][r['opponent']].append(r['score'])
    return {a:{o:sum(v)/len(v) for o,v in vs.items()} for a,vs in by.items()}

def select_target():
    modern=groups()['modern'];d=FROZEN/'target-selection.json';out=FROZEN/'incumbent-field.jsonl.gz'
    focus.play_model(out,focus.BASE,modern,calendars('development_selection_clocks'))
    cells=rates(out)
    rivals=[n for n in modern if n!='wtap' and focus.identity(n,modern[n])!=focus.identity(CURRENT,focus.BASE[CURRENT])]
    target=min(rivals,key=lambda n:(cells[CURRENT][n],n))
    dump(d,dict(target=target,incumbent=CURRENT,field_sha256=sha(out),rates=cells))
    return target

def train():
    focus.TARGET=select_target()
    if not (FROZEN/'round-01/selection.json').exists():focus.stage1(WORK)
    for i in (2,3):focus.fit_round(i,WORK)

def native_name(n):
    return n.removeprefix('legacy-') if n.startswith('legacy-') else focus.names(n)

def record_setup(rows,diameter=13):
    for r in rows.values():
        r.update(diameter=diameter,start_gap=8 if diameter==13 else 6)
    return rows

def regulation_batch(trials,manifest,evidence):
    """Replay referee-invalidated client views in the same cell; retain every attempt."""
    run_batch(trials,manifest)
    attempts=json.loads(manifest.read_text())['matches']
    selected={};accepted={};replacements={};excluded=[]
    for attempt in range(3):
        retry={}
        for key,mid in attempts.items():
            task=replacements.get(key,trials.get(key))
            reason=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text())['reason']
            if reason!='invalid_client_view':
                accepted[key]=task
                selected[key]=mid
                continue
            excluded.append(dict(request=key,mid=mid,reason=reason))
            base=key.split('-view-retry',1)[0]
            request=base+'-view-retry'+str(attempt+1)
            words=trials[base]['command'].split()
            words[words.index(base)]=request
            retry[request]=dict(trials[base],command=' '.join(words))
        if not retry:break
        if attempt==2:
            dump(evidence/(manifest.stem+'-incomplete-views.json'),excluded)
            raise ValueError('Three invalid native views in the same scheduled fixture')
        replacements=retry
        batch=manifest.with_name(manifest.stem+f'-view-retry{attempt+1}.json')
        freeze(batch.with_suffix('.build.json'),manifest.with_suffix('.build.json').read_bytes())
        run_batch(retry,batch)
        attempts=json.loads(batch.read_text())['matches']
    if not excluded:return manifest
    if len(accepted)!=len(trials) or len(set(selected.values()))!=len(trials):
        raise ValueError('Unequal exposure after replacing invalid client views')
    chosen=manifest.with_name(manifest.stem+'-eligible.json')
    dump(chosen,dict(plan=accepted,matches=selected))
    dump(evidence/(manifest.stem+'-invalid-views.json'),excluded)
    return chosen

def read_results(manifest):
    records=json.loads(manifest.read_text())
    if records['matches'].keys()!=records['plan'].keys():raise ValueError('Incomplete schedule')
    out=[]
    for key,mid in records['matches'].items():
        t=records['plan'][key];g=json.loads((ROOT/'runtime/results'/(mid+'.json')).read_text());arena=g['sumo_arena']
        if (g['request'],g['policy_'+t['side']],arena['diameter'],arena['start_gap'])!=(key,t['arm'],13,8):raise ValueError('Match differs from schedule')
        if arena['observed_floor_sha256']!=DIGEST or arena['shape']!='mmc_recorded_13_v1':raise ValueError('Different native floor')
        with (ROOT/'runtime/replays'/(mid+'.jsonl')).open() as f:
            countdown=[]
            for line in f:
                e=json.loads(line)
                if e.get('type')!='tick':continue
                if e['time']>=g['start']:break
                countdown.append(e)
        center=(g['arena']+1)*4096+.5
        if not countdown or countdown[-1]['time']<g['start']-150 or any(
                (e[s]['posX'],e[s]['posY'],e[s]['posZ'])!=(center+sign*4,12,.5)
                for e in countdown for s,sign in [('a',-1),('b',1)]):
            raise ValueError('Final countdown position differs from the recorded map: '+mid)
        if g['reason'] not in ('ring_out','double_ring_out','timeout_draw'):raise ValueError('Competition interrupted: '+mid)
        out.append(dict(mid=mid,**{k:t[k] for k in ('side','rep','rtt','opponent','arm')},win=g['winner']==g[t['side']],draw=not g['winner']))
    return out

def contest_plan(tag,arms,foes,repeats,profile='mmc'):
    tasks={}
    # These are normal referee commands without a diameter argument, so default selection is exercised.
    for o in foes:tasks.update(record_setup(plan('m13center'+tag,[native_name(n) for n in arms],[native_name(o)],repeats[o])))
    if profile!='mmc':
        for r in tasks.values():r['profile']=profile;r['command']=r['command'].replace('preset=mmc','preset='+profile)
    items=list(tasks.items());random.Random(78331).shuffle(items)
    return dict(items)

def contest(tag,arms,foes,repeats,tapes,profile='mmc'):
    d=EVIDENCE/tag;d.mkdir(exist_ok=True,parents=True);model=d/'model.jsonl.gz'
    name='mmc13-'+tag;manifest=WORK/(name+'.json')
    native_install({native_name(n):p for n,p in (arms|foes).items() if p})
    tasks=contest_plan(tag,arms,foes,repeats,profile)
    dump(d/'schedule.json',dict(profile=profile,arms={n:focus.identity(n,p) for n,p in arms.items()},opponents={n:focus.identity(n,p) for n,p in foes.items()},repeats=repeats))
    if not model.exists():
        if profile=='mmc':focus.play_model(model,arms,foes,tapes)
        else:
            from run import compile_model
            compile_model()
            with tempfile.TemporaryDirectory(dir=WORK) as tmp:freeze(model,grid(Path(tmp),'matches',arms,foes,tapes,mode='mouse',profile=profile).read_bytes())
    rates(model)
    manifest=regulation_batch(tasks,manifest,d)
    if tag=='final':
        from final_inputs import eligible
        manifest=eligible(WORK/(name+'.json'),manifest,d,WORK/(name+'-scores'))
    rows=read_results(manifest);actual=counts(rows)
    with gzip.open(model,'rt') as f:predictions=list(map(json.loads,f))
    for r in predictions:r.update(arm=native_name(r['arm']),opponent=native_name(r['opponent']))
    sim=counts(predictions,True)
    winner=min(actual,key=lambda n:(-actual[n]['merit'],-actual[n]['mean'],n!=WT,n))
    bundle=d/'native.json.gz'
    if not bundle.exists():scores=WORK/(name+'-scores');collect(manifest,scores,False);native(manifest,scores,bundle)
    champions.install(name,ROOT/'policies'/(winner+'.properties'),f'Recorded MMC platform native {tag} winner ({winner})',name)
    dump(d/'outcomes.json',dict(winner=winner,model=sim,native=actual,records=rows))
    return rows,predictions

def pilot():
    foes={'wtap':None,OLD_TARGET:groups()['modern'][OLD_TARGET]}
    contest('opening',focus.BASE,foes,{n:4 for n in foes},calendars('development_selection_clocks'))
    from reigning import publish
    folder=EVIDENCE/'opening'
    with gzip.open(folder/'native.json.gz','rt') as f:audit=json.load(f)
    if len(audit['matches'])!=96 or audit['summary']['binding_disagreements']:
        raise ValueError('Opening tournament input validation failed')
    receipt=folder/'outcomes.json'
    winner=json.loads(receipt.read_text())['winner']
    publish('best-sumo',winner,'Recorded MMC platform opening tournament winner ('+winner+')',receipt)

def finalists():
    last=FROZEN/'round-03';result=json.loads((last/'selection.json').read_text());modern=groups()['modern']
    def resolve(n):
        if n.startswith('mmc13-'):
            _,r,k=n.split('-');return FROZEN/('round-'+r)/(k+'.net')
        return (focus.BASE|modern)[n]
    n=result['winner']
    best_new=next(r['name'] for r in result['ranking'] if r['name'].startswith('mmc13-') and r['name'].endswith(('-explicit','-neural')))
    arms=focus.unique(focus.BASE|{n:resolve(n),best_new:resolve(best_new)})
    target=json.loads((FROZEN/'target-selection.json').read_text())['target']
    weak=next(o for o in result['ranking'][0]['weakest'] if focus.identity(o,modern[o])!=focus.identity(n,resolve(n)))
    return n,best_new,arms,list(dict.fromkeys([target,weak,OLD_TARGET,CURRENT]))

def confirm():
    n,new,arms,hard=finalists();modern=groups()['modern']
    repeats={'wtap':12,**{o:6 for o in hard if o!='wtap'}}
    dump(FROZEN/'confirmation.json',dict(selected=n,new=new,arms={k:focus.identity(k,v) for k,v in arms.items()},repeats=repeats))
    tapes=mirrors('mmc13-confirm',WORK)
    rows,model=contest('confirmation',arms,{o:modern[o] for o in repeats},repeats,tapes)
    results={}
    for a in arms:
        name=native_name(a);wt=[r for r in rows if r['arm']==name and r['opponent']==WT]
        sw=[r['score'] for r in model if r['arm']==name and r['opponent']==WT]
        cells={f'{t}/{s}':[r['win'] for r in wt if r['rtt']==t and r['side']==s] for t in (0,80) for s in 'ab'}
        if len(sw)!=32 or any(len(v)!=12 for v in cells.values()):raise ValueError('Unequal W-tap exposure')
        results[name]=dict(wins=sum(r['win'] for r in wt),draws=sum(r['draw'] for r in wt),total=len(wt),
            wtap_wins_by_cell={k:sum(v) for k,v in cells.items()},model_wins=sw.count(1),model_games=len(sw),
            clear=sum(r['win'] for r in wt)>=.90*len(wt) and all(sum(v)>=10 for v in cells.values()) and sw.count(1)>=.9*len(sw))
    dump(EVIDENCE/'confirmation/qualification.json',results)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',choices=('train','pilot','confirm'));args=p.parse_args();setup()
    {'train':train,'pilot':pilot,'confirm':confirm}[args.stage]()
