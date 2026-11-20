# What the ablations changed

## Which matchups need motion and hurt information?

Each trained cell pools the three matched seeds on six clocks and both seats at four link pairs. Scores give half credit for draws. The control columns use a single fixed policy on the same settings.

| Foe | Base | Without own velocity | Without visible opponent displacement | Without hurt ages | Incumbent | Plain W-tap |
|---|---:|---:|---:|---:|---:|---:|
| hold | 100.0% | 100.0% | 96.2% | 100.0% | 100.0% | 32.3% |
| shift-check-v4-4 | 52.8% | 22.2% | 46.5% | 39.2% | 47.9% | 4.2% |
| sumo-own-ping | 48.6% | 23.6% | 51.4% | 42.0% | 46.9% | 6.2% |
| sumo-two-ping | 52.1% | 22.6% | 44.4% | 39.2% | 64.6% | 2.1% |
| trade | 99.3% | 97.2% | 97.2% | 97.2% | 100.0% | 42.7% |
| v4-ppo-g0496 | 51.4% | 25.7% | 42.4% | 39.9% | 50.0% | 6.2% |
| v4-reply-g0312 | 50.0% | 26.4% | 37.5% | 33.0% | 53.1% | 4.2% |
| v4-sprint-link-jitter-15 | 50.0% | 27.8% | 41.7% | 35.8% | 47.9% | 4.2% |
| wtap | 93.8% | 94.4% | 97.9% | 96.2% | 93.8% | 50.0% |
| zest-strafe | 100.0% | 98.6% | 98.3% | 100.0% | 100.0% | 94.8% |

## Can the incumbent use its own RTT reading?

Both copies play each identical physical scenario. The neutral copy always receives the feature value for 80 ms. Therefore the 80/80 cell is an exact negative control. Records are wins–losses–draws. Improved and worsened include win–draw and draw–loss transitions.

| Added RTT, self / foe | Accurate reading | Neutral reading | Score difference | Improved / same / worsened | Plain W-tap |
|---|---:|---:|---:|---:|---:|
| 0 / 0 | 72–23–25 | 61–35–24 | +9.6 | 18 / 96 / 6 | 22–97–1 |
| 80 / 80 | 76–31–13 | 76–31–13 | +0.0 | 0 / 120 / 0 | 30–88–2 |
| 31 / 109 | 75–9–36 | 71–10–39 | +2.1 | 17 / 85 / 18 | 35–84–1 |
| 109 / 31 | 68–32–20 | 64–37–19 | +3.7 | 16 / 92 / 12 | 29–90–1 |

Across the six simulator clock donors, the changes in points for an accurate own RTT reading were +5.6, +6.2, -1.2, -0.6, +11.9, +1.2. Physical clocks and link values were unchanged within each pair.

## Which keys change when a reading disappears?

These are counterfactual decisions on the actual, unmodified native trajectory. Each row holds the observed positions, physical inputs so far, and previous applied action fixed. Only the named feature group changes at inference. An action can change on multiple heads. This counts a jump only when it would be requested on the ground.

| Native actor | Reading disabled | Decisions | Any key | Forward / sprint | Strafe | Click gate | Jump | Matched W-tap screen record |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| sumo-input-base8 | foe-motion | 6,003 | 7.1% | 3.4% | 3.9% | 0.4% | 0.2% | 4–4–0 |
| sumo-input-base8 | hurt | 6,003 | 61.8% | 56.7% | 6.5% | 1.1% | 0.2% | 4–4–0 |
| sumo-input-base8 | own-motion | 6,003 | 15.1% | 8.1% | 8.4% | 0.6% | 0.2% | 4–4–0 |
| sumo-input-base8 | own-ping | 6,003 | 4.5% | 1.6% | 2.9% | 0.1% | 0.1% | 4–4–0 |
| sumo-input-base8 | radial | 6,003 | 6.2% | 2.4% | 3.4% | 0.4% | 0.2% | 4–4–0 |
| sumo-input-base8 | sideways | 6,003 | 4.3% | 0.4% | 3.9% | 0.0% | 0.0% | 4–4–0 |
| sumo-input-base8 | vertical | 6,003 | 9.1% | 5.1% | 4.7% | 0.2% | 0.2% | 4–4–0 |
| sumo-input-full32 | edge | 2,275 | 5.8% | 1.3% | 4.2% | 0.3% | 0.3% | 4–4–0 |
| sumo-input-full32 | motion | 2,275 | 3.8% | 0.9% | 2.6% | 0.1% | 0.2% | 4–4–0 |
| sumo-input-full32 | timing | 2,275 | 6.9% | 0.4% | 6.3% | 0.1% | 0.1% | 4–4–0 |

The base8 actor used its velocity differently in the 150 ms after a visible hurt cue.

- When only own hurt was recent, 146 of 385 decisions changed (37.9%).
- When only opponent hurt was recent, 106 of 406 decisions changed (26.1%).
- When both hurt cues were recent, 128 of 209 decisions changed (61.2%).
- When neither hurt cue was recent, 528 of 5,003 decisions changed (10.6%).

## Simulator ordering and Minecraft ordering

Across the 12 preselected trained entrants, the Spearman correlation between simulator and Minecraft scores on W-tap and shift-check was 0.32. Both scores use the same opponents, physical RTTs and seats. The simulator uses six independent clock donors, while Minecraft has one new bout per opponent, link and seat. The correlation describes this small screen.
