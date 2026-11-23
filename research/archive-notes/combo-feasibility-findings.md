# Combo feasibility: what the current evidence supports

## Comparison gate

The primary task is fresh hits before the first accepted return in a fixed horizon, plus whether the whole horizon has zero returns. Plain zero-delay W-tap and hold-W must start from the same complete state, with identical reach, RTT, profile, clocks, handicap and scoring. Several phases/native repeats are required. The historical search numbers below are **unqualified diagnostic evidence pending those matched baselines**. They are not claims that the search beats W-tap. Search variants stopped when this gate was made explicit.

The earlier oracle guard imposed two additional constraints: defender geometric reach denied at every modeled event, and a fresh hit on the first eligible server drain. These are stricter than merely taking no accepted return. Their counts cannot be compared with a baseline scored only by accepted returns.

| Historical diagnostic | Strict fresh hits / maintenance | W-tap baseline | Interpretation |
|---|---:|---|---|
| Original straight-input oracle, reach2.5, beam256 | 33 / 16.45 s | Pending: unqualified | Finite surviving prefix; beam exhausted |
| Same rank/cell plus committed-future pruning, reach2.5 | 46 / 23.35 s | Pending: unqualified | Earlier exclusion of doomed branches changed the retained search paths |
| Full reach3, seed3950, known-word elite retained | 10 / 5.45 s | Pending: unqualified | Finite strict prefix, not a stable cycle |
| Known-word regression, reach3, beam16, four-second horizon | 8 / 4 s | Pending: algorithm regression only | The search preserves the already-known positive trajectory |
| Vertical scale1.10, full reach3 | 6 / 3.4 s | Pending: unqualified | Heuristic failure, not profile infeasibility |
| Hurt threshold9, full reach3 | 0 / 0 s | Pending: unqualified | Selected acquisition state was already doomed; not a maintenance test |
| Horizontal scale0.90, full reach3 | No seed | Pending: unqualified | This acquisition did not establish the required seed |

All these historical searches used0/120ms RTT, perfect aim, a20CPS ceiling and ordinary native inputs. The first trials restricted strafe to zero; no conclusion about the full permitted control space follows from them. The drivers now branch over A/neutral/D as well. Small smoke checks establish API execution, not performance.

## A concrete repeating vertical mode

Replaying the existing33-hit reach2.5 survivor through the canonical fight kernel exposes a seven-hit pattern:

```
strength:  S        S        N        S        N        S        N
launchVy: .46075   .46075   .009375  .422375  .36075   .109375  .36075
```

The corresponding hits at6425–9425ms and9925–12925ms are500ms apart. At all seven paired phases, target true height, rendered height, outgoing vertical velocity and server latent vertical velocity are bit-identical. This is a repeating vertical subtrajectory in a finite coupled replay. It is not a full-state periodic orbit: horizontal positions, velocities, replication lag and control words still differ.

A native knockback packet does not overwrite the server's restored latent motion. Between server floor contacts or inferred jumps, each zero-input server travel step obeys

```
v[n+1] = (v[n] - .08) * .98
v[n]   = (v[0] + 3.92) * .98^n - 3.92
```

Fresh vertical knockback then uses `min(v/2 + float(.4), float(.4))`, with an additional `.1` for sprint knockback, followed by profile scaling and packet quantization. Consequently “sprint hit” does not identify one fixed vertical launch. The same sprint category can produce `.46075` or `.109375` in the recorded sequence.

The recurrence above is conditional on **no server floor collision/inferred jump**. Actual client landing is sufficient, but not necessary, for a latent-state reset: the server's zero-input travel can reach the floor and infer a jump from a rising C03 even when its trajectory differs from the client's. A simple client-height oscillator that omits this state is inadequate.

## Geometry and its limits

For a collinear chase with grounded A and target B ahead, write `A` for A's current X, `B` for B's current X, `R` for A's rendered B X, and `Q` for B's rendered A X. Let `h` be the expanded halfwidth (approximately.4), and let `dA,dB` be the vertical eye-to-box distances. Where both vertical distances are below their respective reaches, the instantaneous nearest-box conditions are

```
A >= R - h - sqrt(3^2 - dA^2)
A <  B + (A-Q) - h - sqrt(reachB^2 - dB^2)
```

Their interval width is

```
(B-R) + (A-Q) + sqrt(9-dA^2) - sqrt(reachB^2-dB^2).
```

This separates delayed replication and vertical advantage. For target height roughly.28–1.72 above grounded A, B's eye is above A's expanded top while A's eye still intersects B's vertical span. The band helps defender denial without consuming our horizontal reach. The exact native float dimensions are used by the implementation.

