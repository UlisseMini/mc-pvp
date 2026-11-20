"""Persistent main-agent and best-response league with ordinary Java promotion tests."""
import argparse
import csv
import fcntl
import json
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

from report import export
from season import ANCHORS, EVIDENCE, WORK, PLAN, SRC, both_clocks, compile_sim, field_score, hash_policy, run_season, save, train_stage, unique, write_fighter


def local(path):
    return str(Path(path).relative_to(SRC))


def same(a,b):
    return hash_policy(a)==hash_policy(b)


def guest_cpu():
    samples=subprocess.check_output(["ps","-Ao","%cpu=,comm="],text=True)
    return sum(float(r.split(None,1)[0]) for r in samples.splitlines()
               if "com.apple.Virtualization.VirtualMachine" in r or "/lima/hostagent" in r)


def native_panel(tag, actors, opponents, seed, pairs):
    dest=EVIDENCE/tag;work=WORK/tag
    dest.mkdir(exist_ok=True);work.mkdir(exist_ok=True)
    if (dest/"panel.json").exists():return json.loads((dest/"panel.json").read_text())
    registration=dict(actors=[local(p) for p in actors],opponents=[local(p) for p in opponents],
                      actor_hashes=[hash_policy(p) for p in actors],opponent_hashes=[hash_policy(p) for p in opponents],
                      seed=seed,pairs=pairs,clock="ordinary TCP",rtts=[0,100])
    reg=dest/"registration.json"
    if reg.exists() and json.loads(reg.read_text())!=registration:raise ValueError(f"Registration mismatch for {tag}")
    save(reg,registration)
    cmd=[sys.executable,SRC/"native.py","--weights",actors[0],"--rtts","0","100","--pairs",str(pairs),
         "--opponents",*opponents,"--seed",str(seed),"--extra-weights",*actors[1:]]
    run=None
    for log in sorted(work.glob("native-*.log")):
        names=[s.partition(": ")[2] for s in log.read_text().splitlines() if s.startswith("Round recordings:")]
        if names and (Path(names[-1])/"results.json").exists():run=Path(names[-1])
    count=4*pairs*len(opponents)*(len(actors)+1)
    if run is None or len(json.loads((run/"results.json").read_text()))!=count:
        if run is not None:cmd.extend(["--resume",run])
        log=work/f"native-{time.time_ns()}.log"; readings=[]
        with log.open("w") as out:
            process=subprocess.Popen(cmd,stdout=out,stderr=out)
            try:
                while process.poll() is None:
                    try:process.wait(timeout=5)
                    except subprocess.TimeoutExpired:readings.append(guest_cpu())
            except BaseException:
                process.terminate();process.wait();raise
        save(work/"cpu.json",readings)
        if process.returncode:raise RuntimeError(f"Native worker failed; see {log}")
        run=Path(next(s.partition(": ")[2] for s in log.read_text().splitlines() if s.startswith("Round recordings:")))
    meta=json.loads((run/"metadata.json").read_text())
    if meta["clock"] is not None or meta["speed"]!=1 or meta["weights_sha256"]!=registration["actor_hashes"][0] or meta["seed"]!=seed:
        raise ValueError("Unexpected native conditions")
    if [a["sha256"] for a in meta["extra_weights"]]!=registration["actor_hashes"][1:] or meta["opponent_hashes"]!={str(p):hash_policy(p) for p in opponents}:
        raise ValueError("Native roster changed")
    data=export(run,dest,"ordinary")
    if len(data)!=count:raise ValueError(f"Incomplete Java panel for {tag}")
    labels=["learned" if i==0 else f"learned-{i}" for i in range(len(actors))]
    actor_names={k:local(v) for k,v in zip(labels,actors)}|{"wtap":"wtap"}
    # Match eligibility across all arms in a cell. Preserve all original scores and flags.
    bad={(r["rtt"],r["pair"],r["seat"],r["rival"]) for r in data if any(e>1e-5 for e in r["first_input_spawn_error_blocks"].values())}
    scores=[]
    for r in data:
        s=dict(actor=actor_names[r["policy"]],rival=local(r["rival"]),rtt=r["rtt"],opening=r["pair"],seat=r["seat"],
               hp=r["hp"],enemy_hp=r["opponent_hp"],score=1 if r["opponent_hp"]==0 else 0 if r["hp"]==0 else .5,
               qualified=(r["rtt"],r["pair"],r["seat"],r["rival"]) not in bad)
        scores.append(s)
    result=dict(run=run.name,scores=scores,unqualified_cells=len(bad))
    save(dest/"panel.json",result)
    return result


def native_rates(data,name,opponent=None,rtt=None):
    rr=[r for r in data if r["actor"]==name and r["qualified"] and (opponent is None or r["rival"]==opponent) and (rtt is None or r["rtt"]==rtt)]
    return (sum(r["score"] for r in rr),len(rr))


