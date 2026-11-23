# Contact-cycle controller: falsifiable implementation specification

This is a proposed experiment, not an established infinite-combo solution. It
uses normal W/Ctrl and capped physical clicks, perfect local-view aim, no
jumping or strafing, zero artificial reaction delay, and no opponent-client or
server state during control. Both sides have a 20 CPS ceiling. Native three-POV
logs are available only for diagnosis afterward. Implementation and native
diagnostics below have not yet established a sustainable full-reach lock.

The current objective and all user hints are recorded in
`research/combo-research-brief.md`. Own reach is required at the next **native
fresh-damage window**, not throughout immunity. Earlier stronger continuous-own
metrics are retained as secondary diagnostics, not the primary success rule.

## Main hypothesis

### Capability curriculum, 2026-11-01

The user's methodological correction supersedes the variant screen below.
The unfinished `orbit-forge-cycle-20261101` cohort is stopped, with completed
and timing-excluded rounds preserved. Do not resume it as an algorithm search.

The first capability is **maintenance**, using a native-reach candidate and a
reduced-reach hold-W opponent. All other mechanics remain native: vanilla
knockback, both players' real local collision boxes and view delays, 20 CPS
ceilings, ordinary input handling, and no jumps or strafes. Use a six-block
resting start with the existing setup countdown, one serial match, and 20/20 ms
added RTT. JVM phases are recorded rather than claimed to be deterministic.

The controller receives only its own native observations. All three POVs are
used afterward to check the proposed mechanism and the actual failure cycle.
An opponent client's reach observation must never become a live feedback input.

`combo_curriculum.py` runs one declared test and freezes policy, code and
runtime identities. It cannot rank a grid or automatically promote a winner.
Its reduced-reach allowance is separate from the competitive tournament guard,
which still rejects non-3.0 reach. A passing maintenance probe requires, from
the **predeclared third fresh hit**, at least 20 seconds and 20 fresh contacts,
no accepted reply (including upgrades), contact gaps no longer than 1 second,
own reach at every native readiness/send-time bracket, and the candidate
continuously outside the target's own reach, with no >100 ms geometry-data gap. An
early good streak followed by failure or standing out of range cannot pass.
The initial three probes used a weaker defender-denial-only criterion. The
user then clarified that own reach is required at readiness, not during the
entire immunity period. The stronger retrospective audit is
`research/curriculum-strict-audit-20261101.json`; the current primary analysis
is `research/fresh-window-audit-20261101.json`. All three probes still fail a
sustained lock, so these corrections do not promote or erase a result.
This is finite sampled evidence, not proof of infinite or continuous denial.

Stages:

1. Establish a repeatable reduced-reach cycle. Start at 2.5; if acquisition
   itself fails, reduce only defender reach far enough to isolate maintenance.
2. Confirm the same settings on both arena sides and longer runs; inspect
   contact-to-contact separation, height and actual sprint classification.
3. Increase defender reach only after the preceding stage passes. At the first
   failure, preserve its clock/packet/input history and identify which boundary
   was crossed; change one mechanism justified by that boundary.
4. At native 3.0 reach, separate entering the cycle from maintaining it. Then
   vary initial geometry and RTT, and finally test a straight-running W-tapper.
5. Strategic jumping is an explicitly separate later control axis. Human rod
   combos can demonstrate feasible spacing trajectories but cannot establish
   sword-only acquisition or indefinite maintenance by themselves.

Initial evidence: `m1167`, defender reach2.5, scored57–69 and failed. With only
defender reach reduced to1.5, `m1170` scored40–1 but still failed: its reply
arrived11.436s after start. These are capability failures despite the latter's
large winning margin. Both passed native input/clock checks. The fixed controller
aimed at2.75 local nearest-box distance and reset after each visible outgoing cue.

The next targeted intervention is `orbit_min_contact_ms=700`: after a visible
outgoing hurt cue, wait700ms before issuing more physical clicks. This tests a
slower, predominantly grounded launch cycle, rather than hitting at every legal
window. Its spatial predictor must remove the native .6 contact-drag assumption
when no click is requested. Issued clicks remain untouched by the native engine.
The delay is an intentionally conservative first rung, not a fitted optimal
vanilla solution. Historical human footage shows that a small airborne launch
is recoverable with suitable spacing, so it is not an automatic failure proof.

