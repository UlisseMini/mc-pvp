"""Read-only analysis of referee events and replay keys from finished real games."""
import csv
import json
import statistics
from collections import Counter
from pathlib import Path
import sys


def rows(path):
    with path.open() as f:return list(csv.DictReader(f,delimiter="\t"))


def audit(run):
    matches=json.loads((run/"results.json").read_text())
    output=[]
    for m in matches:
        who=int(m["seat"]);name="SwordLab"+"AB"[who];stem=m["id"]
        keys=rows(run/(stem+"-"+"AB"[who]+".tsv"))
        opponent_keys=rows(run/(stem+"-"+"AB"[1-who]+".tsv"))
        events=rows(run/(stem+"-contacts.tsv"))
        own=[e for e in events if e["attacker"]==name]
        counts=Counter()
        for e in own:
            for trait in ("critical","boosted","strong","sweep"):counts[trait]+=e[trait]=="true"
            counts["fresh"]+=int(e["preImmunity"])<=10
            counts["upgrade"]+=int(e["preImmunity"])>10
        pressed=[k for k in keys if k["press"]=="true"]
        cycles={}
        defender_start=next((int(k["epochMs"]) for k in opponent_keys if k["press"]=="true" and "epochMs" in k),float("inf"))
        for need in (3,5,10):
            streak=0
            for idx,e in enumerate(events):
                if int(e["epochMs"])<defender_start:continue
                if e["attacker"]!=name:streak=0;continue
                if int(e["preImmunity"])>10:continue
                streak+=1
                if streak!=need:continue
                entry=int(e["tick"]);pair={}
                for active in (False,True):
                    n=0;last=entry;end=m["ticks"];reason="round_end"
                    for h in events[idx+1:]:
                        t=int(h["tick"])
                        if active and t-last>22:end=last+23;reason="inactivity";break
                        if h["attacker"]!=name:end=t;reason="return";break
                        if int(h["preImmunity"])<=10:n+=1;last=t
                    if active and reason=="round_end" and end-last>22:end=last+23;reason="inactivity"
                    pair["active" if active else "returns"]={"cycles":n,"exposure_ticks":end-entry,"end":reason}
                cycles[str(need)]={"entry_tick":entry,**pair};break
        output.append({**m,"hits":len(own),"events":counts,"landmarks":cycles,"physical_presses":len(pressed),
                       "sampled_cps":len(pressed)*20/max(1,len(keys)),"forward_ticks":sum(k["forward"]=="1" for k in keys),
                       "back_ticks":sum(k["forward"]=="-1" for k in keys),"jump_key_ticks":sum(k["jump"]=="true" for k in keys),
                       "lateral_ticks":sum(k["side"]!="0" for k in keys),"mean_charge_on_press":statistics.mean(float(k["charge"]) for k in pressed) if pressed else None})
    return output


if __name__=="__main__":
    print(json.dumps(audit(Path(sys.argv[1])),indent=2))
