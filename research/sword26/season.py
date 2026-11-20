"""Train one season of a persistent sword league. Selection uses learned opponents and fight outcomes."""
import argparse
import csv
import hashlib
import json
import math
import random
import shutil
import statistics
import subprocess
import time
from collections import defaultdict
from pathlib import Path

from setup import SRC, BUILD, compile_sim, jdk

EVIDENCE = SRC / "evidence/ladder26"
WORK = BUILD / "ladder26"
AIM_PROFILE = "perfect"
PLAN = json.loads((EVIDENCE / "protocol.json").read_text())
ANCHORS = [SRC / p for p in PLAN["anchors"]]


def save(path, value):
    tmp = path.with_suffix(".writing")
    tmp.write_text(json.dumps(value, indent=2) + "\n")
    tmp.replace(path)


def hash_policy(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def unique(paths):
    found = {}
    for path in paths:
        found.setdefault(hash_policy(path), Path(path).resolve())
    return list(found.values())


def lines(path, values):
    path.write_text("".join(str(p) + "\n" for p in values))
    return path


def queued_clock(work):
    settings=EVIDENCE/"perfect-aim/server-queue-training.json"
    group,_,suffix=work.name.partition("-")
    if group not in ("season","java") or not suffix.isdigit() or not settings.exists():
        return False
    return int(suffix)>=json.loads(settings.read_text())["first_season"]


def java(jar, work, name, args, output=None, clock="off"):
    cmd = ["/usr/bin/nice","-n","12",jdk()/"java","-Xmx768m","-XX:+UseSerialGC","-XX:ActiveProcessorCount=4",
           "-XX:CICompilerCount=2","-Dsword.transport=lockstep","-Dsword.aimProfile="+AIM_PROFILE,"-Dsword.phaseClock="+clock,
           "-Dsword.serverPacketBatch="+str(clock!="off" and queued_clock(work)).lower(),"-cp",jar,*args]
    target = output or work/(name+".log")
    temp = target.with_suffix(".running")
    started = time.monotonic()
    with temp.open("w") as out:
        process = subprocess.Popen(cmd, stdout=out, stderr=None)
        measurements = []
        try:
            while process.poll() is None:
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    sample = subprocess.run(["ps","-o","%cpu=","-p",str(process.pid)],capture_output=True,text=True)
                    if sample.stdout.strip():
                        measurements.append(float(sample.stdout))
        except BaseException:
            process.terminate();process.wait()
            raise
        if process.returncode:
            raise RuntimeError(f"{name} stopped with {process.returncode}, see {temp}")
    temp.replace(target)
    with (work/"resources.jsonl").open("a") as f:
        f.write(json.dumps(dict(stage=name,seconds=time.monotonic()-started,maximum_core_percent=max(measurements,default=None),
                                mean_core_percent=statistics.mean(measurements) if measurements else None))+"\n")


def matrix(jar, work, label, actors, rivals, seed, pairs, clock="off"):
    destination = work/(label+".tsv")
    if not destination.exists():
        a=lines(work/(label+"-actors.txt"),actors)
        b=lines(work/(label+"-rivals.txt"),rivals)
        java(jar,work,label,["sword.CrossPlay",a,b,str(seed),str(pairs),"4"],destination,clock)
    data=defaultdict(list)
    with destination.open() as f:
        for r in csv.DictReader(f,delimiter="\t"):
            w,l,d=(int(r[k]) for k in ("wins","losses","draws"))
            data[(r["actor"],r["rival"])].append((w+d*.5)/(w+l+d))
    if set(data)!={(str(a),str(b)) for a in actors for b in rivals} or any(len(v)!=2 for v in data.values()):
        raise ValueError(f"Unexpected or incomplete matchup matrix {destination}")
    return data


def both_clocks(jar,work,label,actors,rivals,seed,pairs,enabled):
    fixed=matrix(jar,work,label,actors,rivals,seed,pairs)
    if not enabled:return fixed
    phase=matrix(jar,work,label+"-phase",actors,rivals,seed,pairs,"on")
    return {k:fixed[k]+phase[k] for k in fixed}


def uniform_positive(regrets):
    vals=[max(0,x) for x in regrets]
    norm=sum(vals)
    return [v/norm for v in vals] if norm else [1/len(vals)]*len(vals)


def opponent_mix(data,pool,main):
    # Regret matching on the symmetric, zero-sum, seat-balanced empirical game.
    pay=[[statistics.mean(data[str(a),str(b)])-statistics.mean(data[str(b),str(a)]) for b in pool] for a in pool]
    n=len(pool); regrets=[0.]*n; accumulated=[0.]*n
    for _ in range(600):
        weight=uniform_positive(regrets)
        for i in range(n):
            accumulated[i]+=weight[i]
            regrets[i]+=sum(v*w for v,w in zip(pay[i],weight))
    hardest=[(1-statistics.mean(data[str(main),str(p)]))**2+.01 for p in pool]
    return [.50*v/600+.30*h/sum(hardest)+.20/n for v,h in zip(accumulated,hardest)]


def breadth(data,policy,rivals):
    cells=[statistics.mean(data[str(policy),str(b)]) for b in rivals]
    tail=sorted(cells)[:max(1,math.ceil(len(cells)/4))]
    return .75*statistics.mean(cells)+.25*statistics.mean(tail)


def field_score(data,a,b):
    return statistics.mean(data[str(a),str(b)])


def write_fighter(source):
    target=EVIDENCE/"fighters"/(hash_policy(source)[:16]+".net")
    target.parent.mkdir(exist_ok=True)
    if not target.exists():
        tmp=target.with_suffix(".writing");shutil.copyfile(source,tmp);tmp.replace(target)
    if hash_policy(target)!=hash_policy(source):raise ValueError("Archive digest mismatch")
    return target


def checkpoints(directory,limit):
    files=sorted(directory.glob("gen-*.net"))
    if not files:return []
    return list(dict.fromkeys(files[round(i*(len(files)-1)/(limit-1))] for i in range(limit)))


def train_stage(jar,work,role,entry,gens,seed,rivals,parents=None,clock="off"):
    out=work/role
    if out.exists() and (out/("gen-%04d.net"%(gens-1))).exists():return out
    if out.exists():out.rename(work/(role+"-partial-"+str(time.time_ns())))
    args=[]
    if parents:
        args.append("-Dsword.parentFile="+str(lines(work/(role+"-parents.txt"),parents)))
    args += ["sword."+entry,out,str(gens),str(seed)]
    if entry=="League":args.append("4")
    args+=rivals
    java(jar,work,role,args,clock=clock)
    return out


def run_season(index,native,main,pool,final=False,native_threats=()):
    work=WORK/("season-%03d"%index);work.mkdir(parents=True,exist_ok=True)
    dest=EVIDENCE/("season-%03d"%index);dest.mkdir(exist_ok=True)
    if (dest/"result.json").exists():return json.loads((dest/"result.json").read_text())
    jar=compile_sim()
    seed=PLAN["seed"]+index*7919
    rng=random.Random(seed);pool=unique([*pool,main,native]);main=next(p for p in pool if hash_policy(p)==hash_policy(main))
    native=next(p for p in pool if hash_policy(p)==hash_policy(native))
    timing=index>=4
    previous=both_clocks(jar,work,"payoffs",pool,pool,seed+10,24,timing)
    weights=opponent_mix(previous,pool,main)
    # Stratified sampling preserves minority opponents in small training fields.
    quantiles=[(i+rng.random())/36 for i in range(36)]
    cumulative=[];s=0
    for w in weights:s+=w;cumulative.append(s)
    mixture=[pool[next((j for j,v in enumerate(cumulative) if v>=u),len(pool)-1)] for u in quantiles]
    by_digest={hash_policy(p):p for p in pool}
    verified=[by_digest[hash_policy(p)] for p in unique(native_threats)]
    replies=[verified[i%len(verified)] for i in range(12)] if verified else []
    rivals=[main,native,*mixture,*replies];parents=[p for p in unique([main,native,*pool]) if p.read_bytes()[:4]==b"SWT1"]
    rng.shuffle(parents)
    gens=320 if final else 144
    main_out=train_stage(jar,work,"main","Refine",gens,seed+1,rivals,parents)
    target=[main]*15+[native]*8+rng.choices(pool,weights=weights,k=3)
    counter=train_stage(jar,work,"counter","Refine",gens,seed+2,target,parents)
    entrant=train_stage(jar,work,"fresh","League",gens*2,seed+3,[main]*5+[native]*3+mixture[::3])
    branch=work/"branches"
    branches_done=work/"branches.done"
    if not branches_done.exists():
        if branch.exists():branch.rename(work/("branches-partial-"+str(time.time_ns())))
        branch_parent=main if main.read_bytes()[:4]==b"SWT1" else max(parents,key=lambda p:breadth(previous,p,pool))
        java(jar,work,"branches",["sword.BranchImprove",branch,branch_parent,lines(work/"branch-field.txt",rivals),
                                  str(seed+4),str(2048 if final else 768),"4"])
        branches_done.touch()
    proposals=checkpoints(main_out,15)+checkpoints(counter,15)+checkpoints(entrant,15)+sorted(branch.glob("head-*.net"))
    if timing:
        mixed=train_stage(jar,work,"variable-timing","Refine",gens,seed+301,rivals,parents,clock="mix")
        proposals+=checkpoints(mixed,18)
    if index==1:
        starters=[p for p in ANCHORS if p.read_bytes()[:4]==b"SWT1"]
        opening=train_stage(jar,WORK,"opening-counter","Refine",264,PLAN["seed"],
                            [ANCHORS[0]]*12+[ANCHORS[1],ANCHORS[2]],starters)
        proposals+=checkpoints(opening,18)
    if final or index%3==0:
        neural=train_stage(jar,work,"recurrent","PolicyGradient",480 if final else 192,seed+5,[*mixture,main,native,SRC/"evidence/neural26/fighter.net"])
        proposals+=checkpoints(neural,12)
    # Score copies together once and use the first representative for each byte string.
    known={hash_policy(p):p for p in pool}
    actors=unique([main,native,*[known.get(hash_policy(p),p) for p in proposals]])
    rivals=unique(pool)
    sel=both_clocks(jar,work,"selection",actors,rivals,seed+11,24,timing)
    choice=unique([*sorted(actors,key=lambda p:breadth(sel,p,rivals),reverse=True)[:4],
                   *sorted(actors,key=lambda p:field_score(sel,p,main),reverse=True)[:3],
                   *sorted(actors,key=lambda p:field_score(sel,p,native),reverse=True)[:3]])
    if verified:
        def coverage(data,p):
            return min(field_score(data,p,rival) for rival in unique([native,*verified]))
        choice=unique([*choice,*sorted(actors,key=lambda p:coverage(sel,p),reverse=True)[:3]])
    sparse=index>=14
    if sparse:
        check_actors=unique([main,native,*choice])
        check=both_clocks(jar,work,"confirmation",[*check_actors,"wtap"],rivals,seed+12,96,timing)
        missing=[p for p in rivals if p not in check_actors]
        if missing:
            check.update(both_clocks(jar,work,"archive-replies",missing,[main],seed+12,96,timing))
    else:
        check=both_clocks(jar,work,"confirmation",[*unique([*choice,*rivals]),"wtap"],rivals,seed+12,96,timing)
    ordered=sorted(choice,key=lambda p:breadth(check,p,rivals),reverse=True)
    best=ordered[0]
    advancing=[p for p in ordered if breadth(check,p,rivals)>breadth(check,main,rivals)+.012
               and field_score(check,p,main)>.515]
    successor=advancing[0] if advancing else main
    counter=max(choice,key=lambda p:field_score(check,p,main))
    native_counter=max(choice,key=lambda p:field_score(check,p,native))
    past=max(field_score(check,p,main) for p in rivals)
    new_counter=field_score(check,counter,main)>=max(.55,past+.025)
    defender=max(choice,key=lambda p:coverage(check,p)) if verified else None
    candidates=[]
    for p in unique([best,successor,counter,native_counter]+([defender] if defender else [])):
        arc=write_fighter(p)
        candidates.append(dict(file=str(arc.relative_to(SRC)),source=str(p.relative_to(BUILD) if p.is_relative_to(BUILD) else p.relative_to(SRC)),
                               sha256=hash_policy(p),breadth=breadth(check,p,rivals),against_main=field_score(check,p,main),
                               against_native=field_score(check,p,native)))
    stored=lambda p:str(write_fighter(p).relative_to(SRC))
    result=dict(index=index,aim_profile=AIM_PROFILE,final_search=final,seed=seed,entry_main=stored(main),entry_native=stored(native),main=stored(successor),
                best=stored(best),counter=stored(counter),native_counter=stored(native_counter),
                advanced=hash_policy(successor)!=hash_policy(main),new_counter=new_counter,previous_best_counter=past,
                main_breadth=breadth(check,main,rivals),wtap_breadth=breadth(check,"wtap",rivals),
                wtap_vs_main=field_score(check,"wtap",main),wtap_vs_native=field_score(check,"wtap",native),
                finalists=candidates,opponent_mix={str(p.relative_to(SRC)):w for p,w in zip(pool,weights)})
    if queued_clock(work):
        result["phase_packet_processing"]="before_server_tick"
    if verified:
        result["native_threats"]=[str(p.relative_to(SRC)) for p in verified]
        result["defender"]=stored(defender)
    files=["payoffs.tsv","confirmation.tsv","resources.jsonl"]
    if timing:files+=["payoffs-phase.tsv","confirmation-phase.tsv"]
    if sparse:files.extend(f.name for f in work.glob("archive-replies*.tsv"))
    for f in files:shutil.copyfile(work/f,dest/f)
    save(dest/"result.json",result)
    return result


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("index",type=int)
    parser.add_argument("--main",type=Path,default=ANCHORS[0])
    parser.add_argument("--native",type=Path,default=ANCHORS[0])
    parser.add_argument("--pool",type=Path,nargs="*",default=ANCHORS)
    parser.add_argument("--final",action="store_true")
    a=parser.parse_args()
    print(json.dumps(run_season(a.index,a.native,a.main,a.pool,a.final),indent=2))
