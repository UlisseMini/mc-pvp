# Moving the combo demonstration toward vanilla

Work in progress, November 1. The two-tick/zero-vertical demo remains an anchor,
not the requested result. **No new near-vanilla native 60-second lock has been
established yet.** The useful new result is a measured distinction between weak
grounded launches, catchable airborne normal hits, and excessive airborne sprint
hits. These explain why simply restoring one parameter at a time is not enough.

The desired neighborhood is fresh damage every9–10 ticks, horizontal and vertical
knockback multipliers0.9–1.1. Those are the requested bounds, not claimed Hypixel
constants. Reach is3 for both players and added RTTs are0/120ms unless stated.

## Native comparisons

Every new comparison uses the same20CPS ceiling, perfect own-client aim, zero
reaction delay and four-second common acquisition. The maintenance horizon is
shown per row and is identical for candidate and baseline.
The hold-W opponent sprints throughout and starts clicking at4s. Both candidate
and reference use the same frozen gap law during acquisition; the reference then
switches to plain W-tap with its cue history already established. Three separate
pairs per profile alternate order. Counts exclude free opening hits and stop at
the first accepted return, including damage upgrades as returns.

| Fresh interval / horizontal / vertical | Maintenance horizon | Candidate fresh before return | Plain W-tap | Full horizon, candidate / W-tap |
|---|---|---|---|---|
|2 /1 /0, earlier anchor|60s|2* /527 /535|1 /2 /5|2 of3 /0 of3|
|4 /1 /0, m1300–m1305|60s|1 /1 /0|0 /0 /0|0 of3 /0 of3|
|6 /1 /1, m1306–m1311|60s|6 /12 /7|3 /3 /1|0 of3 /0 of3|
|6 /1.1 /1.1, m1312–m1317|60s|9 /7 /4|1 /2 /2|0 of3 /0 of3|
|8 /1 /1, m1318–m1323|20s screen|0 /0 /0|0 /0 /0|0 of3 /0 of3|
|10 /1 /1, m1324–m1329|20s screen|0 /1 /0|1 /1 /1|0 of3 /0 of3|

The anchor's star denotes a timing-flagged acquisition; the two successful minute
runs were clean. All new primary windows above have clean measured timing.
Full three-POV packet recordings are present for the six-tick batch. The four-tick
batch has science traces/server replays but lacks full raw packet captures because
the recorder's disk-space guard engaged. Original data remains intact; completed
replays were transparently compressed and byte-verified to restore recording space.
All primary windows in the+10% KB batch are clean too; its final W-tap run has a
later whole-match timing flag, after the scored first return. The row is retained.
The eight- and ten-tick screens have clean full-match timing and complete three-POV
recordings. Every first return is
observed within the shorter horizon; no shortened run is claimed as a60s lock.

The closest tested native regime that **improves across all three repeats** is
currently six ticks with ordinary vanilla KB. It still returns after a few seconds;
that is not an indefinite lock. `/replay m1309` shows its12-hit maintenance prefix.
Only the earlier anchor currently has full-minute native demonstrations.

## The boundary depends on flight phase

**At four ticks with vertical0**, the target never becomes safely separated in
the prescribed opening. A normal launch gives victim forward-away steps of about
0.273,0.123,−0.015,−0.136 blocks. Across the whole four-tick cycle, retreat averages
only0.061 blocks/tick. Our always-W controller cannot slow below roughly0.153
even with continuous A/D. It catches the target and overlaps it before the target
starts clicking. This establishes an actuator/opening failure, not impossibility
for controllers allowed to stop W or choose sprint strength.

**At six ticks with restored vertical KB**, the model can find bounded flight
cycles and the native controller performs substantially better than W-tap.
But the audited native failures land every fresh hit on the six-tick cadence and
still lose spacing: the victim lands, ground friction dissipates backward speed,
and our saturated A/D pursuit remains too fast. In one recording the opponent's
contact comes8ms before the next velocity packet; in another the packet arrives
first but the opponent attacks before its next movement step. Merely increasing
click frequency cannot fix either event ordering.

The+10% KB profile also has this inner-closure failure in m1312, but held-out
m1316 first fails in the opposite direction: five real clicks miss, producing a
12-tick fresh-hit gap. The opponent loses its stream of outgoing knockback and
runs back in. Restoring contact with a grounded normal hit then cannot restore
safe separation. More knockback does not simply repair the weaker-profile failure.

**Sprint hits can fail in the opposite direction.** In a vanilla queued-clock
oracle prefix, the first four sprint launches start their victim movement on the
ground, retaining about0.38 blocks/tick after friction. The fifth starts airborne
and retains0.73 despite a similar launch strength. The victim escapes our range.
Server height at attack time does not identify this boundary reliably: the
important state is grounded/airborne at the victim's first movement after its
delayed S12 velocity packet. This motivates one controlled delayed-click test,
not a general claim that sprint hits are bad.

At eight and ten ticks with vanilla KB, the prescribed four-second opening has
already lost separation. The first return lands89–102ms after the defender starts
clicking. The controller ties or loses to W-tap, so this branch is stopped. These
are failed entries; they say nothing decisive about a better established cycle.

