# Sumo and boxing results

One win earns one point. A timeout or double ring-out earns half a point. The local primary preset is the lab's provisional MMC pre-attack sprint-reset configuration. Native bouts use real Minecraft 1.8.9 clients on the main server. The opponent benchmark consists of bots. **No held-out elite player has played a registered match.** Nomophobia's recordings are development examples.

## Regulation arena

New sumo trials use the most frequent platform in Nomophobia's MMC recordings. The fighting surface spans 13 by 13 blocks, with 137 solid blocks and eight blocks between the two gold spawn centers. The earlier results below used the arena registered for each experiment. Scores on the former 10-block ring have their original geometry. [Recorded block layout and survey](../mmc-arena/README.md)

### First tournament on the recorded platform

The 96 native duels used 20 CPS, physical mouse inputs, three-block reach and regulation ring-outs. Each matchup included four independent bouts from each seat at each of 0 and 80 ms added RTT. The model played the same seats and latencies on eight development calendars.

| Opponent | Native W-tap /16 | Native continuation /16 | Native population /16 | Model W-tap /32 | Model continuation /32 | Model population /32 |
|---|---:|---:|---:|---:|---:|---:|
| 20-CPS W-tap | 6 | 16 | 16 | 16 | 30 | 32 |
| Adaptive pair from round one | 0 | 9 | 8 | 1 | 14.5 | 12.5 |

`continuation-03-fit` won all four native W-tap bouts in every seat and latency cell. It won two or three of four in each cell against the adaptive pair. All 96 native matches ended in ring-outs. The control audit counted 40,982 physical presses, with a minimum interval of 50.006 ms and zero binding disagreements. The permanent winner can be played with `/duel mmc13-opening sumo preset=mmc`. [Individual matches and seat spread](evidence/mmc13/opening/outcomes.json) · [Native input audit](evidence/mmc13/opening/native.json.gz)

Across twelve preselected matches, all **9,009 individual travel steps** reproduced the native position, velocity, collision box and collision flags. Running continuously from the first position with observed keys, contacts and knockback initially disagreed on 1,070 steps. The missing input was the server's ability to change the client's sprint flag between clicks. Supplying the 641 observed sprint-flag changes reproduced all 9,009 steps continuously, including 56 off-platform steps. Predicting both players from just their physical controls and host event times matched the opening two seconds in **10 of 12** matches. Predicting packet order across a full new fight remains a separate limitation. [Physical-input forecasts](evidence/mmc13/opening/fidelity.json.gz) · [Continuous travel checks](evidence/mmc13/opening/travel-with-sprint-metadata.json)

The fixed transport estimate reproduced both players' first two seconds in only **3 of 12** additional openings from the same tournament. We compared four replays of each bout with the same real inputs. Supplying the observed delivery times for the player's own velocity packets raised that to 7/12. Supplying only the times for sprint metadata gave 3/12. Using both gave 9/12, and also recovered all 12 of the earlier openings. Each panel contained 959 travel steps. On the second panel, the two-timing diagnostic matched 3/4 W-tap, 3/4 population and 3/4 continuation games. Ordinary fixed timing matched 1/4, 0/4 and 2/4 respectively.

In m4532 the native player's sprint was still on when the click reduced horizontal speed from 0.854375 to 0.512625. The fixed estimate cleared sprint before that click and missed the 0.341750 reduction. In m4625 even the measured delivery times left an error. The simulator computed sideways velocity of 0.020875 against the recorded 0.021625. These tests supply timing information available only after the fight. Rank controllers using native ring-outs. [Frozen match selection and input definitions](evidence/mmc13/opening/sprint-drain-protocol.json) · [All 24 forecasts, failed steps and packet coverage](evidence/mmc13/opening/sprint-drain-fidelity.json.gz)

