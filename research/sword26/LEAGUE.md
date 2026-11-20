# The continuing sword league

The league starts with the ordinary-Java champion from launcher option 4 and five earlier opponents. The learner controls ordinary keys with the same observations and aim assistance as those fighters. Only fight outcomes and health enter training.

In each season the trainers measure who beats whom, estimate a mixture of difficult opponents, and train a generalist, a response targeting the champion, and a population from random parameters. They also try different presses for one, two, four or eight ticks from the same complete self-play state, let both fighters finish, and fit key decisions from the resulting differences in fight payoff. Recurrent PPO joins every third season and the larger final search. The original simulator and game physics are unchanged by these trainers. [Training program](season.py) · [Paired action forks](BranchImprove.java) · [Run settings](evidence/ladder26/protocol.json)

A model checkpoint can become a training opponent immediately. Promotion into the launcher requires fresh ordinary Java matches in both seats and at both tested latencies. The challenger must win the direct matchup and outperform the incumbent and W-tap across the common opponent field. Close or split results trigger another independent panel. The historical bots remain in the launcher. Option 6 appears after the first successful promotion. [League coordinator](ladder.py)

The coordinator looks for three successive seasons without a meaningful generalist gain or a stronger counter to the model main. It then runs a larger independent challenge and grades the finalists in ordinary Java. A successful native promotion starts the search again. A finite plateau describes the tested league, model family and search budget.

Run `python3 research/sword26/ladder.py` from the project folder to start or resume the saved league. Completed season results and frozen weights are stored under `research/sword26/evidence/ladder26`. Training intermediates and native logs are under `build/sword26/ladder26`. A session lock prevents two coordinators from training the same league concurrently.

## Checking transfer during the league

After season 3 the leading simulated responses played 64 ordinary Java fights. All 64 began at the intended player positions. Counts below are wins on the same two opponents, openings, seats and latencies. The last row combines the two latencies.

| Added RTT | Season 2 challenger | Season 1 challenger | Option 4 | W-tap |
|---|---:|---:|---:|---:|
| 0 ms | 1/8 | 3/8 | 7/8 | 2/8 |
| 100 ms | 3/8 | 4/8 | 5/8 | 1/8 |
| Both | 4/16 | 7/16 | 12/16 | 3/16 |

The season 2 challenger won just one of its eight direct fights against option 4. The season 1 challenger split that matchup 4–4. [Per-bout scores and HP](evidence/ladder26/java-003-a/ordinary-scores.csv) · [Decision](evidence/ladder26/java-003/decision.json)

From season 4 onward the league also trains one response under a mixture of two clocks. In the second clock the server and both clients have independent phases. Packets travel in order and can arrive between the two clients’ input polls. Selection averages both clocks, both seats and both latencies. Each actor gets the same scenarios. Replay of the old scheduled Java recordings still reproduces the archived parity files byte for byte at both 0 and 100 ms. [Timing amendment](evidence/ladder26/timing-protocol.json) · [Clock implementation](PhaseClock.java)

On the 64 development fights the mean squared error predicting the Java result from its recorded opening fell from 0.313 to 0.234. Lower is better. The varying clock still overestimated the win rate of the season 2 challenger at 47% versus 25% observed. For W-tap it predicted 41% versus 19% observed. Its phases approximate local timing; they do not recover the actual packet schedule of an ordinary bout. [Per-fight forecasts](evidence/ladder26/timing-season-003.csv)

After the second Java wave, further promotion tests also accept a nominee selected by a small Java development tournament. Up to four responses play the incumbent on fresh openings at each latency and in each seat. The field includes contenders bred from the incumbent. The pilot winner joins the generalist and strongest simulated counter in the independent promotion test. A separate set of openings is reserved for the final replication. [Scouting protocol](evidence/ladder26/scouting-protocol.json)

## A learned opening against option 4

In the first season 9 Java panel the direct-response fighter, `1ed6988f`, passed up a charged, aimed swing before either player dealt damage in all 16 of its fights. It took the first hit in all 16. Its first reply was a critical hit 9–12 server ticks later. Across both opponents, 108 of its 109 accepted attacks were criticals. The matched W-tap control landed first in 13/16 and recorded zero criticals in 141 attacks. The fighter's training score used only the outcome and health. This is an observation of how it chooses trades. [Paired Java opening audit](evidence/ladder26/java-009-a/opening-audit.csv) · [Control implementation](Controls.java)

