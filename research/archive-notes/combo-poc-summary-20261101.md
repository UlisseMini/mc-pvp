# Combo POC: a real demonstration, with a remaining timing boundary

**The simple own-observation controller held two full 60-second native locks:
527 and 535 fresh hits, zero returns.** Watch `/replay m1272` (535 hits) or
`/replay m1270` (527). These are finite demonstrations, not proof of infinity.

The declared scenario is deliberately easier than vanilla: horizontal KB 1,
**vertical KB 0, fresh damage every 2 ticks instead of 10**, RTTs 0/120 ms, reach 3.0
for both, perfect aim and the same 20 CPS ceiling. Hold-W moves and sprints
throughout, but starts clicking after the common acquisition period. Free
opening hits are excluded from every score below.

## Native results, including failures

Each row has three independent native repeats and a fixed 60 s maintenance window.
Every W-tap reference has identical settings/acquisition and zero reaction delay.

| Shared opening and controller | Fresh hits before first return | Plain W-tap baseline | Full 60 s locks, candidate / W-tap |
|---|---|---|---|
|4 s walking gap feedback|2* /527 /535|7 /5 /7*|2 of 3 /0 of 3|
|Same 4 s trials, alternative own-status W-tap cue|2* /527 /535|1 /2 /5|2 of 3 /0 of 3|
|8 s walking gap feedback|129 /114 /40*|4 /1 /8|0 of 3 /0 of 3|
|8 s Ctrl-gap feedback|1* /424 /47|7 /5* /2|0 of 3 /0 of 3|

Stars mark timing flags during acquisition or before the first return; all rows
are retained. Both full-minute successes have clean clocks throughout. The final
Ctrl-gap run has a later timing flag after its primary outcome. Different rows
use different acquisition policies, so they do not isolate Ctrl's maintenance
effect. The Ctrl version is not established as better than the original.

The two successes are actual denied reach: all 1,200 defender input opportunities
in each run are outside its own reach, and all 1,167/1,165 actual clicks miss.
Its minimum actionable nearest distances are 3.093/3.232 blocks. Our contacts use
ordinary native reach and inputs. Some earliest fresh windows are missed through
input timing, so these are not perfect 600-hit runs.

## What the controller does, and why it works

It holds W, keeps the normal capped click gate open, and uses only the opponent's
distance **on its own client**. Alternating A/D pulses slow forward pursuit when
too close. There is no live opponent-POV or server-state input.

In the regular two-tick knockback cycle, the defender retreats about 0.198 blocks
per tick. Walking W versus W+A/D spans roughly 0.153–0.216, letting feedback match
that retreat. Delayed client views then let us hit while denying their return.
Plain W-tap disrupts this balance through its release, sprint and knockback
sequence. The lesson is speed matching, not maximizing knockback.

Native packet timing can break the regular cycle. In two clean walking-controller
failures, closely spaced velocity deliveries produce defender steps around
0.375/0.315 instead of 0.273/0.123. Walking cannot catch up; own reach is lost, fresh
knockback stops, and the opponent runs back into range. The held-Ctrl variant
adds recovery speed but also contact braking, and has not eliminated this failure.
Its clean failures confirm the same sequence; one needs only a single preceding
burst and a six-tick fresh-hit gap. The actual controls and click timing check out.

## What the parallel work ruled out

The toy thermostat treated movement and hit strength as independent; it lost to
W-tap in the exact kernel. Frozen oracle scripts also failed native transfer.
Those branches stopped. Constant held sprint without distance feedback tied
W-tap natively and stopped too. Feedback produced the useful positive results.

A revealing model ablation changes only physical click spacing from 50 to 51 ms:
constant hold drops from 584–600 fresh hits against W-tap 8–14 to 1 against 1.
Thus a periodic-clock success can be fragile even when the mechanics are exact.
A fresh native failure reconstructs with 3,071 event rows and 323 distance/ray
checks, all zero error, **given its recorded inputs and processing times**.
That does not validate forecasts using idealized future clocks.

Walking toward vanilla remains unsolved. Tiny vertical values are erased by
native quantization/cutoffs; larger ones change the launch cycle and break the
current law. There is no vanilla impossibility proof and no claim that this
profile is Hypixel's. The next useful step is to reproduce the measured service
perturbations in a matched model test and establish enough spacing/control margin
before changing more physics. The server is back on vanilla defaults.

[Full findings and baseline tables](combo-findings-20261101.md) ·
[Native success audit](poc-20261101/legal-controller/native-m1270-findings.md) ·
[Latest native comparison](poc-20261101/ctrl-gap-native-trial.json) ·
[Clean failure audit](poc-20261101/legal-controller/native-ctrl-gap-break-audit.md)
