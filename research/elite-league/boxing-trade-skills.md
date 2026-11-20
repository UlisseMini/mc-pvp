# Hit selection and jump exits against W-tap

The final grade is the accepted-hit score margin in native first-to-100 boxing
against plain W-tap at the same CPS. This study isolates opening hit selection
and defensive jumps. It keeps the first-to-100 grade and the established-combo
maintenance gate separate from the opening drill.

## Opening model results

Both fighters use plain W-tap movement with an ordinary physical click cap.
The first intervention changes only when the first click can be issued. A
counter counts when we accept the first fresh hit and land our first fresh
reply within 275 ms. The combo-start endpoint requires two more fresh hits
before any opposing accepted hit, with at most 1,000 ms between follow-ups.
Each row shares 64 deterministic starting configurations with W-tap. These
come from eight timing recordings, four client phase offsets and both seats.

| Six-block opening | Plain W-tap combo starts | Delayed-click combo starts | Mean score margin improvement over W-tap in 12 s |
|---|---:|---:|---:|
| 10 CPS, 0 ms, 150 ms after own hurt cue | 0/64 | 24/64 | +1.16 |
| 10 CPS, 0 ms, first admission at 650 ms from start | 0/64 | 19/64 | +0.94 |
| 20 CPS, 0 ms, first admission at 500 ms from start | 1/64 | 11/64 | +0.72 |
| 10 CPS, 80 ms, best single early-coast setting by starts | 1/64 | 2/64 | +0.41 |

An optimistic selector that knew each schedule in advance could choose among
the screened click-only delays. At 10 CPS and 0 ms, it found a combo start in
34 of 64 six-block openings. At 80 ms it found one in 64. Even a different
delay for each clock leaves considerable gaps. The separate early-coast
screen varied the starting range of the release and its length. One 80 ms
setting gained two starts with a small score gain, and none was close to
consistent. These are development results.

On a separate eight-recording historical panel, the 150 ms response produced
three-hit starts in 14, 2, 1, 0, and 0 of 64 openings at 0, 20, 40, 60, and
80 ms respectively. W-tap produced 0, 0, 0, 0, and 1. A stronger approach
brake that releases W until the hurt cue found five starts at 80 ms versus
W-tap's one, while lowering the mean accepted-hit margin by 0.92 over 12 s.
Both diagnostics retain the miss cases.

## Why landing first sometimes pays

In the m2065 schedule at 10 CPS and zero added RTT, the delayed arm took the
first hit at 477 ms and answered at 680 ms. Both first launches had horizontal
impulse 0.854 blocks per tick and vertical impulse 0.361. The selector landed
at 952 ms and W-tap at 1,150 ms, four ticks later. The selector's grounded
movement closed to a 2.553-block reach at its next effective click. W-tap's
clicks during its earlier damage window were out of reach. It came within
0.013 blocks of a possible return at the last sampled miss. The selector
landed fresh follow-ups at 1,228 and 1,678 ms.

With the same schedule and 80 ms added RTT, taking the first hit and waiting
150 ms after the hurt cue left W-tap in reach. It consumed a return at 954 ms
with a nearest hitbox distance of 0.679 blocks. The queued attack landed at
1,035 ms before the selector's first landing. An earlier projected landing
therefore needs a separate check of W-tap's reach and packet timing. Choosing
a later counter also opens the opponent's next opportunity before ours.

The useful starting region needs a landing lead, space to deny the opponent's
first legal return and enough forward travel to catch our next fresh-hit
window. Those conditions must persist across realistic variations of the
other client's click phase and views. No screened opening reached that
standard across the tested starts.

There is a repeatable restricted case in the m2110 schedule. At zero added
RTT, 10 CPS and six blocks, the delayed arm in seat A won this opening
endpoint on all four initial phase shifts. The opponent's closest sampled
return was 3.035–3.310 blocks from the hitbox. The selector's successful
follow-up consumed at 2.571–2.964 blocks. A finer 100-point timing grid gave
94 combo starts, versus one for W-tap. In seat B on that same grid, the
selector gave zero, versus eight for W-tap. The selected seat A cases
that succeeded countered 195–200 ms after the incoming fresh hit.
Of those, 86 observed both original landings, with a 153–244 ms lead.
The six failures include two late counters and four cases in which
changing click or return timing defeated another part of the sequence.
These phase shifts reuse one timing recording.

## Jump escapes

The one-jump diagnostic starts from W-tap mirror fights that give up at least
two unanswered fresh hits, then restores the complete state before the first
incoming hit. Replaying unchanged W-tap reproduces the exact accepted-hit
list in every branch. The impact arm requests one ordinary grounded jump when
its client receives knockback and retains W-tap movement and clicking.

| CPS and RTT | W-tap starts conceding at least two before replying | Impact jumps that cut it to one | Impact jumps that added a conceded hit | Mean paired 1.7 s score margin change |
|---|---:|---:|---:|---:|
| 10, 0 ms | 41 | 11 | 3 | +0.17 |
| 10, 80 ms | 41 | 6 | 1 | −0.07 |
| 20, 0 ms | 30 | 8 | 1 | +0.40 |
| 20, 80 ms | 33 | 5 | 2 | −0.06 |

Two privileged probes requested a jump before the baseline first hit, once
from a 75 ms lead and once from 125 ms. Some converted a two-hit loss into
a one-hit loss. None of the tested one-jump branches avoided all accepted
damage in the 1.7 s window. These states were deliberately chosen from mirror
fights with an incoming combo and describe the potential payoff in those
states. They do not measure the benefit of jumping in every trade.

## Native 12-second skill check

All 48 main-lab runs finished. Both fighters used the same configured CPS,
ordinary mouse tracking, three-block reach and zero added reaction delay.
The delayed arm held the first click until an elapsed match time of 650 ms
at 10 CPS and 500 ms at 20 CPS. This arm tests a fixed opening. The
simulator also tested a delay measured from the received hurt cue.

| CPS and RTT | Plain W-tap mean accepted-hit margin | Fixed opening delay | Impact jump |
|---|---:|---:|---:|
| 10, 0 ms | −0.25 | +0.75 | +1.75 |
| 10, 80 ms | +0.50 | +1.00 | +2.25 |
| 20, 0 ms | −0.25 | −4.75 | +1.50 |
| 20, 80 ms | −0.25 | +0.50 | −2.00 |

Each cell contains four independent games, with two per seat. Of 16 fixed-delay
games, 14 produced a counter within 275 ms, four observed the selector
landing first, two produced one follow-up hit, and none produced two. Plain
W-tap had one such follow-up and no three-hit start in its 16 mirror games.
The native jump arm was stronger in this small 10 CPS panel at both latencies,
with relative mean gains of two and 1.75 accepted hits over 12 seconds. The
20 CPS jump results moved in opposite directions at the two latencies.

The client traces explain why a landing lead alone is insufficient. In six
of eight 80 ms fixed-delay openings, W-tap landed its next fresh hit on the
ninth server tick after its first hit. In the two others it returned on tick
11. W-tap still had a usable attack even when the selector eventually landed
earlier.

## Direct hurt-cue opening

The 150 ms rule starts its wait at the first fresh hurt-status message the
selector's own client handles. The input loop admits the first click after
that wait, with a fallback 1,100 ms after the match starts. W-tap movement,
mouse aiming and the ordinary 10 CPS limiter remain in effect. The opening
endpoint stays at an accepted counter within 275 ms of receiving the first
accepted hit, then two more fresh hits before any accepted return.

Eight timing recordings from the earlier spacing-policy study were unused
in selecting this delay. The model replayed each recording from the same
six-block starts for both arms, across both seats and four phase offsets.
These recordings had already been examined for the spacing-policy study.

| RTT | Plain W-tap three-hit starts | 150 ms three-hit starts | Plain W-tap mean 12 s margin | 150 ms mean 12 s margin |
|---|---:|---:|---:|---:|
| 0 ms | 2/64 | 5/64 | 0.00 | −0.64 |
| 20 ms | 0/64 | 2/64 | 0.00 | −1.33 |
| 80 ms | 0/64 | 0/64 | 0.00 | −0.36 |

At zero added latency, the rule made 52 timely counters versus W-tap's 15.
It secured a following fresh hit in 11 of those 52, while W-tap secured one
in 10 of its 15. Forcing the delayed-counter shape increased the number of
attempts much more than the number of openings converted.

The prospective native schedule was frozen before play. It used four
independent 12-second matches per arm at each latency, two per seat. The
audit checked the actual first press against the received cue and verified
that all physical presses respected the shared 10 CPS cap. The opening
gate necessarily withholds some clicks. After it opens, both arms use the
same W-tap controls and pacing.

| RTT | Plain W-tap three-hit starts | 150 ms three-hit starts | Plain W-tap accepted-hit margins | 150 ms accepted-hit margins |
|---|---:|---:|---|---|
| 0 ms | 0/4 | 1/4 | 0, −1, −1, +1 | −3, +2, −4, −1 |
| 20 ms | 0/4 | 2/4 | −1, −1, −1, 0 | +3, −1, −1, +7 |
| 80 ms | 0/4 | 0/4 | +2, +2, 0, −3 | +8, −4, −4, 0 |

The selector won all three successful opening sequences in seat A. In
m4438, m4446 and m4459, both initial hits applied about 0.854 horizontal
and 0.361 vertical blocks per tick, and the reply scored 200–203 ms after
W-tap's first hit. The selector's client landed while W-tap's first flight
was still in progress. Each logged W-tap miss in its first fresh window
was outside the three-block reach envelope. In m4459 W-tap missed at
3.174 blocks shortly after that window opened. The selector scored six
fresh hits in a row and won the drill 16–9.

The losing m4449 opening had the same launch magnitudes and a 202 ms
reply. W-tap still had a 2.456-block ray at its next click. The attack
left the client 425 ms after its first hit and was accepted 454 ms after
that hit, on tick nine. The two attacks were paired through the native
packet trace. This failed before the selector completed its first landing.
The W-tap client had sprint-attacked immediately after receiving the
counter knockback and before taking its first movement step. That contact
did zero accepted damage and cut its backward velocity from 0.854 to
0.513 blocks per tick. In m4459 the first movement step had already used
the full impulse before W-tap made its next sprint contact. This is a
measured timing difference that explains part of the spacing difference.
The two native starts also differ in client phase and replica position.
All four 80 ms trials missed the 275 ms reply limit. Their replies scored
303–348 ms after the first hit.

The 12 W-tap mirrors also supplied new timing calendars collected after
the rule was frozen. Those calendars contain host event opportunities and
exclude attack outcomes and packet payloads. They were replayed in the
model across both seats and four offsets under identical timing within
each pair. These simulations also yielded more opening attempts than
converted advantage.

| RTT | Plain W-tap three-hit starts | 150 ms three-hit starts | Plain W-tap mean 12 s margin | 150 ms mean 12 s margin |
|---|---:|---:|---:|---:|
| 0 ms | 4/96 | 6/96 | 0.00 | −0.38 |
| 20 ms | 1/96 | 3/96 | 0.00 | −0.99 |
| 80 ms | 1/96 | 0/96 | 0.00 | −0.20 |

These native examples establish that hit selection can start a combo
against W-tap. Trigger timing and similar knockback magnitudes did not
guarantee the spacing needed to deny its next attack. The 150 ms rule
stays an opening diagnostic. These short bouts leave the first-to-100
grade and established-combo maintenance comparison to separate matches.

## First movement after the counter

The model fork changes the defender's horizontal velocity immediately
before its first movement under the counter knockback. It preserves the
clicks already consumed, sprint state, replica state, both packet queues
and all clock events. In one direction it restores full recoil after a
native modeled sprint brake. In the other direction it applies the same
40% brake to a start that receives full recoil. The intervention is a
physics diagnostic. Both bots keep using the original input policies.

At 10 CPS, the study used twenty previously examined host calendars,
four phase shifts, both seats, and opening gaps of five to seven blocks.
The unchanged fork reproduced the entire accepted-hit sequence in all
903 timely-counter starts. The simulator isolated the factor from the
delayed opening by retaining both the unchanged W-tap defender against
the delayed arm and a separate plain W-tap mirror at each exact start.

| RTT | Plain W-tap mirror combo starts | Delayed opening against unchanged W-tap | Starts erased by adding brake | Starts rescued by restoring full recoil |
|---|---:|---:|---:|---:|
| 0 ms | 11/480 | 80/480 | 73/263 | 33/134 |
| 20 ms | 3/480 | 29/480 | 28/250 | 23/196 |
| 80 ms | 2/480 | 1/480 | 1/39 | 0/21 |

The application arm erased all 102 of its original combo starts and
created six different starts. The restoration arm created 56 starts and
lost eight. At 80 ms, allowing the full first impulse still rescued zero
in the 21 originally braked and timely cases. Each timing family repeats
some of the same host opportunities. These are paired scenario counts.

The matched native traces agree that full initial recoil can still leave
W-tap a fresh return. In the 150 ms arm there were six timely counters.
W-tap took a full first recoil step in five of them, and three became
combo starts. In m4449 an immune sprint contact cut the first step from the
full-recoil example's 0.727 blocks to 0.385. In m4448 and m4453 W-tap took
the full step and returned at the first fresh window with 2.987 and 2.543
blocks from the selector's hitbox at its return click. Neither W-tap mirror
nor the delayed arm achieved a three-hit opening at 80 ms.

The normal remote arm-animation messages provided sparse observations of
the opponent's local click phase. Before the delayed bot's first press in
the 12 native trials, W-tap made 78 local swings and the selector had
handled 38 remote swing messages. In 10 trials at least one swing had
already occurred after the latest remote animation was handled. In
m4459 the last visible animation was 161 ms old, while the opponent had
swung again 2 ms earlier. A phase estimate from these cues must allow
missed or delayed swings. The native recordings do not establish that an
own-client policy can control which side of the opponent's first movement
step the next click lands on.

## Spacing before the first reply

The next screen isolated ordinary movement just after the selector received
its first hurt cue. Each setting used one to three movement steps of either
coasting or holding S. It resumed W-tap before the click gate opened. Plain
W-tap and the same cue delay without footwork shared the six-block and
neighboring five- and seven-block starts, both seats, the same timing and
the same 10 CPS cap. All 12 fixed settings missed the score qualification
across seats and latencies on the development panel.

| RTT | Plain W-tap combo starts | 100 ms cue alone | 100 ms cue and one backstep | Backstep margin vs plain W-tap in 12 s |
|---|---:|---:|---:|---:|
| 0 ms | 1/192 | 12/192 | 20/192 | −0.52 |
| 20 ms | 0/192 | 16/192 | 11/192 | −0.41 |
| 80 ms | 1/192 | 3/192 | 3/192 | +0.11 |

At zero RTT, the 100 ms backstep reduced timely counters from 173 to 150
even as it increased three-hit starts. With the 150 ms gate the backstep
moved the selector's first aim ray in m2065 from 2.544 to 3.005 blocks at
the same 641 ms input opportunity. The reply missed. W-tap hit again at
977 ms, and the selector's first reply arrived at 1,035 ms. This explains
why adding separation before the outgoing contact can cost the very hit
the opening needs. These are simulator development results. No footwork
setting qualified for native promotion.

## Spacing after W-tap has braked

The follow-up diagnostic kept the first reply intact. It used six-block,
10 CPS openings with a 100 ms cue delay and took every development start
with a timely counter and an ordinary W-tap sprint contact before the
defender's first recoil movement. The fork begins just after that step.
Both copies retain all native input and packet queues. The unchanged
branch reproduced the full accepted-hit sequence through three seconds
in all 102 included starts.

On each exact state, an offline search tried all 81 combinations of
ordinary W-tap movement, coasting and S at the selector's next four
input ticks. The normal click cap and click policy remained in effect.
The plain W-tap column uses the same original start. The unchanged cue
and oracle columns are forks from the same braked counter state.

| RTT | Plain W-tap mirror combo starts | 100 ms cue with unchanged movement | Any successful four-input word |
|---|---:|---:|---:|
| 0 ms | 0/48 | 0/48 | 5/48 |
| 20 ms | 0/26 | 0/26 | 2/26 |
| 80 ms | 0/28 | 0/28 | 0/28 |

The search could choose a different word using the completed simulator
trajectory for every state. Its zero at 80 ms bounds this specific
four-input window after the defender's first recoil step. Earlier inputs,
other starting geometry and other kinds of movement remain outside the
test. This result points to creating the separation earlier in the
approach, while preserving enough reach for the counter.

## Spacing during the grounded approach

Thirty-six settings tested short grounded backsteps or diagonal sprinting
before the first incoming hit. The movement began from an own-client
distance threshold, ended immediately on a hurt cue and left clicking to
the same 100 or 150 ms reply gate. The development starts used 10 CPS,
a six-block gap, eight calendars, four phase shifts, and both seats.
Every setting lost to the straight W-tap mirror on at least one seat
and RTT. Two representative 100 ms settings illustrate the tradeoff.

| RTT | Plain W-tap combo starts | Straight cue-only starts | Two early backsteps | Two diagonal steps | Backstep / diagonal mean 12 s margin vs W-tap |
|---|---:|---:|---:|---:|---:|
| 0 ms | 0/64 | 2/64 | 13/64 | 6/64 | +0.22 / −0.45 |
| 20 ms | 0/64 | 7/64 | 7/64 | 6/64 | −0.25 / −0.23 |
| 80 ms | 1/64 | 0/64 | 1/64 | 0/64 | −0.69 / +0.78 |

The backstep starts at a 3.4-block nearest-hitbox distance. The diagonal
steps start at 4.1 blocks. Both arms still made 63 timely counters at
80 ms, so most failures there occurred after the reply. In the adverse
recoil diagnostic the backstep kept nine combo starts at 0 ms and three
at 20 ms. The diagonal kept three at 0 ms and zero at 20 ms. At 80 ms
both had zero. The sole backstep success there lost all four follow-ups
when the fork applied W-tap's 40% brake before its first movement.
The unchanged simulator branches reproduced the hit lists. These are
development results and neither approach entered native validation.

## Hit selection when both fighters re-approach

The model also tested the delay after the opening, from states produced by
plain W-tap mirrors. Before the next own input, both fighters had feet on the
ground and were again eligible to take fresh damage. The selector was closing
with a 3.05–3.85-block nearest-hitbox distance on its own view. Ground and
fresh eligibility for both fighters are offline selection criteria. Every arm
retained the same complete physics state, applied controls, policy history
and queued clicks and packets. Both players kept plain W-tap movement. The
intervention withheld new physical clicks for 50–350 ms from the cut, or
until 0–150 ms after the next locally handled hurt cue, with a 600 ms fallback.
The cap remained 10 or 20 CPS in both arms. Actual click counts could differ.

Eight development calendars and eight separate previously exposed calendars
that supplied no examples were screened with the same protocol. There were
192 initial and 1,384 mid-fight cuts across both panels. Each compared W-tap
and all delays over 2.5 seconds at three future opponent timing offsets
(0, +5 and +15 ms). Paired branches keep the work already queued. All nominal
W-tap forks exactly reproduced the parent hit list. The offsets on the same
cut and nearby cuts in the same bout are correlated.

| CPS and added RTT | Mid-fight cuts | W-tap three-hit starts across all offsets | One delay counters into three hits across all offsets | Some delay per offset counters into three hits |
|---|---:|---:|---:|---:|
| 10 CPS, 0 ms | 123 | 0 | 14 | 22 |
| 10 CPS, 20 ms | 114 | 0 | 0 | 2 |
| 10 CPS, 80 ms | 107 | 1 | 1 | 2 |
| 20 CPS, 0 ms | 109 | 0 | 3 | 5 |
| 20 CPS, 20 ms | 110 | 0 | 1 | 1 |
| 20 CPS, 80 ms | 109 | 0 | 1 | 2 |

The table uses the separate eight-calendar panel. A counter requires taking
the first fresh hit, replying within 275 ms and then landing two more fresh
hits without an opposing accepted hit or a gap over 1,000 ms. The W-tap
column also credits starts where W-tap lands first. The last column can
choose a different delay for each fully known simulated future. The one
stable 10-CPS result at 80 ms already occurred with plain W-tap. The 20-CPS
result at 80 ms was a new start and became a development witness for the
finer sensitivity check.

One repeatable scoring effect was distinct from the three-hit starts. At
20 CPS and 20 ms, waiting only until the hurt cue improved the summed
2.5-second accepted-hit margin by 97 across 342 development forks and
155 across 330 comparison forks. In the latter it improved 155, tied 115
and worsened 60, with paired differences from −6 to +5. The gains were
positive on both seats and every comparison calendar. Fresh-hit margins
fell by 65 and 29, while upgrade margins rose by 162 and 184 respectively.
The delay averaged 2.19 fewer actual presses in the comparison panel.
At 80 ms the accepted-hit margin for the same intervention fell by 53
and 11 on the two panels. These scores describe single short interventions
from selected states. They do not verify a match-length input policy or
established-combo maintenance.

Two exact witnesses show where the timing breaks. In m4583 seat B at 20 CPS
and 20 ms, the selector was 3.668 blocks from the visible hitbox and moving
toward it at 0.153 blocks per tick. The hurt+100 ms branch replied 199 ms
after the incoming fresh hit. It landed 220 ms before W-tap. After W-tap's
next fresh window opened, the nearest of five sampled W-tap attack rays
before the first fresh follow-up was 3.201 blocks. A +15 ms change to the
selector's future client schedule moved the reply to an earlier server drain.
W-tap then reached at 2.780 blocks and scored its return.

In m4972 seat B at 20 CPS and 80 ms, the selector was 3.838 blocks from
the visible hitbox and closing at 0.150 blocks per tick. It held clicks for
150 ms and replied 54.7 ms after the opening hit. The first W-tap ray
sampled after its fresh window opened was 3.020 blocks. Adding just 5 ms to
the selector's future client opportunities kept the same two accepted hit
times and the same W-tap position at the return click. Its client rendered
the selector about 0.112 blocks closer along the approach axis and the ray
shortened to 2.913 blocks. Both players landed the next fresh hit in the same
drain, breaking the combo.

| Witness and 18 paired future schedules | W-tap three-hit starts | Selected-delay three-hit starts |
|---|---:|---:|
| m4581 A, 10 CPS / 20 ms | 0/18 | 4/18 |
| m4583 B, 20 CPS / 20 ms | 0/18 | 6/18 |
| m4972 B, 20 CPS / 80 ms | 0/18 | 6/18 |

The witness grid adds 0, 5 or 15 ms to the selector and 0 through 25 ms
in 5 ms increments to W-tap, after the same committed start. m4972 passed
all six W-tap offsets with the original selector timing and failed all twelve
with either selector offset. W-tap controls and delays received the same
timing perturbation for every pair. This is simulator sensitivity. The
native 150 ms hurt-cue trials above remain the independent opening reference.

[Re-entry experiment](BoxingReentryTiming.java) ·
[Frozen panel protocol](../../build/boxing-trade-skills/reentry/frozen.json) ·
[Development results](../../build/boxing-trade-skills/reentry/development-with-chains.jsonl) ·
[Separate calendar results](../../build/boxing-trade-skills/reentry/comparison-with-chains.jsonl) ·
[Witness grid and settings](../../build/boxing-trade-skills/reentry/witness-plan.json) ·
[20 ms witness](../../build/boxing-trade-skills/reentry/m4583-witness.jsonl) ·
[80 ms witness](../../build/boxing-trade-skills/reentry/m4972-witness.jsonl)

## Hit selection under changes to both clients' timing

The finer sweep forked 656 qualifying approaches on nine recorded native
W-tap clocks. It used the recorded starting tracker phase, client movement
send opportunities and a transport estimate from recorded movement and
replication packets. The 20-CPS clocks also supplied timing for simulated
10-CPS input. Each fork tried a 5 ms delay grid from the cut or the first
local hurt cue. We compared every delay with plain W-tap at the same complete
state and tested all nine combinations of 0, 5 and 15 ms shifts to the
two clients' unexecuted opportunities. Existing queues stayed committed.
The zero-shift W-tap branch reproduced each parent hit list exactly. This is
a sensitivity grid on already exposed native clocks. It is not a measured
distribution of timing jitter.

The table uses the five comparison clocks, separate from the four development
clocks. Each candidate count gives cuts for which at least one delay obtained
the first reply within 275 ms of taking the first fresh hit and completed
the indicated number of unanswered fresh hits in every timing variant.
The plain W-tap count can also start the chain by hitting first. The same
delay must work for all nine variants of a cut. It can vary between cuts.

| CPS / added RTT | Approach cuts | W-tap doubles / three-hit starts | One delay, doubles / three-hit starts | Pick a delay per future, three-hit starts |
|---|---:|---:|---:|---:|
| 10 / 0 ms | 76 | 1 / 0 | 25 / 10 | 17 |
| 10 / 20 ms | 43 | 1 / 0 | 15 / 2 | 4 |
| 10 / 80 ms | 64 | 0 / 0 | 4 / 0 | 0 |
| 20 / 0 ms | 76 | 0 / 0 | 17 / 2 | 4 |
| 20 / 20 ms | 39 | 1 / 0 | 9 / 1 | 3 |
| 20 / 80 ms | 71 | 1 / 0 | 1 / 1 | 1 |

All ten 10-CPS zero-RTT three-hit starts came from m4869. The 20-CPS
80 ms double also occurred with plain W-tap. Its one timing-stable
three-hit start changed the 2.5 s accepted margin by −3 to +6 across the
nine variants. Even the per-future picker found no other consistently
successful release there. The picker can use future outcomes and covers
only the tested release delays. Cuts and variants from the same match
are correlated.

At 20 CPS and 20 ms, m4761 seat A was closing at 0.153 blocks per tick
from 3.603 blocks on its own view. W-tap saw 3.636 blocks in the other
direction. The useful deadline was 220–245 ms after the cut or 75–105 ms
after the local hurt cue, at 5 ms resolution. The 225 ms branch took
the first hit and replied 151 ms later. Its client was 0.637 blocks above
the floor when it consumed the attack. It then landed 141–171 ms earlier
than W-tap. After the selector's immunity from the initial hit expired,
W-tap's closest eligible sampled ray before the next fresh hit was
3.026–3.302 blocks across the nine variants. The selector improved its
accepted margin by 3–4 hits over the W-tap mirror in every variant.
The selector's timing advantage emerged at the first follow-up, after
both initial hits had already happened.

Waiting only for the local hurt cue gave +123 summed accepted margin
relative to W-tap across the 351 sampled 20-CPS, 20 ms timing variants
of the comparison panel (+15 fresh, +108 upgrades), with per-variant
changes from −4 to +7. The same intervention gave −135 accepted margin
at 80 ms (−166 fresh, +31 upgrades). This short-window scoring effect
does not validate a fixed delay or establish a full-match win.
The experiment selected cuts using both ground states and server fresh-hit
eligibility. Those are offline diagnostics. A native re-entry trial and
an own-view trigger still need validation. Retain the native opening drills
above as separate evidence and the K3/K5/K10 maintenance gate as a separate
continuation measurement.

[Release experiment](BoxingReentryRelease.java) ·
[Frozen protocol](../../build/boxing-trade-skills/release-envelope/frozen.json) ·
[Per-clock results](../../build/boxing-trade-skills/release-envelope/summary.json) ·
[20 ms witness](../../build/boxing-trade-skills/release-envelope/witness-m4761.json) ·
[80 ms witness](../../build/boxing-trade-skills/release-envelope/witness-m4890.json)

## Attack-packet timing and the visible height cue

The native mirror recordings show how much attack arrival can differ from
the movement-derived transport used in the replays. At 20 ms, the estimate
put 1,267 of 1,269 factual attack clicks in the correct server drain.
At 80 ms it put 3,577 of 3,713 in the correct drain. The observed receipt
time itself put 1,269 and 3,711 in the correct drain, respectively.
The range between the earliest per-seat fifth percentile and latest
95th percentile of the residual was −0.009 to +1.215 ms at 20 ms and
−3.151 to +5.240 ms at 80 ms. These are measurements conditional on
the recorded clicks. They describe timing accuracy for this cohort.

We crossed the nine client-phase variants with independent attack-only
receipt shifts for the two players, after the same committed cut.
The 20 ms offsets were 0, +0.47 and +1.20 ms per player. The 80 ms offsets
were −3, 0, +0.47 and +5.25 ms per player. Existing packets and the FIFO
clamp stayed in place. A constant shift for each player is a sensitivity
test, with no implied probability for any row of the grid.

| Witness at 20 CPS | Timing variants | W-tap three-hit starts | Selected delay three-hit starts | Change in 2.5 s accepted margin versus W-tap |
|---|---:|---:|---:|---:|
| m4761 A, 20 ms, wait 225 ms | 81 | 0 | 81 | +2 to +4, median +3 |
| m4890 A, 80 ms, wait 120 ms | 144 | 128 | 144 | −3 to +6, median 0 |

The full 220–245 ms cut-relative and 75–105 ms hurt-relative bands at
20 ms kept the three-hit start across the expanded grid. The first
response accepted 151 ms after the incoming hit in the 225 ms branch.
An earlier branch replied after 105 ms, landed 79 ms ahead of W-tap,
and both scored the next fresh hit together. A later branch replied
after 199 ms and landed 202 ms ahead of W-tap. W-tap found a 2.827-block
ray and returned before the second fresh hit. The selector's local
height when it consumed the reply was 0.361, 0.637 and 0.829 blocks
above the floor, respectively. The return outcome depended on spacing
and who could use their first eligible window, beyond the landing lead.
These are three traced branches of one model state.

We screened release thresholds of 0.30, 0.60 and 0.80 blocks after the
local hurt cue, with a 600 ms fallback. The 0.60 threshold recovered
the m4761 start across all nine original client phases and one more at
zero added RTT on m4869. The threshold uses own height once the experiment
has armed the gate at a qualifying approach. Selecting the approach still
uses offline evidence.

| Added RTT, 20 CPS | Approach cuts | W-tap stable three-hit starts | Height 0.60 stable three-hit starts | Mean accepted-margin change versus W-tap at height 0.30 / 0.60 / 0.80 |
|---|---:|---:|---:|---:|
| 0 ms | 117 | 0 | 1 | −0.27 / −1.04 / −1.17 |
| 20 ms | 79 | 0 | 1 | −0.36 / −0.82 / −1.02 |
| 80 ms | 137 | 0 | 0 | −0.40 / −0.25 / −0.14 |

These are the same nine previously exposed clocks, scored over the nine
joint client-phase variants at each cut. Model evidence with a matched
W-tap branch supports the local height cue for the two individual states.
There is no matched native height-gate cohort. The negative average across
all three thresholds and latencies leaves the general height rule
unqualified for promotion. The next causal check is whether the relative
replica trajectory during the approach predicts denying the first return,
including after W-tap's earliest sprint contact.

[Frozen attack-shift protocol](../../build/boxing-trade-skills/attack-sensitivity/frozen.json) ·
[Factual native receipts](../../build/boxing-trade-skills/attack-sensitivity/factual-receipts.jsonl) ·
[20 ms traced reply boundaries](../../build/boxing-trade-skills/attack-sensitivity/m4761-trace-0-0.json) ·
[Native-style height cue screen protocol](../../build/boxing-trade-skills/attack-sensitivity/lift-plan.json)

## What the selector can see before it delays

At each of the 333 marked 20-CPS approaches, we recorded the selector's
own input-head view through the decision cut. The inputs were own motion,
visible hitbox range and height, recent observed hurt and contact times,
and closing speed measured from the replica's last one or three heads.
All nine timing forks shared that view. The recorder preserved the W-tap
and overlapping counter scores from the prior sweep.

At each RTT, a one-threshold rule chose between plain W-tap, immediate
release on the hurt cue, 90 ms after the cue, 0.60 blocks of own lift,
and 225 ms after the cut. We chose the threshold on four development
calendars and froze it before scoring five separate, previously exposed
calendars. The mark for when to consider delaying still used offline
server eligibility and both ground states. The threshold used only the
recorded local view.

| Added RTT | Comparison cuts | Plain W-tap | Best constant from development | Fitted approach-view rule | Stable three-hit starts, W-tap / fitted rule |
|---|---:|---:|---|---|---:|
| 0 ms | 76 | 0 | +0.14, hurt cue | +0.14, same constant | 0 / 1 |
| 20 ms | 39 | 0 | +0.35, hurt cue | +0.19, cue age split | 0 / 0 |
| 80 ms | 71 | 0 | 0, W-tap | −0.08, range split | 0 / 0 |

Margins are mean changes in accepted hits over 2.5 seconds against
matched W-tap. At 20 ms the cue-age split also lost 0.03 fresh hits per
fork versus W-tap, while the constant gained 0.04. At 80 ms that
split's accepted-margin changes ranged from −7 to +3. Replica motion
features did not improve on the chosen development rules. This screen
has one development and one comparison clock at 20 ms. Its fitted rules
remain model diagnostics, with no matched native arm.

We also scored the full 127-arm release sweep on the same comparison
states. For each cut, one picker knows the mean outcome across the nine
timings and chooses a single delay. The other picker additionally knows
which future will occur and can choose separately for each one. Both
can keep W-tap. This bounds gains within the tested delays and states.

| Added RTT | Plain W-tap | Best single delay per cut, mean | Best delay per future, mean | Cuts with a strict gain in every timing / total |
|---|---:|---:|---:|---:|
| 0 ms | 0 | +0.60 | +1.04 | 7 / 76 |
| 20 ms | 0 | +1.05 | +1.52 | 4 / 39 |
| 80 ms | 0 | +1.01 | +2.07 | 3 / 71 |

At 80 ms, picking the single best-mean arm still lost in at least one
timing variant on 41 of the 71 cuts. Since the pre-arm observations
were identical across timing variants, a selector acting at this point
could not distinguish those futures from the recorded approach view.
All these clocks are now exposed for any follow-up work.

The earlier native hurt-150 trials also yielded two similar pre-click
views at 20 ms. In m4449 and m4459 the selector's own longitudinal
velocity was −0.284 blocks/tick and its feet were 0.829 blocks above the
visible opponent. The opponent replica appeared to approach at 0.220
and 0.224 blocks per 50 ms over the preceding observation interval.
The visible hitbox was 2.310 and 2.219 blocks away. One selector gave
up the first return and the other got five more fresh hits. W-tap's
early sprint brake after the reply differed, as recorded in the native
audit above. These are retrospective observations from the delayed arm
of that study. W-tap's first presses occurred before there were three
prior logged heads, so these examples do not qualify as a matched
native test of the approach-view rule.

[Frozen design](../../build/boxing-trade-skills/approach-view/frozen.json) ·
[Model scores and per-clock spread](../../build/boxing-trade-skills/approach-view/summary.json) ·
[All-delay bounds](../../build/boxing-trade-skills/approach-view/all-tested-delay-bounds.json) ·
[Native pre-click observations](../../build/boxing-trade-skills/approach-view/native-openings-prepress.json)

## Footwork after W-tap brakes its recoil

We searched all 343 sequences of three ordinary movement inputs after W-tap's
first movement step following the counter. Each step could retain the usual
W-tap input, sprint forward, walk forward, release forward, backstep, or sprint
forward on either diagonal. The movement results come from exact forks with the
same click cap, queues, and prior reply. Both players returned to the usual
controls after the three modified inputs. The unmodified continuation
reproduced the complete delayed-reply hit list.

