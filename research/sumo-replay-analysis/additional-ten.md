# Ten additional Sumo match analyses

These matches were selected from the result metadata before opening their exchange traces. The sample includes five wins and five losses, with five opponents and bouts from 3 to 28 seconds. Each case uses the original settings and recordings. The ordinary W-tap opponent appears in m8774 and m8762. The [first ten](original-ten.md) define the two clocks, floor margin, and fixed-motion comparison used here.

## m8900 · Win vs best-sumo · 14.73 s

**The exchange.** You took a 0.95 launch at 5.334 s. You were aimed and sprinting before movement but your first brake came at +91 ms. You still slid 1.63. At the finish the reverse happened. On H49 you cut at +1 ms. At +94 ms your second cut also sent the packet that became H50. The bot took a 0.91 airborne launch with 2.55 blocks of floor left and slid 3.89.

**Forks.** At 5.334 s an added early brake saves 0.90 blocks, versus 0.20 by fixing the later aim miss. At the finish, removing your first cut adds 0.59 in only two moves. At the +94 ms press it moves your nearest-hitbox distance from about 2.60 to 3.18 with the displayed target frozen. That is where this defensive edit threatens the attack that actually won the round, so the branch stops. On the bot's side, removing the sprint bonus on H50 saves 1.82 and barely restores floor at the descent, by about 0.04 blocks. That last geometry is borderline.

**Judgment.** The clearest winning action was braking early enough to keep the next click possible. Sprint strength and the bot's late return miss helped convert that opening.

## m8886 · Loss vs best-sumo · 28.27 s

**The exchange.** H93 at 25.771 s moved you 2.79. You passed a +5 ms aimed sprint sample, then selected the bot at +78 ms with sprint off after a W release. H95 moved you 2.36 more. You did stabilize on H97. The first ~0.53 launch was braked and you recorded zero outward peak in the first six moves. H100 at 27.669 s was also ~0.53, but it arrived airborne. You missed at +77 ms with sprint off, then saw a live miss counter on the next two attempts. You slid 2.28 in six moves and outlasted that measurement window before falling.

**Forks.** H93's early brake saves 1.24, sprint on at +78 saves 0.86, and both save 1.76. The combined projected floor at H95 is 7.59 instead of 4.81. On H100 an early brake saves 1.01 and leaves about 0.86 floor at the actual missed landing. At +77 ms the closest box was about 2.81. Correcting both aim and sprint saves 0.80 and leaves about 0.72 floor. Correcting aim alone could clear the later miss timer but gives no immediate brake with sprint off. The later blocked presses were already roughly 3.37 and 4.65 away.

**Judgment.** The final loss still had an actionable brake. The earlier mistakes reduced your margin and the good H97 reply bought time. The miss timer explains later presses and is secondary to the first missed opportunity.

## m8620 · Win vs best-sumo · 6.23 s

**The exchange.** H15 at 4.520 s pushed the bot 2.43. It was aimed before first travel and did not click. Its +121 ms attempt missed even though the nearest box was about 2.55. H16 at 5.024 s found only about 0.67 floor straight along the launch. The bot drifted sideways along the shape and regained contact with the floor. H17 found just 0.05 blocks in the new direction and sent it 1.35. It got a brake before its first move and the same press registered H18, yet it still went off. You had 10.27 floor against that return.

**Forks.** A bot brake before H15's first movement saves 1.24 and gives it 3.29 instead of 0.67 floor at H16. Correcting the later aim miss saves 0.55 and brings that floor to 1.94. Removing your sprint bonus from H16 alone gives it about 1.44 instead of 0.05 floor at H17. That branch has only 0.005 of estimated reserve at the eventual descent, too close for a confident landing call. Reducing only H17 still leaves a six-move slide of about 0.52, substantially more than the 0.05 launch margin.

**Judgment.** The relevant opponent mistake came two hits before the win. The terminal bot counterhit and brake were real. By then it had almost no floor to spend.

## m9005 · Loss vs sumo-seven-first · 19.86 s

