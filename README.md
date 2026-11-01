# Minecraft PvP Lab

A working local practice server and research ladder using the official **Minecraft
Java 1.8.9** server and real clients through **basic HeadlessMC**. The game performs
movement, collision, raycasting, attacks, knockback and damage immunity.

Current research: [physics validation before policy search](research/README.md).
The [double-hit investigation](research/double-hit-mechanisms.md) records W-tap
versus hold-W trials and the native timing differences behind their exchanges.
The [burst-clicking experiments](research/burst-clicking-results.md) test immediate
recoil cancellation, continuous one-tick W-tap and critical-aware click scheduling.
The [click-rate comparison](research/cps-wtap-results.md) isolates 15, 20, 25 and
30 CPS against otherwise identical 10 CPS one-tick W-tap.
The [timed-block trials](research/blockhit-linear-results.md) cover the practice
feedback, damage metrics and measured strengths and limitations of `blockhit_timing`.
The [formal model](FORMAL-MODEL.md) states the mechanics, hidden information and
combo proof conditions; controlled native recordings test those claims. Its
individual equations are much better checked than its full-fight forecasts.

## Join and play

Optional Forge 1.8.9 client: [our practice mod](practice-client/README.md)
provides ToggleSprint, a potion HUD, automatic localhost packet/input/view
recording, and a link joining the human, bot and server views. Practice modules
reload live; client commands start with `/practice`.

1. Open Minecraft Java **1.8.9** in your normal launcher.
2. Choose **Multiplayer → Direct Connect** and enter **127.0.0.1:25565**.
3. Type **`/bots`** and click a bot to fight it. Boxing is first to 100 hits.
   For full diamond armor and a fight to death, use **`/duel wtap diamond`**.

The server is already set up on this Mac. If it is stopped, double-click
**Start PvP Lab.command** in this folder and leave its Terminal window open.
An “already running” message means you can join immediately.

Local administration from this folder: `python3 lab.py console op Nomophobia`
grants operator access; `python3 lab.py console deop Nomophobia` removes it.
`python3 lab.py console weather clear 1000000` clears rain and thunder for
1,000,000 seconds (about 11.6 days of server uptime). This is renewed at every
startup; vanilla 1.8.9 does not support the later `doWeatherCycle` gamerule.
These commands use the server console through the existing local inbox, without
enabling RCON or opening another network port.

| Command | What it does |
|---|---|
| `/bots` | Clickable roster, including saved search candidates |
| `/bots all` | Include every saved tuning candidate |
| `/duel hold-w` | Fight the simplest baseline |
| `/duel wtap diamond` | Full unenchanted diamond armor and an unbreakable wooden sword; real health and regeneration; fight to death |
| `/duel blockhit_timing diamond` | Timed sword-block bot; native controls, 10 CPS ceiling, 3-block reach, zero artificial reaction delay |
| `/duel wtap rod_diamond` | Same unenchanted diamond armor / wooden sword kit, plus a fishing rod in hotbar slot 2 for both players; existing bots keep using their sword |
| `/duel wtap diamond_box` | 1×1 enclosure, unbreakable wooden swords, full unenchanted diamond armor; successful blocks play your block sound |
| `/duel wtap diamond 20 100` | Same health mode with bot RTT 20 ms and your added RTT 100 ms |
| `/duel wtap rod_diamond reach=3.5 cps=15 ping=100` | Custom practice bot: 3.5-block reach, 15 CPS ceiling, 100 ms added bot RTT; keeps your saved ping |
| `/mode rod_diamond` / `/mode diamond_box` / `/mode diamond` / `/mode boxing` | Choose the default mode for subsequent `/duel` and roster clicks in this session |
| `/blocksound test` | Preview the quiet metallic block clang |
| `/blocksound on` / `/blocksound off` | Enable or mute the block sound; enabled by default |
| `/blocktiming on` / `/blocktiming off` | Enable or hide brief colored timing feedback above the hotbar; enabled by default |
| `/duel predictive` | Fight the hand-written spacing controller |
| `/duel standstill` | Fight a clicking opponent that presses no movement keys; knockback still moves it |
| `/duel zest-w 20 100` | Fight W-only Zest: bot RTT 20 ms, your added RTT 100 ms |
| `/duel zest-strafe` | Fight the version allowed to use normal A/D keys |
| `/duel burst 120 20` | Add 120 ms RTT to the bot and 20 ms to yourself |
| `/ping` | Show current duel RTTs, or your saved ping in the lobby |
| `/ping me 20` | Set and save your added RTT; works in the lobby or during a duel, and persists across matches/reconnects/restarts |
| `/ping bot 100` | Set the bot's added RTT to 100 ms during the duel |
| `/ping 20 100` | Set both: player RTT first, then bot RTT |
| `/rematch` | Same bot, mode, reach/CPS overrides and bot ping, using your saved player ping; after a death, click Respawn first |
| `/rematch cps=20` | Repeat the setup while changing only the bot's CPS ceiling |
| `/stop` | End your duel or leave spectator mode; return to the lobby |
| `/replay` | Watch your latest completed match using the server recording |
| `/replay m0930` | Watch a specific recorded match |
| `/replay list` | Click to watch one of your ten latest matches (`list all` includes bot matches) |
| `/replay controls` | Show the clickable rewind/forward, pause/play, speed and camera controls again |
| `/replay pause` / `/replay play` | Pause/resume; play restarts after the recording ends |
| `/replay seek 12.5` | Jump to 12.5 seconds after the match started |
| `/replay back` / `/replay forward` | Jump back/forward 5 seconds; add a number for a different amount, e.g. `back 0.05` |
| `/replay seek -5` / `/replay seek +5` | Jump back/forward relative to the current position; unsigned numbers are absolute timestamps |
| `/replay speed 0.1` | Play at one tenth speed (10× slower); any finite positive multiplier works, e.g. `0.75`, `0.01` or `3` |
| `/replay a` / `/replay b` / `/replay free` | Follow either recorded fighter or fly freely |
| `/replay stop` | Remove the replay actors and return to the lobby (`/stop` also works) |
| `/matches` | Clickable list of ongoing matches |
| `/watch` | Spectate an ongoing match; click a follow-camera button |
| `/watch m0025 a` | Follow player A in that match, while it is active |
| `/lab match hold-w wtap 20 120` | Start a bot match with those RTTs |

