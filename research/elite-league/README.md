# Learning sumo and boxing in the 1.8.9 lab

The sumo score is a ring-out. Boxing has a first-to-100 score and a separate question about how long a player can keep a combo after establishing it. A player can trade hits successfully in sumo and still give away the next boxing hit. The experiments train and score those skills separately. [Performance and limitations](RESULTS.md)

## Tournament winners on the main server

Use `/duel best-sumo sumo preset=mmc` or `/duel best-boxing boxing preset=mmc` for the current playing choices. Each title change records its evidence and previous winners keep their permanent names. Open `/bots winners` for the overall title holders. The button starts a sumo duel with the same MMC combat preset. `/bots winners tracks` shows the neural and explicit winners from each round. Direct names also work, for example `/duel season-12-explicit sumo preset=mmc`. The numbered series are `population-01` through `population-12`, `season-01` through `season-12`, and `game-01` through `game-06`. A returning champion can hold several titles. New experimental editions can supply a distinct `ELITE_EDITION` prefix. Each published policy carries the source hash and keeps its original weights. [Publisher](champions.py)

## Fight a contender

Use the main server and choose sumo or boxing in the duel command. These settings match the research court's provisional MMC sprint-reset profile, 3-block reach and shared 20 CPS ceiling.

```text
/duel elite-v1-hybrid sumo preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0
/duel elite-boxing boxing preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0
/duel research-wtap20 sumo preset=mmc reach=3 cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0
```

The hybrid uses a recurrent hidden state and a forward-pressure prior. The boxing specialist plays W-tap while watching both hurt cues. After the first two seconds it counts its own observed streak and enables its learned controls at three. A server fresh hit and its client hurt animation can occur at different times. The scorer records both boundaries.

## What the bot can see and do

A playing policy gets the local player's position, motion, sprint and ground state, the rendered opponent, elapsed time since locally received hurt cues, RTT and its previous applied controls. It establishes its origin from the two opening positions. The combat network has 22 input values. It decides among release, W, W with Ctrl and S, left/right strafe, a grounded jump, and whether to admit the next ordinary click. The shared client handles mouse targeting, entity selection, missed clicks, packet reporting and the cap of one physical left press per 50 ms. Aim tracking is programmed for these experiments. The human timing imitation accounts for specific aspects of one person's movement and click pacing. [Inference](../../src/lab/DuelNetwork.java) · [Forge bindings](../../src/lab/Client.java)

The affine, eight-unit feedforward and eight-unit recurrent policies output control scores directly. Clipped PPO trains a stochastic actor and a value function using complete simulated bouts and generalized advantage estimation. Population search evaluates and perturbs whole policies by their ring-out performance. The hybrid adds a programmed pressure bias to recurrent scores. A separate router can switch between four already-trained fighters after its first observed incoming hurt. All of these paths export weights to the same Java 8 runtime. [Policy gradient](PPO.java) · [Population](Population.java) · [Router](../../src/lab/ExpertRouter.java)

## How the ladder improves

1. Include W-tap, Hold-W, hit selectors, resetters, lateral movement, independent learned opponents and a small human-derived sparring group. Keep previously successful opponents in the pool.
2. In **every season**, train a neural contender, a parameterized explicit rule, a distance-and-timing lookup table, and a short-horizon search controller. The search considers ten action programs against two plausible opponent reactions using a local estimate. The actual opponent's response and packet timing are hidden during play. [Explicit tactics](../../src/lab/CombatTactics.java)
3. Cross-play the checkpoints on the development clocks. Sample opponents that both last season's neural and programmed finalists struggled against more often next time. Keep a sampling floor. Feed positions from losses on the training clocks into the next prototype search. Raw per-bout scores, sampling weights, retained weights and season histories are archived. [Twelve seasons](evidence/ladder-development/seasons.json)
4. Inspect comparable native exchanges. Branch a complete matching model state before trying alternate three-head continuations. After season three, fit a table for the first exchange. After season eight, repeat for the second exchange, with the earlier responses in the opposing field. Selection checks that the chosen table's counterfactual score reproduces its complete closed-loop tournament score. [Measured control responses](ladder-refinements)
5. Put milestone entrants on a common field. Freeze them, play a separate main-server selection tournament, then freeze one entry for an independent native confirmation. Later testing against elite humans uses a separate identity roster. [Transfer protocol](ladder-transfer-protocol.json)

## Simulator and evidence

The simulation runs both clients, their independently scheduled input, movement, interpolation and report events, the server's packet drains, entity updates and tracking, plus knockback and hurt immunity. It supports lateral motion and a solid disk with block edges. A donor is a real native timing recording. Different offsets of the same donor are correlated. Training uses 16 original donors. The repeated seasonal tournaments use eight distinct, previously exposed development donors. New mirror recordings supply each registered prospective panel. A recorded donor provides scheduling opportunities. A closed-loop contender still has to generate its own hits, impulses and input history. [Fight kernel](../../tests/PeriodicComboFightModel.java) · [Event clock](../../tests/PeriodicComboClock.java) · [Match environment](../sumo-rl/DuelEnv.java)

Four checks answer different questions. Native travel compares the next position, velocity, collision box and flags. Continuous travel starts once and applies real local velocity receipts and contacts. Physical-input replay predicts both players from keys, clicks and the host's scheduling opportunities with estimated network overhead. A richer replay supplies observed wire timings and checks generated packets. The final check plays independent rounds in real Forge clients. Use each result at its stated level of information. [Fidelity panel](fidelity_panel.py)

For boxing maintenance, establish the same W-tap prefix through three unanswered fresh hits after the defender starts clicking. Fork the complete game, both histories, queues and pending inputs. Score additional completed cycles, first accepted return including a damage upgrade, elapsed exposure and right-censoring. Repeat at five and ten fresh hits. Also end a parallel activity-qualified score after a gap greater than 1,000 ms. Native repeats have independent opening jitter. The scorer keeps their measured landmark times and geometry. [Boxing registration](boxing-protocol.json) · [Complete-state forks](BoxingDrill.java)

## Reproduce a comparison

From the repository root, run a fresh model evaluation of the four original architectures and both simple controls on the frozen first validation calendars. This uses the actual recorded event tapes and writes a new output directory.

```sh
python3 research/elite-league/tournament.py validate \
  research/elite-league/frozen research/elite-league/sealed-clocks \
  build/reproduce-elite-v1
```

Re-run the season tournament against the archived champion weights, inspect a particular native request or branch a training example with the tools below. Native commands enqueue real duels on the main lab. Model ranking and candidate selection must finish before their prospective donors are collected. [Mixed tournament](ladder.py) · [Native selection and confirmation](ladder_transfer.py) · [Native action and knockback audit](audit.py) · [Source-level fight examples](trade_cases.py)

Register human opponents before the first scored fight. Use new, named players of known competitive level and freeze the exact challenger. The grader includes W-tap, balances order within each person, checks per-bout parameters and weights, and reports interruptions. Nomophobia is reserved for development. The grading commands support sumo and boxing. [Grader](../sumo-rl/grade_humans.py)
