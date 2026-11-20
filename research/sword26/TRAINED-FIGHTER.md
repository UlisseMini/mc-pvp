# The league-trained sword fighter

Choose option **4** in **Start Modern Sword Lab.command** to fight this policy in Minecraft Java 26.3. The bot ran 43–17 across five opponents on the native tick-scheduled server. Plain W-tap ran 24–36 from the same seats against the same opponents. The strongest result was 10/12 against W-tap itself. Against the previous fast-trained checkpoint it split 6/12, below the registered requirement for replacing the default. The original and the retreating rival are still available in options 1 and 2. [Playing weights](evidence/league26/fighter.net) · [Protocol](evidence/league26/protocol.json)

[Watch four native bouts](<evidence/league26/Watch the sword bot.html>) to compare the league fighter and plain W-tap against the retreating rival. The viewer uses the first opening and seat A at each latency. Both lose at zero added latency and win at 100 ms on these particular starts. You can switch between the server and either client’s view.

## Training

The policy issues ordinary movement, sprint, jump, steering and swing inputs from its own client observations. Its parents were archived policies descended from random initializations. Five populations played 2,365,440 scored evolutionary duels on the fast simulator, in addition to the fights used to generate intermediate starting positions. Checkpoint selection measured terminal results against eight learned opponents in both seats at both latency settings and gave equal weight to mean performance and the weakest opponent-by-latency result. Each finalist played 1,536 selection duels. The objective uses a win or loss and half the normalized final health difference. Video labels, human demonstrations and named maneuvers have no role in computing the score. The first fast-trained predecessor underwent 1,182,720 scored refinement duels. [Selection records](evidence/league26/selection.json) · [Predecessor’s selection](evidence/flat-training/selection.json) · [Simulator measurements](SIMULATOR.md)

To reproduce this refinement from the saved training population, run the following from the project folder. Supply a fresh output directory. The second command grades the frozen weights.

```sh
python3 research/sword26/flat_train.py research/sword26/evidence/league26/protocol.json build/sword26/my-league-search build/sword26/my-league
python3 research/sword26/model_grade.py research/sword26/evidence/league26/protocol.json build/sword26/my-league/fighter.net build/sword26/my-league-evaluation
```

A second clean run reproduced the playing weights byte for byte, the complete tournament selection, and all ten model grade and maintenance files. On the M2 Max it took 116 seconds including 202,752 final tournament duels and grading. The separate `python3 research/sword26/train.py` entry point starts the full pipeline from random weights. [Reproduction record](evidence/league26/reproducibility.json)

## Whole-fight results

Each scheduled Java cell has six bouts, from three openings played in both seats. Each model cell has 48 bouts. The model samples the native update phases, opening orientation and network order. Its training and evaluation openings come from separate seeds. The Java matches below used a third, independently registered set. [Java per-opening HP and hit counts at 0 ms](evidence/league26/validation-0-scores.csv) · [Java per-opening HP and hit counts at 100 ms](evidence/league26/validation-100-scores.csv) · [Model per-opening scores against the retreating rival](evidence/league26/grade-population.csv)

| Opponent | Model wins, 0 / 100 ms | Model W-tap wins, 0 / 100 ms | Java wins, 0 / 100 ms | Java W-tap wins, 0 / 100 ms |
|---|---:|---:|---:|---:|
| Plain W-tap | 38 / 29 | 24 / 24 | 6 / 4 | 3 / 3 |
| Original playable fighter | 38 / 35 | 3 / 10 | 4 / 4 | 0 / 1 |
| Retreating and strafing fighter | 26 / 46 | 2 / 2 | 1 / 6 | 0 / 2 |
| Previous fast-trained fighter | 28 / 25 | 14 / 11 | 2 / 4 | 1 / 2 |
| New independently trained opponent | 48 / 48 | 48 / 48 | 6 / 6 | 6 / 6 |

The independent opponent began as a separate population and never entered training or checkpoint selection. W-tap also beat it in every match, so sweeping it gives little evidence about high-level play. The retreating rival exposed the clearest weakness. At zero added latency it won five of six scheduled fights against the new fighter.

In a separate, normal-speed TCP run, the clients and server advanced on independent clocks. The fighter beat the retreating rival 2/4 at zero added latency and 4/4 at 100 ms. Its W-tap control went 0/4 at both. Both controllers beat the weaker fresh opponent 8/8. This gives 14/16 ordinary wins for the fighter and 8/16 for W-tap. The scheduled clock and the ordinary run used different openings. [Ordinary TCP per-opening scores](evidence/league26/ordinary-scores.csv) · [Complete native observations](evidence/league26/ordinary-traces.tar.gz)

## Which movements emerged?

In the 60 scheduled bouts the fighter kept forward pressed on all 9,148 sampled input ticks and never strafed. It chose when to sprint, jump, steer and attack. The referee counted 153 critical hits. Of 79 damaging sprint hits, 39 landed during the rise from the fighter’s own grounded jump, with no intervening received knockback. That sequence matches the uppercut described in Intel’s video. Plain W-tap produced zero such hits and zero criticals under the same settings. [Offline signature definitions](technique_audit.py) · [0 ms signatures](evidence/league26/input-patterns-0.csv) · [100 ms signatures](evidence/league26/input-patterns-100.csv)

The new fighter produced 22 boosted replies within two server ticks after receiving a fresh hit. For every one, the fighter had already issued its attack before incoming damage reached its own client. In none did it previously decline a fully aimed, charged opportunity under the three-poll audit. Also, seven new grounded jump presses coincided with receiving knockback. Coincidence provides limited evidence of anticipation or knockback cancellation. These observations leave deliberate hit selection and deliberate jump resetting unproven. The older retreating rival exhibits a different learned choice, backing up during charge recovery and moving in again near full charge. Forcing that rival to hold forward reduced its wins from seven to four in eight real fights. [Earlier movement intervention](RESULTS.md)

