# Movement speed and effective reach in native 1.8.9

This experiment asks how a speed difference changes the two players' reach
opportunities, including at zero added ping. It changes no combat/physics code,
reach, position or velocity. The bots use ordinary controls, native three-block
raycasts, zero artificial reaction delay and (in attacking conditions) 10 CPS.
Health/armor changes from the practice kits do not affect these boxing fixtures.

The theory and fixture list were saved before collecting the main cohort in
[speed-reach-v2.json](speed-reach-v2.json). That file also contains frozen policies,
build hashes and full match results. The analysis is reproducible with:

```sh
python3 reach_speed_report.py speed-reach-v2
```

## Define the quantity before fitting it

On a straight, flat approach, put A to the left of B. Let their current local
positions be xA and xB, and let rA|B mean A's rendered position in B's client.
Each client predicts its own movement but observes the opponent through packets
and interpolation. At one shared wall-clock instant:

    real separation:       d  = xB - xA
    separation in A's POV:  gA = rB|A - xA
    separation in B's POV:  gB = xB - rA|B
    A's view advantage:    E  = gB - gA
                              = (xA - rA|B) + (xB - rB|A)

Positive E favors A. This is a difference in perceived separation, in blocks;
it is not an increased raycast limit. With equal, aligned hitboxes it is the
width of the equivalent one-sided reach band. Different heights/aim directions
add a geometry term; an actual attack must still pass the native raycast and
server acceptance checks.

Define uA and uB as signed velocities **toward the opponent**, in blocks/second.
An opponent moving away from you has a negative approach velocity. If velocity
is approximately constant over the view's age:

    E ≈ uA * tau(A→B) - uB * tau(B→A)

The delays are complete local-position-to-remote-view ages, not just network
ping. For equal delays tau:

    E ≈ tau * (uA - uB)

If the ages differ, writing their mean as tauBar and difference as deltaTau:

    E ≈ tauBar * (uA-uB) + ((uA+uB)/2) * deltaTau

Thus equal ping does not imply equal effective ages. Even a modest difference
between the client/tracker phases can outweigh a small speed advantage.

For changing velocity, replace each speed-times-delay term by its recent
signed displacement. Remote interpolation actually blends historical positions;
a single delayed position is an approximation to that blend. The secondary
model fits one effective history delay on the same calibration subset and uses
recorded local trajectory history, with no rendered-target measurements fed into
its prediction. It is an offline conditional predictor, not a full autonomous
Minecraft replay or a prediction of an unknown opponent's future controls.

## Where delay comes from

The pinned native source tracks players periodically every two server ticks,
with additional forced updates. Client movement-packet handling gives the
remote entity three interpolation increments. Packet delivery, server task
processing, client task processing and each client's tick phase also contribute.
These delays exist on loopback. The local native probes are the reference for
our measurements; source mirrors provide readable versions of the same code:

