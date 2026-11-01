# Combo research: current objective and user guidance

This is the working brief for future sessions. The latest clarification below
supersedes the earlier continuous-own-reach interpretation. Keep historical
measurements and their original definitions; do not silently relabel them.

## HARD RULE: plain W-tap is the mandatory baseline

ALWAYS compare against plain W-tap under identical settings in both the model
and native Minecraft. Every results table gets a W-tap column. Match reach,
both RTTs, KB/hurt-time profile, CPS cap, reaction/aim, duration, initial state,
opening handicap and metric. Include several seeds/tick phases or native repeats
and show spread. Hold-W is an additional control, never a substitute for W-tap.
Historical unmatched results remain explicitly unqualified until backfilled.

The restricted task and primary gate is maximum fresh hits in a fixed time
without getting hit. For a fixed start and horizon, count fresh hits before the
first accepted return (including an accepted damage upgrade as a return), and
report whether the entire horizon had zero returns. Also report the longest
unanswered fresh-hit run, but do not substitute a lucky later run for success
from the prescribed start. Preserve strict own-POV reach/readiness/denial metrics
as additional diagnostics, scored identically for candidate and baseline.

If the controller/search does not clearly beat plain W-tap on this task across
the tested spread, STOP ADDING CONTROLLER FEATURES. Diagnose what W-tap gets
right and what the candidate gets wrong: inputs/contact drag, sprint state,
spacing, launch/height phase and packet timing. A more elaborate controller or
one favorable sample is not progress. Resume development from a concrete causal
diagnosis grounded in matched baseline evidence, not another blind variation.

For oracle maintenance tests, fork candidate and baselines from the same complete
state, including queued packets and immunity. Prime baseline observation history
during acquisition so switching at the seed does not invent a new hurt cue.
Report whole-fight acquisition separately. A baseline must not receive stricter
or looser scoring than the oracle. The historical `combo-wtap20` policy defaults
to150ms reaction; explicitly label it and include a zero-delay plain W-tap
reference for zero-delay research controllers rather than silently changing it.

## Overnight mandate (2026-11-01)

### Latest priority: native schedule calibration, then robustness

Current checkpoint: exact receipt→enqueue boundary errors are proven in fresh
m1395. Two explicitly oracle-conditioned readiness interventions remove the
first replica/body mismatches and reproduce its19-hit failure in the same
accepting drain; native-source W-tap remains2. Later body/input mismatches remain,
and counterfactual same-seed W-tap lacks a sampled source stage. This is causal
evidence, NOT calibration acceptance. Legacy recorded-timing replay still passes
24683 events/9243 states bit-exact. Next frozen test is a whole-donor monotone
receipt→enqueue field with central/lower/upper sensitivities, preserving paired
calendars and exposing different drain selections. Do not independently sample
wall preparation costs: observed174ms pauses can already be represented by
calendar gaps. See [current findings](combo-native-schedules-20261101.md) and
[field plan](poc-20261101/native-schedules/adapter/queue-readiness-field-plan.md).
No controller changes or new prospective native matches until the gate passes.

User requests that evaluation clocks use native-recorded packet jitter, client
tick gaps, task-drain timing and opening variation, rather than the ideal
periodic schedules that falsely predicted reliable750-hit locks. Freeze the
controller until both candidate and paired W-tap native outcomes are represented
by the model distribution. Broad min/max overlap alone is insufficient; check
frequencies, failure mechanisms and held-out/fresh data. Separate network receipt
from task handling. Preserve correlated event order and generate gameplay inputs
and packets autonomously; recorded click counts/positions are not evaluation
inputs. See [the frozen plan](poc-20261101/native-schedules/preplan.md).

Also compare the three earlier150-hit successes with both new failed openings.
Test whether established locks withstand bad subsequent schedules, preserving
full state/queues when separating acquisition from maintenance. A deliberate
own-observation acquisition routine may be sufficient, but correlation in five
matches is not proof. Only after calibration may controller changes be tested,
including the opponent's click after S12 arrival but before movement.

### Current evidence after the strength/timing pass

