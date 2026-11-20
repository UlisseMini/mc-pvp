# Transfer to recorded MMC platforms

Three frozen specialists each trained for 16,384 fights on the default 137-block platform. Each row below uses 128 paired simulator bouts per policy and opponent, with eight development timing donors, both seats and all four combinations of 31 and 109 ms RTT. Each baseline column shows plain W-tap playing the same opponent. All six bots had a seven-CPS cap. Entries are wins / draws / losses.

| Platform and opening | Specialist vs frozen best-sumo | W-tap vs best-sumo | Specialist vs league winner 001 | W-tap vs winner 001 | Specialist vs Sumo Seven | W-tap vs Seven |
| --- | --- | --- | --- | --- | --- | --- |
| Training default, 137 blocks / 8 apart | 128/0/0 | 32/0/96 | 91/0/37 | 3/0/125 | 76/1/51 | 6/0/122 |
| Recorded orientation, 137 / 8 | 128/0/0 | 20/0/108 | 92/1/35 | 2/0/126 | 80/3/45 | 8/0/120 |
| Validation, 129 / 8 | 128/0/0 | 26/0/102 | 93/1/34 | 3/0/125 | 79/2/47 | 8/0/120 |
| Validation, 155 / 10 | 128/0/0 | 20/0/108 | 89/0/39 | 1/0/127 | 74/6/48 | 4/0/124 |
| Validation, 177 / 10 | 128/0/0 | 22/0/106 | 87/2/39 | 1/0/127 | 75/7/46 | 4/0/124 |
| Validation, 221 / 10 | 128/0/0 | 18/0/110 | 81/5/42 | 0/0/128 | 69/16/43 | 0/0/128 |

The old best-sumo counter won 512/512 across the four validation maps. A wider panel on the training map found four losses in 512 games. The validation maps reuse timing donors and seeds, so pooling their games as independent Bernoulli trials would overstate precision. The largest platform produced more timeouts for the Seven specialist. Its paired difference in outright wins from the training floor was −5.5 percentage points with an approximate eight-donor interval from −21.9 to +10.9 points. A zero-width empirical interval when all donor differences are zero describes the observed sample only.

The 155- and 221-block floors have the same ten-block opening along the same axis. Against Seven, the larger floor turned five wins and five losses into draws. Eleven wins became losses and eleven losses became wins. All six previous draws stayed draws. The additional draws account for the entire five-win decrease. Plain W-tap's four wins on the smaller of these two maps all became losses on the larger one.

These are model results. Each map directory stores the scores by seat and both RTTs, the fixtures, and each specialist's direct W-tap matchup. The four sealed shapes are reserved for a separately frozen checkpoint evaluation. [Complete model evidence](tournaments/baseline16k/model) · [Frozen protocol](tournaments/baseline16k/protocol.json)
