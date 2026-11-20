# Does the exact 32–0 bot transfer back to 20 CPS?

| Matched cap and environment | sumo-seven-first W–L–D | sumo-frozen-ppo W–L–D | Plain W-tap W–L–D |
|---|---|---|---|
| native at 7 CPS | 32–0–0 (100.0%) | 5–2–1 (68.8%) | 0–8–0 (0.0%) |
| simulator at 7 CPS | 32–0–0 (100.0%) | 17–11–4 (59.4%) | 2–30–0 (6.2%) |
| native at 20 CPS | 10–22–0 (31.2%) | 8–0–0 (100.0%) | 1–7–0 (12.5%) |
| simulator at 20 CPS | 8–24–0 (25.0%) | 26–5–1 (82.8%) | 2–30–0 (6.2%) |

Both caps apply to both players. The exact weights, game physics and target stayed fixed. Native bouts used independent clock realizations. The simulators reused the same eight clocks at both caps.
