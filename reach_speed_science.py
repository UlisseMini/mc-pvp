#!/usr/bin/env python3
"""Preregistered speed/view-lag experiments using unchanged native clients/server."""
import argparse, asyncio, bisect, collections, gzip, hashlib, json, math, re, statistics, time
from pathlib import Path
from lab import ROOT, RUNTIME, BUILD
from lab_runtime import properties
from experiments import match, save_policy
from science import client_events, server_events

OUT=ROOT/'research'

def distribution(xs):
    xs=sorted(xs)
    return {'n':len(xs),**({'mean':statistics.mean(xs),'median':statistics.median(xs),'p10':xs[int((len(xs)-1)*.1)],'p90':xs[int((len(xs)-1)*.9)],'min':xs[0],'max':xs[-1]} if xs else {})}

def policies():
    common={'science_trace':1,'science_network_trace':1,'reaction_ms':0,'click_cps':10,'pause_ms':50}
    result={}
    pulse='|'.join(f'{t}:{int(t%200==0)}:0:0' for t in range(0,4000,100))
    for speed in ('still','walk','sprint','pulse'):
        for clicks in ('probe','click'):
            name=f'reach-speed-{speed}-{clicks}'
            p={**common,'kind':'standstill' if speed=='still' else 'scripted' if speed=='pulse' else 'wtap',
               'sprint_mode':'walk' if speed in ('walk','pulse') else 'hold','click_mode':'none' if clicks=='probe' else 'fixed',
               'description':f'Speed/reach experiment: {speed}, {clicks}; zero artificial reaction, native movement'}
            if speed=='pulse':p['input_script']=pulse
            save_policy(name,p);result[name]=properties(ROOT/'policies'/f'{name}.properties')
    name='reach-speed-hold-click';save_policy(name,{**common,'kind':'hold','click_mode':'fixed','description':'Speed/reach experiment: hold W+Ctrl, no Wtap'})
    result[name]=properties(ROOT/'policies'/f'{name}.properties');return result

def fixtures():
    rows=[]
    pairs=[('walk','still'),('sprint','still'),('sprint','walk'),('walk','walk'),('sprint','sprint')]
    for phase,clicks,duration in [('geometry','probe',3500),('first_hit','click',4000)]:
        for a,b in pairs:
            for repeat in range(6):
                x,y=(a,b) if repeat%2==0 else (b,a)
                rows.append(dict(phase=phase,condition=a+'-'+b,repeat=repeat,flipped=bool(repeat%2),a=f'reach-speed-{x}-{clicks}',b=f'reach-speed-{y}-{clicks}',ping_a=0,ping_b=0,duration_ms=duration,split='calibration' if repeat<4 else 'holdout'))
    for repeat in range(2):rows.append(dict(phase='geometry',condition='still-still',repeat=repeat,flipped=False,a='reach-speed-still-probe',b='reach-speed-still-probe',ping_a=0,ping_b=0,duration_ms=3500,split='control'))
    for a,b in [('sprint','still'),('sprint','walk'),('sprint','hold')]:
        for flip in (False,True):
            x,y=(b,a) if flip else(a,b)
            rows.append(dict(phase='sustained',condition=a+'-'+b,repeat=int(flip),flipped=flip,a=f'reach-speed-{x}-click',b=f'reach-speed-{y}-click',ping_a=0,ping_b=0,duration_ms=20000,split='holdout'))
    for p,q in [(50,50),(0,100),(100,100)]:
        for a,b in [('sprint','still'),('sprint','walk')]:
            for flip in (False,True):
                x,y=(b,a) if flip else(a,b)
                rows.append(dict(phase='ping_holdout',condition=a+'-'+b,repeat=int(flip),flipped=flip,a=f'reach-speed-{x}-probe',b=f'reach-speed-{y}-probe',ping_a=q if flip else p,ping_b=p if flip else q,duration_ms=3500,split='holdout'))
    for a,b in [('pulse','still'),('sprint','pulse')]:
        for repeat in range(4):
            x,y=(a,b) if repeat%2==0 else(b,a)
            rows.append(dict(phase='acceleration_holdout',condition=a+'-'+b,repeat=repeat,flipped=bool(repeat%2),a=f'reach-speed-{x}-probe',b=f'reach-speed-{y}-probe',ping_a=0,ping_b=0,duration_ms=3500,split='holdout'))
    return rows

def archive(g):
    for s in ('a','b'):client_events(g,s)
    target=OUT/'traces'/f'{g["id"]}-server.jsonl.gz'
    if not target.exists():
        with gzip.open(target,'wt') as f:f.write((RUNTIME/'replays'/f'{g["id"]}.jsonl').read_text())

