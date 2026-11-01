# Deliberate strength and own-packet timing — November 1

**We now have a repeated native 60-second lock at horizontal KB ×1.1, vertical KB ×1, an eight-tick hit window and RTT 150/0 ms.**
The candidate scored 150 / 150 / 150 fresh hits before a return; matched plain W-tap
scored 2 / 2 / 2. Two candidate trials were entirely timing-clean; the first had one
108 ms client-tick gap and remains flagged. Watch the clean demo with
`/replay m1364` (another clean repeat: `m1365`).

Both players have native 3.0 reach and the same 20 CPS ceiling. The opponent moves
and sprints from the start but cannot click for the first 4 seconds; those 9 free
hits are excluded. Candidate and W-tap share that same physical opening protocol.
This demonstrates maintenance after a controlled setup, not reliable acquisition
against an opponent attacking from the start. Combat uses 10% extra horizontal KB,
vanilla vertical KB and an 8-tick fresh window (20% shorter than vanilla's 10).
No native success at 9 or 10 ticks is established.

The useful change was a simple own-hurt-cued admission schedule plus spacing
feedback enabled during the opening. Learning server phase was unnecessary in
the ablation. Deliberate S/N actuation now works as requested, but the tested
preparation protocol wastes time and spacing and performs worse. The successful
controller's 150 fresh hits are all normal, not sprint hits.

## Run it yourself

- **`/lab demo lock8`** starts the same 4-second setup plus 60-second maintenance
  demonstration, and automatically spectates it when used in-game. Its match ID
  is printed for `/replay <id>` afterwards.
- **`/duel lock8 boxing preset=lock8`** faces the controller yourself. The preset
  sets bot/your added RTT to150/0ms, both players' combat to horizontalKB1.1,
  verticalKB1, hit8, and the bot to native reach3 and cap20CPS with perfect aim.
  Your attacks are never disabled. To reproduce the controlled opening, holdW
  without clicking for the first4s, then keep holdingW and attack.
- `/lab match lock8 lock8-target preset=lock8` is the ordinary first-to100 version.
  Replace `lock8` with `lock8-wtap` for the matched baseline (same4s acquisition).
- Explicit options override preset defaults, regardless of token order, e.g.
  `/duel lock8 boxing preset=lock8 ping=60 myping=60`. `/rematch` preserves them.
  A new preset does not change shared policies or server combat defaults.

`/help settings` and `/help matches` include these commands. The aliases preserve
exactly the tested controller, W-tap and opponent inputs. This preset names the
experimental scenario; it is not claimed to reproduce Hypixel.

## Native paired results

Primary metric: fresh hits in the fixed 4000..64000 ms interval before the first
accepted return; accepted damage upgrades also end the count. Each native pair
has identical declared controls/profile/opening and duration, but independent
physical schedules rather than a model's exact complete-state fork.

| Repeat | Candidate, fresh before return | Plain W-tap, fresh before return | Candidate maximum fresh gap | Timing |
|---|---:|---:|---:|---|
|1: m1361 /m1362|150, zero returns for 60 s|2|416ms|Candidate flagged: one108ms own tick; WT clean|
|2: m1364 /m1363|150, zero returns for 60 s|2|410ms|Both clean|
|3: m1365 /m1366|150, zero returns for 60 s|2|411ms|Both clean|

The defender has **zero** geometric within 3.0 probes in 1,175 / 1,175 / 1,176 samples;
minimum distances are 3.010 / 3.116 / 3.043 blocks. It makes 1,163 / 1,163 / 1,162 native
clickMouse calls during these windows and selects the opponent zero times.
This is spacing denial in the sampled own POV, not a claim based only on missed
attacks. Probes are approximately 50 ms apart; unsampled instants are not certified.
Matched W-tap's defender enters reach in each prefix and returns. The candidate
delivers only normal fresh hits, while each WT prefix delivers two sprint hits, loses its
next attack opportunity and admits the return. The large WT gaps are 800–1301 ms
before return, compared with the candidate's roughly 400 ms cadence.

The minimum law uses local eye-to-box range to alternate A/D and release W when
closing too far, keeping W released until range recovers to 2.55. After its own
fresh-hurt acknowledgement it defers new clicks that are clearly too early for
the next hit window. It keeps every previously queued click and uses native
input, sprint state, physics and packet processing. W-tap repeatedly re-arms the
stronger hit, which pushes the opponent beyond its useful next-hit range here.
This is a tested mechanism in this scenario, not a universal ranking of normal
and sprint hits or a claimed Hypixel profile.

Native verification uses fresh m1364 and its paired m1363: the coupled model
reconstructs all 8,215/8,215 scheduled events over their first 12 s. All 3,070 compared
movement/interpolation/input/server state rows are bit-exact; the independent
nearest-box computation differs by at most 8.9e-16 blocks and selected-ray distance
by 4.4e-16. Later native states and packet values are checks, not corrections.
This is conditional validation using recorded inputs and service clocks, not
perfect prediction of future operating-system scheduling. Sources and full
comparison rows are preserved in `poc-20261101/anneal/strength-phase/native/`.

Server combat defaults are restored to vanilla 1/1/10. The verified flat arenas
now have radius 256 instead of 128; the native positive path reaches 190.6 blocks.
This is a floor extension, with no combat or slipperiness change.

This pass tests the two requested mechanisms on the existing spacing feedback.
The original settings grid is unchanged: own/opponent RTT independently
0/60/120/150ms, horizontal and vertical KB .9/1/1.1, fresh delay8/9/10ticks,
reach3/3, perfect local aim, zero reaction. Each of 432 settings has three
physical tick phases, a four-second quiet-defender acquisition, then a 60 s
maintenance horizon. Candidate and primed plain W-tap fork the complete same
state, queues and scheduler. Opening hits are excluded; the first accepted return
ends the primary fresh-hit count. Unsupported states remain unknown.

## Timing result and its causal limit

The new controller uses only its own target-hurt/position packet timestamps,
known configured RTT, and its own input history. It estimates a server phase
interval, projects which server drain an ordinary click could reach, and defers
new admissions only when all projected processing times precede fresh readiness.
Already admitted clicks are preserved, including their physical consequences.
Both sides retain the same 20 CPS ceiling; the main grid uses51ms admission spacing.
No opponent client telemetry or private server state enters decisions.

The full 1,296-cell sweep has unchanged timing, raw-arrival timing, application-time
timing, and W-tap forks in every row. Raw and application timing each produce 28
zero-return60s cells and two settings that pass all three original phases.
Neither 9 nor 10 ticks produces an all-three-phase qualifier.

| Own/B RTT; horizontal/vertical KB; fresh ticks | Old spacing, three-phase fresh prefix | Timing overlay, three-phase fresh prefix | Plain W-tap |
|---|---|---|---|
|150/0;1.1/1;8|148/5/32|150/150/150, all60s|2/2/2|
|60/120;1.1/.9;8|9/5/9|150/150/150, all60s|3/3/3|

All these successful timing-overlay hits are normal. They have400ms maximum
fresh gaps and no missed fresh windows. The primary mechanism is click-cap
alignment, not extra momentum from avoiding sprint-contact drag: measured local
sprint contacts are zero. At exactly50ms admissions, unchanged spacing already
scores150 throughout the successful phases. With51ms admission spacing, its
free-running cadence occasionally misses a useful client consume tick. Scheduling
admissions around readiness removes that slip in the favorable regimes.

Click scheduling does NOT change the native tick clock. Actual consumption
remains on own50ms ticks and server service retains its phase. Whole-tick timing
can change flight stage, strength preparation and available readiness windows;
it cannot continuously slide C02 arrival around a fixed modulo50 boundary.

## Independent phase qualification

The three original A/B/server phases are(0,10,25),(0,25,5),(0,40,40)ms. Four
additional held-out pairs independently cross B5/35 with server15/35; A stays0.
The extra phase B5/server35 breaks both settings. Results below are cap51,
raw/application overlays agree. The same failure persists at cap50 and when
only the phase uncertainty band expands from±5 to±10ms.

| Setting | Overlay across the seven phases | Unchanged spacing | Plain W-tap |
|---|---|---|---|
|150/0;1.1/1;8|150/150/150/150/150/**1**/150|148/5/32/14/5/1/32|2/2/2/2/2/**1**/2|
|60/120;1.1/.9;8|150/150/150/150/150/**42**/150|9/5/9/9/5/7/9|3/3/3/3/3/**1**/3|

At150/0 the bad phase has no missed fresh window. A normal fresh hit at4185ms
delivers velocity to B at4186, but B contacts at4205 BEFORE its first movement
using that velocity at4205.3. The return is accepted at4235. The packet arriving
before a tick is therefore insufficient: the victim's attack phase precedes its
movement. The lower-flight acquisition already leaves a too-close handoff.
This original-opening failure blocks native qualification of that version. It
is retained even though the first three phases passed. The controlled followup
below changes the physical opening, not the phase labels or maintenance metric.

## Simplification and a qualified model point

One controlled change enables the same conditional coast/readiness feedback as
soon as its own first fresh-hurt acknowledgement arrives, during the same4s quiet
opening. This changes the physical handoff state. Candidate and primed W-tap
fork that SAME new complete state; neither gets extra quiet time or a reset.
It repairs the150/0 setting in all seven phases. The60/120 profile still fails
one phase, at119 fresh hits, and is not promoted.

A phase-blind control then produces the same improvement as learning phase.
Therefore the observed gain cannot be credited to phase estimation. The minimum
controller uses only its own handled target-S19 time, own input timing, local
range and declared RTT/fresh delay. Its new-admission condition is:

```
predictedConsume = now                       at input-head poll
predictedConsume = max(now,lastInput+50ms)    at a real frame poll
permit iff predictedConsume + ownRTT +60ms
           >= lastHandledTargetHurt +50ms*hitTicks
```

The60ms retains the original unknown50ms server-period plus two5ms margins;
it is not a new tuned gain. Unknown cues allow ordinary admission. No admitted
press is cancelled; the ordinary native cap/FIFO and all contacts are preserved.
This is an opportunity estimate, not knowledge of actual server immunity.

The minimum law's OWN handled-cue opening is separately qualified; it does not
inherit a raw-arrival or phase-estimator opening.

| A/B/server phase, ms | Minimum law,50ms cap | Minimum law,51ms cap | Plain W-tap,50/51ms |
|---|---:|---:|---:|
|0/10/25|150|150|1 /1|
|0/25/5|150|150|2 /2|
|0/40/40|150|150|1 /1|
|0/5/15|150|150|2 /2|
|0/35/15|150|150|2 /2|
|0/5/35|150|150|2 /2|
|0/35/35|150|150|1 /1|

Profile: own/B RTT150/0,H1.1,V1,hit8,reach3/3. All candidate rows complete60s
with zero returns,400ms maximum fresh gap, zero missed readiness windows and
150 normal/zero sprint fresh hits. This minimum version was qualified at ONLY
this profile; the432-setting grid applies to its earlier phase-overlay parent.
The Java8 native port matches161,294 movement/admission decisions over these14
trajectories, with matched results unchanged. Its path reaches186.30 blocks from
the arena center, so the verified native floor is enlarged to radius 256 before
trials. Native evidence follows separately; model success is not native success.

## Strength selection

The first ordinary-key overlays preserve the old acquisition and spacing law.
They test repeated rearming, alternating requested S/N, received-floor S, and
descending near-landing S. The original twelve complete seeds regress exactly.
Actual strength is attributed to the consumed C02 that caused the fresh hit,
not to the later S19 observation or the key currently being requested.

| Conditional spacing; own/B RTT | Repeated S | Floor cue | Near-landing latched | Plain W-tap |
|---|---|---|---|---|
|150/0;h1.1/v1/t8|2/2/2|148/17/149|3/47/2|2/2/2|
|60/120;h1.1/v.9/t8|3/3/3|9/9/9|3/4/3|3/3/3|

The floor rule's148/149 runs actually deliver only normal fresh hits. Ctrl changes
local pursuit and contact drag without guaranteeing a server sprint hit. Repeated
strong hits instead push the target beyond own reach. A floor cue also arrives
late relative to STOP/START preparation and the future S12 movement. These
findings reject the tested key overlay, not the possibility of deliberate S/N.

There is a composition issue to fix before claiming reliable selection: the
spacing controller tracks its proposed W transitions, but a strength override can
create different actual W transitions and doubletap sprint. Key1 is therefore
not a guaranteed normal hit. A separately declared prepared-input test accounts
for actual output history, drains inherited admissions, and uses completed
STOP/ARM stages with one ordinary click. Its results are recorded separately.

The preparation protocol delivers the requested strength on86/86 accepted fresh
hits, and all three quiet fixtures pass. Nevertheless it loses: it independently
projects two phase intervals and unnecessarily delays fresh attacks by50–100ms
(450–500ms spacing against the native400ms window). The normal-hit variant also
forces two W-off inputs even when already nonsprinting, losing useful pursuit.
This is a failure of the tested preparation/scheduling protocol, not evidence
that stronger hits cannot help.

| Conditional spacing; own/B RTT | Prepared normal | Prepared sprint | Prepared landing choice | Timing-only parent | Plain W-tap |
|---|---|---|---|---|---|
|150/0;h1.1/v1/t8|2/2/4|2/2/2|2/2/5|150/150/150|2/2/2|
|60/120;h1.1/v.9/t8|2/2/3|2/2/2|2/2/3|150/150/150|3/3/3|

These use the original physical seeds, not the later minimum-law opening.
Stop this branch before a grid/native trial: it does not pass the baseline gate.

## Final minimum-law grid

After the native batch, the byte-identical minimum controller ran its OWN opening
across the same 432 settings and three phases, each paired with primed W-tap from
the identical complete physical seed. All 1,296 cells are supported; 36 candidate
cells complete60s, all at 8 ticks. W-tap completes none. There is no 9/10-tick
zero-return cell, so no closer setting is promoted.

| Own/B RTT; H/V/ticks | Minimum law, three phases | Plain W-tap | Further qualification |
|---|---|---|---|
|150/0;1.1/1/8|150/150/150|1/2/1|All seven phases at both50/51ms caps; native three pairs above|
|60/120;1.1/.9/8|150/150/150|4/2/3|Original three phases only for this final law; no native promotion|

This is a boundary of the frozen controller/opening family, not proof that
vanilla locks are impossible. Next work should explain and repair the9-tick
failure using the now-working native8-tick cycle as the reference, retaining
matched W-tap and the measured-view/clock validation.

## Native timing evidence and implementation validation

A no-fit own-S19 predictor was checked on archived native candidate/W-tap pairs.
On the held-out split, median absolute service-time error is2.479ms for candidate
queries and3.018ms for W-tap; modulo50 p95 errors are8.751/8.103ms. Despite those
small phase errors,8/55 candidate and9/66 known W-tap queries miss by over25ms.
One forecast is49.547ms wrong while its modulo50 error is only.453ms: it chooses
the wrong adjacent server tick. Eighteen W-tap labels remain unknown under the
strict FIFO pairing check. All archived cases have ownRTT0, so these numbers do
not validate150ms native transport or the model's±5ms engineering band.

The model now has a read-only own-packet callback with local arrival/application
times and only received payloads. It exposes no server clock, global packet
sequence, victim velocity, future queue or private opponent state.70 checks
cover routing, payload isolation, causality and noninterference; existing clock
and14-fork checks pass(14,972 semantic comparisons). No combat physics changed.

Details and reproducible evidence:

- [Strength inputs and causal diagnostics](poc-20261101/anneal/strength-phase/strength/findings.md).
- [Phase/actuator source audit](poc-20261101/anneal/strength-phase/phase-and-strength-audit.md).
- [Complete phase and minimum-law findings](poc-20261101/anneal/strength-phase/phase/findings.md).
- [Prepared S/N actuator results](poc-20261101/anneal/strength-phase/prepared-strength/findings.md).
- [Frozen phase experiment](poc-20261101/anneal/strength-phase/phase/preplan.md).
- [Native phase validation](poc-20261101/anneal/strength-phase/native-phase-audit/findings.md).

These are finite model and native witnesses. They do not prove an infinite
cycle or vanilla impossibility. Reliable acquisition without the opening
handicap, broader ping robustness and9–10-tick maintenance remain open.
