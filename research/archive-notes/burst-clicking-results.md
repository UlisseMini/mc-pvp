# Burst clicking: native effect and actual match results

The corrected recoil policy demonstrably reduces backward velocity at the right
instant. Adding optional critical attempts then improved its scores, winning both
hold-W games and both direct comparisons with recoil-only burst. It split its
games with continuous one-tick W-tap, which still had larger hold-W margins.
These are small, frozen-policy experiments, not a search for a score-maximizing
parameter combination or evidence of a universally superior policy.

## What was wrong, and what changed

The first burst implementation reacted to a delayed hurt cue and then clicked
for a fixed 150 ms. That is not a reliable test of the proposed mechanism: the
player can already be moving forward during that interval. In the diagnostic
sample `m0424`, `m0430`, `m0432`, defensive-only contacts included 85 forward
sprint drags and only 14 backward ones (plus 25 near-stationary contacts).

The user removed the artificial reaction restriction. The corrected
`wtap-tick-motion` policy observes its current local body and only issues an
optional recoil click when:

1. Native input is about to be polled, the ordinary ray selects the opponent,
   and the native miss counter permits an attempt.
2. Local sprint is active, and horizontal velocity projected toward the opponent
   is below −0.005 blocks/tick.
3. This click leaves at least 100 ms before the estimated next attack window.

All issued clicks enter normal Minecraft input handling. No server immunity,
opponent-private state, direct attack packets or velocity writes enter this
policy. A single physical input limiter still applies across every burst and
quiet interval. Vanilla reach and immune-frame contact effects remain enabled.

During acquisition and predicted fresh-hit windows, it clicks normally, including
misses. The next window starts at visible landed-cue time + 500 ms − own added
RTT − 50 ms. This is an estimate; tick phases and replication still matter.
The 50 ms lead was fixed from input/packet timing before the cohort. W release
is still deliberately scheduled 150 ms after the visible landed cue, for exactly
one movement update. That planned reset placement is distinct from an observation
delay: all new fast policies have **zero artificial observation delay**.

`wtap-tick-window` is the same policy with optional recoil clicks disabled.
`wtap-tick-fast` uses the same W-reset schedule but continuous clicking.
`wtap-fast` uses a 50 ms wall-clock release instead of one movement update.
All fast controls receive the same frame and native-tick input-poll opportunities.

## The local mechanism really happened

With positive radial velocity toward the opponent, a sprinting native contact
applies `v_after = 0.6 × v_before` horizontally. If `v_before < 0`, the change
is toward the opponent; if `v_before > 0`, it slows pursuit. Direction must be
checked at actual contact, not merely when an earlier hurt cue was observed.

In the 18 full corrected-policy games, **878 of 878 optional recoil inputs**
became native contacts and applied the expected multiplier to backward velocity.
There were zero wrong-direction optional recoil contacts and zero missing drags.
The separate functional smoke test verified another 54/54; its immediate W-reset
timing differed, so it is excluded from the performance comparison.

This proves the intended local effect, not an unconditional strategic benefit.
More closing momentum changes spacing and can preserve the opponent's return
reach. A click also occupies a 100 ms input slot. Finally, skipping other contacts
can lose accepted critical-damage upgrades and alter sprint state before the next
fresh hit. Those changes cannot be treated as free in a full fight.

## Corrected-policy scoreboard

Each entry is first to 100 **server-accepted attacks**. A stronger critical attack
inside immunity can deal incremental damage and counts as another accepted
attack. We retain that established scoring rule and separately report fresh hits;
fresh-hit totals from a match stopped by accepted-hit score are not a separate
first-to-100-fresh tournament.

Both sides have zero artificial reaction delay. Pings below are added RTTs, in
the same order as the named bots. At 20/20 each pairing has two games with sides
swapped; unequal-ping cells have one game each.

| First bot | Opponent | RTT ms | Scores, first bot first |
|---|---|---|---|
| Motion burst | Hold-W | 20/20 | 95–100, 84–100 |
| Motion burst | Continuous one-tick W-tap | 20/20 | 87–100, 86–100 |
| Window only | Hold-W | 20/20 | 75–100, 80–100 |
| Window only | Continuous one-tick W-tap | 20/20 | 84–100, 80–100 |
| Continuous one-tick W-tap | Ordinary W-tap | 20/20 | 100–94, 100–96 |
| Continuous one-tick W-tap | Hold-W | 20/20 | 100–75, 100–78 |
| Motion burst | Hold-W | 20/100 | 97–100 |
| Motion burst | Continuous one-tick W-tap | 20/100 | 79–100 |
| Window only | Hold-W | 20/100 | 80–100 |
| Motion burst | Hold-W | 100/20 | 90–100 |
| Motion burst | Continuous one-tick W-tap | 100/20 | 88–100 |
| Window only | Hold-W | 100/20 | 90–100 |

At 20/20, adding the recoil feature improved the window-only policy's observed
scores against hold-W from 75/80 to 95/84. This is encouraging mechanism evidence
but only two independent games per cell, not an exact shared-trajectory
counterfactual. Neither selective policy beat continuous one-tick W-tap.

In `m0443`, motion burst had **95 fresh hits and zero upgrades**; hold-W had
**77 fresh hits and 23 upgrades**. In `m0444`, motion burst had 86 fresh + 1
upgrade against continuous W-tap's 84 fresh + 16 upgrades. Lost criticals are
therefore a concrete candidate cost, rather than a hypothetical excuse for the
score. They do not explain every trajectory change or prove that restoring them
will preserve the fresh-hit advantage.

The [full frozen cohort](motion-burst-comparison-v1.json) stores policies, build
and source hashes, fixtures, per-match validation and timing diagnostics. The
[summary](motion-burst-summary-v1.json) includes fresh/upgrade counts, CPS,
native W-release durations, streaks and combo durations.

