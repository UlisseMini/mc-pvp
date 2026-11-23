# Research Projects & Methods

This folder contains the standalone research projects built on top of the Minecraft PvP Lab. Each project has its own code, evaluation protocol, and write-up.

## Active Projects

| Project | Focus | Write-Up |
| --- | --- | --- |
| **[`bow-aim/`](bow-aim/)** | Multi-mode neural movement prediction, 3D terrain voxels, void-KB expected value, and snowball/egg volley aiming trained on 1.23M real Hypixel ticks. | [Neural Bow Aim results](bow-aim/neural/RESULTS.md) · [Baseline & native Forge tests](bow-aim/RESULTS.md) |
| **[`bow-duels/`](bow-duels/)** | AVX2 Rust-accelerated (105k ticks/s) 1v1 bow duel simulator and adversarial self-play RL league with verified 1.8.9 physics parity. | [Bow Duels league](bow-duels/README.md) |
| **[`bedwars-forecast/`](bedwars-forecast/)** | 3D player location forecasting (5s–120s horizons) with online per-player habit adaptation and volumetric visualization. | [BedWars location forecasts](bedwars-forecast/README.md) · [Within-match adaptation](bedwars-forecast/context-study/RESULTS.md) |
| **[`elite-league/`](elite-league/)** | 1.8.9 Sumo and Boxing RL/search leagues, matched-cap 7-CPS evaluations, and boxing trade-skill studies. | [Classic league overview](elite-league/README.md) · [Performance & limitations](elite-league/RESULTS.md) |
| **[`sword26/`](sword26/)** | Modern Minecraft (Java 26.3) self-play sword league, flat-court JVM simulator, and ordinary-Java native promotion ladder. | [Modern sword lab](sword26/README.md) · [League log](sword26/LEAGUE.md) · [Simulator parity](sword26/SIMULATOR.md) |
| **[`mmc-arena/`](mmc-arena/)** | Block-exact reconstruction of 28 Minemen Club sumo arenas (9 floor outlines) from packet captures, verified against vanilla `Entity.moveEntity`. | [Measured MMC platforms](mmc-arena/README.md) |
| **[`sumo-rl/`](sumo-rl/)** | Fast 1.8.9 sumo environment, recorded timing calendars, and human-duel grading tools. | [`sumo-rl/`](sumo-rl/) |
| **[`sumo-replay-analysis/`](sumo-replay-analysis/)** | Three-POV replay diagnosis and counterfactual branching for sumo knockback braking and floor reach. | [Replay analysis tools](sumo-replay-analysis/README.md) |

## Shared Principles

1. **Separate physics parity from policy search.** Conditional single-step checks validate individual equations; uncorrected multi-second rollouts verify that equations compose without drift. See the [formal 1.8.9 combat model](../docs/FORMAL-MODEL.md).
2. **Matched baselines everywhere.** Every melee policy is compared against plain **W-tap** under identical reach, RTT, CPS ceiling, aim settings, and win conditions.
3. **Held-out splits.** Training captures/calendars are separated from held-out validation and sealed test matches before evaluation.
4. **Own-client observations only.** Live policies act strictly from their own client's view; multi-POV server/opponent traces are used offline for analysis and verification.

## Working Brief & Historical Notes

- **[Current working research brief](combo-research-brief.md)** — rolling log of objectives, constraints, and methodological notes for boxing and sumo studies.
- **[`archive-notes/`](archive-notes/)** — preserved markdown reports from earlier October–November 2026 physics, click-rate, burst-clicking, blockhit, and exact-travel studies (heavy raw `.jsonl.gz`/`.json` trace dumps were pruned from git history to keep the repository lightweight).
