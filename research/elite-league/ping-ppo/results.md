# Sumo with independently randomized player pings

Both PPO actors started at the deployed v4-ppo-g0496 weights. Both saw the same independent per-match draws, seats, opponents and random action seeds. Added RTT was uniformly sampled at whole milliseconds from 0 to 150 except 23 and 97. Those two values were reserved for transfer. The actor has eight softsign hidden units and five movement and click outputs. The two-ping version adds opponent RTT and an availability indicator to the original 22 inputs.

The deterministic development score gives equal weight to field average, W-tap and the weakest opponent, with half credit for draws. Each check used 256 fights. After at least 256 updates, six checks without a half-point gain triggered a fourfold learning-rate reduction. Six further checks without a half-point gain ended training.

- two-ping peaked at update 480 with score 73.2%. It stopped at update 672 after 21,504 training bouts.
- own-ping peaked at update 192 with score 75.4%. It stopped at update 576 after 18,432 training bouts.

The incumbent scored 66.5% on development and plain W-tap scored 32.5%. The first four new W-tap mirrors supplied the sealed host clocks. Test rows within each host clock share timing. The native matches below are independent real games.

## Transfer

All records show wins–losses–draws from the column bot’s perspective.

| Environment and opponent | Two-ping PPO | Own-ping PPO | Incumbent | Plain 20-CPS W-tap |
|---|---:|---:|---:|---:|
| Simulator, eight opponents | 180–74–2 | 165–46–45 | 161–54–41 | 81–175–0 |
| Simulator, W-tap | 32–0–0 | 31–1–0 | 31–1–0 | 16–16–0 |
| Simulator, shift-check-v4-4 | 11–20–1 | 11–15–6 | 11–14–7 | 1–31–0 |
| Minecraft, W-tap | 4–0–0 | 4–0–0 | 4–0–0 | 3–1–0 |
| Minecraft, shift-check-v4-4 | 3–1–0 | 2–2–0 | 1–2–1 | 0–4–0 |

### Native seat and RTT spread

Each cell below combines the two scheduled opponents. The JSON results also break each opponent out by seat and link. The first RTT belongs to the column bot.

| Slice | Two-ping PPO | Own-ping PPO | Incumbent | Plain 20-CPS W-tap |
|---|---:|---:|---:|---:|
| Seat a | 3–1–0 | 3–1–0 | 3–1–0 | 2–2–0 |
| Seat b | 4–0–0 | 3–1–0 | 2–1–1 | 1–3–0 |
| RTT in ms 23/97 | 3–1–0 | 4–0–0 | 2–1–1 | 1–3–0 |
| RTT in ms 97/23 | 4–0–0 | 2–2–0 | 3–1–0 | 2–2–0 |

## What did the opponent ping contribute?

On 128 asymmetric simulated fights, replacing only the observed opponent ping with the actor’s own ping changed 26 results. The physical pings, both seats, host timings and both policies’ weights stayed fixed. The actor scored 90 points with the real value and 92.5 with the substituted value. The network uses the extra input, but the correct value gave no aggregate advantage in this panel.

The native audit verified 25,880 movement decisions across both players in 32 bouts. The two-ping actor received the correct HUD value on 2,583 scored decisions, with 0 unknown. The shortest recorded physical click interval was 50.013 ms. The initial parser and intermittent-HUD attempts are archived separately. The final comparison retained every original control and replayed all eight two-ping bouts with match-local HUD memory. No elite human bouts were scored.
