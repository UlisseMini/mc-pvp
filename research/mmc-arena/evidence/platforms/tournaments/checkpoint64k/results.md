# 65,536-fight specialists

Entries are wins/draws/losses. Every duel uses the MMC preset and a seven-CPS cap for both players. Each specialist faces its own frozen training opponent. Plain W-tap faces that same opponent with the same seat, reach and two RTTs.

## Simulator

| Platform | Frozen best-sumo | W-tap vs best-sumo | League winner 001 | W-tap vs winner 001 | Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| default | 128/0/0 | 32/0/96 | 93/0/35 | 3/0/125 | 108/1/19 | 6/0/122 |
| mmc-137 | 127/0/1 | 20/0/108 | 91/1/36 | 2/0/126 | 115/3/10 | 8/0/120 |
| mmc-129 | 127/0/1 | 26/0/102 | 90/0/38 | 3/0/125 | 116/2/10 | 8/0/120 |
| mmc-155 | 127/0/1 | 20/0/108 | 99/0/29 | 1/0/127 | 109/2/17 | 4/0/124 |
| mmc-177 | 128/0/0 | 22/0/106 | 96/1/31 | 1/0/127 | 104/7/17 | 4/0/124 |
| mmc-221 | 128/0/0 | 18/0/110 | 91/9/28 | 0/0/128 | 90/20/18 | 0/0/128 |

Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. The same timing recordings appear in several cells.

## Main Minecraft server

| Platform | Frozen best-sumo | W-tap vs best-sumo | League winner 001 | W-tap vs winner 001 | Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| default | pending | pending | pending | pending | pending | pending |
| mmc-129 | pending | pending | pending | pending | pending | pending |
| mmc-221 | pending | pending | pending | pending | pending | pending |

Partial native sample: 0 of 8 planned seat-and-RTT blocks completed per matchup. Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.

[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model) · [Native schedule and audits](native)
