"""Single-match PPO. Clipped surrogate and recurrent minibatching adapted from CleanRL
ppo_atari_lstm.py at fe8d8a03c41a7ef5b523e2e354bd01c363e786bb (see CleanRL-LICENSE).
Java collects trajectories, PyTorch autograd differentiates complete episodes from spawn.
"""
import base64
import hashlib
import io
import json
import math
import os
import random
import struct
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from torch.nn.utils.rnn import pack_sequence, PackedSequence

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BUILD = ROOT / "build/elite-league/single-sumo"
EVIDENCE = HERE / "evidence"
INCUMBENT = "v4-ppo-g0496"
BASE_COUNTER = "sumo-seven-first"
RTTS = [(31,31),(109,109),(31,109),(109,31)]
FIELD_PATH = HERE.parent / "sumo-inputs/archive-field.tsv"
CLOCK_PATH = HERE.parent / "ping-ppo/clocks.tsv"
FIELD = [s.split("\t")[0] for s in FIELD_PATH.read_text().splitlines()]
CLOCKS = {k: [r.split("\t")[1] for r in CLOCK_PATH.read_text().splitlines() if r.split("\t")[0] == k] for k in ("train","dev")}
FAMILIES = {"baseline":["wtap","hold"], "click-phase":["legacy-wtap-clickphase"],
            "frozen champion":[INCUMBENT], "7 CPS responses":[BASE_COUNTER,"sumo-seven-ppo"],
            "20 CPS responses":["sumo-frozen-ppo","sumo-cap-ramp"], "input studies":["sumo-vote-final","sumo-own-ping","sumo-two-ping"],
            "recurrent archive":["v3-link-fixed-63","v3-memory-27","population-01"], "style switches":["shift-train-07","shift-check-v4-4"],
            "trade and sprint":["mmc13-trade-2-fit","mmc13-actor-2-fit","v4-sprint-link-jitter-15","v4-reply-g0312"]}
assert sorted(FIELD)==sorted(n for ns in FAMILIES.values() for n in ns)
torch.set_num_threads(1)
torch.set_num_interop_threads(1)

def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as f:
        f.write(data); f.flush(); os.fsync(f.fileno())
    os.replace(f.name,path)

def freeze(path, data):
    if path.exists():
        if path.read_bytes()!=data: raise ValueError(f"Frozen data changed at {path}")
    else: atomic(path,data)

def write_json(path, value, fixed=False):
    (freeze if fixed else atomic)(path,(json.dumps(value,sort_keys=True,indent=2,allow_nan=False)+"\n").encode())

class Actor(nn.Module):
    def __init__(self, kind):
        super().__init__()
        self.kind=kind
        width=32 if kind=="gru" else 180
        if kind=="gru":
            self.encoder=nn.GRU(25,width)
            self.encoder.bias_hh_l0.requires_grad_(False)
            for gate in self.encoder.weight_hh_l0.chunk(3): nn.init.orthogonal_(gate)
            for gate in self.encoder.weight_ih_l0.chunk(3): nn.init.xavier_uniform_(gate)
            nn.init.zeros_(self.encoder.bias_hh_l0); nn.init.zeros_(self.encoder.bias_ih_l0)
        elif kind=="mlp":
            self.encoder=nn.Linear(25,width)
            nn.init.orthogonal_(self.encoder.weight); nn.init.zeros_(self.encoder.bias)
        else: raise ValueError(kind)
        self.head=nn.Linear(width,6)
        nn.init.orthogonal_(self.head.weight[:5],.01)
        nn.init.orthogonal_(self.head.weight[5:],1.0); nn.init.zeros_(self.head.bias)
        assert sum(p.numel() for p in self.parameters() if p.requires_grad)==5766

    def sequences(self, packed):
        if self.kind=="gru": h=self.encoder(packed)[0].data
        else: h=F.softsign(self.encoder(packed.data))
        return self.head(h)

    def export(self, path):
        enc=self.encoder
        params=([enc.weight_ih_l0,enc.weight_hh_l0,enc.bias_ih_l0] if self.kind=="gru" else [enc.weight,enc.bias])
        weights=np.concatenate([v.detach().numpy().ravel() for v in [*params,self.head.weight,self.head.bias]])
        prefix=b"GRU32." if self.kind=="gru" else b"LEAGUE_MLP180."
        atomic(path,prefix+base64.b64encode(weights.astype(">f4").tobytes())+b"\n")

def ordinal_logit(x, low, high, choice):
    # Stable log of the interval between two logistic CDF thresholds.
    a=(low-x)/.25; b=(x-high)/.25
    middle=F.logsigmoid(-a)+F.logsigmoid(-b)+math.log(-math.expm1((low-high)/.25))
    return torch.where(choice<0,F.logsigmoid(a),torch.where(choice>0,F.logsigmoid(b),middle))