def vector(s):return [s['posX'],s['posY'],s['posZ']]
def dist(a,b):return math.hypot(b[0]-a[0],b[2]-a[2])
def dot(a,b):return a[0]*b[0]+a[2]*b[2]
def sub(a,b):return [x-y for x,y in zip(a,b)]
def direction(a,b):
    d=dist(a,b);return [(b[0]-a[0])/d,0,(b[2]-a[2])/d] if d>1e-8 else [0,0,0]

class Track:
    def __init__(self,points):
        unique={t:p for t,p in points};self.times=sorted(unique);self.values=[unique[t] for t in self.times]
    def at(self,t):
        i=bisect.bisect_right(self.times,t)-1
        return self.values[i] if i>=0 and t-self.times[i]<=110 else None
    def linear(self,t):
        i=bisect.bisect_right(self.times,t)-1
        if i<0 or i>=len(self.times)-1:return None
        t0,t1=self.times[i:i+2]
        if t1-t0>110:return None
        f=(t-t0)/(t1-t0)
        return [a+(b-a)*f for a,b in zip(self.values[i],self.values[i+1])]

def analyze_game(g):
    es={s:client_events(g,s) for s in ('a','b')};raw=server_events(g['id'])
    moves={s:[e for e in es[s] if e['type']=='science_move'] for s in es}
    probes={s:[e for e in es[s] if e['type']=='reach_probe' and 'own' in e] for s in es}
    own={s:Track([(e['time'],vector(e['before'])) for e in moves[s][:1]]+[(e['time']+.001,vector(e['after'])) for e in moves[s]]) for s in es}
    remote={s:Track([(e['time'],vector(e['target'])) for e in probes[s]]+[(e['time']+.001,vector(e['after'])) for e in es[s] if e['type']=='science_interpolation']) for s in es}
    velocity={s:Track([(e['time']+.001,[(e['after']['pos'+a]-e['before']['pos'+a])*20 for a in 'XYZ']) for e in moves[s]]) for s in es}
    hits=[e for e in raw if e['type']=='hit'];first_hit=hits[0]['time'] if hits else g['finish']
    # Evaluate every shared-wall-clock 5 ms; states remain native step functions.
    rows=[];crossed=False
    for t in range(g['start']+150,g['finish'],5):
        a,b=own['a'].at(t),own['b'].at(t);ra,rb=remote['a'].at(t),remote['b'].at(t)
        va,vb=velocity['a'].at(t),velocity['b'].at(t)
        if any(x is None for x in (a,b,ra,rb,va,vb)):continue
        n=direction(a,b);d=dist(a,b)
        if d<1.8:crossed=True
        ua=dot(va,n);ub=-dot(vb,n)
        ga,gb=dist(a,ra),dist(b,rb)
        lag_a=dot(sub(a,rb),n);lag_b=-dot(sub(b,ra),n)
        rows.append(dict(t=t,d=d,ua=ua,ub=ub,dv=ua-ub,adv=gb-ga,lag_a=lag_a,lag_b=lag_b,
                         flat=all(abs(x[1]-4)<1e-6 for x in (a,b,ra,rb)),opening=not crossed and t<first_hit,dga=ga,dgb=gb,
                         y_a=a[1],y_b=b[1]))
    opening=[r for r in rows if r['opening'] and r['flat'] and 2<=r['d']<=5.5]
    # First native opportunity, with the last false sample retained as its timing interval.
    first={}
    for s in ('a','b'):
        prev=None
        for e in probes[s]:
            if e['in_range']:
                first[s]={'time':e['time'],'previous_false_time':prev,'center_distance_view':dist(vector(e['own']),vector(e['target']))};break
            prev=e['time']
    lead=(first['b']['time']-first['a']['time']) if len(first)==2 else None
    first_server=next((s for s in ('a','b') if hits and hits[0]['attacker']==g[s]),None)
    ticks=[e['time'] for e in raw if e['type']=='tick' and e['time']>=g['start']]
    gaps=[b-a for a,b in zip(ticks,ticks[1:])]
    clicks={s:[e for e in es[s] if e['type']=='engine_attack'] for s in es}
    result={'id':g['id'],'qualification':g.get('validation'),'reason':g.get('reason'),'server_tick_gaps_ms':distribution(gaps),
            'opening':{k:distribution([r[k] for r in opening]) for k in ('ua','ub','dv','adv','lag_a','lag_b')},
            'opening_samples':opening,'samples':rows,'first_reach':first,'first_reach_lead_ms_a':lead,
            'first_server_hit_side':first_server,'first_server_hit_after_start_ms':hits[0]['time']-g['start'] if hits else None,
            'first_local_contact':{s:clicks[s][0]['time']-g['start'] if clicks[s] else None for s in es},
            'score':[g['hits_a'],g['hits_b']], 'rows':len(rows)}
    return result

