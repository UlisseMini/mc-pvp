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
