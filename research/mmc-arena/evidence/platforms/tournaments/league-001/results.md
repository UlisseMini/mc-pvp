# sumo-league-001 on the MMC validation floors

Entries are wins/draws/losses. The published winner faces sumo-seven-first, its weakest distinct development opponent, and plain W-tap. Every matchup uses seven CPS on both sides.

## Simulator

| Platform | Winner vs sumo-seven-first | Plain W-tap vs sumo-seven-first | Winner vs W-tap | Plain W-tap mirror |
| --- | --- | --- | --- | --- |
| default | 74/0/54 | 6/0/122 | 127/0/1 | 64/0/64 |
| mmc-137 | 64/2/62 | 8/0/120 | 125/1/2 | 64/0/64 |
| mmc-129 | 64/2/62 | 8/0/120 | 125/1/2 | 64/0/64 |
| mmc-155 | 67/6/55 | 4/0/124 | 124/0/4 | 62/4/62 |
| mmc-177 | 67/15/46 | 4/0/124 | 126/0/2 | 62/4/62 |
| mmc-221 | 65/24/39 | 0/0/128 | 126/0/2 | 60/8/60 |

Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. The same timing recordings appear in several cells.

## Main Minecraft server

| Platform | Winner vs sumo-seven-first | Plain W-tap vs sumo-seven-first | Winner vs W-tap | Plain W-tap mirror |
| --- | --- | --- | --- | --- |
| default | 1/0/0 | 0/0/1 | 1/0/0 | 1/0/0 |
| mmc-137 | 1/0/0 | 0/0/1 | 1/0/0 | 0/0/1 |
| mmc-129 | 1/0/0 | 0/0/1 | 1/0/0 | 1/0/0 |
| mmc-155 | 1/0/0 | 0/0/1 | 1/0/0 | 0/1/0 |
| mmc-177 | 1/0/0 | 1/0/0 | 1/0/0 | 0/0/1 |
| mmc-221 | 1/0/0 | 0/0/1 | 1/0/0 | 1/0/0 |

Partial native sample: 1 of 8 planned seat-and-RTT blocks completed per matchup. Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.

[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model) · [Native schedule and audits](native)