An offline intervention compared waiting with pressing once at the first skipped, charged and aimed opening. Each comparison replayed the entire prefix and let both sides react normally thereafter. The opposing fighter was option 4. There were 512 fresh fights per clock, balanced across seats and latencies.

| Model clock | Eligible opening forks | Original fighter wins | One earlier press wins | W-tap wins from the same state |
|---|---:|---:|---:|---:|
| Fixed | 496 | 423 | 455 | 121 |
| Variable phases | 428 | 374 | 349 | 118 |

The benefit of waiting changed sign with the clock. Native play establishes that the bot repeatedly chose this opening and won. The causal comparison above applies to simulated counterfactuals. [Fixed-clock forks](evidence/ladder26/opening-season-009-off.csv) · [Variable-clock forks](evidence/ladder26/opening-season-009-on.csv) · [Evaluation code](OpeningPressAudit.java)

## First ordinary-Java promotion

The independent replication promoted the season 7 specialist `1ed6988f` after season 9. It beat the original champion 7/8 and 8/8 on two separate panels. In those 32 fights against both rivals it passed up the available opening in all 32, never landed the first hit, and landed a critical as its first reply in every fight. The replication widened the reply interval to 8–14 server ticks. Across all contacts the count was 210 criticals out of 214, versus zero out of 286 for W-tap. [Independent opening audit](evidence/ladder26/java-009-b/opening-audit.csv)

| Ordinary Java matchup | New champion | Other finalist | Option 4 | W-tap |
|---|---:|---:|---:|---:|
| Directly against option 4 | 15/16 | 10/16 | 8/16 | 7/16 |
| Both opponents, first panel | 13/16 | 12/16 | 10/16 | 5/16 |
| Both opponents, independent replication | 11/16 | 11/16 | 10/16 | 5/16 |
| Both panels and opponents | 24/32 | 23/32 | 20/32 | 10/32 |

This fighter wins damage trades, with a distinct weakness after entering an established combo. Against the retreating rival, switching to it after a common three-hit W-tap entry produced zero further unanswered fresh-hit cycles across 26 fixed-clock and 223 variable-clock starts. Continuing W-tap completed 17 and 100 more cycles, respectively. These totals count correlated cycles across the available starts. Independent native fights in the first panel had no three-hit entry for this fighter. [Fixed-clock continuations](evidence/ladder26/java-009/maintenance-retreating-off.csv) · [Variable-clock continuations](evidence/ladder26/java-009/maintenance-retreating-on.csv) · [Promotion decision](evidence/ladder26/java-009/decision.json)

## First-knockout correction

The referee sampled health once per server tick. In three season 14 panel A bouts, a player died and respawned between samples. The match continued and the first winner eventually lost the second exchange. The server's contact log identifies the first knockout in all three. Earlier Java panels 3A, 9A and 9B had no such result. The original option 6 promotion remains 15/16 head-to-head. The corrected referee latches both health values at the first lethal contact. Ordinary Java results and watch-room replays now use that event. The original scores and uncut recordings are retained. [Affected rounds](evidence/ladder26/first-knockout-revisions.csv) · [Original panel A scores](evidence/ladder26/java-014-a/historical-poll-scores/ordinary-scores.csv)

| Season 14, first panel | Generalist | Direct response | Incumbent | W-tap |
|---|---:|---:|---:|---:|
| Original end-of-tick scores | 22/32 | 20/32 | 18/32 | 14/32 |
| First-knockout scores | 23/32 | 21/32 | 18/32 | 13/32 |
| First knockout directly against incumbent | 5/8 | 6/8 | 4/8 | 0/8 |

On the 1,841 scored contacts up to the first knockout, supplying the Java pre-hit state reproduced all 1,841 resulting health values and motion-packet presence decisions. All 1,840 emitted knockback vectors matched. The simulator still overestimated the direct response against the incumbent. From the eight actual starting poses it predicted roughly 99.7% under varying phases and won 6/8 in Java. Exact accepted-hit mechanics leave differences in which attacks land. [Corrected Java panel](evidence/ladder26/java-014-a/ordinary-scores.csv) · [Contact parity](evidence/ladder26/java-014-a/conditional-contact-parity.csv)

