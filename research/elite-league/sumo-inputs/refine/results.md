# Continuing PPO on its weakest development matchups

The common parent was full32-66047 (sumo-input-devbest). Its three weakest distinct development opponents were v4-sprint-link-jitter-15, v4-ppo-g0496, v4-reply-g0312. The focused arm spent half of its draws on these three and half on the original uniform field. The control trained uniformly for the same extra 384 updates. Each arm used three paired seeds. Both policies saw the same link draws, clocks, seat and action seeds.

## Four new simulator clocks

Every cell is a points percentage with half credit for draws. Each actor played 320 fights across ten opponents.

| Seed | Uniform | Focused | Focused change (points) | Parent | Plain W-tap |
|---|---:|---:|---:|---:|---:|
| 4711 | 74.5% | 69.7% | -4.8 | 74.5% | 25.6% |
| 5821 | 70.5% | 76.2% | +5.8 | 74.5% | 25.6% |
| 6911 | 73.4% | 75.3% | +1.9 | 74.5% | 25.6% |

## Native confirmation

Each matchup covers both seats and both asymmetric RTT pairs. Records are wins–losses–draws.

| Policy | vs W-tap | vs v4-sprint-link-jitter-15 | A / B | 31/109 / 109/31 | Plain W-tap overall |
|---|---:|---:|---:|---:|---:|
| sumo-input-uniform | 4–0–0 | 3–1–0 | 3–1–0 / 4–0–0 | 4–0–0 / 3–1–0 | 2–6–0 |
| sumo-input-devbest | 4–0–0 | 2–2–0 | 3–1–0 / 3–1–0 | 4–0–0 / 2–2–0 | 2–6–0 |
| v4-ppo-g0496 | 3–1–0 | 2–2–0 | 3–1–0 / 2–2–0 | 4–0–0 / 1–3–0 | 2–6–0 |
| sumo-input-focused | 4–0–0 | 1–3–0 | 2–2–0 / 3–1–0 | 2–2–0 / 3–1–0 | 2–6–0 |
| research-wtap20 | 2–2–0 | 0–4–0 | 1–3–0 / 1–3–0 | 1–3–0 / 1–3–0 | 2–6–0 |

The observed native bracket winner was sumo-input-uniform. Its permanent alias is sumo-input-refinement. The uniform and focused entrants are available as sumo-input-uniform and sumo-input-focused. Selection used development scores. The named hard opponents and validation margin were frozen before comparison.
