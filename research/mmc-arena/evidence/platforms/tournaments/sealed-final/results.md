# Frozen specialists on sealed maps

Entries are wins/draws/losses. Every duel uses the MMC preset and a seven-CPS cap for both players. Each specialist faces its own frozen training opponent. Plain W-tap faces that same opponent with the same seat, reach and two RTTs.

## Simulator

| Platform | Frozen best-sumo | W-tap vs best-sumo | League winner 001 | W-tap vs winner 001 | Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| default | 128/0/0 | 32/0/96 | 93/0/35 | 3/0/125 | 108/1/19 | 6/0/122 |
| mmc-137 | 127/0/1 | 20/0/108 | 91/1/36 | 2/0/126 | 115/3/10 | 8/0/120 |
| mmc-165 | 127/0/1 | 20/0/108 | 98/1/29 | 1/0/127 | 107/4/17 | 4/0/124 |
| mmc-193 | 128/0/0 | 22/0/106 | 95/5/28 | 1/0/127 | 98/11/19 | 2/0/126 |
| mmc-201 | 128/0/0 | 22/0/106 | 95/5/28 | 1/0/127 | 95/14/19 | 0/0/128 |
| mmc-223 | 128/0/0 | 18/0/110 | 91/9/28 | 0/0/128 | 90/20/18 | 0/0/128 |

### Matched final and original checkpoints

| Platform | Frozen opponent | Selected specialist | Original 16k specialist | Plain W-tap |
| --- | --- | --- | --- | --- |
| default | frozen best-sumo | 128/0/0 | 128/0/0 | 32/0/96 |
| default | league winner 001 | 93/0/35 | 91/0/37 | 3/0/125 |
| default | Sumo Seven | 108/1/19 | 76/1/51 | 6/0/122 |
| mmc-137 | frozen best-sumo | 127/0/1 | 128/0/0 | 20/0/108 |
| mmc-137 | league winner 001 | 91/1/36 | 92/1/35 | 2/0/126 |
| mmc-137 | Sumo Seven | 115/3/10 | 80/3/45 | 8/0/120 |
| mmc-165 | frozen best-sumo | 127/0/1 | 128/0/0 | 20/0/108 |
| mmc-165 | league winner 001 | 98/1/29 | 89/0/39 | 1/0/127 |
| mmc-165 | Sumo Seven | 107/4/17 | 74/6/48 | 4/0/124 |
| mmc-193 | frozen best-sumo | 128/0/0 | 128/0/0 | 22/0/106 |
| mmc-193 | league winner 001 | 95/5/28 | 83/2/43 | 1/0/127 |
| mmc-193 | Sumo Seven | 98/11/19 | 72/12/44 | 2/0/126 |
| mmc-201 | frozen best-sumo | 128/0/0 | 128/0/0 | 22/0/106 |
| mmc-201 | league winner 001 | 95/5/28 | 81/4/43 | 1/0/127 |
| mmc-201 | Sumo Seven | 95/14/19 | 69/15/44 | 0/0/128 |
| mmc-223 | frozen best-sumo | 128/0/0 | 128/0/0 | 18/0/110 |
| mmc-223 | league winner 001 | 91/9/28 | 81/5/42 | 0/0/128 |
| mmc-223 | Sumo Seven | 90/20/18 | 69/17/42 | 0/0/128 |

Each cell contains 128 bouts from eight timing recordings, mirrored seats and four RTT pairs. Matching timing recordings and action seeds across maps lets the platform change while the fixture stays fixed. The same timing recordings appear in several cells.

## Main Minecraft server

| Platform | Frozen best-sumo | W-tap vs best-sumo | League winner 001 | W-tap vs winner 001 | Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| default | pending | pending | pending | pending | pending | pending |
| mmc-137 | pending | pending | pending | pending | pending | pending |
| mmc-165 | pending | pending | pending | pending | pending | pending |
| mmc-193 | pending | pending | pending | pending | pending | pending |
| mmc-201 | pending | pending | pending | pending | pending | pending |
| mmc-223 | pending | pending | pending | pending | pending | pending |

Partial native sample: 0 of 8 planned seat-and-RTT blocks completed per matchup. Each published block has passed movement, physical click-spacing, map-state and countdown spawn checks.

[Match protocol](protocol.json) · [Per-seat and per-RTT simulator results](model) · [Native schedule and audits](native)