Latest follow-up: [lock8 robustness and boundary](combo-lock8-robustness-20261101.md).
`/lab demo lock8` and `/duel lock8 boxing preset=lock8` are deployed. The frozen
20-run native robustness batch is complete: equal RTT0/0,60/60,120/120 does not
beat W-tap; the fresh300s150/0 pair is2 versus2 before first return, with clean
primary/acquisition clocks. The healthy command smoke is5 versus3. No rerolls
or controller changes. Existing150/150/150 native witnesses remain valid, but
robustness is not established. The periodic model gives750 in all14 five-minute
cases versus W-tap1–2, so actual timing/seed sensitivity needs causal explanation.
Do not resume feature tuning from the periodic success alone. All matches ended;
global combat defaults are restored1/1/10. Native logs and all failures retained.
Fresh six-trace12s conditional replay now completes24683 events with9243 state
rows bit-exact (nearest error≤4.52e−13), after fixing two adapter clock-boundary/
snapshot-ordering bugs. No kernel or controller change. Native failures are
contact before first knockback movement (m1368) or before next fresh (m1388),
with earlier launch/replica histories differing. Late identical-launch8→9/10
forks fail with no recorded missed fresh window: a scoped committed-view bound
shows the frozen law brakes too late, but earlier braking has reach slack.
This is NOT a proof against all vanilla controllers; no global empty corridor
has been established. See the new findings before adding features or sweeping.

See [combo-strength-phase-20261101.md](combo-strength-phase-20261101.md).
A legal own-hurt-cued spacing controller now maintains60s natively at own/B
RTT150/0,H1.1,V1,hit8:150/150/150 fresh versus W-tap2/2/2. Last two repeats are
fully timing-clean; first retains one108ms tick flag. Both use the same4s silent
opponent opening, whose free hits are excluded. Native3.0 reach and20CPS cap.
The successful controller uses normal hits; learning phase was unnecessary in
its causal ablation. The prepared S/N branch works mechanically but performs
poorly because its protocol adds50–100ms and unnecessary W-off movement.
The final432-setting,three-phase grid contains no9/10-tick success; this is NOT
an infeasibility proof. Keep the8-tick native witness as the new continuation
anchor. Acquisition without a handicap and broader ping robustness remain open.
No extra native/offline job is queued; server combat defaults restored1/1/10.

### Latest direction: legal-controller settings search before native trials

Next mechanism pass: add deliberate normal-versus-sprint hit selection on top
of the frozen spacing feedback, verifying actual delivered strength rather than
assuming Ctrl means a sprint hit. Use own received landing/height cues and
spacing; do not read the victim's true state. Separately estimate processing
phase from this client's incoming packet timing and test phase-aware click
admission. Distinguish observable server phase, opponent movement phase, and
which timing components ordinary inputs can actually change. A delayed click
still passes through the native input queue and cannot move an admitted attack
backwards in time. Keep the same settings/phase sweep and matched W-tap gate.
Only combine individually justified changes; a nominal wall-clock phase change
is not evidence that actual C02 consumption/service phase moved.

Search the coupled model first across near-vanilla settings, including BOTH
players' RTTs (initial values0/60/120/150ms), horizontal KB within±10%, vertical
KB within the same near-vanilla neighborhood, and fresh windows8–10 ticks.
The searched policy must use only its own client's observations and declared
settings; an oracle trajectory is not a qualifying legal feedback controller.
Require sustained fresh hits and zero accepted returns for60s across several
physical tick phases, with plain W-tap from identical complete states. Report
fresh gaps/readiness as well as scores so merely running away is not called a
combo lock. Keep acquisition separate and all free opening hits excluded.
Only the best one or two settings that pass the model qualification go native;
coordinate their compute/timing gates as before. Community ping claims motivate
the grid; they are not verified Hypixel constants.

Audit precisely which hidden information the105-hit vanilla oracle used:
victim state, latent server motion, future event ordering and in-flight views.
Distinguish that offline search access from the saved word's legal but brittle
runtime inputs. Investigate whether ordinary, own-observation-cued inputs can
FORCE a reproducible landing/flight state rather than estimate it precisely.
A proposed reset must preserve reach/denial and work against an active hold-W
opponent; an oracle state edit or newly disabled opponent is not maintenance.
Freeze simple laws before the settings sweep and diagnose failures against
W-tap before adding features. No RL/ML. The deliverable remains the closest
near-vanilla native lock and a mechanistic account of its breaking boundary.

### Current priority: continue the anchor toward vanilla

The user explicitly rejects zero vertical KB plus two-tick fresh damage as the
desired combat regime. Preserve that POC as an anchor only. The deliverable is
the closest-to-vanilla **natively demonstrated** lock with matched W-tap, plus
the mechanical reason it breaks at the next continuation step. Desired settings
are fresh-hit intervals around9–10 ticks and horizontal/vertical KB multipliers
within roughly0.9–1.1 of vanilla; do not claim these numbers are verified Hypixel
constants. Follow the hit-delay ladder2→4→6→8→10 (also examine9) and restore
vertical KB toward1. Separate one-axis causal changes from joint compensation.