In spectator mode, fly around normally. Sneak exits a followed bot's camera.
The action bar shows the current score. Finished matches are saved automatically.
Spectators cannot interfere with combat. There are eight real bot clients; the
experiment scheduler uses at most six so human challenges have spare slots.

Finished-match chat includes a clickable **Watch server replay** button. Replay
actors are private packet-only copies in a separate viewing area: they consume
no bot slots, run no combat, and cannot affect another match. Starting a duel or
watching a live match removes your replay; `/stop` exits it. Completed recordings
remain available across server restarts. Pause, seek, speed and follow controls
operate independently for each viewer. Open chat (T) to click the controls shown
when a replay starts. **Custom...** fills in the speed command for you to type a
number; **Go to...** fills in the seek command. Rewind/forward preserve your
camera and pause state and stop at the recording's boundaries. You can pause
and jump in small increments to inspect a hit. Playback changes the timing of
recorded positions/events; normal client animations still run at client speed.

This is a playback of **recorded server positions and accepted combat events**,
not a rerun of bot decisions or either player's original screen. Your live client
predicts your movement and sees delayed/interpolated opponent movement; those
views differ from the server's. The spectator client also performs its ordinary
packet quantization/interpolation during playback. New recordings include server
look direction, held item, blocking/sneaking, swing state, rod flight and final
health. Older recordings still play, but look direction/equipment are approximated
and missing rod flight/block poses cannot be recovered; the viewer labels this.
The flat arena/box is recreated for viewing, rather than recording all world
changes. A client-side Replay Mod recording remains the way to preserve what
your original client saw.

RTTs are **added**, symmetric network delay. Your own real connection and normal
Minecraft tick/interpolation delays remain. The human-facing latency setting is
shared: this first version is intended for **one human player at a time**.

Optional duel settings are `reach=`, `cps=`, `ping=` (or `botping=`), and
`myping=`. Reach accepts **1–5 blocks**, CPS accepts **1–30 whole clicks per
second**, and added ping accepts **0–500 ms RTT**. CPS is the physical-input
ceiling; a burst policy may choose fewer clicks. Bare `/duel <bot>` uses that
policy's normal reach/CPS, zero added bot ping, and your saved ping. The older
numeric syntax remains valid: `/duel wtap diamond 20 100` explicitly sets bot
RTT to 20 and saves your RTT as 100. `/ping me 0` clears your added delay.

Overrides are copied into this match, never written into the shared policy.
`/rematch` keeps them; a new `/duel` starts from the selected policy again.
Custom reach changes the bot client's native target-selection distance while
keeping its native line-of-sight, target hitboxes and ordinary attack inputs.
It does not change your client or the server's attack validation. Bots retain
their existing strategies; planners are not retrained for the changed reach.
Non-three-block setups are visibly marked **modified-reach practice**, recorded
with effective settings, and excluded from vanilla ladder qualification. Player
ping preferences are stored by name in `runtime/preferences/`.

After a match, each player's **damage taken per hit** is their actual health
lost to the opponent divided by accepted melee hits received, displayed in
hearts. This includes the actual effects of crits, blocks, armor and partial
immunity damage upgrades (plus enchantments if manually equipped). Zero-damage rod contacts and environmental
damage are excluded; regeneration is not subtracted. Lethal damage counts only
the HP actually lost, and no melee hits received is shown as `N/A`.

Results also show **Blocking saved** and **Extra crit damage**, in hearts.
These compare that single hit with blocking or its critical multiplier removed,
keeping the same pre-hit immunity state, armor, Resistance and actual Protection
roll. Absorption and remaining HP cap both alternatives. They do not predict
how the rest of the fight would have changed. Averages are shown per successful
block/crit and across all damage-dealing melee hits received/landed; empty
denominators show `N/A`. A blocked critical can contribute to both metrics, so
the two benefits are not an additive decomposition of total damage.

