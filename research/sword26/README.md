# Modern sword lab

## Watch the league

Double-click **Watch Sword League.command** in the project folder to open the local match room. New ordinary Java bot matches have first-person MP4s you can play, seek, download or watch full screen. The tournament clients render in a 360 × 240 headless window capped at 50 fps. A separate daylight exhibition profile records 854 × 480 at a 60 fps cap. Older league bouts offer timestamped arena replays. The graphs, match library and Java results refresh every five seconds. Finished MP4s are saved in the **Sword League Recordings** folder beside the launcher, organized by tournament panel. On this Mac each video shares its disk data with the original run file. The dashboard listens on the Mac's loopback address. [Open the match room](http://127.0.0.1:25708/)

## Fight the bot

Double-click **Start Modern Sword Lab.command** in the project folder. Enter your Java player name and choose **6** for the current champion from the ordinary-Java league. [League results and training](LEAGUE.md)

Choose **5** for the fighter trained from scratch on the final simulator. It learned to jump on receiving knockback. Option 4 plays the earlier league-trained fighter. Option 1 plays the original checkpoint, option 2 plays the retreating and strafing rival, and option 3 gives a blind comparison of the original and plain W-tap. When Terminal prints the address, open **Minecraft Java 26.3**, choose **Multiplayer → Direct Connection**, and enter **127.0.0.1:25663**. The lab equips both players and starts a duel. The launcher runs ten rounds and saves the results and replay. Use Control-C in Terminal to end your session early.

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

The learning and replay programs compile a standalone JVM simulator and run without a game window or loaded game world. The default trainer uses the Java 26.3 flat-court simulator in `FastDuel.java` and `FlatPhysics.java`. Each bot sees its own locally predicted motion and an interpolated view of the other player. The simulator separately advances both server players, input packets, damage timers, sprint state, knockback and velocity packets. Historical results from the first model can be reproduced with `--engine historical`. The [simulator measurements](SIMULATOR.md) compare full fights to two stock game clients running Mojang’s code with the lab’s scheduling and recording hooks.

## Run a real comparison

```sh
python3 research/sword26/native.py --weights research/sword26/champion.net --pairs 4 --rtts 0 100
```

Each paired opening runs the candidate and plain W-tap in both seats. Both face the same opponent, using the same kit, aim, start and added RTT. The order is shuffled. Real runs have independent packet and client timing. The runner also accepts other saved policies through `--opponents`. A development run can use `--speed 3` to accelerate both clients and the server. The confirmation results use normal speed.

The output folder contains server health and positions, accepted attack events, each client's observed positions and keys, a result for each completed round, and **Replay.html**. Open the replay in a browser, choose a matchup and view, and scrub through it. The two client views reconstruct the visible hitbox and controls. Recent hits are marked below the picture. To resume an interrupted comparison, rerun its original command with `--resume /absolute/path/to/its/output`. It verifies the schedule and policy hashes, copies the completed rounds into a new run folder, and starts at the interrupted round.

For a faster real-game comparison, add `--clock fast` and supply one RTT such as `--rtts 100`. This runs both real clients and the dedicated server through their normal game ticks under a shared packet schedule. Rendering is skipped during loaded gameplay. `--clock realtime` paces the same schedule at normal speed. Omit `--clock` for the independent TCP and render timing used in ordinary match confirmation. All three modes keep the bot windows inside Linux.

Use the fast simulator for training and candidate selection. For a promotion, freeze the weights and compare the challenger, incumbent and plain W-tap on fresh ordinary Java openings with the same opponent pool, both seats and each declared latency. Report results by opening and latency. Keep previously inspected openings for diagnosis and reserve fresh ones for the next promotion.

When two clocks give different results, pass `--openings-from PATH` to reuse an earlier run’s `results.json` or a saved list of opening angles and distances. The runner matches geometry by latency and opening number. The clients and TCP still run on independent clocks in ordinary Java. Scheduled Java tests reproducible trajectories, while ordinary Java tests whether the training distribution predicts playing strength. Investigate discrepancies alongside training and record the first observable difference before changing a physics constant. [Matched-clock investigation](SIMULATOR.md#the-same-openings-on-both-java-clocks)

The recorded comparisons also travel with the project. For example, these commands rebuild the independent-opponent replay from its saved traces. Round 7, view B shows the retreating rival fighting the default bot.

```sh
mkdir -p build/sword26/independent-replay
tar -xzf research/sword26/evidence/frozen/independent-native-traces.tar.gz -C build/sword26/independent-replay
python3 research/sword26/replay.py build/sword26/independent-replay
open build/sword26/independent-replay/Replay.html
```

Read the [from-scratch bot’s results and jump-reset tests](SELF-PLAY.md), the [earlier results and observed techniques](RESULTS.md) and the [settings frozen before evaluation](protocol.json).
