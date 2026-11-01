# Matched native baselines, November 1

Matched native30s boxing, own/target3.0reach,20CPS cap,RTT0/120,vanillaKB/hit10,perfectaim,target2s opening no-click handicap. Historical combo-wtap20 reaction150ms explicitly retained; all other candidates/references zero delay. Three sequential repeats, rotated order. Not a superiority claim until complete spread and quality checked.

Each cell lists repeats 1 / 2 / 3. All variants share the stated settings;
the 150 ms W-tap is an explicitly separate historical reference. Native
runs do not share exactly the same OS schedule or tick phase. Opening
handicap hits and post-wake maintenance are shown separately.

| Metric | Plain W-tap, 0 ms | Hold-W | Historical W-tap, 150 ms | Fixed word | Word + strafe | Word + cue timing |
|---|---|---|---|---|---|---|
| Fresh hits before first return, including opening | 4 / 4 / 4 | 4 / 4 / 4 | 4 / 4 / 4 | 5 / 5 / 3 | 5 / 5 / 4 | 4 / 4 / 4 |
| Fresh hits after opponent wakes, before first return | 1 / 1 / 1 | 1 / 0 / 0 | 1 / 1 / 1 | 1 / 1 / 0 | 1 / 1 / 0 | 0 / 0 / 1 |
| Longest unanswered fresh run, whole match | 4 / 5 / 4 | 4 / 4 / 4 | 5 / 4 / 6 | 5 / 5 / 3 | 5 / 5 / 4 | 4 / 4 / 4 |
| Longest unanswered fresh run after wake | 3 / 5 / 3 | 2 / 1 / 1 | 5 / 2 / 6 | 5 / 2 / 3 | 3 / 1 / 1 | 2 / 3 / 2 |
| Entire 30 s without a return | no / no / no | no / no / no | no / no / no | no / no / no | no / no / no | no / no / no |
| First return after wake (ms) | 695.0 / 645.0 / 698.0 | 190.0 / 137.0 / 143.0 | 740.0 / 700.0 / 742.0 | 563.0 / 540.0 / 138.0 | 644.0 / 541.0 / 844.0 | 141.0 / 144.0 / 147.0 |
| Replay IDs | m1225 / m1235 / m1245 | m1221 / m1242 / m1251 | m1223 / m1233 / m1252 | m1227 / m1236 / m1247 | m1229 / m1238 / m1249 | m1231 / m1240 / m1250 |

All 18 matches passed the existing build, click-cap, native-reach
and target-integrity checks. The report also verifies frozen settings and
six-block opening distance from recorded data. Tick/movement interval
means range from 49.590 to
50.008 ms; the largest interval is
213.049 ms. There are
20 intervals above 100 ms.
Additional timing audit flags intervals above 100 ms in the primary window of: m1221.
Whole-match timing flags: m1221, m1223, m1225, m1231, m1238, m1247, m1249, m1250.
These rows remain visible, but their affected metrics are timing-qualified,
not clean-clock evidence. In particular, the first hold-W opening is flagged;
the candidate and W-tap first-return comparisons have no such long stall.
The 100 ms flag is an explicit supplementary audit, not a claim that smaller
jitter is irrelevant. These checks do not establish whole-fight model parity.

Three repeats are descriptive evidence, not a tight estimate of tail behavior.
No superiority claim follows from one favorable opening or later run.
