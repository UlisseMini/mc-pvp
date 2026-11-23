# Click-consumption ordering: m0362

The replay's first large error after enabling the separately tested fall-damage
factor was an input-adapter error. A physical click and its consuming client tick
can share one wall-clock millisecond. Comparing only those milliseconds assigned
seven clicks to the following tick in the first ten seconds of m0362.

This is a diagnosis of one mechanism in one 0/0-RTT Wtap-versus-no-click-pursuit
recording. It does **not** validate the complete model. Fresh unequal-RTT holdouts
still contain large trajectory errors and need separate causal investigation.

## Direct native evidence

All times below are relative to wall-clock epoch `1792811654110` ms. Native trace
files are `traces/m0362-a.jsonl.gz`, `traces/m0362-b.jsonl.gz`, and
`traces/m0362-server.jsonl.gz`. The exact causal records are also included in
`click-order-diagnosis.json`.

| Event | Relative wall time | Evidence |
| --- | ---: | --- |
| A physical injection | 5610 ms | Same-JVM `monotonic_ns=112864763756291` |
| A native attack | 5610 ms | Selected canonical target; native ray 2.197797805 m; local sprint true→false; horizontal velocity multiplied by 0.6 |
| A travel hook | 5610 ms | `monotonic_ns=112864764254833`, **498,542 ns after injection**; pre-travel velocity already contains attack slowdown |
| Server fresh A→B hit | 5640 ms | Accepted hit 8, sprint=true, target hurt-resistant timer becomes 20 |
| B velocity packet | 5648 ms | `(vx,vy,vz)=(.892,.46075,-.118875)` |
| B travel | 5660 ms | Uses that packet velocity; feet rise from 4 to 4.46075 |

The old adapter used a half-open wall-time interval ending before the movement
timestamp. It excluded the 5610-ms click from the 5610-ms travel and put it on
A's 5662-ms tick. The model therefore lacked the native impulse at B5660.
Local monotonic ordering assigns it to the demonstrated consuming tick.

## Isolated reproduction

`python3 research/click-order-reproduction.py` compiles only the model and offline
runner into a temporary directory, reads archived traces, and prints JSON.
Its independent assignment enumerates each click into the first later movement
event; it does not use the main adapter's bisect implementation. Both variants
keep the same actual tick timestamps, same actions, initial states/views, head-yaw
factor on, fall-damage factor on, server timestamp shift 0, and tie ordering
SERVER→A→B. No later positions, velocities, native hit confirmations, or packet
payloads enter the model. Only the per-client click-consumption mapping changes.

All seven affected clicks belong to A:

| Injection time (ms) | Old consuming tick (ms) | Monotonic consuming tick (ms) |
| ---: | ---: | ---: |
| 3059 | 3112 | 3059 |
| 3917 | 3968 | 3917 |
| 4766 | 4819 | 4766 |
| 5610 | 5662 | 5610 |
| 5713 | 5760 | 5713 |
| 7615 | 7666 | 7615 |
| 7820 | 7858 | 7820 |

## Results

Euclidean post-travel position error, sampling the closest actual tick to the
listed horizon:

| Horizon | A old→corrected (m) | B old→corrected (m) |
| ---: | ---: | ---: |
| 5000 ms | .055885→.057326 | .045571→.045319 |
| 5660 ms | .074352→.050957 | **1.146062→.044352** |
| 6000 ms | .063241→.049205 | .398729→.034033 |
| 7000 ms | .052334→.006287 | 2.538397→.027767 |
| 9000 ms | .321120→.092254 | 1.600876→.142203 |

The corrected replay has 14 fresh accepted hits, matching all 14 native fresh
hits through 10 s. Each predicted hit falls on the native hit's corresponding
recorded server-end event, 1–3 ms later than the server attack hook. Native times:

`637,1188,1690,2790,3343,4188,4689,5640,6189,6937,7437,8390,8891,9840`

Corrected replay times:

`638,1190,1691,2793,3344,4189,4690,5641,6192,6938,7438,8391,8892,9841`

The old mapping produced 15 fresh hits and changed the contact sequence after
the misplaced eighth hit. With monotonic assignment, the first position error
above .1 m moves from B5660 to B8561; maximum error over ten seconds is .143519 m
for A and .179875 m for B. B8561 has matching vertical position and approximately
.102 m transverse error, a gradual residual rather than a missing launch.

The remaining model limitations include approximated packet dispatch, server-end
timestamp proxies, native float/angle effects, rendered-view timing, and tracker
events. Same-JVM monotonic ordering fixes local consumption attribution only; it
does not establish the exact cross-process packet timeline. The public adapter's
separate `--monotonic-clicks` ablation reproduced these 9-s errors.
