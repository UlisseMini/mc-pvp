# Combo research — findings, November 1

**There are now two clean native 60-second combo demonstrations: 527 and 535
fresh hits without any return.** They use a simple own-distance feedback
controller, both reaches 3.0, RTTs 0/120 ms, zero vertical knockback and a two-tick
fresh-hit window. Matched plain W-tap breaks after a few hits. A third candidate
run fails during acquisition and has timing flags. A later eight-second-opening
trial fails cleanly after129 maintenance hits, so the lock is not yet robust
to native timing variation. This is a strongly modified profile, not vanilla or an infinity proof.
The temporal-word branches are stopped. The baseline rule is mandatory in
`AGENTS.md` and `combo-research-brief.md`.

## The comparison that now governs the work

The primary task is fresh hits within a fixed time without taking a return hit:
count fresh hits before the first accepted return, and report whether the whole
horizon had zero returns. An accepted damage upgrade breaks the run; it is not
a fresh outgoing hit. Longest later unanswered runs and stricter reach/readiness
checks are separate diagnostics, applied equally to every policy.

Every performance table must include plain W-tap with identical reach, RTT,
combat settings, CPS limit, reaction/aim, duration, acquisition state and handicap.
We report several phases or native repeats. Historical `combo-wtap20` has a
150 ms reaction delay; the new explicit `research-wtap20` baseline uses zero
delay, matching the research controllers. Both remain available.

## Matched model results

The search now branches over A/neutral/D as well as W/Ctrl and clicking. A small
grid covers horizontal and vertical KB multipliers 0.9/1.0/1.1, fresh-hit
intervals 9/10 ticks, opponent RTT 0/60/120 ms and three tick-phase pairs:
54 settings, 162 phase cases. Own RTT is zero, both reaches are 3.0 and the
CPS ceiling is 20. Maintenance comparisons fork the same complete acquisition
state, including queued packets and baseline observation history.

The original oracle guard rejected any opponent reach opportunity or missed
first fresh-damage window. Thus “zero hits” from that search was not comparable
to W-tap's ordinary unanswered-hit count. Saved trajectories have now been
rescored over the same full horizon: after a saved oracle prefix ends, they use
a declared plain-W-tap fallback. This is an offline diagnostic, not a deployed
observation-only controller.

Entries below give fresh hits before the first accepted return at the three
phase pairs, in order: opponent/server (10,25), (25,5), (40,40) ms. Our phase is
zero. Opponent RTT is 120 ms and combat is vanilla in these selected rows.

| Opponent reach / maintenance horizon | Saved oracle → W-tap | Plain W-tap, 0 ms | Hold-W | Historical W-tap, 150 ms |
|---|---|---|---|---|
| 3.0 / 12 s | 10 / 1 / 1 | 4 / 1 / 1 | 2 / 1 / 1 | 4 / 1 / 1 |
| 2.5 / 30 s | 33 / 0 / 0 | 1 / 0 / 2 | 1 / 0 / 3 | 1 / 0 / 2 |
| 1.5 / 30 s | 60 / 1 / 2 | 1 / 1 / 2 | 1 / 1 / 4 | 28 / 1 / 2 |

There is a useful advantage in one favorable phase, but it fails to transfer.
Only the reach-1.5 candidate's first phase survives its entire 30-second horizon.
The reduced-reach rows replay the old saved inputs unchanged in other phases;
they do not reoptimize each phase.

Acquisition also differs across phases: the full-reach (25,5) seed occurs during
an ongoing trade, whereas (40,40) begins from a clean four-hit opening. Candidate
and baselines share the exact seed within each pair, but the three seeds do not
represent the same kind of opening. Short maintenance from one selected seed is
not evidence that every possible acquisition under that phase fails.

Across the full grid, 144 cases acquired a seed and 18 did not. Against matched
zero-delay W-tap, the saved-prefix candidate is higher in 44, equal in 95 and
lower in 5. Neither candidate nor W-tap completes a whole 12-second zero-return
horizon. These are related deterministic cases, not 144 independent statistical
trials. The beam is incomplete and even previously discarded a known successful
trajectory; failed searches do not map a proven physical infeasibility boundary.

