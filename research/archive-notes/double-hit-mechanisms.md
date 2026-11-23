# Why W-tap only sometimes doubles hold-W

Native Minecraft Java 1.8.9; normal speed; flat full-block ground; ordinary W/Ctrl and native clicks, no jumping or strafing. Tactical observations are at least 150 ms old. Continuous physical clicks remain capped at 10 CPS through misses and immunity. Unless stated otherwise, both players have 20 ms added RTT.

## What counts as a double

A scoreboard increment is not necessarily a new knockback hit. During the upper half of immunity, a stronger falling sword hit can increase damage without restarting the fresh-hit knockback sequence. We separate **fresh hits** (`hurtResistantTime <= 10` at native server attack entry) from these **damage upgrades**.

Two adjacent fresh events by A count as an ordering double. We also report whether B dealt any intervening accepted damage and how soon B's next fresh hit arrived. An A–B / B–A ordering reversal can put two A events together without producing a useful combo. A return at least 150 ms after the second A hit is a separate diagnostic, not the definition of a permanent lock. End-of-record censoring is explicit in the updated analyzer.

## Native event condition

Let `c` be an actual vanilla click-consumption event and `q(c)` its server task execution, when a C02 ATTACK was emitted. Define

```
contact_i(c) = native cached ray selects the assigned opponent
               and native leftClickCounter permits the click
fresh_i(c)   = contact_i(c), server handler accepts the contact,
               and opponent hurtResistantTime <= 10 at q(c)
```

After A's first fresh hit, A gets a fresh double exactly when its next qualifying `q(c)` precedes every qualifying return from B. The comparison includes B packets already in flight. Equal wall-clock timestamps are ordered by the native event stream. This is an exact event criterion for the captured scenario, not a predictive solver: predicting these events requires client movement, rendered opponent history, click consumption, packet queues and server tick order.

Identical policy names and nominal ping are **not identical initial states**. Client ticks, render frames, click injection/consumption, network task drains and server ticks have relative phases. Their real execution clocks are recorded, not held fixed by a deterministic scheduler. A deterministic transition function can give different answers from these different states. The earlier bit-exact movement test established conditional movement parity; it did not establish a complete deterministic duel rollout.

## Initial first-to-100 baseline

The three original W-tap versus hold-W games were **100–70, 100–69 and 100–75** (`m0389–m0391`). Fresh-hit totals were 225–162; damage-upgrade totals were 75–52. The score advantage is real, but not all of its extra points represent new knockback.

Across these recordings, all **60** W-tap fresh hits without server sprint followed a sprint-consuming damage upgrade since its preceding fresh hit, with no intervening server START_SPRINTING. An early reset can therefore be spent on an upgrade before the next fresh-hit window. This does **not** mean immune clicks should be suppressed: local contact slowdown remains part of normal play.

Also, 81 of 223 nominal 50 ms releases lasted **two native movement updates**, rather than one. A wall-clock deadline just after the next update can keep W released for another tick. We added a distinct `wtap-tick` policy that resumes on the next movement update; the ordinary policy is preserved.

## Landing phase: a concrete successful sequence

In `m0396`, both fresh attacks in a trade execute at 11,643–11,644 ms. The velocity packets arrive at B at 11,655 ms and A at 11,657 ms—only two milliseconds apart.

| First movement after those packets | W-tap A | Hold-W B |
|---|---:|---:|
| Feet height | 4.000 | 4.527 |
| Grounded before movement | Yes | No |
| Horizontal friction multiplier | approximately .546 | approximately .91 |
| Horizontal X motion after movement | −.146818 | +.461002 |

These are native measured transitions. The players also differ in impulse size, yaw and intervening contact slowdown, so this table is **not** a one-variable intervention proving friction alone caused the outcome. It identifies a concrete mechanism to test: A counteraccelerates and damps its knockback on the ground, while B retains more outward motion in the air.

There is another observed difference: A's own entity metadata changes its local sprint flag from true to false at 11,657 ms, after its velocity packet. Its next contact therefore does not apply the .6 local sprint slowdown. B's next contact does apply that multiplier before movement. A faithful composed model must reproduce this metadata/attack/movement ordering; it cannot assume that holding Ctrl makes every consumed click locally sprinting.

A then lands fresh hits at **12,296, 12,790 and 13,845 ms** before B returns at 13,943 ms. At the 12,790 hit A is grounded and B is 1.042 blocks above it. B's seven actual clicks from 12,250 through 12,845 all miss the native entity ray, with zero miss cooldown before each click. Their closest padded-box distances range from 3.053 to 4.095 blocks. These were real missed return opportunities, not immunity-filtered clicks or extra reach.

Contrast the same game's 5,392/5,442 ms trade: both first movements after receiving knockback start grounded. Both return fresh hits at 5,892/5,946 ms. Later, after A's 14,892 ms hit, B's 14,948 ms click still selects A at a closest-box distance of 2.428 blocks and becomes a fresh return at 14,995 ms. Sprint knockback was present, but the opponent still had a legal contact before displacement removed the opportunity.

