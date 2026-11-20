"""Offline, timestamp-aligned spacing diagnostics from both native client views."""
from bisect import bisect_right
from collections import Counter
from math import hypot


class ClientView:
    def __init__(self, rows):
        self.rows = rows
        self.times = [int(r["epochMs"]) for r in rows]

    def at(self, time):
        i = bisect_right(self.times, time) - 1
        if i >= 0 and time - self.times[i] <= 100:
            return self.rows[i]
        return None


def in_reach(row):
    dx = max(0, abs(float(row["targetX"]) - float(row["x"])) - .3)
    dz = max(0, abs(float(row["targetZ"]) - float(row["z"])) - .3)
    dy = float(row["targetY"]) - (float(row["y"]) + 1.62)
    return hypot(dx, dz, max(0, dy, -dy - 1.8)) <= 3


def measure(keys, opponent_keys, server, events, name, opponent_seat):
    if any(not source or "epochMs" not in source[0] for source in (keys,opponent_keys,server,events)):
        return {}
    own, other = ClientView(keys), ClientView(opponent_keys)
    counts = Counter()
    window_offsets = Counter()
    clock = {int(row["tick"]): row for row in server}
    immunity_column = "damageCooldown" + opponent_seat
    direct = immunity_column in server[0]
    fresh = [event for event in events if event["fresh"]]
    for i, event in enumerate(events):
        if not event["fresh"]:
            continue
        tick, time = int(event["tick"]), int(event["epochMs"])
        if event["attacker"] == name:
            counts["own_fresh_hits"] += 1
            # A fresh hit can reopen and consume the window before the next server sample.
            same_target = [int(e["tick"]) for e in fresh if int(e["tick"]) > tick and e["attacker"] == name]
            if direct:
                open_ticks = [t for t in range(tick+1,tick+13) if t in clock and int(clock[t][immunity_column]) <= 10]
                candidates = open_ticks + [t for t in same_target[:1] if t <= tick+12]
                window = min(candidates) if candidates else None
            else:
                window = tick + 10
            if window not in clock:
                counts["window_censored"] += 1
                continue
            window_offsets[window-tick] += 1
            stamp = int(clock[window]["epochMs"])
            a, b = own.at(stamp), other.at(stamp)
            if a is None or b is None:
                counts["window_missing_views"] += 1
                continue
            counts["window_sampled"] += 1
            counts["window_in_own_reach"] += in_reach(a)
            counts["window_own_reach_only"] += in_reach(a) and not in_reach(b)
            counts["window_without_fresh_hit"] += window not in same_target
        else:
            before = own.at(time)
            if before is None:
                continue
            counts["incoming_fresh_sampled"] += 1
            if before["forward"] != "-1":
                continue
            counts["back_at_incoming"] += 1
            counts["back_and_jump_at_incoming"] += before["jump"] == "true"
            next_contact = int(events[i+1]["epochMs"]) if i+1 < len(events) else time + 501
            last_out = None
            for t in range(tick+1,tick+11):
                if t not in clock:
                    break
                stamp = int(clock[t]["epochMs"])
                if stamp >= next_contact or stamp > time + 500:
                    break
                a, b = own.at(stamp), other.at(stamp)
                if a is None or b is None or in_reach(a) or in_reach(b):
                    last_out = None
                elif last_out is not None and last_out[0] != a["tick"] and last_out[1] != b["tick"]:
                    counts["back_then_both_out"] += 1
                    counts["back_jump_then_both_out"] += before["jump"] == "true"
                    break
                else:
                    last_out = (a["tick"], b["tick"])
    return {"window_source": "server damage cooldown and fresh hits" if direct else "fresh hit plus ten ticks",
            "window_offset_ticks": dict(window_offsets), **counts}