We also tested a timing estimate that can run before the fight. On those 24 openings, the best of 64 delay pairs used 1.5 ms beyond nominal transport for velocity and 0.75 ms for sprint metadata. It matched 15/24 versus 13/24 at the standard 0.35 ms. On 12 separate openings from the same tournament it matched 6/12 versus 5/12. The mean of the largest position error in each bout fell from 0.570 to 0.521 blocks. A shared 1.5 ms estimate did equally well on those 12. The only additional exact match came from the continuation at zero added RTT in seat A. Plain W-tap stayed at 2/4 in both models. Forty future confirmation match IDs have been reserved to test whether this transfers. [Fit and validation results](evidence/mmc13/transport/summary.json) · [Prospective fixtures](evidence/mmc13/transport/prospective-protocol.json)

### Which inputs helped after a trade?

The second PPO actor struggled most against its earlier training opponent. On 16 training calendars, we resumed both bots from the same complete simulator state after their first exchange of hits. For the next three input decisions we changed combinations of forward/sprint keys, strafe, clicks and jump. The actor still chose every unchanged input from its own view. Each branch then played to a ring-out or timeout. The original continuations, including their early hit traces, reproduced on every fixture.

| First exchange opponent | Forks reached / starts | Plain W-tap from fork | Unchanged PPO | Force left strafe only | Sprint forward and left, allow clicks, no jump |
|---|---:|---:|---:|---:|---:|
| Earlier PPO actor | 63/64 | 3.5 | 27.5 | 37 | 38 |
| Adaptive pair from round four | 64/64 | 4 | 47 | 46 | 46 |
| Plain W-tap | 64/64 | 35.5 | 60 | 59 | 59 |

Against the earlier actor, left strafe alone improved 21 outcomes and worsened 11. At zero added RTT it cost three points from seat A and gained 4.5 from seat B. At 80 ms it gained 5.5 and 2.5 points respectively. Seven calendars improved, six tied and three worsened. At the second exchange the full four-input change gained five points against this opponent and lost 6.5 against the adaptive pair. A fixed direction at every trade would give up points in some matchups.

Against the adaptive pair at the first exchange, the actor scored 47 points, W-tap scored four, and sprinting straight while allowing clicks only during recoil scored 54. Changing only click timing added half a point. Removing strafe alone added three. The full change improved both the inputs and how they worked together. These are training-calendar mechanism tests. Independent native tournaments assess the complete controllers. Native attribution for the individual input changes remains unmeasured. [First-exchange wins, draws, losses and timing spread](evidence/mmc13/trade-inputs/comparison-1.json) · [Second exchange](evidence/mmc13/trade-inputs/comparison-2.json) · [Experimental design](evidence/mmc13/trade-inputs/design.json)

We then changed only the timing *after* each first exchange. Against the earlier PPO actor, delaying its next and subsequent client opportunities by 5 ms reversed the earlier result. The unchanged actor scored 39.5/63, the three-input left response scored 30.5/63, and plain W-tap scored 4.5/63 from the same states. Across the original timing and four 5 or 12 ms variations, 37 of the 63 states had both a strictly beneficial and a strictly harmful outcome for that same left response. Each pair of outcomes started with identical observations. The timing variations are sensitivity tests on the same sixteen clocks. [Per-phase outcomes by seat, latency and calendar](evidence/mmc13/phase-trades/comparison-1.json) · [Training and confirmation protocol](mmc13-phase-trade-protocol.json)

## Six further rounds against an adversarial mixture

Each round measured the historical field, solved a game over the observed head-to-head scores, and trained explicit and neural responses against a distribution weighted toward the hardest opponents. Six rounds added 1,597,440 training bouts and 854,816 matrix and tournament bouts. On the eight previously exposed development clocks, the final field contained 55 opponents.

| Development metric | Plain W-tap | Best new lookup table | Best new neural policy | Previous equal-budget control |
|---|---:|---:|---:|---:|
| Average points per bout | .226 | .759 | .743 | .731 |
| Average against weakest five | .006 | .503 | .431 | .438 |