The700ms intervention (`m1172`) scored37–72 and failed. It kept its target
inside local reach after the third fresh hit, but the defender could also reach
it in440/522 samples. The input/packet trace identifies a concrete coupling:
after the2243ms sprint hit, the spatial controller started sprint at2740ms,
then stopped sprint at2843ms to coast. The next attack arrived3040ms while the
player was still coasting, producing an ordinary .4 horizontal launch rather
than the intended .9 sprint launch. A W reset is not stored sprint readiness;
movement at the eventual attack matters. No claim that this was a successful
grounded sprint cycle is justified.

Under the stricter definition, `m1170`'s impressive40–1 score also contains no
three-fresh-hit continuous one-sided window. Its target was outside the
candidate's local reach in257/537 post-entry samples. The longest sampled
one-sided window was1141ms. Do not call this an indefinite-combo stepping stone
merely because the score was large.

Before another controller trial, compute conditional single-launch movement
envelopes using a recorded opponent trajectory and the verified exact travel
kernel. This asks whether the candidate has enough headroom and acceleration
to satisfy the upper reach boundary at all, then checks the lower boundary.
The fixed-trajectory counterfactual ends at the next opponent-affecting event;
it cannot prove full-fight feasibility or impossibility.

### Distance and momentum state

For aligned players on flat ground, let positive x point from A toward B,
and let `B_A` mean B's rendered position on A's client. Ignoring the common
horizontal hitbox border, the measured horizontal reach advantage is exactly

```
d_B - d_A = (x_B - B_A) + (x_A - A_B).
```

At approximately constant velocities and effective view ages tau,
`d_B-d_A ≈ v_B*tau_BA + v_A*tau_AB`. A advancing and B moving backward from
its own facing direction make both terms positive. This is a displacement-over-
view-lag statement, not an intrinsic extra reach or a universal .5-block bonus.
For real observations, shortest eye-to-expanded-box distance also includes a
height correction; packet timing and interpolation vary the effective ages.

The controller's objective is a viable region in **distance, velocity and
remaining immunity**, not
one constant target distance or maximum knockback. A strong launch can violate
the upper boundary by pushing B beyond3; too little launch or over-pursuit can
violate the lower boundary on B's screen. Native click drag, acceleration and
sprint packet state constrain which corrections are actually available. The
readiness-based band is primary; progress and fresh hits prevent a stationary
or no-attack solution from being counted as success. A brief upper-bound
excursion during immunity is allowed if the next native damage window is met.

`fresh_window_analysis.py` brackets readiness using native target immunity
snapshots rather than nominal500ms or the policy's click gate. It maps the
bracket to a local input interval using the full observed contact-to-server
delay envelope and reports unknown/mixed geometry honestly. Its initial
milestone also requires the next hit within two nominal ticks of the bracket,
so an arbitrary700ms gate cannot redefine an inconvenient damage window.
The per-window evidence is preserved even when this conservative certificate
fails. Input-to-server counterfactual arrival remains uncertain.

### Native diagnostic update, 2026-11-01

The first six 30-second native scenarios against W-tap with attacks disabled
for its first 2,000 ms all passed the declared timing/input gates. Both sides
had the same 20 CPS ceiling, perfect local aim and three-block reach. These
are mechanism tests, not first-to-100 victories or evidence of an indefinite
lock. The complete frozen cohort is
`experiments/orbit-wake-mechanism-20261101.json`; the native input-to-server
linkage audit is `research/orbit-native-mechanism-20261101.json`.

| Intervention | Native matches | Candidate-first accepted scores |
| --- | --- | --- |
| Acquisition W-taps only | m1141, m1146 | 49–50, 44–41 |
| Enter normal mode after two cues, continuous clicks | m1142, m1143 | 60–74, 49–47 |
| Same entry, selective clicks | m1140, m1145 | 42–41, 44–47 |

In m1140 the first two fresh hits were sprint hits at 563 and 1,061 ms. The
next fresh hit was normal at 2,067 ms; the opponent replied at 2,115 ms, only
115 ms after its attack enable time. All six linkage audits had zero matching
errors. Each maintenance variant delivered only one or two fresh normal hits
during its requested maintenance periods. Normal mode was executed, but did
not establish a sustained lock.