The main panel used the 0.60-block release and required a timely reply and
horizontal recoil at exactly 60% of the received impulse immediately before
W-tap's first movement. The whole-approach plain W-tap arm retained the same
20 CPS, start, clock, and latency. The conditional search also has a delayed
reply with stock movement as its exact continuation comparator.

| Added RTT | Naturally braked model variants | Plain W-tap three-hit starts | Delayed reply, stock movement | Any footwork three-hit starts | New starts beating both score controls |
|---|---:|---:|---:|---:|---:|
| 0 ms | 645 | 12 | 5 | 72 | 66 |
| 20 ms | 480 | 2 | 12 | 60 | 48 |
| 80 ms | 1,042 | 17 | 0 | 0 | 0 |

The last two columns can use a different sequence in each future and each
approach. Requiring the same sequence across all observed braked variants
of a cut, with at least two variants and a strict margin gain over both
controls, left three cuts at zero RTT, one at 20 ms, and none at 80 ms.
The 20 ms case had only three braked phases. Including full-recoil phases
removed that consistent score gain and three-hit start. These diagnostics
use previously exposed calendars and have no matched native movement arm.

Three consecutive backsteps consistently recovered the m4869 seat A example
at zero RTT across all nine phases, with a +2 to +3 paired accepted-margin
gain over plain W-tap. The same fixed sequence lost score across the full
screen of timely replies, pooling both recoil classes.

| Added RTT | Timely reply variants | Plain W-tap mean margin | Delay only mean margin | Delay and three backsteps mean margin | Backsteps' paired range versus W-tap |
|---|---:|---:|---:|---:|---:|
| 0 ms | 891 | +0.08 | −0.87 | −0.42 | −6 to +4 |
| 20 ms | 636 | +0.00 | −0.72 | −0.47 | −7 to +4 |
| 80 ms | 1,196 | +0.02 | −0.19 | −0.26 | −9 to +6 |

For the two previously timing-stable witnesses, we forced the horizontal
factor to 0.60 just before the first defender step, leaving the existing
packets and sprint flags in place. This is a single-variable recoil stress
test. Both delay-only witnesses dropped from nine of nine three-hit starts
to zero. Plain W-tap had zero of nine on the 20 ms approach and eight of
nine on the 80 ms approach without the recoil intervention. The entire
footwork search recovered three variants at 20 ms and zero at 80 ms.

On m4761, the three backsteps shifted W-tap's first eligible return ray
from 2.803 to 3.060 blocks on the nominal clock. W-tap still caught the
selector at 2.960 blocks on a later click and traded the next fresh hit.
With the selector's future client opportunities 15 ms later, the same
backsteps kept that later ray at 3.108 blocks. The selector reached the
next three fresh hits unanswered. The difference was preserving the
return gap through the selector's follow-up, beyond the first miss.

[Frozen protocol](../../build/boxing-trade-skills/reentry-footwork/frozen.json) ·
[Conditional search results](../../build/boxing-trade-skills/reentry-footwork/summary.json) ·
[Fixed backstep results](../../build/boxing-trade-skills/reentry-footwork/back-three-summary.json) ·
[20 ms causal trace](../../build/boxing-trade-skills/reentry-footwork/m4761-backstep-trace.json)

## Starting the footwork from the client's own cues

We moved the start of the same 343 three-input sequences to the first
input head following the selector's own hurt cue or the first head
following its first post-hurt local contact. Both cues are available
to that client. The original gate still marks the approach using offline
evidence. Each fork retains the pending clicks, packets and observations
at its own checkpoint. The zero-change sequence reproduced the entire
stock delayed-reply hit list. Earlier movement could cause the reply to
miss, which counted as a failed start. All arms retained 20 CPS.

| Added RTT | Naturally braked variants | Plain W-tap doubles / three-hit starts | Delay only doubles / three-hit starts | New starts after first recoil step | New starts at own contact cue | New starts at own hurt cue |
|---|---:|---:|---:|---:|---:|---:|
| 0 ms | 645 | 65 / 12 | 83 / 5 | 84 / 66 | 78 / 66 | 150 / 127 |
| 20 ms | 480 | 39 / 2 | 41 / 12 | 62 / 48 | 56 / 51 | 135 / 98 |
| 80 ms | 1,042 | 52 / 17 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

A new start beats both control margins over 2.5 seconds and makes a double
or three-hit start where the stock delay failed to make that length of
chain. The word can differ across rows and futures in this table. The
braked subset is selected from the delay-only arm of each pair. Plain
W-tap can also start its chains by hitting first. At 80 ms in this subset,
the contact cue preceded the first recoil step by a median of 90 ms and
the hurt cue by 238 ms. The lack of score-improving doubles persisted
even when the selector could change its movement before its reply.

Across both recoil classes, one fixed sequence per approach gained
margin over both controls and made a three-hit start through all nine
phases on eight zero-RTT approaches from the contact cue, and four
from the hurt cue. Those cuts came from two clocks and the sequences
can differ by cut. There were zero such cuts for either cue at 20 or
80 ms. The clocks had already been used for diagnosis. This mode has
no matched native footwork arm.

In m4761 seat A at 19.285 seconds, one backstep at the hurt cue kept
the initial reply on the same drain. On the nominal clock it changed
W-tap's first return ray from 2.992 to 3.634 blocks. W-tap's subsequent
rays missed until the selector's follow-up at 919 ms after the cut.
The same backstep produced the combo in six of nine phase variants.
In the other three, W-tap also missed until the selector's follow-up,
which landed 50 ms later. It returned on the next accepted fresh hit.
Preserving the first follow-up can be enough for a double while leaving
the following window exposed.

[Frozen local-cue protocol](../../build/boxing-trade-skills/reentry-cue-footwork/frozen.json) ·
[Matched cue results](../../build/boxing-trade-skills/reentry-cue-footwork/summary.json) ·
[Single-backstep trace](../../build/boxing-trade-skills/reentry-cue-footwork/m4761-one-backstep.json)

## Shaping the approach before the first hit

We applied the same 343 ordinary three-input sequences starting at the
approach cut, before the local hurt cue. Each sequence reused the same
20 CPS cap, pending queues and clock as the plain W-tap and delay-only
controls. The main panel used the existing 0.60-block lift release.
At 80 ms we also restarted clicks at the first local hurt cue. The
stock movement branches reproduced the earlier hit outcomes.

| Added RTT and release | Model variants | Plain W-tap doubles / three-hit starts | Delay only doubles / three-hit starts | New score-improving doubles / three-hit starts | Cuts with a fixed gain across all nine phases, double / three hits |
|---|---:|---:|---:|---:|---:|
| 0 ms, lift | 1,053 | 111 / 24 | 224 / 24 | 139 / 131 | 2 / 1 |
| 20 ms, lift | 711 | 64 / 6 | 116 / 36 | 120 / 97 | 0 / 0 |
| 80 ms, lift | 1,233 | 63 / 19 | 5 / 4 | 7 / 4 | 0 / 0 |
| 80 ms, hurt cue | 1,233 | 63 / 19 | 5 / 0 | 134 / 65 | 0 / 0 |

New starts credit a sequence that produces the chain and strictly
improves the 2.5 second score margin over both controls where the
delay-only arm failed to produce that chain length. One sequence
can be picked per future for that column. The final column requires
the same sequence to beat both controls and make the chain across
all nine timing variants. These are previously exposed model clocks.
The native footwork comparison remains open.

At 80 ms the subset with braked recoil under stock lift had 1,042
variants. Plain W-tap made 52 doubles and 17 three-hit starts.
Earlier movement with the same lift gate produced zero new,
score-improving chains in this subset. Switching to the hurt cue
gave 123 new score-improving doubles and 59 three-hit starts.
This subset is defined by the stock lift branch. A different reply can
change whether W-tap actually brakes.

In two 80 ms witnesses, two backsteps and a forward release before
the first hit gave the selector a much earlier reply. In m4892 seat A,
the first six phase variants at the hurt cue replied at +97 ms, versus
+198 to +248 ms with the lift gate. W-tap used its full horizontal
impulse of 0.854 blocks/tick on the first step in those six variants.
All six started a three-hit chain, and five beat both score controls.
The lift version's first step used 0.513 blocks/tick. In m4886 seat A,
the same sequence at the hurt cue gave four score-improving three-hit
starts, replying within 112 ms in each. The lift gate missed the
275 ms counter limit in all nine variants. In both witnesses,
intervening only on horizontal recoil to impose a 40% brake before
that first step erased every three-hit start.

Across 73 fixed deadlines with the same footwork, the best single
deadline on each 80 ms witness yielded a score-improving double in
six of nine phases. At three hits, the best counts were five on m4892
and four on m4886.

[Frozen movement design](../../build/boxing-trade-skills/reentry-approach-footwork/frozen.json) ·
[Matched results](../../build/boxing-trade-skills/reentry-approach-footwork/summary.json) ·
[m4892 forced brake trace](../../build/boxing-trade-skills/reentry-approach-footwork/m4892-forced.json) ·
[m4886 deadline sweep](../../build/boxing-trade-skills/reentry-approach-footwork/m4886-combined.json)

## The server tick that the first reply reaches

The two 80 ms witnesses used two backsteps, a forward release and clicks
from the own hurt cue. In the failed +15 ms selector phases the first
reply click already selected W-tap. Its packet became ready after the
server had started the earlier drain. The first contact went to the next
drain and W-tap braked before its first recoil movement.

| Exposed witness, opponent phase 0 ms | Plain W-tap short margin at +15 ms | Selected first ray | Packet ready after earlier drain | Resulting reply delay |
|---|---:|---:|---:|---:|
| m4892 seat A | −1 | 2.197 blocks | 2.684 ms | 156.157 ms after incoming hit |
| m4886 seat A | 0 | 2.500 blocks | 8.267 ms | 155.008 ms after incoming hit |

In the nominal successful phases, the corresponding packets reached
their earlier drains with 12.696 ms and 3.669 ms to spare. These are
measurements inside the model; no first-click miss preceded the late
contact after the local hurt cue. Moving only future generated selector
attack receipts 3 ms earlier, with the same shift applied to both
comparators, changed the number of score-improving three-hit starts
on m4892 from five to seven of nine. On m4886, even 8 ms earlier left
the count at four. Adding 5.25 ms there removed all four. The model
retained every queued packet at the approach cut. The artificial
receipt shifts isolate the effect of transport timing.

We then replayed factual attacks from eight native W-tap mirror
recordings at 80 ms added RTT. Four development clocks set a fixed
phase correction to a 50 ms clock and a median attack transport delay.
The other four clocks supplied comparison attacks. One predictor used
only its most recent local input head as the timing reference. The
other used the most recent incoming opponent movement packet from its
own wire log. Offline server events supplied the outcome labels. In the
admission version, a frame click also forecast the next local consume
opportunity from past local heads.

| Native measurement on the comparison clocks | Plain W-tap local-head reference | Own replica-receipt reference |
|---|---:|---:|
| Correct drain using observations at click consumption | 2,111 / 3,580 (59.0%) | 3,185 / 3,580 (89.0%) |
| Correct drain using observations at click admission | 2,147 / 3,581 (60.0%) | 2,901 / 3,581 (81.0%) |
| At admission, actual arrival within 10 ms of a server drain | 1,164 / 1,941 (60.0%) | 1,332 / 1,941 (68.6%) |

For the replica estimate at admission, asking for more than 6 ms of
predicted clearance on either side of the arrival kept 2,293 of 3,581
attacks. It correctly predicted 2,052 of those (89.5%). The admission
check scored the original clicks at the recorded physical injection
boundary. The four comparison matches each contribute many correlated
attacks. The replica forecast's
per-match 90th percentile absolute phase error was 7.2–8.2 ms. That
uncertainty spans the smaller boundary in the trace and can span
the larger one. Use the estimate to express confidence in a planned
release, with a separate matched score test for any decision based on it.

[Frozen dispatch sweep](../../build/boxing-trade-skills/reentry-dispatch/frozen.json) ·
[m4892 packet trace](../../build/boxing-trade-skills/reentry-dispatch/m4892-0.json) ·
[Native consumption forecast](../../build/boxing-trade-skills/reentry-dispatch/native-phase-summary.json) ·
[Native admission forecast](../../build/boxing-trade-skills/reentry-dispatch/native-admission-summary.json)

## Acting on the first-reply timing estimate

The model applied the own-replica forecast to the first click after
the local hurt cue. When a capped press was otherwise eligible, the
guard waited if its predicted arrival was within the guard band of
a server drain. It tried again at the next local opportunity and fell
back at the first poll after the configured wait expired. After the
first admission it used standard pacing. The separate contact gate
waited for the current selection ray with the same 20 ms fallback.
Only the timing of the first new click could change. The other arm
used immediate release on the same hurt cue and with the same movement.
Both arms shared the same cap and queues with that paired start.

We scored 137 approach cuts on the development clocks and 133 cuts on
four separate, previously exposed clocks, each with nine timing variants
at 80 ms and 20 CPS. The latter four had supplied native phase forecasts
and had not supplied movement examples for these guards. The initial
approach was still marked offline. The phase gate used only target
packets already delivered to that client and its own most recent head.
The immediate arms exactly replayed the earlier outcomes on the
overlapping development clocks.

| Movement and timing rule, separate clocks | Plain W-tap mean margin | Diagnostic mean margin | Timely replies | Reply chains of three or more |
|---|---:|---:|---:|---:|
| Stock movement, immediate | +0.011 | −0.188 | 1,197 | 9 |
| Stock movement, 3 ms guard and 20 ms wait | +0.011 | −0.206 | 1,197 | 8 |
| Stock movement, 6 ms guard and 20 ms wait | +0.011 | −0.232 | 1,194 | 6 |
| Stock movement, 6 ms guard and 50 ms wait | +0.011 | −0.238 | 1,194 | 6 |
| Stock movement, contact wait | +0.011 | −0.188 | 1,197 | 9 |
| Back, back, release, immediate | +0.011 | −0.331 | 1,117 | 11 |
| Back, back, release, 3 ms guard and 20 ms wait | +0.011 | −0.400 | 1,088 | 10 |
| Back, back, release, 6 ms guard and 20 ms wait | +0.011 | −0.630 | 1,029 | 8 |
| Back, back, release, 6 ms guard and 50 ms wait | +0.011 | −0.657 | 1,012 | 9 |
| Back, back, release, contact wait | +0.011 | −0.330 | 1,117 | 11 |

Plain W-tap had 12 three-hit opening chains in these 1,197 variants,
including chains where W-tap hit first. The 6 ms timing guard with
approach footwork deferred at least one poll in 302 variants and
changed the first physical consume opportunity in 239. It gave up
88 timely replies. The paired short-margin change versus immediate
release averaged −0.299 and ranged from −7 to +3. All timing guards
also lost on average relative to immediate release on the development
clocks. Waiting for a currently visible contact kept the same reply
times and chain counts on the separate panel while changing some
first-click consume opportunities.

The earlier native forecast classified already chosen attacks. Here,
declining a borderline opportunity delayed the causal reply. This
model screen leaves the guards unqualified for native promotion.
Before changing the click gate again, test whether a phase estimate
available before arming can help avoid unfavorable exchanges while
there is still a W-tap fallback.

[Frozen controls and policies](../../build/boxing-trade-skills/reply-admission/frozen.json) ·
[Matched outcomes and per-seat spread](../../build/boxing-trade-skills/reply-admission/summary.json)

## Choosing at the approach using packet timing

The arming diagnostic chose between plain W-tap, waiting for the
first local hurt cue with W-tap movement, and the same wait with
back/back/release. Each decision preceded the first modified input
and used a single action across nine own/opponent timing variants.
The measurement is accepted-hit margin over 2.5 seconds at matched
20 CPS and 80 ms RTT. Both seats shared the same complete start and
queues with their matched W-tap control. Four development clocks
selected the rule. Four separate, previously exposed clocks measured
its transfer. The approach marks still rely on offline information.
All original fork outcomes were reproduced exactly after adding the
observation logger.

Each rule could use one cutoff. Geometry included own motion, the
opponent replica's position and closing speed, and local hurt and
contact cues. Timing used only processed target-movement timestamps
and local input heads. The combined rule chose a timing cutoff.
It attempted hit selection when the newest movement packet was
at most 23.19 ms old and continued plain W-tap otherwise.
Clocks have equal weight within each panel.

| Pre-arm rule | Plain W-tap improvement | Leave one development clock out | Fitted development | Separate clocks |
|---|---:|---:|---:|---:|
| Best constant (W-tap) | 0 | 0 | 0 | 0 |
| Geometry | 0 | −0.064 | +0.020 | −0.044 |
| Packet timing | 0 | +0.009 | +0.038 | −0.043 |
| Geometry and timing | 0 | −0.073 | +0.038 | −0.043 |

The frozen timing rule delayed the first click on 27 of 133 separate
cuts. Mean gains were negative on three of the four clocks and the
individual paired margins ranged from −5 to +4. At those same 133
cuts, the score change from choosing the stock hurt cue switched
sign across the nine future timings in 95 cuts. The same happened
in 89 cuts using approach footwork. The shortage persists even with
hindsight. An oracle choosing the best average action from those
three earned +0.359 on the separate panel and still lost in some
timing variants. Requiring a strict gain in every timing left just
two cuts and zero score-improving three-hit chains. Keeping the
same action set and finding a better predictor alone is unlikely
to make hit selection reliable across this timing spread.

[Frozen procedure](../../build/boxing-trade-skills/reply-arm/protocol.json) ·
[Per-clock and per-seat margins](../../build/boxing-trade-skills/reply-arm/summary.json)

## Hit selection under the recorded MMC preset

At 20 CPS, all four model profiles start from the same recorded-profile
W-tap approaches. Both players are grounded and fresh even under a nine-tick
horizon. Each pair keeps pending attacks, movement, and velocity packets.
Only new hits use the alternate recoil and immunity settings. Each row
has its own matched plain W-tap continuation. The comparison panel has 13
previously exposed timing recordings distinct from the 11 development
recordings.

A strict success uses one delay at a cut across all nine joint timing
shifts. It must land an unanswered fresh double and improve the accepted
score margin in the 2.5-second suffix on every shift. Plain W-tap must have
no double in any of those shifts. A three-hit chain also has to stay active.

| Suffix recoil and fresh window | Added RTT | Cuts | Plain W-tap doubles in all nine | Strict new doubles | Of those, active threes in all nine |
|---|---:|---:|---:|---:|---:|
| Recorded, 10 ticks | 0 ms | 120 | 1 | 15 | 9 |
| Recorded, 10 ticks | 20 ms | 243 | 3 | 20 | 6 |
| Recorded, 10 ticks | 80 ms | 141 | 2 | 4 | 3 |
| Recorded, 9 ticks | 0 / 20 / 80 ms | 120 / 243 / 141 | 1 / 3 / 2 | 15 / 17 / 3 | 9 / 5 / 2 |
| Provisional, 10 ticks | 0 / 20 / 80 ms | 120 / 243 / 141 | 1 / 4 / 1 | 19 / 13 / 1 | 11 / 2 / 1 |
| Provisional, 9 ticks | 0 / 20 / 80 ms | 120 / 243 / 141 | 1 / 4 / 1 | 18 / 16 / 1 | 10 / 2 / 1 |

With recorded recoil, 30 of the 35 strict nine-tick starts also pass
at ten ticks using a common winning delay. With the fresh window fixed
at ten ticks, only three of the 33 strict provisional-recoil starts share
a winning delay with recorded recoil. Recoil changes which delay works
and where it works. The cooldown length alone cannot account for the
change of successful starts between the profiles.

We chose 12 fixed recorded-profile delays on the narrow grid, favoring
distinct clocks, then widened both client shifts to ±10 ms and independently
added 0, 3, or 6 ms to each side's new attack dispatches. The 225 combinations
per witness recover the original nine outcomes exactly. No witness kept a
strict new double and positive score improvement in every combination.

| Selected witnesses | Widened variants | Plain W-tap doubles | Delayed doubles | Strict new doubles with higher score | Also active threes |
|---|---:|---:|---:|---:|---:|
| 0 ms, four cuts | 900 | 0 | 768 | 717 | 485 |
| 20 ms, four cuts | 900 | 9 | 723 | 708 | 114 |
| 80 ms, four cuts | 900 | 0 | 651 | 636 | 462 |

At 80 ms, the fixed 75 ms delay on m4890 seat B had zero losses on paired
accepted margin and produced a strict three-hit gain in 147 of 225
combinations. The result depends on selecting that particular state and
delay ahead of the widened test.

Action-specific ridge predictors trained on 427 development cuts used
only features visible to the attacker. On 504 separate cuts, the geometry
predictor gained 0.032 accepted margin over W-tap per suffix and found one
strict new double across nine timings. Adding recent packet-derived motion,
hurt and contact history gained 0.027 and found zero. One fixed delay per
RTT, chosen on development, gained 0.160 and found four. All three lost
aggregate fresh-hit margin and gained through upgrades. A retrospective
selector using average future score found 32 strict doubles and gained
1.166 accepted margin. All score means weight the donor clocks equally.
Selecting cuts still uses hidden grounded and fresh state. These are
isolated model branches. The native score and maintenance tests remain
separate from this release screen.

[Registered forks](../../build/boxing-trade-skills/recorded-hit-selection/protocol.json) ·
[Paired profile results](../../build/boxing-trade-skills/recorded-hit-selection/summary.json) ·
[Wider timing results](../../build/boxing-trade-skills/recorded-hit-selection/widened-summary.json) ·
[Frozen local score predictors](../../build/boxing-trade-skills/recorded-hit-selection/learned-frozen.json) ·
[Separate-clock predictions](../../build/boxing-trade-skills/recorded-hit-selection/learned-results.json)

### Why the 80 ms three-hit opening breaks

The selected m4890 seat B branch holds the first press for 75 ms, then
resumes ordinary W-tap clicks. All times in this trace are relative to
the shared approach cut. In the nominal replay W-tap hits at 148.316 ms.
The selector's 111.724 ms click reaches the server at 151.996 ms and is
accepted on the next drain at 200.493 ms. Shifting its client by +10 ms
puts the first allowed poll just before an earlier input consume. The
press is consumed at 76.123 ms and reaches the server at 116.395 ms.
Both fighters now hit on the 148.316 ms drain.

A separate branch keeps the opening hits at 148.316 and 200.493 ms,
but loses the next unanswered fresh hit. At fixed client shifts +5/0 ms,
the selector's 405.192 ms contact produces no accepted damage in either
arm. With ordinary dispatch its attack and following movement
report reach the server at 445.529 and 446.491 ms and drain at 448.998 ms.
Adding 6 ms to that one attack carries the following position report to
451.529 ms behind it in the uplink FIFO. Both now drain at 499.506 ms.
W-tap takes its 577.507 ms swing from the same feet position, but sees
the selector's hitbox closer. Its ray changes from 3.00244 to 2.98154
blocks and the return scores at 648.019 ms, before the next fresh
follow-up at 704.194 ms. Perturbing only the 405.192 ms attack reproduces
the outcome of delaying all the selector's future attacks in this branch.

| Client shifts and packet intervention | Plain W-tap accepted score | 75 ms delay accepted score | 75 ms delay clean opening hits |
|---|---:|---:|---:|
| Nominal | 3–4 | 6–3 | 3 |
| +10/0 ms, normal dispatch | 3–4 | 3–4 | 0 |
| +5/0 ms, normal dispatch | 3–4 | 6–3 | 3 |
| +5/0 ms, only the 405.192 ms attack delayed | 3–4 | 4–3 | 1 |
| 0/−10 ms, normal dispatch | 3–4 | 5–4 | 1 |
| 0/−10 ms, W-tap dispatch delayed by 6 ms | 3–4 | 6–3 | 3 |

The selector lands about one tick of damage after W-tap in the useful
opening. The later knockback and difference in landing times matter
through the next fresh window. Aiming at the rendered hitbox also makes
the return depend on when movement reached W-tap's client. A release
rule that only predicts the first attack's drain misses this second
failure mode.

[Packet, click and movement replay](../../build/boxing-trade-skills/recorded-hit-selection/failure-trace/boundary-summary.json)

### Retesting every first-click delay on the widened grid

Keeping the 12 selected cuts fixed, we ran all 126 original delays in
all 225 timing combinations per cut. These results select delays after
seeing widened outcomes. The columns for best fixed delay and per-case
choice bound the actions we tested on these states.

| Added RTT | Cuts | Plain W-tap clean doubles | New clean doubles with higher score, best fixed delay per cut | At least one tested delay succeeds per timing | Cuts where one delay succeeds in all 225 |
|---|---:|---:|---:|---:|---:|
| 0 ms | 4 | 0 / 900 | 852 / 900 | 870 / 900 | 3 |
| 20 ms | 4 | 9 / 900 | 750 / 900 | 801 / 900 | 0 |
| 80 ms | 4 | 0 / 900 | 687 / 900 | 708 / 900 | 0 |

All 225 shifts of m4869 seat A with a 195 ms delay and m4864 seat B
with a 310 ms delay yield four unanswered fresh hits, against zero
and one by the same unanswered-opening metric for their unshifted
W-tap controls. Paired
accepted margin gains are +3 and +4 in every variant, respectively.
The third robust start, m4864 seat A, yields a double and a +1 gain
through a damage upgrade. Its overall fresh margin is tied.

In the nominal four-hit traces the selector answers from the air,
134.701 and 200.406 ms after the incoming hit. It lands 150.937 and
203.821 ms before W-tap. W-tap's closest return swing after the
selector becomes vulnerable and before the selector's second fresh
hit is 3.161 and 3.548 blocks, respectively. The selector's own
follow-up rays are 2.757 and 2.985 blocks. This describes a
comfortable margin on defense in these two replays. Both follow-ups
come after the first fresh window opens. This is evidence for
acquisition into a four-hit chain, with no change to the
established-combo maintenance criterion.

At 80 ms on m4890 B an 80 ms delay scores at least two more accepted
hits than W-tap in every tested branch. It still only produces the
same 147/225 clean doubles as the original 75 ms delay, the maximum
available even with a different delay per timing. Its advantage in
the other 78 variants comes from upgrades while the fresh margin is
tied or worse. No new bot policy was fitted or tested natively in
these experiments.

[All delays and the peak score by cut](../../build/boxing-trade-skills/recorded-hit-selection/widened-all-delays-summary.json) ·
[0 ms seat A replay](../../build/boxing-trade-skills/recorded-hit-selection/failure-trace/m4869-a-wait-195.jsonl) ·
[0 ms seat B replay](../../build/boxing-trade-skills/recorded-hit-selection/failure-trace/m4864-b-wait-310.jsonl)

### Moving after the first reply

On the v3 profile, 108 short ordinary movement choices tested one to three
heads starting zero to five heads after the locally observed reply. Each
fork retains the same first delay, cap and aim. The first incoming and
outgoing fresh hit times match the delayed-only arm in all 291,600 forks.
All 12 cuts and their donor clocks had already been used for development
diagnostics.

| Selected v3 starts | Plain W-tap doubles | Delay-only strict new doubles | Best fixed pulse per cut | Fixed pulse succeeds in all 225 at how many cuts |
|---|---:|---:|---:|---:|
| 0 ms, four cuts | 0 / 900 | 852 / 900 | 852 / 900 | 3 |
| 20 ms, four cuts | 9 / 900 | 750 / 900 | 819 / 900 | 0 |
| 80 ms, four cuts | 0 / 900 | 687 / 900 | 765 / 900 | 1 |

On m4890 seat B at 80 ms, taking two backsteps on the first two input
heads after the local reply cue gives a strict new double in all 225
timings, up from 147 with the same 80 ms delay alone. It gives an active
three in 180 and improves margin over W-tap by at least one. Compared
with the delay alone, its mean score gain is +1.73, with a spread from
−3 to +4. In the previously traced 6 ms FIFO failure, it moves W-tap's
same-timestamp ray from 2.982 to 3.012 blocks, preserves the first fresh
follow-up at 704.194 ms, and turns one unanswered hit into five.
Applying one shared movement choice across all four 80 ms cuts adds
strict doubles but lowers the mean score relative to delay alone by 0.02.
The per-cut best results rely on selecting the pulse retrospectively.

[Frozen diagnostic](../../build/boxing-trade-skills/recorded-hit-selection/reply-spacing/protocol.json) ·
[Matched scores](../../build/boxing-trade-skills/recorded-hit-selection/reply-spacing/summary.json) ·
[Recovered FIFO branch](../../build/boxing-trade-skills/recorded-hit-selection/reply-spacing/trace/m4890-b-6-0.json)

### Transfer to the distance-dependent MMC preset

The practice server's `preset=mmc` now uses the v4 distance-dependent
horizontal impulse. `preset=mmc-v3` retains the previous fixed recoil.
The v4 simulator scored 104, 242 and 81 new eligible approaches on the
three comparison RTTs. The narrow grid yielded 13, 16 and one strict
new double where a single delay works in all nine timings, versus no
all-nine doubles for matched plain W-tap in these pools.

The widest tests below are the top cut per RTT selected on the narrow
grid, in descending order of the minimum improvement. Values are strict
new doubles and active threes against each case's paired W-tap, across
225 timings.

| Added RTT and cut | Plain W-tap doubles | Delay and strict doubles / threes | Best post-reply pulse and strict doubles / threes |
|---|---:|---|---|
| 0 ms, m4864 B | 0 | 305 ms from cut, 180 / 180 | Release for three heads after two, 225 / 0 |
| 20 ms, m4761 B | 0 | 170 ms after own hurt cue, 225 / 225 | Walk for one head, 225 / 225 |
| 80 ms, m4886 A | 0 | 135 ms after own hurt cue, 225 / 225 | Release for two heads, 225 / 225 |

The fixed delay alone improves the paired accepted margin by at least
two hits on the 20 ms cut and at least one on the 80 ms cut. Applying
those delays to every eligible comparison cut yields two strict doubles
in 242 cuts with the 170 ms delay at 20 ms and one in 81 cuts with the
135 ms delay at 80 ms. Their clock-weighted mean margin gains are −2.018
and +0.096, respectively. Both lose aggregate fresh-hit margin. The
cuts still use privileged enrollment. The timing result on a chosen
geometry does not establish a full-match policy.

The existing movement weights were also played through first-to-100
on all 11 development clocks in both seats with the v4 model. Every
bout reached 100, with closely matched measured CPS throughout.

| Added RTT | Plain W-tap wins / median margin | Existing movement wins / median margin / range |
|---|---|---|
| 0 ms | 4/8 / 0 | 4/8 / −3 / −47 to +12 |
| 20 ms | 3/6 / 0 | 5/6 / +6 / −4 to +21 |
| 80 ms | 4/8 / 0 | 5/8 / +9.5 / −33 to +38 |

On the same complete v4 K3 states W-tap completes 304 active cycles
against 72, with accepted returns in 12 of 17 versus 16 of 17 episodes.
The remaining runs reach the 20-second horizon. Return-only counts
agree. At K5 the totals are 271 versus 60 from 16 starts, and at K10
they are 203 versus 35 from 13. In two exact forks, m5492 A at 0 ms
and m5489 B at 20 ms, the first follow-up lands on the same drain
for both policies. W-tap uses a sprint hit delivering 0.776 and 0.756
blocks/tick horizontally, versus the existing bot's 0.5275 base
knockback in both. W-tap gets 25 and 24 further hits from those K3s.
The existing bot gets one each. In these traces it alternates walking
and rearming while W-tap releases and returns to sustained sprint.

The native protocol uses the unchanged weights, matched W-tap and
the current v4 law, with 24 regulation bouts and 24 independent K3
drills. It waits for the frozen range build while sharing the normal
lab queue. The earlier ambiguous request using `preset=mmc` was
withdrawn before it issued any matches because it expected v3 recoil.

[Individual model bouts and K3/K5/K10 states](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/summary.json) ·
[Matched m5492 inputs and contacts](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/ledgers/m5492.jsonl) ·
[Fixed-delay transfer](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/cue-transfer.json) ·
[Frozen native v4 plan](../../build/boxing-double-search/timing-transfer/chase-window/v4-chase-plan.json)

### Separating the first sprint hit from sustained W-tap

We forked the exact v4 K3, K5 and K10 states on 11 development and
13 distinct comparison clocks. Each state had nine future client
timings with the same queued inputs and inherited click clocks.
All clocks had previous exposure. The nominal development controls
reproduce the prior maintenance score and exposure for every state.

In 73 separate-panel K3 timing variants, both policies land the next
fresh hit on the same server drain and W-tap delivers the stronger
horizontal impulse. Swapping just the horizontal components of
that one outgoing velocity packet makes a controlled diagnostic.
Attack order, local contact drag, server sprint state, vertical
velocity and packet delivery still come from each original arm.

| Added RTT | Same-drain variants with stronger W-tap launch | Plain W-tap | Existing movement | W-tap with movement recoil | Movement with W-tap recoil |
|---|---:|---:|---:|---:|---:|
| 0 ms | 39 | 803 | 55 | 337 | 89 |
| 20 ms | 28 | 518 | 62 | 312 | 104 |
| 80 ms | 6 | 54 | 11 | 12 | 20 |

The counts are further active hit cycles. The stronger first impulse
recovers some of the lost hits, while W-tap also relies on it to protect
its opening. In nominal m5492 A, the two controllers go from 25 and
one cycles to one and three after swapping velocity. In nominal m4758
B at 20 ms they go from 31 and one to two and six. The same states
give the learned movement far fewer cycles with the stronger
impulse than W-tap gets from its original continuation.
The broader same-drain panel with weaker or near-equal W-tap
impulses is reported separately.

Adding Ctrl to the existing model's proposed W at head nine was the
best single window on the development set. It adds 70 cycles across
216 comparison branches, from 847 to 917, against W-tap's 3,928.
Choosing different windows by added RTT failed to close the gap.
The raw branch counts are correlated. The score files retain per-cut
exposure, returns and censoring for both maintenance endpoints.

Substituting W-tap's ordinary release and chase as separate factors
shows why one component alone is insufficient. The full substitution
is a reproduction control and matches W-tap at all three landmarks.

| Added RTT, separate comparison K3 branches | Plain W-tap | Existing movement | W-tap keys for the first 12 heads | W-tap release only, throughout | W-tap chase only, throughout |
|---|---:|---:|---:|---:|---:|
| 0 ms, 54 | 1,077 | 135 | 288 | 168 | 96 |
| 20 ms, 108 | 1,738 | 333 | 362 | 535 | 326 |
| 80 ms, 54 | 1,113 | 379 | 474 | 203 | 123 |

The first hit's direction and size affect the immediate return.
Repeating the full release and sprint sequence affects subsequent
spacing across many cycles. Adding one sprint adjustment to the
existing movement leaves the maintenance gate open.

[Recoil exchange and Ctrl window results](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/sprint-attribution/summary.json) ·
[Recoil results by strength](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/sprint-attribution/strength-stratified.json) ·
[Release and chase substitutions](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/sprint-attribution/factorial/summary.json)

### First new v4 timing recordings

