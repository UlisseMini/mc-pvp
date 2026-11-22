# BedWars heatmaps and within-match learning

The new study uses 12 recorded BedWars match segments. The training and development split was frozen before scoring. Models fit on games 01–08 were evaluated on games 09–12. The viewer also includes the separate development forecasts for games 07–08, made with a fit on games 01–06. The last test match is capture-limited. The benchmark uses seed 393, reports a spread across held-out matches, and treats the many overlapping one-second forecasts as correlated.

## Held-out location forecasts

Each number is mean exact 3D block log loss, in nats. Lower is better. The table weights the four matches equally. Unrecorded endpoints are excluded from exact-block scoring and marked in the viewer. The frozen study input is [tracks.json.gz](../../runtime/bedwars-forecast/advanced/tracks.json.gz).

| Horizon | Stay | Motion mixture | Exact-loss heatmap | Multi-scale heatmap | Best adapted heatmap |
| --- | ---: | ---: | ---: | ---: | --- |
| 5s | 9.76 | **8.85** | 9.00 | 9.00 | Ordinary head tuning · 8.87 |
| 10s | 10.98 | 10.52 | 10.31 | 10.30 | Meta history · 10.15 |
| 30s | 12.63 | 12.52 | 11.98 | 11.97 | Meta history · 11.46 |
| 60s | 13.05 | 12.91 | 12.53 | 12.50 | Meta history · 11.98 |
| 120s | 13.23 | 13.00 | 12.72 | 12.69 | Meta history · 12.43 |

The heatmap predicts a 16-block X/Z tile, then a horizontal block and altitude conditional on that tile. Each fine horizontal axis has 16 bins. The altitude head is a normalized histogram on integer heights. The multi-scale loss adds the log scores of the predicted 16- and 32-block horizontal regions with weights 0.45 and 0.28. The exact voxel term has weight 1. The architecture improves on the shared motion mixture at 10–120s. The incremental exact-block gain from the multi-scale objective over exact-only training is small, about 0.01–0.02 nats per horizon, and the auxiliary loss gives mixed results on the 16-block metric.

## Within-match learning

Once per 30 seconds, the adaptive models use up to 32 completed examples for that player and horizon, taken from issues at multiples of three seconds. An example enters only after its future endpoint was recorded. The comparison below uses issue times at least ten seconds after the 55% match landmark and with at least eight completed examples. The fitted population model never uses labeled examples from the held-out games. Within those games the adaptation arms update online as outcomes arrive.

| Horizon | Matched starts | Stay | Multi-scale prior | Ordinary head tuning | ANIL prior | ANIL adapted | Fixed history | Meta history |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5s | 7,461 | 9.95 | 9.22 | 9.07 | 9.25 | 9.08 | 9.41 | 9.22 |
| 10s | 7,165 | 11.13 | 10.51 | 10.32 | 10.55 | 10.32 | 10.43 | 10.39 |
| 30s | 6,132 | 12.85 | 12.15 | 11.87 | 12.27 | 11.84 | 11.69 | 11.68 |
| 60s | 4,972 | 13.63 | 12.97 | 12.61 | 13.09 | 12.56 | 12.39 | 12.37 |
| 120s | 3,090 | 14.04 | 13.45 | 13.57 | 13.48 | 13.55 | 13.16 | 13.20 |