This is a **conditional instantaneous nearest-box calculation**, not a reachability proof. The own-reach inequality is required at the fresh opportunity, not throughout immunity. The lag terms change with past controls and cannot be reset freely. Native head-ray selection is stronger than nearest-box geometry. In2D, use the full expanded AABB and the actual head ray; the scalar interval is not a substitute for that calculation.

## A causally committed dead-end

At18449.999ms in the33-hit survivor, the next B interpolation at18460.3ms produces local reach2.47328869055, violating the reduced2.5 boundary. All six next straight key/click actions yield the same failure. The already queued fresh velocity reaches B only around18485ms. A new input at18450 cannot affect B before the next server drain at18475 plus the60ms downlink and frame application, approximately18536ms.

Thus this particular state is unrecoverable before the violation under the declared fixed schedule. Changing the immediate key, strafe or click cannot alter B's already committed body/view update. Earlier choices could have avoided the state. This is a local causal obstruction, not a global vanilla impossibility argument.

`ComboFeasibilitySearch.committed` forks the same model and examines the prefix strictly before any newly issued A input can first reach B. It prunes a denial failure in that prefix, within its declared separated domain. The forecast uses no new plant. `ComboFeasibilityCheck` verifies that all18 W/Ctrl/strafe/click variants have identical B body/view trajectories through the committed prefix of a healthy sample state. This supplements the causal timing argument; it is not an exhaustive proof over every initial state.

Ranking remains heuristic. Replacing the old rank with the minimum future margin shortened the surviving prefix. Keeping the old rank and using the forecast only to prune did better on the historical reach2.5 sample. The search also initially lost a known full-reach word after just two hits; retaining that word as an explicit elite repaired the regression. These observations show why beam exhaustion cannot establish physical infeasibility.

## Reproducible interfaces

Both `ComboOracleSearch` and `ComboFeasibilitySearch` accept

```
output.json reach horizontal vertical hitTicks seconds \
  [beam=96] [opponentRTT=120] [strafe=1] [seedMs=auto] [openingBurst=2]
```

They use `-Dcombo.b_phase=10` and `-Dcombo.server_phase=25` by default and report those phases. Strafe branches are ordinary damped input `-.98,0,+.98`; there is no yaw-offset aim trick. Deduplication includes radial motion, view/goal XZ, server horizontal velocities and queued XYZ/velocityZ. It remains approximate deduplication in a finite beam, not exhaustive state enumeration.

Results distinguish `seed_notfound`, `beam_extinguished` and `finite_witness`. The feasibility driver additionally preserves the opening word as an elite and prunes committed-future denial. Oracle ranking uses hidden model state only offline; it is not an observation-only live controller. The canonical kernel, clock adapter and recorded replay remain shared.

`ComboFeasibilityTrace` reproduces the older straight-input survivor from `combo-feasibility-oracle-r25.tsv` and emits the per-hit evidence and six final extensions. The current trace's X-only interval is explicitly restricted to this historical collinear run.

## Strongest defensible conclusion

There is no global impossibility result and no demonstrated stable full-reach lock here. A periodic vertical subtrajectory and finite full-reach strict prefixes exist in the validated fixed-clock model. Specific delayed-feedback states are provably too late to rescue under that model's causal schedule. The outstanding question is whether a simple controller can avoid those states better than matched plain W-tap across phases and native repeats. That comparison must precede further controller features or claims about a beneficial physics tweak.

## First matched causal comparison: one phase only

A retrospective replay now compares the saved grid prefix followed by plain zero-delay W-tap against that same W-tap and hold-W. All fork from the complete seed at3650ms, including pending packets and shadowed baseline cue history. Settings are full reach3, vanilla,0/120RTT, phases0/10/25,20CPS, perfect aim, the same burst2 acquisition and a12-second measurement horizon.

| Primary metric | Saved prefix, then W-tap | Plain W-tap baseline (0ms) | Hold-W control |
|---|---:|---:|---:|
| Fresh hits before first accepted return | 10 | 4 | 2 |
| First accepted return, absolute time | 8675ms | 6175ms | 4775ms |
| Entire12s horizon has zero returns | No | No | No |

The hold-W rollout later stops at unsupported degenerate random knockback, after its first return; its first-return result is already determined, but its unobserved tail must not be given a complete total-hit score. This one phase is a causal example, not the required across-phase superiority gate. Native matched repeats remain necessary.

The saved strict prefix contains nine fresh hits. Its W-tap fallback adds a delayed tenth hit at8525ms before the return at8675ms. Thus the primary count is10, not the strict prefix's9.