Eight independent native v4 W-tap mirrors were reserved by immutable
request ID for the two already chosen hurt-cue delays. Four have complete
physical click, raw movement send and tracker captures. The model
reuses their native host calendar and runs W-tap and both delays from
the same states.

| Added RTT and armed reply | Starts | Plain W-tap doubles in all nine | Delay doubles in all nine | Delay strict new doubles | Mean accepted / fresh improvement versus W-tap |
|---|---:|---:|---:|---:|---:|
| 0 ms, 135 ms delay | 54 | 1 | 1 | 0 | −2.216 / −2.381 |
| 0 ms, 170 ms delay | 54 | 1 | 1 | 0 | −2.042 / −2.166 |
| 80 ms, 135 ms delay | 31 | 2 | 0 | 0 | −0.150 / −0.772 |
| 80 ms, 170 ms delay | 31 | 2 | 0 | 0 | +0.286 / −0.472 |

These are generated model interventions on new native schedules.
The actual native fights were W-tap mirrors. Eligible cuts still use
privileged enrollment. This interim transfer panel covers 0 and 80 ms.

[Registered native calendars](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/prospective/protocol.json) ·
[Four completed timing recordings](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/prospective/interim-summary.json)

### What the client sees before delaying

We replayed 358 development and 427 separate comparison approaches
on v4. Each state has nine future clock variants. The four tested
actions are plain W-tap, wait to the first own hurt cue, and wait
135 or 170 ms longer. Removing the extra trace fields exactly
recovers the original W-tap and delayed scores in all 28,260
branches. No reserved prospective recording supplied examples.

The before-arm models see the attacker's current and recent local
position, velocity and opponent replica. The after-cue model also
sees the first cap-eligible local poll following the incoming hurt
cue while the approach has withheld its first press. This later
model can choose an immediate or delayed reply. An additional stage
uses out-of-clock estimates to learn whether to arm at all. The
cut still uses privileged enrollment. All learning and thresholds
use development only.

| Choice on the 427 separate cuts | Paired mean accepted margin versus plain W-tap | Paired mean fresh margin | Strict new doubles in all nine phases |
|---|---:|---:|---:|
| Plain W-tap | 0 | 0 | 0 |
| Fixed per-RTT choice | +0.315 | −0.040 | 8 |
| Before arm, current geometry | +0.151 | −0.016 | 5 |
| Before arm, recent observation history | +0.120 | −0.017 | 2 |
| Always arm and choose at the hurt cue | +0.294 | −0.037 | 8 |
| Decide both whether to arm and when to reply | +0.138 | −0.013 | 4 |
| Retrospective best fixed action per cut | +0.729 | +0.306 | 14 |
| Retrospective best action per future phase | +0.962 | +0.483 | 14 |

An action that can be changed after the clock tie breaks is worth
more than a single choice given the same approach observation.
The extra local poll still comes after the bot has committed to
withhold the first press. The tested predictors lag the fixed choice
on accepted score and give up fresh margin.

At 80 ms, the 135 ms delay gives up a return before its second
unanswered hit in 684 of 729 comparison variants. The median of
the minimum aimed ray at the defender's eligible attempts from the
vulnerability window to the next follow-up is 1.589 blocks for
these failures. This is far inside ordinary reach. Another 22
variants never deliver a fresh reply. Among 12 strict new doubles
the median sampled minimum return ray is 3.968. The landing lead
does not replace this separation. Where both first landings can
be observed, failed branches give the selector a median 258 ms
head start, compared with 295 ms in the successful ones. The
ranges overlap.

### A close approach at the first hurt cue

At 80 ms the displayed opponent's hitbox being within one block
at the first hurt poll marks a more favorable state. A threshold
of one block with a 170 ms late reply was selected on development.
All 18 strict score-improving doubles for that delay in the separate
panel occur in the 170 timing variants that meet the threshold.
None occur in the other 559. The same delay's mean accepted gains
over W-tap in those groups are +1.23 and +0.22. The fresh gains
are +0.09 and −0.36. Most eligible close states still give up a return.

| 80 ms frozen rule, 81 separate cuts | Paired mean margin over W-tap | All-nine strict new doubles |
|---|---:|---:|
| Plain W-tap | 0 | 0 |
| Always reply at the first hurt cue | +0.060 | 0 |
| Always reply 170 ms after the cue | +0.552 | 1 |
| Use 170 ms within one block, otherwise reply immediately | +0.337 | 1 |

Predicting the later close state before arming with the recent
observation history raises precision to 54% in the top tenth of
comparison cuts, versus a 23% base rate by phase. It catches 23%
of close variants. The geometry-only predictor has 21% precision
there. This observes a useful part of the condition. The tested
signals lack sufficient coverage to justify a controller based on
recognizing the close state before committing. Test whether ordinary
approach movement can increase the frequency of those states while
preserving the accepted-score and maintenance comparisons.

[Frozen observation test](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/decision-evidence/protocol.json) ·
[Paired scores and return geometry](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/decision-evidence/results.json) ·
[Close-cue forecast](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/decision-evidence/close-cue-results.json)

### Can approach movement create the favorable reply?

From each exact v4 approach, we tested all 49 two-input combinations
of keep, sprint, walk, coast, back, sprint-left and sprint-right.
Six repeated three-input choices extend the test. Each movement
word runs under ordinary capped clicking, withholding until the
first own hurt cue, and withholding for another 170 ms. The arms
share the same complete prefix, aim, CPS ceiling and defender. This
recovers the unchanged score and early observations in all 4,743
timings from 527 previously exposed approaches. The W-tap and
delayed-only controls retain the original movement.

At 80 ms, a different movement can create a one-block first-hurt
cue in 11 of the 729 comparison timing variants where the delayed
control would fail that condition. None of the 11 yields a new
strict double in the same branch. Across nine timings per cut,
the movement search yields the close cue on the same number of cuts
as stock movement, 12 of 81, and creates zero new consistently
close cuts. At 20 ms none of the movement words creates the close
cue in a variant where the corresponding stock arm lacked it.
The useful region at 20 ms extends beyond one block.

| Separate comparison arms | Plain W-tap | No movement change, wait to hurt | No movement change, wait another 170 ms | With movement sweep, ordinary click | With movement sweep, wait to hurt | With movement sweep, wait another 170 ms |
|---|---:|---:|---:|---:|---:|---:|
| 20 ms, cuts with a strict double in all nine | 0 reference | 4/242 | 2/242 | 1/242 | 8/242 | 3/242 |
| 80 ms, cuts with a strict double in all nine | 0 reference | 0/81 | 1/81 | 3/81 | 2/81 | 1/81 |

The movement columns select a separate fixed word for each cut
after seeing the outcomes. The 80 ms late double is the known
m4886 A start. Forcing sprint on the first head gives the exact
same result as the unchanged delayed movement in all 225 widened
timings. The new 20 ms late witness is m5749 A. Coasting then walking
gives 180/225 strict doubles and 135 active threes, with paired
accepted gains from −5 to +5. With no change in movement the same
delay gives 15/225 strict doubles. The closer-than-one-block cue
never appears in either arm.

Two other traces explain the alternative way movement helps:

- At 80 ms, m4874 B uses three backsteps and ordinary clicking.
  With the original movement it hits first at 184.876 ms and takes
  W-tap's hit at 234.418 ms. After the backsteps, the original
  100.258 ms click misses at 3.309 blocks. W-tap still hits on
  the 234.418 ms drain, and the candidate answers at 338.528 ms.
  A second fresh hit lands at 834.717 ms before any return. The
  footwork gives strict doubles in 180/225 widened timings and
  improves the accepted margin over plain W-tap by +1 to +6 in
  every variant on this cut.
- At 20 ms, m5749 B uses back then coast and releases at the first
  own hurt cue. The first incoming and reply drains match the
  unchanged delayed arm, at 140.169 and 238.939 ms. The two arms
  send the same 0.9495 blocks/tick sprint impulse. The candidate
  gives no return in the nominal 2.5-second trace and lands five
  fresh hits, while stock movement takes an upgrade at 593.471
  ms and a fresh return at 638.868 ms. Across 225 timings the
  change gives 180 strict new doubles and 171 active threes,
  versus zero under the same click policy with stock movement.
  The accepted gain over plain W-tap ranges from zero to +4.

On the 81 separate 80 ms cuts, the one fixed back-and-coast word
selected on development gains +0.255 accepted and loses 0.057
fresh margin per 2.5-second suffix. The 170 ms delay with unchanged
movement gains +0.552 accepted and loses 0.225 fresh. At 20 ms the
top development action still uses stock movement and opens at the
hurt cue. The evidence supports selected spacing and hit-order
effects. Matching a close hurt cue by itself has not created a
reproducible hit-selection policy.

[Crossed movement and click results](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/summary.json) ·
[Wider timing controls](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/wide/summary.json) ·
[Wider timing for the new 20 ms late witness](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/wide-new-late/summary.json) ·
[Nominal 80 ms backstep replay](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/trace/m4874-b.json)

### Why timing alone fails on the backstep starts

On m4874 B at 80 ms and m5749 B at 20 ms, the unchanged-movement
delay screen includes an action that hits on exactly the same first
two drains as the footwork in all 225 paired variants per cut.
Across the original 127 presses-and-delay options, none of those
matched-order branches reproduces a strict double with unchanged
movement where the selected footwork gets one. On the 80 ms cut,
none of the delay-only arms gets a strict double at all. On the
20 ms cut, some delay-only arms do so in 45 variants with different
opening hit times. The geometry matters after picking the drain.

The 80 ms backstep's successful and failed timings have the same
opening order, 234.418 ms incoming and 338.528 ms reply relative
to the cut, with identical 0.9495 blocks/tick knockback. For
all 45 own +10 ms variants the candidate misses the ordinary
immune swing that normally brakes its incoming horizontal motion.
In the nominal branch S12 applied at 284.818 ms, the 300.563 ms
contact multiplies it by 0.6, and horizontal velocity immediately
after that contact is 0.5697. In the +10 ms branch S12 applies
before the first post-knockback move as well. No press is consumed
then and the candidate skids farther away. Its next fresh hit
slips from 834.717 to 1,332.578 ms in the nominal-delivery
comparison, where W-tap scores in the same drain.

The follow-up keeps the three backsteps and sweeps all 127 fixed
release choices at the same CPS. Waiting 15 ms from the cut
preserves the initial hit times in the traced +10 ms branch and
shifts physical admission to earlier polls in the later sequence.
It restores the missed immune contact, consumed at 310.563 ms,
with the same 0.5697 post-contact horizontal velocity. This
retrospectively selected rule gets a strict new double in all
225 variants and improves the accepted margin by +2 to +6.
Plain W-tap and every fixed delay without movement have zero
strict doubles on the same cut. The other 20 ms case still peaks
at 180/225 strict doubles after retiming. Its failures at +10 ms
land the first reply a full drain earlier and give W-tap an aimed
return at 2.537–2.783 blocks.

A separate own-observation fit over all 165 approach choices
selected model and threshold on leave-one-development-calendar-out
results. It gives +0.198 accepted margin at 20 ms and +0.024 at
80 ms on separate exposed calendars. The frozen fixed choices
give +0.258 and +0.255. A retrospective selector using one action
per cut gets +1.049 and +2.018. Both groups retain the same plain
W-tap reference. The retimed backstep is a repeatable local witness.
The tested selector falls short when it has to choose on its own.

[First-hit order and return traces](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/order-attribution/summary.json) ·
[Retimed opening outcomes](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/order-attribution/retime-summary.json) ·
[Restored immune swing](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/order-attribution/retime-trace-m4874.json) ·
[Separate-clock selector results](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/close-approach/selector/results.json)

### Handing established combos back to W-tap

In a separate full-match experiment, the existing learned movement
handles acquisition and stock W-tap takes over once the client
observes two unanswered target hurt cues. It switches back on
incoming hurt or a gap beyond 1,100 ms. The unchanged W-tap
controller sees the same observations even when its inputs are
inactive. The network also records the keys actually applied.
There is no state oracle in this regulation controller. No
hit-selection wait or jump experiment is included.

Two cues win the development comparison against one and three at
every tested latency. The separate comparison uses 13 previously
exposed timing calendars, both seats and nine correlated shifts
each. Every bout finishes first-to-100.

| Added RTT | Plain W-tap wins / mean margin | Existing movement wins / mean margin | Two-cue handoff wins / mean margin / spread | Handoff vs W-tap actual CPS |
|---|---:|---:|---:|---:|
| 0 ms | 27/54 / 0 | 40/54 / +0.89 | 42/54 / +16.26 / −56 to +69 | 16.654 / 16.656 |
| 20 ms | 54/108 / 0 | 56/108 / −6.66 | 76/108 / +11.74 / −94 to +83 | 16.634 / 16.637 |
| 80 ms | 36/72 / 0 | 33/72 / −15.39 | 44/72 / +5.36 / −91 to +82 | 16.686 / 16.687 |

At 80 ms the mean is +20.53 from seat A and −9.81 from seat B.
The matching plain W-tap mirrors average +28.56 and −28.56,
so the paired changes are −8.03 and +18.75, respectively.
Without timing shifts the eight 80 ms outcomes average −0.625.
The individual scores retain this timing and seating sensitivity.
In the separate common-state maintenance drills, the two-cue
handoff matches W-tap exactly on completed cycles, accepted
returns, inactivity, exposure and censoring at all 216 K3,
207 K5 and 153 K10 comparison branches. It preserves the
maintenance reference. The nine timings are repeated observations
of the same start, so the win fractions are descriptive.

The model and staged Java 8 bot client use a shared continuation
gate. Replaying three model donor clocks after sharing it reproduces
all 54 original regulation and maintenance rows exactly. The
staged bridge and 48-bout native plan are frozen. Its policy files
remain outside the live directory until a lab window is available.

[Individual scorecards and maintenance checks](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/summary.json) ·
[Frozen native comparison and bridge hashes](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/staged/native-plan.json)

### Two-cue handoff in native first-to-100

All 48 frozen native fixtures completed and passed the input audit. It
replays the neural proposals, the two-cue selection, the logged W-tap
release windows and the actual movement keys. The 24 regulation fixtures
use matched 20 CPS caps. Actual candidate versus opponent rates are
17.57 versus 17.54 CPS, and the largest per-bout difference is 0.126 CPS.
The W-tap controls average 17.55 CPS on both seats. The candidate wins
6/12 with mean margin −0.83 and a range of −67 to +48. The independently
played W-tap mirrors win 10/12 from the designated seats, with mean margin
−0.17 and a range of −74 to +20.

| Added RTT per player / seat | Two-cue handoff, repeats 0 and 1 | W-tap mirror, repeats 0 and 1 |
|---|---|---|
| 0 ms / A | 89–100 (m7074), 100–52 (m7029) | 100–80 (m7053), 100–87 (m7037) |
| 0 ms / B | 100–98 (m7067), 100–85 (m7042) | 100–92 (m7068), 100–95 (m7034) |
| 20 ms / A | 99–100 (m7041), 33–100 (m7065) | 100–98 (m7030), 100–95 (m7055) |
| 20 ms / B | 100–96 (m7031), 96–100 (m7049) | 100–93 (m7043), 100–97 (m7033) |
| 80 ms / A | 80–100 (m7066), 100–81 (m7062) | 97–100 (m7073), 100–93 (m7064) |
| 80 ms / B | 100–82 (m7061), 87–100 (m7039) | 26–100 (m7045), 100–95 (m7058) |

The separate 24 maintenance drills share the acquisition procedure and use
independent native starts. Inactivity-qualified and return-only outcomes
are equal in this panel. Strong model preservation of maintenance cannot
eliminate the independent native start and timing spread.

| Landmark | Two-cue entries / cycles / returns / censored | W-tap entries / cycles / returns / censored | Two-cue / W-tap exposure |
|---:|---:|---:|---:|
| 3 | 10 / 113 / 8 / 2 | 11 / 164 / 8 / 3 | 60.954 / 86.106 s |
| 5 | 10 / 96 / 8 / 2 | 9 / 154 / 5 / 4 | 52.631 / 80.223 s |
| 10 | 5 / 62 / 3 / 2 | 8 / 133 / 4 / 4 | 32.772 / 69.641 s |

The candidate wins six of seven bouts where it establishes the first
five-fresh-hit run. It loses all five other bouts, including two with no
five-hit run by either side. In its 100–52 win, m7029, the first two scoring
clicks of the 38-hit run occur under learned movement. The remaining 36
occur under W-tap. In the 33–100 loss, m7065, the bot never
scores from a W-tap movement head. Its longest streak is two fresh hits.
The defender has runs of 19 and 49. The linked ledger assigns a hit to the
controller at its physical click, so accepted packets in flight do not
get attributed to the next controller.

The 12 W-tap mirrors also supply newly exported clocks for the unchanged
model. Each has both seats and nine related timing shifts. The same fork
and cutoff govern both arms in each row. All 531 K3/K5/K10 endpoints match
W-tap exactly for two-cue and the frozen one-cue delay, including cycling,
returns, inactivity, exposure and censoring. The mean first-to-100
result, especially at 20 ms, is stronger in the model than in Minecraft.

| Added RTT | Plain W-tap wins / mean score margin | Two-cue wins / mean margin | One-cue delayed start wins / mean margin |
|---|---:|---:|---:|
| 0 ms | 36/72 / 0 | 47/72 / +7.69 | 45/72 / +2.28 |
| 20 ms | 36/72 / 0 | 36/72 / +6.24 | 40/72 / +2.79 |
| 80 ms | 36/72 / 0 | 34/72 / −1.92 | 39/72 / +1.94 |

On these mirrors the one-cue wait loses 7.22 and 7.00 hits on one calendar
each at 20 and 80 ms. The native two-cue policy fails the score and
maintenance comparison. No one-cue delay policy has been deployed.

[Every physical input and native score](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/native/acquisition-native.scores.json) ·
[Accepted attacks linked to each controller](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/native/regulation-controllers.json) ·
[Model scores on the twelve native mirrors](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/native/model-mirrors/summary.json)

### Why the two-cue bot loses the first movement race

The exact-input model recreates all 13,489 compared events through 38 s in
m7065 and exactly recreates the examined prefixes of m7029 and m7062.
Both original scoring attacks at 21.520 and 21.619 s in m7065 launch
0.9495 horizontally and +0.361375 vertically. The W-tap defender clicks
between receipt and movement and brakes its own recoil. At 21.674 s the
candidate selects the target from 1.061 blocks and consumes a
non-sprint contact before its first travel. It was holding W without Ctrl on
the preceding movement. It has not re-entered local sprint when the click
is consumed, even with Ctrl now held. The first travel covers 0.822 blocks
backward after ground acceleration. Changing the preceding movement to
W+Ctrl makes the contact take sprint drag, so first travel covers 0.442.
Actual mouse consumption times and the original trade launches stay fixed.

| m7065 fixed ordinary click schedule | Native recorded input reproduced | Plain W-tap movement fork | Change only the prior movement to W+Ctrl |
|---|---:|---:|---:|
| 21.619 s trade, example through 23.310 s | 1–5 | 3–4 | 3–6 |
| 34.779 s trade, every original-timing fork through 36.500 s | 1–4 | 2–2 | 2–2 |

At 34.779 s both launches are again full strength. The first movement
after the return has no consumed click, and the single changed earlier
input helps via position and sprint timing. Neither branch changes the
actual first step backward from 0.822. In m7062, learned and W-tap movement
both brake the incoming hit. The earlier motion changes which server
window the opponent's first fresh packet reaches. The nominal learned
control gives 6–1 across the 17 nearby start cuts versus 6–4 or 4–3 under
W-tap. A common +0.5 ms perturbation removes the same advantage. Across
four selected trades, all 70 nominal unchanged forks match their recorded
hit sequences and launch magnitudes. Three start ticks in the first m7065
trade and one in the second improve over both baselines with the two-input
W release and restart across all three timing variants. Those are
retrospectively located ticks in one loss. The same reset typically gives
away points around the two winning exchanges. Keep the pre-trade state and
first contact brake separate when testing any future trigger.

[Strict-prefix checks and matched input forks](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/native/witness-forks/summary.json) ·
[m7065 native replay](../../runtime/replays/m7065.jsonl)

### Starting on W-tap until a local hit deficit

The 80 ms comparison changes sharply when only the recorded client
calendars trade places. The W-tap mirror seat means reverse from
+28.56/−28.56 to −8.53/+8.53, and the handoff's paired gains
reverse from −8.03/+18.75 to +30.86/−7.25. Swapping client
packet-delay residuals gives +10.75/+39.69. Swapping only tracker
timing keeps the aggregate gains at −8.03/+18.75. Swapping all
three gives +14.58/+7.39. The server calendar stays fixed. The
complete swap fails to reproduce the opposite-seat bout scores,
so the swap measures timing sensitivity within this simulator.

In the original comparison, the 80 ms handoff changes how often
each seat gets the first five-hit run. In seat A the candidate gets
there first in 24 bouts and its opponent in 11, compared with
30 and three with W-tap. For seat B these counts are 18 and 17,
compared with three and 30 with W-tap. Attributing accepted points
to the part of a run after two unanswered fresh hits accounts for
−7.86 of the −8.03 paired margin change in seat A and +15.53 of
the +18.75 change in seat B. This is a descriptive partition of
full matches. The independent maintenance gate still uses common
K3, K5 and K10 forks.

An offline variant starts on W-tap and enables the unchanged two-cue
controller when its cumulative incoming fresh-hurt cue count exceeds
its target cue count. The threshold choices were one, two or four
cues. Development selected no wait at 0 ms and one cue at 20 and
80 ms. The table reports every one-cue comparison against the frozen
handoff and plain W-tap on the original held-apart, previously
exposed calendars. Each calendar contributes both seats and nine
correlated phase shifts.

| Added RTT | Plain W-tap wins / mean margin | Two-cue handoff wins / mean margin | One-cue wait wins / mean margin / score range |
|---|---:|---:|---:|
| 0 ms | 27/54 / 0 | 42/54 / +16.26 | 38/54 / +17.07 / −57 to +74 |
| 20 ms | 54/108 / 0 | 76/108 / +11.74 | 65/108 / +9.99 / −89 to +97 |
| 80 ms | 36/72 / 0 | 44/72 / +5.36 | 51/72 / +14.67 / −88 to +82 |

At 80 ms, the one-cue wait scores +28.81 and +0.53 on seats A and
B, paired gains of +0.25 and +29.08 over the W-tap mirror. The four
comparison calendar gains are +21.00, +26.17, +3.00 and +8.50.
Measured comparison CPS is 16.6886 versus 16.6876. Every threshold
matches W-tap exactly in every scored K3, K5 and K10 comparison
fork. At 80 ms, this covers 54, 54 and 36 forks. The 80 ms one-cue
wait gains +16.15 with the client calendars swapped, +11.04 with
the wire swap and +4.94 with the full comparison swap. It falls to
−0.35 on the calendar-swapped development set. The unchanged
two-cue candidate and W-tap are queued for the 48-bout native test.
The one-cue wait is an offline experiment.

[Timing rotation scores](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/seat-rotation/summary.json) ·
[Full-match hit ledger](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/regulation-ledger/summary.json) ·
[Frozen threshold selection](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/entry-deficit/frozen.json) ·
[Per-match scores and exact maintenance outcomes](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/entry-deficit/summary.json) ·
[Timing stress scores](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/entry-deficit/stress-summary.json)

### The first movement switch explains little of the regulation gain

Three 80 ms arms replay the same opening on 72 cases per timing
panel. One arm stays on W-tap. The full one-cue arm keeps using
the handoff after activation. The brief arm uses the same actions
through 1,100 ms after its first applied input differs and then
returns permanently to W-tap. All three have identical accepted
hit histories before the first changed input. The full and brief
arms also match hit for hit through 1,100 ms after the switch.

| Timing panel | Plain W-tap wins / mean | Brief switch wins / mean | Full handoff wins / mean | Full gain over W-tap by five seconds after switch |
|---|---:|---:|---:|---:|
| Original development | 36/72 / 0 | 32/72 / −0.81 | 54/72 / +21.89 | −0.14 |
| Calendar-swapped development | 36/72 / 0 | 28/72 / −5.54 | 42/72 / −0.35 | +0.01 |
| Original comparison | 36/72 / 0 | 33/72 / −9.32 | 51/72 / +14.67 | +0.13 |
| Fully swapped comparison | 36/72 / 0 | 46/72 / +16.03 | 45/72 / +4.94 | −0.01 |

The five-second column uses bouts in which the first movement
change occurred. On the original comparison the median switch
was at 2.045 s. The true accepted score was tied in 32 of 72
bouts and the bot was ahead in one, even though its client's cue
count had triggered the switch. The first post-switch accepted
hit went to the bot in 56 bouts, versus 55 for W-tap. The bot
was first to two unanswered fresh hits in 45 versus 43 and first
to five in 48 versus 33. The full-match improvement appears after
later exchanges. Even the brief intervention's full-match score
changes substantially when the recorded timing is rotated. These
results support a later regulation effect with little immediate
gain at activation.

[Shared-prefix verification and post-switch hit sequences](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/entry-deficit/first-change/summary.json)

### Movement after later trades

At 80 ms, the first close trade in each eight-second interval
from 8 to 32 seconds enrolls when the two fighters accept fresh
hits within 200 ms of each other. The next subject input head
forks the complete state. The branch policies retain queued
packets and presses, the actual warm W-tap and network histories,
and the current handoff state. All four arms admit identical
physical press timestamps in each of the 1,022 cuts. The source
arm reproduces the entire original hit suffix in every case.

| Source timing and policy | Cuts | Stock W-tap paired margin | First two learned inputs only, 4.5 s | First two on W-tap, then handoff, 4.5 s | Full handoff, 450 ms / 4.5 s |
|---|---:|---:|---:|---:|---:|
| Development, W-tap | 186 | 0 | +0.30 | +0.18 | +0.09 / +0.40 |
| Development, handoff | 162 | 0 | +0.10 | +0.47 | +0.18 / +0.85 |
| Comparison, W-tap | 154 | 0 | −0.22 | +0.54 | +0.05 / +1.02 |
| Comparison, handoff | 177 | 0 | +0.44 | +0.20 | +0.02 / +0.89 |
| Calendar-swapped development, W-tap | 170 | 0 | +0.05 | +0.19 | +0.13 / +0.35 |
| Calendar-swapped development, handoff | 173 | 0 | −0.39 | +0.01 | +0.08 / +0.03 |

On the original comparison cuts off W-tap, the full handoff gets
the first five-fresh-hit run in 14 of 154 versus one for W-tap.
The respective opponent counts are three and one. From the same
154 cuts the brief two-input intervention gets to five first
in two cases and the opponent in seven. On the existing handoff
trajectory the full handoff gets there first in 12 of 177
versus three for W-tap. Those 4.5-second tests include a pending
return already committed at the cut when present. Both policies
are scored from every enrolled cut.

In 266 of 331 original comparison cuts, the first movement
difference within two decisions belongs to one of two classes.
In 163, W-tap holds W and Ctrl and the network holds W alone.
The player is already sprinting in all 163 snapshots. The first
own fresh hit launches at 0.9495 horizontal blocks/tick in 91
W-tap branches and 76 two-input branches, and at 0.5275 in 70
and 83. In the other 103 cuts, the network keeps W and Ctrl
through a W-tap release. These are differences in inputs and
the distribution of the next delivered impulse. The next hit
can accept on a different drain or at a different separation.
At the next decision after keeping W and Ctrl, the client is
sprinting in 90 of 103 short branches versus 21 with W-tap,
and its nearest observed gap is 0.127 blocks smaller by the
median paired difference. After changing W and Ctrl to W alone,
the next-decision sprint counts are 24 versus 48 and the gap is
0.029 blocks larger by the same measure. This is local reach at
the sampled decision, with the server hit ledger reported apart.

[Matched trade cuts and control and impulse traces](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/later-trades/summary.json)

### Removing one movement change at a time in regulation

In 80 ms regulation, an offline action filter suppresses selected
differences from W-tap and lets the frozen model run as usual on
each arm's resulting observations. Its handoff gates remain the
same. In the original comparison the full handoff averages
16.6886 versus 16.6876 measured CPS. The ablations use the same
input pacing and admit the same fixed-rate clicks.

| Movement allowed | Plain W-tap wins / mean | Development wins / mean | Comparison wins / mean |
|---|---:|---:|---:|
| Full handoff | 36/72 / 0 | 54/72 / +21.89 | 51/72 / +14.67 |
| W and Ctrl to W alone only | 36/72 / 0 | 35/72 / −6.22 | 22/72 / −15.25 |
| Keep W and Ctrl through a release only | 36/72 / 0 | 43/72 / +8.88 | 42/72 / +4.01 |
| Both changes only | 36/72 / 0 | 43/72 / +7.39 | 41/72 / −0.35 |
| Full handoff except W and Ctrl to W alone | 36/72 / 0 | 50/72 / +11.03 | 39/72 / −1.06 |
| Full handoff except keeping W and Ctrl | 36/72 / 0 | 33/72 / −2.08 | 27/72 / −14.81 |
| Full handoff except both changes | 36/72 / 0 | 25/72 / −9.92 | 29/72 / −8.76 |

Across the ten original and rotated panels, keeping W and Ctrl
alone averages above W-tap in eight. Its two losses are −0.97
and −6.11. The full handoff with that change suppressed loses
to W-tap in nine panels. Suppressing both loses in all ten.
The full handoff beats W-tap in nine. The gain depends on using
these choices together with other responses and on the timing
that each choice inherits. Full runs also change future states,
so this comparison measures the effect of filtering an action
class throughout a bout. The matched trade forks measure the
shorter effects on the same starting states. Native evaluation
remains on the two frozen queued benchmarks.

[Regulation sufficiency results](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/later-trades/directions-summary.json) ·
[Regulation removal results](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/later-trades/necessity-summary.json)

### What a single sprint input changes

Of the later trades, 921 have an eligible stock W-tap release
within 350 ms of the anchor. The model forks immediately before
the first such decision. It releases, holds W alone or holds
W and Ctrl for that decision. The controller uses warmed W-tap
thereafter. All three branches have identical admitted press
timestamps within each timing variant. Five proposed timing
variations change future local client opportunities by at most
5 ms while preserving executed events and pending packets.

| 80 ms panel | Cuts | Plain W-tap unanswered follow-ups | W only follow-ups / 2.2 s gain | W and Ctrl follow-ups / 2.2 s gain |
|---|---:|---:|---:|---:|
| Original development | 309 | 109 | 106 / −0.039 | 148 / +0.084 |
| Original comparison | 300 | 64 | 68 / −0.046 | 109 / +0.055 |
| Calendar-swapped development | 312 | 92 | 123 / +0.022 | 178 / +0.236 |

A follow-up extends the latest accepted hit when it was an
own fresh hit. A new fresh hit must follow within 850 ms of the
previous hit and before a return, including a return in the same
server drain. In the original comparison, W and Ctrl rescue 63
follow-ups, lose 18 and rescue the follow-up in all five timing
variants at five cuts across three calendars and both seats.
Some cuts share the same recorded timing. A separate measure
that starts counting consecutive fresh hits only after the cut
gives the subject the first new pair in 136 timing variants with
W and Ctrl versus 142 with W-tap. With W and Ctrl, the average
accepted margin at 2.2 seconds improves by +0.156 in 110 early
grounded comparison states within 1.6 blocks, versus +0.055 on
the entire comparison set. The corresponding grounded-close
gains on original and swapped development are +0.354 at 78
cuts and +0.543 at 87 cuts.

In m4886 B, the local body still reports grounded at the cut
with an upward velocity of +0.361375 blocks/tick. Fresh hurt
cues from both sides arrived about 15 ms earlier. The body is
already moving away at 0.9495 blocks/tick and the observed gap
is 1.340 blocks. The next contact occurs 44.0 ms after the cut.
The W-tap branch stays out of sprint and retains 0.518 blocks/tick of
recoil. The W+Ctrl branch sprints and that contact reduces
its recoil from 0.449 to 0.269. At precisely the same physical
click schedule, the next fresh hit lands 750–801 ms after the
previous one across five timings, versus 1,056 ms for W-tap.
The defender returns at 801–906 ms in the W+Ctrl branches.
This is a repeatable follow-up under a bounded timing change.
The inputs change both movement before contact and subsequent
contact drag, so the trace does not isolate their contributions.

[One-input paired scores and packet links](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/summary.json) ·
[m4886 B contact and accepted-hit ledger](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/m4886-b-witness.json)

### The grounded close release rule in regulation

An ordinary-input policy uses the close grounded condition on
the first stock release input per local hurt cue. It holds W and
Ctrl for that input if the client is grounded within 1.6 blocks
and has observed fewer than two active unanswered target hurt
cues. It leaves the click rate and the remaining W-tap release
duration unchanged. A control holds on every eligible release
regardless of geometry. Both use only local observations.

| Added RTT, original comparison | Plain W-tap wins / mean | Hold on every release wins / mean | Grounded close rule wins / mean / spread |
|---|---:|---:|---:|
| 0 ms | 27/54 / 0 | 30/54 / +12.07 | 28/54 / −0.43 / −60 to +60 |
| 20 ms | 54/108 / 0 | 52/108 / −0.81 | 63/108 / +3.10 / −82 to +82 |
| 80 ms | 36/72 / 0 | 48/72 / +10.94 | 51/72 / +21.74 / −70 to +91 |

At 80 ms the grounded rule averages +4.29 to +28.99 across all
ten original and swapped timing panels. One development donor
averages −4.06 in the original panel and one comparison donor
averages −1.83 in the fully swapped panel. The original 80 ms
comparison means against matching-seat controls are +16.64
and +26.83 at 16.6857 versus 16.6878 measured CPS, with roughly
7.7 changed decisions per bout. Original 20 ms gains by seat
are −4.39 and +10.59. The original comparison uses previously
exposed clocks and repeated phases. In 216 K3, 207 K5 and 153
K10 common comparison forks, both rules match W-tap exactly on
cycles, returns, inactivity exposure and censoring.

The model and Forge bridge now share the release decision class.
The shared version reproduced all 72 archived 80 ms comparison
rows exactly, including both maintenance endpoints. The native
adapter uses the live baseline's own sampled cue to identify the
release and the native received fresh-hurt cues for continuation.
A separate 64-bout native comparison is queued with pinned
server and Java 8 bridge builds. It covers 32 first-to-100 bouts
and 32 maintenance drills, both seats at 0, 20 and 80 ms, with
four repeats per seat and arm at 80 ms and two at other RTTs.
Input audit checks each activated head and acquisition handoff.

[Individual regulation scores and exact maintenance results](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/closed-loop/summary.json) ·
[Frozen native plan](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/staged/native-plan.json)

### Release geometry and latency

The same late-trade enrollment and single-decision intervention
at 0 and 20 ms preserve the original regulation traces and
the complete state, queues and click times for each W-tap, W
and W+Ctrl fork. The archived comparison clocks contain
very different local release geometry at the three latencies.
The cut denominators below count states and the follow-up
totals count the five correlated timing variants per state.