## Isolated critical-attempt experiment

`wtap-tick-critical` changes only `burst_critical=1` relative to motion burst.
It permits one optional attempt per visible fresh cue when its own local body
satisfies native critical predicates, the ordinary ray selects the opponent,
and at least 100 ms remains before the next predicted attack window. Merely
observing an opportunity does not spend the budget; only an actual physical
input does. If a recoil click already satisfies this rule, it spends that same
budget. The variant does not infer a fresh hit from its attempt or reset the
fresh-window clock without a new visible cue.

The cohort fixes six full games at 20/20 ms RTT: two side-swapped games each
against hold-W, continuous one-tick W-tap and otherwise-identical motion burst.
The optional input changes no W-reset parameter, aim rule, reaction setting,
reach, packet handling or physics.

| Critical-aware burst versus | Scores, burst first | Fresh-hit counts in those same games |
|---|---|---|
| Hold-W | 100–93, 100–86 | 86–74, 84–65 |
| Continuous one-tick W-tap | 94–100, 100–97 | 89–83, 92–87 |
| Motion burst without optional criticals | 100–87, 100–92 | 90–87, 86–92 |

The [six-game checkpoint](critical-burst-comparison-v1.json) and
[summary](critical-burst-summary-v1.json) retain the fixed intervention and every
result. The [native attempt audit](critical-burst-attempt-audit-v1.json) links each
physical input through native consumption, contact, C02 receipt and server attack:

- 120 optional critical inputs all reached native server attack handling.
- 67 produced accepted damage upgrades.
- 43 were immune because the server already recorded equally strong critical
  damage for that hurt window.
- 10 were immune because the server did not see the attacker falling, despite
  local eligibility. These are a remaining client/server timing limitation of
  the optional-critical rule, not a failure of the recoil-direction check.

There were no unexplained acceptance mismatches in that audit. It uses private
diagnostic state only after the match. The bot itself does not read the victim's
previous damage or server fall state.

The cost is visible too: 68 of those critical contacts slowed forward motion;
23 slowed backward motion, six were near stationary, and 23 had no local sprint
drag. Of the 67 upgrades, 42 consumed an active server sprint flag. For 40 of those,
the next fresh attack arrived without server sprint; the other two had no next
fresh attack before the match ended. This is observed state ordering, not a
counterfactual claim that every later hit would otherwise have been stronger.

All six games passed the existing gates. Their shortest physical input interval
was 100.010083 ms, worst client movement p95 was 55.413 ms and largest movement
gap was 144.878 ms. All 842 optional recoil contacts in this cohort applied the
correct backward multiplier, including recoil-only opponents and clicks that
also qualified as critical attempts. Combined with the earlier 18 games, that is
**1,720/1,720 correct optional recoil contacts** across 24 full zero-delay games.

The isolated critical feature improved both direct matches, but the sample is
too small to establish reliability across phases or pings. It is not yet clearly
better than continuous one-tick W-tap: their head-to-head split one game each.
The critical cohort tested only 20/20 ms; asymmetric-ping critical performance
remains unmeasured.

The next mechanistic tests should predict the server's critical eligibility from
our own previously transmitted movement and packet order, then separately test
an ordinary sprint reset after a predicted accepted critical. The former targets
the ten local/server falling disagreements; the latter targets lost sprint on
the next fresh hit. Neither should use hidden state in live control. Keeping
those as separate interventions would distinguish their effects from a new
click-window or movement search.

## Validation and evidence boundaries

All 18 games passed native input, view-identity/replication, declared reaction,
reach and cadence gates. The minimum recorded physical input interval was
100.00575 ms. The worst client movement p95 was 58.454 ms; the largest movement
gap was 84.627 ms. Two games ran concurrently; no result was accepted with a
failed cadence gate. The arena was verified flat and clear before use, and
clients settled after terrain loading before combat.

The earlier 150 ms reaction cohort was stopped when the user changed that rule.
Its 17 qualified games remain in [the original checkpoint](burst-wtap-comparison-v1.json),
with the post-stop completion explicitly separated. They are not pooled with
the zero-delay cohort. Failed setup smokes are retained and quarantined; the
repaired-arena and functional smoke results are also separate from full matches.

Every archived native stream is retained under `traces/`, with bytes and SHA-256
in [the manifest](trace-manifest.json). These traces are diagnostic recordings;
private server and opponent-client measurements do not feed the bot.

Interactive replay views:

- [Corrected motion burst versus hold-W, 95–100](../recordings/m0443.html).
- [Continuous one-tick W-tap versus hold-W, 100–75](../recordings/m0448.html).
- [Critical-aware burst versus hold-W, 100–93](../recordings/m0463.html).
- [Critical-aware burst versus continuous one-tick W-tap, 100–97](../recordings/m0468.html).

These are offline trace replays, not captured Minecraft video.

## Reproduce and challenge

Start the lab, then use a fresh evidence name:

```sh
python3 burst_science.py my-motion-comparison --motion
python3 burst_summary.py research/my-motion-comparison.json research/my-motion-summary.json
python3 burst_science.py my-critical-comparison --critical
python3 critical_attempt_audit.py research/my-critical-comparison.json research/my-critical-attempt-audit.json
```

Join Minecraft Java 1.8.9 at `127.0.0.1:25565`. Use `/duel wtap-tick-fast`
for the continuous baseline, `/duel wtap-tick-motion` for corrected recoil
scheduling, or `/duel wtap-tick-critical` for the extra critical-attempt variant.
`/ping bot 100` and `/ping me 20` adjust added RTT during a human duel.
`/matches` and `/watch` show active bot matches.