Two independent problems motivate the next controls. First, releasing Ctrl
after a local contact also makes the pursuer walk. Native local sprint and
server sprint are different states: holding W+Ctrl can restore local sprint
after contact without sending another START, even though an accepted server
attack cleared server sprint. `orbit_maintenance_ctrl=1` tests pursuit with
Ctrl held; actual server hit classification must still be checked offline.

Second, acquisition was too deep: m1140's first hit had only 2.179 blocks of
true center separation. Later the second airborne sprint hit opened a large
gap, and the target landed before the next contact. An initial wait followed
by W+Ctrl at a declared **local nearest-box distance** (3.6 or 3.9 blocks)
tests a different opening geometry without a deliberately normal standing
opening hit. `orbit_repair_every=2` tests one W reset after even-numbered
outgoing cues, aiming for sprint/normal alternating contacts. Cues remain
imperfect observations; server logs determine what actually occurred.

This alternating hypothesis comes from a short, approximate cycle using
measured impulses: about 2.543 blocks of target displacement over ten moves
after the first grounded sprint launch, then 1.311 after an airborne normal
launch. Continuous local sprint contacts give about 3.789 blocks of pursuer
movement over twenty moves. Their near balance nominates an experiment; it
does not certify a periodic orbit. Server vertical motion, actual contact
times, landing and view delay can change these quantities.

The approximate whole-duel model has not produced a sustained lock in the
screened variants, including entry after one cue and Ctrl-held maintenance.
Its exact travel component tests do not establish full-duel accuracy. Native
tests must check the proposed contact cycle, and a failed screen is not a
proof of impossibility.

Separate acquisition from maintenance. Sprint knockback can acquire height and
spacing, but repeating the strongest hit at every fresh window need not preserve
either. Test mostly normal hits during maintenance, using sprint hits only as a
separate, explicitly tested repair action. Suppress unnecessary forward-motion
contact drag and allow locally verified backward-motion drag. Control distance
during immunity, before the opponent generates a return contact.

The prior motion-burst study already verified 1,720/1,720 intended recoil drags.
It also showed that suppressing immune clicks loses critical upgrades and can
reduce accepted-hit score despite a fresh-hit advantage. Report both outcomes;
do not optimize the existing scoreboard as a proxy for reach denial.

## Small first implementation

Use Java 8 final classes with public final fields, constructors, enums and
ordinary collections; no records or switch expressions. A pure controller can
have `decide(Input)`, `onInputIssued(...)`, `onMovementCompleted(...)`, and
`reset()`. Bookkeeping advances on actual movement updates, not repeated render
polls. The click limiter is shared by every click reason and advances only when
physical input is actually issued.

Input contains:

- Local timestamp and movement-update serial; native input-poll flag.
- Own current position, motion, ground and sprint flags, previous W/Ctrl inputs.
- Opponent rendered position/AABB and velocity estimated from its visible
  history; no opponent motion fields or private interpolation target fields.
- Ordinary current native target selection, local miss counter and critical
  eligibility. These are observations of our own normal input opportunity.
- Fresh visible outgoing/incoming hurt cues and their local timestamps; own
  actual consumed contact events and issued input times.
- Known configured RTTs and our latest known local sprint transition. Tracking
  our own emitted sprint-state transition is allowed; it is our own output,
  not evidence of the current server sprint state.

An outgoing hurt cue is a candidate landed-fresh cue, not infallible proof of
our damage: fall damage can produce a hurt status. Associate it with a recent
own contact using the known latency interval. Mark ambiguous associations and
do not silently count them as confirmed outgoing hits. Native offline scoring
uses actual fresh server events. This matters for acquisition counters as well
as attack timing.

The first controller has only ACQUIRE and NORMAL modes:

1. ACQUIRE is the existing same-build 20 CPS one-tick W-tap baseline. Maintain
   a count of associated outgoing fresh cues since the latest incoming cue.
2. After two associated outgoing cues with no intervening incoming cue, enter
   NORMAL. Finish an already active release normally, then hold W continuously
   and hold Ctrl up. Cancel future scheduled W resets. Do not add spacing yet.
