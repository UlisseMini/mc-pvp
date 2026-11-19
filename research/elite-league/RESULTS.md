# Sumo and boxing results

One win earns one point. A timeout or double ring-out earns half a point. The local primary preset is the lab's provisional MMC pre-attack sprint-reset configuration. Native bouts use real Minecraft 1.8.9 clients on the main server. The opponent benchmark consists of bots. **No held-out elite player has played a registered match.** Nomophobia's recordings are development examples.

## The first native transfer

The first registered tournament played 336 native rounds, including boxing and the separate Hypixel-like preset. The MMC cells below have symmetric 0 or 80 ms RTT, both seats, an ordinary 3-block reach and a 20 CPS ceiling. The simulator used eight previously unseen donor calendars. The pairs of columns show points earned against the same opponent.

| MMC sumo opponent | Model W-tap | Model hybrid | Native W-tap | Native hybrid |
|---|---:|---:|---:|---:|
| Hold-W | 7/32 | 26.5/32 | 1/8 | 8/8 |
| W-tap | 16/32 | 26/32 | 4/8 | 6.5/8 |
| Prior learned pressure bot | 12/32 | 31/32 | 3/12 | 10/12 |

The hybrid won all four native Hold-W matches at each RTT. The eight games are a small sample of the default 10-block ring. With the same native starts and settings across arms, independent bouts still have different OS and packet timing. The full archive records every win, draw, seat and repeat. [Native outcomes and spread](evidence/v1/native-paired.json.gz) · [Model outcomes by donor and seat](evidence/v1/model-paired.json.gz)

## Why keeping W down can work

On this preset a fresh straight hit sent about **0.5274 blocks/tick** horizontally before the sprint bonus. Pre-attack server sprint adds **0.327**, before packet quantization. W-tap gets the larger impulse on many individual hits. Releasing W also changes how far the attacker advances. Local attack contact can damp the attacker's current horizontal motion; whether that helps depends on whether that motion is toward or away from the opponent. A sumo win depends on both players' displacement up to the ring edge.

A second, independently repeated 160-round intervention isolated the sprint input. The table includes both W-tap and Hold-W opposition. All native entries have eight bouts per opponent; each simulated entry has 32. The last column counts actual native **fresh own hits** receiving the sprint bonus across these two opponents. Long bouts contribute many hits to this descriptive fraction.

| Attacking change | Model vs W-tap (W-tap baseline 16/32) | Model vs Hold-W (W-tap baseline 9/32) | Native vs W-tap (W-tap baseline 2.5/8) | Native vs Hold-W (W-tap baseline 2/8) | Native sprint bonus |
|---|---:|---:|---:|---:|---:|
| Plain W-tap | 16 | 9 | 2.5 | 2 | 196/238 |
| Hold W and Ctrl | 23 | 16 | 7 | 5.5 | 16/925 |
| Hybrid | 32 | 30 | 7 | 8 | 150/205 |
| Hybrid with Ctrl pinned while W is down | 27 | 19 | 8 | 3.5 | 34/883 |
| W held, request Ctrl only while locally unsprinted | 26 | 17 | 8 | 5 | 159/205 |

Holding W and Ctrl throughout earned very few sprint bonuses. The hybrid usually advanced, but changed its Ctrl request. Pinning Ctrl cut both its bonus frequency and its Hold-W result. A simple local sprint feedback rule recovered many bonuses while continuing to advance, and still came up short of the hybrid against Hold-W. In a separate ablation, keeping the click gate open produced the same simulated points as the original hybrid in both matchups. The 28,806 active learned-policy heads in this native intervention panel contained zero closed click gates. These bouts give strong evidence for sprint timing and little evidence that click withholding explains this particular fighter. [Native ablations](evidence/mechanism/native.json.gz) · [Registered interventions](mechanism-protocol.json)

## Two learned counters

Two separate fighters trained specifically against Hold-W and against the first hybrid. They then played a 128-bout native tournament and a simulator field on eight fresh mirror calendars. The Hold-W counter is a feedforward MLP. It took 8/8 points against native Hold-W, including 4/4 at each latency. Against W-tap it took 4/4 at 0 ms and 3/4 at 80 ms.