| Added RTT | Close grounded / all release cuts | Plain W-tap unanswered extensions | W+Ctrl unanswered extensions | W+Ctrl paired margin at 2.2 s, all cuts / close grounded |
|---|---:|---:|---:|---:|
| 0 ms | 0 / 269 | 22 | 76 | +0.036 / — |
| 20 ms | 28 / 527 | 93 | 179 | −0.020 / +0.121 |
| 80 ms | 110 / 300 | 64 | 109 | +0.055 / +0.156 |

The median local nearest gap falls from 2.236 and 2.125
blocks at 0 and 20 ms to 1.723 at 80 ms. Keeping sprint
adds some unanswered extensions outside the close grounded
condition at lower latency. Its score gain is small because
additional hits go to both sides. For example, in the 20 ms
comparison W+Ctrl adds 0.192 own accepted hits and 0.212
opponent accepted hits by 2.2 seconds. In development the
close grounded cut counts are 7/325 at 0 ms and 16/277 at
20 ms. These panels use different clocks and fight states.

Five real v4 W-tap mirrors show the same direction in the
opportunity rate. At the first release per distinct observed
cue, 51 of 274 decisions are grounded and under 1.6 blocks in
two 80 ms bouts. At 0 ms, five of 533 decisions fit across
three bouts. Restricting to the registered late-trade windows
gives 3/10 and 0/12. These are archived baseline observations.
Candidate decisions and matched first-to-100 outcomes are
still pending in the native queue.

[Lower-latency paired exchange results](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/low-rtt/summary.json) ·
[Archived native mirror geometry and individual scores](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native-geometry.json)

### Isolating contact recoil and future latency

The local-contact test selects the first press within 250 ms
that contacts in both branches at the same consume timestamp
and sprints only in the W+Ctrl branch. The simulator then
restores horizontal velocity after that single sprint contact,
or multiplies W-tap's by 0.6 after the same contact. The chosen
ray, attack packet and sprint-state transition are preserved.
Subsequent motion and acceptance can diverge. This is a
privileged physics intervention applied to one contact.

| Original comparison | Eligible timing rows | Plain W-tap margin change at 550 ms | W+Ctrl | W+Ctrl with first brake removed | W-tap with first brake added |
|---|---:|---:|---:|---:|---:|
| 0 ms | 752 | 0 | +0.009 | +0.008 | +0.001 |
| 20 ms | 1,526 | 0 | +0.050 | +0.020 | +0.036 |
| 80 ms | 858 | 0 | +0.164 | +0.017 | +0.105 |
| 80 ms, close grounded | 355 | 0 | +0.315 | +0.034 | +0.183 |

At 80 ms there are 31 strict extensions with W-tap, 80 with
W+Ctrl, 41 with its first brake removed and 57 with an
artificial brake added to W-tap. On the m4886 B witness, the
follow-up moves from 750–801 ms back to 906–953 ms when the
first brake is removed. The isolated brake added to W-tap
also gives 906–953 ms. All five follow-ups within the 850 ms
window require both the changed sprint input and its brake
in this witness. The matched comparison verifies the physical
press times, contact identity, ray and original sequences.

The future-delay test keeps the same inherited release state
and the original already queued packet times. It changes
both actors' RTT only for future events, including the combat
profile's RTT-dependent landing estimate. It updates both
the travel-side nominal RTT and the timing adapter's reference
half-RTT so samples still follow actual send time. At the
80 ms comparison's 110 close grounded states, the 2.2-second
W+Ctrl gains over W-tap are +0.156 with the original suffix,
+0.082 with future 0 ms and +0.102 with future 20 ms. The
same development gains move from +0.354 to −0.054 and +0.028.
Giving the 0 and 20 ms comparison states an 80 ms suffix
produces overall gains of +0.001 and −0.025 versus W-tap.
The boost depends on the state inherited at release as well
as timing after it. These are controlled simulator branches.

[Matched contact-drag interventions](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/contact-drag/summary.json) ·
[Same-state future latency comparisons](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/latency-splice/summary.json)

### How soon W-tap returns after a rescued follow-up

The release ledger identifies each original comparison variant
where one W+Ctrl input adds a strict unanswered follow-up and
plain W-tap misses the same extension. The replay with input
and delivery stages reproduces the original accepted hits and
contact ledger exactly. The same attack packet ID links the
first accepted return to the opponent's local click.

| Added RTT | Plain W-tap extensions across the full panel | Rescued extensions examined | Returns within 100 ms | Return click already sent by follow-up acceptance | First own input with a new cue could deliver position before return click |
|---|---:|---:|---:|---:|---:|
| 0 ms | 22 | 61 | 48 | 0 | 3 |
| 20 ms | 93 | 104 | 85 | 6 | 5 |
| 80 ms | 64 | 63 | 39 | 32 | 3 |

The median return ray is 2.679, 2.623 and 2.652 blocks at
0, 20 and 80 ms. All 50, 90 and 54 first returns in the
three panels are fresh. The remaining 11, 14 and nine are
censored at 2.2 seconds. At 20 ms, the return turns into an
unanswered opponent pair in 20 variants. At 0 and 80 ms
the counts are zero and two. The 61, 104 and 63 rescues
come from 18, 43 and 18 distinct registered cuts with
related timing variants.

An attacker input sees a new target hurt cue before the
return click in 32, 26 and five cases. The position delivery
column is an optimistic bound: it waits for the next own
move and flush, server drain and tracker stages, and the
nominal one-way network delay in each direction. It grants
instant application on the other client and a position
update from the tracker. At 0 ms, only three returns leave
even this optimistic path open. The 20 ms mean lead over
matched W-tap is +1.019 hits at follow-up acceptance and
+0.096 within another 450 ms on this selected subset.
Footwork intended to deny most of these return clicks needs
to act before the newly delivered hit cue.

[Accepted return and click lineage](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/followup-returns.json) ·
[Input and position delivery bounds](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/followup-heads/summary.json)

### First native results from the frozen movement benchmark

The shared lab completed eight fixtures from the original
48-bout v4 plan with the frozen server hash. The accepted
scores and physical click audits cover four regulation and
four maintenance fixtures. The two regulation candidate
matches used the original learned movement throughout.

| Added RTT and seat | Candidate first-to-100 | Plain W-tap mirror | Candidate and opponent measured CPS | Paired improvement over mirror for the same seat |
|---|---:|---:|---:|---:|
| 0 ms, A | 97–100 | 94–100 | 17.540 / 17.568 | +3 |
| 80 ms, A | 75–100 | 69–100 | 17.520 / 17.551 | +6 |

In the independent 0 ms drills, the candidate adds seven
cycles past K3 and gives up a return after 4.057 seconds.
W-tap adds 33 cycles and is still active at the horizon,
16.960 seconds after K3. At 80 ms, the candidate has no
K3 entry and W-tap adds one cycle before a return. This
subset has one bout of each kind per arm and setting.
Seat B, 20 ms and the independent repeats remain pending.

The two completed mirror schedules qualify as model timing
inputs for the already frozen close-release rule. On m6736
at 0 ms the rule wins 9/18 modeled bouts and averages +2.56
versus 9/18 and zero for W-tap. On m6744 at 80 ms it wins
10/18 and averages +6.83, with margins from −24 to +47.
At nominal timing on m6744 it scores 100–86 and 100–85
in the two seats. The modeled mirrors are 76–100 and
100–76. All common K3 and K5 outcomes match W-tap exactly
on both clocks, as do the K10 forks available on m6736.
Two clocks had been inspected before registering this replay
panel. The remaining ten mirror request IDs were frozen ahead
of their results. The separate native close-release cohort
remains in the queue.

[Audited native first-to-100 and maintenance scorecards](../../build/boxing-double-search/timing-transfer/chase-window/v4-shared-qualified.scores.json) ·
[Registered timing replay panel and individual scores](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/prospective/summary.json)

### Spacing changes before the rescued follow-up

All 194 linked return variants have an earlier input head
whose movement could, under the optimistic delivery bound,
reach the opponent before the original return click. They
replay the complete stock and sprint-release states, including
the queued packets and physical press history. The test
changes one or two inputs before the follow-up click to coast,
walk, hold sprint, backpedal or strafe. A wider replay starts
three to five consecutive input heads earlier and also tests
coasting or backpedaling followed by sprint on the final head.
The registered windows use future timing as a diagnostic.
Every arm has exactly the same admitted clicks. The wider
test reproduces all shorter arms and both controls exactly.

| Added RTT | Linked variants | Plain W-tap strict extensions | Sprint release extensions / 500 ms protection | Wider search keeps extension and denies original return | Newly protects for 500 ms | Optimistic margin gain over sprint release, preserving extension |
|---|---:|---:|---:|---:|---:|---:|
| 0 ms | 50 | 0 | 50 / 2 | 3 | 0 | +0.180 |
| 20 ms | 90 | 0 | 90 / 0 | 9 | 0 | +0.056 |
| 80 ms | 54 | 0 | 54 / 3 | 6 | 0 | +0.444 |

Zero plain W-tap extensions follows from enrollment. The
last column lets an offline selector choose the best tested
action separately for each timing, with sprint release as
the fallback. It measures accepted score through 2.2 seconds.
The shorter search's corresponding gains are +0.140, +0.044
and +0.370. Of the 18 distinct cuts with all five timings
represented, none gains both 500 ms protection and accepted
margin over sprint release and W-tap at one fixed set of
input heads across the five. In three variants, added movement
improves the later score of a follow-up already protected for
500 ms by sprint release. These are separate from new denial.

At 20 ms, the median own follow-up ray is 2.925 blocks and
the median W-tap return ray is 2.623, each measured from the
respective client's view at its actual click. The median best
increase in a still-contacting return ray among branches that
retain the follow-up is 0.010 blocks. The two-input backpedal
retains the follow-up in 17 of 90 variants. On m5761 A, a
late coast keeps the follow-up on the original drain and
removes a return from a 2.978-block ray. W-tap's next attack
scores by 448 ms after the follow-up, and both branches finish
the cut at +1. The tested movements rarely get the defender
out of reach while preserving our own reach. Successful
denial can also require covering more than one return click.

[Short movement tests and original return packet fate](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/early-denial/summary.json) ·
[Wider movement and parity check](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/early-denial/wide/summary.json)

### Changing the preceding knockback launch

This diagnostic takes the 70 distinct release states represented
in the early-denial test. It replays each original path to the
preceding accepted own hit. From that single attack packet it
replaces only the outgoing knockback components, leaving damage,
the original hit and packet times, and local contact effects
intact. W-tap and the one-input sprint release replay at all
five timings for each state, including variants where the sprint
release originally failed. The unchanged attacks, contacts and
release decisions exactly replay the earlier traces. These are
outcome-conditioned states with fixed physical press timing.

All 70 preceding packets deliver **+0.361375 blocks/tick** of
vertical lift. The model marks 68 victims grounded at the hit,
and the preset gives the same vertical launch to normal and
sprint contacts. This accounts for the vertical-setting branches
being identical. The two archived native W-tap mirrors m6736
and m6744 also record exactly +0.361375 on all 335 accepted
fresh-hit velocity packets, including both sprint states.

| Added RTT | Timing variants | Plain W-tap strict extensions | Sprint release strict extensions | Normal-strength preceding horizontal launch plus sprint release | New 500 ms protections using stronger or weaker horizontal launch |
|---|---:|---:|---:|---:|---:|
| 0 ms | 65 | 2 | 54 | 0 | 0 |
| 20 ms | 200 | 9 | 99 | 22 | 0 |
| 80 ms | 85 | 2 | 62 | 8 | 0 |

In 335 of the 350 variants, the natural preceding horizontal
launch is already at the sprint magnitude for its separation.
Forcing a normal-strength horizontal impulse usually removes
the unanswered follow-up. Forcing the
stronger horizontal impulse in the remaining variants creates
zero new 500 ms protection. All arms retain the same primary
click cap and actual press timestamps.

The vertical sensitivity check artificially changes just the
preceding packet to zero or +0.461375 blocks/tick. This grants
an extra input to the simulator. The configured boxing preset
does not give the extra 0.1 to a player through sprinting.

| Added RTT | Plain W-tap protected extensions | Sprint release protected extensions | Zero lift plus sprint release | Extra lift plus sprint release | Extra lift paired margin vs plain W-tap / sprint release |
|---|---:|---:|---:|---:|---:|
| 0 ms | 0 | 4 | 0 | 16 | +1.277 / +0.923 |
| 20 ms | 0 | 3 | 0 | 13 | +0.315 / +0.345 |
| 80 ms | 0 | 9 | 0 | 9 | +0.224 / −0.271 |

Only one state gains protection and score throughout all five
timings. On m5743 B at 20 ms, the nominal return click has a
2.730-block ray while W-tap is grounded. The extra lift keeps
W-tap 0.534 blocks above the floor and expands its horizontal
center-to-center gap from 3.130 to 3.797 blocks on the same
click. The ray is 3.406 blocks and misses. The candidate's
follow-up lands about 51 ms later and its first return moves
from 600–648 ms to 1,995 ms after the preceding hit. This
isolates how increased airtime and horizontal separation can
deny the return in this state. Reproducing that trajectory
under the ordinary input rules remains an acquisition question.

[Preceding horizontal launch and matched controls](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/prior-launch/summary.json) ·
[Artificial lift and zero-lift comparison](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/prior-launch/vertical/summary.json) ·
[m5743 B matched return clicks](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/prior-launch/m5743-return-ray.json)

### Timing the click that leads into the follow-up

The same 70 development starts now fork just before the physical press
that produced the preceding fresh hit. Link that press to its consumed
attack and server acceptance. Delay one cap-eligible press by 15 to 120 ms,
then resume ordinary click pacing at the same 20 CPS cap. Evaluate W-tap
and the one-input sprint release after the next locally received hurt cue.
Physics, aim and jump inputs are unchanged. The original press and
earliest release time come from the replay. The five timing variants start
at the earlier fork. Both no-delay arms reproduce the preceding press,
accepted hit and all subsequent accepted attacks on all 70 unshifted starts.

The first own fresh hit must be followed by a second within 850 ms with no
opponent acceptance between them or on either hit's server tick. Protection
requires another 500 ms without a return. A gain also requires a better
accepted-hit margin than both no-delay arms at the 2.2-second cutoff after
the original release. These counts include the preceding hit and use a
different timing cut from the knockback intervention above.

| Added RTT | Timing variants | No-delay W-tap protected doubles | No-delay sprint release protected doubles | Variants with new protection and score gain | New gains at equal physical press counts |
|---|---:|---:|---:|---:|---:|
| 0 ms | 65 | 0 | 4 | 0 | 0 |
| 20 ms | 200 | 2 | 3 | 32 | 2 |
| 80 ms | 85 | 0 | 0 | 8 | 5 |

One fixed delay made a protected double and improved the score across all
five timings of a single start. On m5779 B at 20 ms, the 90 ms delay with
sprint release accepts the first own fresh hit about 99 ms later, then
lands the next fresh around 496 ms after it. Both no-delay arms give away
a return on or within 51 ms of their second fresh. The delayed arm scores
4–2 in all five variants, with 37 own presses versus 39 for both
comparators and 39 from the defender in every branch. No start meets the
same five-timing criterion at equal actual press counts. On m5743 B, the
artificial-lift witness, the 30 ms delay at the unshifted timing loses
the original launch and concedes four straight fresh hits. The earlier
click can matter, but these selected starts do not support a general
delay rule or a native promotion.

[Physical press timing intervention](BoxingPriorPress.java) ·
[Controls, fixed delays and per-timing witnesses](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/prior-press/summary.json)

### Completed native benchmark for the frozen movement bot

All 48 original fixtures finished on the recorded MMC profile. The 24
regulation results below are accepted hits from the tested seat's perspective.
Each entry links to a replay by ID. Both arms use ordinary mouse-input aim
and the same 20 CPS cap. Candidate matches measure 17.51 versus 17.52 CPS
on average, and every pair is within 0.25 CPS. The mirrors average 17.43
versus 17.44 CPS with one pair above a 0.25 CPS difference. Mirror and
candidate bouts have independent clocks.

| Added RTT per player / seat | Frozen movement | Plain W-tap mirror |
|---|---|---|
| 0 ms / A | 97–100 (m6746), 100–99 (m6989) | 94–100 (m6736), 100–99 (m6996) |
| 0 ms / B | 69–100 (m7021), 79–100 (m7001) | 100–99 (m7022), 52–100 (m6994) |
| 20 ms / A | 100–94 (m7000), 15–100 (m7020) | 81–100 (m6990), 100–90 (m7012) |
| 20 ms / B | 100–95 (m6991), 59–100 (m7007) | 100–94 (m7002), 62–100 (m6993) |
| 80 ms / A | 75–100 (m6742), 83–100 (m7017) | 69–100 (m6744), 97–100 (m7019) |
| 80 ms / B | 100–81 (m7016), 100–89 (m6998) | 73–100 (m7004), 8–100 (m7014) |

The frozen bot wins 5 of 12 with a mean margin of −15.08 and a spread
from −85 to +19. The designated W-tap mirrors win 4 of 12 with a mean of
−20.50 and a spread from −92 to +10. At 80 ms the frozen bot wins both
seat-B rounds and loses both seat-A rounds. At 20 ms it is worse on mean
margin than W-tap from either seat despite winning the first repeat in
both. Full-match results fail the promotion gate.

The 24 native maintenance bouts share the acquisition protocol. The
counts are completed cycles from each bout's first qualifying landmark.
Return-only and inactivity-qualified outcomes agree throughout this panel.

| Landmark | Frozen movement entries / cycles / returns / censored | Plain W-tap entries / cycles / returns / censored | Frozen movement / W-tap exposure |
|---:|---:|---:|---:|
| 3 | 10 / 28 / 10 / 0 | 11 / 155 / 10 / 1 | 17.560 / 83.437 s |
| 5 | 4 / 14 / 4 / 0 | 9 / 136 / 8 / 1 | 7.867 / 72.165 s |
| 10 | 1 / 0 / 1 / 0 | 6 / 103 / 5 / 1 | 0.552 / 54.407 s |

[Every qualified native score, click rate and maintenance interval](../../build/boxing-double-search/timing-transfer/chase-window/v4-chase-qualified.scores.json)

At 20 ms, W-tap's 87-hit streak in m7020 accounts for most of the worst
loss. The match was still 15–13 for the frozen movement bot at 10.828 s.
Its fifteenth hit and W-tap's thirteenth landed in the same server drain.
Both clients were sprinting when they consumed the attacks. The candidate's
server sprint flag had been cleared by its previous accepted hit 941 ms
earlier and had received no start-sprint command since. Its return therefore
sent 0.5275 horizontal blocks/tick. W-tap sent 0.9495. Both sent +0.361375
vertical. Before its first travel, W-tap landed a local sprint contact that
further reduced its actual incoming horizontal speed to 0.3165. The
candidate made a local contact before its first travel too, but was out of
local sprint at that contact and kept the full 0.9495. The first actual
horizontal steps went 0.317 and 0.822 blocks away, respectively. W-tap's
first unanswered follow-up scored at 11.426 s. The candidate's next 772
consumed clicks through the end were all geometrically out of reach, even
though it issued 977 presses to W-tap's 963 across the full match. The
packet and input chain identifies the asymmetric launch and braking before
the streak begins. It leaves open whether a different sprint input alone
would have prevented the run.

[m7020 replay](../../runtime/replays/m7020.jsonl) ·
[Same-tick attacks and first movement](../../build/boxing-double-search/v4-reset-confirmation/m7020-opening-trade.json) ·
[Ordered server sprint events](../../build/boxing-double-search/v4-reset-confirmation/native-sprint-causes.json)

### Which earlier sprint inputs change the 87-hit loss

Exact recorded-input replay reproduces all 4,639 modeled events in the
first 13 seconds of m7020. Own travel, opponent interpolation, click
selection, server state and generated packet payloads agree with the
capture. The replay uses the original key presses and exact packet service
schedule through each fork. The fork copies both players, render replicas,
sprint states and pending packets. It generates new packet contents, using
matching route and send-opportunity service samples or nominal latency
for new sends. Both recorded aim and consumed clicks stay fixed. This is
an offline intervention with recorded timing. The W-tap comparator uses
its ordinary cue-based movement, primed on the copied observation history
and evaluated at the same movement opportunities.

The sample includes every qualifying trade between 1.2 and 11.1 seconds
of four inspected 20 ms recordings. The candidate must land a normal
fresh hit, W-tap must land a sprint fresh hit within one server tick,
and both victims must be grounded. This leaves room for both the
pre-trade intervention and the follow-up inside the exact prefix.
Each trade supplies 17 possible start ticks for a one-tick W release and
W+Ctrl the next tick. The unchanged branches reproduce the native scoring
sequence and launch speeds at all 51 start ticks with unshifted new-packet
timing. All comparisons use the same horizon for their matched branch.

| Recorded trade | Original keys with normal-strength hit | Plain W-tap movement, sprint-strength trade hits out of 17 starts | Release then W+Ctrl, sprint-strength trade hits out of 17 starts |
|---|---:|---:|---:|
| m7020 A, 6.985 s | 17/17 | 17/17 | 1/17 |
| m7020 A, 10.828 s | 17/17 | 1/17 | 16/17 |
| m7007 B, 3.177 s | 17/17 | 0/17 | 15/17 |

For the 10.828 s trade, the reset changes the actual stop/start command
sequence. Simply substituting Ctrl on the earlier moving ticks gives
zero sprint-strength trade hits at the nominal timing. The reset changes
the original 1–4 stretch to 2–2 or 3–3 in 16 of 17 variants. The primed
W-tap branch scores 2–2 at all 17. Across the three trades and ±0.5 ms
service perturbations, no fixed start tick for the two-input reset beats
both W-tap and the original keys in all three timing variants.

| m7020 A fork at 9.961 s | Score through 12.5 s | Own launch / own horizontal speed before first travel after taking the trade |
|---|---:|---:|
| Original keys | 1–4 | 0.5275 / 0.9495 |
| Plain W-tap movement from the same fork | 2–2 | 0.9495 / 0.9495 |
| Ordinary W release and sprint restart | 3–3 | 0.9495 / 0.5697 |
| Hold Ctrl at the original trade click's movement step | 4–5 | 0.5275 / 0.5697 |
| Both ordinary changes | 4–4 | 0.9495 / 0.5697 |
| Replace outgoing horizontal velocity only, privileged | 2–2 | 0.9495 / 0.9495 |
| Apply incoming horizontal brake only, privileged | 4–5 | 0.5275 / 0.5697 |
| Replace launch and apply brake, privileged | 4–3 | 0.9495 / 0.5697 |

The privileged branches change exactly the affected velocity components at
the original attack and first local contact. Ordinary inputs also move the
players and change when W-tap's return packets are sent. In the 9.961 s
example the restart pushes W-tap's return to the next server tick, so the
same incoming speed is braked on a different movement step. At the earlier
6.985 s trade, there is continual sprint traffic. A new start command is
already due, but comes 0.36 ms after the weak attack's server application.
W-tap's full movement gives away no fresh return within 150 ms in seven
of the 17 nominal starts of that earlier exchange. A stronger launch helps explain
the long loss, and arranging the trade still matters. No input sequence
here establishes a repeatable advantage over the plain W-tap comparator.

In all 24 first-to-100 bouts of the frozen benchmark, 1,244 grounded fresh
trades qualify under the one-server-tick rule with a full 1.67-second
follow-up. The tested seat deals a normal hit into a sprint hit in 11/217,
5/182 and 2/151 frozen-movement trades at 0, 20 and 80 ms. The corresponding
W-tap values are 1/321, 2/253 and 6/120. For 17 of the 18 frozen-movement
asymmetries and all nine mirrors, the last observed server sprint change was
a prior accepted attack clearing the flag. Only one of the 18 candidate
asymmetries is immediately followed by two unanswered opposing fresh hits
within 850 ms of each other. It is m7020. This mechanism is a clear cause of
a particular collapse, with a small and latency-dependent footprint in
these independent bouts.

[Strict recorded-input fork](BoxingNativeTradeFork.java) ·
[Timing, exact-control verification and outcomes](../../build/boxing-double-search/v4-reset-confirmation/trade-forks/summary.json) ·
[Separate horizontal impulses](../../build/boxing-double-search/v4-reset-confirmation/trade-forks/m7020-factorial.jsonl) ·
[Combined ordinary keys](../../build/boxing-double-search/v4-reset-confirmation/trade-forks/m7020-reset-and-contact.jsonl) ·
[Grounded trades across the native benchmark](../../build/boxing-double-search/v4-reset-confirmation/trade-forks/native-trades.json)

### The close-range rule on the reserved native clocks

All twelve registered W-tap mirror fixtures finished. Two added 0 ms
archives lack complete client captures and are excluded from the model
export. Keep the two previously inspected fixtures separate. The remaining
eight newly recorded calendars export successfully. The frozen 1.6-block
rule and the unrestricted one-input exception use the same physical
polls as plain W-tap under both seats and nine phase pairs per calendar.

| Added RTT | New calendars | Plain W-tap wins / mean margin | Close rule wins / mean margin | Unrestricted rule wins / mean margin |
|---|---:|---:|---:|---:|
| 0 ms | 1 | 9/18 / 0 | 9/18 / −1.56 | 7/18 / −4.28 |
| 20 ms | 4 | 36/72 / 0 | 35/72 / −2.00 | 29/72 / −8.33 |
| 80 ms | 3 | 27/54 / 0 | 43/54 / +42.96 | 37/54 / +17.63 |

At 80 ms the close rule has a mean gain of +36.00, +43.39 and +49.50
on the three separate calendars. The seat means are +43.04 and +42.89.
Individual scores still range from −98 to +99. Close-rule K3, K5 and K10
metrics match the same complete-state W-tap forks exactly across all
eligible starts. These are prospective model results on recorded clocks.
The native close-rule cohort remains separate.

[All reserved native clocks, archive exclusions and model scores](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/prospective/summary.json)

### What one close sprint input changes in the native recordings

The native audit reconstructs the first W-tap release per hurt cue,
ground state, observed distance and the two-hit continuation guard.
It checks every recorded key decision against the frozen rule and
verifies the 0.60 horizontal multiplier on sprint contacts. The same
audit counts the qualifying opportunities in the plain W-tap mirrors.

From eight earlier W-tap mirrors, enroll the first two qualifying
inputs per seat before 25.5 seconds. Reproduce the exact input, movement
and packet history up to each cut. Hold W+Ctrl for one movement step
or replay the original W-tap release. Both branches use the same
consumed click schedule and recorded aim. The unchanged branch
reproduces the ordered accepted events and velocity payloads in all
20 nominal suffixes, within 2 ms. The following counts use a common 2.2-second
horizon and the recorded service timing. Protection requires a fresh
hit within 850 ms, without a return in that server drain or in the next
500 ms. Recoil contacts count the first 260 ms.

| Added RTT | Paired starts | Plain W-tap first hits / protected / recoil contacts | Hold first hits / protected / recoil contacts | Mean hold margin change vs W-tap |
|---|---:|---:|---:|---:|
| 0 ms | 1 | 0 / 0 / 0 | 1 / 0 / 2 | 0 |
| 20 ms | 3 | 0 / 0 / 4 | 2 / 0 / 10 | −0.33 |
| 80 ms | 16 | 2 / 0 / 31 | 6 / 0 / 50 | +0.13 |

The intervention also changes grounded acceleration. In two m6744 B
forks it reduces backward travel by 0.1274 blocks on the first step
while the short-window recoil contact count stays fixed. In the earlier
case the new follow-up lands 103 ms sooner and W-tap returns about
51 ms later. In the reverse test, replacing the executed hold with the
original release key isolates the head while preserving later observed
keys. In m7084 and m7087, 14 of 16 cuts reproduce the unchanged accepted
events. The hold averages −0.21 hits of paired margin over those 14
short suffixes despite adding recoil contacts.

Across those bouts and the next four completed 80 ms candidates,
41 of 46 cuts pass the unchanged-suffix check. The single hold
improves 15 short margins, ties 12 and worsens 14 at recorded
timing. The mean difference is +0.20 hits with a −4 to +4 range.
It increases protected fresh follow-ups from one to four. Six
qualified cuts lack a shared early recoil contact with distinct
sprint flags, leaving 35 for a contact-only test. The difference
between adjacent timing probes shows how sensitive this development
sample is to packet order after the cut.

| Delivery shift | Plain W-tap release margin | Recorded hold margin | Hold with first brake removed | Release with first brake restored |
|---|---:|---:|---:|---:|
| −0.5 ms | +0.03 | +0.14 | −0.23 | −0.06 |
| Recorded | 0.00 | +0.14 | −0.26 | −0.03 |
| +0.5 ms | −0.20 | +0.89 | +0.03 | +0.71 |

At 32.642 seconds in native m7087, the bot holds sprint once at a
1.319-block local gap. An immune contact 44.7 ms after the movement
step brakes incoming horizontal speed from 0.9495 to 0.5697 blocks
per tick. The recorded-input branch gets four fresh hits in the
next 2.2 seconds without a return. A single release instead gives
a 2–2 trade. Removing only that contact's drag from the hold gives
2–2 as well. Applying it to the release gives 4–0. A +0.5 ms shift
in the new-packet delivery estimate gives 4–0 under both ordinary
input branches. The negative shift retains the nominal split.
The native streak starting at 33.122 seconds lasts 45 hits. The
paired result covers its first four hits.

At 35.333 seconds in m7096 the hold gives 4–0 across all three
tested timings. W-tap gives 2–2 at nominal timing and 2–3 at
+0.5 ms. Removing the first contact brake from the hold gives
2–3 in all three timings. Adding the brake to the release gives
3–3 at nominal and −0.5 ms, then 5–0 at +0.5 ms. In this example
the movement and braking both matter. The two altered-velocity
branches are offline diagnostics. The ordinary-input branches
match actual click counts and vary one movement input.

At 46.279 seconds in m7102, the bot has already applied the first
sprint brake when the movement branch starts. Both alternatives
therefore use 0.5697 blocks/tick as the starting backward motion.
Holding reduces the immediate backward step from 0.570 to 0.442
blocks. The following sprint contacts retain identical sprint flags
in the first 160 ms, yet the hold scores 5–0 against 3–2 for the
release. It includes one damage upgrade and continues to an eight-hit
native streak before a contested server tick. The −0.5 ms branch
retains the +4 margin gain. Both inputs tie at +0.5 ms.

The 100–43 m7100 win has a 61-hit streak beginning 9.358 seconds
after its last hold. The candidate follows ordinary W-tap throughout
that streak. Plain W-tap also has 66- and 60-hit streaks in
independent mirrors m7111 and m7117. Large single margins therefore
need the matched series and the local intervention traces for
interpretation.

Across nine shared delivery shifts from −5 to +5 ms, the hold
strictly improves margin at every shift in six of 41 candidate
cuts and one of 16 mirror cuts at 80 ms. In 17 and seven cuts,
respectively, it both wins and loses relative to the single-release
branch as timing changes. Of the three candidate cuts protected
under all nine timings with the hold, each also has some protected
plain-W-tap timings. The steadiest score gain is m7096 at
35.333 seconds, with +4 to +5 hits across the nine shifts. These
shifts are deterministic probes of new packet delivery after
the same cut. They do not fit a probability distribution of timing.

[Native opportunity audit](boxing_release_audit.py) ·
[Paired W-tap mirror cuts](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/comparison-mirrors.json) ·
[First two candidate bouts](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/comparison-held.json) ·
[Next four candidate bouts](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/comparison-additional.json) ·
[All first-contact comparisons](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/brake-factorial-summary.json) ·
[Nine delivery shifts on mirror starts](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/stress-mirrors.json) ·
[Native streaks and observed hold times](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/release-chains.json)

### Completed native close-range comparison

All 64 scheduled bouts pass the input and result checks. The 32
first-to-100 matches include 16 candidate bouts and 16 W-tap
mirrors. Both use 3.0 reach, a 20 CPS cap and the same recorded
MMC knockback preset. Median and range below are accepted-hit
margin from the nominated seat. The controls are independent
native repeats.

| Added RTT | Plain W-tap wins; mean / median / range | Close rule wins; mean / median / range | Close rule measured CPS, bot / opponent |
|---|---:|---:|---:|
| 0 ms | 2/4; −19.25 / −0.5 / −81 to +5 | 2/4; +7.25 / +6 / −16 to +33 | 17.54 / 17.50 |
| 20 ms | 1/4; −10.00 / −6.5 / −30 to +3 | 2/4; +1.00 / 0 / −5 to +9 | 17.48 / 17.38 |
| 80 ms | 4/8; +9.25 / +1 / −37 to +69 | 7/8; +23.88 / +13.5 / −20 to +65 | 17.54 / 17.55 |

At 80 ms the candidate mean from seat A is +16.00 and the
W-tap mirror mean is +28.00. From seat B they are +31.75 and
−9.50. The close rule runs zero times in two of the four 0 ms
candidate bouts, including the 100–67 win. It runs 12 times
across the four 20 ms bouts and 70 times across the eight
80 ms bouts.

The remaining two 80 ms candidate recordings supply 15 more
qualified input cuts. The one-input contrast averages −0.13
accepted hits through 2.2 seconds, with zero cuts improving
strictly at every one of the nine shifts. In the 100–40
m7131 win, removing the 24.406-second hold changes 5–1 to
2–2 under recorded timing. Removing only its first changed
contact brake or restoring that brake to the release gives
3–2. The 59-hit streak starts after a return, 1.473 seconds
after the hold. The same cut yields 4–0 with or without the
hold after a +0.5 ms delivery change.

| Native maintenance landmark | Plain W-tap entries / cycles / returns / censored | Close rule entries / cycles / returns / censored | W-tap / close rule exposure |
|---:|---:|---:|---:|
| 3 | 8 / 125 / 5 / 3 | 14 / 278 / 8 / 6 | 66.642 / 146.394 s |
| 5 | 6 / 111 / 4 / 2 | 14 / 250 / 8 / 6 | 59.062 / 132.128 s |
| 10 | 5 / 99 / 2 / 3 | 12 / 185 / 6 / 6 | 51.714 / 98.266 s |

The two maintenance endpoints agree. The server gate and
captured handoff agree with the independently reconstructed
landmarks. The rule changes zero movement inputs from the
first K3 until its return or censor, across all 16 candidate
drills. Any logged holds come after the first K3 episode.
The different continuation totals reflect independent
acquisition states and timing under the same protocol. The
exact common-state model comparison gives identical maintenance
results. Preserve W-tap as the reference. The observed sprint
brake and acceleration explain several individual starts,
while promotion still requires agreement across seats and RTT.

[All scores, CPS and maintenance distributions](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/close-release-scorecard.json) ·
[Every native handoff and hold](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/release-maintenance-handoffs.json) ·
[Final two 80 ms candidate replay forks](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/comparison-last-eight.json)

### What changes when a position update crosses a client tick

The ordered-delivery replay shifts one network direction at a time
after the same native cut. It keeps both click schedules and aim,
the native application opportunities and FIFO order on each
connection. The shifts affect the model's estimated post-cut
service times. All 72 previously qualified 80 ms starts preserve
the ordered hits and knockback in the nominal unchanged branch,
including 16 plain W-tap mirrors and 56 candidate starts.

