# Do independently trained policies help one another?

The original development score selected four networks from ten eligible candidates, with all 210 distinct groups tested. The pooling comparison averages their five action scores before deciding on movement, jump and attack. The parameter average uses the identical members with mean weights. Both use local observations and the same aim, reach and click controls as the incumbent.

The chosen members were base8-29137, edge8-7181, full8-66047, v4-ppo-g0496. The strongest individual on development was edge8-7181. All three were frozen before scoring the four new timing schedules.

| Entrant | Ten-opponent model | Model vs W-tap | 257-opponent model | Archive weakest (W–L–D) | Native vs W-tap | Native vs incumbent | Native vs shift-check | W-tap model field / native overall |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| sumo-vote-member | 68.1% | 31–1–0 | 96.8% | v3-feed-39 (10–6–0) | 4–0–0 | 2–1–1 | 2–1–1 | 27.2% / 0–12–0 |
| sumo-vote-pooled | 68.6% | 32–0–0 | 97.3% | v3-memory-27 (11–5–0) | 4–0–0 | 2–2–0 | 2–2–0 | 27.2% / 0–12–0 |
| v4-ppo-g0496 | 69.1% | 31–1–0 | 96.0% | v3-ppo-01-g0368 (7–7–2) | 3–1–0 | 2–1–1 | 1–1–2 | 27.2% / 0–12–0 |
| sumo-vote-average | 69.7% | 32–0–0 | 97.3% | v3-exchange-1 (11–5–0) | 3–1–0 | 1–3–0 | 3–1–0 | 27.2% / 0–12–0 |
| research-wtap20 | 27.2% | 16–16–0 | 59.6% | adapt-01-recurrent (0–16–0) | 0–4–0 | 0–4–0 | 0–4–0 | 27.2% / 0–12–0 |

| Entrant | Native A / B | Native 31/109 / 109/31 | Plain W-tap overall |
|---|---:|---:|---:|
| sumo-vote-member | 5–1–0 / 3–1–2 | 5–0–1 / 3–2–1 | 0–12–0 |
| sumo-vote-pooled | 4–2–0 / 4–2–0 | 4–2–0 / 4–2–0 | 0–12–0 |
| v4-ppo-g0496 | 3–1–2 / 3–2–1 | 4–0–2 / 2–3–1 | 0–12–0 |
| sumo-vote-average | 4–2–0 / 3–3–0 | 4–2–0 / 3–3–0 | 0–12–0 |
| research-wtap20 | 0–6–0 / 0–6–0 | 0–6–0 / 0–6–0 | 0–12–0 |

The actual native bracket winner was sumo-vote-member. Its permanent playable alias is sumo-vote-final. Each matchup covered four independent native bouts. A strong score in the archive describes 257 historical bot opponents.
