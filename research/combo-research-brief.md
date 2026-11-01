# Combo research: current objective and user guidance

This is the working brief for future sessions. The latest clarification below
supersedes the earlier continuous-own-reach interpretation. Keep historical
measurements and their original definitions; do not silently relabel them.

## Success definition — latest clarification

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
- Primary movement simplification: forward W/coast and ordinary sprint inputs;
  ignore aim noise and strafing for now. Direction tracking imperfections are
  a distraction for two straight-running bots.
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