The table shows the four inspected traces as hold score and the
one-head W-tap release score, with both inputs evaluated from the
same cut. The right columns delay one downstream route by 0.5 ms.

| Cut | Recorded timing, hold / W-tap | Delay only position updates, hold / W-tap | Delay other downstream packets, hold / W-tap |
|---|---:|---:|---:|
| m7087 at 32.642 s, own client | 4–0 / 2–2 | 4–0 / 4–0 | 4–0 / 2–2 |
| m7102 at 46.279 s, own client | 5–0 / 3–2 | 5–0 / 5–0 | 5–0 / 3–2 |
| m7131 at 24.406 s, defender | 5–1 / 2–2 | 5–0 / 2–2 | 3–3 / 2–2 |
| m7096 at 35.333 s, defender | 4–0 / 2–2 | 4–0 / 1–4 | 5–0 / 2–2 |

Shifting only the position updates scheduled in the first
450 ms of m7087 or 350 ms of m7102 already reproduces the
score tie. The tested single updates move their main-thread
application by tens of milliseconds when they miss an original
processing opportunity. None independently reproduces that
improvement in the W-tap arm. In m7102, independently delaying
the update scheduled 249.251 ms after the cut worsens the
hold from 5–0 to 2–4. When the whole position sequence is
shifted the hold stays at 5–0 while W-tap improves.

Across the five timing probes on each of four directions and
the combined probe, three candidate cuts improve every margin.
None of the mirror cuts does. The robust short-score witness
in m7096 gains three to seven hits and retains its protected
follow-up at every setting. Plain W-tap gets protection in one
combined shift of that same state. No cut in either panel adds
protection over W-tap at every tested setting. These are timing
sensitivities at already inspected starts. They leave open
which stable conditions a bot can recognize from its own view.

[Ordered common-state route forks](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/delivery-routes.json) ·
[Position-only and individual-update tests](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/qualified-hand-forks/delivery-packets.json)

### How plain W-tap starts long native combos

In m7111, both sides land a 0.9495-block/tick sprint launch
while grounded, 52 ms apart. Each makes a contact between
receiving the velocity and its first movement. Seat A still
has sprint on at that contact and brakes to 0.5697. Seat B
keeps the full impulse through its non-sprint contact. A
moves back 0.4423 blocks with forward acceleration. B moves
back 0.9495 blocks. B's first seven selected contacts after
the streak begins are immune, and the other 12 consumed
clicks through the third hit miss. Its nearest hitbox on
those misses remains at least 3.135 blocks away. A lands
its second fresh hit 494 ms after the first and continues
to 66 while using ordinary W-tap.

The 61-hit m7100 sequence shares the initial mechanism. At
the recorded stock W-tap opening, the winner receives 0.5275
and brakes it to 0.3165 before forward travel. Its opponent
receives 0.9495 and its non-sprint contact leaves that
unchanged. Their backward first steps are 0.1891 and 0.8221
blocks. This asymmetry can arise with equal or unequal
server launch magnitudes, before the follow-up fresh window.

The comparison below enrolls every uncontested fresh streak
start after an opponent fresh hit, with at least 1.6 seconds
left to observe a second hit before a return. Doubles require
that second fresh hit within 850 ms. A long streak has at
least 20 fresh hits and no gap above 1,100 ms. Both sides of
each mirror are described. Multiple starts in one bout are
correlated. The control rate is descriptive.

| Added RTT | Plain W-tap mirrors starts / doubles / long | Close rule starts / doubles / long |
|---|---:|---:|
| 0 ms | 222 / 27 / 1 | 144 / 20 / 1 |
| 20 ms | 214 / 25 / 1 | 130 / 15 / 0 |
| 80 ms | 476 / 69 / 5 | 253 / 39 / 4 |

All ten observed 80 ms long streaks across both fighters
start with a sprint hit. The defender is airborne at the
second fresh hit in each. A further W-tap streak occurs
against the candidate. Sprint alone is common among controls,
including 367 of 476 mirror starts at 80 ms, and does not
identify these runs. The 66-hit W-tap example explains how
ordinary timing and continued pressure can produce the
same first-step separation as the experimental hold.

[Native starts with their linked velocity and click traces](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/long-chain-starts.json) ·
[All native fresh-run starts](../../build/boxing-trade-skills/recorded-hit-selection/range-transfer/acquisition-control/release-decision/native/chain-controls.json)

### Skipping the first contact on a fast approach

Use 31 strict native prefixes from 14 earlier W-tap mirrors on the
range-v4 preset. Pick the first eligible own hit in each match for a
quiet approach, a paired trade and a delayed reply. The cut precedes
the physical click by 48–96 ms. Every cut reproduces the recorded
accepted hits and launch magnitudes at nominal time. Each model arm
keeps both physical press schedules and starts with the same state,
queues and W-tap histories. Compare centered aim with 100 ms of
ordinary quantized mouse input tracking 4°, 8° or 12° to the side.
Opposing aim follows the same tracking code. Each offset is tested
in both directions. The table pools 25 matching timings per cut.

| Model intervention | Plain W-tap doubles | Intervention doubles | Accepted-margin gains / losses vs W-tap | Cuts with a new double and gain at every timing |
|---|---:|---:|---:|---:|
| 4° left or right, per direction | 55 | 55 | 0 / 0 | 0 |
| 8° left or right, per direction | 55 | 80 | 25 / 41 | 1 |
| 12° left or right, per direction | 55 | 39 | 50 / 192 | 0 |
| Isolated miss of the original scoring press | 55 | 150 | 268 / 195 | 2 |

The fixed 8° turn improves one quiet 80 ms start, m7340 B. At the
original scoring press the aligned ray reaches at 2.813 blocks.
The 8° offset makes that press miss. The next press scores a fresh
hit 60 ms later and the suffix goes from 3–4 to 4–4. Keeping centered
aim and excluding contact on just that press reproduces the double
and score comparison across all 65 widened timing settings, including
the failures. The two interventions gain a point and a new double in
56 settings. A 10 ms delay in our attack upload still yields a double
and loses three points. The 4° arm preserves the original shot and
can redirect the launch by about 2.17° without changing any score in
the panel. The m7340 gain is reproduced by delaying the first hit
alone. The attacker still lands about 32 ms after the defender.

At 20 ms in m7346 A, the isolated miss moves the first own score
47 ms later while the incoming hit keeps its original time and
launch. The model puts the attacker's first landing 70 ms before
the defender's, versus 21 ms in the W-tap arm. The next own fresh
hit follows 601 ms after the delayed shot and the defender returns
48 ms later. The 1.8 s suffix moves from 2–2 to 4–3, with the
one-point margin coming from an accepted damage upgrade. The new
double and positive margin survive all 65 widened timing settings.
Both examples eventually take returns. The isolated miss uses the
factual press as an offline diagnostic. It cannot pick that press
using the server result during a fight.

[Mouse offset screen](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/summary.json) ·
[Single-press controls and wider 80 ms timings](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/attribution.json) ·
[Wider 20 ms timings](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/second-witness-wider.json) ·
[Movement and landing traces](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/witness-ground.json)

The first own-view rule arms from the movement head at 3.70–4.10
blocks of displayed reach while grounded and closing at least
0.13 blocks/tick. The displayed feet must be within 0.05 blocks
of own height and neither hurt cue can be newer than 1.1 seconds.
It skips the first selected contact in the next 140 ms. The match
cap is four eligible heads, at least 1.1 seconds apart. The model
skips a contact with a synthetic one-press ray veto. The decision
uses own information at that press, keeps the physical calendar,
and permits normal attack and movement after it. Eligible cuts
are chosen before any native hits or defender observations are
used to score them. All 51 nominal recorded branches match native
acceptances and launch magnitudes. Two exports report extra network
events after the strict interval needed for their last scored cut.

| Own-view cohort | Cuts / timing variants | Plain W-tap doubles | Skip-first doubles | Accepted-margin gains / losses vs W-tap | Cuts with a new double and gain at every timing |
|---|---:|---:|---:|---:|---:|
| Development, 14 matches | 31 / 775 | 0 | 100 | 179 / 70 | 2 |
| Comparison, 0 ms | 8 / 200 | 0 | 34 | 0 / 64 | 0 |
| Comparison, 20 ms | 7 / 175 | 0 | 25 | 11 / 25 | 0 |
| Comparison, 80 ms | 5 / 125 | 25 | 25 | 41 / 9 | 0 |

The nine independent comparison recordings are from the W-tap
observer arm of the previous jump study. Their clocks were exposed
there and furnished no inputs to this rule's design. The median
paired 1.8 s score difference is zero, with a range of −4 to +2.
At 20 ms, m7375 A begins at 3.835 blocks and 0.149 blocks/tick.
This closely matches the successful m7346 A. Two physical presses
are consumed only 2.154 ms apart, so omitting one moves the accepted
hit by 0.731 ms within the same server processing interval. Both
arms score 4–3. One physical press is a variable time delay.

At 0 ms, m7359 B has received the opponent's velocity 41.313 ms
before the original own press. W-tap's local sprint contact reduces
the 0.9495 blocks/tick recoil to 0.5697 before the first move. The
skip keeps the full recoil and takes a 0.8221-block backward step,
versus W-tap's 0.4423. The next physical press is outside reach at
3.302 blocks. W-tap's press reaches at 2.923. The model suffix
moves from 2–2 to 0–4. This identifies a harmful omission to avoid
using fresh local hurt or recoil evidence at the press. Such a guard
would also need to account for hits still arriving. The experiment
supports conditional hit selection and leaves both the playing
policy and native first-to-100 promotion open. The established-combo
comparisons retain their separate protocol.

[Frozen own-view protocol and all comparison rows](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/own-view-transfer/summary.json) ·
[Comparison press and server traces](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/own-view-transfer/comparison-examples.json) ·
[Incoming velocity and local contact drag](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/own-view-transfer/incoming-brake.json)

### Guarding the recoil brake and enforcing a real delay

Keep the previous own-view approach gate and compare four model
interventions from each exact prefix. The recoil guard cancels the
wait at a selected press if the client has received its own hurt
status or velocity since arming, or is moving away at over 0.05
blocks/tick along the line to the visible target. The time-based
arm skips any selected press in the first 35 ms from the first
selected contact, including that contact. The combined arm can
release early if the recoil guard trips. The exercise uses the
original press calendars in every branch, with simulated misses
on skipped contacts. It tests a decision at consumption. A live
bot deciding whether to inject a physical click would read its
observations at that earlier time.

All 51 development cuts reproduce both original simulated arms
over all 25 timing variants, and the nominal native accepted hits
and launch magnitudes. The older comparison is now development.

| Model arm on 1,275 development variants | Plain W-tap doubles | Arm doubles | Accepted-margin gains / losses vs W-tap | Mean paired margin | Cuts with a new double and gain at every timing |
|---|---:|---:|---:|---:|---:|
| Skip one | 25 | 184 | 231 / 168 | −0.024 | 2 |
| Guarded skip one | 25 | 184 | 231 / 118 | +0.072 | 2 |
| Wait 35 ms | 25 | 209 | 251 / 168 | −0.009 | 3 |
| Guarded wait 35 ms | 25 | 209 | 251 / 118 | +0.088 | 3 |

Plain skip leaves the first own hit in the same server tick in
41 variants and loses it entirely in 16. The combined arm moves
all 750 changed first hits into a later tick in this panel. At
m7375 A, the minimum wait suppresses both presses consumed 2.154
ms apart, shifts the next accepted own hit 101 ms later and goes
4–2 instead of plain W-tap's 4–3. The double and +1 gain hold
through all 65 widened timings. The recoil guard preserves the
0.9495-to-0.5697 brake in m7359. Across all matched development
variants it cancels 75 initial skips and removes 50 score losses.

Of the 750 guarded skips, 50 take place after an accepted incoming
hit the modeled guard has yet to observe. Fourteen of those lose
score and none gains. A further 250 have a pending opponent attack
at first contact, with 80 gains and 27 losses. Knowing an opponent
packet is in flight does not by itself mean an immediate reply is
better. These annotations use offline server state. In nominal
native traces the modeled first-contact guard agrees at all 33
selectable clicks. The other 18 heads have no qualifying click in
the 140 ms window. The native client has a packet cue at consumption
in three cases and has it before key injection in two of those.

[Frozen analysis and timing protocol](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/analysis-protocol.json) ·
[Development comparisons](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/development-results.json) ·
[Widened witness timings](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/wider-witnesses.json) ·
[Native guard cues](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/development-guard-audit.json)

Twelve fresh native W-tap mirrors provide two repeats on both
seats at each of 0, 20 and 80 ms added RTT. All finish at 100 hits
and qualify on the input audit. The physical click rates differ
by at most 0.234 CPS within a bout. Ten mirrors supply 19 eligible
strict prefixes. The two with no arming head remain in the fixture
list. None of the 19 scored windows overlaps a measured server
interval above 100 ms. All four interventions and the baseline
share the same complete state, press calendar and cloned queues at
each prefix. The recorded arm reproduces all 19 native score and
launch sequences at nominal timing.

| Added RTT, cuts / variants | W-tap doubles | Guarded wait doubles | Guarded wait gains / losses vs W-tap | Mean paired margin | Stable new double and gain |
|---|---:|---:|---:|---:|---:|
| 0 ms, 8 / 200 | 25 | 34 | 25 / 50 | −0.500 | 0 |
| 20 ms, 6 / 150 | 25 | 25 | 25 / 0 | +0.167 | 0 |
| 80 ms, 5 / 125 | 0 | 41 | 74 / 9 | +1.216 | 1 |
| All, 19 / 475 | 50 | 100 | 124 / 59 | +0.162 | 1 |

The median paired score change is zero and the range is −4 to +4.
The guard removes 75 of the unguarded arm's 134 losses. Every
35 ms arm has the same outcome as its respective one-press arm in
this sample. All surviving contact waits skip one press or none.
Of 325 guarded waits that skip a press, 32 register the first hit
in its original server tick. Delaying a local press does not
necessarily change its server tick. At 80 ms the two available
seat-B mirrors have average paired improvements of +2.44 and +2.00,
while the one available seat-A mirror averages −0.18. Even choosing
the best tested action with full future knowledge produces a
stable new double and gain at only one of the 19 cuts.

The fresh witness is m7386 B. Its ground approach has a displayed
nearest gap of 3.863 blocks and forward speed of 0.149 blocks/tick.
Delaying the first effective attack moves its acceptance 94 ms
later. The incoming hit and our first landing keep their timing.
W-tap lands 34 ms after the defender. With the delay it lands 69 ms
before the defender. The score goes from 3–4 to 3–2 over the short
fork. Under the widened attack and position variations all 65
timings gain score (median +2, range +1 to +3), and 64 give the
new double. The probes do not demonstrate a third unanswered hit.

At the 16 nominal selected contacts, all native guard decisions
agree with the modeled cues at consumption. In each of the three
guarded cases, the original physical click was queued on a render
poll before the hurt and velocity packets arrived. The packets
are already visible at the corresponding native input head in all
three cases. That head is a possible ordinary-input decision point.
A live implementation would need to measure the cost of moving
the click out of the earlier poll and compare with equally paced
W-tap on the same admission schedule. The fixed-consumption model
does not establish the whole-match result or qualify this guard
for deployment. W-tap remains the maintenance reference.

[Fresh mirror settings and scores](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/mirrors.json) ·
[Prospective paired results and timing spread](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/prospective-results.json) ·
[Fresh 80 ms wider witness](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/prospective-wider-witness-m7386.json) ·
[Landing trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/prospective-witness-landing-m7386.json) ·
[Injection and client tick audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/prospective-guard-audit.json)

### Deciding at the client input tick

Two ordinary W-tap arms use the same fast grounded approach
predicate and the same 20 CPS limit. One logs the first eligible
selected click and plays as usual. The other issues that click at a
client input tick within the same 140 ms approach window. A render
poll that misses the opponent still admits the ordinary press. The
first selected input-tick click or the window expiry restores the
usual admission behavior. The counter continues from the actual
physical injection. Aim, reach and movement stay on W-tap.

Twelve previously exposed native W-tap mirrors supply the recorded
opportunities for the model. Each comparison pairs complete starting
state and clocks across both seats and five client phase settings.
At the first deferred press, the 114 paired scenes keep the first
effective click on the same input tick. Its physical injection is
0.46–37.05 ms later, median 16.94 ms. The median change in subject
CPS over that same interval is −0.011, with a range of −0.038 to 0.
Later contacts diverge in 98 cases. In 66 of 114, leaving the next
original physical click at its original time would violate the
50 ms cap, and all 66 of those presses selected contact in the
original branch. The first changed accepted point occurs a median
4.75 seconds after the first deferral among the 70 cases with a
shared-horizon difference. Four change within 500 ms. Shifting
admission can change much later scoring through the physical cap.

| Completed common-clock first-to-100 | Ordinary W-tap wins / median margin | W-tap at input tick wins / median margin | Guarded hold at input tick wins / median margin | Hold minus input-tick W-tap paired median [range] |
|---|---:|---:|---:|---:|
| 0 ms, 40 pairs | 20 / 0 | 19 / −7 | 20 / −1 | −3.5 [−76, +85] |
| 20 ms, 40 pairs | 20 / 0 | 20 / 0 | 16 / −2.5 | 0 [−101, +24] |
| 80 ms, 40 pairs | 20 / 0 | 24 / +2.5 | 18 / −1 | 0 [−80, +91] |
| All, 120 pairs | 60 / 0 | 63 / +1 | 54 / −2 | 0 [−101, +91] |

The model-only guarded arm takes the same first selected decision
at the client input tick. It allows the click when it sees the own
recoil cue and otherwise waits at least 35 ms for a selected click,
with earlier release if recoil arrives. This version changes actual
press pacing and compares against W-tap on the same input-tick
schedule. Before its first hold, each pair has identical accepted
events. The model has 242 holds and 26 preserved recoil contacts.
The whole-bout paired actual CPS change has median −0.017, with a
range of −0.077 to +0.016. The guarded arm improves 48 bouts, loses
56, and wins in all five timings on no donor-and-seat pair. Three
pairs lose margin in every phase. The unmodified input-tick control
has no demonstrated positive score effect under the paired spread.

[Common-clock W-tap admission comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/score.json) ·
[Click cap and earliest scoring changes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/admission-diagnosis/score.json) ·
[Guarded hold against equally scheduled W-tap](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/guarded-hold/score.json)

In 24 independent native first-to-100 matches, the ordinary
W-tap control wins 6/12 with median margin 0 [−32, +29]. The
input-tick W-tap arm wins 8/12 with median +2.5 [−33, +81]. It
has two repeats on each seat and RTT, as does the control. Actual
subject median CPS are 17.469 and 17.436 respectively, and the
largest subject/opponent difference in any bout is 0.242 CPS.
All bouts qualify on cap, controls, inputs and match settings.
Six share a server interval above 100 ms, which remains recorded
in the audit. The independent runs share setup and pacing rules.

| Added RTT and nominated seat | Ordinary W-tap scores | Input-tick W-tap scores |
|---|---|---|
| 0 ms A | 100–74, 76–100 | 100–99, 100–97 |
| 0 ms B | 100–99, 71–100 | 67–100, 100–33 |
| 20 ms A | 68–100, 95–100 | 100–94, 94–100 |
| 20 ms B | 100–71, 100–99 | 100–97, 95–100 |
| 80 ms A | 100–96, 99–100 | 100–19, 100–50 |
| 80 ms B | 94–100, 100–99 | 96–100, 100–98 |

The largest two native margins both come from input-tick arm
fixtures where the tested deferral never fires. Both this spread
and the common-clock model leave the admission change unqualified
as a score improvement. It establishes feasibility at this input
boundary. The 25 first frame deferrals all produce a fresh hit in
the first recorded attack loop after the original frame. Nineteen
explicit input-tick selections move physical injection 0.78–40.24 ms
later (median 20.36). In the other six, the 140 ms window expires
before the next loop, and the ordinary input supplies the contact.
There are no observed interventions during an active native K3+
streak in these regulation traces. The separately registered
maintenance gate still applies to a playing controller.

Three native contacts receive own hurt and velocity between the
blocked frame and actual input-tick admission. Two are at 0 ms
and one at 20 ms. In each the preserved sprint contact reduces
horizontal recoil from 0.9495 to 0.5697 blocks/tick. For example,
m7403's velocity applies 23.28 ms after the original frame, and
the native input admits the click 25.43 ms after it. This directly
demonstrates the availability of the later cue while keeping the
original attack loop. The native arms use W-tap inputs throughout.
The model-only 35 ms guarded hold fails the equally scheduled
W-tap score comparison and has no live rollout.

[Native fixture plan and complete score and input audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/score.json) ·
[Native input probe](../../src/lab/BoxingApproachPoll.java) ·
[Common-clock control and experimental hold](BoxingApproachPollCompare.java)

### Where the input-tick hold gains or loses the exchange

Replay all 120 earlier closed-loop comparisons and verify the original
ordered hits, physical inputs and first-to-100 scores. Trace the first
actual hold in 108 variants. Both branches already use the client-input
timing control. Each horizon starts at the same cap-eligible poll and
includes accepted damage upgrades. The double count preserves attack
ordering inside a server drain and rejects a return through the drain
of the second fresh hit. The first-hold trace covers the original
repeated rule. The separate duration test acts once.

| Time from first hold | Matched W-tap comparison | Wait gains / losses in 108 variants | Whole-bout regressions already behind at this horizon |
|---|---|---:|---:|
| 250 ms | Same input-tick W-tap | 0 / 1 | 1 of 56 |
| 1.1 s | Same input-tick W-tap | 22 / 6 | 3 of 56 |
| 1.8 s | Same input-tick W-tap | 21 / 19 | 12 of 56 |

The wait creates 14 clean doubles, compared with one for W-tap.
Thirty of the 56 worse full bouts give up more hits before their
first own fresh than the control. Most whole-bout regressions occur
after a first exchange that tied or improved on score. This comparison
does not by itself distinguish later applications of the hold from
ongoing changes to press timing and motion after the first one.

Forty-six variants deliver their first fresh reply one or two server
drains after the first incoming hit and land before the defender. The
landing comparison requires both ground contacts before another fresh
launch on either respective client. All 14 wait doubles occur in this
group. A further 16 variants fail to select any contact whose receipt
could be handled in the first two fresh drains under the model's
attack propagation. Eight variants share a drain with the return on
their actual follow-up. Six concede a return before their next fresh
window, and two select a contact near the opening with an earlier or
late return outcome. The labels use the modeled client views and server
drains. Early landing alone leaves several different failure mechanisms.

Run a single guarded wait of 20–95 ms from that same first opportunity.
Keep both actors on the actual physical limiter and on their ordinary
subsequent movement and attack controls. The development panel uses
twelve earlier mirror clocks. The separate panel uses twelve native
ordinary W-tap observer recordings from the admission study. Those
clocks were exposed for admission timing and supplied no duration
examples. All 24 clocks export successfully. There are 108 and 103
eligible held starts across both seats and five phases per clock.

| Minimum wait | W-tap short margin difference | Development mean difference / doubles | Comparison mean difference / doubles |
|---:|---:|---:|---:|
| 0 ms, W-tap | 0 | 0 / 1 | 0 / 3 |
| 20 ms | 0 | −0.083 / 13 | +0.233 / 15 |
| 35 ms | 0 | −0.009 / 14 | +0.233 / 13 |
| 55 ms | 0 | +0.241 / 13 | +0.515 / 24 |
| 75 ms | 0 | +0.185 / 15 | +0.641 / 18 |
| 95 ms | 0 | +0.139 / 15 | +0.573 / 14 |

The 55 ms rule's 1.8 s advantage is strongest at 20 ms on development
(+0.56) and positive at all three comparison RTTs. At 0 ms on
development it averages −0.025. Across 17 complete five-phase donor/seat
sets in each panel, selecting a duration with hindsight finds only
two always-positive short margins on development and six on the
comparison. No duration yields both a new double and score gain at
every phase of any complete set. Whole first-to-100 score shifts do
not follow the opening means. For example, 55 ms has a median paired
whole-bout change of −2.5 on development and +2 on comparison. This
establishes some short transfer of the waiting action and leaves
selection and whole-bout qualification open.

An independent mechanism probe keeps the 35 ms reply and clamps just
its outgoing horizontal S12 to normal strength, 0.5275 blocks/tick.
The accepted first reply, its vertical velocity, and all earlier
inputs and service times match exactly. In the 16 landing-first
misses above, the clamp restores a first-window contact in eight and
adds eleven doubles. It gains short score in just three, and no case
loses in this selected set. Across the whole development and comparison
panels, it drops all 14 and 13 existing wait doubles, respectively.
In 26 of those 27 breaks, the defender returns before the next own
fresh opportunity. Mean paired score versus the unmodified wait is
−0.111 on development and +0.029 in comparison. The weaker launch
brings us back into reach and also gives back the defender's window.
The probe uses direct model velocity replacement through the same
packet, so it diagnoses the horizontal component without claiming
to implement this result through ordinary movement.

At intermediate synthetic strengths, 0.75 gives the best paired
score in both panels. It adds 21 doubles and loses nine on development,
and adds two and loses eight in comparison. It has positive mean
short score (+0.102 and +0.126) versus the unmodified wait, with
24 gains and 10/8 losses. No strength adds a new double with a
score gain in every phase of a complete donor/seat set. All 211
unmodified delayed replies apply 0.9495 blocks/tick horizontally.
A straight sprint hit on this lab preset would need about 3.60
blocks of server horizontal separation for a 0.75 launch under the
range rule. The traced replies end their server drains no farther
apart than 2.29 blocks. End-of-drain spacing may differ from spacing
at impact. The practical question is how to create enough space
before releasing the hit while preserving the expected first reply.

[Ordered first-exchange results](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/guarded-hold/first-exchange/ordered-summary.json) ·
[Follow-up window ledger](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/guarded-hold/first-exchange/follow-up-cause.json) ·
[Single-hold development scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/development-ordered-summary.json) ·
[Separate model scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/comparison-ordered-summary.json) ·
[Horizontal impulse mechanism](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/first-impulse/comparison-summary.json) ·
[Intermediate launch strengths](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/first-impulse/midpoints/summary.json)

### Footwork at the withheld input

Fork immediately before the first input poll that withholds a click under
the recoil guard. Try 28 movement sequences of up to three client ticks at
each of 35, 55 and 75 ms. The first movement can change in the same client
tick as the poll. The players inherit the same queues, positions, velocity
and W-tap histories. The 20 CPS physical cap and aiming procedure are
shared. Actual click counts may differ when an input is withheld. Score
accepted hits over the next 1.8 s, including damage upgrades. These are
model forks on two previously exposed native clock panels, with five
input phases per donor and seat, at 0, 20 and 80 ms added RTT.

All 211 runs reproduce the previous first attack, ordered double and
short margin in each of the four unchanged control arms. An additional
double count requires two fresh hits in different server drains and no
opposing acceptance from the first through the second drain, including
an opponent hit immediately before ours in the first drain. A hit on an
earlier drain is allowed. The original ordered-follow-up counts remain
available under their original definition.

| Panel | Input-tick W-tap mean short margin / clean doubles | 35 ms wait mean margin / clean doubles | 35 ms wait, release W twice then sprint | Donor/seat sets with any fixed sequence gaining a clean double and points at all five phases |
|---|---:|---:|---:|---:|
| First, 108 forks | 0.000 / 1 | −0.009 / 14 | −0.074 / 1 | 0/17 |
| Second, 103 forks | −0.087 / 3 | +0.146 / 11 | 0.000 / 4 | 0/17 |

For an all-phase gain, the sequence must beat both W-tap and the same wait
with stock footwork, and neither control may already have a clean double.
Thirty-four sequence/fork pairs in the first panel and 122 in the second
meet those criteria individually. None repeats across a complete set.
The largest server center gap **at impact** on any delayed first reply in
these panels is 2.187 blocks. Distance attenuation starts at 3.0 on the
lab’s MMC preset. The movement at this late poll can remove the sprint
bonus by releasing the movement keys, but it never reaches the distance
needed to weaken a sprint hit through spacing.

On m7392 seat A at 0 ms, the 35 ms wait plus two releases and a sprint is
the only sequence to gain points and produce two ordered fresh hits across
all five original phases while both previous controls fail that measure.
At nominal timing the stock wait’s reply is a sprint hit at 1.349 blocks,
with horizontal knockback of 0.9495 blocks/tick. The release makes the
reply a normal hit at 1.476 blocks with 0.5275 horizontally. Vertical
velocity is 0.361375 in both. W-tap hits first within that same server
drain. The release arm gets a damage upgrade 349 ms later and another
fresh hit 599 ms after its first. Its short score is 4–3 versus 2–2 for
each control. Actual presses over that window are 31/31 for the release
and stock wait, and 32/31 for input-tick W-tap. The footwork recovers a
point after a trade. The shared opening drain excludes it from the clean
double count.

| Perturbation on m7392 A | Input-tick W-tap | Footwork gain / tie / loss in short margin | New ordered double and a gain over both controls | New clean double and a gain over both controls |
|---|---|---:|---:|---:|
| Both input timelines varied from match start, plus attack upload shifts after the hold | Paired on all 153 forks | 99 / 12 / 42 | 89 / 153 | 0 / 153 |
| Nominal starting state and queues kept, only future W-tap input opportunities and attack uploads varied | Paired on all 99 forks | 90 / 9 / 0 | 67 / 99 | 0 / 99 |

In the first row, 17 timeline settings vary either client by up to 10 ms.
Each uses a 0/5/10 ms delay independently on new attack uploads by each
side. The second row uses 11 W-tap offsets up to ±20 ms. Draws within each
row share the same donor. A −5 ms W-tap shift from match start changes the
first hold from 2265 to 2224 ms and moves the reply a full server drain
earlier. W-tap still lands at 2352 ms and its first hit ends the streak.
The release goes 2–2 while W-tap goes 4–2. Apply the same −5 ms shift
after the nominal hold instead and the release gains one in all nine
upload settings. The setup state matters more than small future timing
changes in this example. Testing earlier spacing requires starting the
intervention before the first withheld poll. A trade-recovery rule would
need to recognize the recoverable state from the subject’s own view.
The clean hit-selection, native first-to-100 and established-combo
comparisons remain open.

[Footwork replay model](BoxingApproachFootwork.java) ·
[Frozen protocol and model calibration](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/footwork/exclusive/protocol.json) ·
[Whole-approach timing outcomes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/footwork/exclusive/wider-witness.json) ·
[Fixed-state timing outcomes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/footwork/exclusive/fixed-prefix.json)

### Moving before the first attack decision

Take the first ready own input in each of three visible hitbox-distance
bands, 3.70–4.10, 4.10–4.65, and 4.65–5.20 blocks. Require grounded feet,
target height within 0.05 blocks, own velocity toward the target of at
least 0.13 blocks/tick, and neither hurt cue for 1.1 s. The cut does
not depend on whether a future attack or hold will occur. Fork the
complete state and apply a single one-to-four-tick movement sequence
from that head. Compare to ordinary W-tap, W-tap with input-tick
admission, and the same optional guarded wait under stock movement.
The rate cap is 20 CPS for both players. Count accepted points over
1.8 s, including upgrades. Two fresh hits within 850 ms count as a
clean double if the defender gets no accepted hit in either drain or
between them. The three-hit chain uses a separate activity cutoff of
1.1 s between consecutive fresh hits and excludes shared drains.

The two exposed clock panels give 627 eligible cuts from 240
donor/seat/timing starts and up to three bands per start. Each cut tests
31 movement sequences including stock, crossed with no hold and 35, 55,
or 75 ms guarded holds. All cuts precede the first input-tick deferral.
The ordinary W-tap control scores identically when replayed before and
after the intervention arms. The maximum observed server separation at
a first sprint hit is 2.855 blocks. No first sprint hit enters the
range-attenuation regime. Early movement can change the gap and attack
order while keeping the full sprint impulse.

Eight distinct donor/seat combinations have a new clean double or
three-hit chain and positive score gains at all five initial timings.
Each compares to all three controls. These are selected examples.
When the strongest backstep move is applied blindly in the 3.70–4.10
band, it averages **−1.21 and −1.45 points** relative to W-tap in the
two panels. At the same distance, 55 ms plus two diagonal sprint
steps averages +0.31 and +0.39 in the short exchange, without an
all-phase double-and-score gain on any donor/seat. The fixed moves
do not establish a general playing rule.

| Selected example | Move and local distance band | Paired W-tap clean doubles / three-hit chains | New clean doubles / three-hit chains with positive margin | Short score change vs W-tap, gain / tie / loss |
|---|---|---:|---:|---:|
| m7378 A, 20 ms | Three backsteps then sprint, 3.70–4.10 | 0 / 0 | 85/85 / 74/85 | 85 / 0 / 0 |
| m7378 B, 20 ms | Three backsteps then sprint, 3.70–4.10 | 0 / 0 | 72/85 / 0/85 | 81 / 4 / 0 |
| m7381 A, 0 ms | Release forward twice, 4.10–4.65 | 0 / 0 | 0/85 / 57/85 | 57 / 28 / 0 |
| m7389 A, 80 ms | Release forward three times then sprint, 4.65–5.20 | 0 / 0 | 69/85 / 11/85 | 74 / 11 / 0 |
| m7392 A, 0 ms | Two left-only steps and 55 ms hold, 3.70–4.10 | 0 / 0 | 0/85 / 50/85 | 72 / 8 / 5 |
| m7412 A, 0 ms | Release forward once, 3.70–4.10 | 61 / 0 | 1/70 / 50/70 | 59 / 11 / 0 |
| m7409 A, 20 ms | Release forward three times then sprint, 4.65–5.20 | 0 / 0 | 79/85 / 0/85 | 80 / 2 / 3 |
| m7413 B, 80 ms | Release forward three times then sprint, 4.65–5.20 | 0 / 0 | 73/85 / 33/85 | 77 / 4 / 4 |

The wide test crosses 17 whole-match input timetable variants (single-side
offsets through ±10 ms and simultaneous ±5 ms offsets) with 0/5/10 ms of
additional future attack upload on one side at a time. Every control uses
the same timing. Fifteen m7412 variants lack the anchor and remain out of
the paired scores. Across the 665 available variants, W-tap lands first
in 641 and shares the first drain in 24. Successful sequence branches
can let W-tap land first, then deny its return. They should not be described
as winning the opening hit.

In the nominal 20 ms m7378 A run, the backsteps give a 4–1 short score and
three unanswered fresh replies, vs W-tap’s 2–2 and one fresh reply before a
return. Both arms physically press 26/28 times. Incoming damage still
lands at 546.48 ms. The first outgoing hit moves from 599.84 to 745.27 ms.
Our first landing remains at 1007.05 ms. W-tap lands at 1240.73 ms in the
backstep arm (234 ms later than us), vs 979.25 ms in the W-tap mirror.
The backsteps move our reply far enough away to matter even though its
0.9495 horizontal and 0.361375 vertical launch stay unchanged.

