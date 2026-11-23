# Conditional native single-launch envelopes

**Current objective, clarified by the user after this audit began:** stay outside the defender's local reach throughout, but require our own local reach when the native fresh-damage window opens and an attack can connect. Temporary own-range excursions during immunity are allowed. Do not replace native readiness with an arbitrary 700-ms gate. The continuous-own-range analysis below is an additional capability/robustness test, not the primary success definition.

The useful capability result is **m1170's airborne normal hit at 2987 ms**. With all optional later clicks suppressed, both continuous sprint pursuit and ordinary walking preserve the own-view 3-block reach bound at every recorded current-box/own-position state boundary through the next recorded hit. Walking also preserves a conservative opponent-exclusion bound with substantial margin at reduced 1.5 reach. The second airborne sprint hit at 1391 ms fails the own-range boundary even under fastest pursuit before the nominal next fresh-hit window. This separates a launch-strength/phase problem from merely insufficient controller tuning.

These are conditional finite-interval results, not a sustainable combo or an impossibility claim. No native matches were run. Files: `launch-envelope.py` is the reproducible offline helper; `launch-envelope-20261101.json` contains all samples, movement-event bounds, hashes and assumptions.

## Method and validity interval

The helper links a recorded server hit to its native local selected click using ordered ATTACK packets. It initializes our native movement state **after the required contact's actual drag and movement cutoff**. It then passes every travel step through the existing `ExactTravel.java` kernel via `ExactTravelCheck --predict-tsv`. The ordinary `.005` living-motion cutoff is applied between steps. Recorded movement timestamps, flat floor, own grounded state and yaw are retained. No optional subsequent clicks are applied to counterfactual paths.

The native golden test passes. All **60 factual native travel steps** in the final four intervals reproduce all 12 recorded double bit patterns and all four collision/ground flags. The earlier count of 61 included one step now excluded by the tighter end boundary.

Own reach uses native eye-to-expanded-box nearest distance, with the recorded B rendered box. This is an all-aim geometric test; aim is perfect in the intended task. The counterfactual own eye is substituted into that recorded box trajectory. The original sparse-probe output excludes ambiguous same-millisecond movement order. A second, denser audit merges own movement, remote interpolation and target replication-apply events by monotonic timestamp. It checks both sides of each state change and asserts every subsequent recorded before-box and click-box matches the reconstructed target box. All continuity assertions pass. Native targeting with `getMouseOver(1.0)` uses these current positions/AABBs; visual frame partial interpolation is outside this scope. Under complete event capture, the dense audit covers the piecewise-constant current-box geometry between events.

Every interval ends **strictly before** the next recorded accepted server attack of either player or the next B local selected contact, whichever comes first. This also excludes a rejected B attack that would brake B locally. B has fixed native forward `.98`, strafe `0`, yaw `90` throughout each interval. Our heading is `-90`, movement is nondecreasing along +X, and we receive no own velocity packet. Given no earlier counterfactual B contact, crossing, correction or changed attack, the fixed B trajectory is independent of our altered pursuit on these intervals. Where that independence cannot be established, results remain optimistic diagnostics.

The WALK path applies the ordinary native movement attribute. It does **not** establish that a legal Ctrl/W transition produces this attribute: Ctrl-up does not cancel an already active sprint, and W re-press can trigger the native double-tap timer. The initial contact at 2987 ms is locally unsprinted and starts from zero horizontal motion, but the unlogged sprint-toggle timer still prevents treating this hypothetical WALK path as an already validated input sequence.

## Own upper-bound measurements

Distances are blocks. The listed maximum is from the dense boundary audit and includes the initial click. Sparse probe measurements are retained separately in the JSON.

| Launch | Initial own range | Stop, exclusive | Factual max | Fast sprint max | Walk max | Coast max |
|---|---:|---:|---:|---:|---:|---:|
| m1170 grounded sprint 886 | 2.9802 | 1391 | 2.9802 | 2.9802 | 2.9802 | 3.6365 |
| m1170 airborne sprint 1391 | 2.8712 | 2488 | 4.9828 | 4.2174 | 4.9919 | 8.2535 |
| m1170 airborne normal 2987 | 2.7472 | 3490 | 3.0326 | 2.8196 | 2.8490 | 4.5353 |
| m1172 grounded normal 3040 | 2.5662 | 3761 | 2.5662 | 2.5662 | 2.5662 | 2.5662 |

The 1391-ms airborne sprint sends B `[.852875, .384625, 0]` from height 1.1355. Our factual required contact reduces own stored horizontal velocity from `.09234839` to `.05540903`. Even maximum sprint acceleration with no more click drag first exceeds our range at **1619 ms**, 228 ms after the server hit (the first sparse probe detecting it was 1658 ms). Before +500 ms, the dense trajectory needs 1.1402 additional forward translation to repair the boundary. This failure precedes the nominal 500-ms fresh-hit interval, so another ordinary fresh hit cannot simply repair it after the failure. Any claim about exceptional damage upgrades requires separate analysis.

The following geometric diagnostic translates our initial position forward while freezing B's recorded trajectory and our initial velocity. It reports extra translation required to repair the dense upper boundary; it is not a realizable new opening or a total reach-band proof.

| Launch | Fast extra translation before nominal +500 ms / whole recorded interval | Walk extra translation before +500 ms / whole interval |
|---|---:|---:|
| grounded sprint 886 | 0 / 0 | 0 / 0 |
| airborne sprint 1391 | 1.1402 / 1.2174 | 1.7747 / 1.9919 |
| airborne normal 2987 | 0 / 0 | 0 / 0 |
| grounded normal 3040 | 0 / 0 | 0 / 0 |

