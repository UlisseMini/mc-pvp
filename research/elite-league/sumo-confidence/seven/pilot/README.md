# First 7-CPS native transfer

Both Forge clients used a verified 7 CPS ceiling. The target was the frozen best-sumo checkpoint. The early policy was chosen before fighting it in Minecraft.

| Against frozen best-sumo | 7-trained PPO W–L–D | 20-trained PPO at 7 W–L–D | Plain W-tap at 7 W–L–D |
|---|---|---|---|
| native | 32–0–0 (100.0%) | 5–2–1 (68.8%) | 0–8–0 (0.0%) |
| model | 32–0–0 (100.0%) | 17–11–4 (59.4%) | 2–30–0 (6.2%) |

All eight native scheduling blocks were perfect. A conservative exact 95% interval treating each whole block as a trial is 63.1% to 100.0%. Assuming independent bouts instead, 32/32 gives an exact 95% win-rate interval of 89.1% to 100%. The preregistered t interval collapses at an all-wins boundary. The two references have eight native bouts apiece. The separately frozen 16-block crossover compares both caps directly.