The click-only comparison holds stock movement and varies a local contact
deadline from 100 to 600 ms in 10 ms steps. It preserves the rate cap and
allows noncontact attempts. Then, with hindsight, match the actual first
accepted drain for both attackers and select the best short margin among
the matching deadlines. All 133 available no-upload cases match; footwork
beats the best matched delay in 85, ties 30 and trails 18. On m7378 A,
all 17 footwork variants have a clean double. The matched time-only arms
never reproduce the double at the same margin. Nominally, the strongest
time-only arm matches the exact reply click, the full incoming and outgoing
launch magnitudes, and both first landing times. It presses 25/28 and goes
6–4. Its 0.442-block gap at the reply is 1.34 blocks narrower than the
backstep arm’s 1.783. W-tap’s return click at 1076.95 ms has a 2.251-block
ray with the delayed click and a 3.837-block ray with backsteps. W-tap
uses the same press at that boundary and misses in the backstep arm.
The landing lead creates a chance to score. In this branch, the approach
spacing is what protects the chance from a return.

Keep m7378 as development evidence for a spacing-dependent counter. Before
using a live playing rule, test whether own-view approach observations can
separate the favorable state from the many negative backstep branches.
Delayed clicks and earlier landing alone are insufficient labels for that
rule. These are model interventions on known native clocks. The native
first-to-100, separate maintenance and jump comparisons remain open.

[Model](BoxingEarlierApproach.java) ·
[Frozen protocol](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/protocol.json) ·
[Wide timing comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/wider-score.json) ·
[Matched click timing](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/contact-timing-score.json) ·
[m7378 packet and click trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/m7378-a-nominal-mechanism.json)

### Recognizing the approach from our client

Compare each fixed movement option with ordinary W-tap from the same
state immediately before the decision. The candidate never withholds
clicks. The tested options are three backsteps followed by sprint or
one release at 3.70–4.10 blocks to the visible hitbox, two releases at
4.10–4.65, and three releases followed by sprint at 4.65–5.20. The
grounded and quiet entry conditions stay in place. Allow up to four
approaches per band within the first 28.2 seconds, separated by 1.8 s
within each band. The 1.8 s score counts all accepted hits. Each arm
has exactly the same number of physical presses as W-tap for that
player over the paired window. Both actors use the 20 CPS cap.

Fit one two-level regression tree per band and movement option to
predict the paired point gain. Weight entire recording calendars
equally within the fit, require at least three calendars in each leaf,
and shrink predictions toward zero. Take the predicted best movement
only when it exceeds +0.25. Compare current own-client distance,
velocity and RTT with a version that includes the last four local
input snapshots and target position packets, click age and packet
timing. A separate offline check adds the defender's current view,
movement and current reported server separation. These are diagnostics
and supply no inputs to the own-view rules. No version uses donor,
seat, future event or outcome as an input.

The fit uses 12 earlier clocks. Test those with complete leave-one-
calendar-out folds. Use the same final trees on 12 previously exposed
clocks that contribute no examples and on 12 prospective clocks,
m7414–m7425. The fresh donors are native 35-second W-tap mirrors with
four matches per 0/20/80 ms RTT, with both seats and nine timing
variants in simulation. All 12 exported with the declared controls.
The trees were frozen before opening those clocks for the test.
Recordings supply timing for counterfactual simulation. The candidate
did not play those native bouts.

| Timing panel | Plain W-tap mean margin / clean doubles | Snapshot rule mean gain | Own-history rule mean gain / gains–ties–losses | Privileged snapshot + history mean gain | Always apply preset footwork mean gain | Best action with outcome known mean gain |
|---|---:|---:|---:|---:|---:|---:|
| Leave a full development calendar out, 726 cuts | +0.017 / 65 | −0.036 | −0.137 / 29–607–90 | −0.136 | −0.747 | +0.403 |
| Other exposed clocks, 856 cuts | −0.006 / 64 | +0.011 | −0.030 / 51–760–45 | −0.045 | −0.891 | +0.278 |
| Prospective native timing, 764 cuts | −0.008 / 13 | +0.006 | −0.002 / 43–677–44 | −0.012 | −0.706 | +0.281 |

Gain columns average per-calendar paired means. On the prospective
panel the own-history rule acts 178 times, ranges from −3 to +2
points per cut, and adds a double with points in two cuts. At
0/20/80 ms it averages 0.000/−0.043/+0.038. It improves two calendars,
ties seven, and loses three. The snapshot rule acts 40 times. Its
positive point estimate is small compared with the loss of the same
rule in full development-calendar folds. No tested rule clears a
promotion gate. The best action chosen after observing each outcome
is an upper benchmark for this action set. It is not a legal rule.

On the two exposed panels, also keep the exact pre-input world,
local histories and both queues fixed. Change only the defender's
unattempted input opportunities by ±5/10 ms, or add 5 ms to future
attack delivery on either side. Cross the choices for 15 equally
declared variants and give ordinary W-tap the same timing changes.
Nominal controls and all pre-input observations reproduce exactly.
The backstep gains in some variants and loses in others at **22 of
85** identical starts. It gives a new double and points in all 15
variants at one of the 85 starts. Across all four moves, nine of
174 states have a fixed movement that gains points at every tested
timing and four have one that also adds a clean double at every timing.
With exact knowledge of that ensemble, selecting a move expected to
gain without any tested regression raises mean gain to +0.189 and
+0.219 on the two panels, relative to W-tap. This diagnostic assigns
equal weight to the declared perturbations. It provides no estimate
of their live probabilities.

For m7407 A at 20 ms, the defender's attack delivery shift of +5 ms
leaves its first hit at 449.886 ms and our first reply at 552.019 ms.
With nominal delivery both later attacks land on the same drain at
1298.780 ms. With +5 ms the backstep arm gets the next fresh hit at
1204.489 ms and a third at 1757.128 ms without a return. Matched
W-tap goes 3–2 in both variants. Backsteps go 2–2 and 3–1. The first
two accepted timestamps alone therefore cannot screen out this
failure. An advance prediction must account for the width of the
possible follow-up window. A later decision could use the first
received hurt or velocity evidence before committing further pursuit.
This result rules out treating a positive label from one timing as
a robust approach label. It leaves other action sets and later
own-observation feedback open. First-to-100 and established-combo
comparisons still require a qualified candidate.

[Observation and fork code](BoxingApproachObservations.java) ·
[Frozen predictors](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/observation/frozen-trees.json) ·
[Native clock fixtures](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/observation/sealed-native.json) ·
[Prospective test results](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/observation/sealed-score.json) ·
[Fixed-state timing scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/observation/fixed-state-timing-score.json) ·
[m7407 hit and input trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/observation/m7407-fixed-state-trace.json)

### Pursuit after both hurt cues

Start from ordinary W-tap on both sides. Detect the first ready own input
where the client has seen both hurt cues, within 300 ms of one another
after at least 1.1 s without a hurt cue. Compare immediate movement to
starting 200 or 325 ms later if no newer local hurt cue interrupts the
exchange. Compare each exact state and queued input history against
plain W-tap for 1.35 s. Keep the same physical presses on both sides.
A successful follow-up scores a fresh hit within 850 ms of the cut,
before any accepted return, including an upgrade. This is a post-cut
measure. A shared opening drain or intervening hit before a later cut
does not establish an unanswered streak from the first reply.

Each cut tests 26 one-to-three-tick words using ordinary movement.
The three 12-calendar panels have already been exposed. Only the first
panel selects the fixed intervention. There are 446, 456 and 451
immediate starts, and 445, 456 and 447 at 325 ms. The best choice made
after seeing each outcome would improve 161, 176 and 141 immediate
starts, versus 75, 96 and 82 at 325 ms. These are conditional ceilings
for the tested states at each stage. The 325 ms groups keep only states
where another local hurt cue has not interrupted the exchange.

| Immediate screen | Plain W-tap clean follow-ups | Two sprint ticks in place of stock movement, mean paired gain overall | Two sprint ticks at 80 ms, seat A / B |
|---|---:|---:|---:|
| Development | 40 / 446 | +0.113 | +0.507 / +0.154 |
| Separate comparison | 41 / 456 | +0.085 | +0.332 / +0.264 |
| Latest previously exposed | 42 / 451 | +0.090 | +0.375 / +0.301 |

The short score gains use the same-cadence control. Both 80 ms seats
gain in all three panels. The lowest cell at 0 or 20 ms in each panel
is −0.231, −0.125 and −0.050. Only one donor/seat/episode in the
short screen has the same word add a clean follow-up and points in
every one of the five phases. The immediate opportunity is stronger
than waiting for around the next fresh-damage window with this action
set. Across the three panels, the same two sprint ticks at 200 ms
average zero paired points each.

The fixed regulation variant applies two sprint ticks whenever the
bilateral cue arrives at 80 ms and otherwise uses W-tap. The 240
lower-RTT controls reproduce W-tap exactly. At 80 ms all 120 pairs
finish at 100 hits and match every physical press through their common
horizon. The actual median rates in these comparisons are 17.50–17.56
CPS. A separate run applies only the first pulse, under the same clock.

| 80 ms regulation, 40 pairs each | Plain W-tap wins / raw median margin | Repeated sprint wins / raw median margin | Repeated mean / median paired gain | First-only mean / median paired gain |
|---|---:|---:|---:|---:|
| Development | 20 / 0 | 27 / +23 | +17.83 / +13.5 | +13.20 / +4.5 |
| Separate comparison | 20 / 0 | 23 / +3.5 | +2.73 / −1 | +9.45 / 0 |
| Latest previously exposed | 20 / 0 | 23 / +2.5 | −1.73 / +2 | +0.25 / +2.5 |

Seat B's repeated paired median is +13, −14.5 and −2. First-only
ends up with a −0.9 mean in the latest seat B cell. Nine of the 13
newer-panel pairs with a positive first 1.35 s window are worse at
first-to-100 with repetition. Seven are worse with the first pulse
only. This rules out harmful repeat activation as the entire reason
for the regression. Paired regulation spans −131 to +118 over the
three panels. Both arms finish inside the original recording period
in 20 and 23 pairs in the older panels, with paired medians 0 and −2.
The 35-second donor clocks all repeat before regulation ends. These
are common model scheduling comparisons. The inside-period subset
is selected by both completion times and is descriptive. No result
establishes a repeatable native score advantage.

| Maintenance fork at 80 ms | Common starts | W-tap cycles / exposure seconds / censored | Sprint cycles / exposure seconds / censored |
|---|---:|---:|---:|
| K3 | 99 | 1525 / 810.2 / 37 | 1525 / 810.2 / 37 |
| K5 | 77 | 1418 / 745.4 / 41 | 1418 / 745.4 / 41 |
| K10 | 58 | 1098 / 575.7 / 40 | 1098 / 575.7 / 40 |

Each duration and endpoint ties W-tap from the same complete start.
The return-only and inactivity-qualified scores agree in all these
forks. This score does not show a maintenance improvement. No jump
or supplementary movement rule is in this comparison.

The earlier m7407 A case at 20 ms isolates one reason a single pulse
helps in one trade and hurts another. Adding 5 ms to W-tap's upload
preserves both first attacks and their server acceptance times. It
lets the first incoming attack read 3.168 blocks of server separation
instead of 3.032 blocks. The lab's distance-dependent sprint knockback
is 0.89350 instead of 0.93875 horizontally, with the same 0.361375
vertical. The client has handled that impulse by 464.514 ms, before
its outgoing reply at 552.019 ms. At the first head with both cues
(602.089 ms), the larger recoil branch is 3.176 blocks from the
visible box and the weaker branch 3.135 blocks.

| m7407 A timing and first incoming S12 | Ordinary W-tap from original approach | Backstep approach, stock continuation | Backstep approach, one sprint tick after both cues |
|---|---:|---:|---:|
| Nominal attack delivery, original 0.93875 | 3–2 | 2–2 | 3–1 |
| Nominal delivery, substitute 0.89350 | — | 3–1 | 2–2 |
| Defender upload +5 ms, original 0.89350 | 3–2 | 3–1 | 2–2 |
| Defender upload +5 ms, substitute 0.93875 | — | 2–2 | 3–1 |

The replacement changes only the new S12 horizontal components on
the model client. The unmodified arms reproduce the previous full
hit ledger exactly. Swapping the received impulse alone swaps the
entire accepted-hit sequence for the stock continuation. The pulse
benefit also reverses. At 200 or 325 ms the tested sequences cannot
recover the nominal loss. The received momentum and first available
pursuit input matter in this witness. Continuing to sprint blindly
on the same cues spends the spacing margin of the weaker branch.
Use the incoming horizontal impulse and projected follow-up reach
to explain when the pulse is needed before considering another live
controller. The synthetic rows diagnose the backstep branches and
have no whole-approach W-tap counterfactual with edited velocity.

[Paired cue experiment](BoxingTradeFollowup.java) ·
[Regulation and maintenance model](BoxingTradeFollowupRegulation.java) ·
[Individual 80 ms and lower-latency comparisons](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/regulation-v2/newer_exposed.json) ·
[First-pulse-only comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/repeat-check/newer_exposed.json) ·
[m7407 velocity and pursuit forks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/m7407-causal.json)

### Forecasting reach and the arrival of the next attack

Use the same 1,353 immediate bilateral-cue starts as the short pursuit
comparison, across the three exposed panels. From each exact cut replay
plain W-tap, one immediate sprint tick, and two immediate sprint ticks.
All 4,059 arms reproduce the previously recorded points, clean follow-up
status and physical mouse presses. For each side, record the range to
the visible hitbox at its original first fresh server drain. Also record
selected local attacks emitted after the cut whose packets are ready for
either of that side's first two fresh drains. A distance sample at the
drain is one geometric measurement. Attacks can be selected before it,
or at another client input for the second drain.

The own-view forecast starts with current own position and velocity,
the last five received position reports and both local hurt cues.
It uses the existing flat-floor travel mechanics. Estimate the target's
velocity from the position reports, with alternative outgoing impulses
when the reports still show a recent grounded target. Estimate view lag,
local sprint contact braking and a 10-tick damage window. Use the
configured symmetric RTT for that timing estimate. The nominal path
brakes if the estimated hitbox is reachable on a movement step. Test
that and no braking as alternatives, with ±25 ms deadline changes,
nearby view lags and one or two stock W-release ticks. These are model
hypotheses rather than confidence intervals. No future stream or
opponent-private state enters the own-view forecast.

| Timing panel, plain W-tap arms | Own / defender window-time MAE | Own / defender nominal range MAE | Range MAE with actual current bodies and replicas | Range MAE with actual bodies, replicas and future contact brakes |
|---|---:|---:|---:|---:|
| Development, 446 | 16.70 / 19.03 ms | 1.67 / 1.84 blocks | 1.15 / 1.19 | 0.87 / 0.96 |
| Separate comparison, 456 | 15.47 / 15.05 ms | 1.54 / 1.64 blocks | 0.95 / 0.99 | 0.62 / 0.66 |
| Latest previously exposed, 451 | 15.00 / 16.36 ms | 1.63 / 1.75 blocks | 1.36 / 1.43 | 0.92 / 1.00 |

MAE is mean absolute error. Both columns with actual bodies use the
true damage window and are offline diagnostics. Giving the own-view
forecast the true window alone changes either range error by less than
0.03 blocks. The nominal forecast underestimates range by 1.47–1.67
blocks on average. Suppressing all predicted contact brakes shifts the
estimate to an overestimate of 0.97–1.05 blocks. With the exact starting
bodies, giving the forecast the realized future sprint-contact brakes
reduces MAE a further 0.24–0.44 blocks. This diagnostic uses the actual
movement-step index of each brake, with a nominal 50 ms rollout and
approximate remaining movement and replication. It still leaves error
comparable to a meaningful fraction of the 3-block reach threshold.

The finite hypothesis ranges cover 69–79% of the realized distances with
median widths of 3.77–4.08 blocks. The nominal model classifies being
within 3 blocks correctly for 63–69% of these player-and-role samples.
The stock arms are outside reach at the measured window in 87–92%, so
a simple outside-reach default has better accuracy on this imbalanced
sample. A useful forecast also needs to identify the rarer in-range
outcomes. A rule that sprints only if stock would miss, our sprint
brings us in range, and W-tap still cannot return activates once across
the 1,353 starts with the nominal projection and zero times with the
hypothesis ranges. The one activation ties W-tap on points.

| Timing panel | Plain W-tap clean follow-ups | Exact later range gate, uses / paired gain | First-two-drain contact diagnostic, uses / paired gain | Always sprint twice, paired gain |
|---|---:|---:|---:|---:|
| Development | 40 / 446 | 22 / +0.054 | 26 / +0.080 | +0.113 |
| Separate comparison | 41 / 456 | 25 / +0.011 | 44 / +0.111 | +0.085 |
| Latest previously exposed | 42 / 451 | 19 / +0.033 | 39 / +0.110 | +0.090 |

In the exact range column, use the same sprint rule with the actual
range at each side's first server window. The contact diagnostic uses
future knowledge of both clients' selected attacks and ready times. It
uses the shortest pulse that adds an own packet without increasing the
defender's count for its first two drains. The score is the same 1.35 s
accepted margin against matched W-tap. Panel means weight calendars
equally. Relative to stock, always sprinting twice adds an own early
packet without adding a defender early packet in 105 of 1,353 forks.
Those forks improve in 75, tie in 25, and regress in five. Both packet
counts are outcomes of the actions. This association describes where
the sprint gains came from in these forks. It gives no online trigger.

The large-recoil, already-outside-reach condition illustrated by m7407
appears in just two of these stock starts at the first bilateral cue
(visible range >3 and incoming horizontal component ≥0.915). That case
is evidence about a spaced reply. Use the first eligible physical
contact and its arrival window as the next forecast target. Compare
short options with plain W-tap without changing actual cadence or
defining the damage window from the chosen click. The current projection
does not support a live pursuit rule. Native regulation remains open.

[Projection code](BoxingFollowupProjection.java) ·
[Paired reach and attack audit](BoxingFollowupReach.java) ·
[Development results and calibration](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/reach-forecast-v4/development-score.json) ·
[Separate comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/reach-forecast-v4/comparison-score.json) ·
[Latest exposed comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/reach-forecast-v4/newer_exposed-score.json)

### Judging a click near the first fresh window

Link actual presses, native consumption, local attack selection, wire
arrival and server processing in 24 W-tap mirrors. The press cohort
uses a recent replica report and the last received target hurt status,
200–650 ms before the physical press. The status is linked offline
to the damage tick that produced it. Its next native window starts
ten ticks later. A press can miss locally, produce a packet outside
the first two eligible drains, or reach either drain and fail to
score because another attack already dealt fresh damage.

Fit the estimated send phase and median processing delays on the
first 12 matches, with independent calibration for 0, 20 and 80 ms
added symmetrical RTT. Forecast at each factual press from the
cached crosshair, the local input head, and the receipt times of the
target hurt status and latest visible position report.

| Native W-tap cohort | Physical presses | Attack packets | First two drains | Correctly predicted timely packets | Fresh hits from those drains |
|---|---:|---:|---:|---:|---:|
| Calibration | 11,877 | 2,839 | 810 | 750 | 504 |
| Later, previously exposed | 5,371 | 1,260 | 363 | 320 | 227 |

On the later cohort, cached target selection matches all 1,260
emissions and 4,111 non-emissions. The local timing forecast makes
43 false timely calls and misses 43. The first deadline estimate
has 3.97 ms mean absolute error. Using the receipt of the hurt
status without replica phase correction gives 4.31 ms and catches
325 timely packets with 48 false calls. Supplying actual local
consumption time to the phase forecast catches 338 with 23 false
calls. These results describe an already chosen press.

All 136 timely contacts without fresh damage in the later cohort
follow an earlier fresh hit since the reference hurt cue. Of these,
131 are immune and five score upgrades. Calling all predicted timely
contacts fresh finds 197 of 227, with 166 false calls. Remembering
a previously predicted contact at least 6 ms inside the window
finds 187 with 73 false calls. Fresh-hit precision rises from 54%
to 72% and recall falls from 87% to 82%. This audit keeps all
physical clicks in place.

At the earlier bilateral cue, shallow action-conditioned trees use
the same lawful movement and reach features to predict early packets
for W-tap or a sprint pulse. The trees distinguish some individual
contacts. Across the two previously exposed comparison panels, the
predicted *change* from sprinting has AUROC 0.50 for adding an own
early packet without adding a defender packet. The threshold sweeps
mostly select every pulse or none. Always sprinting scores +0.113,
+0.085 and +0.090 short margin relative to plain W-tap in the
three panels, with worst seat/RTT cells −0.227, −0.125 and −0.050.
The late packet forecast and the earlier action choice answer
different questions. This result leaves the pursuit rule unchanged.

[Factual native audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/contact-timing/native-later.json) ·
[Native calibration and split](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/contact-timing/native-protocol-v3.json) ·
[Paired pursuit threshold sweep](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/contact-timing/model-sensitivity.json)

### Deferring the click immediately before fresh damage

Fork the same 1,353 bilateral-cue starts with plain W-tap as the
control. Consider one deferred physical press per fork. At a capped
poll that selects the opponent, the client forecast can predict that
the attack would reach the server on the last drain before fresh
damage. If so, wait until the next client tick and return to ordinary
20 CPS pacing. The guarded version needs a 6 ms margin at both ends
of the predicted interval and preserves the sprint contact while
moving away faster than 0.05 blocks per tick. In a timing diagnostic,
use the exact first contact that would reach that drain in the stock
branch. The diagnostic uses future information and shares the same
one-tick hold. Neither the estimated nor the exact interval moves the
damage window. Movement, sprint reset logic, aim and jump are unchanged.

| Timing panel | Plain W-tap unanswered follow-ups | Forecast hold, activations / mean margin change | Guarded hold, activations / mean margin change | Exact timing diagnostic, activations / mean margin change |
|---|---:|---:|---:|---:|
| Development, 446 | 40 | 54 / −0.027 | 35 / −0.025 | 49 / −0.026 |
| Comparison, 456 | 41 | 53 / −0.016 | 38 / −0.006 | 51 / −0.011 |
| Latest exposed, 451 | 42 | 29 / −0.002 | 15 / −0.006 | 21 / −0.006 |

Per-calendar means use the 1.35 s accepted margin including upgrades.
All three alternatives add zero unanswered follow-ups and lose the
same two. The exact timing arm improves five forks, ties 1,325 and
regresses in 23. Actual physical press counts can differ as the
deferral rephases the cap. The unguarded arm omits a net 95 own presses
over 136 activations. Where both sides' physical counts match, it
improves two, ties 30 and loses nine across its 41 activations. The
exact timing arm is +2 / 30 ties / −7 in its 39 activated forks with
equal counts. These descriptive subsets depend on both arms' counts.

The unmodified baseline first drains within the fresh window already
have an attack packet in 111 of the 121 exact-timing activations.
Moving the preceding contact adds an early-window packet to just one
of the other ten starts. Its final short margin falls by two. Among
the 23 exact-timing regressions, 19 lose W-tap's accepted damage upgrade
on the last pre-fresh drain. Twenty have equal fresh-hit margin and
all 23 receive their first return at the same time. The five cases
with better accepted margin all have the original first fresh-hit time.
In m7407 B in the +5 ms own-clock branch, stock earns an upgrade at
316.21 ms after the cut and a fresh hit at 415.25 ms. The deferral
preserves that fresh hit and loses the upgrade. Later hits change
timing, with both arms ending on the same fresh margin.

The factual native audit shows the same scoring opportunity. Restrict
it to W-tap hurt-cue windows with at least one contact handled on the
last pre-fresh drain and no newer fresh hit between it and the cue.
There are 401 such windows in the calibration mirrors and 167 in the
later mirrors. W-tap retries and gets a fresh hit in the next two drains
in 378 and 155 of those windows. It gets an upgrade on the earlier
drain in 35 and 18 windows, of which 30 and 16 also score in the next
two drains. The native matches keep their factual input sequences.

Ordinary W-tap often collects both scoring opportunities using
successive presses. A fresh-time forecast alone values only one of
them. Preserve the upgrades and contact brakes when measuring any
later alternative. The after-trade hold fails the score gate, including
with exact timing. No native controller is promoted.

[Model controller and matched forks](BoxingFollowupClick.java) ·
[Frozen comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/click-admission/protocol.json) ·
[Model traces and causes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/click-admission/forensics/comparison-causes.json) ·
[Native late-mirror upgrade counts](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/followup/click-admission/forensics/native-early-upgrades-later.json)

### Testing the best approach examples against an early recoil brake

Reproduce the eight selected earlier approach examples across their
original client offsets and attack upload delays. All 665 available
starts reproduce their original approach cuts and short outcomes.
Fifteen remain without an eligible approach. Trace the first step after
the defender receives our first fresh launch. Then replay each exact cut
with the defender starting that step at either 60% or 100% of the
received horizontal impulse. Compare the movement sequence against plain
W-tap and the original timing controls, giving each the same recoil
treatment on its own first launch. Attack order before the step,
contact selection, sprint state, packet queues, vertical velocity and
physical inputs up to the intervention all stay intact. The 60% arm
isolates the movement effect of one early sprint contact.

| First recoil step | Plain W-tap doubles / three-hit chains | New movement doubles / three-hit chains with score gain | Movement score gains / ties / losses vs W-tap |
|---|---:|---:|---:|
| Natural recoil | 61 / 0 | 379 / 275 | 585 / 68 / 12 |
| At 60% of the received impulse | 0 / 0 | 60 / 0 | 338 / 230 / 97 |
| At 100% of the received impulse | 61 / 0 | 365 / 292 | 566 / 89 / 10 |

All selected variants resolve the first travel step in both arms.
The selected controls sometimes use a guarded wait, which can rephase
presses under the shared 20 CPS cap. On the 585 variants with identical
physical counts on both sides, the movement adds 379 doubles with
natural recoil and 59 with the brake. The m7378 A backsteps yielded
85 new doubles and 74 new three-hit chains in the natural arm.
Giving W-tap the early brake leaves one double and zero chains.
At 80 ms, m7389 A keeps 24 doubles across 85 variants with the brake.
M7413 B goes from 73 new doubles and 33 chains to zero of either.
No selected case produces an all-phase double or chain with the brake.

The nominal 20 ms m7378 A example keeps its reply drain and
0.9495-block/tick launch. Braking the defender reduces its first
backward movement from 0.8221 to 0.4423 blocks. A later W-tap
click at 778.06 ms after the cut now raycasts at 2.843 blocks
and scores at 802.49 ms. Without the brake its nearest legal
hitbox distance at that click is 3.829 blocks. The movement
arm's 4–1 becomes 4–3; its matched W-tap comparator goes 2–2 to
2–3. The short score gain can survive the loss of the combo.

In m7389 A with the early brake, one W-tap return ray is 2.957
blocks at 820.59 ms. The return shares our follow-up drain at
862.17 ms. Adding only 5 ms to future defender attack transport
keeps that ray and our hit time. It puts the same return one
server drain later, at 920.64 ms, and makes a strict double
under the existing definition. The trade still has the same 3–2
short score in both cases. That double depends on packet order
through a still-reachable defender.

Test the same three representative input words on the larger
pre-decision cohort. The close band uses three backsteps, the
middle band releases twice, and the far band releases three
times then pursues. Test each word at its original range band
against the same W-tap continuation, with all three earlier
12-calendar panels and five offsets on each. The 940 available
cuts include 801 where both first recoil steps resolve. Keep
all cases in the overall estimates. All 14 braked strict new
doubles have both steps resolved. All 940 braked comparisons
have identical physical press counts on each side.

| Broader panel | Plain W-tap doubles, natural / braked | Movement new doubles, natural / braked | Movement mean accepted margin change, natural / braked |
|---|---:|---:|---:|
| Development, 312 | 31 / 19 | 25 / 6 | −0.551 / −0.679 |
| Comparison, 317 | 28 / 0 | 22 / 6 | −0.596 / −0.678 |
| Latest exposed, 311 | 0 / 5 | 7 / 2 | −0.476 / −0.598 |

Four of 175 complete donor/seat/range groups produce a strict
new double with positive margin at every natural timing.
With the brake, none do. This extends the diagnosis from the
selected examples to the enrolled approach population. A usable
recognizer needs to distinguish a genuine reach gap from a return
that happens to fall on the other side of a server drain.

| Factual native W-tap mirrors, 24 | Full first recoil step, trades / clean follow-ups | Braked first recoil step, trades / clean follow-ups |
|---|---:|---:|
| 0 ms | 139 / 17 | 9 / 0 |
| 20 ms | 140 / 19 | 5 / 0 |
| 80 ms | 41 / 3 | 2 / 0 |
| All | 320 / 39 | 16 / 0 |

The native audit enrolls the first fresh hit after 1.1 s without
one, then requires the other side's fresh reply within 275 ms
and 1.35 s of later coverage. Both sides' S12s and first travel
steps are linked through the original packet payload and local
event order. The table describes the player who hits second,
including 161 same-drain pairs. Their follow-up must score within
850 ms with no accepted return after their initial hit through
the follow-up drain. Full and braked steps have median horizontal
travel of 0.9495 and 0.4423 blocks. The 16 natural brakes make a
small observational group. The simulator's symmetric interventions
provide the controlled comparison. This study advances the approach
diagnosis. The fixed motion tests fail the score gate.

[Frozen recoil experiment](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/protocol.json) ·
[Selected examples](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/witness-summary.json) ·
[Broader approach comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/broad-summary.json) ·
[Return click traces](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/witness-nominal-traces.json) ·
[Native W-tap steps](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/native-quiet-trades.json)

### Checking whether a clean double denied the return

Trace all 60 selected and 14 broader movement doubles that gained
score under the 60% first-step brake. Apply the same packet test to
their exact W-tap comparators and to the 24 broader stock W-tap
doubles. There are 97 distinct starts. The original arm summaries
all reproduce. After the first defender recoil step, let each
new defender attack packet arrive up to 2, 5, 10 or 20 ms earlier.
Keep send time as the earliest possible arrival, preserve packet
order on the route, and keep the timing of every movement, sprint,
and outbound server packet. Allow subsequent ordinary controls
and hit outcomes to respond to the changed attack delivery.

| Maximum attack advance | Plain W-tap doubles kept, of 24 | Selected movement doubles kept, of 60 | Broader movement doubles kept, of 14 | Selected movement margin gain vs paired W-tap |
|---:|---:|---:|---:|---:|
| 0 ms | 24 | 60 | 14 | +1.03 |
| 2 ms | 24 | 55 | 14 | +0.98 |
| 5 ms | 24 | 44 | 14 | +0.50 |
| 10 ms | 24 | 36 | 14 | +0.13 |
| 20 ms | 24 | 34 | 14 | +0.02 |

Through the original reply drain, the accepted hit ledger is
identical in every accelerated branch. Every lost double has a
return accepted by the original follow-up drain. In all 26
affected starts, the same W-tap attack packet had already been
sent on the original trajectory. Its local ray and click time
are identical in the accelerated branch. The original packet
missed the follow-up drain's boundary by 0.73–17.75 ms, with a
3.69 ms median. Speeding up the packet eliminates all 24 surviving
m7389 A doubles at 80 ms by the 20 ms stress tier. The movement
arm's mean score margin across the selected set is
unchanged between 0 and 20 ms. The paired margin shrinks because
the W-tap control's subsequent scoring changes under the same test.
At 20 ms the selected movement margins range from −1 to +1, with
27 gains, seven ties and 26 losses.

The reach audit uses the original client view at each **consumed
press**. It selects presses whose real attack, or a shadow attack
on the same copied timing route for a miss, would be eligible for
a fresh drain through our follow-up. All 60 selected movement
doubles have nearest hitbox distance above three blocks at every
sampled press in that original window. In 26 of them, a different
press enters reach before the follow-up drain and its packet arrives
afterward. The geometry and packet labels answer different questions.
In the broader 14, ten have all sampled presses outside reach,
three have a close enough hitbox with no selected ray, and one
has timely C02s without an accepted return. None of those 14 emits
a later return before the follow-up drain, and all keep the double
through the 20 ms test. One still loses on short score relative to
W-tap. These are selected cases on exposed calendars.

The m7378 B backstep gives 67 doubles in 85 timing variants with
the early brake. All 67 keep the double when W-tap's attacks arrive
up to 20 ms earlier. The original movement arm leads +2 at the
second hit, including a damage upgrade, and +1 by the 1.8 s
cutoff in every one of the 67. What separates them is the matched
W-tap score. It ends even in the 34 gain cases, and finishes
with a margin of +1 or +2 in the other 33. There are 24 losses and nine ties
in that latter group. The movement's double and reach denial are
reliable over those 67 observed timings, while its advantage over
W-tap depends on the control's later scoring.

For the 39 factual native doubles in the previous quiet-trade
audit, pair the outgoing and incoming C02 payloads and join the
first subsequent accepted return by packet ID. Thirty-five returns
leave the defender only after the follow-up drain has begun.
Two go out before and reach the server just 1.48 and 4.10 ms after
that drain begins. Both occur at 20 ms RTT and score on the next
server tick. The remaining two have no later recorded return.
All 39 have at least one observed defender press between the last
pre-fresh drain and the follow-up drain. In 37, all such samples
are outside the three-block hitbox envelope. The native count is
a factual wire observation. Earlier acceptance of either in-flight
packet in Minecraft remains a counterfactual.

These results give two distinct development checks. Inspect the
defender's actual click and advance margin before calling the
double a spacing win. Measure score against W-tap several fresh
windows after that win, including damage upgrades in both arms.
The 80 ms selected route depends on a narrow packet race and the
broader movement rules still lose the score comparison. No
candidate passes through to native regulation.

[Frozen model packet test](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/protocol.json) ·
[Paired return and reach scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/summary.json) ·
[m7378 B accepted-hit and upgrade attribution](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/m7378-b-margin.json) ·
[Native return packets](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/native-returns.json)

### When movement can protect a double

Extend all 67 m7378 B doubles, 14 broader movement doubles, and
24 stock W-tap doubles from the earlier one-step recoil stress.
These are 104 distinct exact starts and one shared membership.
Reproduce each 1.8 s result and score through 3 s. Preserve the
opening and click policy. Test one to three ordinary movement
inputs on the first own input head at cue age 250, 350 or 450 ms,
or on the first own head after the second outgoing hurt cue.
The timed branches remain idle if the second status is already
visible. Use matched W-tap and the unmodified opening as controls.

| Model cohort | Plain W-tap in the original match pairing | First return clicks already consumed at our second-cue head | First return censored at 3 s |
|---|---|---:|---:|
| m7378 B backstep doubles | Matched W-tap on the same 67 starts | 67 / 67 linked | 0 |
| Broader movement doubles | Matched W-tap on the same 14 starts | 7 / 11 linked | 3 |
| Plain W-tap doubles | Stock on all 24 starts | 15 / 21 linked | 3 |

In m7378 B the first return is accepted 52.070 ms after the
second hit. Its click is consumed 3.42–23.42 ms before the first
own input with the second target status visible. None of the 16 words
started from that cue delays the return while retaining both hit
times. The best post-cue
word is one diagonal sprint step. Its first return is identical
in all 67. It improves the 3 s score over the unchanged backstep
in 14, ties 49 and loses four. It averages +0.61 relative to the
original continuation and +0.58 against matched W-tap. The gain
occurs after the immediate return. Earlier tested words also
recover zero first returns while preserving both hit drains in
this set. The best fixed word in the broader movement doubles
changes the 3 s margin by +0.07 relative to their original
continuation, with four gains, six ties and four losses.

