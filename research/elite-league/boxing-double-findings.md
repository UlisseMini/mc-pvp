# Equal-CPS doubles against W-tap

`boxing-spacing10` won six of eight first-to-100 Minecraft bouts on the practice
lab's MMC preset. It took three of four at each latency. The scores, with the
trained bot first, were 96–100, 100–84, 100–83 and 100–92 at 0 ms, and
100–93, 100–95, 98–100 and 100–73 at 80 ms. Both fighters kept clicking at a
configured 10 CPS with the same mouse tracker. The logs measured 9.30 and
9.29 CPS on average across the eight candidate bouts.

| Native metric | Plain W-tap mirror | Trained movement |
|---|---:|---:|
| First-to-100 wins from the nominated seat | 2/8 | 6/8 |
| All doubles for / against in full bouts | 52 / 51 | 77 / 52 |
| Doubles after a paired trade, for / against | 9 / 5 | 27 / 7 |
| First K3, starts / activity-qualified cycles | 3 / 1 | 4 / 3 |
| First K3, starts / return-only cycles | 3 / 1 | 4 / 4 |
| First K5, starts / cycles | 0 / 0 | 2 / 3 |
| First K10, starts | 0 | 0 |

A double has two consecutive fresh hits no more than 850 ms apart, with no
accepted opposing hit through the second hit's server tick. A paired trade is
an isolated pair of opposing fresh hits within 75 ms. Its double score asks
whether one side gets the next two fresh hits without taking an accepted hit.
The full bouts had 455 paired trades with the candidate and 502 in the
mirrors. Three candidate trades and one mirror trade were censored at the
end of the match.
The native maintenance starts have independent queues and match clocks.
All first K3 runs eventually took a return. The activity cutoff ended one
candidate run before that return.

On 32 simulated first-to-100s from timing recordings collected after the policy
was frozen, the candidate won 29 and the W-tap mirror won 16 from the nominated
seat. The matched K3 continuations from 14 identical states gave 14
activity-qualified cycles for the candidate and 10 for W-tap. Four states
improved, two fell back, and the others tied. The candidate's clear whole-match
edge and the sparse maintenance result answer different questions.

## Frozen movement at 20 CPS

`boxing-spacing20` uses the same frozen movement weights as `boxing-spacing10`.
It won seven of eight first-to-100 native bouts against plain W-tap, with a
median accepted-score margin of +11 hits. It won three of four at zero added
RTT and all four at 80 ms. The only loss was 99–100. The average measured
click rates were 16.65 and 16.67 CPS at the shared 20 CPS cap. The movement
policy did not gate clicks. Across the eight candidate bouts the 98-hit
score advantage came entirely from fresh hits. Both sides had 103 upgrades.

| Native first-to-100 metric | Plain W-tap mirror | Frozen movement |
|---|---:|---:|
| Wins from nominated seat | 2/8 | 7/8 |
| Score margins at 0 ms | −2, −7, +5, −1 | +8, +10, −1, +24 |
| Score margins at 80 ms | −2, −2, +19, −3 | +12, +6, +26, +13 |
| All doubles for / against | 35 / 26 | 50 / 18 |
| Doubles after a paired trade, for / against | 10 / 9 | 26 / 0 |

The candidate had 520 eligible paired trades, three censored at the bout
end. The mirrors had 612 trades and six censored. In 17 of the candidate's
26 post-trade doubles, every opponent click consumed between the two
fresh hits missed the target or was suppressed by a miss cooldown. The
opponent made contact during nine. In the nominated seat of the mirrors,
the corresponding counts were nine and one out of ten doubles. All of
these click intervals were linked to the native contact trace.

The eighth mirror was a same-settings repeat after m4484 ended with an
invalid client opponent view. Its replacement, m4515, completed 97–100.
Both records remain available. The invalid attempt was excluded from
scoring and from the timing donors.

The eight completed mirrors supplied timing calendars collected after the
weights were frozen. On the paired simulator replays at 20 CPS, the
trained policy won 23/32 with a +7.5 median score margin. Plain W-tap won
16/32 from the nominated seat. The candidate won 13/16 at zero RTT and
10/16 at 80 ms, with margins spanning −11 to +21 and −4 to +31,
respectively. The calendars supply host opportunities and retain the
simulator's transport and input assumptions.

The separate 20-second maintenance matches left the candidate on plain
W-tap until the referee observed a K3 landmark after both bots began
clicking. Every first K3 return was a fresh hit in this cohort.

| Native maintenance metric | Plain W-tap | Frozen movement after K3 |
|---|---:|---:|
| K3 entries in eight independent bouts | 4 | 2 |
| K3 return-only additional cycles and exposure | 0, 2, 0, 1 over 150, 1460, 54, 1451 ms | 1, 5 over 900, 3312 ms |
| K3 activity-qualified cycles and exposure | 0, 1, 0, 1 over 150, 1455, 54, 1449 ms | 1, 5 over 900, 3312 ms |
| K3 return-only failures / right-censored | 4 / 0 | 2 / 0 |
| K3 activity-qualified failures / inactivity / right-censored | 2 / 2 / 0 | 2 / 0 / 0 |
| K5 entries / additional cycles | 1 / 0 | 2 / 3 |
| K10 entries | 0 | 0 |

The candidate's two K3 starts were in seat A at 80 ms. Both had the
attacker at floor height and a defender 0.790 blocks above the floor. The
four W-tap starts had both players at floor height. The first learned
input decision took effect 36 ms after each accepted landmark. Neither return was
already in flight at the landmark. The native comparison is descriptive
because the two arms acquired different states and had independent
clocks and packet queues.

The post-freeze model comparison acquired nine K3 states once with plain
W-tap, then forked each complete state for both arms. W-tap completed
three additional cycles and the candidate completed four. One state
improved and eight tied by cycle count. Both took nine returns and the
exposures summed to 3.293 and 3.191 seconds, respectively. Both definitions
agreed because none of the episodes reached the inactivity cutoff. K5
had one matched state and zero extra cycles for either arm. K10 had no
state. The evidence supports the regulation score advantage and leaves
the general maintenance improvement unresolved.

Timing explains much of the limited room for improvement in this gate.
Six of the nine model states returned within 0–52 ms at 80 ms RTT. One
return had already been accepted in the landmark's server drain. In the
other five, the return preceded the arrival time of any movement update
from the candidate's first new input head even at the nominal 40 ms
uplink. The only additional cycle was on the m4500 donor, in seat A at
80 ms, with the defender 0.790 blocks above the attacker when the fork
started. This is a comparison on one complete state, with no return-time
gain. Establishing a better position before K3 is a separate problem
from prolonging a combo once that state is given.

## Why the fast returns start before the third hit

The follow-up traced the six fast-return model cases to unique queued
attack packets. W-tap consumed all six attack presses 8.9–80.9 ms before
the server accepted the third hit. W-tap was at floor height and had a
2.305–2.909 block ray when it clicked. Even the return processed in the
same server drain was a separate contact issued 80.9 ms earlier.

The 20 CPS native regulation bouts showed the same ordering.

| During whole matches | Plain W-tap mirror | Frozen movement |
|---|---:|---:|
| Three-hit entries after both players began clicking | 9 | 26 |
| Further fresh hits before return, activity-qualified | 10 | 48 |
| First returns within 52 ms of third hit | 0 | 9 |
| Those fast returns with the opposing click consumed before the third hit | 0 | 7 |

All 35 returns were individually linked. These are descriptive excerpts
from independently played regulation matches. The matched maintenance
comparison above retains the same pre-landmark policy across arms.

The first diagnostic forked each fast case immediately after the second
accepted hit. It replayed all 14,375 combinations of a four-tick
longitudinal movement word and selected cut times. The cut times covered
earlier movement and windows starting roughly 240 and 360 ms before the
defender's known return click. W-tap's own inputs were replayed exactly
in each unchanged fork. No word preserved the third hit and added a
fourth before the first return within the activity cutoff. This is a
bound on the tested words and timings from these six states.

Changing the second hit itself exposed more room. An isolated simulator
intervention reduced the already queued horizontal knockback to the
normal-hit magnitude of about 0.527 blocks per tick. It preserved the
vertical impulse and the input and packet queues. This rescued two of
the six cases across the recorded timing and both ±10 ms defender phase
shifts. The experiment then used ordinary movement keys in the preceding
interval, from the exact first-hit state. It searched for an actual
normal-strength second hit with the same zero vertical impulse.

| 80 ms development clock | Plain W-tap on the original timing | Ordinary movement producing a normal K2 | Same input word across three phases |
|---|---:|---:|---:|
| m4494, seat B | 2 more fresh hits, then a return | 183 words added at least a fourth hit | 0 words |
| m4511, seat A | 2 more fresh hits, then a return | 61 words added at least a fourth hit | 47 words |

The words were chosen with access to the completed simulation. On m4511,
one tick of W without Ctrl before the second hit reduced the actual
horizontal impulse from 0.854 to 0.527 and preserved the zero vertical
impulse. W-tap had two further fresh hits under all three schedules,
while the one-tick change gave four, three and four. The vulnerable case
on m4494 shows the risk of choosing the timing word from one schedule.
The earlier validation tapes are development data for this diagnosis.

The exact m4511 action was W without Ctrl on the seventh input head
after the first accepted hit, with ordinary W-tap at every other head.
The second hit still scored 443 ms after the first. The third scored
894 ms after the first instead of 1,301 ms, and the fourth landed before
the return. The same single tick worked under ten of eleven defender
phase shifts spaced 5 ms apart from −25 to +25 ms. At +25 ms the
second hit still had normal strength and the fourth was lost. These
paired branches share one exact first-hit state. The action timing
uses a server landmark for diagnosis. An own-client trigger and new
native repeats remain open.

## Cue-timed sprint release

In m4511 at 80 ms, the successful input head came 263 ms after the
local target hurt cue. The attacker was grounded, the target appeared
0.288 blocks higher, and the nearest point on its hitbox was 2.623
blocks away. The rule waits for an actual local sprint contact near
that cue age, then releases Ctrl for the following movement step while
holding W. This is the step where W-tap would normally re-arm sprint.
The rule uses the client's received hurt cues and configured RTT. It
leaves click timing and the 20 CPS ceiling unchanged.

Six variants were screened on 1,248 common first-hit states. The
timing-only rule selected there allows one release per target hurt cue,
with no newer own hurt cue, at 450 ms minus RTT minus 80 ms, within
28 ms. It reproduced four follow-up fresh hits versus two on m4511.
The separate transfer panel used eight recorded host calendars that
had appeared in other studies and had never supplied examples to this
rule. The release was frozen before scoring them. Both seats ran on
each calendar at each RTT. The first-hit forks and all maintenance
comparisons shared the exact pre-fork state, queues and observation
history with plain W-tap. The regulation runs started at equal settings
with both bots clicking from the opening.

| Added RTT | Common first-hit forks | W-tap further active fresh hits | Release further active fresh hits | W-tap first-to-100 wins | Release first-to-100 wins | W-tap median margin [range] | Release median margin [range] |
|---|---:|---:|---:|---:|---:|---:|---:|
| 0 ms | 185 | 42 | 42 | 8/16 | 11/16 | 0 [−11, +11] | +4 [−15, +16] |
| 20 ms | 192 | 39 | 45 | 8/16 | 7/16 | 0 [−15, +15] | −1.5 [−18, +30] |
| 80 ms | 239 | 65 | 64 | 8/16 | 10/16 | 0 [−9, +9] | +2 [−8, +15] |

Within 2.2 seconds, the counts of forks that reached a fourth active
fresh hit were 3, 1 and 6 under W-tap and 3, 3 and 6 under the release.
The win split by seat A/B changed from 7/1 to 8/3 at 0 ms, 6/2 to 6/1
at 20 ms and 6/2 to 5/5 at 80 ms. At K3, W-tap completed 9 further
cycles across 10 exact starts (3/6/0/1 starts with 0/1/2/3 further hits)
over 6.53 seconds of total exposure. The release completed 7
(3/7/0/0) over 5.64 seconds. Individual times to return ranged from
0 to 1.75 seconds for W-tap and 0 to 0.95 seconds for the release.
At K5 the three exact starts yielded W-tap 0/1/2 further hits and
1.96 seconds total, and the release yielded 0/1/4 and 2.80 seconds
total. Individual K5 return times ranged from 0 to 1.30 and 0 to
2.15 seconds, respectively. Every fork at K3 and K5 ended with an
accepted return. There were no inactivity or right-censored endpoints,
so the return-only and activity counts agree. There were no K10
starts on this panel. The rule did not pass the maintenance gate.

Among first-hit forks where both arms still landed an unanswered
second hit and the release preceded that hit, the next horizontal
impulse changed from 0.854 to 0.527 blocks per tick in only 3 of 6
cases at 20 ms and 5 of 15 at 80 ms. It changed in the opposite
direction in 3 cases at each RTT. For example, on m4584 seat B at
80 ms both arms scored the second hit at the same time with the same
vertical impulse. The release arm actually landed the stronger hit
and completed one fewer cycle. At about 404 ms after the first hit,
the two arms both lost server sprint after the same earlier attack.
The release arm's brief stop and restart then put a sprint-start
command into the 454 ms drain before the scoring attack in the
506 ms drain. In m4587 the restart was instead queued behind the
scoring attack in the same drain. It made that hit normal strength
and gave an extra cycle. A useful release depends on which attack
scores and the sprint commands that precede it at the server. The
previous hurt cue's age alone leaves both uncertain.

Eight unchanged native W-tap mirrors exposed 79 contacts where the
local trigger would fire, 5 at 0 ms and 74 at 80 ms. W-tap had already
re-entered sprint by the following movement step at every one. A new
contact occurred within one movement step at 5/5 opportunities with
a subsequent contact observed at 0 ms and 44/72 at 80 ms. These
captures show how often native W-tap re-arms and how long the rule
would have to affect an attack. The release was simulated, so there
is no native outcome comparison or promotion for it.

[Study source](BoxingSprintReleaseStudy.java) ·
[Frozen rule and transfer split](../../build/boxing-double-search/sprint-release/frozen-rule.json) ·
[Paired launch audit](../../build/boxing-double-search/sprint-release/transfer-launches.jsonl) ·
[Packet timelines](../../build/boxing-double-search/sprint-release/wire-timeline.log)

## Where a weaker second hit helps

The broader diagnostic includes all 344 second fresh hits reached by
W-tap after both sides began clicking, with 2.2 seconds of clock left.
It includes both seats at 0, 20 and 80 ms on 16 previously exposed
calendars. Each branch starts after the same accepted second hit and
changes only the horizontal magnitude of its queued velocity packet.
It preserves the vertical impulse, direction, sprint state, already
committed attacks, policy history and other queued effects. The
unchanged branch reproduces every recorded control suffix in the
simulation. This tests the value of changing recoil, before asking
whether a legal input can do it.

| W-tap second hit | Starts | W-tap further active cycles | Weaker impulse cycles | W-tap fourth hits | Weaker impulse fourth hits | W-tap summed score margin | Weaker impulse summed margin |
|---|---:|---:|---:|---:|---:|---:|---:|
| 0 ms, upward launch | 110 | 8 | 1 | 4 | 0 | −111 | −119 |
| 20 ms, upward launch | 110 | 10 | 6 | 4 | 2 | −92 | −139 |
| 80 ms, upward launch | 77 | 15 | 10 | 5 | 2 | −76 | −89 |
| 80 ms, zero upward launch | 13 | 6 | 18 | 0 | 3 | −2 | +10 |

The table covers the 310 originally strong launches. The other 34
starts test the reverse change in the raw output. In the last row
with a 10 ms shift of the defender's future client opportunities,
W-tap had 12 further cycles and zero fourth hits versus 16 cycles
and two fourth hits after the weaker launch. The same two starts
accounted for the stable fourth hits in both phases. Both attackers
were grounded. In m4494 seat B and m4511 seat A, lowering the impulse
advanced the third hit from 855→454 ms and 857→451 ms after the
second. In five other nominal gains, the weaker hit added a third but
hurried the first accepted return. The full baseline and branch
scores include returns and upgrades during the same 2.2-second horizon.

An ordinary-input search replayed those 13 zero-lift states from
their respective common first hits. It tried 340 timings for holding
W without Ctrl for one to four movement heads near the second
contact. Four starts admitted a better fourth-hit-or-longer sequence
at nominal timing, including m4494 seat B. Only m4511 improved on
the corresponding W-tap branch under all three requested defender
phase shifts (0 and ±10 ms). Several hold lengths worked on that
one state. The stable impulse-only witness in m4494 had ten
nominal input solutions and no three-phase input solution.
The search uses completed simulations to choose timings.

In the independently played native matches, the strong horizontal
second hit with zero lift appeared in 3/47 W-tap and 10/57 frozen
movement two-hit entries at 80 ms. Only one of each had a grounded
attacker. No such hit appeared in their 85 and 72 starts at 0 ms.
The archived matches test occurrence only. No weaker-hit intervention
ran in the native matches.

[Complete paired model results](../../build/boxing-double-search/second-hit-envelope/k2-comparisons-timed.jsonl) ·
[Ordinary-input searches](../../build/boxing-double-search/second-hit-envelope/k1-ordinary-inputs.jsonl) ·
[Native second-hit audit](../../build/boxing-double-search/second-hit-envelope/native-second-hits.json)

## Frozen movement at 20 ms RTT

The model scores below use the frozen movement policy and 20 CPS
ceiling. Each panel has eight historically exposed native timing
calendars and both seats. The prior native donors were originally
collected to test 0 and 80 ms. The other panel comes from a separate
native calibration. Neither panel is a new sealed test.

| 20 ms timing panel | W-tap wins | Frozen movement wins | W-tap median margin [range] | Frozen movement median margin [range] | W-tap fresh / upgrade margin | Frozen movement fresh / upgrade margin |
|---|---:|---:|---:|---:|---:|---:|
| Prior native donors | 8/16 | 7/16 | 0 [−12, +12] | −1 [−7, +19] | 0 / 0 | +90 / −51 |
| Other native donors | 8/16 | 5/16 | 0 [−15, +15] | −2.5 [−17, +9] | 0 / 0 | +25 / −65 |

Against frozen movement, W-tap scored 448 upgrades across the
panels. For 432 of them, the candidate's last fresh hit was at or
before W-tap's preceding fresh hit. The candidate had that ordering
on 172 of its 332 upgrades. In W-tap mirrors it occurred on 200
of 231 per side. The extra W-tap points were concentrated in
sequences where it already held the most recent fresh hit or shared
its server drain. This comparison uses server drain timestamps and
leaves same-drain attack order unresolved for this diagnostic.

W-tap completed five further active cycles across five exact K3
starts versus four for frozen movement. Total exposure was 6.10
versus 5.60 seconds. The distribution across the five starts was
one each at zero and two, plus three at one for W-tap. Frozen movement
had two at zero, two at one and one at two. Both arms ended with an
accepted return in every branch, so return-only and activity-qualified
outcomes agree. The sole K5 start ended with a return 744 ms later
with no further hits in either arm. There were no K10 starts.

[Model score panels](../../build/boxing-double-search/rtt20) ·
[Damage-upgrade timeline](../../build/boxing-double-search/rtt20/upgrade-timeline.jsonl)

### Native 20 ms confirmation

All 16 trials finished under the same Forge bridge and server
build as the 0 and 80 ms trials. Four independent first-to-100s
per arm included two per seat. Click delivery for frozen movement
and its opponent averaged 16.59 and 16.57 CPS on the shared 20 CPS
cap. The mirrors averaged 16.57 and 16.63.

| Native first-to-100 metric | Plain W-tap mirror | Frozen movement |
|---|---:|---:|
| Wins from nominated seat | 2/4 | 2/4 |
| Seat A margins | +2, +2 | −4, +13 |
| Seat B margins | −6, −13 | −7, +1 |
| Median accepted margin | −2 | −1.5 |
| Fresh-hit margin | −15 | +16 |
| Damage upgrades for / against | 27 / 27 | 29 / 42 |
| Doubles after a paired trade, for / against | 0 / 2 | 6 / 2 |

W-tap had the most recent fresh hit on 41 of its 42 damage upgrades
against frozen movement, in replayed server attack order. That is
the same type of sequence that produced most of its model upgrade
advantage at 20 ms. The candidate won once in each seat. Across
the 0, 20 and 80 ms cohorts, the frozen policy has 9/12 wins and a
median +9 accepted hits. The 20 ms cohort does not establish a
clear advantage at that latency.

There were no K3 entries in any of the four W-tap or four gated
candidate maintenance trials. The gate stayed closed. K5, K10 and
survival conditional on K3 cannot be estimated from these eight
trials. They were independent runs of the same acquisition protocol.

### Model replay of the new 20 ms schedules

The four new mirror calendars were collected after the movement
weights were frozen. With both seats per calendar, W-tap won
4/8 and frozen movement won 3/8 as the nominated player. The
median accepted margins were 0 and −2, with ranges [−5, +5]
and [−9, +10]. Frozen movement's +8 fresh-hit margin was again
offset by a larger upgrade deficit (−16 versus zero in the
mirrors). The simulation reuses the host schedules and supplies
its own gameplay and packet outcomes.

| Identical model starts | W-tap cycles active / return-only | Frozen movement cycles active / return-only | W-tap exposure active / return-only | Frozen movement exposure active / return-only |
|---|---:|---:|---:|---:|
| K3, three starts | 10 / 11 | 5 / 5 | 6.69 / 6.79 s | 3.50 / 3.50 s |
| K5, one start | 7 / 8 | 4 / 4 | 4.70 / 4.80 s | 3.00 / 3.00 s |
| K10, one start | 2 / 3 | 2 / 3 | 2.00 / 2.10 s | 1.95 / 2.00 s |

At K3 the individual active cycle counts were 9/1/0 for W-tap and
4/1/0 for frozen movement. The 9 became 10 under return-only scoring.
There was one W-tap inactivity end at K3 and two W-tap returns.
Frozen movement had three returns. At K5 and K10 both arms ended
by inactivity in the active metric. All return-only episodes
ended with an accepted return. No episodes were right-censored.
The long W-tap continuation on m4761 seat A accounted for the
K3 gap and supplied the only K5 and K10 starts. A replay from
that K3 finds the third follow-up hit was equally strong in the
two branches. Frozen movement delivered it at a 3.69-block
server separation and waited another 950 ms for its next hit,
which drew a return 49 ms later. W-tap's third follow-up started
from a 2.93-block separation and its next fresh hit took 504 ms.
The new score panel was untouched by the frozen policy selection.
The m4761 repeat has now been opened for diagnosis and is
development evidence for any follow-up changes.

[Qualified native scores](../../build/boxing-double-search/rtt20/scoring-input.scores.json) ·
[Verified K3 handoffs](../../build/boxing-double-search/rtt20/native-k3.json) ·
[Damage-upgrade audit](../../build/boxing-double-search/confirmation-boxing-spacing20-rtt20-qualified.upgrades.json) ·
[New model scores](../../build/boxing-double-search/rtt20/prospective.jsonl) ·
[m4761 continuation trace](../../build/boxing-double-search/rtt20/m4761-k3-trace.json)

### Which input changed the 20 ms continuation

The input probe restored each full established state, including
both policies' history and the pending queues. In each branch it
substituted the other policy's longitudinal keys at one input
head and resumed the original policy thereafter. Click admission,
aim, jumping and strafing were equal. Both unchanged trajectories
were verified against the original policies. The sweep used 1,615
one-head substitutions across nine K3, K5 and K10 checkpoints with
the recorded future timing and three defender phase perturbations.
It includes the m4761 witness and 16 historically exposed donors.
Phase perturbations are sensitivity checks on the same acquired
state and have requested offsets 0, −10, +10 and +20 ms.

On m4761 seat A, W-tap released W and Ctrl at the first input
after the third hit. Swapping only that release for the frozen
network's W+Ctrl pressed at +20.7 ms caused the fourth hit to lose
its sprint bonus. The scoring click was consumed at the same
instant and accepted at +497.0 ms in both arms, with the attacker
grounded and the target rendered 0.770 blocks higher. W-tap's stop
at +51.0 ms and restart at +101.3 ms left the server sprinting when
the C02 arrived. The sprint-press branch sent neither command before
the scoring drain and reached it with server sprint off. The
outgoing horizontal speeds were 0.854 and 0.527 blocks per tick.
Vertical speed was 0.361 in both.

| m4761 seat A from K3, activity-qualified | Plain W-tap | One head of W+Ctrl in place of the first release | W-tap inputs with only weaker outgoing recoil |
|---|---:|---:|---:|
| Recorded timing | 9 | 3 | 3 |
| Requested −10 ms | 2 | 3 | 2 |
| +10 ms | 11 | 3 | 2 |
| +20 ms | 11 | 2 | 2 |

Keeping the earlier sprint press and restoring just the stronger
packet recovered five cycles at nominal timing and six at each
positive phase shift. This accounts for part of that input effect.
The swing and geometry also changed over the run. Swapping a
later release could help. At the K3 input head at +2,128.8 ms,
the one-tick sprint press gave 12 cycles on the original clock
versus W-tap's nine. It gave six at either positive phase shift
versus W-tap's 11. No tested single swap at K3, K5 or K10 beat
W-tap across all four m4761 timings.

The direction reversed for frozen movement from the m4511 seat A
checkpoint. Substituting W+Ctrl for W alone at +205.3 ms or
+310.3 ms let an intervening immune contact interrupt sprint.
The next scoring attack was then processed before the restart
command in the same server drain. Both arms scored at +451.0 ms.
The weaker launch left the frozen policy able to land the next
fresh hit at +1,050 ms, compared with +1,399 ms for the stronger
launch. Restoring only the stronger impulse on the altered
input sequence returned the short follow-up count at each
tested phase. This mechanism also occurred in seat B on the same
donor, with smaller nominal gains. Counts at the four phases
were as follows when applying the later of the two tested W+Ctrl
presses on each seat.

| m4511 from K3 | Plain W-tap | Frozen movement | Frozen movement with one W+Ctrl press |
|---|---|---|---|
| Seat A, further active cycles | 2 / 2 / 3 / 2 | 2 / 2 / 2 / 1 | 9 / 10 / 4 / 7 |
| Seat B, further active cycles | 1 / 1 / 2 / 2 | 1 / 1 / 1 / 1 | 3 / 3 / 3 / 3 |

The pattern did not hold across independent clocks. In an
additional scan of the first post-K3 launch from 25 matched
states across 0, 20 and 80 ms, the two gains for frozen
movement from a weaker launch were both m4511 seats at 20 ms.
At 20 ms, switching only the first outgoing horizontal impulse
changed the six-state cycle totals from 14 to nine for W-tap
and from eight to 15 for frozen movement. At 0 ms the respective
totals stayed three and one. At 80 ms they fell from 12 to ten
and from 17 to nine when testing the opposite impulse class.
No first launch means no intervention in that branch.

The native regulation archives at 20 ms had six K3 entries for
frozen movement and zero from the nominated seat of W-tap
mirrors. The six entries produced two further fresh hits under
the activity cutoff and three by return-only scoring. All six
returned and one crossed the inactivity cutoff first. The only
active fourth hit in regulation was a full 0.854 horizontal and
0.361 vertical launch 601 ms after K3. These are naturally
acquired states and give no native controlled comparison of
single input swaps. The separate native K3 trial arms both had
zero entries. No new controller was promoted.

[Input and phase results](../../build/boxing-double-search/continuation-inputs/m4761-phases.jsonl) ·
[Packet and impulse traces](../../build/boxing-double-search/continuation-inputs/impulse-wire-sprint-history.jsonl) ·
[Broader K3 impulse scan](../../build/boxing-double-search/continuation-inputs/impulse-envelope-wire.jsonl) ·
[Native regulation audit](../../build/boxing-double-search/continuation-inputs/native-20-regulation-k3.json)

### Same view, different timing

At nine input heads selected from the m4511 and m4761 development
recordings, the model paused immediately before the input decision.
All branches had the same completed physics, packet and click
queues, local observations and actual input history up to that cut.
The pending input head itself stayed in place. For each cut, 45
continuations crossed 0–20 ms of added defender phase, 0–5 ms of
added server phase and 0–4 ms of extra own uplink for newly sent
packets. All pre-cut events and packets already in flight remained
committed. The scoring code verified exact equality of the input
observation at the fork and reproduced the nominal outcomes.
These are equally weighted timing sensitivities, with no estimated
probability assigned to the variants.

On m4511 seat A, the input 205.3 ms after K3 saw a grounded,
sprinting player, a 2.713-block distance to the expanded hitbox
and a target 0.605 blocks higher. On the original clock the key
swap gave nine further active hits versus two from frozen
movement. Moving future server ticks by 5 ms, with defender
and uplink timing fixed, gave three versus four with the same
input observation. The original key now also delivered the normal
0.527 horizontal impulse. Changes to which sprint commands had
reached the server explained this change in strength. It makes
an early choice based on the benefit of the weaker hit alone
unreliable. The early key swap improved 27 of the 45 branches,
hurt 12 and tied six. It lost 33 active cycles in aggregate.

Waiting two input heads, about 105 ms in seat A and 102 ms in
seat B, was a separate intervention from the same earlier cut.
The ordinary keys until the later head stayed under the original
policy. In seat A the 45 branches yielded only two distinct
histories at the later input decision. In the 15 still sprinting
branches the later swap added hits. In the 30 with sprint off
it made no difference. Both groups had the same rendered
target height and target hurt age. Waiting exposed an own-client
sprint difference that was hidden at the earlier cut.

| m4511 from the earlier input cut, 45 timing variants per seat | W-tap from the same cut | Frozen movement | Swap at the earlier head | Swap two heads later |
|---|---:|---:|---:|---:|
| Seat A further active cycles, sum [range] | 45 [1–1] | 237 [1–19] | 204 [3–11] | 297 [3–19] |
| Seat B further active cycles, sum [range] | 45 [1–1] | 78 [1–2] | 190 [2–15] | 196 [3–15] |

