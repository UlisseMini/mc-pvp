# Moving the combo demonstration toward vanilla

Work in progress, November 1. **No near-vanilla native60-second lock yet.**
The two-tick/zero-vertical demonstration remains only an anchor. We tested the
continuation ladder, then a model-derived vanilla input sequence and two
mechanically motivated corrections, always with matched W-tap.

The latest small-tweak native result uses ordinary ten-tick immunity with only
5% less vertical knockback: **5 /10 /1 fresh hits before return versus W-tap
3 /3 /2**, all timing-clean. The10-hit run is `/replay m1351`; it returns at6088ms.
With fully vanilla KB, the one-row input correction gives7 /7 /2 versus3 /3 /3.
These are short prefixes with phase sensitivity, not indefinite locks.

The full coupled model has a saved60-second105-hit zero-return vanilla sequence
versus W-tap3 in a favorable nominal phase. Native traces now show exactly why
that word does not transfer generally: packet processing order changes vertical
impulses, landing phase, horizontal retention and the opponent's contact window.
The kernel reconstructs measured native events bit-exactly; it does not predict
independent future thread service. This is evidence against claiming vanilla
impossible, but not evidence of a native minute-long controller.

The desired neighborhood is fresh damage every9–10 ticks, horizontal and vertical
knockback multipliers0.9–1.1. Those are the requested bounds, not claimed Hypixel
constants. Reach is3 for both players and added RTTs are0/120ms unless stated.

## Latest input-timing checks (model only)

The long vanilla sequence depends on **when the ordinary click is consumed**,
not just which input ticks leave the click gate open. At the shared51ms admission
interval an immune contact consumes at1250ms after sprint has resumed; its native
40% attacker drag preserves spacing. With50ms admission, the same gate word
consumes earlier and loses that brake. Explicitly requesting the original own
consumption slots restores the long prefix without changing combat physics.

| Vanilla, same canonical seed and60s horizon | Fresh before return | Plain W-tap | Zero returns for60s |
|---|---:|---:|---|
| Original persistent gate, shared51ms admission |105|3|yes|
| Original persistent gate, shared50ms admission |6|3|no|
| Explicit own consumption slots, shared50ms |83|3|no|
| Same slots, real-frame prequeue after prior own movement |83|3|no|
| Same slots, one W-release at47800ms |95|3|no|
| Same slots, W-release at47750 and47800ms |84|3|no|

Both explicit encodings reproduce418 ordinary queued consumptions, including
immune contacts and misses. They never cancel an admitted click. The one-tick
release adds about0.277 blocks of gap before the old48.26s denial failure and
prevents it; the unchanged later word then misses reach and returns at55.925s.
Two releases spend too much of the subsequent reach budget and return at49.525s.
This isolates an effective spacing actuator and its cost, not a feedback solution.
These repairs are single-phase diagnostics, not native or robust-cycle results.

The unchanged frame-prequeue contract was then checked over11 physical phases
at the shared50ms cap. Seven give83 versus W-tap3; four give0 versus0 because
the opponent returns before the first planned1050ms maintenance click. All11
reproduce the418 consumption slots exactly. Canonical51ms gives105 versus3;
53ms misses three slot deadlines and drops to10 versus3. A53ms interval becomes
55ms on the model's frame grid: the first missed6400ms deadline follows an
admission at6347.5ms, so the next press cannot legally be injected before6400.5.
This is a bounded timing test, not a forecast of native frame service.

See the [frame-contract findings](poc-20261101/anneal/mechanism/frame-prequeue-findings.md)
[timing spread](poc-20261101/anneal/mechanism/frame-prequeue-timing-findings.md),
and [one/two-release audit](poc-20261101/anneal/astra-boundary/late-gap-repair-findings.md).

## Native transfer of the vanilla input sequence

This is a separate acquisition from the ladder below. Both A policies execute
the same18-own-tick opening, with W-tap's cue history running throughout. The
hold-W opponent starts clicking at900ms; score900–10900ms for both arms.
Vanilla h/v1, immunity10, reach3/3, RTT0/120, same20CPS ceiling, perfect own aim,
zero reaction delay. The candidate runs the saved ordinary keys and explicit
queued consumption slots; this is an open-loop program, not a feedback bot.

| Pair / native recordings | Candidate fresh before return | Plain W-tap | Candidate / W-tap first return | Candidate / W-tap zero10s |
|---|---:|---:|---|---|
|1: m1336 /m1337|0*|3|1047 /2344ms|no /no|
|2: m1339 /m1338|5|3|3740 /2347ms|no /no|
|3: m1340 /m1341|5|3|3741 /2344ms|no /no|

