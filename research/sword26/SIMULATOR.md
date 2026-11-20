# How closely does the simulator match Java 26.3?

The lab simulates the 24-block-wide flat court, diamond swords and unenchanted diamond armor used in these duels. Keyboard movement, assisted turning, jumping and attacking are available every tick. The policy sees its own client’s state and the opponent’s displayed hitbox. The world advances independently on the server and in both client views.

I used Horizon’s implementation and Java comparison harness as references while working through modern movement. For the tested version, I checked the rules against Mojang’s 26.3 classes and recorded fights between two stock 26.3 clients on a stock dedicated server with lab instrumentation. The fast trainer runs independently of the three game processes. [Horizon movement at the reviewed commit](https://github.com/horizon-rust/horizon/blob/6966c176fb45d3d90cc76a4c0c99751e55b47a96/crates/horizon-world/src/living_air.rs) · [Horizon’s Java knockback comparison](https://github.com/horizon-rust/horizon/blob/6966c176fb45d3d90cc76a4c0c99751e55b47a96/tools/test_server_knockback_parity.py)

## What a comparison measures

Every matchup includes a trained-policy arm and a plain W-tap arm against the same rival. Both play each seat on the same openings, with the same sword, reach, aim helper, press ceiling and latency. Each Java fight supplies the starting poses, orientation, tracker phases and entity order. From that point the simulator evolves both clients and the server on its own.

One replay uses the observed physical keys and aim angles. A second runs the two policies on their simulated observations and lets the fight play out. The second test catches small movement errors that cause a bot to make a different decision several hits later. A contact match requires every accepted hit to occur on the same tick, in the same order, from the same seat, with identical attack charge, critical status, sprint knockback, fresh damage or damage upgrade. The fight must also end on the same tick. Exact observation counts include own position and velocity, grounding, sprint, charge and displayed target coordinates. Health and physical key decisions are recorded separately. Final HP comparisons use the native binary floating-point values.

## How the implementation changed

The first prospective test used four new openings per latency and three opponents in both seats. At zero added latency, closed-loop terminal health matched in 24/24 trained-policy bouts and 24/24 W-tap bouts. At 100 ms it matched in 22/24 and 18/24. The simulator was sending an extra stationary position step, rounding unchanged coordinates and pushing a player twice during some overlaps. Java’s client-side entity filter lets the remote player initiate the push. The earlier scores remain with that first version. [First protocol](evidence/kernel/protocol.json) · [Zero-latency results](evidence/kernel/fast-0.csv) · [100 ms results](evidence/kernel/fast-100.csv)

After those corrections, a second prospective test used four different openings per latency. All 96 closed-loop fights matched both terminal health values and the entire ordered contact sequence through termination. All observed positions, velocities, sprint flags, charge and displayed target positions matched in 95 of the 96 bouts. One zero-latency bout had a maximum displayed error of 0.0384 blocks and own-position error of 0.00000365 blocks. Replaying the recorded keys separately also reproduced every outcome and contact. These are independent openings for the corrected simulator. [Second protocol](evidence/kernel/validation-v2.json) · [Zero-latency results](evidence/kernel/v2-sealed-0.csv) · [100 ms results](evidence/kernel/v2-sealed-100.csv)

The third version also modeled Java’s eight-degree glancing-wall rule for retaining sprint. I identified that rule from an older development bout where W-tap grazed a barrier. A third prospective comparison used a newly trained policy, five opponents, three openings per latency and both seats. Each cell below counts whole fights under closed-loop control. [Third protocol and frozen hashes](evidence/kernel/validation-v3.json) · [Zero-latency results](evidence/kernel/v3-sealed-0.csv) · [100 ms results](evidence/kernel/v3-sealed-100.csv)

| Independent Java comparison | Trained policy, 0 ms | W-tap, 0 ms | Trained policy, 100 ms | W-tap, 100 ms |
|---|---:|---:|---:|---:|
| Winner and both final health values identical | 30/30 | 30/30 | 30/30 | 30/30 |
| Entire ordered hit sequence and terminal tick identical | 30/30 | 30/30 | 30/30 | 30/30 |
| Every measured motion observation identical | 29/30 | 30/30 | 30/30 | 30/30 |

All 38,796 compared physical key decisions and health observations matched. Of those samples, 38,628 also matched exactly on the measured motion fields. In the single divergent bout the largest difference in displayed enemy position was 0.1652 blocks and the largest difference in own position was 0.00180 blocks. With the original physical keys and aim angles replayed, own position stayed exact throughout all 120 fights. One displayed-position sample differed. The 120 recorded-key comparisons and 120 closed-loop comparisons use the same 120 Java recordings.

An additional challenge introduced the league-trained fighter and a new opponent that rapidly alternates forward and backward inputs while airborne. The third version predicted all 120 winners on that challenge, but reproduced both final health values in 110 and all hits and terminal ticks in 107. Every large trajectory error came against the new opponent. Mojang’s local client lets a first forward tap arm sprint in midair and a backward press clears the timer. Implementing these rules restored the full hit and final-HP match in all 120 replays of the diagnostic panel. This is a rescore on exposed openings. Its former results are retained. [Third-version challenge, 0 ms](evidence/kernel/league-0.csv) · [100 ms](evidence/kernel/league-100.csv) · [Corrected development rescore, 0 ms](evidence/kernel/v4-development-league-0.csv) · [100 ms](evidence/kernel/v4-development-league-100.csv)

The fourth prospective test deliberately includes that troublesome opponent. The other opponents are W-tap, the retreating fighter and a recurrent fighter whose policy had never been used in a parity test. It uses three fresh openings at each latency, both seats and matched controls. [Frozen fourth protocol](evidence/kernel/validation-v4.json)

The final simulator and real Java matched the winner, both final HP values, every accepted hit and the death tick in **96/96 new bouts**. They matched all **31,502** physical-key decisions. With recorded keys and aim angles, own position stayed exactly equal throughout. Only 29 of the 31,502 compared motion observations differed, with up to 0.4201 blocks of displayed-opponent error. With independently calculated aim, the maximum own-position difference was 0.00235 blocks, and 30,553/31,502 measured motion observations were bit-exact. The larger count of bit differences in the closed loop reflects aim feedback after a displayed-position difference. [Final 0 ms scores](evidence/kernel/v4-sealed-0.csv) · [Final 100 ms scores](evidence/kernel/v4-sealed-100.csv) · [Raw Java recordings at zero latency](evidence/kernel/java-v4-0.tar.gz) · [Raw Java recordings at 100 ms](evidence/kernel/java-v4-100.tar.gz)

| Final prospective test under independent policy control | Learned, 0 ms | W-tap, 0 ms | Learned, 100 ms | W-tap, 100 ms |
|---|---:|---:|---:|---:|
| Complete hit sequence, terminal tick and both health values | 24/24 | 24/24 | 24/24 | 24/24 |
| Every measured motion observation identical | 22/24 | 23/24 | 20/24 | 22/24 |

The policy subsequently trained from random weights on this final kernel was checked on its own 48 scheduled Java evaluation bouts, including the matched W-tap arms. All 48 matched on every measured motion field, displayed position, health, key choice, contact and death tick. That is 14,228 paired client observations. These additional fights used the policy evaluation openings. [From-scratch policy check at 0 ms](evidence/kernel/playing-policy-0.csv) · [100 ms](evidence/kernel/playing-policy-100.csv)

## Speed

The third-version benchmark used five batches of 8,000 complete duels per arm on one warmed CPU thread on this Mac’s Apple M2 Max. Both policies made a decision each tick, including aim. The benchmark includes opening creation and the fight through death or the time limit. At the median speed, a single thread advances learned-policy fights about 80,400 times faster than the game’s 20 ticks per second. [All five timings and checksums](evidence/kernel/throughput-v3.csv) · [Benchmark](KernelBench.java)

| Median per second | Learned policy against learned rival | W-tap against the same rival |
|---|---:|---:|
| Complete duel ticks | 1,607,794 | 1,863,562 |
| Complete fights | 10,016 | 11,921 |

The learned-policy batches ranged from 7,716 to 10,721 fights per second. The W-tap batches ranged from 7,895 to 14,277. Speed depends on the policy, how long a fight lasts and other load on the machine.

With the current sprint implementation and the new league policy against the retreating rival, the single-thread medians were **9,348 complete fights and 1,457,576 duel ticks per second**. The matched W-tap arm ran at 13,388 fights and 2,092,831 duel ticks per second. The learned batches ranged from 8,401 to 10,204 fights per second. The W-tap batches ranged from 8,151 to 13,769. [Current benchmark](evidence/kernel/throughput-v4.csv)

With the actual from-scratch playing policy against the retreating rival, the medians were **10,724 complete fights and 1,662,224 duel ticks per second**. The matched W-tap arm ran at 14,778 fights and 2,309,245 duel ticks per second. The learned arm ranged from 10,116 to 10,826 fights per second across the five batches. [Playing-policy benchmark](evidence/kernel/throughput-playing-policy.csv)

## What ran inside actual Minecraft

The accelerated stock-game oracle ran the final 96-fight panel with a median of 0.47 wall seconds per bout for each arm. That includes the between-round countdown and excludes starting the three JVMs. The two stock clients ran in private virtual displays.

In 24 paired re-runs, the accelerated and normal-speed Java referee recorded the same winners, both terminal health values, hit ticks, native damage flags, pre-hit velocities, outgoing impulses, and death ticks in every fight. Both used the same tick-indexed network schedule. Both client recordings agree over their common samples. At 100 ms the final number of samples can differ when a client sees the bout end. The ordinary TCP confirmation lets the server and both render loops run on their own clocks. That is a separate measure of playing strength. [Clock comparison at zero added latency](evidence/kernel/java-clocks-0.csv) · [Clock comparison at 100 ms](evidence/kernel/java-clocks-100.csv)

A separate component check calls Mojang’s methods directly. All 65,536 pairs of sine and cosine table indices and 16,384 randomized cases each for keyboard acceleration, knockback, packed velocity and armor damage matched at bit precision. The complete-game tests additionally exercise damage immunity, charge, movement prediction, interpolation, collision, attack ordering and input timing. [Component checker](VanillaParity.java) · [Full-duel checker](KernelReplay.java)

## The same openings on both Java clocks

The original 6/8 versus 2/8 results for option 5 against option 4 came from two real Java panels. They used different random openings and different clocks. I replayed the exact angle and gap from each panel under the other clock, using the same weights, equipment, both seats, and their original zero or 100 ms RTT. This added 32 Java bouts. Each cell gives wins out of eight. The parentheses give the wins at zero and 100 ms, each out of four. [Diagnostic protocol](evidence/random26/clock-crossover/protocol.json) · [All paired results and timing](evidence/random26/clock-crossover/paired-clocks.csv)

| Set of starting positions | Scheduled Java, option 5 | Scheduled Java, W-tap | Ordinary TCP, option 5 | Ordinary TCP, W-tap |
|---|---:|---:|---:|---:|
| Original scheduled openings | 6 (4, 2) | 1 (0, 1) | 5 (2, 3) | 0 (0, 0) |
| Original TCP openings | 4 (2, 2) | 6 (3, 3) | 2 (1, 1) | 1 (0, 1) |

All 16 option-5 pairs began at identical coordinates on each player's first input poll. Seven changed winner across clocks. Six changed which player landed the first accepted hit. These are individual runs of independent native clocks on previously tested geometries. The panel cannot assign a particular loss to a particular packet delay.

Two of the 16 W-tap pairs had different observed starting coordinates. On the TCP recordings for the original TCP opening at 0 ms, player B drifted 0.040 and 0.100 blocks before the first input. Both server traces show the movement in the neutral countdown after a correct teleport. The historical counts above include those bouts. With those two pairs removed, W-tap won 4/6 scheduled and 1/6 ordinary bouts on that geometry set. The referee now checks both bot positions in the neutral countdown, corrects drift before release, and the audit reports each client's first-input spawn error.

The simulator reproduced the complete contact sequence, death tick and both terminal health values in all 32 scheduled bouts in the crossover. Every measured motion and displayed-position sample in the 16 newly recorded scheduled bouts matched (4,622 client observations). In the 32 ordinary TCP bouts, feeding the recorded attacker charge, hit type, target state and geometry into the production damage and knockback functions reproduced the defender’s resulting health and the pre-encoding knockback vector on all 581 accepted hits. This last check conditions on Java having accepted the hit. [New 0 ms parity](evidence/random26/clock-crossover/scheduled-0-parity.csv) · [New 100 ms parity](evidence/random26/clock-crossover/scheduled-100-parity.csv) · [Contact check](ContactParity.java)

The two clocks differ before a bot reacts. At zero added latency, Java’s fixed schedule placed the first input poll showing a surviving defender’s new HP 35 ms after the recorded server contact every time it was observed. Ordinary TCP produced 4–72 ms with a median of 21. At 100 ms added RTT the fixed schedule gave 85 ms and ordinary TCP gave 52–124 ms with a median of 81. The fixed clock uses a synthetic 15 ms offset for the server event stamp. The ordinary stamps share a VM wall clock. We could match 544/549 nonlethal hits to an ordinary input observation and 538/542 scheduled ones. Missing observations and lethal hits are retained in the data. These measurements include both delivery and the wait for the next input poll. [Hit-by-hit observations](evidence/random26/clock-crossover/damage-observations.csv)

As an additional sensitivity check, I reversed the order in which the model handled the two clients' incoming batches on the first accepted-hit tick only. All state and controls up to that tick were identical. The exact scheduled replay supplies the factual comparison. The switch changed the winner in 1/16 option-5 bouts and 2/16 W-tap bouts. This shows a timing mechanism capable of changing the outcome. Ordinary TCP would need a packet-processing trace to reconstruct which ordering and delivery times occurred in a particular bout. Run `python3 research/sword26/clock_crossover.py` to regenerate the crossover and hit-by-hit files.

In an eight-bout ordinary Java regression, the console displaced player B by 0.25 blocks during the neutral countdown of each of the first four bouts. The referee corrected all four displacements and two naturally occurring drifts of 0.079 and 0.081 blocks. Both clients began on their exact requested coordinates in all eight bouts. The stress test is separate from the strength results. [Regression design](evidence/random26/clock-crossover/countdown-regression.json) · [First input coordinates check](evidence/random26/clock-crossover/countdown-starts.csv)

## Replaying the evidence

From the project folder, this example regenerates the v2 score with the archived simulator and the original policy files resolved by hash. The replay program builds that revision without changing the checkout or opening Minecraft. Omit `--revision` to score the same recordings with the current simulator.

```sh
mkdir -p build/sword26/replay-kernel
tar -xzf research/sword26/evidence/kernel/java-v2-100.tar.gz -C build/sword26/replay-kernel
python3 research/sword26/parity_lab.py build/sword26/replay-kernel build/sword26/replayed.csv --revision d284955a
python3 research/sword26/kernel_report.py summary build/sword26/replayed.csv
```

The measured scope is a level stone floor, fixed equipment and the lab’s duel settings. The whole-game comparisons cover zero and 100 ms symmetric added RTT under a tick-indexed packet schedule. Vanilla servers on the internet have independent timing, jitter and sometimes modified combat rules. Human rankings, other kits and irregular terrain require their own evidence.

## Turning in place

A smooth-aim rematch exposed a discrepancy when a player reached an arena corner, turned, and moved away. At tick 126 the simulated spectator position advanced 0.028 blocks early. A Java rotation-only movement packet adds an interpolation step at the remote player's existing destination. Reproducing that step and the packed yaw/pitch change threshold restored the entire trajectory. Replaying 708 archived scheduled fights from the two original player observations per opening then matched the ordered contacts, final health and end tick in 354/354 learned and 354/354 W-tap bouts. Previously those counts were 354/354 and 353/354. This is a regression check on recorded openings, including the discovery game. [Results by run](evidence/ladder26/showcase-004/rotation-regression.csv)

The perfect-aim season 21 main policy against the previous reference ran a median of 14,437 complete training fights and 1.91 million duel ticks per second on one host core. W-tap against the same reference reached 14,297 fights and 2.30 million ticks per second. Each arm used the same mixed 0/100 ms openings across five measured batches of 3,000 games after warm-up. [Perfect-aim throughput](evidence/ladder26/perfect-aim/throughput.csv)

## Ordinary Java timing with perfect aim

On the 96 promotion fights in season 23, the simulated champion strongly favored itself on two openings where it lost. Replaying each client's and the server's actual tick times for the entire fight reduced the simulated win rate from 92% to 13% on round 70. On round 71 it increased from 80% to 95%. Both runs used the actual opening and let the two policies decide independently. Using the complete clock is a post-game diagnostic. Unknown packet arrival and processing times still matter. [Promotion scores](evidence/ladder26/java-023-a/ordinary-scores.csv) · [Clock comparison by fighter, RTT and W-tap baseline](evidence/ladder26/java-023-a/observed-clock-error.csv)

Across all 96 fights the mean squared error was 0.135 for the randomized-phase scheduler, 0.151 for the repeated opening cadence and 0.152 for the full-clock replay. On 24 native scouting fights the errors were 0.089, 0.106 and 0.130. These are development measurements under the perfect-aim format. The next scouting and promotion panels were reserved in advance for the same test. Run `clock_prediction.py --recorded-cadence` with a native recording and an output CSV to reproduce the full-clock diagnostic. [Scouting error](evidence/ladder26/scout-023/observed-clock-error.csv) · [Prospective check](evidence/ladder26/perfect-aim/ordinary-clock-check.json)