The old always-W gap law may leave its control-authority range as the hit interval
grows. Diagnose its failures against W-tap, then change inputs only from that
mechanism. Failed acquisition is not maintenance infeasibility, and the boundary
of one controller is not a universal boundary. Model candidates remain proposals
until native repeated pairs confirm them with clean timing. Do not substitute
another far-from-vanilla POC or more tuning at hit2 for this continuation result.

### Earlier parallel POC direction (superseded by the near-vanilla target)

The earlier POC stage prioritized a legal own-observation controller that sustains a long
native lock against hold-W in **any declared scenario**, massively outperforming
matched plain W-tap, before walking settings back toward vanilla. Run distinct
GPT6.1Sol approaches: simplified analytic existence model, exact-kernel
relaxation/continuation, witness-to-legal feedback, and justified richer-input
search at a failure boundary. Astra continues the seven-hit vertical-cycle
analysis. That stage allowed easier settings first. The later continuation and
settings-first instructions above now take priority. All baseline and validation
gates remain mandatory.

Offline work may run in parallel with isolated owned files. Serialize native
research matches and coordinate CPU load so clocks remain meaningful. The
coordinator stops approaches that do not beat W-tap after a fair trial and focuses
effort on positive, explainable evidence. Each approach writes short findings.
See [worker ownership and experiment coordination](poc-20261101/COORDINATION.md).

Continue autonomously without questions while the user sleeps. Focus exclusively
on understanding combo dynamics; no RL or ML. Deliver either a near-indefinite
vanilla hold-W lock, or a strong, explicitly scoped argument for impossibility
plus a working demo with small declared knockback/damage-delay changes. Different
ping pairs are valid variants. Failure even under easier settings is evidence
that our understanding is incomplete, not proof that human Hypixel combos cannot
exist. Read the actual Zest teaching and distinguish verified behavior from
unverified Hypixel constants. Finding a tweak that enables a cycle identifies a
sufficient mechanism, not necessarily Hypixel's actual implementation.

The user's final overnight clarification explicitly treats a small-tweak demo
plus strong evidence against vanilla as a good outcome. Do not force vanilla
indefinitely after a validated model identifies a concrete obstruction. Quantify
the scope of that obstruction, seek the smallest enabling KB/hit-delay change,
expose it as an optional duel setting, and record a native demo. A failed bounded
search still does not constitute a universal impossibility proof.

Build a small coupled 1D model around ExactTravel; validate movement, server
motion, damage immunity, knockback, sprint transitions and client replicas against
native traces. Search for a feasible repeating hit-to-hit state/cycle, then test
the resulting ordinary inputs natively. Proving absence requires covering the
allowed state/input space, not just a finite parameter grid. Keep bookkeeping
proportionate. Write a short plain-English morning findings document with what
works, what fails, why, numbers and commands to watch the best demo.

## Success definition — latest clarification

### Network-model acceptance gate (latest correction, 2026-11-01)

Use one coupled fight model with ExactTravel inside it. ExactTravel alone is not
the fight model. Validate zero-added-ping native traces first, then introduce
ping. Zero added ping still has independent client/server clocks, queued packet
handling, server tracker cadence/quantization, and client interpolation. Include
client/server phase explicitly and preserve measured event order; inspect native
frame/task handling rather than assuming every packet applies only on a tick.
Model both directions of movement, attacks, sprint commands, metadata and velocity
delivery. Damage immunity runs on the server clock. Include yaw/strafe in the
eventual model, even when the initial calibration deliberately uses W-only.

Before accepting a cycle, report held-out errors in each POV's reach at clicks
and fresh readiness windows, and per-hit positions/knockback. Conditional replay
with observed packets is a component check, not a free-running fight prediction.
Cycle margins smaller than model error provide no evidence. Search remains paused
until this gate is met. Preserve failed/stalled fixtures separately from clean
clock fixtures instead of discarding inconvenient outcomes.

The user's illustrative tick pipeline was a sketch, not a specification.
`FORMAL-MODEL.md`, pinned native code and measured traces take precedence. In
particular, S12 is sent inside the attack; client handling uses main-thread task
drains; dirty/isAirBorne tracker updates can bypass the two-tick cadence.

### Revised control space and Zest hints (2026-11-01)

