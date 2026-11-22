# Gen 4 with automatic sprint resets

The Ctrl override won 2 of 16 audited native matches against Gen 4, drew 2, and lost 12. The mixed-ping simulator gave it 31 wins, 11 draws and 214 losses in 256 matches. The published experimental bot is `gen4-auto-sprint`. The champion still uses its trained sprint policy.

## Controller and scoring

Both bots load the same frozen GRU32 weights and consume the original action sample each tick. When the network chooses forward, the variant presses Ctrl if its own local sprint is off and releases Ctrl if sprint is already latched. After a sprinting contact the release lets Minecraft send STOP. The next forward input can re-arm sprint. Strafe, forward/back, jumps, aim, the click gate and its seven-CPS limiter follow the original controls. An edited fight produces new observations and therefore new future network decisions. The server sprint flag is available only to the scorer.

Every bout used normal speed, the recorded 137-block floor, eight-block opening, three-block reach, zero reaction/aim noise, the recorded-range MMC preset and a two-minute draw limit. Counts below are wins / draws / losses.

## Matched simulator results

| Opponent | Auto Ctrl | Gen 4 | Plain W-tap baseline |
|---|---:|---:|---:|
| Gen 4 | 31 / 11 / 214 | 112 / 27 / 117 | 0 / 0 / 256 |
| Plain W-tap | 249 / 1 / 6 | 255 / 1 / 0 | 128 / 0 / 128 |
| Sumo Seven First | 122 / 29 / 105 | 234 / 4 / 18 | 8 / 0 / 248 |
| Gen 4 counter | 18 / 18 / 220 | 125 / 11 / 120 | 0 / 0 / 256 |
| Gen 3 | 33 / 20 / 203 | 130 / 14 / 112 | 0 / 2 / 254 |

Each row uses 256 matches per controller, both seats, eight development calendars, four seed repetitions, and four own/opponent RTT pairs. Every arm in a fixture gets the same timing calendar and initial action seeds. A check on 40 unchanged-policy games reproduced all archived-runtime decisions counted, fresh-hit trajectories, times and outcomes in the review output.

| RTT, actor / opponent (ms), against Gen 4 | Auto Ctrl | Gen 4 | Plain W-tap baseline |
|---|---:|---:|---:|
| 0/0 | 26 / 11 / 27 | 31 / 14 / 19 | 0 / 0 / 64 |
| 0/80 | 4 / 0 / 60 | 44 / 4 / 16 | 0 / 0 / 64 |
| 80/0 | 0 / 0 / 64 | 12 / 3 / 49 | 0 / 0 / 64 |
| 80/80 | 1 / 0 / 63 | 25 / 6 / 33 | 0 / 0 / 64 |

Across mixed pings, 8271/8430 (98.1%) of the variant’s fresh hits against Gen 4 carried sprint KB. The intact mirror actor had 15286/16124 (94.8%). These are hit-weighted rates. At 80 ms own RTT, automatic Ctrl reduced the contact rate from 76.3% to 67.8% and outward contact brakes per incoming fresh hit from 1.79 to 1.50. They describe the fights the intervention created. Movement spacing, later observations and click timing changed together.

The 0/0 follow-up used 1,024 new pairs of action seeds on the same calendars. Auto Ctrl scored 307 / 223 / 494 against Gen 4. The matched intact mirror actor scored 464 / 152 / 408 and plain W-tap scored 8 / 14 / 1,002. With half credit for a draw, auto Ctrl scored 40.9% versus 52.7% for the mirror actor. The paired difference was −11.9 percentage points (95% bootstrap interval −15.7 to −8.0 points, resampling repetitions within calendar and keeping the two seats together). All eight development calendars favored the intact actor. This measures repeatability on those clocks.

## Main-server Minecraft

| Actor RTT / opponent RTT (ms), against Gen 4 | Auto Ctrl | Plain W-tap baseline |
|---|---:|---:|
| 0/0 | 0 / 2 / 2 | 0 / 0 / 2 |
| 0/80 | 2 / 0 / 2 | 0 / 0 / 2 |
| 80/0 | 0 / 0 / 4 | 0 / 0 / 2 |
| 80/80 | 0 / 0 / 4 | 0 / 0 / 2 |

Across the two seats, automatic Ctrl scored 2 / 1 / 5 in A and 0 / 1 / 7 in B. Against plain W-tap, auto Ctrl scored 8 / 0 / 0 and the intact champion scored 8 / 0 / 0. In their direct matchup the variant dealt sprint KB on 548/560 fresh hits and intact Gen 4 on 539/570. The two native policies played independent round starts with actual client and server timing. Match counts per latency are small.

On local sprint contacts where W was held through the next decision, the automatic variant sent STOP on the contact tick and START on the next tick in 1327/1330 cases. The intact Gen 4 did that in 179/1394. These denominators include local contacts during server immunity.

32 native matches passed the action and click audit. Every recorded policy decision reproduced the expected mapping from sampled action and local sprint. Both neural arms used identical archived weights. The physical click admission interval for both seats was at least 142.857 ms. The scorer checked the actual MMC sprint bonus at each fresh hit and scored timeout draws at the regulation limit. Excluded control failures and their replays were preserved: m10374 (b no verified running controller). Each excluded fixture was repeated with matching controls and a new request suffix.

[Per-match evidence, seat and RTT splits](auto-sprint-results.json) · [Simulator reproduction script](auto_sprint.py) · [Playable native policy](../../../../../policies/gen4-auto-sprint.properties)
