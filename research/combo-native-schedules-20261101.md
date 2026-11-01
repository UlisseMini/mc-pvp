# Native timing calibration and acquisition diagnosis

The periodic-clock prediction was misleading: realistic timing makes the same
controller fail. The frozen replacement now predicts failures in four fresh
native tests, including the opponent clicking after receiving knockback but
before moving. This supports a narrow controller experiment, not a claim that
we have calibrated every timing distribution or achieved a reliable lock.

## Current: fresh validation, with the controller still unchanged

We froze the controller, timing donor pool and advance prediction before running
two more native pairs. Both use150/0 RTT, H1.1/V1/hit8, reach3/3,20CPS and a
four-second silent-opponent opening followed by60s of maintenance. The primary
score excludes the free opening hits and stops at the first accepted return.

| Fresh native repeat | Frozen candidate | Plain W-tap | Timing quality through first return |
|---|---:|---:|---|
|1: m1398 / m1397|6|2|Both clean|
|2: m1399 / m1400|5|2|Both clean; m1400 has a later whole-match stall|

The native pairs share settings and the opening rule, not identical physical
clock realizations. The model comparisons below fork the complete acquired
state, including queues and a primed W-tap history, on each recorded calendar.
No controller fitting or parameter adjustment occurred between these tests.

There are two different validation claims:

- **Before seeing the new recordings:** the frozen equal-calendar timing mixture
  predicts frequent early failures. The new candidate scores5 and6 fall around
  the41st–46th percentile, accounting for unfinished prefixes; both W-tap2
  results fall in its central mass. It no longer predicts a universal750-hit lock.
- **After capturing the new clocks:** reuse the frozen timing sampler on each
  held-out calendar, with20 declared seeds. This conditions on measured host
  stage times and queue workload. It is stronger than replaying recorded attacks,
  but is not an unconditional forecast of a new laptop workload.

| Held-out calendar / native arm | Candidate model distribution | Matched W-tap model distribution | Native observation |
|---|---|---|---|
|m1397 / W-tap|2 in20/20|2 in20/20|W-tap2|
|m1398 / candidate|6 in19/20;3 once|1 in20/20|Candidate6|
|m1399 / candidate|5 in11/20;2 five times;9 three times;20 once|2 in16/20;1 four times|Candidate5|
|m1400 / W-tap|5 in20/20|2 in9/20;5 in11/20|W-tap2|

All80 held-out paired model draws resolve their primary prefix; none sustains
the60s horizon. These are **four native observations**, not80 independent native
trials. The table's unplayed arm is a model counterfactual, not another native
measurement. The development ensemble's4/80 candidate and1/80 W-tap unknowns
remain in its advance forecast; they have not been discarded.

Matching the endpoint alone is insufficient. In m1398, the sampled seed0 path
also matches all14 pre-return launch vectors and compared physical/view geometry
to1e-12. In m1399, six of20 frozen draws match all14 precritical fresh launch
vectors and accepting drains, and both bodies/views by operation ordinal to
1e-12. Those six also reproduce the decisive opponent ray
**2.998794094709 blocks**, only0.001205905291 inside reach. Two match every
compared physical/view timestamp; four retain small generated backpressure
shifts, so they are not timestamp-exact replays. Other draws legitimately take
different trajectories; do not interpret their distance from this one native
realization as a physics error or select only the matching seed.

Both new candidate failures have the same dangerous order:

- m1398: knockback applied6346.055ms; opponent clicks6389.376ms;
  moves6392.668ms; return accepted6396.576ms.
- m1399: knockback applied5846.519ms; opponent clicks5882.644ms;
  moves5886.779ms; return accepted5893.323ms.

The body has not yet moved away when the click is selected. Its sprint contact
also applies the native horizontal attack drag to the received velocity. A
controller must prevent this contact opportunity; simply sending knockback in
time does not ensure that the target moves before attacking.

