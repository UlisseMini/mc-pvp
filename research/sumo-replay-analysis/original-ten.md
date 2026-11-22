# Ten re-reviewed Sumo matches

These are the ten matches from the original Sumo Coach review. The match clock starts when the fight opens. Times with a plus sign start when the velocity packet was applied on the receiving client. H49 means accepted server hit 49. Slides are peak travel along the launch direction in the first six available movement updates unless stated otherwise. Floor is distance until the player's footprint would stop overlapping a platform block along the indicated direction.

Each edited result is an estimate from the recorded motion, holding later movement inputs, attack choices and launches fixed unless specified. A click at an aimed sample shows the value of a contact at that moment. Some of those samples leave only a few milliseconds before the first movement. They are evidence for being ready to click into a trade. The recordings do not establish how quickly you could have reacted to the hit. The ordinary W-tap bot was an opponent in the additional set. These are individual match diagnoses under their original settings.

## m9186 · Win vs best-sumo · 4.38 s

**The exchange.** H2 at 0.821 s and H6 at 2.071 s were nearly equal incoming launches, 0.946 and 0.950 blocks/tick. H2 took 1.63 blocks after your first brake at +105 ms. H6 took 0.73 with brakes at +31 and +88 ms. The +88 ms attack reached the server and added no accepted damage. You still got the second local cut. Later your H7 pushed the bot 2.36 blocks, then H8 at 3.217 s found it with just 0.74 blocks of floor.

**Forks.** A before-movement brake on H2's early aimed sample would save about 0.86 more blocks. Removing both H6 cuts would grow its slide from 0.73 to 2.38. It also puts your later selected press at roughly 5.38 blocks from the same displayed target instead of 2.87. On offense, reduce H8 from 0.77 to 0.53 and the bot's slide falls from 1.11 to 0.36. At the actual next launch the same path has about 0.87 blocks of floor instead of zero.

**Judgment.** H8 created the floor loss. The bot did get a brake and an accepted counterhit after H9, but it was already outside the platform footprint. Its H8 return attempt was about 3.62 blocks from your displayed hitbox, so the useful bot correction had to come earlier.

## m9161 · Win vs best-sumo · 40.36 s

**The exchange.** H48 at 12.811 s put you back 2.80 blocks. You had a before-movement aimed sample. W was up for the first move. On the +93 ms click W was down again and the client still had sprint off. The server processed the press and it did no fresh damage.

**Forks.** An early brake saves 1.25 blocks. Enabling sprint on the +93 ms press saves 0.87. Accomplishing both saves **1.77**, and the later +243 ms miss goes from about 3.97 to 2.69 blocks to the frozen hitbox. Under the same follow-up, you gain 2.81 blocks of floor margin by H50. That also puts the bot closer when it attacks, so there is no basis to infer a denied hit. Compare H22 at 5.511 s, when two early brakes held an almost identical launch to 0.90 and saved 1.68 blocks. The later sprint-off presses had little speed left to cut.

**Finish.** H135 and H136 were two ~0.95 pushes along the same side. The bot's first H136 return press was already 3.16 away, with 2.23 blocks of floor left. The actual six-move slide was 2.36. With the same H136 contact at normal strength it would be 0.97, and on the actual missed landing step the edited position would have about 1.32 blocks of floor. An earlier H135 brake also buys about 1.22 blocks of floor at H136.

## m9159 · Loss vs best-sumo · 3.82 s

**The exchange.** Your opening push was a genuine 0.93 sprint launch. The bot cut it at +3 and +206 ms and slid 0.99. Your +2.5 ms reply on the bot's 0.86 launch selected it with sprint off. You slid 2.22 and still had over six blocks of floor at the start of that push. By H10 you had 1.64.

**Forks.** Remove the bot's two opening cuts and its 0.99 becomes about 2.29. Fix your sprint flag at H2 and your 2.22 becomes about 1.09, with about 1.59 more floor at H4 on the recorded route. The terminal H10 brake at +41 ms was already valuable. Removing it changes 2.34 to 4.16. An additional early brake at the next aimed sample saves about 0.83, enough to put the six-move peak around 1.51, but the edited path still has no supported landing within the comparable trace. Correcting the +147 ms miss alone saves only about 0.60.