The feedback is important: when B misses, it also misses the local contact slowdown that would have reduced its horizontal knockback. Conversely, a contact during immunity can damp that motion and help B recover. All of those clicks are retained in these experiments.

Watch the [native trajectory/data replay](../recordings/m0396.html), especially 11.6–14.0 seconds. This is an offline reconstruction from recorded positions and events, not rendered Minecraft video.

The [packet-linked aggregate](trade-grounding.json) includes 348 paired trades across 11 games. In the repaired-arena trials, A repeated within 850 ms without a fresh B reply through 150 ms afterward in 12/95 ground/air cases and 5/128 ground/ground cases. The older three baselines do **not** reproduce that association (9/48 versus 15/74). These groups are kept separate. Landing phase is a mechanically relevant state variable, but the observational counts do not prove a universal ground/air rule; impulse, sprint metadata, range and local drag remain confounders.

## Preregistered reset interventions

Eight 30-second fixtures, two per policy, with the second repeat in reverse order. All use the same loaded agent and repaired arena. Only the stated reset rule changes; click cadence and observations remain normal.

| Reset rule | Scores versus hold-W | Fresh hits with server sprint |
|---|---|---:|
| Ordinary 50 ms release | 53–50, 56–50 | 70 / 86 |
| Exactly one movement tick | 55–45, 57–44 | 68 / 87 |
| One tick, later by RTT-adjusted 200 ms | 66–67, 60–58 | 61 / 97 |
| One tick, gated by own issued click near estimated window | 64–60, 67–58 | 64 / 101 |

The one-tick variant improves the short-run scores, but does not repair every sprint loss. The later variants fail their intended mechanism: the estimated phase often places the reset on the fresh click rather than its preceding immune click. In the click-gated trials, 63 of 100 resets coincide with a fresh click. Neither later variant is promoted as an improvement. A separate six-match first-to-100 confirmation compares the ordinary and one-tick releases.

Physical input intervals averaged 105.1–105.9 ms in these short trials, with a hard minimum of 100 ms. Native movement-update intervals had medians of 51 ms and 95th percentiles of 54–57 ms. The first control trial also contains a 167 ms gap; the other seven maxima are 62–65 ms. These are actual clock schedules, not ideal 50/100 ms grids. Trials are serial, and the short-run scores are preliminary evidence rather than a statistically established ranking.

## Full-match confirmation

Three additional first-to-100 games per reset rule, at 20/20 ms added RTT, with counterbalanced order and the same agent:

| Policy | Three scores | Total | Mean hold-W return hits |
|---|---|---|---:|
| Ordinary 50 ms W release | 100–86, 100–81, 100–83 | 300–250 | 83.33 |
| Exactly one movement update | 100–73, 100–82, 100–76 | 300–231 | 77.00 |

This is a modest improvement in this sample, not a high-confidence universal ranking or an always-double result. The candidate remains named `wtap-tick`, and the original `wtap` remains available. The best one-tick recording (`m0404`) has a seven-fresh-hit unanswered streak, but its longest strict measured reach-denial sequence is only 1.696 seconds with four fresh contacts. Unanswered hits and continuous inability to return are different metrics. Watch the [100–73 recording](../recordings/m0404.html).

The mechanism check passes: all 221 candidate releases last exactly one movement update. The control has 76 two-update releases among 218 total. Yet server sprint is present on 175/221 control fresh hits and 179/223 candidate fresh hits—almost the same proportion. Shortening the release does not solve sprint consumption by upgrades. Candidate fresh-hit totals are 223–183, versus control 221–197; useful ordering doubles with at least 150 ms to the next fresh return number 28 versus 14. Under the narrower conditional measure “A returns within 200 ms of B's hit, then the next fresh event is A,” success is only 36/81 for the candidate versus 34/84 for control. The score improvement does not establish reliable doubling from every trade.

The [independent full-match grounding analysis](trade-grounding-confirmation.json) finds 18/119 ground/air pairs and 7/154 ground/ground pairs meeting its repeat-within-850-ms and return-denied-for-150-ms criterion. It retains two unresolved terminal packet cases and censored endings. This is another observed association, still not a controlled intervention on ground state alone.

These new control scores are worse than the original three baseline scores. We therefore compare the interventions with their own same-build control cohort rather than presenting historical 100–70/69/75 scores as interchangeable counterfactual controls. The games do not reproduce identical native clock schedules.

## Opening click-phase intervention

Six additional 12-second fixtures keep the one-tick W-tap attacker and change only hold-W's initial click-start delay, 0 versus 50 ms. Both then continue the same capped physical click stream. Order is counterbalanced. The [opening analysis](trade-opening-phase-v1-opening-analysis.json) retains the actual first injection, consumption and contact times, every fresh event through six seconds, and the first three uniquely paired trades per game.

