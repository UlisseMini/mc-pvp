# Click rate versus otherwise identical one-tick W-tap

Across the 16 higher-rate games, the higher-CPS bot finished ahead in fresh hits in **16/16**. Each rate has four games, two from each starting side, at **20/20 ms added RTT**. This establishes a repeatable advantage in this small cohort; it does not establish the best rate across other pings or opponents.

The comparison changes only the physical click ceiling. Every bot has the same one-tick post-hit W release, perfect local-view aim and zero artificial observation delay. The release is deliberately scheduled 150 ms after the visible landed cue; that planned reset delay is distinct from a reaction restriction.

## Results

Counts below are fresh native hits at the existing **first-to-100 accepted-attacks** stopping point. An accepted stronger damage upgrade during immunity increments the referee scoreboard without being a fresh hit. We retain the stopping rule to isolate CPS, count upgrades separately, and do not relabel these games as first-to-100-fresh races.

| Configured CPS vs 10 | Measured CPS, mean | Fresh hits across four games, higher rate first | Pooled fresh ratio | Fresh leads | Accepted-score wins |
|---|---:|---|---:|---:|---:|
| 15 | 14.80 | 85–79, 88–86, 93–80, 86–73 | 1.107 | 4/4 | 3/4 |
| 20 | 19.63 | 86–83, 91–59, 82–62, 85–79 | 1.216 | 4/4 | 2/4 |
| 25 | 24.40 | 91–75, 92–82, 85–64, 86–77 | 1.188 | 4/4 | 4/4 |
| 30 | 29.16 | 92–76, 90–77, 82–77, 80–63 | 1.174 | 4/4 | 3/4 |

**A large single-game margin is not enough to identify a CPS effect:** the separate,
qualified recovery control between identical 10-CPS bots finished **94–68 fresh**
(`m0508`). That makes the 16/16 direction across side-balanced comparisons more
informative than the best individual game. It also cautions against ranking the
higher rates by these four-game averages. The exact cause of the control's
asymmetry has not been isolated; it should not be assigned to one timing mechanism
without examining its native trace.

The passing 10-vs-10 control was `m0479`: 92–98 fresh (93–100 accepted). Starting phases and operating-system/network scheduling still vary; side swapping does not create identical trajectories.

**The 91–59 game was `m0489`: 20 CPS one-tick W-tap versus 10 CPS one-tick W-tap**, with the higher-rate bot in side B. Both had 20 ms added RTT. Its accepted score was 100–74: 91 fresh + 9 upgrades against 59 fresh + 15 upgrades. That is about 54% more fresh hits. [Open its offline replay](../recordings/m0489.html).

## Native delivery and mechanism evidence

| Configured CPS | Native ticks with clicks/second, higher / 10 | Backward drag contacts/second, higher / 10 | Forward drag contacts/second, higher / 10 |
|---|---:|---:|---:|
| 15 | 14.80 / 9.91 | 2.18 / 1.18 | 2.21 / 0.83 |
| 20 | 19.61 / 9.91 | 2.93 / 1.27 | 3.44 / 0.69 |
| 25 | 19.99 / 9.91 | 3.05 / 1.24 | 3.35 / 0.83 |
| 30 | 20.00 / 9.91 | 3.07 / 1.45 | 3.49 / 0.79 |

Higher input rates cover more native input ticks and create more opportunities to brake recoil, while also creating more forward contact drag. Above 20 CPS, the ordinary input loop can consume multiple queued clicks in the same movement tick. The game still advances at 20 movement updates per second. Same-tick clicks can still affect contact/sprint state and server packet processing, so these observations do not prove that every click beyond 20 CPS is redundant.

These contact counts are observed mechanisms, not a measurement of net displacement or a causal decomposition of the score improvement. The current experiment isolates CPS as a policy parameter but does not separately identify the contributions of tick coverage, recoil cancellation, forward drag, sprint consumption and critical upgrades. Four games per rate cannot reliably rank close rates or establish an optimal ceiling.

## Every qualified original game

Both counts put the configured higher rate first; the control rows put the scheduled reference side first.

| Game | Configured CPS | Repeat | Higher-rate side | Fresh | Upgrades | Accepted score |
|---|---:|---:|---|---:|---:|---:|
| m0479 | 10 | 1 | A | 92–98 | 1–2 | 93–100 |
| m0478 | 15 | 1 | A | 85–79 | 15–16 | 100–95 |
| m0493 | 15 | 2 | B | 88–86 | 8–14 | 96–100 |
| m0494 | 15 | 3 | A | 93–80 | 7–15 | 100–95 |
| m0506 | 15 | 4 | B | 86–73 | 14–11 | 100–84 |
| m0482 | 20 | 1 | A | 86–83 | 6–17 | 92–100 |
| m0489 | 20 | 2 | B | 91–59 | 9–15 | 100–74 |
| m0497 | 20 | 3 | A | 82–62 | 18–15 | 100–77 |
| m0503 | 20 | 4 | B | 85–79 | 10–21 | 95–100 |
| m0481 | 25 | 1 | A | 91–75 | 9–20 | 100–95 |
| m0488 | 25 | 2 | B | 92–82 | 8–17 | 100–99 |
| m0496 | 25 | 3 | A | 85–64 | 15–17 | 100–81 |
| m0502 | 25 | 4 | B | 86–77 | 14–10 | 100–87 |
| m0486 | 30 | 1 | A | 92–76 | 8–20 | 100–96 |
| m0485 | 30 | 2 | B | 90–77 | 10–20 | 100–97 |
| m0499 | 30 | 3 | A | 82–77 | 10–23 | 92–100 |
| m0500 | 30 | 4 | B | 80–63 | 20–17 | 100–80 |