3. In NORMAL, use the window/recoil click rule below. After an actual local
   contact, Ctrl-up allows local sprint to remain off while W stays down. The
   next completed movement must confirm local sprint off; record this as the
   transition boundary. A click before that transition may still be a sprint
   hit. A later attack ordered after our transmitted STOP should be normal,
   absent a newly transmitted START. Verify this inference offline.
4. An incoming fresh cue returns to ACQUIRE and clears the count. If no outgoing
   cue arrives for 1,000 ms, return to ACQUIRE. These are recovery rules, not
   definitions of success or of the end of a continuously denied interval.

The minimal first ablation should keep continuous clicks and change only this
mode-dependent W/Ctrl behavior. A second cell adds selective clicking. Otherwise
the experiment cannot attribute a difference to normal hits versus click drag.

### Click rule at the ordinary native input poll

Use one global monotonic limiter: `nowNs - lastIssuedNs >= 50_000_000`. No
catch-up burst, no fresh-window reset of the limiter, no direct C02 packets.

For an associated outgoing cue at local time c, the nominal next useful local
contact time is `c + 500ms - ownRTT`. This is a phase estimate: server tick
decrement, delivery and cue sampling produce uncertainty. Initially start the
window one native tick early, and keep it open until a new associated cue.
Use milliseconds for this estimate and movement serials for W/Ctrl sequencing.

- ACQUIRE: continuous capped clicks, including misses and immune contacts.
- NORMAL/window: capped clicks, including misses, with the current native aim.
- NORMAL/outside window: permit a recoil click only at a native input poll,
  with a legal current local contact, local sprint active, and current radial
  motion toward the rendered opponent below -0.005 blocks/tick. Require that
  the next 50 ms click slot precede the window start. This is 50 ms at 20 CPS,
  not the existing controllers' hard-coded 100 ms reservation.
- Otherwise suppress the click. Charge a click only after actual injection.

Local critical attempts are OFF in the initial denial experiment. Add them as
an isolated later score-oriented ablation, since an accepted upgrade can consume
server sprint without creating a fresh knockback, while a local contact also
changes movement. A grounded maintenance player has no normal falling-critical
opportunity anyway. An actual native contact is required for local drag; a
missed physical click is not recoil braking.

## Second implementation: three movement choices and explicit sprint repair

Only add this after the first implementation achieves its intended native
normal-hit transition. Actions are SPRINT `(W=true, Ctrl=true)`, WALK
`(true,false)` and COAST `(false,false)`. Their names are desired inputs, not
claims about current native sprint state.

**Vanilla double-tap trap:** Ctrl-up alone does not cancel existing sprint.
Conversely, a short COAST followed by W can start sprint even with Ctrl up.
Track the vanilla seven-update double-tap timer from actual issued W edges and
observed local sprint/contact transitions. If its state is uncertain, carry
both possible states. Never assume COAST-to-W means walking. There are two
safe initial implementations:

- Restrict spacing to choosing continuous WALK versus SPRINT between whole
  cycles, with no COAST-to-W transition until the inferred timer expires.
- Allow COAST but label an unexpected local sprint as NORMALIZE. Hold Ctrl up
  and permit one otherwise-immune contact while grounded and away from the
  next fresh window to stop local sprint, then verify STOP transmission before
  relying on a normal hit. If no contact is available, either wait until the
  timer expires or explicitly classify the next hit as uncertain/sprint.

Do not repeatedly release/resume W to cancel accidental sprint: that can keep
re-arming the same double-tap mechanism. Do not write the timer or sprint flag.

Use a two-movement-update lookahead over the three legal input choices, not a
whole-duel beam search. Own movement uses ExactTravel with the required local
entry-state adapter and input ordering. The opponent projection is a small
visible-motion estimate with uncertainty, not an exact latent-state model.
Each decision logs the projected band and chosen action. Prefer the action
keeping the predicted gap nearest the band center; tie-break by preserving the
current action. Use 0.03-block hysteresis as a declared initial engineering
value, not a fitted physical constant.