The lookup table came from round six and the neural fighter from round four. The weakest-five average still permits considerable losses to individual opponents. These are simulator development scores with the original ideal-angle tracker. [Individual matchup results](empirical-game-frozen/selection.json) · [Round-by-round games](evidence/empirical-development)

## The population champion against ten historical opponents

The 496-bout native tournament tested symmetric and asymmetric latency, the two combat profiles, PPO and population training. On the common MMC regulation subset, the frozen population champion scored **78/80** against ten distinct opponents. It scored **39/40 at each RTT**. Plain W-tap scored **45/80**, split 20/40 at 0 ms and 25/40 at 80 ms. Both sides had the same original ideal-angle tracker, a 20 CPS ceiling, normal reach and the same independent seat/repeat schedule. The model used eight additional real mirror calendars.

| MMC regulation opponent | Model W-tap /32 | Model population /32 | Native W-tap /8 | Native population /8 |
|---|---:|---:|---:|---:|
| W-tap | 16 | 32 | 6 | 8 |
| Hold-W | 7 | 30.5 | 1 | 8 |
| First hybrid | 1 | 28 | 1 | 8 |
| Hold-W counter | 2 | 31 | 0 | 7 |
| Hybrid counter | 4 | 29 | 1 | 7 |
| Zest strafe | 32 | 32 | 8 | 8 |
| Timed W-tap | 22 | 31 | 8 | 8 |
| Phase spacing | 31 | 32 | 7 | 8 |
| Pull displacement | 32 | 32 | 8 | 8 |
| Edge displacement | 23 | 25.5 | 5 | 8 |

The two native losses were to separately trained adversaries. Eight native games per opponent leave considerable uncertainty about each individual win rate. The frozen bot is `/duel league-robust sumo preset=mmc`. [All native games and binding checks](evidence/robust/native.json.gz) · [Model outcomes by donor and seat](evidence/robust/regulation-paired.json.gz)

## Repeated adaptation within a fight

Four more league rounds trained a recurrent movement policy and a feedback selector between two primed experts. On the common exposed field of 87 individual opponents, the best feedback selector used the population leader and the round-six spacing table. Its average points were .742, its weakest-five average was .509 and its weakest individual matchup was .453 against round four's empirical-game neural bot. Plain W-tap scored .131, .000 and .000 on the same schedule. The population component alone scored .730, .388 and .281. These bouts used the integer virtual mouse. [Per-opponent simulator scores](adaptive-frozen/selection.json)

In 256 trials against eight training styles, 255 opponents reached their first change of tactics. Just before the change, the full simulation state and all primed policy histories were copied. Unchanged copies produced the original terminal state. W-tap from the landmark earned 29.5/255, the learned selector 183/255, always choosing its population component 192/255, and always choosing the table 169/255. Against one switching style the selector gained three points over its population component. Against another it lost five. This prompted an isolated test of the minimum time committed to one expert. [All causal branches](evidence/adaptive-development/switch-forks.jsonl.gz) · [Timing experiment](cadence-protocol.json)

## Learning from alternative continuations

For each surviving training position, continue the same fight with the population or spacing expert controlling the next 3, 8 or 20 input decisions. The other fighter keeps reacting to what actually happens. After that short intervention, return control to the current gate. Play two further branches with W-tap and the unchanged gate. The three rounds produced 72,366 positions with an ensuing decision. Fit the next gate to the difference in ring-out points between the two expert branches using only the local observation available for that decision. At inference it reads 22 current features and 14 terms summarizing recent movements and hurt cues. It receives neither the opponent ID nor any branch outcome. The three playable titles are `continuation-01`, `continuation-02` and `continuation-03`. The first bot defended its title in round two. [Training protocol](continuation-protocol.json) · [Matched state labels](evidence/continuation-development)

| Simulator development field | W-tap mean / worst | Population mean / worst | Earlier adaptive mean / worst | Continuation winner mean / worst |
|---|---:|---:|---:|---:|
| 90 contemporary and switching fighters | .127 / .000 | .715 / .250 | .733 / .453 | .766 / .484 |
| 85 older configurations | .669 / .031 | .938 / .672 | .941 / .750 | .944 / .688 |
| 65 additional historical configurations | .798 / .359 | .974 / .672 | .979 / .906 | .967 / .844 |

