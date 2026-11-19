# Learning sumo in the 1.8.9 lab

The playable policy is `sumo-rl-pressure`. In Minecraft, run:

```text
/duel sumo-rl-pressure sumo preset=mmc cps=20 tapdelay=0 aimdelay=0 aimnoise=0 aimrate=0
```

The [performance report](RESULTS.md) gives the simulator and actual Minecraft results, the movement-only control, the boxing maintenance test, and the limits of the elite-human claim.

## What the bot learned

An episode starts with two players six blocks apart on a ten-block disk. The environment ends on a server-observed ring-out or at 120 seconds. Cross-entropy policy search gives a win one point, a loss zero, and a draw half a point. A policy picks a forward-pressure style and a table of sixteen grounded jump decisions. Candidates in one generation see the same starting states, opponents, timings and seats. The final fit played 34,752 training bouts and selected continuous W with four jump conditions. The final ranking used all 192 development scenarios.

The bot holds W and the sprint key, tracks its opponent, and uses ordinary attack presses with a 50 ms minimum interval. It reads its own motion, its on-ground flag and the opponent it sees on its client. The signed approach speed is the component of its own horizontal velocity pointing toward the opponent. Three cases describe all four enabled table entries.

| Opponent height relative to own feet | Own horizontal motion | Rendered horizontal center gap at which to press Jump |
| --- | --- | --- |
| At most 0.25 blocks higher | Moving away faster than 0.015 blocks/tick | Under 3.4 blocks |
| More than 0.25 blocks higher | Moving away faster than 0.015 blocks/tick | Under 2.35 blocks |
| More than 0.25 blocks higher | All other speeds | From 3.4 up to 4.75 blocks |

The thresholds and observation bins were designed. The optimizer chose the movement style and which bins should jump. Aim and the attack scheduler are shared with the baseline. Click selection, burst scheduling, tactical strafing, and responses to individual human habits are outside this policy's learned actions. The Forge client calls the same [jump function](../../src/lab/SumoJumpPolicy.java) as the simulator and presses the vanilla Jump binding.

## What one rollout simulates

[DuelEnv](DuelEnv.java) owns two client bodies, the two separately interpolated opponent views, and the server's version of the fight. [PeriodicComboClock](../../tests/PeriodicComboClock.java) orders input heads, frame polls, queued clicks, client task drains, movement, packet flush, server task drain, entity ticks and tracker updates. [PeriodicComboFightModel](../../tests/PeriodicComboFightModel.java) generates contacts, temporary immunity, damage upgrades, sprint changes, velocity packets and tracker messages. [ExactTravel](../../src/lab/ExactTravel.java) clips the player's retained bounding box against the ground or the actual disk cells, including the recovery step onto the lip. A sumo ring-out is decided from the server's feet position falling below Y=11.5. Boxing uses a flat floor.

Training is head-on. The movement and ray code retain X, Y and Z, which also allows a separate test with a strafing opponent. The host clocks are real recordings stripped down to scheduling opportunities. Short recordings repeat to cover a longer round. The fitted environment estimates additional network scheduling latency at 0.35 ms per leg on top of half the configured RTT. A particular live game can receive a packet on a different frame and take a different trajectory.

Train, preserve and evaluate a new run from the project root with Java 17 and Python 3. The timing tapes and frozen evaluation clocks are checked in. The v1 test clocks encode a retired three-world importer bug and are preserved as historical evidence. The current reader rejects them.

```sh
mkdir -p build/sumo-study
python3 research/sumo-rl/train_league.py --output build/sumo-study/new-fit.json
python3 research/sumo-rl/run.py evaluate-league research/sumo-rl/league-validation-clocks build/sumo-study/frozen.jsonl
python3 research/sumo-rl/run.py evaluate-solid research/sumo-rl/league-validation-clocks build/sumo-study/current-physics.jsonl
python3 research/sumo-rl/run.py evaluate-lateral research/sumo-rl/league-validation-clocks build/sumo-study/lateral.jsonl
java -ea -cp build/sumo-rl/classes SimChecks research/sumo-rl/train-clocks
java -ea -cp build/sumo-rl/classes Benchmark research/sumo-rl/league-validation-clocks 8192 4 70704
```

The league evaluation modes use the **frozen** league fit by default. Add `--fit build/sumo-study/new-fit.json` to score your own run. The first reproduces the training-era edge and tracker handling. The second tests the same weights with solid edge recovery and the donor's tracker iteration order. The third plays the hit-selecting Zest strafer. The integer argument to the benchmark packs the movement style above the sixteen jump bits. Plain W-tap is 0, Hold-W is 65536 and the frozen policy is 70704.

## Repeat a native test or audit a replay

With the lab running and the bot bridge installed, use a fresh manifest and request prefix. The runner reserves two bot slots and plays bot-only duels. The scorer checks policy, physical click intervals, both client builds, reach, reaction and aim settings, latency, native scores and packet quantization.

```sh
python3 research/sumo-rl/native.py build/sumo-study/native.json --prefix sumo-replication-example --policy sumo-rl-pressure
python3 research/sumo-rl/score_runs.py build/sumo-study/native.json build/sumo-study/scores
python3 research/sumo-rl/check_fights.py build/sumo-study/native.json build/sumo-study/physics
```

To audit one local recording with detailed probes on both clients:

```sh
python3 research/sumo-rl/validate.py build/sumo-study/m2197-travel.json m2197
python3 research/periodic-clock-replay.py m2197 --frames --transport-trace --seconds 8 --output build/sumo-study/m2197-fight.json
python3 research/sumo-rl/input_replay.py build/sumo-study/m2197-input.json m2197
```

The first audit supplies observed attack contacts and incoming velocity, then checks native movement and the bounding box. The second generates contacts and packets with recorded input, aim and packet-service times, and asserts their payloads and the resulting fight state. The third uses physical inputs and host stages, estimates transport delay, and aims inside the simulator. For the strict test, inspect completed versus requested events, bit mismatches and the first packet error. Missing probes are reported explicitly.

## Grade against elite humans

The elite-player cohort belongs to the final evaluator. The training and native bot results here use zero matches from that cohort. The local grader can freeze an evaluation roster and arm order ahead of play. Replace the example names with the actual roster.

```sh
python3 research/sumo-rl/grade_humans.py register build/sumo-study/elite.json PlayerOne PlayerTwo --pairs 10 --seed 71839 --rtt 0
python3 research/sumo-rl/grade_humans.py score build/sumo-study/elite.json
```

Within a player, one pair is a round against each arm. The plan counterbalances the order. Set the player's added RTT with `/ping me 0` for this example, then use the duel command above with the scheduled bot name and `ping=0`. The grader checks future matches chronologically, verifies the frozen bridge and settings, and counts interruptions separately. Registering a plan creates a local file. Invitations and live human participation are separate.
