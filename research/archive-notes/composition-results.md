# Uncorrected replay: isolated corrections and fresh failures

The replay initializes once. Later native positions, impulses and hit results never correct the prediction. Recorded physical inputs and client/server clocks are supplied offline. These are model diagnostics, not bot performance scores.

The fall-damage and click-order diagnoses isolate one change at a time. The table below shows their combined effect with head-yaw memory enabled, using zero server-timestamp shift. The full JSON reports retain the declared ±5ms sensitivity cases.

| Native case | A error at5s | B error at5s | A error at9s | B error at9s |
|---|---:|---:|---:|---:|
| m0360 | 0.030m | 0.020m | 0.001m | 0.012m |
| m0362 | 0.057m | 0.045m | 0.092m | 0.142m |
| m0363 | 0.755m | 0.025m | 6.846m | 9.112m |
| m0364 | 7.667m | 7.301m | 0.902m | 1.336m |
| m0367 | 1.141m | 1.588m | 3.442m | 3.460m |
| m0370 | 1.655m | 1.531m | 3.910m | 4.445m |
| m0371 | 1.453m | 1.935m | 3.864m | 2.499m |
| m0372 | 1.090m | 1.266m | 0.833m | 2.365m |
| m0373 | 2.530m | 4.644m | 20.749m | 24.698m |
| m0374 | 2.167m | 2.074m | 10.733m | 14.974m |
| m0375 | 0.715m | 1.290m | 5.532m | 7.415m |

`m0370`–`m0375` are fresh unequal-RTT trials, not cases used to identify the fall omission. Their errors reject any claim that the full model is already faithful across pings. A mathematically plausible controller optimized against it can still exploit simulation mistakes.

Read [fall-damage-diagnosis.md](fall-damage-diagnosis.md) for the source-checked first error. The next priority is the earliest causal mismatch in an unequal-RTT trace, separating packet/application phases and rendered-view evolution from physical equations.