The first informative launch comparison is at4425ms. Hold-W sends a normal launch `[.399875,.322375]`; W-tap restores sprint and sends `[.9,.422375]`, delaying the return. At4925ms, W-tap again launches strongly `[.9,.46075]`, while the saved prefix uses a normal launch `[.399875,.36075]`. W-tap's local range is already2.852 at that server event, versus2.043 for the prefix. W-tap then misses the5425 fresh opportunity and reconnects at6075, when B's local range is2.484; B returns at6175.

This identifies the competing functions of W-tapping: it repairs insufficient outgoing knockback, but another strong hit can overextend pursuit. Continuous local contacts also apply drag and alter the preceding spacing; earlier accepted upgrades and replication states differ slightly. The trace therefore supports a coupled mechanism diagnosis, not a claim that changing just one impulse would reproduce the whole advantage. `combo-feasibility-paired-trace.json` and `ComboFeasibilityBaselineTrace` retain the per-hit and input evidence. No new controller feature or search was introduced for this comparison.


## Matched native first-repeat failure diagnosis

These are the first six completed runs from the parent's three-repeat suite, not the final spread. All use30s, reach3/3, RTT0/120, vanilla,20CPS, perfect aim and the same2s target no-click opening. Independent native clocks and the resulting acquisition trajectories differ; they are matched settings, not identical complete-state forks. All six passed the recorded quality gate.

| Native policy | Fresh before first return | Plain W-tap0 baseline | Fresh after wake before return | First return | Whole30s zero returns |
|---|---:|---:|---:|---:|---|
| Hold-W, m1221 | 4 | 4 (m1225) | 1 | 2190ms | No |
| Historical W-tap150, m1223 | 4 | 4 (m1225) | 1 | 2740ms | No |
| Plain W-tap0, m1225 | 4 | 4 (self) | 1 | 2695ms | No |
| Fixed word, m1227 | 5 | 4 (m1225) | 1 | 2563ms | No |
| Word plus strafe, m1229 | 5 | 4 (m1225) | 1 | 2644ms | No |
| Cue-gated word, m1231 | 4 | 4 (m1225) | 0 | 2141ms | No |

The word's extra fresh hit occurs before wake-up. It does not yield an additional post-wake fresh hit, and the first accepted return is earlier than either W-tap reference. These six runs do not establish robust superiority; the full repeated suite is authoritative for the spread.

**Fixed-word failure is not initially a missed fresh window.** m1227's first five fresh server attacks occur at495,996,1493,1991,2513ms, with actual server strengths S,N,S,N,S. The fourth is a grounded normal launch `[.38675,.36075,0]`. Its lower arc preserves our own reach: sampled own distances from1914ms until the first return remain below2.405. But the defender's range falls from3.799 at2025ms to3.169 at2382ms, and its first native contact occurs at2440ms with actual selected-ray distance2.976. That precedes our fifth contact at2479ms and its fresh server attack at2513ms. The defender's first attack is accepted at2560ms (the scoring hit callback records2563ms). This return was already launched from the defender's legitimate local view before the next outgoing fresh hit. A future hit cannot recall an already-sent C02.

**W-tap gets sprint restoration right but overextends this opening.** m1225's first three fresh attacks at493,998,1496ms are S,S,S. The second sends `[.859875,.46075,0]`; the third, against height2.313, sends `[.88425,.109375,0]`. Our sampled local range subsequently peaks5.249 at2234ms. There are no native contacts between1633 and2580ms; the next fresh attack is delayed until2594ms. The defender's first contact is2609ms, after ours, and its return accepts2694ms. Stronger launches preserve denial longer, but the long pursuit gap makes the eventual reacquisition a trade. W-tap150 has the same broad S,S,S/long-gap mechanism, with native times589,1094,1590,2690ms.

**The other first-repeat controls support the same narrow diagnosis.** Hold-W sends normal hits after its first sprint hit and collapses to true center gap.703 at its fourth fresh attack, then takes a return50ms later. Strafe delays the first defender contact relative to the straight word, but still allows it: at2535ms its native selected ray is2.980 and the accepted return follows2644ms. Cue gating in m1231 gets four fresh hits but the fourth normal launch occurs at1990ms, after an extra server tick of immunity expiry; the defender is already sampled in range at2001ms and contacts2035ms. Its fourth cue cannot retrospectively restore denial.

This is a coupled spacing/height/latency failure, not evidence that a timing resync or maximal knockback alone solves the problem. The fixed-word and W-tap acquisition states differ markedly by the second hit (true center gaps.726 versus1.706), so replacing a single impulse is not a demonstrated causal cure. No new controller feature follows from this inspection. The useful next evidence is the complete matched repeat spread and, if needed, a same-state replay intervention explaining this exact tradeoff.

