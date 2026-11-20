# A sword bot learned to jump when hit

**Choose option 5 in Start Modern Sword Lab.command** to fight the policy trained from random weights on the final Java 26.3 simulator. It won 19 of 24 scheduled fights against three opponents in actual Minecraft. The matched W-tap control won 5 of 24. Its clearest learned movement is timing a grounded sprint jump to the receipt of knockback. [Watch four native bouts](<evidence/random26/Watch the self-play bot.html>) · [Playing weights](evidence/random26/fighter.net)

## From random weights to a playable policy

This run used the standard `python3 research/sword26/train.py` entry point. All opponents, parents and intermediate starting states came from its own games. The agent chooses physical keys and a heading offset from its own client observations. Every agent gets the same bounded aim assistance. Population fitness uses a win or loss plus half the normalized final health difference. The recurrent PPO stage uses changes in health difference and the terminal outcome. Checkpoint selection uses results against learned opponents. W-tap, the video and the technique audits are downstream evaluations.

The run scored 3,087,680 training fights, plus the games used to sample mid-fight starts. A tournament played another 117,504 duels to select among 102 snapshots. The winning policy uses six small trees. The entire training and selection run took **209 seconds** on the M2 Max with the runtime already installed. Its first independent evaluation action took place after saving the weights. [Registration](evidence/random26/protocol.json) · [Selected snapshot and hash](evidence/random26/selection.json) · [Comparison to stock Java](SIMULATOR.md)

## Whole fights

There are 96 model bouts per opponent and eight real Java bouts per opponent, equally divided between zero and 100 ms added RTT. The two Java openings at each latency were played in both seats, with matched W-tap arms. The columns show wins. Both model and Java record the health remaining at each individual opening. [Model against W-tap](evidence/random26/grade-wtap.csv) · [Model against the retreating rival](evidence/random26/grade-population.csv) · [Model against the league fighter](evidence/random26/grade-fighter.csv) · [Native 0 ms scores](evidence/random26/native-0-scores.csv) · [Native 100 ms scores](evidence/random26/native-100-scores.csv)

| Opponent | Model bot / 96 | Model W-tap / 96 | Java bot, 0 / 100 ms, each out of 4 | Java W-tap, 0 / 100 ms, each out of 4 |
|---|---:|---:|---:|---:|
| Plain W-tap | 93 | 48 | 4 / 4 | 2 / 2 |
| Retreating and strafing fighter | 54 | 7 | 4 / 1 | 0 / 0 |
| Earlier league-trained fighter | 60 | 30 | 4 / 2 | 0 / 1 |

The bot won all 12 zero-latency bouts and seven of the 12 at 100 ms. The retreating opponent gave it the most trouble at 100 ms. The data cover local opponents and a fixed kit. Human-tier strength remains unmeasured.

In the separate ordinary TCP confirmation against the league fighter, this bot won **2/8**, one of four at each latency. W-tap won **1/8**. The league fighter therefore won six of eight under free-running client and server timing, reversing the six-of-eight result in the scheduled panel. The panels used independent openings as well as different clocks, so they cannot attribute the reversal to latency or timing alone. Both policies are playable. Option 4 is the league fighter and option 5 is this from-scratch policy. [Ordinary TCP registration](evidence/random26/ordinary-protocol.json) · [Scores and HP spread](evidence/random26/ordinary-scores.csv) · [Ordinary TCP recordings](evidence/random26/ordinary-traces.tar.gz)

The jump behavior was observable with both clocks. Against that same league opponent, a new grounded jump coincided with 32 of 60 drops in the bot’s own sampled HP under ordinary TCP and 36 of 60 under the scheduled clock. An HP change observed between two client polls is a timing proxy. Exact packet-delivery alignment and one-tick counterfactuals use the scheduled recordings. [Client HP and jump observations for both arms](evidence/random26/tcp-damage-observations.csv)

## What the jump decision responds to