- [EntityTracker: player update frequency](https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/entity/EntityTracker.java)
- [NetHandlerPlayClient: movement interpolation increments](https://raw.githubusercontent.com/Marcelektro/MavenMCP-1.8.9/master/src/main/java/net/minecraft/client/network/NetHandlerPlayClient.java)
- [EntityOtherPlayerMP: interpolation recurrence](https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/client/entity/EntityOtherPlayerMP.java)

For symmetric links, a useful first approximation is:

    tau ≈ engine view age + (RTT_A + RTT_B) / 2000     [seconds; RTT in ms]

A's position must travel A→server→B, so both network legs matter. This is not
proof of identical behavior on all servers: server forks, client versions,
tracking/lag-compensation rules, load and asymmetric network paths can change it.
First-hit arrival also depends separately on the attacker's outbound path.

## Reach opportunity versus first accepted hit

With steady closing speed c=uA+uB>0 and approximately constant advantage E:

    earlier reach opportunity ≈ E/c seconds
    earlier server attack ≈ E/c + (B click wait - A click wait)
                                  + (B outbound wait - A outbound wait)

Ticks make movement discrete, so the smooth E/c expression is a planning
estimate. An earlier reach opportunity does not guarantee the first accepted
hit. At 10 CPS a short opportunity can be missed between clicks. Once a hit lands,
knockback and click slowdown alter both histories; measuring the victim's actual
first return opportunity then is not the same as the no-hit counterfactual.

A sprinting pursuer and an equally fast retreating target have equal speedometer
speeds but opposite signed approach speeds. Their unsigned speed difference is
zero while their view advantage can be large. Therefore there is no universally
correct mapping from the difference of two speedometer magnitudes to reach.

## Design and measurement limits

The main cohort contains 88 planned trials:

- 32 no-attack trials: standing, walking, sprinting and equal-speed controls;
- 30 four-second attacking trials of the five moving pairings;
- six 20-second W-tap exchanges against standstill, walking W-tap and hold-W;
- 12 non-attacking ping checks at 50/50, 0/100 and 100/100 added RTT;
- eight acceleration checks using 100 ms W-on / 100 ms W-off cycles. Native
  double-tap sprint behavior remains enabled; actual movement is measured.

All conditions swap starting sides. Two trials run concurrently and reserve
capacity for human practice. Speeds come from actual displacement per native
movement tick, not the stored post-friction motion field. Both clients' local
positions and rendered target positions are aligned by real system time.
Native states are step functions; the analysis samples these at 5 ms spacing,
which does **not** create independent observations. Reported averages and error
comparisons are aggregated per match. Approximately 50 ms native raycast probes
supply independent classification checks and timing brackets. Finer first-crossing
times are reconstructed from native positions and the verified flat ray geometry,
not presented as continuously sampled native raycast timestamps.

Fit only the first four repetitions of the three unequal-speed, zero-ping,
non-attacking conditions (12 matches). Hold out the last two repetitions,
equal-speed controls, attacking matches, acceleration and ping conditions.
The fixed load gate excludes calibration/evaluation trials with more than 2%
of server tick gaps above 80 ms, or any above 200 ms. Failed setup checks remain
recorded separately and are not turned into successful physics evidence.

The initial 1.8-second pilot was too short for the existing runner's heartbeat
check, which starts two seconds into a match. Some 3.5-second retries also lost
that check when synchronous postprocessing of another trial blocked its polling.
The runner now skips historical result files and runs each match supervisor in
an independent worker; it retains the same heartbeat checks. These are harness
failures, not evidence for or against the movement model. Their traces/results
remain available. No live Minecraft process or controller needed a restart.

Numerical results are written to [speed-reach-v2-analysis.json](speed-reach-v2-analysis.json).

## Results and refined rule

All 88 planned trials completed and passed the native input/setup checks. No trial failed the preregistered server-load gate. The simple flat reach classification matched all 1,672 checked native raycasts; this validates the flat geometry check, not a complete combat predictor.

The fitted through-origin coefficient was **0.1377 seconds**, giving a practical zero-added-ping rule of **about 0.15 blocks of view advantage per extra block/second toward the opponent**. This is an average, not a per-frame guarantee.

| No-hit approach (six trials each) | Mean measured speed difference, m/s | Mean view advantage, blocks | Mean reconstructed earlier reach, ms | Faster player first in separate attacking trials |
|---|---:|---:|---:|---:|
| Walk vs standstill | 4.26 | 0.60 | 143 | 6/6 |
| Sprint vs standstill | 5.49 | 0.75 | 140 | 6/6 |
| Sprint vs walk | 1.21 | 0.04 | 5 | 2/6 |

The sprint-versus-walk discrepancy is informative: the constant shared-delay model predicts roughly 0.17 blocks, but this six-trial opening cohort averaged only 0.04. Its modest speed edge was comparable to the unmodeled phase/history terms. Two first hits in six is a small sample, not evidence that walking is generally superior. Equal-speed controls also produced different winners (walking A first in 3/6, sprinting A in 5/6); there is no unique first-hit outcome from speed alone.

In a concrete equal-speed control, m0842 at +350 ms, A moved at 5.568 m/s and B at 5.531 m/s. A saw a 2.958-block center gap while B saw 3.510 blocks: **0.552 blocks of view advantage despite only 0.037 m/s speed difference**. Both were grounded. One client could reach and the other could not. This is retained as a counterexample to any precise instantaneous speed-only rule, not as a typical error bound.

| Evaluation set | Trials | Instantaneous-speed model mean absolute error, blocks | History model mean absolute error, blocks |
|---|---:|---:|---:|
| geometry/holdout | 10 | 0.167 | 0.136 |
| first_hit | 30 | 0.163 | 0.125 |
| sustained | 6 | 0.285 | 0.138 |
| ping_holdout | 12 | 0.148 | 0.100 |
| acceleration_holdout | 8 | 0.175 | 0.114 |

The secondary model's fitted history offset is 170 ms. It uses the displacement over that recent interval, with the opponent's direction accounted for. Historical positions are linearly interpolated between recorded ticks, so this fitted offset is an effective model parameter, not a direct network-delay measurement. On the six sustained fights it roughly halves the horizontal view-difference error. Those sustained measurements focus on real center separations of 2–5 blocks; height/aim still require a separate native ray test.

With network delay, the untuned extension `E ≈ (0.138 + (RTT_A+RTT_B)/2000) * (uA-uB)` retained about 0.148 blocks of mean absolute error on the 12 ping holdouts. The table below isolates sprint-versus-standstill (two side-balanced trials per added-ping pair):

| Added RTT, sprinter / stationary (ms) | Mean view advantage (blocks) |
|---|---:|
| 0 / 0 | 0.75 |
| 50 / 50 | 1.00 |
| 0 / 100 | 1.03 |
| 100 / 100 | 1.28 |

The similar 50/50 and 0/100 results support the sum-of-network-legs approximation for view age. These are position/opportunity tests; they do not establish equal first-hit arrival behavior under asymmetric ping.

| Twenty-second exchange | W-tap score, first side | W-tap score, reversed side |
|---|---:|---:|
| W-tap vs standstill | 20–0 | 20–0 |
| W-tap vs walking W-tap | 24–15 | 29–16 |
| W-tap vs hold-W | 37–31 | 38–25 |

Scores are accepted native hits, including damage upgrades; these short runs are mechanism demonstrations, not a policy ranking or an infinite-combo proof. W-tap here has zero artificial reaction delay, unlike the older 150-ms profile.

The most portable conclusion is the formula with **separate view ages**, not a universal numeric coefficient. Use 0.15 as a local steady-motion planning estimate. For a sharp W-tap, reversal or knockback, use recent displacement/history; for different heights use the actual ray/box geometry; for the first accepted hit include click phase and outbound processing. No data in this study establishes one coefficient for every server fork/client combination.

Replays: [walk toward standstill, no attacks](../recordings/m0810.html), [W-tap vs standstill, 20–0](../recordings/m0885.html), [W-tap vs walking W-tap, 24–15](../recordings/m0888.html). These are native-data visualizations, not rendered Minecraft video.

## Retrospective first-hitter audit

The original report measured reach-difference error, **not first-hitter accuracy**.
After the user asked for that distinction, a separate audit applied the frozen
models to all 36 attacking openings (30 dedicated first-hit tests plus six longer
fights). The other 52 trials did not attack. All 36 attacking openings used zero
added ping. Neither model was refitted; its original 12 fitting matches were
non-attacking and are disjoint from these 36 matches.

The newly chosen scoring rule is to take the sign of predicted reach advantage
at the first available local-separation sample at or below five blocks. Stored
samples begin at +150 ms. This uses both players' recent **local movement** from
the logs, not their rendered opponent positions or future attacks. Every call
was 289–545 ms before the first accepted hit. An edge within 1e-9 blocks is no
prediction, rather than a forced tie-break. The server's accepted-hit event order
defines the actual first hitter, including contacts recorded in the same
millisecond. This is an offline retrospective audit of a reach-edge proxy, not a
new prospective test, a prediction from nominal speeds alone, or a model of
exact click/attack timing.

| Opening matchup (dedicated and longer trials combined) | Trials | Refined model correct | Wrong | No predicted edge |
|---|---:|---:|---:|---:|
| Walking vs stationary | 6 | 6 | 0 | 0 |
| Sprinting W-tap vs stationary | 8 | 8 | 0 | 0 |
| Sprinting W-tap vs walking W-tap | 8 | 7 | 0 | 1 |
| Walking vs walking | 6 | 3 | 3 | 0 |
| Sprinting vs sprinting | 6 | 2 | 0 | 4 |
| W-tap vs hold-W | 2 | 0 | 0 | 2 |
| **Total** | **36** | **26** | **3** | **7** |

At this same checkpoint the original instantaneous-speed model got 21 correct,
eight wrong and seven no-edge calls. The refined history model got 26/36 correct
(72.2% of all trials), or 26/29 (89.7%) among the trials where it named a side;
coverage was 29/36 (80.6%). Reporting only the latter accuracy would hide its
seven unresolved openings.

The dedicated first-hit subset was 22 correct, three wrong and five no-edge out
of 30. The six longer-fight openings contributed four correct and two no-edge.
Stationary opponents were 14/14 correct. With both players moving, the result
was 12/22 correct, three wrong and seven no-edge, so the overall accuracy should
not be interpreted as robust prediction in the harder moving-opponent cases.
These are first-hitter identities, not millisecond timing-error measurements.
There is no first-hit accuracy result for the non-attacking added-ping trials.

Run `python3 reach_first_hit_audit.py` to reproduce the audit. The
[per-match predictions, outcomes and counts](speed-reach-first-hit-audit.json)
retain the frozen fit, input hashes, checkpoint distances and prediction lead
times. No fresh Minecraft matches or changes to the running server were needed.