In the factual native cohort, pair each second hit's outgoing
target status with its client receipt and main-thread handler
return using the wire packet ID. Locate the first own native
input head afterward. The 39 native W-tap doubles give 34
matched cue heads and 32 cases with both a cue head and the
first accepted return's original outbound packet. In 20 of
the 32, that return has already left the defender at the first
own head. Across the 34 linked cue heads the median head delay
from the follow-up server drain is 36.39 ms. Keep the five
unlinked heads and the returns without a linked pair visible.
The time from the cue head to the factual send is an observed
deadline. A later send can still leave too little time for
movement, server tracking, transport and interpolation.

### Delaying the second hit through spacing

One of the earlier W-tap doubles behaves differently if the
bot releases forward for two input ticks at a first-cue age
of 450 ms. Nine gains in the selected stock-W-tap panel are
three range checkpoints on the same m7385 A continuation in
each of three timing variants. Evaluate only the nearest
checkpoint on that donor in the widened test. Stress the same
release against W-tap under 17 client offset pairs and five
attack-transport settings. Run both natural recoil and the
same one-step 60% defender recoil used in the development
diagnostic. Physical press counts match in every pair.

| m7385 A, 0 ms, 85 variants per row | Plain W-tap doubles / three-hit chains | Release doubles / three-hit chains | Paired 3 s gains / ties / losses | Mean paired margin |
|---|---:|---:|---:|---:|
| Natural first step | 6 / 0 | 6 / 0 | 16 / 59 / 10 | +0.24 |
| First step at 60% of impulse | 44 / 0 | 44 / 44 | 50 / 20 / 15 | +1.66 |

In the nominal braked fork the first hit order and first launch
are unchanged. Stock selects a 2.908-block ray at 1190.333 ms
and its second hit scores at 1207.197 ms. Releasing forward
opens that ray to 3.449 blocks. It also moves W-tap's upcoming
return ray from 2.617 to 3.030 blocks at 1220.234 ms, denying
the return that originally scores at 1257.198 ms. The release
arm gets its second hit on that same 1257.198 ms drain from a
later 2.951-block ray. It maintains five unanswered fresh hits
through the 3 s horizon. This shows a spacing-mediated delay
with the same click cap and physical press counts.

Natural recoil gives the same nominal launch and a different
position when the re-approach begins. At the first release
step the original own visible hitbox distance is 5.841 blocks
on the natural branch and 4.488 on the braked branch. In the
natural replay both actors' next fresh hits shift one tick
later and W-tap still returns, at 1507.137 ms. The natural
release does improve the nominal 2–5 score to 3–3, while its
median matched result across phases is zero. The experimental
five-hit chain relies on the braked starting state in this
donor. The grounded approach still needs a repeatable way to
create and detect that spacing with ordinary inputs before
entering native first-to-100. Maintenance and jump remain
separate evaluations.

[Exact continuation probe](BoxingAfterDouble.java) ·
[Frozen short-continuation fixtures and scores](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/summary.json) ·
[Native cue-to-return timing](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/native-cue-timing-v2.json) ·
[Natural and braked release scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/m7385-wider.json)

### Testing the re-entry release on naturally occurring trades

Enroll ordinary W-tap mirrors from all 36 previously exposed
calendars. After 1.1 seconds of quiet, the subject sees both
hurt cues within 300 ms. Fork the first own input head at an
outgoing cue age of at least 450 ms while still waiting for
another fresh cue. Consider the first four detected bilateral episodes
per matchup and leave all earlier physics, stock recoil, clocks,
and queues intact. Compare a two-tick forward release to plain
W-tap on the same state. All 1,263 pairs use identical physical
press timestamps on both clients. Report accepted score at 2.2 s
and the next clean fresh hit, with the 850 ms and shared-drain
criteria in the frozen protocol.

| Timing panel | Plain W-tap clean follow-ups / three-hit chains | Release clean follow-ups / three-hit chains | Paired score gains / ties / losses | Paired mean margin |
|---|---:|---:|---:|---:|
| Development, 419 | 74 / 6 | 41 / 1 | 69 / 287 / 63 | −0.050 |
| Comparison, 414 | 83 / 2 | 51 / 1 | 64 / 282 / 68 | −0.106 |
| Latest exposed, 430 | 73 / 4 | 40 / 3 | 64 / 301 / 65 | −0.037 |

The same release loses average margin on each panel's observed
first-step brake subset. The means are −0.131, −0.528 and −0.137
on 107, 106 and 102 starts. No complete five-phase clock/seat
group adds a clean follow-up and points in every variant.

Pair each first clean hit that changes with the C02 that scored
in the stock state. In all 145 losses of a clean follow-up, the
bot's **same consumed press** is beyond reach after the release.
Median nearest hitbox distance changes from 2.858 to 3.215
blocks. W-tap still scores its first return on the original drain
in 117 of the 145 losses. The release creates 47 different clean
follow-ups. In 45, the defender's stock scoring press now misses
because the hitbox is outside three blocks. Both remaining links
are unresolved. Ordinary W-tap preserves contact better in the
enrolled population. Retreat only improves that contact race in
a smaller set where it costs the opponent a usable shot.

The predeclared local m7385 band requires sprinting on the floor,
4.3–4.9 blocks to the visible hitbox and a height offset under 0.8
blocks. It occurs in 34 forks and yields nine score gains and
25 ties. The 34 contain one clean hit with W-tap and zero with the
release, and no three-hit chains in either arm. In the 24 archived
native W-tap mirrors, 173 heads meet the cue enrollment. A unique
same-wall-time own-view observation joins to 139 of those heads.
Just four of the 139 match the local band. The other 34 heads
retain unknown geometry. These are factual native frequencies,
with movement counterfactuals measured in the simulator.

### How much first-step recoil the delay needs

Repeat the widened m7385 A phase panel while changing only the
defender's horizontal velocity immediately before its first
movement. Both candidate and plain W-tap receive the same
synthetic treatment, with the original first damage, vertical
velocity, sprint flags and local contact queues intact. The
original sprint launch is 0.9495 blocks per tick. A horizontal
velocity of 0.5275 equals the nominal normal-hit magnitude on
this preset. It is a travel substitution and leaves the other
consequences of a real normal hit untested.

| Effective horizontal velocity, 85 timings per row | Plain W-tap doubles / three-hit chains | Release doubles / three-hit chains | Release mean score gain |
|---:|---:|---:|---:|
| 0.47475 | 0 / 0 | 0 / 0 | −0.46 |
| 0.52750 | 8 / 0 | 0 / 0 | −0.08 |
| 0.56970 | 44 / 0 | 44 / 44 | +1.66 |
| 0.61718 | 8 / 4 | 0 / 0 | +0.06 |
| 0.66465 | 57 / 44 | 0 / 0 | −1.98 |
| 0.75960 | 0 / 0 | 0 / 0 | −0.08 |

The 44 chains with the release at 0.56970 and the 44 chains
with stock W-tap at 0.66465 occur in exactly the same timing
variants. They share their first two accepted hit times,
any first return time through the horizon, and the final score.
In the nominal 0.52750 replay the forward release delays both
players into the same server drain. The defender still selects
a 2.815-block ray at the original 1220.234 ms press. At 0.56970
with the release that ray is outside reach. At 0.66465, stock
W-tap already finds the favorable geometry and the extra release
pushes the re-entry into a different mutual hit. The gain depends
on where the bot puts both clients at the next press, including
the narrow separation between those presses and their drains.
The fixed release and normal-hit magnitude both leave native
first-to-100 qualification open.

[Natural re-entry probe](BoxingNaturalReentry.java) ·
[Later model scorecard with seat and RTT cells](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/newer_exposed-score.json) ·
[Matched press and first-return attribution](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/change-causes.json) ·
[Native cue frequency](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/native-enrollment.json) ·
[Strength sweep scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/effective-strength.json)

### A one-tick re-entry release

The same 1,263 natural-recoil cuts reproduce every stock and
two-tick result, including the scored C02s and physical timings.
From each cut, add a release on just its first input head, or
just its second. Score 2.2 s of ordinary closed-loop play on the
original clock and two counterfactual futures. Add 5 ms to new
own attack uploads or to new defender attack uploads, keeping
old queues and position transport. Each future has its own
identical-state W-tap control and unchanged physical press times.

| Panel | Plain W-tap clean follow-ups | First-tick release clean follow-ups / mean margin change | Second-tick release clean follow-ups / mean margin change | Two-tick release clean follow-ups / mean margin change |
|---|---:|---:|---:|---:|
| Development, 419 | 74 | 60 / −0.079 | 54 / −0.033 | 41 / −0.050 |
| Comparison, 414 | 83 | 66 / −0.179 | 69 / −0.075 | 51 / −0.106 |
| Latest exposed, 430 | 73 | 54 / +0.053 | 49 / −0.030 | 40 / −0.037 |

The best word chosen with the realized outcome averages +0.344,
+0.300 and +0.349 points. A separate oracle picks the word with
the best minimum paired margin over all three futures, including
W-tap. Its mean minimum gains are +0.184, +0.213 and +0.242, with
positive minimum in 54, 63 and 68 cuts. The fixed second-tick
release delays an accepted return and keeps the time of the first
own fresh hit in 175 cuts across all three futures. Only one cut
adds a clean follow-up and gains points in all three futures for
either single release. Many of the delays still leave the return
on or before our own hit, or the first hit is already beyond the
clean follow-up horizon. None of the complete five-phase donor
and seat groups consistently produces a new clean hit and gain.

Fit a depth-two tree per action to paired accepted margin using
only the own-client position, movement and cue history at the
cut. Fit on the original 12 development clocks, check whole
clock folds, and apply the frozen model to the two other exposed
panels. With a +0.05 threshold, the nominal-score trees change
calendar-weighted mean margin by −0.026, +0.008 and −0.005.
The trees predicting worst paired margin under the three futures
always choose W-tap at that threshold. Dropping the threshold to
zero activates only three cases in development folds, all ties.
The fitted scores fail the W-tap comparison.

### A natural four-hit re-entry on m7422

The single timing-stable new follow-up is m7422 A at zero
added RTT, with the subject's pre-cut client clock offset
of −5 ms. Before the selected head the fighter is on the
ground with almost zero forward speed. Its opponent's visible
hitbox is 6.137 blocks away and the gap is still growing.
The target appears 0.770 blocks higher. Both opening hits
already used natural sprint recoil. All later controls use
ordinary keys and the unchanged 20 CPS limiter.

In the matched stock branch both next attacks score in the same
drain. W-tap's physical press at 517.240 ms after the cut sees a
2.758-block ray. One forward release moves that ray to 3.024
blocks. Our same physical press at 538.681 ms sees a 2.885-block
ray after the release, up from 2.604, and scores on the original
547.853 ms drain. Two steps move our shot out of reach too.

Fork that exact cut under 55 combinations of future input
phase and attack transport. Move defender head opportunities
up to 10 ms earlier, or give either or both sides later input
opportunities, and cross with up to 10 ms of additional new
attack upload on either side. Preserve all prior packets and
states, with timestamp changes bounded by the cut. In every
one-tick arm our original scoring ray still contacts, and the
defender's original matching press is beyond reach.

| m7422 exact start, 55 futures | Plain W-tap | One release on either head | Two releases |
|---|---:|---:|---:|
| First uncontested fresh hit after cut | 0 | 55 | 5 |
| Four opening fresh hits after cut without a return | 0 | 38 | 0 |
| Accepted margin gain / tie / loss vs stock | 0 / 55 / 0 | 38 / 10 / 7 | 0 / 55 / 0 |

At the four-hit outcomes the defender never scores after the
cut through the 2.2 s horizon. Expected margin is still mixed
after the first clean hit. This fixed state is distinct from
other pre-cut offsets on the same clock. The ordinary offset
leaves the subject airborne with negative forward motion and
the release ties stock. The +5 ms own pre-cut phase also leaves
it airborne and costs three points on the first-head release.
Preserve this distinction between variation before the decision
and variation after a common state.

Register m7422 as development and remove its whole calendar
from the subsequent recognition check. A predeclared local band
around the grounded, near-stationary, still-widening position
enrolls 27 cases on the other clocks. Release gains nine,
ties 16 and loses two, with zero robust new clean follow-ups.
Even an offline rule given both future stock rays, with at least
0.28 blocks of our spare reach and less than 0.28 for the
defender, gains in 21 of 264, ties 205 and loses 38. The distance
change from a release varies with subsequent flight, input and
replica movement. Use the measured one-tick re-entry as a
causal example. The current guards do not justify a live rule
or first-to-100 promotion.

[Matched release forks and outcome bounds](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/one-tick-release/newer_exposed-score.json) ·
[Local-observation fit results](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/one-tick-release/local-score-fit/scores.json) ·
[m7422 timing and paired attack rays](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/one-tick-release/wider-m7422-v2/geometry.json) ·
[Cross-calendar guard checks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/one-tick-release/cross-calendar-geometry.json)

### Timing the re-entry release from the first own landing

Enroll on the first input head that sees the quiet bilateral trade.
Use the first four encounters per fight within 23 seconds. From each
complete state, compare plain W-tap with a one-head release at target
hurt cue age 450 ms, the first observed grounded head after being
airborne since our own hurt cue, and one or two heads later. Cancel a
pending pulse on either new hurt cue. Enrollment may precede the pulse
by several ticks. Only enrollment is bounded at 23 seconds in this
study. The 1,226 common timer starts with overlapping coverage
reproduce both original 2.2 s scoring ledgers exactly.

The three exposed panels contain 1,353 cuts across 36 timing
recordings. Each unchanged W-tap arm and each movement arm has the
same physical click timestamps on both sides. The three futures
are the recorded schedules and an extra 5 ms upload for newly sent
own or defender attacks. Report accepted points within 2.7 seconds
of the earlier bilateral cut. The early clean follow-up allows an
850 ms initial gap from that cut, or from the last own fresh hit
if the streak is already open. The subsequent gap limit is 1,100 ms.
This clock starts earlier than the previous age-450 comparison.

| Nominal timing panel | Plain W-tap early clean hits / three-hit chains | 450 ms pulse early clean / mean paired margin | First landed head early clean / mean paired margin | Landing +1 early clean / mean paired margin | Landing +2 early clean / mean paired margin |
|---|---:|---:|---:|---:|---:|
| Development, 446 cuts | 39 / 8 | 28 / −0.094 | 24 / −0.063 | 25 / −0.007 | 28 / −0.040 |
| Comparison, 456 cuts | 39 / 1 | 17 / −0.189 | 13 / −0.167 | 18 / −0.178 | 23 / −0.035 |
| Later exposed, 451 cuts | 36 / 5 | 21 / +0.029 | 14 / −0.027 | 19 / −0.042 | 21 / +0.011 |

No pulse adds an early clean hit with positive score across all
three futures of a cut. Match the first own fresh hit to its
scoring packet across the whole 2.7 s as a separate measure.
W-tap gets a first fresh hit before any return in 315 cuts. The
first-landing pulse creates 106 such outcomes and loses 176.
Of the losses, 174 put the original own scoring press beyond
three blocks, and two remain unresolved. The defender's first
accepted return stays on the same drain in 172 losses. Median
nearest hitbox distance on the lost own presses increases from
2.885 to 3.140 blocks. In the 106 new first-hit outcomes, 104
take a linked return. That return scores a median 48.6 ms after
the attacker scores, and 31 are already clicked before the own
hit scores. The other two reach at least three unanswered hits
within the horizon. Only ten of the 106 increase accepted margin.
The previously frozen m7422 local band,
checked at activation on the other clocks, gives three variants
that add a late first uncontested hit and gain points across all
three futures. All are nearby pre-cut phases of one encounter,
m7388 A episode four. This is one physical witness.

The original m7422 A four-hit witness reproduces in the broader
experiment. The fixed timer and first own grounded head both
release at 6148.505 ms. The first and second grounded heads each
score 4–0 on the nominal future, with W-tap at 2–2. Other
pre-cut phases on this recording enroll different encounters.

In 24 archived native mirrors, 173 of 188 bilateral cut
opportunities reach the fixed-age head before the next cue.
Only 58 of those 173 are grounded. Among 163 cuts with both a
timer and a landing, landing occurs later in 105, on the same
head in 42, and earlier in 16. The median lag is 48.6 ms, with
a range from −108.1 to +153.4. This is an audit of observed
native movement and cues. The pulse comparisons are simulator
forks at matched CPS.

#### The 80 ms reach denial on m7388

In the nominal m7388 A fork, a release one head after landing
moves the defender's original 2.891-block scoring ray to
3.129. Our original ray moves from 2.502 to 2.782 and scores
on its original server drain. The defender replies 51 ms
later on the next drain. The 2.7 s score is 3–3, versus
2–3 for W-tap. The first hits in this case are outside the
850 ms window from bilateral enrollment.

Widen the exact m7388 prefix with 11 future client scheduling
pairs and five attack transport settings, advancing either
uplink by 10 ms, delaying either uplink by 5 ms, or keeping
both unmodified. All earlier states and queues are identical.
Track the actual C02 that converts each original stock click
to an accepted hit, and follow those same consumes in each
movement branch. The trace reproduces all 275 score ledgers.

| m7388 A after bilateral enrollment, 55 futures | Plain W-tap | Fixed 450 ms release | Release at landing or one head later | Release two heads after landing |
|---|---:|---:|---:|---:|
| First own fresh hit before any return within 2.7 s | 3 | 3 | 45 | 40 |
| New first-unanswered hit with a score gain | 0 | 0 | 28 | 28 |
| Original own shot scores on the same drain and original defender press is beyond reach | 0 | 0 | 40 | 40 |
| Two consecutive own fresh hits before a return within 2.7 s | 0 | 0 | 0 | 0 |
| Accepted margin gain / tie / loss vs stock | 0 / 55 / 0 | 31 / 18 / 6 | 33 / 11 / 11 | 36 / 9 / 10 |

There is genuine reach denial in all 28 new first-hit gains.
In the nominal upload case with our future input phase 5 ms
later, the next stock scoring press is consumed 197 ms earlier
than on the nominal phase. The release now moves our ray from
2.817 to 3.094, and leaves the defender in reach at 2.960.
Shifting only the defender's opportunities 10 ms later gives
the original one-sided ray outcome at every tested uplink.
Four of those five branches lose a point anyway. The opponent
still replies on the next drain and then scores an extra damage
upgrade that the stock suffix avoids. Early ray denial is a
separate outcome from preventing the replacement click or
extending the lead through the next fresh window.

All 45 first-hit leads on the landing-plus-one branch take a
return from a click consumed before the new knockback applies
on the defender. The return ray spans 2.027 to 2.568 blocks.
This includes every one of the 28 new leads that gain points.
Nominally the defender's original click misses about 83 ms
before the own fresh hit scores. It clicks again at 2.568 blocks
about 21 ms before that hit scores and 73 ms before the new
velocity applies. The corresponding next defender press in the
nominal m7422 four-hit branch occurs after the own hit scores
and misses on a 3.002-block ray. Checking one original press
is therefore insufficient. A repeatable combo start needs
protection across the whole set of defender click opportunities
that can score before the attacker gets its next fresh hit.

[Landing probe](BoxingLandingReentry.java) ·
[Matched score and seat/RTT breakdowns](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/) ·
[Matched contact losses and m7422 reproduction](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/contacts/causes.json) ·
[Native timer and landing audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/native-landing.json) ·
[m7388 widened timings, original scoring presses and replacement return timing](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/wider-m7388/press-audit-v2/summary.json)

### Footwork against the defender's replacement click

Use the 25 exposed exact cuts in which the landing-plus-one
release takes the first fresh hit under all three existing upload
settings, with a nominal replacement attack already clicked
before that hit scores. Include the m7422 four-hit cut as a
reference. Starting from the same complete bilateral cut, insert
one or two ordinary movement heads after the release. Test
grounded starting heads +2 through +14, eight first movements
and an optional second movement from the same eight choices.
All actions stop on a new own or target hurt cue. Every branch
matches both fighters' physical click timestamps and the 20 CPS
cap. The 73,008 comparisons retain the 2.7 s horizon. Count a
new double by comparing the uninterrupted fresh-hit run with
both plain W-tap and the first release alone. Its two hits must
be no more than 1.1 s apart, with no accepted return through
the second hit. Preserve early-850-ms counts separately.

Only one of the 25 cuts adds a double and beats both controls in
the original, extra 5 ms own-upload and extra 5 ms defender-upload
futures. Ten movement sequences do so in that same m7398 seat B,
second trade at zero added RTT. Each preserves the first fresh
scoring drain. None of the three enrolled m7388 trajectories at
80 ms add a double with any tested movement.

Freeze the simplest winning addition, one backstep at grounded
head +2, and the largest nominal winner, a right strafe at +2
and a sprint-right head at +3. The original W release is at
head +1 for both. Widen the exact m7398 state with the same
11 future phase pairs and five nonnegative C02 upload settings
used for the zero-RTT m7422 example. Include both movements
without the first release as controls.

| m7398 B, 55 timing variants | Plain W-tap | One release | Release and backstep | Release and right strafe then sprint-right | Backstep alone | Right strafe then sprint-right alone |
|---|---:|---:|---:|---:|---:|---:|
| Two or more uninterrupted fresh hits | 0 | 0 | 33 | 33 | 0 | 0 |
| Three or more uninterrupted fresh hits | 0 | 0 | 0 | 33 | 0 | 0 |
| Accepted margin gain / tie / loss vs W-tap | 0 / 55 / 0 | 0 / 55 / 0 | 33 / 20 / 2 | 33 / 22 / 0 | 0 / 55 / 0 | 0 / 55 / 0 |
| Keep the release-only own scoring press on its original drain and deny its replacement return ray | 0 | 0 | 25 | 25 | 0 | 0 |

In the nominal fork, the backstep moves the replacement ray
from 2.668 to 3.141 blocks, with our original ray still in
reach at 2.988. The lateral sequence moves it to 3.005
and preserves our original ray at 2.860. For lateral, the
nearest target edge is 2.993 blocks from the defender's eye,
so a different aim ray could still connect. Advancing the
defender's schedule preserves the result. Shifting it 2 ms
later gives the same replacement click a 2.964-block ray
after the backstep and a 2.827-block ray after lateral movement.
Both branches lose the double in all five upload variants at
this later phase. In the backstep branch, the physical poses
of both fighters at the defender's replacement press are
identical across these phases. The defender's interpolated
copy of the attacker is 0.177 blocks closer with the 2 ms
shift. This accounts for the full change in the selected ray.

The frozen moves also run on all 1,353 original nominal
bilateral starts. Both controls reproduce exactly. Exclude the
whole m7398 recording when comparing the other exposed clocks.
The longer movement gives up average score in all three panels.

| Nominal panel | Plain W-tap doubles / paired mean margin | One release doubles / paired mean margin | Release and backstep doubles / paired mean margin | Release and lateral doubles / paired mean margin |
|---|---:|---:|---:|---:|
| Development, 446 cuts | 9 / 0 | 8 / −0.007 | 9 / −0.164 | 9 / −0.105 |
| Comparison, 421 cuts | 1 / 0 | 1 / −0.188 | 1 / −0.297 | 0 / −0.290 |
| Later exposed, 451 cuts | 5 / 0 | 4 / −0.042 | 3 / −0.180 | 6 / −0.084 |

Six distinct encounter groups on five other clocks gain a
nominal double and points against both baselines. All are at
zero added RTT. Group by donor, seat and episode, then widen
one winning pre-cut phase per group with no further movement
tuning. Across these groups the best registered action gives
6, 21, 40, 34, 25 and 21 double-and-score gains out of 55.
Neither baseline has a double in those six panels. The highest
count, 40 on m7385 A, comes from a backstep moving both the
original and replacement defender contacts beyond reach in
every gain. Several other examples do as well when the initial
release is removed. None of the selected starts survives every
timing. These are simulator outcomes on exposed donor clocks,
with native first-to-100 and separate maintenance qualification
still open.

[Replacement protection search](BoxingReentryProtection.java) ·
[Matched word screen and both baselines](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/summary.json) ·
[M7398 exact timing and replacement clicks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/check/wider-summary.json) ·
[M7398 actual and interpolated positions at the same defender press](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/check/replacement-poses/summary.json) ·
[Other-clock nominal comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/check/comparison-summary.json) ·
[Other selected exact cases with all timing variants](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/check/other-witnesses/summary.json)

### Holding the same movement across defender view timing

Cross all 936 earlier movement sequences with all 55 previously
registered futures on the 25 replacement-return cuts, the m7422
reference and the six other exposed witnesses. These are 1,647,360
exact comparisons with matching physical clicks within a future.
The earlier 73,008 outcomes reproduce. Each continuation gives
plain W-tap and the one-head release the same clock and queues.
An additional double must beat both controls in uninterrupted
fresh hits and in accepted score. A timing oracle may choose a
different word in each realized future, or stay on plain W-tap.

| Exact starts by added RTT | Plain W-tap futures with a double | Release-only futures with a double | Futures where any word adds a double and margin vs both | Sum of qualifying futures for the best fixed word per cut | Cuts with a word qualifying across all 55 |
|---|---:|---:|---:|---:|---:|
| 0 ms, 8 cuts × 55 | 0 | 38 | 216 | 205 | 0 |
| 20 ms, 11 cuts × 55 | 0 | 0 | 0 | 0 | 0 |
| 80 ms, 13 cuts × 55 | 0 | 0 | 0 | 0 | 0 |

Three zero-RTT cuts have words that survive both the nominal
and +2 ms defender schedule with all five upload settings.
For m7398, ten words qualify at nominal timing and zero of
936 qualify with the defender 2 ms later, even when choosing
with hindsight. Across the full grid an oracle finds a word
in 37/55 futures of that cut. The highest-score oracle obtains
about +0.75 and +1.63 short points per future at 20 and 80 ms
despite finding no new clean double with positive margin there.
These are finite bounds for the tested menu and clocks.

#### The single tracker update that flips m7398

The server sends the relevant tracker update 940.680 ms
after the bilateral cut. It is ready at 940.916 ms, about
0.066 ms after the nominal defender drain starts. Nominally
the defender takes an interpolation step toward the old goal
at 943.161 ms and handles the new update at 961.605 ms. The
+2 ms client schedule handles the update at 942.850 ms,
before the 945.161 ms interpolation step. The new goal is the
same, and the extra step toward it accounts for the closer
replica at the replacement click.

Move just that model position packet's delivery while leaving
the client schedules, clicks, packet payload and W-tap
controls matched. Advancing it by 0.15 ms on the nominal
schedule puts it in the earlier drain. The backstep's two-hit
run and the lateral three-hit run both become single-hit leads.
On the +2 ms schedule, delaying it by 2.25 ms restores the
two- and three-hit runs. The first own score stays in its
original drain in both directions. W-tap's total margin and
chain length are unchanged in both timing comparisons. This
isolates tracker timing in the model.

Across 24 archived native W-tap mirrors, 7,525 of 28,999
linked first interpolation steps have a movement packet
received within 2 ms of a client drain start. In 2,449 of
these the nearby drain and its neighbor on the other side
of receipt bracket an interpolation opportunity. In 1,849
of those the measured first horizontal step is over 0.1
blocks. Wire receipt is a proxy for queue readiness. This
describes how often the timing boundary arises in native
recordings; the archive audit does not estimate how many
hits would change.

#### A nearly repeatable zero-RTT opening on m7384

On m7384 B, release at grounded head +1, walk forward
without Ctrl on +2 and take one backstep on +3. At the
exact pre-cut phase (0,+5 ms) of this witness, the word
keeps the same first scoring drain and gives 3–4 unanswered
hits and +3 to +4 points against both controls in 54/55
timings. The remaining future ties, and no other word
rescues that future. Both controls have zero doubles across
all 55. The original W-tap return is still denied in the
failure. With future own opportunities 10 ms later, adding
10 ms to our C02 makes it ready 0.350 ms after the original
server drain. The same own ray still contacts at 2.996
blocks, but the first point moves 54.427 ms later. The
defender's replacement gets a 2.047-block ray after the
new velocity applies and about 0.56 ms before the first
movement under it.

Freeze this word across the 1,353 nominal cuts and verify
that all earlier controls reproduce. Remove the entire
m7384 recording from the comparison below.

| Other exposed clocks | Plain W-tap doubles / mean margin change | Release-only doubles / mean margin change | Frozen walk/backstep doubles / mean margin change |
|---|---:|---:|---:|
| Development, 412 cuts | 9 / 0 | 8 / −0.007 | 12 / −0.124 |
| Comparison, 456 cuts | 1 / 0 | 1 / −0.178 | 1 / −0.276 |
| Later exposed, 451 cuts | 5 / 0 | 4 / −0.042 | 3 / −0.177 |

The tested footwork can protect the approach when the
attacker's packet lands early enough and the defender's local
view stays outside reach through its remaining clicks.
Those conditions are distinct. Neither this fixed word nor
the earlier variants transfer on broad score. Native
first-to-100 and established-combo gates remain separate.

[Full timing-grid scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/summary.json) ·
[Tracker update intervention](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/check/replica-timing/summary.json) ·
[Native tracker boundary audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/check/replica-timing/native.json) ·
[M7384 witness attack packets](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/chosen-transfer/witness.json) ·
[Frozen walk/backstep comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/chosen-transfer/transfer-summary.json)

### When a short trade gain lasts through the match

Five exposed positive-RTT cuts have a fixed movement word with
no score loss across the 55 original timing variants and a mean
gain of at least 0.7 accepted hits through 2.7 seconds. Select
at most one cut per donor and seat, taking the highest prior
mean. Reproduce every score and trace the actual scoring presses.
This is a selected panel, so selection gains need a separate
comparison. Include each word without the earlier release.

The words and the release together are ahead against W-tap in
181 of 275 variants at 2.7 seconds. They are already ahead at
1.35 seconds in only four of those 181. Compare the first
scoring drain whose ledger differs. All 289 different scoring
credits there belonged to the original W-tap arm. The same
presses are outside reach after the movement, including our own
original press in many simultaneous trades. The first accepted hit alone
does little to explain the eventual gain. Over the full 2.7 s,
the net gain totals 167 fresh hits and 190 damage upgrades.
Dropping the initial release cuts the mean from +1.298 to
+0.127 and creates 35 losses.

On m7421 B nominal, two late release heads alone preserve the
original mutual first drain. The 2.7 s gain is +2: an opponent
upgrade that scores with W-tap instead has a legal 2.028-block
ray but is uncredited, and the candidate gets an upgrade of
its own. On the related futures where adding the initial
release also preserves both original scoring presses, the
first difference is a later defender attack. In four, the same
upgrade press moves from a 2.646-block ray to 3.246. The
later trade still has a return before a double.

Extend the exact same branches to eight seconds. Both sides
resume ordinary W-tap after the registered heads. All earlier
ledgers reproduce. Only 74 of the 181 selected 2.7-second
leaders are still ahead by eight seconds. Eighty are behind
and 27 tie.

| Selected exact donor and seat, 55 futures each | Plain W-tap paired score change | Candidate at 2.7 s | Candidate at 8 s |
|---|---:|---:|---:|
| m7378 B, 20 ms | 0 | +0.73 | +1.07 |
| m7382 B, 20 ms | 0 | +0.78 | +0.60 |
| m7388 A, 80 ms | 0 | +1.00 | −0.20 |
| m7410 B, 80 ms | 0 | +1.93 | −0.44 |
| m7421 B, 80 ms | 0 | +2.05 | −1.36 |
| All 275 futures | 0 | +1.30 | −0.07 |

Continue the same model matches to 100 accepted hits, with
the exact match score before the fork included in all arms.
Retain the supplied client phase and stop changing the upload
after eight seconds. Scoring ends on the actual hundredth hit,
with native drain ordering and damage upgrades. All 275 paired
variants finish. Their replay schedules can cycle through a
recorded calendar multiple times, and they share just five
selected starting states. This measures persistence in the
model. It is separate from independent native regulation.

| First-to-100 model result | Plain W-tap | Release only | Release and selected movement | Selected movement alone |
|---|---:|---:|---:|---:|
| Wins | 125 / 275 | 110 / 275 | 113 / 275 | 113 / 275 |
| Own final margin, median | −3 | −6 | −5 | −3 |
| Paired mean score change | 0 | −10.00 | −8.59 | −4.46 |
| Paired change, min / median / max | 0 / 0 / 0 | −110 / −3 / +106 | −115 / −2 / +122 | −138 / −4 / +132 |

In the nominal m7410 case, the release and footwork add
three points by 2.7 s, then finish 95–100 where the matched
W-tap arm wins 100–27. Both finish within the original donor
calendar. In all 55 m7410 variants, both arms complete within
the donor's first calendar period. In the nominal comparison,
W-tap lands 71 consecutive fresh hits starting 5.856 s after
the cut, with no accepted return or gap over 1.1 s. With the
movement, the longest own run is three. Across the 55 variants
the median own longest run is 57 with W-tap and three with
the movement. The factual m7410 donor finished 100–99 from
seat B, with a longest run of nine there and eleven in A.
The native result bounds how literally to read the model's
late chains. Neither model arm replays the donor's factual
full server state or presses.

#### Comparing the moves on other clocks

Replay all 1,353 original cuts with the five words and their
no-release variants. Use three futures per cut and reproduce
both earlier control ledgers. Exclude all five example
donors from comparison and fitting. Use own-client features
in a depth-two tree per move, trained on other development
donors, with separate targets for nominal score or the
minimum of the three margins. All scores below are calendar
weighted. The development row holds out each whole clock
from the model that scores it.

| Other exposed cuts | Plain W-tap | Nominal-score tree | Minimum-score tree | Oracle with all three future margins |
|---|---:|---:|---:|---:|
| Development folds, 332 | 0 | −0.071 | +0.015 | +0.572 |
| Comparison, 418 | 0 | −0.074 | −0.034 | +0.500 |
| Later exposed, 417 | 0 | −0.006 | +0.014 | +0.551 |

The oracle selects the word maximizing the minimum score over
the three futures, using mean margin to break ties. The table
reports the nominal realized margin of that selection. The best
fixed word on the comparison panel loses an average of 0.008
points. The scoring trees fail the matched score gate.
Native regulation, maintenance and
jump comparisons remain separate.

[Five-cut score and attack attribution](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/score-only/summary.json) ·
[Eight-second score trajectories](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/score-only/longer-horizon/summary.json) ·
[All model first-to-100 scoreboards](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/score-only/regulation-suffix/summary.json) ·
[M7410 paired model streaks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/score-only/regulation-suffix/m7410-streaks.json) ·
[Factual m7410 streaks](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/score-only/regulation-suffix/m7410-native-reference.json) ·
[Other-clock scores and selector outcomes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/range-v4-jump/reply-timing/camera-spacing/recoil-and-delay/native-input-poll/model/single-hold-sweep/earlier-approach/first-recoil-stress/return-probe/next-exchange/natural-reentry/landing-reentry/replacement-protection/robust-survey/score-only/transfer/summary.json)