The later swap improved or tied frozen movement in all 90
variants. All arms returned with no right-censored branch from
these two cuts. The raw outcomes retain the separate return-only
counts, accepted return times, inactivity endpoints and exposure.
Earlier inputs still matter. On m4761 seat A, overriding
W-tap's first release lost in all 45 variants from that initial
cut. At a later m4761 cut, the same type of intervention added
active cycles in all 45. Each group shares one acquisition and
does not constitute 45 independent bouts.

Across eight matched native 20 ms regulation bouts, the middle
90% of individual server drain intervals in the W-tap mirrors
was 44.80–55.13 ms, versus 44.81–55.07 ms in the frozen movement
matches. Across five consecutive client head intervals the
middle 90% spread was about 20 ms in both arms. The audit linked
all 1,552 scored hits to their local contact and server enqueue.
The middle 90% from contact consumption to enqueue was
10.33–11.59 ms in the mirrors and 10.34–11.32 ms in the frozen
matches. The own-uplink stress margin in the model extends beyond
that middle range. These distributions establish variation at
the tested scales. They do not calibrate the joint probability
of a particular post-decision timing change.

#### Does the later cue transfer?

The cue nominated from m4511 used only the acting client's
observations and the two available key choices. The first
eligible input samples a grounded, sprinting self with a
target-hurt cue age of 275–380 ms, a rendered target at least
0.70 blocks higher and a nearest hitbox distance of 2.50–2.98
blocks, while W-tap would use W+Ctrl and the frozen network W.
It swaps just that input. The thresholds were frozen before
testing 35 historically exposed calendars that supplied no
examples to this cue. K3 acquisition and both stock branches
were matched. Each seat took the recorded timing and two
defender-phase variants when it reached the landmark.

| Independent recorded clocks at nominal timing | K3 common starts | W-tap active / return-only cycles | W-tap with cue | Frozen movement | Frozen movement with cue |
|---|---:|---:|---:|---:|---:|
| 0 ms | 19 | 22 / 23 | 22 / 23 | 30 / 30 | 21 / 21 |
| 20 ms | 17 | 18 / 18 | 20 / 20 | 17 / 19 | 17 / 19 |
| 80 ms | 37 | 30 / 31 | 30 / 31 | 41 / 41 | 41 / 41 |

At 20 ms, the cue occurred twice on the frozen branch at
nominal timing and four times including the phase variants.
None changed the hit count. The two additional active hits
on the W-tap branch came from one clock and did not repeat
under either phase variant. The frozen arm fell from 116 to
68 active cycles across the 57 zero-latency phase samples.
At K5 the 20 common starts included one W-tap gain of two at
20 ms and no changes to the frozen arm on the recorded timing.
The defender-phase variants produced one frozen gain of six
at 0 ms and changes of +2, −1 and −6 at 20 ms. There were no
K10 starts in the transfer panel. The exposure,
censoring and distribution summary retains the paired figures
for every latency and landmark.

The 20 ms native regulation archives showed no cue match in
the first second of any of the six frozen K3 episodes before
return. These are observations of the existing policy. No native
controller used the proposed cue. Waiting improved the selected
development state, while the simple rule from that state lacked
the independent performance needed for a live change.

[Frozen splits and timing inputs](../../build/boxing-double-search/same-view-timing/protocol.json) ·
[Matched waiting test](../../build/boxing-double-search/same-view-timing/wait-two-inputs-local-view.jsonl) ·
[Native timing audit](../../build/boxing-double-search/same-view-timing/native-timing-spread.json) ·
[Cue rules and transfer split](../../build/boxing-double-search/same-view-timing/cue-frozen-split.json) ·
[Transfer exposure and distribution](../../build/boxing-double-search/same-view-timing/cue-transfer-summary.json)

### Why W-tap scored more upgrades at 20 ms

The native audit checked every recorded attack in the 24
first-to-100 bouts from the 0, 20 and 80 ms comparison cohorts.
Every accepted upgrade was a falling critical that raised
the target's current window from 8 to 12 nominal damage. The
referee awarded one score point. The 12 in the replay's attack
field is the new nominal damage, with four additional damage
before armor and other modifiers. Every scored upgrade was
linked to the local click. Reported descent reconstructed from
received movement packets agreed with server ground and fall
distance at all 18,315 attack samples. We also sampled the
attacker's own fresh raycast during the same time window.

| 20 ms native windows opened by a normal hit | W-tap mirror, nominated seat | Frozen movement | Its W-tap opponent |
|---|---:|---:|---:|
| Windows observed through immunity expiry | 328 | 309 | 303 |
| At least one falling movement report | 323 | 286 | 295 |
| Falling with a sampled legal ray and no miss cooldown | 35 | 53 | 47 |
| Scored upgrades in fully observed windows | 27 | 29 | 41 |
| Scored upgrades including end-of-bout windows | 27 | 29 | 42 |
| Median server tick of upgrade after preceding normal hit | 6 | 8 | 7 |

The window closed at the ninth server tick. The candidate
had more windows where a falling player was seen in range,
yet more of its attacks reached the server after immunity.
Of 24 candidate windows with a ready falling ray but no
upgrade, 16 had an actual falling click accepted as fresh.
Fifteen arrived in the first fresh tick. In ten cases the
client consumed that click after the last immune server
drain had already begun. Earlier range or a differently
timed click would be needed to change those cases. Range
samples alone cannot establish that the physical cap and
packet timing allowed an earlier hit. Six of the 24
windows had no recorded falling click before expiry.

W-tap scored 35 of 42 upgrades at ticks six or seven and had
server sprint on 41. Frozen movement scored 18 of 29 on the
last immune tick and had server sprint on 15. In both arms
most normal-hit windows had a fall, so the more specific
20 ms difference is when the falling contact arrives.
The audit gives the observed causal sequence of each
scored hit. It does not estimate a joint probability for
an unattempted earlier click.

#### Which defensive responses change that window?

The simulator restored each complete post-hit state after an
opposing fresh hit in the first 20 seconds with both players
active. Exact initial and forked stock hit lists agreed. The
counterfactual input was set only after the fighter received
its own hurt cue. It retained click admission and aim.
The choices were two ticks of coasting, two ticks of walking
backward, or one ground-eligible jump at each of 0, 100, 200
and 250 ms after that cue. All later keys returned to the
stock policy. W-tap from the same cut retained its primed
state. Score differences below sum over overlapping 1.4
second horizons. They are window diagnostics, separate from
full-match wins.

| Frozen-branch incoming states | W-tap from the same cuts | Frozen movement | Immediate coast | Immediate backward | Immediate jump |
|---|---:|---:|---:|---:|---:|
| 512 cuts on 12 development clocks, accepted margin sum | +77 | +89 | +20 | +49 | +101 |
| 351 cuts on eight other exposed clocks, accepted margin sum | +73 | +48 | +40 | +53 | +97 |

Immediate backward input removed 47 of 86 upgrades before
the opponent's next fresh hit in development and 20 of 53
in the second cohort. It lost 47 and four net fresh hits
respectively across the full horizons. The immediate
jump removed zero first-window upgrades on either cohort
and added 29 and 12 incoming upgrades, respectively.
Its accepted-score gains of 12 and 49 came with additional
fresh hits. Immediate jump execution was verified in 442
of 512 and 306 of 351 frozen states. A delayed 100 ms jump
never executed in development because the actor was
already off the ground. The measured positive jump effect
is therefore a separate scoring mechanic from preventing
the upgrade.

[Native windows and early/late clicks](../../build/boxing-double-search/upgrade-mechanism/native-20-last-drain.json) ·
[Frozen window protocol](../../build/boxing-double-search/upgrade-mechanism/model-protocol.json) ·
[Development branches](../../build/boxing-double-search/upgrade-mechanism/model-development.jsonl) ·
[Other clock branches](../../build/boxing-double-search/upgrade-mechanism/model-transfer.jsonl)

### Standalone impact jump at different latencies

A separate first-to-100 simulation used the existing impact
jump. It requests one ordinary jump at the next input head
after received horizontal velocity if grounded. Both players
otherwise use plain W-tap with a 20 CPS cap. This velocity
trigger matches the existing native jump policy and is
distinct from the hurt-cue intervention above. Twenty
independent archived host calendars supplied 40 pairs per
latency across seats. None supplied examples to a learned
jump policy. The two panels were model evaluations on
previously exposed clocks.

| Added RTT | W-tap mirror wins | Impact jump wins | W-tap median margin [range] | Impact jump median margin [range] |
|---|---:|---:|---:|---:|
| 0 ms | 20/40 | 36/40 | 0 [−13, +13] | 7 [−6, +23] |
| 20 ms | 20/40 | 35/40 | 0 [−15, +15] | 6 [−6, +18] |
| 80 ms | 20/40 | 5/40 | 0 [−15, +15] | −7 [−17, +7] |

At 20 ms the 260-point aggregate model margin included
+124 fresh hits and +136 upgrades. At 80 ms it reversed
to −268, including −72 fresh and −196 upgrades. Earlier
native 12-second diagnostics used the same jump and both
seats. Their mean advantage over W-tap at 20 CPS was
+1.75 at 0 ms and −1.75 at 80 ms. The independent long
bouts at 20 ms are reported below.

On the eight-clock panel, the extra RTT sweep won 15/16
at 10 ms, 15/16 at 30 ms, 14/16 at 40 ms and 14/16
at 50 ms. It fell to 7/16 at 60 ms and 5/16 at 70 ms.
All mirror baselines won 8/16. The respective median
jump margins were +8, +9.5, +6.5, +5, −1 and −2.
This brackets the flip on these host schedules. It does
not establish a universal cutoff for live play.

The explicit established-combo check restored 41 identical
landmarks across RTTs. Enabling the velocity trigger after
the landmark changed none of the paired outcomes. At K3
both arms had 37 active and 38 return-only cycles in 32
starts over 28.84 and 28.94 seconds of exposure. There were
31 returns and one right-censoring in return-only scoring.
There were 30 returns, one inactivity end and one censoring
under activity scoring. The active counts were zero in
12 starts, one in 14, two in two, three in two, four in one
and nine in one. At K5, eight starts yielded 13 active and
14 return-only cycles in 9.41 and 9.51 seconds. The sole K10
start yielded two and three cycles in 2.00 and 2.10 seconds.
All K5 and K10 returns were observed. The rule can act
after a return's velocity packet, after the first-return
maintenance comparison has ended.

[Model regulation panel A](../../build/boxing-double-search/upgrade-mechanism/jump-regulation-native-donors.jsonl) ·
[Model regulation panel B](../../build/boxing-double-search/upgrade-mechanism/jump-regulation.jsonl) ·
[RTT sensitivity](../../build/boxing-double-search/upgrade-mechanism/jump-extra-rtt.jsonl) ·
[Model maintenance comparison](../../build/boxing-double-search/upgrade-mechanism/jump-maintenance.jsonl) ·
[Native 20 ms plan](../../build/boxing-double-search/upgrade-mechanism/confirmation-boxing-jump20-rtt20.plan.json)

### Why the impact jump loses at higher RTT

The jump has two physical effects. It replaces the normal upward velocity
with `0.42` blocks per tick and adds `0.2` blocks per tick forward when
sprinting. A build-only simulator experiment suppressed each effect in turn
in matched first-to-100s. All 96 runs with both effects suppressed exactly
reproduced their W-tap controls. Each latency pair used the same eight host
calendars, both seats, 3-block reach and a 20 CPS cap. The separated effects
require simulator intervention. An ordinary jump applies both mechanics.

| Added RTT own / opponent | W-tap | Normal jump | Lift only | Sprint boost only |
|---|---:|---:|---:|---:|
| 20 / 20 ms | 8/16, 0 [−15, +15] | 14/16, +5 [−3, +16] | 10/16, +2.5 [−17, +17] | 16/16, +11 [+2, +21] |
| 50 / 50 ms | 8/16, 0 [−9, +9] | 14/16, +5 [−6, +18] | 9/16, +1.5 [−13, +17] | 14/16, +4 [−3, +15] |
| 60 / 60 ms | 8/16, 0 [−13, +13] | 7/16, −1 [−10, +7] | 7/16, −2 [−10, +4] | 12/16, +3.5 [−8, +10] |
| 80 / 80 ms | 8/16, 0 [−9, +9] | 2/16, −7 [−17, +7] | 3/16, −6 [−17, +5] | 10/16, +2 [−8, +14] |
| 20 / 80 ms | 0/16, −10 [−19, −1] | 6/16, −2.5 [−11, +8] | 1/16, −6.5 [−16, +1] | 1/16, −9 [−18, +6] |
| 80 / 20 ms | 16/16, +10 [+1, +19] | 11/16, +5 [−7, +16] | 9/16, +1 [−7, +8] | 16/16, +15 [+2, +28] |

Cells show wins and median accepted-hit margin [range]. At symmetric 80 ms,
the full jump lost 109 accepted hits summed across margins, including 38
fresh hits and 71 upgrades. The forward boost alone gained 44, including
34 fresh hits and 10 upgrades. The asymmetric results keep the latency
effect relative to the W-tap control for that pair.

The archived 12-second native recordings showed the expected movement.
When the bot received positive lift while grounded and landed before a new
velocity packet, W-tap landed after 10 movement updates and the jump arm
landed after 12. At 0 ms this covered 45 of 48 W-tap events and 34 of 41
jumps. At 80 ms it covered 23 of 41 W-tap events and 15 of 39 jumps. Later
velocity packets or the end of recording censored the remaining airtimes.
After accounting for attack contact drag, the sprint boost appeared on only
11 of 41 reset jumps at 0 ms and 19 of 39 at 80 ms. The four 80 ms W-tap
controls summed to −1 accepted hit, and the four reset bouts to −8. The
reset bouts lost three further fresh hits and four further upgrades versus
the control. Native runs share settings and seats with independent clocks.

The 1.4-second model forks distinguish one jump from repeated use. Starting
from plain W-tap's complete state just after an incoming fresh hit, adding
only one jump improved the total margin from +65 to +80 across 315 cuts
at 20 ms and from +70 to +80 across 367 cuts at 80 ms. In a separate fork,
each shared state came from the corresponding repeated-jump bout. At the
cut, one branch reverted to W-tap, one kept jumping and one skipped just
the next attempt before resuming the same rule.

| Added RTT | Paired cuts | W-tap from cut | Keep jumping | Skip one then resume |
|---|---:|---:|---:|---:|
| 20 ms | 322 | +98 | +118 | +100 |
| 80 ms | 362 | +114 | +70 | +96 |

Values sum accepted-hit margins over overlapping windows. At 80 ms, skipping
one gained 13 fresh hits and 13 upgrades versus keeping the jumps. The model
attributes part of the loss to repeatedly taking the jump from states that
the policy itself helped create. The windows share trajectories and are
correlated.

An ordinary-input test allowed the jump only when W-tap was about to send
forward with sprint. The frozen rule was evaluated on 28 previously seen
calendars that supplied no examples to it, separate from the eight used for
the diagnosis. Settings remained identical across arms within each pair.

| Added RTT | W-tap | Normal jump | Jump with forward and sprint keys only |
|---|---:|---:|---:|
| 0 ms | 28/56, 0 [−18, +18] | 48/56, +7 [−4, +22] | 48/56, +7 [−7, +25] |
| 20 ms | 28/56, 0 [−12, +12] | 50/56, +7 [−6, +26] | 48/56, +5.5 [−8, +22] |
| 50 ms | 28/56, 0 [−18, +18] | 51/56, +6 [−15, +16] | 41/56, +3 [−12, +29] |
| 60 ms | 28/56, 0 [−21, +21] | 35/56, +2 [−17, +13] | 26/56, −1.5 [−16, +13] |
| 80 ms | 28/56, 0 [−20, +20] | 10/56, −8 [−19, +7] | 7/56, −7 [−22, +6] |

The sprint-key rule did not repair the loss at 80 ms. Its total margin
fell from −375 to −423 even as its median improved. The full jump's two
panels disagree at 60 ms. The 130 shared maintenance landmarks on this
panel tied W-tap exactly, as did 27 more on the development clocks. At K3
the 99 starts on the 28-calendar panel completed 109 active and 114
return-only cycles over 91.65 and 92.27 seconds. The activity measure ended
89 at accepted return, seven at inactivity and three at censoring. The
return-only measure observed 96 returns and three censorings. At K5 the 30
starts completed 33 and 34 cycles over 26.09 and 26.19 seconds, with 28
and 29 returns, one inactivity and one censoring in each measure. At K10
the only start completed two and three cycles over 2.00 and 2.10 seconds,
ending at inactivity and accepted return respectively. The filtered rule
stays a simulator diagnostic.

[Component protocol and scores](../../build/boxing-double-search/impact-components/protocol.json) ·
[Regulation results](../../build/boxing-double-search/impact-components/regulation.jsonl) ·
[Native movement audit](../../build/boxing-double-search/impact-components/native-short-impacts.json) ·
[Native score audit](../../build/boxing-double-search/impact-components/native-short-criticals.json) ·
[One jump forks](../../build/boxing-double-search/impact-components/one-impact-windows.jsonl) ·
[Repeated jump forks](../../build/boxing-double-search/impact-components/repeated-impact-windows.jsonl) ·
[Frozen rule](../../build/boxing-double-search/impact-components/selective-frozen.json) ·
[Transfer and maintenance results](../../build/boxing-double-search/impact-components/selective-transfer.jsonl)

### Native first-to-100 impact jump at 20 ms

The frozen native cohort completed all eight bouts, with two runs in each
seat per arm. Both fighters had 20 ms added RTT, 3-block reach, a 20 CPS cap
and the same mouse aim settings and provisional MMC profile. The candidate
added only the velocity-triggered ordinary jump. The mirror design measures
the run-to-run spread from ordinary W-tap playing itself. These were
independent native runs with separate clocks.

| Accepted-hit benchmark | Plain W-tap mirror | W-tap plus impact jump |
|---|---:|---:|
| Wins | 3/4 | 3/4 |
| Median margin [range] | +7 [−1, +11] | +4 [−3, +6] |
| Seat A margins | −1, +11 | +2, −3 |
| Seat B margins | +4, +10 | +6, +6 |
| Total fresh-hit margin | +16 | −4 |
| Total upgrade margin | +8 | +15 |
| Doubles for / against | 30 / 19 | 28 / 30 |
| Post-trade doubles for / against | 2 / 2 | 1 / 2 |
| Mean measured CPS for / against | 16.70 / 16.66 | 16.64 / 16.61 |

The candidate's +7 upgrade-margin gain was outweighed by a 20-point drop
in fresh-hit margin versus the control. Client traces verified 253 actual
jumps, with 110 sprint boosts. All 173 uncensored jump landings took 12
movement updates after the received velocity. All 300 comparable W-tap
landings took 10. The other receipts were interrupted by more velocity or
the recording boundary.

The simulator still favored the jump when run on the four new native
mirror calendars in both seats. The same capped first-to-100 setup gave
the jump 8/8, with median +7.5 [+3, +16], versus W-tap's 4/8 and 0
[−9, +9]. The simulator gained +37 in fresh-hit margin and +31 in
upgrade margin. Host opportunity timing from this cohort preserved the
discrepancy. The model still supplies the network, movement and combat
trajectory. The four native candidate bouts are a small sample and fail
the test for promoting this jump at 20 ms.

[Native plan and results](../../build/boxing-double-search/upgrade-mechanism/confirmation-boxing-jump20-rtt20.json) ·
[Native verified scores](../../build/boxing-double-search/upgrade-mechanism/confirmation-boxing-jump20-rtt20.scores.json) ·
[Native impact trace audit](../../build/boxing-double-search/upgrade-mechanism/confirmation-boxing-jump20-rtt20.impacts.json) ·
[Model on the new mirror clocks](../../build/boxing-double-search/upgrade-mechanism/jump20-native-mirror-model.jsonl)

### Native impact-jump repeat across three latencies

The follow-up completed 24 independent first-to-100 matches on the main
lab. The frozen schedule used four bouts per arm at each latency, two
on each seat, with 3-block reach, a 20 CPS cap, identical mouse settings
and the same MMC preset. The jump arm changed only the immediate
velocity-triggered jump. We checked all request IDs, final scores and
physical click traces against the frozen schedule. Both arms have their
own native clocks and acquisition states.

| Added RTT | Plain W-tap wins and median margin [range] | W-tap plus impact jump wins and median margin [range] |
|---|---|---|
| 0 ms | 3/4, +2 [−4, +6] | 3/4, +5.5 [−1, +8] |
| 20 ms | 2/4, −1 [−12, +9] | 4/4, +5.5 [+4, +9] |
| 80 ms | 2/4, +0.5 [−13, +8] | 0/4, −11.5 [−14, −3] |

Individual scores list the nominated seat first, with the two A scores
followed by the two B scores.

| Added RTT | Plain W-tap scores | Impact jump scores |
|---|---|---|
| 0 ms | 100–99, 100–94, 96–100, 100–97 | 100–96, 100–92, 100–93, 99–100 |
| 20 ms | 100–91, 88–100, 100–93, 91–100 | 100–94, 100–96, 100–95, 100–91 |
| 80 ms | 100–92, 100–96, 97–100, 87–100 | 90–100, 97–100, 87–100, 86–100 |

Measured mean click rates for the jump fighter and its opponent were
16.59 / 16.73, 16.63 / 16.67, and 16.74 / 16.79 CPS at 0, 20 and
80 ms. The mirrors averaged 16.74 / 16.77, 16.72 / 16.65, and
16.74 / 16.67 CPS. At 20 ms the jump fighter gained 24 accepted hits
of margin in aggregate over its opponents, of which eight were fresh
and 16 were upgrades. The mirrors had −1 fresh and −4 upgrades.
At 80 ms the jump fighter had −13 fresh and −27 upgrades. Its raw
after-trade double margins were +2, +4 and +1 across the three RTTs,
even though the whole-match score fell at 80 ms. The prior 20 ms
cohort matched wins and had a lower jump median than the mirrors.
This independent repeat strengthens the 80 ms failure and gives
mixed evidence across the two 20 ms cohorts. These matches measure
the standalone jump's regulation effect. A targeted combo-exit
decision and the established-combo gate remain separate.

The simulator replayed each of the 12 new mirror clocks in both seats
using the recorded initial tracker state. Eleven clocks supported the
movement-wire dispatch reconstruction. On m5497, the movement-wire order
could not be paired one to one with the native steps, so that donor stays
in the tick-end reference only.

| Added RTT | Plain W-tap with wire dispatch | Impact jump with wire dispatch |
|---|---|---|
| 0 ms | 4/8 wins, median 0 [−26, +26] | 8/8 wins, median +6 [+1, +13] |
| 20 ms | 3/6 wins, median 0 [−8, +8] | 4/6 wins, median +5 [−2, +10] |
| 80 ms | 4/8 wins, median 0 [−10, +10] | 0/8 wins, median −5 [−14, −3] |

The tick-end variant also gave the jump zero wins at 80 ms, with
a median −7 against the mirror's 0. Both modeling modes predict the
high-latency loss on these clocks. The actual native median there was
−11.5. At zero added RTT, the model's perfect wire-dispatch record
exceeds the native sample's three wins in four.

[Frozen plan](../../build/boxing-double-search/timing-transfer/confirmation-boxing-impact-repeat.plan.json) ·
[Verified native scores and click rates](../../build/boxing-double-search/timing-transfer/confirmation-boxing-impact-repeat.summary.json) ·
[Models on the new mirror clocks](../../build/boxing-double-search/timing-transfer/repeat-model/summary.json)

### Where the native and modeled jump scores diverge

All eight native bouts used a nine-server-tick fresh-damage window. Starting
from each player's first fresh hit, the audit compared the drain of their
next fresh hit with the first eligible drain. It reconciled the full
scoreboards and linked local consumptions to the server attacks. A repeat
counts as unanswered here if the other side lands no accepted hit, including
upgrades, from the prior fresh hit's drain through the repeat's drain. This
includes two-hit sequences and is separate from the K3/K5/K10 gate.

| Native score margin summed across four bouts per arm | Plain W-tap mirror | W-tap plus impact jump |
|---|---:|---:|
| Fresh hits on the first eligible drain | +8 | −6 |
| Fresh hits on the next drain | +7 | −2 |
| Fresh hits on later drains | +1 | +4 |
| All fresh hits | +16 | −4 |
| Unanswered fresh repeats on the first two drains | +9 | 0 |

The 20-point loss in fresh-hit margin was concentrated in the first two
eligible drains. The jump arm scored 69 early hits versus its opponent's
77. In the mirror cohort the designated W-tap controls scored 44 versus 29.
On the first eligible drain alone, 194 of the jump arm's 326 covered
windows had at least one local consumption during the preceding server-tick
interval and all such consumptions missed or were suppressed, compared with
190 of 332 for its opposing W-tappers. That interval does not establish that
a missed click could have reached the server before the drain.

The historical timing export repeats the first 160 ticks and treats frame
and input-head times as click-poll times. The new diagnostic also exported
the recorded cap poll timestamps and the whole common span of each bout.
Each clock repeats at its own exported endpoint if necessary. Both modeled
arms shared the same opportunities and generated their own admissions,
combat and movement. A further comparison used measured movement-packet
wire delays as time-indexed proxies on the four routes. It did not import
recorded attack times, checks of hit success, packet bodies, or later
positions. These eight already exposed host recordings cover both native
arms. Both simulator seats used every donor at matched 20 CPS and 20 ms RTT.

| Model timing | Plain W-tap mirror | W-tap plus impact jump |
|---|---:|---:|
| First 160 ticks, frame/head poll proxies | 8/16, 0 [−11, +11] | 14/16, +6 [−7, +16] |
| First 160 ticks, recorded polls | 8/16, 0 [−11, +11] | 14/16, +5.5 [−1, +21] |
| Full span, frame/head poll proxies | 8/16, 0 [−9, +9] | 14/16, +8 [−4, +15] |
| Full span, recorded polls | 8/16, 0 [−12, +12] | 16/16, +8 [+1, +20] |
| Full span, recorded polls and variable wire delay | 8/16, 0 [−9, +9] | 16/16, +9.5 [+1, +16] |

Cells show wins and median accepted margin [range]. The observed per-side
median C02 wire time was 10.42–10.67 ms, with 95th percentiles of
12.09–14.64 ms. Replacing the historical constant 10.35 ms per leg with
movement-derived wire timings preserved the candidate's early scoring
advantage. In the full-span model with variable wire timing, the jump's
fresh-hit margin was +70, including +60 in the first two drains and +38
from unanswered repeats in those drains. The W-tap mirror was symmetric
across seats. The native jump's first-two-drain margin was −8, and its
unanswered repeats there were balanced. The discrepancy to investigate is
whether the simulated opponents really lose return reach after these jumps.
Wire samples are a transport proxy. They do not reproduce main-thread
handler timing, aim sampling or position paths in a changed fight.

[Native window audit](boxing_fresh_windows.py) ·
[Native window data](../../build/boxing-double-search/jump-timing/native-fresh-windows.jsonl) ·
[Timing experiment](BoxingJumpTimingAudit.java) ·
[Frozen timing comparisons](../../build/boxing-double-search/jump-timing/protocol.json) ·
[Recorded wire latencies](../../build/boxing-double-search/jump-timing/wire-latency.jsonl) ·
[Variable-wire results](../../build/boxing-double-search/jump-timing/full-explicit-wire.jsonl)

### Return geometry after a trade

The follow-up audit paired opposing fresh hits up to three server ticks
apart. Each fresh hit belongs to at most one pair. It scored each player's
own first two eligible drains after the trade, so a player that hits first
also becomes eligible to score again first. The model used full host spans,
recorded click polls, and the movement-derived wire delays. Both modeled
arms used the same eight calendars in both seats. The native arms remain
four independently run bouts apiece.

| Post-trade result | Plain W-tap mirror | W-tap plus impact jump |
|---|---:|---:|
| Native, all paired trades | 9 / 2 of 344 | 11 / 16 of 315 |
| Native, designated fighter hit first | 2 / 2 of 95 | 1 / 10 of 92 |
| Native, designated fighter hit first, both victims grounded with full sprint knockback | 2 / 1 of 73 | 0 / 6 of 56 |
| Model, all paired trades | 30 / 30 of 1,342 | 52 / 14 of 1,254 |
| Model, designated fighter hit first | 9 / 20 of 356 | 19 / 8 of 314 |
| Model, designated fighter hit first, both victims grounded with full sprint knockback | 9 / 15 of 262 | 17 / 5 of 216 |

Each cell shows trades where only the designated fighter landed an early
fresh follow-up, then trades where only its opponent did. The grounded
subset requires grounded victims, positive lift and horizontal impulse
above 0.7 blocks per tick for both initial hits. The jump model's excess
early advantage survives that restriction. The effect is largest when the
jump fighter was first to score in the original trade. These are
conditional descriptions of independently generated trades, rather than
estimates of switching input from an identical post-trade state.

Every paired-trade window with a processed attack in those two drains
scored a fresh hit there, in both native and modeled arms. The math for
crosshair selection also agreed on all 21,242 eligible native clicks when
given the recorded positions and aim, including 6,170 target selections.
The agreement covers the recorded geometry. It leaves the model's generated
geometry and the timing of the click relative to it as separate variables.

The packet trace linked 6,133 interior native C02s to processed server
ticks. The next drain after actual wire receipt matched 6,127. A fixed
10.35 ms sender-to-receipt assumption matched 5,994. All 1,372 covered own
S12 packets could affect the same next movement step predicted from their
actual wire receipt and recorded client drain opportunities. The fixed
10.35 ms assumption moved four across a movement boundary. These are
checks on factual sends and receipt times. Model-generated carryover
inputs and the spacing at their possible click times still need validation.

The native clients also logged 1,373 W-tap releases for 1,373 target hurt
statuses. About 63% released for two movement ticks. The full-span model
also released for two ticks in about 61–62% of its recorded reset runs.
The sign reversal therefore survives matching the opening impulse class,
and the factual trace gives no indication of a systematic extra task tick
or a different hitbox calculation in the native client.

[Native click and reach audit](boxing_return_geometry.py) ·
[Native packet-stage audit](boxing_task_stage_audit.py) ·
[Native trade windows](../../build/boxing-double-search/return-geometry/native.jsonl) ·
[Model trade windows](../../build/boxing-double-search/return-geometry/model-windows.jsonl) ·
[Window counts and provenance](../../build/boxing-double-search/return-geometry/summary.json) ·
[Native packet-stage counts](../../build/boxing-double-search/return-geometry/native-task-stages.jsonl)