The learned jump tree branches on the client’s own hurt timer. Across the 24 native bouts, while the bot had at least 12 HP, it pressed jump on **624/624** input ticks with at least three hurt-animation ticks remaining and on **0/1,031** other ticks. It pressed forward throughout and recorded 60 critical hits. Seven damaging sprint hits landed on the rise from its own jump without an intervening incoming impulse. The training objective contains no term for these actions. [Hurt-conditioned inputs with W-tap comparisons](evidence/random26/jump-by-visible-hurt.csv) · [0 ms contact signatures](evidence/random26/input-patterns-0.csv) · [100 ms contact signatures](evidence/random26/input-patterns-100.csv)

Of 170 fresh hits the bot received, 102 coincided with a **new** grounded jump press on the first client poll at which the knockback arrived. I replayed each of those 102 native histories twice in the validated simulator. The recorded input reproduced the actual next position exactly in all 102. The comparison changed only that one jump press, with the same position, velocity, view, input history, network queues and all other controls. In **100 of 102** cases jumping reduced the bot’s outward movement along the line away from its opponent. The median reduction was **0.20 blocks on that tick**. W-tap had no grounded jump edges on its corresponding receipt ticks. This measures the movement consequence of a learned jump reset. It can reduce the first tick of displacement while some knockback remains. [Matched 0 ms forks](evidence/random26/matched-jump-forks-0.csv) · [Matched 100 ms forks](evidence/random26/matched-jump-forks-100.csv) · [Fork implementation](JumpFork.java)

## Does reacting to damage improve the result?

After the weights were frozen, two comparison policies were made by fixing the hurt signal seen by the jump tree. One always sees zero and the other always sees a recent hit. Both retain the original tree’s other inputs, all the other control trees, and the aim helper. This intervention never entered training or selection. The table gives wins from the same openings, 96 per model opponent and eight per real opponent. [Intervention protocol](evidence/random26/jump-sensor-protocol.json) · [Java 0 ms](evidence/random26/jump-0-scores.csv) · [Java 100 ms](evidence/random26/jump-100-scores.csv)

| Jump tree’s hurt signal | Model vs league | Model W-tap vs league | Java vs league | Java W-tap vs league | Model vs W-tap | Model W-tap mirror | Java vs W-tap | Java W-tap mirror |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Actual client signal | 56 | 30 | 7 | 4 | 88 | 48 | 7 | 4 |
| Fixed to zero | 43 | 30 | 1 | 4 | 78 | 48 | 8 | 4 |
| Fixed to recent hit | 36 | 30 | 2 | 4 | 91 | 48 | 7 | 4 |

Against the earlier league fighter, the learned response helped on these openings. Against W-tap, the three versions won seven, eight and seven bouts. Timing the jump offers a matchup-dependent advantage. Across the original 24 fights, two of 14 quick boosted replies were issued after the client could see incoming damage. In none of those 14 cases did the three-poll audit find an earlier fully charged, aimed opportunity where the bot withheld the swing. Deliberate hit selection remains unproven.

## Established combos

In the 24 scheduled bouts the bot established a three-fresh-hit streak twice and its W-tap control once. Neither reached five or ten. The two learned continuations each ended on a return ten ticks later, with zero further cycles. W-tap received a return on the entry tick. The two arms’ entry histories differ. In a separate model comparison the retreating rival allowed eight common three-hit states. From exact forks of those states, the bot and W-tap added seven more fresh cycles apiece. The league fighter and W-tap allowed zero such entries on their model opening panels. [Full-state model forks, with both stop rules](evidence/random26/maintenance-population.csv) · [0 ms native audit](evidence/random26/native-0-audit.json) · [100 ms native audit](evidence/random26/native-100-audit.json)

In a separate native test against the retreating rival, both arms used plain W-tap until three consecutive fresh hits. Each arm had six starts at zero added latency and six at 100 ms. None reached three, so the planned switch to the learned controller never occurred. Those bouts supply no native comparison of play after establishment. [Registered protocol](evidence/random26/maintenance-protocol.json) · [Zero-latency starts](evidence/random26/branches-0-branches.csv) · [100 ms starts](evidence/random26/branches-100-branches.csv)

Across all regular scheduled fights, the bot had geometric reach at 107/190 scorable native fresh-damage windows, with exclusive own-view reach in 18. W-tap had reach at 27/205, exclusive in 11. Neither arm delivered fresh damage on the exact opening tick in those windows. Round end censored 23 and 15 windows respectively. These counts stay separate from the maintenance comparison.