A/D and approach angle are now explicitly allowed and supersede the earlier
W-only restriction. Extend the same fight model to horizontal 2D and validate
input normalization, native aim/head yaw and directional knockback before using
those controls in a search. W-only fixtures remain useful simpler tests.

Treat community tutorial claims as hypotheses: W for two contacts, release for
the third; perhaps an S,N,S strength sequence, but determine actual local/server
sprint state rather than assuming this mapping. Also test the shorter two-contact
variant, selective clicking, and slight A/D angle. First latency variant after
zero-RTT validation: vanilla combat with the opponent around90–140ms RTT, before
changing knockback or immunity. Explain any benefit through delayed velocity,
movement and replica histories, not “ping increases the KB constant.”

The user further clarified that Zest may be Hypixel-specific. Vanilla is the
primary target; do not spend effort reproducing its recipe exactly. Use the
mechanism hints to derive and test vanilla controls from the validated model.

Latest search correction: both initial oracle drivers fixed strafe at zero.
Expand their ordinary-key branches to A/neutral/D before drawing conclusions
from full-reach failures. Then run a small, explicit factorial grid over
horizontal KB, vertical KB, fresh-hit ticks and opponent RTT. Map finite
maintenance witnesses and the unresolved region; distinguish failed acquisition
from failed maintenance search. A heuristic no-witness cell is not an
infeasibility certificate. Preserve a known successful trajectory as a search
regression so beam pruning cannot silently erase the positive control.

Keep acquisition (hit-selection, winning a trade, favorable first contact)
separate from maintaining an established lock. Hold-W remains the primary target;
reported escape inputs (S, sneak, blockhit, facing away) are later robustness
tests. Tutorial reference: https://www.youtube.com/watch?v=mTeXebYfIIs .

Keep the opponent unable to return a hit, and have them in our **own client's
legal reach when the next native fresh-damage window opens**, so we can keep
landing fresh hits promptly. Excursions outside our own reach during the
opponent's damage immunity are allowed. Merely waiting longer to click does
not postpone the game's damage window and must not turn a missed opportunity
into success.

Minecraft1.8 has target damage immunity, not the later weapon recharge system.
Use the actual native immunity/tick timeline for retrospective scoring; account
for the time from a local attack input to server processing. A stronger damage
upgrade during immunity is distinct from a fresh knockback hit. Do not count
crit upgrades or rod contacts as additional fresh sword contacts.

Track, separately:

- Longest regular fresh-hit chain with no accepted return; whether it ended or
  was censored by the test finishing. Long runs matter more than a percentage.
- For each fresh-damage window: when it opened, local reach around the relevant
  attack time, first eligible native contact, actual accepted hit, and avoidable
  delay/missed windows. An unavailable timing/POV observation is unknown.
- Defender reach opportunities from the defender's own POV throughout the
  chain, even when its actual click missed. Also report actual return hits.
- Time-weighted one-sided reach, own out-of-reach excursions during immunity,
  longest continuous one-sided interval, distance/momentum/height trajectories.
  These explain the result; continuous own reach is **not** a required success
  criterion after the user's latest clarification.
- Final full-reach boxing performance against hold-W, then W-tap, at equal CPS.
  A large reduced-reach winning score alone does not establish combo control.

The ultimate objective is a near-indefinite lock against native-reach hold-W,
ideally robust across pings and maintainable against any straight-running
opponent once the first one or two hits establish a favorable state. Do not
claim infinity from a finite match, or impossibility from unsuccessful tuning.

## Mechanics and control constraints

- Reference: real vanilla Minecraft1.8.9, normal speed, flat ground, large arena,
  no potions. Real headless Minecraft with the shared Forge practice mod is the
  primary backend; preserve legacy code/history. Do not substitute Mineflayer
  or an approximate engine for the reference experiment.
- Both research bots have the same **20 CPS ceiling**. The controller can click
  less or in bursts when justified. Earlier10CPS and human reaction limits were
  relaxed; perfect local aim and superhuman tactical precision are allowed.
- Initial calibration uses forward W/coast and ordinary sprint inputs. The latest
  control space also permits A/D and angle; keep aim noise disabled while testing
  those mechanics. Do not interpret the earlier W-only simplification as a ban.
- Strategic jumps are an allowed later control option, particularly when there
  is ample room. Test them as a separate expansion so their contribution is clear.
- Every issued click goes through normal native input, miss handling, collision,
  damage and drag. An immune contact can still slow the attacker. Never silently
  remove immune attempts or their physical consequences after input issuance.
