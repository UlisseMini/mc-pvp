# Native trade return windows

The decisive event is the defender's **local contact generation**, not the later scoreboard hit or knockback movement. In this 20/20 ms RTT cohort, every one of 194 prompt fresh returns was generated before the defender's first movement using the attacker's next knockback. Increasing that next hit's knockback cannot cancel these already generated attacks.

This report analyzes recorded events; it does not establish an always-winning controller. Source data, per-trade windows, linkage diagnostics and hashes are in [trade-return-windows.json](trade-return-windows.json). Reproduce with `python3 trade_return_windows.py` from the repository root; the implementation is [trade_return_windows.py](../trade_return_windows.py).

## Definitions and scope

- A is Wtap, B is clicking hold-W. All seven analyzed matches have 20/20 ms RTT, continuous physical click streams and the existing 150 ms tactical delay.
- A paired trade is a unique pair of opposing fresh server attacks within 75 ms. Fresh means a scoreboard increment with the victim's native `hurtResistantTime <= 10` immediately before that server attack. Within-immunity critical damage upgrades are excluded.
- **Strong true double:** after the paired trade, the next two nonempty server-tick groups of fresh attacks contain only A: A2, then A3, before B2. A B attack in either of those server ticks defeats the definition, regardless of log ordering within that tick. No duration cutoff is imposed; long gaps are disclosed separately.
- **Earlier delay proxy:** A repeats within 850 ms and B has no fresh return through A2 + 150 ms. This can be A2, then B2, with no A3. Its historical JSON name `A_double_with_denial` is retained for compatibility and must not be read as proof of two consecutive hits or continuous denial.
- The six full confirmation matches m0403–m0408 contain 277 paired trades. The shorter m0396 case study contains 29 and is reported separately. These are repeated, overlapping phases within a small number of games, not independent trials.

Physical injections map to native click consumption using same-client FIFO and monotonic timestamps. Native contacts map to all received server C02 ATTACK inputs in FIFO order, including inputs rejected before entering the server attack method. Exact S12 integer payloads and FIFO order link server sends to client applications. Across the seven matches, 6,226 injections have 6,226 consumptions; 3,700 local contacts have 3,698 received C02 packets, with two explicit terminal unreceived contacts and no interior linkage errors. Different JVM monotonic clocks are never compared. Cross-JVM wall-millisecond ties are retained as ambiguities.

For a real received attack, native victim immunity is known exactly. A missed click or nonclick reach probe has no actual hypothetical packet arrival. Its eligibility annotation uses the preceding completed server tick and excludes intervening fresh attacks **and status-2 sends to A**, including fall damage. Server log order resolves same-millisecond snapshot/send ordering. Cross-JVM timing and hypothetical future acceptance remain unproven.

## What actually happened

| Cohort | Paired trades | Strong true doubles | Strong outcome censored at end | Earlier delay proxy |
|---|---:|---:|---:|---:|
| m0396 case study | 29 | 3 | 1 | 4 |
| m0403–m0408 confirmation | 277 | 16 | 5 | 25 |

Thirteen of the 16 confirmation doubles have both the trade→A2 and A2→A3 gaps at most 850 ms. The other three include gaps of 851, 952 or 1,051 ms, so they establish attack ordering rather than a tight uninterrupted combo. The 277-trade denominator includes the five censored endings; it is not a fitted success rate.

The earlier proxy partitions the 277 trades into 194 prompt returns, 25 delayed returns, 50 without A repeating within the horizon, one B-first outcome, and seven censored outcomes. The following timing breakdown refers **only to those 194 prompt-return cases**:

| B's fresh-return contact, relative to A2 knockback received by B | Count |
|---|---:|
| Before S12 application | 164 |
| After S12 application, before first travel using it | 30 |
| After that first travel | 0 |

At the instant of S12 application, 111 returns had already been accepted by the server, 53 were already sent and in flight, 15 physical inputs were already queued, and 15 had not yet been injected. The last 30 still generated their contacts before movement. Local velocity changes when S12 applies; position does not change until travel. Native click processing can therefore produce a return during this interval.

Fourteen of the 16 strong confirmation doubles contain at least one positive, cooldown-clear native reach probe while the prior server snapshot indicates eligibility. This is evidence of a **local opportunity between actual clicks**, not proof that a hypothetical click would arrive as a fresh server hit. Conversely, sampled negative probes never prove denial at every possible click time. Actual two-hit success is therefore substantially weaker than a demonstrated phase-independent lock.

## Concrete success and failure

Times below are milliseconds since match start.

**Strong success, m0396:** the paired trade is A11643/B11644. A then obtains fresh attacks at 12296 and 12790; B's next fresh attack is 13943. B's actual clicks at 11942, 12045, 12148 and 12250 miss with cooldown zero. This is a genuine two-hit sequence.