Evidence: [fresh native captures](poc-20261101/native-schedules/native/reserved/trial.json),
[distribution comparison](poc-20261101/native-schedules/joint-stage-generator/reserved-comparison.json),
[native failure audit](poc-20261101/native-schedules/reserved-native-audit/findings.md),
[held-out path audit](poc-20261101/native-schedules/joint-stage-generator/heldout-native-audit/findings.md).

## Does a better opening solve it?

The old three150-hit successes do not separate cleanly from failures by a4s
pose: successful m1365 and failed m1368 have the same defender height/vertical
speed and differ in horizontal spacing by only about0.0012 blocks. The old
recordings lack the unconditional input-stage hooks needed for the newer exact
schedule crossover, so we have not proved that every old successful acquisition
survives arbitrary later timing.

A complete-state crossover on the newer m1395 calendar gives causal evidence
that acquisition matters. Keep the entire4s prefix, pending packets, selected
timing tuples, policies and startup-cost commitments, then change only the future
sampler seed. Every diagonal reproduces all original result fields:

| Acquisition timing seed | Future timing seed | Frozen candidate | Same-state plain W-tap |
|---|---|---:|---:|
|0|0|19|1|
|0|3|19|1|
|3|0|1|2|
|3|3|1|2|

Scores follow the inherited prefix in these four tests. They do **not** establish
that a good acquisition lasts indefinitely: even the better prefix returns a
hit after19. The prefix also retains the opponent's first two startup-call costs,
so this is a complete acquired-state result, not a pure position-only comparison.

The first seven fresh launch vectors are identical. At the seventh, about half
a millisecond of enqueue timing puts the same impulse on opposite sides of a
client drain. In one path the defender lands before receiving it; in the other
it receives it while airborne, retains more horizontal momentum, and escapes
six attempted contacts. The candidate then misses the next fresh window.

W-tap shows this state is recoverable: its Ctrl pursuit makes a contact at
**2.973391 blocks**, while the candidate misses at**3.002790**. Its next fresh
hit arrives57.45ms earlier and the defender moves before its dangerous click.
W-tap still fails later; this diagnoses a useful behavior, not a lock.

The next tightly scoped model test adds Ctrl pursuit only while the candidate's
own aimed ray is outside3.0 and its existing law holds W. Coasting, strafe,
click timing and queued inputs remain unchanged. It must beat both the old law
and W-tap from the same complete state without wrecking the better opening.
No live controller has been changed. See the
[causal opening audit](poc-20261101/native-schedules/acquisition-tail/opening-findings.md).

## Remaining limits

The replacement is credible enough to test this one causal hypothesis. It is
not a universal latency generator: whole calendars and queue fields are still
conditional on measured workloads, only two development donors supply the
joint per-hit costs, and long-range cost correlation is not fully modeled.
Four fresh runs cannot calibrate rare five-minute survival probabilities.
Any improved model result still needs new, serialized native pairs. We have
neither established a robust lock nor proved vanilla combat infeasible.

## Previous: frozen joint-timing distribution goes to fresh validation

The new timing sampler keeps each measured hit's hurt notification, knockback,
enqueue delay and cue-publication cost together. It uses only the two development
recordings, with no controller or physics change. The80 midpoint draws are done:

| Recorded clock / native arm | Candidate fresh before first return | Matched complete-state W-tap | Native source outcome |
|---|---|---|---|
|m1391 /candidate|26 in16/20;6,9,20 once each;1 unknown after10|3 in17/20;2 twice;1 once|26|
|m1392 /W-tap|93 in13/20;3 twice;9,18,52,55,92 once each|1 in20/20|1|
|m1395 /candidate|19 in5/20;1 in12/20;4,15 once each;1 acquisition unknown|2 in12/20;1 in7/20;1 acquisition unknown|19|
|m1396 /W-tap|4 in18/20;2 unknown after1|2 in20/20|2;277ms native gap retained|

Each target is60s after the same4s quiet opening,150/0 RTT,H1.1/V1/hit8,
3/3 reach,20CPS. Unknowns are unfinished prefixes, not losses. No completed
sample sustains the full horizon. The sampler is no longer predicting universal
locks, and both candidate native counts occur with substantial mass. One native
observation per calendar cannot establish whether those frequencies are right;
nor should we keep fitting until the native count becomes the mode.

