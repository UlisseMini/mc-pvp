# Moving the combo demonstration toward vanilla

Work in progress, November 1. **The vanilla model now has a 60-second,
105-fresh-hit, zero-return input sequence, versus matched W-tap's 3 hits.** It is
one nominal clock phase selected by an offline oracle, not a native demonstration
or a robust feedback bot. It is evidence against declaring vanilla impossible.

**No new near-vanilla native 60-second lock has been established yet.** The old
two-tick/zero-vertical demonstration remains only an anchor. The native ladder
below maps the frozen controller's failures, not the limits of all controllers.

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
|6 /1.1 /1.1, hold after opening, RTT140, m1330–m1335|60s|5 /8 /6|2 /2 /1|0 of3 /0 of3|
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

The focused experiments below preserve a matched W-tap reference:

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

Astra's normal-airborne handoff leads to a stronger **vanilla model** result.
The completed-launch search preserves the physical first-hit acquisition and
extends it to18 fresh hits with zero returns over10s, versus W-tap3 from exactly
the same900ms seed. All ordinary inputs and queued clicks are saved. This is
one nominal phase, selected by an offline oracle, not a playable feedback bot
or an established cycle. Its first18 launch modes are mostly grounded sprint
hits, with two normal-airborne and two sprint-airborne launches. Height remains
bounded in this prefix; a full relative-state recurrence is not established.

| Same vanilla seed and10s horizon | Candidate fresh before first return | Plain W-tap |
|---|---:|---:|
| Coordinated saved movement/click word |18, no return|3|
| Same movement, click gate always open |3|3|
| Same click gate, ordinary W-tap movement |3|3|

Both parts matter. Constant clicking changes launch strengths as well as contact
drag, so this is not a pure slowdown ablation. Sparse clicking with W-tap
movement preserves the first three hits but then loses spacing. Merely combining
"few clicks" and "sprint reset" without their timing does not reproduce the result.
The first attempt to extend from its11.6s terminal state reaches20 fresh but
returns at11.975s: the last normal-grounded launch leaves the opponent advancing
only0.048 blocks/tick while our bot advances0.150. Restarting the search before that launch's preparation succeeds: **105 fresh
hits, zero accepted returns over the original 60-second window**, versus W-tap's
3. The conditional continuation from10.9s contributes87 versus W-tap1. All18
earlier hits are preserved. The extra700ms still has no return and supplies a
following contact for every scored launch; the last unscored launch remains
pending beyond the horizon, so this is finite survival, not an invariant cycle.

The successful change makes11375ms a grounded sprint hit instead of a normal
hit: the horizontal launch rises from0.332 to0.832. It then alternates grounded
sprint and weaker airborne normal launches for a while. This is a concrete
mechanism, not simply more click frequency. Six of103 recorded first-eligible
input opportunities miss reach; these remain reported rather than redefining
fresh windows. The witness is still limited to one phase and has no live policy
or native validation.

The full launch audit now confirms84 grounded sprint,13 airborne normal and8
airborne sprint launches. Late in the minute,47 of52 launches start grounded
and sprint-strength. Their main intervals are550/600ms. After a grounded sprint
launch with vertical packet0.422375, the next grounded sprint never occurs at
550ms in this witness;600ms is common. With vertical packet0.384625,550ms can
work. The vertical recurrence explains those thresholds: ground-origin impulses
0.384625,0.422375 and0.46075 land on movement11,12 and13 respectively. A new
velocity applied before that landing movement interrupts it; beginning the next
launch grounded therefore needs550,600 or650ms in the nominal50ms clock.
This is a landing deadline, not a universal fixed hit rhythm. The occasional
normal launch bridges a flight phase where another strong launch would occur
in the air. Actual own STOP/START and queued-click order explain the strength;
current W/Ctrl keys alone do not determine it. The next validation is unchanged
word transfer across physical clock phases, before extracting a feedback law.

