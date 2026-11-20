# MMC sumo input and capacity experiment

Three matched training seeds per variant. Each learner started from v4-ppo-g0496, ran 320 PPO updates of 32 fights, and retained the checkpoint with the best development score. Every run saw the same scenarios and exploration seeds as the other variants of that seed. The 32-unit actor starts with the same logits as the eight-unit actor before any feature removal. Opponent ping was hidden for both widths.

All outcomes below use half credit for draws. Records are wins–losses–draws. Prospective evaluation used six new native timing calendars, ten opponents, both seats, and four independently specified ping pairs. Each trained checkpoint played 480 model bouts. The ten opponents include both previous ping policies, the incumbent, W-tap and shift-check-v4-4. The native screen used the median development seed of each variant.

## Retraining each variant

The change is in percentage points against base8 on the same bouts in each training seed. The W-tap and shift columns pool all three seeds. The last column is the plain W-tap field score on the identical panel.

| Variant | Field score | Change for seeds 7181 / 29137 / 66047 | vs W-tap | vs shift-check | W-tap field score |
|---|---:|---:|---:|---:|---:|
| base8 | 69.8% | +0.0 / +0.0 / +0.0 | 135–9–0 | 72–64–8 | 24.7% |
| blind8 | 68.6% | +1.6 / -3.4 / -1.8 | 136–7–1 | 63–67–14 | 24.7% |
| noself8 | 53.9% | -13.9 / -19.8 / -14.2 | 136–8–0 | 30–110–4 | 24.7% |
| nofoe8 | 65.3% | -3.5 / -5.0 / -4.8 | 141–3–0 | 67–77–0 | 24.7% |
| nohurt8 | 62.3% | -14.0 / -3.2 / -5.4 | 138–5–1 | 46–77–21 | 24.7% |
| wide32 | 66.2% | -0.4 / -9.3 / -0.9 | 140–4–0 | 63–70–11 | 24.7% |
| motion8 | 66.6% | -3.0 / -5.1 / -1.4 | 136–8–0 | 60–73–11 | 24.7% |
| edge8 | 70.8% | +2.9 / -3.2 / +3.2 | 138–6–0 | 76–64–4 | 24.7% |
| timing8 | 68.1% | -0.4 / -2.9 / -1.7 | 137–7–0 | 68–69–7 | 24.7% |
| full8 | 67.8% | -3.5 / -1.5 / -1.0 | 135–9–0 | 65–75–4 | 24.7% |
| full32 | 69.8% | -0.3 / -0.7 / +1.1 | 139–5–0 | 68–73–3 | 24.7% |

base8 uses the original inputs; blind8 fixes own ping at 80 ms. noself8 removes own XYZ velocity, nofoe8 removes immediate visible opponent XYZ displacement, and nohurt8 removes the two hurt-cue ages and their difference. motion8 adds a wider velocity encoding, a time-normalized smoothed XYZ opponent estimate, and elapsed time since visible movement. edge8 adds point-to-edge distances. timing8 adds reach margin and physical click age. full combines the three additions. wide32 has the original inputs. Group effects do not identify an individual channel inside a group.

## Is the incumbent helped by reading its own ping?

With identical incumbent weights and physical links, the accurate reading earned 338/480 points and a constant 80 ms reading earned 319.5/480. Altering only that feature changed 87 outcomes. The retrained blind8 comparison above tests whether learning can compensate.

## Native screen

Each foe has one bout per combination of seats A/B and added RTT 31/109 or 109/31 ms. Seat and link totals combine the opponents.

| Actor | vs research-wtap20 | vs shift-check-v4-4 | Seat A / B | RTT 31/109 / 109/31 | W-tap overall |
|---|---:|---:|---:|---:|---:|
| sumo-input-edge8 | 4–0–0 | 4–0–0 | 4–0–0 / 4–0–0 | 4–0–0 / 4–0–0 | 4–4–0 |
| sumo-input-full8 | 4–0–0 | 3–1–0 | 4–0–0 / 3–1–0 | 4–0–0 / 3–1–0 | 4–4–0 |
| sumo-two-ping | 4–0–0 | 3–1–0 | 4–0–0 / 3–1–0 | 4–0–0 / 3–1–0 | 4–4–0 |
| sumo-input-base8 | 4–0–0 | 2–1–1 | 2–1–1 / 4–0–0 | 3–0–1 / 3–1–0 | 4–4–0 |
| sumo-input-wide32 | 3–1–0 | 2–1–1 | 2–1–1 / 3–1–0 | 3–0–1 / 2–2–0 | 4–4–0 |
| sumo-input-nofoe8 | 4–0–0 | 2–2–0 | 3–1–0 / 3–1–0 | 2–2–0 / 4–0–0 | 4–4–0 |
| v4-ppo-g0496 | 4–0–0 | 1–1–2 | 3–0–1 / 2–1–1 | 2–0–2 / 3–1–0 | 4–4–0 |
| sumo-input-nohurt8 | 4–0–0 | 0–1–3 | 2–0–2 / 2–1–1 | 2–0–2 / 2–1–1 | 4–4–0 |
| sumo-input-inc-blind | 3–1–0 | 1–2–1 | 2–1–1 / 2–2–0 | 1–2–1 / 3–1–0 | 4–4–0 |
| sumo-input-blind8 | 4–0–0 | 1–3–0 | 3–1–0 / 2–2–0 | 3–1–0 / 2–2–0 | 4–4–0 |
| sumo-input-devbest | 4–0–0 | 1–3–0 | 3–1–0 / 2–2–0 | 3–1–0 / 2–2–0 | 4–4–0 |
| sumo-input-full32 | 4–0–0 | 1–3–0 | 3–1–0 / 2–2–0 | 3–1–0 / 2–2–0 | 4–4–0 |
| sumo-input-noself8 | 4–0–0 | 1–3–0 | 3–1–0 / 2–2–0 | 3–1–0 / 2–2–0 | 4–4–0 |
| sumo-input-timing8 | 4–0–0 | 1–3–0 | 2–2–0 / 3–1–0 | 3–1–0 / 2–2–0 | 4–4–0 |
| sumo-input-motion8 | 3–0–1 | 1–3–0 | 2–2–0 / 2–1–1 | 1–2–1 / 3–1–0 | 4–4–0 |
| research-wtap20 | 1–3–0 | 3–1–0 | 2–2–0 / 2–2–0 | 1–3–0 / 3–1–0 | 4–4–0 |

