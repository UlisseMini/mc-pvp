# BedWars player movement and within-match adaptation

## What the tracks showed

The top-down plots show positions colored by when they were observed. The strips show distance from the map center and the pace of consecutive samples. They leave gaps where the recorder lost the player.

- **Match 03.** One player repeatedly used the east and south bridge approaches. Another circulated more widely with fewer slow samples.
- **Match 07.** One player’s most-used base tile accounted for 63% of observed positions in the first half of their track and 87% in the second. Another shifted from the western area toward mid and made repeated loops there. Their original favorite tile dropped from 44% to 14% of their time.
- **Match 08.** One player stayed in a single tile for all 109 observed seconds. Another started with 92% of their positions in one tile, dropped to 28% there later, and used a wider set of bridges and islands.

[Movement plots](../../../runtime/bedwars-forecast/context-study/development-tracks.png) · [Track measurements](../../../runtime/bedwars-forecast/context-study/track-observations.json)

## What the model predicts

For each player and horizon, the model gives probability to every integer **feet block `(X, Y, Z)`** in its distribution. It combines seven forecasts with a learned set of weights. Two give general movement predictions. The other five look at staying in place, the most recent 24 seconds, older locations, destinations of earlier trips from similar positions, and an inferred home base when the player was observed near the opening. Each forecast can spread mass across several candidate locations. The mixture adds their probabilities before scoring the recorded block.

A small network learns how to choose the weights from the current movement, recent dwell time, revisits, and route match quality. It learns across development games. At inference, it builds a separate memory from each player's observed history. For an earlier route starting at second `s`, its destination can influence a forecast issued at second `t` only once `s + horizon ≤ t`. The training loss is exact-block negative log probability plus 0.25 times the negative log probability of the recorded 16-block horizontal tile. The coarse term provides credit for assigning mass nearby.

## Evaluation design

- Fit the shared priors and gate with matches 01–08. For the gate, predict each development match with upstream models trained on the other development fold.
- Compare models on matching observations from matches 09–12. Those matches had appeared in the earlier BedWars report and were reserved from this fit and manual inspection. Match 12 is partial.
- Average each metric within each match and give each match equal weight. The late cohort uses the earlier benchmark's cutoff of 55% of the match plus 10 seconds and at least eight completed support outcomes. Report late player-level comparisons only where the pair has 15 or more predictions.
- Score predictions when the recorded client sees the target at the prediction time and at the endpoint. This measures future location conditional on a recorded endpoint. The recordings provide partial coverage of distant players.
- Check altitude separately by subtracting exact X/Z log loss from exact X/Y/Z log loss. The result is the log loss of `P(Y | recorded X, Z)`. The observed X/Z is used for evaluation of that term.
- Verify the causal memory by removing all future locations and unmatured route outcomes from sampled development forecasts. The memory and route inputs matched across 9,545 forecasts.

## Results

The comparison includes 100,075 paired forecasts across the five horizons. The scores are exact 3D foot-block log loss in nats, with lower values better.

| Horizon | Map only | Frozen heatmap | Frozen motion | Previous meta-history | Current-state gate | Fixed personal blend | Personal without routes | Full personal gate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5s | 13.108 | 8.995 | 8.847 | 8.985 | 8.054 | 7.648 | 7.484 | 7.383 |
| 10s | 13.108 | 10.301 | 10.516 | 10.146 | 9.615 | 8.685 | 8.565 | 8.489 |
| 30s | 13.129 | 11.965 | 12.518 | 11.464 | 11.492 | 9.990 | 9.953 | 9.922 |
| 60s | 13.153 | 12.503 | 12.914 | 11.979 | 11.994 | 10.585 | 10.577 | 10.562 |
| 120s | 13.142 | 12.693 | 13.000 | 12.427 | 12.226 | 11.131 | 11.150 | 11.144 |

At 60s late in the match (4,972 forecasts), the personal gate scored **11.18** versus **12.97** for the frozen heatmap and **12.37** for the previous adaptation. That is 6.0× and 3.3× the geometric mean probability assigned to the actual 3D block. Gains against the frozen heatmap ranged from 0.77 to 2.54 nats across the four matches, with wins for 34/39 eligible player–match pairs. Against previous adaptation the range was 0.59–1.98, with wins for 34/39 pairs.

### What drove the gain

- **Personal history.** The current-state gate scored 12.53 at 60s. Adding even a fixed blend of personal forecasts brought that to 11.17. That fixed blend uses each player’s past locations and routes with the same learned base weights for everyone at that horizon.
- **Switching behavior.** A learned gate improved on the fixed blend by 0.26 nats at 5s and 0.21 at 10s late in the match. Their late-game scores differed by at most 0.02 nats at 60s and 120s. Removing the route expert raised the all-row score by 0.10 nats at 5s and 0.01 at 60s.
- **Short-horizon decisions.** After a stop of at least eight seconds, the 5s gate gave the tight-stay forecast 67% of the probability on average. For players averaging 2+ blocks per second, that fell to 5% and the route expert received 24%. At 60s, the frozen heatmap, older locations and home memory were the three largest components overall.

### Height check

The height audit re-evaluated 14,266 matching forecasts at a seven-second sampling interval. This is a smaller sample than the main table. At 60s late in the match:

| Model | Exact X/Z | Y given exact X/Z | Total exact X/Y/Z |
| --- | ---: | ---: | ---: |
| Frozen heatmap | 10.06 | 2.86 | 12.92 |
| Old meta history | 9.49 | 2.86 | 12.35 |
| Fixed weights | 8.67 | 2.48 | 11.14 |
| Full personal gate | 8.65 | 2.50 | 11.15 |

The prior meta-history model adjusted X/Z tile probabilities and reused the frozen height head within each tile. The new personal forecasts also use the player's previous altitudes.

[Score and gate charts](../../../runtime/bedwars-forecast/context-study/context-results.png) · [Match scores](../../../runtime/bedwars-forecast/context-study/game-scores.csv) · [Player scores](../../../runtime/bedwars-forecast/context-study/player-scores.csv) · [Height audit](../../../runtime/bedwars-forecast/context-study/height-audit.csv)

## In Minecraft

The installed models support **3D** and **Sum over Y** heatmaps. The 3D display groups the model’s block probabilities into 16×4×16-block volumes. Sum over Y paints the horizontal probabilities on the terrain. Ghosts use a predicted feet block in either mode. The display threshold applies to the probability of the whole hotspot, and the ghost cap applies across the selected horizons.

On the test machine, complete Java inference and aggregation took 170–206 ms for five horizons and 16 players on the three models timed. It runs on a worker thread. Java matched Python on 150 volumetric forecasts across all 15 supported models, with a maximum bin difference of 2.76 × 10⁻⁷. An isolated Forge 1.8.9 launch exercised the commands, menu persistence, and native 3D volume and ghost drawing. The new personal gate in this study is an offline model.

## Reproduce

From the repository root with the saved recordings and frozen checkpoint in `runtime/bedwars-forecast/advanced`:

```bash
uv run --python 3.12 --with torch==2.15.0 --with numpy==2.4.6 \
  --with scipy==1.18.1 --with scikit-learn==1.9.1 \
  python research/bedwars-forecast/context-study/context_models.py \
  --no-cache --out runtime/bedwars-forecast/context-study

uv run --python 3.12 --with torch==2.15.0 --with numpy==2.4.6 \
  --with scipy==1.18.1 --with scikit-learn==1.9.1 \
  python research/bedwars-forecast/context-study/height_audit.py \
  runtime/bedwars-forecast/context-study
```

The frozen split, source hash, horizons, and metrics are in the [study protocol](protocol.json).