See [all 54 matched rows](combo-baseline-tables-20261101.md). Some rollouts stop
at the explicitly unsupported degenerate random-knockback branch after their
first return. Their primary count is determined, but their later totals and
longest-run values are incomplete and must not be used as full-horizon results.

## Native comparison

The matched suite uses 30-second boxing matches, reach 3.0 on both sides,
20 CPS ceiling, RTT 0/120 ms, vanilla combat, perfect aim and the same six-block
start. The hold-W opponent has a declared two-second no-click opening handicap.
Six policies run three times each in rotated order. These are independent native
runs with observed timing variation, not artificially identical OS schedules.

All 18 matches finished. Cells list repeats 1 / 2 / 3.

| Metric | Plain W-tap, 0 ms | Hold-W | W-tap, 150 ms | Fixed word | Word + strafe | Word + cue timing |
|---|---|---|---|---|---|---|
| Fresh hits before first return, including opening | 4/4/4 | 4/4/4 | 4/4/4 | 5/5/3 | 5/5/4 | 4/4/4 |
| Fresh hits after opponent wakes, before return | 1/1/1 | 1/0/0 | 1/1/1 | 1/1/0 | 1/1/0 | 0/0/1 |
| Longest unanswered fresh run, whole match | 4/5/4 | 4/4/4 | 5/4/6 | 5/5/3 | 5/5/4 | 4/4/4 |

No run completes 30 seconds without a return. These controllers do not establish
better sustained maintenance. Settings, click-cap, reach and target checks pass,
but the supplementary clock audit flags intervals above 100 ms in eight whole
matches, including the first hold-W opening. Candidate/W-tap primary windows
have no such long stall; whole-match longest runs remain timing-qualified where
flagged. See [all results and timing qualifications](combo-matched-native-tables-20261101.md).

The first-repeat trace already explains an apparent opening advantage. The
fixed word lands five fresh hits at about 495, 996, 1493, 1991 and 2513 ms,
alternating sprint/normal/sprint/normal/sprint. But the opponent's client selects
it at 2440 ms, before that fifth hit can rescue the spacing. The return is
already in flight. Zero-delay W-tap makes three early sprint hits, pushes the
opponent out of its own reach, and has to wait until about 2594 ms for another
fresh hit. Its first return is later. Both policies get only one fresh hit
after the opponent wakes before taking a return. An extra free opening hit is
not evidence of better sustained denial.

## What W-tap gets right, and what still needs explaining

In one matched model phase, hold-W's normal launch lets the opponent reconnect
early. W-tap restores sprint and supplies enough knockback to delay that return.
However, another strong hit can push the target beyond our next ready window.
The saved oracle instead uses a normal hit in that part of the sequence and
achieves ten fresh hits before a return versus W-tap's four. Other phases erase
the advantage. Contact drag, launch height and the delayed views all change
together; this comparison does not prove that changing one impulse alone is
sufficient.

The native traces expose the complementary failure: keeping our next fresh hit
available can let the opponent's delayed view enter reach first. The controller
must satisfy both conditions at once. “Hit every fresh window” and “use less
knockback” are each insufficient. We need a causal account of the first lost
denial interval, followed by matched tests of that account, before another
controller feature or claimed enabling KB tweak.

The third word repeat exposes an earlier, different failure. Its second scheduled
attack is processed with immunity H=11, one tick too early, rather than H=10 as
in the favorable repeat. The next retry gate opens but no click is injected;
the check occurs around the strict 50 ms CPS boundary. Rejection by the CPS
gate is the supported explanation, although its precise check timestamp was not
logged. The two-slot burst then closes. By the next fresh hit the players have
crossed, changing the horizontal knockback direction and weakening the launch.
W-tap's persistent, frame-polled click gate retries across the missed boundary.

This is an immediate model/control integration obligation: recorded-input physics
parity does not validate which inputs a nominal-clock controller actually emits
under native jitter and CPS pacing. The next diagnostic step is to reproduce that
gate/pacing behavior in the policy adapter and validate it against the recorded
injections, before adding spacing features. The detailed evidence and inference
limits are in [the native diagnosis](combo-feasibility-findings.md). The two weak
model phases are separately examined in [the seed diagnosis](combo-feasibility-phase-diagnosis.md);
neither was already doomed at entry, and delaying the return by 50 ms still did
not produce an extra fresh hit.