async def run(name):
    path=OUT/(name+'.json')
    previous=json.loads(path.read_text()) if path.exists() else None
    bots=policies();fs=fixtures()
    doc={'stage':'preregistered','created_unix':time.time(),'agent_sha256':hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest(),'policies':bots,'fixtures':fs,
         'model_before_tests':{'definition':'A advantage = B perceived horizontal center separation minus A perceived separation; positive favors A. Signed approach speeds, not speed magnitudes.',
         'constant_speed':'advantage = uA*tau_A_to_B - uB*tau_B_to_A; equal delay => tau*(uA-uB)',
         'variable_speed':'replace speed*delay with net displacement over the rendered history age; actual interpolation is a mixture of historical positions',
         'first_hit':'eligibility time lead approx advantage/(uA+uB), then add click phase and outbound packet-processing differences',
         'prior_engine_age_seconds':[.10,.25], 'ping_generalization':'tau approx engine_age + (RTT_A+RTT_B)/2000 for symmetric links; engine contribution must be calibrated',
         'calibration':'Fit through-origin coefficient on first four repetitions of zero-ping non-clicking unequal-speed conditions; hold out last two, equal-speed controls, real attacks, acceleration and pings.',
         'caveats':'No universal coefficient from unsigned speed difference; height, velocity history, update/client phase, server fork, lag compensation and attack timing remain relevant.'},
         'measurement':'Native shared-wall-clock states sampled every5ms as step functions; do not treat dense samples as independent replicates. First reach bracketed by approximately50ms probes.',
         'load_gate':'Two concurrent trials maximum. Exclude from calibration trials with >2% server tick gaps >80ms or any >200ms; preserve failures.', 'results':[]}
    if previous is not None:
        if previous['policies']!=bots or previous['fixtures']!=fs or previous['agent_sha256']!=doc['agent_sha256']:raise ValueError('Cannot resume changed experiment')
        doc=previous
    completed={r['index'] for r in doc['results']}
    def save():
        temp=path.with_suffix('.tmp');temp.write_text(json.dumps(doc,indent=2)+'\n');temp.replace(path)
    save();sem=asyncio.Semaphore(2)
    async def trial(i,f):
        async with sem:
            for p in (f['a'],f['b']):
                if properties(ROOT/'policies'/f'{p}.properties')!=bots[p]:raise RuntimeError('Frozen policy changed')
            for attempt in range(2):
                try:
                    g=await asyncio.to_thread(lambda:asyncio.run(match(f['a'],f['b'],f['ping_a'],f['ping_b'],duration_ms=f['duration_ms'])));break
                except RuntimeError as error:
                    failure={'index':i,'fixture':f,'error':str(error),'attempt':attempt,'time':time.time()}
                    found=re.search(r'Invalid setup for (m[0-9]+):',str(error))
                    if found:
                        failed=json.loads((RUNTIME/'results'/(found[1]+'.json')).read_text());archive(failed);failure['game']=failed
                    doc.setdefault('failed_trials',[]).append(failure);save()
                    if attempt or not found:raise
                    print('Retrying recorded setup/qualification failure: '+str(error),flush=True)
            await asyncio.to_thread(archive,g);a=await asyncio.to_thread(analyze_game,g)
            detail=OUT/(name+'-samples');detail.mkdir(exist_ok=True)
            with gzip.open(detail/(g['id']+'.json.gz'),'wt') as out:json.dump(a,out,separators=(',',':'))
            a={k:v for k,v in a.items() if k not in ('samples','opening_samples')}
            doc['results'].append({'index':i,'fixture':f,'game':g,'analysis':a});doc['results'].sort(key=lambda r:r['index']);doc['stage']='running';save()
            print(json.dumps({'completed':len(doc['results']),'total':len(fs),'id':g['id'],'condition':f['condition'],'phase':f['phase'],'mean_advantage':a['opening']['adv'].get('mean'),'first_reach_lead_ms':a['first_reach_lead_ms_a'],'score':a['score'],'qualified':g.get('validation',{}).get('passed')}),flush=True)
    for phase in ('geometry','first_hit','sustained','ping_holdout','acceleration_holdout'):
        await asyncio.gather(*(trial(i,f) for i,f in enumerate(fs) if f['phase']==phase and i not in completed))
    doc['stage']='complete';save()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('name');args=p.parse_args();asyncio.run(run(args.name))
