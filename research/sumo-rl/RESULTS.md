# Native transfer of the frozen sumo policy

The policy won **14 of 16** real Forge 1.8.9 sumo fights against 20-CPS W-tap on the lab's provisional MMC profile. Plain W-tap won **7 of 16** against the same opponent. The learned policy won all eight fights at 0 ms added RTT and six of eight at 80 ms. The most credible source of this advantage is its choice to keep moving forward. In the eight matched settings where the simple Hold-W control also played, Hold-W and the learned policy both won seven. The experiment supports using the policy for head-on MMC sumo. The unchanged policy lost ground in the Hypixel-like test and in native boxing combo maintenance. The elite human benchmark is still open.

## Matched ring-outs

Each native cell shows wins–losses–draws. The closing condition is a server ring-out or the 120-second limit. Players had three-block reach, a 20-CPS ceiling, zero added reaction and aim delay, no aim noise or turn cap, equal added RTTs, a six-block opening and a ten-block disk. Both arms played from each seat in randomized order. Native runs share conditions and independently scheduled acquisition. The simulator uses the identical donor clock and initial state across arms.

| Native opponent | Plain W-tap | Frozen pressure policy | Hold-W without jumps |
| --- | ---: | ---: | ---: |
| MMC W-tap | 7–9–0 | **14–2–0** | 7–1–0 |
| MMC Hold-W | 0–8–0 | **5–0–3** | 4–3–1 |
| MMC trade-aware W-tap | 2–6–0 | **6–2–0** | — |
| MMC jump-reset, excluded from training | 2–6–0 | **8–0–0** | — |
| MMC hit-selecting Zest strafer, additional test | 16–0–0 | **15–1–0** | — |
| Hypixel-like W-tap | 2–2–0 | **1–3–0** | — |

Against plain W-tap, the learned arm scored 8/8 as player A and 6/8 as B. Plain W-tap scored 3/8 and 4/8 respectively. A descriptive 95% Wilson interval for 14/16 is 64%–97% assuming independent games with the tested mixture. The interval applies to this particular bot matchup. At 80 ms, three of four learned rounds against Hold-W reached the time limit. The remaining round was a win.

Model cells show regulation points out of 32, with half a point for a draw. Each row uses eight whole validation recordings, both seats and both RTTs. The opponents in the first three rows appear in training. Jump-reset and the Zest strafer do not. The additional Zest test reuses the already exposed validation clocks. Its live bouts were newly recorded after freezing the controller.

| Model opponent | Plain W-tap | Frozen pressure policy | Hold-W without jumps | W-tap with the same learned jumps |
| --- | ---: | ---: | ---: | ---: |
| MMC W-tap | 16 | 27 | 23 | 9 |
| MMC Hold-W | 9 | 21.5 | 16 | 6 |
| MMC trade-aware W-tap | 14 | 27 | 27 | 10 |
| MMC jump-reset | 18 | 25 | 17 | 13 |
| MMC hit-selecting Zest strafer | 32 | 30 | 32 | 31 |
| Hypixel-like W-tap | 16 | 8 | 6.5 | 8 |

The first four model rows are from the frozen training-era simulator. With solid lip recovery and the donor's individual tracker order, the learned arm gains one additional point against Hold-W. All other displayed MMC head-on totals stay the same. The Zest row uses the corrected physics. The local Zest controller gives up the opening hit and alternates strafe direction. Its result gives limited information about a human who can change style in response.

## Why forward pressure worked

A fresh, aligned MMC strike supplies about 0.5274 blocks/tick of horizontal velocity plus 0.327 if the **server** sees the attacker sprinting at attack entry. W-tap earned that bonus on 177 of 195 follow-up fresh hits in its W-tap mirror bouts. The learned controller earned it on **zero of 170** follow-ups against W-tap. Its ring-outs came despite dealing less knockback per follow-up hit.

The other half of the exchange takes place on the attacker's **client**. When moving away from the opponent, a sprinting contact scales the attacker's current horizontal velocity by 0.6, even on an attempt that deals no fresh damage. Among native engine contacts while moving backward faster than 0.005 blocks/tick, the learned arm braked on **492 of 503**, plain W-tap on **280 of 630**, and plain Hold-W on **299 of 306**. W-tap also coasted for 362 of 3,842 recorded movement steps. Both continuous-W arms coasted for zero. The controller can therefore concede a larger initial impulse and spend fewer subsequent steps moving toward its own edge. Counts describe mechanisms within independently played matches. The movement-only comparison tests the contribution of the jump decisions.

Against W-tap in the subset with all three native arms, plain W-tap won 3/8, continuous W won 7/8, and continuous W with learned jumps won 7/8. Against Hold-W, the learned arm scored 6.5/8 ring-out points, pure Hold-W scored 4.5/8 and plain W-tap scored 0/8. Higher-latency results against Hold-W included long stalemates. Across the 120 main native matches, all **845** learned jump requests corresponded to grounded native takeoffs. This verifies that the same control acts in the game even where its competitive value is uncertain.

## Boxing maintenance

The drill lasts 20 seconds. The defender starts clicking at two seconds. An entry requires three unanswered fresh hits after both players are active. Every accepted return, including a damage upgrade, ends the run. The model forks both arms from the same complete W-tap state, history and queues. Each native arm plays eight independent rounds. The numerical gate is maintenance from the landmark. Entry count is secondary.

