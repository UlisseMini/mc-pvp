# Linear timed blocking plus W-taps — 2026-10-31

The updated `/duel blockhit_timing diamond` uses genuine HeadlessMC inputs on
Minecraft 1.8.9: perfect client-visible aim, native three-block reach, maximum
10 clicks/second, forward/sprint keys, post-hit W-taps and short right-click
holds. No server health, velocity or forthcoming attack is exposed to it.
Artificial reaction delay is zero, as authorized for the later experiments.

## Final repeated results

The final policy won **12/12 real diamond duels**, including the two exploration
matches used to select independent guards and ten subsequent confirmation
matches. Allocation was reversed on every second game. This does not establish
an unbeatable bot; one equal-ping confirmation win had only 0.77 hearts left.

| Opponent | Bot / opponent added RTT | Wins | Mean remaining hearts | Incoming hits blocked | Server blocking uptime |
| --- | --- | ---: | ---: | ---: | ---: |
| W-tap | 0 / 0 ms | 2/2 | 3.27 | 43.4% | 4.0% |
| W-tap | 20 / 20 ms | 4/4 | 4.00 | 31.5% | 5.1% |
| W-tap, zero reaction delay | 20 / 20 ms | 2/2 | 2.66 | 34.7% | 4.3% |
| W-tap | 100 / 20 ms | 2/2 | 0.32 | 12.3% | 4.1% |
| W-tap | 20 / 100 ms | 2/2 | 1.36 | 29.4% | 4.3% |

The equal-ping advantage is useful; asymmetric-ping blocking is still weak,
especially when our own RTT is 100 ms. Treat those narrow wins as inconclusive
about a reliable large advantage. No losses from other candidate policies were
removed: all 59 current-iteration games remain in their frozen trial directories.

Key holds had medians of 50–51 ms and a maximum of 64 ms. Native server guards
had a maximum of 105 ms across these twelve matches, with no long desynchronized
guards. All twelve passed native reach, 10 CPS input-cap, opponent-identity,
source/build and block-time reconstruction checks. During trials the recorder's
mean server tick intervals were 49.97–50.00 ms, consistent with 20 TPS.

After the first five online corrections per game, the 408 recorded calibration
residuals had **+0.62 ms mean error and 46.14 ms mean absolute error**. Every
game's mean lay between −2.39 and +6.04 ms. This supports small measured bias,
not guaranteed unbiasedness or exact hit timing. At the causal command horizon,
468/507 native hurt cues had a finite forecast; a separate alarm-based check
found 37/506 forecasts without a subsequent cue within 250 ms of predicted time.
Those are distinct diagnostics, not interchangeable success rates.

[Machine-readable outcomes and frozen-configuration checks](../experiments/blockhit-final-evaluation.json)
and [timing, forecast coverage and native-window audit](../experiments/blockhit-final-timing-audit.json).
The `blockhit_free_guard` experiment name and live `blockhit_timing` configuration
are identical except for their description; the summary checks this before pooling.

Recordings: [6-heart W-tap win](../recordings/m0789.html),
[close reversed-side W-tap win](../recordings/m0791.html),
[zero-ping 45% blocking example](../recordings/m0782.html).

Reproduce the final summary:

```sh
python3 blockhit_evaluation_report.py \
  experiments/blockhit-20261031-204632-ad7de0 \
  experiments/blockhit-20261031-204923-fbd335 \
  experiments/blockhit-20261031-204923-9fff9a \
  experiments/blockhit-20261031-204923-d20bff \
  experiments/blockhit-20261031-205103-5b8931 \
  experiments/blockhit-20261031-205248-873acf \
  --alias blockhit_free_guard=blockhit_timing \
  --output experiments/blockhit-final-evaluation.json
```

## Final algorithm

1. Fit each player's x/y/z position against time over the last three local
   observations. This is ordinary least squares, `position = velocity*t + b`.
   Project at most 300 ms ahead, in 5 ms steps, clamping extrapolated floor
   height and implausible fitted velocities. It is not a physics simulator.