## Exact model comparisons, separate from native evidence

All rows below use native queued-input mode with51ms admission spacing, three
independent nominal client/server phases, the same complete4s acquisition state
and queues for candidate/W-tap, then60s of maintenance. The candidate law and
gains are frozen. Counts correspond to phases0/10/25,0/25/5,0/40/40ms.

| Fresh interval / horizontal / vertical | Candidate fresh before return | Plain W-tap | Candidate full60s |
|---|---|---|---|
|2 /1 /0|571 /571 /571|5 /6 /6|3 of3|
|6 /1 /1|1 /200 /2|1 /3 /4|1 of3|
|6 /1.1 /1.1|200 /190 /38|3 /2 /3|2 of3|
|7 /1.1 /1|0 /172 /1|0 /2 /1|1 of3|
|8 /1 /1|1 /0 /0|1 /0 /0|0 of3|
|9 /1 /1|1 /0 /0|1 /0 /0|0 of3|
|10 /1 /1|0 /1 /1|0 /1 /1|0 of3|

The190-hit phase includes19 misses at the first eligible fresh window; it passes
the no-return primary metric but not perfect window utilization. All comparisons
use identical scoring. The seven-tick conditional lock has normal hits every350ms
with a bounded four-hit flight pattern, but fails the other two phases. It is
not a robust result. Most8–10 tick failures already have unsafe geometry at the
end of acquisition; they cannot establish that maintenance from a better seed is
impossible. Full grids include horizontal/vertical0.9,1,1.1 and show no robust
target-neighborhood success for this frozen controller.

The kernel reconstructs the fresh native six-tick failure m1306 through2,427
recorded processing events: zero bit mismatches in movement, input, interpolation
or server rows, and reach/ray errors below9e−16 blocks. This checks mechanics on
fresh data **given measured inputs and processing times**. It does not validate
a prediction that future clocks will remain perfectly periodic. A second fresh
reconstruction, m1312 with+10% horizontal/vertical KB, passes2,441 events with
zero bit mismatches and reach/ray error below5e−16 blocks. That distinction
is necessary: nominal phase-specific minute locks have failed native transfer.

## Focused follow-ups

Three small experiments are complete, each preserving a matched W-tap reference:

- Release W for two ticks when the existing slowdown saturates, with unchanged
  gains/clicks. At6/1/1 the three-phase counts become1/200/8 versus the frozen
  law's1/200/2 and W-tap's1/3/4. Native double-tap state is audited: no accidental
  sprint movement or sprint-strength hits. This fixes some lost control authority
  in one phase, but still does not establish reliable minute-long maintenance.
- Delay one specific sprint click to move S12 application from airborne to
  grounded. The actual fifth hit remains a sprint hit and becomes grounded, but
  the vanilla prefix worsens from6 to5 fresh versus W-tap3 over the same10s horizon.
  First return moves earlier, from4375ms to3575ms. The wait gives the opponent a
  contact before its next movement, and that contact brakes its new velocity.
  Landing alignment alone is insufficient; the intervening reach margin matters.
- Allow ordinary acquisition trades at9–10 ticks. All12 tested openings really
  consume the defender's server sprint state and restore the predicted normal
  launch budget (about0.4 times the horizontal multiplier). Nevertheless, every
  handoff is unsafe: the candidate is out of reach, the opponent can reach it, or
  both. Candidate and W-tap remain at0–1 fresh before return. The hidden-state
  mechanism is real, but this opening does not create a useful lock entry.

The coast transfer to6/1.1/1.1 fails:7/14/37 versus frozen200/190/38 and
W-tap3/2/3, destroying both nominal minute locks. This branch is stopped without
deployment. A correction helpful in one flight state is harmful in another.

Astra's four-mode diagnostic finds a better vanilla handoff: change the fifth
launch to **normal while airborne**, keep its timing, and coordinate braking and
subsequent sprint pursuit. From the original900ms seed through4350ms it gives7
fresh versus W-tap3 with zero candidate returns. Its new1.1s suffix contributes3
fresh versus W-tap2 from exactly the same later state; both survive that short
suffix. The minimum sampled defender distance is3.14765. This is a single-phase
oracle prefix, not a legal controller or stable cycle. The final sprint's flight
falls beyond the horizon and remains unresolved. The next extension branches
before that hit and checks post-launch viability, preserving all earlier credit.

These are causal tests, not a vanilla infeasibility proof. No bounded search has
covered all allowed inputs, phases, acquisition states or pings.

Detailed evidence: [full compensation grid](poc-20261101/anneal/continuation/horizontal-compensation-findings.md),
[four-tick native audit](poc-20261101/anneal/legal/native-t4-acquisition-findings.md),
[six-tick native audit](poc-20261101/anneal/legal/native-t6-phase-findings.md),
[vanilla oracle/landing audit](poc-20261101/anneal/mechanism/findings.md),
[Astra's reach corridor and normal-airborne witness](poc-20261101/anneal/astra-boundary/findings.md).
