# How much of the change comes from the opening distance?

Both policies use seven CPS in the simulator. Each row compares two frozen specialists and plain W-tap against Sumo Seven on the same 128 fixtures. The two synthetic openings keep the original facing, floor, midpoint and clocks. Entries are wins/draws/losses.

| Floor | Start gap | Opening | 16k specialist | 32k specialist | Plain W-tap |
| --- | --- | --- | --- | --- | --- |
| mmc-137 | 8 | recorded | 80/3/45 | 105/0/23 | 8/0/120 |
| mmc-137 | 10 | synthetic | 78/1/49 | 103/4/21 | 4/0/124 |
| mmc-221 | 8 | synthetic | 73/18/37 | 90/18/20 | 10/0/118 |
| mmc-221 | 10 | recorded | 69/16/43 | 92/19/17 | 0/0/128 |

For the 32k specialist, changing floors averaged -10.2 percentage points (approximate 95% interval -16.2 to -4.1) in outright wins across both start distances. Moving the spawns from eight to ten blocks averaged +0.0 percentage points (approximate 95% interval -7.8 to +7.8) across both floors. These are paired estimates across eight timing donors.

All 19 draws in the recorded 221-block opening lasted the full 120 seconds. The two players dealt a median of 269 fresh hits between them per draw. The median last fresh hit landed 0.34 seconds before timeout.

The six recorded-opening arms reproduced all 768 previously measured outcomes and terminal times. Each synthetic comparison uses the same seat, two RTTs, event calendar and action seeds as its recorded counterpart. The larger floor also has its recorded block materials and edges.

[Paired estimates](paired-effects.json) · [Seat, RTT and timeout breakdown](results.json) · [Matched native tests on the recorded openings](../tournaments/checkpoint32k/results.md)
