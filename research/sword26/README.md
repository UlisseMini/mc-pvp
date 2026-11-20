# Modern sword lab

## Fight the bot

Double-click **Start Modern Sword Lab.command** in the project folder. Enter your Java player name, then pick the trained bot, its retreating and strafing rival, or a blind comparison with plain W-tap. When Terminal prints the address, open **Minecraft Java 26.3**, choose **Multiplayer → Direct Connection**, and enter **127.0.0.1:25663**. The lab equips both players and starts a duel. The launcher runs ten rounds and saves the results and replay. Use Control-C in Terminal to end your session early.

On this Mac, the bot and referee run in a dedicated Lima Linux VM. Each stock bot client has its own Xvfb virtual display and Mesa software renderer. The host launcher never opens a bot window. The first run downloads verified game assets and installs guest dependencies. The VM mounts the modern lab source read-only and its build folder read-write. The old practice server has its own port and runtime. The guest can be stopped with `limactl stop sword26` when you finish. The next launch starts it automatically.

To compare the trained bot with plain W-tap yourself, replace `YourPlayerName` with your player name and run the following from the project folder. You play five pairs of duels in shuffled order. Each pair starts at the same angle and distance. The Terminal hides each opponent's identity until the recordings are available.

```sh
python3 research/sword26/native.py --human YourPlayerName --blind --pairs 5
```

A round uses full unenchanted diamond armor and a diamond sword, a 24-block-wide court, normal difficulty and disabled natural regeneration. A kill wins. A round lasting 600 server ticks is a draw. Both bot seats get the same aim assistance and one physical press opportunity per client input tick. The aim helper tracks vertical aim and turns up to 45 degrees per input tick. A policy can request a heading away from the opponent. The normal Minecraft client chooses the targeted entity and the server determines the result.

## Run the learner

From the project folder on this Mac:

```sh
python3 research/sword26/train.py
```

The trainer creates a new folder under `build/sword26`. It prints the path to the selected weights and preserves all of the earlier checkpoints. `--scale 0.1` runs a shorter experiment. The default run starts with random weights. It trains a recurrent population and a recurrent PPO policy, evolves decision-tree policies against the growing field, then selects a checkpoint by its average and worst-opponent results in paired matches against learned opponents. The selected family can be a tree or a neural network. Movement, steering, sprint, jump, and click choices all come from the saved weights.

The population score uses the win or loss plus half the final health difference normalized by starting health. PPO gets the change in normalized health difference and the terminal win or loss. In later training stages some rounds start partway through an ordinary training game. Replaying its inputs reconstructs the model's clock, pending contacts and knockback, observation history, and previous controls. Training reads local player features and a delayed opponent view. The code for grading against named baselines is outside the training program.

`Model.java` implements a fast approximate flat-court simulator. It uses tick-sized latency queues, local motion, a displayed hitbox, ray checks, attack charge, immunity, damage upgrades, armor and momentum. The two real clients run Mojang's movement, picking and attack code in a vanilla dedicated-server match. `Parity.java` compares model movement and recorded real movement. Predictions of a whole exchange accumulate error. Independent native matches decide whether an apparent improvement carries over.

## Run a real comparison

```sh
python3 research/sword26/native.py --weights research/sword26/champion.net --pairs 4 --rtts 0 100
```

Each paired opening runs the candidate and plain W-tap in both seats. Both face the same opponent, using the same kit, aim, start and added RTT. The order is shuffled. Real runs have independent packet and client timing. The runner also accepts other saved policies through `--opponents`. A development run can use `--speed 3` to accelerate both clients and the server. The confirmation results use normal speed.

The output folder contains server health and positions, accepted attack events, each client's observed positions and keys, a result for each completed round, and **Replay.html**. Open the replay in a browser, choose a matchup and view, and scrub through it. The two client views reconstruct the visible hitbox and controls. Recent hits are marked below the picture. To resume an interrupted comparison, rerun its original command with `--resume /absolute/path/to/its/output`. It verifies the schedule and policy hashes, copies the completed rounds into a new run folder, and starts at the interrupted round.

The recorded comparisons also travel with the project. For example, these commands rebuild the independent-opponent replay from its saved traces. Round 7, view B shows the retreating rival fighting the default bot.

```sh
mkdir -p build/sword26/independent-replay
tar -xzf research/sword26/evidence/frozen/independent-native-traces.tar.gz -C build/sword26/independent-replay
python3 research/sword26/replay.py build/sword26/independent-replay
open build/sword26/independent-replay/Replay.html
```

Read the [results and observed techniques](RESULTS.md) and the [settings frozen before evaluation](protocol.json).