## How much of the model is validated

Eight complete recorded-schedule native fixtures cover 0/0 and 0/120 ms RTT,
ordinary W-tap/hold-W, angular movement and failed periodic controllers. Across
them, 5,723 own movement rows, 5,723 remote replica rows, 4,078 click-selection
rows and 2,862 server snapshots have zero compared state-bit mismatches.
Represented generated packet contents and order also match. Across the two
30-second fixtures, 963 selected-ray distances differ by at most
4.44e-16 blocks and 1,249 nearest-box distances by at most 4.55e-13 blocks.

The model is initialized once and generates later states and packet contents.
Recorded inputs and actual task-service schedules are supplied; later native
positions are assertions, not corrections. This validates mechanics under those
schedules, not prediction of future OS jitter. Even zero added ping includes
tick phases, task drains, tracking and interpolation. A packet arriving 0.014 ms
before a drain can still be serviced at the next drain.

Additional angular-combat slices pass at vanilla 0/120 and modified
horizontal/vertical 0.9, hit interval 9, RTT 0/60. A vertical-1.1 fixture has an
exact prefix followed by unsupported random-direction knockback. See
[validation scope and limits](periodic-findings.md), including excluded tails.
Unvalidated profiles and heuristic search failures must not support an
impossibility claim.

The first four seconds of fresh W-tap m1245 and failed-word m1247 also pass:
all 1,419 and 1,380 requested event rows execute, with zero compared state-bit,
packet or numerical reach errors. This includes their first returns and supports
the diagnosis given actual issued clicks. It does not close the policy-pacing
gap described above.

## Watching and reproducing

`/replay m1225` shows the first zero-delay W-tap repeat; `/replay m1227` shows
the corresponding fixed-word attempt under the same settings. Both are failed
maintenance examples, not indefinite-combo demos.

Per-duel experimental settings are available:

```
/duel hold-w boxing kb_vertical=1.1
/rematch kb=vanilla
```

`kb_horizontal`, `kb_vertical` and `hit_ticks` apply equally to both players;
defaults are 1, 1 and 10. Multipliers scale final fresh melee velocity before
S12 quantization. They are not asserted to be Hypixel's constants. The strongly
relaxed profile below has finite native demonstrations, with robustness limits.

The earlier provisional model's 1,000-contact result remains rejected. It omitted
coupled mechanics and failed native transfer. The current model/search evidence
is archived in `combo-matched-model-evidence-20261101.json.gz`, with individual
results and their qualifications preserved.

## New parallel POC: a deliberately relaxed stepping stone

The latest user instruction is to find a native POC under any declared settings,
then walk back toward vanilla. Four GPT6.1Sol approaches plus Astra's vertical
analysis now have isolated [working directories](poc-20261101/COORDINATION.md).
The simple toy thermostat lost the exact-kernel baseline comparison: coasting
also removed sprint knockback, contrary to the toy's independent-impulse
assumption. That controller branch stopped. A/D micro-corrections extended a
finite run, but did not meet the 60-second or robust-baseline gate.

Exact-kernel continuation found a 119-hit, 60-second oracle witness at opponent
reach 1.4 in one phase, versus W-tap's one. The frozen word survives opponent
reach 1.465 but fails at 1.47. This is a boundary of that trajectory, not a
physical impossibility boundary.

The legal worker distilled the witness into an own-visible-hurt-cue temporal
controller. It chooses movement/sprint inputs and preserves scheduled immune
contacts; it never reads the opponent's private client or server state. Its
declared positive model slice is own/opponent reach 3/1.4, RTT 0/120 ms, vanilla
combat, 20 CPS ceiling, six-block start, two-second opponent opening handicap,
and server phase 5 ms relative to our phase zero. Maintenance is scored for
60 seconds after the acquisition period.

| Opponent phase | Legal controller fresh hits | Plain W-tap fresh hits | Legal whole 60 s without return |
|---:|---:|---:|---|
| 20 ms | 120 | 1 | Yes |
| 25 ms | 120 | 1 | Yes |
| 30 ms | 120 | 1 | Yes |

