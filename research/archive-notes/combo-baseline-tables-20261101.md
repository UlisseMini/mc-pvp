# Matched model baselines, November 1

Primary metric: fresh hits before the first accepted return, within a fixed12-second maintenance horizon. Candidate and baselines start from the same complete state, including packet queues. The saved offline-oracle prefix uses plain zero-delay W-tap after its saved controls end; this fallback is declared in advance. This is not a live legal feedback controller.

Each entry lists three phase pairs in order: client-B/server (10,25), (25,5), (40,40) ms; client A starts at0. Values are per-phase counts, not repeated independent random trials. `—` means acquisition did not yield a seed, so there is no matched maintenance comparison. Every pair uses own RTT0, full3.0 reach and20CPS cap. No candidate or W-tap baseline completed the whole12s with zero returns in this grid. This maps unresolved finite-search outcomes, not an infeasibility boundary.

| Horizontal KB | Vertical KB | Fresh ticks | Opp RTT | Saved oracle → W-tap | Plain W-tap,0ms | Hold-W | Historical W-tap,150ms |
|---:|---:|---:|---:|---|---|---|---|
| 0.9 | 0.9 | 9 | 0 | 5/4/1 | 2/3/1 | 2/3/1 | 2/2/1 |
| 0.9 | 0.9 | 9 | 60 | 1/1/1 | 1/1/1 | 1/1/1 | 1/1/1 |
| 0.9 | 0.9 | 9 | 120 | 3/1/1 | 2/1/1 | 2/1/1 | 2/1/1 |
| 0.9 | 0.9 | 10 | 0 | 1/0/0 | 1/0/0 | 1/0/0 | 1/0/0 |
| 0.9 | 0.9 | 10 | 60 | 0/4/1 | 0/1/1 | 0/1/1 | 0/1/1 |
| 0.9 | 0.9 | 10 | 120 | 0/4/— | 1/1/— | 1/1/— | 1/1/— |
| 0.9 | 1 | 9 | 0 | 2/1/1 | 2/1/1 | 2/1/1 | 2/1/1 |
| 0.9 | 1 | 9 | 60 | 1/3/0 | 1/1/0 | 1/1/0 | 1/1/0 |
| 0.9 | 1 | 9 | 120 | 1/3/1 | 1/1/1 | 1/1/1 | 1/1/1 |
| 0.9 | 1 | 10 | 0 | —/—/0 | —/—/0 | —/—/0 | —/—/0 |
| 0.9 | 1 | 10 | 60 | 0/—/1 | 0/—/1 | 0/—/1 | 0/—/1 |
| 0.9 | 1 | 10 | 120 | —/—/1 | —/—/1 | —/—/1 | —/—/1 |
| 0.9 | 1.1 | 9 | 0 | 2/1/2 | 1/0/1 | 1/0/1 | 1/0/1 |
| 0.9 | 1.1 | 9 | 60 | 2/1/1 | 1/1/1 | 1/1/1 | 1/1/1 |
| 0.9 | 1.1 | 9 | 120 | 1/1/5 | 1/1/3 | 1/1/4 | 1/1/3 |
| 0.9 | 1.1 | 10 | 0 | 1/1/0 | 1/0/0 | 1/0/0 | 1/0/0 |
| 0.9 | 1.1 | 10 | 60 | 0/1/1 | 0/1/1 | 0/0/1 | 0/1/3 |
| 0.9 | 1.1 | 10 | 120 | 1/1/1 | 1/1/0 | 1/0/0 | 1/1/0 |
| 1 | 0.9 | 9 | 0 | 1/5/1 | 1/3/1 | 1/2/1 | 1/1/1 |
| 1 | 0.9 | 9 | 60 | 1/1/3 | 1/1/1 | 1/1/1 | 1/1/1 |
| 1 | 0.9 | 9 | 120 | 8/1/1 | 2/1/1 | 2/1/1 | 2/1/1 |
| 1 | 0.9 | 10 | 0 | 0/0/— | 0/0/— | 0/0/— | 0/0/— |
| 1 | 0.9 | 10 | 60 | —/0/1 | —/1/0 | —/1/0 | —/1/1 |
| 1 | 0.9 | 10 | 120 | 1/0/0 | 1/1/0 | 1/1/0 | 1/1/0 |
| 1 | 1 | 9 | 0 | 3/4/3 | 2/1/2 | 3/2/3 | 2/1/2 |
| 1 | 1 | 9 | 60 | 3/4/6 | 2/2/3 | 3/2/3 | 2/2/2 |
| 1 | 1 | 9 | 120 | 0/4/1 | 0/2/1 | 0/2/1 | 0/2/1 |
| 1 | 1 | 10 | 0 | 0/3/0 | 0/2/1 | 0/1/1 | 0/1/1 |
| 1 | 1 | 10 | 60 | 0/1/1 | 1/1/1 | 1/1/1 | 1/1/1 |
| 1 | 1 | 10 | 120 | 10/1/1 | 4/1/1 | 2/1/1 | 4/1/1 |
| 1 | 1.1 | 9 | 0 | 1/1/0 | 1/0/0 | 1/1/0 | 0/0/0 |
| 1 | 1.1 | 9 | 60 | 0/1/3 | 0/1/2 | 0/1/2 | 0/1/2 |
| 1 | 1.1 | 9 | 120 | —/1/0 | —/1/0 | —/1/0 | —/1/0 |
| 1 | 1.1 | 10 | 0 | 1/3/0 | 1/2/0 | 1/4/0 | 1/1/0 |
| 1 | 1.1 | 10 | 60 | 0/1/1 | 0/1/0 | 0/0/0 | 0/1/1 |
| 1 | 1.1 | 10 | 120 | 1/1/1 | 1/1/1 | 1/0/1 | 1/1/1 |
| 1.1 | 0.9 | 9 | 0 | —/—/— | —/—/— | —/—/— | —/—/— |
| 1.1 | 0.9 | 9 | 60 | —/1/1 | —/1/1 | —/1/1 | —/1/3 |
| 1.1 | 0.9 | 9 | 120 | —/1/3 | —/1/3 | —/1/3 | —/1/3 |
| 1.1 | 0.9 | 10 | 0 | 1/2/1 | 1/0/1 | 1/0/1 | 2/1/1 |
| 1.1 | 0.9 | 10 | 60 | 1/1/1 | 1/1/1 | 1/1/1 | 1/1/1 |
| 1.1 | 0.9 | 10 | 120 | 1/1/1 | 0/1/1 | 0/1/0 | 0/1/1 |
| 1.1 | 1 | 9 | 0 | 1/1/1 | 1/1/1 | 1/1/1 | 3/1/2 |
| 1.1 | 1 | 9 | 60 | 1/1/2 | 1/1/2 | 1/1/1 | 2/1/2 |
| 1.1 | 1 | 9 | 120 | 3/1/1 | 3/1/1 | 3/1/1 | 3/1/1 |
| 1.1 | 1 | 10 | 0 | 2/2/3 | 1/0/2 | 1/0/2 | 4/0/2 |
| 1.1 | 1 | 10 | 60 | 3/2/1 | 2/0/1 | 2/0/1 | 2/0/1 |
| 1.1 | 1 | 10 | 120 | 1/2/5 | 1/0/2 | 1/0/4 | 1/0/2 |
| 1.1 | 1.1 | 9 | 0 | —/—/— | —/—/— | —/—/— | —/—/— |
| 1.1 | 1.1 | 9 | 60 | —/2/1 | —/2/1 | —/2/1 | —/2/1 |
| 1.1 | 1.1 | 9 | 120 | 2/2/0 | 2/2/0 | 2/2/0 | 2/2/0 |
| 1.1 | 1.1 | 10 | 0 | 4/1/1 | 1/1/1 | 1/1/1 | 1/1/1 |
| 1.1 | 1.1 | 10 | 60 | 1/3/3 | 1/1/2 | 1/1/3 | 1/1/2 |
| 1.1 | 1.1 | 10 | 120 | 2/3/1 | 2/1/1 | 2/1/1 | 2/1/1 |