Freeze this version and use the two reserved native pairs to test it, without
tuning. The pre-outcome equal-calendar mixture has completed-run candidate
10th/50th/90th percentiles1/18/93 versus W-tap1/2/3, with4/80 candidate and1/80
W-tap unknowns. These are scheduling sensitivities, not confidence intervals or
80 independent native fights. Early/late cue bounds use the predeclared three
seeds per calendar. Fresh host calendars will also be evaluated afterward; that
is held-out conditional timing validation, separate from this advance mixture.

The seed0 smoke matches all four native source scores, but m1395's sampled
trajectory differs by up to0.219 blocks in paired own-view distances. This is
not a failure of conditional exact physics: it is a different timing draw.
Its ending still reproduces the important sequence: knockback arrives, the
opponent clicks in range, then moves. Matching a score alone is not enough.

Evidence: [frozen advance prediction](poc-20261101/native-schedules/joint-stage-generator/prospective-frozen-prediction.json),
[trajectory and mechanism audit](poc-20261101/native-schedules/joint-stage-generator/smoke-native-audit/findings.md),
[prospective criteria and acquisition crossover](poc-20261101/native-schedules/stage-field-validation/semantic/prospective-and-fork-review.md).

## Previous: native failure reconstruction passes; distribution calibration does not

Both candidate failures now regenerate their own inputs correctly under captured
timing. Movement, views, key/range observations, click admissions/consumptions
and all35/27 launch vectors agree through the first return. The strict semantic
adapter also preserves separate hurt/velocity packet arrivals rather than one
stage average. W-tap source recordings still end at1/2 fresh hits, in the same
accepting server drains as native. Unknown counterfactual tails remain unknown.
The six older conditional physics replays remain unchanged across24,683 events.

The frozen20-seed timing ensembles tell a narrower story:

| Native calendar / played arm | Candidate before attack-loop correction | Candidate after correction | Matched W-tap, both ensembles | Direct native label |
|---|---|---|---|---|
|m1391 / candidate|26×1,32×1,69×17,87×1|26×20|3×20|Candidate26|
|m1392 / W-tap|6×19,93×1|unchanged|1×20|W-tap1|
|m1395 / candidate|1×16,4×1,19×3|unchanged|1×4,2×16|Candidate19|
|m1396 / W-tap|4×20|unchanged|2×20|W-tap2, timing flag retained|

All rows use the same64s target/4s quiet opening,150/0RTT,H1.1/V1/hit8,
3/3reach,20CPS, complete-state W-tap fork and primary metric. These are160
model cases on four recorded calendars, not160 native fights. The attack-loop
fix explains the development candidate, but most samples on the separate
candidate validation calendar still fail much earlier than native. Occasional
coverage of19 is not a calibration pass.

The next hypothesis samples each generated forward hit's notification,
knockback receipt, enqueue preparation and own cue service **jointly** from
one measured development tuple. Raw/prep support is complete for the needed
forward triplet; unmeasured cue precision uses declared interval sensitivities,
not invented exact timestamps. Stochastic packet-level coverage is promising
but not yet tested as a full-fight predictor. Controller optimization remains
paused at this gate; no new controller feature or native trial has been added.

Evidence: [ensemble comparison](poc-20261101/native-schedules/ensemble-queue-loop-audit/findings.md),
[semantic native audit](poc-20261101/native-schedules/semantic-diagnostic/native-audit/findings.md),
[joint timing hypothesis](poc-20261101/native-schedules/stage-field-validation/distribution/findings.md).

## Previous: queue eligibility and attack-dispatch errors isolated

The whole-recording receipt→enqueue field now fixes every body/view comparison
through3.6 s in the fresh candidate recording. Native packet timestamps are
matched at their recorded integer-nanosecond precision; an earlier floating-point
lookup alias is fixed, with v1 artifacts retained. This field stays paired with
its own recording's clocks, so a shared host pause is not independently added a
second time. It remains conditional on observed workload, not a calibrated
new-schedule generator.

