# Combo research — findings, November 1

**There is still no native indefinite-combo demonstration or vanilla impossibility
proof.** The earlier full-reach controllers fail the matched W-tap gate. The new
parallel, relaxed-scenario effort has a legal own-cue model controller with a
60-second lock in a declared clock window, but its first native comparison loses
to plain W-tap. A concrete cue-timing transfer error is now isolated for testing.
Its wider phase failures remain visible. The baseline rule is mandatory in
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
S12 quantization. They are not asserted to be Hypixel's constants, and no tweak
has yet earned a robust native combo-maintenance claim.

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
