# How Gen 4 wins sumo, and what happened in the slow-motion room

## Your Minecraft fights

In nine normal-speed games with complete captures before the repair (m800000033–036 and 042–046), Nomophobia won seven. The fight clock and both clients were running at about 20 ticks/s. The bots' nanosecond click timer was still running at 0.3×. Seven permitted clicks per game second therefore translated into a ceiling of **2.1 per real second**. Across 180.08 seconds, Nomophobia made 1,223 attack presses (6.79 CPS) and Gen 4 made 276 (1.53 CPS). The same error affected the two Gen 2 matches, the Seven match, and both plain W-tap matches between those groups. The 0.3× game m800000032 is separate. Game 047 started at a different speed and lacks the bot POV; game 048 ended on a failed client-view check during deployment. Neither enters the comparison.

Gen 4 and Nomophobia were close in fresh damage, 259 to 260. The difference was what happened on and between those fresh hits. Nomophobia made 792 verified local sprint-contact brakes and delivered 177 sprint-powered fresh hits. Gen 4 made 260 brakes and delivered only 29 sprint-powered fresh hits. A brake is a local contact that cut horizontal velocity to 60%. It can slow inward movement as well as outward movement. Counts of brakes measure opportunities spent, not blocks saved. The human also scored 103 damage upgrades. Counting those as another full fresh launch would misdescribe the fight.

In m800000033, Nomophobia's fresh hit 31 launched Gen 4 at 0.9495 horizontal blocks/tick with only 0.953 blocks of floor left along its launch direction. The bot made a sprint brake immediately after taking that launch and landed hit 32. Its reply was an ordinary 0.5275 launch. The bot still slid 1.139 blocks in the measured prefix and fell. Both had three-block attack reach; the longest successful native bot ray observed across the nine games was 2.998 blocks.

The shared clock now reconciles the host's monotonic timer with the wall time used to schedule speed changes. A rejoining bot also receives the active server time anchor and pending transition at startup. In native match m800000049, after a live 0.3× → 1× switch, both clients' injection clocks ran at 1.000×. Gen 4's shortest interval was 148.0 ms and W-tap's was 143.8 ms (seven-CPS floor 142.857 ms). Gen 4 won by ring-out. Across the first five post-fix human duels, m800000050–054, Gen 4 went 5–0. Its injection clock measured 1.000× in every fight. Nomophobia made 400 presses in 59.271 seconds (6.75 CPS) and Gen 4 made 235 (3.96 CPS). Sprint powered 66 of Gen 4’s 74 fresh hits and 48 of Nomophobia’s 69. Game 052 lasted 28.854 seconds and Nomophobia led 37–36 in fresh hits. These five games verify the repaired timing across human duels; the sample is small for estimating win rate.

In m800000053 both players dealt 14 fresh hits. At 11.962 s, Gen 4’s hit 34 launched Nomophobia at 0.9494 horizontal blocks/tick with 2.756 blocks of floor left along the launch direction. Nomophobia’s return at 12.017 s carried 0.5266 blocks/tick with sprint off. Gen 4 had 9.240 blocks of floor along that launch and made a brake 65.1 ms after receiving it. Nomophobia’s later press missed 147.7 ms after receiving hit 34, and the following press at 242.7 ms encountered the miss timer. At 12.415 s the server recorded the start of Nomophobia’s final stretch off the platform.

## Seven and the league champions

The four-opponent comparison reproduces every winner and terminal time in the 256 original simulator fixtures per matchup. It uses the 137-block floor, eight development timing calendars, both seats, all four combinations of 31 and 109 ms RTT, and equal seven-CPS caps. Each edited policy receives new observations from the fight it actually creates. Both players keep making decisions. Percentages below use outright wins unless otherwise stated.

| Opponent | Gen 4 wins / draws / losses | Force clicks open after first visible hurt cue, wins | Hold W+sprint after first cue, wins | Disable jumps within 150 ms of own hurt cue, wins | Plain W-tap wins / draws / losses |
|---|---:|---:|---:|---:|---:|
| Sumo Seven First | 226 / 10 / 20 | 12 | 4 | 167 | 12 / 0 / 244 |
| Plain W-tap | 255 / 1 / 0 | 212 | 165 | 255 | 128 / 0 / 128 |
| Gen 3 champion | 153 / 8 / 95 | 6 | 2 | 39 | 0 / 2 / 254 |
| Gen 4 counter GRU | 124 / 12 / 120 | 1 | 2 | 37 | 1 / 0 / 255 |

