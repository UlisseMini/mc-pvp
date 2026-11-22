# Sumo replay analysis

[Original ten, re-reviewed](original-ten.md) · [Ten additional matches](additional-ten.md) · [Archive manifest and hashes](cohort.json)

Each match analysis combines a player-side explanation, the opponent's observed response, and one or more measured alternatives. The original coaching evaluation keeps its original highlight metric. Both groups were used to learn the replay-analysis workflow. The additional ten are case studies, so a future scoring test should use untouched recordings.

## What this review added

- **Follow the brake through the next hit.** In m9153 the earlier brake takes the initial slide from 2.36 to 1.11 blocks and leaves 2.41 instead of 0.72 floor at the next launch. With that launch kept as recorded, the edited position still overlaps the platform at the missed landing. In m9005 an early brake also saves the next landing in the fixed replay. Moving that correction to the first post-travel sample drops its saving from 1.25 to 0.75 blocks and the same landing is lost. In m8974 even a 1.82-block saving leaves a 2.30-block slide against 1.60 of floor. The branch also restores geometric reach for a possible second brake. These three outcomes need different explanations.
- **Test whether the next contact still works.** In m8900 the first brake had no accepted damage. The second brake's click made the winning attack. Remove the first cut and the frozen-view geometry for the second press moves from about 2.60 to 3.18 blocks. The branch stops there. In several other cases reaching a previously missed box became possible. That is a useful reason to stop extrapolating future attack results.
- **Check movement past six ticks.** m9040 and m8948 both looked just inside the floor margin at six movements and went off afterward. m8620 also shows the reverse risk of a one-dimensional score. The bot traveled farther than its original straight-line margin but curved over remaining floor until the next hit. The tool checks the 2D footprint on successive movements and separately identifies a changed descent.
- **Separate the first error from its side effects.** In m8886 aim and sprint were both wrong on the first late click. It then started a miss timer. Clearing the timer alone for subsequent presses does little when the nearest hitbox is already beyond reach. In m9092 the same ~0.87-block sprint correction applied three times after a W release. One of those presses actually scored damage while it missed the local brake.
- **Tie causality to packet phases rather than wall-clock coincidences.** The winning launch in m5922 occurs beside the bot's immediate reply. The server hit event and its velocity row were 1 ms apart. The working link uses adjacency, target, integer S12 payload, and client main-thread application. This recovers the right launch without conflating it with the other side of the trade.

## Run an audit

From the project root, use Python 3.11 or later. The tool reads the original records in `runtime/results`, `runtime/replays`, and `runtime/pov`.

```sh
# Last few exchanges and terminal hits
python3 research/sumo-replay-analysis/inspect_replay.py m9153 --finish

# Full machine-readable evidence for named matches, with a filtered console view
python3 research/sumo-replay-analysis/inspect_replay.py m9153 m8900 --out build/sumo-review --hit 49 50

# A match with three complete POV archives can also be inspected by ID
python3 research/sumo-replay-analysis/inspect_replay.py m9127 --finish

# Re-run the verified 20-match cohort, then inspect selected hits from the cache
python3 research/sumo-replay-analysis/inspect_replay.py --out build/sumo-review
python3 research/sumo-replay-analysis/inspect_replay.py m5919 --from-json build/sumo-review --hit 26 28

python3 -m unittest discover -s research/sumo-replay-analysis -v
```

For frozen IDs, source SHA-256 hashes are checked before reading. POV capture footers must be complete, with zero reported drops. An ambiguous packet join or incomplete movement ends the affected episode. The current reader expects the recorded 13×13 bare-fist arena and one complete event file per perspective. The input captures stay local. The JSON output preserves per-move paths, click phases, branch limits, and landing anchors for manual checking.

### Read the output

| Field | Meaning |
|---|---|
| `i`, `t`, `victim` | Accepted hit index, milliseconds on the server match clock, and receiving seat. |
| `receipt`, `delay_ms`, `first_moves` | Receiving client monotonic application stamp, server send-to-application duration, and movement samples relative to receipt. Move samples contain time, outward displacement, grounded state, forward input, sprint, and travel consistency. |
| `slide`, `first_n`, `client_margin` | Peak outward slide over the available six-move prefix, number of comparable moves, and floor distance along the incoming launch at receipt. The independently tracked footprint can give a different answer on a curved path. |
| `presses` | Selected outcome, relative time, outward speed, sprint, press-time miss counter, key state if captured, nearest-box distance from the preceding render sample, and optional server packet link. An empty linked acceptance list means no accepted hit was observed inside that handler. A missing link is unknown. |
| `saving`, `measured_steps` | Actual minus edited projected peak over the same compared moves. Negative savings mean extra slide. A truncated comparison uses fewer movements. |
| `at_next` | Spatial shift and the new margin along the next incoming launch's direction, with up to six comparable follow-up movements. The next received velocity replaces the speed difference. The position difference survives. |
| `checks`, `opponent_press_geometry` | Possible reach changes under a frozen replica. The opponent comparison uses the observed press that sent the next hit when uniquely linked, and sweeps recent position shifts from the preceding 150 ms. These are geometry checks, with no claim about new aim or hit acceptance. |
| `floor_difference`, `landing`, `status` | First footprint difference and, when the branch reaches it, the first downward movement with different floor coverage. A changed landing ends the branch. So does a selected later click projected well beyond reach. `window_complete` means the recorded prefix ended without either stop condition. |
| `server_tail`, `first_final_server_no_support_ms` | Recent positions and the start of the final run outside horizontal floor overlap. The player can still be airborne at this point. |

### Scope of the estimates

The movement comparison is a local, recorded-boundary calculation. It checks each factual movement's horizontal displacement against the observed post-travel motion and drag. It applies changed clicks as a horizontal velocity difference, using the actual pre-click motion. Later factual cuts scale the difference again. It conditions on later movement forces, horizontal drag, vertical motion, other inputs and replicas until a change in floor or contact requires a new branch. The approximate calculation can differ near small motion clamps, block boundaries, and sampled/interpolated reach. Report tiny margins accordingly. A new spent click could alter sprint, cooldown, damage, subsequent input and the opponent's reaction. The tool does not regenerate those choices. A v4 sprint-strength swap uses that archived preset's separation-dependent amplitude ratio and preserves its launch direction and vertical component. Older presets keep their measured impulses.

For questions that hinge on a marginal visible ray or the next accepted hit, trace the actual source report, replica goal, interpolation and attack packet first. `local_replay.py`, `src/lab/ExactTravel.java`, and the worked `trade_coast_response.py` are available for captures with the requisite movement and replica fields. Use a complete paired native or model fork for claims about how both players would play on. The conditioned branches here establish how individual corrections could change initial slide, reach geometry, and floor through the next exchange.
