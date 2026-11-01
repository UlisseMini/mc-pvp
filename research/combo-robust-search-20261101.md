# Robust search on calibrated native clocks

Latest: extending the oracle's forecast from1s to2s passes all8 fresh
confirmation draws, versus7/8 for the1s oracle and0/8 for matched W-tap.
The longer forecast is frozen for reserved qualification on four other calendars
and new seeds3000–3019. That test is in progress; no legal-controller or native
promotion follows from these tuning results alone.

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

The controlled development comparison changes one planning axis at a time: repair
failed-branch ordering and require active continuation before rewarding spacing;
or extend lookahead; or increase the number of timing futures. This searches
ordinary inputs against the same calibrated model, without adding a new bot
rule. A failed restricted beam is not an upper bound on every possible policy.

The16-case comparison is complete on m1395/m1396 seeds10/11: unchanged version2,
objective-only, horizon-only2s, and particle-only9. Each gets the same240s budget
and paired W-tap. The objective change is optional/default-off and passed403
focused assertions; replay of the saved143-hit version2 word and its W-tap1
control stays exact. Its new launch accounting avoids counting an inherited
pending launch as completion of a new terminal hit.

| Calendar / seed | Plain W-tap fresh | Version2 fresh | Objective-only fresh | Horizon2s fresh | Particles9 fresh |
|---|---:|---:|---:|---:|---:|
|m1395 / 10|2|149 lock|122 lock|150 lock|150 lock|
|m1396 / 10|2|149 lock|4 return|149 lock|149 lock|
|m1395 / 11|1|16 return|121 lock|149 lock|16 return|
|m1396 / 11|2|1 UNKNOWN|4 return|1 UNKNOWN|1 UNKNOWN|
|Minute locks / all4 draws|0/4|2/4|2/4|3/4|2/4|

All four arms have identical timing-tape, acquisition-state and policy-history
hashes, and exactly equal W-tap/unchanged-controller control outputs. UNKNOWN
prefixes are not completed physical losses. No arm exhausted its resource cap.
The objective change trades away both m1396 outcomes and is rejected; nine
particles adds work without fixing either nonlock. Only the2s horizon is selected
for fresh confirmation, with the original objective and three particles.

The initial exploratory selection rule required improvement on both calendars.
After seeing H2 and before inspecting K9, we explicitly relaxed it to improvement
on at least one calendar with no regression on either. That is an adaptive
selection decision, not a preregistered finding. All outcomes and the amendment
are retained in the [selection review](poc-20261101/robust-search/legal/oracle-objective-review.md).
Confirmation compares the frozen H2 and v2 on seeds12–15 on both tuning calendars;
reserved qualification still requires16/20 on each m1397–m1400 calendar, using
new seeds3000–3019. Selection and confirmation are not robustness qualification.

Confirmation is complete, with no UNKNOWN or resource-censored outcome:

| Calendar / seed | Plain W-tap fresh | Version2 fresh | Horizon2s fresh |
|---|---:|---:|---:|
|m1395 / 12|2|150 lock|150 lock|
|m1396 / 12|2|149 lock|149 lock|
|m1395 / 13|1|150 lock|150 lock|
|m1396 / 13|2|149 lock|149 lock|
|m1395 / 14|2|111 return|150 lock|
|m1396 / 14|2|149 lock|149 lock|
|m1395 / 15|2|150 lock|150 lock|
|m1396 / 15|2|149 lock|149 lock|
|Minute locks / all8 draws|0/8|7/8|8/8|

All eight pairs have exactly equal source-state/history hashes, timing tapes
and W-tap/old-controller outputs; both arms used the same source hashes. H2
rescues a second distinct failure without a confirmation regression. This is
enough to proceed with its frozen reserved test, not to claim80% reliability.
The [recorded admission decision](poc-20261101/robust-search/confirmation-decision.json)
keeps the configuration and untouched evaluation seeds explicit.

On the rescued m1395seed11, H2 changes inputs about201ms earlier than H1.
The first14 fresh-hit timestamps are identical; H2 then delays the15th by52.5ms
and maintains149 fresh, versus H1's16 and W-tap's1. Its longer sampled tail
reveals risk and prompts earlier release/strafe control. This does **not** show
foreknowledge of the exact eventual return: planner futures differ from the
evaluation future, and the1s horizon already included that actual return time.
See the [paired decision audit](poc-20261101/robust-search/oracle/horizon-m1395-seed11-review/findings.md).
The rejected objective instead rewards wider spacing after only two forecast
completions, sacrificing a third available hit and then losing contact; see the
[regression audit](poc-20261101/robust-search/oracle/objective-m1396-seed10-review/findings.md).

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

The72-profile screen is now complete: neither the frozen legal controller nor
W-tap produces a minute lock in288 draws. Among203 pairs with completed outcomes
on both arms, the controller wins24 fresh-count comparisons, ties152 and loses27.
There are85 unresolved pairs; they are not discarded from qualification.

| All72 profiles,4 draws each | Frozen legal controller | Plain W-tap |
|---|---:|---:|
|Active minute locks / all draws|0/288|0/288|
|Shared acquisition UNKNOWN|75|75|
|Continuation UNKNOWN|10|4|
|Completed paths with an accepted return|203|209|

The best fully supported four-draw profile is still the existing lock8 setting,
not a new region. It beats W-tap in fresh count in all four draws but never locks:

| lock8 calendar / seed | Frozen legal fresh before return | Plain W-tap fresh before return |
|---|---:|---:|
|m1395 / 20|118|1|
|m1395 / 21|19|1|
|m1396 / 20|4|2|
|m1396 / 21|4|2|

Both m1396 arms take their first return at the same time despite the fresh-count
difference. The long118-hit sample is therefore not evidence of reliable
maintenance, and other settings do not justify a native trial. The strong
dependence on the full inherited opening/calendar remains. See the
[full settings review](poc-20261101/robust-search/legal/fixed-settings-review/findings.md)
for per-profile spread, support counts and baseline comparisons. This is a
negative result for the frozen law under a conditional timing transfer, not a
proof that other controllers or unsupported settings cannot work.

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