**The exchange.** The midround had separate failures. H33 at 9.003 s had a +5 ms selected press with sprint off. H52 at 15.031 s had sprint on but missed an approximately 1.38-block box at +14 ms. The key chain was H61 at 18.253 s. You slid 2.48 with no brake, moving from 3.47 floor to 0.095 by H62. H62 was only 0.67 blocks/tick. It pushed you out on first travel. The larger late movement from H63 began below the floor.

**Forks.** Match the fix to the failure. H33 sprint on saves 1.25, and H52 aim on saves 1.23. On H61 you had an aimed sample at +2.9 ms, with first travel at +12.3. A before-travel brake saves 1.25 and gives 1.84 floor at H62 instead of 0.095. The unchanged 1.45-block follow-up then leaves about 0.36 floor at the actual descent. At a +21 ms sample after first travel, the saving falls to 0.75 and the next-launch margin to 1.27. The later branch does not restore the same landing. Removing only H62's sprint bonus saves 0.47 and still leaves zero floor by H63.

**Judgment.** The highest-value local deadline was before the first movement on H61. The very short early sample is a cue for entering the trade ready to click.

## m8170 · Win vs sumo-seven-first · 3.25 s

**The exchange.** Your H5 defense took two brakes at +1.5 and +47 ms on a ~0.95 launch and slid 0.93. Your outgoing H6 was also strong, but the bot's early brake and second cut held it to 0.45. At H8, the bot was airborne with 4.71 floor. Its +9.5 ms press selected you around 2.50 away with sprint off. It traveled 4.20 in six moves and kept going.

**Forks.** Remove your two brakes at H5 and the peak climbs to 2.63. A later selected press then has a closest-point estimate over five blocks instead of 2.92. Turn on the bot's sprint at its actual H8 press and its 4.20 becomes 2.38, with about 1.68 floor on the descent. A normal-strength version of your H8 also lets the fixed path land, with 1.98 floor left.

**Judgment.** Both your sprint hit and the bot's missed brake mattered. H6 is an in-match control for what the bot's early cut can do. On H8 it had a real chance to brake while still in reach and missed it.

## m8948 · Loss vs sumo-league-001 · 6.98 s

**The exchange.** H14 at 5.476 s could already have ended the fight. You braked at +8 ms and the same press became H15. You held that slide to 1.19 with 2.18 floor at launch. You both scored in the last trade around 6.374 s and each took a ~0.95 push. You had 2.48 floor and the bot had 9.39. Your first +101 ms attempt missed and the timer climbed to 9 by +150 ms, when the nearest box was about 3.35. Your slide was only 2.41 at six moves and crossed the boundary shortly afterward.

**Forks.** Without your H14 brake you would add 1.25 to the six-move peak and lose landing support on the recorded route. In the terminal trade an aimed sprint sample at +13 ms on H16 gave a possible brake worth 0.75. It leaves about 0.67 floor at the factual failed descent. If the bot made the corresponding early contact on H17, its gain would also be about 0.75 blocks. It was already safe either way.

**Judgment.** Equal launch strength and equal slides gave very different outcomes because the two sides had different floor margins. There was a useful before-first-opportunity correction on your side of the trade.

## m8774 · Loss vs W-tap · 11.58 s

**The exchange.** You defended the opening well. Two cuts at H1 held 0.95 to 0.86. Near the finish, you braked H28 at +74 ms, made a selected sprint-off click at +156 ms, then got a third press to cut at +264 ms. H30 and H31 then came without an observed brake. H30's first press was ~3.06 by the frozen view. H31 found only 2.97 floor and sent you 4.10 in six moves, with its first actual attempt at 3.45.

**Forks.** Removing both H1 cuts changes 0.86 to 2.38. Fixing the H28 +156 ms sprint flag saves about 0.22, versus 0.86 from the sampled before-travel brake. Doing both saves 0.97 and increases the floor margin at H30 from 4.92 to 6.18 with the next attack fixed. The immediate finish had no comparably strong demonstrated click fix. Replacing H31's sprint launch by normal reduces the slide by 2.03 and restores about 0.45 floor on the recorded descent, confirming the size of the terminal pressure. It is a diagnostic of the opponent's hit strength.

