"""Measure contact and input signatures offline in completed tick-scheduled Java recordings."""
import argparse
import csv
import json
import math
from collections import Counter
from pathlib import Path
from audit import rows, classify_contacts


def aimed_and_ready(row):
    if float(row["charge"]) < .9:
        return False
    yaw, pitch = map(math.radians,(float(row["yaw"]),float(row["pitch"])))
    direction=(-math.sin(yaw)*math.cos(pitch),-math.sin(pitch),math.cos(yaw)*math.cos(pitch))
    center=[float(row[t])-float(row[p]) for t,p in (("targetX","x"),("targetY","y"),("targetZ","z"))]
    lo=(center[0]-.3,center[1]-1.62,center[2]-.3)
    hi=(center[0]+.3,center[1]+1.8-1.62,center[2]+.3)
    near,far=0.,3.
    for a,b,v in zip(lo,hi,direction):
        if abs(v)<1e-12:
            if not a<=0<=b:
                return False
        else:
            near=max(near,min(a/v,b/v))
            far=min(far,max(a/v,b/v))
    return near<=far


METRICS=["wins","damage_events","boosted_hits","rising_boosted_hits","rising_boosts_after_own_jump","criticals","received_fresh",
         "grounded_jump_at_knockback","new_grounded_jump_at_knockback","quick_boosted_replies",
         "quick_replies_after_client_received_hit","quick_replies_with_prior_ready_no_press","unmapped_attack_inputs"]


def count(run, game):
    seat=game["seat"]
    who="SwordLab"+"AB"[seat]
    stem=game["id"]
    trace=rows(run/f"{stem}-{'AB'[seat]}.tsv")
    by_time={int(k["epochMs"]):k for k in trace}
    events=rows(run/f"{stem}-contacts.tsv")
    classify_contacts(events)
    lag=game["rtt"]//100
    impulses={int(e["epochMs"])-15+50*(lag+1) for e in events
              if e["attacker"]!=who and (e["fresh"] or e["boosted"]=="true")}
    c=Counter(wins=int(game["opponent_hp"]==0))
    previous=None
    for event in events:
        # Input poll, packet processing and velocity delivery share the registered tick clock.
        server_poll=int(event["epochMs"])-15
        press_stamp=server_poll-50*lag
        delivery=server_poll+50*(lag+1)
        if event["attacker"]==who:
            c["damage_events"]+=1
            c["criticals"]+=event["critical"]=="true"
            boosted=event["boosted"]=="true"
            sample=by_time.get(press_stamp)
            if not sample or sample["press"]!="true":
                c["unmapped_attack_inputs"]+=1
            else:
                c["boosted_hits"]+=boosted
                rising=boosted and sample["ground"]=="false" and float(sample["vy"])>0
                c["rising_boosted_hits"]+=rising
                if rising:
                    for takeoff in range(press_stamp-50,press_stamp-351,-50):
                        k=by_time.get(takeoff)
                        if k is None or takeoff in impulses:
                            break
                        if k["ground"]=="true":
                            following=by_time[takeoff+50]
                            c["rising_boosts_after_own_jump"]+=(
                                k["jump"]=="true" and float(following["y"])-float(k["y"])>.35
                                and press_stamp not in impulses)
                            break
                if (boosted and previous and previous["attacker"]!=who and previous["fresh"]
                        and 0<=int(event["tick"])-int(previous["tick"])<=2):
                    c["quick_boosted_replies"]+=1
                    received=int(previous["epochMs"])-15+50*(lag+1)
                    c["quick_replies_after_client_received_hit"]+=press_stamp>=received
                    # Only count an earlier look/geometry opportunity before the client saw the hit.
                    earlier=[by_time[t] for t in range(press_stamp-150,press_stamp,50) if t<received and t in by_time]
                    c["quick_replies_with_prior_ready_no_press"]+=any(k["press"]=="false" and aimed_and_ready(k) for k in earlier)
        elif event["fresh"]:
            c["received_fresh"]+=1
            sample=by_time.get(delivery)
            if sample and sample["jump"]=="true" and sample["ground"]=="true":
                c["grounded_jump_at_knockback"]+=1
                prev=by_time.get(delivery-50)
                c["new_grounded_jump_at_knockback"]+=bool(prev and prev["jump"]=="false")
        previous=event
    return c


def export_signatures(run, out):
    meta=json.loads((run/"metadata.json").read_text())
    if meta["clock"] not in ("fast","realtime"):
        raise ValueError("Exact attack-poll alignment requires the scheduled Java clock")
    paired={}
    for game in json.loads((run/"results.json").read_text()):
        key=(Path(game["rival"]).name,game["rtt"],game["pair"],game["seat"])
        paired.setdefault(key,{})[game["policy"]]=count(run,game)
    with out.open("w") as f:
        w=csv.writer(f,lineterminator="\n")
        w.writerow(["opponent","rtt","opening","seat","policy",*[a+"_"+k for a in ("learned","wtap") for k in METRICS]])
        for key,arms in sorted(paired.items()):
            for arm in sorted(arms.keys()-{"wtap"}):
                w.writerow([*key,arm,*[arm_stats[k] for arm_stats in (arms[arm],arms["wtap"]) for k in METRICS]])


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording",type=Path)
    parser.add_argument("output_csv",type=Path)
    args=parser.parse_args()
    export_signatures(args.recording,args.output_csv)
