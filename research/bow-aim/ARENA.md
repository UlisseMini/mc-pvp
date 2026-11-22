# BedWars bow dodging arena

The arena imports the recorded voxel terrain from replay games 07–11 at
match second 110. It retains the observed map layout and block edits up to
that time. Starts span different distances on the same island and across map
gaps. The pack includes five maps and the source hashes and spawn coordinates
for every case. [Arena map manifest](../../runtime/bow-aim/arenas.bin.json)

## Scenarios

Both actors move before release. The shooter strafes, slows down while drawing,
and varies draw duration from 15 to 20 ticks. The defender uses continuous
movement with acceleration, jump and landing dynamics, and ledge avoidance.
The evaluator uses the same pre-release state for every model. Each arrow
gets the same seeded dispersion.

1. **Repeating strafe.** The defender reverses on a sustained rhythm. Each
   episode randomizes the period and phase.
2. **Feints.** The defender varies reversal timing, occasionally hops, and
   mixes forward and backward steps into the strafe.
3. **Reactive dodges.** The defender uses variable footwork and can react to
   the arm's actual arrow after a randomized delay. It needs line of sight to
   a nearby incoming arrow. Each arm starts from an identical fork, then the
   defender responds to what it sees in that arm.

The shooter observes only visible positions up to the previous tick, quantized
to 1/32 block. The tests use replay geometry with approximate voxel collisions
and movement physics in a local simulator. The installed mod's separate native
tests check actual Minecraft arrows and the camera and display controls.
[Simulator](DodgeArena.java) · [Map packer](arena_maps.py)

## First iteration

The new predictor searches the visible recent motion for a repeating period.
It compares horizontal movement over two stretches of history and only blends
in the previous cycle when the match is close. It uses the installed model's
height prediction. An ablation of three match thresholds ran on development
maps 07–09. The overall hit count selected a threshold of 0.40 before maps
10 and 11 were scored. The results use full calculated lead for all three
predictors, which isolates prediction from the live 3° camera cap.
[Protocol](arena-protocol.json) · [Frozen selection](arena-selection.json)

| Defender | Eligible scenarios | Straight-line lead | Installed predictor | Cycle predictor |
| --- | ---: | ---: | ---: | ---: |
| Repeating strafe | 1,407 | 26.2% | 38.3% | **82.6%** |
| Feints and hops | 1,395 | 28.0% | 46.2% | **47.1%** |
| Reactive dodges | 1,377 | 34.0% | **46.6%** | 45.5% |

There were 4,752 attempts and 4,179 shared eligible release states. Shooter
travel averaged about 24 blocks before release. Defender travel averaged
31–34 blocks depending on the strategy. In the reactive tier, the defender
actually responded to a visible arrow in about 82% of eligible shots against
the installed predictor and the cycle predictor.

The cycle correction transferred to both evaluation maps for the rhythmic
test. It gave a small gain on feints and lost 16 hits on reactive dodges. The
cycle predictor remains an arena experiment. The reactive results give a
target for the next iteration, especially recognizing when the opponent has
changed rhythm or is reacting to a shot already in flight.
[Development scores](../../runtime/bow-aim/arena-development.csv) · [Evaluation scores](../../runtime/bow-aim/arena-heldout.csv)

## Run the arena

From the repository root:

```sh
python3 research/bow-aim/arena_maps.py research/bedwars-forecast/viewer/dist/assets \
  runtime/bow-aim/arenas.bin.gz
mkdir -p runtime/bow-aim/classes
javac --release 8 -d runtime/bow-aim/classes practice-client/core/practice/core/bow/*.java \
  research/bow-aim/DodgeArena.java
java -cp runtime/bow-aim/classes DodgeArena runtime/bow-aim/arenas.bin.gz \
  practice-client/assets/practice/bow-motion.bin dev 15
java -cp runtime/bow-aim/classes DodgeArena runtime/bow-aim/arenas.bin.gz \
  practice-client/assets/practice/bow-motion.bin heldout 36 2
```