**Judgment.** This one points to the opening asymmetry as well as the late loss. The late W release came after the biggest brake already worked. A six-move threshold by itself would have overstated the terminal fix.

## m9153 · Loss vs best-sumo · 15.17 s

**The exchange.** H49 at 14.060 s sent you 2.36 blocks. The client had the bot selected with sprint on at +17 ms, before the +40 ms movement. The first actual press was +90 ms, already about 3.16 from the displayed hitbox. H50 at 14.564 s was a normal 0.53 launch, covering 0.97 blocks against about 0.72 of floor. H52 arrived after you had fallen below platform height.

**Forks.** Brake at the H49 sample and the six-move slide changes to 1.11. The +90 ms press would have a nearest-hitbox distance of about 2.78 rather than 3.16 with the bot view held fixed. Propagate the shift into H50 and floor goes from **0.72 to 2.41**, against the same **0.97** of follow-up travel. At the actual missed descent, the edited path still overlaps the floor with about 1.50 blocks of margin. The bot's geometry at its linked H50 press gets closer, about 2.13 to 0.56–0.91 across sampled view delays. The branch buys floor even if the normal hit still arrives. It also opens possible return presses, where a full fight replay would diverge.

**Cross-check.** H23 at 6.167 s was an example of that defense working. Brakes around +40 and +84 ms cut a six-move peak from 2.41 to 0.76. Removing both leaves you beyond the platform on a descent step. The late finish is a stronger reason to practice the earlier contact than the final out-of-range miss.

## m9108 · Loss vs best-sumo · 11.56 s

**The exchange.** At 4.057–4.109 s you and the bot both delivered near-0.95 launches. The bot's +94 ms reply selected you with sprint off. Its only observed cut at +283 ms saved 0.09 and it slid 2.24. Your three brakes held your side to 0.89, saving 1.58. At 10.856 s you had only 1.94 blocks of floor and no actual cut against another 0.95 launch.

**Forks.** Give the bot sprint at +94 ms in the first trade and its slide shrinks about 0.69. Give it the early sampled contact as well and the combined saving is about 1.53. At the finish, an early sampled brake changes your 2.41 slide to 1.16. The first actual miss was +184 ms at 3.34 on the nearest-box check. With the early cut that geometry is about 2.57. Even keeping later presses and launches as recorded, the adapted path has 0.63 blocks of floor at the descent where the factual path had none. The previous H34 offered an earlier fallback too. A cut at its +10 ms sample would add about 0.56 floor by H37, but it was a smaller lever.

**Judgment.** The loss came from a missed early opening near the edge. The winning side of the earlier trade shows you can already execute the useful sequence.

## m9092 · Loss vs best-sumo · 51.68 s

**The exchange.** H46, H73 and H162 at 13.877, 21.525 and 48.178 s showed the same pattern. One W-up movement after receiving ~0.95, then a selected press at +67, +84 and +81 ms respectively with sprint still off after W resumed. All three slid 2.80–2.88 blocks. The H162 press even registered server hit 163 while failing to brake locally.

**Forks.** Sprint on at each of those actual presses saves about **0.87 blocks** in the first six movements. On H162 the same branch gives about 5.21 instead of 3.69 blocks of floor at the next incoming launch. At the finish, H168's two real brakes bought 1.06 blocks and the unbraked path loses floor on a descent before the next received hit. H170 at 51.081 s was a distinct miss. Your crosshair was already on target in the first rendered sample, W stayed down, and the +127 ms press was 3.49 away. A cut before first travel would take 2.36 down to 1.11 and leave about 0.52 blocks of floor on the actual missed landing step.

**Judgment.** There are two good drills here. Reproduce the recurring sprint reset timing, then practice clicking in an early window with W still held. The last loss has direct evidence for the second drill.

## m9051 · Loss vs sumo-seven-first · 1.85 s

**The exchange.** Three launches with zero accepted returns. After H1 your first press was at +72 ms and the nearest box was about 2.97 away, close enough to the limit that the archive cannot tell cleanly whether earlier aim or earlier timing mattered most. H2 at 1.227 s was only a 0.53 launch, but it arrived airborne with 2.28 blocks of floor. It took 2.07 blocks in the first six moves and continued drifting. H3 arrived once you were below the floor.