def log_prob(y, ground, action):
    key=action%4; side=(action//4)%3-1
    walk=torch.where(key==3,-1,(key!=0).long())
    sprint=F.logsigmoid(torch.where(key==2,y[:,1],-y[:,1])/.25)
    click=F.logsigmoid(torch.where((action//12)%2==1,y[:,2],-y[:,2])/.25)
    jump=F.logsigmoid(torch.where(action>=24,y[:,3],-y[:,3])/.25)
    return ordinal_logit(y[:,0],-.65,0,walk)+torch.where(walk==1,sprint,0.)+ordinal_logit(y[:,4],-.45,.45,side)+click+torch.where(ground,jump,0.)

class Worker:
    def __init__(self,cps=7,current_runtime=False,runtime_directory=None):
        if cps not in (7,20):raise ValueError(cps)
        self.tmp=tempfile.TemporaryDirectory(dir=BUILD); self.folder=Path(self.tmp.name)
        from build import sim_paths
        cp=str(runtime_directory) if runtime_directory else os.pathsep.join(map(str,sim_paths(training=not current_runtime)))
        self.proc=subprocess.Popen(["java","-ea",f"-Dleague.cps={cps}","-XX:ActiveProcessorCount=2","-XX:+UseSerialGC","-Xms128m","-Xmx1400m","-cp",cp,"LeagueWorker","2"],
                                   cwd=ROOT,text=True,stdin=subprocess.PIPE,stdout=subprocess.PIPE)
    def run(self, schedule, collect):
        plan=self.folder/"plan.tsv"; out=self.folder/"results.bin"
        plan.write_text("".join("\t".join(map(str,r))+"\n" for r in schedule))
        self.proc.stdin.write(f"{'collect' if collect else 'eval'}\t{plan}\t{out}\n"); self.proc.stdin.flush()
        if self.proc.stdout.readline().strip()!="OK":raise RuntimeError(f"Java roller exited {self.proc.poll()}")
        data=memoryview(out.read_bytes()); count,=struct.unpack_from(">i",data); pos=4; results=[]
        if count!=len(schedule): raise ValueError("Incomplete rollout")
        for _ in range(count):
            reward,time,heads=struct.unpack_from(">ffi",data,pos);pos+=12
            rows=np.frombuffer(data[pos:pos+heads*128],">f4").astype(np.float32).reshape(heads,32);pos+=heads*128
            results.append((reward,time,rows))
        if pos!=len(data):raise ValueError("Trailing rollout data")
        return results
    def close(self):
        self.proc.stdin.close()
        if self.proc.wait()!=0:raise RuntimeError("Java worker failed")
        self.tmp.cleanup()

def training_schedule(config, weights, step):
    rng=random.Random(config["draw_seed"]+step*1009)
    action_rng=random.Random(config["action_seed"]+step*2027)
    families=config["families"]; scores=config.get("scores",{})
    ps=[(1-scores.get(k,.5))**2+.05 for k in families]
    options=[i for i in range(151) if i not in (31,109)]
    result=[]
    for _ in range(64):
        if config["role"]=="exploiter": foe=config["incumbent"]
        else:
            u=rng.random()
            if u<.25:foe=config["incumbent"]
            elif u<.50:foe=config["counter"]
            else:
                family=rng.choice(list(families)) if rng.random()<.5 else rng.choices(list(families),weights=ps)[0]
                foe=rng.choice(families[family])
        result.append((weights,foe,rng.choice(CLOCKS["train"]),rng.choice("ab"),rng.choice(options),rng.choice(options),action_rng.getrandbits(60),rng.getrandbits(60)))
    return result

def prepare(rollouts):
    episodes=[]
    for reward,_,array in rollouts:
        data=torch.from_numpy(array);v=array[:,31].astype(np.float64)
        delta=np.empty_like(v);delta[:-1]=v[1:]-v[:-1];delta[-1]=2*reward-1-v[-1]
        from scipy.signal import lfilter
        adv=lfilter([1.],[1.,-.97],delta[::-1])[::-1].copy()
        advantages=torch.tensor(adv,dtype=torch.float32)
        action=data[:,25].long();logp=log_prob(data[:,26:31],data[:,8]>0,action)
        episodes.append(torch.cat((data[:,:26],logp[:,None],advantages[:,None],(advantages+data[:,31])[:,None],data[:,26:32]),dim=1))
    flat=torch.cat([e[:,27] for e in episodes]);mean=flat.mean();sd=flat.std(unbiased=False).clamp_min(1e-8)
    for e in episodes:e[:,27]=(e[:,27]-mean)/sd
    return episodes

def train_update(net,opt,episodes,seed,conditioned=False):
    rng=random.Random(seed);inds=list(range(len(episodes)));metrics=[];max_error=0.
    for epoch in range(3):
        rng.shuffle(inds)
        for start in range(0,len(inds),8):
            seq=pack_sequence([episodes[i] for i in inds[start:start+8]],enforce_sorted=False)
            d=seq.data; x=PackedSequence(d[:,:25],seq.batch_sizes,seq.sorted_indices,seq.unsorted_indices)
            y=net.sequences(x,d[:,35].long()) if conditioned else net.sequences(x)
            if epoch==0 and start==0:
                max_error=float((y.detach()-d[:,29:35]).abs().max())
                if max_error>.001:raise ValueError(f"Java/PyTorch on-policy disagreement {max_error}")
            logratio=log_prob(y,d[:,8]>0,d[:,25].long())-d[:,26]
            ratio=logratio.exp();adv=d[:,27]
            pg_loss=torch.maximum(-adv*ratio,-adv*ratio.clamp(.82,1.18)).mean()
            value_loss=F.mse_loss(y[:,5],d[:,28])
            loss=pg_loss+.5*value_loss
            opt.zero_grad(); loss.backward()
            norm=nn.utils.clip_grad_norm_(net.parameters(),.6,error_if_nonfinite=True)
            opt.step()
            with torch.no_grad():
                metrics.append([float(loss),float(((ratio-1)-logratio).mean()),float(((ratio-1).abs()>.18).float().mean()),float(norm)])
    return dict(zip(("loss","kl","clip_fraction","grad_norm"),np.mean(metrics,axis=0).tolist()),parity_max=max_error)
