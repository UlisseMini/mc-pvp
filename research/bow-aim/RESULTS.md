# Adaptive bow aim for Minecraft 1.8.9

`/p bowaim on` uses the learned movement model with per-player adjustment.
`/p bowaim linear` uses the same arrow solver with constant velocity from
the last five client ticks. `/p bowaim off` restores manual aiming.
The assist rotates the visible crosshair while the user draws the bow.
At manual release it sends the ordinary look update before the release packet.

## What was trained

The model uses consecutive client observations of remote players from the
same frozen BedWars captures as the location study. It fits ridge regressions
for displacements at 3, 5, 8, 12, 16, 22, 30 and 42 ticks. Inputs include
velocity over four windows within the last second, differences in recent
horizontal movement and vertical velocity changes. X and Z share coefficients.
Y has its own coefficients. Predictions between trained times are interpolated.

The adaptive arm uses earlier completed movements from the current target.
It looks for similar recent velocities and nearby starting positions in the
last 256 consecutive observations. It adds a weighted fraction of the errors
the shared model made on those earlier examples. The fit used matches 01–08,
with 07–08 used as a development check before the final fit. Matches 09–12
were reserved for evaluation of this bow model.

[Benchmark and fit code](Benchmark.java) · [Movement model](../../practice-client/core/practice/core/bow/BowMotion.java) · [Frozen comparison](protocol.json)

## Replay comparison

Each mode used the same target start and future path, shooter position, full
draw and seeded arrow spread. The shooter was placed at a known distance and
bearing in open air. Each arm got 184,532 paired shot scenarios. Overlapping
starts from the same match are correlated. Values below average the four match
hit rates equally.

| Starting distance | Straight-line lead | Shared learned movement | Adaptive lead |
| --- | ---: | ---: | ---: |
| 12 blocks | 93.2% | 97.0% | 97.0% |
| 24 blocks | 70.2% | 77.8% | 78.0% |
| 38 blocks | 52.9% | 59.8% | 60.0% |
| 54 blocks | 40.2% | 45.8% | 45.8% |

Adaptive lead beat straight-line lead at every tested distance in each match.
At 24 blocks, the advantage ranged from 7.5 to 8.1 percentage points. At a
16-tick movement horizon, mean horizontal error fell from 1.28 to 1.12 blocks and
vertical error from 0.93 to 0.61 blocks. The shared model accounted for most of
the gain. Individual memory added up to 0.25 percentage points across the
replay ranges.

[Replay hit counts](../../runtime/bow-aim/held-out-hit-rates.csv) · [Position errors](../../runtime/bow-aim/held-out-position-errors.csv)

## Native Forge comparison

The integrated Forge 1.8.9 server applied 857 sampled target paths from the
same four evaluation recordings to native entities. It fired real
`EntityArrow` objects into native collision checks, with the same full draw
and seed across the three arms. Straight-line lead hit 552/857 (64.4%).
The shared and adaptive models each hit 595/857 (69.4%). Adaptive lead beat
straight-line lead overall in all four matches. It fell one shot short of
linear lead at 54 blocks in match 10 and two shots short in match 12.

A separate native client check exercised the mode switches and verified that
the look packet preceded the ordinary release packet. The accuracy tests
use controlled open shots. Live server latency, cover, and a player's reaction
to an incoming arrow can change results.

[Native hit counts](../../runtime/bow-aim/native-scores.csv) · [Forge probe](ForgeProbe.java)

## Reproduce

Run these from the repository root with the frozen BedWars captures, the game
runtime and the isolated build in `build/bow-aim/practice-client`.

```sh
python3 research/bow-aim/extract.py runtime/bedwars-forecast/advanced/tracks.json.gz \
  runtime/bow-aim/trajectories.bin.gz
mkdir -p runtime/bow-aim/classes
javac --release 8 -d runtime/bow-aim/classes practice-client/core/practice/core/bow/*.java \
  research/bow-aim/Benchmark.java
java -cp runtime/bow-aim/classes Benchmark train runtime/bow-aim/trajectories.bin.gz \
  practice-client/assets/practice/bow-motion.bin 8
java -cp runtime/bow-aim/classes Benchmark score runtime/bow-aim/trajectories.bin.gz \
  practice-client/assets/practice/bow-motion.bin 9 12
java -cp runtime/bow-aim/classes Benchmark native runtime/bow-aim/trajectories.bin.gz \
  practice-client/assets/practice/bow-motion.bin runtime/bow-aim/native-cases.bin
python3 research/bow-aim/native.py
```