For the first spacing variant, when the band estimate is empty, chase with W
but mark maintenance confidence lost; do not invent a safe band. When the gap is
above the band, accelerate; below the band, coast only subject to the sprint
state constraint above. A 100 ms own-motion lookahead is distinct from the
longer time for our motion to become visible to the opponent. Estimate that
visibility delay from known latency plus measured tracking/interpolation
uncertainty and our own position history. Begin a spacing correction during
immunity; a correction at the next fresh contact is often already too late.

SPRINT_REPAIR is a separate third intervention, initially OFF. Request it only
when the next normal-hit cycle is predicted to lose the height/spacing band,
the target is descending, and the sprint-hit projection still permits a later
contact. A useful initial diagnostic height threshold is 0.8 blocks above our
feet, but this threshold alone is insufficient: a low target far away does not
need a stronger horizontal impulse. Treat this threshold as tunable policy,
not a theorem. Do not repair with sprint if target height is already rising or
the target is moving out of pursuit range.

To arm a deliberate sprint hit: release W/Ctrl for one completed movement;
resume W/Ctrl for one completed movement; only then use the following native
input poll for the planned attack. ATTACK precedes movement/sprint transmission
within a tick, so an attack in the resume tick is too early to depend on that
START. Suppress optional/critical contacts during arming. After the planned
contact hold W and Ctrl up, normalize through the actual local contact/STOP
sequence, and return to normal maintenance. Keep the global click limiter.
The estimate must also include a possible incoming or previously queued return;
new knockback cannot cancel a contact already generated by the opponent.

## What geometry and an orbit require

Use distance from each observer's eye to the opponent's *expanded rendered
box*. A cached chosen ray can miss even when another perfect aim direction
would hit. All-aim denial needs nearest-box distance greater than 3; our attack
needs actual ordinary selection at distance at most 3. Server position is a
third, different geometry and still determines server acceptance.

For a collinear horizontal sketch, let g be true center separation, h be B's
height above grounded A, and approximate horizontal box half-width plus border
as 0.4. For h >= 0:

```
R_A(h) = 0.4 + sqrt(9 - max(0, h - 1.72)^2)
R_B(h) = 0.4 + sqrt(9 - max(0, h - 0.28)^2)
```

Use these only where the square-root argument is nonnegative. They are axial
intuition; the real controller/analyzer uses 3D AABBs. Same-time one-sided reach
needs `R_B < g <= R_A`. At h=0.5, 1.0 and 1.5 the band is only approximately
0.008, 0.088 and 0.259 blocks wide. Small height advantage is not automatically
a useful robust reach advantage.

Let both horizontal velocities be signed along A toward B, and let tauBA be
age of B in A's view and tauAB age of A in B's view. Constant-velocity intuition:

```
g_A ~= g - v_B * tauBA
g_B ~= g + v_A * tauAB
g_B - g_A ~= v_A * tauAB + v_B * tauBA
```

Pursuing an outward-moving target can increase the favorable view difference.
When B starts moving toward A, the second term reverses sign. Thus normal-hit
maintenance must prevent closing too deeply near the end of B's outward arc.
Do not use one undifferentiated ping value or the same current box for both
views. Own past-position history is useful for reconstructing what B may see.

A lightweight observer should retain visible position samples, robust slopes,
own trajectory history, a fresh-window interval, and a few plausible view-age
hypotheses. An abrupt velocity/height innovation widens uncertainty. Evaluate
the action under each hypothesis; mark an action robust only to this declared
finite hypothesis set. This is not an interval proof covering all schedules.

At successive fresh contacts an enduring orbit must restore, up to translation:

- Bounded horizontal separation and bounded height, with repeatable relative
  velocity, launch/landing phase and sprint-state distribution.
- A further legal own contact after B's fresh immunity expires.
- No opponent contact already queued/in flight that can become fresh, and no
  dangerous future local consumption with legal return geometry.
- A positive denial margin through the entire cycle, including the interval
  between velocity application and the next movement.

A sequence of unanswered scoreboard hits does not establish these conditions.

### Why normal-hit cycles deserve an isolated test

The following are deliberately approximate arithmetic using typical configured
launches, **not predictions certified against the coupled native duel**. With
vertical update `v <- (v - .08) * .98`, ten airborne moves starting at vy=.3608
sum to -0.0463 blocks; starting at vy=.4608 they sum to +0.8684. Repeated earliest
sprint launches can increase height each cycle; repeated normal launches can
slowly lose it. Actual launch depends on native server motion, so infer and
validate its distribution from each new cohort rather than treating either
constant as universal.

