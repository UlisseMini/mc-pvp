# Robust search on calibrated native clocks

The first oracle passed all four development minute tests but failed the reserved
robustness test. It achieved4 locks in23 evaluated draws;3 draws were UNKNOWN.
Even if all57 unrun draws passed, it could not reach the planned64/80 target, so
the evaluation stopped without running them. This is model evidence with
privileged information, **not a deployable bot or native result**. No native
trials or legal-controller parameter search have been started.

Version2 improves the sampled results but also fails qualification:12 locks in22
evaluated fresh draws, versus0/22 W-tap. The weakest calendar has1 lock and5 real
returns in6 draws, so it cannot reach16/20 even if every remaining draw passes.
Two other draws are UNKNOWN. The58 unrun draws are not counted as failures.

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

## Reserved failures and the next controlled comparison

The reserved v1 experiment retained every evaluated failure, including unsupported
timing paths. Fresh counts below for UNKNOWN paths are diagnostic prefixes, not
completed combat outcomes. The unrun remainder is neither success nor failure.

| Calendar | Evaluated | Oracle minute locks | Plain W-tap minute locks | Oracle fresh range | Plain W-tap fresh range | UNKNOWN |
|---|---:|---:|---:|---:|---:|---:|
|m1397|6|2/6|0/6|2–142|2–2|1|
|m1398|6|0/6|0/6|22–103|1–1|0|
|m1399|6|1/6|0/6|6–144|2–2|1|
|m1400|5|1/5|0/5|27–144|2–5|1|

Two exact failure replays exposed planner limitations. First, a plan's remaining
two-tick action could disappear from the next search's one/three-tick action
alphabet. An optional version2 retains and reevaluates that suffix. On the
selected diagnostic m1398seed1000, v1 gets78 fresh hits, v2 gets143 and a minute
lock, and paired W-tap gets1. This is a development comparison selected after
seeing the failure, not evidence of80% robustness. Version2 was frozen for
new seeds2000–2019 and stopped at its predeclared per-calendar futility boundary:

| Calendar | Evaluated | Version2 minute locks | Plain W-tap minute locks | Version2 fresh range | Plain W-tap fresh range | UNKNOWN |
|---|---:|---:|---:|---:|---:|---:|
|m1397|6|5/6|0/6|19–145|2–2|1|
|m1398|6|1/6|0/6|40–143|1–1|0|
|m1399|5|3/5|0/5|53–145|1–2|0|
|m1400|5|3/5|0/5|49–145|2–5|1|

Versions1 and2 use different reserved seeds. Their aggregate rates are not a
paired estimate of the warm-start change; only the isolated diagnostic above
holds its entire setting/sample fixed.

Second, version2's m1398seed2000 failure is exactly replayed:73 fresh hits before
a return versus W-tap1. Its last safe forecast has no new fresh hits and already
exceeds a one-second fresh gap. Keeping the suffix avoids the immediate return,
but the next forecast sees danger just beyond the old horizon. When both choices
are doomed, the current smaller-gap tie-break can prefer **earlier death** because
the gap is measured only until the return. Increasing timing samples alone does
not repair that scoring error. The retained suffix's late miss also depends on
aim: nearest distance is2.992 while aimed-ray distance is3.060. Both must be
reported, rather than calling all such escapes a robust spacing margin.

The next development comparison changes one planning axis at a time: repair
failed-branch ordering and require active continuation before rewarding spacing;
or extend lookahead; or increase the number of timing futures. This searches
ordinary inputs against the same calibrated model, without adding a new bot
rule. A failed restricted beam is not an upper bound on every possible policy.

The16-case comparison is frozen on m1395/m1396 seeds10/11: unchanged version2,
objective-only, horizon-only2s, and particle-only9. Each gets the same240s budget
and paired W-tap. The objective change is optional/default-off and passed403
focused assertions; replay of the saved143-hit version2 word and its W-tap1
control stays exact. Its new launch accounting avoids counting an inherited
pending launch as completion of a new terminal hit.

All observed UNKNOWNs so far concern unsupported client-handler overlap, not
missing packet-delay samples or accepted returns. Two examples have a client
HEAD111/204 microseconds before a queued cue publication finishes. Others have
injection backpressure compress a drain and frame to the same timestamp while
the cue still needs349–757 microseconds. We do not silently invent the missing
scheduling behavior. Real return hits independently fail both qualification
tests; resolving these UNKNOWNs favorably would not rescue those tests.

## Fixed-policy settings sensitivity

A separate calibrated settings screen keeps the existing legal controller fixed
and pairs it with W-tap, without starting parameter optimization. It covers72
profiles: RTT pairs0/0,60/60,120/120,150/0,0/120,60/120,120/0,150/120;
horizontal KB0.9/1/1.1; hit delay8/9/10; vertical KB1 and reach3/3. Four draws per
profile use m1395/m1396 and seeds20/21. A valid512MiB cost pilot took2.903s;
the predeclared2.5s threshold selected this coarse grid rather than the full432.
The earlier256MiB pilot exhausted memory while hashing the seed, before scoring;
it is preserved as a resource abort, not a combat failure. The successful pilot
has118 fresh before return versus W-tap1, with no minute lock for either.

This screen is conditional on fixed recorded host workload. Changed RTTs move
arrival times through the same measured queue fields; this is an explicit
transfer assumption. Unsupported acquisition or continuation remains UNKNOWN.
The grid therefore separates supported failures from uncovered regions, and
cannot establish that a controller is physically impossible in an uncovered
region. Only a promising profile would proceed to fresh sampled confirmation.

The initial reserved test froze version1 over seeds1000–1019 on each calendar
m1397–m1400. UNKNOWN and resource-censored cases remain in the denominator. The
target is at least16/20 locks per calendar, with matched W-tap in every row. Four
host calendars with resampled delays are not80 independent native experiments.
The frozen sampler remains conditional on recorded workload and has not been
validated as a universal counterfactual scheduler for changed ping/KB profiles.

The legal family is prepared but has not been evaluated. Its parameters cover
rendered spacing target, own-motion braking lead, sprint request threshold and
click admission lead. It observes only its own state and rendered/received cues.
Legal search waits for the oracle qualification gate. A model settings sweep may
instead locate a robust oracle region if lock8 does not qualify. Native
promotion waits for a robust legal model result.

Code and evidence:

- [Search contract](poc-20261101/robust-search/oracle/contract.md)
- [Development cases](poc-20261101/robust-search/results/oracle-dev-v1/cases.jsonl)
- [Reserved cases as they finish](poc-20261101/robust-search/results/oracle-reserved-v1/cases.jsonl)
- [Version2 reserved cases](poc-20261101/robust-search/results/oracle-reserved-v2/cases.jsonl)
- [Version2 failure diagnosis](poc-20261101/robust-search/oracle/failure-v2-m1398-seed2000/findings.md)
- [Exact clock optimization audit](poc-20261101/robust-search/audit.md)
- [Calibrated-model background](combo-native-schedules-20261101.md)

The new shared immutable calendar reduces fork overhead without changing event
order or physical state. Default/shared/HEAD stepping match complete native
paths, including changed-action branches, queued publications and rejected
overlaps. The two seed-factory regressions also reproduce every saved outcome
field and the original frozen tape bytes. No live controller or server setting
has changed during this work.
