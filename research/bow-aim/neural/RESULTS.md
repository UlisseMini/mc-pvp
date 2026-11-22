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

---

## BowNeural v2 (`BNN2`: 48-tick History + Relational Threats + `serverPos` + Fine/Coarse Voxels + Correlated `ρ_xz` + Hit-Rate Fine-Tuning + Continuous Refinement + Own-Ping Lead)

- **Model (`502,380` floats, `1.96 MB`):** 4-layer strided Conv1d over 48 ticks × 26 channels (smoothed pos, velocity, **unsmoothed `serverPos/32 - pos` packet lead**, rotation, flags, and 8 nearest-player/ranged-threat relational channels) + 30-dim per-player style/periodicity summary + multi-scale 3D voxels (**fine `9×7×9` `±4b` + coarse `11×5×11` `±11b`**) + 2-block residual trunk + `K = 6` correlated horizontal modes `(σ_x, σ_z, ρ_xz)`.
- **Two-Stage Training:** Stage 1 correlated bivariate Gaussian Mixture NLL (24 epochs, including simulated-remote self-tracks in train only) → Stage 2 direct differentiable expected-hit-probability fine-tuning (6 epochs, **won validation selection: `72.77%` vs `72.69%`**).
- **Exact Python ↔ Java 8 v2 Parity:** `max_w = 1.93e-7`, `max_pos = 1.05e-5`, `max_std = 8.00e-6`, `max_rho = 1.43e-6`, `max_prob = 7.19e-7`, live latency **`1.49 ms/target`**.
- **Sealed Calibration Brier:** `0.1333` (mean predicted **`76.4%`** vs actual **`76.2%`**).

### Sealed Held-Out Results Against True Unsmoothed `serverPos`
| Benchmark (True `serverPos` Target) | `linear` | `shared` (old ridge) | `v2_novox` | `v2_norel` | `v2_discrete` | **`v2_refined`** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **BedWars `game-09..12` (0 ms RTT, 92,328 shots)** | 59.73% | 64.49% | 72.96% | 76.49% | 76.57% | **76.63% (+12.14%)** |
| **BedWars `game-09..12` (100 ms RTT = 2t lag, 61,392 shots)** | 54.46% | 57.93% | 68.33% | 72.06% | **72.24%** | **72.12% (+14.19%)** |
| **SkyWars (5 unseen games, 0 ms RTT, 15,200 shots)** | 59.53% | 64.38% | 72.38% | 73.45% | 73.41% | **73.61% (+9.23%)** |
| **Live-POV Terrain-Occluded (4,296 shots)** | 41.15% | 44.16% | — | — | — | **54.45% (+10.29%)** |

### User's 202 Actual Real Hypixel Bow Shots (`analyze_real_shots.py`)
155 full-draw (`>=20t`) + 47 partial-draw (`<20t`) real bow releases extracted across all 13 Hypixel capture sessions:

| Arm | Short `<16b` (53) | Mid `16–32b` (90) | Long `32b+` (59) | **Overall (202 shots)** |
| --- | ---: | ---: | ---: | ---: |
| `user_raw` (unassisted aim) | 52.6% | 31.2% | 14.6% | **31.99%** |
| `linear` | 72.2% | 48.2% | 33.5% | **50.19%** |
| `shared` (old ridge) | 82.5% | 50.0% | 39.3% | **55.41%** |
| `v2_nudge3` (3° default cap) | 74.5% | 54.4% | 23.9% | **50.77%** |
| `v2_nudge8` (8° cap) | 86.9% | 71.7% | 36.9% | **65.50%** |
| `v2_full_0ms` | 92.6% | 73.8% | **50.7%** | **71.97%** |
| **`v2_full_60ms` (60 ms RTT lead)** | **92.7%** | **75.6%** | 49.3% | **72.37% (+16.96% vs ridge)** |

**Failure-Mode Breakdown of v2's 43 Real-Shot Misses:**
- `lateral_curve_or_strafe`: 14 (32.6%)
- `partial_draw_slow_arrow`: 9 (20.9%)
- `vertical_jump_or_drop`: 7 (16.3%)
- `midflight_reversal_or_dodge`: 5 (11.6%)
- `target_took_kb_midflight`: 4 (9.3%)
- `near_miss_spread`: 2 (4.7%)
- `sudden_stop` / `sudden_sprint`: 2 (4.6%)

---

## BowAim v3 Tactical Projectile Engine (Void-KB EV + Snowball/Egg Volley Coverage + Multi-Mode Ghosts + Learned `V_wait` Head)