The initial S12 applies to B11655 and A11657. A's first subsequent travel starts grounded; B's starts airborne at approximately y=4.527. An A metadata update changes local sprint to false before A's next contact, so that contact does not multiply horizontal motion by 0.6. B's contact does. These are concrete trajectory differences that a replay must retain. The grounded/airborne category alone does not prove the cause: metadata, contact slowdown, launch timing, geometry and packet phase differ together.

**Failed trade for focused replay, m0404:** the paired fresh attacks are A12800/B12801. A releases W for its actual move at 13056 and resumes at 13100. B generates a native contact at 13250, which becomes a fresh server hit at 13300. A's next local contact is 13303, accepted fresh at 13346. B's return already exists before A's next hit; modifying only that hit's knockback cannot remove it.

B's cached native ray distance at 13250 is 2.99510165. That does **not** mean a 0.0049 m displacement denies perfect aim. Using B's recorded eye and current expanded A box, the minimum eye-to-box distance is 2.90818367 m, including a vertical gap of 0.85647186 m. With B's eye fixed, the smallest horizontal outward box translation reaching distance 3 is 0.09593811 m. Along A's actual coast-away axis it is 0.09661315 m, approximately vector `(-0.09364076, 0, +0.02378045)`. Strict all-aim denial requires exceeding that threshold. This calculation concerns B's **rendered box**, not A's true body position; the actual cached ray can also reflect an earlier frame.

The separately recorded [conditional coast response](trade-coast-response.json) tests this distinction. One extra A coast tick at 13003 moves A's 13100 position by about 0.06976 m, but quantization and one interpolation step transmit only about 0.02329 m of box displacement to B's 13250 view. The nearest distance becomes 2.92922 m. Coasting at all three moves 13003/13056/13100 gives 2.93910 m. Both remain reachable by suitable aim. These projections hold the recorded contacts, aim and packet schedule fixed; they neither prove the actual cached click would remain a hit nor constitute a whole-duel counterfactual. They reject the simple claim that one extra coast necessarily supplies enough all-aim spacing in this case.

## Conditions for producing a double

For a fixed factual event stream, let `C_B(c)` denote that B's native consumption at time c selects A and passes its local click gate. Let `P(c)` be its actual C02 processing event. A fresh B return requires:

`C_B(c) AND server-melee-legal(P(c)) AND H_A(P(c)) <= 10`.

The strong double is exactly A2 and A3 being accepted fresh with **no such B return in an earlier or shared server tick through A3**. This includes contacts generated before A2: the queue and packets in flight are part of the state. Damage upgrades require separate accounting because they can consume sprint and alter future motion without being fresh hits.

For robustness to B's physical click phase, excluding just the observed clicks is insufficient. Every permitted consumption time whose packet could be accepted before or with A3 must fail at least one condition above. A practical, stronger sufficient condition is:

1. No queued or in-flight B contact can become fresh within the protected interval.
2. Throughout all dangerous local consumption times, B lacks native contact geometry; eye-to-expanded-box distance greater than 3 is a useful all-aim sufficient margin, provided it applies to the view/frame actually used by native selection.
3. A still generates A2 and A3 at accepted fresh windows and retains pursuit.

This must cover the entire vulnerable return interval, including the period between S12 application and movement. A continued lock additionally needs a recurrence restoring those conditions after A3. The current observations do not prove such an invariant or show that it is impossible.

## Actionable control boundary

The legal place to shape spacing is **during the immunity interval before B's dangerous contact is generated**. Reacting to the new hit or its arriving knockback is too late for the 194 prompt returns above. With 150 ms delayed observation, a sub-tick or one-tick range window must be anticipated from older visible own motion/hurt cues and known issued inputs. The control must allow time for own movement, C03 transmission, tracker update, delivery and B interpolation. A native instantaneous packet or opponent-state oracle is unnecessary and impermissible.

The smallest initially plausible intervention was extending an existing release by one tick while keeping the physical click stream unchanged. The focused projection shows that it is **not yet a sufficient spacing rule**. A defensible next controller specification must predict a conservative B-view exclusion interval and preserve A's next two fresh contacts, rather than merely schedule a sprint reset near A2. An initial click-phase shift is a different intervention: it can change which short opportunities are sampled, but also changes local contact slowdown and subsequent trajectories, so it cannot by itself establish a universal movement rule.

To establish the requirements causally, replay the concrete success and failed pair from before their first divergence, preserving an explicitly chosen exogenous event schedule. Changed movement must generate changed C03 payloads, tracker sends, remote interpolation, aim/cached selection, contact slowdown, server attacks and knockback. Stop treating recorded contacts or S12 payloads as inputs at the first point where the intervention changes them. Then test the resulting rule across measured initial phases and latency bounds; a finite set of wins is still not an invariant certificate.

The [once-initialized conditional local replay](trade-local-replay-v1.json) passes all movement steps for all 14 clients in m0396 and m0403–m0408 without position or motion correction. That is strong evidence for accumulated local travel under the supplied S12/contact/sprint/yaw/acceleration boundaries. It does not supply the missing coupled-duel counterfactual automatically.