## Excluded control and recovery check

The final original 10-vs-10 control, **`m0505`**, reached 98–100 accepted attacks (91–96 fresh, scheduled reference side first), but failed the predeclared maximum tick-gap gate. Its server had a **1,475 ms** gap; client maximum gaps were **1,373 and 1,436 ms**. It remains in the original cohort and archived traces, and is explicitly excluded from qualified performance totals. The original cohort status remains `incomplete` with its recorded quality-gate error; no result was deleted or replaced.

All 16 higher-rate games passed their gates. In particular, the overlapping last 15-CPS game `m0506` had already finished and its maximum server gap was 72 ms. The failed control shows that this laptop workload can occasionally stall; passing ordinary p95 timing alone is insufficient. Concurrent postprocessing is a possible contributor, not a demonstrated cause.

Exactly one separate [recovery control](cps-wtap-control-v1.json) was registered after the failure, with the same policies and 20/20 RTT and no concurrent benchmark. `m0508` passed the original gates: 94–68 fresh, 100–71 accepted. Maximum server gap: 57.0 ms; maximum client gaps: 55.6/56.0 ms. This follow-up is reported separately, not pooled as a replacement for the failed control.


## Method and checks

- Native Minecraft Java 1.8.9 client/server, normal speed, diamond swords, flat verified full-block ground; no potions, jumping, blocking or strafing.
- Same `momentum` controller with `click_mode=fixed`, `reaction_ms=0`, `pause_ticks=1`, `pause_ms=50`, `reset_delay_ms=150`. Only `click_cps` and the descriptive label differ. The runner asserts this before launching games.
- Physical inputs use normal keybinding consumption and vanilla reach/attack processing, including misses and immune-frame contacts. Minimum inter-input spacing is the ceiling of one second divided by configured CPS, paced from completed input with no catch-up injections. Other policies retain their own 10 CPS default.
- Four games per rate, two per side; rate order reverses between repeats. Two 10-vs-10 controls bracket the cohort. Two benchmark matches run concurrently. Human duels sometimes overlap and are excluded from the cohort; cadence gates apply to every included benchmark game.
- Policies, loaded agent hash, source hashes, fixtures, ping, frame setting and acceptance gates were frozen before the full cohort. All 18 full games reached their declared accepted-score stopping rule. Seventeen passed native input, reach, replication and cadence checks; the failed equal-rate control is disclosed above.
- Among the 17 qualified original games, worst client movement p95: **51.520 ms**; worst client movement gap: **184.650 ms**; worst server p95: **55.0 ms**. Every qualified side delivered at least **97.1%** of its configured physical ceiling.
- The existing HeadlessMC display-sleep setting was 1 ms for both sides, instead of its 10 ms default. This increases input-poll frequency without modifying Minecraft movement ticks. No new dependency, input thread or direct attack-packet route was added.

Before the cohort, [smoke v1](cps-wtap-smoke-v1.json) at the default display throttle measured about 13.51, 18.60, 21.16 and 24.83 CPS for ceilings 15, 20, 25 and 30. Those were delivery checks, not performance samples. Reducing display sleep equally for both bots produced approximately 14.78, 19.47, 24.38 and 29.12 CPS in [smoke v2](cps-wtap-smoke-v2.json). The full cohort then used the stricter predeclared 90% delivery floor. Both smoke cohorts remain archived and are excluded from the full-game results.

## Play, inspect and reproduce

The running local server is `127.0.0.1:25565`. Use `/duel wtap-cps20 20 20`, or substitute `wtap-cps10`, `wtap-cps15`, `wtap-cps25` or `wtap-cps30`. The two numbers are bot added RTT and player added RTT. `/matches` and `/watch` expose live matches.

The current server already uses the 1 ms headless display setting. On a later fresh start, use the command below to reproduce the measured high-rate delivery; the ordinary launcher retains its 10 ms default. Do not launch a second server or restart an active human duel.

```sh
python3 lab.py serve --frame-sleep-ms 1
# In another terminal, after the lab is ready:
python3 cps_science.py another-cps-comparison
python3 cps_summary.py research/another-cps-comparison.json research/another-cps-summary.json
```

- [Frozen cohort and all per-game checks](cps-wtap-comparison-v1.json).
- [Machine-readable aggregate and normalized per-game results](cps-wtap-summary-v1.json).
- [Native trace hash manifest](trace-manifest.json); each included game has archived client A, client B and server JSONL streams in `traces/`.
- Additional offline trajectory replays: [25 CPS `m0481`](../recordings/m0481.html), [20 CPS `m0482`](../recordings/m0482.html), [30 CPS `m0486`](../recordings/m0486.html). These are data viewers, not rendered Minecraft video. Fresh counts and upgrades are shown separately from the accepted-score graph.
