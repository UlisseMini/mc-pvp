# Lock8 preset, robustness and the 9–10 tick boundary

The preset is implemented and the frozen native robustness batch is complete.
The earlier three one-minute locks remain valid witnesses, but they are **not
robust across fresh runs**: the new five-minute native trial loses its lock
after two fresh hits, the same as matched W-tap. The periodic model survives
five minutes in all 14 tested schedules. That transfer gap must be explained
before adding controller features or claiming a reliable demo.

## Use it

`/lab demo lock8` starts the bot demonstration and spectates it in-game.
`/duel lock8 boxing preset=lock8` lets you face the same controller.
`/replay m1364` plays a previously timing-clean 150-hit demonstration.

The preset uses horizontal KB ×1.1, vertical KB ×1, an 8-tick fresh-hit interval,
native reach 3, a 20 CPS cap, perfect own-view aim, zero reaction and
controller/opponent RTT 150/0 ms.
Explicit options win: `ping=60 myping=60`, for example. In bot matches use
`a.ping=60 b.ping=60`. `/rematch` retains preset settings. An implicit preset RTT
does not overwrite your saved human ping; an explicit `myping` does.

The automated demo runs 4 s with an advancing but non-clicking opponent, then 60 s
with its attacks enabled. Opening hits do not count in research results. Your
attacks are never disabled in a human duel; to reproduce the opening, holdW
without clicking for 4 s. `/lab match lock8 lock8-target preset=lock8` is the
first-to-100 version. `lock8-wtap` is the matched plain W-tap baseline, including
the same acquisition inputs during the first 4 s.

The command now makes the demonstrated scenario convenient; it does NOT promise
a lock on every native run. The fresh failures below qualify the earlier result.

## What the robustness tests say

Every entry scores fresh hits after the same 4 s opening and before the first
accepted return (including damage upgrades). Candidate/W-tap within each row
share profile, ping, reach, cap, duration and opening. Native phases are
independent physical runs; model arms fork the same complete state and queues,
with W-tap observation history primed during the candidate's physical opening.

| Evidence; own/opponent RTT | Maintenance horizon | Candidate fresh prefix | Plain W-tap fresh prefix |
|---|---:|---|---|
|Prior native 150/0, three pairs|60 s|150 / 150 / 150; all zero-return 60 s|2 / 2 / 2|
|New healthy command demo 150/0 + companion|60 s|5; return at 5.836 s from match start|3; return at 6.591 s|
|New native 150/0, one paired stress trial|300 s|2; return at 5.093 s from match start|2; return at 5.241 s|
|Native equal 0/0, three pairs|15 s|2 / 2 / 2|2 / 2 / 2|
|Native equal 60/60, three pairs|15 s|0 / 2 / 2|2 / 2 / 1|
|Native equal 120/120, three pairs|15 s|1 / 1 / 1|2 / 3* / 3|
|Model 150/0, seven phases × two legal cap cadences|300 s|750 in all 14, zero returns|1–2|
|Model 0/0, same 14 cases|15 s and 60 s|1–2, all return|2–5, all return|
|Model 60/60, same 14 cases|15 s and 60 s|0–5, all return|0–4, all return|
|Model 120/120, same 14 cases|15 s and 60 s|0–6, all return|0–3, all return|

The starred W-tap prefix has a >100 ms timing interval and remains flagged.
The original first 150-hit witness also has one 108 ms interval; the other two
original witnesses are fully clean.
Other later timing flags are preserved in the raw trial, separate from the
primary prefix. Model15/60 snapshots are the SAME failed physical prefixes,
not twice as many independent runs. The14 long model cases have400ms maximum
fresh gaps, no missed fresh windows and no defender reach opportunities.

The native long pair is m1388/m1389, explicitly one pair, not a repeated
five-minute success test. Both first-return prefixes, including acquisition,
pass the clock gate: largest client/server intervals are 61.50/58.61 ms for
the candidate and 56.60/59.31 ms for W-tap. Later timing flags in m1388 remain
recorded. Neither long match ever develops a later long lock: each match's
A-side longest fresh run is 11, including the nine free opening hits.

