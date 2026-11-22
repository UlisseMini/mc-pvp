# Cloning the most-recorded native Sumo MLP

The teacher is `v4-ppo-g0496`. It has 22 inputs, eight softsign hidden units,
five output scores and 229 weights and biases. The archive through match 9438
contains 1,147 client recordings under that name and 93 under the currently
identical `best-sumo` alias. Grouping published aliases by exact network gives
the next most-recorded MLP 512 client recordings. The teacher's original PPO
phase logged about 11.2 million actor decisions by generation 496.
[Original PPO log](../../recorded-mmc-frozen/range-model/training.jsonl)

We recovered 459,029 native input decisions in 1,120 perspectives from 1,109
completed Sumo fights. The reconstruction uses the teacher client's logged
observations and previous applied controls. The original network's proposed
actions match all included decisions. Twenty-five candidate perspectives had a
replay disagreement and were excluded whole. The recordings span 5, 7 and
20 CPS caps. The 629 training fights, 116 development fights and 133 test fights
are disjoint. Another 231 fights are against 12 opponent identities absent from
training and development. These retrospective tests measure action agreement on
teacher trajectories.

## Preregistered guess

I expected 50–150 varied fights to reach 99% exact agreement on all four
controls at once and 150–400 to reach 99.5%. The reasoning was that the
student receives the same 22 inputs and can represent the function with the same
229 parameters. The biggest uncertainty was coverage near rare decision
thresholds. [Frozen protocol](protocol.json)

## Results

Across three seeds, training the eight-unit student from recorded button choices
gave 91.32% exact agreement at 16 fights, 93.01% at 64, 93.19% at 256, and
93.71% at 629. The full-data spread was 93.43–94.10%. On opponents absent
from training and development it averaged 92.63%. Always taking the most common
training choice for each control scored 54.01% on the first test and 47.13%
on the held-out-opponent test.

With all the training data, the eight-unit student agreed on forward/sprint
98.87%, strafe 95.07%, click gating 99.70%, and jump request 99.93%. It got
69.35% of non-neutral strafe decisions right. Joint agreement on the training
rows was 94.13%.

A diagnostic follow-up reused the frozen test fights. Widening the student
to 64 units gave 97.08% across the full test and 96.27% on the
held-out opponents, using recorded actions alone. Equalizing the weight of
the teacher's five numeric score targets gave an eight-unit student 98.72%
with 64 training fights and 99.25% with 256. The corresponding
held-out-opponent scores were 98.46% and 99.13%. All three 256-fight fits
scored at least 99% on the first test. With 629 fights, one initialization
reached 99.76% and another reached 95.32%. Unscaled score regression
averaged 94.12% at 256 fights. The white-box score targets are recomputed
from the archived weights and native observations.

The 99% guess was too optimistic for the action-only training recipe.
Widening the model at a fixed data budget improved it, and richer score
targets crossed 99% with the original width. Exact imitation from button
labels remains open. The experiment measures action fidelity and has no
closed-loop ring-out or native win-rate score.

## Reproduction

From the workspace root, run with Python 3.11, NumPy, PyTorch, orjson and JDK 17.
The test manifest, arrays, weights and per-seed reports are kept under
`build/elite-league/single-sumo/native-distillation/`.

```bash
python3.11 research/elite-league/single-sumo/native-distillation/prepare.py
python3.11 research/elite-league/single-sumo/native-distillation/fit.py
python3.11 research/elite-league/single-sumo/native-distillation/fit.py --followup
```