**Against Seven, the repeated trade matters more than the first click.** Gen 4 landed the first fresh hit in 251/256 games. Allowing every click only until either player showed a hurt cue produced 231 wins. Allowing every click after that cue produced only 12. That edit increased physical clicks from 4.27 to 6.13 CPS. The share making contact fell from 82.3% to 57.4%, and sprint-contact brakes while moving away fell from 1.94 to 1.38 per incoming fresh hit. Forcing W+sprint after the opening reduced sprint-powered fresh hits from 98.0% to 9.1% and reduced mean outgoing horizontal velocity from 0.875 to 0.533 blocks/tick. Disabling jumps only outside the 150 ms own-hurt window preserved all 226 wins. The jump intervention tests the value of those timed inputs, not whether every jump changed the same knockback component.

In one equal-31-ms Seven replay, both the intact and click-edited Gen 4 landed the first fresh hit at 1.329 s and received Seven's reply at 1.533 s. The edited bot had spent an intervening press at 1.456 s on a miss. The intact bot was ready for a sprint-contact brake at 1.551 s, 18 ms after Seven's hit. The edited bot's next brake came at 1.598 s, 65 ms after the hit. The intact bot then landed the next fresh hit at 1.880 s, versus 1.929 s for the edited bot. The intact bot won at 16.345 s; the edited bot lost at 23.302 s. Those later outcomes also include every subsequent click change.

**Against W-tap the opening is reversed.** W-tap landed the first fresh hit in all 256 games. Gen 4 still won 255. It averaged 4.06 CPS and made contact on 74.0% of its presses. W-tap averaged 6.65 CPS and made contact on 38.7%. Per incoming fresh hit, Gen 4 made 1.76 sprint brakes while moving away, versus 0.85 for W-tap. Removing jumps had no effect on the overall 255-win count here.

**What improved over Gen 3.** Gen 3 already won 230/256 tournament games against Seven. Gen 4's gain was against the stronger recent opponents. On the exact same starting fixtures and with the same action seeds, replacing its Gen 3 parent's controls with Gen 4's raised wins against the Gen 3 champion from 111 to 153. Testing all 16 ways of assigning forward/sprint, strafe, click and jump to either network attributed the 16.4-point gain to forward/sprint (+6.9 points), click timing (+5.4), jump (+2.8) and strafe (+1.2), averaged over replacement orders. The pure parent and child branches reproduced outcome and end time in every comparison. The corresponding W-tap baseline went 0/256 against Gen 3 in the tournament.

Strafing remains a weakness. On a separate set of 128 new action seeds per opponent, disabling A/D raised Gen 4's wins against its own counter from 67 to 93 and against Gen 3 from 67 to 85. Against Seven, wins fell from 109 to 105; against the historical frozen PPO they fell from 127 to 120. Matched W-tap won 1, 1, 6 and 14 respectively. These are new action seeds on previously used development calendars. Plain W-tap and the Gen 4 champion were compared in native match 049; the control edits and Seven comparisons above are simulator evidence.

## Is it adapting?

Resetting Gen 4's GRU before every decision reduced wins against Seven from 226 to 3 and against Gen 3 from 153 to 1. Retaining only the six preceding observations before each decision recovered 223 wins against Seven and 118 against Gen 3. This is an intervention on a trained recurrent policy. It differs from training a fresh memoryless policy.

To test opponent recognition, we swapped in another game's older GRU state after 24, 100 or 240 decisions, replayed the recipient's last six observations, then continued from the same fight state. The donor shared the recipient's timing calendar, starting seat and both RTTs. Same-opponent and other-opponent donors came from a different repetition. A sham restored the original hidden state exactly and reproduced every original winner and finish time. Later results cover the matches that lasted long enough and had available donors.

After 100 decisions the bot won **585/830** with older history from a different game against the same opponent and **580/830** with older history from a different opponent. After 240 decisions the figures were **231/377** and **231/377**. At 24 decisions using the other opponent's history actually had five more wins (759 vs. 754 of 1,024). The learned timing depends heavily on recurrence. These tests provide little evidence of a durable opponent-specific strategy selected from older history. They leave open faster recognition from the last few observations.

[Per-opponent counts, interventions, native replays and timing measurements](results.json)

[Seven-CPS automatic Ctrl resets versus Gen 4 and W-tap](auto-sprint.md) compares the same frozen network in simulation and native Minecraft.
