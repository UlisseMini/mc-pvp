# sumo-generalist-001-64k on the MMC validation floors

Entries are wins/draws/losses. The published generalist faces sg003-long-g1, its weakest distinct development opponent, and plain W-tap. Every matchup uses seven CPS on both sides.

## Simulator

| Platform | Generalist vs sg003-long-g1 | Plain W-tap vs sg003-long-g1 | Generalist vs W-tap | Plain W-tap mirror |
| --- | --- | --- | --- | --- |
| default | 59/0/69 | 3/0/125 | 127/0/1 | 64/0/64 |
| mmc-137 | 61/0/67 | 0/0/128 | 127/0/1 | 64/0/64 |
| mmc-129 | 64/0/64 | 0/0/128 | 127/0/1 | 64/0/64 |
| mmc-155 | 56/2/70 | 1/1/126 | 128/0/0 | 62/4/62 |
| mmc-177 | 56/6/66 | 0/1/127 | 128/0/0 | 62/4/62 |
| mmc-221 | 51/14/63 | 1/0/127 | 127/0/1 | 60/8/60 |

Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. The same timing recordings appear in several cells.

## Main Minecraft server

| Platform | Generalist vs sg003-long-g1 | Plain W-tap vs sg003-long-g1 | Generalist vs W-tap | Plain W-tap mirror |
| --- | --- | --- | --- | --- |
| default | 0/0/1 | 0/0/1 | 1/0/0 | 0/0/1 |
| mmc-137 | 0/0/1 | 0/0/1 | 1/0/0 | 1/0/0 |
| mmc-129 | 0/0/1 | 0/0/1 | 1/0/0 | 0/0/1 |
| mmc-155 | 0/0/1 | 0/0/1 | 1/0/0 | 0/0/1 |
| mmc-177 | 0/0/1 | 0/0/1 | 1/0/0 | 0/1/0 |
| mmc-221 | 0/0/1 | 0/0/1 | 1/0/0 | 1/0/0 |

Partial native sample: 1 of 8 planned seat-and-RTT blocks completed per matchup. Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.

[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model) · [Native schedule and audits](native)