The search explored54 profiles ×3 phases at beam64 and12s requested maintenance. It found no full strict witness. There were144 acquired-seed comparisons and18 missing-seed cases. Primary-score outcomes versus zero-delay W-tap:44 higher,95 equal,5 lower. These comparisons share a deterministic model and hand-chosen phases; they are not independent samples for a significance test. Native paired validation is separate.

Some baseline rollouts encounter the explicitly unsupported degenerate-knockback random-direction branch after their first return. The primary first-return count is already determined, but complete total-hit or longest-run claims cannot be made from those truncated tails. Raw rows retain execution horizon and stop reason.

## Reduced-reach backfill

The old saved oracle inputs are replayed unchanged across the same three phase pairs, with matched30-second baselines from the same acquisition state. Our reach remains3.0; only the opponent reach changes. This tests phase transfer, not a new optimization.

| Opp reach | Saved oracle → W-tap | Plain W-tap,0ms | Hold-W | Historical W-tap,150ms | Full30s no-return candidate phases |
|---:|---|---|---|---|---|
| 1.5 | 60/1/2 | 1/1/2 | 1/1/4 | 28/1/2 | yes/no/no |
| 2.5 | 33/0/0 | 1/0/2 | 1/0/3 | 1/0/2 | no/no/no |

The60-hit reach1.5 run is a real finite model witness in its original phase, but it is fragile to phase changes. At reach2.5 the original33-hit advantage also fails to transfer. These rows do not pass the robust-improvement gate.