## Independent native confirmation

Each foe has one bout per combination of seats A/B and added RTT 31/109 or 109/31 ms. Seat and link totals combine the opponents.

| Actor | vs research-wtap20 | vs shift-check-v4-4 | vs v4-reply-g0312 | Seat A / B | RTT 31/109 / 109/31 | W-tap overall |
|---|---:|---:|---:|---:|---:|---:|
| sumo-two-ping | 4–0–0 | 2–2–0 | 3–1–0 | 4–2–0 / 5–1–0 | 4–2–0 / 5–1–0 | 1–11–0 |
| v4-ppo-g0496 | 3–1–0 | 2–0–2 | 1–3–0 | 3–2–1 / 3–2–1 | 1–3–2 / 5–1–0 | 1–11–0 |
| sumo-input-edge8 | 4–0–0 | 0–4–0 | 3–1–0 | 3–3–0 / 4–2–0 | 4–2–0 / 3–3–0 | 1–11–0 |
| research-wtap20 | 1–3–0 | 0–4–0 | 0–4–0 | 0–6–0 / 1–5–0 | 0–6–0 / 1–5–0 | 1–11–0 |

## Turning off a group in the frozen 32-unit actor

All trainable weights and physical delays are held fixed. A positive change means removing the input helped. Native records combine W-tap and shift-check; all retained base and W-tap controls came from the original matched screen.

| Feature removed | Model change by seed (points) | Model outcomes changed / 1,440 | Native vs W-tap | Native vs shift-check | Plain W-tap vs same foes |
|---|---:|---:|---:|---:|---|
| intact | +0.0 / +0.0 / +0.0 | 0 | 4–0–0 | 1–3–0 | 1–3–0 / 3–1–0 |
| motion | -1.6 / +2.0 / -2.5 | 367 | 4–0–0 | 1–3–0 | 1–3–0 / 3–1–0 |
| edge | -3.8 / -6.5 / +1.7 | 411 | 4–0–0 | 4–0–0 | 1–3–0 / 3–1–0 |
| timing | -2.1 / -3.9 / +0.0 | 390 | 4–0–0 | 2–2–0 | 1–3–0 / 3–1–0 |

## Historical opponent stress test

Both asymmetric ping pairs, both seats and six clocks, giving 24 fights per archive entry. Each listed opponent receives equal weight. This secondary test uses the same six prospective clocks. No native score over the entire archive was collected.

| Actor | Opponents | Field score | Ahead / tied / behind | Weakest opponent (record) | Plain W-tap field |
|---|---:|---:|---:|---|---:|
| wtap | 257 | 58.3% | 138 / 9 / 110 | legacy-search-spacing (0–24–0) | 58.3% |
| v4-ppo-g0496 | 257 | 95.4% | 257 / 0 / 0 | v3-exchange-1 (14–10–0) | 58.3% |
| sumo-two-ping | 257 | 96.3% | 257 / 0 / 0 | v3-ppo-01-g0368 (14–10–0) | 58.3% |
| edge8-66047 | 257 | 96.4% | 257 / 0 / 0 | v3-memory-27 (13–11–0) | 58.3% |
| full32-66047 | 257 | 95.9% | 257 / 0 / 0 | v3-ppo-02-g0376 (14–10–0) | 58.3% |
| base8-7181 | 257 | 94.7% | 257 / 0 / 0 | v3-memory-27 (13–11–0) | 58.3% |

## Qualification

The native screen winner was sumo-input-edge8. The trained challenger selected for confirmation was sumo-input-edge8. The independent confirmation winner was sumo-two-ping. Both winners have permanent aliases, sumo-input-screen and sumo-input-final. All native representatives can be dueled by the names above.

- screen covered 128 valid bouts, 136,661 verified movement decisions, 57,493 reconstructed feature timing records, and a minimum physical press interval of 50.009 ms. Largest observed server tick gap 221 ms. Retained 0 invalid attempts.
- confirmation covered 48 valid bouts, 41,668 verified movement decisions, 4,042 reconstructed feature timing records, and a minimum physical press interval of 50.017 ms. Largest observed server tick gap 145 ms. Retained 0 invalid attempts.
- masks covered 24 valid bouts, 20,464 verified movement decisions, 10,237 reconstructed feature timing records, and a minimum physical press interval of 50.006 ms. Largest observed server tick gap 139 ms. Retained 0 invalid attempts.

The score spread across three training seeds reflects optimization variability. Both seats and real timing donors are reused across policies in simulation. Native games are independent. Four native bouts per matchup give coarse resolution. Historical opponents and timing clocks are proxies for play against independent elite humans.