### Recorded inputs reproduce the return failures

The strict replay generated both clients and the server using the recorded
inputs, aim, and packet delivery schedule. The recorded positions and packet
payloads served as assertions after initialization. The completed prefixes
covered seven native bouts, including all six grounded sprint trades in
which the jump bot landed first and only W-tap scored an early follow-up.
Across the longest replay per bout, all 9,800 movement steps, 9,800 opponent
interpolations, and 8,153 recorded click comparisons matched exactly. Some
pre-hit server snapshots differed by 0.5 in the stored initial last-damage
value, with the same positions and accepted hits. The eighth replay, m5044,
stopped on an unmodeled initial movement report before a full recorded input
step. It was outside the six jump witnesses.

In five of the six jump witnesses, the jump bot consumed a click whose
crosshair missed near its first eligible drains. In one, m5038 at server
ticks 102022–102023, both sides had clear rays. The jump bot's first useful
contact was processed one drain before its fresh window, while its next
contact was processed two drains after the window opened. The W-tap
opponent's first contact landed on its opening drain. In m5042 at ticks
104326–104328, even a 3.008-block minimum hitbox distance exceeded the
3-block reach. The strict replay retained both failures. These comparisons
cover recorded trajectories, with the conditioning on recorded input and
packet delivery separate from generating new inputs or packets.

Generated input and position paths were then compared within the first 40
client ticks. Recomputing the 20 CPS gate with the recorded completion
timestamps reproduced all 106,139 poll decisions exactly. Pacing it from
the poll timestamps instead changed 335 decisions across the full bouts.
With the recorded tracker seed, 15 of 16 client prefixes matched native
click counts on all 40 input ticks. That left early replica positioning as
a direct source of divergence.

Two timing differences were isolated in the autonomous model.

- The generic tracker counter started at 29. The recorded first complete
  server tick started at 50 for three donors and 49 for five. In m5037,
  using counter 50 reproduced which server tick sent the second opponent
  position update. It removed a 0.118-block early difference in the
  predicted replica while own movement and consumed click counts matched.
- In m5042 the first movement report went on the wire at 7.324 ms and
  reached the server at 21.999 ms relative to the clock origin, just before
  the 22.084 ms server drain. Using the client tick end as send time delayed
  it to 22.322 ms in the model. It missed that drain and produced a
  0.052-block opponent position error at the next common click. Using the
  recorded movement send timestamp restored that report to the first drain
  and removed the 0.052-block error. One side still diverged later in the
  same bout.

| Matched 20 CPS and 20 ms RTT | Plain W-tap mirror | W-tap plus impact jump |
|---|---:|---:|
| Eight model calendars, generic tracker start | 8/16, 0 [−9, +9] | 16/16, +9.5 [+1, +16] |
| Same eight calendars, recorded tracker start | 8/16, 0 [−8, +8] | 14/16, +8 [−5, +15] |
| Six-calendar subset, recorded tracker start and tick-end movement send | 6/12, 0 [−8, +8] | 11/12, +8.5 [−5, +15] |
| Same six calendars, recorded tracker start and movement send | 6/12, 0 [−9, +9] | 9/12, +6 [−5, +20] |

Cells give wins and median accepted-hit margin [range]. The six-calendar
comparison excludes two captures where the wire send events did not fit
one per recorded movement interval throughout the export. Within the same
six calendars, using the recorded movement sends reduced the jump's early
fresh-hit margin from +34 to +26 and its overall accepted margin from
+99 to +76. Host stages and both fighters' input caps stayed the same.
Sprint commands still share the modeled movement dispatch opportunity.
The timing changes reduce the overprediction while the adjusted model
continues to favor a jump that failed the native comparison.

[Strict replay reports](../../build/boxing-double-search/return-replay/coupled-16s.json) ·
[Longest jump replay](../../build/boxing-double-search/return-replay/m5041-coupled.json) ·
[Replay witness ledger](../../build/boxing-double-search/return-replay/native-return-steps.jsonl) ·
[Native movement send example](../../build/boxing-double-search/return-replay/m5042-native-pipeline.json) ·
[Corrected movement dispatch trace](../../build/boxing-double-search/return-replay/m5042-wire-flush-pipeline.jsonl) ·
[Timing protocol and verification](../../build/boxing-double-search/return-replay/summary.json)

## Frozen movement on the new wire-dispatch clocks

The unchanged boxing-spacing20 weights played both seats on the 11
wire-qualified native W-tap mirror calendars collected for the jump
repeat. These clocks came after the movement weights were fixed.
They were previously exposed in the jump and timing diagnostics.
The model used the measured tracker start and movement-send schedule.
Both arms used the same 20 CPS cap, 3-block reach, aim model and
first-to-100 rule.

| Added RTT | Plain W-tap wins / median margin [range] | Frozen movement wins / median margin [range] |
|---|---|---|
| 0 ms | 4/8 · 0 [−26, +26] | 5/8 · +1 [−17, +21] |
| 20 ms | 3/6 · 0 [−8, +8] | 3/6 · −1.5 [−8, +3] |
| 80 ms | 4/8 · 0 [−10, +10] | 7/8 · +3.5 [−3, +13] |

At 20 ms the frozen movement gained 15 fresh hits of total margin
and lost 26 upgrades, for an accepted margin of −11. At 80 ms it
gained 30 fresh hits and ten upgrades in total. The file below keeps
every score and seat.

The separate establishment drill gave both arms identical complete
model checkpoints after three unanswered fresh hits. It retained
the queued packets, actual applied-input history, and the opponent's
two-second delayed engagement. All seven K3 pairs were ties with
zero further fresh cycles. Every pair took a fresh return.

| Added RTT | Shared K3 starts | Plain W-tap cycles / total exposure | Frozen movement cycles / total exposure |
|---|---:|---|---|
| 0 ms | 2 | 0 / 933.45 ms | 0 / 933.45 ms |
| 20 ms | 0 | 0 / no entries | 0 / no entries |
| 80 ms | 5 | 0 / 313.49 ms | 0 / 313.49 ms |

Return-only and activity-qualified results agreed. There were no
right-censored K3s or K5/K10 entries. These sparse continuations leave
the maintenance gate open. We froze an independent native comparison
with 24 regulation matches and 24 separate drills at the same three
latencies, with both seats and ordinary W-tap comparators. The existing
movement policies are unchanged.

[Frozen model roster](../../build/boxing-double-search/timing-transfer/incumbent-model/frozen.json) ·
[Model scores and individual maintenance outcomes](../../build/boxing-double-search/timing-transfer/incumbent-model/summary.json) ·
[Independent native schedule](../../build/boxing-double-search/timing-transfer/confirmation-boxing-spacing20-repeat.plan.json)

### Completed independent native repeat

All 48 scheduled bouts finished. Each latency had two
regulation bouts per seat and arm, plus two independent
maintenance drills per seat and arm. The same frozen weights,
MMC preset, reach and click cap were used throughout.

| Added RTT | Plain W-tap wins and median [range] | Frozen movement wins and median [range] | Frozen first-to-100 scores, bot first |
|---|---|---|---|
| 0 ms | 3/4, +1.5 [−7, +20] | 3/4, +7 [−9, +34] | 100–66, 100–95, 91–100, 100–91 |
| 20 ms | 3/4, +2 [−14, +11] | 2/4, +5 [−9, +15] | 100–85, 100–88, 98–100, 91–100 |
| 80 ms | 2/4, −1 [−10, +3] | 4/4, +4.5 [+4, +14] | 100–96, 100–96, 100–95, 100–86 |
| All | 8/12, +1.5 [−14, +20] | 9/12, +5 [−9, +34] | Individual results above |

In regulation the candidate's mean physical click rate was
16.638 CPS against 16.644 for W-tap at the same 20 CPS cap.
The candidate's total accepted margin was +82, from +96
fresh hits and −14 upgrades. The designated W-tap mirror
seat gained +11 fresh hits and lost three upgrades. The
candidate's post-trade doubles were 22 for and two against,
versus 11 for and nine against from the mirror seat. At 20 ms
the candidate gained 33 fresh hits and lost 17 upgrades in
total, with two clear losses in the four-bout group.

| Added RTT | Plain W-tap K3 starts / further cycles / exposure | Frozen movement K3 starts / further cycles / exposure |
|---|---|---|
| 0 ms | 1 / 0 / 50 ms | 0 / 0 / no entries |
| 20 ms | 1 / 0 / 105 ms | 1 / 10 / 5,550 ms |
| 80 ms | 2 / 1 / 904 ms | 2 / 1 / 898 ms |
| All | 4 / 1 / 1,059 ms | 3 / 11 / 6,448 ms |

All seven first K3 episodes ended on a fresh return within
the activity limits, with zero right-censored episodes in
either definition. Only m5781 reached K5 or K10. It added
eight cycles over 4,549 ms after K5 and three over 2,000 ms
after K10. Native runs used a common acquisition procedure
with independent state and packet timing. For example, the
sole 20 ms candidate entry started at a 3.483-block server
gap versus 2.948 for the 20 ms W-tap entry. One run accounts
for ten of the candidate's 11 cycles. The fixed movement is
the current regulation benchmark and the common-state
maintenance gate remains open.

[Completed schedule and results](../../build/boxing-double-search/timing-transfer/confirmation-boxing-spacing20-repeat.json) ·
[Individual scores, CPS and maintained chains](../../build/boxing-double-search/timing-transfer/confirmation-boxing-spacing20-repeat.summary.json)

### Where the points went in the 20 ms repeat

The 98–100 loss in m5770 had 93 fresh hits apiece and
upgrades of 5–7. The 91–100 loss in m5798 had 77 fresh hits
apiece and upgrades of 14–23. Both score gaps came from
damage upgrades inside the existing hurt window. The
auditor reconciled all accepted points to the original
scoreboard and linked their client clicks.

| 20 ms regulation counts | Plain W-tap mirror, nominated seat | Frozen movement | W-tap against frozen movement |
|---|---:|---:|---:|
| Observed normal-hit windows through expiry | 336 | 288 | 261 |
| Windows with a sampled ready falling ray | 31 | 72 | 62 |
| Accepted upgrades, including final windows | 25 | 41 | 58 |
| Median upgrade tick after the normal hit | 7 | 8 | 6 |
| Critical fresh on the first fresh tick after an un-upgraded normal hit | 6 | 24 | 3 |
| Of those, click consumed at or after the last immune drain | 6 | 17 | 2 |
| Of those late criticals, an earlier cap-compatible attack loop with the measured wire delay | 0 | 2 | 0 |

The movement bot had more observed falling rays and converted
later. It scored 24 of its 41 upgrades after taking an incoming
fresh hit during the same outgoing window. Its W-tap opponents
did this for four of 58. This describes the recorded order
and does not imply that giving up a fresh hit is desirable.

At the actual consumption times, one of the movement bot's
24 late packets could cross the last-immune deadline with at
most 2 ms less transit, five with at most 5 ms, and seven
with at most 10 ms. The remaining 17 were consumed at or
after that drain, so reducing transit alone cannot move them
into it. Keeping the previous physical press and the 50 ms
cap left only two cases with an earlier native attack loop
under the observed transit time. One of those windows also
had an earlier sampled falling ray. These timing bounds do
not establish that the earlier loop had a valid ray or that
the server would process the strike as a critical.

The model then moved one already-emitted C02 to the previous
drain, when it could do so within 10 ms without jumping a
prior queued packet. It replayed both seats on all four
wire-qualified 20 ms mirror calendars. The unchanged match
scores matched the ordinary scorer, and unchanged branches
reproduced their source hit lists exactly.

| Late modeled criticals over both seats | Plain W-tap | Frozen movement |
|---|---:|---:|
| Audited late criticals | 20 | 22 |
| Consume occurred after the earlier drain | 19 | 21 |
| Earlier C02 produced an upgrade | 1 | 1 |
| Short accepted margin improvement in that paired state | +3 | +1 |

The frozen movement's successful branch on m5743 B needed
5.782 ms earlier packet readiness. Across the next 1.4 s its
accepted margin went from zero to +1, with its fresh-hit
margin unchanged. A plain W-tap suffix from the identical
state also had zero accepted margin. The opportunities were
rare in both policy arms, and the native packet timing
screen does not show that the same score gain would occur
there. Prioritize creating earlier usable contacts when
the bot is falling. A shorter transit path can only help
the smaller group already taking its swing before the cutoff.

[Native match and deadline audit](../../build/boxing-double-search/timing-transfer/repeat-upgrades/summary.json) ·
[Per-click timing bounds](../../build/boxing-double-search/timing-transfer/repeat-upgrades/critical-deadlines.json) ·
[Exact packet forks](../../build/boxing-double-search/timing-transfer/repeat-upgrades/model.jsonl)

### What footwork could change before a late critical

In the 100 ms before the last immune drain, the frozen
movement bot's final preceding press occurred before local
descent in 14 of its 24 late-critical windows. In nine it
made a falling contact and in one it missed while falling.
Several windows contain more than one press. Of the ten
actual falling contacts in that span, seven became the
first-fresh-tick criticals already counted in the outcomes.
Three arrived inside immunity while the server still saw
a non-falling attacker, and scored no damage. The six
late-critical windows in the nominated W-tap mirror seat
all had a final pre-cut press before local descent.

| Last press before the native cutoff | Plain W-tap mirror, nominated seat | Frozen movement | W-tap against frozen movement |
|---|---:|---:|---:|
| Airborne before local descent | 6 | 14 | 1 |
| Falling contact | 0 | 9 | 2 |
| Falling ray miss | 0 | 1 | 0 |

The model then forked at the normal hit that opened the
window, with the policies primed on the original history.
It tried all 728 non-default three-input words beginning
at each movement head before the last immune drain.
The click cap and gate, aim model, and existing jump inputs
stayed fixed. No-op branches reproduced the exact original
hit lists. A word had to create an early upgrade and gain
accepted margin by 1.4 seconds after the cutoff to count as
a joint gain. Nine own/opponent timing combinations tested
whether the same word and ordinal head could repeat the gain.

| Model windows on the four current mirror clocks | Plain W-tap | Frozen movement |
|---|---:|---:|
| Exact late-critical windows | 20 | 22 |
| Words tested | 117,208 | 126,672 |
| Windows with an earlier upgrade at nominal phase | 4 | 5 |
| Windows with an earlier upgrade and higher margin at all nine timings versus stock | 1 | 2 |
| Same, also beating plain W-tap from the same intervention cut at all nine timings | 1 | 0 |

The older two wire-qualified clocks provided six additional
frozen late-critical windows and no W-tap windows. Their
34,944 words added zero earlier upgrades. On the current
clocks two frozen words beat their stock suffixes at every
timing. Keep, walk, release on m5749 A gained +1 to +3,
and keep, keep, sprint-left on m5779 A gained +1. Each
tied its plain W-tap suffix on some phases. One W-tap
word on m5761 A improved +1 to +2 over its own unchanged
suffix. The comparison shows room for individual earlier
upgrades and no broad frozen-policy improvement from the
tested post-hit movement. The native press audit suggests
a separate timing question around when the preceding
normal hit opens the immunity window relative to descent.

[Native pre-cut presses and server outcomes](../../build/boxing-double-search/timing-transfer/upgrade-footwork/native-before-cut.json) ·
[Exact input words and paired timing results](../../build/boxing-double-search/timing-transfer/upgrade-footwork/summary.json)

### Delaying the normal hit to catch the fall

We replayed from just before the physical press that had
scored the normal hit in each late-critical case. A bounded
wait prevented only new physical presses. It preserved
the pending packets, aim, movement policy and 50 ms
physical click cap. The recorded output distinguishes the
scheduled wait from the actual next poll. Both controls
forked the same state and histories at each timing.

| Known late-critical windows at 20 ms | Plain W-tap starts | Frozen movement starts |
|---|---:|---:|
| Current clocks, exact cases | 20 | 22 |
| One fixed wait kept a later normal hit, earned its upgrade, and beat both controls across nine timings | 8 | 6 |
| Older clocks, exact cases | 0 | 6 |
| Same joint gain on the older clocks | 0 | 4 |

The shortest successful waits in the six current frozen
cases ranged from 65 to 200 ms. At nominal timing each
of those waits conceded one opposing point ahead of the
first own hit of the branch where stock conceded zero.
In m5749 A an 80 ms wait admitted the next press after
93.7 ms. It moved the normal hit from 62.6 to 159.7 ms
after the original press and landed an upgrade at
460.5 ms. Over nine timings it gained +1 to +2 accepted
points versus unchanged movement and +3 to +5 versus the
same-state W-tap suffix. The other starts include failures,
and the initial anchor knows which stock press will score.

The older six frozen cases selected an 80 ms wait.
It gave the highest joint-win count there, three of six.
We then applied it to every normal-hit press in three
separate, previously exposed mirror calendars with no
examples used to make that choice. Both seats ran from
exact states with the same short endpoint. Nominal gains
against both controls were retested across the nine
timings. The oracle still knew which original press was
going to score a normal hit.

| 80 ms wait on the all-normal panel | Plain W-tap with the wait | Frozen movement with the wait |
|---|---:|---:|
| Exact start windows | 503 | 413 |
| Mean nominal score difference versus unchanged same-policy suffix | −0.064 | +0.094 |
| Mean nominal score difference versus unchanged same-cut W-tap | −0.064 | +0.041 |
| Nominal wins / ties / losses versus unchanged same-cut W-tap | 158 / 206 / 139 | 167 / 132 / 114 |
| Score gains against both controls across nine timings | 43 | 43 |
| Of those, preserved normal hit and earned its upgrade in all nine | 32 | 32 |
| Extra opposing point before the first own hit at nominal timing | 275 | 227 |

The frozen branch's score difference against same-cut
W-tap ranged from −5 to +4 over the nominal windows.
A descriptive distance split found that the wait averaged
−0.20 across 66 frozen starts inside 2.5 blocks at the
original poll and +0.086 across 347 farther starts.
The sign of the split held on all three calendars.
This compares an observable distance within a
hindsight-selected set of scoring presses. It does not
measure the cost of using that cue on misses and immune
contacts, or the effect of repeated holds in a whole match.
Neither the native regulation grade nor the K3 maintenance
gate has been retested for a hit-selection policy.

[Late-window procedure](../../build/boxing-double-search/timing-transfer/upgrade-hit-selection/protocol.json) ·
[Frozen all-normal comparison](../../build/boxing-double-search/timing-transfer/upgrade-hit-selection/all-normal-protocol.json) ·
[Case scores and timing spread](../../build/boxing-double-search/timing-transfer/upgrade-hit-selection/summary.json)

### Testing the edge-of-reach hold in full matches

The tested rule used only own-client observations. At a
cap-eligible poll it required a currently legal aim ray,
grounded movement, 2.5–3.0 blocks nearest distance, and
500 ms without a local own or opponent hurt cue. It held
new presses for 80 ms, kept the existing movement, and
restored click cadence. It re-armed after leaving close range.
The simulator used the same provisional MMC build as the
preceding window tests. Both arms had the same 20 CPS cap
and its 50 ms physical interval was verified.

| Model regulation panel | Plain W-tap wins / median | Frozen wins / median | W-tap with hold wins / median | Frozen with hold wins / median |
|---|---:|---:|---:|---:|
| 20 ms development | 3/6 / 0 | 3/6 / −1.5 | 4/6 / +4.5 | 5/6 / +5.5 |
| 20 ms separate, previously exposed | 4/8 / 0 | 6/8 / +4.5 | 5/8 / +3.5 | 3/8 / −2.5 |
| 0 ms transfer | 4/8 / 0 | 5/8 / +1 | 1/8 / −18 | 2/8 / −6.5 |
| 80 ms transfer | 4/8 / 0 | 7/8 / +3.5 | 3/8 / −3 | 6/8 / +2.5 |

On the separate 20 ms clocks, adding the hold to frozen
movement changed the aggregate accepted margin from +26
to −5. The fresh-hit margin fell from +23 to −134 and
the upgrade margin rose from +3 to +129. Average physical
click rates were 15.742 against 16.644 CPS in the held
arm. These are cap-matched rather than equal delivered
rates. The one-hold cap came close to equal delivery and
still lost ground. Its click rate was about 0.02 CPS below
the opponent's. Caps of one, four and twelve holds yielded
3/8, 3/8 and 5/8 with median margins of −2, −3.5 and +4.
The unmodified policy remained ahead on this panel.

From ten common K3 starts across the panels, plain W-tap
completed six active cycles, frozen movement two, and
frozen movement plus the hold five. The one K5 start
gave four, eight and four respectively. There were no
K10 starts. Active and return-only results agreed and
every run ended with a fresh return. Seven K3s outside
the separate 20 ms panel accounted for zero further
cycles in those arms. The result leaves the common-state
maintenance gate open.

To isolate the effect of one hold, a passive copy of
the rule marked matching polls during unchanged frozen
play. Its markings preserved the regulation scores and
the exact pre-poll replay reproduced the original hits.
Of 411 marks, 405 of the immediate stock presses scored
a fresh hit. One hold gained +0.219 accepted points
against the same stock state in the next 1.4 seconds, from
−0.219 fresh and +0.438 upgrades. It conceded an extra
opposing point before the first own point in 288 of those
windows. The result differs from the repeated full-match
use, whose trajectories and later opportunities change.

| Stock first exchange, classified after the fact | Plain W-tap unchanged delta | Isolated hold count | Mean delta versus stock | Mean delta versus same-cut W-tap | Wins over both at all nine timings |
|---|---:|---:|---:|---:|---:|
| W-tap scores before the selector | 0 | 111 | −0.180 | −0.171 | 9 |
| Selector scores first, return within 75 ms | 0 | 265 | +0.547 | +0.619 | 36 |
| Selector scores first, later or no return | 0 | 35 | −1.000 | −0.800 | 0 |

When the stock quick-return label held across all nine
timings, 31 of 189 holds beat both controls at every
timing. The means in this table use the nominal timing.
The first exchange labels use the future stock outcome.
They show why distance alone is an incomplete cue.
These are model diagnostics on previously exposed clocks.
They do not establish a native hit-selection improvement.

[Regulation and maintenance results](../../build/boxing-double-search/timing-transfer/edge-hit-select/summary.json) ·
[Frozen controller conditions](../../build/boxing-double-search/timing-transfer/edge-hit-select/protocol.json) ·
[Exact single-hold comparisons](../../build/boxing-double-search/timing-transfer/edge-hit-select/single-hold.jsonl)

### Selecting holds by expected score

The observation fit used three development calendars with
both seats. At each marked poll it saw its own movement
and recent contact, hurt and physical-click times. It also
compared the opponent replica's positions across recent
own input heads. The additional timing model used only
target movement packets already processed by that client.
The fits used either the fraction of nine future timings
with a quick return or the mean score gain of the same-state
hold. The four comparison clocks had been opened for earlier
diagnostics and supplied no fit examples. A shift after the
same observation changed the trade class in 109 of their
411 polls.

| Same-state choices on the four comparison clocks | Quick-trade AUC | Accepted margin versus no hold | Accepted margin versus plain W-tap |
|---|---:|---:|---:|
| Continue plain W-tap | — | −0.050 | 0 |
| Continue frozen movement | — | 0 | +0.050 |
| Always hold | — | +0.185 | +0.235 |
| Range-only value rule | 0.674 | +0.122 | +0.173 |
| Local history value rule | 0.715 | +0.213 | +0.263 |
| Local history and packet value rule | 0.718 | +0.192 | +0.242 |
| Hindsight choice by true mean gain | — | +0.472 | +0.523 |

The AUC column measures the associated quick-return forecast
from each feature set. The action column uses the separate
score fit. Predicted gain of at least +0.25 opened a hold on
202 opportunities for the local history fit. Net improvement
over stock was positive on all four clocks, from +0.183 to
+0.258. The score model predicted changes in accepted margin
more usefully than a fixed threshold on quick-return probability.
An opposing C02 was already queued in 51 comparison states.
Only about 1% of the nine timings from those states were
stock own-first quick returns. That queued state is available
only to the diagnostic.

The local history value fit had the best leave-one-clock-out
score on development and was then frozen for full matches.
Every cap-eligible opportunity used the same local approach
gate. The score controlled only the decision to admit the
80 ms hold. The causal feature history and warm continuation
controls shared the exact scored states.

| Regulation panel, wins / median accepted margin | Plain W-tap | Frozen movement | Frozen plus score-selected hold |
|---|---:|---:|---:|
| 20 ms development | 3/6 / 0 | 3/6 / −1.5 | 6/6 / +12.5 |
| Separate 20 ms | 4/8 / 0 | 6/8 / +4.5 | 7/8 / +16 |
| 0 ms transfer | 4/8 / 0 | 5/8 / +1 | 4/8 / +2.5 |
| 80 ms transfer | 4/8 / 0 | 7/8 / +3.5 | 8/8 / +10 |
| Separate 20 ms over nine shifts per start | 36/72 / 0 | 48/72 / +4 | 65/72 / +14 |

At nominal separate 20 ms the candidate ranged from −4 to
+23, with +10 total fresh margin and +85 upgrade margin.
Across shifted timings the range was −12 to +26. The 72
shifted bouts derive from eight source seat states and are
correlated. Both fighters had the same cap. The candidate
averaged 16.13 CPS against 16.64 in the eight nominal
comparison bouts. Applying the same score-selected gate
to W-tap movement there won 4/8 with median −0.5.

From the ten exact maintenance K3s, plain W-tap completed
six active cycles, frozen movement two, and frozen movement
plus the score gate one. The one K5 start gave four, eight
and seven respectively. K10 had no qualifiers. At m5743 A
K3 the score gate began holding 1,422.860 ms after the
landmark. The stock next hit at 1,451.353 ms was postponed
to the return's drain at 1,551.957 ms and processed after
the return. The active episode also reached the 1,000 ms
inter-hit gap cutoff before the return. The same failure
mode gave up one further cycle from K5. This is a regression
on one common start. The stronger regulation results
support continued diagnostics under the frozen model.
Matched delivered CPS and maintenance remain unqualified
for a native candidate trial.

[Feature definitions and fit](../../build/boxing-double-search/timing-transfer/quick-return-prediction/protocol.json) ·
[Paired prediction scores](../../build/boxing-double-search/timing-transfer/quick-return-prediction/summary.json) ·
[Individual full-match and maintenance results](../../build/boxing-double-search/timing-transfer/quick-return-prediction/full-summary.json)

### How long the selected click needs to wait

The score fit and admission threshold stayed frozen. We changed only
the duration of the hold. The shortest choice skips the currently
eligible poll and allows the next one. On the 202 score-selected
opportunities in the separate 20 ms panel, the median actual wait was
14.2 ms for one skipped poll, 31.1 ms for the 20 ms hold and 89.8 ms
for the 80 ms hold.

| Isolated hold on 202 selected opportunities | Mean paired score versus plain W-tap | Mean paired fresh margin versus frozen movement | Score gains against both controls at all nine timings | New unanswered doubles against both at all nine timings |
|---|---:|---:|---:|---:|
| Skip one poll | +0.050 | +0.002 | 0 | 0 |
| 20 ms | +0.182 | +0.009 | 4 | 0 |
| 40 ms | +0.284 | +0.028 | 6 | 0 |
| 60 ms | +0.334 | −0.026 | 11 | 0 |
| 80 ms | +0.482 | −0.069 | 26 | 0 |
| 100 ms | +0.490 | −0.133 | 25 | 0 |

The extra accepted margin at 60–100 ms came from damage upgrades.
The double check required a fresh hit on the first admitted press
and a second fresh hit within 850 ms, strictly before any accepted
return. The replay retained all earlier poll marks and reproduced every
archived unchanged and 80 ms branch. These source clocks were
already exposed. The nine timing branches per cut are correlated.

| Full model regulation at nine timing shifts, wins / median margin | Plain W-tap | Frozen movement | Frozen movement skipping one selected poll |
|---|---:|---:|---:|
| 0 ms | 36/72 / 0 | 56/72 / +3.5 | 58/72 / +7 |
| Separate 20 ms | 36/72 / 0 | 48/72 / +4 | 47/72 / +4 |
| 80 ms | 36/72 / 0 | 59/72 / +8 | 67/72 / +10.5 |

The 80 ms skip won seven or eight of eight bouts in each phase
cell. Margins across the 72 correlated runs ranged from −7 to +24.
It delivered 16.67 CPS versus 16.72 for its W-tap opponent at the
shared 20 CPS cap. On the separate 20 ms clocks the frozen 80 ms
hold remains the stronger regulation result, with lower delivered
CPS. The best hold length depends on the RTT and the metric.

| Matched maintenance | Plain W-tap | Frozen movement | Skip one poll | 20 ms hold | 80 ms hold |
|---|---:|---:|---:|---:|---:|
| K3, active / return-only cycles across ten starts | 6 / 6 | 2 / 2 | 2 / 2 | 1 / 2 | 1 / 1 |
| K5, active / return-only cycles on one start | 4 / 4 | 8 / 8 | 8 / 8 | 8 / 8 | 7 / 7 |
| K10 entries | 0 | 0 | 0 | 0 | 0 |

At m5743 A, skipping one poll preserved frozen movement's follow-up
on the original server tick. A 20 ms hold shifted it a drain later,
outside the activity limit while still beating the return. The 80 ms
hold moved it into the return's drain and lost the order. Shortening the
hold can fix that specific timing regression. The unmodified movement
still lost four additional cycles to W-tap at K3.

The first fresh follow-up in that fork landed after 452.6 ms with
frozen movement and after 552.0 ms with W-tap. Both imparted the same
velocity. Horizontal server separation at those launches was 3.738
versus 3.530 blocks. The next chase took 998.8 ms versus 597.5 ms.
On that next hit the gap had closed to 1.979 blocks with frozen movement
and 2.934 with W-tap. Replacing only the frozen walk 81.1 ms after
K3 with W-tap's release delayed the first hit by 99.5 ms and changed
the activity-qualified count from two to six on the nominal clock.
With a −5 ms defender shift it still reached only two, while matched
W-tap reached six. At K5 on the same donor, one walk tick in W-tap's
continuation extended it from 4/4/6 to 8/11/8 active cycles at
nominal/−5/+5 ms. The first follow-up stayed fixed and the second
landed 51.9 ms earlier at nominal timing. These are single-input
diagnostics from one donor. They show how the same movement change can
affect a later pursuit cycle and why the K3 deficit cannot be repaired
by tuning the approach hold alone.

