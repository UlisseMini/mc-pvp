"""Read-only analysis of referee events and replay keys from finished real games."""
import csv
import json
import statistics
from collections import Counter
from pathlib import Path
import sys
from spacing import measure


def rows(path):
    with path.open() as f:return list(csv.DictReader(f,delimiter="\t"))


def movement(keys):
    full=[k for k in keys if float(k["charge"])==1]
    recovering=[k for k in keys if float(k["charge"])<.5]
    return {"observation_ticks":len(keys),
            "forward_ticks":sum(k["forward"]=="1" for k in keys),
            "back_ticks":sum(k["forward"]=="-1" for k in keys),
            "lateral_ticks":sum(k["side"]!="0" for k in keys),
            "jump_key_ticks":sum(k["jump"]=="true" for k in keys),
            "full_charge_ticks":len(full),"full_charge_forward_ticks":sum(k["forward"]=="1" for k in full),
            "low_charge_ticks":len(recovering),"low_charge_back_ticks":sum(k["forward"]=="-1" for k in recovering)}


def classify_contacts(events):
    last_fresh={}
    for event in events:
        target=event["defender"]
        tick=int(event["tick"])
        if "preDamageCooldown" in event:
            fresh=int(event["preDamageCooldown"])<=10
        else:
            # Older files logged the entity invulnerability field. A fresh player
            # hit sets the native hurt animation to ten. Upgrades leave it ticking.
            fresh=int(event["hurt"])==10
            if fresh and tick-last_fresh.get(target,-100)<=1:
                raise ValueError("Historical same-tick hurt animation cannot distinguish an upgrade")
        event["fresh"]=fresh
        if fresh:last_fresh[target]=tick


def count_events(events):
    counts=Counter()
    for event in events:
        for trait in ("critical","boosted","strong","sweep"):
            counts[trait]+=event[trait]=="true"
        counts["fresh"]+=event["fresh"]
        counts["upgrade"]+=not event["fresh"]
    return counts


def survival(events, index, name, end_tick):
    entry=int(events[index]["tick"])
    results={}
    for active in (False,True):
        cycles=0;last=entry;end=end_tick;reason="round_end"
        for hit in events[index+1:]:
            tick=int(hit["tick"])
            if active and tick-last>22:
                end=last+23;reason="inactivity";break
            if hit["attacker"]!=name:
                end=tick;reason="return";break
            if hit["fresh"]:
                cycles+=1;last=tick
        if active and reason=="round_end" and end-last>22:
            end=last+23;reason="inactivity"
        results["active" if active else "returns"]={"cycles":cycles,"exposure_ticks":end-entry,"end":reason}
    return {"entry_tick":entry,**results}


def audit(run):
    matches=json.loads((run/"results.json").read_text())
    output=[]
    for m in matches:
        who=int(m["seat"]);name="SwordLab"+"AB"[who];stem=m["id"]
        keys=rows(run/(stem+"-"+"AB"[who]+".tsv"))
        opponent_file=run/(stem+"-"+"AB"[1-who]+".tsv")
        opponent_keys=rows(opponent_file) if opponent_file.exists() else []
        events=rows(run/(stem+"-contacts.tsv"))
        classify_contacts(events)
        server=rows(run/(stem+"-server.tsv"))
        own=[e for e in events if e["attacker"]==name]
        pressed=[k for k in keys if k["press"]=="true"]
        cycles={}
        defender_start=next((int(k["epochMs"]) for k in opponent_keys if k["press"]=="true" and "epochMs" in k),float("inf"))
        for need in (3,5,10):
            streak=0
            for idx,e in enumerate(events):
                if "epochMs" not in e or int(e["epochMs"])<defender_start:continue
                if e["attacker"]!=name:streak=0;continue
                if not e["fresh"]:continue
                streak+=1
                if streak!=need:continue
                cycles[str(need)]=survival(events,idx,name,m["ticks"]);break
        branch_file=run/(stem+"-landmark.tsv")
        branch=None
        if branch_file.exists():
            signal=rows(branch_file)[0]
            ix=next(i for i,e in enumerate(events) if e["tick"]==signal["tick"] and e["attacker"]==name and e["fresh"])
            switch=next((k for k in keys if k.get("assignedPolicyActive")=="true"),None)
            branch={**signal,**survival(events,ix,name,m["ticks"]),
                    "switch_delay_ms":None if switch is None else int(switch["epochMs"])-int(signal["epochMs"])}
        output.append({**m,**movement(keys),"hits":len(own),"events":count_events(own),"landmarks":cycles,
                       "branch":branch,
                       "fresh_classification":"native damage cooldown" if events and "preDamageCooldown" in events[0] else "native post-contact hurt animation",
                       "defender_input_recorded":bool(opponent_keys),"physical_presses":len(pressed),
                       "opponent_movement":movement(opponent_keys),"opponent_events":count_events(e for e in events if e["attacker"]!=name),
                       "spacing":measure(keys,opponent_keys,server,events,name,"AB"[1-who]),
                       "opponent_spacing":measure(opponent_keys,keys,server,events,"SwordLab"+"AB"[1-who],"AB"[who]),
                       "sampled_cps":len(pressed)*20/max(1,len(keys)),
                       "mean_charge_on_press":statistics.mean(float(k["charge"]) for k in pressed) if pressed else None})
    return output


if __name__=="__main__":
    print(json.dumps(audit(Path(sys.argv[1])),indent=2))
