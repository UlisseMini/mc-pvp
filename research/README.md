# Physics before policy search

This work follows the [user's original brief](USER-BRIEF-2026-10-23.txt), preserved
verbatim, and the source-checked [formal specification](../FORMAL-MODEL.md).
The brief's recollections and simplified simulations are hypotheses, not native
Minecraft evidence. Broad parameter search is paused while we test the mechanics.

## What a combo must maintain

A combo is a repeated **one-sided opportunity**, not just an unanswered score.
After each fresh hit, the attacker must arrange another legitimate contact while
the defender's own delayed view still offers no return hit. The relevant state
includes both velocities and heights, both rendered histories, server immunity,
server sprint, pending packets, and click/tick phases. Gap alone is insufficient.

In the limited constant-speed approximation, with positive speeds toward each
other, the attacker's geometric advantage is

    (own reach limit − opponent reach limit)
        + own approach speed × delay in their view
        − their approach speed × delay in our view.

That explains why chasing a retreating airborne player can favor the attacker,
and why enabling the victim's W input can remove the advantage. It does not prove
a lock: actual rays, landing, click slowdown, immunity and packet ordering decide
whether the next exchange is one-sided. Our exact tests use each native client's
raycast rather than this scalar approximation.

For an indefinite claim, [section 9](../FORMAL-MODEL.md#9-a-precise-combo-invariant)
defines a return map between fresh hits. A set of states must return into itself,
always yield another fresh hit within a declared time, deny every return
opportunity, and respect the delayed-observation input restriction under all
declared timing uncertainty. A finite recording supplies evidence, not that proof.

## Two different validation standards

1. **Conditional transition:** give the model the measured native pre-state and
   input, then compare one predicted transition. This isolates an equation.
2. **Uncorrected composition:** initialize once, replay input and clocks, and let
   the model predict future states and packets without importing actual later
   velocities, hits or positions. This tests whether the equations compose.

The second standard is essential. We found a wrong sprint acceleration transition
even though the travel equation passed when supplied the measured acceleration.
We also found a missing fall-damage velocity packet that caused a long rollout to
diverge after its first few exchanges. Neither error should be fitted away by
changing a bot's reward weights.

**Current acceptance target: zero differing bits and discrete events.** Earlier
distance tolerances describe progress only. The new independent travel kernel
matches all 2,091 fresh transitions in the [exact suite](exact-parity-v2.json),
including retained bounding boxes, knockback takeoffs and landings. It is a
conditional component result, not full-duel parity and not yet a replacement for
the approximate policy model. The [exact-parity report](exact-parity-report.md)
records the evidence, remaining composition gap and next minimal experiment.

## Native evidence

- [Which return opportunities break a double](trade-return-windows.md): stricter
  A-then-A-before-B scoring, exact contact/S12 ordering, and positive reach
  opportunities between the defender's actual clicks. The
  [late-coast response](trade-coast-response.json) also measures how little a
  last-minute local movement change can affect the opponent's already delayed
  view; its frozen boundary conditions are explicit.
- [Unreset local trajectory replay](trade-local-replay-v1.json): 13,209 movement
  steps across 14 clients, with no position/motion corrections and zero differing
  checked bits. Recorded contact/sprint/aim/acceleration and S12 streams remain
  boundary inputs. This is a larger conditional composition check, not the full
  two-player autonomous replay standard above.
- [Why W-tap sometimes doubles hold-W](double-hit-mechanisms.md): full baseline
  games, isolated reset changes, native click results and landing-phase evidence.
  The exact packet links in [trade-grounding.json](trade-grounding.json) retain
  intervening local contact slowdown and sprint metadata, and separate the older
  arena recordings from the repaired-arena trials.
- [Initial controlled suite](native-mechanisms-v2.md): 11 scenarios; motion without
  contact, then W-tap against stationary, approaching, non-clicking, delayed-click,
  and ordinary clicking opponents at added RTTs 0/0 and 100/100 ms.
- [Fresh asymmetric RTT trials](native-mechanisms-holdout.md): 20/100 and 100/20 ms,
  with the additional actual-look and velocity-application probes.
- [Entry versus maintenance](entry-maintenance-analysis.md) establishes a
  stationary combo for eight seconds, then enables the defender's ordinary
  W+Ctrl. This separates entry from maintenance; two no-click crossing controls
  additionally exercise movement/view geometry without combat impulses.

All comparisons use native 1.8.9, ordinary input, at most 10 physical CPS, 150 ms
tactical delay, normal speed and no potions. Scientific probes only record state;
private server/other-client diagnostics never feed the controller. No-click or
late-click opponents are explicitly declared handicaps. Ordinary clicking runs
keep clicking through immunity and misses.

In the initial suite, 12,330 movement transitions, 11,397 interpolation steps,
2,079 local contact slowdowns (including 1,609 during visible hurt), 2,023 server
attack decisions and 512 fresh-hit velocity packets passed their declared
conditional checks. Three of 11,356 ideal-aim classifications failed; these remain
in the report. Passing equations is **not** certification of the full simulator.

Across all **23 native scenarios**, the [aggregate](aggregate-validation.json)
contains 24,982 passing movement steps, 23,562 interpolation steps, 4,660 contact
slowdowns, 4,579 attack-acceptance checks, 1,121 fresh sword impulses and 614
observed client velocity overwrites. Four of 22,978 ideal-aim classifications
disagreed. All 11,619 checked actual-look classifications agreed outside the
predeclared boundary exclusion. Historical ideal-aim failures remain recorded.

W-tap kept stationary opponents denied sampled reach through most of the 30-second
trials, including clicking opponents. It did **not** maintain such a lock against
sprinting opponents. The 42–0 score against a non-clicking sprinter is not a lock:
that opponent had native return opportunities for about 7.8 of the 30 seconds in
`m0362`. Withholding its opening clicks for 1.8 seconds did not solve maintenance.
These are finite mechanism trials, not first-to-100 tournament rankings.

The movement intervention established a stationary denial sequence before W
began in both no-click cases. Their first measured return opportunities arrived
2,066 ms and 2,073 ms after W onset at 0/0 and 100/100 ms added RTT respectively. The
100/100 clicking case also acquired a lock, then offered return reach 2,068 ms
after W onset. The 0/0 clicking case did not meet the three-contact acquisition
criterion at the switch, so it cannot establish a maintenance failure from that
starting point. This is evidence against the current W-tap maintenance policy,
not a proof that an optimal policy cannot keep an approaching opponent locked.

Watch the [movement-only intervention](../recordings/m0380.html) or
[clicking intervention](../recordings/m0381.html), with the switch at 8 s.

Watch the [stationary clicking case](../recordings/m0361.html) and
[approaching non-clicking case](../recordings/m0362.html). These are portable
trajectory/data replays, not rendered Minecraft video. Their underlying native
client and server streams are preserved in [traces/](traces/).

## Corrections and open discrepancies

- **Ground acceleration changes immediately; air acceleration is cached.**
  Correcting the ground transition reduced the first sprint-start prediction
  error at 150 ms from about .0294 m to .000011 m in `m0360`. Native player virtual
  dispatch, not the inherited living-entity cache, determines ground acceleration.
- **Immune contacts still slow the locally sprinting attacker.** The observed
  multiplier is .6. The fixed physical click stream remains independent of server
  immunity; no “skip immune clicks” optimization was introduced.
- **Server motion and client motion differ.** Fresh sword knockback uses the
  server's latent motion, sends a quantized vector, then restores that server
  motion. The receiving client overwrites its own velocity. A fixed .4/.5 vertical
  launch is not universal.
- **Requested aim is not necessarily the native ray direction between ticks.**
  Native `getLook(1)` uses head yaw, which the living update synchronizes. Three
  historical mismatches followed abrupt turns; fresh traces record actual eye,
  look, expanded box and selection to test this attribution directly. The native
  controls have not been changed to force faster head synchronization.
- **Fall damage is part of the combat dynamics.** In `m0362`, the first large
  trajectory error follows a non-sword velocity packet at about 2.29 seconds.
  Ordinary fall damage can change immunity, emit a hurt cue and cause the tracker
  to overwrite client motion with server motion. An animation alone cannot prove
  that a sword hit landed. The new optional transition predicts the native fall
  at the recorded time without importing its result. Its isolated effect reduces
  defender error at 5 s from 1.383 m to .046 m; see the
  [causal diagnosis](fall-damage-diagnosis.md).
- **Millisecond ties can misassign physical clicks to native ticks.**
  [Independent reproduction](click-order-diagnosis.md) found seven such presses
  in `m0362`'s first 10 s. Local nanosecond ordering resolves them. With both
  corrections, the replay matches all 14 native fresh contacts and stays within
  .25 m for that 10 s case, without state correction. Error at 9 s is .092 m/.142 m.
- **The full forecast remains unvalidated.** Even replaying the actual two input
  streams and native tick timestamps did not eliminate long-horizon drift.
  Head-yaw modeling alone did not alter the five selected first-10-second
  rollouts. That factor is not an explanation of their large trajectory errors.
  The [combined replay results](composition-results.md) retain the failures on
  fresh unequal-RTT cases; success on one trace is insufficient.

The [formal specification](../FORMAL-MODEL.md) also corrects sprinting criticals,
damage upgrades, the padded-box low-ground threshold, and forced tracker updates.

## Reproduce and extend

With the existing prepared lab running:

```sh
python3 -m unittest -v test_lab test_model
python3 science.py run another-native-suite
python3 science.py run another-asymmetric-suite --holdout
python3 science.py run another-entry-maintenance-suite --wake
python3 clock_replay_check.py native-mechanisms-v2 --matches m0360 m0362 m0363 m0364 m0367
python3 clock_replay_check.py native-mechanisms-v2 --matches m0360 m0362 m0363 m0364 m0367 --head-yaw
python3 clock_replay_check.py native-mechanisms-v2 --matches m0360 m0362 m0363 m0364 m0367 --head-yaw --fall-damage --monotonic-clicks
python3 clock_replay_check.py native-mechanisms-holdout --head-yaw --fall-damage --monotonic-clicks
python3 research/click-order-reproduction.py
python3 entry_analysis.py
```

Native suite names cannot overwrite existing evidence. Each suite saves its
fixture list, policy parameters, agent hash and numerical gates before starting;
failures and raw counterexamples remain visible. Archived `.jsonl.gz` streams
support offline replay without the mutable runtime logs. `native-mechanisms-v1`
is a quarantined instrumentation pilot; it is not included in the qualified suite.

Continue with the smallest unequal-RTT counterexample. Validate
tracker emission/application timing and forecasted rendered views, including
crossing and landing. Only after those pass should we search for a robust combo
return set or rank stronger policies against W-tap with single-factor ablations.

The [first unequal-RTT failure](replication-timing-diagnosis.md) is now localized:
the simulated target is 9.3 cm too close in A's rendered view, making a predicted
2.948 m contact where the native ray is 3.041 m. That creates a slowdown and launches
the rest of the exchange early. The next experiment must record each tracker
entry's counter and dirty/airborne flags, emission times and main-thread packet
application. Choosing the phase that best fits the outcome would not validate
that mechanism.