The original ordinary clients used Minecraft’s AFK frame limiter. Long sessions included alternating input intervals near zero and 100 ms. An offline simulation repeated each side’s first 20 input intervals and the server’s first 20 intervals, initialized from the recorded opening positions, and let both simulated bots make every decision. It sampled the unknown network transit and entity update order 128 times per fight. On these 128 development bouts its mean squared outcome error fell from 0.182 with varying phases to 0.167. At 100 ms the direct challenger’s predicted score against the incumbent fell from 99.4% to 89.5%, with 2/4 actually won. W-tap’s predictions were 1.6% and 4.5%, with 0/4 won. The timing measurement explains part of the aggregate error. These exposed opening timestamps are an offline diagnostic. Promotion uses newly drawn Java openings and continuously active clients. [Predictions per bout](evidence/ladder26/forecast-polls-014-a.csv) · [Active-client protocol](evidence/ladder26/active-client-protocol.json)

The second original panel changed this picture. Across its 128 fights, repeating the measured opening cadence increased mean squared error from 0.135 to 0.144. The original fixed clock scored 0.127. Timing helped on panel A, and the result did not replicate on B. Both panels remained development evidence. All 1,826 accepted contacts on B reproduced the recorded post-hit health and motion-packet presence, and all 1,824 comparable impulses matched. [Second panel forecasts](evidence/ladder26/forecast-polls-014-b.csv) · [Second panel contact audit](evidence/ladder26/java-014-b/conditional-contact-parity.csv)

The matched-opening crossover then replayed the first panel’s angles and gaps in both seats. It changed the native client’s frame-limiting option. Both clients polled 3,517 times across 16 fresh games with their 50 fps cap and zero AFK-limited samples. At 100 ms, intervals under 5 ms fell from 50.2% to 2.6% of all input intervals. Intervals over 85 ms fell from 49.3% to 1.8%. The direct challenger won the two opening/seat combinations on which it had previously lost. Free-running Java repeats still have independent thread and packet timing. The same openings were used for diagnosis and excluded from promotion. [Matched scores and input intervals](evidence/ladder26/timing-014/paired-client-timing.csv)

| Added RTT against incumbent | Original challenger | Active-client challenger | Original W-tap | Active-client W-tap |
|---|---:|---:|---:|---:|
| 0 ms | 4/4 | 4/4 | 0/4 | 0/4 |
| 100 ms | 2/4 | 4/4 | 0/4 | 0/4 |

## What the full-frame exhibition shows