Two remaining errors come from **starting attack transport at the wrong stage**.
The model generates C02 at the unconditional click-loop opportunity, while the
latency lookup was indexed by the later native send call. That both omits local
work and selects the preceding timing sample. In m1395 a5.052 ms receipt error
reverses attack/movement order within one server drain, changing knockback. In
m1391 a3.239 ms error puts an attack one drain early, while the target is immune.

A frozen diagnostic changes just that one raw arrival in each recording:

| Native calendar;60 s target after4 s quiet opening | Candidate with central queue field | Candidate plus one arrival correction | Matched complete-state W-tap | Native arm |
|---|---|---|---|---|
|m1391|16; unknown at10.695 s|26; return at14.344 s|1; unknown at4.544 s in both|Candidate26|
|m1392|0; unknown at4.242 s|unchanged|1; return at5.191 s|W-tap1|
|m1395|19; return at11.591 s|19; same return drain|0; unknown at4.243 s in both|Candidate19|
|m1396|0; unknown at4.191 s|unchanged|2; return at5.893 s|W-tap2, retained timing flag|

Each selected correction applies exactly once; no extra route-FIFO clamp is
introduced. Unknown prefixes are not completed losses. The return times use
drain start; native attack code executes a fraction of a millisecond later.
**This is causal diagnosis, not a predictive pass:** corrected timestamps come
from the native outcome being explained, and counterfactual arms still encounter
unmeasured source stages. The repaired candidate traces now match all compared body/view/key/range
rows to1e−12 through their first returns (m1395:232A/228B movement rows;
m1391 through14.318 s); m1395 admissions and consumptions also match exactly.
This remains conditional trajectory repair.

The general source-clock fix is now tested: C02 timing is anchored to the
unconditional native attack loop, while other uplinks retain their original
send-call anchor. **No per-match arrival override is used.** Its four saved
traces are byte-identical to the diagnostic repair (8,041 event-object checks).
Min/mean/max reductions of the six duplicate-loop receipt intervals all give the
same completed or unknown primary outcomes. Two256MiB bookkeeping heap failures
were retained and rerun at512MiB; neither changed the model or result.

This also preserves the remaining discrepancy rather than hiding it: m1391 has
three extra normal immune consumes (173 versus native170). Their admission cue
is timestamped at drain start rather than at publication inside the handler;
a386µs difference flips the first gate. m1395 admissions/consumptions match.
An opt-in delayed-publication capability is implemented with46 focused checks;
the unchanged legacy replay still passes24,683 events/9,243 states bit-exact.
It is not enabled in the ongoing queue-only versus queue+loop timing ensembles.
The separate cue diagnostic will use measured own cue timestamps, with no
controller change. A correlated stage-field proposal will first face masked
native packet/drain tests before any new fight generation.

The separate measured-cue diagnostic now removes all three extra m1391 immune
consumes:170A/200B consumes and all completed admission slots match native.
m1395 remains exact at150A/146B consumes. Both candidate prefixes retain exact
compared bodies, views and key/range decisions through their first returns.
This uses conditional own-A cue publication labels; it is not a sampled timing
model. See [cue diagnostic](poc-20261101/native-schedules/cue-diagnostic/findings.md).

The proposed time-only stage interpolation failed its independent masking check:
roughly10–16% of packets select the wrong client drain. Separating generated
OWN_HURT, TARGET_HURT and OWN_VELOCITY represents all480 observed DRAIN
packets exactly, but held-out missing stages still do not predict the right drain.
That supports a narrow semantic timing interface, not a calibrated generator.
See [semantic validation](poc-20261101/native-schedules/stage-field-validation/semantic/findings.md).

No controller tuning or prospective native trials have resumed. A simple opening
snapshot still does not separate prior successes from failures; later failures
after19/26 hits show that maintenance timing also needs explaining. Complete-state
opening×future-schedule swaps remain pending, after same-donor chronology passes.