Removing only the scheduled immune contacts reduces all three runs to 17 fresh
hits before return. Their native drag/sprint effects are part of control, not
wasted clicks. Wider phase tests still fail, and one slightly wider starting
gap also lets W-tap lock. All sensitivity rows are retained in
[the legal-controller findings](poc-20261101/legal-controller/findings.md).
Java 8 compilation and 3,723 decision-parity checks pass. These tests used the
model's cue timestamps and regular clocks; they did not establish native policy
realization under sampled cues and tick jitter.

## First native legal-controller comparison: baseline wins

Six serialized matches test the frozen candidate against plain W-tap with the
same prescribed two-second acquisition policy, six-block start, reach 3/1.4,
RTT 0/120, vanilla combat, perfect aim and 20 CPS. The opponent begins clicking
at two seconds. Total duration is 62 seconds, with maintenance scored from two
to 62 seconds. Native schedules are measured, not artificially identical.

| Metric; repeats 1 / 2 / 3 | Frozen legal candidate | Plain W-tap, same opening |
|---|---|---|
| Fresh maintenance hits before first return | 3 / 9 / 8* | 84 / 31 / 87 |
| Full 60-second maintenance without return | no / no / no* | yes / no / yes |
| Fresh hits including opening before return | 6 / 13 / 12* | 88 / 35 / 91 |
| Replay IDs | m1253 / m1256 / m1257 | m1254 / m1255 / m1258 |

The starred candidate run has a 304 ms client stall before its return and fails
the supplementary timing gate. Keep it as flagged evidence, not a clean repeat.
The other primary windows have no interval above 100 ms. All six pass native
reach, click-cap, build and target checks. The candidate does not pass the W-tap
improvement gate, and its old implementation is stopped.

The first candidate's own cue is stamped when an input tick observes hurtTime.
Successive native input times map to wall-clock slots 0,0,1,3,4,5,5,7,7,9: two
movement slots are skipped. Its first maintenance interval therefore coasts for
eight inputs instead of seven and fails to restore sprint for the next fresh
hit. W-tap restores sprint and preserves separation. In the successful model,
the cue was stamped on packet service 43 ms before the first observing input;
this sampling distinction was missing from the nominal adapter test.

Both native policies also admit a third opening contact in a nominal two-slot
burst: a frame queues it just before the next tick closes the gate. This shared
acquisition discrepancy is separate from the candidate-specific skipped slots.
The next bounded test changes only own-tick slot indexing, with opening admission
as a separate ablation; it retains the same movement/click tables. No new spacing
feature is justified before this causal timing diagnosis is tested.

Raw [native results](poc-20261101/native-trial.json), all three archived POVs and
per-match metadata are retained. See [the prefix diagnosis](poc-20261101/legal-controller/native-m1253-diagnosis.md)
and [the timing-only proposal](poc-20261101/legal-controller/timing-contract-proposal.md).
`/replay m1254` shows W-tap's easy reduced-reach lock; it is not the requested
candidate-over-W-tap POC or evidence about full-reach vanilla feasibility.

## Timing ablation: corrected slots still do not maintain the lock

The tick-indexed version preserves the same 200 movement/click slots and opening;
only cue-slot selection changes. All 12 nominal comparisons (four timing switches
and three phases) retain 120/60s versus W-tap's one, and recorded-jitter admission
tests pass. A second native six-match suite uses the same settings as above.

| Metric; repeats 1 / 2 / 3 | Tick-indexed candidate | Plain W-tap, same opening |
|---|---|---|
| Fresh maintenance hits before return | 2* / 0 / 3 | 1 / 31* / 13 |
| Full 60-second maintenance without return | no / no / no | no / no / no |
| Replay IDs | m1259 / m1262 / m1263 | m1260 / m1261 / m1264 |

The first candidate has a 117 ms acquisition stall, although its maintenance
prefix has no interval above 100 ms. The second W-tap has a 288 ms defender
stall before its return. Both are flagged; conditioning only on a clean later
maintenance clock would not make their acquisition/earlier trajectory clean.