The 2987-ms normal hit sends B `[.352875, .284625, 0]` from height 1.1355. Our own horizontal velocity is zero at the required contact. Walking then yields own range **2.3087–2.8490**, versus sprint **1.6742–2.8196**, across 68 audited boundary states. Pure coast fails the upper boundary. This identifies a useful speed interval for the weaker airborne launch, without assuming the same conclusion for a grounded normal launch.

## Actual native fresh-window evidence

The nominal +500-ms marker is only a convenient diagnostic; server tick phase matters. The recorded server tick transitions below bracket `hurtResistantTime` 11 to 10. Own ranges are the last native current-box states before the first recorded ready tick. These timestamps are offline evidence, not a permitted live oracle, and a local click must account for transport and tick ordering before reaching the server.

| Launch | Last recorded H11 | First recorded H10 | Sprint own range at H10 | Walk own range at H10 |
|---|---:|---:|---:|---:|
| grounded sprint 886 | 1290 | 1340 | .9692 | 1.5391 |
| airborne sprint 1391 | 1789 | 1838 | **3.9931** | **4.5629** |
| airborne normal 2987 | 3392 | 3441 | **1.9544** | **2.5243** |
| grounded normal 3040 | 3448 | 3498 | 0 | .5422 |

Thus the airborne-sprint launch still fails the revised own-range-at-readiness objective under fastest pursuit; its transient excursion is not the decisive objection. The airborne-normal launch remains the useful controlled candidate. The tiny/crossed ranges for grounded normal do not establish success because defender exclusion has already become uncertified.

Stored horizontal velocity after the last simulated move (blocks per native tick; this is post-friction state, not that tick's displacement):

| Launch | Last movement ms | Sprint vx | Walk vx | Coast vx |
|---|---:|---:|---:|---:|
| 886 | 1369 | .15307343 | .11776118 | 0 |
| 1391 | 2469 | .15321667 | .11785899 | 0 |
| 2987 | 3470 | .15301977 | .11770752 | 0 |
| 3040 | 3713 | .15320034 | .11784667 | 0 |

## Conservative defender exclusion

Use B's **local own position**, not its delayed server position. If A moves monotonically forward, its past transmitted positions are no farther forward than its current position. Ordinary delayed interpolation between such positions cannot put B's rendered A ahead of current A, apart from a conservative quantization allowance. Under this hypothesis:

`B_local_x - A_current_x - expanded_halfwidth - 1/32 > defender_reach`

is sufficient to exclude B horizontally; vertical distance can only help. The expanded halfwidth is native `.4000000134110451`. The initial recorded B view of A is behind initial A in all four cases. This bound additionally assumes no pending prehistory position ahead of initial A, no teleport/correction and no interpolation overshoot beyond the allowance. These replica assumptions are stated, not independently proven by the travel kernel.

The script evaluates the bound at the union of every A and B movement timestamp. Same-millisecond ordering is conservatively covered using the smallest before/after B position and largest A position. Values below are minimum margins above reduced **1.5** reach. Positive is a sufficient conditional exclusion certificate; zero or negative means this conservative test is inconclusive, not that a return is necessarily possible.

| Launch | Sprint margin | Walk margin | Coast margin |
|---|---:|---:|---:|
| grounded sprint 886 | −.3193 | −.2445 | +.0050 |
| airborne sprint 1391 | −1.2877 | +.1239 | +1.8615 |
| airborne normal 2987 | **+.3854** | **+1.0199** | +1.7916 |
| grounded normal 3040 | −4.1244 | −3.2309 | −.2463 |

For the airborne normal launch, the positive lower bound supports B-trajectory independence: B cannot initiate an earlier contact under the stated replica assumptions. Walking also leaves only **.0199** conservative margin at defender reach 2.5. That small margin is not a robust target for a new native trial; reduced 1.5 reach provides a clearer first capability test. Together with the dense own-range audit, this is a conditional finite single-launch certificate at reach 1.5; it does not establish a repeatable cycle.

The grounded-normal fastest paths eventually pass/cross the target. Their later frozen-target estimates cannot be promoted to valid full counterfactual duels. The airborne-sprint fastest path also loses the conservative lower certificate near the end; its much earlier own-upper-bound failure is the cleaner diagnostic.

## Minimal next step

Use the airborne-normal launch as a controlled plant-identification target. First verify an actual unsprinted WALK continuation and its timer/packet state from an equivalent controlled entry, with optional clicks suppressed until the next useful hit. Compare native own movement and both local reach traces to this finite-interval envelope. Log actual local sprint, server sprint, movement attribute, W/Ctrl edges and the selected attack's impulse; a controller's requested hit label is insufficient.

Then solve the terminal handoff as one coupled decision: target phase, next hit strength, preparatory movement, and own residual velocity. The easy-stage spacing controller can use reach feedback inside a phase whose launch is already feasible, but neither a PID gain nor an every-Nth-cue reset can repair an overstrong launch whose fastest trajectory remains out of reach at readiness. A desired sprint repair must include time and space to arm sprint before its click; a desired normal hit must include a verified way to avoid rearming it. Keep own reach at the fresh window and continuous defender exclusion as separate hard constraints. Track continuous own containment as an ancillary robustness metric.

The dense audit addresses current-AABB containment for this recorded interval. A native controlled continuation is still needed to validate the unsprinted input transition and fixed-trajectory assumptions. Before claiming a cycle, demonstrate the terminal state returns to a feasible entry set for the next hit. The present result justifies studying that handoff for the airborne normal launch rather than launching another broad variant search.
