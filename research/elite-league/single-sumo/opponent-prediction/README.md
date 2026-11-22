# Predicting opponent inputs in Sumo

The prediction uses one client's observations and previously issued controls.
The other client's controls provide labels offline. At each input decision,
the targets are the opponent's next forward/sprint and strafe choices, and any
physical attack or jump request during the next 100 ms. Windows beyond the
recorded duel or without a recorded opponent input are excluded. The evaluation
uses the first 320 observer input decisions of each duel, both seats, the
137-block MMC floor, a 7 CPS cap, and all four 31/109 ms ping pairs.

The frozen support set has 256 simulated duels per opposing policy. The
validation and test sets have 64 and 96 per policy, respectively, with disjoint
timing recordings. Opponent families, seeds, tracks, and recordings are
registered in [protocol.json](protocol.json). The recordings were previously
collected for other research. These are never-example transfer checks for
this predictor.

## Comparisons

- The generic MLP receives four own observations covering the last eight
  input ticks, spread across that span. It has no persistent memory.
- The in-context GRU uses the same short context and a recurrent summary of
  older observations. Its memory updates through the duel at frozen weights.
  The memory reset control zeros only that older summary.
- The specialist MLP receives the same short context. Each separate model
  trains on only its opponent's 256 support duels.
- The shared ID MLP receives short context and the verified one-hot opponent
  identity. All 20 identities use the same shared weights.

The matched models see the same 256 support duels per opponent. Shared models
have eight training epochs and specialists have twenty, with each checkpoint
selected on validation loss. The strict unseen comparison removes seven
policies from three complete families from both training and validation for
the generic and recurrent arms. Those families are also absent on the observer
side. The identity-aware and specialist scores on those bouts describe the
advantage of their separate labeled support and known identities.

We report mean cross-entropy per action in natural log units, averaged over
decisions within each bout, bouts within each opponent, and the frozen families
with equal weights. We separately score the aftermath of a received hurt cue
at close range, forward/sprint, strafe, attack, and jump. The recorded W-tap
opponent has its own forecast scores. Bootstrap intervals resample whole
timing recordings jointly across the fixed opponent field.

The hidden-switch panel uses matched continuations with a tactic change at
2 s. The two policies both receive the realized prefix while only the active
one controls movement. The native panel scores archived Forge bouts from their
actual two-client decisions. The native translation maps arena positions to
the simulator's fixed arena frame and censors lobby or unrecorded tails.
The selective follow-up keeps the generic forecast on forward/sprint and jump
and uses older history for strafe and clicks on separate timing recordings.

## Results

The three simulator panels contain 8,320 duels, with 1,108,195 training,
278,943 validation, and 407,028 test decisions. In the overall test, 86.6% of
next movement labels request forward with sprint, 78.6% have no strafe, and
98.4% have no jump request. The rare decisions and calibrated probabilities
are therefore better measures than raw accuracy. Values below average two
independently initialized fits.

| Predictor with matched support data | 20-opponent simulator test | Archived native test | Simulator W-tap alone |
| --- | ---: | ---: | ---: |
| Generic short-context MLP | .3739 | .3844 | .3261 |
| In-context GRU | .3535 | .3644 | .2980 |
| One MLP per opponent | .3125 | .3207 | .2126 |
| Shared MLP given opponent ID | .3139 | .3263 | .2226 |

The simulator panel includes 1,920 duels on 12 timing recordings. The native
score comes from 282 perspectives in 182 archived matches against six known
opponents. The simulator models were frozen before prediction on native
observations. The native measurement uses the extracted in-arena decision
history. An initial coordinate-mismatched score was retained separately and
excluded from this comparison.

### Opponents absent from training

The strict simulator test covers 672 duels and 139,612 decisions against
seven policies in three families. The generic MLP scores **.5497**, the GRU
with intact history **.5794**, and the same GRU with old history reset
**.5432**. The generic beats the intact GRU by .0305 and .0289 in the two
fits. The respective paired 95% timing-donor intervals are [.0206, .0374]
and [.0204, .0350], conditional on these opponent families. The gap persists
in the close-range 350 ms after an own hurt cue (.5314 versus .5596).