- **ANIL-style head adaptation.** Train on chronological support/query episodes and backpropagate through one update to the coarse spatial bias. The update smooths the gap between observed and forecast occupancy and learns a step size for each horizon. At 60s it gained 0.53 nats over the same initialization without adaptation, with gains of 0.01–1.26 across games and 29 of 40 player-match pairs improving. Ordinary head tuning had a 0.36-nat gain over its own prior at 60s. ANIL's average error rose at 120s, with two games improving and two getting worse. [Raghu et al., ANIL](https://arxiv.org/abs/1909.09157)
- **Meta history.** Use a smoothed heatmap of the player's completed outcomes and train a small gate to decide how much to trust it. The gate learns horizon-dependent weights from later query outcomes, conditioning on support count and a few current state features. At 60s it gained 0.60 nats over its own prior across the four games, improved 36 of 40 player-match pairs, and achieved 0.50 nats lower loss than the prior for query starts with only 4–11 completed examples. A fixed history blend achieved almost the same late-game 60s result. At 5s the gate learned to rely overwhelmingly on the current-state model.
- **Player example.** In match 09 at 11:23, the 60s meta-history forecast shows multiple populated regions. At 12:23 the recorder is on the eastern edge of the arena. The paired renders and interactive viewer retain the same forecast while switching the observed player positions and recorded block edits.
- **When a style persisted.** In match 12 the recorder was in the same southern tile for 83 of 156 early observations and 99 of 130 later observations. The 60s meta-history model was 4.03 nats better than its prior on that player and match. Match 10's Player 06 moved more often toward mid in the later part of the match. Its meta-history score was 0.57 nats worse than its prior, illustrating the cost of carrying an early pattern forward after it changes.

The on-map overlay is a 16-block horizontal marginal. It uses display quantization in the browser and full-precision probabilities in the mod and score calculations. The observer's local view limits player availability and terrain reconstruction. These experiments cover only this set of maps, modes and players.

## Viewer and live mod

The [private BedWars Forecast Atlas](https://bedwars-forecast-atlas.white-hinny-0472.chatgpt.site) includes 163,438 forecast starts across the four test matches and two development matches. Every available issue time supports an outcome jump, model changes, and orbit, overhead and follow views. The web version uses game-local player aliases. The live mod uses the names and scoreboard teams visible to the client.

Open `/p forecast` in Forge 1.8.9 to enable the overlay, pick any of 15 position models, enable one or more of the five horizons, and filter by player name, team color, or both. A team view averages its selected players' distributions, just as the audience view pools tracked players in the web report. Start with the overlay enabled to collect the full support history. See the [mod commands](../../practice-client/README.md#live-bedwars-forecasts).

Java and Python agreed on 300 model outputs, covering all 15 models across five horizons with empty and populated support. The largest absolute tile probability difference was 0.000000075. A separate history check matched support sequences across 19,475 replay queries. An isolated Forge 1.8.9 launch loaded the installed model format and opened the native panel with an adaptive model, three simultaneous horizons and both filters configured. A fresh online match remains to be tested. The live mod uses its own client observations, so players outside its loaded view remain unscored.

## Reproduce

The input SHA-256 is `bf345d6d7b8f5138e1aa5f706b608ac02204d563b76d91e1d945a22431ccb052`. Use the frozen manifest to preserve the benchmark. Raw recordings and player names are used only locally.

```sh
uv run --python 3.12 --with torch==2.15.0 --with numpy==2.4.6 --with scipy==1.18.1 --with scikit-learn==1.9.1 \
  python research/bedwars-forecast/heatmap.py runtime/bedwars-forecast/advanced/tracks.json.gz \
  --out runtime/bedwars-forecast/advanced --assets research/bedwars-forecast/viewer/dist/assets

uv run --with numpy python research/bedwars-forecast/extract_terrain.py \
  runtime/bedwars-forecast/advanced/tracks.json.gz --out research/bedwars-forecast/viewer/dist/assets

uv run --with numpy python research/bedwars-forecast/pack_replays.py research/bedwars-forecast/viewer/dist/assets \
  --store-originals runtime/bedwars-forecast/advanced/replays

uv run --python 3.12 --with torch==2.15.0 --with numpy==2.4.6 --with scipy==1.18.1 --with scikit-learn==1.9.1 \
  python research/bedwars-forecast/portable.py runtime/bedwars-forecast/advanced --refresh-fixtures
```

To preview locally, serve `research/bedwars-forecast/viewer/dist` from the project folder with `python3 -m http.server 8784 --directory research/bedwars-forecast/viewer/dist` and open `http://localhost:8784/`.
