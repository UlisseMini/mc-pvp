# 16,384-fight specialists

Entries are wins/draws/losses. Every duel uses the MMC preset and a seven-CPS cap for both players. Each specialist faces its own frozen training opponent. Plain W-tap faces that same opponent with the same seat, reach and two RTTs.

## Simulator

| Platform | Frozen best-sumo | W-tap vs best-sumo | League winner 001 | W-tap vs winner 001 | Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| default | 128/0/0 | 32/0/96 | 91/0/37 | 3/0/125 | 76/1/51 | 6/0/122 |
| mmc-137 | 128/0/0 | 20/0/108 | 92/1/35 | 2/0/126 | 80/3/45 | 8/0/120 |
| mmc-129 | 128/0/0 | 26/0/102 | 93/1/34 | 3/0/125 | 79/2/47 | 8/0/120 |
| mmc-155 | 128/0/0 | 20/0/108 | 89/0/39 | 1/0/127 | 74/6/48 | 4/0/124 |
| mmc-177 | 128/0/0 | 22/0/106 | 87/2/39 | 1/0/127 | 75/7/46 | 4/0/124 |
| mmc-221 | 128/0/0 | 18/0/110 | 81/5/42 | 0/0/128 | 69/16/43 | 0/0/128 |

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

[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model)