The native comparison re-linked every transition after the first K3
in seven independent entries from the previous 24 registered drills.
Five of seven final returns were clicked before the last own hit had
finished processing, including three same-drain returns. In the
13-hit frozen movement run, the follow-up after hit twelve landed
ten server drains after the first fresh window opened.
The pursuer took two sprint-contact brakes during immunity in that
long chase. It took two to four in the earlier successful chases too.
The count alone gives no causal explanation for the stall. The native
records show a late chase and an early return in the same sequence.
The changed click holds were tested in the model only and none passed
the matched maintenance gate.

[Frozen test protocol](../../build/boxing-double-search/timing-transfer/edge-hold-duration/protocol.json) ·
[All durations and individual outcomes](../../build/boxing-double-search/timing-transfer/edge-hold-duration/summary.json) ·
[Matched input substitutions](../../build/boxing-double-search/timing-transfer/edge-hold-duration/maintenance-inputs.jsonl) ·
[Native pursuit and return links](../../build/boxing-double-search/timing-transfer/edge-hold-duration/native-chase.json)

### Transfer to the recorded MMC preset

The current practice preset uses ten ticks to the next fresh
hit and about 0.9495 blocks/tick horizontal sprint knockback.
The provisional preset used nine ticks and about 0.8544.
We held the existing score fit, movement weights, 3-block reach
and 20 CPS cap fixed. The same recorded opportunity schedules
were used under both profiles. The explicit v2 run reproduced
every archived control and hold outcome. The new profile
comparison reuses those exposed schedules as timing donors.
It is a joint change of immunity and knockback.

| Recorded-profile regulation, wins / median margin | Plain W-tap | Frozen movement | Skip one selected poll | Hold the selected poll for 80 ms |
|---|---:|---:|---:|---:|
| 0 ms, nine phase variants per start | 36/72 / 0 | 46/72 / +4 | 52/72 / +7.5 | 18/72 / −9 |
| Separate 20 ms, nine phases per start | 36/72 / 0 | 56/72 / +5 | 48/72 / +3.5 | 32/72 / −3 |
| 80 ms, nine phases per start | 36/72 / 0 | 46/72 / +6 | 59/72 / +7 | 55/72 / +8.5 |

Every cell has eight source starts. At 80 ms the skip ranged
from −13 to +30 and delivered 16.670 versus 16.711 CPS.
At 20 ms the long hold's advantage on the old profile
reversed under the new one. Frozen movement at 20 ms
delivered 16.647 versus 16.646 CPS. The individual scores
retain fresh hits and damage upgrades separately.

| Recorded-profile matched maintenance, active cycles | Plain W-tap | Frozen movement | Skip one selected poll | 80 ms hold |
|---|---:|---:|---:|---:|
| K3 at 0 ms, one state × nine phases | 66 | 49 | 48 | 9 |
| K3 at 20 ms, four states × nine phases | 138 | 238 | 156 | 120 |
| K3 at 80 ms, three states × nine phases | 67 | 99 | 100 | 118 |
| K5 at 0 / 20 / 80 ms, one / three / three states × nine phases | 30 / 124 / 9 | 60 / 121 / 9 | 93 / 111 / 9 | 24 / 87 / 9 |
| K10 at 20 ms, one state × nine phases | 35 | 10 | 10 | 10 |

The phase shifts begin after exact common landmarks in the
maintenance runs. The active stop used the two-fresh-window
plus 100 ms cutoff for the profile. Return-only totals matched
the active totals in this table. The phase replicates remain
correlated, and K10 was absent at 80 ms. A W-tap-movement
arm with the same one-poll gate gained K3 cycles at 0 and
20 ms but lost them at 80 ms. The gain depended on the
interaction between click timing and movement.

### Footwork before and after the first follow-up

From the exact K3 checkpoints we tried three-head ordinary
movement sequences with the original click policy. The same
W-tap and frozen controls ran through a 4.5 s horizon.
The audit measures distance to the opponent's displayed
hitbox at the first eligible server drain and the actual
fresh hit delay separately. A robust word must add an
activity-qualified cycle over both controls and preserve or
improve both score margins in every timing variant.

On provisional m5743 A, frozen movement's distance at the
first window following its first follow-up was 4.465 blocks,
against 3.219 for W-tap. The next hit was 11 drains late,
against three for W-tap. None of 4,368 post-launch movement
words brought that frozen next hit forward. On the recorded
profile, 8,917 of 62,608 post-launch words beat both controls
on nominal timing, yet zero did so across all nine phases.
The same search beginning before the first follow-up found
56 robust words out of 69,888, all at m5505 A.

At that state, starting back, walk, back at the first
head after the locally visible target hurt cue made the
first follow-up about 204 ms later. It launched from
3.092 blocks horizontally at nominal timing, against 3.476
for frozen and 3.784 for W-tap. At the next fresh window
the nearest distances were 2.126 blocks on our client and
3.902 on the defender's. The next hit landed on the first
eligible drain in all nine phases. W-tap landed it six
drains late at nominal timing and frozen movement two
drains late. The word gave three to seven active cycles
across the phases, versus two for W-tap and two to six for
frozen movement. Short margin gains over both ranged from
zero to two.

The same cue and word reduced the cycle count versus frozen
movement on six of the other seven recorded-profile states
in all nine phases. The seventh also lost cycles in its
six live continuations. This was a local cue replay after a
shared referee-acquired K3 checkpoint on exposed clocks.
The witness identifies a geometry and timing intervention,
with no transferable rule established by this cue.

The incumbent movement weights entered an independent
48-bout native benchmark at 0, 20 and 80 ms under the
recorded profile. Its comparison uses existing policies and
shares the lab's scheduler. It includes ordinary first-to-100
and separate 20-second K3 starts.

[Matched model scores and exposure](../../build/boxing-double-search/timing-transfer/chase-window/results.json) ·
[Cue replay](../../build/boxing-double-search/timing-transfer/chase-window/cue-mmc-recorded-80.jsonl) ·
[Native benchmark schedule](../../build/boxing-double-search/timing-transfer/chase-window/native-benchmark-plan.json)

### Separating pursuit spacing from click delay

At m5505 A, holding the first scoring press for 150 or
200 ms reproduced the retreat's exact contact time and
scoring drain in six timing variants. All six applied
the same sprint knockback in both arms. The defender
consumed the same scheduled click 12.4–18.3 ms after
the hit. It reached from 2.705–2.706 blocks against the
click-only hold and missed with the nearest hitbox at
3.622 blocks against back, walk, back. The click-only
branches ended with one active follow-up each. The
retreat added three to seven total follow-ups in those
same phases. At the other three phases, the available
hold lengths did not reproduce the launch within 50 ms.
The same-drain comparison isolates the role of pursuit
spacing from the time of the hit and its impulse.

The shorter retreat and click-only hold screen retained
all eight exact drill starts. No fixed hold length gave
a cycle gain over both controls with equal or better
short score in all nine phases of any drill. We repeated
the actions on 30 other exact K3 starts drawn from W-tap
regulation before either fighter reached 85 accepted
points. Both players had been clicking from the opening.
None of the fixed retreat lengths or holds passed across
all nine timings at any of those starts.

Searching all ordinary three-head words at the first
locally visible cue produced a different witness in
the natural regulation start m5493 A, at 0 ms added RTT.
Seven of the 42,224 tested words beat both controls
throughout the phase grid, all from this state.
Walk, right, sprint-left landed the first follow-up
on the same nominal drain as W-tap. Its following hit
landed on the first fresh drain in all nine variants.
At nominal timing the nearest distances at that drain
were 2.906 for the attacker and 3.954 for the defender.
It sustained eight active cycles in the 4.5-second cut
in all variants, compared with three to seven for
W-tap and two to six for frozen movement. The 4.5 s
endpoint censored the winning branch. Action selection
used outcome hindsight. Two different witnesses support
testing how the first launch sets up the next usable
range window before training a general rule.

The full-match one-poll gain at 80 ms also needs a
perturbation control. Of 115 selected isolated polls,
113 preserved both sides' first accepted hit times
relative to frozen movement throughout the phase grid.
The average 1.4 s margin gain over frozen movement was
+0.020. No selected skip added an uninterrupted double
over both controls across all timings. Waiting 80 ms
gained +0.437 accepted margin in those cuts while
losing 0.137 fresh margin, via damage upgrades.

| Recorded-profile first-to-100 at 80 ms | Plain W-tap | Frozen movement | Skip one high-score poll | Skip first eligible poll | Skip one low-score poll | Unlimited high-score skips |
|---|---:|---:|---:|---:|---:|---:|
| Wins in 72 related timing variants | 36 | 46 | 51 | 49 | 52 | 59 |
| Median accepted score margin | 0 | +6 | +7 | +7 | +8 | +7 |

The three one-skip arms averaged 16.708–16.709 CPS
against 16.711–16.712. Choosing the high-score poll did
not beat the opposite threshold. A cap of four selected
skips gave 51 wins and median +5. A cap of sixteen gave
59 wins and +6. The unlimited rule averaged fifteen skips
per match. The results show sensitivity of cumulative
score to small timing changes, while the isolated tests
leave reliable fresh-hit selection unqualified. Each
full-match panel uses the same eight exposed source starts.

[Exact comparisons and full match scores](../../build/boxing-double-search/timing-transfer/chase-attribution/summary.json) ·
[Natural K3 cue search across both seats](../../build/boxing-double-search/timing-transfer/chase-attribution/observed-cue-screen.json)

### Own observations and the timing limits of two successful starts

We froze the two footwork sequences and replayed them at
the first locally visible target hurt cue on 38 exact K3
starts from 12 previously exposed clocks. Both actions
and controls reused identical acquisition histories and
queued packets at each start. The bot had a decision in
336 of 342 phase variants. The readouts used displayed
spacing and height, own movement, contact and hurt ages,
RTT and the latest past position samples. No defender POV
or future outcome was a fitted input. Each fold excluded
its complete donor clock, across both cohorts and seats.

| Rule over 342 phase variants | Active cycles | Accepted margin over the 4.5 s cuts | Starts that gained cycles and preserved margin against both controls in all nine phases |
|---|---:|---:|---:|
| Plain W-tap | 845 | +548 | — |
| Frozen movement | 1,018 | +789 | — |
| Back, walk, back at every cue | 223 | +75 | 1 |
| Walk, right, sprint-left at every cue | 950 | +726 | 1 |
| Own geometry fit, one donor left out per fold | 1,019 | +777 | 0 |
| Own history fit, one donor left out per fold | 1,025 | +779 | 0 |
| Hindsight choice of a word that passes all nine | 1,059 | +835 | 2 |

The history fit intervened in 21 phase variants. It
gained seven cycles over frozen movement and gave up
ten accepted points. Its cycle gain came from 80 ms
cases, where it gained ten cycles and gave up nine
points. It chose neither witness across all its timing
variants. The two witness cues were both grounded with
sprint released. Their displayed nearest gaps were
2.106 and 2.369 blocks and their recent closing speeds
were about 0.110 and 0.052 blocks/tick. A method that
chooses from these measurements alone has marginal
headroom with the two tested actions on this panel.

For each witness we combined 25 future client phase
pairs with nine extra attack-dispatch assignments,
using zero, three or six milliseconds on each side.
Only new packets were retimed.

| Widened timing sensitivity | Plain W-tap median active cycles | Frozen median | Fixed word median | Word next hit on first fresh drain | Word beats both maintenance and short score |
|---|---:|---:|---:|---:|---:|
| m5505 A, back/walk/back | 2 | 4 | 5 | 225/225 | 138/225 |
| m5493 A, walk/right/sprint-left | 7 | 2 | 8 | 180/225 | 144/225 |

At m5505, the word still hit on the first fresh drain
with six extra milliseconds of attack dispatch on both
sides, while only seven of 25 phase variants passed
both outcome conditions. Overall it gave up cycles
relative to frozen movement in 45 of 225 variants.
Each of its four measured cue vectors occurred in
both passing and failing runs. At m5493 the word
preserved or added cycles versus both controls in all
225 variants. Its joint score gate passed in 16 of 25
client phase pairs for every tested dispatch assignment.
One of five measured cue vectors had both outcomes.
These are sensitivities on two states. First-window
control and whole-chain survival measure different
parts of this fight.

[Clock-blocked cue fits and timing variants](../../build/boxing-double-search/timing-transfer/chase-observations/results.json)

### Standalone jump escape on the recorded preset

We repeated the existing velocity-triggered jump in
model regulation under the recorded preset, while
keeping ordinary W-tap movement, aim and click pacing.
Each latency panel uses eight exposed source starts
and nine input-phase pairs with a matched W-tap mirror.

| Added RTT | Plain W-tap wins / median margin | Impact jump wins / median margin [spread] | Jump bout actual CPS, subject / W-tap |
|---|---:|---:|---:|
| 0 ms | 36/72 / 0 | 54/72 / +5.5 [−10, +31] | 16.744 / 16.744 |
| 20 ms | 36/72 / 0 | 39/72 / +1 [−10, +34] | 16.650 / 16.650 |
| 80 ms | 36/72 / 0 | 44/72 / +3 [−12, +20] | 16.711 / 16.711 |

The current model's positive median at 80 ms differs
from the older provisional-profile study. Native evidence
for the older profile still stands under its original
configuration. For the isolated jump we forked immediately
before the first incoming fresh hit in each qualifying
chain under W-tap. The initial hit and queued attacks
were shared. We counted accepted hits before the first
reply, including damage upgrades, over 1.7 seconds.

| Added RTT | Plain W-tap cases conceding two or more | One jump cut those cases to one | Plain W-tap cases conceding one | One jump raised those cases to two or more |
|---|---:|---:|---:|---:|
| 0 ms | 147 | 55 | 354 | 62 |
| 20 ms | 176 | 49 | 338 | 63 |
| 80 ms | 191 | 21 | 200 | 34 |

The isolated reference and jump had identical K3/K5/K10
outcomes on 8/7/2 exact starts, with 33/18/4 active cycles
respectively and matching return-only endpoints. A return's
velocity is usually observed after the event that terminates
maintenance. The early controls intended to keep a combo
and the later controls intended to escape one stay separate.

[Standalone first-to-100 and exact escape comparisons](../../build/boxing-double-search/timing-transfer/chase-observations/jump-results.json)

#### Native first-to-100 on the current range preset

The existing grounded impact-jump policy played 12 independent
first-to-100 bouts against W-tap on `mmc_recorded_range_v4`. The
control group played 12 W-tap mirrors. The schedule froze two
repeats in each seat at 0, 20 and 80 ms of symmetric added RTT.
Both arms used native 3-block reach, physical mouse input and
the same 20 CPS ceiling. This is a separate current-preset test
from the older native trials.

| Added RTT | Native W-tap wins / median margin [range] | Native impact jump wins / median margin [range] | Model W-tap wins / median margin [range] | Model impact jump wins / median margin [range] |
|---|---:|---:|---:|---:|
| 0 ms | 2/4 / 0 [−60, +51] | 2/4 / −1.5 [−5, +2] | 20/40 / 0 [−49, +49] | 28/40 / +10.5 [−8, +82] |
| 20 ms | 4/4 / +9.5 [+5, +63] | 2/4 / +8.5 [−2, +32] | 20/40 / 0 [−73, +73] | 30/40 / +4 [−8, +94] |
| 80 ms | 3/4 / +3 [−9, +9] | 4/4 / +25 [+10, +49] | 15/30 / 0 [−75, +75] | 22/30 / +8.5 [−14, +72] |

The native table retains all completed scoreboards. In m7312 the
subject's last recorded press is 400 ms before the finish, and the
input capture cannot link the last server C02. On the input-qualified
0 ms subset the jump arm is
1/3 with median −4. In the 80 ms qualified bouts the jump arm's
median physical CPS is 17.376 against 17.405, and the W-tap
mirror is 17.509 against 17.516. The largest within-bout difference
over all qualified runs is 0.301 CPS. Model arms used the same
opening, seats, input clocks and cap on 11 previously exposed clocks
with five timing variants per seat. These timing variants are
correlated. Native repeats do not share exact full state.

The 80 ms winning margins are +15 and +10 in seat A and +35
and +49 in seat B. The first unanswered incoming run is one
hit before a reply in three of four bouts and two in one of four
in both arms at that latency. The regulation result therefore
does not establish that the first escape itself improved. For
all qualified jump bouts the ledger finds 648 takeoffs on fresh
melee velocity and five on other self-velocity packets. The
first jump movement starts grounded at +0.42 blocks/tick in
each linked case. Nine end-of-bout packets in the full cohort
have no next recorded movement. Keep their physics unclassified.
The smaller independent native comparison gives a positive
80 ms result and does not confirm the model's gains at 0 or
20 ms. No integration or maintenance promotion.

[Frozen native schedule](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/protocol.json) ·
[All scores, actual CPS and incoming-run ledger](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/score.json) ·
[Current-preset model pairs](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/model-score.json)

#### Fresh 80 ms repeats and exact first-impact forks

The frozen second batch is 16 independent first-to-100s on the
same range preset, with four repeats per arm per seat. All
physical input traces qualify. The table shows the named
fighter's score first and keeps each independent bout.

| Seat | Plain W-tap scoreboards | Impact jump scoreboards | Plain W-tap wins / median margin [range] | Impact jump wins / median margin [range] |
|---|---|---|---:|---:|
| A | 100–14, 100–38, 100–81, 100–95 | 97–100, 100–87, 100–87, 100–90 | 4/4 / +40.5 [+5, +86] | 3/4 / +11.5 [−3, +13] |
| B | 100–89, 82–100, 100–58, 9–100 | 100–28, 100–90, 100–6, 100–87 | 2/4 / −3.5 [−91, +42] | 4/4 / +42.5 [+10, +94] |
| Both | — | — | 6/8 / +15 [−91, +86] | 7/8 / +13 [−3, +94] |

The controls' median physical CPS is 17.571 / 17.532. The
jump arm's median is 17.515 / 17.523. The maximum within-bout
difference is 0.207 CPS. There are 301 verified takeoffs from
fresh-hit velocity and two from other own-velocity packets.
The first incoming run costs two or more hits in five jump
bouts and two control bouts, out of eight each. Across
complete bouts, the median share of activity-qualified
incoming runs that reach two hits is 32.0% versus W-tap's
37.9%. These are separate views of independent engagements.

The low B control floor and high B jump scores reflect long
runs. Counting each accepted point at position three or later
in a run with gaps at most 1.1 seconds, the B jump arm gains
170 and gives up six. The B control gains 84 and gives up 142.
The biggest jump totals are 92 in m7327 and 73 in m7336.
This partition describes the scores. Both native arms started
from the same rules with independent timing and acquisition.

Each of the eight new W-tap mirrors supplies a measured
input and wire calendar. From these clocks, the model
compares both seats and five timing settings per seat.
W-tap wins 40/80 with median zero [−94, +94]. The jump wins
70/80 with median +13.5 [−10, +94]. Median paired changes
are +13 [−95, +103] in A and +58 [−56, +150] in B.
Seat B has greater upside on these calendars. In seat A,
the unpaired arm medians also fall in the model, from +24.5
to +13.5, even though the median paired change is positive.
Clock variants are correlated. Individual paired results
vary sharply in both seats.

We also held the native recoil and both fighters' queues fixed
just before the first grounded movement after an incoming hit.
Both branches keep exactly the same physical presses and
recompute W-tap and aim. One branch presses Jump for that move
and then both return to ordinary W-tap. The recorded arm
checks the accepted native hits and launches.

| Panel | Qualified cuts / requested | W-tap variants with one incoming hit before reply | Jump variants with one incoming hit before reply | Jump shortens / lengthens the run vs W-tap | Jump shortens / lengthens under the 1.1 s activity limit |
|---|---:|---:|---:|---:|---:|
| Pilot recordings | 47/48 | 272/423 | 260/423 | 32 / 44 | 23 / 34 |
| Fresh confirmation recordings | 88/94 | 531/792 | 449/792 | 37 / 136 | 28 / 119 |

Six fresh cuts from m7326 lack the strict prefix. Its replay
attempts to send the opening sprint toggle before the first
available native position packet. One pilot cut from m7313
fails accepted-hit parity. The native bout results are retained.
In 31 of 88 confirmation cuts a reply already scored before
takeoff, so the first unanswered run cannot change. In the
rest, the nominal median change in the 1.7-second accepted
margin is zero [−3, +4]. Among the 109 timing variants that
turn a W-tap single into two or more incoming hits, 89 keep
the same opponent press scoring while delaying the reply.

At 1.11 seconds in m7328, the jump changes the defender's
first reply ray from 3.279 to 2.967 blocks and moves the
reply up by 249 ms. The original opponent follow-up ray
goes from 2.860 to 3.989 blocks. At 0.61 seconds in m7333,
the jump takes the reply from 2.829 to 3.206 blocks,
delays it 58 ms and allows the same opponent press to
score first. Each effect holds in all nine tested timing
variants for its start. These exact pairs explain why
jumping on every eligible impact is a poor early escape rule.

[Native scores and run distributions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/confirmation-80/score.json) ·
[Common-clock model pairs](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/confirmation-80/model-score.json) ·
[Exact grounded-impact comparisons and linked presses](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/confirmation-80/impact-forks/summary.json)

#### A jump after two unanswered fresh hits

On the eight native confirmation mirrors, the designated
defending seat receives 61 second unanswered fresh hits.
Sixty have a grounded movement and an input decision after
the linked velocity packet. The first six eligible per bout
give 36 requests with enough later recorded play. Thirty
pass strict reconstruction. Five from m7326 fail the opening
prefix check, and one from m7332 fails suffix hit parity.
In 24 of the 30, the defender has already scored a reply
before the movement. The table counts from the second
fresh hit onward and includes this anchor on both sides.

| W-tap mirror group | Timing variants | W-tap still concedes multiple hits before a reply | Jump still concedes multiple hits before a reply | Jump shortens / lengthens the run vs W-tap |
|---|---:|---:|---:|---:|
| All qualified cuts | 270 | 27 | 20 | 24 / 0 |
| Six cuts with no already-scored reply | 54 | 27 | 20 | 24 / 0 |

The effect is spread over three native states. It persists
under both return-only and activity-qualified counts.
At 5.72 seconds in m7324 B the jump brings a reply ray from
3.666 to 2.785 blocks and scores a reply 650 ms after the
anchor. W-tap concedes four points from that hit onward.
The jump concedes two across all nine timing variants
and gains two points in accepted margin relative to W-tap.
The median short score change on all six genuinely open cuts
remains zero [−2, +4]. There is no live triggered-jump trial
at this landmark.

A read-only cue test checks every linked, grounded impact
in the eight mirrors. Counting exactly two own hurt-status
packets since the last visible target hurt catches all 16
server-confirmed open second-hit threats. It also flags 21
other impacts. Twenty already have a scored reply before
travel, including 19 true second hits. The packets supply a candidate trigger with
limited precision. An accepted upgrade or a return in flight
can affect the real outcome before the client's visible
hurt order changes. Avoid treating the visible tally as the
referee's true streak.

[Second-hit replay pairs and enrollment](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/confirmation-80/after-two-forks/summary.json) ·
[Own-client packet audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/confirmation-80/threat-observations.json)

#### Allowing for the reply's time to reach the server

The first development screen treated a recent local sword
contact as evidence of a reply. No setting in that screen
both reduced false calls and preserved at least 75% of
open threats in the eight development mirrors. A linked
attack from a visible second-hurt call takes a median
69.54 ms from local contact to acceptance, spanning
48.12–92.21 ms at 80 ms RTT. A contact immediately before
the input head can still be queued. Of 37 calls, 18 have
an accepted reply before the head, ten have already
sent the eventual reply, five press it later, and four
have no reply in 1.7 seconds.

The second development screen therefore considers only
contacts old enough to have been handled. Freeze one rule
before reading the new captures. Suppress the jump if the
bot made a target contact at most RTT plus 25 ms before
the latest own hurt cue, and at least half RTT plus 24 ms
before the input head. Both times come from the bot's own
client. The older-mirror fit keeps all 16 open threats and
flags four other situations, down from 21. Leaving each
whole mirror out in turn keeps 13 and flags three, versus
16 and 21 from the visible hurt count alone.

The development replay regrade applies both rules to the
same paired forks. Of 270 variants, plain W-tap concedes
multiple hits in 27, and either jump rule reduces that
to 20. Both jump rules shorten 24 runs and extend zero.
The service guard chooses 72 jumps to the unguarded
rule's 126. Its median paired short score is zero
[−2, +4], compared with zero [−3, +4] without the guard.

| Added RTT on exposed model clocks | W-tap wins / median margin | Second-hurt jump wins / median margin | Reply-guard jump wins / median margin |
|---|---:|---:|---:|
| 0 ms | 20/40 / 0 | 30/40 / +1.5 | 30/40 / +1.5 |
| 20 ms | 20/40 / 0 | 27/40 / +2 | 23/40 / +1 |
| 80 ms | 40/80 / 0 | 63/80 / +10.5 | 59/80 / +8.5 |

All 160 model pairs reach 100 with identical press
opportunities through their shared endpoint. The two
jump selectors also have identical return-only and
activity-qualified results to W-tap from all 130 K3,
115 K5, and 104 K10 matched maintenance states. The
guard makes fewer jumps, but that improvement in threat
classification does not increase full-match model score.

[Frozen development rule and outcomes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/service-development.json) ·
[Linked reply timing](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/development-inflight.json) ·
[Exposed clock scores and maintenance outcomes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/model-exposed/score.json)

#### Fresh mirrors and the regulation tradeoff

The reply rule froze before reading the 16 new native
mirrors. Two 0 ms scoreboards have terminal gaps in their
physical input traces. Their scores remain archived, while
the following observed-cue totals use the 14 full traces
and only the preassigned seat in each match.

| Added RTT | Open grounded second-hit threats | W-tap jump calls | Visible second-hurt true / false calls | Reply guard true / false calls |
|---|---:|---:|---:|---:|
| 0 ms | 6 | 0 | 6 / 0 | 6 / 0 |
| 20 ms | 9 | 0 | 9 / 2 | 8 / 2 |
| 80 ms | 25 | 0 | 25 / 31 | 24 / 2 |
| Total | 40 | 0 | 40 / 33 | 38 / 4 |

A separate packet audit links 65 of the visible calls
to a W-tap reply within 1.7 seconds. Thirty-one already
scored before the input head, 18 were sent before the
head but scored later, and 16 were pressed afterward.
Eight have no reply in the window. The guard suppresses
29 of the already-scored cases and two in flight.

The exact replay enrolls the first six usable visible
calls in each independent mirror, with the call fixed
before assessing the reply. All 55 requests pass recoil
and accepted-hit parity. Plain W-tap concedes multiple
hits in 99/495 timing variants. Either jump rule brings
this to 81/495 and shortens 81 runs without extending one.
The guard skips 17 of the 55 calls. In those 17 paired
states, jumping improves the 1.7-second margin in 27
variants and reduces it in 18, for a net +18 points across
the tested clocks. A jump can affect the following exchange
even when the current reply has already scored.

| Added RTT on fresh model clocks | W-tap wins / median margin [range] | Second-hurt jump wins / median margin [range] | Reply-guard jump wins / median margin [range] |
|---|---:|---:|---:|
| 0 ms | 20/40 / 0 [−61, +61] | 29/40 / +2 [−4, +75] | 29/40 / +2 [−4, +75] |
| 20 ms | 20/40 / 0 [−91, +91] | 30/40 / +2 [−11, +91] | 29/40 / +2 [−11, +91] |
| 80 ms | 40/80 / 0 [−94, +94] | 65/80 / +17.5 [−8, +94] | 58/80 / +5 [−9, +94] |

The visible and guarded arms use 816 and 478 jumps.
All 160 paired matches complete with identical physical
press schedules up to the first endpoint. The exact
model scoreboards and remaining-length distributions
match W-tap at all 126 K3, 119 K5, and 111 K10 maintenance
starts. Native mirror clocks are independent, while
both simulated seats and timing variants from a mirror
are correlated. The earlier exposed clocks remain a
separate result.

At the first difference between visible and guarded play,
the two simulated histories are identical. On the 62
complete first-difference windows where a reply had
already scored, skipping the jump improves the short
margin 13 times and worsens it nine. Across their tested
timing variants the signed sum is −12 points. The already
scored reply label alone does not tell us whether a jump
helps the next exchange. Start native regulation with
the simpler visible rule and score both seats against
plain W-tap before any integration.

[Fresh local cue results](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/prospective.json) ·
[Reply packet stage](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/prospective-inflight.json) ·
[New exact replay pairs](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/prospective-forks/summary.json) ·
[Fresh matched model scores and maintenance](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/model-prospective/score.json)

#### Native second-hurt jump regulation

Both arms run the same cue counter in the Forge client.
At the first grounded input head with the second visible
hurt and an applied own impulse, the candidate taps Jump.
The W-tap control records the same decision and keeps
its normal inputs. The two arms independently play
first-to-100 against plain W-tap, with two repeats in
each seat and each symmetric RTT. The simulator and the
native client share the decision code.

| Added RTT | W-tap scores in A and B, respectively | Second-hurt jump scores in A and B, respectively | W-tap wins / median margin [range] | Second-hurt jump wins / median margin [range] |
|---|---|---|---:|---:|
| 0 ms | 91–100, 100–94 / 68–100, 100–62 | 100–94, 98–100 / 97–100, 97–100 | 2/4 / −1.5 [−32, +38] | 1/4 / −2.5 [−3, +6] |
| 20 ms | 93–100, 100–59 / 100–98, 62–100 | 100–45, 100–95 / 99–100, 100–90 | 2/4 / −2.5 [−38, +41] | 3/4 / +7.5 [−1, +55] |
| 80 ms | 89–100, 99–100 / 100–59, 100–96 | 100–95, 100–91 / 98–100, 97–100 | 2/4 / +1.5 [−11, +41] | 2/4 / +1.5 [−3, +9] |