The first candidate has primary and full-match >100ms clock flags, retained.
The other five full matches have clean timing. All six pass the ordinary
reach/CPS/build/target checks and have complete human-equivalent client POVs
for both bots plus the server POV. No outcome-based replacement was run.
Both clean candidates reproduce the planned consumption slots, including the
source1250ms immune slowdown (native1288/1283ms, exact0.6 velocity multiplier).
The own input anchor begins34/31ms after server start. No missed deadlines or
consumption mismatches were recorded. The discrepancy is in flight state:
native gives four grounded sprint launches followed by a grounded normal launch;
the model's fifth launch was airborne.

In both natives, B's movement packet is processed before A's attack in the same
server task drain. In the nominal source, their order is reversed. This advances
the server's latent vertical motion before knockback: the first scored vertical
packet is0.311625 rather than0.34775. Later hops are lower too. The fifth normal
packet then starts on the ground and loses horizontal velocity to ground friction.
B catches up and contacts at3673/3674ms, returning at3740/3741. There was no B
contact between any of these first five velocity packets and their first moves.
W-tap also receives lower-than-nominal vertical impulses and still scores3.

The coupled kernel reconstructs both native fights through10894ms using the
measured inputs and packet service:3884/3908 events,1375/1374 compared rows,
zero bit mismatches; both POVs' click and movement reach errors below9e−16 blocks.
This validates the actual mechanics and event ordering, not the model's forecast
of independent future clocks. `/replay m1339` shows the clean five-hit prefix;
it is not a full-minute demo. The next controlled experiment extended Ctrl for
one own tick before the fifth contact, with every other source input unchanged.

The follow-up is complete. The one-row extension makes the fifth hit a grounded
sprint hit and initially restores separation. But the sixth starts airborne at
height +0.171648 and retains too much horizontal velocity: the next scheduled
click misses at roughly4 blocks. A separate early failure has perfectly executed
inputs but misses a server task drain, delaying the second launch by one tick.
B contacts before its first post-packet movement and applies0.6 drag to the new
impulse. It occurs before the changed fifth-hit input.

| Same10s native maintenance, new three-pair batches | Candidate fresh before return | Plain W-tap | Full-horizon locks candidate /W-tap |
|---|---|---|---|
|One extra W+Ctrl tick before fifth hit, vanilla, m1342–m1347|7 /7 /2|3 /3 /3|0 /0|
|Same inputs, vertical0.95, all else vanilla, m1348–m1353|5 /10 /1|3 /3 /2|0 /0|

All primary windows are timing-clean. The vanilla batch has later full-match
flags in m1344/m1345; the0.95 batch is entirely clean. All three POVs are complete.
The0.95 value was derived from the observed fifth launch: its0.384625 vertical
packet lands on movement11, whereas a packet no larger than0.36575 lands by10.
With the observed pre-hit state fixed, the exact quantized scaling threshold is
about0.950976. This predicts a local landing transition, not the global fight:
lowering the whole profile also changes earlier motion, launch values and spacing.
The varied native results show why that qualification matters. `/replay m1351`
shows the10-hit prefix with only a5% vertical reduction and ordinary ten-tick
immunity, but it still returns at6088ms and does not establish a lock.

The local prediction did succeed in that recording. The fifth velocity packet
was0.365375; B landed on its tenth movement at3871ms, before the sixth launch's
first movement at3921ms. Hits six through eight then started grounded, avoiding
the previous outward escape. Later normal launches lost the safe gap: the tenth
vertical packet was negative (−0.02075), and B contacted A between packet receipt
and its next movement, braking the new horizontal impulse before returning.

The shorter repeats expose why this is not yet a controller. In m1348 the fifth
vertical packet was identical, but the fourth launch had already been weaker:
movement-before-attack ordering gave0.365375 instead of0.40125. B's own reach
distance before hit five was2.913 instead of3.155, enough for a contact before
the first post-packet move. In m1352 the acquisition was delayed by one server
tick and the first scored launch failed. Both are ordinary timing differences,
with no >100ms clock fault. Reproducing a precomputed input word cannot correct
these different observed states.

Conditional reconstruction of m1351 covers3,852 recorded processing events and
1,374 compared rows through10889ms, with zero bit mismatches and POV reach error
below9e−16 blocks. All29 native launch vertical values match the formula, and all
30 Y/velocity/ground values in the predicted fifth flight match. This confirms
the landing mechanism; it does not validate a future service-time forecast.

The original unchanged word was also tried at opponent RTT140 instead of120,
with fully vanilla physics and the same10s horizon. This was motivated by measured
packet-order margins, but actual acquisition and later packet history also change.

| Original word, vanilla KB/immunity, RTT0/140 | Candidate fresh before return | Plain W-tap | Candidate /W-tap first return |
|---|---:|---:|---|
|Pair1, m1354 /m1355|0|3|1041 /2542ms|
|Pair2, m1357 /m1356|1|3|1296 /2494ms|
|Pair3, m1358 /m1359|1|3|1304 /2341ms|

