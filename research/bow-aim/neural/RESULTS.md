# End-to-End Offline Neural Bow Aim (`BowNeuralNet` + `9×7×9` Voxels + Optimal-Stopping Auto-Release)

## Data & Airtight Split (Pure Offline Human Gameplay — Zero Sim-to-Real)
Extracted from all local Hypixel captures (`research/bow-aim/neural/extract_rich.py`): **1,228,783 remote-player ticks** across **42 full games** (12 BedWars + 30 SkyWars) with exact causal `9×7×9` solid-block patches (reconstructed strictly from prior Protocol 47 chunk/block packets), head yaw/pitch, sneaking, held item class, hurt/KB, and line-of-sight.

- **Train:** 29 matches (7 BedWars `bw-01..07` + 22 SkyWars, `642,618` ticks → `312,569` training examples @ stride 2 + 2× mirror symmetry + 15% terrain dropout)
- **Validation (calibration & model selection only):** 4 matches (`bw-08` + 3 SkyWars, `119,283` ticks)
- **Sealed Test (never seen during training, tuning, or calibration):** 9 matches (`bw-09..12` + 5 SkyWars, `466,882` ticks)

## Architecture & Decision-Theoretic Solver
- **Model:** 233,490-parameter PyTorch network (`912 KB` binary `bow-neural.bin`) with 3-layer 1D Conv over 24-tick raw history, 22-dim velocity/autocorrelation context, sparse binary `9×7×9` ego-cardinal solid-voxel encoder, residual fusion trunk, and **`K = 6` coherent trajectory modes** `(π_k, μ_k(t), σ_k(t))` over horizons `{3, 6, 10, 15, 22, 30, 42}`.
- **Aim Solver:** Evaluates exact 1.8.9 `BowBallistics.intercept` for every mode + mean path across body heights `{0.50, 0.76, 0.88, 0.32}`, rejects terrain-blocked arcs, and picks the `(yaw, pitch)` maximizing analytic expected hit probability across all `K = 6` modes + native arrow spread.
- **Exact Python ↔ Java 8 Parity:** `max_w = 3.48e-7`, `max_pos = 1.14e-5`, `max_prob = 6.48e-7`, `release_mismatch = 0`, latency `0.66–0.99 ms/target`.

## Sealed Held-Out Benchmark Results

### 1. Sealed BedWars (`game-09..12`, 184,564 shots)
| Model | 12b | 24b | 38b | 54b | **Total** |
| --- | ---: | ---: | ---: | ---: | ---: |
| `linear` | 92.89% | 69.16% | 52.14% | 39.07% | 63.32% |
| `shared` (old ridge) | 96.68% | 76.63% | 58.83% | 44.74% | 69.22% |
| `neural_mm_novox` (no 9×7×9 voxels) | 98.13% | 84.93% | 67.04% | 51.42% | 75.38% |
| `neural_mean` (voxels, single mean path) | 99.01% | 89.29% | 71.73% | 52.84% | 78.22% |
| **`neural_mm` (K=6 multi-mode + 9×7×9 voxels)** | **98.86%** | **88.80%** | **72.74%** | **56.16%** | **79.14% (+9.92%)** |

### 2. Sealed SkyWars (5 unseen matches, 22,816 shots)
| Model | 12b | 24b | 38b | 54b | **Total** |
| --- | ---: | ---: | ---: | ---: | ---: |
| `linear` | 92.65% | 69.72% | 53.31% | 39.83% | 63.88% |
| `shared` (old ridge) | 96.28% | 77.03% | 59.12% | 44.97% | 69.35% |
| `neural_mm_novox` | 98.21% | 84.05% | 66.25% | 50.72% | 74.81% |
| `neural_mean` | 98.93% | 86.99% | 67.93% | 47.70% | 75.39% |
| **`neural_mm` (K=6 multi-mode + 9×7×9 voxels)** | **98.98%** | **86.76%** | **69.16%** | **52.23%** | **76.78% (+7.43%)** |

### 3. Realistic Live-POV Terrain-Occluded Shots (Sealed Test, 4,296 engagements)
- `linear`: **43.97%** (`1,889 / 4,296`)
- `shared`: **47.39%** (`2,036 / 4,296`)
- **`neural_mm`:** **56.49%** (`2,427 / 4,296`, **+9.10 percentage points**)

### 4. Held-Out `DodgeArena` (BedWars voxel maps 10–11)
| Difficulty | `linear` | `adaptive+cycle` | **`neural+voxels`** |
| --- | ---: | ---: | ---: |
| `rhythm` | 30.5% | 81.1% | **77.2%** |
| `feints` | 40.7% | 64.0% | **66.2%** |
| `reactive` | 29.4% | 50.0% | **54.4%** |

### 5. Calibration & Principled Optimal-Stopping Auto-Release (`smart_peak`, 5,886 3s windows)
- Sealed-test calibration Brier: **`0.1184`** (mean predicted `78.5%` vs actual `78.9%`).
- **`/p bowaim release smart`** (default when `/p bowaim autorelease on`): fires on layup (`>= threshold`), relative predictability spike peak (`p` surges over engagement EMA then turns over / plateaus 3 ticks), or patience-decayed relaxation after 1.5s.

| Policy | Fire% (3s) | Hit \| Fire | **Hits / Window** | Avg Wait | Low-Base (<45%) Fire% | **Low-Base Hit \| Fire** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `immediate` | 100.0% | 79.14% | 79.14% | 0.0t | 100.0% | 36.46% |
| `thresh_75` | 92.9% | 86.38% | 80.24% | 3.5t | 53.2% | 72.38% |
| `thresh_50` | 99.6% | 80.98% | 80.65% | 0.8t | 94.2% | 58.60% |
| **`smart_peak`** | **99.7%** | **82.79%** | **82.57%** | **3.2t** | **99.5%** | **54.20% (+17.74%)** |
