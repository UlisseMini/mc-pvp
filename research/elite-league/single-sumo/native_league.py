"""Play and audit immutable single-match cohorts in the main 1.8.9 lab."""
import fcntl
import importlib.util
import random
import sys
import time
from league_tournament import *
sys.path.insert(0,str(HERE.parent))
import native_trials
import champions
from cpu_limit import family_usage,lab_cpu_budget
import audit
from final_inputs import verify_match
from movement_audit import legacy
spec=importlib.util.spec_from_file_location('league_native_fixture',HERE.parent/'ping-ppo/native.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
WT=fixture.WT
def read(path):return json.loads(path.read_text())
def props(name):return dict(r.split('=',1) for r in (ROOT/'policies'/(name+'.properties')).read_text().splitlines() if '=' in r)
def alias(name):return {'wtap':WT,'hold':'research-hold20','legacy-wtap-clickphase':'wtap-clickphase'}.get(name,name)
def publish(name,ref,caption):
    if ref['reference'].endswith('.net'):
        raw=(ROOT/ref['reference']).read_bytes();assert hashlib.sha256(raw).hexdigest()==ref['sha256']
        base=props('research-hold20');base.update(duel_network=raw.decode().strip(),duel_network_landmark='0')
    else:base=props(alias(ref['reference']))
    base.update(click_cps='7',description=caption,league_mode='sumo',league_source_sha256=ref['sha256'])
    roster={'league_stage':name,'league_track':'winner'} if name.startswith('sumo-league-') else {}
    base.update(roster)
    dest=ROOT/'policies'/(name+'.properties')
    if dest.exists():
        old=props(name)
        if any(old.get(k)!=v for k,v in base.items() if k!='description' and k not in roster):
            raise ValueError('Published controls differ for '+name)
        if any(old.get(k)!=v for k,v in roster.items()):
            old.update(roster)
            atomic(dest,(''.join(f'{k}={v}\n' for k,v in old.items())).encode())
    else:freeze(dest,(''.join(f'{k}={v}\n' for k,v in base.items())).encode())
    return name
def fixtures(tag,arms,foes,blocks,cap):
    order=[]
    for i in range(blocks):
        perm=[]
        for j,foe in enumerate(foes):
            for k,arm in enumerate(arms):
                # All three arms play a given opponent in the same seat/RTT in this block.
                cell=(i+3*j)%8;side='ab'[cell%2];a,b=RTTS[cell//2]
                q=f'{tag}-b{i:02}-f{j:02}-a{k}'
                v=fixture.trial(q,arm,foe,side,a,b)
                v.update(cps_cap=cap,command=v['command'].replace(' cps=20 ',f' cps={cap} '),block=i)
                perm.append((q,v))
        random.Random(9813+i).shuffle(perm);order.extend(perm)
    return dict(order)
def reconcile(plan):
    games={}
    for p in (ROOT/'runtime/results').glob('m*.json'):
        try:g=read(p)
        except (OSError,ValueError):continue
        if g.get('request') in plan:games[g['request']]=g['id']
    return games
def play(plan,out,bridge):
    from shutil import disk_usage
    from forge_window import server_hash
    if out.exists():
        d=read(out)
        if d['plan']!=plan:raise ValueError('Frozen native fixture changed')
        if len(d['matches'])==len(plan):return d['matches']
    out.parent.mkdir(parents=True,exist_ok=True)
    if not native_trials.ready(bridge):raise RuntimeError('Waiting for designated Forge bridge')
    sample=None;allowed=2;expected_server=server_hash();missing=None
    def slots():
        nonlocal sample,allowed,missing
        try:ready=native_trials.ready(bridge) and server_hash()==expected_server
        except (OSError,ValueError,KeyError):return 0
        if not ready:
            if missing is None:missing=time.monotonic()
            if time.monotonic()-missing>60:raise RuntimeError('Main Minecraft runtime changed during a frozen fixture')
            return 0
        missing=None
        if disk_usage(ROOT).free<9*1024**3:return 0
        now=time.monotonic()
        if sample is None or now-sample[0]>=3:
            current=family_usage([read(ROOT/'runtime/manager.json')['pid']],True)
            if sample is not None:
                prev,prior=sample;rate=sum(max(0,v-prior.get(k,v)) for k,v in current.items())/(now-prev)
                allowed=3 if rate<lab_cpu_budget()-.7 else 2 if rate<lab_cpu_budget()-.1 else 1
            sample=now,current
        return allowed
    batch=native_trials.load_batch()
    found=reconcile(plan)
    batch.run(plan,3,out,2,True,found,2,capacity_limit=slots)
    return read(out)['matches']
def certify(mid,trial,bridge):
    raw=read(ROOT/'runtime/results'/(mid+'.json'));assert raw['mode']=='sumo'
    if raw['reason']=='invalid_client_view':return {'valid':False,'reason':raw['reason']}
    for seat in 'ab':
        assert raw['client_'+seat]['bridge_sha256']==bridge and raw['click_cps_'+seat]==trial['cps_cap']
    assert raw['duration_limit_ms']==120000
    # Audit runs its JVM against all original research dependencies plus this league's implementation.
    # The Java launcher accepts a path-list here.
    from build import sim_paths
    audit.class_path=lambda:os.pathsep.join(map(str,sim_paths()))
    score=verify_match(mid,trial,BUILD/'native-scores')
    if not score['valid']:return score
    min_ns=math.ceil(1e9/trial['cps_cap']);intervals={};all_heads={}
    for seat in 'ab':
        path,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
        clicks=[];heads=[];declared=[]
        for e in audit.captures.events(path,{'click','duel_local_timing'}):
            if e.get('match')!=mid:continue
            if e['type']=='click':clicks.append(e['monotonic_ns']);declared.append(e['minimum_click_interval_ns'])
            else:heads.append((e['head_ns'],e['last_press_ns']))
        if any(n!=min_ns for n in declared) or any(b-a<min_ns for a,b in zip(clicks,clicks[1:])):raise ValueError('Physical CPS check failed '+mid)
        import bisect
        for t,last in heads:
            k=bisect.bisect_right(clicks,t)
            if last!=(clicks[k-1] if k else 0):raise ValueError('Incorrect local press age '+mid)
        intervals[seat]=min((b-a for a,b in zip(clicks,clicks[1:])),default=None);all_heads[seat]=len(heads)
    return dict(valid=True,mid=mid,score=.5 if not raw['winner'] else int(raw['winner']==raw[trial['side']]),
                controls=score['sides'],minimum_physical_ns=intervals,local_timing_heads=all_heads,
                reason=raw['reason'],duration=(raw['finish']-raw['start'])/1000)
def cohort(folder,plan,bridge):
    folder.mkdir(parents=True,exist_ok=True);records={};rejected=[]
    from forge_window import server_hash
    stamp=folder/'native-build.json'
    if stamp.exists() or not (folder/'attempt-0.json').exists():
        clients=[read(ROOT/f'runtime/bots/{i}/client-runtime.json') for i in range(8)]
        fields=('bridge_sha256','module_sha256','headless_sha256','java_sha256')
        versions={tuple(c[k] for k in fields) for c in clients}
        if len(versions)!=1 or clients[0]['bridge_sha256']!=bridge:raise ValueError('Mixed native cohort runtime')
        write_json(stamp,dict(server=server_hash(),**dict(zip(fields,versions.pop()))),fixed=True)
    write_json(folder/'protocol.json',dict(plan=plan,bridge=bridge),fixed=True)
    todo=plan.copy();links={q:q for q in todo}
    for attempt in range(8):
        found=play(todo,folder/f'attempt-{attempt}.json',bridge);next_todo={};next_links={}
        for q,mid in found.items():
            src=links[q];evidence=folder/'audits'/(mid+'.json')
            if evidence.exists():r=read(evidence)
            else:
                for k in range(31):
                    try:r=certify(mid,todo[q],bridge);break
                    except (EOFError,FileNotFoundError,StopIteration):
                        if k==30:raise
                        time.sleep(3)
                write_json(evidence,r,fixed=True)
            if r['valid']:records[src]=r
            else:
                rejected.append(dict(request=q,mid=mid,reason=r.get('reason')));new=f'{src}-r{attempt+1}'
                next_todo[new]=dict(plan[src],command=plan[src]['command'].replace(src,new));next_links[new]=src
        if not next_todo:break
        todo=next_todo;links=next_links
    if len(records)!=len(plan):raise RuntimeError('Repeated invalid native fixtures, preserved in attempt ledgers')
    write_json(folder/'verified.json',dict(matches=records,invalid=rejected),fixed=True);return records
if __name__=='__main__':
    if sys.argv[1]=='baseline':
        bridge=read(HERE.parent/'sumo-confidence/pins.json')['bridge']
        p=fixtures('sg-native-wt-control',[WT],[INCUMBENT,BASE_COUNTER],8,7)
        cohort(EVIDENCE/'native-baselines',p,bridge)