The table retains all scoreboards. A 5.629-second server
drain gap overlaps m7354, m7355 and m7356 in the early
schedule. The long gap delays actual attacks well outside
their intended latency comparison. A later 1.728-second
pause affects m7371 and m7372. The m7361 terminal input
trace has an unmatched attack. All six stay in the table
and are excluded from the timing-qualified comparison.
Independent native results reverse the seat direction of
the 80 ms model gain. Both native seat-A jumps win, and
both seat-B jumps lose.

| Added RTT, timing-qualified | W-tap wins / median margin [range] | Second-hurt jump wins / median margin [range] |
|---|---:|---:|
| 0 ms, two jump starts both in A | 2/3 / +6 [−32, +38] | 1/2 / +2 [−2, +6] |
| 20 ms | 1/3 / −7 [−38, +41] | 2/3 / +5 [−1, +55] |
| 80 ms | 1/3 / −1 [−11, +4] | 2/4 / +1.5 [−3, +9] |

Each arm has nine qualified bouts. Physical press medians
are 17.463/17.457 CPS in control bouts and 17.471/17.428
in jump bouts. The largest difference between active
players in a qualified bout is 0.246 CPS. The native
observer and offline reconstruction agree on all 87
eligible decisions. There are 48 logged jumps linked
to melee knockback, and zero jump presses in the control.
None of the 87 decisions occurs while the subject has
three or more current unanswered fresh hits in regulation.
The separate native maintenance drills remain a promotion
gate. The positive 20 ms margin comes primarily from
seat A. Across added RTTs and seats the live comparison
does not reproduce the model's decisive win rate.

[Frozen native schedule](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/native-selective/protocol.json) ·
[Native score, CPS and activation audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/native-selective/score.json)

The first three usable decisions per qualified bout give
49 exact isolated forks. Each compares from the same
body, replica, W-tap history, queues, and physical
presses. All qualified native branches reproduce the
recorded accepted hits and launch vectors. We keep the
1.7-second accepted score separate from an escape.

| Added RTT | W-tap accepted multiple incoming before reply | Isolated jump accepted multiple incoming before reply | W-tap versus isolated jump run length |
|---|---:|---:|---:|
| 0 ms | 18/126 | 9/126 | Jump shortens 18, extends 0 |
| 20 ms | 9/135 | 9/135 | Jump shortens 9, extends 0 |
| 80 ms | 9/180 | 9/180 | Jump shortens 9, extends 0 |

The four starts with a shorter incoming run at every
timing all come from the W-tap control arm. These are
different states from those visited in the jumping
bouts. In m7358 A at 10.55 seconds, W-tap takes four
hits from the anchor with no reply within the horizon;
the isolated jump replies in 649 ms, concedes two and
improves the short margin from −4 to −2. The median
short score change is zero at every RTT. Across the
441 dependent timing variants, 93 improve margin and
82 lose margin. Isolated escape effects are repeatable
in individual states. They do not explain a robust
full-bout advantage across seats.

The Forge archive was built and its local checks passed
in a separate package directory. The original live
bridge and server hashes were verified after restoration.

[Actual activation replay pairs and exclusions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/native-selective/isolated-activation-forks/summary.json)

### Predicting when an impact jump helps

We paired no jump and one jump before every incoming
fresh hit in 20 CPS W-tap mirrors, stopping enrollment
before either actor reached 85 points. The paired actors
shared their complete state, including contacts and queued
attacks before the cut. The first grounded decision after
receiving horizontal velocity used the bot's own motion,
replica movement, the received velocity and local cue
ages. The risk fit estimated whether W-tap would concede
at least two points before replying. The score fit
estimated the change in 1.7-second accepted margin.
The fitted feature scales, weights and thresholds came
from 12 development donors and froze before scoring 12
separate previously exposed donors.

On the comparison clocks there were 1,788 exact cuts,
with a grounded decision in 1,776. The risk forecast
ranked impending unanswered runs with 0.773 AUC.
At its fixed threshold, 164 of its 237 jumps were
cases where W-tap would have conceded two or more
points. Choosing that action still produced just two
net accepted points across the paired short windows.

| Comparison cuts | Plain W-tap | Always jump | Jump by predicted risk | Jump by predicted score | Jump with advance knowledge of a W-tap double |
|---|---:|---:|---:|---:|---:|
| Jump decisions | 0 | 1,776 | 237 | 195 | 573 |
| W-tap runs of two or more cut to one | 0 | 137 | 24 | 11 | 137 |
| W-tap single-hit runs made longer | 0 | 187 | 37 | 17 | 0 |
| Paired accepted margin gained in 1.7 s | 0 | +29 | +2 | +39 | +27 |

These overlapping windows show why the risk of a
combo and the consequence of a jump need separate
estimates. At 80 ms, even the hindsight risk rule lost
24 accepted points over its exact short windows.
The score rule gained ten while always jumping lost
16 on the same labeled cuts. A per-state hindsight
choice by positive score change could gain 336 over
all latencies, which describes the upper bound from
retrospectively choosing between the two actions.

| First-to-100 on the separate comparison clocks, wins / median margin | Plain W-tap | Always jump | Jump by predicted risk | Jump by predicted score |
|---|---:|---:|---:|---:|
| 0 ms | 27/54 / 0 | 41/54 / +3 | 24/54 / −1 | 31/54 / +2 |
| 20 ms | 45/90 / 0 | 55/90 / +2 | 44/90 / −1 | 51/90 / +1.5 |
| 80 ms | 36/72 / 0 | 52/72 / +5 | 32/72 / −1.5 | 49/72 / +4 |
| Pooled | 108/216 / 0 | 148/216 / +3 | 100/216 / −1 | 131/216 / +2 |

The score rule averaged 7.3 jumps per match against
72.3 for always jumping. It had nearly identical actual
click rates to W-tap: 16.6724 versus 16.6730 CPS pooled.
At 80 ms it gained 233 fresh hits and 46 upgrades across
the 72 related matches, versus 137 fresh hits and 176
upgrades for always jumping. Its double margin was +75
versus −42. The regulation grade still favored the
always-jump control. The phase pairs share source states,
and these matches were simulated.

We then checked the next grounded reaction on states
actually reached by repeated jumps. Each arm shared the
initial incoming hit and used the same policy after the
one forced or skipped decision.

| Source under always jumping | Exact first-impact forks | Continue W-tap from cut, 1.7 s margin | Jump next and resume | Skip next and resume | Jump minus skip, unanswered points conceded |
|---|---:|---:|---:|---:|---:|
| 0 ms | 444 | −117 | −62 | −108 | +9 |
| 20 ms | 738 | −191 | −134 | −184 | +8 |
| 80 ms | 525 | −106 | −107 | −100 | +19 |

Jumping at 0 and 20 ms added later score despite
slightly worsening the immediate loss count. At 80 ms
the next jump sacrificed both objectives on average.
The score rule's own state distribution also gave
similar numbers of cases where its skipped jump would
help or hurt at 80 ms, 104 and 92 respectively. Predicting
a likely second hit alone therefore cannot capture the
later exchange. Across both cohorts, all four arms had
identical accepted sequences up to the first return at
24 K3, 16 K5 and five K10 checkpoints. There was no
grounded impact decision before a return on these cuts.

[Paired risk and value tests](../../build/boxing-double-search/timing-transfer/jump-cue/short-results.json) ·
[Per-match scores, CPS and repeated-state attribution](../../build/boxing-double-search/timing-transfer/jump-cue/regulation-results.json)

### When W-tap committed the first return

The model trace now joins each accepted hit to the specific attack
packet the server processed, including when several attacks are
ready in one drain. The traced W-tap continuations reproduced the
same landmark times, return times and cycle counts as the matched
forks above.

| Added RTT | Shared K3 starts | Plain W-tap return click already consumed before the next selector input head | Plain W-tap return accepted before that head | Frozen movement additional fresh cycles |
|---|---:|---:|---:|---:|
| 0 ms | 2 | 1 | 1 | 0 |
| 20 ms | 0 | 0 | 0 | 0 |
| 80 ms | 5 | 4 | 2 | 0 |

Three of the five 80 ms W-tap return clicks were consumed 29–65 ms
before the server accepted the third hit. One landed in the same
server drain as that third hit. In five of the seven matched
checkpoints the opponent had already found a valid client ray by
the next selector input head. Three returns were also accepted by
then, before the movement policy could issue a new input. The
other four still need their own diagnosis. The earliest three
cases motivate testing reach denial before the landmark while
keeping the same landmark for measuring maintenance.

[Packet links, client rays and per-start matched outcomes](../../build/boxing-double-search/timing-transfer/incumbent-model/k3-packets.json)

### Which later returns movement could change

Four of the seven exact K3 starts had a selector input head before
W-tap's return was accepted. At each input head before those
returns, the model tried every three-head movement sequence over
the tested forward, backward and lateral inputs. The W-tap click
gate, aim and jump inputs remained fixed. Each candidate shared
the full W-tap prefix and queues at its input cut. We replayed
nominal gains under eight more own and defender phase settings,
with a new matched W-tap control at each timing.

| Clock and seat | RTT | Plain W-tap nominal K3 cycles / 2.2 s margin | Movement sequences tried | Added cycles at all nine timings | Improved margin at all nine timings |
|---|---:|---:|---:|---:|---:|
| m5509 A | 0 ms | 0 / 0 | 13,104 | 38 | 0 |
| m5498 A | 80 ms | 0 / −1 | 1,456 | 0 | 0 |
| m5502 A | 80 ms | 0 / −1 | 1,456 | 0 | 0 |
| m5505 A | 80 ms | 0 / −1 | 728 | 0 | 39 |

In m5509 A, two backsteps and one released tick starting 220.3 ms
after K3 made the opponent's 859.5 ms click miss. Its nearest
distance at that click changed from 2.930 to 4.211 blocks, and
it missed its next two clicks. Our side's first contact shifted
from 872.8 to 964.8 ms. It landed the fourth fresh hit at
999.3 ms, followed by a return at 1,099.4 ms. The unchanged
W-tap arm had lost the ordering within the 900.3 ms server drain.
The 38 sequences added one to three active cycles across the phase
sweep, with some returns censored at 2.2 s. All had a tied nominal
short score margin and lost one accepted hit in the worst phase.
The nominal fourth hit was late in the activity window.

In m5505 A, one backstep followed by sprinting improved the
2.2-second margin by one to three hits across the sweep. The
first return still landed at 52.3 ms on the nominal timing, from
a contact made before K3. The nominal gain came from an extra
damage upgrade during the later exchanges. This result covers
one development clock and leaves the maintenance comparison tied.

In a separate offline intervention the model removed only the
specific packet that caused the first return, immediately before
its server drain. That preserved the opponent's local contact drag
and all earlier input. The removal opened one additional fresh
cycle in two of six checkpoints. In m5509 A both sides still
landed in the 900.3 ms drain, with our own hit processed first
after the earlier attack packet was removed. In m5502 A our fourth
hit preceded the next return at 504.3 ms. There the return click
was made 64.9 ms after K3, and the best post-K3 movement trace
still gave W-tap the same 2.870-block nearest distance at that
click. In m5498 A another queued attack replaced the removed
packet in the same drain. Three other removals only delayed the
return. The seventh return was already accepted in the K3 drain.
The packet intervention is a mechanism check. The movement
comparisons are model-only and these clocks are development data.

[Frozen procedure](../../build/boxing-double-search/timing-transfer/late-returns/protocol.json) ·
[Verified outcomes and removed packet IDs](../../build/boxing-double-search/timing-transfer/late-returns/summary.json) ·
[Click and movement trace at 0 ms](../../build/boxing-double-search/timing-transfer/late-returns/0.jsonl) ·
[Click and movement traces at 80 ms](../../build/boxing-double-search/timing-transfer/late-returns/80.jsonl)

### Reaching the return window from the second hit

The 80 ms m5502 A follow-up forked the exact state after its
second unanswered hit. Both sides kept the same aim and capped
click processing. Of 8,008 three-head sequences, 868 reached
at least a fourth hit and extended the active chain beyond
matched W-tap at every one of the nine tested timings. Of
those, 198 also improved the 2.2-second accepted margin at
every timing. These are variations of a single development
clock and start.

| Same-complete-state K2 fork at 80 ms | Plain W-tap across nine timings | Back, sprint-left, walk across nine timings |
|---|---:|---:|
| Further unanswered fresh hits | 1 | 3–4 |
| Accepted-hit margin over 2.2 s | −1 to +1 | +3 to +4 |
| First return | 551.7–595.7 ms after K2 | Censored at 2,200 ms in all nine |

The paired score gain was +3 to +4. On the nominal clock the
sequence began 56.6 ms after the second hit, 15.2 ms after
the selector had processed the opponent's hurt cue. The
selector still landed the third hit in the same server drain
at +444.1 ms. W-tap's return click at +508.9 ms then missed
from 3.115 blocks instead of connecting from 2.870 blocks
in the control. This left the selector inside range on its
next recorded click at +524.4 ms, where it was still waiting
for the opponent's immunity to expire. The fourth fresh hit
landed at +895.9 ms.

The input word was selected by hindsight and evaluated from
the already identified second hit. The nominal hurt cue provides
a candidate observable trigger for a separate test. Transfer
across independent clocks and the native K3 comparison remain
open. This is an established-combo continuation example. It
does not establish opening hit-selection reliability.

[Exact K2 procedure](../../build/boxing-double-search/timing-transfer/late-returns/k2-protocol.json) ·
[Matched nine-phase margins and exposure](../../build/boxing-double-search/timing-transfer/late-returns/k2-summary.json) ·
[Input, click, and packet trace](../../build/boxing-double-search/timing-transfer/late-returns/k2-m5502-a.jsonl)

### Starting the footwork from the local hurt cue

We kept the movement sequence fixed and tested three triggers.
The offline trigger used the next local hurt cue after the
actual second hit. The local tracker counted consecutive
opponent hurt cues with a recent contact, resetting on an
own hurt cue or a long gap. The geometry rule also checked
our ground and sprint state and the opponent replica's
height and distance at the trigger. The local trackers were
primed on the identical W-tap prefix for each K2 fork.

The local tracker started the inputs on the demonstrated head
in all nine timings of m5502 A. When shifting the schedule from
the exact hit acceptance, it reproduced every outcome from
the previous search. We also shifted the same scenarios from
the end of the landmark server tick, 2.754 ms later. At that
cut the unshifted path still reproduced, and the sequence
still prolonged the chain in all nine cases. The short score
gain ranged from −2 to +4, versus +3 to +4 with the earlier
cut. Committing the intervening events before varying future
timing changes the stress test. These are two sensitivity
panels on the same start.

| Trigger on seven comparison clocks | Plain W-tap margin change | Mean margin change at acceptance cut | Mean margin change at tick-end cut | Fourth-hit gains at all nine timings |
|---|---:|---:|---:|---:|
| Exact K2 plus target hurt cue | 0 | −0.121 | −0.049 | 0 |
| Local cue counter | 0 | −0.087 | −0.067 | 0 |
| Local cue counter and geometry | 0 | 0 | 0 | 0 |

The comparison has 45 K2 starts and 405 variants per cut.
The table weights each clock equally. At the acceptance cut,
the local trigger fired before the W-tap return in 63 cases
and after it in 68. It gained a cycle in three variants of a
single m5510 B start, reaching K3 in each; it lost cycles in
ten. The geometry rule fired before the return in zero cases.
Even the trigger given the true K2 landmark could neither
reliably extend these starts nor improve their aggregate margin.
This screen covers previously exposed clocks and a one-shot
branch from K2. It supplies no native promotion result.

[Frozen cohort and cues](../../build/boxing-double-search/timing-transfer/second-hit-cue/protocol.json) ·
[Per-clock margins and exact witness](../../build/boxing-double-search/timing-transfer/second-hit-cue/summary.json)

### How much three inputs could change across second-hit starts

The bounded search let each of the 53 exact K2 checkpoints
choose its own three-head movement word. In each state it tried
all 728 non-default words from each of the first three input
heads before the original return. There were 40,768 nominal
trials in total. Both arms shared the same complete start and
queues, with W-tap click pacing, aim and jump inputs at matched
20 CPS and 80 ms added RTT. Chosen words had to improve on their
paired W-tap control on all nine timings. This is a per-state
model search across previously exposed development clocks.

| K2 state subset | Starts | Plain W-tap activity-qualified cycles at nominal timing | Starts with an available input head before the return | Starts with a word adding cycles and score in all nine timings |
|---|---:|---:|---:|---:|
| All eight clocks, seat A | 33 | 16 | 20 | 5 |
| All eight clocks, seat B | 20 | 1 | 10 | 0 |
| Seven clocks outside m5502, both seats | 45 | 16 | 27 | 4 |

Of the 53 starts, 22 already included a return in the K2 server
drain. One further return beat the next selector input head.
In another 16 starts the actual return packet was already in the
queue at K2, though it had yet to score. Where it could be
removed in the simulator, dropping just the first return packet
improved the active cycle count in two starts. This leaves a
substantial difference between making a single attack miss and
creating a usable fresh-hit window.

Out of the 43 starts where W-tap failed to get a third active
hit, none had a word that extended the combo to K4 at every
timing. The five joint gains all came from starts where W-tap
already reached its third hit. Four of those gains came from
two clocks besides m5502 and all five kept the nominal K3
acceptance time unchanged. Their best paired short
score improvements ranged from +1 to +4. One additional start,
m5505 A, had reliable short score gains without an added cycle.

Two examples show how the spacing changes worked. In m4874 A at
2,981.541 ms, two sprint-left inputs moved the fourth hit from
1,253.6 to 902.9 ms after K2. The defender's later click at
1,214.2 ms missed from 4.504 blocks, where it had connected from
2.600 under W-tap. In m4886 A at 10,281.160 ms, three sprint-left
inputs left K3 and K4 on the same drains and moved K5 from
1,692.2 to 1,347.6 ms. The defender's later click at 1,656.4 ms
missed from 5.095 blocks, up from 2.773. Other winners used
straight sprint or the earlier backstep. Stress-tested
improvements from two clocks besides m5502 establish useful
case diversity while leaving the search unqualified for
independent native evaluation.

[Frozen search scope](../../build/boxing-double-search/timing-transfer/second-hit-envelope/protocol.json) ·
[Per-state phase comparisons](../../build/boxing-double-search/timing-transfer/second-hit-envelope/summary.json)

### Changing movement before the second-hit trade

We reconstructed the previous first hit for each of the 22
model states where W-tap's return landed in the same server
drain as hit two. The movement forks shared that exact first-hit
state at 80 ms and 20 CPS. They tried all 728 non-default
three-head words starting at any selector input head before
either original attack had been consumed. W-tap aim, click
pacing and jump inputs stayed fixed.

| Result across 22 exact K1 starts | Plain W-tap nominal | Per-state movement search |
|---|---:|---:|
| Starts reaching three active unanswered fresh hits | 0 | 4 nominal, 1 with a gain at all nine timings |
| Starts with a score improvement across all nine timings | 0 | 10 |
| Starts with both a chain and score improvement across all nine timings | 0 | 1 |

The 247,520 nominal movement trials produced 49 joint-winning
words on that one start, m5510 A. Across all 22 controls, W-tap
had already clicked 43.2–84.3 ms before the server accepted hit
two. W-tap clicked 1.4–44.3 ms after the selector's hit-two
click. Both attack packets were ready in the same drain.
Dropping just the return packet gave zero additional active
cycles in these starts. It left the preceding pursuit and the
opponent's original local contact unchanged.

In m5510 A, sprint-left, sprint-left, sprint starting at
the first selector head made its +380.6 ms click connect
from 2.562 blocks. Plain W-tap missed this click from 3.520.
Hit two advanced from +898.4 to +444.1 ms after K1, close
to the next fresh window. The hit at +898.4 ms became hit
three. W-tap's original return click at +836.8 ms missed
from 4.641 blocks where it had connected from 2.816.
Across the nine timings the word gave one to four additional
active cycles and a paired score gain of +1 to +4 over 2.2 s.
Six of nine returns were censored. The first movement began
26.4 ms after K1, before the new target hurt cue was visible
on the nominal clock. This is an offline example of changing
both pursuit and the timing of the follow-up.

The native replay audit covered all 48 bouts of the completed
incumbent repeat. On every one of the 225 same-tick returns,
the defender's click was already consumed by the time hit
two accepted. Both clicks were linked in all 225 episodes.
The counts describe independently played bouts.

| Native sample | Plain W-tap same-tick returns / K2 entries | Frozen movement same-tick returns / K2 entries |
|---|---:|---:|
| First-to-100 regulation | 97 / 234 | 87 / 216 |
| Separate K3 maintenance bouts, all K2 entries | 22 / 55 | 19 / 50 |

The maintenance bouts used the shared W-tap approach until
K3, and the counts cover every K2 entry across the bout.
These rates are descriptive and include independent starts,
some of which occurred after the learned movement engaged.

[Frozen search and native audit definitions](../../build/boxing-double-search/timing-transfer/before-hit-two/protocol.json) ·
[Model scores and packet times](../../build/boxing-double-search/timing-transfer/before-hit-two/summary.json) ·
[Native same-tick packet links](../../build/boxing-double-search/timing-transfer/before-hit-two/native-audit.json)

### How the 13-hit native combo ended

In the independent 20 ms maintenance bout m5781, seat B reached
K3 with ordinary W-tap, then the frozen movement added ten
fresh hits over 5.550 seconds. The recorded first-to-last chain
contains 13 fresh hits after both players had started clicking.
Hits two through twelve were 447–602 ms apart. Every hit sent
the defender the same velocity along the lane, −0.854375
horizontal and +0.361375 vertical blocks per tick.

The twelfth hit accepted at 7,976 ms. Four later player
contacts were immune. The following 11 player clicks missed
as the nearest ray distance grew to 4.325 blocks and closed
again. The bot held forward on all 19 movement updates in
the 951 ms gap. It finally clicked from 2.701 blocks. W-tap
clicked from 2.571 blocks 13.96 ms later, before its client
could respond to the thirteenth knockback. The same server
tick accepted both hits 0.756 ms apart. The combination of
the long re-entry and the close final exchange explains the
break in this particular run. Its result remains one native
maintenance entry with its own acquisition state.

[Ordered hits, velocity receipts and click trace](../../build/boxing-double-search/timing-transfer/before-hit-two/native-m5781-chain.json)

### How the 52-hit W-release combo ended

The separately registered `v4-reset-regulation-student` plays a fixed
W-release schedule from its own observed target hurt cue and distance. It
keeps ordinary clicks enabled and asks for sprint when advancing. The rule
controls inputs from the opening. These independent seat-A native bouts
all use the same recorded MMC profile, 3-block reach and 20 CPS cap.

| Added RTT per player | W-release score vs plain W-tap | Seat-A plain W-tap mirror | W-release / mirror A longest fresh chain before return |
|---|---:|---:|---:|
| 0 ms | 93–100 (m6968) | 100–67 (m6965) | 2 / 34 |
| 80 ms | 100–3 (m6980) | 71–100 (m6962) | 52 / 17 |

In m6980, the physical audit finds 896 candidate presses and 891 W-tap
presses. The 52-fresh-hit chain spans 9.336 to 35.338 seconds. From the
first hit to the return, 459 of 460 W-tap consumed clicks have an eye-to-
expanded-hitbox minimum distance greater than three blocks. W-tap takes
lift and horizontal velocity at 35.392 seconds. At 35.412 it clicks from
a 2.977-block ray before its first travel update has applied the movement.
The packet reaches the server 40 ms after the click and is accepted at
35.479. The next W-tap click misses after travel has widened the gap.
The candidate selects W-tap on 452 of 459 clicks during the same span.
This is one observed long combo and one clear break in reach denial. The
0 ms loss shows that the 80 ms score does not generalize across these two
fixtures. The remaining registered seats and repeats are needed for a
native comparison across the tested spread.

[m6980 score and physical-input audit](../../build/elite-league/recorded-mmc/v4-box-reset/scores/m6980.json) ·
[W-release rules and registration](../mmc-knockback/boxing_reset.py) ·
[Native W-tap mirror](../../runtime/results/m6962.json)

### Comparing the W-release pilot on common clocks

Across the five already inspected native pilot calendars, replay the two
frozen W-release rules and plain W-tap at their recorded RTT. Each calendar
contributes both seats, with the original phase and four ±5 ms variants.
Every arm sees the same clock, transport samples and physical click times
within a fixture. None reaches 100 before the common 45-second cutoff. The
table uses mean accepted-hit margin and the spread of paired differences,
over these correlated development variants.

| Added RTT / seat | Variants | Plain W-tap mean | Distance-based release mean | Paired change (min to max) | Constant release mean |
|---|---:|---:|---:|---:|---:|
| 0 ms / A | 15 | +11.07 | −5.67 | −16.73 (−84 to +9) | +27.73 |
| 0 ms / B | 15 | −11.07 | −6.33 | +4.73 (−48 to +82) | −8.40 |
| 80 ms / A | 10 | +1.60 | +30.40 | +28.80 (−86 to +104) | −12.00 |
| 80 ms / B | 10 | −1.60 | +0.80 | +2.40 (−32 to +73) | −10.00 |

In the nominal m6980 replay, the first nine accepted attacks agree in order,
side, fresh or upgrade, and server tick. The predicted tenth lands one
server tick later than the recorded one. By 45 seconds the same policy
scores 80–30 in the model versus 88–3 in Minecraft. W-tap scores 77–46 in
the same-clock model. On m6962, shifting only the tested side by +5 ms
moves the distance-based arm from 85–46 to 9–89. The recorded W-tap suite
also contains a 45-fresh-hit chain in m6977 without either release rule.

In 20-second maintenance forks after common W-tap establishment, the
distance-based release completes 199 K3 cycles at 0 ms versus 586 for
W-tap across 30 variants. The constant release completes 414. At 80 ms,
the distance-based release exactly preserves both survival endpoints in
59 of 60 K3/K5/K10 variants, including duration and censoring. The
distance-based arm totals the same 416 K3 cycles as W-tap. These matched
starts support studying the rule's effect on how the combo begins without
attributing the 52-hit native streak to better maintenance. Final native
validation uses independent starts and a separate 20 ms panel.

[Common-clock runs, exact packet trace and maintenance endpoints](../../build/boxing-double-search/v4-reset-confirmation/pilot-audit.json) ·
[Frozen 72-fixture native confirmation](../../build/boxing-double-search/v4-reset-confirmation/input-protocol.json)

### The first three new W-release regulation replays

Both clients pass the movement binding audit in the 100–99
win at 0 ms, the 100–48 win at 20 ms and the 100–45 win at
80 ms. All three are seat A. The tested and opposing measured
click rates are 17.53/17.52, 17.51/17.44 and 17.51/17.57.
The 80 ms seat-A W-tap mirrors are 71–100 and 100–96. The
20 ms cell does not yet have a completed seat-A mirror.
Keep both seats and the remaining scheduled matches in the
comparison before ranking the rule.

The first 33-hit run in m7152 starts at 13.679 seconds.
The candidate received 0.5275 blocks/tick horizontally from
W-tap's earlier normal hit and braked it to 0.3165 before
its first movement. It traveled back 0.1891 blocks. W-tap
received a sprint launch of 0.9495 at the start of the
streak and moved back 0.8221 blocks on its first step.
W-tap's first 17 consumed clicks up to the third fresh
hit all missed. A second 24-hit streak starts at 43.575
seconds. The prior launches again differ, but both players
brake before their first movement. The two sequences show
different launch and brake combinations in real play.
They do not isolate the distance-based W-release policy's
contribution from those complete native states.

[Verified click rates, run starts and motion traces](../../build/boxing-double-search/v4-reset-confirmation/interim-long-starts.json) ·
[Frozen native control plan](../../build/boxing-double-search/v4-reset-confirmation/input-protocol.json)

### Completed W-release regulation and maintenance comparison

All 72 native fixtures pass the physical-input audit on
their first recorded attempts. Regulation uses the distance-based
release. The separately frozen maintenance contestant uses a
105 ms release after the observed hurt cue, enabled only at
the declared server landmark. Both use a 20 CPS cap and 3-block
reach. The table lists scores from the nominated seat, including
the original pilot fixtures.

| RTT / seat | Plain W-tap mirrors | Distance-based W-release vs W-tap |
|---|---|---|
| 0 ms / A | 100–67 (m6965), 100–74 (m7179) | 93–100 (m6968), 100–99 (m7149) |
| 0 ms / B | 100–56 (m6977), 100–93 (m7142) | 86–100 (m7170), 99–100 (m7191) |
| 20 ms / A | 100–5 (m7185), 100–95 (m7187) | 100–48 (m7152), 95–100 (m7183) |
| 20 ms / B | 100–92 (m7141), 100–75 (m7153) | 99–100 (m7163), 100–98 (m7194) |
| 80 ms / A | 71–100 (m6962), 100–96 (m7140) | 100–3 (m6980), 100–45 (m7150) |
| 80 ms / B | 28–100 (m7143), 29–100 (m7198) | 95–100 (m7171), 87–100 (m7175) |

| Added RTT | Plain W-tap wins and mean / median margin | W-release wins and mean / median margin | W-release CPS, bot / opponent |
|---|---:|---:|---:|
| 0 ms | 4/4, +27.50 / +29.5 | 1/4, −5.25 / −4 | 17.50 / 17.45 |
| 20 ms | 4/4, +33.25 / +16.5 | 2/4, +12.00 / +0.5 | 17.49 / 17.46 |
| 80 ms | 1/4, −42.00 / −50 | 2/4, +33.50 / +25 | 17.55 / 17.60 |

At 80 ms both rules score differently by seat. The nominated
seat-B W-tap mirrors lose by 71 and 72, so their independent
repeat spread matters even though W-release also loses twice
from that seat. Removing the earlier inspected 100–3 leaves
the 80 ms candidate with one win in three, a mean of +12.33
and median −5. The actual click rates remain close. All match
margins, fresh and upgrade counts, doubles and rates are in
the scorecard.