| Opponent | Model W-tap | Model Hold-W counter | Native W-tap | Native Hold-W counter |
|---|---:|---:|---:|---:|
| Hold-W | 7/32 | 31/32 | 2.5/8 | 8/8 |
| W-tap | 16/32 | 28/32 | 3/8 | 7/8 |

When played against that counter, native W-tap, Hold-W and the original pressure bot each lost all eight. The first hybrid scored 5/8, with 1/4 at 0 ms and 4/4 at 80 ms. Against the independently trained anti-hybrid, the first hybrid also scored 5/8, and the latency split reversed. All 29,260 recorded network decisions in the challenge replayed, with zero discrepancies in 29,208 matched movement updates. [Native results and spread](evidence/challenge/native-paired.json.gz) · [Model comparison](evidence/challenge/prospective-paired.json.gz)

The Hold-W counter withheld clicks on 522 of 13,963 active input heads. On 397 of those it was still more than 3.31 blocks from the opponent and had seen neither hurt cue. On 78 it had seen a hurt cue and was within that distance. A withheld click on approach and a withheld click during a trade have different physical opportunities to brake the attacker. We registered an intervention that keeps the same trained MLP and pins only its click output open. [Observed gate timing](evidence/click-gate/development.json) · [Intervention protocol](click-gate-protocol.json)

## What twelve seasons changed

The ladder ran **1,486,464 training bouts**, **597,696 seasonal tournament bouts**, and **59,168** final common-field bouts. The four tracks trained in each season were explicit rules, explicit lookahead, nearest-situation tables and a direct-control neural policy. Historical opponents stayed available. Weak matchups received a higher sampling probability the next season.

On the common field of 43 opponents, each opponent family received equal weight. The equal-budget neural control trained without the human imitation scored 79.0%. The wrapped second-trade reference also scored 79.0%, the best milestone neural policy 77.8%, the population reference 77.2%, and the best milestone explicit table 72.9%. W-tap scored 26.2% and Hold-W 32.8%. These are development scores. The simulator selected season one's neural contender and season four's table ahead of their later descendants. The joint native tournament includes each milestone plus the highest-ranked pure rule and lookahead program. [Common ranking](evidence/ladder-development/common-results.json.gz) · [Frozen entrants](ladder-frozen/frozen.json)

In the first-trade study, **every candidate action began at the same complete state**. A three-head intervention selected from local observations brought the season-three table from 177.5 to 184 points across 288 bouts; full W-tap scored 35.5. The population reference went from 225.5 to 226. In the harder second-trade field, the revised reference went from 282.5 to 293 points over 384 bouts, while the revised table went from 251 to 250 and W-tap scored 43.5. Intervening after the same visible event had different value for different base policies. [First- and second-trade experiments](ladder-refinements)

## Boxing after an established combo

All arms use W-tap for acquisition in the maintenance test. The defender starts clicking after two seconds. A landmark is three, five or ten consecutive unanswered fresh hits after the defender is active. The numbers below count further completed fresh hit-to-hit cycles before an accepted return or a 1,000 ms gap. The model forks both arms from an identical full state. The native entries are independent repeats with the same acquisition protocol.

| Landmark | Model common starts | Model W-tap cycles | Model sumo hybrid cycles | Native W-tap starts / cycles | Native sumo hybrid starts / cycles |
|---|---:|---:|---:|---:|---:|
| 3 | 15 | 22 | 16 | 7 / 1 | 7 / 7 |
| 5 | 8 | 9 | 7 | 1 / 0 | 2 / 2 |
| 10 | 0 | — | — | 0 / — | 0 / — |

Both arms entered K3 in seven of sixteen native bouts. All seven first-landmark episodes in each arm ended in a return; summed exposure was 2.789 seconds for W-tap and 4.741 seconds for the hybrid. The small native sample and the opposite model ordering do not establish a repeatable hybrid improvement. One developmental common-state trace shows what W-tap sometimes gets right. At 11,181.312 ms it landed a sprint-bonus hit with 2.130 blocks of horizontal separation. The sumo hybrid had already closed to 1.961 blocks, dealt the base impulse and took a return already committed in the same server drain. [Matched-state diagnosis](boxing-protocol.json) · [Native landmark distributions](evidence/v1/native.json.gz)