Evidence: [queue-field audit](poc-20261101/native-schedules/queue-field-diagnostic/findings-v2.md),
[packet-order mechanism](poc-20261101/native-schedules/queue-field-diagnostic/kb-order-findings.md),
[development timing audit](poc-20261101/native-schedules/queue-field-development-diagnostic/findings.md),
[one-leg intervention results](poc-20261101/native-schedules/evaluation/queue-field-arrival-oracle-v1/cases.jsonl).

## Previous checkpoint: a proven queue-boundary error

The controller is still frozen. New server task hooks are deployed, and a fresh
paired recording exposed a specific remaining scheduling mistake: **a received
packet is not necessarily enqueued before the next task drain**. A few
microseconds can select a different drain and therefore a different movement
tick. This is more useful than making the score range merely wide enough.

| Fresh 64 s native check, excluding the same 4 s quiet opening | Candidate | Plain W-tap |
|---|---:|---:|
| Fresh hits before first accepted return |19 (m1395)|2 (m1396)|
| First return from match start |11591.695 ms|5893.115 ms|
| Timing qualification |Clean|Retained 277 ms client-gap flag|

This is not a qualified new performance comparison: the W-tap timing flag stays
attached. The candidate's return is particularly informative. After a fresh hit,
the defender applies knockback at11540.650–11540.817 ms, selects a legal return
at11551.173 ms (own ray2.88150 blocks), and only moves at11589.533 ms. Its click
precedes movement by38.359 ms and reduces the newly received horizontal motion
by the ordinary0.6 contact drag. Later movement cannot retract an already sent
attack. The server accepts it at11591.695 ms.

The conditional model's **first** discrepancy is much earlier:

* A position packet's raw receipt is864.300209 ms in both model and recording.
  A drain begins7.875 microseconds later. Native decoding/enqueue finishes at
  864.521001 ms, so Minecraft handles it in the next drain at872.386126 ms.
  The model incorrectly includes it in the earlier drain. Its first wrong view
  appears at866.803501 ms, despite exactly matching the wire timestamp.
* Later, a knockback packet arrives3.625 microseconds before a drain, but finishes
  enqueueing after that drain unlocks. The model applies it before the defender's
  next movement; Minecraft applies it afterward. This produces the first body
  error at3541.836792 ms. The corresponding W-tap recording still has exact
  body/view rows through the same3.6 s diagnostic window.

The causal check now separates raw receipt from enqueue readiness and changes
only those two identified timestamps. The first intervention removes the first
view error; adding the second removes the first body error. Together they produce
candidate19 and a return in the same server drain as native. The native W-tap
calendar remains2. This is explicitly an oracle diagnostic: the same-seed
counterfactual W-tap stops at an unsupported new source stage, and the candidate
still develops a body error at5971.46 ms and an input error at7133.40 ms.
Matching19 is therefore **not** trajectory parity or a calibration pass.

The final failure mechanism agrees despite those errors: both native and model
let the defender click after receiving knockback but before movement. The model
ray is2.89048 blocks versus native2.88150. The model applies server effects at
drain start; native accepts224 microseconds later inside that drain.

The new packet-readiness option preserves raw arrival separately and passes the
legacy six-recording replay regression:24683 events,9243 scored state rows,
zero bit mismatches. This is a component regression, not new predictive evidence.
The current next step is a whole-recording receipt→enqueue timing field, kept
with that recording's joint clocks, with explicit interpolation uncertainty.
Independent preparation-cost sampling is not justified: even an interval that
does not cross a queue lock can contain a shared174 ms scheduling pause already
represented in the calendar. Within-drain application/cue timing remains a
separate unresolved approximation.

A broader stage-delay/call-cost sensitivity already removes the universal750-hit
prediction, but has not passed the calibration gate:

| Captured clock, 20 seeds each | Candidate fresh prefix (frequency) | Complete-fork W-tap | Actually played native arm |
|---|---|---|---|
|m1391|69×18,26×1,87×1|3×20|Candidate26|
|m1392|6×19,93×1|1×20|W-tap1|
|m1395|1×16,19×3,4×1|2×16,1×4|Candidate19|
|m1396|4×20|2×20|W-tap2, timing flag|