def rate(pair):
    return pair[0]/pair[1] if pair[1] else 0.


def compare_native(scores,challengers,inc):
    baseline=local(inc)
    ref=rate(native_rates(scores,baseline))
    wt=rate(native_rates(scores,"wtap"))
    eligible=[]; threats=[]
    for p in challengers:
        a=local(p)
        direct=rate(native_rates(scores,a,baseline)); overall=rate(native_rates(scores,a))
        if direct>.5:threats.append(p)
        if direct>.5 and overall>max(ref,wt):eligible.append(p)
    eligible.sort(key=lambda p:rate(native_rates(scores,local(p))),reverse=True)
    return eligible,threats


def native_scout(index, jar, work, dest, candidates, inc, base, general, direct):
    """Select a response from fresh ordinary-Java development fights before promotion."""
    field=unique([inc,*ANCHORS])
    parents=[inc] if inc.read_bytes()[:4]==b"SWT1" else [p for p in field if p.read_bytes()[:4]==b"SWT1"]
    refinement=train_stage(jar,work,"incumbent-descendants","Refine",80,base+310,
                           [inc]*8+field,parents,clock="mix")
    descendants=[p for p in sorted(refinement.glob("gen-*.net")) if not same(p,inc)]
    versions=unique([*candidates,*descendants])
    calibrated=both_clocks(jar,work,"scout-selection",[*versions,"wtap"],field,base+311,48,True)
    def robust_direct(p):
        return min(calibrated[str(p),str(inc)])
    available=[p for p in versions if not same(p,inc)]
    robust=max(available,key=robust_direct)
    representatives={hash_policy(p):p for p in versions}
    distinct_descendants=unique([representatives[hash_policy(p)] for p in descendants])
    near=max(distinct_descendants,key=robust_direct) if distinct_descendants else robust
    shortlist=unique([general,direct,robust,near])
    by_score=sorted(available,key=robust_direct,reverse=True)
    shortlist=unique([*shortlist,*by_score])[:4]
    shortlist=[write_fighter(p) for p in shortlist]
    pilot=native_panel(f"scout-{index:03d}",[*shortlist,inc],[inc],base+312,1)
    rows=pilot["scores"]
    def score(p):
        subset=[r for r in rows if r["actor"]==local(p) and r["qualified"]]
        if not subset:return (0.,-20.)
        return statistics.mean(r["score"] for r in subset),statistics.mean(r["hp"]-r["enemy_hp"] for r in subset)
    winner=max(shortlist,key=score)
    save(dest/"scout.json",dict(entrants={local(p):score(p) for p in [*shortlist,inc]},selected=local(winner),
                               wtap=native_rates(rows,"wtap"),run=pilot["run"]))
    return winner


def native_wave(index,main,inc,pool,history,final):
    tag=f"java-{index:03d}"
    dst=EVIDENCE/tag;dst.mkdir(exist_ok=True)
    decision=dst/"decision.json"
    if decision.exists():return json.loads(decision.read_text())
    wa=WORK/tag;wa.mkdir(exist_ok=True)
    candidates=unique([main,*[SRC/s[k] for s in history for k in ("main","best","counter","native_counter")]])
    if final:
        candidates=unique([*candidates,*pool])
    candidates=[p for p in candidates if not same(p,inc)]
    if not candidates:
        candidates=[p for p in pool if not same(p,inc)]
    jar=compile_sim();base=PLAN["seed"]+index*6011+10000000
    merits=both_clocks(jar,wa,"nomination",[*candidates,inc,"wtap"],unique([inc,*ANCHORS,*pool]),base,64,index>=4)
    opponents=unique([inc,ANCHORS[2],ANCHORS[0]])
    if len(opponents)<2:opponents.append(ANCHORS[1])
    # A broad entrant and a champion-specific entrant can each qualify.
    def overall(p):
        values=[field_score(merits,p,b) for b in unique([inc,*ANCHORS,*pool])]
        return .75*statistics.mean(values)+.25*min(values)
    general=max(candidates,key=overall)
    direct=max(candidates,key=lambda p:field_score(merits,p,inc))
    choices=unique([general,direct])
    if index>=10 and (final or index>=15):
        pilot_winner=native_scout(index,jar,wa,dst,candidates,inc,base,general,direct)
        choices=unique([*choices,pilot_winner])
    if final:
        verified=[]
        for record in sorted(EVIDENCE.glob("java-*/decision.json")):
            old=json.loads(record.read_text())
            if same(SRC/old["old_champion"],inc):
                verified.extend(SRC/p for p in old["credible_counters"])
        choices=unique([*choices,*verified])
        opponents=unique([*opponents,*verified])
        fresh_dir=train_stage(jar,wa,"outside","League",360,base+4811,[])
        outsider=write_fighter(sorted(fresh_dir.glob("gen-*.net"))[-1])
        opponents.append(outsider)
    choices=[write_fighter(p) for p in choices]
    actors=[*choices,inc]
    save(dst/"nomination.json",dict(general=local(choices[0]),challengers=[local(p) for p in choices],incumbent=local(inc),
                                     model_direct={local(p):field_score(merits,p,inc) for p in [general,direct,inc]},
                                     model_wtap_direct=field_score(merits,"wtap",inc),final_challenge=final))
    panels=[native_panel(tag+"-a",actors,opponents,base+1,2)]
    data=panels[0]["scores"];eligible,threats=compare_native(data,choices,inc)
    uncertain=any(rate(native_rates(data,local(p),local(inc)))<.75 or min(rate(native_rates(data,local(p),local(inc),rtt=r)) for r in (0,100))<.5 for p in eligible)
    # Confirm a potential specialist failure even when it misses the broader promotion gate.
    if (eligible and uncertain) or (threats and not eligible) or panels[0]["unqualified_cells"] or final:
        panels.append(native_panel(tag+"-b",actors,opponents,base+2,2))
        data=data+panels[1]["scores"]
        eligible,threats=compare_native(data,choices,inc)
    winner=eligible[0] if eligible else inc
    outcome=dict(champion=local(winner),old_champion=local(inc),promoted=not same(winner,inc),
                 credible_counters=[local(p) for p in threats if rate(native_rates(data,local(p),local(inc)))>=.60],
                 native_direct={local(p):native_rates(data,local(p),local(inc)) for p in actors},
                 native_overall={local(p):native_rates(data,local(p)) for p in actors},
                 wtap_direct=native_rates(data,"wtap",local(inc)),wtap_overall=native_rates(data,"wtap"),
                 panels=[x["run"] for x in panels],final=final)
    save(decision,outcome)
    return outcome