Similarly, an always-airborne W-holding target with horizontal launch .4 or .9,
air acceleration toward A of .026*.98 and drag .91 travels approximately 1.630
or 5.023 blocks over ten moves. Grounded A can travel at most about 2.806 blocks
over ten moves at settled sprint speed, or about 2.671 in a periodic cycle with
one sprint-contact slowdown. Takeoff while initially grounded changes this
comparison substantially because the first movement uses ground friction.

These calculations reject treating 'more knockback' as an unconditional
maintenance improvement. They do not reject a landing-synchronized sprint
orbit, a mixed normal/sprint cycle, or a latency-supported orbit. Those remain
falsifiable candidates.

## Experiment ladder and minimum comparisons

Keep perfect aim and W-only controls throughout this ladder. Freeze the native
build and compare equal 20 CPS ceilings. The user-requested opening handicap
changes opponent attacks only: B holds W from the start, does not attack during
the opening, then enables its ordinary clicking W-tap policy after a fixed
predeclared wake time. Do not reset positions, velocities, immunity or inputs
at wake-up. Log the actual native activation instant.

1. First reproduce same-build 20 CPS W-tap versus continuous-clicking hold-W.
2. Compare baseline with NORMAL mode using continuous clicks. Then add the
   selective window/recoil rule as a separate cell. Use opponent hold-W first.
3. Test the same frozen candidates against W-holding B with attacks disabled
   until a fixed wake time expected to allow 2–3 free hits, then ordinary W-tap.
   Pilot the delay once; freeze a short ladder such as 1,500/1,000/500/0 ms or
   a corrected ladder based on actual contact timing. Free hits are reported
   but excluded from post-wake performance. Do not call fixed delays equivalent
   to a guaranteed number of free hits; record the actual number.
4. Add spacing alone if the normal-hit effect is mechanically correct but fails
   by closing too deeply. Add sprint repair only if normal cycles systematically
   lose height or recoverability. Then shorten the handicap and finish against
   fully active W-tap. Retain active hold-W as its own baseline.
5. If acquisition is the obstacle, a clearly labeled oracle/seeding experiment
   may identify a viable maintenance region. Final competition receives only
   local observations, and seeding successes must not count as acquisition.

Score after wake-up by exact server fresh events and accepted damage separately.
Also report first opponent fresh return after wake, fresh hits before that
return, longest post-wake continuous reach-denial interval, lowest B nearest-box
denial margin, and time to loss of own contact. If there was no established
denial at wake, classify acquisition failure, not maintenance failure. Keep
negative reach samples distinct from continuous-time guarantees, and retain
end-of-record censoring.

For each candidate cycle log start/end gap and height, net drift, local and
server sprint at the fresh hit (server only in offline analysis), received
impulse, actual movement count, B ground/air transitions, all local contacts
and their drag direction, plus attack queue ordering. A long streak whose
height or gap drifts monotonically is a transient, not evidence of an invariant.

## How to interpret failure without overclaiming impossibility

**Model error:** native replay of original inputs disagrees before the proposed
action's first divergence, or the declared observer interval misses the actual
three-POV quantity. Repair the relevant boundary before searching harder.

**Search insufficiency:** a native replay or explicit legal oracle policy finds
a viable continuation from the failed state while the current controller does
not. This falsifies impossibility for that state but may still be unavailable
from local observations.

**Limited-observation obstruction:** two native states/schedules consistent with
the same allowed observation history require disjoint safe next actions. Show
this with branching counterexamples and an action-complete local certificate;
a failing particle estimate alone is insufficient.

**Physical obstruction within a defined state/control set:** an exhaustively
validated transition enclosure has no indefinitely viable safe subset, or a
proved monotone height/gap quantity must exit the safe set. Specify pings,
schedule bounds, allowed actions and acquisition states. A finite parameter
sweep, failed score optimization, or several broken combos cannot establish it.

The appropriate positive target is a robust controlled invariant set in a
validated contact-to-contact map, including observer beliefs and packet queues.
A single long native run is valuable engineering evidence but not that proof.