2. Approximate the opponent's view using 150 ms remote interpolation plus the
   configured half-RTTs. Project our box backward and their eye forward by
   that view age, then test eye-to-expanded-box distance against three blocks.
3. Use our most recent native S19 fresh-hurt notification plus 500 ms as the
   earliest next fresh-hit cue. Critical damage upgrades during immunity do
   not generate this cue and do not restart the clock.
4. Start with a +25 ms cue-forecast intercept. After receiving a fresh cue,
   compare it with a forecast actually issued at least `own RTT + 50 ms`
   beforehand (capped at 250 ms). Move the intercept by 20% of the error,
   limiting each error contribution to ±150 ms and total correction to ±100 ms.
   This correction resets every match; no future data or server event is read.
5. Convert expected cue receipt to a proposed command time by subtracting our
   RTT. Remember the impending threat so an already-in-range forecast cannot
   keep moving into the future. Admit a guard when its command is within ±50 ms,
   independently of our attack cadence. Release right-click on the next client
   tick. Vanilla still handles item use and any attack suppression; the bot
   does not inject an extra attack or bypass blocking's movement penalty.
6. Independently release W for 50 ms after observing a landed-hit cue, then
   resume W and sprint. A small cue queue supports controlled W-tap-delay
   comparisons. The selected configuration uses no extra tap delay.

The selected click cadence starts when the native ray selects the opponent and
continues at the capped rate while in range, including immune-frame clicks.
This is normal attack-key input, not direct attack calls or packet injection.

Sources: [predictor](../src/lab/LinearHitPredictor.java),
[guard controller](../src/lab/TimedBlockController.java),
[client integration](../src/lab/Client.java),
[configuration](../policies/blockhit_timing.properties).

## Timing calibration and rejected changes

The first six no-block shadow games calibrated the geometry. A static +35 ms
intercept from the first-side games overcorrected the reversed-side games:
held-out raw mean error was +13.4 ms, versus −21.6 ms after correction at a
100 ms horizon. Therefore the controller adapts one intercept during each
match rather than claiming that one static offset is unbiased everywhere.
[Shadow calibration and coverage](../experiments/blockhit-linear-calibration.json).

Initial adaptive pilots, after their first five calibration updates, had mean
cue errors +11.3/+1.8/+0.3 ms at 0/0, 20/20 and 100/20 ms RTT. Absolute errors
were 29.4/50.7/59.4 ms. These are conditional on a finite earlier forecast;
small mean bias does not mean individual hits are predicted accurately.
Final reports also include missing forecast coverage and false alarms.

A separate native pipeline audit measured approximately 40–48 ms of key/input,
server scheduling and cue-dispatch delay beyond configured network RTT in the
initial pilots. Subtracting another 45 ms from command time was tested as a
single change, followed by centering the admission window 25 ms earlier.
Neither improved the sampled health margins or hit-block rate. The admission
also depends on our next swing and native reach, so shifting its window is not
an isolated translation of every actual guard. The final policy retains
zero extra delivery correction; the measured pipeline delay is still real.
[Native timing audit](../experiments/blockhit-pipeline-audit.json).

Two-tick (~100 ms) input guards appeared strong but are **excluded from short-
blocking evidence**: native sword inventory replacement sometimes clears local
item use before release, leaving the server guarding for seconds. Maximum
server windows in those two games were 9.8 and 17.1 seconds. The experimental
profile was removed from the live bot list. The one-tick profiles are audited
against native server start/stop events; short key presses alone are not proof.

## Initial selection experiments

All games below used diamond sword, full Protection IV diamond, natural damage
and regeneration, full food/saturation, normal speed, and 20/20 ms added RTT.
Two-game comparisons reverse bot allocation on the second game. They are small
exploratory samples, not statistically isolated estimates of every interaction.

