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

## Replaying the evidence

From the project folder, this example regenerates the v2 score with the archived simulator and the original policy files resolved by hash. The replay program builds that revision without changing the checkout or opening Minecraft. Omit `--revision` to score the same recordings with the current simulator.

```sh
mkdir -p build/sword26/replay-kernel
tar -xzf research/sword26/evidence/kernel/java-v2-100.tar.gz -C build/sword26/replay-kernel
python3 research/sword26/parity_lab.py build/sword26/replay-kernel build/sword26/replayed.csv --revision d284955a
python3 research/sword26/kernel_report.py summary build/sword26/replayed.csv
```

The measured scope is a level stone floor, fixed equipment and the lab’s duel settings. The whole-game comparisons cover zero and 100 ms symmetric added RTT under a tick-indexed packet schedule. Vanilla servers on the internet have independent timing, jitter and sometimes modified combat rules. Human rankings, other kits and irregular terrain require their own evidence.