| Native handoff | Plain W-tap entries / cycles / returns / censored | 105 ms release entries / cycles / returns / censored | W-tap / release exposure |
|---:|---:|---:|---:|
| K3 | 10 / 206 / 5 / 5 | 8 / 111 / 7 / 1 | 108.569 / 59.478 s |
| K5 | 4 / 46 / 4 / 0 | 5 / 54 / 5 / 0 | 24.950 / 29.038 s |
| K10 | 4 / 58 / 2 / 2 | 4 / 57 / 3 / 1 | 30.824 / 30.327 s |

Both maintenance endpoints agree in this cohort. Every frozen
handoff agrees with the independent server scoring boundary.
At K3 the 105 ms rule changes 86 longitudinal inputs before
the first return or censor across seven drills, against
ordinary W-tap evaluated on the captured client cues. The
drills have independent acquisition timing and states. The
raw cycle totals do not hold establishment state fixed.

[Native scorecard and each continuation](../../build/boxing-double-search/v4-reset-confirmation/native-scorecard.json) ·
[Complete physical audit](../../build/boxing-double-search/v4-reset-confirmation/input-audit.json)

### Which clock and movement changes account for the reset result

Eight additional W-tap mirror clocks qualify for the unchanged
model transfer. One other 20 ms recording lacks verified
movement-flush order and remains excluded from the model
panel. Its native score stays in the native table. Three
previously inspected calendars are tabulated separately in
the model artifact. Each new clock contributes both seats
and five related timing variants. Regulation has a 45-second
observation horizon. Maintenance forks the same complete
state, queues, clock and primed observation history, then
uses the 20-second horizon.

| Added RTT | Plain W-tap regulation change / K3 cycles | Distance release paired change / K3 cycles | 105 ms paired change / K3 cycles |
|---|---:|---:|---:|
| 0 ms | 0 / 122 | −1.05 / 85 | +5.70 / 178 |
| 20 ms | 0 / 412 | −4.23 / 370 | −3.67 / 432 |
| 80 ms | 0 / 311 | +6.90 / 311 | −25.67 / 312 |

The 80 ms distance-based rule gains +31.33 from seat A
and loses 17.53 from seat B on the original three new
calendars. Swapping only the two clients' input calendars
on the same server reduces the A gain to −2.07 and
takes B to +16.47. Swapping only the wire residuals stays
near the original split. Swapping tracker order gives
the same results. The full rotation shifts both advantages
toward B. This is evidence that the model's seat gap is
sensitive to the client tick schedule. It does not identify
the causes of every score in the independent native bouts.

Near the start of each of the two long runs in m7152 and
the two in m7150, the earlier recorded movement can be
retained and the subsequent movement replaced with
primed W-tap. The nominal short scores are identical
despite changes to some movement heads. The W-tap shadow
reproduces all keys and outcomes on six nominal cuts in
two separate W-tap mirrors. The exact-replay branches
hold click timestamps and aim fixed, and reach conclusions
only from the copied state onward.

In the m7171 loss the bot advances just after the defender
lands its first sprint hit. At its next contact it brakes
the resulting recoil from 0.4489 to 0.2693 blocks/tick.
Plain W-tap releases for that movement step, then resumes
sprint on the next step. Changing only those two inputs
turns a 0–4 stretch into 1–1 at recorded delivery timing.
The single release or removing only the extra brake also
helps, without reaching the same 1–1 result. The original
branch wins the comparison after a +0.5 ms timing shift.
This is a case where holding forward and braking closes
space the defender can use, so always seeking an extra
brake would lose useful W-tap behavior.

In the nominal 20 ms m7185 B maintenance fork, the
distance-based and 105 ms rules both miss W-tap's next
contact opportunity. At 1.043 seconds after K3, W-tap
contacts at a 2.867-block minimum gap. The longer releases
see gaps of 3.777 and 3.067. The latter rules add two
fresh hits before the return, versus 24 for W-tap. At
m7153 B, the longer release instead puts the defender's
return click outside reach while keeping its own attack.
W-tap takes the return after five additional hits. The
two longer-release arms reach 30 and 31. Longer releases
can protect spacing or miss the next hit. Avoid applying
an unconditional longer release based on a single good
streak.

[New model clocks and matched maintenance](../../build/boxing-double-search/v4-reset-confirmation/mirror-transfer/summary.json) ·
[Seat-timing rotation](../../build/boxing-double-search/v4-reset-confirmation/seat-rotation/summary.json) ·
[Strict native start forks](../../build/boxing-double-search/v4-reset-confirmation/start-forks/summary.json) ·
[m7171 one-contact test](../../build/boxing-double-search/v4-reset-confirmation/start-forks/m7171-one-move-and-brake.json) ·
[m7171 two-input replay](../../build/boxing-double-search/v4-reset-confirmation/start-forks/m7171-two-tick-wtap.jsonl) ·
[m7185 exact K3 trace](../../build/boxing-double-search/v4-reset-confirmation/landmark-traces/m7185-b.json) ·
[m7153 exact K3 trace](../../build/boxing-double-search/v4-reset-confirmation/landmark-traces/m7153-b.json)

### First release and reach at the next fresh window

The model copies the complete W-tap state and queues at the
first release or resumption after a target hurt cue. It flips
one movement input and resumes W-tap on the following input.
The regulation roots are early enough to allow a 2.2-second
observation window from the initiating hit. The maintenance
roots use the first post-wake K3. Five development recordings
and three separate recordings supply the timing calendars.
All eight were exposed in earlier work. Both arms deliver
the same number of physical presses on every cut and phase.

A protected follow-up lands in the first or second native
fresh-eligible drain and avoids an accepted return in that
drain and for the following 500 ms. These are short model
diagnostics. The maintenance counts below are limited to
the same 2.2-second observation window.

| Separate panel, nominal receipt timing | Cuts | Plain W-tap | One-input change |
|---|---:|---:|---:|
| Regulation, skip release, timely hits / protected hits | 38 | 10 / 1 | 14 / 3 |
| Regulation, extend release, timely hits / protected hits | 32 | 8 / 1 | 7 / 1 |
| K3, skip release, further active cycles | 3 | 11 | 6 |
| K3, extend release, further active cycles | 3 | 11 | 11 |

On the development K3 cuts, skipping and extending the
release complete 13 and 24 active cycles versus 26 for
plain W-tap on the same seven starts. In separate regulation
cuts, skipping the release creates two new protected hits
in all five attack-receipt variants. One of those two also
survives all four additional position-receipt variants.
Perturbations shift only new packet receipts by 2 ms in
either direction on one leg at a time. The experiment did
not combine the two packet classes in the same shift.

Both 80 ms witnesses, m7143 A in development and m7198 A
in the separate panel, see about 1.2 blocks of local gap
while grounded and moving backward at 0.9495 blocks per
tick. The changed input reduces the first retreat to 0.8221.
It also restores sprint for the subsequent contact, which
brakes the recoil. In m7198 the next fresh hit moves from
about 955 ms, with a same-drain return, to about 506 ms
after the anchor. The first return is beyond the 2.2-second
horizon in the changed arm. The other separate attack-phase
witness, m7142 B at 0 ms, loses its protection when new
position updates to the defender are 2 ms later.

An observation-only check uses native W-tap mirror captures.
The target is the distance to the displayed expanded box in
the last own client sample before the first fresh-eligible
server drain. It is a distance sample, with no claim about a
future aim ray. At W-tap's first resume input, the latest
observed gap classifies 213 of 479 samples correctly at the
3-block threshold. A fixed closing-speed extrapolation gets
363. A ridge fit using local status and movement history,
trained only on the five development calendars, gets 447.
For the 66 comparison samples within 0.35 blocks of the
threshold, the counts are 36, 35 and 41. This final group is
where the prediction has to distinguish the reach boundary.
No movement policy is selected or promoted by this fit.

[Model ledger and counts](../../build/boxing-double-search/v4-reset-confirmation/release-window/summary.json) ·
[Independent receipt variants](../../build/boxing-double-search/v4-reset-confirmation/release-window/position-protocol.json) ·
[m7143 contact trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/witness-m7143.json) ·
[m7198 contact trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/witness-m7198.json) ·
[Native observation forecast](../../build/boxing-double-search/v4-reset-confirmation/release-window/reach-fit.json) ·
[Model diagnostic](BoxingReleaseWindow.java)

### Walking or sprinting before the release

Six alternatives change at most the first two movement
inputs at the stock W-tap release after a local target hurt
cue. The cut includes the complete state and queued packets.
W-tap resumes on every later input. The receipt panel takes
all nine combinations of a −2, 0 or +2 ms shift for each
client, with two directions for the position shifts relative
to the attack shifts. The shared nominal case appears once.
Across 117 cuts and 17 receipt variants, all 11,934 candidate
comparisons use identical physical click timestamps to
their W-tap controls during the first 2.2 seconds.

| Separate regulation panel, nominal timing, 38 starts | Plain W-tap | Walk first | Sprint first | Sprint, then release |
|---|---:|---:|---:|---:|
| Timely follow-up hits | 10 | 11 | 14 | 12 |
| Protected follow-up hits | 1 | 2 | 3 | 3 |
| Mean paired accepted score change | 0 | +0.16 | +0.11 | +0.21 |
| Starts with a new protected follow-up at every timing | 0 | 1 | 1 | 1 |

Sprint-first and sprint-then-release protect the same
separate witness, m7198 A at 80 ms. Both permit the next
click to brake horizontal recoil from 0.449 to 0.269
blocks/tick. The follow-up is sent from an aimed distance
of 2.847 blocks before the first fresh server drain at
506 ms. Its initial outward launch of 0.527 blocks/tick
is weaker than W-tap's 0.950, which lands at 955 ms in the
same drain as a return. The earlier hit buys enough time
for continued ordinary W-tap.

In m7142 B at 0 ms, a walk input also moves the first
step 0.025 blocks toward the target. That step leaves
sprint off. Sprinting produces the same first step and
adds a brake on the next click. The walk branch reaches
its second fresh hit at 549 ms and lands four additional
fresh hits before the 2.2-second cutoff. Its paired lead
of three over that interval does not carry to the full
score. Short-window protected hits need a regulation check.

| Full modeled first-to-100, 17 receipt variants per selected cut | Plain W-tap wins, median margin and range | One-time change wins, median margin and range | Paired change median and range |
|---|---|---|---|
| m7142 B, 0 ms, walk first | 11, +4 [−32, +30] | 4, −6 [−43, +18] | −8 [−65, +43] |
| m7143 A, 80 ms, sprint first | 9, +3 [−12, +48] | 15, +20 [−4, +53] | +9 [−41, +62] |
| m7198 A, 80 ms, sprint first | 14, +21 [−19, +33] | 17, +94 [+94, +94] | +73 [+61, +113] |

All selected model bouts finish. Physical click timestamps
match by seat through the earlier finish in all 51 paired
comparisons. At nominal timing m7198 A scores 100–6 after
the change versus 100–79 for its same-cut control. The
winning branch measures 17.63 / 17.40 suffix CPS and gets
94 consecutive unanswered fresh hits after the cut in all
17 phases. The control's longest run ranges from two to
35. The phases reuse one opening and one host timing
recording. The native mirror was a separate measurement.

| Exact maintenance cuts, full available 20-second drill | Cuts across both panels | Plain W-tap further active cycles | Walk first | Sprint first | Sprint, then release | Release twice |
|---|---:|---:|---:|---:|---:|---:|
| K3 | 10 | 129 | 126 | 50 | 126 | 129 |
| K5 | 11 | 137 | 124 | 72 | 137 | 139 |
| K10 | 7 | 109 | 109 | 32 | 109 | 109 |

The nominal return-only counts equal the active counts.
Across the full receipt panel no alternative adds active
cycles on every phase of any K3 or K10 cut. One development
K5 start, m7140 B, gains one or two active cycles across
all 17 phases with a walk or sprint input. The own player
already moves toward an airborne defender at the cut. The
extra pursuit advances the next hit from 1,040 to 490 ms
at nominal timing, and both variants give a lower full
drill score than W-tap on that timing. The maintenance
evidence leaves plain W-tap as the reference.

[Registered split, inputs and timing](../../build/boxing-double-search/v4-reset-confirmation/release-window/reset-order/protocol.json) ·
[All short and maintenance comparisons](../../build/boxing-double-search/v4-reset-confirmation/release-window/reset-order/summary.json) ·
[m7142 full-score and click trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/reset-order/witness-m7142.json) ·
[m7143 full-score and click trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/reset-order/witness-m7143.json) ·
[m7198 full-score and click trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/reset-order/witness-m7198.json) ·
[m7140 maintenance trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/reset-order/witness-m7140.json)

### Prospective test of the grounded recoil rule

The rule was frozen before collecting 12 native first-to-100
W-tap mirrors at 0, 20 and 80 ms added RTT. At the first
stock release for the newest target hurt cue, it keeps W
and Ctrl for one input if the local player is grounded
with sprint off, moving backward at least 0.80 blocks/tick
along the target direction, and sees a gap at most 1.30.
Both hurt cues must be within 16 ms of each other and the
target cue at most 75 ms old. This is the model rule.
The native controls stayed on ordinary W-tap.

All 12 native fixtures finished and passed physical-input
audits. Reconstructing the rule on their actual client
observations finds 0, 3 and 26 eligible inputs at 0, 20 and
80 ms. The native audit joins the stock release to the
latest newly received status. The W-tap log timestamps
the sampled cue independently of the packet's arrival.
The initial audit required those times to be identical and
is retained separately. Three longer recordings required
following the validated continuation segments. One 80 ms
mirror, m7206, remains outside the model panel because
the movement send does not follow its movement step.

| Nominal modeled first-to-100 at 80 ms | Plain W-tap | Grounded recoil rule | Paired change |
|---|---:|---:|---:|
| m7203 A | 88–100 | 100–28 | +84 |
| m7203 B | 100–88 | 83–100 | −29 |
| m7207 A | 93–100 | 100–94 | +13 |
| m7207 B | 100–93 | 85–100 | −22 |
| m7209 A | 79–100 | 53–100 | −26 |
| m7209 B | 100–79 | 100–74 | +5 |

Both arms have three wins. The median paired change is
−8.5 with a range of −29 to +84. The median over all
30 paired 80 ms timing variants is +14.5 with a range
of −38 to +99. The variants reuse the three donor clocks.
The 0 and 20 ms nominal runs have identical scores in
both arms. All 110 model bout pairs finish. Physical
click timestamps match by seat to the earlier finish
throughout the test. At 80 ms the average nominal gate
versus defender input rates are 17.56 and 17.56 CPS.

The frozen rule admits 24 exact W-tap cuts. Holding
sprint advances our next fresh hit in all 24 nominal
cuts. It advances the defender's return in nine, and
in seven of those cases the return scores before our
new follow-up. Six of the nine earlier returns are
damage upgrades. Three cuts add a timely protected hit
over W-tap at all 17 receipt phases. This is also true
of three of 60 wider heavy-recoil cuts outside the
gate. Click timestamps match in every short pair.

In the m7209 A loss, the defender's same physical click
at +379 ms after the anchor misses from a 3.331-block
ray under W-tap. Holding sprint brings that ray inside
reach at 2.979 blocks. The return accepts as an upgrade
at +451 ms, before the accelerated own fresh hit at
+552 ms. W-tap accepts its first return at +799 ms.
The candidate's earlier own window by itself is an
inadequate test of the spacing decision.

| Model maintenance landmark and phase pairs | Plain W-tap cycles / accepted returns / censored | Grounded recoil rule cycles / accepted returns / censored |
|---|---:|---:|
| K3, 95 | 1,958 / 44 / 51 | 1,958 / 44 / 51 |
| K5, 90 | 1,754 / 40 / 50 | 1,754 / 40 / 50 |
| K10, 70 | 1,351 / 20 / 50 | 1,351 / 20 / 50 |

The activity-qualified and return-only endpoints
agree. No maintenance input changes before the return
or observation cutoff. These results preserve the
W-tap maintenance baseline. Regulation performance
and the return exposure diagnosis leave the rule
unqualified for promotion.

[Original protocol and native schedule](../../build/boxing-double-search/v4-reset-confirmation/release-window/prospective-recoil/protocol.json) ·
[Native mirror scores and requests](../../build/boxing-double-search/v4-reset-confirmation/release-window/prospective-recoil/native-mirrors.json) ·
[Model and native input scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/prospective-recoil/summary.json) ·
[m7209 paired click and return packet trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/prospective-recoil/explain-m7209.jsonl) ·
[Segmented capture recovery](../../build/boxing-double-search/v4-reset-confirmation/release-window/prospective-recoil/segmented-recovery.json) ·
[Frozen-rule model driver](BoxingRecoilTransfer.java)

### Which earlier returns can one more movement input deny?

The three 80 ms calendars are now exposed development data.
We recreate all 24 admitted cuts from the prior study and
verify the short stock and sprint outcomes against the frozen
ledger across all 17 timings. There are 121 harmful cut-phase
pairs. Holding sprint improves the next own fresh hit and
advances a defender return to before it. Each of those
returns maps to the same physical press as a miss in plain
W-tap. The defender's local nearest gap shrinks by a median
0.306 blocks, with a range of 0.263 to 1.062.

A causal probe removes only the packet for the first such
return, immediately before processing at the server. The
actual press, local contact and contact drag remain. Both
arms retain the same physical click schedule. Removing
the packet leaves the advanced own fresh hit at its original
time in all 121 cases.

| Harmful phases only, 2.2-second outcomes | Plain W-tap | Sprint only | Sprint with first return packet omitted |
|---|---:|---:|---:|
| Own fresh hit at first or second eligible drain | 0 | 102 | 102 |
| Timely hit with 500 ms return protection | 0 | 0 | 23 |
| Completed further active cycles | 21 | 0 | 145 |
| Accepted score margin sum | +73 | +50 | +185 |

The omitted returns comprise 102 upgrades and 19 fresh
hits. In 98 of 121 variants another fresh return still
scores within the short window. A one-packet suppression
cannot stand in for a sequence of denied click rays.

On eight cuts with some early-return exposure, 128 fixed
open-loop alternatives add either release or back for one
of the next eight input heads after sprint. Each is scored
on all 17 phases. One sequence, sprint followed by release
on the very next head, protects m7207 A across every phase
with at least a tied short margin against both controls.
At nominal timing the first follow-up still accepts at
+499 ms after the anchor and four fresh hits go unanswered
in 2.2 seconds. Holding sprint alone had given up a return
at +452 ms. The complete model scores at that timing are
93–100 for W-tap, 100–89 for sprint and 100–85 for sprint
then release. All 17 repair arms win that start, gaining
5–22 hits relative to matched W-tap.

| Full modeled first-to-100 from all 24 admitted cuts | Plain W-tap | Sprint only | Sprint then release | Sprint unless future evidence predicts the early return |
|---|---:|---:|---:|---:|
| Nominal wins | 12 | 13 | 10 | 10 |
| Nominal median paired margin change | 0 | +0.5 | −2 | 0 |
| Wins across all 408 cut-phase pairs | 213 | 229 | 234 | 233 |
| Mean paired margin change across all phases | 0 | −0.81 | −2.43 | +2.27 |
| Median paired margin change across all phases | 0 | +1 | +1 | 0 |

Every modeled bout reaches 100, with identical physical
press timestamps through the earlier finish. These
same-state branches reuse three clocks and overlapping
portions of a match. The timing-aware hindsight veto
still has no median gain. An oracle choosing the best
final score of only the three movement options reaches
19/24 nominal wins and a median paired +1. Its 353/408
across the timing variants and median +6 bound this action
set with future information. A pre-input fit using only
the observed gap, cue age and displayed target height
raises Brier error from 0.223 for the training base rate
to 0.419. Each fit excludes the clock it scores. This
small exposed sample is a diagnostic of those inputs.

In the factual 80 ms native mirrors, all 26 locally
eligible release heads map to a unique preceding
own fresh hit. W-tap's first opposing return was
already accepted before 14 of those decisions. Of the
15 first returns that landed before our next native
fresh window, 14 were already accepted at the decision
and one was a later damage upgrade. At 20 ms two of
three were already accepted. In all 14 committed
80 ms cases, the defender clicked before our own
fresh hit reached the server. Its click preceded the
release decision by 122–155 ms, with a 138 ms median.
The last own input head before each of those clicks was
three heads before the release. Ordinary movement
would have to reach the defender's view before the
click, so the last preceding head is only a timing
bound. This native audit covers stock W-tap in the
previously recorded mirrors.

[Paired packet and whole-score outcomes](../../build/boxing-double-search/v4-reset-confirmation/release-window/return-denial/summary.json) ·
[Native first-return audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/return-denial/native-returns.json) ·
[Fork and intervention driver](BoxingRecoilReturn.java)

### Moving before both attacks were sent

All 14 native 80 ms exchanges where the return was already
committed are enrolled. Each comparison starts from the
same strict replay state before the earlier of the two
original physical clicks. Both click timestamps and aim
stay recorded. The first panel tests 24 two-input words at
each of three movement cutoffs. The second tests 107 words
of up to four inputs at four earlier cutoffs. Stock W-tap
replays its recorded keys on each matching receipt phase.
The panel uses nominal receipts and independent ±2 ms
position shifts. An opening is only protected where its
own physical press stays fresh in the original or next
server drain, and the opponent's original click misses
from a nearest eye-to-box gap beyond 3 blocks.

| Cut at least this long before the first click | Matched native exchange cuts | Plain W-tap repeats with a clean double across all five timings | Exchange cuts with one movement word making a clean double and gaining score across all five timings |
|---:|---:|---:|---:|
| 40 ms | 14 | 0 | 0 |
| 90 ms | 14 | 0 | 0 |
| 140 ms | 14 | 0 | 0 |
| 190 ms | 14 | 0 | 0 |
| 240 ms | 14 | 0 | 0 |
| 290 ms | 14 | 0 | 1 |
| 340 ms | 14 | 0 | 1 |

The original screen qualifies 13 exchanges. In all 13,
changes at 40 or 90 ms leave the defender's nominal gap
identical at the committed click. At 140 ms the largest
improvement while keeping the own hit is 0.146 blocks.
The missing m7206 comparison had dispatched its first
attack to the wire 8.279 ms after the modeled click.
Using that wire send time to find a delivery sample
assigned the no-op attack to an earlier server drain.
Indexing attack receipts by strict replay generation times
recovers all seven nominal m7206 baselines. The recovery
adds no double and preserves the two m7203 witnesses.

Both successes come from the same exchange in m7203 A.
At 309 ms before the first click the tested client sees
a 2.120-block gap, is grounded, and displays the opponent
0.586 blocks higher after an earlier hit. Three backsteps
then a release increase the selector's first-click gap
from 1.612 to 2.948 while the defender's gap increases
from 2.656 to 3.283. The original own press stays fresh
and lands a second unanswered hit 505 ms later.

We also recompute both sides' W-tap movement using the
counterfactual cues at recorded movement times. In the
same m7203 exchange, three backsteps then release preserve
the original double in eight of nine separate attack and
position receipt settings. With +2 ms own attack receipts,
an earlier physical press at 2.989 blocks instead opens a
four-hit unanswered run. The fixed word gains +3 to +5
over the recomputed W-tap comparator across all nine.
Four backsteps also preserve the original double in eight,
but the earlier press misses at 3.007 blocks in the
+2 ms variant. The defender then returns before the next
fresh hit and the branch loses one point relative to its
comparator. These are two cuts of one exposed exchange.

A frozen local trigger uses the first W-tap resume on the
same hurt cue, with own grounded slow movement, target
elevation and the witnessed gap and cue-age bands. It fires
13 times on the 12 captured mirrors, all at 80 ms. Five
are on m7203. On the eight cuts from the other three
previously exposed clocks, W-tap already has at least
three unanswered fresh hits, with a range of 3 to 60.
The trigger fires inside established combos. Plain W-tap
scores a double in all 72 timing pairs across those cuts.
The backstep word scores one. At all eight nominal cuts
the next W-tap scoring click has a gap of 2.247–2.631
blocks. The same click with the backstep word misses at
3.535–3.964. The damage to the W-tap follow-up is visible
in the geometry even where the recorded and recomputed
W-tap complete attack streams differ. The rule still
needs a way to distinguish threatened returns from
combos to maintain, as well as repeatable margin gains.

[Immediate cut comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/before-commit/summary.json) ·
[Earlier cut comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/before-commit/earlier/summary.json) ·
[m7206 receipt calibration](../../build/boxing-double-search/v4-reset-confirmation/release-window/before-commit/calibration.json) ·
[Nine-phase m7203 comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/before-commit/witness.json) ·
[Own-observation transfer and miss geometry](../../build/boxing-double-search/v4-reset-confirmation/release-window/before-commit/resume-transfer/diagnosis.json) ·
[Strict replay fork driver](BoxingNativeTradeFork.java)

### Protecting the third hit from a short streak

We enroll the first two occurrences per calendar, seat,
streak length and stock outcome at the first forward
input following a W-tap release. The server must have
accepted one or two unanswered fresh hits in the active
streak at that head. We grade the next two fresh hits
before a return or 1,100 ms of inactivity. Returns in
the same drain as the next fresh hit count against both
arms. The twelve clocks are previously exposed. m7203
is retained as a development reference and kept out of
the comparison totals below.

All 159 exact cuts reproduce the nominal native hit
sequence. Fifteen ordinary movement words produce
21,465 same-cut comparisons over the nine receipt
settings. Both sides' aim and physical click timestamps
remain recorded. m7206 and m7211 report strict transport
failures beyond their last evaluated suffixes. The cut
and entire scored suffix are qualified in both cases.

| Other exposed cuts, nominal receipt timing | Cuts | Plain W-tap doubles | Two backsteps | Three backsteps then release |
|---|---:|---:|---:|---:|
| W-tap would give up a return first or in the same drain | 51 | 0 | 2 | 1 |
| W-tap would continue its streak | 51 | 10 | 5 | 0 |
| W-tap is inactive through the scoring window | 40 | 0 | 0 | 0 |

At 20 ms, m7204 A has two unanswered hits. The own
client is grounded with the opponent 0.458 blocks
higher and a local gap of 2.539. Two backsteps leave
the next own hit on the same server drain and move its
click gap from 2.002 to 2.728. The defender's same
click moves from a 2.919-block local gap to 3.515 and
misses. The first return moves from 490 to 1,197 ms
after the second-hit anchor at recorded timing.

| Selected K2 branch, 25 independent receipt shifts around one clock | Plain W-tap recomputed further fresh hits | Movement further fresh hits | Paired short margin range against recomputed W-tap |
|---|---:|---:|---:|
| m7204 A, 20 ms, two backsteps | 0–1 | 2–4 | +1 to +3 |
| m7203 A, 80 ms, three backsteps then release | 0–1 | 4 | +3 to +5 |

The 25 timing settings include ±2, ±5 and ±10 ms on
one attack or position leg at a time. The 20 ms
intervention ties the recorded-input W-tap reference
on nominal short accepted margin, despite improving the
streak. It gains across recomputed W-tap phases and ties
at least one phase of the recorded-input comparator.
The shorter retreat loses the 80 ms double in all 25
phase variants. The longer retreat keeps the 20 ms
double in three. On matched nominal receipt timing,
neither fixed word improves median accepted margin
across the 142 separate cuts.

The original 80 ms word passes all nine timing variants
when started on the observed resume head, and zero when
started on the next head. The 20 ms word also passes
all nine when started one head later. One further 20 ms
start is excluded on nominal replay parity. Both cases
occur after two locally counted hurt cues. Across the
comparison cameras the cue counter agrees at 13 of
15 real K2 resume decisions, with five other decisions
also counted as two, including one already at K3 or
higher. Of 51 sampled threatened returns, nine were
already clicked before the resume head. Distinguishing
an actionable short streak from a safe combo requires
both timing and space. Further active-hit gains on the
two exposed seat-A cases alone do not qualify the rule.

[Verified short-streak comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/short-streak/summary.json) ·
[Wider timing and click geometry](../../build/boxing-double-search/v4-reset-confirmation/release-window/short-streak/stress.json) ·
[Neighboring input heads](../../build/boxing-double-search/v4-reset-confirmation/release-window/short-streak/onset.json) ·
[Native return timing](../../build/boxing-double-search/v4-reset-confirmation/release-window/short-streak/native-returns.json) ·
[Local cue and server count comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/short-streak/cue-parity.json)

### How much retreat keeps a hit and denies the return

The two-click comparison links the first own fresh hit and
first accepted return in the nominal W-tap suffix to the
physical clicks that caused them. Both hits must be within
1,100 ms of the streak anchor. Both clicks must occur after
the cut and before either hit is accepted. The 159 native
cuts yield 48 overlaps. In the rest, 58 lack an eligible
pair, 42 have a press after the first acceptance, ten have
a press before the cut or without a link and one has the
return accepted before the cut. Motion branches keep both
players' aim and every physical click, including misses.

Across the 41 overlaps outside m7203 and m7204, two
backsteps increase median nearest distance by 0.606 blocks
on our original press and by 0.588 blocks on the defender's
original press. The gaps increase on both clients in every
case. Three backsteps and a release cost 1.008 blocks on
ours and add 0.956 on the defender's side. A reverse step
buys close to the same space for each side in this sample.

At five cuts, 20 movement words keep the original own ray
inside three blocks and put the original defender click
beyond three across all nine timing variants. Only one
also gains both a double and short accepted score across
all nine against recorded-input W-tap. This is the known
m7203 retreat. Outside the two case-study clocks, seven
words across three cuts keep and score the original own
click while denying the original return in every variant.
In 61 of their 63 variants a different defender click
still scores before the second continuation hit and within
1,100 ms of the anchor. In nominal m7205 B, denying the
first return at 3.017 blocks postpones the accepted return
by just 55 ms. In m7209 B the next defender click has
already been sent and the return moves only 2.1 ms. The
first two rays alone leave out the rest of the window.

### Letting both original clicks miss