`Blocking saved` also shows equivalent **normal hits**. For each blocked hit,
divide the actual HP saved by a full, unblocked, noncritical hit from that
attacker's weapon, with the same armor, Resistance and Protection roll; then sum
those fractions. This handles wood/diamond swords, partial armor, criticals and
immunity upgrades. The reference is a full mitigated hit before absorption and
remaining-HP caps; the saved HP numerator keeps those caps. Current practice
kits have no Protection enchantments. The same calculation also supports older
recordings and manually equipped enchanted armor, whose Protection varies per hit.

**Damage blocked %**, shown after hit block %, is saved HP divided by (actual
melee HP lost + saved HP). It excludes rods, environmental damage and healing.
It compares the same recorded hits with their blocking removed, not a replay of
how an entire unblocked fight would have unfolded.

The result panel's headline **Hits** now counts only accepted melee attacks that
actually removed HP. **Rod contacts** are listed separately and never added to
that headline. Saved replays retain the original accepted-contact `hits_a/b`
counters for research and add `damage_hits_a/b` for the new headline.

**Rod tricks**, shown for both players in rod-mode results, counts completed
rod → damaging sword upgrade → fresh damaging sword-hit sequences. The fresh
hit must follow the first sword upgrade within five victim ticks (250 ms at
20 TPS). A rod contact alone, a sword arriving after the rod's immunity window,
or a normal sword hit upgraded to a crit does not count. Further crit upgrades
do not extend the deadline; each rod setup counts at most once. A new rod,
non-sword contact, zero-HP-damage contact or environmental damage interrupts an
unfinished sequence. Opposing hits during a trade do not interrupt it. Saved
results expose `stats_a/b.rod_tricks`; `rod_trick` replay events identify all
three accepted hit IDs. This is a timing-based statistic, not a claim that the
rod caused an extra hit that would otherwise have been impossible.

**Block %** is blocked incoming melee hits / all incoming melee hits. Blocking
30 of 40 hits shows `75.0% (30/40 incoming hits)`. Each accepted critical upgrade
is a hit; zero-damage rod contacts, misses and rejected immune-frame attacks are
excluded. Missed block attempts do not affect this percentage. No incoming melee
hits displays `N/A`. Your bottom bar uses the same definition: `Blocks: 30/40
(75.0%)`, with your own incoming-hit count; spectators see both players in score
order. Separate raw attempt telemetry remains in research logs.

`blockhit_timing` uses immediate post-hit W-taps plus separate defensive blocks.
It fits straight-line motion to the last three visible positions of each player,
projects the opponent's reach and next fresh-hit opportunity, and learns a small
timing correction from its own native hurt notifications. It commits to an impending hit forecast, raises the sword independently of its
attack cadence, and releases one client tick later (about 50 ms).
Clicks remain capped at 10 CPS and continue during immunity when the opponent
is in native three-block reach. Blocking retains vanilla's movement penalty and
attack suppression; the policy reads no referee or hidden opponent state.
See the [linear-controller experiments](research/blockhit-linear-results.md)
for the algorithm, calibration, controls, unequal-ping results and recordings.

Results now also show **Blocking time** in seconds and as a percentage of fight
time, plus **saved hearts per second spent blocking**. These measure actual
server guard intervals, including an unfinished guard at match end. Use the
percentage alongside hit block %: less time blocking is useful only if it still
prevents damage and preserves good trades. Native client/server item-use
mismatches can make a server guard outlast a mouse press, so trials audit both.

Successful sword blocks play a quiet, higher-pitched `random.anvil_land` only to the blocking player.
The server observes the actual vanilla damage-reduction branch, after attack
acceptance and before armor. Merely holding right-click, an opponent's miss, or
an attack rejected during immunity produces no chime. Accepted damage upgrades
can produce a chime when blocking really reduces them. Sound arrives with the
normal server-to-client network delay; no client mod or resource pack is needed.
The action bar also counts successful blocks for each player. Use
`/blocksound test` to check your sound settings.

The hotbar feedback briefly shows green `BLOCKED · held ~80 ms`, orange
`EARLY · released ~60 ms before hit`, or red `LATE · blocked ~90 ms after hit`.
Successful blocks report how long the guard was held before impact, not an
optimal timing error. Early/late attribution requires an actual unblocked
melee hit within 250 ms of a guard edge; if both edges are nearby, the closer
one wins. Other hits show `HIT · no nearby block`. Durations use server-observed
times and include network/tick scheduling; they are rounded to 10 ms. Feedback
lasts one second, retains your block ratio and HP, then restores the normal bar.
Use `/blocktiming off` to keep the normal stats bar throughout combat.