Each opponent contributed 32 model bouts on the same eight exposed timing calendars. Some older configurations are fixed-control drills. The older fighter fields include hit selection, periodic sprint releases, burst clicking and Zest timing variants. The continuation winner's weakest contemporary opponent was `adapt-01-pair`. Its weakest on the 85-opponent field was `trade-wtap-late`. Scores against someone who can change tactics within a fight also include bouts ending before the change. [Individual contemporary and legacy scores](continuation-frozen/selection.json) · [Additional historical scores](evidence/history-cup/legacy-model-expanded-development/results.json)

## The first native transfer

The first registered tournament played 336 native rounds, including boxing and the separate Hypixel-like preset. The MMC cells below have symmetric 0 or 80 ms RTT, both seats, an ordinary 3-block reach and a 20 CPS ceiling. The simulator used eight previously unseen donor calendars. The pairs of columns show points earned against the same opponent.

| MMC sumo opponent | Model W-tap | Model hybrid | Native W-tap | Native hybrid |
|---|---:|---:|---:|---:|
| Hold-W | 7/32 | 26.5/32 | 1/8 | 8/8 |
| W-tap | 16/32 | 26/32 | 4/8 | 6.5/8 |
| Prior learned pressure bot | 12/32 | 31/32 | 3/12 | 10/12 |

The hybrid won all four native Hold-W matches at each RTT. The eight games are a small sample of the original 10-block lab ring. With the same native starts and settings across arms, independent bouts still have different OS and packet timing. The full archive records every win, draw, seat and repeat. [Native outcomes and spread](evidence/v1/native-paired.json.gz) · [Model outcomes by donor and seat](evidence/v1/model-paired.json.gz)

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

Two of the 15 original model K3 starts already included a return in the same server drain. The forks occurred at that tick's end, 1.702 to 10.284 ms after the landmark across the 15 starts. In native fight **m2800**, the defender's attack left its client 7.774 ms before the server began processing the attack that established K3. The server had already queued the return when it processed K3. It accepted the return in the same tick, 1 ms later by the replay clock, before the attacker's next input head. In **m2793** the defender sent its return 8.698 ms before K3, but it arrived afterward and scored 44 ms after K3. Those cases distinguish a spacing decision made before establishment from what a controller can change after it. The packet analysis keeps unpaired records unknown. [Per-return packet and input timing](evidence/v1/boxing-returns.json.gz)

On a different set of eight **development** donors, the clairvoyant two-head action search scored 119 further active-qualified cycles from 18 K3 states; W-tap scored 14. Nine starts improved. Two searched continuations reached the 20-second match horizon. The search knows future timing. A legal MLP trained from its ordinary-key demonstrations and then optimized on paired suffixes scored 33 cycles on these 18 development states, against 27 for the previous specialist and 14 for W-tap. It improved four starts and lost one relative to W-tap. The original specialist improved six and lost none, giving it the better registered per-start score. [Search paths](evidence/boxing-oracle/development.jsonl) · [Student selection](boxing-distilled-frozen/selection.json)

### Specialist transfer

The separately trained boxing specialist used an affine controller. In 64 independent native drills, both arms faced a defender that started clicking after two seconds. Each arm played 32 bouts. Once a bout reached its first K3, every qualifying episode ended in an accepted return. The activity-qualified and return-only scores agreed.

| Landmark | Model starts | Model W-tap cycles | Model specialist cycles | Native W-tap starts / cycles | Native specialist starts / cycles |
|---|---:|---:|---:|---:|---:|
| 3 | 14 | 22 | 25 | 13 / 6 | 11 / 4 |
| 5 | 6 | 9 | 14 | 0 / — | 2 / 1 |
| 10 | 0 | — | — | 0 / — | 0 / — |

