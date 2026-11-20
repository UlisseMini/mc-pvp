# Single-match sumo league

Every match starts from the eight-block opening on the recorded 137-block MMC floor. Both players have a seven-CPS physical ceiling. Each controller acts at the local input head and sees its own client observations. A match ends on ring-out or after 120 seconds. A simultaneous ring-out or timeout scores half a win.

The [experiment plan](../sumo-league-proposal.md) defines the training and promotion rules. The [frozen protocol](evidence/protocol.json) identifies the clocks and archive. The [population](../sumo-inputs/archive-field.tsv) starts with 17 learned checkpoints and three explicit controls. Nomophobia's games are development evidence. Independent elite-human performance has no score yet.

## Training and transfer

[learner.py](learner.py) adapts CleanRL's recurrent PPO training loop to complete Minecraft duels. PyTorch differentiates a 32-unit GRU from spawn. A 180-unit softsign MLP has the same 5,766 trainable parameters. Both predict the same five physical action scores and a training value estimate. Every match resets memory and independently samples both players' RTTs. Java applies the stochastic buttons in simulation and in the Forge 1.8.9 client. The [upstream CleanRL source](https://github.com/vwxyzjn/cleanrl/blob/fe8d8a03c41a7ef5b523e2e354bd01c363e786bb/cleanrl/ppo_atari_lstm.py) and [license](CleanRL-LICENSE) identify the implementation used for reference.

[league.py](league.py) resumes from the atomic training checkpoints and immutable fixture manifests. The development tournament reuses paired matchups only with the same model hashes and protocol. Native cohorts preserve request IDs and invalid games. Each full generation records a tournament winner and a separate decision about the live title. The server alias for a tournament winner is `sumo-league-NNN`.

## What has been verified

Replaying 897 recorded local input heads on the Forge Java 8 bridge and simulator Java 17 produced identical features and buttons for both architectures. The Python forward pass matched the Java scores to within 0.000001 on the initial test episodes. An unchanged GRU continuation reproduced both players' outputs and random streams and the full clock state at 210 server boundaries. [Trace evidence](evidence/runtime-parity.json)

Against the frozen incumbent, the first specialist trained from scratch for 16,384 duels. The native audit replayed both clients' controls. Wins, draws and losses below are from the challenging side. Each native seat and RTT cell has just one game per arm.

| Same opponent at 7 CPS | GRU wins / draws / losses | Plain W-tap wins / draws / losses |
| --- | ---: | ---: |
| Development simulator, eight donor clocks | 6 / 2 / 248 | 64 / 0 / 192 |
| Main Forge server, eight seat and RTT cells | 2 / 0 / 6 | 4 / 0 / 4 |

The first attempts typically ended early. The specialist's median simulated fight lasted 3.23 seconds against 9.12 for W-tap. Suppressing jumping in matched model continuations increased mean fresh hits dealt from 2.60 to 10.65 and produced 3 wins in 256 games. Replacing the first 20 decisions with W-tap produced 2 wins. These interventions explain part of the difference in trade length. They did not recover W-tap's ring-out performance. [Simulator results](evidence/g001/counter.json) · [Input interventions](evidence/g001/opening-diagnosis.json) · [Native control audit](evidence/g001/native-exploiter/verified.json)

## How to read the adaptation results

A higher win rate by itself says little about what the bot remembered. First, a matched continuation experiment checks whether the better response actually reverses between two opponent habits. Only a verified reversal opens the frozen memory test. The test preserves both players, world state, clocks, RNG streams and recent inputs. It replaces the GRU's older memory with a matched donor from the same or the opposite style, or zeros its state. A sham swap must reproduce the original outcome. A pre-contact control checks that the assigned future style cannot change the hidden state before the opponent reveals it.

Each generation writes the development reversal results, coverage, held-out branch outcomes if eligible, and the evidence for or against a payoff from history. Its native and independent-human results remain separately identifiable.

## How much does knowing the opponent help?

The referee can give an experimental MLP the identity of its opponent before the duel. The shared network learns a separate input bias for each of 20 opponents. A second model routes directly to one of 20 independent MLPs. A third trains on the same fights with the identity hidden. All three start from the same 180-unit controller and use the same physical observations, match schedule and seven-CPS limit. Evaluating the shared model twice on the same fights, once with the correct ID and once with a deliberately wrong ID, tests whether it uses that information. [Opponent-ID protocol](evidence/id-oracle/protocol.json)

A dedicated 180-unit MLP trains exclusively against the frozen `v4-ppo-g0496` checkpoint that held the `best-sumo` title when the experiment started. Its first 204 and first 820 fights reproduce the training fixtures for that exact opponent at the two shared-model milestones. Its final checkpoint has received all 16,384 fights against that opponent. A shared 360-unit model starts with the same input-output function as the 180-unit model. The width comparison helps distinguish representational limits from the advantage of playing one opponent more often. Dedicated and mixed PPO updates contain different batches, so a specialist advantage alone also admits an optimization explanation. [Frozen specialist comparison](evidence/id-oracle/capacity-protocol.json) · [Native transfer protocol](evidence/id-oracle/native-protocol.json)

At the interim checkpoint the shared model had trained on 4,096 matches. Each specialist had seen exactly the same training fixtures against its single opponent. Entries below are wins / draws / losses across 256 separate simulator bouts. Each row uses a different dedicated model.

| Opponent | Target training fights | Shared 180, correct ID | Dedicated 180 | Common initializer | Plain W-tap |
| --- | ---: | ---: | ---: | ---: | ---: |
| Frozen best-sumo | 204 | 250 / 0 / 6 | 241 / 0 / 15 | 235 / 2 / 19 | 64 / 0 / 192 |
| Sumo-seven-first | 205 | 69 / 37 / 150 | 13 / 116 / 127 | 20 / 60 / 176 | 12 / 0 / 244 |

The shared-minus-dedicated score gaps were +3.5 and +6.4 percentage points, with paired development-clock intervals of −1.0 to +8.0 and −2.9 to +15.8 points. Against sumo-seven-first the shared policy converted far more fights into wins, while the dedicated policy kept more fights from becoming losses. The eight donors measure timing variability for these particular trained checkpoints. [Frozen champion comparison](evidence/id-oracle/capacity-development/v4-ppo-g0496/u064.json) · [Harder opponent comparison](evidence/id-oracle/capacity-development/sumo-seven-first/u064.json)

Across the 20 opponents, the 180-unit shared network scored 87.6% with the correct ID and 87.3% with an incorrect ID. Its counterpart trained with the ID hidden scored 87.1%, the shared 360-unit model scored 87.5%, and plain W-tap scored 18.6%. Routing to independent heads made ID assignment more consequential. The correct expert scored 81.7%, compared with 74.7% when assigned a different opponent's expert. These figures use the interim simulator checkpoint and one training seed. [All matchup scores, seats, RTTs and paired contrasts](evidence/id-oracle/eval-u064-development/summary.json)

## First league tournament

Play the generation-one winner with `/duel sumo-league-001 sumo preset=mmc cps=7`. The 23 entrants played 70,656 simulated matches. The winning 180-unit MLP learned from 32,768 PPO duels, starting from scratch. It uses its own client observations. Its family-weighted field score was 87.7%.

| Opponent | Tournament winner W / D / L | Plain W-tap W / D / L |
| --- | ---: | ---: |
| Plain W-tap | 249 / 0 / 7 | 128 / 0 / 128 |
| Frozen best-sumo | 254 / 0 / 2 | 64 / 0 / 192 |
| Sumo-seven-first | 121 / 4 / 131 | 12 / 0 / 244 |

The weakest matchup is sensitive to ping. Against sumo-seven-first the winner scored 68.8% with 31 ms of its own RTT and 109 ms for its opponent. Reversing those pings reduced its score to 18.8%. Equal-ping scores were 51.6% at 31 ms and 53.1% at 109 ms. This 48.0% overall matchup kept the live title promotion gate closed. The tournament winner keeps its own permanent duel alias. [Tournament, including all seat and RTT cells](evidence/g001/tournament.json) · [Published controls](../../../policies/sumo-league-001.properties)

A separate recognition test trained classifiers to distinguish frozen best-sumo from sumo-seven-first during a common opening. At spawn the two opponents gave identical inputs and balanced accuracy was 50%. After 24 local decisions the current observation gave 51–53% accuracy across three trained classifiers. GRUs reading the preceding observations scored 74–100%. Every pair survived to this decision. Whether the information changes ring-outs requires testing different responses from the same match state. [Recognition results](evidence/id-oracle/recognition/results.json)

The interim specialist comparison also ran on the main Forge server. These 48 independent bouts included both seats and all four 31/109 ms ping combinations. Both participants used the seven-CPS cap. All 48 replays passed the physical-input audit. The shortest interval between actual clicks was 142.881 ms.

| Native opponent | ID-informed MLP W / D / L | Dedicated MLP W / D / L | Plain W-tap W / D / L |
| --- | ---: | ---: | ---: |
| Frozen best-sumo | 8 / 0 / 0 | 7 / 0 / 1 | 1 / 0 / 7 |
| Sumo-seven-first | 1 / 0 / 7 | 0 / 2 / 6 | 1 / 0 / 7 |

Both specialist draws came at 31/31 ms, once in each seat. Eight native bouts per arm can reveal a large failure and leave considerable uncertainty about smaller score differences. [Native per-seat and per-RTT results](evidence/id-oracle/native-u064/summary.json) · [Verified physical controls](evidence/id-oracle/native-u064/verified.json)

## The seven-CPS reference opponent

`sumo-seven-first` is the first completed seven-CPS PPO response to frozen `best-sumo` (v4-ppo-g0496). It uses the older 32-unit feedforward INPUT32 actor. Starting from the previous 20-CPS specialist, seed 1801 trained for 1,024 batches of 32 simulated duels. Development selection retained update 640, equivalent to 20,480 bouts after the warm start. Training varied both RTTs and seats. Ring-out was the reward. Use `/duel sumo-seven-first sumo preset=mmc cps=7` to reproduce its training cap. [Original schedule](../sumo-confidence/seven/protocol.md) · [Checkpoint selection](../sumo-confidence/specialist/fits/seven-ppo-specialist-1801.csv)

It won all 32 native pilot fights at 7/7 against its target. Plain W-tap lost all eight native reference fights. At 20/20 the unchanged specialist went 10–22 and W-tap went 1–7. In the matched 7/7 simulator pilot, the specialist got the first fresh hit twice and won 32 times. Replacing its forward/sprint and strafe commands before either hurt cue with plain W-tap reduced its score by 17.2 points. Suppressing a selected near-hit jump in 29 paired games increased outward travel over the following 250 ms by 0.34 blocks on average. Those interventions preserved all 29 wins. [Matched native games and model interventions](../sumo-confidence/seven/pilot/cap-transfer.json)

## Can history change a ring-out?

Two independently trained 180-unit response policies prefer different opponents. A learned GRU read 24 input heads from a common opening and then selected a response for the rest of the duel. Opponent names were used to train this classifier. Its deployed inputs consist entirely of the player's own observations. Each alternative continued from the same clock, both players and queued events. The paired results cover 512 regulation matches on eight development timing donors.

| Two-opponent average | Score | Plain W-tap from spawn |
| --- | ---: | ---: |
| GRU chooses a learned response from the first 24 observations | 64.65% | 14.84% |
| Better of those two fixed responses on training clocks | 62.70% | 14.84% |
| Last observation alone chooses a response | 56.84% | 14.84% |
| Replace history through head 18 with the opposite opponent, preserve the last six observations | 56.93% | 14.84% |
| Referee supplies the actual opponent ID | 69.92% | 14.84% |
| Strongest unrestricted fixed continuation, sumo-cap-ramp | 75.78% | 14.84% |

The GRU gained 1.95 percentage points over the better fixed learned branch and 7.71 points over the swapped history. The eight recording averages all favored intact history. The recordings came from concurrent matches within 30 seconds on one server, so confidence intervals treating the eight as independent overstate certainty. Stratifying independent fight seeds by fixed recording, starting seat and both pings gave paired 95% intervals of −0.43 to +4.34 and +5.19 to +10.24 points. The improvement over the fixed response was inconclusive in this sample. [Timing overlap and uncertainty](evidence/id-oracle/recognition/payoff/timing-uncertainty-dev.json) With access to all nine continuations, the strongest training-clock choice was sumo-cap-ramp against both opponents. This makes it possible to distinguish context-dependent improvement within a learned pair from the strength of the best available fixed strategy. The complete client controller reproduced all 512 counterfactual scores for each of five assignments. The Forge Java 8 and simulator Java 17 implementations returned identical actions on 897 recorded local observations for all five. A prospective timing panel and native ring-outs provide separate transfer tests. [Paired development results](evidence/id-oracle/recognition/payoff/routed-dev.json) · [Unrestricted comparison](evidence/id-oracle/recognition/payoff/dev.json) · [Executable client parity](evidence/id-oracle/recognition/payoff/client/parity.json)

The first prospective replication used 32 new recordings and 512 new bouts. Actual history scored 60.64%, the stronger fixed learned response 58.69%, swapped history 54.00%, and plain W-tap from spawn 15.53%. The original source matches overlapped in four groups over 253 seconds. An analysis pairing the two opponents within each independent simulation fixture and stratifying by seat and both RTTs gave **+1.95 points [−0.60, +4.50]** versus fixed and **+6.64 [+4.47, +8.81]** versus swapped (approximate 95% intervals for this schedule). This replication also leaves the margin over fixed unresolved. A separate frozen study uses 64 fresh action-seed replications per cell on the original clocks. Another enrolls 32 native timing recordings with at least 60 seconds between one donor's finish and the next donor's start. [Prospective results](evidence/id-oracle/recognition/payoff/routed-sealed.json) · [Timing-aware intervals](evidence/id-oracle/recognition/payoff/timing-uncertainty-sealed.json) · [Further replication protocol](evidence/id-oracle/recognition/payoff/replication/protocol.json)

On the eight original recordings, 8,192 fresh simulated bouts per controller resolved a small benefit from selecting a response. The score is wins plus half of draws, divided by games. Each independent fixture contained both named opponents and shared its seat, pings and button seeds across all alternatives.

| Opponent | GRU selects | Always use the fixed learned response | Opposite early history | Plain W-tap from spawn |
| --- | ---: | ---: | ---: | ---: |
| Frozen best-sumo | 90.95% | 82.81% | 86.67% | 25.00% |
| Sumo-seven-first | 37.87% | 43.60% | 27.42% | 4.69% |
| Equal mixture | 64.41% | 63.21% | 57.04% | 14.84% |

The paired gain was 1.20 percentage points over the fixed response (95% interval +0.64 to +1.76) and 7.37 points over swapped history (+6.83 to +7.90). Both prespecified comparisons also cleared zero after adjusting for testing two contrasts. Against sumo-seven-first, switching increased losses relative to the fixed response often enough to cost 5.74 points. These estimates apply to the two frozen opponents, the specific trained classifier and this timing schedule. [Frozen protocol](evidence/id-oracle/recognition/payoff/replication/protocol.json) · [8,192-fight results and confidence intervals](evidence/id-oracle/recognition/payoff/replication/fresh-seeds/summary.json)

The original recognition study had trained three classifiers on the same examples. As a secondary check, all three were scored on the same 512 fights from the 32 fresh timing recordings, keeping both response policies frozen. Their gains over the fixed response were +1.95 points for seed 621 (95% interval −0.60 to +4.50), +3.22 for 751 (+1.20 to +5.24), and +7.03 for 834 (+4.43 to +9.63). Plain W-tap scored 15.53% on these fixtures. These are paired estimates for individual classifiers. The 512 fixtures contribute once to each comparison. [Sensitivity protocol](evidence/id-oracle/recognition/payoff/classifier-seeds/protocol.json) · [Seed 751](evidence/id-oracle/recognition/payoff/classifier-seeds/sealed-751.json) · [Seed 834](evidence/id-oracle/recognition/payoff/classifier-seeds/sealed-834.json)