### Why the generated W-tap mirror drifts from its donor

Use actual inputs and packet handler times in the strict replay
of the first four seconds of m7410, m7388 and m7382. All 4,285
requested records reproduce, with bit-exact compared state
and the same contact selection. No later native pose is fed
back. Ray-distance roundoff is at most 2.3e−16 blocks. Use
the same five exposed W-tap donors in the separate generated
replay, aligned to the original monotonic origin at the first
native heads and server drain. Its baseline uses the earlier
movement-derived wire estimates, native host opportunities,
zero input completion cost, and generated W-tap actions.

In m7410 B, the generated W-tap arm gets the first 11 accepted
scoring events on the same side and drain. The first remote
view disagreement is at 1,217.214 ms, when the generated
position packet is ready at 1,215.122 ms. It catches the
1,215.348 ms task drain. The native packet reaches the wire
at 1,215.385 ms and queues at 1,215.451 ms, missing that
drain. Replacing just its readiness removes that first view
error. The next view error occurs around 1,911.552 ms. This
earlier view mismatch by itself leaves the later score
unchanged in the tested fork.

The first different own movement in m7410 B is at
2,668.263 ms. The generated S12 is ready at 2,665.407 ms,
just before the 2,665.513 ms client drain. The actual S12
queues at 2,666.875 ms and applies in the next drain.
Moving just that velocity packet to its attested time makes
both clients' movement match through 4.5 seconds and
restores the 16 recorded scoring events. Substituting the
attested preceding S19 alone also moves the velocity to
the next drain through FIFO ordering. The generated press
admissions already agree over this interval.

| m7410 B on its original calendar, one nominal model run | Plain W-tap timing model | Replace the early position update | Replace the S12 time | Replace position, S19 and S12 |
|---|---:|---:|---:|---:|
| Same native scoring prefix through 4.5 s | 11 | 11 | 16 | 16 |
| Model first-to-100 score from B | 100–27 | 100–27 | 93–100 | 93–100 |
| Longest own uninterrupted fresh run | 71 | 71 | 2 | 2 |

Both model scoreboards finish within the original recording
period. Actual m7410 ends 100–99 from B and has a longest B
run of nine. The one-packet substitution demonstrates that
the 71-hit result depends on an early timing error. Later
generated outcomes still diverge from the recorded fight.

#### Wire receipt, queue readiness and press completion

In m7388 A the relevant S12 reaches the wire at 1,598.491 ms,
which is before the 1,599.759 ms client drain. The native
enqueue occurs at 1,600.494 ms, after it. Supplying only raw
wire receipt still applies the knockback early: A's generated
position rises by 0.361375 blocks on the 1,602.441 ms move
while native stays grounded. Supplying the measured enqueue
moves the impulse past that step. A's own movement then
matches through the first difference at 2,912.074 ms.
The first attack admission already differs at 2,911.259 ms.

The modeled click cap also omits completion time. In m7378
A, the 489.489 ms press takes 5.088 ms to complete.
The 542.791 ms opportunity is 53.302 ms after the previous
poll and only 48.215 ms after completion. The generated
zero-cost cap admits at that poll. The native cap waits until
555.732 ms. Using the single measured completion cost
restores admission until 2.851 s and moves A's first movement
error from 0.548 to 1.949 s. In m7382 B the analogous
4.918 ms cost fixes the recorded admission sequence for
the 4.5 s check, while the earlier interpolation and movement
errors remain traceable independently.

Across the active windows of the same five native recordings,
raw receipt predicts the task drain too early for 55 of 7,098
position updates, four of 652 velocity packets and eight
of 1,304 hurt statuses. Observed enqueue and main-thread
handler timing agree on the drain for all linked packets in
that audit. The individual timestamp edits use hindsight.
They isolate transport, queue and cap details for future
baseline tests and leave normal simulator settings intact.

[Strict native opening replay](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/strict-opening.json) ·
[Generated mirror and native alignment](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/summary.json) ·
[Individual packet and cap interventions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/causal-first-events/summary.json) ·
[Paired first-to-100 timing sensitivity](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/causal-first-events/long-m7410/summary.json) ·
[Native wire and task queue coverage](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/causal-first-events/native-stage-coverage.json)

### Calibrating packet and click timing on separate match recordings

Freeze 12 development recordings, four at each of 0, 20 and
80 ms added RTT. Fit a median wire-time residual by route and
packet family relative to the existing movement-derived
delay estimates. Estimate the wire-to-enqueue delay separately.
Fit physical press-completion costs by RTT, distinguishing the
first two presses from the later ones. The fits use the first
12 seconds of linked timing records and use no scoreboard labels.
Attack wire offsets range from 0.406 to 0.571 ms by RTT. Velocity
offsets range from 1.181 to 1.596 ms. Median queue delays vary
from about 0.01 to 0.12 ms by family. The Java runner preserves
FIFO ordering and generates the clicks and packets for both bots.

The opening comparison uses 22 other W-tap mirrors on two
previously exposed panels of eleven. Two timing-diagnostic
recordings, m7410 and m7421, appear separately in the raw output.
Every arm uses that match's archived host opportunities and the
same prior movement-delay estimate. Score error adds the
absolute count errors of both players at 4.5 seconds. The exact
prefix counts accepted events in order with the same attacker,
drain and damage class. Movement error compares corresponding
native movement ordinals on each side.

| Measure through 4.5 s | Plain W-tap, original timing | Wire offsets | Wire and queue | Click cost | Wire, queue and click cost |
|---|---:|---:|---:|---:|---:|
| Mean score count error | 1.955 | 1.727 | 1.409 | 1.682 | 1.364 |
| Mean differing accepted events | 8.773 | 7.182 | 6.500 | 8.409 | 6.182 |
| Median exact initial score events | 4.5 | 7 | 7 | 4.5 | 7.5 |
| Mean position error, blocks | 0.894 | 0.714 | 0.480 | 0.845 | 0.442 |
| Mean disagreeing physical press polls per side | 5.295 | 5.295 | 5.295 | 4.773 | 4.773 |

Adding queue time improves the combined average beyond the
wire-only estimate. Wire plus queue reduces mean score error
from 2.545 to 1.455 on the first panel and ties at 1.364 on the
later panel. Mean position error falls from 1.226 to 0.559 and
from 0.562 to 0.402 blocks respectively. Adding the click fit
then moves mean score error to 1.000 on the first panel and
to 1.727 on the later panel. Physical poll disagreements also
move in opposite directions. Use the packet-only fit for the
opening witness checks below.

In a separate check conditioned on known native send
opportunities, the central estimate places 529 of 562 velocity
packets in the correct eligible task drain versus 491 for the
original timing. The empirical 5th–95th percentile residual
interval covers the observed drain for 557, and spans multiple
drains for 141. It covers the observed ready timestamp for 472.
For attack packets, drain accuracy is 2,668 of 2,717 versus
2,634. This tests readiness at observed send opportunities.
The closed-loop opening comparison above generates its own sends.

#### Retest the frozen opening cuts

Reproduce all five original 55-variant grids before switching
packet timing at the same complete bilateral checkpoints. Keep
already committed packets and inputs, prime FIFO state from the
pending packets, keep the original click cap, and replay the
same frozen movements. Some checkpoints use clocks included
in the transport fit. This is a sensitivity test of the exposed
witnesses. Mean margin change is against W-tap within the same
timing model and variant.

| Recorded cut, seat and RTT | Plain W-tap clean doubles, both models | Original clean doubles / mean margin change | Calibrated clean doubles / mean margin change |
|---|---:|---:|---:|
| m7422 A, 0 ms | 0 | 38 / +2.636 | 38 / +2.636 |
| m7398 B, 0 ms | 0 | 33 / +1.527 | 32 / +1.164 |
| m7384 B, 0 ms | 0 | 54 / +3.655 | 54 / +3.309 |
| m7410 B, 80 ms | 0 | 0 / +1.927 | 0 / +2.091 |
| m7388 A, 80 ms | 0 | 0 / +1.000 | 0 / +0.873 |

On m7384 the movement word keeps its paired W-tap arm's
first scoring drain in all 55 variants of both models.
Among its 54 clean chains, 20 reach four hits with
the fitted timing, down from 39. The other 34 reach three.
The 80 ms movement words retain short score gains. Both still
lose the clean-double test. Native regulation and maintenance
require their own comparisons.

[Frozen timing split](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/protocol.json) ·
[Stage coefficients](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/fit.json) ·
[Opening comparisons](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/comparison/summary.json) ·
[Eligible drain intervals](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/envelope/summary.json) ·
[Frozen-cut timing grids](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/reentry-compare/summary.json)

#### When delays change within a fight

Fit each development recording separately and omit the
witness's own recording. The 18 individual fits produce
the same clean-double counts in exactly the same phase and
upload combinations as the pooled fit. The effects on
accepted margin and chain length still vary. All five
witnesses are selected archived checkpoints.

Now use four 2.7-second blocks from each other same-RTT
donor, starting at 0, 3, 6 and 9 seconds of its clock.
Align each block to the witness checkpoint. At each
generated send, use the most recent delay sample from the
same route and packet family. Use the match median when
there is no preceding sample. Keep the measured wire
residual and wire-to-enqueue delay together. The common
fork preserves pending packets and input history, and it
checks both physical press schedules against W-tap. These
72 blocks and 3,960 paired stress scenarios are correlated
reuses of old clocks. They measure sensitivity to those
delay patterns. They do not estimate native success rates.

| Witness and RTT | Plain W-tap doubles across all blocks | Delay blocks | Candidate double gains per block, of 55 | Same timing succeeds in every block, of 55 | Mean paired score gain across blocks, range |
|---|---:|---:|---:|---:|---:|
| m7384 B, 0 ms | 0 | 12 | 54 each | 54 | +2.49 to +3.31 |
| m7398 B, 0 ms | 0 | 16 | 15–46 | 15 | +0.55 to +2.33 |
| m7422 A, 0 ms | 0 | 16 | 0–38 | 0 | −0.71 to +2.89 |
| m7388 A, 80 ms | 0 | 12 | 0 | 0 | +0.87 to +1.13 |
| m7410 B, 80 ms | 0 | 16 | 0 | 0 | +1.47 to +2.15 |

For m7384 the 648 qualifying runs include 70 doubles,
378 three-hit chains and 200 four-hit chains. The same
timing fails in all 12 blocks and ties W-tap on accepted
points in each. In m7422, one block erases all 38 doubles
from the pooled fit. On m7398, 47 timing combinations
work in at least one block and 15 work in every block.

Trace the nominal timing of the worst block on m7422
and m7398. Substitute and remove the measured delays for
each packet family, then repeat in a 0.02 ms interval
around the individual send. All times below are from the
common checkpoint.

- **m7422, the first attack arrives late.** A press at
  1035.922 ms selects the same 2.88445-block ray. Applying
  one attack timing sample moves readiness from 1036.654
  to 1060.088 ms, past the 1045.093 ms server drain. It
  scores at 1097.063 ms. The defender's next click sees a
  1.85596-block ray instead of 3.00024, and the return
  scores before the combo can continue. The four-hit
  chain becomes one, with paired margin falling from +4
  to zero for the single substitution. The whole block
  gives −1. The source sample's large residual is between
  the observed client attack loop and its local wire send,
  which are 23.946 ms apart. The measured wire leg itself
  is 1.330 ms. Restoring the median to just that send
  restores the four-hit chain in the attack-only stress.
- **m7398, the defender sees the approach early.** A
  relative position packet becomes ready at 940.835 ms
  rather than 941.218, catching the 940.850 ms client
  drain and one extra interpolation step. The first own
  score stays at 1068.790 ms. The defender's 1060.992 ms
  press has a 2.82685-block ray instead of 3.00462 and is
  accepted at 1105.994 ms. The chain drops from three to
  one and the paired margin from +2 to zero. Restoring
  the median to just that update recovers all three in
  the position-only stress.

The present timing envelope separates the deadline for the
attacker's packet from the defender's rendered reach. The
same observed first contact can leave either margin exposed.
One robust zero-added-RTT state survives, and these selected
checkpoints leave broad transfer and native policy promotion
open.

[Individual match fits](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/single-donor-fits/summary.json) ·
[Time-varying delay protocol](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/native-delay-blocks/protocol.json) ·
[All 3,960 paired scenarios](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/native-delay-blocks/summary.json) ·
[Packet-family interventions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/native-delay-causes/summary.json) ·
[Single-send interventions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/native-delay-causes/single-packet/summary.json)

#### When the approach creates the same combo start

Rebuild the approach from spawn for the same five selected
recordings. Apply the seven declared phase proposals
from −8 to +8 ms around each player's original phase. At
the corresponding bilateral encounter ordinal within
225 ms of the original, fork the full state, including
pending packets and primed histories. Run the same 55
future timing combinations for plain W-tap and the fixed
movement. The original and fitted transports reproduce
all ten old central result grids. Negative phase proposals
can share effective schedules where the clock floors an
event at the start. None are independent native runs.

On the first trade of m7384, separately vary one or two
own inputs on approach. Try W with Ctrl released, releasing
W, S, or A at the first own head within 4.25, 4.75, or
5.25 blocks of the visible hitbox. Cancel on either hurt
cue. This adds 24 proposed starts to the 245 phase cells.
Only the six W-preserving inputs keep the hit sequence.
They retain the same sampled recoil, spacing and score as
the original, consistent with sprint already being active.
All 17 other in-window cuts give zero double gains, and
one loses the qualifying encounter entirely.

| Proposed approaches | Matching ordinal in window | Plain W-tap doubles | Fixed movement double gains per 55 futures | Mean paired 2.7 s score gain |
|---|---:|---:|---:|---:|
| m7384 B phase, 0 ms RTT | 49 / 49 | 0 | 54 in 12, 50 in two, zero in 35 | +0.805 |
| m7384 B approach keys, 0 ms RTT | 23 / 24 | 0 | 54 in six, zero in 17 | +0.906 |
| m7398 B phase, 0 ms RTT | 8 / 49 | 0 | 31–32 in eight | +1.327 |
| m7422 A phase, 0 ms RTT | 7 / 49 | 0 | 34–38 in seven | +2.605 |
| m7388 A phase, 80 ms RTT | 6 / 49 | 0 | Zero in six | +0.767 |
| m7410 B phase, 80 ms RTT | 7 / 49 | 0 | Zero in seven | +1.810 |

The fixed movements and timing models in this table use
the same physical press times within each pair. Most
shifted later encounters move outside the 225 ms window
or fail to reach the target ordinal by its end. All cells
are retained. The score column only uses cells with a cut.

**The defender's first movement under knockback creates the
m7384 opening.** At the modeled cut, the +5 and +7 ms
defender schedules have identical registered own-view
features, attack separation and initial impulses. Holding
future defender opportunities at the +7 ms schedule, the
earlier +5 ms history gives four unanswered hits and +4
points after the cut. The +7 ms history gives zero on
both counts. The two histories begin from the same 1–1
score. The defender's first move after the trade occurs
at 425.409 or 427.409 ms. In the successful history it
uses −0.5697 blocks/tick horizontally after a local
contact. In the failed history it uses the full
−0.9495. Giving the successful history full recoil for
that step alone removes the four hits. Giving the failed
history 60% at that step recovers all four. The intervention
leaves both players' vertical physics and click calendars in
place. The paired W-tap continuations all finish at zero
net points over the scored window.

By the release decision the two natural traces have
visible gaps of 6.577 and 8.190 blocks. The intervention
that restores the combo brings the gap to 6.716. Test
whether a similar gap suffices on previously exposed
calendars. Before reading those outcomes, freeze 6.3–7.0
blocks at the candidate's grounded release and an absolute
gap change at most 0.06 blocks per tick. It activates on
59 of 1,319 archived cuts after removing all of m7384.
Across all cuts the resulting short score margins are −7,
0, and +3 points for the three comparison groups versus
W-tap. Only m7392 A has a new nominal double, and the
pooled timing fit removes it from that nominal case.
Across 16 timed blocks it gains a double in 0–35 of the
55 futures, with zero qualifying timings across every
block. The biggest loss under the same cue, m7392 B,
adds zero doubles and averages −2.91 to −1.00 points
across the blocks. W-tap gets zero doubles in both block
comparisons. This visible cue distinguishes the specific
m7384 recoil intervention. The transfer test leaves it
insufficient as a trigger on its own.

[Frozen approach protocol](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/acquisition-neighborhood/protocol.json) ·
[All 269 approach proposals](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/acquisition-neighborhood/summary.json) ·
[Matched future schedules](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/acquisition-neighborhood/phase-traces/summary.json) ·
[First-step interventions](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/acquisition-neighborhood/phase-traces/first-recoil/summary.json) ·
[Visible-gap transfer](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/acquisition-neighborhood/landing-cue-transfer/widened-summary.json)

#### Can the first click create the recoil opening?

Extend the native quiet-trade audit to both recipients
and all 36 exposure clocks. The middle cohort has a
W-tap observer on one side. Require the first fresh hit
after 1.1 s quiet and the other player's fresh reply
within 275 ms. Record the recipient's exact S12 payload,
the subsequent sprint contacts, and first travel. Both
steps reconcile in all 606 pairs. Keep the 595 with a
complete 2.7 s continuation for the double count. A
double requires two additional fresh hits before any
accepted opposing return, with no shared return tick and
at most 1.1 s between the two new hits. The role names
use server processing order, including same-tick pairs.

| First-step recoil, earlier hitter / later hitter | Native W-tap trade pairs | Same-tick pairs | Complete 2.7 s | Native W-tap doubles, earlier / later |
|---|---:|---:|---:|---:|
| Full / full | 319 | 279 | 316 | 2 / 1 |
| Full / braked | 260 | 9 | 253 | 1 / 11 |
| Braked / full | 12 | 11 | 11 | 2 / 0 |
| Braked / braked | 15 | 0 | 15 | 0 / 0 |

The earlier 24-mirror quick follow-up result reproduces
under its original 850 ms definition. Of 302 resolved
braked steps across all 36, the initial brake happens a
median 19.457 ms after velocity applies and 0.621 ms
before first travel. Most brakes occur on the later
hitter, who can receive a hit and use their own reply to
brake. This is a measured association with ordering and
follow-up hits. The factual first trade in m7384 has
two full 0.9495-block/tick steps and no clean double.
The four-hit result belongs to its model phase variant.

#### Retime the first press with the physical cap in force

Use both seats and three defender phases from each of
the 36 archived clocks, plus five focused m7384 phase
proposals. Select the first W-tap admission that is later
consumed as a contact, before 950 ms. This selection is
an offline label. Use eleven frozen waits from zero to
60 ms at that poll and let the normal cap time all later
presses. From the first bilateral cut, optionally play the
fixed m7384 footwork. Compare total accepted-hit margin
from spawn through 3.100 s. Same-state suffixes retain
identical press histories. The qualified full-opening
comparison also requires both seats to keep their original
press counts through the common deadline.

| Modeled cohort | Plain W-tap doubles at zero delay | Retimed openings with equal counts | New defender brakes at equal counts | New brake plus double plus margin gain | All doubles with positive score against W-tap, at equal counts |
|---|---:|---:|---:|---:|---:|
| Development | 0 | 279 | 0 | 0 | 0 |
| Comparison | 0 | 246 | 28 | 0 | 0 |
| Later exposed | 0 | 292 | 22 | 0 | 5 |
| Focused m7384 | 0 | 0 | 0 | 0 | 0 |

There are 217 starts with a contact in the opening and
2,170 nonzero delays. The five qualifying doubles all
come from the same retimed admission on m7421 A at
80 ms added RTT. A four-millisecond request moves the
actual admission from 410.696 to 431.263 ms because
the client must wait for its next eligible poll. The
first own score moves from 500.045 to 550.281 ms,
sharing the defender's initial scoring drain. The
defender's first movement uses the full 0.9495
blocks/tick, versus 0.5697 before the delay. The two
additional unanswered hits occur with plain W-tap after
the delayed press. The later movement adds zero. Each
side presses 55 times through 3.1 s in both arms. Score
margin changes from −1 to +2. The loss of the defender
brake accompanies the changed first-hit order and flight.

Repeat that four-millisecond request across 23 defender
phase proposals on both seats, using the prior timing
and the pooled fit from spawn plus four recorded delay
traces starting at spawn. On A, ten of the 23 phase
proposals gain a double across all six timing models.
Individual models produce 10–12 qualifying phases and
mean paired score gains of +1.39 to +1.87 over all 23.
Plain W-tap gets zero doubles on A in each comparison.
B loses one physical press and provides no equal-count
comparison. The archived full 35-second W-tap source
ended 6–66 from A. Its score and the 3.1 s diagnostic
describe different horizons and control histories.

In the pooled model the earliest cap-ready poll with
a legal local ray and no miss cooldown matches the
offline admission in all 46 stock runs. In the reference
A case that poll sees a 2.824-block hitbox and both
hurt cues are absent. The observation supplies a way to
test the timing without the future contact label. The
evidence supports one timing-sensitive short opening on
one clock and seat. Independent regulation and maintenance
promotion remain open.

[Native first-step audit](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/native-bilateral-recoil/summary.json) ·
[Paired first-press delay results](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/opening-press-delay/summary.json) ·
[80 ms timing stress](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/opening-press-delay/positive-rtt-case/summary.json) ·
[Own-client poll trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/opening-press-delay/positive-rtt-case/poll-observation.json)

#### A local first-click delay on fresh clocks

Freeze one four-millisecond requested hold at the first
cap-ready poll with a valid local target and no miss
cooldown. The trigger is available only before either
hurt cue and through the first 950 ms. The opener uses
ordinary W-tap movement and ordinary capped presses
afterward. Its paired W-tap continues immediately at
the same poll. The simulator uses the pooled packet fit
from spawn and the common 3.1 s scoring deadline. A
separate result requires equal press counts for both sides
across the two arms. This is a locally executable decision
in the emulator with no future contact label.

On the original 36 exposed recordings, 150 of 216
proposals have equal counts. The sole additional double
in those 150 is m7421 A. On that example's extended
phase grid, all 23 A proposals keep equal counts, 11
gain a double, and the mean score advantage is +1.78.
The 23 B proposals lose a press each. These expanded
conditions reuse the same recording.

Freeze the rule before capturing 12 new independent
W-tap mirrors, four each at 0, 20 and 80 ms. The main
Minecraft lab runs plain W-tap on both sides for 8.5 s.
All 12 native exports pass the wire-flush and seed
checks. The unshifted W-tap emulator matches the full
3.1 s credit sequence on eight recordings, and the two
overall counts on ten. There are two counts outside
that match. Retain every recorded schedule and compare
both seats at −5, 0 and +5 ms defender phase offsets.

| Newly captured clock RTT | All modeled pairs | Equal-count pairs | Plain W-tap doubles, equal count | Delay doubles, equal count | Mean margin change vs W-tap, equal count | Gain / tie / loss, equal count |
|---|---:|---:|---:|---:|---:|---:|
| 0 ms | 24 | 12 | 0 | 0 | +0.167 | 2 / 10 / 0 |
| 20 ms | 24 | 21 | 0 | 0 | +0.048 | 2 / 18 / 1 |
| 80 ms | 24 | 21 | 1 | 0 | −0.667 | 4 / 11 / 6 |
| All | 72 | 54 | 1 | 0 | −0.204 | 8 / 39 / 7 |

Including unequal counts, the mean score gain is −0.333
over all 72 pairs and −0.625 at 80 ms. The fresh-clock
policy fails the transfer comparison. New native play
in this cohort supplies only W-tap evidence. Both arms
of the hold comparison run in the simulator.

On ten previously surviving m7421 A phase variants,
swap just the horizontal speed used for the defender's
first travel after the initial hit. The two arms retain
their full physical press timestamps. Putting the 60%
brake back into the delayed trajectory removes all ten
doubles, leaving 5–5 each time. Giving ordinary W-tap's
defender the full launch without the click delay gives
either 5–6 or 5–5. The beneficial timing combines the
changed hit order and full first recoil in this fixture.

In the largest equal-count loss, m7429 B at defender
phase −5 ms, both arms take the opening hit at 475.836
ms. Plain W-tap responds at 524.081 ms and leaves the
defender at a 3.09772-block ray at its 906.003 ms
click. Adding the hold moves the first response to
568.839 ms. That same opposing click now has a
2.15870-block ray and the defender scores at 970.561
ms. W-tap's five unanswered follow-ups become a 5–5
opening, down from 7–1. Both natural arms' defenders
take the full first recoil. Forcing the 60% brake takes
W-tap from 7–1 to 5–4 and the delayed arm from 5–5 to
7–4. The opposite +5 ms phase has a delay gain of two
with a natural defender brake. Removing it drops that
arm from 8–6 to 5–6. The two phase proposals have the
same own trigger time and their visible hitbox ranges
differ by 0.007 blocks. These comparisons establish why
recoil and positioning after the opening matter even
when the own-view click trigger barely changes. Mark
m7429 as development for later rules after this diagnosis.

[Frozen decision and capture schedule](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/local-first-poll/protocol.json) ·
[Twelve native mirror scores and coverage](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/local-first-poll/native-exports.json) ·
[Native W-tap replay comparison](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/local-first-poll/native-score-parity.json) ·
[Paired model scorecard](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/local-first-poll/with-prospective-summary.json) ·
[m7421 recoil intervention](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/local-first-poll/recoil-swap/summary.json) ·
[m7429 contact and score trace](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/local-first-poll/recoil-swap/prospective-contact-trace.json)

#### Changing the clock before the first contact

Hold exactly one of the first six cap-ready clicks when
the opponent's visible hitbox is beyond 3.2 blocks and
the ray cannot click them. The candidate can arm during
the first 550 ms, before either hurt cue and before any
cap-ready target ray. Test requested holds of 4–40 ms.
Ordinary input polling decides when the hold actually
ends. Keep all movement and the 20 CPS cap. Each paired
comparison uses the same spawn, both host calendars,
wire estimates and 3.1 s endpoint. Grade two fresh hits
before the first return, including after the first quiet
bilateral trade. Count a gain only with a longer qualifying
chain and an improved accepted margin. The primary
subset retains both players' exact press counts.

| Exposed panel | Starts | Plain W-tap opening doubles | Starts with an extra equal-count double available in hindsight | Clock and seat groups with one fixed double gain in all five phases |
|---|---:|---:|---:|---:|
| Development | 120 | 0 | 5 | 1 |
| Comparison | 120 | 4 | 0 | 0 |
| Later exposed | 120 | 0 | 0 | 0 |
| Recent mirrors excluding diagnostic m7429 | 110 | 1 | 0 | 0 |
| m7429 diagnosis | 10 | 1 | 1 | 0 |

The five consistent gains come from m7378 B at 20 ms.
The fifth press held for 16 or 20 ms generates the same
admission on the nominal clock. Both arms have 48 own
and 47 defender presses through 3.1 s. W-tap's press at
411.637 ms becomes an air swing at 422.514 ms. Holding
it sends at 431.863 ms and consumes at 432.430 ms, after
the local view has moved the target into reach. Its
2.42947-block ray credits at 444.591 ms. W-tap's first
credit is at 546.480 ms. The candidate lands again at
942.965 ms before the return at 994.604 ms. The margin
moves from 3–3 to 3–2.

Keep this sequence and three other short-score examples
frozen. Cross five own and five defender phase proposals
with the pooled fit and four 2.7 s delay blocks from each
other same-RTT development recording. The block data
are stressed from spawn. Across all profiles, both arms
keep the same physical counts within every pair.

| Selected clock and seat | Transport profiles | W-tap doubles in stress pairs | Joint phases with a new double in every profile, of 25 | Joint phases with score gain in every profile, of 25 | Mean margin gain by profile, range |
|---|---:|---:|---:|---:|---:|
| m7378 B, 20 ms | 13 | 0 | 10 | 10 | +0.60 to +1.16 |
| m7413 B, 80 ms | 17 | 0 | 0 | 22 | +3.92 to +5.76 |
| m7427 B, 80 ms | 17 | 0 | 0 | 0 | −0.08 to +1.56 |
| m7430 A, 0 ms | 17 | 0 | 0 | 1 | +0.08 to +0.60 |

On m7378 the nominal attack is ready at 443.562 ms,
only 1.029 ms before the scoring drain. Adding 1.2 ms
to that packet alone preserves the click and eliminates
the double. With the own calendar two milliseconds
later, the same ray connects with readiness 445.919 ms
and misses that drain. Advancing that one packet by
1.2 ms leaves it too late. Advancing it by 1.6 ms
restores the two hits and the 3–2 score. W-tap and
both sides' complete press histories reproduce in
each comparison. On m7413, the nominal scores are
3–4 and 8–3, with the original first two hits on the
same drains. The shifted cadence supplies another
immune contact and earlier subsequent accepted damage.
The evidence supports specific short openings under
shared clock conditions. Broad native transfer and the
first-to-100 gate remain open.

[Frozen cadence screen](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/protocol.json) ·
[All 480 starts](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/summary.json) ·
[Joint timing and transport stress](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/targeted-stress/summary.json) ·
[Nominal press, ray and credit traces](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/targeted-stress/nominal-contact-traces.json) ·
[Single-packet loss](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/targeted-stress/one-attack/summary.json) ·
[Single-packet restoration](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/targeted-stress/one-attack/restore.json)

#### Anchoring the early press to the original recording

The four-second m7378 transport replay executes all 1,381 event
rows. The 160 client movement, 160 interpolation and 80 server
snapshots agree exactly when supplied the recorded inputs and
transport. The generated W-tap arms below start from the same
seed and schedules, with their own input and packet generation.
They reproduce the first four factual credits. The last two
factual credits share the 2,401.894 ms drain. The generated
W-tap credits share the 2,752.703 ms drain instead.

The original B click misses at a 3.714-block ray and sets miss
cooldown to ten. At the next native attack loop the recorded
geometry still gives 3.072 blocks. At the loop after that it
gives 2.429 blocks, exactly as in the delayed model. Both later
loops had no factual press. Three B tick starts fall between
421.355 and 432.244 ms, following a 79.621 ms gap. An ordinary
50 ms spacing between those client ticks would give a different
delay-to-consumption mapping.

Use either zero completion times, the single recorded A outlier,
or all exact costs for factual polls that also admit in the
simulated arm. At polls that did not admit in the recording,
either use zero or sample a fixed empirical same-side and
same-source quantile. The replaced B native poll has 0.566 ms
before its scheduled attack loop. The median native-poll cost
from those B samples is 0.039 ms. The maximum of five is
5.425 ms. That maximum also supplies the nearest-rank P90.

| Shared input completion costs | W-tap score / B and A presses | Delayed score / B and A presses | First delayed B credit | Delayed opening fresh chain |
|---|---|---|---:|---:|
| Zero or the one recorded A outlier | 3–3 / 48 and 47 | 3–2 / 48 and 47 | 444.591 ms | 2 |
| Recorded, plus zero or source median at new polls | 3–3 / 48 and 46 | 3–2 / 48 and 46 | 444.591 ms | 2 |
| Recorded, plus source P90 or max at new polls | 3–3 / 48 and 46 | 3–3 / 48 and 46 | 501.412 ms | 1 |

The native W-tap match has 48 and 46 presses through this horizon.
In the slow-call probe the new press still has the same 2.429-block
ray, but blocking its client loop moves packet readiness to
448.778 ms. The target drain ran from 444.591 through 446.084 ms.
At the original attack loop there are 12.161 ms to the drain.
The measured attack-loop-to-queue delays on the other three
20 ms clocks fit that budget in 346 of 404 recorded contacts.
Those are correlated timings from ordinary attacks, and the slow
call bound comes from a small sample. Keep the hit and double
conditional. The new input was simulated throughout.

[Native reconstruction](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/native-anchor/strict-native.json) ·
[Recorded geometry and paired completion probes](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/native-anchor/summary.json)

#### Varying each player's RTT independently

Use the same six exposed native timing tapes at every pair of
0, 20 and 80 ms added RTT. Reuse their server and client clocks,
tracker seeds, and source-clock transport residual estimates.
Both seats start six blocks apart and use ordinary W-tap movement
and the 20 CPS cap. The alternative waits for the local incoming
hurt cue plus 0–200 ms on a 5 ms grid, with an 1,100 ms fallback.
Score a strict gain when the opponent hits first, a fresh reply
within 275 ms starts an unanswered double, and the alternative
beats the W-tap margin through 3.1 seconds. W-tap must have no
opening or reply double. The same delay must pass nominal timing
and each separate ±5 ms shift of either client's opportunities to
count as stable. The 12 unshifted controls at their source RTT
reproduce the earlier model's opening credits, scores and counts.

| Added RTT, own / opponent | W-tap nominal double starts, of 12 | Starts with a delay gain at nominal timing, of 12 | Starts with one fixed delay gain across all five timings, of 12 |
|---|---:|---:|---:|
| 0 / 0 | 0 | 3 | 0 |
| 0 / 20 | 0 | 3 | 0 |
| 0 / 80 | 1 | 4 | 1 |
| 20 / 0 | 2 | 1 | 0 |
| 20 / 20 | 2 | 0 | 0 |
| 20 / 80 | 0 | 3 | 1 |
| 80 / 0 | 1 | 1 | 0 |
| 80 / 20 | 1 | 1 | 0 |
| 80 / 80 | 1 | 0 | 0 |

Both stable starts are on m7380. At 0/80, seat A has a 70–100 ms
band and a separate 20 ms point, all landing a 196.390 ms server
reply in all five phases. At 20/80, seat B has a 25–135 ms band.
All those waits reply at 199.878 ms in four phases and at
255.202 ms in the +5 ms defender phase. The broad release band
maps to the same scoring drain within each tested phase. Its
nominal accepted margin is +2 or +5 versus zero for W-tap, with
39–41 own presses versus 49. Every successful nominal wait at
every pair changes the physical totals. Treat this as a
same-cap model sensitivity check, separately from the matched
physical-count evidence above and native first-to-100 tests.

A hurt-cue-to-reply loop consumes roughly one own RTT in
downlink and uplink, plus local admission and server queue
rounding. At a fixed server reply delay, extra victim RTT on
the opponent's side also postpones their local launch. Both
receipt timing and altered spacing affect their return range.
The lab results show clock-specific windows and do not recover
a general window that increases with either player's RTT.

[Frozen RTT design](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/independent-rtt/protocol.json) ·
[RTT comparison and winning delay bands](../../build/boxing-double-search/v4-reset-confirmation/release-window/native-recoil/native-model-divergence/predictive/earlier-click-cadence/independent-rtt/summary.json)

[Model screen and witness trace](BoxingTradeSkills.java) ·
[Matched jump forks](BoxingJumpForks.java) ·
[Native skill comparisons](boxing_trade_skills_native.py) ·
[Native click and launch audit](boxing_opening_audit.py) ·
[Recoil forks](BoxingOpeningRecoil.java) ·
[Native swing timing audit](boxing_swing_audit.py) ·
[Pre-counter footwork screen](BoxingOpeningFootwork.java) ·
[Post-counter movement forks](BoxingAfterCounterEnvelope.java) ·
[Grounded approach screen](BoxingApproachStudy.java) ·
[Current first-to-100 benchmark](boxing-double-findings.md)