Diamond mode starts each player at ten hearts, full hunger and full
saturation, with an unenchanted, unbreakable wooden sword and all four diamond
armor pieces without enchantments. A full normal hit against this intact armor
deals 0.50 hearts; a full crit deals 0.75, before blocking. Removing Protection
removes its random damage reduction. Wooden swords are the weakest native swords;
fights can be shorter than the old Protection IV setup. Every supplied sword, armor piece and rod has the native `Unbreakable:1b` item
property; damage and natural regeneration run
normally at Normal difficulty. The round ends on death; inventory is retained
through respawn and the next duel supplies a fresh kit. Boxing continues to
restore health and food, with its established first-to-100 scoring. Existing
bot algorithms and click ceilings apply in both modes.
All players, including bots, receive full hunger (20), full saturation (20) and
zero food exhaustion once per second and at round setup. The periodic refresh
does not reset the native regeneration timer. There is no potion or direct heal
from this refresh; ordinary 1.8 regeneration provides the health gain.
`rod_diamond` adds a normal, unenchanted fishing rod in hotbar slot 2 for both
players. Rods, armor and swords all have the native unbreakable item property. Current bot policies
continue using their sword; rod AI is not enabled.
This mode enables PvP rod knockback: zero-damage rod contacts between the current
duel opponents bypass `EntityPlayer.attackEntityFrom`'s zero-damage early return.
The original living-entity code then handles knockback and hurt immunity, with
zero health damage. Rod flight, hooking and reeling remain native. This deliberate
exception is restricted to active `rod_diamond` rounds; other modes and the lobby
retain vanilla's zero-damage rejection. No new server fork or plugin is installed.
`diamond_box` uses the same health rules and unenchanted diamond armor /
unbreakable wooden sword kit. Both players share a 1×1 interior,
two blocks tall, with bedrock walls and a transparent barrier ceiling for daylight.
Ordinary block collision contains knockback; combat movement is not teleported or
overridden. The enclosure is removed after the round so later flat-ground matches
can reuse the arena. `/rematch` retains this mode and both ping settings; `/stop`
returns you to the lobby. All health practice rounds keep the three-minute limit.

At the end of a round, both players and spectators receive each player's total
accepted hits, native accepted criticals, successfully blocked incoming hits,
and actual health regenerated. Healing and damage are displayed in hearts.
The colored result panel names the winner and labels both players; your duels
always list you first, then the bot. Red HP is the final health **before** lobby
healing, yellow is hits, purple is criticals, aqua is blocks, and green is healing.
Vanilla command feedback and console-to-operator broadcasts are disabled, so
setup, kit, teleport and game-mode commands do not flood the chat. Console logs
and normal chat/help remain available.
The summary also reports effective health damage dealt/taken and the number
of accepted damage upgrades during immunity. Rod contacts are separate from
the headline damage-dealing hits and cause no health damage. A stronger attack
during immunity applies only the increase over the previous raw damage: a normal
wooden-sword hit (5) followed by a critical (7.5) applies only 2.5 before mitigation.
That accepted critical upgrade counts as one crit and one damaging hit. A rod (0)
followed by a sword is also an immunity upgrade but still applies full sword damage.
Criticals are a subset of accepted hits. Round/lobby health resets are excluded from healing.
Damage is measured after blocking, armor, enchantments and absorption, capped by
the victim's remaining health (overkill is excluded). Boxing's automatic health
restoration is not counted as regeneration.

Equal hit/crit/block counts need not imply equal damage: blocking a critical
differs from blocking a normal hit, immunity upgrades apply only extra damage,
rod-in-hand melee attacks are weaker than sword hits. Older enchanted kits also
had random Protection reduction; current kits remove that randomness and keep
all equipment unbreakable. The actual damage and healing counters
provide the health balance for normal health duels.
The [practice verification notes](experiments/PRACTICE-NOTES.md) include native
blocking, sound-delivery, death and rematch evidence.

## Controllers and rules

| Bot | Control idea |
|---|---|
| `hold-w` | Hold W and sprint, click continuously |
| `hold-w-probe` | Same pursuit and perfect aim, no clicks; logs native reach opportunities |
| `standstill` | Perfect aim and fixed clicks, no movement keys, ordinary knockback |
| `wtap` | One short W release after a landed-hit cue |
| `wtap-tick` | Experimental post-hit W release lasting exactly one movement update |
| `wtap-tick-burst` | Same one-tick W-tap movement; 150 ms defensive click bursts after delayed hurt cues, plus predicted next-hit attempts |
| `wtap-tick-motion` | Immediate current-motion recoil clicks plus predicted attack windows; planned one-tick W-tap |
| `wtap-tick-critical` | Motion burst plus one optional locally eligible critical attempt per visible fresh-hit cue |
| `wtap-tick-window` | Same movement and attack windows, with optional recoil clicks disabled for comparison |
| `wtap-tick-fast` / `wtap-fast` | Continuous-click controls with zero observation delay; one-update / 50 ms W releases |
| `hold-w-fast` | Continuous W/Ctrl/clicking control with zero observation delay |
| `wtap-cps10` / `wtap-cps15` / `wtap-cps20` / `wtap-cps25` / `wtap-cps30` | Explicit click-rate experiment: same one-tick W-tap movement, with the named physical CPS ceiling |
| `trade-wtap` | Post-hit reset that prefers to release just after receiving a hit |
| `hit-reset` | Older confirmed-hit W-tap timing |
| `periodic-tap` | Fixed-rhythm control experiment; not normal W-tapping |
| `spacing` | Delayed closing-speed forecast with coasting and hysteresis |
| `predictive` | Spacing forecast with an explicit ping-dependent lead |
| `phase-spacing` | Adjust movement around a forecast hit window |
| `burst` | Spacing with explicit 300 ms click bursts per 500 ms cycle |
| `zest-w` / `zest-strafe` | Three-contact Zest cycle; selected W-up hit, hit-select entry/recovery |
| `zest-two-w` / `zest-two-strafe` | Two-contact variation of that cycle |
| `zest-tune-…` | Exploratory Zest timing candidates; results, not names, determine quality |
| `evolved-g…` | Saved parameter-search candidates, challengeable immediately |
| `evolved-champion` | Selected candidate, once a search finishes |
| `mpc-probe` | Approximate physics/packet beam search, specialized to the non-clicking probe |
| `mpc-visible` / `mpc-ensemble` | Experimental visible-trajectory reconstruction, with one or several tick-phase hypotheses |
| `mpc-duel` | Experimental two-way model of clicking hold-W, choosing ordinary W/Ctrl inputs |
| `mpc-diverse` | Longer planning horizon with distinct physical states and first-action alternatives |
| `mpc-macro` | Also compares complete opening/reset sequences and the shifted previous plan |
| `pvp-candidate` | Latest saved selection from the actual-score search, once it finishes |
| `pvp-knows-wtap` | Same candidate parameters, predicting the actual delayed post-hit W-tap policy |
| `pvp-knows-standstill` | Same candidate parameters, predicting a stationary clicking opponent |