Native files: `poc-20261101/lock8-followup/native/`.
Model inputs, phase tables and checks:
`poc-20261101/lock8-followup/robustness-model/findings.md`.
The model run passes142236 runtime and2279 artifact checks; it changes no
controller or movement/kernel code. It covers fixed periodic schedules, not all
native service jitter. The fresh failed command demo still has all-normal hits
at4186,4584,4993,5385,5784ms before the5836ms return: it did not simply skip a
400ms fresh window. Its primary prefix passes the100ms timing gate. A causal
native timing/geometry reconstruction is described below.

The audit preserves server event order even when our fresh hit and their return
share one millisecond. It asserts agreement with the frozen primary score;
filtering only timestamps would incorrectly remove one earlier hit in m1373.
All 20 scored runs pass the existing reach, input/CPS and loaded-client checks.
Their complete body footprints remain inside the verified floor; both fresh
failures are ordinary reachable returns, not a fall or an out-of-world case.

## What breaks in the fresh native runs

In m1368, the last fresh hit before return occurs at 5,784 ms. Its knockback is
already applied on the defender at about 5,790 ms, but the defender's actual
click starts at 5,814 ms, before its first movement under that velocity at
5,848 ms. Its ray distance is 2.931516: 0.068484 blocks inside legal reach.
The server accepts the return at 5,836 ms. The new impulse was on time; its
physical displacement was not early enough to deny this already-issued click.

In m1388, the defender clicks at about 5,086 ms with ray distance 2.992085,
only 0.007915 blocks inside reach. The server accepts it at 5,093 ms, before
our next fresh hit at 5,094 ms and the resulting velocity application at
5,098 ms. These rounded host times describe event order; the audit uses the
recorded monotonic clocks where available. Neither failure is saved by a later
hit, even when the two accepted attacks occur in nearly the same instant.

The acquisition histories already differ from successful m1364. For example,
m1388's second vertical impulse is 0.322375 rather than 0.36075. In m1368 the
first different impulse class is at hit six, 0.041375 rather than 0.074125.
Pre-hit server velocity and position-report records support these differences.
Normal-hit strength depends on latent server motion and packet order, so the
same nominal profile does not imply the same flight. This is a measured causal
chain to inspect, not a counterfactual proof that one impulse alone determines
the result. The initial observed position, velocity, ground and sprint fields
match; an unobserved sprint-history difference has not been established.

The successful m1364 trace has no return contact in the first 12 seconds and
a sampled minimum defender distance of 3.127397. Fresh failing runs consume
that spacing margin. Fixed periodic schedules and a coarse 100 ms stall gate
do not certify robustness to all real packet/movement orderings.
See [the six-trace comparison](poc-20261101/lock8-followup/failure-audit/findings.md).

## Model validation against these fresh failures

After fixing two clock-alignment bugs in the replay adapter, the first 12 s of
all six native traces (three matched pairs) replay completely: **24,683 of
24,683 event rows and 9,243 compared state rows, all state values bit-exact**.
This includes the acquisition and first return in both fresh failed pairs.
The maximum independent nearest-box distance error is 4.52e−13 blocks; maximum
selected-ray error is 4.44e−16. These are floating-expression rounding errors,
far below the measured denial margins. No server snapshots are skipped.

The adapter previously chose the initial tracker counter using wall time while
including events using monotonic time. It also sorted wall-millisecond server
snapshots against precise packet events. The correction uses one clock boundary
for initialization and the source-proven tick-end hook order for snapshots.
Four focused boundary tests and the old successful/baseline pair pass. The
original failed comparisons remain in `recorded-replay-initial-summary.json`;
the repaired results are in `recorded-replay-validated-summary.json`, both under
`poc-20261101/lock8-followup/native/`.

This validates the mechanics **conditional on recorded inputs and scheduling**.
It does not turn the periodic five-minute prediction into a native success.
The deterministic kernel can reproduce both outcomes; the frozen controller
lacks robustness to the actual flight and packet/movement histories. No
controller, knockback or movement-kernel code was changed to obtain agreement.

## What ping is doing

These results do not prove that exactly150/0 is necessary, nor that no other
controller can lock at equal ping. Equal-ping acquisition reaches different
complete states; some are already unsafe when the quiet opening ends.

In the model's canonical0/0 phase, the opponent closes into reach before the
next useful impulse, despite an unmissed fresh-hit cadence. At60/60 the canonical
opening is already unsafe; other phases fail when the opponent clicks before
its first movement using an applied velocity packet. At120/120 some openings
miss own reach at a fresh window; others admit a return before the next velocity
packet arrives. A fresh server hit does not retroactively cancel an already
issued return attack.

