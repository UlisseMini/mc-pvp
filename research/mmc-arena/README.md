# MMC sumo platform

The test server and regulation sumo simulator use a 13×13 platform with 137 floor blocks. The players start on gold blocks eight blocks apart. Walking straight back from either spawn takes 2.5 blocks to the edge of the top surface. A player's 0.6-block-wide body can extend past that edge while still touching the floor.

Nomophobia's practice mod bookmarked 430 sumo rounds across 19 MMC connections. We identified both countdown spawns and the connected platform below them in 429 rounds. The most frequent exact footprint and start gap appeared in 222 rounds across 17 connections. MMC used several other maps, including a different 13-block-wide footprint. The survey counts appearances in these recordings.

The copied floor comes from clip `fb302faee0f03c27bcaac437a64752fa`. It contained 74 stone, 32 stone brick, 29 mossy stone brick and two gold blocks. All 137 floor cells had two empty blocks directly overhead. We rotated the floor 90 degrees clockwise to put the gold blocks on the lab's usual approach axis. The lab keeps its own platform elevation and ring-out rule. Players lose when their feet fall half a block below the top surface.

```text
....#####....
..#########..
.###########.
.###########.
#############
#############
##A#######B##
#############
#############
.###########.
.###########.
..#########..
....#####....
```

A normal `/duel wtap sumo preset=mmc` selects the measured platform. An explicit `diameter=10` selects the former 80-block lab disk and six-block opening. The server also preserves the sizes of old replays.

The main server played three consecutive validation duels in the same arena slot. The default in m4513 matched all 256 recorded floor and surrounding air cells in a 16×16 square. M4514 selected the older disk. M4516 restored the recorded floor and gold markers. Both players occupied the expected spawn centers in all 41 countdown frames of each duel, and all three ended in ring-outs. [Live block hashes and spawn coordinates](evidence/live-verification.json)

## Reproducing the measurement

The decoder verifies the server name and Minecraft protocol 47. It reconstructs received chunk sections and subsequent block changes up to a stationary countdown frame, then flood-fills the fighting surface. It requires a known perimeter and a connected path between the players. One bookmark had only one frame with the opposing spawn visible and was excluded.

```sh
mkdir -p build/mmc-survey
cp research/mmc-arena/evidence/bookmarks.json build/mmc-survey/
python3 research/mmc-arena/survey.py build/mmc-survey --retry-errors --publish build/mmc-survey/results
```

[Survey counts](evidence/survey.json) · [Source block states and spawns](evidence/platform.json) · [Per-match measurements](evidence/measurements.jsonl.gz)
