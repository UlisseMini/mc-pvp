# Minecraft PvP Lab

A working local practice server and research ladder using the official **Minecraft
Java 1.8.9** server and real clients through **basic HeadlessMC**. The game performs
movement, collision, raycasting, attacks, knockback and damage immunity.

## Join and play

1. Open Minecraft Java **1.8.9** in your normal launcher.
2. Choose **Multiplayer → Direct Connect** and enter **127.0.0.1:25565**.
3. Type **`/bots`** and click a bot to fight it. Each duel is first to 100 hits.

The server is already set up on this Mac. If it is stopped, double-click
**Start PvP Lab.command** in this folder and leave its Terminal window open.
An “already running” message means you can join immediately.

| Command | What it does |
|---|---|
| `/bots` | Clickable roster, including saved search candidates |
| `/duel hold-w` | Fight the simplest baseline |
| `/duel predictive` | Fight the hand-written spacing controller |
| `/duel burst 120 20` | Add 120 ms RTT to the bot and 20 ms to yourself |
| `/rematch` | Same bot and simulated pings again |
| `/stop` | End your duel or leave spectator mode; return to the lobby |
| `/matches` | Clickable list of ongoing matches |
| `/watch` | Spectate an ongoing match; click a follow-camera button |
| `/watch m0025 a` | Follow player A in that match, while it is active |
| `/lab match hold-w wtap 20 120` | Start a bot match with those RTTs |

In spectator mode, fly around normally. Sneak exits a followed bot's camera.
The action bar shows the current score. Finished matches are saved automatically.
Spectators cannot interfere with combat. There are six real bot clients; the
experiment scheduler uses at most four so human challenges have spare slots.

RTTs are **added**, symmetric network delay. Your own real connection and normal
Minecraft tick/interpolation delays remain. The human-facing latency setting is
shared: this first version is intended for **one human player at a time**.

## Controllers and rules

| Bot | Control idea |
|---|---|
| `hold-w` | Hold W and sprint, click continuously |
| `wtap` | Short scheduled W releases every 400 ms |
| `hit-reset` | React to a visible hit, briefly release W |
| `spacing` | Delayed closing-speed forecast with coasting and hysteresis |
| `predictive` | Spacing forecast with an explicit ping-dependent lead |
| `phase-spacing` | Adjust movement around a forecast hit window |
| `burst` | Spacing with explicit 300 ms click bursts per 500 ms cycle |
| `evolved-g…` | Saved parameter-search candidates, challengeable immediately |
| `evolved-champion` | Selected candidate, once a search finishes |

- Normal speed, no potions, no enchantments, flat grass, diamond swords.
- First to 100 **server-accepted attacks**. Health is restored after real damage
  so a duel can continue; knockback, damage immunity and critical-hit logic run.
- Perfect aim uses the current **local client view**. It does not read server
  positions or aim at removed entity copies left over from teleports.
- W or release and the normal sprint key. No strafing, S, jumping or blocking.
- Tactical observations are at least **150 ms old**. Planned timing is allowed.
- Fixed policies schedule nominal **10 CPS**, with at least 100 ms between input
  clicks. Frame scheduling makes measured CPS slightly lower. Burst is labelled.
- Every scheduled click enters normal Minecraft input and attack handling,
  including immune-frame attacks, air misses and their native slowdown/cooldown.
- No direct bot movement/attack packets, custom reach, velocity overrides during
  combat, or immunity-based engine optimizations. Teleports/equipment are round setup.

The sprint key is held while W is held, so vanilla can restore sprint after an
attack even for the baseline. [THEORY.md](THEORY.md) explains the reasoning and
why known ping alone is insufficient to specify an exact optimal best response.
These are initial controllers and a small evolutionary search, with no claim of
global optimality or equivalence between every 1.7 and 1.8 combat detail.

## Experiments and recordings

From Terminal in this folder:

```sh
python3 experiments.py pilot
python3 experiments.py evolve --population 4 --generations 1
python3 experiments.py league --bots hold-w wtap hit-reset predictive --rtts 20,20 20,120
```

The pilot compares the hand-written controllers. Evolution mutates three spacing
parameters, evaluates each against W-tap in both starting roles, saves the best
candidate, then tests different ping and a different opponent outside selection.
The random seed fixes parameter generation; process scheduling and game/network
clock phases still vary. Two games per candidate are an exploratory sample.

The scheduler validates that both clients are alive, ticking, and can see their
live opponent. Invalid setups and incomplete games fail the experiment rather
than silently awarding wins. See **experiments/RESULTS.md** after a run completes.
Early setup/debug matches are excluded from that report.

Every match records server trajectories and accepted hits in
`runtime/replays/<match>.jsonl`, with client input/attack telemetry under
`runtime/bots/<slot>/events.jsonl`. Export a finished match:

```sh
python3 replay.py m0025
```

Open the resulting file in **recordings/** in your browser. It includes play,
pause, speed, scrubbing, overhead/side views, scores and recorded input cadence.
These are **trajectory analysis replays, not Minecraft video**. All data is
embedded, with no external resources. Three sample recordings are included,
including the 20/120 ms spacing-versus-burst match.

## Setup and checks

This implementation targets macOS and uses the existing Homebrew Java 17 and
Python installations. No Forge, Fabric, Mineflayer, npm or pip install is needed.
For a fresh copy on a similarly configured Mac:

```sh
python3 lab.py prepare
python3 lab.py build
python3 lab.py sandbox-test
python3 lab.py serve
```

`prepare` downloads hash-locked dependencies without executing upstream builds.
The Java agent compiles locally with annotation processing disabled. `serve`
checks dependency hashes and refuses to launch if the sandbox checks fail.

```sh
python3 lab.py status
python3 lab.py match hold-w wtap 20 120
python3 lab.py stop
python3 -m unittest -v test_lab
python3 lab.py logging-test
python3 benchmark.py --seconds 20
```

`python3 lab.py verify-ux` uses a separate real headless client to test commands;
start a fresh match first and run it while no human is playing. Logs are in
`runtime/logs/`. Do not edit the agent while a tournament is running; build and
restart the lab after Java changes.

The first measured load was two bot matches plus one human duel: seven JVMs used
about 3.5 GiB RSS and 38% of one CPU core over 20 seconds. Native server tick gaps
had a 50 ms median, 56 ms p99 and 58 ms maximum. This verifies that load on this
M2 Max; it does not establish the maximum capacity. Raw measurements are in
`experiments/performance.json`.

## Security

All game listeners bind to this computer's IPv4 loopback. There is no public
server, router change, tunnel, RCON, account-token access or authentication
launcher. Runtime JVMs have a tested macOS sandbox, a clean environment and a
separate home directory. Waiting bots use spectator mode and arenas are widely
separated. See [SECURITY.md](SECURITY.md) for dependencies, restrictions and limits.
