# Native timing calibration: work in progress

The periodic-clock prediction is not a robustness result. The unchanged lock8
controller remains frozen while we replace that evaluation with measured timing
opportunities and validate both it and plain W-tap. A physics replay with recorded
inputs and packet timings is a different claim from predicting a new fight.

## Current: queue eligibility and attack-dispatch errors isolated

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
This remains conditional trajectory repair. The next change must correct the general send-clock basis, not retain per-match patches.
A separate within-handler S19 cue-publication error is also proven:386 µs changes
one admission predicate in m1391. It will be modeled independently of control.

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