At K3 the native W-tap episodes lasted 5.915 seconds in total, with a median of 154 ms. The specialist episodes lasted 5.259 seconds, median 56 ms. W-tap completed one further cycle from two entries at 0 ms and five from eleven at 80 ms. The specialist completed one from three and three from eight. Across the entire bouts, 507/737 scored W-tap fresh windows and 504/715 specialist windows were definitely more than 100 ms late. The native result gives no grounds to promote this specialist over W-tap for maintenance. [Native episodes and seed geometry](evidence/boxing/native.json.gz) · [Model forks](evidence/boxing/matched-forks.jsonl.gz) · [Return packet timing](evidence/boxing/boxing-returns.json.gz)

The model's three-cycle aggregate gain came from one start gaining seven, two losing three and one respectively, and eleven ties. On **m3072, seat B, 0 ms**, the local opponent was 2.721 blocks away horizontally at the first decision. W-tap released forward on its next two heads. The specialist moved forward and immediately strafed. By 128 ms the local gaps were 2.856 and 2.662 blocks. Both next fresh hits occurred at 501 ms and both delivered 0.854 blocks/tick horizontally. W-tap preserved enough space to get six additional hits before the return at 3.739 seconds. The specialist got three before the return at 1.918 seconds. On **m3076, seat A, 80 ms**, the initial local gap was 3.919. This time the specialist's continued advance brought the next hit forward by 50 ms and it completed ten cycles to W-tap's three. All accepted hits in both branches were checked against the original score sequence. The ledger also checks every local contact's horizontal velocity before and after drag. These examples explain why one total can hide a reversal with starting spacing. [Matched input, movement, contact and impulse ledger](evidence/boxing/suffix-ledger.jsonl.gz)

## Simulator fidelity and speed

In 12 native fights sampled by policy, seat and RTT, the first audit matched **1,886 of 1,886 isolated travel updates**. One continuously integrated update missed x by 0.000552 blocks. A fighter slid against the vertical face of the ring while falling. In the native living update, the preceding horizontal collision clears sprint even with Ctrl pressed. The reduced model kept sprint active for one extra tick. Correcting that transition matched all 1,886 continuous updates, given real local velocity and contact receipts. Replaying the same 59,168 common-field bouts changed eight termination timestamps or outcomes and four win/draw/loss results. The original ranking and frozen weights are preserved. [Physics correction](edge-sprint-correction.json) · [Before](evidence/v1/stratified-fidelity.json.gz) · [After](evidence/v1/stratified-after-edge-fix.json.gz) · [Tournament differences](evidence/ladder-development/edge-correction.json)

In that 12-game sample, using measured wire timings completed all 13,928 requested event rows across the opening four seconds, with zero reported component error. Providing only physical inputs and host stage times with fixed 0.35 ms transport overhead reproduced both players' movement exactly for the opening two seconds in **6 of 12 games**. A separate 15-opening test reproduced 7 of 15 with the original overhead and 6 of 15 with the alternative calibrated on training donors. Closed-loop fights diverge when packets or clicks land on opposite sides of a drain. Native transfer testing is necessary. [Prospective transport test](evidence/v1/timing-validation.json)

On this 12-core host with the main lab running, the benchmark played 4,096 regulation bouts per arm at each worker count. Different policies produce different fight lengths. Input heads per second include both fighters. Three workers measured **2.94 million heads/s for W-tap**, 2.28 million for the MLP, 2.43 million for the recurrent policy, and 1.70 million for the search policy. Corresponding complete bout rates were 10,473, 2,282, 5,871 and 4,298 per second. These are short measured runs under the league CPU governor. [All rates and simulated seconds](evidence/throughput.json)

## The human evidence

Nomophobia supplies examples from one developing player. The frozen split contains 52 fit games, 11 selection games and 19 later games. One later game had no usable action frames. Five of the 82 eligible completed games were against a 20-CPS opponent. A sampled action model reproduced some distance-dependent click behavior and improved bout-equal later-game loss to 0.663, against 0.764 for a previous-input predictor. Grounded jump positives were sparse. A world-class human result requires separate registered players and real matches. [Development split](human-development.json) · [Held-out human grader](../sumo-rl/grade_humans.py)

