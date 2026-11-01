# Native timing calibration: work in progress

The periodic-clock prediction is not a robustness result. The unchanged lock8
controller remains frozen while we replace that evaluation with measured timing
opportunities and validate both it and plain W-tap. A physics replay with recorded
inputs and packet timings is a different claim from predicting a new fight.

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

Read-only native hooks will record every actual input polling opportunity,
including deferred/empty polls, the exact wall-clock value used by W-tap and the
opening switch, and the unconditional native attack-loop opportunity. Admission
completion overhead must also be represented because the limiter measures from
completion. The old input/cap/control behavior stays unchanged.

Then collect fresh serialized paired recordings, check opening contact chronology
and both POV geometries, and evaluate reserved schedules before tuning. Preserve
failed/unsupported evaluations and timing-quality flags. Matching only a broad
min/max score range is not acceptance.

Detailed evidence and protocol:
[preplan](poc-20261101/native-schedules/preplan.md),
[opening audit](poc-20261101/native-schedules/opening-audit/findings.md),
[design review](poc-20261101/native-schedules/design-review.md),
[calendar evaluator](poc-20261101/native-schedules/evaluate.py).
