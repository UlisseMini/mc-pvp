"""Two policies share a learned opening; change only the post-hit W reentry threshold."""
from learner import *
from league_tournament import historical
def decode(path):
    kind,raw=path.read_text().strip().split('.',1)
    return kind,np.frombuffer(base64.b64decode(raw),'>f4').astype(np.float32)
def build(folder,starter,fast,slow,delay):
    paths={s[0]:ROOT/s[1] for r in FIELD_PATH.read_text().splitlines() if len(s:=r.split('\t'))>1}
    kind,base=decode(paths[starter]);assert kind=='MLP'
    _,example=decode(paths['shift-train-07'])
    header=8;gate=37;block=(len(example)-header-gate)//2
    assert header+gate+2*block==len(example) and block>=len(base)
    def variant(ms):
        v=base.copy()
        # The last hidden unit is monotone in the age of the most recent local incoming hurt.
        v[7*23:8*23]=0;v[7*23]=1-ms/300;v[7*23+11]=-1
        # Zero threshold separates W from release. Keep the negative range above the back-key threshold.
        v[184:193]=0;v[192]=.6
        return v
    early=variant(fast);late=variant(slow)
    result={}
    for label,post in [('early',early),('late',late)]:
        w=np.zeros_like(example);w[:8]=[1,0,0,1,delay,0,1,0]
        start=header+gate;w[start:start+len(early)]=early;w[start+block:start+block+len(post)]=post
        data=b'PAIR.'+base64.b64encode(w.astype('>f4').tobytes())+b'\n'
        p=folder/(label+'.net');freeze(p,data)
        result[label]=dict(reference=str(p.relative_to(ROOT)),sha256=hashlib.sha256(data).hexdigest())
    return result

def freeze_assay(gen,folder):
    spec=folder/'assay.json'
    if spec.exists():return json.loads(spec.read_text())
    latest=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())
    # Sealed versions share the manipulation, use different thresholds, switch lag and backbone.
    fast=(29,39,19,34);slow=(215,245,270,230)
    backbones=['v4-sprint-link-jitter-15','mmc13-actor-2-fit','sumo-own-ping','v4-reply-g0312']
    k=(gen-1)%len(fast)
    test=build(folder/'unopened-styles',backbones[k],fast[k]+gen//4,slow[k]-gen//4,1+gen%4)
    dev=build(folder/'development-styles',INCUMBENT,50,180,0)
    data=dict(generation=gen,test=test,dev=dev,first_future_id=latest+4,calendar_count=32,
              dev_fast_ms=50,dev_slow_ms=180,test_fast_ms=fast[k]+gen//4,test_slow_ms=slow[k]-gen//4,
              capture_rule='first 32 eligible consecutive bot-bot sumo calendars with complete input, movement and server streams; no outcome filter',
              matches_per_test_style=256,match_tolerance=[1000,100,100,.25,.1,.1],
              max_scenarios_per_test_style=8192,student_control='same-generation equal-parameter MLP',
              pre_cue_control='opening identical until opponent receives hurt; independently assigned style',
              response_candidates=['wtap','hold',INCUMBENT,BASE_COUNTER,'sumo-seven-ppo'])
    write_json(spec,data,fixed=True);return data
