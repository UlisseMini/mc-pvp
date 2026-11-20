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