**Forks.** A normal-strength H1 would cut 1.36 blocks from the six-move slide and add about 1.75 blocks of floor at H2. The actionable correction is H2's +66 ms press, where sprint was on, the timer was clear, and the nearest box was about 2.86. If the same press selected the bot, the brake would save 0.76 blocks and leave about 1.01 floor at the actual descent. The later +207 ms attempt was already about 3.41 away, so aim alone there lacks the same case.

**Judgment.** Fix the first selectable return when you are already sprinting. W was held here. The terminal hit provides almost no useful training window.

## m9040 · Loss vs sumo-seven-first · 29.72 s

**The exchange.** Early double braking worked at H54. +7 and +58 ms held a 0.95 launch to 0.77. Sprint-off on the later +264 ms contact mattered about 0.02 blocks because velocity was almost spent. At H94 at 29.057 s you took a ~0.95 launch already airborne and cut it at +61 ms. That click's attack was handled with no accepted hit. The first six moves still covered 2.80 of the 2.98 blocks of floor. You continued past the edge on the eighth move.

**Forks.** Removing the +61 ms cut raises the six-move peak to 4.20. Adding a brake to the earlier before-travel sample lowers it to 1.55 and gives about 1.46 floor at the actual failed descent. Even the later +74 ms aimed sprint sample, after the first brake, would save 0.61 and leaves about 0.77 floor in the same local comparison. W did come up for one move, but the recorded +61 ms brake and +74 ms sprint-on sample are stronger explanations than the release alone.

**Judgment.** A second timely contact was more valuable than policing late sprint-off cues. The first brake and most of the damage already happened before the obvious out-of-range attempts.

## m8974 · Loss vs sumo-seven-first · 14.98 s

**The exchange.** H45 at 13.115 s took 2.32 blocks and left just 1.81 floor at H46. The normal H46 took another 0.97. You landed a strong outgoing hit at 14.171 s, but the bot braked twice and slid 1.20 in the first six moves. Your terminal H48 arrived during a takeoff and ran 4.12 against 1.60 floor. By the first actual press at +146 ms your displayed target was about 3.45 away.

**Forks.** The bot's two cuts on your last push saved about 0.68 blocks. An added H48 brake at the first selected sample saves **1.82** and moves that +146 ms attempt to about 2.73, where a second brake might become possible. The first brake alone still leaves a six-move peak of about **2.30**, greater than the starting floor, and does not give the local branch a supported landing. On the earlier H45, removing the bot's sprint bonus illustrates how much the setup mattered. It adds about 1.92 floor at H46. The H45 return clicks in the actual record were already beyond reach by the first attempt, so this diagnostic has no demonstrated one-click player fix at H45.

**Judgment.** H48 is the clearest example of a valuable isolated correction that still needs another contact or better prior positioning. Contrast H43, where you actually chained +39, +142 and +237 ms brakes. Removing the first one makes the third press geometrically unreachable in the frozen view.

## m8926 · Win vs sumo-league-001 · 12.09 s

**The exchange.** On H8 you cut a 0.95 launch at +2 and +43 ms and slid 0.78. On H21 at 5.481 s the first aimed early sample had no click. The +80 ms click missed at roughly 2.68 to the box and the +136 ms press was gated with the box already at ~3.43. That slide reached 2.44.

**Forks.** Removing H8's two real brakes gives 2.43 and makes a later selected contact's closest geometry about 4.96 instead of 2.42. At H21 the early brake would save 1.25, versus about 0.75 by correcting aim at +80 ms. Clearing the timer alone at +136 ms leaves the range problem. These are different fixes for the same slide.

**Finish.** The bot took successive normal hits H40 and H41 while airborne. Each traveled over 2.1 blocks in the six-move window. H41 had only 1.48 floor and the first return press was about 4.12 away. Giving H40 a sprint bonus adds about 1.55 slide, enough to remove the floor margin even earlier in the fixed-motion branch. H41 already had enough push. The strength of the finish was making the earlier hit count and keeping the bot out of return range.