In development fight **m2150**, the 10-CPS Hold-W opponent landed the first of four hits at 3.085 seconds, with horizontal knockback magnitude 0.52736. Nomophobia clicked at 3.159 and hit back at 3.185 with a sprint bonus, producing 0.85430. At 3.409 and 3.459 their forward input was zero, then positive again at 3.511. The next two accepted hits belonged to Nomophobia at 3.733 and 4.233, both with a sprint bonus. At those instants center-to-center horizontal separation was 3.246 and 3.313 blocks. A client attack targets an entity's hitbox. This trace motivated testing a short movement response after a reciprocal exchange. The counterfactual branches above measure its effect at an identical start. [Timestamped human and bot examples](evidence/human-informed/development-trades.json.gz)


## Mouse control and physics validation

The initial transfer experiments and all six empirical-game training rounds used a shared tracker that assigned the desired local yaw and pitch directly. The joint native selection and later title panels use one integer mouse movement per render frame, actual vanilla sensitivity and the native look function. A native input tick can refresh the ray without turning the camera again. Both players use the same tracking settings in each comparison. [Mouse control amendment](ladder-transfer-protocol.json)

On the earlier Hypixel-like transfer panel, the model reproduced all three velocity components for 4,170 ordinary fresh native hits when supplied the measured attack state. Eleven native impacts with almost coincident positions matched the fallback's random-direction magnitude and vertical law. The exact hidden native random angles were unavailable. On 448 simulated bouts, the four registered randomness seeds gave the same outcomes on the 418 unaffected bouts. Sixteen of the 30 bouts that reached a coincidence changed outcome across seeds. [Native impulses](evidence/v1/native-knockback-check.json) · [Seed-by-seed outcomes](evidence/robust/hypixel-random-directions.json.gz)


On a second independent Hypixel-like cohort the impulse check reproduced all three wire components on 3,737 ordinary fresh hits and the fallback magnitude and vertical component on nine coincident hits. The population champion earned 15/16 native points across W-tap and Hold-W, against 12/16 for plain W-tap. The matched model scores were 46/64 and 37/64. On the asymmetric MMC panel, combining 40/100 ms and 100/40 ms, population scored 15/16 against W-tap and the anti-hybrid fighter, against 5/16 for W-tap. The model scored 59/64 and 21/64. [Independent impulse check](evidence/robust/native-knockback-post-freeze.json) · [Native bout records](evidence/robust/native.json.gz)

In the 16-bout integer-mouse smoke test, 32 player recordings yielded 1,700 verified count-to-angle updates. Across eight selected bouts all 1,908 isolated movement updates and all 1,908 continuously integrated updates matched with the measured local contact and velocity inputs. An initial analysis had misclassified the first movement in m3805 as sprinting. Its input head began one millisecond before the server's declared start and its travel occurred afterward. Reading the originating input head recovered the actual walk key. Supplying measured packet timings completed all 9,181 requested replay events. With only physical inputs and host-stage times and 0.35 ms estimated transport, just **one of eight** openings matched every movement for the first two seconds, both with recorded integer mouse counts and with the tracker generating them. The first divergence was often the tick on which a knockback arrived. [Mouse and click audit](evidence/mouse-smoke/native.json.gz) · [Movement and packet timing](evidence/mouse-smoke/conditional-with-opening-head.json.gz) · [Open-loop physical controls](evidence/mouse-smoke/openings-with-opening-head.json)

The earlier 20-stratum native timing test had 17 scorable openings. A training-fitted stage-dependent transport estimate matched six of 17, the same number as the fixed 0.35 ms model. Its mean maximum position error across those fights was 1.202 blocks versus 1.067 for the fixed estimate. Three recordings lacked the inputs needed to score them. [Per-opening forecasts](evidence/robust/transport-shape.json)