Reproduce the saved first-repeat evidence with `python3 research/combo-feasibility-native-diagnosis.py --repeat 1`. It reads completed archives only and stores server accepted attacks, native local contacts, independent reach probes and actual consumed movement around wake-up in `combo-feasibility-native-first-repeat.json`. Science-attack and scoring-hit callbacks can differ by a few milliseconds; the primary table uses the suite's scoring callback, while the mechanism paragraphs use the named native event.

## Third fixed-word repeat: the first lost transition moves earlier

The same frozen word fails differently in m1247. Compare each word run with its own same-repeat W-tap0 reference:

| Repeat / word match | Word fresh before return | Matched plain W-tap0 baseline | Word / W-tap post-wake fresh | Word / W-tap first return |
|---|---:|---:|---:|---:|
| 1 / m1227 | 5 | 4, m1225 | 1 / 1 | 2563 / 2695ms |
| 3 / m1247 | 3 | 4, m1245 | 0 / 1 | 2138 / 2698ms |

All four runs pass the native quality gate. No policy setting or physical constant changed. The first sprint launch is identical in the two word runs, `[.9,.46075,0]`, with true center gap1.900. The decisive next transition is different:

- m1227: first local contact477ms → server495ms, then phase10 contact961ms → server996ms. The second attack arrives10 server ticks later with target H10 and is fresh. The measured contact-to-server delays are18.731749 and35.011958ms.
- m1247: first local contact499ms → server542ms, then phase10 contact985ms → server989ms. The second attack arrives only9 server ticks later with H11 and is immune. Contact-to-server delays are42.558250 and4.525167ms. Thus a comparable484–486ms local-contact interval spans ten server ticks in one run and nine in the other.

The word intends another attempt at phase11. In m1247 its plan at1033ms has `click_gate=true`, the native ray selects the opponent, and no screen is open, yet no `click`, `science_click`, engine contact or server C02 is recorded. The preceding injection is984ms. Cycle policy inputs are evaluated only at the native tick; by phase12 at1084ms the gate is closed. The next actual contact is1486ms and the next fresh server attack1489ms—947ms after the first fresh hit.

The missing retry is consistent with the hard20CPS gate: the policy evaluation occurs about49ms after the prior completed injection. Precisely, that injection is monotonic50193980826875ns. The next `tick_start` snapshot begins50194030830917ns, only50.004042ms later; the eligibility check occurs before this snapshot. Its exact `ClickRate.ready` timestamp is not logged, so **CPS rejection is an inference from timing and the other satisfied conditions, not a directly recorded rejection code**. By contrast, m1227 actually issues its phase11 retry50.169250ms after the phase10 injection.

The missed fresh launch permits the bodies to cross. At1489ms A's server X8194.34118 exceeds B's8193.82987, while A still faces−90 degrees from its delayed local view. Normal separation knockback points backward, and the sprint yaw bonus points forward: B's latent horizontal motion/2 minus.4 plus.5 yields only `[.11125,.46075,0]` after quantization. This is not an unexpectedly weak sprint constant. It is the native vector sum in a crossed geometry. The third fresh at1995ms is normal `[.473125,.36075,0]` at true gap.795. B contacts2053ms at native range1.197 and returns2138ms.

Plain W-tap m1245 retries through H11 at946ms and reaches H10 at997ms, then lands another sprint fresh at1497ms. Its later overshoot/reacquisition failure matches the first W-tap repeat, but it does not lose an entire attack window here. `Client.frame` permits ordinary W-tap's continuous gate to retry on frames after the50ms cap expires; cycle clicks additionally require `nativeInputPoll`, so a short tick can consume the sole remaining retry slot without issuing anything.

This separates **policy-to-native input scheduling** from **fight-kernel mechanics**. The parent's recorded-schedule replay of the first4s of m1245/m1247 passes all1419/1380 requested rows, with0 unexecuted rows, exact represented state/packet comparisons and zero numeric range error. That replay consumes the clicks actually issued; it cannot validate the missing policy-level retry. The nominal fixed-clock search assumes50ms input steps and does not expose this particular sub50ms tick/cap interaction. Before any new spacing feature, the controller-to-click pacing adapter needs an explicit native comparison. This diagnosis does not authorize raising the shared20CPS ceiling or establish that repairing pacing alone yields sustained denial: m1227 still fails geometrically after five regular fresh hits.

`combo-feasibility-native-word-repeat.json` retains the three compared native event streams, including immune attempts and plans. `combo-feasibility-native-word-timing.json` retains FIFO contact/server pairings and exact injection/snapshot clocks. Recreate the principal extract with:

```sh
python3 research/combo-feasibility-native-diagnosis.py --matches m1227 m1247 m1245 --output research/combo-feasibility-native-word-repeat.json
```