At m7205 A, with zero added RTT, the recorded W-tap
players both select the original clicks at 2.774 and 2.912
blocks and score on the same drain. One backstep and one
release make both original clicks miss. Our next press,
49 ms after the original, contacts at 2.957 blocks and
lands first. At exact recorded delivery the suffix contains
three further unanswered fresh hits and gains two points
over the recorded movement. Recomputing W-tap movement
for both sides yields four further hits versus zero for
W-tap and a +4 margin at the same delivery timing.

Replay all 256 four-head words over back, release, W and
W+Ctrl plus the three original words. All five neighboring
cuts reproduce native hits at nominal time. The best
word at each cut wins at most seven of nine timing
settings on the paired double and score test. The best
late start, two movement heads later, improves short
accepted margin by +1 to +4 against each W-tap baseline
and gets one or four further fresh hits, versus zero or
one for W-tap. From the original and late cuts, another 320
three-head changes apiece around the next own click reach
the same seven-of-nine ceiling.

With the late word, delaying the own position updates by
0.25 ms moves the next own ray from 2.992 to 3.189 blocks,
so the following physical press scores instead. With
position updates unchanged, delaying own attack receipts
by 0.75 ms keeps the 2.992-block ray and pushes the hit
into the next server drain, about 48 ms later than nominal.
Both paths give the defender the same later press, at
2.065 or 2.149 blocks rather than the nominal 3.195-block
miss. The candidate gives up a return about 52 ms after
its first fresh hit. All tested shifts on either defender
leg separately through ±10 ms retain four fresh hits.
These timing boundaries describe one exposed clock.
The next mechanism to test is whether the retreat can
also keep the defender out on its second return press if
our first effective hit slips by one drain. A legal bot
would have to act before that slip using only its own
view. These offline suffixes do not qualify a controller
or replace native first-to-100 and K3/K5/K10 comparisons.

[Two-click coverage and geometry](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/summary.json) ·
[Exact two-click records](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/pairs.jsonl.gz) ·
[Four-head and onset screen](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/double-miss.json) ·
[Late movement screen](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/second-approach.json) ·
[Sub-millisecond receipt sweep](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/receipt-boundary.json)

### A late return against live target tracking

Extend the m7205 A input change through the later defender
press. Search longitudinal and sidestep pulses and bounded
mutations at the two earlier cuts. All 6,735 sampled words
retain the native physical clicks. Three words at the first
cut beat both W-tap comparators through all 25 receipt
variants when aim is recorded. The strongest word produces
two further fresh hits in each variant. The defender's late
press misses at a 2.294-block nearest distance, with no
intersection along the recorded ray. The result leaves room
for the opponent to turn its camera.

Each retargeted branch uses the existing 1.8.9 mouse-count
model at the recorded frame opportunities. It aims from
that client's own simulated position toward its rendered
opponent and uses the resulting yaw for travel and attacks.
Run the same W-tap controls with the same tracking settings.
The unchanged-key and unchanged-aim control reproduces the
native nominal score sequence at every cut. On the 48-cut
sample, nominal retargeted W-tap agrees with that control on
the count of further fresh hits before return or inactivity
in 46 cuts and on the 2.2-second accepted margin in 43. The
retargeted branches are modeled, with both press calendars
and frame opportunities taken from the native recordings.

| m7205 A, same 25 receipt variants | W-tap further fresh hits and late press contacts | Sidestep further fresh hits and late press contacts |
|---|---|---|
| Recorded aim | 0–1, 9/25 | 2, 0/25 |
| Defender tracks | 0–1, 9/25 | 0, 25/25 |
| Both track | 0–1, 9/25 | 0–1, 25/25 |

With defender tracking only, the same press contacts at
2.318 blocks in every variant. With both clients tracking,
the best sidestep gives up that return in every variant,
and its nominal accepted margin is three points below
matched W-tap. The previous straight backstep and release
continues its double and improves score in 19/25 variants
with live tracking, missing the same own-client timing
settings. None of the fixed sidesteps improves both double
and score across all nine timings at any of the 48 cuts.

Run the full pulse and mutation screen again with tracking
on both sides. No word gets two unanswered follow-ups in
either of the two adverse +2 ms phases. In 351 sampled words
the late defender press has a nearest gap above three in
both phases. Each still gives up another accepted return in
the 1,100 ms window. These are correlated alternatives on
one clock. A useful spacing condition must cover the
sequence of defender presses while keeping our first and
second fresh hits reachable. Optimizing the gap at one
defender press leaves the main scoring failure in place.

The two earlier K2 retreats remain positive when both
clients track. Across the same 25 separate receipt shifts,
m7203 A at 80 ms gets four further fresh hits to W-tap's
zero or one and a +3 to +4 accepted margin. m7204 A at
20 ms gets two to four to W-tap's zero or one and a +1 to
+4 margin. Both are still exposed seat-A fixtures. Across
the 37 overlapping press pairs outside the three case-study
clocks, all begin at K1 and neither word improves a double
even in one timing variant. The separate retest of all 20
original K2 cuts keeps the two case-study clocks in
development. Of the 11 other cuts, only three have an
original return threatening the third hit. The two-backstep
word improves both score and double in 7/9 timing variants
on m7207 A and 1/9 on m7212 B. Both are cases in which
W-tap already had the third hit. None passes all nine.
The median nominal margins relative to W-tap on these
11 cuts are zero for two backsteps and −3 for three and
a release. The next independent collection should enrich
threatened K2 starts on both seats, with enough time before
the first defending click and with several potential return
presses in scope. Keep those calendars separate from the
two existing case studies.

[Recorded-aim movement search](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/late-reply.json) ·
[Controlled tracking comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/track-witness.json) ·
[Tracking transfer](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/track-transfer.json) ·
[Movement search with target tracking](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/late-reply-tracked.json) ·
[Retested positive retreats](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/track-retreat-witnesses.json) ·
[K2 comparison with tracking](../../build/boxing-double-search/v4-reset-confirmation/release-window/spacing-budget/track-retreat-k2.json)

### Prospective native clocks put the return before the retreat

Freeze the two longitudinal words, the first same-cue
forward-resume trigger, and the nine timing variants before
requesting 12 new native first-to-100 W-tap mirrors. Four
matches each at 0, 20 and 80 ms added RTT use both seats
and the provisional MMC range preset. All reach 100. Actual
admitted input rates span 17.18–17.72 CPS, with within-match
differences at most 0.31 CPS. The cap is verified by linking
each admitted click to its exact native poll completion
timestamp. Independent mirrors at 20 ms range from 100–34
to 100–92 among the seat-A wins and also include a 55–100
seat-B win. These are baseline native fixtures for both
positions. Candidate changes below are model branches.

Twenty-four distinct two-hit anchors have an eligible first
forward-resume decision. Ten are threatened and fourteen
are continuing. The recorded branch reproduces the native
accepted sequence and knockback for 23. In m7222 B near
57.6 seconds, the recorded-input fork has the correct score
events and a later horizontal launch of 0.896625 instead
of the native 0.883625. That cut remains excluded from
movement scoring. Its factual click timing remains valid.
With tracking recomputed, nominal W-tap preserves the
recorded control's fresh count to first return or inactivity
in all 23 model-qualified cuts and its short margin in 20.

| Both seats, nominal tracked forks | Qualified cuts | Plain W-tap doubles | Two backsteps | Three backsteps and release |
|---|---:|---:|---:|---:|
| Threatened, 20 ms | 5 | 0 | 0 | 0 |
| Threatened, 80 ms | 4 | 0 | 0 | 0 |
| Continuing, 0 ms | 4 | 4 | 1 | 0 |
| Continuing, 20 ms | 4 | 4 | 2 | 1 |
| Continuing, 80 ms | 6 | 5 | 1 | 0 |

Every candidate keeps the same physical click schedule as
its matched W-tap fork. Neither retreat improves both the
double and short margin in every timing at any cut. On the
14 continuing cuts the nominal median score changes are
−1 and −3.5 respectively. Across the ten factual threats
(three from A and seven from B), the physical return click
precedes the forward-resume decision by 11–80 ms. There
is no factual server-report and defender-interpolation path
from that movement head to the click in any of the ten.

The separate native census counts all 276 threatened K2
anchors, regardless of whether they had a forward-resume
head. It links each to its physical defending click. The
defender has already clicked before the second hit is
accepted in 156 cases (57%). In 234 (85%) it clicks before
our first input decision after that server hit. The server
accepts the second hit and return in the same tick in 103
cases (37%). A rule waiting to see two unanswered hits is
too late for most threats on this panel. Each of the 276
threatened streaks has a native own input head carrying the
first hurt cue at least 150 ms before the defender's eventual
return click. The median lead is 768 ms. The clock join uses
the locally observed cue between the two server hit wall
times and the true own decision and opposing physical click
monotonic clocks. These retrospectively identified windows
give the earlier causal test an observable trigger. That
test should measure whether spacing preserves our second
hit and denies returns across several defender clicks.

[Registered protocol](../../build/boxing-double-search/v4-reset-confirmation/release-window/threatened-k2/protocol.json) ·
[Individual native scores and matched fork results](../../build/boxing-double-search/v4-reset-confirmation/release-window/threatened-k2/summary.json) ·
[Native anchor and physical click census](../../build/boxing-double-search/v4-reset-confirmation/release-window/threatened-k2/anchor-census.json)

### Acting on the first visible hurt cue

The next frozen study tests movement at the first local
hurt cue and three or six input heads later on the same
12 source clocks. Six are development and six are a
separate, previously exposed comparison panel. Each panel
has two clocks per RTT. Enroll from native hits before
looking at candidate outcomes, with stable own hurt cues
and before the factual second hit. Include continuing
streaks and a small fixed sample where W-tap never gets
hit two. Eight movement words include release, retreat,
W and W+Ctrl. Both players retain their physical click
opportunities and continuously recompute their own aim.
The exact recorded-input baseline verifies 527 of the
528 cuts. The one excluded suffix has an extra model
acceptance. A late strict-export failure on m7217 occurs
after the end of every scored window on that clock.

| First visible cue, threatened starts | Qualified cuts | W-tap nominal doubles | One W+Ctrl head nominal doubles | W+Ctrl all-nine paired gains |
|---|---:|---:|---:|---:|
| Development | 70 | 0 | 3 | 1 |
| Previously exposed comparison | 71 | 0 | 3 | 0 |

All 425 qualified threatened cuts precede the original
defender click. Across the six comparison clocks, no
tested word at any onset strictly improves both the double
and 2.2-second accepted margin in all nine timing settings.
The original return is often easy to remove by giving up
our hit. In the comparison subset where tracked W-tap
accepts both original clicks, two backsteps deny the
original return in 1,051 of 1,634 cut/timing variants.
Only 17 of those denials still score the original second
hit on the same physical press. Later legal scoring presses
are evaluated independently in the primary metric.

The one all-phase result is m7219 B at 80 ms. At the
first cue it is grounded, has 0.9495 blocks/tick of
recoil away from the opponent, and sees a 1.490-block
gap. Holding W+Ctrl through that head lets the next
actual contact, 53 ms later, brake horizontal recoil from
0.449 to 0.269 blocks/tick. The same W-tap click contacts
with sprint off and keeps 0.518 blocks/tick. The candidate
has already moved 0.127 fewer blocks away on the first
step. At nominal timing it scores the next fresh hit
at 500 ms after the anchor, 399 ms earlier than W-tap.
That successful click has a 2.992-block ray, where the
same W-tap click misses from 3.697 blocks. The earlier
fresh hit sends 0.5275 horizontal knockback and makes the
original return click miss at 4.072 blocks, up from
2.082. The original W-tap scoring click still contacts
in the changed branch, where the target is immune after
the earlier hit.

| m7219 B, 25 receipt variants | Plain W-tap | W+Ctrl | W+Ctrl with early brake removed | Release with early brake added |
|---|---:|---:|---:|---:|
| Nominal first further fresh hit from anchor, ms | 898.7 | 500.0 | 898.7 | 801.0 |
| Nominal further fresh hits before return or inactivity | 1 | 3 | 0 | 1 |
| Variants with more fresh hits, a double and positive margin relative to W-tap | 0 | 25 | 6 | 6 |

The two brake changes are physics interventions on the
same contact. Removing only the 0.6 multiplier loses the
early next hit. Adding that multiplier during an ordinary
release improves its nominal hit time by 98 ms and fails
to reproduce the full benefit. The actual W+Ctrl word
gains +1 to +7 margin in the 25 variants. Its combination
of first movement and the following recoil brake matters.

The comparison case m7222 B at 80 ms comes close with the
same grounded recoil. The change wins 21 of 25 variants.
With 2 ms later defender position receipts, the same
opponent press changes from a 3.106-block miss to a
2.870-block hit. It upgrades the damage 50 ms before
our follow-up, ending the streak. At m7224 A, the same
2 ms change to own position receipts moves our first-window
ray from 3.257 to 2.691 and makes a four-hit continuation
possible. That case wins 6 of 25 variations. These cases
are now exposed for diagnosis. A useful gate must identify
the early reciprocal reach margins from own observations,
and handle accepted upgrades along with fresh returns.
The first hurt cue supplies time to act. Grounded recoil
alone leaves the packet timing boundary unresolved.

[First-cue protocol and split](../../build/boxing-double-search/v4-reset-confirmation/release-window/first-cue/protocol.json) ·
[Paired scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/first-cue/summary.json) ·
[Original-click geometry](../../build/boxing-double-search/v4-reset-confirmation/release-window/first-cue/geometry.json) ·
[Brake intervention](../../build/boxing-double-search/v4-reset-confirmation/release-window/first-cue/first-cue-brake.json) ·
[Neighboring clock failures](../../build/boxing-double-search/v4-reset-confirmation/release-window/first-cue/near-miss-exchanges.json)

### Post-brake movement and the client task deadline

On the three exposed 80 ms cuts, test 288 additional
movement pulses after the initial W+Ctrl. Each lasts one,
two or three heads and begins between the third and tenth
own movement head. Longitudinal keys and sidestep chords
use both bots' recalculated tracking. Each arm retains
the recorded physical presses. The recorded-input and
recomputed W-tap controls reproduce the earlier fork
results.

On m7219 B, the strongest follow-up is two backsteps
beginning at the tenth movement head. Relative to the
single sprint tick this adds a fresh hit in seven of the
25 independent timings with no short-score loss. The
other two clocks have no additional pulse that passes
the nine-timing condition. The picked move and the
single-sprint control then face all sixteen ±2 ms joint
sign combinations on the two attack uploads and two
position downloads. The original case gives the sprint
arms the first follow-up at 500 ms in every joint
setting. In four, the paired plain W-tap branch also gets
three fresh hits before return or inactivity.

| m7219 B, matched 2.2-second forks | Plain W-tap | One W+Ctrl head | W+Ctrl then two later backsteps |
|---|---:|---:|---:|
| Independent timings with more fresh hits, a double and higher margin | 0/25 | 25/25 | 25/25 |
| Joint timings with more fresh hits, a double and higher margin | 0/16 | 11/16 | 12/16 |
| Joint timings with a higher accepted margin | 0/16 | 13/16 | 13/16 |

Picking by points alone instead selects a one-head
sprint and left strafe on the third movement head of
m7222 B. It improves 24/25 independent margins, with a
tie at the worst timing, and all 16 joint margins by
+3 to +6. It gives up the same early upgrades as the
original sprint branch. Its short-score gain therefore
remains distinct from protecting the streak. Across the
18 previously exposed grounded-recoil cuts from the
other nine clocks, neither added move produces a
strictly positive score in every timing. Median nominal
changes for the later backstep pulse are 0, 0 and −0.5
at 0, 20 and 80 ms respectively. The strafe pulse has
the same medians. No controls move to a native trial.

### Recorded handler delays and actual network delays

Localize the first-cue failures by shifting each modeled
position receipt before the decisive press individually.
On m7222 B, delaying one position update in the
defender's client turns the critical early click from
a 3.106-block miss into a 2.997-block hit. The original
whole-route shift gives 2.870. Retaining just that one
handler timestamp while shifting the other packets
makes the press miss at 3.038 and restores the four-hit
continuation. The corresponding factual network task
was enqueued 5.883 ms before the original drain began.

At m7224 A, delaying one incoming position update
makes our own first-window click reach at 2.691 blocks.
Retaining just that handler timestamp under the same
whole-route shift moves the ray to 3.142. The four-hit
continuation falls back to one. The factual task was
enqueued 11.524 ms before the original drain. Both
source tasks would still have been queued before their
original native client drain under a 2 ms delay to
readiness, with the other events fixed.

The 12 native mirrors link 20,117 opponent position
packets to their wire arrival, task enqueue and main
thread handler. 17,639 (87.7%) were already queued at
least 2 ms before their drain began. At 0, 20 and 80 ms
the proportions are 89.4%, 85.3% and 88.1%. The small
shifts in these experiments apply to modeled handler
times. Applying the same perturbation to network
readiness would often leave the native handler's task
batch intact. The packet-by-packet intervention shows
why a test that perturbs the handler timestamp cannot
be read directly as network jitter tolerance. Replay
the native client task opportunities and vary readiness
before fitting a rule to these timing failures. Keep
the already-tracked generated-packet mismatches and
arrivals during a drain visible in that validation.

[Movement pulse screen](../../build/boxing-double-search/v4-reset-confirmation/release-window/post-recoil/development-summary.json) ·
[Combined timing and separate-clock comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/post-recoil/transfer.json) ·
[Score-first comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/post-recoil/score-transfer.json) ·
[Single-update interventions](../../build/boxing-double-search/v4-reset-confirmation/release-window/post-recoil/isolated-position.json) ·
[Native task readiness audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/post-recoil/native-ready-margin.json)

### Replay by client queue readiness

The optional position adapter keeps the strict copied
state and changes newly generated position timing only.
Each recorded sample supplies the raw wire time, the
enqueue time, its enclosing task drain and its handler
timestamp. The twelve source tapes link all 12,990
position timing samples. The adapter retains the original
handler time when the same observed batch stays eligible.
For a changed batch, it dispatches by the end of the
appropriate measured drain. Unmatched positions use the
configured half RTT plus a residual and those same drain
opportunities. Exact subtype and nearby send opportunity,
matched different position subtype, and unsupported
subtype/send timing each have separate counters. Copied
in-flight packets keep their actual scheduled times.

The vanilla 1.8.9 bytecode guards both `Queue.add` and
`Queue.poll` with Minecraft's same scheduled-task monitor.
The capture's begin and end timestamps are inside that
lock. A packet becoming ready during the drain must wait
until after unlock. The late interpretation is used for
the calibrated result. The early interpretation is an
optimistic sensitivity bound. In nominal W-tap recorded
input replay, accepted-hit order and knockback match
native in all 21 cuts, with exact receiver, subtype and
handler-time agreement at 1,042 of 1,059 native position
applications. These bounds leave room for unobserved
preparation and service, even on the recorded calendar.

Masked factual position packets provide a separate check
on timing with an unknown sample. On six separate clocks
(6,517 positions), using the server tracker start and the
original 0.35 ms residual predicts the correct native
client drain in 6,103 cases. Calibrating the median
enqueue residual for each RTT on six other clocks raises
this to 6,300. The fitted residuals are 0.939, 1.297 and
1.044 ms at 0, 20 and 80 ms added RTT. All 12,837
compatible source positions point to the correct drain
when supplied their measured enqueue timestamps. The
prediction test uses native packets emitted under
observed inputs. Newly generated counterfactual packets
still have uncertain transport and service. The model
does not use this timing information in a playing policy.

| Frozen initial W+Ctrl, calibrated late model | W-tap nominal further fresh hits | W+Ctrl nominal further fresh hits | W+Ctrl all-position gain in double and score | W+Ctrl mixed joint gain |
|---|---:|---:|---:|---:|
| m7219 B | 1 | 1 | 3/25 | 3/16 |
| m7222 B | 1 | 4 | 25/25 | 12/16 |
| m7224 A | 0 | 1 | 0/25 | 2/16 |

On m7222 B the +2 ms defender position shift now leaves
the critical click outside reach at 3.106. The sprint
continuation beats W-tap by +3 to +4 across the 25
position-only settings using the calibrated residual.
The m7224 A first-window click stays outside reach at
3.257 under its +2 ms position shift. On m7219 B, the
older replay gave a 2.992-block own ray at the first
fresh window. The queue adapter serves additional
generated position packets at real client opportunities.
The same press now misses at 3.091, and the first
fresh follow-up moves from 500 to 751 ms. It stays at
one fresh follow-up at nominal timing when matching
different subtypes is disabled and when the unmatched
residual varies from 0.25 to 2 ms.

Across the other 18 previously exposed cuts, no word
perfects a double and score gain in either set of
settings. Score remains a separate target. The sprint
tick gets +1 in all 53 calibrated settings on m7221 B.
At nominal time that gain comes from one additional
damage upgrade, with the same fresh score on both sides.
The later backsteps score +1 or better across all 53
on m7217 B while giving up the initial return. The
nominal median is zero for all three words on the
comparison group. The mixed variants still use the
historical attack-handler perturbation. Validate server
attack readiness through actual server drains and test
timing for changed generated packets on independent
native inputs before using these boundaries for a gate.

[Position source coverage](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/source-coverage.json) ·
[Nominal parity and missing-sample sensitivity](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/generated-check.json) ·
[Separate-clock readiness prediction](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/fallback-validation.json) ·
[Calibrated timing and W-tap comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/calibrated-results.json)

### Replay attack arrival through the server queue

All 6,342 C02 attacks and 25,453 movement or sprint tasks
on the twelve tapes link to their observed server receipt,
enqueue, handler and drain bracket. Server bytecode uses
the same queue monitor for add and poll. A job ready after
the drain starts waits for the next batch. Shifting the
native attack handler 2 ms earlier targets a time before its
actual drain in 6,042 cases. Applying that shift at the wire
instead moves only 243 attacks to an earlier batch. Within
an eligible batch the replay estimates service order from
the native enqueue anchors, preserving same-client packet
order through the existing queue watermark. Shifted handler
microtimes are estimated. Keep native source handler times
when the generated attack uses its original unshifted sample.

With each attack's own timing sample hidden, a fixed
0.35 ms overhead places 3,164 of 3,202 attacks in the correct
server batch on six comparison clocks. Median residuals
fitted on six other clocks give 3,174/3,202. The batch and
order relative to the movement and sprint anchors both agree
for 3,140/3,202. The calibrated extra time from click consumption
to enqueue is 0.414, 0.601 and 0.659 ms above half RTT at 0, 20
and 80 ms. The fit only uses source transport measurements.

A separate check fully masks the attack receipt, enqueue and
service anchors on eight cuts from six previously exposed
native fights, four played by the distance-based W-release rule versus
W-tap and two W-tap mirrors. It retains their actual physical
inputs, position timings, non-attack queue anchors and source
event clock. The calibrated model recovers the native accepted
hit and upgrade order, server batches and fresh knockback
on **8/8** cuts and dispatches **319/323** C02s in the correct
batch. The 0.35 ms model gets 7/8 and 308/323 respectively,
with 12 native contacts missing from its processed attack
list through the scoring cutoff. Match-level timings remain
dependent. This checks replay dispatch under recorded native
inputs, with no native intervention on the new move.

| Frozen single sprint head | Matched W-tap nominal fresh follow-ups | Sprint nominal fresh follow-ups | Joint ±2 ms double and score gains | All-settings double and score gains | Sprint minimum paired score gain |
|---|---:|---:|---:|---:|---:|
| m7219 B | 1 | 1 | 0/16 | 4/53 | +1 |
| m7222 B | 1 | 4 | 16/16 | 52/53 | +2 |
| m7224 A | 0 | 0 | 0/16 | 0/53 | +1 |

All 21 measured cuts preserve the nominal native hits
and launches on the recorded-input control. In the other
18 cuts, no frozen movement word improves double and score
in every setting. Their nominal paired score medians are zero.
m7221 B's added upgrade and m7217 B's late backstep still
score at least +1 across all 53 settings at their original
cuts. The three words remain isolated timing diagnostics.

The narrowed m7222 B stress test places the modeled
decisive C02 at a 2.921-block ray with 3.996 ms of readiness
slack before the server drain starts. A +3.9 ms upload
perturbation still scores 4–0 against matched W-tap's 2–2.
At +4.0 ms the same click remains in range and its server
batch moves from 4473 to 4521 ms. W-tap gets a fresh return
at 4723 ms and the sprint arm scores 4–2. This is a failure
to preserve the double at the critical window despite a
positive short score. At +10 ms an earlier press becomes
a fresh hit, the original decisive attack counts as an
upgrade, and the streak returns. The 3.996 ms boundary
uses an estimated enqueue for a newly generated attack on
one recorded clock and applies only to that replay. A prospective matched intervention
has to reproduce the relevant recoil and click phase, and
vary them across independent clocks before training a gate.

[Server source links](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/server-source-coverage.json) ·
[Masked factual arrival predictions](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/attack-fallback-validation.json) ·
[Separate native-input replays](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/native-input-validation/results.json) ·
[Full 53-setting W-tap comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/server-queue-results.json) ·
[Decisive attack timing trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/client-queue/attack-deadline-trace.json)

### Native one-tick sprint intervention

The revised m7222 B replay now isolates the first step
and next contact under the same 53 timing settings. W+Ctrl
shortens the first retreat from 0.9495 to 0.8221 blocks.
On the next contact, 51 ms later, sprint brakes 0.4489 to
0.2693 blocks/tick. Plain W shortens the first retreat
to 0.8515 blocks and has no sprint brake on that contact.
Both players use matched click attempts in every branch.

| m7222 B replay input | W-tap nominal accepted score | Variant nominal accepted score | Better short score than W-tap | Double and score improvement over W-tap |
|---|---:|---:|---:|---:|
| One W+Ctrl tick | 2–2 | 4–0 | 53/53 | 52/53 |
| One W tick | 2–2 | 2–2 | 5/53 | 0/53 |
| W+Ctrl with the next brake removed | 2–2 | 3–2 | 42/53 | 0/53 |
| W-tap release with a synthetic brake | 2–2 | 3–3 | 13/53 | 1/53 |

The last two variants intervene directly on model
velocity for one contact. Both pieces of the ordinary
sprint move are necessary for the robust double in
this replay. The short score alone can sometimes
improve through later exchanges that include returns.

The prospective native trial uses a single intervention
on the first eligible stock release. Both trial arms
use the same frozen cue and ordinary W-tap controls
outside that head. Both opponents always use W-tap.
Entry requires grounded own-view recoil of at least
0.80 blocks/tick, a nearest hitbox distance at most
1.30 blocks, sprint off, and same-trade own and target
hurt cues. The native starts are independently played
and the target and clocks vary. The model takes matched
forks at the measured movement boundary, retaining full
queues, prefix, and physical press schedule.

All 24 planned 30-second bouts pass the input, movement
and accepted-score checks. At 80 ms the median rates for
the sprint bot and its opponent are 17.54 and 17.53 clicks
per second. In the W-tap control they are 17.60 and 17.53.
The sprint trial consistently moves 0.8221 blocks backward
on the intervention step against 0.9495 in the observed
W-tap activations. All eight 80 ms sprint activations also
show a 0.6 horizontal velocity multiplier on an early
contact. Three of the six W-tap activations produce a brake
within the same three-input horizon.

| Symmetric added RTT | W-tap bouts, activations, doubles | Sprint bouts, activations, doubles | W-tap timed margin, median [range] | Sprint timed margin, median [range] |
|---|---:|---:|---:|---:|
| 0 ms | 2, 0, 0 | 2, 0, 0 | 0 [0, 0] | +0.5 [0, +1] |
| 20 ms | 2, 0, 0 | 2, 1, 0 | 0 [−1, +1] | 0 [0, 0] |
| 80 ms | 8, 6, 0 | 8, 8, 2 | +1.5 [−45, +16] | +1 [−19, +44] |

Both native protected doubles occur from seat A. In
`m7238`, the selected sprint tick at 9.36 s produces
four further fresh hits in the 2.2 s window, then
continues to 40 fresh follow-ups before match time runs
out at 55–15. `m7244` has four in the initial window
and 24 before an accepted return, finishing 56–12.
The treatments make exactly one movement change, after
which ordinary W-tap continues. Same-state model forks
score one W-tap follow-up versus four with sprint for
both recordings. The sprint arm improves the double and
the short accepted margin in all 25 variants of each
start, at least +6 accepted points. The other thirteen
starts have no robust double. Several already-returned
starts still improve short scores. This distinction
matters for regulation scoring.

The native study samples independent openings and
times out after 30 seconds. The median and win record
at 80 ms favor plain W-tap (5/8 wins against 4/8), with a
larger upside and smaller worst loss in the sprint arm.
The tested move has two native success witnesses and
an inconsistent aggregate. Keep it experimental and
preserve the separate first-to-100 and K3/K5/K10 gates.

m7228 starts only 0.006 blocks farther from the hitbox
than the original m7222 B witness. It has the same
initial recoil, first step and early 0.6 contact brake.
Our next fresh hit lands at the opening server tick,
but W-tap already scored a falling damage upgrade at
459 ms after the anchor, about 41 ms before the next
fresh hit. The native opponent ray is at 2.697 blocks.
Paired model W-tap puts the same press at 3.005 blocks,
a miss in 8/9 individual timing settings. The sprint
variant allows it in 9/9. Holding sprint into the
second step makes the defender's ray shorter again.
The pulse can solve our reach while improving the
defender's earlier upgrade opportunity.

Nine of fifteen native activations occur after an
accepted return already broke the anchor, including
five of six W-tap and four of eight sprint activations
at 80 ms. The latest applied own and target S19 hurt
statuses during the preceding 150 ms preserve the
order of the two accepted fresh hits in all 15 cases.
This is a retrospective observable signal. It would
have screened out those nine starts while retaining
both native sprint successes. The remaining early
upgrade failures show the additional reach and timing
problem after a valid start.

The first nominal model run qualifies 14/15. In
m7233 an output payload differs by 0.010625 horizontal
blocks/tick. The original version looks up movement
and sprint samples by their recorded wire send times.
Calibrating those origins against strict model
generation events, as already done for attacks,
qualifies 15/15 with the same two robust doubles.
The original mismatch and refined results are saved
separately.