| Variation | Wins vs W-tap | Remaining hearts | Incoming hits blocked |
| --- | ---: | --- | --- |
| Immediate W-tap, fixed clicks, one-tick predicted guard | 2/2 | 2.53, 2.42 | 22.9%, 34.8% |
| Start clicks in native reach (initial selection) | 4/4 | 4.02, 3.26, 5.13, 3.16 | 32.6%, 34.9%, 38.6%, 35.6% |
| Selected + 45 ms delivery correction | 2/2 | 4.82, 1.60 | 31.0%, 27.8% |
| Delivery correction + earlier-centered window | 2/2 | 2.71, 1.87 | 24.1%, 17.0% |
| Block after every in-range swing, immediate W-tap | 2/2 | 3.50, 0.45 | 19.5%, 27.9% |

Selected-profile evidence: [first pair](../experiments/blockhit-20261031-203843-f034ff/report.json),
[repeat pair](../experiments/blockhit-20261031-204208-b54671/report.json).
Delivery and centering: [delivery](../experiments/blockhit-20261031-204208-8d115f/report.json),
[centered](../experiments/blockhit-20261031-204208-5c1111/report.json).
The remaining exploratory variants and all their raw results are retained in
the timestamped `experiments/blockhit-20261031-*` directories.

## Validation and metrics

The runner freezes source, policy and agent hashes, checks normal reach, native
opponent identity, input caps and declared reaction delay, and records both
client decisions and server outcomes. It independently reconstructs block
attempts, successful attempts, block percentage and blocking milliseconds from
native events. Long native guards are explicitly flagged in new reports.

Blocking time sums native server guard intervals, clipping an open interval
at match finish. Blocking uptime is that time divided by fight duration;
saved hearts per blocking second uses the same per-hit counterfactual damage
saving already validated against native armor, Protection rolls and criticals.
It is an efficiency statistic, not a replacement for win rate or damage saved.

Unit checks cover linear approach, independent player velocities, immunity,
network delay mapping, causal intercept updates, reset between duels, attack
alignment, one-tick release and cumulative/open guard timing. The existing
native damage-math checks remain applicable: damage calculations were not changed.

## Follow-up: forecast commitment and independent guards

Cross-ping tests exposed a scheduling failure even though cue forecasts had
small average error. Once in range, a recomputed forecast can become
`now + intercept`. Subtracting RTT gives `now + intercept - RTT`; if the
remaining intercept exceeds the guard's lead, every new tick moves the target
away again. A low mean forecast error alone therefore does not prove the
controller will act at the right time.

The `block_latch` control remembers an impending threat until its admission
window passes, a fresh hurt cue arrives, or the model no longer predicts a
near hit. At zero RTT it improved the two sampled wins from about 1.1 hearts
each to 3.56 and 3.31 hearts, while blocking 35.4% and 30.6%. At 20/20 it won
both games but with smaller margins (1.96 and 1.58 hearts) than the unlatched
selection. This is a tradeoff, not evidence of universal dominance.

We also removed the requirement to wait for our own queued swing. This makes
blocking an independent defensive input, as originally requested, while
retaining the same predictor, forecast commitment, one-tick release and W-taps.
The first reversed-side pair won with 5.10 and 4.11 hearts remaining. It blocked
24.2% and 31.7% of incoming hits. Shorter guards avoid the old controller's long
attack suppression; vanilla can still discard an attack during item use.
[Independent-guard pair](../experiments/blockhit-20261031-204632-ad7de0/report.json).

The otherwise identical no-block trading control won its two 20/20 games with
2.51 and 0.78 hearts remaining. Therefore the improved movement/click timing
also contributes. The same-hit native damage saving is directly measured;
small independent match samples alone cannot precisely isolate the total
causal benefit of blocking on movement and later exchanges.
[No-block control](../experiments/blockhit-20261031-204611-fb1a0e/report.json).