def run():
    state_file=EVIDENCE/"state.json"
    state=json.loads(state_file.read_text()) if state_file.exists() else dict(season=0,incumbent=local(ANCHORS[0]),main=local(ANCHORS[0]),
        pool=list(map(local,ANCHORS)),stale=0,last_java=0,history=[],promotions=[],counter_signatures=[],status="running")
    while state["status"]=="running":
        i=state["season"]+1;final=state["stale"]>=3
        inc=SRC/state["incumbent"];main=SRC/state["main"];pool=unique([SRC/p for p in state["pool"]])
        s=run_season(i,inc,main,pool,final)
        changed=s["advanced"] or s["new_counter"]
        state["stale"]=0 if changed else state["stale"]+1
        state["season"]=i;state["main"]=s["main"];state["history"].append(i)
        pool=unique([*pool,*[SRC/s[k] for k in ("main","best","counter","native_counter")]])
        print(f"Season {i}: advanced={s['advanced']} new counter={s['new_counter']} consecutive flat={state['stale']}",flush=True)
        due=(i==3 or (i-state["last_java"]>=6) or (final and not changed))
        if due:
            hist=[json.loads((EVIDENCE/("season-%03d"%n)/"result.json").read_text()) for n in state["history"] if n>state["last_java"]]
            outcome=native_wave(i,SRC/state["main"],inc,pool,hist,final and not changed)
            state["last_java"]=i
            print("Native result",json.dumps(outcome),flush=True)
            if outcome["promoted"]:
                state["incumbent"]=outcome["champion"];state["main"]=outcome["champion"];state["stale"]=0
                state["promotions"].append(i);state["counter_signatures"]=[]
                pending=EVIDENCE/"champion.writing"
                shutil.copyfile(SRC/outcome["champion"],pending);pending.replace(EVIDENCE/"champion.net")
            elif outcome["credible_counters"]:
                new=[p for p in outcome["credible_counters"] if p not in state["counter_signatures"]]
                if new:
                    state["counter_signatures"].extend(new);state["stale"]=0
                    pool=unique([*pool,*[SRC/p for p in new]])
                elif final and not changed:
                    state["status"]="plateau_with_known_counter"
            elif final and not changed:state["status"]="plateau"
            if any("sword26 Running" in l for l in subprocess.check_output(["limactl","list","-f","{{.Name}} {{.Status}}"],text=True).splitlines()):
                idle=subprocess.run(["limactl","shell","--workdir","/tmp","sword26","pgrep","-x","java"],stdout=subprocess.DEVNULL)
                if idle.returncode==1:subprocess.run(["limactl","stop","sword26"],stdout=subprocess.DEVNULL,check=True)
        state["pool"]=[local(p) for p in pool]
        save(state_file,state)
    print(state["status"],flush=True)


if __name__=="__main__":
    WORK.mkdir(parents=True,exist_ok=True)
    with (WORK/"coordinator.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        run()
