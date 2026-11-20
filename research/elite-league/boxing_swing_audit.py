"""Compare the selector's available swing cues with the opponent's actual native swings."""

import argparse
import base64
import json
from pathlib import Path
import sys

from boxing_opening_audit import events
from run import ROOT

sys.path.insert(0, str(ROOT / "research"))
from network_pipeline_audit import Reader


def measure(match, seat):
    other = "b" if seat == "a" else "a"
    game = json.loads((ROOT / "runtime/results" / f"{match}.json").read_text())
    ours, = (ROOT / "runtime/pov" / match).glob(f"{seat}-client-*-events.jsonl.gz")
    theirs, = (ROOT / "runtime/pov" / match).glob(f"{other}-client-*-events.jsonl.gz")
    own = list(events(ours, {"observation", "click", "packet_wire", "packet_handler_return"}))
    first = next(e["monotonic_ns"] for e in own if e["type"] == "click" and e.get("match") == match
                 and e["time"] >= game["start"])
    targets = {e["target_id"] for e in own if e["type"] == "observation" and e.get("match") == match
               and e["target_is_canonical"]}
    if len(targets) != 1:
        raise ValueError(f"{match}: ambiguous opponent entity")
    target, = targets
    swing_ids = set()
    for e in own:
        if e["type"] != "packet_wire" or e.get("match") != match or e["time_ms"] < game["start"]:
            continue
        meta = e["data"]["packet"]
        if meta["kind"] != "S0BPacketAnimation" or meta["direction"] != "in":
            continue
        reader = Reader(base64.b64decode(e["data"]["base64"]))
        if reader.varint() != 11:
            raise ValueError(f"{match}: unexpected protocol packet ID")
        if reader.varint() == target and reader.number("B") == 0:
            swing_ids.add(meta["packet_id"])

    observed = sorted(e["monotonic_ns"] for e in own if e["type"] == "packet_handler_return"
                      and e.get("match") == match and e["monotonic_ns"] < first and e["data"]["main_thread"]
                      and e["data"]["kind"] == "S0BPacketAnimation" and e["data"]["packet_id"] in swing_ids)
    actual = sorted(e["monotonic_ns"] for e in events(theirs, {"packet_handler_enter"})
                    if e.get("match") == match and e["time_ms"] >= game["start"] and e["monotonic_ns"] < first
                    and e["data"]["kind"] == "C0APacketAnimation" and e["data"]["main_thread"])
    seen = observed[-1] if observed else None
    last = actual[-1] if actual else None
    return dict(match=match, side=seat, rtt=game[f"initial_ping_{seat}"],
                actual_swings=len(actual), observed_swings=len(observed),
                actual_swing_age_ms=(first - last) / 1e6 if last is not None else None,
                observed_swing_age_ms=(first - seen) / 1e6 if seen is not None else None,
                swings_after_last_observation=sum(t > seen for t in actual) if seen is not None else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("out", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    if manifest["plan"].keys() != manifest["matches"].keys():
        raise ValueError("Native cohort is incomplete")
    rows = []
    for req, match in manifest["matches"].items():
        setup = manifest["plan"][req]
        rows.append(dict(arm=setup["arm"], **measure(match, setup["side"])))
    args.out.write_text(json.dumps(rows, indent=2) + "\n")


if __name__ == "__main__":
    main()
