# sumo-league-remote-001 on the MMC validation floors

Entries are wins/draws/losses. The published winner faces remote-001-mlp, its weakest distinct development opponent, and plain W-tap. Every matchup uses seven CPS on both sides.

## Simulator

| Platform | Winner vs remote-001-mlp | Plain W-tap vs remote-001-mlp | Winner vs W-tap | Plain W-tap mirror |
| --- | --- | --- | --- | --- |
| default | 77/5/46 | 1/0/127 | 128/0/0 | 64/0/64 |
| mmc-137 | 75/3/50 | 0/0/128 | 128/0/0 | 64/0/64 |
| mmc-129 | 75/3/50 | 0/0/128 | 128/0/0 | 64/0/64 |
| mmc-155 | 82/8/38 | 0/0/128 | 126/0/2 | 62/4/62 |
| mmc-177 | 72/15/41 | 0/0/128 | 127/0/1 | 62/4/62 |
| mmc-221 | 65/27/36 | 0/0/128 | 127/0/1 | 60/8/60 |

Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. The same timing recordings appear in several cells.

## Main Minecraft server

| Platform | Winner vs remote-001-mlp | Plain W-tap vs remote-001-mlp | Winner vs W-tap | Plain W-tap mirror |
| --- | --- | --- | --- | --- |
| default | pending | pending | pending | pending |
| mmc-137 | pending | pending | pending | pending |
| mmc-129 | pending | pending | pending | pending |
| mmc-155 | pending | pending | pending | pending |
| mmc-177 | pending | pending | pending | pending |
| mmc-221 | pending | pending | pending | pending |

The live comparison has eight independently played seat and RTT blocks per matchup. Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.

[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model)