The season 9 champion played its season 11 counter from both seats on one scheduled Java opening. It began a grounded jump on the input tick delivering 14 of the 16 fresh knockbacks it received. W-tap did this on 0 of 14. For each of those 14 jumps, the simulator reproduced the pre-jump state and the next native position. Releasing only the jump key increased outward movement by approximately 0.20 blocks on the next tick in all 14 forks. The champion still lost both fights. This measures the physical effect of a learned jump reset on one opening. The score used to train the policy rewarded wins and health. [Watch one duel](http://127.0.0.1:25708/?match=showcase-003%2Fround-002#matches) · [Paired native inputs](evidence/ladder26/showcase-003/input-signatures.csv) · [One-key intervention](evidence/ladder26/showcase-003/jump-forks.csv)

## Season 14 promotion

The season 14 generalist, `62476e8c`, won 53 of its 64 fights against four opponents across the two independently seeded, active-client Java panels. It beat the defending champion in 13 of 16 direct fights. The season 11 counter swept the defending champion 16–0 and scored 41/64 across the field. The league promoted the generalist and resumed training against it. Each fighter played both seats with 0 and 100 ms added RTT. All 256 fights began at qualified positions. Draws count half a point. [Promotion decision](evidence/ladder26/java-014/decision.json) · [First panel](evidence/ladder26/java-014-c/ordinary-scores.csv) · [Replication](evidence/ladder26/java-014-d/ordinary-scores.csv)

| Ordinary Java score | Season 14 generalist | Season 11 counter | Defending champion | W-tap |
|---|---:|---:|---:|---:|
| First panel, all four opponents | 27/32 | 20/32 | 22/32 | 16/32 |
| Replication, all four opponents | 26/32 | 21/32 | 22/32 | 14/32 |
| First panel, directly against defending champion | 8/8 | 8/8 | 4/8 | 0/8 |
| Replication, directly against defending champion | 5/8 | 8/8 | 4/8 | 0/8 |

Before these panels, the simulator’s nomination games gave the generalist 81.25% and the counter 100% against the incumbent, with W-tap at 1.17%. Their observed direct scores were 81.25%, 100% and 0% over 16 games per fighter. Supplying the native pre-hit state reproduced the resulting health and motion-packet presence on all 3,737 accepted contacts and all 3,731 comparable knockback vectors. One overlap required the server’s private random draw. [Frozen forecasts](evidence/ladder26/java-014/nomination.json) · [Contact audit C](evidence/ladder26/java-014-c/conditional-contact-parity.csv) · [Contact audit D](evidence/ladder26/java-014-d/conditional-contact-parity.csv)

In those 64 fights the promoted bot landed 369 sprint-boosted hits and 139 criticals among 631 accepted attacks. It pressed back on 364 of 10,037 input ticks and used zero lateral inputs. W-tap recorded 630 sprint-boosted hits and zero criticals in 635 accepted attacks. The new bot established three unanswered fresh hits after its opponent started clicking in 33/64 games, versus 26/64 for W-tap. Those independent native runs measure entry frequency. In the matched simulated continuations against the retreating opponent, the generalist completed 31 further fresh-hit cycles versus W-tap’s 39 on the fixed clock and 96 versus 93 on the variable clock. The tests show mixed maintenance results. [Native input and contact audit](evidence/ladder26/java-014-c/ordinary-audit.json) · [Replication audit](evidence/ladder26/java-014-d/ordinary-audit.json) · [Matched continuation protocol and raw files](evidence/ladder26/java-014/maintenance)

In the 720p exhibition against its predecessor, six of the new champion’s 22 accepted attacks were sprint hits landed while rising after its own grounded jump. This is the jump-and-strike sequence Intel calls an uppercut. W-tap landed no such attacks among its 16 contacts. The new champion won from both seats on the same opening. Across all four exhibition games the simulator reproduced the accepted hits in order, final health and knockout tick. [Watch the new champion](http://127.0.0.1:25708/?match=showcase-014%2Fround-000#matches) · [Input audit](evidence/ladder26/showcase-014/input-signatures.csv) · [Frame counts](evidence/ladder26/showcase-014/frame-quality.csv) · [Game parity](evidence/ladder26/showcase-014/model-parity.csv)

## Perfect-aim movement league

Starting in season 21, every simulated and native fighter uses the same instantaneous target lock. The controller chooses forward/back, left/right, sprint, jump and attack. Yaw and pitch come from the opponent on the fighter's own client. At the edge of three-block reach, the mouse can aim at the reachable side of the visible hitbox. The camera calculates the same lock from interpolated positions at each rendered frame. The previous champion and archive seed training, and all new ratings use fresh fights. [Series settings](evidence/ladder26/perfect-aim/protocol.json)

On two fresh openings with 100 ms added latency and both seats, the actual Java picker selected the opponent on all 582 reachable player-ticks, including all 68 in-reach clicks. The learned-versus-learned arms accounted for 26 of those ticks, and the matched W-tap games accounted for 556. Some of the old policies spent whole fights out of reach under the new controls. All eight scheduled games matched the simulator on ordered contacts, terminal health and end tick. [Native pick counts](evidence/ladder26/perfect-aim/java-100-picks.csv) · [Full-game replay](evidence/ladder26/perfect-aim/java-100-parity.csv)

In the four filmed games on the original showcase opening, both fighters' real crosshairs selected the opponent on all 506 reachable player-ticks, including all 62 in-reach clicks. The simulator reproduced the hit sequence, terminal health and knockout tick in all four. The first-person camera samples the current displayed body position on all three subframes and agreed with the Java player's aim on every click. [Perfect-aim rematch, 720p60](http://127.0.0.1:25708/?match=showcase-005%2Fround-003#matches) · [Actual crosshair picks](evidence/ladder26/showcase-005/native-aim.csv) · [Matched simulator replays](evidence/ladder26/showcase-005/model-parity.csv)
