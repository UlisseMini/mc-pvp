# Robust search on calibrated native clocks

The first fixed oracle passes all four development minute tests. This is model
evidence with privileged information, **not a deployable bot or native result**.
The frozen80-draw reserved evaluation is running; no robustness claim yet.

| Calendar / timing seed | Oracle fresh hits before return | Plain W-tap | Unchanged controller | Oracle minute lock |
|---|---:|---:|---:|---:|
| m1391 / 0 |147|3|26|yes|
| m1391 / 1 |149|3|26|yes|
| m1392 / 0 |146|1|93|yes|
| m1392 / 1 |149|1|93|yes|

Every arm starts at the same complete4s physical acquisition, including queued
clicks, packets, timing commitments and primed W-tap history. Settings: RTT150/0,
reach3/3,20CPS, horizontal KB1.1, vertical KB1, fresh delay8ticks. Maintenance is
60seconds. A lock requires no accepted return, at least120 fresh hits, and no
fresh-free gap over800ms including boundaries. Opening hits are excluded.

The oracle uses the actual current full state and recorded future host calendar.
It does **not** plan with the evaluation future random seed: each decision tests
three independently keyed timing futures, preserving already committed work.
It searches W/coast/Ctrl, A/D/neutral and ordinary click gating, replanning at
each actual own HEAD. Width2, depth2,1s forecast, later actions held3HEADs; the
unchanged feedback continuation is retained independently of beam pruning.
Future host-clock knowledge is privileged; this is a conditional-calendar oracle.
Neither this result nor any failed finite beam proves general feasibility limits.

The first147-hit trajectory has140 normal and7 sprint fresh hits; its largest
fresh gap is691ms. It retains feedback on1157/1200 decisions and overrides43.
A cheap replay of its saved controls exactly reproduces all1200 HEAD timestamps
and all147 hit timestamps. All seven sprint hits land while B is server-grounded.
W release/resume can re-arm sprint through double-tap behavior; Ctrl requests
alone do not identify sprint hits. These are mechanism clues for parameter
search, not yet an own-observation recovery rule.

The reserved test freezes this algorithm over seeds1000–1019 on each calendar
m1397–m1400. UNKNOWN and resource-censored cases remain in the denominator. The
target is at least16/20 locks per calendar, with matched W-tap in every row. Four
host calendars with resampled delays are not80 independent native experiments.
The frozen sampler remains conditional on recorded workload and has not been
validated as a universal counterfactual scheduler for changed ping/KB profiles.

The legal family is prepared but has not been evaluated. Its parameters cover
rendered spacing target, own-motion braking lead, sprint request threshold and
click admission lead. It observes only its own state and rendered/received cues.
Search and any settings sweep wait for the oracle qualification gate. Native
promotion waits for a robust legal model result.

Code and evidence:

- [Search contract](poc-20261101/robust-search/oracle/contract.md)
- [Development cases](poc-20261101/robust-search/results/oracle-dev-v1/cases.jsonl)
- [Reserved cases as they finish](poc-20261101/robust-search/results/oracle-reserved-v1/cases.jsonl)
- [Exact clock optimization audit](poc-20261101/robust-search/audit.md)
- [Calibrated-model background](combo-native-schedules-20261101.md)

The new shared immutable calendar reduces fork overhead without changing event
order or physical state. Default/shared/HEAD stepping match complete native
paths, including changed-action branches, queued publications and rejected
overlaps. The two seed-factory regressions also reproduce every saved outcome
field and the original frozen tape bytes. No live controller or server setting
has changed during this work.