The corrected native controller does emit all ten slots after a new cue. But
m1259's intended opening contacts encounter H11/H12 immunity, postponing fresh
hits. At the fixed two-second handoff there are only three fresh hits instead of
the model's four. The old cue starts the teacher word at slot eight; the eventual
fourth cue advances to the next word interval. Even its next sprint hit cannot
rescue the spacing. The acquisition state differs, not just the timestamp label.
See [the measured diagnosis](poc-20261101/legal-controller/native-m1259-diagnosis.md).

This temporal-word branch is stopped. It has not earned another strategy feature
or a vanilla-feasibility conclusion. The next approach derives a feedback law in
a deliberately stronger relaxation, so its response depends on actual visible
spacing instead of assuming the nominal acquisition trajectory.

## Strong relaxation: a model lock with a large baseline advantage

Both reaches are 3.0, RTTs are 0/120 ms, horizontal KB is vanilla, vertical KB is
zero, and fresh hits can occur every two ticks (100 ms instead of vanilla's
500 ms). Both sides have perfect aim, zero reaction delay and a 20 CPS ceiling.
The hold-W opponent gets a declared four-second no-click acquisition handicap.
The following 60-second maintenance scores exclude those free opening hits.
Candidate and baselines fork the same full model state at four seconds, with
W-tap's own hurt history shadowed during acquisition.

| Model clock phases: opponent/server | Own-gap feedback fresh hits before return | Plain W-tap baseline | Hold-W control | Candidate full 60 s without return |
|---|---:|---:|---:|---|
| 10/25 ms | 600 | 5 | 6 | yes |
| 25/5 ms | 600 | 6 | 597 | yes |
| 40/40 ms | 600 | 10 | 8 | yes |

The controller holds W without Ctrl and alternates short A/D pulses to reduce
radial pursuit speed when its own displayed target gets too close. It uses no
server state, opponent POV or stored hit sequence. All 600 hits are normal hits;
every first fresh window is used. The defender's minimum own-POV distance is
over 4.09 blocks, while our maximum is below 2.78. Hold-W also survives one phase,
so it remains a meaningful extra control.

The mechanism is speed matching: rapid normal KB makes the defender retreat
at about 0.198 blocks/tick. Our walking speed can be adjusted between about
0.153 and 0.216 blocks/tick using diagonal input. Own-gap feedback selects the
appropriate mixture. W-tap instead changes sprint/hit strength and loses that
equilibrium. These are nominal model findings; the native checks below separately
test the actual profile, pacing and finite maintenance. See [the frozen policy and derivation](poc-20261101/continuation/strong-relaxation.md).

The first native candidate, m1265, takes a return 145 ms after the opponent's
four-second wake-up. Its own controls match the frozen policy, but the initial
engagement collapses into body overlap. Maximum diagonal braking subsequently
recovers spacing too slowly: own displayed distance is only 1.59 at four seconds,
versus the model seed's approximately 2.5. Recorded native admission is also
slower than the nominal 20 Hz clock. The failed run has acquisition timing flags
and is retained rather than treated as a clean repeat.

The new profile's five-second recorded-input coupled replay completes all 1,796
events, with zero bit mismatches, zero error on 108 nearest-distance checks and
74 selected-ray checks, including the first return. An older vanilla fixture
still passes all 1,765 events. Two adapter corrections were needed: use recorded
W-without-Ctrl keys rather than assuming every W press holds Ctrl, and stop
packet-handler events at the requested replay cutoff. No physics correction
was needed. This validates the recorded transitions, not future OS scheduling.

One attempted baseline, m1266, received vanilla combat because separate manager
commands were dequeued by random filename order. It is excluded from the matched
comparison and retained as a setup failure. The runner now awaits server profile
acknowledgement before starting and checks the match's recorded profile.

## Native feedback POC: two clean 60-second locks