- W-taps normally reset sprint after a landed hit; they are not arbitrary
  periodic releases. Timing the release relative to an incoming hit matters.
  Deliberately selecting a *normal* hit may instead require releasing W before
  it. A post-hit release cannot change the knockback already delivered.
- Movement, clicking and hit strength are coupled. Coasting may clear sprint;
  resuming W may re-arm sprint or trigger the native double-tap behavior. Verify
  actual local and server sprint state at contacts, not merely policy labels.
- A sprint hit into an airborne opponent can produce much larger displacement
  than a grounded launch because motion persists differently in the air. The
  initial impulse itself is not necessarily larger. Investigate strong hits
  near the ground and normal hits in the air as a phase-dependent hypothesis.

## Distance, momentum and observation

The useful framing is: advance while knocking the opponent backward, keep the
relative spacing controllable, and be ready to hit again when immunity expires.
Maximal knockback alone is not the objective. Nor is standing at a fixed gap
without accounting for velocities, height, remaining immunity, click drag and
future acceleration/braking.

Distances are native eye-to-expanded-hitbox distances measured independently on
each client. Three blocks means actual native3.0 selection, not center distance.
Positive advantage means **opponent's distance to us minus our distance to them**.
The user's rough .5-block advantage while advancing toward a still opponent,
and possibly1block when the opponent moves backward, are hypotheses to measure,
not universal constants. View delay/interpolation, speed, height and phase matter.

Use only the controlling client's observations and known configured pings in a
competitive policy. Never feed the other bot's private client view or server
state into live control. All POVs may be aligned by real host time and analyzed
offline. An explicitly labeled temporary oracle can diagnose feasibility, but
must not be presented as an autonomous legal controller.

## Method: build capabilities, then harden

Do not resume blind tournaments over unrelated variants. Use a predictive model
and successively harder problems:

1. Separate acquisition from maintenance. Fix/rest the starting geometry and
   record clock phase, first-hit state and any opening handicap. A target that
   misses the opening or begins attacking after a delay is a valid declared
   stepping stone. Clock phases are not identical merely because positions are.
2. Prefer reducing **opponent** reach (for example2.5, lower if needed) while
   keeping our reach3.0. Prove the intended spacing/ready-window capability,
   rather than celebrating a large score. Smaller knockback or altered damage
   delay are possible additional relaxations, clearly separated from vanilla.
3. Identify the smallest failed transition: initial contact, one knockback arc,
   next fresh window, then several cycles. Use exact conditional travel and
   recorded packets/inputs to validate predictions and understand the failure.
4. Use simple feedback/PID or model-based input search where appropriate. Choose
   state variables and tuning from the plant's response and reach constraints.
   A model-based search with explicit constraints is different from blind policy
   mutation. Failed searches require model/feasibility diagnosis.
5. Confirm a capability with frozen settings, both sides and longer runs before
   raising opponent reach toward3.0. Then broaden initial conditions and RTTs,
   and move from hold-W to W-tap. Keep acquisition and maintenance claims separate.
6. Make causal comparisons one mechanism at a time, inspect combinations only
   when there is a reason, and retain negative and timing-invalid evidence.

An exact movement kernel does not make the coupled fight/network model exact.
Report the tested scope and held-out errors. A rigorous impossibility argument
must cover the permitted controls/states, not just one failed controller.

## Human evidence and practical setup

Analyze Nomophobia's historical W-tap matches, especially rare long rod-diamond
combos. Inventory all recordings, including old server-only files. Distinguish
rod-assisted acquisition from subsequent sword-only maintenance; separate changed
reach, CPS, ping, equipment and missing client POVs. Human wins and existing
W-tap wins are useful sanity checks that a weak research controller is not the
best achievable behavior.

Keep matches/replays available for deliberate practice. Preserve server and both
client recordings with identities/builds and timing quality. Use more concurrent
matches only after demonstrating that laptop load does not contaminate clocks;
single serial diagnostics are appropriate during model development. Do not
interrupt a human duel to deploy/reload research code.

## Evidence pointers

- `research/combo-orbit-controller-spec.md`: implementation history and failures.
- `research/human-combo-findings-20261101.md`:254-match historical audit and POV
  qualifications; m1071 is a useful six-fresh-hit example.
- `research/curriculum-strict-audit-20261101.json`: retrospective **stronger**
  continuous-own-reach metric, retained as a secondary diagnostic.
- `research/launch-envelope-20261101.json`: conditional native-launch envelopes.
- `research/launch-phase-model-20261101.json`: simple visible-height predictor
  benchmark; failed transfer must be resolved before relying on it in control.
