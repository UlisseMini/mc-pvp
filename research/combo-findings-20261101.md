# Combo research — working findings

The vanilla infinite-combo problem is **not solved**, and we have no impossibility
proof. The useful progress is identifying and removing errors in the coupled
model before using it to choose controls.

An earlier simplified periodic plant produced1,000 fresh contacts, but its first
native test (`m1186`, opponent reach1.5) managed only five consecutive fresh hits
and a45–31 score. That model result does not establish feasibility. Search is now
paused behind the full-pipeline validation gate.

## What the measurements establish

- The independent fresh-hit launch calculation matches344/344 recorded native
  S12 vectors exactly. It takes measured pre-hit server state as input; this is
  a component check, not a whole-fight prediction.
- Replaying actual incoming packets from a single initial remote state reproduces
  both replicas over three15-second matches:1,806 interpolation steps and1,720
  click-time distances, with zero error. This still supplies the real packet
  stream and the observer's local position. It does not prove that a model can
  generate those packets or local positions itself.
- Exact server queue boundaries predict the processing tick for2,975/2,975
  movement, attack and sprint packets in `m1192–m1194`.
- New client queue instrumentation predicts the processing drain for3,808/3,808
  incoming tasks in the clean `m1196–m1198` suite. Both packet enqueue and drain
  timestamps are captured under the same native lock. No combat behavior is
  changed by these observers.

Zero configured ping still has delay. In the initial clean fixtures, attacks
typically waited about19–28ms from client send to server handling. Client packet
handling occurred between physics ticks; velocity changed then, while movement
waited for its next tick. These are measurements of these runs, not constants for
every server or phase.

## Why the earlier plant was wrong

It omitted mechanics already documented in `FORMAL-MODEL.md`: own sprint metadata
can clear the attacker's local sprint between clicks; air acceleration is cached
from the previous update; dirty/isAirBorne tracker flags bypass the regular
two-tick position cadence. It also conflated frame entry, queue drain and tick
end. Any one of these can move a contact into another tick or change contact
slowdown. ExactTravel alone cannot fix those composition errors.

The new rollout predicts state and generated packets without later state
corrections. Recorded scheduling/transport traces are allowed as exogenous
inputs for this stage; packet contents remain predictions and assertions. The
full held-out fight is not yet certified. Later crossing, 2D geometry, tracker
look updates, damage upgrades and fall-state transitions must stay visible in
the failure report, rather than disappearing behind a short successful prefix.

## Next experiments and watching the evidence

Finish one shared fight kernel, validate W-only and controlled lateral motion,
then test vanilla combat with the opponent around100–140ms RTT. Search controls
such as W-release duration, selected click times and approach angle only after
the corresponding model transitions pass. Zest is a source of mechanism hints,
not a recipe or an asserted description of Hypixel's implementation. Acquisition
and maintenance remain separate experiments.

Use `/replay m1198` for the clean20CPS W-tap/hold-W reference (41–32), or
`/replay m1186` to inspect the failed periodic controller. Neither is an
indefinite-combo demo. Experimental knockback/immunity controls are implemented
and verified separately; ordinary duels and the current research default remain
vanilla.

Evidence: `zero-queue-native-fixtures-20261101.json`,
`zero-queue-pipeline-20261101.json`, `zero-clock-pipeline-20261101.json`,
`zero-ping-accumulated-view-20261101.json`, `native-launch-parity-20261101.json`.