The RTT-only check is also complete:60 cells, with all12 old120ms fingerprints
passing unchanged. Opponent RTT140/180/240 does not rescue the frozen9–10 tick
controller. At six ticks/+10% KB/140ms, however, **plain hold W+Ctrl** gives
200/200/3 versus W-tap5/5/3 over the same60s: two large wins and one tie. The two
successes naturally give one sprint fresh hit then199 normal hits. W-tap keeps
rearming strong sprint launches and later loses reach; the normal-only gap law
never gets the initial impulse and closes too far. A cue-based one-sprint
intervention produces the same primary spread, so it adds no demonstrated benefit
over simple hold. The matched native test of that simpler control gives5/8/6 versus W-tap2/2/1,
with no minute lock. All six primary windows are clean and all three POVs are
complete; m1332 has one later whole-match timing flag, retained. In m1330 and
m1334 the first strong launch lifts from the ground and the next normal hits
in the air. The target escapes own range, then catches up after a long gap.
m1333 enters with a different, favorable descending strong launch but still
misses enough clicks to create a nine-tick gap. Steady pursuit cannot restore
denial afterwards. These are native failures of the proposed simple continuation,
not evidence of a successful high-delay boundary.

A separate forced-pulse diagnostic rules out a tempting overclaim: vanilla
victim flight itself can be periodic. Alternating normal/sprint hits every ten
ticks settles into four flight modes over2s, requiring6.366 blocks of pursuit.
The sequence includes a weak sprint launch while airborne and a normal launch
on the ground; "air means normal, ground means sprint" is too crude. This
relaxes attacker reach and input feasibility, so it proves no playable combo.
The missing obligation is keeping both POV distances legal through the outward
burst and the slow return leg using ordinary movement and sprint commands.

These are causal tests, not a vanilla infeasibility proof. No bounded search has
covered all allowed inputs, phases, acquisition states or pings.

Detailed evidence: [full compensation grid](poc-20261101/anneal/continuation/horizontal-compensation-findings.md),
[four-tick native audit](poc-20261101/anneal/legal/native-t4-acquisition-findings.md),
[six-tick native audit](poc-20261101/anneal/legal/native-t6-phase-findings.md),
[vanilla oracle/landing audit](poc-20261101/anneal/mechanism/findings.md),
[Astra's reach corridor and normal-airborne witness](poc-20261101/anneal/astra-boundary/findings.md).

Further details: [RTT check](poc-20261101/anneal/continuation/rtt-continuation-findings.md), [forced-flight return map](poc-20261101/anneal/astra-boundary/shooting-findings.md).

Latest vanilla evidence: [60-second witness](poc-20261101/anneal/mechanism/n-air-vanilla-60s-findings.md), [all105 launch audit](poc-20261101/anneal/mechanism/n-air-105-launch-audit-findings.md).

## Unchanged vanilla word: timing transfer

Each row physically reruns the same first-hit acquisition, then forks the word
and primed W-tap from identical complete900ms states. No seed correction or
phase-specific repair. Shared h/v1, immunity10, reach3/3, RTT0/120,60s primary.
The admission interval is a declared transport setting, not an extra cooldown.

| B / server phase ms; admission interval | Saved word fresh before return | Plain W-tap | Word zero60s |
|---|---:|---:|---|
|10 /25;51ms|105|3|yes|
|25 /5;51ms|1|2|no|
|40 /40;51ms|1|2|no|
|9 /25;51ms|83|3|no|
|11 /25;51ms|83|3|no|
|5 /25;51ms|105|3|yes|
|15 /25;51ms|1|2|no|
|10 /24;51ms|105|3|yes|
|10 /26;51ms|105|3|yes|
|10 /20;51ms|1|2|no|
|10 /30;51ms|105|3|yes|
|10 /25;50ms|6|3|no|

All900ms seeds are geometrically separated; the four one-hit failures lose the
first continuation cycle rather than beginning already inside opponent reach.
Their first strong launch begins airborne. In three, the victim makes a local
contact before its first movement after S12, reducing horizontal velocity by.6.
The83-hit failures preserve the first82 launch trajectories exactly but admit
an opponent contact around48.26s. They are not explained by a landing-mode switch.
Several full-minute positives share identical sampled launch states, so they are
not independent evidence of a broad stable region. The50ms admission check
stops the unchanged-word native proposal: changing only the shared injection
ceiling changes the queue/consumption sequence enough to destroy the minute.
A clock-indexed gate is not yet a robust input program.

The recorded word also leaves the currently verified128-block arena by33.71s.
Any later native minute trial needs a larger verified ordinary floor. No walls,
teleports or hidden state corrections may be used to keep the modeled sequence.