The150/0 anchor combines delayed position views with prompt victim knockback
delivery. Both matter. Zero opponent ping alone is insufficient:0/0 fails too.
A precise necessity claim needs a coupled state/clock argument, not a statement
that ping changes Minecraft's knockback constant.

## Why extending the hit interval breaks this controller

This is a controlled late-cycle intervention, not another settings sweep. Run
the physical 8-tick acquisition and lock for 20 seconds, then fork the complete
state, queues and observation histories. Keep the common real fresh hit and
its impulse identical; change only the subsequent fresh-hit interval. Exclude
that common hit and score the following three seconds. W-tap starts from the
same complete state in every comparison.

| Fresh-hit interval | Frozen controller: fresh prefix across three phases | Plain W-tap: fresh prefix |
|---|---|---|
|8 ticks|7 / 7 / 7; all three seconds without a return|2 / 1 / 2|
|9 ticks|2 / 2 / 2; all return|2 / 1 / 3|
|10 ticks|1 / 2 / 1; all return|3 / 1 / 2|

None of the candidate cases misses an eligible fresh-input window. Extra time
between impulses lets the opponent approach while its delayed image of us
continues moving forward. In the canonical 9-tick case, that image closes by
0.572 blocks and the opponent approaches by 0.128 blocks over 150 ms: almost
0.700 blocks of closure, while our actual player advances only 0.315 blocks.
The next knockback packet arrives, but the opponent clicks at 21,160 ms, at
ray distance 2.952, **0.3 ms before** its first movement using that impulse.

There is a useful causal bound here. Our position report at 20,900.6 ms already
determines the relevant image at that click. Under this grounded, normal-hit
branch, later input cannot change it before the opponent's next interpolation
at 21,160.3 ms. The controller does not begin coasting until 21,100 ms, about
199 ms after that commitment. Available correction to that already-committed
view is therefore zero. At the recorded opponent pose, shifting our image
backward by more than 0.094 blocks would suffice to restore geometric denial.
That is a sufficient geometry repair, not the minimum possible ray correction.

This explains why the **frozen late-braking controller** fails at 9–10 ticks.
It does not prove vanilla impossible, or exclude earlier braking: our own ray
at the relevant eligible input is only 2.287 blocks, leaving reach slack.
The three-phase result and committed-view calculation are stronger than a
negative sweep, but no global empty spacing corridor has been established.
See [the bound and event timelines](poc-20261101/lock8-followup/bound/findings.md).

## Deployment and invalid setup retained

Preset/parser, command and rollout commits: d218cbf, f934c30,77c7810,e4aaf13.
Model robustness commit:781ed7c; native evidence:8e46906; boundary:18f9d65;
native failure audit:f62b9a7; replay adapter repair:49e8257.
Server and Forge builds and the pure Java8
preset/parser checks pass. All three friendly policy aliases have identical
input properties to their tested originals, excluding descriptions.

A long verified floor was prepared before scoring: half-X1024/half-Z512,
bedrock atY3, airY4..19, unchanged slipperiness. The14 modeled300s footprints
fit inside it (maximumZ372.314); this is not a universal heading bound. The
native footprint audit also passes every run. The server heap ceiling was raised from1
to2GiB for preparation, without changing combat or tick rate. Timed scenarios
now accept304000ms; the runner's wait limit includes the requested duration.

Initial command smoke m1367 is INVALID SETUP: a42s floor-preparation stall caused
bot timeouts and broken automatic reconnect views. Both clients fell below the
floor in their own worlds, while server bodies stayed at spawn. Preserve it;
zero hits is not a lock. All idle clients were restarted after preparation,
then the healthy command test m1368 and matched W-tap m1369 were recorded. No
candidate retry follows the healthy failure. The equal-ping/long trial plan was
frozen before its outcomes; no controller changes or outcome-based rerolls.

An independent source/configuration audit found identical effective control
settings between m1364 and m1388, unchanged controller/combat/movement sources,
and identical per-slot keybindings, FPS settings and mod configuration. Native
sprint/double-tap history is not explicitly normalized by the match reset, so
complete acquisition-state identity is not implied by matching policy settings.
This is an unverified seed caveat, not an established cause of the failure.

All native trials are finished; global combat defaults are restored to vanilla
1/1/10. The optional preset changes only its own match.