| Hold-W initial delay | Three 12-second scores, W-tap first | First fresh B hit minus first fresh A hit |
|---|---|---|
| 0 ms | 22–14, 22–21, 22–21 | +1, +1, 0 ms |
| 50 ms | 24–18, 21–20, 19–21 | +1, −46, −53 ms |

The nominally later start can make B's first actual contact **earlier**: it shifts which physical click catches the first in-range opportunity. It is not a constant 50 ms shift of attack results. For example, the first physical B click in `m0410` is 97 ms after A's first physical click, but the first server hits are only 1 ms apart. Different native startup/frame phases remain part of the measured state.

Among the first three paired trades, only 1/9 of the 0 ms cases and 0/9 of the 50 ms cases meet the grounding analyzer's repeat/denial criterion. Changing nominal phase did not create a reliable double policy. The paired-trade summary is conditional: changing phase can change which events fall inside the 75 ms pairing window. It is not a clean causal estimate for landing state or a claim that one phase is optimal.

## Experimental validity and limitations

An earlier intervention batch hit a pre-existing hole in the arena (`m0392`); its results are quarantined in `trade-reset-interventions-v1.json`. The affected bot fell below Y=4 and became stuck. The cause of the original damage is not established. Before the replacement suite, the surface was repaired with ordinary bedrock full blocks, which have the same .6 slipperiness as the original grass/dirt. The referee now aborts below-floor matches, and evaluation rejects below-floor positions and unexpected horizontal block collisions. The three original baseline recordings were separately checked and contain neither problem.

The [additional exact-travel check](trade-surface-travel-parity.json) runs the independent movement kernel against both clients in `m0396`, including takeoffs and landings on the repaired surface. It uses the existing zero-bit-difference contract; this remains conditional movement validation, not whole-duel parity.

The click probe observes vanilla `clickMouse`, including misses and cooldown. Entity contacts are paired with **all** incoming ATTACK packets, including packets rejected before entering the server attack method; otherwise one rejected packet would shift every later association. The regression suite checks this case. Private native state is used only in postprocessing and is unavailable to the controllers.

Two analysis bookkeeping corrections are explicit. The `*-exchange-check.json` files apply end-of-record censoring to the 150 ms return diagnostic, reducing that count by one for `m0390` and `m0401`; original suite outputs remain preserved. The original three traced W-tap games used the label `trade-wtap` by mistake. Their saved parameters unambiguously say `kind=wtap`; the public `trade-wtap` policy has been restored to `kind=tradetap`, and future research clones use the reserved `trace-exchange-` prefix. Replay aliases preserve the distinction.

## What an improved policy needs to control

The desired state is not just “sprinting at the next hit.” It is a useful combination of impulse, landing phase and spacing: recover our forward motion, keep the opponent airborne/outward, and land at the edge of our legal ray while their own view offers no eligible return. Excess knockback can instead make both miss the following hit. Repeated trades can also bring both players into overlapping reach, where a reset cannot undo an already emitted return packet.

A controller should forecast an interval of landing and next-contact times from delayed visible samples and its own inputs, then use ordinary movement to choose re-entry timing. It must account for continuous click slowdown and possible critical upgrades. Its forecast should be tested against native events before optimizing its reward. The present experiments do **not** establish an always-double policy or an invariant infinite combo against hold-W.

The next decisive experiment is an uncorrected, event-complete replay of a single recorded trade, including own sprint metadata, click consumption and packet application. First require the original input sequence to reproduce the native trace. Then compare legal W-release sequences from that same state and schedule, and repeat across neighboring schedule phases. If an input only works for one hidden phase, it is not a robust 150 ms-observation policy. This will distinguish a controllable missed opportunity from a state in which the current input restrictions cannot guarantee the desired double; the current live trials alone do not prove either universal possibility or impossibility.

## Evidence and reproduction

- `trade-baseline-v1.json` and `trade-baseline-v1-causes.json`: original three full matches.
- `trade-reset-interventions-v2.json` and corresponding causes file: eight controlled short trials.
- `trade-reset-confirmation-v1.json`: preregistered full-match confirmation.
- `trade-opening-phase-v1.json` and corresponding opening analysis: six nominal click-start interventions.
- `traces/`: archived native server and both client streams.
- `../trade_science.py`: trial runner and fresh-event classification.
- `../trade_causes.py`: sprint consumption, release duration and actual native click outcomes.
- `../trade_grounding.py`: exact integer-payload S12 pairing and first post-application travel, including intervening metadata and local contact drag.
- `../phase_exchanges.py`: measured opening input/contact phases; no assumption that a nominal offset persists.

Run analysis with `python3 trade_causes.py SUITE_NAME`. New trials require a running lab and a fresh evidence name, e.g. `python3 trade_science.py new-evidence-name --variants wtap-control wtap-tick --repeats 3`. Existing evidence files are not overwritten by the runner.
