"""Recover the first lethal server contact when an immediate respawn preceded the referee's next poll."""
import csv
from functools import lru_cache
import json


def missed_knockout(match,events,names):
    first=next(((i,e) for i,e in enumerate(events) if float(e["defenderHP"])<=0),None)
    if first is None:return None
    idx,hit=first
    won=hit["attacker"]==names[match["seat"]]
    winner_hp,loser_hp=(match["hp"],match["opponent_hp"]) if won else (match["opponent_hp"],match["hp"])
    if idx==len(events)-1 and winner_hp>0 and loser_hp==0 and abs(match["ticks"]-int(hit["tick"]))<=1:return None
    return first


def adjudicate(match, events, server, names):
    first=missed_knockout(match,events,names)
    if first is None:return match,None
    index,hit=first;winner=hit["attacker"];won=names[match["seat"]]==winner
    when=int(hit["epochMs"])
    side="AB"[names.index(winner)]
    # Regeneration is disabled. Include same-tick incoming damage between the server sample and the kill.
    seen=[float(r["hp"+side]) for r in server if int(r["epochMs"])<when and int(r["tick"])>=0]
    seen+=[float(e["defenderHP"]) for e in events[:index] if e["defender"]==winner]
    hp=float(hit["attackerHP"]) if "attackerHP" in hit else min([20.,*seen])
    if hp<=0:raise ValueError("The first lethal contact has a dead winner")
    fixed=dict(match,hp=hp if won else 0.,opponent_hp=0. if won else hp,ticks=int(hit["tick"]),
               first_ko_epoch_ms=when,original_poll_result={k:match[k] for k in ("hp","opponent_hp","ticks")},
               post_ko_seconds=max(0.,(int(server[-1]["epochMs"])-when)/1000))
    return fixed,index


@lru_cache(maxsize=4096)
def correction(run,rid,seat,hp,enemy_hp,ticks):
    contact_file=run/(rid+"-contacts.tsv")
    if not contact_file.is_file():return {}
    with contact_file.open() as f:events=list(csv.DictReader(f,delimiter="\t"))
    meta=json.loads((run/"metadata.json").read_text())
    match=dict(id=rid,seat=seat,hp=hp,opponent_hp=enemy_hp,ticks=ticks)
    if missed_knockout(match,events,meta["names"]) is None:return {}
    with (run/(rid+"-server.tsv")).open() as f:server=list(csv.DictReader(f,delimiter="\t"))
    result,cut=adjudicate(match,events,server,meta["names"])
    return result if cut is not None else {}