- Boxing research: normal speed, no potions or enchantments, flat full-block ground, diamond swords. Health practice modes use the kits described above.
  Arena centers use bedrock with the same slipperiness as grass to prevent holes.
- Boxing: first to 100 **server-accepted attacks**. Health is restored after real
  damage so a duel can continue; knockback, damage immunity and critical-hit logic run.
  Diamond mode instead uses real health and ends on death.
- Perfect aim uses the current **local client view**. It does not read server
  positions or aim at removed entity copies left over from teleports.
- W or release and the normal sprint key. Only the explicitly named Zest
  strafing variants use A/D. No S, jumping or blocking.
- Legacy policies use **150 ms** tactical observation delay. The `motion`,
  `critical`, `window`, `fast` and `wtap-cps…` comparison policies use **zero artificial delay**, as
  requested; each recording declares both sides' settings. Planned timing is allowed.
- Default fixed policies schedule nominal **10 CPS**, with at least 100 ms between
  input clicks. The explicitly requested `wtap-cps15/20/25/30` experiments raise
  only their own ceiling. Each match records per-side rates; frame scheduling
  makes measured CPS lower than the configured ceiling. Burst is labelled.
- Every scheduled click enters normal Minecraft input and attack handling,
  including immune-frame attacks, air misses and their native slowdown/cooldown.
- Zest entry/recovery can explicitly hit-select, waiting for an incoming-hit
  observation before starting clicks. It does not suppress immune-frame clicks
  during its ordinary contact cycle.
- No direct bot movement/attack packets, custom reach, velocity overrides during
  combat, or immunity-based engine optimizations. Teleports/equipment are round setup.

Baseline policies hold the sprint key while W is held. Some experimental
policies select normal W and Ctrl independently. A restored client sprint flag alone does
not guarantee a fresh sprint-start packet after the server cleared sprint on an
attack. The W release/re-press creates that stop/start transition.
[THEORY.md](THEORY.md) explains the reasoning and
why known ping alone is insufficient to specify an exact optimal best response.
These are initial controllers and a small evolutionary search, with no claim of
global optimality or equivalence between every 1.7 and 1.8 combat detail.
The Zest controllers implement an interpretation of the creators' demonstrated
cycles. [Source notes](experiments/ZEST-NOTES.md) explain what the videos teach,
why their caption tracks were insufficient, and which timings we must measure.

## Experiments and recordings

From Terminal in this folder:

```sh
python3 experiments.py pilot
python3 experiments.py latency-pilot
python3 experiments.py integrity-load
python3 experiments.py evolve --population 4 --generations 1
python3 experiments.py evolve-locks --population 4 --generations 1
python3 experiments.py evolve-pvp --population 4 --generations 1
python3 experiments.py league --bots hold-w wtap hit-reset predictive --rtts 20,20 20,120
python3 experiments.py league --bots hold-w zest-w zest-strafe --rtts 0,0 20,100 20,140
python3 compare_wtap.py league
python3 compare_wtap.py ablation
python3 compare_wtap.py standstill
python3 wtap_research.py models
python3 burst_science.py my-burst-comparison
python3 burst_summary.py research/my-burst-comparison.json research/my-burst-summary.json
python3 burst_science.py my-motion-comparison --motion
python3 burst_science.py my-critical-comparison --critical
python3 cps_science.py my-cps-comparison
python3 cps_summary.py research/my-cps-comparison.json research/my-cps-summary.json
```