Neither arm locks for10s. All primary windows are timing-clean; later whole-match
flags in m1355/m1357/m1359 are retained. All recordings have complete three-POV
archives and pass ordinary input/build/reach/CPS checks. This branch loses the
mandatory comparison and is stopped. A frozen-timestamp order counterfactual is
not a complete forecast when changing actual latency. The next task is to explain
why ordinary W-tap handles these entries better and what own-observation feedback
can correct, before any further controller features or profile sweeps.

The input audit finds no lost press: all three candidates consume the76 planned
pulses exactly. But their first maintenance contact waits until1091–1097ms;
W-tap contacts at943ms after the common opening. In m1354 the return at1041ms
even precedes the candidate's first admission. In clean m1358, the opening
launch is one server tick later than the clean RTT120 entry. At900ms B's own
distance is about3.114 rather than3.422. The first scored launch has stronger
vertical velocity, but begins airborne and B contacts before its first movement.
That contact brakes the new horizontal velocity from0.8485 to0.5091, allowing
the1304ms return. W-tap accepts an earlier normal hit and reaches3 before return.
The entries are independent physical runs, so this is a mechanism diagnosis,
not a same-state causal attribution of the whole score difference.

Next: audit a small passive landing estimator using only A's received position
updates and own input history, before changing control. Rendered motion alone
mixes actual target motion with client interpolation. The raw replica endpoints
and their ages are legal own-client observations already in the recordings;
they still cannot reveal B's exact future tick phase. Report uncertainty and
incorrect confident predictions, then test any resulting controller on fresh
native data with the same W-tap gate. Further fixed-word patches are stopped.

That passive audit is now complete. It uses only the querying bot's received
position endpoints, hurt cues and input history; the other POVs are consulted
after predictions, for labels. On recordings held out from estimator fitting,
the corrected observer confidently classifies6 of23 candidate fresh launches
and3 of12 W-tap launches, all correctly grounded. It makes no confident airborne
prediction, and cannot distinguish the critical fifth/sixth transitions. These
recordings were previously examined for combat outcomes; they are not unseen
scientific test data. Six successes with abstention on most launches do not
establish a usable timing controller.

Requiring different XYZ endpoints to come from different movement samples
removes an impossible inference, but the retained sample/application timing
interval is still seven ticks wide. The source-age envelope is an unvalidated
operational assumption; a separate packet-age audit is next. See the preserved
[V1](poc-20261101/anneal/mechanism/passive-landing-observer-findings.md) and
[V2](poc-20261101/anneal/mechanism/passive-landing-observer-v2-findings.md).

We also isolated entry from maintenance without changing physics or the input
word: both model arms run the same original word through4s while B does not
click, then the candidate continues and the reference becomes primed W-tap.
All free opening hits are excluded from the4–14s score. Complete state and
pending queues are identical within each pair; three physical phases are used.

| Shared admission cap; vanilla physics, RTT0/120 | Word fresh before return, three phases | Plain W-tap | Word /W-tap zero10s |
|---|---|---|---|
|50ms|17 /0 /0|2 /0 /0|1 of3 /0 of3|
|51ms|17 /0 /0|2 /1 /1|1 of3 /0 of3|

The alternate phases already enter unsafe geometry. No return or attack is
queued at handoff, so this is not a scoring artifact from a pre-opening hit.
In the favorable phase, B's last opening reach opportunity was at710ms; extending
the handicap from900ms to4s did not repair its acquisition. This branch fails
the spread gate and receives no native trial. The result is a failure of this
fixed acquisition recipe, not of maintained vanilla combos in general.
Details: [quiet-four-second diagnostic](poc-20261101/anneal/continuation/word-quiet4-acquisition-findings.md).

## Native continuation ladder

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

A bounded coupled-input test now exercises that obligation. From the complete
physical4250ms state, it searches ordinary W release/W/W+Ctrl and click gating
for actual alternating N/S fresh hits500ms apart. It realizes N-air, S-air,
N-ground, S-ground, then returns. The original900–10900ms score is10 versus
W-tap3; the same-state4250–10900ms score is4 versus W-tap2. Neither is a lock.
This is one nominal phase and a restricted six-action search, not evidence of
general superiority or infeasibility for strafing/different timing.

After the third normal launch, B's reach distance falls from4.832 to2.713 blocks
before the next strong packet can move it. The cached ray is2.741 at contact:
about0.259 blocks inside reach. During that leg, A advances1.239 blocks, but B's
delayed view of A advances2.039 as earlier movement catches up. B's contact at
5860ms is already queued when the strong launch starts moving it at5910ms;
the return registers at5925ms. The search's final rejection therefore discovers
an earlier commitment, not a new barrier at its last step. Increasing only that
normal pulse's horizontal component by10% adds about0.139 blocks by contact,
insufficient to close this selected path's deficit. Whole-profile changes would
also change earlier motion and remain untested by that local calculation.
See the [coupled NS diagnostic](poc-20261101/anneal/mechanism/ns-coupled-stage2-findings.md).

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