**Judgment.** Against plain W-tap you can already manage the first trade. When subsequent clicks are beyond reach, the earlier opportunity from H28 deserves more attention than the late timer cues at H30–31.

## m8762 · Win vs W-tap · 27.34 s

**The exchange.** H37 at 13.133 s was an early-cadence and sprint failure. You slid 2.53 after leaving an early opening unused and taking the +73 ms contact with sprint off. Compare H73 at 26.080 s, when two cuts held a similar launch to 0.92 and the first cut also became outgoing H74. Your H74 pushed the bot 1.73, and H76 at 26.727 found it with 1.52 floor. The bot's first actual H76 press was ~3.52 away and the slide ran 2.31.

**Forks.** On H37 the early brake saves 1.25, the sprint correction 0.75, and both save 1.65. On H73 removing your two brakes adds 1.52 and creates a later reach violation. On the bot's side an early H74 brake would add 1.26 floor at H76 and might leave just 0.08 on the eventual descent. There was a stronger second chance. On H76 it had a selected sprint sample at +15 ms before its first move. That cut saves 1.23 and leaves about 0.50 landing margin. The next aimed sample, at +34 ms after travel, buys 0.74 and does not restore the same landing.

**Judgment.** More than one early window could have changed the local outcome. The terminal bot miss was largely a consequence of waiting until +146 ms. The first-travel boundary explains why two windows just 19 ms apart had different value.

## m5922 · Win vs mmc13-trades · 6.58 s

**The exchange.** You traded normal hits and defended the bot's H19 with a +1 ms brake, followed by smaller cuts. Your last major attack was H20 at 5.971 s, an airborne 0.84 launch. The bot was still over the platform with 3.81 floor at receipt, took 3.68 blocks in six moves and continued past the edge. The immediately following H21 was its trade response on you, from which you moved 1.04. The raw H20 hit and knockback rows were one millisecond apart. Matching only identical timestamps would skip the actual winning hit.

**Forks.** Removing your early cuts at H19 raises the six-move peak from 0.43 to 1.19, with the first alone worth about 0.51. On H20 the bot had a selected sprint sample at +8 ms, before its first movement. That extra brake would save 1.62 and leave 1.52 floor on the recorded descent. It got another sampled chance at +73 ms. The modeled saving is still 0.92, with 0.76 floor at the descent, holding its attack decisions fixed.

**Judgment.** It was a trade won on airborne travel and readiness to brake. The bot's accepted reply at essentially the same server instant did not undo its missed local chances. The older preset used here has a different sprint-direction law, so the current v4 same-hit sprint swap is unavailable.

## m5919 · Loss vs mmc13-trades · 10.08 s

**The exchange.** At 6.272 s a +52 ms selected reply had sprint off after a W release and you slid 3.84. At 8.171 s H24 had an early on-target opening with no click in the slide window. You took 2.49. At H26 you braked at +99 and +292, and selected at +192 with sprint off. The bot braked your answer H27 and its reply became H28 at 9.675 s. That reply was a normal 0.53 with you high in the air, carrying you 2.28 against 2.05 initial floor.

**Forks.** Sprint correction at 6.272 s saves 1.30. In the terminal chain an extra H26 brake at its +17 ms sample saves 0.66 immediately and raises floor at H28 from 2.05 to 2.80. With the recorded H28 launch preserved, about 0.49 floor remains at the factual missed descent. Correcting only H26's +192 ms sprint saves 0.22 and leaves just 0.06 floor on that descent. H28 itself had a before-movement brake opportunity. It saves 1.01 and leaves 0.76 floor. Sprint on at the actual +91 ms selected press, about 56 ms after W went up, saves 0.80 and leaves 0.55 floor.

**Judgment.** This one offers two different rescue points and a repeatable practice target. Prioritize being sprint-ready during the opening of a received hit. There is enough evidence to show an earlier single improvement would have bought floor even if the bot still hit you.
