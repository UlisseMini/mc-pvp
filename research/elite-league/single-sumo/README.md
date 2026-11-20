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
