"""Two archived opponents with crossed best responses. Both start the match with identical controls."""
from assay import development,evaluate
from bootstrap import fit
from league_tournament import *
from run_training import train
from train import configure
from styles import decode
import sys

DRILL=EVIDENCE/'style-drill'
STYLES={'early':INCUMBENT,'late':'mmc13-actor-2-fit'}
RESPONSES=[BASE_COUNTER,'v3-memory-27']
SEED=1377

def pair(folder,opening,delay):
    paths={r[0]:ROOT/r[1] for line in FIELD_PATH.read_text().splitlines() if len(r:=line.split('\t'))>1}
    _,template=decode(paths['shift-train-07']);start=8+37;block=(len(template)-start)//2
    result={}
    for label,second in STYLES.items():
        w=np.zeros_like(template);w[:8]=[1,0,0,1,delay,0,1,0]
        for i,name in enumerate([opening,second]):
            kind,weights=decode(paths[name]);assert kind=='MLP' and len(weights)<=block
            w[start+i*block:start+i*block+len(weights)]=weights
        data=b'PAIR.'+base64.b64encode(w.astype('>f4').tobytes())+b'\n'
        p=folder/(label+'.net');freeze(p,data)
        result[label]=dict(reference=str(p.relative_to(ROOT)),sha256=hashlib.sha256(data).hexdigest())
    return result

def plan():
    path=DRILL/'assay.json'
    if path.exists():return json.loads(path.read_text())
    clock=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())+8
    train_styles=pair(DRILL/'train-styles',INCUMBENT,1)
    dev_styles=pair(DRILL/'development-styles',INCUMBENT,3)
    test_styles=pair(DRILL/'unopened-styles','v4-reply-g0312',5)
    spec=dict(train=train_styles,dev=dev_styles,test=test_styles,first_future_id=clock,calendar_count=32,
        response_candidates=['wtap',*RESPONSES],matches_per_test_style=256,max_scenarios_per_test_style=8192,
        selection='exploratory crossed payoffs in existing archive development games, then shared-opening qualification',
        training='128 batches of 32 legal student trajectories and referee-assigned expert button labels, then 256 PPO batches of 64',
        evidence_scope='same post-switch opponent identities, unseen opening and delay, prospective timing donors',
        labels_at_inference=False)
    write_json(path,spec,fixed=True)
    return spec

def pilot():
    spec=plan();folder=DRILL/'pilot';p=EVIDENCE/'bootstrap-sumo-seven-first/initial.net'
    freeze(folder/'main/final.net',p.read_bytes())
    return development(SEED,folder,spec)

def student(kind,until=None):
    folder=DRILL/('main' if kind=='gru' else 'mlp');spec=plan()
    reversal=json.loads((DRILL/'pilot/adaptation-development.json').read_text())['verified_reversals'][0]
    assignment={v['reference']:reversal[k] for k,v in spec['train'].items()}
    if until is not None:
        fit(folder/'demos',kind,'verified style-specific response',128,assignment,until);return
    while not (folder/'demos/trainer.pt').is_file():
        f=folder/'demos/progress.json';u=json.loads(f.read_text())['step'] if f.is_file() else 0
        governed('student',kind,u+12)
    a,b=[v['reference'] for v in spec['train'].values()]
    configure(folder,kind,kind,2139511+(kind=='mlp'),5479823,a,b,{'two styles':[a,b]},warm=folder/'demos/trainer.pt')
    train(folder,256);pin_model(folder)

def governed(*args):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,__file__,*map(str,args)],check=True)

def main():
    spec=plan()
    governed('pilot')
    result=json.loads((DRILL/'pilot/adaptation-development.json').read_text())
    if not result['eligible_for_sealed_test']:return
    for k in ('gru','mlp'):student(k)
    governed('confirm')
    if not json.loads((DRILL/'adaptation-development.json').read_text())['eligible_for_sealed_test']:return
    subprocess.run([sys.executable,HERE/'calendars_holdout.py',DRILL],check=True)
    governed('memory')

if __name__=='__main__':
    op=sys.argv[1] if len(sys.argv)>1 else 'run'
    if op=='pilot':pilot()
    elif op=='student':student(sys.argv[2],int(sys.argv[3]))
    elif op=='confirm':development(SEED,DRILL,plan())
    elif op=='memory':evaluate(SEED,DRILL)
    elif op=='run':main()
    else:raise ValueError(op)
