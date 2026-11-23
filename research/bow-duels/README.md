# Bow Duel league

The league runs complete 20 Hz duels in a batched simulator. The opening
spacing, armor and kit come from the recorded November 22 duel. The original
arena has open and covered variants. Movement, damage, collision, pearl
teleports and the dry-arrow path approximate Minecraft 1.8.9. Episodes end
with a knockout or a draw after 640 ticks. Native transfer and the installed
aim assist as a tournament baseline still need a separate evaluation.

The MLP, GRU and GRU with opponents' future-position prediction share the
observation and action interface. New policies start with trainable weights
that use observed lead angles, draw charge and the current need to keep the
bow equipped, then train with PPO. They output movement, sprint, sneak, jump,
attack, hotbar, use-button state and continuous yaw and pitch deltas. Terrain
and projectiles are expressed relative to the player. Opponent movement uses
observed positions and their age. The forecast policy learns Gaussian heads
at 4, 8 and 16 ticks with logged simulator positions as labels, and its
control head receives the same observations as the other policies.

## Counter training and title defense

[The competition runner](competition.py) starts with the last champion from
the previous run. Each generation clones two generalist policies and trains
those counters exclusively against the frozen incumbent for three 96-arena
updates. A counter enters the threat pool if it wins 77 of 128 development
duels. A reactive script plays the same panel as a reference.

Then three generalists each train for four updates. Each rollout assigns 25%
of arenas to the incumbent, 25% to qualified counters and the learner's
weakest archive matchup, and 50% to the weighted archive. The archive contains
the three scripts and up to the sixteen most recent admitted learned policies.
Previous champions and qualified counters remain frozen on disk. Within the
weighted half, an opponent gets probability proportional to
`(1 - score)^2 + 0.05`, with score equal to wins plus half the draws divided
by games on a 24-duel panel. Each learner gets its own sampling plan.

The incumbent and all five candidates play the same script and archive
panel. Merit averages the mean family score and worst family score. A
challenger also plays 128 direct duels from both spawn sides on a separate
generation-specific panel. Promotion requires higher merit than the
incumbent, more direct wins than losses, and a one-sided paired sign test
with a 5% familywise threshold across the five challengers. If multiple
challengers qualify, the highest-merit one takes the title. Otherwise the
incumbent retains it. Intermediate optimizer checkpoints, matchup weights,
title results and candidate weights are archived. All league scores come
from simulation.

## Batched Rust backend

[The Rust library](geometry.rs) updates solid-box collision for both
players and builds terrain and projectile features for every arena in the
batch in one call. On CPUs with AVX2 it processes four cover bearings at
once and reuses the ray intervals for all three heights. Active obstacles
are stored as a bit mask in the kernels. The policy also samples its
button groups in a single tensor operation. PyTorch still performs model
inference and PPO updates.

The Hetzner simulation-only benchmark includes stepping, vision, both
observations and resets. At 24 arenas it ran at 17.7k arena ticks/s versus
4.36k for NumPy. At 384 arenas it ran at 105k versus 8.19k. A matched
24-arena GRU benchmark including optimization rose from 2.45k to 4.20k
arena ticks/s. At 96 arenas it rose from 3.94k to 5.94k. The Rust and
NumPy versions agreed on the discrete outcomes in the deterministic
backend comparison and had a maximum feature difference of about 1.2e-7.

```sh
python3 research/bow-duels/rust_arena.py runtime/bow-league/parity
python3 research/bow-duels/bench_geometry.py /path/to/libbow_geometry.so
```

Use `BOW_GEOMETRY_LIB` to select the built library when running the league.
The native verifier creates an instrumented local Forge 1.8.9 server and two
real loopback clients. It archives both PLAY packet streams, per-client
inputs and observations, projectile spawns, damage events and server state
at every tick of the fixtures. The comparator uses the recorded inputs for
movement and the measured launch state for projectile tests. Across nine
movement fixtures, including a walk and a jump into cover, the maximum
position error was 4.2e-6 blocks. The six arrows agreed on hit versus miss,
and the pearl destination error was below 1e-6 blocks. This comparison
conditions on the recorded launch state, including the sampled spread.
Packet latency, damage immunity interactions, general terrain and policy
outcomes still need full-duel parity checks.

```sh
python3 research/bow-duels/native_parity.py runtime/bow-league/parity/native-new
python3 research/bow-duels/compare_native.py runtime/bow-league/parity/native-new --verify
```

Run the league locally with `python3 research/bow-duels/competition.py` (set `BOW_GEOMETRY_LIB` to point to the compiled Rust library for AVX2-accelerated rollouts).