| Condition and landmark | Plain W-tap | Frozen pressure policy |
| --- | ---: | ---: |
| Model, 3 hits, completed further cycles / common starts | 14 / 18 | 22 / 18 |
| Native, 3 hits, completed further cycles / entries | 9 / 8 | 7 / 14 |
| Native, 3 hits, accepted returns / censored | 7 / 1 | 14 / 0 |
| Native, 3 hits, total exposure / median remaining time | 8,803 / 1,152 ms | 5,244 / 51.5 ms |
| Model, 5 hits, completed further cycles / common starts | 6 / 7 | 7 / 7 |
| Native, 5 hits, completed further cycles / entries | 1 / 3 | 2 / 1 |
| Model / native, 10-hit starts observed | 0 / 0 | 0 / 0 |
| Native, rounds acquiring the 3-hit landmark | 6 / 8 | 5 / 8 |

At three hits, every model fork ended in a return. Model exposure totaled 12,661 ms for W-tap and 13,315 ms for the learned policy. In Minecraft, 10 of the 14 learned entries ended without one more fresh hit. Plain W-tap had three such entries out of eight. The inactivity-qualified calculation, ending on an inter-fresh gap above 1,000 ms, gives the same results as return-only in this drill. The native scorer found a delay definitely above 100 ms on 127/190 scored next-fresh windows for W-tap and 85/265 for the learned bot. Its higher output left more opportunities for immediate returns. Per-episode times, five-hit censoring and exposure, initial geometry, timing bins, unfinished windows and remaining-hit distributions are in the [per-fight evidence](outcomes-v3.json.gz). Cycles within a run are correlated. W-tap remains the boxing reference.

W-tap had server sprint on all eight native third-hit landmarks, with a median horizontal center gap of 3.31 blocks. The learned arm had server sprint on zero of fourteen, with a median gap of 3.01 blocks. In replay `m2430`, W-tap's third fresh hit applied 0.8544 horizontal velocity at a 3.249-block gap. W-tap hit again 449 ms later and received its first return 1,401 ms after the landmark. In `m2413`, pressure's third hit applied 0.5274 at a 2.956-block gap. The opponent returned it 49 ms later. The narrower gap and smaller outward impulse help explain why pressure trades effectively yet struggles to deny the return. The two native examples started independently.

## Fidelity and speed

- **Conditional native travel.** Across the checks there were 18,671 native movement steps, 8,653 attack contacts, 1,382 incoming velocity packets, 233 jumps and 290 steps over empty space. There were zero position, velocity, bounding-box or collision-flag mismatches, both stepwise and in continuous per-client rollouts supplied the recorded contacts and incoming velocity. Some opponent presets lack detailed probes; their missing POVs are listed in the evidence.
- **Coupled, measured-timing replay.** Across 24 head-on and four lateral sumo recordings, all 60,448 scheduled replay events completed. The 22,815 scored state snapshots had zero mismatching rows, and generated combat packet subtypes and represented payload fields passed their assertions. These windows lasted up to eight seconds or 100 ms before match end. Four historical boxing windows also passed 5,623 replay events. Instrumentation exposed incorrect lip recovery and reversed server entity-tracker iteration order. Both corrections have native checks.
- **Physical input with estimated packet delay.** At the two-second mark all checked movement vectors matched throughout 15/19 head-on openings. In the four divergent games, maximum position errors reached 1.04–2.79 blocks. Against the lateral opponent one of four openings matched exactly, and the other three differed by at most 0.031 blocks. A small timing error can move a hit to a different client or server tick. The strict replay's measured service times are available only after a fight.
- **Throughput.** On the local Apple M2 Max with Java 17 and four workers, three 8,192-round batches of the frozen policy ran at 2,135–2,178 complete bouts/s, or 1.11–1.14 million equivalent 20 Hz duel ticks/s. Plain W-tap ran at 7,899–8,872 bouts/s and 1.46–1.64 million equivalent ticks/s because its rounds were shorter. The timings include both policies, movement, clicks, packet queues and ring-out scoring after warm-up. Trace serialization, optimizer updates, training a neural network, and loading donors are separate costs.
- **Repeatability.** Re-running the league fit reproduced every generation score, finalist score and chosen policy. Eighty full-state no-op comparisons exercised both RTTs and multiple event boundaries. Additional Java tests cover queued input, event timing, fork isolation, packet readiness, MMC knockback and the native lip recovery. [Fidelity and performance data](fidelity.json)

## Experiment history and status

The first jump-only fit used 13,312 bouts with a faulty importer that counted all three loaded Minecraft worlds. The corrected importer advances each fighter's immunity once per overworld tick. The second fit played 12,352 bouts. Neither jump-only policy was promoted. The final fit used 16 development donors for 34,752 bouts. Its eight new evaluation donors are m2375–m2382. Those clocks are now exposed for future work. No evaluation donor entered the final fit. The [protocol](revision-3.json) and [frozen weights](league-fit.json) predate the transfer results.

| Native MMC comparison against W-tap | Plain W-tap | Learned policy |
| --- | ---: | ---: |
| Historical v1, faulty training clock | 7 / 16 | 11 / 16 |
| v2, corrected clock and jump-only actions | 11 / 16 | 8 / 16 |
| v3, pressure style and jumps | 7 / 16 | 14 / 16 |

The [v1 archive](v1/outcomes.json.gz), [v2 archive](outcomes-v2.json.gz), [v3 archive](outcomes-v3.json.gz) and [lateral archive](outcomes-lateral.json.gz) contain the full matched model and native results. The lab profile is an explicit approximation of MMC. Real elite players, asymmetric latency, other platform sizes, and generalization to different knockback settings remain open evaluation targets.