These are v7 diagnostics using only m1391/m1392 donor costs and stage delays,
with complete-state paired forks and generated inputs/packets. All80 cases
resolve, but native candidate outcomes sit in small fractions of the spread,
and the early queue chronology above is wrong. Score overlap is **not** a pass.
A separately frozen first-two-call cost sensitivity (v8) is also complete:
all80 resolve; only one candidate m1391 seed changes, from69 to32. Candidate
m1391 is therefore69×17,26×1,87×1,32×1, with paired W-tap3×20; the other
three rows are unchanged. This startup-cost distinction does not explain the
gap and does not authorize controller tuning.

The reserved prospective pairs remain unused. One warmup failed because its
chunks were unloaded after a long floor-preparation stall; a later disk-reserve
check prevented a match before it started. Both failures are retained. A loaded
chunk preflight then passed, and the pair above ran serialized. Global combat
defaults are restored to vanilla1/1/10; the server is idle and available.

Evidence: [fresh boundary diagnostic](poc-20261101/native-schedules/diagnostic-stage-v6-fresh/findings.md),
[server service and return audit](poc-20261101/native-schedules/service-task-audit/findings.md).

The sections below preserve earlier checkpoints and their limitations.

## Fresh native check and the first wrong prediction

Read-only input hooks are now deployed to the Forge bots. Two fresh64s matches,
with the same4s silent-opponent opening, produced **26 fresh hits before return
for lock8 (m1391)** and **1 for plain W-tap (m1392)**. Both timing checks passed.
These are prefix scores, not total match scores or a claim of indefinite locking.

| Captured calendar | Frozen model candidate | Complete-fork model W-tap | Direct native label |
|---|---:|---:|---|
| m1391 |24, then unsupported timing overlap|2|candidate26, return14345ms|
| m1392 |6|1|W-tap1, return5192ms|

Only the actually played arm on a calendar has a direct native trajectory label.
The other arm is a model counterfactual. The24 prefix is **unknown beyond its
cutoff**, not a predicted24-hit loss. Even its earlier movement eventually differs.

The new clocks reproduce both openings'9/8 fresh hits and all320 movement rows
through4s within1e-12. In m1391 both bodies continue to agree until9165.55ms.
The first mismatch is now explained, rather than attributed to chaotic physics:

* The model creates a position update at tracker start9093.975ms. Its timing
  function is indexed by native **wire-send** times, which occur later.
* The previous wire sample, at9045.192ms, had19.562ms delay. Holding that value
  forward incorrectly predicts receipt at9113.537ms.
* The corresponding native packet was encoded at9094.141ms and received at
  9094.882ms: only0.741ms later. The0.166ms difference between tracker start and
  wire send selects an entirely different delay sample.
* Consequently the model misses an interpolation update, changes the defender's
  aim by about0.10 degrees, and first changes horizontal movement by less than
  0.00005 blocks. By11s, feedback has amplified the discrepancy into a different
  W-release decision. That is why a nearly exact prefix is insufficient.

There is a separate cue-timestamp mismatch. Native hurt notifications become
available inside the client task drain, after diagnostic logging, not at its
start. A0.386ms difference changes one m1391 admission decision just after4s,
although that extra immune contact does not immediately alter movement. The
W-tap source's final return currently agrees to about1ms, but that alone does
not certify the whole pipeline.

## Call-duration sampling check

An individual click-injection duration must not be held constant until the next
historical admission. I tested a frozen alternative: sample individual measured
call costs, retaining spikes, with20 seeds on each calibration calendar. This
also exposes a limitation of keeping all future client stages fixed:

| Calendar | Candidate resolved /20 | W-tap resolved /20 | Resolved primary scores candidate / W-tap |
|---|---:|---:|---|
|m1391|0|7|unknown /2|
|m1392|4|4|6 /1|

Every other run is explicitly unknown because a generated synchronous call
overlaps a later fixed stage;16 runs already fail this condition in acquisition.
No overlaps were clipped, resampled, removed or called wins. This independent
cost distribution also discards measured source dependence and temporal
clustering, so it is a failed scheduling sensitivity test, not a calibrated
performance distribution. It provides no reason to optimize the controller.