The pilot compares the hand-written controllers. The latency pilot includes
zero-ping controls and 20/100 ms asymmetric matches, with starting sides and ping
assignments swapped. The balanced league command extends that to every chosen
bot pairing. Evolution mutates three spacing
parameters, evaluates each against W-tap in both starting roles, saves the best
candidate, then tests different ping and a different opponent outside selection.
The random seed fixes parameter generation; process scheduling and game/network
clock phases still vary. Two games per candidate are an exploratory sample.

`evolve-pvp` selects by **actual hit margin against clicking hold-W**, with
sustained combo duration as a secondary objective. Each candidate plays both
starting sides at 20/100 ms RTT. W-tap controls and fresh matches at 0/0 and
100/20 ms test whether the selected candidate improves beyond its selection
games. A saved candidate is an experiment, not a proven champion.

`cps_science.py` compares the same continuous-click one-tick W-tap at 15, 20, 25
and 30 CPS against 10 CPS. Each rate gets four games at 20/20 ms added RTT, two
per starting side, with rate order reversed between repeats. Two 10-vs-10
controls bracket the cohort. Fresh-hit counts and damage upgrades are reported
separately; the existing first-to-100-accepted-attacks stopping rule is unchanged.
Inputs retain vanilla processing, including multiple queued clicks within one
native tick. The runner checks per-policy physical ceilings, measured rates,
native cadence and replication. `--smoke` runs four short setup checks.
For this comparison, start an idle lab with `python3 lab.py serve --frame-sleep-ms 1`.
This uses HeadlessMC's existing display-sleep setting equally for all bots, giving
the input scheduler finer timing. Ordinary startup retains its 10 ms display
sleep. The suite records that setting, actual CPS, and the unchanged native
20 Hz movement cadence; it rejects rates below 90% of their declared ceiling.

`compare_wtap.py league` runs the fixed-policy 36-game comparison of W-tap,
hold-W and the candidate, with four games per pair and oriented RTT condition.
`ablation` runs exactly three games under each opponent-model assumption, at
0/0, 20/100 and 100/20 ms candidate/opponent RTT. The known version changes only
`opponent_model=wtap`; movement choices, controller weights and physical click
timing stay the same. `standstill` checks stationary input and native knockback
against three pursuit policies at 20/100 ms.

`burst_science.py` freezes a 36-game comparison: burst one-tick W-tap versus
hold-W and continuous-click one-tick W-tap, plus continuous one-tick W-tap
versus ordinary W-tap and hold-W. Each pairing gets three games at 20/20,
20/100 and 100/20 ms RTT, with starting sides reversed in the middle repeat.
Two games run concurrently, with native tick-cadence and input checks on every
result. Use a fresh output name; `--smoke` runs two 12-second setup checks.

The public `wtap-tick-burst` bot retains the same one-update W release as
`wtap-tick`. It clicks during acquisition, for 150 ms after a legally delayed
incoming hurt cue, and from 75 ms before its estimated next fresh-hit window
until another landed-hit cue arrives. The estimate is visible cue time plus
500 ms minus its own configured RTT. A 150 ms tactical delay applies to every
new observation. The physical click limiter persists across quiet intervals;
all issued clicks retain ordinary Minecraft raycasts, misses and contact drag.
This is an approximate visible-cue schedule, not exact server immunity access.

The newer `--motion` cohort removes artificial observation delay. Its optional
recoil clicks require current backward motion, local sprint and an ordinary
in-range native selection at the input poll. It reserves the next 100 ms click
slot for an approaching attack window. Every fast control uses the same input
poll opportunities; all issued clicks still undergo vanilla processing. The
one-tick and 50 ms W releases remain planned for 150 ms after the visible hit
cue, preserving the earlier reset placement rather than imposing a reaction
limit. The 50 ms attack lead compensates for the shorter input queue delay.
The fixed 18-game design includes window-only ablations, side swaps and both
orientations of unequal RTT. Native traces must verify that optional recoil
contacts actually apply the 0.6 multiplier to backward velocity.

The `--critical` cohort changes only the motion-burst policy's extra critical
attempt rule. It permits one attempt per visible landed cue when the local
native critical predicates and ordinary contact checks hold, while preserving
the next attack slot. Six full 20/20 ms games compare it with hold-W, continuous
one-tick W-tap and motion burst, with sides swapped. Local eligibility does not
guarantee a server critical or a damage upgrade; both outcomes are traced.

`wtap_research.py` checkpoints every completed round and freezes the policies,
build hash and fixture list for each experiment. Its initial model screen ran
24 games: the unchanged known-W-tap model won two of four, while none of five
isolated weight/observer/dwell changes won a game. These changes did not produce
a reliable W-tap counter. See [the full model screen](experiments/antiwt-model-screen.md).
Use a fresh `--name` when rerunning, so earlier data stays intact. The new
`momentum` policy supports isolated sprint-reset timing, retaining W during a
Ctrl release, pursuit spacing and A/D experiments; these features use ordinary
keys and keep the fixed click clock.

`evolve-locks` is a diagnostic search against the non-clicking probe using the
conditional native one-sided percentage. It searches a small set of controller
weights and observer timing/velocity corrections. Each candidate gets both
starting sides at 20/100 ms RTT, followed by fresh repeats and 0/0 and 100/20 ms
tests for the selected `lock-candidate`. A round-duration penalty discourages
getting a high ratio by landing hits extremely slowly. Total attack opportunity
and continuous windows remain visible in the report. Selection games and fresh
tests are separate; this small search cannot establish an optimal algorithm.