These independent native repeats use the identical declared settings and common
four-second acquisition policy above. Plain W-tap is tested with its existing
sampled hurt-animation cue and, separately, every own S19 hit-status cue (the
model's baseline). Both release W for 50 ms after a cue. The primary window is
strictly [4,64) seconds; free opening hits and the late finish callback are excluded.

| Native metric; repeats 1 / 2 / 3 | Own-gap feedback | Plain W-tap baseline | Plain W-tap, S19 cue |
|---|---|---|---|
| Fresh hits before first accepted return | 2* / 527 / 535 | 7 / 5 / 7* | 1 / 2 / 5 |
| Entire 60 s without a return | no* / yes / yes | no / no / no* | no / no / no |
| Replay IDs | m1265 / m1270 / m1272 | m1274 / m1268 / m1273 | m1267 / m1269 / m1271 |

An asterisk marks a >100 ms client/server interval during acquisition or the
primary prefix: m1265 has defender stalls; m1273 has a 107 ms server interval.
All successful candidate runs have clean full-match clocks. Some failed baseline
runs have later stalls after their first return; those remain recorded but do not
cause the already-observed primary failure. m1266 is the excluded wrong-profile
setup attempt, replaced by m1274.

The opponents genuinely try to hit back. In both successes, their own nearest
distance exceeds 3.0 at every one of 1,200 native attack opportunities, including
ticks with no consumed click. Their 1,167/1,165 actual clicks all miss. Minimum
actionable defender distances are 3.093/3.232 blocks. Our own movement/update
geometry stays in reach throughout maintenance. There are no loaded-ground or
target-guard failures, even over more than 219 blocks of travel.

Late spacing plateaus rather than drifting toward failure. Fresh timing is not
perfect: real capped click/packet timing sometimes produces three-tick gaps,
and once a four-tick gap. We report those missed windows; they are not disguised
as later cooldowns. One success has three sub-3 geometric transients inside an
entity-update loop, each approximately 0.16 ms with no input/frame opportunity;
the other has none. Actionable reach denial and this stricter intermediate-state
diagnostic are kept separate.

Initial overlap alone does not explain failure: a successful run also overlaps
during acquisition, but recovers more separation before wake-up. The next frozen
test changes only the common no-click opening from four to eight seconds. Its
first repeat gives129 fresh maintenance hits before a clean return at22.194s
absolute (14.194s after wake), versus matched sampled-cue W-tap4 and S19 W-tap2.
Thus a longer opening does not eliminate maintenance failure. The completed spread is below; gains and controls stayed unchanged.

Watch **`/replay m1272`** for the cleanest current demonstration. Full measurements
and the finite-horizon qualifications are in [the native audit](poc-20261101/legal-controller/native-m1270-findings.md)
and [the trial results](poc-20261101/strong-native-trial.json). No claim about
vanilla impossibility follows from these experiments.


## Clean failure identifies a packet-phase limit

m1275 is the first eight-second-opening candidate trial. No client/server
interval exceeds100ms anywhere in the match. Its initial failure mechanism is
not a lost fresh hit or changed KB magnitude: the final pre-escape fresh contacts
still occur every two server ticks and emit normal horizontal velocity near.4.

At21.149s, a velocity packet is applied just before the defender's21.151s move
while its ground flag is false. Zero vertical velocity preserves an air-friction
move. Its next two displacements are approximately.3745/.3153 blocks instead
of the usual.2726/.1233. The event repeats about200ms later. Our bot removes all
strafe and reaches its maximum walking pursuit speed, but cannot catch that
burst. Our displayed distance passes3; the resulting nine-tick fresh-hit gap
lets the opponent run back and return a hit.

The two successful full-minute runs contain no such airborne-flag/zero-vy move
during maintenance. The failed run contains18 before its return; earlier ones
recover, while the close pair precedes escape. This comparison identifies a
concrete mechanism to test, not a universal sufficiency proof. A controlled
packet-service perturbation in the same kernel is the next causal check.

The successful held-out m1272 first10s recorded-input replay also passes:
3,696/3,696 event rows, zero compared state-bit mismatches, zero error on310
nearest-distance and186 selected-ray checks. Those checks validate the plant
under supplied clocks; the nominal20Hz click adapter is being extended to
represent physical click admission separately from native queued consumption.


The same explanation transfers to a held-out failed native run, m1280:114 fresh
maintenance hits before return, versus matched W-tap1 and status-W-tap7. All
clocks are clean. Two air-flag velocity bursts201ms apart produce the same
approximately.375/.315 displacements; own range then reaches3.291, followed
by another nine-tick fresh-hit hole. Its frozen input-policy reconstruction has
zero mismatches. This is stronger than an explanation fitted only to m1275.


## Completed eight-second-opening native controls

Same full-reach0/120ms, h1/v0/hit2 profile,20CPS and fixed60s maintenance window
as the previous native POC, except the common acquisition now lasts8s. Three
independent repeats per policy; the three main policies ran in rotated order.
The additional hold-W controls ran afterward, not retrospectively interleaved.

| Strict8..68s metric; repeats1/2/3 | Own-gap controller | Plain W-tap baseline | Plain W-tap S19 cue | Hold-W+Ctrl control |
|---|---|---|---|---|
| Fresh before first accepted return |129 /114 /40*|4 /1 /8|2 /7 /4|5 /5* /1|
| Whole60s without return |no /no /no*|no /no /no|no /no /no|no /no* /no|
| Replay IDs |m1275 /m1280 /m1282|m1276 /m1278 /m1283|m1277 /m1279 /m1281|m1284 /m1285 /m1286|

The starred runs have an opponent acquisition interval above100ms:308.6ms in
m1282 and104.3ms in m1285. All other acquisition/primary prefixes pass this
supplementary gate. m1283 has a115ms own interval later, after its first return;
its primary outcome is clean, with the later full-match flag retained.

The candidate still beats both W-tap references substantially on the prescribed
primary task, but none of these three repeats sustains the full minute. Longer
acquisition is not a robustness solution. The four-second-opening successes are
finite witnesses, not evidence that this feedback law always maintains a lock.

The hold-W control later finds250- and454-fresh unanswered chains in m1285 and
m1286 respectively, after its initial failure. This is valuable evidence that
other states can enter long-lived motion modes; it is not a substitute for its
primary5/1 scores or a reason to hide the simple control. The later454-hit chain
now has an [actionable-reach and contact-drag audit](poc-20261101/legal-controller/native-m1286-late-findings.md).

All12 rows, timing measurements and actual profiles are preserved in
[the complete trial](poc-20261101/strong-native-trial-opening8s.json). Server
defaults were restored to vanilla after the batch.


## Failure mechanism passes the recorded and component checks

The complete m1275 prefix through its first return,22.3s, executes8,233/8,233
recorded-schedule event rows with zero compared state-bit mismatches. All710
nearest-distance and420 selected-ray checks have zero numerical error. The
coupled kernel generates later states and packet contents; recorded inputs and
actual processing clocks are supplied. No physics correction was required.

A separate three-move component check initializes from each of the two native
pre-packet states. Its ordinary branch matches266 state-field comparisons plus
six acceleration checks exactly. Applying the same S12 just after the first
movement instead reduces predicted three-move travel by.330028 and.330027
blocks. This verifies the local ordering mechanism; it does not claim that the
counterfactual saves the full fight. See [the component evidence](poc-20261101/legal-controller/native-m1275-component.md)
and [the full-prefix report](poc-20261101/m1275-coupled-replay-22s.json).

The canonical clock now also has an explicit queued-input mode: physical
admission is capped; native consumption may drain several queued presses in
one tick. Its60 adapter assertions and legacy clock/fork/metric regressions
pass, including14,972 fork comparisons. Original DIRECT-mode results are
unchanged. Admission-call duration and held-out native admission/queue parity
remain unvalidated; this extension alone does not establish future-timing
prediction accuracy.

## Ordinary held sprint: useful mechanism, failed acquisition

The audited later454-hit native chain suggested a simpler mechanism. Holding
Ctrl re-arms local sprint after contact drag, while the server can remain in
normal-hit state because no new sprint transition is sent. Contact ticks brake
the attacker; missed contacts allow faster pursuit. This is ordinary native
client behavior, with no direct sprint-state assignment or forged packets.
That chain tolerates25 zero-Y/airborne-flag bursts. Its prescribed primary score
remains1, versus matched W-tap8 (sampled cue) or4 (status cue); the later chain
is diagnostic evidence, not a matched performance win.

Six model checks then tested acquisition from rest: either constant W+Ctrl for
8s, or4s frozen gap feedback followed by4s W+Ctrl. At8s, complete states and
queues fork into the candidate and a shadowed plain status-cue W-tap baseline.
Same h1/v0/hit2, reach3/3, RTT0/120,20CPS ceiling and60s maintenance throughout.

| Common model acquisition; phases0/10/25,0/25/5,0/40/40 | Constant hold fresh before return | Plain W-tap fresh before return | Full60s zero return, hold / W-tap |
|---|---|---|---|
| Hold from start |599 /600 /584|8 /13 /14|yes in all /no in all|
| Gap4s then hold4s |599 /599 /585|15 /8 /13|yes in all /no in all|

These are nominal DIRECT-input clock results, not a native acquisition forecast.
The subsequent native experiment falsifies that transfer for hold from start:

| Native common8s hold acquisition; repeat1/2/3 | Constant hold | Plain status-cue W-tap |
|---|---|---|
| Fresh before first accepted return, strict8..68s |2 /1 /1|2 /1* /2|
| Full60s without return |no /no /no|no /no* /no|
| Replay IDs |m1293 /m1290 /m1291|m1288 /m1289 /m1292|

The star marks an acquisition clock interval over100ms. All three main hold
runs and the other two baseline runs have clean clocks throughout. m1287 is
retained separately as a startup pilot (primary1). Its exclusion and replacement
were prescribed before its outcome: the scheduler started while other clients
were loading, so the required quiet interval had not occurred. The initial
six-case batch alternated pair order; replacement m1293 ran last. The runner now
requires all eight current-build clients ready plus10s quiet before starting.

**This branch fails the W-tap improvement gate and is stopped.** No extra hold
strategy features were added. Longer native chains after earlier trades do not
repair its failed prescribed acquisition. The frozen gap controller remains the
native POC; a nominal simpler model winner is not substituted for it.

Clean failures m1290/m1291 have73 free opening fresh hits and about19.34 admitted
CPS, versus76 opening fresh in the nominal model. Neither has a defender
airborne/zero-Y burst before the first return. Their first local contacts occur
at447/437ms, from the same own X position4094.910117592, with native selected-ray
distances2.823370/2.801765. Both first sprint responses are bit-identical:
defender X4096.250263896769→4097.022738915746→4097.419030326659.
By about1s, own nearest distance is about.37 and the opponent's about2.15.
Nominal model openings also pass through this close state. Its presence alone
does not explain the failed transfer: ideal50ms admission later recovers the gap,
whereas slightly slower admission can prevent that recovery.

A fresh held-out coupled replay of m1290 through8.3s, including its first return,
matches3,071/3,071 supplied-schedule event rows with zero state-bit mismatches.
All165 nearest-distance and158 selected-ray comparisons have zero error. There
are no unsupported events or unexecuted tails. No physics change was needed.
This validates the mechanics on the failed opening while leaving the nominal
clock/admission forecast unvalidated. See [replay summary](poc-20261101/m1290-coupled-replay-8s-summary.json),
[native trial](poc-20261101/hold-native-trial.json), and
[clean opening audit](poc-20261101/legal-controller/native-m1291-early-contact.md).

The bounded queued-input ablation identifies a sufficient failure mechanism.
Only the physical admission interval changes, on both clients; the control,
profiles, three phase pairs, common8s acquisition and60s maintenance stay fixed.
Every row forks the complete acquired state into hold and shadowed plain W-tap.

| Shared physical admission interval | Hold fresh before return | Plain W-tap fresh before return | Hold full60s zero-return |
|---|---|---|---|
|50ms|599 /600 /584|8 /13 /14|yes /yes /yes|
|51ms|1 /1 /1|1 /1 /1|no /no /no|

First contacts, the first five launches and the1s states are identical between
the two admission settings within each phase. The51ms case then gets73 opening
fresh hits instead of76, matching the clean native opening count. By8s its own
gap is0–.16 and the defender's1.62–1.77: denial was never established. None of
these openings requires an airborne/zero-Y burst. At one phase the initial
defender response matches the native X positions above exactly.

This is a causal fixed-clock ablation, not a claim that51ms is the actual native
schedule. Native clicks average about19.34CPS and have jitter; the51ms model's
frame-polled cadence is about19.05CPS. The important corrected conclusion is that
slightly noncommensurate admission is sufficient to break this constant-input
acquisition. Earlier50/51ms tests left the **gap-feedback** controller successful;
they did not establish cadence irrelevant to all controllers. Ordinary held
sprint lacks feedback to repair this changed knockback/braking balance.
