# Why the two other vanilla120ms phases return only one fresh hit

This is a retrospective replay of the already-saved prefixes and their matched zero-delay W-tap baselines. No new search or controller feature was introduced. Both policies fork the same complete state, including queued packets and shadowed W-tap observation history. Full reach3, vanilla, RTT0/120,20CPS, perfect aim and the original burst2 acquisition are unchanged. The initial phase pairs are opponent/server; our phase is0.

## The seeds are materially different

- **Phase25/5, seed8700ms:** the opening has already traded repeatedly:15 outgoing fresh hits and19 accepted incoming hits. A is descending at height.4795 above the floor, with vertical velocity−.19536; own hurt resistance13, target15. The last outgoing fresh hit was8455ms, launch `[.9,.078]`. Own range2.64397 and defender range3.64304 pass the instantaneous seed filter, but this is a temporary separation within an ongoing fight, not an established clean opening lock.
- **Phase40/40, seed2000ms:** the opening is clean4–0. Both true bodies are grounded. The fourth fresh hit at1990ms has emitted `[.415,.36075]`, still queued for B at2050.4ms and applied on its2051ms frame. Own range1.31738 and defender range4.26699 reflect very different delayed views. Target hurt resistance19; own0.

Neither seed has a pending defender C02. The phase25 seed has only two B movement packets queued. The phase40 seed additionally has the already-created outgoing velocity/status and an A-position replica for B.

For phase25, the earliest new A input can affect B on the8766ms frame; defender margin stays at least+.64304 through the preceding causally committed prefix. For phase40, that frame is2101ms and the minimum committed margin is+1.10082. Therefore **neither seed is already a lost-denial state within the no-new-control-effect horizon**, and neither is doomed by a return packet already sent at the seed. This is not a proof that either seed admits indefinite maintenance: later reachable geometry and all legal controls have not been exhaustively covered.

## Same-state next-hit and first-return timeline

All times below are absolute modeled milliseconds. “Geometry lost” is the first nearest-box defender distance≤3. Actual contact uses the native-style head ray at the defender's next input. The saved prefixes fall back to plain W-tap after150ms and400ms respectively.

| Phase / event | Saved prefix → W-tap | Plain W-tap0 baseline |
|---|---:|---:|
| 25/5: first geometric denial loss | 8925.3 | 8875.3 |
| 25/5: next outgoing fresh hit | 8955, sprint `[.9,.46075]` | 8955, normal `[.399875,.36075]` |
| 25/5: first defender local contact | 8975 | 8925 |
| 25/5: first accepted return | 9055 | 9005 |
| 25/5: fresh hits before that return | 1 | 1 |
| 40/40: next outgoing fresh hit | 2490, sprint `[.835,.5,−.091]` | 2490, sprint `[.839625,.5,0]` |
| 40/40: first geometric denial loss | 2540.3 | 2490.3 |
| 40/40: first defender local contact | 2590 | 2540 |
| 40/40: first accepted return | 2690 | 2640 |
| 40/40: fresh hits before that return | 1 | 1 |

In phase25, plain W-tap has no new target hurt cue before the next fresh hit, so it keeps W/Ctrl and clicking. Server sprint was already false at the seed. Its next fresh remains normal. The saved prefix explicitly releases W at8700 and resumes at8750; this repairs server sprint for8955. But the defender's already-approaching view enters reach first. The new velocity is not delivered until9016ms: W-tap's first return is accepted9005ms before delivery; in the saved branch the defender sends its C02 at8975ms before delivery, and it accepts9055ms afterward. Repairing hit strength at8955 is too late to erase that input.

In phase40, both continuations hit the next fresh window at2490ms and produce a strong launch. The new S12 applies2551ms. W-tap's defender contact at2540 precedes application. The saved prefix buys one extra tick: its contact is2590, **after velocity application but before the defender's next movement update integrates it**. The packet changes B's velocity; it does not instantly move B or remove its current ray selection. By the time the saved branch's return accepts2690ms, its instantaneous defender nearest range has recovered to3.08697, but the earlier legitimate C02 is already queued. Scoring only current geometry at acceptance would miss this history.

## Where the loss becomes committed

The short-horizon bound is useful later in each replay:

- Phase25 W-tap: at8849.999ms, new A effects cannot reach B until8916ms; the queued view update will violate denial at8875.3ms, margin−.00297. Saved prefix: the corresponding committed loss appears at8899.999ms, violation8925.3ms before cutoff8966ms, margin−.29499.
- Phase40 W-tap: at2399.999ms, committed denial loss occurs2490.3ms before cutoff2501ms, margin−.03272. Saved prefix: at2449.999ms, violation2540.3ms precedes cutoff2551ms, margin−.13490.

The bodies remain outside the server-overlap exclusion during these prefixes. Thus changing the **next** input at those later boundaries cannot prevent the named geometric loss under this fixed scheduler. These are certificates of the next committed geometry event, not universal certificates that an accepted return is unavoidable, and not proofs that the original seeds were unsalvageable. Earlier inputs had time to affect B's view. The two observed continuations do affect the result—each saved prefix delays the return50ms—but neither uses that opportunity to obtain another fresh hit.

The strongest conclusion is therefore: acquisition heterogeneity plus unsuccessful finite control, not an already-unrecoverable seed demonstrated at entry. The grid's one-hit outcomes cannot establish vanilla infeasibility or a robust maintenance-controller ceiling. In particular, the late8700ms seed must not be presented as the same clean acquisition as the2000ms seed.

## Reproduction

`tests/ComboFeasibilityPhaseDiagnosis.java` consumes the two saved TSV schedules and runs only their prefix→W-tap and plain-W-tap replays. Compile against the canonical kernel/clock and existing baseline/search classes, then:

```sh
java -cp /tmp/combo-final-check ComboFeasibilityPhaseDiagnosis research/combo-feasibility-phase25-5.tsv
java -cp /tmp/combo-final-check ComboFeasibilityPhaseDiagnosis research/combo-feasibility-phase40-40.tsv
```

The adjacent JSON files retain complete seed snapshots, queued packets, acquisition accepted-hit history, chosen inputs, next-fresh/first-contact/return events and committed-prefix bounds every50ms. Both policy seed snapshots were checked equal; each replay gives exactly one fresh hit before its first accepted return, agreeing with the matched primary grid. This diagnostic stops after that first return and does not replace the existing12s horizon results.
