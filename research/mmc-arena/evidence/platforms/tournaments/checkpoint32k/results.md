# 32,768-fight specialists

Entries are wins/draws/losses. Every duel uses the MMC preset and a seven-CPS cap for both players. Each specialist faces its own frozen training opponent. Plain W-tap faces that same opponent with the same seat, reach and two RTTs.

## Simulator

| Platform | Frozen best-sumo | W-tap vs best-sumo | League winner 001 | W-tap vs winner 001 | Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| default | 128/0/0 | 32/0/96 | 96/0/32 | 3/0/125 | 104/4/20 | 6/0/122 |
| mmc-137 | 127/0/1 | 20/0/108 | 86/0/42 | 2/0/126 | 105/0/23 | 8/0/120 |
| mmc-129 | 127/0/1 | 26/0/102 | 86/0/42 | 3/0/125 | 103/0/25 | 8/0/120 |
| mmc-155 | 127/0/1 | 20/0/108 | 93/2/33 | 1/0/127 | 98/5/25 | 4/0/124 |
| mmc-177 | 128/0/0 | 22/0/106 | 95/4/29 | 1/0/127 | 99/9/20 | 4/0/124 |
| mmc-221 | 128/0/0 | 18/0/110 | 94/7/27 | 0/0/128 | 92/19/17 | 0/0/128 |

Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. The same timing recordings appear in several cells.

## Main Minecraft server

| Platform | Frozen best-sumo | W-tap vs best-sumo | League winner 001 | W-tap vs winner 001 | Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| default | pending | pending | pending | pending | pending | pending |
| mmc-137 | pending | pending | pending | pending | pending | pending |
| mmc-129 | pending | pending | pending | pending | pending | pending |
| mmc-155 | pending | pending | pending | pending | pending | pending |
| mmc-177 | pending | pending | pending | pending | pending | pending |
| mmc-221 | pending | pending | pending | pending | pending | pending |

The live comparison has eight independently played seat and RTT blocks per matchup. Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.

[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model) · [Native schedule and audits](native)