## Does choosing the jump actually help?

I froze the weights, changed one output at a time, and replayed each variant against W-tap and the stronger previously tested never-example. Every variant had identical aim, equipment, reach, input opportunities and openings. The same control fights are shared across the four rows. The model used 96 fights per opponent. Native used 12 per opponent, evenly split across latency and seats. [Predeclared intervention](evidence/league26/movement-protocol.json) · [Native 0 ms](evidence/league26/movement-0-scores.csv) · [Native 100 ms](evidence/league26/movement-100-scores.csv)

| Frozen policy intervention | Model vs W-tap | Model W-tap mirror | Model vs never-example | Model W-tap vs never-example | Java vs W-tap | Java W-tap mirror | Java vs never-example | Java W-tap vs never-example |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Learned controls | 68 | 48 | 59 | 13 | 10 | 6 | 6 | 1 |
| Jump always released | 75 | 48 | 78 | 13 | 9 | 6 | 9 | 1 |
| Jump always held | 91 | 48 | 86 | 13 | 11 | 6 | 11 | 1 |
| Strafe neutral | 68 | 48 | 59 | 13 | 10 | 6 | 6 | 1 |

In Java, holding jump produced 22 wins in 24 fights. The original got 16. This audit shows that the learned jumping pattern can execute an uppercut and that its timing still costs wins in these matchups. The fixed-jump intervention was made after training and is excluded from playing-weight selection.

## Maintaining a combo

The fixed starting point is the third unanswered fresh hit after the defender begins clicking. Model comparisons replay the same complete W-tap prefix in both continuations. Native arms each run their own W-tap opening and switch at the next input poll after the referee records entry. Only the model supplies identical complete state. A return includes an accepted damage upgrade. The activity-qualified score also ends on a gap longer than 22 ticks.

Against the retreating fighter the model found five three-hit starts in 96 opening-and-seat combinations. From the same five states, each controller added three fresh cycles in total. The new policy saw three returns and two round-end censors. W-tap saw four returns and one censor. In the actual scheduled game both arms established zero of six starts at zero latency and four of six at 100 ms. From the four entries, the new fighter added 1, 1, 0 and 0 fresh cycles. W-tap added 2, 2, 0 and 0. All eight continuations ended on an accepted return. Return-only exposures were 11–23 ticks for the fighter and 11–25 for W-tap. The inactivity-qualified counts and exposures were identical in these eight continuations. The attached rows record actual entry HP, range, grounding and switch delay for each independent native run. [Model forks](evidence/league26/maintenance-population.csv) · [Native 0 ms](evidence/league26/branches-0-branches.csv) · [Native 100 ms](evidence/league26/branches-100-branches.csv)

The difference from W-tap is clearer against the weaker independent opponent, where the model supplies 89 matched three-hit states. The new fighter added 85 cycles, W-tap 112. At the first input opportunity the attacker had a median of 12% sword charge and the defender 80%. W-tap released forward on 44 of 88 actionable starts. The new fighter held forward on all 88 and pressed jump on 44. At the next native fresh-damage window, W-tap had own-view reach on 53/88 and the new fighter on 47/88. Returns before the window occurred in 33 W-tap and 37 learned continuations. This identifies spacing and the defender’s existing charge as concrete reasons to study the simple baseline. The forks change all subsequent learned inputs together, so they cannot assign the difference to a single key. [Matched input, reach and exposure by state](evidence/league26/forks-fresh.csv) · [Return-only and inactivity-qualified outcomes at three, five and ten hits](evidence/league26/maintenance-fresh.csv)

Across the regular 60 scheduled bouts the fighter entered a three-hit streak twice and added zero further cycles. W-tap entered 15 times and added eight cycles. The fighter had no five-hit entry, W-tap had eight, and neither reached ten. These two groups have different acquisition histories. Separately, the fighter had geometric reach at 167/487 scorable native fresh-damage windows and W-tap at 129/539. Each arm missed a fresh hit on the precise opening tick in every scorable window. Round end censored another 52 and 44 windows respectively. Delaying a swing does not move that window. [Full scheduled zero-latency audit](evidence/league26/validation-0-audit.json) · [100 ms audit](evidence/league26/validation-100-audit.json)

## A recurrent challenger

I also trained two recurrent PPO runs against seven learned opponents and their evolving self-play archives. The actor received health-difference changes and terminal fight outcomes. The runs played 68,000 training fights and supplied 216 snapshots for a frozen cross-play selection. Tree opponents kept their original policy representation. [Training registration](evidence/neural26/protocol.json) · [Frozen neural checkpoint](evidence/neural26/fighter.net) · [Selection](evidence/neural26/selection.json)

| Opponent | Model neural wins / 64 | Model W-tap wins / 64 | Java neural wins / 8 | Java W-tap wins / 8 |
|---|---:|---:|---:|---:|
| W-tap | 29 | 32 | 4 | 4 |
| Retreating rival | 24 | 1 | 1 | 0 |
| Stronger previously tested never-example | 37 | 6 | 5 | 1 |

The neural policy used the back key on 216 of 3,731 native input ticks. Of those retreats, 210 occurred below half sword charge. It moved forward on all 802 fully charged ticks and never pressed jump in this panel. That is another independently learned way to manage space. Its poor matchup against the retreating rival limits its playing strength. [Native zero-latency audit](evidence/neural26/native-0-audit.json) · [Native 100 ms audit](evidence/neural26/native-100-audit.json)