The scheduler validates that both clients are alive, ticking, use the current
agent build, and see the canonical live opponent. It also checks that the native
opponent simulation region is loaded and its entity keeps ticking. Invalid setups and incomplete games fail the experiment rather
than silently awarding wins. See **experiments/RESULTS.md** after a run completes.
Early setup/debug matches are excluded from that report. Historical matches
called the fixed-rhythm control `wtap` incorrectly; these now appear as
`periodic-tap` in reports and replays, with the original raw logs preserved.
The first parameter search was against that periodic control, not the corrected
post-hit W-tap. It did not beat its control opponent.
Those historical runs also used the earlier click-clock bookkeeping; exact
input spacing is not established by their wall-clock logs. Current evaluations
require monotonic input intervals of at least 100 ms and reject violations.

Human duels `m0148` and `m0149` exposed a separate same-ID replacement bug:
the native entity map held a new opponent copy that was absent from the world's
ticking list. It remained at spawn while packet goals moved, producing invalid
attacks against a stale local view. `audit_duels.py` records those as invalid.
Round setup now separates ordinary server untracking and retracking by 500 ms,
before combat. A live watchdog requires the exact native entity to be in the
ticking list and chunk, with advancing ticks. If that fails, keys and queued
clicks are cleared, and the referee stops an invalid round. No combat positions,
velocities, hit immunity or reach limits are changed by this recovery.

**Replication qualification:** experiments before `m0121` used low chunk
distances that could freeze a remote entity even while packets updated its
position target. Those results are provisional and excluded from qualified
rankings; `m0066` and `m0101` conclusively contained frozen replication. Current
settings use server view distance 8 and client render distance 6, with continuous
target-tick and loaded-region checks. Larger view distance is needed for the
vanilla client's surrounding-area guard, even in an otherwise empty arena.

The main performance metric is **the score against clicking hold-W**. Sustained
combo duration comes second: at least three fresh knockback contacts while the
defender continuously lacks native reach, with no accepted return attack and
at most one second between contacts. Both players can briefly be out of range
inside that span; a long retreat without fresh contacts does not count.

New reports also include a contact sequence inside continuous defender reach
denial with no maximum hit-gap cutoff. Its fresh-hit count and largest contact
gap are reported explicitly. This distinguishes a real long denial sequence
with slower contacts from a zero caused solely by the strict one-second cutoff.

The conditional reach percentage is a diagnostic: time that **A can hit B and
B cannot hit A**, divided by **all time A can hit B**. Each side's own native
`getMouseOver` raycast is
sampled independently and aligned using shared host timestamps. Damage immunity
does not remove a reach opportunity. `native_windows.py` integrates durations,
excludes stale sample gaps, and reports paired coverage, total attack opportunity
and the longest continuous one-sided window. A 100–0 score against the
non-clicking probe is not a PvP win or proof of a sustained combo lock.

The model is a planning approximation. Neither its geometry nor its predicted
opponent state replaces the two real clients' measurements. Private opponent
client logs are used only for analysis after a match.

The newer duel model includes both players' contact slowdown, fresh knockback,
falling critical upgrades, and queued attacks. The completed two-generation
actual-score search ran 22 validated native games: two W-tap controls, 16 selection
games and four fresh tests. The saved `pvp-candidate` scored:

| Added RTT, bot/opponent | Fresh score against clicking hold-W |
|---|---|
| 20/100 ms | 100–65 |
| 100/20 ms | 100–78 |
| 0/0 ms, both starting sides | 100–77 and 100–95 |

The selected parameters produced short 1.90 s and 1.55 s reach-denial locks in
selection games. None of the four fresh tests qualified as a sustained lock.
This does **not** meet the requested 100–10/20 or indefinite-lock target.
The W-tap controls scored 100–83 and 100–78 at 20/100 ms. Small samples and
clock-phase variability limit the comparison. See [the full search](experiments/pvp-search.json)
for parameters, validation, selection games and separate fresh tests.

A subsequent fixed-policy comparison ran 36 games, with four games for each
pairing and RTT condition and balanced starting sides. Mean scores below are
oriented as the first-named bot/opponent:

| First bot / opponent | 0/0 ms | 20/100 ms | 100/20 ms |
|---|---:|---:|---:|
| W-tap / hold-W | 100–74.5 | 100–85 | 100–80 |
| Candidate / hold-W | 99.75–85 | 100–77 | 100–68.5 |
| W-tap / candidate | 100–72.25 | 100–76.75 | 100–80.25 |

W-tap won all 12 head-to-head games against the original candidate. The candidate
had larger margins against hold-W at the asymmetric pings. These matchups do not
establish a universal ranking. See [all scores and strict lock durations](experiments/WTAP-COMPARISON.md).

The requested six-game opponent-model test changed only the candidate's
prediction of the opponent's movement. Scores are candidate–W-tap:

| Candidate / W-tap RTT | Assumes hold-W | Models W-tap |
|---|---:|---:|
| 0/0 ms | 78–100 | 79–100 |
| 20/100 ms | 77–100 | 100–97 |
| 100/20 ms | 74–100 | 100–91 |

Knowing the W-tap policy helped in the two asymmetric-ping trials and barely
changed the zero-ping trial. There is only one game per cell, so this is a
diagnostic result requiring repeats. All six games passed native input, reach
and replication checks. [Full data and model diagnostics](experiments/wtap-opponent-model.json).

The stationary clicking control was tested at 20/100 ms attacker/opponent RTT:

| Attacker / standstill | Score | Strict lock (gaps ≤1 s) | Denial contact sequence | Fresh hits / largest gap |
|---|---:|---:|---:|---:|
| Hold-W | 100–24 | 1.90 s | 1.90 s | 4 / 0.85 s |
| W-tap | 100–1 | 0 s | 79.90 s | 76 / 1.90 s |
| Candidate modeling standstill | 100–9 | 5.35 s | 15.40 s | 22 / 1.55 s |

All three passed input, reach and replication checks; the target pressed no
movement keys and still moved from normal knockback. Each row is one game.
W-tap's strict zero comes from the one-second hit-gap cutoff. Its 79.90-second
contact sequence remains inside measured continuous opponent reach denial,
with no accepted return hit. A finite test against a stationary opponent at
one ping setting does not establish an indefinite lock against hold-W.
[Control data and telemetry checks](experiments/standstill-controls.json).

Every match records server trajectories and accepted hits in
`runtime/replays/<match>.jsonl`, with client input/attack telemetry under
`runtime/bots/<slot>/events.jsonl`. Export a finished match:

```sh
python3 replay.py m0025
```

Open the resulting file in **recordings/** in your browser. It includes play,
pause, speed, scrubbing, overhead/side views, scores and recorded input cadence.
New recordings also show each client's native reach status and the conditional
one-sided percentage. Historical recordings carry their qualification limits.
These are **trajectory analysis replays, not Minecraft video**. All data is
embedded, with no external resources. Sample recordings include historical
spacing experiments and newer matches with the strict monotonic click cap.

Qualified examples: [W-tap vs hold-W, 100–83](recordings/m0161.html),
[W-tap vs the original candidate, 100–73](recordings/m0281.html),
[candidate modeling W-tap, 100–91](recordings/m0304.html),
[candidate vs standstill, 100–9](recordings/m0307.html),
[W-tap vs standstill, 100–1](recordings/m0308.html),
[a fresh selected-controller test, 100–65](recordings/m0257.html),
[a selection game with a short lock, 100–68](recordings/m0241.html),
[a reach-edge controller trial](recordings/m0168.html), and
[a 79% one-sided probe trial](recordings/m0169.html). The latter's fresh zero-ping
repeats reached 65% and 48%, so it is not a stable combo lock or a proven champion.

## Setup and checks

This implementation targets macOS and uses the existing Homebrew Java 17 and
Python installations. No Forge, Fabric, Mineflayer, npm or pip install is needed.
For a fresh copy on a similarly configured Mac:

```sh
python3 lab.py prepare
python3 lab.py build
python3 lab.py sandbox-test
python3 lab.py serve
```

`prepare` downloads hash-locked dependencies without executing upstream builds.
The Java agent compiles locally with annotation processing disabled. `serve`
checks dependency hashes and refuses to launch if the sandbox checks fail.

```sh
python3 lab.py status
python3 lab.py match hold-w wtap 20 120
python3 lab.py stop
python3 -m unittest -v test_lab test_model
python3 lab.py logging-test
python3 benchmark.py --seconds 20
```

`python3 lab.py verify-ux` uses a separate real headless client and isolated test
proxy to check commands, live ping changes and rematch ping retention. Start a
fresh match first. Logs are in
`runtime/logs/`. Keep the agent build fixed during an experiment. After Java
changes, run `python3 lab.py build` and wait for completion, then
`python3 lab.py reload-bots`. This reloads idle bots without disconnecting your
Minecraft client. Busy bots are skipped; reload them after their duel ends.

The current steady sample had **three concurrent bot matches**, eight clients
plus the server, view distance 8 / render distance 6. The nine supervised JVMs
used about **6.1 GiB RSS** and **105% of one CPU core** over 15 seconds. Every
client advanced at approximately 20 native ticks/second. Server tick gaps were
50 ms median, 55 ms p99 and 56 ms maximum. A separate startup sample included
one 147 ms gap. The graphical user client and temporary UX client are excluded
from the process resource totals. This supports the current three-match cap on
this M2 Max, not a maximum-capacity claim. See
`experiments/performance-three-matches.json` and
`experiments/performance-three-matches-steady.json`.

## Security

All game listeners bind to this computer's IPv4 loopback. There is no public
server, router change, tunnel, RCON, account-token access or authentication
launcher. Runtime JVMs have a tested macOS sandbox, a clean environment and a
separate home directory. Waiting bots use spectator mode and arenas are widely
separated. See [SECURITY.md](SECURITY.md) for dependencies, restrictions and limits.