For those unseen opponents, intact history changes generic log loss from
.8705 to 1.0055 on forward/sprint, .6282 to .6117 on strafe, .6263 to .6238
on attacks, and .0738 to .0768 on jumps. The largest forward/sprint loss
comes from population-01, followed by shift-train-07. Sumo Seven's two
policies improve on the simulator panel. On the same two policies in 66
archived native perspectives, the GRU also improves overall (.5808 to
.5669), while resetting memory scores .5830. These perspectives represent
one held-out family.

The hidden-switch panel has 384 paired switch/stay fixtures. Prefix
observations and labels match before the switch can affect the forecast.
For the first 300 ms after the switch, loss is .5737 for the generic model,
.6005 for the GRU and .5513 with memory reset. The GRU is still behind the
generic model over the next 500 ms (.5494 versus .5020).

### Selective use of memory

On 12 further never-example timing recordings and the same seven opponents,
the generic model scores .5484, the full GRU .5779, and the selective model
**.5435**. Resetting the selective model's older history gives .5508. The
paired generic-minus-selective gains are .0054 and .0045, with timing-donor
intervals [.0032, .0075] and [.0027, .0063]. The selective model uses memory
for strafe and attacks. Forward/sprint and jump predictions come from the
generic model. This design was chosen after the first panel and evaluated on
separate clocks. It shows a small transferable gain across clocks for
specific outputs. Whole-policy prediction on the broader unseen field
still favors the generic model.

## Reproduce

```sh
python3.11 research/elite-league/single-sumo/opponent-prediction/prepare.py
python3.11 research/elite-league/single-sumo/opponent-prediction/train.py
python3.11 research/elite-league/single-sumo/opponent-prediction/switch_panel.py
python3.11 research/elite-league/single-sumo/opponent-prediction/selective.py
python3.11 research/elite-league/single-sumo/opponent-prediction/native.py
```

The task files, preprocessed datasets, weights, and reports are under
`build/elite-league/single-sumo/opponent-prediction/`.

## Arena-centered & family-CV update (`opponent-prediction-v2`)

Two bugs in the initial pipeline were fixed while preserving the original
evidence above untouched:

1. **Arena centering:** `PredictionTrace.java` and `native.py` previously fed
   world coordinates around `(4096.5, 12, 0.5)` into the radial/arena-relative
   features (`f[12..15]` had mean ~4096 / std ~3360, drowning out ±6-block ring
   edge geometry). Both now subtract the legal head-0 arena midpoint and floor Y.
2. **Unseen early stopping & GRU regularization:** `unseen_mlp` and `unseen_gru`
   previously early-stopped on `validation[~unseen]` (the same 13 seen training
   opponents). They now choose stopping epochs via 3-fold held-out-family CV
   across the 6 seen families, with 0.25 memory dropout and 0.02 weight decay
   on `unseen_gru`.

Averaged across both seeds on the identical panels (`build/elite-league/single-sumo/opponent-prediction-v2/`):

| Predictor | 20-opp sim test (v1 → v2) | Archived native test (v1 → v2) | Unseen sim (v1 → v2) | Unseen native (v1 → v2) |
| --- | ---: | ---: | ---: | ---: |
| Generic short-context MLP | .3739 → **.3635** | .3843 → **.3714** | .5497 → .5531 | .5808 → **.5793** |
| In-context GRU | .3535 → **.3460** | .3644 → **.3573** | .5794 → **.5730** | .5669 → **.5564** |
| One MLP per opponent | .3125 → **.2939** | .3207 → **.3021** | — | — |
| Shared MLP given opponent ID | .3139 → **.2987** | .3263 → **.3106** | — | — |

On the hidden-switch panel (first 300 ms after a tactic change at 2 s), `unseen_gru`
improved from `.6004` to **`.5801`** and `unseen_mlp` from `.5736` to **`.5532`**.
On archived native unseen opponents, `unseen_gru` improved from `.5669` to **`.5564`**
(beating `unseen_mlp` `.5793` by `.0229` nats). On the broader 7-policy simulator
unseen panel, `unseen_gru` improved by `.0064` nats (`.5794` → `.5730`) and
after-hurt loss improved by `.0090` nats (`.5596` → `.5506`), while still trailing
`unseen_mlp` on full unseen generalization across all four actions.