- **Exact 1.8.9 Void-KB Scorer (`VoidKbValue.java`):** 14-tick 1.8.9 `knockBack` + Punch enchantment + gravity + solid wall/floor collision + conservative counter-strafe + 18-block void scan (unloaded chunks conservatively solid). Hit-Equivalent values: Bow `1.0×` normal / `8.0×` void kill (`5.0×` lethal HP finish); Snowball/Egg `0.4×` normal / `10.0×` void kill.
- **Learned Continuation Value Head (`BowWaitModel.java`, `bow-wait.bin`, `109 KB`):** 2-round Causal Longstaff-Schwartz Fitted Value Iteration (FVI) over frozen `BowNeuralNetV2` 224-dim trunk + 10 shooter-relative tactical features (`234 → 96 → 48 → 1 + Softplus`, trained on 671 windows / `79,178` mirrored samples; validation-tuned `fireAlpha = 0.75`). Eliminated Round 0 hindsight inflation (`mean_y: 2.288 → 1.590`).
- **Exact Python ↔ Java 8 v3 Parity (`check_v3_parity.py`):**
  - `VoidKbValue` (40 bridge/edge/wall/Punch cases): **`0` void-kill mismatches**, `max_drop_err = 2.89e-15`, `max_val_err = 0.00`.
  - Throwable ballistics + Volley Coverage + `BowWaitModel` (24 real sealed tracks): `max_t = 8.02e-6`, `max_cov = 4.62e-6`, `max_V_wait = 2.57e-6` (`0.13 ms` cold).

### 1. Sealed Bow Tactical Benchmark (495 unseen BedWars `bw-09..12` + 5 SkyWars 3.0s windows)
| Arm | Fire% | Hit% | **EV / Arrow** | **EV / Window** | **Void Kills / 10** | **VK / 10 (Void-Opp)** | Avg Hold |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `v2_myopic_immediate` | 100.0% | 85.1% | 1.401× | 1.401× | 0.79 | 2.46 | 0.8t |
| `v2_myopic_smart` | 100.0% | 85.0% | 1.414× | 1.414× | 0.81 | 2.52 | 1.9t |
| `v3_ev_immediate` | 100.0% | 84.8% | 1.404× | 1.404× | 0.80 | 2.49 | 0.8t |
| **`v3_ev_vwait` (Full v3)** | **99.8%** | **84.7%** | **1.464×** | **1.461×** | **0.88 (+11.4%)** | **2.75 (+11.8%)** | **1.5t** |

- **SkyWars (275 windows):** `v2_myopic_smart` `1.539×` (`1.06` VK/10) → **`v3_ev_vwait` `1.621×` (`1.18` VK/10, `+11.3%`)**.
- **BedWars (220 windows):** `v2_myopic_smart` `1.258×` (`0.48` VK/10) → **`v3_ev_vwait` `1.267×` (`0.50` VK/10)**.

### 2. Sealed Snowball / Egg Volley Benchmark (Unseen BedWars + SkyWars, Range 10–26b)
| Burst / Arm | Joint Hit% | **Joint Void-Kill%** | **Burst EV** |
| --- | ---: | ---: | ---: |
| **3-Throw Full Sealed (`n = 14,344`)** `linear` | 72.08% | 5.65% | 0.831× |
| **3-Throw Full Sealed (`n = 14,344`)** `v2_single` | 92.37% | 8.34% | 1.170× |
| **3-Throw Full Sealed (`n = 14,344`)** **`v3_ev_volley`** | **92.08% (+20.0%)** | **8.47% (+50% vs linear)** | **1.182×** |
| **10-Throw Sustained — All (`n = 517`)** `linear` | 83.93% | 14.23% | 1.702× |
| **10-Throw Sustained — All (`n = 517`)** `v2_single` | 96.94% | 20.79% | 2.383× |
| **10-Throw Sustained — All (`n = 517`)** `v3_ev_novolley` | 96.83% | **21.97%** | **2.496×** |
| **10-Throw Sustained — All (`n = 517`)** **`v3_ev_volley`** | **96.84%** | **21.77% (+53% vs linear)** | **2.477×** |
| **10-Throw Sustained — Void-Exposed (`n = 125`)** `linear` | 88.54% | 58.84% | 6.003× |
| **10-Throw Sustained — Void-Exposed (`n = 125`)** `v2_single` | 98.60% | 85.96% | 8.647× |
| **10-Throw Sustained — Void-Exposed (`n = 125`)** `v3_ev_novolley` | 98.95% | **90.84% (+4.88pp vs v2)** | **9.116×** |
| **10-Throw Sustained — Void-Exposed (`n = 125`)** **`v3_ev_volley`** | **99.70%** | **90.02% (+31.2pp vs linear)** | **9.041×** |