## What the first calendar test says

The first evaluator keeps each whole recording's correlated client/server clocks
and wire-delay process, but generates its own inputs and packet contents. It
forks the complete state after the same four-second quiet opening for candidate
and a cue-primed plain W-tap. Historical click counts are never input opportunities.
All settings remain H1.1/V1/hit8, RTT150/0, reach3/3 and20CPS.

These are **diagnostic, uncalibrated** predictions over60s of maintenance:

| Native calendar | Frozen candidate | Matched W-tap | Relevant native result |
|---|---:|---:|---|
| m1364 |150|2|candidate150; companion WT m1363=2|
| m1363 |150|2|WT2; candidate here is a model counterfactual|
| m1368 |11|2|candidate5; companion WT m1369=3|
| m1369 |2|3|WT3; candidate here is a model counterfactual|

This removes the periodic model's universal-success illusion, but **does not pass
calibration**. In particular, m1368's own nine timing-sensitivity points predicted
11–35 candidate hits and2 WT hits, still missing the native candidate5. These
points are sensitivity checks, not a fitted probability distribution or enclosing
confidence interval. No controller optimization follows from them.

## First divergence, not merely final scores

In m1368, the model's first contact occurs at596.983ms; the native selected click
is at485.511ms on the same monotonic clock origin. The modeled bodies remain
exact until the earlier native knockback reaches B. The error precedes maintenance:
the simulated opening has8 fresh hits versus9 natively.

The cause is already visible in click admission. At approximately483.5ms the
modeled opponent is in reach, but the model has no queued click: it admitted and
consumed an empty-air click near434ms. Minecraft instead admitted a click at
474.849ms, which was consumed at485.511ms. Both respect20CPS; their click phases
are different. The model sampled the limiter at frame/head marker times rather
than the actual point after native tracing/aim work and before input admission.

Using a constant offset from Forge's tick-start event is insufficient. One
candidate decision preceded that marker by34.8ms. On another tick, the native
consume opportunity was near the beginning of a51.6ms interval before movement;
the midpoint-style approximation put it much later. These are measured differences,
not parameters selected to reproduce a desired score.

The send-clock audit also found Forge tick-end is later than the actual C03 send
call, sometimes by16.4ms in these calibration prefixes. The refined extractor uses
the observed unconditional per-tick send call and measures uplink latency from
that call to network receipt. Countdown packets with ambiguous repeated payloads
are not treated as valid active-match residual samples.

## Does acquisition explain the old successes?

Not by a simple opening-state rule. The opening audit compares all three native
150-hit successes with the two fresh failures and their W-tap companions. m1368
and m1365 are particularly close at4s: their true defender height and vertical
velocity agree, while horizontal spacing differs by about0.0012 blocks. m1368's
own observed range and hurt-cue age also lie inside the successful recordings'
ranges. m1388 starts substantially closer, so acquisition variation exists, but
it cannot explain every failure from a single snapshot.

We still need the causal test: preserve a complete acquired state, queues,
controller memory and click clocks, then replace only the future joint timing
schedule at a compatible boundary. Do not infer survival from teleporting into
a few matching position/velocity values.

## Next gate

The input hooks now record every actual polling opportunity, including
deferred/empty polls, the original wall-clock value used by W-tap/opening, and
the unconditional native attack loop. The next correction aligns timing samples
with the stage that actually generates each packet. Server queue/enqueue/service
markers are also being added because the old archive cannot identify individual
server task execution costs exactly. No controller or combat behavior changes.

Check contact chronology and both POV geometries, then evaluate reserved fresh
serialized pairs before tuning. The two planned reserved pairs remain unused. Preserve
failed/unsupported evaluations and timing-quality flags. Matching only a broad
min/max score range is not acceptance.

Detailed evidence and protocol:
[preplan](poc-20261101/native-schedules/preplan.md),
[opening audit](poc-20261101/native-schedules/opening-audit/findings.md),
[design review](poc-20261101/native-schedules/design-review.md),
[calendar evaluator](poc-20261101/native-schedules/evaluate.py).