[Replay mechanism contrasts](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/mechanism.json) ·
[Frozen native schedule](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/protocol.json) ·
[Native scores, actual CPS and input checks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-score.json) ·
[Exact paired replay variants](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/model/upload-origin-results.json) ·
[Replay of the early damage upgrade](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/m7228-return.json) ·
[Own-client status order audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/cue-order.json)

#### What ended the long native streaks

The two sprint witnesses use ordinary W-tap after the initial
intervention. The broader drills separately evaluate bots that
change their controls during maintenance. These three recordings
illustrate endings, without providing a between-policy estimate.

The longest streaks including each anchor were 25 hits in m7244,
41 in m7238, and 66 in the m7111 plain W-tap mirror. The final
hit-to-hit intervals were 1,046.8 ms, 542.7 ms, and 603.9 ms
respectively. The first returns were fresh hits at +49.3 ms in
m7244 and +146.3 ms in m7111. The m7238 episode was censored.

In m7244, the next fresh window opened 495.2 ms after the
penultimate hit. The attacker was still outside local reach. Its
next click, at 531.0 ms, had 4.945 blocks to the nearest edge of
the opponent's expanded hitbox. It missed 17 consecutive recorded
clicks before selecting the opponent at 2.822 blocks. The defender's
next click selected the attacker at 2.626 blocks, 24.0 ms before
the server processed that last outgoing hit. The return was already
in flight before the last hit's new knockback could separate them.
The late outgoing follow-up and the defender's successful re-entry
are observed directly. Which earlier input would preserve reach
while still denying the return remains untested on this tail.

In m7111, the final follow-up was 103.4 ms after the fresh window
opened. The defender's return click came 73.6 ms after that follow-up
was accepted. A 3.211-block nearest-box click missed about 54 ms
earlier, then its actual selected ray was 2.875 blocks on the
successful return. The defender had already applied the new
knockback, but clicked before its first movement under that
velocity. That sprint contact cut the outgoing horizontal motion
from 0.849625 to 0.509775 blocks per tick. In m7238 both players
were still clicking when the match's clock ended. That episode
gives a lower bound on the streak under those particular conditions.

[Tail audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/chain_end.py) ·
[m7244 linked clicks and native hits](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/m7244-chain-end.json) ·
[m7238 timed censor](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/m7238-chain-end.json) ·
[m7111 W-tap comparison example](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/m7111-chain-end.json)

### Validating hurt order and the early return boundary

The separate comparison covers both seats of 24
previously exposed native first-to-100 W-tap mirrors.
All 63 heads that meet the existing recoil gate
have both hurt statuses linked to the latest two
fresh hits. The applied packet order identifies
all 33 already-landed returns and retains all 30
other heads. At 80 ms that split is 29 rejected and
24 retained. The broader grounded-recoil sample
has 1,524 agreements in 1,525 heads. Its one exception
is a local hurt status sent after the winning
point in m7221, when the scoreboard already ignores
the next attack. These are retrospective native
packet labels. The signal sorts received fresh
hurt statuses. Returns in flight and later damage
upgrades have separate evidence.

To evaluate a once-per-match sprint, choose the
first stock release with the existing recoil gate,
then repeat with own hurt required to arrive before
target hurt. All enrollment happens on the factual
W-tap path. Four seats change their selection to
a later release and eight give up the activation.
Every trial uses the matching W-tap suffix and
identical presses at its own cut.

| Previously exposed panel, 48 seat-matches | Matched W-tap short paired gain | Original first sprint | Require own hurt before target hurt |
|---|---:|---:|---:|
| Verified activations | Same cuts as each candidate | 24/25 | 16/17 |
| Nominal accepted gain summed over verified windows | 0 | +11 | +17 |
| Median short gain, with inactive seats scored zero | 0 | 0 | 0 |
| Gain in both doubles and score in all 25 phases | 0 | 1 | 2 |
| Same gain after excluding three prior witness clocks | 0 | 0 | 1 |

One start in m7223 is unresolved under both rules.
The new repeatable seat-B replay is m7209, near
10.55 s. The rule skips the earlier 2.16 s release,
which already gave away a return. It selects a
later 0.950-block start. The sprint continuation
scores 5–0 at nominal timing and four active fresh
follow-ups. Matched W-tap scores 3–2 with zero
protected follow-ups. The gain stays +4 to +6
accepted hits and improves the double in all 53
timing variants. Plain W without Ctrl has zero
double improvements across that spread. In the
native recording both players ran W-tap. The replay
identifies a prospective test case, with its score
measured over 2.2 seconds.

The early upgrade remains a separate boundary.
W-tap makes falling presses in both successful
native runs. Around 370–374 ms after the launch
anchor its nearest hitbox point is 3.319 or 3.238
blocks away, and both clicks miss. At similar
phases in m7227 and m7228, falling presses find
2.837 and 2.677 blocks of box distance. The
accepted upgrades arrive with 11 immunity ticks
left, before our first eligible fresh hit.

| Native failure replay, 25 timings per cut | W-tap timely protected follow-ups | One sprint timely protected follow-ups | Added pulse variants with a timely protected follow-up |
|---|---:|---:|---:|
| m7227 A | 0/25 | 0/25 | 0/1,050 |
| m7228 B | 0/25 | 0/25 | 0/1,050 |

The single sprint makes the next fresh hit timely
in all 25 variants of each failure, while opening
the early opposing upgrade in all 25. The 42 later
pulses leave their pivotal press accepted throughout.
In m7228, two early backsteps move our own player
0.525 blocks farther away by the defender's click.
The defender's rendered attacker position changes
only 0.0026 blocks and its ray still reaches at
2.696 blocks. The changed trajectory first appears
in its interpolated position 56 ms before the click.
A fresher position is already queued as an
interpolation goal with three steps remaining.
In m7227, the best pulse increases the opponent's
ray only from 2.806 to 2.827 blocks. The local
retreat needs time to pass through the server and
become visible on the other screen.

An exhaustive three-head screen of release, W,
W+Ctrl and back also yields zero timely protected
starts across both failures in all 25 timings.
The score-selected back/sprint/sprint changes gains
in some later exchanges. It loses the protected
double on both native success tapes where the
single sprint achieves it in all 25. Keep the
one-tick intervention as the comparison arm. Test
the received cue order as a prospective selector.
The admitted window still needs separate evidence
for early reciprocal reach under falling crits.

[Hurt-packet order on the two comparison panels](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/cue-comparison.json) ·
[First activation replay comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/order-admission/results.json) ·
[New seat-B 53-phase replay](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/ordered-witness.json) ·
[Native falling presses](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/falling-windows.json) ·
[How the retreat reaches the opposing client](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/retreat-publication.json) ·
[Early movement and preservation checks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/first-steps-transfer.json)

### Closed-loop regulation with the hurt-order guard

The guard compares the order of the latest two received fresh-hurt
statuses before allowing one extra W+Ctrl head in an otherwise ordinary
W-tap bout. The model's received-packet callbacks preserve within-client
ordering when timestamps tie. Eleven previously exposed native timing
recordings supply both seats, one measured timing and four perturbed
routes each. All 110 pairs reach 100 accepted hits and have identical
physical press times up to the common endpoint. Every activation also
has an identical accepted-hit history in both arms before that input.

| Full regulation set | W-tap wins / median margin [range] | Guarded sprint wins / median margin [range] | Paired margin change, median [range] |
|---|---:|---:|---:|
| All 110 pairs | 59 / +1 [−82, +82] | 60 / +1 [−82, +82] | 0 [−75, +84] |
| Measured timing, 0 ms (8) | 4 / 0 [−49, +49] | 4 / 0 [−49, +49] | 0 [0, 0] |
| Measured timing, 20 ms (8) | 4 / 0 [−73, +73] | 4 / 0 [−73, +73] | 0 [0, 0] |
| Measured timing, 80 ms A (3) | 0 / −12 [−21, −7] | 2 / +55 [−9, +72] | +76 [−2, +84] |
| Measured timing, 80 ms B (3) | 3 / +12 [+7, +21] | 1 / −7 [−54, +25] | −14 [−75, +13] |

The guard activates in 24 full pairs. In those pairs, the 2.2-second
paired gain after activation ranges from −3 to +7, with median +0.5.
The later exchanges alone range from −72 to +85. For m7203 A on a
perturbed clock, the nearby window loses one point relative to W-tap
and later exchanges gain 85. For m7209 B at measured timing, the
nearby window loses three while later exchanges lose another 72.
The long margin swings are sensitive to what happens after the
intervention, while the short identical-prefix forks localize its
immediate effects. This closed loop uses approximate wire delivery
and the model's W-tap hurt-cue servicing.

The separate common-state maintenance forks produce identical outcomes
under both arms, for both return-only and activity-qualified endpoints.
The guard never changes an input before either endpoint. At K3 both
arms complete 1,915 cycles in 95 forks, with 45 returns and 50 censored.
At K5 both complete 1,732 cycles in 89 forks, with 39 returns and 50
censored. At K10 both complete 1,344 cycles in 71 forks, with 20 returns
and 51 censored. These correlated phase forks are descriptive. The
full remaining-hit distributions and exposure are in the score file.

[Frozen model protocol](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/guarded-regulation/model-protocol.json) ·
[Every score, matched press check and continuation](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/guarded-regulation/model-score.json)

### Prospective native regulation and maintenance with the hurt-order guard

All 28 independent Forge first-to-100 bouts pass the assumed server
profile, client build, motion/input, cap and scoreboard checks. Both
arms face plain W-tap. The W-tap reference records the same decisions
without changing inputs. Both arms have native 3-block reach and a
20 CPS cap. Observed median subject / opponent rates are 17.520 /
17.531 CPS for W-tap and 17.504 / 17.546 for the sprint arm. The
largest within-bout absolute differences are 0.252 and 0.174 CPS.

| Symmetric added RTT | W-tap wins / median margin [range] | Guarded sprint wins / median margin [range] | Guarded sprint activations |
|---|---:|---:|---:|
| All | 11/14 / +2 [−77, +47] | 5/14 / −3.5 [−96, +93] | 2/14 |
| 0 ms | 4/4 / +3.5 [+2, +14] | 2/4 / −1.5 [−8, +6] | 0/4 |
| 20 ms | 4/4 / +3.5 [+2, +13] | 1/4 / −2 [−39, +80] | 0/4 |
| 80 ms A | 1/3 / −4 [−30, +2] | 0/3 / −60 [−96, −47] | 1/3 |
| 80 ms B | 2/3 / +2 [−77, +47] | 2/3 / +66 [−34, +93] | 1/3 |

Every one of the 21 rejected regulation heads is after an accepted
return to the last own fresh hit. None of the seven permitted heads
(five observation-only) is after such a return. Both treated bouts
give up a return before establishing a double. In m7266 the next own
fresh hit lands in the first eligible server tick. The defender has
already landed a falling damage upgrade at 2.469 blocks, 101 ms
before the fresh window opens, and the bout ends 40–100. In m7297
the defender's 2.620-block fresh return lands before the late next
own fresh hit. The bout ends 100–34, with the later score change
following an intervening return. The subject was ahead 14–11 at the
intervention. It starts a later 62-fresh-hit W-tap streak 16.9 s
after the sprint at 38–34 and carries it to the winning point.
Much of the aggregate arm spread occurs in bouts whose inputs the
guard never changes. Independent regulation margins by seat and
repeat are performance results, without isolating one key's causal
effect.

All seven separate activation forks reconstruct the recorded server
hits and knockback before comparing matched physical clicks from the
same native prefix. No cut improves both the double and the short
score at all 25 receipt variants. m7262 improves both in four of 25.
The two treated native cuts produce no improved double in any of
the 25 per cut. In m7297 the model's short W-tap comparator gains
one point over the intervention in all variants. In m7266 it gains
five points. The latter recomputes W-tap controls and view after the
fork, so its later cycles differ from the reconstructed native
trace. The 396 ms first return is retained under all three nominal
branches. These are short model diagnostics with matched starting
states and press schedules.

All 20 separate 20-second native drills qualify. Each defender begins
clicking after two seconds, and each subject stays on W-tap through
its assigned K3, K5 or K10 landmark. There are six bouts per arm at
K3 across both seats and RTTs of 0, 20 and 80 ms. Each arm has two
bouts at K5 and two at K10, both seats at 80 ms. No subject actually
changes inputs, and the only post-landmark observation marker is
after the scored return in m7293. The return-only and 1,100 ms
activity-qualified endpoints agree. Exposure is measured from the
landmark and excludes non-entries. Censoring is at the match limit.

| Native landmark | W-tap entries / completed cycles / returns / censored / exposure | Guarded sprint entries / completed cycles / returns / censored / exposure |
|---|---:|---:|
| K3 | 5/6 / 114 / 2 / 3 / 59.544 s | 3/6 / 78 / 1 / 2 / 40.709 s |
| K5 | 2/2 / 38 / 1 / 1 / 20.069 s | 2/2 / 36 / 1 / 1 / 18.710 s |
| K10 | 1/2 / 13 / 1 / 0 / 7.197 s | 2/2 / 48 / 1 / 1 / 24.818 s |

The independent native starts share one acquisition protocol and
separate seats and latency. The full-state model forks establish
identical maintenance. Keep W-tap as the maintenance reference and
the sprint guard as an acquisition diagnostic. Mid-combo reach at
the next fresh window and the defender's return timing still account
for the failures observed in the long native tails.

[Frozen schedule](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/guarded-regulation/protocol.json) ·
[All regulation scores, CPS, seat spread and native maintenance distributions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/guarded-regulation/score.json) ·
[Exact-prefix comparisons](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/guarded-regulation/activation-forks.json)

### Where established W-tap combos give the return away

Both seats of 38 ordinary native W-tap regulation mirrors supply 707
fresh-hit windows after at least three unanswered fresh hits. The
24 earlier fights and 14 recent regulation controls are kept separate
in the ledger. The same-cycle count agrees
under return-only and 1,100 ms activity rules. At K3+ there are 671
further hits, 25 returns and 11 right-censored windows. At K5+ the split
is 617 / 14 / 11, and at K10+ it is 509 / 10 / 10. Native score and
velocity-packet linkage preserve both seats. Five click streams have
FIFO coverage limitations. All 25 breaking presses link unambiguously.

Of the 25 returns, 22 score before the combo holder's next native
fresh-damage window. Six return clicks precede the server processing
the outgoing anchor. Another 16 come after the new knockback is
applied and before the defender's first movement under it. The final
three occur after that first movement. Every breaking press is a
sprint contact. Across the 671 successful continuation windows, zero
defenders make a sprint contact between the anchor's velocity apply
and first travel. This interval contains such a contact on 19 of the
25 failures, including three contacts distinct from the already-sent
breaking press. In the 14 failures at 80 ms, all returns score before
the next fresh opening. The samples show why changing only the input
at that opening commonly arrives too late. These are correlated
within-bout observations.

We also compare each completed within-combo cycle with the subsequent
window. If the prior follow-up landed in the first two server ticks,
5 of 639 resolved subsequent windows return and another 11 are
censored. If it landed two to four ticks after reopening, 4 of 12
subsequent windows return. If it landed five or more ticks late, all
nine subsequent windows return. For those nine, every sampled click
in the first 100 ms after the earlier reopen is beyond three blocks
even at the nearest edge of the target box. The twelve mildly late
windows have a selected contact in that same period. Those are
distinct problems at the same next-fresh deadline.

The own-view alarm uses the first head at 500 ms minus configured RTT
after the observed target hurt cue, with a newer cue canceling it.
Nearest-box range exceeding three blocks tags all nine severely late
precursors and none of the other 659 covered adjacent windows. Three
windows are unavailable. The alarm precedes the eventual return click
by 311–519 ms. This predicts a missed pursuit window on the inspected
clocks. The mildly late and near-timely returns need a different prediction.

### Moving one hit earlier to change the return

The strict model tests all 64 three-head sequences of back, release,
W without Ctrl and W+Ctrl at fixed action boundaries in the recorded
tails. Both arms preserve original physical click calendars and fork
the same native state and queues. The stock W-tap arm and own-input
interventions recompute the view and W-tap state forward. Factual
control checks reproduce accepted hits and knockback. A finite end in
m7111 truncates its matched test to 1.3 seconds. The original 2.2 s
result and its four uncovered cuts remain available.

After the final outgoing hit of either m7111 or m7244, none of the 64
words prevents the first return. In m7244 it is already committed.
In m7111 the modified input cannot reach and move the defender's view
in time. One hit earlier in m7111, back/release/sprint moves the
original return press from 2.875 to 3.311 blocks at the 300 ms action
cut. This gives more active cycles in all 25 tested queue variants
and changes the short margin by 0 to +1. It preserves own hit 66
timing nominally and improves no first-window count over W-tap.
In m7244, the own ray stays too far after the penultimate hit. Early
forward sprint brings the follow-up one tick closer, while the best
of 64 words still misses timely protected reach in all nine phases
at each of the three tested cuts.

The separated replay panel chooses the earliest predecessor of a
return in each of 14 other matches. The 25 queue variants per cut
include joint timing. At the earliest movement after that preceding
hit, back/release/sprint adds active cycles in 75 variants and loses
cycles in 20. Back/back/sprint adds cycles in 184 and loses cycles
in 58. At 300 ms the shorter retreat gains cycles in 121 and loses
them in 118, including missed windows W-tap had covered. These
starts are labeled using their subsequent outcome, so the counts
describe intervention authority rather than a legal selector.

| Earliest movement, 25 timing variants per recorded start | W-tap timely protected | Back/release/sprint timely protected | Back/back/sprint timely protected | Sprint for three heads timely protected |
|---|---:|---:|---:|---:|
| 14 known precursors | 100/350 | 100/350 | 100/350 | 100/350 |
| Nine earlier good cycles | 216/225 | 216/225 | 184/225 | 216/225 |

In m7207 at 80 ms the shorter retreat keeps our next hit at
552 ms and moves the original defense press from 2.884 to 3.144
blocks. W-tap's return at 704 ms is delayed to 1,251 ms. Continuous
sprint keeps the same own hit and advances the return to 652 ms.
In the wide m7213 start the shorter retreat increases our closest
sampled range near the fresh window from 3.962 to 4.286 blocks.
It pushes own follow-up into the return drain and costs the stock
protected follow-up in 16 of 25 variants. The two cases distinguish
good separation during pursuit from abandoning the next own reach.

### Acting on the attacker's received hurt cue

All 35 registered cue-start cuts reproduce the factual native hit and
knockback sequence. The first tested movement occurs after the attacker
actually observes the new target hurt cue. The mild retreat still gains
one or more active cycles in all 25 variants of m7111, m7207 and m7223.
In m7207 and m7223 it also gains accepted margin in all 25. On m7224
it gained cycles in 16 early-start variants, while the received-cue
start 56 ms after the server hit ties W-tap in every variant. On the
wide m7244 witness the same cue-driven movement gives up the successful
late follow-up in all 25. Across the 14 selected precursors, the
cue-driven shorter retreat adds cycles in 59 variants and loses cycles
in 20. Its short accepted-margin change ranges from 0 to +2.

The recent regulation control panel supplies its first K3, K5 and K10
per seat even if the combo later survives. The candidate changes only
the three movement heads after this cue in the simulation. Entries are
from native play. Paired continuations keep the exact original choices,
positions, pending network deliveries and click times through the cut.
Each recorded start has 25 timing variants.

| Earliest qualifying landmark | W-tap timely protected next hits | Back/release/sprint timely protected | Back/back/sprint timely protected | Sprint for three heads timely protected |
|---|---:|---:|---:|---:|
| K3, nine native starts | 150/225 | 125/225 | 100/225 | 150/225 |
| K5, six native starts | 125/150 | 125/150 | 75/150 | 150/150 |
| K10, six native starts | 125/150 | 125/150 | 59/150 | 125/150 |

At K3 the shorter retreat adds zero cycles and loses cycles in 25
variants. The heavier one adds cycles in 25 and loses them in 16,
while its timely protection is worse. At K5 the sprint sequence
makes more earliest-window hits while losing active cycles in 59
variants. Timing and chain survival therefore remain separate
metrics. Stock W-tap stays the established-combo reference. The
observed mechanics point to an earlier separation decision when
both players will re-enter on the same exchange, and a distinct
pursuit correction when the opponent is still outside own reach
at the next window. Selecting the right movement with only the
attacker's view remains open.

[Cycle audit and native counts](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/summary.json) ·
[Adjacent windows and delayed-hit labels](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/transfer/adjacent-native-windows.json) ·
[Own-view deadline alarm](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/transfer/observable-deadline.json) ·
[All development movement forks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/forks/summary.json) ·
[Short-clock m7111 forks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/forks/short-window-summary.json) ·
[Original m7111 return press under 25 timings](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/forks/m7111-queue-stress.json) ·
[Separated panel same-state comparisons](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/transfer/summary.json) ·
[Received-cue comparisons and K3/K5/K10 sensitivity](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/own-cue/summary.json)

### A fresh hit just before the defender lands

Link 671 factual adjacent fresh windows from the 38 W-tap regulation
mirrors to the received velocity packet and the defender's true
pre-application state. The fresh hit that leads to a severely late
continuation catches the defender airborne at exactly 0.309409 blocks
above the floor, falling at −0.350413 blocks/tick, in all nine cases.
Without the new vertical velocity, the next movement would land. The
other 662 receipts, including the twelve mildly late windows, find the
defender grounded. The comparison uses the actual defender POV for
this label. Its ground state can differ from both the attacker's view
and the server pose when the hit is accepted. Median horizontal launch
is actually lower in the severe group, 0.743750 versus 0.753875
blocks/tick in the on-time group.

The separately played m7244 witness applies its penultimate launch
0.268404 blocks above the floor. Approximate true horizontal gap
at that receipt is 5.361 blocks, using the most recent own movement
sample. The nine mirror failures are at 2.825–3.516. The m7244 shot
one cycle earlier also catches the defender 0.309409 blocks above
the floor, at a 2.876-block gap and with an on-time follow-up. These
two recorded receipts expose a separate spacing limit.

### Changing launch or contact before the wide window

The pre-hit model forks the last three available own movement heads
before the original physical shot. Its common prefix includes the
prior fresh hit, pending packets and both W-tap histories. Control and
candidate keep both physical click schedules. A timely recovery must
retain the original shot and land the next follow-up in the first
two drains of that shot's original fresh window, before a return.
Accepted margin and active cycles also score through the same 1.65 s
horizon, including branches whose opening shot changes.

The development sweep tests 69 ordinary movement sequences in four
failure witnesses and four preceding timely references. Three W-only
movement heads starting at the second last pre-press decision rank
first on that panel. This reduces the original server impulse to
0.527500 blocks/tick while retaining the original press. Both the
post-hit gap and timing improve on m7203 and m7216 in every
development variant. The rule is frozen before the comparison on six
separate previously exposed failures, including two from m7222.

| Post-establishment local movement contrast | Stock W-tap timely and protected | Three W-only heads timely and protected | W-only active-cycle gains / losses vs W-tap |
|---|---:|---:|---:|
| Four development wide starts, 9 timings each | 0/36 | 18/36 | 27 / 9 |
| Four development preceding cycles | 36/36 | 36/36 | 18 / 0 |
| Six comparison wide starts, 25 timings each | 0/150 | 66/150 | 116 / 0 |
| Four comparison preceding healthy cycles | 100/100 | 50/100 | 0 / 64 |

On m7222 the two original impulses of 0.712625 and 0.849750 become
0.527500. This preserves both original hits and makes both next fresh
hits timely and protected in all 25 timings, at +3 accepted margin
in each paired 1.65 s suffix. The earlier healthy reference on that
same recording instead loses an active cycle in all 25 with the
same W-only action. Equal motion on every contact fails the healthy
comparison despite the causal gains in the wide starts.

Two additional interventions isolate phase. One preserves the
original scoring hit, its launch vector, and all other packet
eligibility, while delaying that single S12 application across the
first defender movement. All 250 wide-start variants now apply on
the ground. It is a synthetic model intervention. The other uses
the same physical button press but turns the selected ray away for
that press and restores aim, with the same later input opportunities.
The press is chosen from the factual trace and needs an online
selector before use. Its original damage deadline remains fixed.

| Same-timing comparison, 25 variants per start | Stock W-tap timely and protected | Move velocity application past the movement step | One deliberate missed press |
|---|---:|---:|---:|
| Ten wide starts including m7244 | 0/250 | 202/250 | 0/250 |
| Eight preceding timely cycles | 200/200 | 50/200 | 0/200 |

For the wide starts, the successful missed-press alternatives trade
one opening chance for more active cycles and increased accepted
margin in 200/250 variants. The eight timely controls have 71 joint
gains and 79 short-margin losses. In m7244, crossing the landing step
improves the closest own sample near reopening from 4.843 to 3.728
blocks, still a miss. It scores one fewer accepted hit in all 25
variants. The same timing correction leaves the next hit outside
reach from this wider starting gap.

### Can the attacker spot the landing step in time?

Only attested inputs already available to the attacker are used for
the feature audit. These include received target relay heights and
on-ground flags, the locally observed hurt cue, configured RTT and
rendered pose. Eight scalar cutoffs are fitted on the four designated
example recordings at the second-last pre-press movement head. The
best recall on the separate panel comes from own-view distance. It
identifies one of six airborne receipts and raises 38 false alarms.

A separate physical interval reconstruction uses the quantized
vertical relay sequence, symmetric RTT and 0–100 ms additional
service delays. At the recorded local press, 120 grounded receipts
from 590 comparison windows are classified as safely at or beyond
the estimated landing boundary. None is classified as clearly
before landing. Of the six airborne receipts, five straddle that
boundary and one has no compatible flight interval. At the earlier
movement head, one airborne receipt is incorrectly classified as
after. Retain that counterexample to the assumed service model.
The practical boundary is the defender's client movement step,
whose timing is uncertain given the attacker's observed packets.
W-tap remains the playing and maintenance reference. All candidate
scores here are exact-prefix model comparisons on recorded inputs.

[Recorded launch and pre-hit pose data](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/native-launches.json) ·
[Pre-hit development results](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/development/summary.json) ·
[Separate ordinary movement comparisons](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/transfer/summary.json) ·
[Landing and missed-press interventions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/landing-probe/summary.json) ·
[Single-observation predictions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/landing-forecast/results.json) ·
[Flight timing intervals from local relays](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/landing-interval/results.json)

### Movement on both sides of the defender's landing step

On the four development clocks, test all 54 combinations of a
short pre-hit move and up to three movement heads following the
attacker's received target-hurt cue. The physical press schedules,
prior native state and queued packets are common. Each movement and
its W-tap control run twice. One branch uses the recorded recoil
application. The other moves that velocity packet past the defender's
next movement step. On the wide starts that is the landing step.
The intervention checks timing sensitivity. A playing bot does not
control the opponent's packet application.

| Panel of wide starts | W-tap timely unanswered follow-ups, recorded phase | W-tap timely unanswered follow-ups, crossed phase | Cuts with a move that gains cycles and score in both phases without a loss in any tested timing |
|---|---:|---:|---:|
| Four development cuts, 54 moves | 0/36 | 27/36 | 0/4 |
| Six previously exposed comparison cuts, six frozen moves | 0/141 | 118/141 | 0/6 |

Nine of 150 comparison variants at m7257 do not preserve the
original fresh shot in either stock branch. Both branches use the
same click schedule, but there is no valid paired launch at the
target batch. Their input and outcome records remain in the raw
results. Forcing W without sprint for three movement heads recovers follow-ups
in the recorded airborne branch, while often giving up cycles or
accepted hits when the same opponent would land before the recoil.
The plain W-tap arms and both stock movement words agree exactly on
the scored effects, hit timing and receipt pose.

Even choosing the best movement separately for every timing variant
in development, a choice that must work in both landing phases
improves accepted margin with no cycle loss in 0/36. Knowing which
phase occurred and choosing separately in each phase raises that to
17/36. On the separate comparison panel the same contrast is 23/141
versus 91/141 with the smaller frozen field. The latter picker can see
past the attacker's observations and future model outcomes. The
comparison establishes how much the tested moves depend on this
boundary. It leaves plain W-tap in place.

[Development forks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/crossed-landing/development/summary.json) ·
[Frozen comparison and unqualified variants](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/maintenance-cycles/prelaunch/crossed-landing/transfer/summary.json)

## What can produce a double

The historical scan covered 128 bouts with equal configured caps. Of 2,860
paired trades, 64 led to a post-trade double in either direction. The historical
10 CPS cohorts used several knockback profiles. The historical 20 CPS cohorts
were acquisition drills, so the scan excludes all hits before both bots began
clicking. The old linear specialist often closed its click gate and had a lower
measured rate than its opponent.

In 10 CPS match m1752, W-tap landed at 9.087 s. The other bot's client applied
the knockback at 9.105 s. Its sprint contact at 9.135 s reduced its horizontal
recoil from 0.854 to 0.513 blocks per tick. It also used a jump reset. Its next
two fresh hits landed at 9.636 s and 10.286 s. W-tap missed all six actual
clicks between those hits and briefly had a reachable ray on a sampled tick.
The trace shows both the advantage of the observed timing and its sensitivity
to when W-tap clicks.

In the 64-bout historical 20 CPS drill, 37 of 109 linked prompt returns were
consumed before the preceding follow-up hit landed. In 9 of 18 scoreboard
doubles in that drill, the defender generated a valid fresh return during the
double and the server processed it after the second hit. This makes actual
click and packet timing important even when a replay shows two clean hits.

An offline search also tried 125 fixed-click sequences of three two-tick
movement choices per state. It forked each of 32 modeled 10 CPS W-tap trades
from the same complete snapshot. The best sequence in hindsight improved
16 starts, but produced a double in just one. That case took two ticks of
forward pressure before returning to W-tap and extended one follow-up hit
to four. A separate earlier-fork test with a small set of forward and backward
inputs gave zero doubles. Adding jump and one strafe direction found one
earlier-fork double in the same 32 cases. This is a bounded search over
known schedules and a small action set.

[Protocol](boxing-double-protocol.json) ·
[Model search](BoxingDoubleSearch.java) ·
[Native runner](boxing_double_native.py) ·
[Playable policy](../../policies/boxing-spacing10.properties)
