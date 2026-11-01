# Combo POC: what works, and what still breaks

**A simple legal bot has now held two native, full-reach, 60-second locks:
527 and535 fresh hits without being hit back.** Matched plain W-tap gets only
a few hits. Watch `/replay m1272` for the clearest demonstration.

This works with a large declared relaxation: horizontal knockback1,
**vertical knockback0, fresh damage every2ticks instead of10**, and RTTs0/120ms.
Both players have3.0 reach, perfect aim and the same20CPS ceiling. The opponent
walks/sprints toward us throughout and starts attacking after a shared acquisition
period. Free opening hits are excluded. This is neither vanilla nor proof of
an infinite lock.

## The honest comparison

All three native repeats below use the same four-second opening and60-second
scoring window. Schedules vary naturally between native runs. W-tap has zero
reaction delay; its two columns differ only in whether it reads sampled hurt
animation or its own delivered fresh-hit status packet.

| Native repeats | Gap controller | Plain W-tap | Plain W-tap, status cue |
|---|---|---|---|
| Fresh hits before first return |2* /527 /535|7 /5 /7*|1 /2 /5|
| Full60s without a return |no* /yes /yes|no /no /no*|no /no /no|
| Replay IDs |m1265 /m1270 /m1272|m1274 /m1268 /m1273|m1267 /m1269 /m1271|

The stars mark timing flags before the primary outcome. Both successful runs
have clean clocks throughout. The defender actively tries to attack: all1,200
attack opportunities in each success are outside its own reach. Its actual
1,167/1,165 clicks all miss. Our bot remains in its own reach. Real capped input
timing still misses some earliest damage windows; these are not perfect600-hit
runs.

The completed eight-second-opening trial is less stable:

| Native repeats, same60s maintenance | Gap controller | Plain W-tap | Status W-tap | Hold-W control |
|---|---|---|---|---|
| Fresh before first return |129 /114 /40*|4 /1 /8|2 /7 /4|5 /5* /1|
| Full60s without return |no /no /no*|no /no /no|no /no /no|no /no* /no|

Stars again mark acquisition timing flags. More acquisition time does not fix
maintenance stability. Hold-W later finds250- and454-hit unanswered chains
after its initial failures; those are interesting secondary evidence, not its
score on the prescribed starting-window task.

## The controller and mechanism

It holds W without Ctrl and clicks through the ordinary capped input path.
Using only the distance to the opponent **as rendered on its own client**, it
adds alternating A/D pulses when too close. Diagonal movement reduces forward
pursuit speed. There is no opponent-client telemetry or server-state oracle in
the live controller.

The usual two-move knockback cycle makes the defender retreat at about.198
blocks/tick. Our walking speed can range from about.153 to.216 using W versus
W+A/D, so feedback can match that retreat and keep the gap. The delayed views
then permit our hits while denying theirs. W-tap changes sprint/hit strength
and loses this particular balance.

But native packet timing sometimes changes the ground-flag cycle. In failed
m1275, two closely spaced velocity deliveries produce defender steps of roughly
.375/.315 instead of.273/.123. Our maximum walking speed cannot keep up. We
lose reach, stop supplying knockback, and the defender runs back into us. This
is a concrete control-authority failure, not evidence that all legal controllers
must fail.

## What the parallel work established

- The simplified model exposed a useful speed-matching idea, but its first
  controller incorrectly treated movement and sprint knockback as independent.
  It lost to W-tap in the exact kernel, so that branch stopped.
- Oracle trajectories produced long model locks at reduced reach. Distilled
  temporal scripts then lost to W-tap natively. Timing corrections were tested;
  the weak script branch stopped instead of accumulating more features.
- The feedback branch produced the native successes above. Walking settings
  back toward vanilla has not yet produced a meaningful positive: tiny vertical
  impulses get erased by the movement cutoff; larger ones change the server's
  inferred motion and weaken later horizontal launches.
- The timing branch is extending the same fight model to distinguish capped
  physical clicks from queued native consumption. Recorded-input mechanics are
  exact on tested traces; future click admission and OS scheduling are a separate
  validation problem.

There is **no vanilla impossibility proof**. The next justified work is to test
the measured packet-phase failure causally, widen legal pursuit/braking control,
and validate that controller against the same W-tap references before another
physical continuation step.

Detailed evidence: [full findings](combo-findings-20261101.md),
[native success audit](poc-20261101/legal-controller/native-m1270-findings.md),
[matched native trial](poc-20261101/strong-native-trial.json), and
[vertical continuation](poc-20261101/continuation/vertical-continuation-findings.md).