On a different set of eight **development** donors, the clairvoyant two-head action search scored 119 further active-qualified cycles from 18 K3 states; W-tap scored 14. Nine starts improved. Two searched continuations reached the 20-second match horizon. The search knows future timing. A legal MLP trained from its ordinary-key demonstrations and then optimized on paired suffixes scored 33 cycles on these 18 development states, against 27 for the previous specialist and 14 for W-tap. It improved four starts and lost one relative to W-tap. The original specialist improved six and lost none, giving it the better registered per-start score. [Search paths](evidence/boxing-oracle/development.jsonl) · [Student selection](boxing-distilled-frozen/selection.json)

## Simulator fidelity and speed

In 12 native fights sampled by policy, seat and RTT, the first audit matched **1,886 of 1,886 isolated travel updates**. One continuously integrated update missed x by 0.000552 blocks. A fighter slid against the vertical face of the ring while falling. In the native living update, the preceding horizontal collision clears sprint even with Ctrl pressed. The reduced model kept sprint active for one extra tick. Correcting that transition matched all 1,886 continuous updates, given real local velocity and contact receipts. Replaying the same 59,168 common-field bouts changed eight termination timestamps or outcomes and four win/draw/loss results. The original ranking and frozen weights are preserved. [Physics correction](edge-sprint-correction.json) · [Before](evidence/v1/stratified-fidelity.json.gz) · [After](evidence/v1/stratified-after-edge-fix.json.gz) · [Tournament differences](evidence/ladder-development/edge-correction.json)

In that 12-game sample, using measured wire timings completed all 13,928 requested event rows across the opening four seconds, with zero reported component error. Providing only physical inputs and host stage times with fixed 0.35 ms transport overhead reproduced both players' movement exactly for the opening two seconds in **6 of 12 games**. A separate 15-opening test reproduced 7 of 15 with the original overhead and 6 of 15 with the alternative calibrated on training donors. Closed-loop fights diverge when packets or clicks land on opposite sides of a drain. Native transfer testing is necessary. [Prospective transport test](evidence/v1/timing-validation.json)

On this 12-core host with the main lab running, the benchmark played 4,096 regulation bouts per arm at each worker count. Different policies produce different fight lengths. Input heads per second include both fighters. Three workers measured **2.94 million heads/s for W-tap**, 2.28 million for the MLP, 2.43 million for the recurrent policy, and 1.70 million for the search policy. Corresponding complete bout rates were 10,473, 2,282, 5,871 and 4,298 per second. These are short measured runs under the league CPU governor. [All rates and simulated seconds](evidence/throughput.json)

## The human evidence

Nomophobia supplies examples from one developing player. The frozen split contains 52 fit games, 11 selection games and 19 later games. One later game had no usable action frames. Five of the 82 eligible completed games were against a 20-CPS opponent. A sampled action model reproduced some distance-dependent click behavior and improved bout-equal later-game loss to 0.663, against 0.764 for a previous-input predictor. Grounded jump positives were sparse. A world-class human result requires separate registered players and real matches. [Development split](human-development.json) · [Held-out human grader](../sumo-rl/grade_humans.py)

In development fight **m2150**, the 10-CPS Hold-W opponent landed the first of four hits at 3.085 seconds, with horizontal knockback magnitude 0.52736. Nomophobia clicked at 3.159 and hit back at 3.185 with a sprint bonus, producing 0.85430. At 3.409 and 3.459 their forward input was zero, then positive again at 3.511. The next two accepted hits belonged to Nomophobia at 3.733 and 4.233, both with a sprint bonus. At those instants center-to-center horizontal separation was 3.246 and 3.313 blocks. A client attack targets an entity's hitbox. This trace motivated testing a short movement response after a reciprocal exchange. The counterfactual branches above measure its effect at an identical start. [Timestamped human and bot examples](evidence/human-informed/development-trades.json.gz)
