# Combo research: current objective and user guidance

This is the working brief for future sessions. The latest clarification below
supersedes the earlier continuous-own-reach interpretation. Keep historical
measurements and their original definitions; do not silently relabel them.


## Sumo research default, November 20

Use a matched **7 CPS ceiling for both players** in new sumo league development and primary evaluation. Keep the recorded MMC floor and independent per-player RTTs. Report the measured press intervals. Historical and already scheduled cap comparisons retain their original settings. Nomophobia is a development opponent. Independent elite human duels remain the final external test.

Use the [active sumo roster](elite-league/sumo-inputs/archive-field.tsv) for new league work. W-tap and Hold-W are the baselines. A click-timed W-tap is the only additional hand-coded training opponent. The rest are learned checkpoints. Reproduce the original 257-opponent benchmark using its [frozen roster](elite-league/sumo-inputs/historical-field-257.tsv) and the pre-cleanup native policy snapshot at `cf38fd9e`.

## November 19 boxing goal and grading

The primary grade is the bot's final accepted-hit score minus plain W-tap's
score in ordinary first-to-100 native MMC boxing at matched CPS. A 100–70 win
scores +30. Maximize that margin across independent matches on both seats and
multiple latency settings. Report the per-match scores and the median and
spread by CPS and latency. Include actual click rates, the win record,
fresh hits, damage upgrades, and doubles as supporting measurements.

First establish where hit selection works consistently against W-tap. Vary
starting spacing, momentum, hurt and landing phase, and click and packet
timing. Determine when delaying the first hit makes the selector land before
the opponent and start a combo. Preserve the failure boundary as well as
repeatable starts. Once those starts repeat across a range of conditions,
teach a policy to recognize and create them using only its own client
observations, then measure the score margin in full boxing matches. Isolate
jump escapes separately and measure whether they avoid or shorten the
opponent's combo before combining skills. Retain the matched established-combo
drill and its separate promotion gate.

The [current-preset isolated jump](elite-league/boxing-double-findings.md#native-first-to-100-on-the-current-range-preset)
won all four native 80 ms bouts with a +25 median score, versus
three of four and +3 for W-tap. Both seats improved in that
small panel. Equal 0 and 20 ms native trials did not corroborate
the positive results from 110 common-clock model pairs.
At 80 ms the first incoming unanswered-run distribution was
identical in the two native arms. Keep the standalone regulation
effect and proof of an individual escape separate. The scorer
links five takeoffs to self-velocity packets outside fresh melee.
Retain the one truncated click trace in the scoreboard cohort.

The [fresh isolated jump replication](elite-league/boxing-double-findings.md#fresh-80-ms-repeats-and-exact-first-impact-forks)
wins 7/8 at 80 ms with median +13, compared with 6/8 and
+15 for W-tap. Its median leads in B and trails in A. It
concedes multiple hits in five first incoming runs versus
two for W-tap. Across 88 qualified first-impact forks and
nine route settings, one jump shortens 37 incoming runs and
extends 136. In 89 of 109 newly multi-hit runs, the same
opponent press still scores and the jump delays the reply.
The new [second-hit forks](elite-league/boxing-double-findings.md#a-jump-after-two-unanswered-fresh-hits)
shorten 24 runs and extend zero in 54 timings of the six
genuinely open starts. Only one start improves the run
across all nine timings. Two visible own hurt statuses catch
all 16 open grounded second-hit threats in the new mirrors,
with 21 false alarms, including 20 already-scored replies.
Separate the timing of one's own pending contact from the
visible hurt tally before testing a selective late escape
or any integration. The full regulation and separate
maintenance promotion gates remain in force.

The [frozen reply-timing comparison](elite-league/boxing-double-findings.md#fresh-mirrors-and-the-regulation-tradeoff)
waits for the local reply contact to be at least half RTT
plus 24 ms old and no more than RTT plus 25 ms before the
latest own hurt cue. It preserves 38/40 true calls and reduces
the visible rule's 33 false calls to four on 14 complete new
mirror traces. Both jump rules shorten 81 of 495 exact
first-six replay variants without extending one. The guard
skips 17 of 55 calls, including effects that sometimes help
later scoring after a reply has already landed. On 160 fresh
common-clock first-to-100s, W-tap, visible jump and guarded
jump win 80, 124 and 116 with median margins 0, +7 and +4.
All 126/119/111 K3/K5/K10 model continuations tie W-tap.
Use the simpler visible jump for the separately frozen
24-bout native comparison with an observation-only W-tap
arm. The regulation, hit-selection and maintenance promotion
gates remain distinct.

The [24 native second-hurt jump bouts](elite-league/boxing-double-findings.md#native-second-hurt-jump-regulation)
finish 6/12 for both arms. At 80 ms both arms go 2/4 with
median +1.5. The native B jump goes 0/2 despite the model's
larger expected gain in that seat. At 20 ms the jump arm
has +7.5 versus W-tap's −2.5 on all scoreboards, and +5
versus −7 in the qualified subset. Its two qualified wins
are both from seat A. Two shared server stalls and one
terminal contact gap disqualify six bouts from clock/click
comparison. The remaining physical CPS differ by at most
0.246 within a bout. All 87 local activation decisions
reconstruct, including 48 actual takeoffs and no activation
before a return in an own K3+ streak during regulation.
This cue alone does not justify integration. Use matched
native-state replay branches to diagnose the seat/latency
split before further controller work.

The [matched activation forks](elite-league/boxing-double-findings.md#native-second-hurt-jump-regulation)
reproduce all 49 requested qualified native starts and
test nine timing variants each. Jumping shortens 36
runs and lengthens zero, but yields zero median accepted
gain at each RTT. At 80 ms the only timing-stable shorter
run is a W-tap seat-A example, m7358, and still concedes
two hits from the anchor. The four strong examples all
come from W-tap control states. Do not infer from this
that the jump arm visits the same opportunities in full
bouts. Preserve the whole-bout seat split, keep the cue
separate from attacking hit selection, and address the
server's cold/overlapping arena stalls before a further
native timing promotion.

The [one-press hit-selection forks](elite-league/boxing-trade-skills.md#skipping-the-first-contact-on-a-fast-approach)
hold both physical click calendars fixed. Two of 31 native starts give
a new double and +1 accepted margin throughout the 25 timings when
the first effective contact is skipped. At 20 ms, m7346's first own
hit arrives 47 ms later and its landing lead grows from 21 to 70 ms.
The new double and +1 survive all 65 widened timing settings. The
80 ms m7340 gains in 56/65; a 10 ms own-upload delay gives a double
and loses three points. Its 8° aim offset helps by skipping contact,
with the same timing results under a centered single-press miss.
Neither witness demonstrates a sustained combo.

The frozen own-view gate (fast grounded approach, 3.70–4.10 blocks
to the displayed hitbox, first selected contact within 140 ms) finds
neither a timing-stable new double with a gain in 20 other starts nor
a positive median margin. Its 52 gains and 98 losses span −4 to +2
over 500 paired timings from nine earlier native W-tap observer bouts.
At 0 ms it has 64 losses and no gains. In m7359 the skipped contact
would have braked an already-applied sprint launch from 0.9495 to
0.5697, so the proposed wait removes our next chance to hit. In
m7375 two successive presses land on the same server interval, so
skipping one provides essentially no delay. These outcomes justify
testing the receipt of own recoil and whether a delay changes the
server scoring interval before another hit-selection rule. Do not
promote the gate or fit again on the comparison clocks and call them
sealed. The native score and separate maintenance gates still apply.

The [recoil guard and 35 ms hold test](elite-league/boxing-trade-skills.md#guarding-the-recoil-brake-and-enforcing-a-real-delay)
rescues m7359's brake and changes m7375's same-server-tick skip
into a two-tick delay with a new double and a point in all 65
widened timings. The 12 fresh native mirrors give 19 exact model
starts. Guarded hold gains 124 variants and loses 59, versus 124
gains and 134 losses without the guard. Paired mean margins at
0/20/80 ms are −0.50/+0.17/+1.22, with median zero overall. The
35 ms minimum changes no prospective outcome beyond one skip.
At 80 ms the improvements come from B. Fresh m7386 B changes the
landing lead from −34 to +69 ms, gains 1–3 points in all 65 variants,
and adds a double in 64. Each of three newly observed recoil guards
is available by the actual input head and arrives only after the
original frame-issued press was queued. Test the cost of moving
that ordinary input decision to the native head against W-tap at
the same cadence before considering a live selector. Keep full
native first-to-100, separate maintenance and jump gates intact.

The [client-input admission experiment](elite-league/boxing-trade-skills.md#deciding-at-the-client-input-tick)
reuses the fast grounded approach gate. In 114 changed common-clock
first-to-100s, moving the first eligible contact decision from a
render poll to input retains its original consume tick. Physical
injection moves a median 16.94 ms and the subject's physical rate
changes by median −0.011 CPS. Keeping the very next stock admission
would violate the same cap in 66 variants, and all 66 are contacts.
The first changed accepted point occurs a median 4.75 s later among
70 changed common horizons. Phase changes matter even at matched CPS.
The model-only recoil-guarded hold against equally scheduled W-tap
wins 54/120 versus 63/120, with no donor-and-seat pair improving
in all five variants. It fails the current promotion comparison.

The [native admission controls](elite-league/boxing-trade-skills.md#deciding-at-the-client-input-tick)
complete all 24 first-to-100 bouts. The ordinary control wins 6/12
with median 0, and input-tick W-tap wins 8/12 with median +2.5.
The two largest wins occur with no actual deferral, so the score
spread does not establish an admission advantage. All 25 frame
deferrals preserve the contact in the expected attack loop. Three
receive own velocity between the first frame and input selection,
and each keeps the 0.9495→0.5697 horizontal recoil brake. Actual
median subject rates are 17.469 and 17.436, maximum within-bout
gap 0.242 CPS. The input boundary is usable mechanically. The
guarded wait itself fails the matched model score gate, so keep
the native cadence evidence and diagnose the delayed attack
before changing the playing policy. Preserve maintenance and
jump isolation.

The [first-hold and follow-up diagnosis](elite-league/boxing-trade-skills.md#where-the-input-tick-hold-gains-or-loses-the-exchange)
preserves the full 120 earlier outcomes. Only 12 of 56 worse
whole bouts are already behind at 1.8 seconds. Of 46 one- or
two-drain late replies that make us land first, 14 give an
unanswered double and 16 have no own contact in either of the
first two fresh drains. A further eight trade the actual follow-up
drain, and six take returns before the next fresh window. The
fixed single 55 ms wait averages +0.24/+0.51 and 75 ms +0.19/+0.64
in the short model across development and separate observer clocks.
No duration gives a new double and score gain at every phase of any
complete donor/seat set. The 55 ms whole-bout paired median is
−2.5/+2 across those panels. Short transfer is insufficient for
further playing-policy promotion.

A first outgoing horizontal impulse clamp to normal strength
rescues eight of 16 late-window reach failures and adds 11 doubles,
yet improves short score in three. It breaks all 27 wait doubles
in the two panels. Twenty-six of those receive a return before
their next fresh window. Intermediate 0.75 gives +0.102/+0.126
short score versus the unmodified wait, without a phase-stable
new double and gain. This is an offline shot-impulse diagnostic,
not an ordinary input policy. All original delayed shots apply
full 0.9495; the test points toward creating wider release geometry
and managing defender reach together, beyond picking a better
duration. Keep the native regulation, maintenance and jump gates.

The [withheld-input footwork test](elite-league/boxing-trade-skills.md#footwork-at-the-withheld-input)
tried 28 short movement sequences at three wait durations across
211 exact model forks. No fixed sequence adds a clean double
and points against both W-tap and the wait control across all
five phases of any complete donor/seat set. Maximum first-reply
separation at impact is 2.187 blocks, below the 3-block threshold
for weaker sprint hits. The lone ordered-double witness, m7392 A,
removes sprint to land a normal reply in the same drain as W-tap's
first hit. It gains short points in 90/99 fixed-state timing forks,
with no losses, and in 99/153 when timing can also change the
approach, with 42 losses. It never gains a clean double in either
stress panel. Treat it as a trade recovery. For this hit-selection
route, establish reach and favorable initial order before the hold
instead of retuning late footwork. These clocks were previously
exposed. Native score and separate maintenance gates still apply.

The [earlier grounded approach forks](elite-league/boxing-trade-skills.md#moving-before-the-first-attack-decision)
use 627 cuts and three local distance bands before the first attack
decision. At 20 ms, three backsteps followed by sprint on m7378 A
give a new clean double and +1–3 points in all 85 wide timing
variants. Seventy-four give a three-hit chain. The mirror at the
same cap gets zero in both measures. More reliably scoring the
opening is not the mechanism. Nominally W-tap still lands first and
our reply applies full sprint knockback. Holding the first two
accepted hit times and the landing times fixed, the best tested
click-only delay leaves a 0.442-block gap, compared to 1.783 with
backsteps. At the same follow-up defender input the legal ray is
2.251 vs 3.837 blocks. The attacker gets three unanswered hits with
backsteps and one with the matched time-only delay. All tested
backstep timings on this seat beat or tie the best matched delay.
Blind backsteps average −1.21/−1.45 short points in the same local
distance band across the two exposed panels. Before another input
feature, identify a client-observable approach condition that
distinguishes the favorable gap after reply from the losing cases.
No native or separate maintenance promotion is supported yet.

The [approach recognition test](elite-league/boxing-trade-skills.md#recognizing-the-approach-from-our-client)
freezes a small ranking rule on 12 earlier donor calendars, with
up to four approaches per visible distance band. Exact W-tap pairs
keep the same physical presses. The history rule averages −0.137
points when holding out whole training calendars and −0.002 on
764 forks from 12 prospective native mirror clocks. The simpler
snapshot rule gets +0.006 on those prospective clocks after −0.036
in the full-calendar folds. Neither clears the gate. Keep m7414–25
separate as prospective evidence for these frozen rules. With the
outcome known, these short movement choices offer +0.281, so the
action set occasionally helps. Across 85 exact starts in the old
panels, a preset backstep gains and loses under different future
times in 22. M7407 changes from −1 to +1 when future defender
attack uploads take 5 ms longer, despite identical first hit times.
Label robust timing windows from matched branches, or test a later
own-observation decision after a new cue resolves some of the
uncertainty. Avoid more features on the failed frozen gate. Native
regulation, separate maintenance and the jump comparison stay open.

The [pursuit after the bilateral hurt cue](elite-league/boxing-trade-skills.md#pursuit-after-both-hurt-cues)
screen finds mean short gains from both 80 ms seats in all three
exposed panels with two immediate sprint ticks. At 80 ms the full
repeated policy's paired first-to-100 means are +17.83/+2.73/−1.73,
with medians +13.5/−1/+2; it loses mean margin in comparison and
latest seat B. First-pulse-only leaves latest seat B at −0.90.
All 99/77/58 K3/K5/K10 model continuations tie W-tap on both
endpoints. Preserve all score spread and the timing-period repeats.
No policy passes the general score gate. On the m7407 20 ms witness,
swapping only incoming horizontal S12 .93875↔.89350 swaps the
entire later hit list in both original timing branches. One sprint
tick after both cues recovers the larger recoil but erases the
weaker branch's gain. All aim/click cadence controls are unchanged.
Investigate prediction of the necessary pursuit from received
recoil and projected reach before adding a rule. Keep native,
maintenance and jump comparisons separate.

The [follow-up reach and packet audit](elite-league/boxing-trade-skills.md#forecasting-reach-and-the-arrival-of-the-next-attack)
reproduces all 4,059 earlier W-tap/one/two-sprint arms. Own-view estimates
miss the original fresh-window time by 15–19 ms MAE and distance by
1.54–1.84 blocks. Supplying the exact deadline alone barely changes
the range error. Exact bodies and replicas plus actual future contact
braking still leave 0.62–1.00 blocks MAE on the nominal rollout. A
gate with future knowledge of geometrical reach gains +.054/.011/.033
short points across the three panels versus plain W-tap. Using future
selected contacts in the first two eligible server drains gains
+.080/.111/.110 instead. The two-tick pulse adds an own early contact
without adding a defender early contact in 105 forks, of which 75
gain, 25 tie and five lose points. Use that timing mechanism for
the next controlled forecast. The current nominal gate activates
once and ties. No live policy or maintenance promotion is supported.

The [late press forecast](elite-league/boxing-trade-skills.md#judging-a-click-near-the-first-fresh-window)
correctly calls 320 of 363 timely attack packets in later native W-tap
mirrors, with 43 false calls. It estimates the first eligible drain to
3.97 ms MAE at factual admissions. Remembering a predicted earlier
scorer for the same hurt cue raises fresh-hit precision from 54% to
72% while lowering recall from 87% to 82%. At the earlier trade cue,
shallow trees' predicted change from a sprint pulse has AUROC 0.50
for improving the paired contact count on both comparison panels.
The [one-tick click deferral](elite-league/boxing-trade-skills.md#deferring-the-click-immediately-before-fresh-damage)
adds zero unanswered follow-ups in 1,353 exact forks. With the true
pre-fresh drain it gives five score gains and 23 losses, including
19 losses of a stock pre-fresh damage upgrade. Stock already has a
timely follow-up packet in 111 of the 121 exact activations. In later
native mirrors, 155 of 167 comparable pre-fresh contact windows
already score fresh in the next two drains, including 16 windows
where both the preceding upgrade and fresh hit score. Stop tuning
this hold. The timing signal can still diagnose whether approach or
spacing creates a reach opportunity. Preserve accepted upgrades,
physical cadence, and the separate maintenance and jump gates.

The [first-recoil brake stress test](elite-league/boxing-trade-skills.md#testing-the-best-approach-examples-against-an-early-recoil-brake)
reproduces all 665 selected wider approach variants. Applying 60%
of the defender's received horizontal impulse at its first step
reduces strict new doubles from 379 to 60 and new three-hit chains
from 275 to zero. Stock W-tap has 61/0 and 0/0, respectively, under
the same control. All eight selected examples lose all-phase
qualification. Across the broader 940-cut matched screen no
complete five-timing family clears the strict gate with the
brake, compared with four of 175 naturally. At 80 ms m7389 A
keeps a 2.957-block return ray under the brake. Five ms of
defender upload moves that return out of the follow-up drain,
creating a double at the same short score. Score denial by ray or
contact separately from favorable drain ordering. In 24 native
W-tap mirrors, 16/336 quiet bilateral trades have this first-step
brake on the defender after the reply; none give the replying
side a clean follow-up, compared with 39/320 on full recoil.
The native cohort is descriptive. Preserve the regulation,
maintenance and jump gates.

The [return-packet timing test](elite-league/boxing-trade-skills.md#checking-whether-a-clean-double-denied-the-return)
isolates all 60 selected score-gaining doubles under the first-step
brake. A defender-only C02 advance of 2/5/10/20 ms erases
5/16/24/26. Every loss is the same physical return crossing the
follow-up drain. The 34 at the 20 ms tier are all m7378 B.
The average short score gain versus matched W-tap falls from
+1.03 to +0.02. All 14 broader movement doubles and all 24 broader
W-tap doubles survive the sweep. In m7378 B another 33 with
real sampled reach denial keep the same +1 then tie or lose
to the matched W-tap short finish, so clean doubles alone
are an insufficient training label. Two of 39 archived native
W-tap doubles also have an accepted return already in flight
at the follow-up start, landing on the next drain. Distinguish
range denial and transport luck in the lab and compare later
margin against W-tap on the same perturbed clock.

The [next-exchange movement test](elite-league/boxing-trade-skills.md#when-movement-can-protect-a-double)
reproduces 104 distinct scored doubles. The first own head with
the second hurt cue comes after the return click in all 67 m7378 B
forks, and after the return's wire send in 20 of 32 linked native
W-tap doubles. The late one-step diagonal improves the 3 s score
in 14 m7378 B starts, ties 49 and loses four while leaving the
first return unchanged. A distinct early two-tick forward release
on m7385 A shifts the second hit one drain later and pushes the
old defender ray from 2.617 to 3.030 blocks. From the braked
first step, it creates a three-hit chain in 44/85 variants
versus 0/85 for W-tap, with +1.66 mean short margin. With
natural recoil the same word gives 0/85 for both and +0.24 mean
margin, median zero. All gains in the initial screen came from
three phase variants on this one clock, repeated at three cuts.
Use the actual reach gap on re-entry to explain the transfer
difference before adding a rule. Native regulation, maintenance
and isolated jump comparisons remain separate.

The [natural re-entry screen](elite-league/boxing-trade-skills.md#testing-the-re-entry-release-on-naturally-occurring-trades)
compares the same two forward releases in 1,263 exact bilateral
W-tap states. It keeps identical physical presses, changes clean
follow-ups from 230 to 132 and lowers mean accepted margin in
all three panels. In all 145 lost clean follow-ups the same own
press exceeds reach after the release, with 117 unchanged first
returns. In 45 of the 47 gains the defender's original scoring
press is beyond reach. The frozen m7385 local band adds no clean
follow-up and occurs in four of 139 linked native samples.
The [m7385 recoil sweep](elite-league/boxing-trade-skills.md#how-much-first-step-recoil-the-delay-needs)
finds zero release chains at the equivalent normal-hit 0.5275
horizontal first step, versus 44 at 0.5697. Stock W-tap alone
reproduces those same 44 chains, timings and scores at 0.66465.
The fixed move repositions a narrow pre-press interval and
does not give repeatable advantage given varied initial flight.
Preserve the naturally paired geometry diagnosis and native,
maintenance and jump gates.

The [one-tick re-entry comparison](elite-league/boxing-trade-skills.md#a-one-tick-re-entry-release)
reproduces all 1,263 natural cuts with unchanged physical presses.
Delaying future attack delivery by 5 ms in either direction
leaves only one new clean-and-score gain stable across all three
futures. A privileged score oracle can improve minimum paired
margin in 54/63/68 cases, while the own-view trees either tie
W-tap or fail across comparison panels. On that [m7422 A
start](elite-league/boxing-trade-skills.md#a-natural-four-hit-re-entry-on-m7422)
the natural one-tick release keeps the original own scoring
click and moves the original defender ray from 2.758 to 3.024.
It denies that return in all 55 wider same-state timing forks
and adds four unanswered hits and +4 points in 38. Plain
W-tap has no such clean follow-up there. Keep seven score
losses and the earlier acquisition phase differences. The
frozen own-view band has zero robust clean gains in 27
other-clock starts. This supplies a natural reach-denial
witness and leaves repeatable recognition and native
regulation open. Maintenance and jumps remain separate.

The [first own landing comparison](elite-league/boxing-trade-skills.md#timing-the-re-entry-release-from-the-first-own-landing)
enrolls 1,353 quiet bilateral trades and preserves both physical
click schedules. The first grounded release averages −0.063,
−0.167 and −0.027 points across the three exposed panels. It adds
106 first-hit leads after the bilateral cut and loses 176; in 174
of those losses the original own scoring press is outside reach.
Only two of the 106 new leads get multiple hits before a return.
The m7422 four-hit witness reproduces. In a second exposed
witness, the 80 ms m7388 A encounter, the release preserves the
original own scoring drain and denies the original defender ray
in 40 of 55 widened timings. Of 45 leads with the landing-plus-one
release, all 45 take returns from replacement clicks consumed
before the new knockback applies. Twenty-eight gain points with
a new first-hit lead and none add a second unanswered hit.
Separate original-shot denial from protection against the full
set of return opportunities. The new 850 ms clock starts at
bilateral enrollment; do not apply it to the prior 2.2 s witness
at cue age 450. The movement outcomes are model-only. Native
regulation, maintenance and jump remain separate gates.

The [replacement-click footwork search](elite-league/boxing-trade-skills.md#footwork-against-the-defenders-replacement-click)
tests 73,008 exact same-click continuations in 25 replacement
return cuts and one four-hit reference. Ten sequences add a
double and points in all three initial timings of one zero-RTT
state, m7398 B. The simplest adds one backstep. The other
uses right strafe then sprint-right. They gain in the same
33 of 55 widened timings, with two and three uninterrupted
hits respectively. In the nominal case the backstep keeps
our original 2.988-block shot and forces the replacement
from 2.668 to 3.141 blocks. With defender opportunities 2 ms
later, both physical poses at the click are unchanged, but
its copy of the attacker is 0.177 blocks closer. The same
shot connects at 2.964 and the double disappears at every
tested upload. The lateral denial also disappears, and its
nominal nearest-edge distance was already within reach.
The frozen moves lose average margin on all three broader
exposed panels. Six other zero-RTT encounters give limited
same-cut gains under wider timing, most 40/55 on m7385 A.
No 20 or 80 ms case in the focused search adds a double.
Future reach estimates need to account for how differently
the defender can be interpolating our movement at the same
press. Keep native regulation, maintenance and jump separate.

The [full replacement timing grid](elite-league/boxing-trade-skills.md#holding-the-same-movement-across-defender-view-timing)
reproduces 73,008 earlier branches and crosses 936 movement
sequences with 55 futures in 32 exposed exact starts. The best
word per cut gains a double and points in 205/440 zero-RTT
futures. The timing oracle expands that to 216/440. No word
qualifies across all 55 of any cut, and no tested 20 or 80 ms
future qualifies even with hindsight. On m7398, advancing
one defender tracker packet by 0.15 ms makes it catch the
earlier main-thread drain and removes both chains with the
same click schedules and first own score. Delaying the same
packet by 2.25 ms restores both chains on the later client
schedule. In 24 archived native mirrors, 1,849 of 28,999
linked first interpolation steps have >0.1-block horizontal
travel, a wire receipt within 2 ms of a drain, and an
interpolation opportunity between that drain and its
neighbor across receipt. This is frequency evidence for
the timing boundary, not a count of changed hit outcomes.

The simple m7384 B walk/backstep after the release keeps the
first score and generates 3–4 unanswered hits and +3/+4
points in 54/55 exact zero-RTT futures. In the only loss of
the advantage, our still-in-range attack packet misses its
server drain by 0.350 ms. The defender gets a replacement
click after receipt of our knockback and before its first
movement. The frozen move averages −0.124/−0.276/−0.177
across the three exposed comparison panels after removing
m7384. No live promotion follows. Address both own attack
drain timing and defender view timing in subsequent
baseline-grounded diagnosis. Preserve the independent
native regulation, maintenance and jump comparisons.

The [score persistence comparison](elite-league/boxing-trade-skills.md#when-a-short-trade-gain-lasts-through-the-match)
selects five positive-RTT starts that had nonnegative 2.7 s
score across the 55 existing futures. The frozen moves gain
357 accepted points over W-tap across 275 variants: 167 fresh
and 190 upgrades. Only four of 181 winning cuts are ahead
at 1.35 s. Mean paired margin +1.298 at 2.7 s falls to −0.065
at 8 s. The two 20 ms examples retain gains at 8 s, while
all three 80 ms examples reverse on average. In first-to-100
paired model continuations from those same states, both arms
finish all 275. The combined move wins 113 against W-tap's
125, with −8.59 average paired margin. Dropping the first
release wins 113 and averages −4.46. The nominal m7410 model
mirror goes 100–27 on a 71-hit uninterrupted run, while the
move goes 95–100 after its short gain. The factual donor
finishes 100–99 from B with longest streak nine. The paired
model score includes possible calendar cycling. Both scoring trees fail transfer after
removing all five example calendars. Short-score positives
alone are insufficient to propose a playing feature. Keep
native regulation, maintenance and jumps separate.

The [generated mirror forensics](elite-league/boxing-trade-skills.md#why-the-generated-w-tap-mirror-drifts-from-its-donor)
reproduce 4,285 strict input-and-transport replay events from
three recent donors. In the free-running m7410 mirror, an
early S12 application moves B one tick ahead starting
2.668 s into the fight. Giving just that packet its actual
wire and queue times extends the matched score prefix from
11 to all 16 events at 4.5 s and changes the modeled final
score from 100–27 to 93–100. The 71-hit model chain becomes
two. Factual m7410 ends 100–99 from B. The first m7410
view error is an independently removable early tracker
receipt. In m7388, the actual wire timestamp still falls
before the wrong drain; only actual enqueue timing delays
the impulse as observed. One observed 5.088 ms click
completion in m7378 fixes the near-50 ms cap discrepancy
and extends motion parity. These are matched offline
interventions with measured stage times and no playing
policy change. Validate predictive timing on disjoint
native clocks before using long deterministic model chains
to rank new playing features. Preserve native score,
maintenance and jumps separately.

The [packet-timing calibration](elite-league/boxing-trade-skills.md#calibrating-packet-and-click-timing-on-separate-match-recordings)
uses 12 development matches for stage delays and 22 separate,
previously exposed mirrors for comparison. Through 4.5 s,
mean combined score error drops from 1.95 to 1.41 hits and
mean movement-position error from 0.894 to 0.480 blocks.
Median exact scoring prefix rises from 4.5 to seven. The
press-completion fit improves one panel and worsens the
other. Replaying five frozen exact cuts with just the
packet calibration retains 54/55 clean doubles on m7384,
38/55 on m7422 and 32/55 on m7398. Both 80 ms cuts still
produce zero clean doubles. These are short-window checks
on archived host opportunities and selected checkpoints.

The [time-varying delay stress](elite-league/boxing-trade-skills.md#when-delays-change-within-a-fight)
checks 72 short blocks from other same-RTT recordings on the
same five frozen cuts. Individual match medians preserve the
pooled double counts. Changing delay within each block leaves
m7384 at 54/55 timings across all 12 blocks, m7398 at 15/55
across all 16, and m7422 at zero across all 16. Both 80 ms cuts
still have zero. One attack's readiness moves m7422's own
point to the next server tick. A 0.383 ms tracker readiness
change on m7398 creates a legal return before displacement,
even with the first own hit on its original tick. Future
hit-selection examples need separate space for both margins.

The [approach neighborhood](elite-league/boxing-trade-skills.md#when-the-approach-creates-the-same-combo-start)
rebuilds 269 proposed starts before the trade. The best
m7384 sequence gains a double in 54/55 subsequent timings
for 12 of 49 proposed phase pairs, in 50/55 for two, and
in zero for the other 35. All six successful approach-key
changes retain the original motion summary. With future
timing aligned, m7384's defender uses 60% horizontal
knockback on its first step in the four-hit start and full
knockback in the failure. Changing only that first step
reverses each result. The eventual own-view gap reveals
the different recoil, but a frozen landing-gap rule adds
only one nominal double across 1,319 other cuts. That case
has zero robust gains across 16 recorded delay blocks.

The [first-press delay test](elite-league/boxing-trade-skills.md#can-the-first-click-create-the-recoil-opening)
links both recoil steps in 606 native W-tap trades. The
m7384 factual first trade has two full recoil steps. In
2,170 modeled first-press delays, 817 retain both original
press counts, including 50 new defender brakes. None of
those new brakes earns a double with positive score. At
80 ms on m7421 A, a delay instead removes the defender's
early brake and gives a double, with short margin −1→+2
at 55 presses each. The same ten of 23 phase proposals
gain the double in all six transport checks. Seat B loses
a press and remains unqualified. In all 46 stock phase
cases the first locally eligible poll identifies the
tested admission, providing an observable experimental
trigger. This is still a one-clock result and the archived
35-second source was 6–66 from A. Validate the early
window across independent clocks before a regulation arm.

The [local first-poll transfer](elite-league/boxing-trade-skills.md#a-local-first-click-delay-on-fresh-clocks)
uses current crosshair and miss cooldown only. It reproduces
the m7421 A gain. On 12 newly captured native mirror clocks,
the modeled delay loses 0.20 points on average in 54 equal-
count trials. At 80 ms it loses 0.67 and gets zero doubles
to W-tap's one. Native clients in the collection play W-tap
throughout. The stock model reproduces every 3.1 s credit
on eight unshifted mirrors. Recoil intervention erases the
m7421 doubles when it restores the defender's brake. On
m7429 B, the delay hands the defender its native fresh
return when the phase is −5 ms, but gains short points at
+5 ms. Treat m7429 as development from this diagnosis
onward. The fixed delay fails the transfer comparison.

The [earlier click cadence screen](elite-league/boxing-trade-skills.md#changing-the-clock-before-the-first-contact)
tests a single delayed out-of-range press at 480 starts.
On m7378 B at 20 ms, a fifth-press delay turns an air
swing into the first fresh hit, 102 ms before W-tap's first
score. It produces a strict double at the same counts in
all five defender phases and 10/25 joint phases across 13
transport profiles. The causal margin at the first attack
drain is 1.029 ms. Moving just that packet by 1.2 ms
erases the gain, and a 1.6 ms advance restores it in the
adjacent failing phase. No extra double appears across
the 110 other recent-clock starts, even choosing each
sequence in hindsight. m7413 B has a short matched gain
over 22 joint timing pairs across 17 profiles, with zero
qualifying doubles. All are short model comparisons.

The [native geometry and completion audit](elite-league/boxing-trade-skills.md#anchoring-the-early-press-to-the-original-recording)
matches the m7378 delayed ray to the original 2.429-block
geometry. The prior native loop was still at 3.072 blocks.
The witness relies on catch-up ticks after an 80 ms gap.
Exact measured costs on unchanged physical polls reproduce
both native press counts. Median costs on the new polls keep
the 3–2 modeled double versus W-tap's 3–3. A measured slow-call
cost misses the needed drain and both go 3–3 at the same
counts. Treat the counterfactual as conditional on completion
and transport, and retain the observed W-tap trace separately.

The [independent RTT cross](elite-league/boxing-trade-skills.md#varying-each-players-rtt-independently)
uses the same six exposed host clocks and both seats at each
0/20/80 ms pair. Only m7380 supplies an unanswered score gain
under all five timing probes, at 0/80 and 20/80. The latter's
25–135 ms local wait band maps to one server reply drain in
each phase. No nominal success maintains both baseline press
totals. Keep this as same-cap geometry and timing evidence.
Current data do not justify a ping-scaled timing rule. Before
a native policy comparison, find a visible condition that
works beyond that donor and preserve the matched physical
cadence, regulation, and maintenance comparisons.

The [first hurt-cue test](elite-league/boxing-trade-skills.md#direct-hurt-cue-opening)
produced three native combo starts at matched 10 CPS, all from seat A with
0–20 ms added RTT. The same delay failed the score comparison on each new
model timing panel, and all four native 80 ms replies missed the timely
counter window. M4459 and m4449 took near-identical initial impulses and
replied 203 ms and 202 ms later. The first denied W-tap's reach and scored
six fresh hits in a row. The second gave up a return at the first native
fresh window. In m4449 W-tap's immune sprint contact braked the counter
recoil by 40% before its first movement step. In m4459 the first step used
the full recoil. [Paired recoil forks](elite-league/boxing-trade-skills.md#first-movement-after-the-counter)
erased 102 starts by adding the early brake and rescued 56 by restoring
full recoil, with zero rescues at 80 ms. Two native timely counters still
failed with full first-step recoil because W-tap remained inside reach.
Native arm-animation cues exposed only 38 of 78 swings before the first
counter press. Test whether ordinary movement can create spacing that
denies the first fresh return even when W-tap brakes immediately. Keep
any estimator of the opponent's swing phase probabilistic.

The [follow-up footwork study](elite-league/boxing-trade-skills.md#spacing-before-the-first-reply)
found that brief movement changes after the incoming hit and before the
reply often lost the counter. In m2065 one backstep pushed the otherwise
identical first aim ray across 3.0 blocks. After a timely reply and an
actual early W-tap brake, an offline search over four more ordinary
movement inputs recovered five of 48 starts at zero added RTT, two of
26 at 20 ms and zero of 28 at 80 ms. Prioritize spacing before the first
incoming hit or another approach geometry with enough margin for the
selector's reply. Keep the braked first step as an adverse diagnostic.

The [grounded approach screen](elite-league/boxing-trade-skills.md#spacing-during-the-grounded-approach)
found no fixed early backstep or diagonal that improved the score across
seats and RTT. Both representative arms made 63 of 64 timely counters at
80 ms, but neither started a combo there with W-tap's early brake.

The [mid-fight re-entry forks](elite-league/boxing-trade-skills.md#hit-selection-when-both-fighters-re-approach)
tested click timing when both players were grounded and fresh again. At
20 CPS, one fixed delay produced a consistent new three-hit start in one
of 110 separate-panel cuts at 20 ms and one of 109 at 80 ms, across three
opponent timings. The 80 ms case survived every tested opponent shift while
the selector's timing stayed fixed. A 5 ms change to the selector's future
timing gave W-tap a return at 2.913 blocks on the same initial hit times.
Waiting only to the hurt cue improved short-window accepted margin at 20 ms
through upgrades while losing fresh-hit margin. Treat that scoring effect
separately from reliable three-hit starts and complete-match performance.

The [two-client release sweep](elite-league/boxing-trade-skills.md#hit-selection-under-changes-to-both-clients-timing)
tested 656 approaches at 5 ms release resolution with the observed tracker
phase and movement-send timing. Each cut tested nine joint timing shifts
with the same W-tap control. At 20 CPS the separate panel produced one
robust three-hit start in 39 cuts at 20 ms and one in 71 at 80 ms, against
zero from plain W-tap at each. At 20 ms the m4761 selector clicked its
first reply while airborne, landed 141–171 ms before W-tap, and improved
accepted margin by 3–4. W-tap's return rays remained outside 3.0 blocks
through the first follow-up in every variant. The 80 ms witness could
lose margin despite getting the first three hits. Use the successful
20 ms case to diagnose which visible position history predicts the
first-return gap, and keep the 80 ms case as a failure control. These
cuts are privileged and the donor clocks were previously exposed.

The [attack receipt and height check](elite-league/boxing-trade-skills.md#attack-packet-timing-and-the-visible-height-cue)
kept the m4761 three-hit start in all 81 combinations of client phases
and attack receipt shifts drawn from the measured error envelope.
Plain W-tap had zero. The selected delay improved the short score by
2–4. At 80 ms the m4890 delay had 144/144 starts to W-tap's 128/144
with a median paired score change of zero. The 20 ms delay replied
while the selector was 0.637 blocks high. Releasing at a local height of
0.60 blocks reproduced that start, yet averaged −0.82 accepted margin
versus W-tap at 20 ms across all 79 eligible approaches and their
nine timings. The earlier and later reply boundaries delayed the
first follow-up or exposed a return despite an earlier landing.
Diagnose relative replica motion and braking before extending the gate.

The [pre-arm approach-view screen](elite-league/boxing-trade-skills.md#what-the-selector-can-see-before-it-delays)
froze a single-threshold rule on four clocks and scored five separate
previously exposed calendars. At 20 CPS and 20 ms it gained +0.19 accepted
hits over the matched W-tap suffix, below +0.35 for always waiting to
the hurt cue. At 80 ms it lost 0.08. Even with the best delay chosen in
hindsight, strict score gains across every timing variant existed in
only 4/39 comparison cuts at 20 ms and 3/71 at 80 ms. At 80 ms the
best-mean single delay averaged +1.01, and a picker that knew the future
timing averaged +2.07. The all-delay diagnostic bounds release choice
from the tested starts. Before more pre-arm fitting, check whether
movement can create a reply that tolerates W-tap's early sprint brake.
Use the scored clocks as exposed and keep the native research gate.

The [post-brake footwork search](elite-league/boxing-trade-skills.md#footwork-after-w-tap-brakes-its-recoil)
tested all 343 three-head movement sequences at 20 CPS after the
opponent's first braked step. At 20 ms the search added 48 three-hit
starts in 480 naturally braked timing variants while improving score
over both plain W-tap and the stock delayed reply. No fixed sequence
kept that result across all nine phases of a 20 ms cut. At 80 ms none
of the 1,042 braked variants yielded a three-hit start. Forcing the
0.60 horizontal factor removed both timing-stable witness starts.
Three backsteps recovered three of nine forced phases at 20 ms. The
first return ray became a miss in the nominal phase, yet W-tap
still returned on a later click at 2.960 blocks. Diagnose relative
spacing through the entire first follow-up and when the selector can
begin shaping it from its own view. The short post-reply search remains
a privileged model diagnostic.

The [earlier local cue forks](elite-league/boxing-trade-skills.md#starting-the-footwork-from-the-clients-own-cues)
moved the same three-head search to own hurt or post-hurt contact.
At 20 ms, hurt-cue movement gave 98 new three-hit starts in 480
braked timing variants with a score gain over both controls, versus
51 at the contact cue and 48 after the first recoil step. No fixed
sequence qualified across all nine phases there. At 80 ms neither
early cue produced a score-improving double in 1,042 braked variants,
even though the hurt cue preceded the recoil step by 238 ms at the
median. In the m4761 single-backstep witness, a later selector
phase secured a second fresh hit and then gave up a return. Preserve
the distinction between the first denied ray, the scored double,
and the whole decisive-margin goal.

The [pre-hit approach search](elite-league/boxing-trade-skills.md#shaping-the-approach-before-the-first-hit)
kept the stock lift gate and found no score-improving three-hit
starts in the 1,042 naturally braked 80 ms variants. With the same
movement choices and immediate release on the local hurt cue, it
found 59 in that stock-defined subset, but no fixed word held
across all nine phases of any 80 ms cut. The m4892 and m4886
back/back/release witnesses used full first-step recoil on
their successful early replies. A forced 40% horizontal brake
erased every three-hit start. Rescanning 73 deadlines recovered
score-improving doubles in at most six phases per witness.
Prioritize spacing and packet order that prevent the early brake,
and keep that distinct from recovering after the brake already happened.

The [reply dispatch and native phase audit](elite-league/boxing-trade-skills.md#the-server-tick-that-the-first-reply-reaches)
found that the late m4892 and m4886 replies already had valid rays.
Their C02s reached the server 2.684 and 8.267 ms after the earlier
drain. A −3 ms selector receipt shift took m4892 from five to seven
score-improving three-hit phases out of nine. It left m4886 at four.
On four separate 80 ms native W-tap clocks, an own replica-receipt
predictor classified 81.0% of factual attacks into the right drain
at click admission versus 60.0% for the local-head reference.
Near a cutoff those were 68.6% and 60.0%. Requiring more than 6 ms
forecast clearance kept 64.0% of attacks at 89.5% accuracy.
These clocks were already exposed for other diagnostics. The forecast
checks factual C02 timing. Use explicit uncertainty around the planned
reply, and require a separate matched native score check for a policy.

The [first-reply guard screen](elite-league/boxing-trade-skills.md#acting-on-the-first-reply-timing-estimate)
tested bounded waits using only the latest delivered replica clock
and local head. On four separate 80 ms clocks, a 6 ms guard with a
20 ms wait cut timely pre-footwork replies from 1,117 to 1,029 and
lowered mean short margin by 0.30 relative to immediate release.
Stock movement also lost with the guard. A current-contact gate
kept the reply counts and barely moved the score. Forecasting an
uncertain drain was useful as a diagnostic. Deferring this first
reply made the exchange worse. Test pre-arm action choice before
another timing-based wait.

The [pre-arm timing screen](elite-league/boxing-trade-skills.md#choosing-at-the-approach-using-packet-timing)
kept plain W-tap available at each offline approach cut. The best
development timing split gained 0.038 accepted hits over 2.5 s but
lost 0.043 on four separate, previously exposed 80 ms clocks. It
lost on three of those clocks. At 95 of 133 separate cuts the
stock hurt-cue action switched between a gain and a loss as future
timing changed. Choosing the best worst-case action from plain
W-tap and two hurt-cue variants with full hindsight left two
strictly improving cuts and zero score-improving three-hit starts.
The tested action set has little robust upside even with perfect
pre-arm knowledge. Use paired failure analysis to find a physical
change that preserves useful spacing across timing before adding
another arming rule.

The separate [24-bout native impact-jump repeat](elite-league/boxing-double-findings.md#native-impact-jump-repeat-across-three-latencies)
verified matched 20 CPS caps and similar actual click rates. The jump
won 3/4, 4/4 and 0/4 at 0, 20 and 80 ms, with median margins
+5.5, +5.5 and −11.5. Plain W-tap mirrors won 3/4, 2/4 and 2/4,
with medians +2, −1 and +0.5. The earlier independent 20 ms cohort
had a lower jump median than its W-tap mirrors, so keep that evidence
and the new sweep together. At 80 ms the jump gave up fresh hits
and upgrades. Evaluate jump escapes separately from delayed-hit
selection and distinguish targeted escape timing from jumping on
every eligible impact.
Replaying the new mirror clocks gave zero model jump wins out of eight
at 80 ms under both movement timing modes. The wire-dispatch model
gave eight out of eight at 0 ms, compared with three out of four in
the actual native repeat. Eleven of the twelve clocks had qualified
wire dispatch.

The [frozen movement policy at 20 CPS](elite-league/boxing-double-findings.md#frozen-movement-at-20-cps)
won 7/8 native first-to-100s at matched settings, with +11 median accepted
margin, and won 23/32 on newly collected model calendars. The native K3
continuations were 6 cycles from two entries versus W-tap's 2 from four
under the activity metric. Their seed heights differed. On nine exact
post-freeze model forks the same comparison was 4 versus 3 cycles.
Use this as the regulation benchmark while the matched maintenance gate
and robust hit selection remain open. Six of the nine K3 model states
returned before a post-landmark movement update could reach the server.
Diagnose the earlier geometry on these states before adding a different
continuation controller.

The [wire-dispatch repeat for frozen movement](elite-league/boxing-double-findings.md#frozen-movement-on-the-new-wire-dispatch-clocks)
scored the unchanged weights on the 11 wire-qualified jump-repeat
mirror clocks. Its median accepted margins were +1 at 0 ms, −1.5
at 20 ms and +3.5 at 80 ms, versus 0 for the matched W-tap mirrors.
The 20 ms shortfall again came from upgrades despite a fresh-hit
gain. All seven exact K3 pairs tied with zero additional cycles,
and there were no K5/K10 entries. A new native schedule freezes
24 regulation matches and 24 separate maintenance drills with the
old policies across both seats and the same three RTTs.
The [completed independent native repeat](elite-league/boxing-double-findings.md#completed-independent-native-repeat)
won 9/12 regulation bouts at matched 20 CPS, with median +5
and range −9 to +34. The W-tap mirror nominated seat won 8/12
with median +1.5 and range −14 to +20. The frozen policy took
3/4 at 0 ms, 2/4 at 20 ms and 4/4 at 80 ms, with median
margins +7, +5 and +4.5. Actual click rates were 16.638 and
16.644 CPS. Keep the +96 fresh-hit gain and −14 upgrade
losses separate. The maintenance tally was 11 cycles across
three K3 entries versus one across four for W-tap. One
20 ms candidate run supplied ten cycles and had a different
seed gap from the 20 ms W-tap entry. Preserve the exact
common-state maintenance comparison and the shortfall at 20 ms.
The [repeat's 20 ms upgrade audit](elite-league/boxing-double-findings.md#where-the-points-went-in-the-20-ms-repeat)
found equal fresh-hit counts in both losses. The bot's 24
first-fresh-tick criticals outnumbered its W-tap opponents'
three, but 17 of the bot's clicks missed the last immune
drain even with zero transit. With the preceding physical
press pinned, only two of 24 had an earlier observed attack
loop that fit the cap and measured transit. Advancing a
single already-emitted C02 in the model was feasible on one
of 22 frozen-policy cases and one of 20 W-tap cases. It
converted both. The frozen case gained one accepted point
in 1.4 s versus zero under both unchanged movement and plain
W-tap from that state. Preserve these as isolated packet
interventions. To broaden the opportunity, diagnose how the
movement lead-up can create an earlier contact at matched CPS.
The [post-normal-hit movement search](elite-league/boxing-double-findings.md#what-footwork-could-change-before-a-late-critical)
found earlier upgrades in five of 22 frozen late-critical
windows on the current 20 ms clocks and four of 20 W-tap
windows. Two frozen words gained on stock across all nine
timings, and neither beat same-cut W-tap across the spread.
The older two clocks added zero conversions in six frozen
windows. Of the native bot's 24 late-critical windows, the
last press before the cutoff occurred before descent in 14
and missed while falling in one. The three falling contacts
that reached the server while the victim was still immune
were all evaluated with the attacker still non-falling.
Consider the preceding normal hit's timing and the attacker's
vertical phase jointly before another geometry-only rule.
The [preceding-hit delay diagnostic](elite-league/boxing-double-findings.md#delaying-the-normal-hit-to-catch-the-fall)
found one fixed wait that kept a later normal hit, earned
its upgrade, and beat both controls across nine timings in
six of 22 current frozen late-critical cases. All six
conceded an opposing point before their first own hit in
the nominal branch. An 80 ms wait frozen from the older
six cases beat both controls across nine timings in 43 of
413 ordinary frozen normal-hit starts on three separate
previously exposed clocks; 32 also converted the upgrade.
The nominal mean was +0.041 versus same-cut W-tap. The
distance split was negative inside 2.5 blocks and positive
beyond on each clock. This test selected actual scoring
presses with hindsight. Any own-client rule must account
for the full population of polls, including misses and
immune contacts, before a full-match or native promotion.
The [own-client edge-hold diagnostic](elite-league/boxing-double-findings.md#testing-the-edge-of-reach-hold-in-full-matches)
tested the 80 ms wait at every eligible local approach.
It fell from 6/8 to 3/8 on the separate 20 ms clocks,
with fresh margin shifting +23 to −134 and upgrade margin
+3 to +129. It failed at zero added RTT and weakened the
80 ms result. A one-hold limit nearly matched actual CPS
and still lost ground, as did longer budgets on the
separate 20 ms panel. The matched K3 score remained below
plain W-tap. Isolating one hold at a factual stock poll
gave +0.219 in 1.4 s, with a negative mean when the
opponent hit first or when the original win on first
contact had no prompt return. The useful hindsight case
was own-first followed by an accepted return within 75 ms
(+0.547 nominal). Of 189 starts with that stock trade
ordering in all nine timing variants, 31 held branches
beat both controls throughout the sweep. Work from that
trade prediction problem and keep the edge-only gate
unqualified for native deployment.
The [local value selection test](elite-league/boxing-double-findings.md#selecting-holds-by-expected-score)
used preceding own motion, replica closure and contact
history. It forecast quick trades at 0.715 AUC versus range
alone at 0.674. The frozen value fit selected holds with
positive short-window gains on all four comparison clocks.
In full model regulation with frozen movement it won 7/8
at 20 ms with median +16, 8/8 at 80 ms with median +10,
and 4/8 at zero RTT. Across nine future shifts of the same
eight 20 ms seat states it won 65/72 with median +14 versus
frozen's 48/72 and +4. The nominal physical rates were
16.13 versus 16.64 CPS. In common K3 forks it completed
one cycle versus frozen's two and W-tap's six. The m5743 A
hold deferred an available new fresh hit into the return's
drain, where it lost the order. Diagnose protection of a
genuine established follow-up before trialling the frozen
value rule natively. Preserve the delivered-CPS distinction,
the weak zero-RTT result and the matched K3 gate.
The [hold duration sweep](elite-league/boxing-double-findings.md#how-long-the-selected-click-needs-to-wait)
kept the value fit fixed. On the separate 20 ms panel, 26 of
202 selected 80 ms holds improved score over both controls at
all nine timings. None added an unanswered double across every
timing. The longer holds earned their gains through upgrades.
At 80 ms RTT, skipping one eligible poll won 67/72 modeled
bouts with median +10.5 versus frozen movement's 59/72 and +8
and W-tap's 36/72 and zero. These are correlated variants of
eight starts and the physical rates were 16.67 versus 16.72
CPS. At 20 ms the same skip won 47/72 versus frozen's 48/72.
The skip recovered the follow-up lost to the long hold at
m5743 A, but the resulting K3 count remained two across ten
starts, versus six for W-tap. At that donor's K3, frozen's
first follow-up launched from 3.738 blocks and took nearly
one second to catch again. W-tap's identical impulse from
3.530 blocks allowed a 598 ms follow-up. One W-tap release
restored frozen's six cycles on the nominal clock and failed
with the defender shifted 5 ms earlier. In native m5781 the
follow-up after hit twelve landed ten drains late despite
an ordinary number of immune sprint contacts. Prioritize
launch separation and next-window pursuit with usable phase
margin. Hold length alone does not clear the K3 gate.
The [recorded-profile transfer](elite-league/boxing-double-findings.md#transfer-to-the-recorded-mmc-preset)
reran the frozen policies under ten fresh-hit ticks and the
recorded sprint impulse. At 80 ms, skipping one poll won
59/72 modeled bouts with median +7, versus frozen's 46/72
and +6 and W-tap's 36/72 and zero. The long hold reversed
at 20 ms to 32/72 and −3, below frozen's 56/72 and +5.
The delivered click rates at 80 ms with the skip were 16.670
and 16.711. Both frozen and the skip trailed W-tap in K3
cycles at zero RTT despite gains at 20 and 80 ms. The
phase variants reuse the same enrolled states. Compare the
unchanged incumbent natively under the new profile before
attributing progress to click timing.
The [pre-launch versus post-launch screen](elite-league/boxing-double-findings.md#footwork-before-and-after-the-first-follow-up)
found no post-launch words that beat both controls across
all nine timings in 62,608 recorded-profile trials. All 56
robust pre-launch words were from m5505 A. Back, walk, back
from its first local target hurt cue delayed the next launch
by about 204 ms, compressed its launch gap and brought the
following hit to the first native fresh window in all nine
timings. Applying that cue and word broadly lost cycles.
At this point a motion-based condition before launch is still
missing. Keep model and native evidence separate and retain
both maintenance endpoints when the native cohort completes.
The [spacing versus click-delay attribution](elite-league/boxing-double-findings.md#separating-pursuit-spacing-from-click-delay)
reproduced the m5505 launch timing and impulse with a
click-only hold in six timings. At the same defender
click, the ray was 2.705–2.706 blocks on the click-only
branch and the nearest box was 3.622 on the retreat
branch. The frozen cue actions failed to transfer to
30 regulation-derived K3 starts. The per-state word
search at the first local hurt cue found a second
repeatable witness, m5493 A at 0 ms, using walk, right,
sprint-left and landing the next hit on the first
fresh drain in all nine timings. It reached eight
cycles, versus three to seven for W-tap, before the
4.5-second cutoff. Develop any own-client rule from
its launch geometry and return threat, with the many
other natural starts treated as negatives. At 80 ms
one high-score skip won 51/72, a low-score skip 52/72,
frozen movement 46/72 and W-tap mirrors 36/72.
The trained threshold did not establish a specific
advantage. The benchmark process still waits for the
shared native scheduler; keep the validation gate open.
The [own-observation and wide-timing test](elite-league/boxing-double-findings.md#own-observations-and-the-timing-limits-of-two-successful-starts)
froze the two footwork actions and evaluated all 38
shared starts on 12 previously exposed donors. The
clock-blocked history fit gained seven active cycles over
frozen movement and lost ten accepted points across 342
phase variants. It identified no start that passed both
controls across all phases. The hindsight action ceiling
passed two starts. On m5505 the wider timing and dispatch
grid kept the first-window hit in 225/225 but the joint
gate passed 138/225. On m5493 the counts were 180/225
and 144/225. Identical measured cue features could have
different outcomes for both words. Use those cases to
separate forecasts of the next hit from longer-run effects.
The [recorded-preset jump transfer](elite-league/boxing-double-findings.md#standalone-jump-escape-on-the-recorded-preset)
won 54/72, 39/72 and 44/72 modeled first-to-100s at
0/20/80 ms, versus matched mirrors at 36/72 each.
Physical CPS was nearly identical. At 80 ms one jump
cut 21 of 191 exact incoming runs with at least two
points to one, while worsening 34 of 200 single-hit
runs. It matched every known K3/K5/K10 maintenance
endpoint. Keep the existing jump policy separate while
diagnosing which incoming states warrant it natively.
The [jump-cue comparison](elite-league/boxing-double-findings.md#predicting-when-an-impact-jump-helps)
froze separate risk and score fits on 12 development
donors. On 12 previously exposed never-example donors
the risk predictor achieved 0.773 AUC for two unanswered
accepted hits. Its selected jumps gained only two points
over 1.7-second exact cuts and scored 100/216 modeled
regulation wins versus W-tap's 108. The score predictor
gained 39 isolated points and won 131/216 with +2 median
at 16.6724 versus 16.6730 CPS, below always jumping's
148/216 and +3. At 80 ms the score rule gained fresh
hits and doubles but reduced upgrades
compared with always jumping. On states reached by
the always-jump policy, forcing the next jump gained
46 and 50 accepted points at 0 and 20 ms versus
skipping it, even as it increased the short unanswered
loss. At 80 ms it lost seven accepted points and gave
up 19 more unanswered points. All 45 exact K3/K5/K10
starts matched W-tap through the first return. Keep
jump controls separate and judge the full exchange,
including the period after the first reply.
The [return-packet audit](elite-league/boxing-double-findings.md#when-w-tap-committed-the-first-return)
linked all seven W-tap returns directly to their attack packets.
Five return clicks preceded the selector's next input head. Three
returns had already accepted by that head, including one in the
same server drain as the third hit. Earlier spacing is a prerequisite
to moving out of reach before those clicks. Retain the common K3
landmark and separate the later returns when diagnosing movement.
The [later-return sweep](elite-league/boxing-double-findings.md#which-later-returns-movement-could-change)
tested 16,744 three-head movement sequences from exact K3 forks.
At 0 ms, m5509 A gained active cycles across nine shifted timings
by backing away before re-entry. Its one to three extra cycles
did not produce a reliable short score gain. The 80 ms forks added
zero active cycles, though m5505 A improved short accepted margin
on all nine timings via later exchanges. Removing the exact first
return packet by offline intervention exposed an additional cycle
in two of six starts. In m5498 A another attack in the same drain
gave W-tap an immediate replacement. At 80 ms m5502 A is the
specific witness for testing reach denial before the return click,
with m5498 A as a check against mistaking one miss for a safe
window. The different movement sequences share the source clocks
and are not independent runs.
The [exact K2 follow-up](elite-league/boxing-double-findings.md#reaching-the-return-window-from-the-second-hit)
on m5502 A found 198 ordinary movement sequences that extended
the chain and improved accepted margin in all nine timing
variants. Back, sprint-left, walk gained three to four paired
accepted hits over 2.2 s, with all nine returns censored. On
the nominal timing it preserved the third hit and made the
return click miss at 3.115 blocks, up from 2.870. The chosen
start was 15.2 ms after the selector processed a target hurt
cue. Test that cue and measured geometry across new states in
the model before any native policy trial. Keep m5502 A marked
development and preserve the common K3 maintenance gate.
The [local hurt-cue transfer](elite-league/boxing-double-findings.md#starting-the-footwork-from-the-local-hurt-cue)
reproduced the m5502 A input and outcome at the acceptance cut.
Starting the phase sweep 2.754 ms later at tick end preserved
the chain gain but gave a score range of −2 to +4. On seven
other previously exposed clocks, the fixed movement gave no
robust fourth-hit gains across 45 K2 starts. The local geometry
rule never triggered before a return. Even an exact K2 mark
and the first local cue failed to generalize. Diagnose the
per-state movement and reach opportunity directly before
tuning a tighter cue or promoting the word.
The [bounded multi-state search](elite-league/boxing-double-findings.md#how-much-three-inputs-could-change-across-second-hit-starts)
tested 40,768 movement words across all 53 exact 80 ms K2 starts.
It found joint score and cycle gains across nine timings in five
starts, all in seat A and spread over three development clocks.
Four of the gains were outside m5502. In 22 starts a return was
accepted in the K2 drain and another 16 had the eventual return
packet queued by K2. None of the 43 starts where W-tap lacked a
third active hit yielded a robust K4 from the tested post-K2
inputs. Compare earlier forks to separate reaching a viable third
hit from preserving an already favorable chase. In the positive
cases, measure the timing of the fourth and later fresh hits and
whether earlier hits forced the following opposing clicks to miss.
The [earlier K1 sweep](elite-league/boxing-double-findings.md#changing-movement-before-the-second-hit-trade)
tested 247,520 ordinary movement words in the 22 same-drain K2
failures. It found one robust chain rescue with a paired score
gain of +1 to +4 across nine timings. In m5510 A, sprint-left
twice and sprint advanced hit two by 454 ms, preserved the
original later hit as hit three, and made the return click miss
from 4.641 blocks. The first movement preceded the new local
hurt cue. Single return packet deletion alone helped zero
starts. The completed 48-bout native packet audit linked all
225 same-tick returns to clicks made before hit two accepted.
Separate recognizing a viable approach from committing to a
movement change before that cue. Keep the earlier-word model
screen and independent native regulation score separate.
In the native repeat, [m5781](elite-league/boxing-double-findings.md#how-the-13-hit-native-combo-ended)
added ten fresh hits after K3 at 20 ms. All impacts had the
same measured impulse. After hit 12, four contacts were
immune and eleven subsequent clicks missed while the selector
held forward on every movement update. Hit 13 took 951 ms
instead of the prior 447–602 ms, and the return accepted
0.756 ms later in the same tick. Diagnose the long re-entry
and the pre-launch state in any follow-up on this bout.

The [pre-K3 packet and spacing audit](elite-league/boxing-double-findings.md#why-the-fast-returns-start-before-the-third-hit)
linked all six fast model returns to a click consumed 8.9–80.9ms before
the third hit accepted. None of 14,375 bounded footwork words after the
second hit fixed the failure. An isolated weaker horizontal impulse on
that second hit rescued two exact forks across three tested phases. One
ordinary-input reproduction used one W-only tick in m4511 seat A at
80ms. It delivered a normal second hit and then scored a fourth hit in
10 of 11 phase shifts from −25 to +25ms. At +25ms it still landed a
normal second hit and failed to reach a fourth. The equivalent search
did not cover all three initial phases in m4494. Find an own-client cue
for the weaker hit beforehand, then freeze and validate it. Keep these
clock donors marked development.

The [cue-timed sprint release](elite-league/boxing-double-findings.md#cue-timed-sprint-release)
reproduced the extra hits on m4511 using only the client's local contact
and hurt cues. On eight historically exposed calendars that supplied
no examples to the rule, it won 28/48 full modeled matches versus
W-tap's 24/48 and lost ground at 20 ms. From ten identical K3 starts it
scored seven more active cycles versus W-tap's nine. At 80 ms only
five of 15 paired unanswered second hits after a release became
weaker, and three became stronger. Native W-tap re-armed sprint before
the following movement at all 79 matched trigger opportunities.
Keep the frozen movement policy as the native benchmark. Before
considering another release rule, account for which contact actually
scores and which sprint transition precedes it on the wire. Keep
this transfer panel marked development for any such follow-up.

The [second hit diagnostic](elite-league/boxing-double-findings.md#where-a-weaker-second-hit-helps)
found no general advantage in weakening W-tap's next sprint hit.
At 80 ms, changing only horizontal recoil on 13 zero-lift launches
raised further active cycles from 6 to 18 and fourth hits from 0
to 3 at nominal timing. Two fourth hits survived a 10 ms defender
phase shift, both with a grounded attacker. Only m4511 reproduced
an improvement with ordinary keys across three defender timings.
The state was uncommon in native regulation. W-tap's stronger hits
had better aggregate margins in all three upward-launch groups.

The [20 ms model extension](elite-league/boxing-double-findings.md#frozen-movement-at-20-ms-rtt)
found that the frozen movement policy's 115 extra fresh hits were
offset by 116 fewer damage upgrades across the two donor panels.
It won 12/32 versus the 16/32 W-tap mirror. The matched native 20 ms
cohort split 2/4 in both arms. Frozen movement led by 16 fresh hits,
but gave up 13 more upgrades than it delivered. On 41/42 of its
upgrades W-tap had the most recent fresh hit. The eight 20 ms
maintenance runs had no K3 entries. The regulation benchmark
across 0, 20 and 80 ms is now 9/12 with a +9 median margin.
The new mirror schedules independently gave 3/8 modeled candidate
wins versus 4/8 in the mirror, again with a fresh-hit gain and an
upgrade deficit. From three exact model K3 states the active
cycle tally was 5 versus W-tap's 10. m4761 seat A accounted for the
difference. After equally strong third follow-up hits, the policy
had a 950 ms gap and a prompt return, where W-tap's closer launch
allowed a 504 ms follow-up. Use m4761 as development to diagnose
the failed spacing before changing the controller.

The [matched input substitutions](elite-league/boxing-double-findings.md#which-input-changed-the-20-ms-continuation)
showed that replacing W-tap's first reset on m4761 with one
W+Ctrl tick removed the fourth hit's sprint bonus and cut nine
further active cycles to three. Changing only horizontal recoil
reproduced that count on the original clock. A weaker launch had
the opposite effect on m4511 in the frozen policy's branch.
There the restart command followed the scoring C02 in the same
drain. One W+Ctrl tick induced a normal-strength hit and improved
both m4511 seats across four defender timings. No other donor
gained for frozen movement when switching its first post-K3 hit
to the other impulse class. Before adding a rule, test whether
visible contact and movement histories can distinguish these
situations early enough to select useful recoil. The same
physical launch strength can overshoot or fail to deny return
range depending on the trajectory.

The [same-view timing test](elite-league/boxing-double-findings.md#same-view-different-timing)
paused before each ordinary input and changed only future
opportunities and new packet delays. At the early m4511 seat A
decision, a 5 ms future server shift changed the optimal branch
from a key swap (9 versus 2) to the original key (4 versus 3)
with identical local history up to the input. Waiting two heads
revealed a difference in own sprint state and improved or tied
frozen movement in all 90 variants across the two seats of that
same clock. The simple local cue did not transfer. On 35 exposed
never-example calendars it added zero at 20 and 80 ms to frozen
movement and cost nine active cycles at zero latency in the
nominal K3 results. Native timing varies on the relevant scale,
but the sensitivity grid is not a measured distribution. Keep
the cue experimental and the ambiguous decision case as a
regression for any estimator.

The [native damage-upgrade audit](elite-league/boxing-double-findings.md#why-w-tap-scored-more-upgrades-at-20-ms)
found that frozen movement more often sampled a ready falling
ray at 20 ms (53 windows versus its W-tap opponent's 47), but
scored 29 versus 41 before the cutoff in fully observed windows.
Sixteen of its 24 missed conversion windows had a falling click
accepted as fresh instead, 15 on the first fresh tick. Short
retreats blocked some upgrades in exact model forks and lost
fresh hits. Immediate jumps gained fresh hits while giving up
more upgrades, so their score effect is separate from an escape.

The [isolated velocity-triggered jump](elite-league/boxing-double-findings.md#standalone-impact-jump-at-different-latencies)
with W-tap keys won 36/40 and 35/40 first-to-100 model bouts at
0 and 20 ms and fell to 5/40 at 80 ms. With 16 pairs per point
on one panel it flipped between 50 and 60 ms. The K3/K5/K10
from-landmark branches were identical across 41 checkpoints,
as the first accepted return ends the maintenance score before
a reaction to its velocity can help. In the [native 20 ms regulation test](elite-league/boxing-double-findings.md#native-first-to-100-impact-jump-at-20-ms),
both arms won 3/4. The jump had a +4 median margin compared with the
mirror's +7. The fresh margin fell by 20 in aggregate despite gaining seven
upgrades. Simulating both seats on the four new mirror calendars gave
8/8 for the jump against the W-tap mirror's 4/8, so the new host timings
alone did not account for the difference. Keep the jump isolated from the
incumbent spacing policy and the delayed-hit selector.

The [first-eligible-drain audit](elite-league/boxing-double-findings.md#where-the-native-and-modeled-jump-scores-diverge)
located the native jump's fresh-hit loss at the first two eligible drains
after the prior fresh hit. Relative to the mirror, it lost 23 points of
margin there and gained three later. With full recorded host timing,
actual click polls and movement-derived wire delays, the model still won
16/16 versus W-tap's 8/16, with +60 early fresh-hit margin. Its unanswered
early repeats contributed +38 while the native jump and its W-tap opponent
were even on those repeats. Compare first-return reach and client packet
application from shared native states before changing the jump rule. These
eight clock donors are now development data.

The [trade geometry audit](elite-league/boxing-double-findings.md#return-geometry-after-a-trade)
found the largest sign reversal when the jump fighter scored first in a
trade. Restricting to two grounded sprint knockbacks, native one-sided
early follow-ups were 0 for the jump fighter versus 6 for W-tap in 56
trades. The model gave 17 versus 5 in 216. The same model ray agreed with
all 21,242 recorded eligible native click decisions using factual pose and
aim. Actual wire receipts predicted 6,127 of 6,133 attack processing ticks
and all 1,372 next movements after velocity. The model needs better
validation of the generated geometry and click ordering after the trade.
Keep this cohort as development and the conditional trade metric separate
from the established-combo gate.

The [strict trade replay](elite-league/boxing-double-findings.md#recorded-inputs-reproduce-the-return-failures)
matched all 9,800 local moves, 9,800 interpolations and 8,153 click checks
through all six grounded return failures. It conditioned on recorded
inputs and delivery. In generated play the native initial tracker phase
corrected a one-server-tick replica update shift. A further 0.322 ms
movement-send anchor difference in m5042 moved the report across a drain
and delayed the visible target. Fixing the startup counters and using raw
movement-send opportunities reduced the model's predicted jump advantage.
On the six calendars qualified for both variants, the jump dropped from
11/12 and +8.5 median to 9/12 and +6 against W-tap's 6/12 and 0 in both.
Keep new timing modes as diagnostics until tested against unseen clocks.

The [impact-jump component and repeated-use tests](elite-league/boxing-double-findings.md#why-the-impact-jump-loses-at-higher-rtt)
found that the forward sprint boost helped at 80 ms in the model, while
the extra lift cost fresh hits and upgrades. An isolated jump still gained
hits at 80 ms from ordinary W-tap states. On exact forks of states reached
by repeated resets, skipping the next jump improved the next 1.4 seconds
of score at 80 ms and hurt it at 20 ms. Older native traces showed 10
uninterrupted movement updates to land for W-tap and 12 for the jump,
and only 30 of 80 jumps actually carried a sprint boost. Gating the
ordinary jump on W-tap's next sprint input failed the 28-calendar model
transfer and tied all 157 maintenance forks. This study remains separate
from delayed-hit selection and the native regulation comparison.

The [recorded MMC hit-selection sweep](elite-league/boxing-trade-skills.md#hit-selection-under-the-recorded-mmc-preset)
found 15/120, 20/243 and 4/141 approaches at 0, 20 and 80 ms where one
fixed delay added a clean double and improved the short score across nine
joint timings. Matched plain W-tap managed a double across all nine at
one, three and two cuts. Crossed suffix forks show recoil changes which
delay works more often than changing the fresh window alone. None of 12
preselected delays kept a strict improvement across a wider 225-way
client and dispatch grid. A fixed 75 ms delay on m4890 B at 80 ms never
lost paired score and produced 147 strict three-hit improvements in
225 variants. The separate-clock local-geometry and observation-history
predictors improved less than fixed delays, all through damage upgrades
in aggregate. The clocks were previously exposed and the cut uses
privileged enrollment. Preserve this as a model diagnostic and use the
failing nearby schedules to locate the sequencing or spacing margin
before further controller changes.

The [attack and movement receipt trace](elite-league/boxing-trade-skills.md#why-the-80-ms-three-hit-opening-breaks)
isolates two failures on m4890 B. A poll shift can put both opening
hits into one server drain. With the successful one-drain lead
intact, adding 6 ms to one later attack that produces no damage
delays its following movement report by a full server tick in
the FIFO. W-tap's return ray moves from 3.00244 to 2.98154.
The [expanded release sweep](elite-league/boxing-trade-skills.md#retesting-every-first-click-delay-on-the-widened-grid)
finds two different 0 ms states on both seats with four unanswered
fresh hits across all 225 timing variants, versus plain W-tap's
zero and one in their nominal branches. Both successful fighters
land at least 150 ms before W-tap in the nominal replays. The
same full sweep at 20 and 80 ms gives no universally successful
fixed delay on those cuts. Diagnose lead and minimum return-ray
margin together. The favorable 0 ms waits were selected using
these outcomes and need a separate clock panel before any policy
claim.

The [reply-spacing forks](elite-league/boxing-trade-skills.md#moving-after-the-first-reply)
rescue all 78 failed timings of the single v3 m4890 B approach with
two immediate backsteps after the reply cue. The first hit times
remain exact. The previously fatal ray goes from 2.982 to 3.012 in
the +6 ms dispatch branch. This is a selected local intervention on
an exposed clock. The same movement choice does not improve the
pooled accepted score across other high-latency starts.

The [current v4 distance-dependent test](elite-league/boxing-trade-skills.md#transfer-to-the-distance-dependent-mmc-preset)
finds one 20 ms and one 80 ms approach with a strict three-hit
gain over W-tap in all 225 timings using a fixed offset after the
own hurt cue. The same cue offsets fail to gain fresh margin
when issued on every separate comparison approach. Existing movement
wins 14/22 regulation bouts over W-tap on the exposed model clocks
and completes 72 active K3 cycles against W-tap's 304 from 17
common states. W-tap preserves sprint knockback where the learned
movement can land the same follow-up without it. Diagnose that gap
before controller changes. The unchanged native v4 benchmark is
frozen separately from the retired v3 schedule.

The [v4 sprint attribution forks](elite-league/boxing-trade-skills.md#separating-the-first-sprint-hit-from-sustained-w-tap)
show that the first impulse contributes to the maintenance gap.
Across 73 comparison timing variants with a same-drain stronger W-tap
launch, swapping only the horizontal velocity grows learned movement
from 128 to 213 active cycles and drops W-tap from 1,375 to 661. The
best ordinary one-head Ctrl window grows the separate full comparison
from 847 to 917 versus W-tap's 3,928. Replacing all release and
chase decisions reproduces W-tap. Replacing either component alone,
or applying both only during the first 12 heads, leaves a large gap.
Target the spacing and sprint transition repeated between successive
fresh hits. The first four newly reserved v4 W-tap calendars also
yield zero strict new doubles for both frozen hit-selection delays
in 85 openings. Keep those eight requested calendars out of examples.

The [v4 observation and reply audit](elite-league/boxing-trade-skills.md#what-the-client-sees-before-delaying)
reproduces every control and finds the late 135 ms reply at 80 ms
interrupted by an accepted return in 684/729 comparison variants.
The median minimum aimed return ray is 1.589 blocks for those
failures. The clock-weighted pre-arm and post-cue selectors do
less well on accepted score than frozen constant choices and all
lose fresh-hit margin. At 80 ms every one of the 18 strict new
doubles for the 170 ms delay occurs when the first own hurt
poll sees the opponent within one block. A pre-arm local-history
forecast recalls only 23% of those close variants in its top
tenth of cuts. The post-cue threshold is too late to decide
whether to withhold the first press and loses accepted margin
against the fixed late choice. Diagnose creating that spacing
with ordinary approach movement before further selector fitting.

The [v4 crossed approach experiment](elite-league/boxing-trade-skills.md#can-approach-movement-create-the-favorable-reply)
tests 55 ordinary movement words at 20 and 80 ms. At 80 ms the
movement can create the one-block close cue in 11 separate timing
branches, with zero new strict doubles in those branches. The
sole nine-phase 170 ms success there already qualifies with
unchanged movement. On m4874 B, three backsteps with ordinary
clicking instead turn the initial contact into a miss, leave
W-tap's first hit at the same tick, and win 180/225 widened
strict doubles with +1 to +6 accepted gain. At 20 ms m5749 B
back/coast plus release on the hurt cue gives 180/225 doubles
and 171 threes without changing the nominal first exchange timing
or impulse. The single-word aggregate comparisons fall short
of reliable fresh-hit improvement. Diagnose the contact order
and denied return spacing on these states before fitting another
close-cue rule. The native current-profile benchmark is separate.

The [equal opening order and retiming study](elite-league/boxing-trade-skills.md#why-timing-alone-fails-on-the-backstep-starts)
shows that the same first two hit drains with stock movement do not
reproduce either movement witness's doubles. On m4874 B the +10 ms
own shifts omit the local immune sprint contact that would brake
the received 0.9495 horizontal motion to 0.5697. Three backsteps
with a 15 ms initial admission delay restore that contact in the
traced failed phase, reach 225/225 strict doubles overall, and
gain +2 to +6 vs W-tap. This is a selected cut with known timing
and does not transfer the retrospective movement choice.

The [two-cue W-tap handoff](elite-league/boxing-trade-skills.md#handing-established-combos-back-to-w-tap)
runs learned movement only through acquisition. It adds +16.26,
+11.74 and +5.36 mean model regulation margin at 0, 20 and 80 ms
on separate exposed calendars at matched measured CPS. The
80 ms seat B mean is −9.81 and nominal-only mean is −0.625.
The model matches W-tap's maintenance outcomes individually
in all 216 K3, 207 K5 and 153 K10 comparison branches.
Prioritize its staged native check without treating it as a
maintenance improvement or transferring the selected hit-delay
witness into it.

The [seat timing and local deficit study](elite-league/boxing-trade-skills.md#starting-on-w-tap-until-a-local-hit-deficit)
compares the 80 ms arms against each seat's own mirror. The
original handoff gains −8.03/+18.75; swapping the client timing
calendars gives +30.86/−7.25 while the server timing stays fixed.
Starting on W-tap until one observed fresh-hurt cue behind gives
51/72 wins and +14.67 mean, compared with 44/72 and +5.36 for
the original handoff and 36/72 and zero for W-tap. The exact
K3/K5/K10 results equal W-tap. The gain falls to −0.35 on
calendar-swapped development clocks. Keep the native candidate
and test schedule frozen while they wait for the shared lab.

The [native two-cue confirmation](elite-league/boxing-trade-skills.md#two-cue-handoff-in-native-first-to-100)
is complete. It wins 6/12 and averages −0.83 accepted hits at measured
17.57 versus 17.54 CPS. The mirrors average −0.17. K3 cycles total 113
versus 164 across 10 and 11 native entries. On the 12 native mirror
clocks, both frozen handoff arms preserve all 531 matched maintenance
endpoints in the model. The model scores +6.24 at 20 ms, whereas the
native mean is −17.00. Native success tracks who starts the long run.
In m7029 learned motion starts the 38-hit run and W-tap executes 36 of
its fresh contacts. In m7065 plain W-tap adds 19- and 49-hit runs and
the candidate never scores under the fallback. Exact prefix replay
separates the two m7065 failures. At 21.619 s both launches are full
strength and an earlier Ctrl restores local contact braking but loses
the short comparison to W-tap. At 34.779 s the first movement receives
no click and earlier Ctrl only ties W-tap after changing positioning.
Three and one retrospective reset times beat both controls at the two
losing sites across three timing variants. The same intervention tends
to undo the two winning trade starts. No handoff variant is promoted.

The [first-switch counterfactual](elite-league/boxing-trade-skills.md#the-first-movement-switch-explains-little-of-the-regulation-gain)
finds +0.125 accepted margin through five seconds after the
first changed input, followed by +14.67 at regulation end on
the original 80 ms comparison clocks. A switch for just 1,100 ms
scores −9.32 by regulation end. First-to-five unanswered hits
rises from 33 to 48 out of 72 with the full handoff. The local
cue tally can trigger in a tied server score. Use exact paired
later exchanges to locate which movement changes create those
runs. Preserve the first-switch result as the immediate-effect
check and score it separately from the whole-match advantage.

The [later-trade forks](elite-league/boxing-trade-skills.md#movement-after-later-trades)
enroll 1,022 cuts from 8–32 s at 80 ms. On the original
comparison clocks, a full switch gains +1.02 or +0.89
points over 4.5 s from W-tap and handoff histories, with
physical press timestamps identical. A two-input switch
gains −0.22 and +0.44. The full switch's next-450-ms gain
is only +0.045 and +0.023. The full switch gains +0.35
and +0.03 on calendar-swapped development. Keep the time
to a new five-fresh run separate from immediate hit selection.
The [paired physical readings and regulation removals](elite-league/boxing-trade-skills.md#removing-one-movement-change-at-a-time-in-regulation)
show that the common changes push the next decision's range
in opposite directions. In the original comparison, retaining
only the decision to keep W and Ctrl through a W-tap release
scores +4.01; suppressing that decision scores −14.81; the
full switch scores +14.67, with the matched W-tap mirror
at zero. Retaining both common changes alone scores −0.35.
Use these controls to ask which preceding inputs and contact
brakes make a continued sprint productive on a later exchange,
and keep the already queued native candidate frozen.

The [single-release sprint forks](elite-league/boxing-trade-skills.md#what-a-single-sprint-input-changes)
test 921 late-trade release decisions against warmed W-tap.
In 300 original comparison cuts, W and Ctrl rescue 63 and
cost 18 extensions over five timings per cut. Five cuts hold
the rescue throughout the five timings. In m4886 B the first
new sprint contact brakes recoil from 0.449 to 0.269 blocks
per tick. The next fresh hit lands 750–801 ms from the prior
hit versus 1,056 ms for W-tap. Preserve the ground flag and
positive vertical launch as simultaneous facts at the cut.
The [grounded close release rule](elite-league/boxing-trade-skills.md#the-grounded-close-release-rule-in-regulation)
uses that one-head intervention only within a 1.6-block local
gap and keeps W-tap during active continuation. Its modeled
original comparison means are −0.43, +3.10 and +21.74 at
0/20/80 ms versus zero for the matching mirrors. All ten
80 ms timing panels are positive in aggregate, with some
donor and seat losses. Exact K3/K5/K10 comparison endpoints
equal W-tap. The shared Forge rule and 64-bout regulation and
maintenance confirmation are queued; use native conditions
and results to decide whether it transfers at high latency.

The [lower-latency release audit](elite-league/boxing-trade-skills.md#release-geometry-and-latency)
finds the close grounded state in 0/269, 28/527 and 110/300
comparison cuts at 0/20/80 ms. The v4 native W-tap mirrors
have five close grounded first releases out of 533 at 0 ms
versus 51/274 at 80 ms across just three and two bouts.
The [one-contact physics forks](elite-league/boxing-trade-skills.md#isolating-contact-recoil-and-future-latency)
reduce the 80 ms W+Ctrl gain through 550 ms from +0.164 to
+0.017 when removing its first shared contact brake. Adding
that brake to plain W-tap produces +0.105. The five m4886 B
follow-ups all move beyond 850 ms if the brake is removed.
Changing future RTT with the inherited state fixed shrinks
the close-state gain and fails to reproduce the high-RTT
effect on low-RTT origins. Keep the high-RTT rule and the
0/20 ms learned movement comparisons in their current frozen
native cohorts. Record the different opportunity rates when
interpreting their native margins.

The [return timing audit](elite-league/boxing-trade-skills.md#how-soon-w-tap-returns-after-a-rescued-follow-up)
shows that at 0/20 ms W-tap returns within 100 ms in 48/61
and 85/104 rescued extension variants. A newly observed cue
and position report have an optimistic delivery path before
only three and five of those sets' return clicks. At 80 ms,
32/63 return commands are already sent when the follow-up
accepts. Test denial from earlier movement, before the cue,
and verify whether the follow-up still lands.
The [first eight native v4 fixtures](elite-league/boxing-trade-skills.md#first-native-results-from-the-frozen-movement-benchmark)
reconcile from the frozen schedule. In seat A regulation,
unchanged movement loses 97–100 at 0 ms and 75–100 at 80,
versus W-tap mirrors at 94–100 and 69–100. At 0 ms it adds
seven K3 cycles before return versus W-tap's 33 when the
drill censors. Seat B, 20 ms and repeats remain pending.
The frozen grounded release rule gains +2.56 and +6.83 on
these two newly recorded mirror schedules in the simulator.
Track them as already inspected and reserve the remaining
ten scheduled mirrors for untouched timing transfer.

The [pre-follow-up movement bounds](elite-league/boxing-trade-skills.md#spacing-changes-before-the-rescued-follow-up)
replay all 194 rescued and source-linked comparison returns
at matched physical click times. One through five selected
heads of movement, including a final chase input, produce no
new 500 ms protected follow-up. The wide screen can keep the
follow-up and deny the original return press in 3/50, 9/90
and 6/54 variants at 0/20/80 ms. The defender can still score
another return. At 20 ms the median rays at the actual own
follow-up and return clicks are 2.925 and 2.623 blocks. The
optimistic increase in the return ray while maintaining both
contacts has a median of 0.010 blocks. Keep the sprint-release
gain separate from reliable denial. Further timings should
change the launch or spacing before this pursuit window, or
account for multiple early return clicks. The frozen native
cohorts remain separate from this hindsight movement screen.

The [preceding launch forks](elite-league/boxing-trade-skills.md#changing-the-preceding-knockback-launch)
test all five timings on the 70 prior release states. All
70 previous packets are +0.361375 vertical under the native
profile, which uses the same vertical magnitude for sprint
and normal hits. Switching the horizontal strength finds
no new 500 ms protections. Normal-strength horizontal launch
cuts the 20 ms sprint-release extensions from 99 to 22.
A single artificial +0.1 lift increases protection from
4 to 16 at 0 ms and from 3 to 13 at 20 ms. The protected
80 ms count stays nine and its paired margin falls. Only
m5743 B is robust across five timings. The return ray at
nominal timing goes from 2.730 to 3.406 as the defender stays
airborne and farther away. Use this witness to distinguish
what ordinary changes in launch timing and ensuing position
could reproduce from what requires the hypothetical impulse.

The [preceding-click timing forks](elite-league/boxing-trade-skills.md#timing-the-click-that-leads-into-the-follow-up)
move the common cut to physical press admission and reproduce both
unchanged controls on every unshifted start. Delaying only that click
produces protected doubles with higher short-window accepted margin than
either unchanged control in 40 of 350 selected timing variants, seven
with exact press counts. One fixed delay works across all five timings
of m5779 B at 20 ms, but uses 37 rather than 39 own presses. No state
repeats the gain across all five timings with equal actual presses. The
artificial-lift witness loses the original opening hit at the nominal
timing with a 30 ms delay and concedes four fresh hits. The model cases
are development diagnostics. Keep acquisition and native maintenance
validation separate.

The [completed frozen movement benchmark](elite-league/boxing-trade-skills.md#completed-native-benchmark-for-the-frozen-movement-bot)
has 48 completed native fixtures. The candidate wins 5 of 12 regulation
bouts at measured 17.51 vs 17.52 CPS, mean margin −15.08. Score is worse
than W-tap from both seats at 20 ms. In the K3 drills the candidate adds
28 cycles from 10 entries versus 155 from 11 for W-tap, with ten returns
each and one W-tap censor. No promotion. Keep the already frozen close
release and two-cue comparisons independent. The [distance-based W-release pilot](elite-league/boxing-double-findings.md#comparing-the-w-release-pilot-on-common-clocks)
matches the first nine accepted attacks on the m6980 clock, then diverges.
Its model lead is concentrated in seat A at 80 ms and the exact maintenance
forks give the same 416 K3 cycles as W-tap at 80 ms. Resume the original
native reset schedule using intact request IDs and add matched 20 ms
regulation and independent maintenance repeats. The 100–3 native fixture
establishes a long example and does not settle the comparison.

In m7020 at 20 ms, the frozen movement bot leads 15–13 at the last
trade, then gives up 87 fresh hits. Its outgoing normal horizontal
launch is 0.5275 because the server sprint flag had remained clear
since the previous accepted attack. W-tap deals 0.9495 and further
brakes its own incoming knockback to 0.3165 with a valid sprint
contact before travel. The candidate's same-stage contact lacks that
brake. The first separation difference precedes all 87 hits. On the
reserved model clocks the close grounded rule averages +42.96 over
W-tap across the three new 80 ms calendars and preserves matched
maintenance, but averages −2.00 at 20 ms and −1.56 on the only
eligible 0 ms donor. Keep the high-latency native confirmation frozen
and diagnose sprint continuity and first travel at 20 ms separately.

The [strict native trade forks](elite-league/boxing-trade-skills.md#which-earlier-sprint-inputs-change-the-87-hit-loss)
replay the native m7020 lead and the losing trade exactly. An ordinary
one-tick W release followed by a restart restores the strong hit in 16
of 17 potential input windows at the losing trade, changing 1–4 to
even in those nominal short runs. The primed W-tap movement is also
even. At an earlier m7020 trade the same two-input reset restores sprint
in 1/17 windows versus W-tap in 17/17. At m7007 B it restores sprint
in 15/17, with no beating-W-tap result at nominal timing. Fixing only
the m7020 outgoing velocity gives 2–2, only the incoming contact brake
gives 4–5, and both artificial changes give 4–3. Combining the actual key
changes gives 4–4 in that witness because the opponent's return shifts
to a later tick. No release timing beats both baseline arms across the
recorded suffix and both ±0.5 ms perturbations. Use this distinction
between a missing sprint message and a message arriving behind the attack
when diagnosing low-RTT trades. No new controller was selected.

The [single close sprint input forks](elite-league/boxing-trade-skills.md#what-one-close-sprint-input-changes-in-the-native-recordings)
reconstruct complete states in earlier W-tap mirrors and in the first
six close-rule bouts at 80 ms. In 16 mirror cuts the hold increases
unanswered fresh follow-ups from two to six at nominal timing, with
zero 500 ms protections in either arm. In 41 qualified candidate
cuts it improves 15 margins and loses 14, averaging +0.20 hits over
the next 2.2 seconds. Protected follow-ups rise from one to four.
The 45-hit native m7087 chain starts after a restored early sprint
brake. Its first four hits change from 4–0 to 2–2 when isolating the
0.60 horizontal multiplier at the shared contact. The isolated
brake reproduces the 4–0 in the release arm. In m7096 the early
grounded movement and contact brake both contribute. In m7102 the
brake already occurred before the head and keeping W adds 0.1274
blocks of forward displacement on the first step. These effects
can disappear with delivery shifts. Keep the first 2.2 seconds
separate from causation of a whole streak. The 61-hit m7100 run
begins 9.358 seconds after its last hold. Do not infer robust
native improvement from individual blowouts.

The [completed close-release cohort](elite-league/boxing-trade-skills.md#completed-native-close-range-comparison)
verifies all 64 native bouts. At 80 ms the candidate wins 7/8
with +23.88 mean and +13.5 median at 17.54 vs 17.55 measured
CPS. W-tap mirrors go 4/8 at +9.25 mean and +1 median.
The 80 ms mean difference is +41.25 from seat B and −12.00
from seat A. The 0 and 20 ms candidate cells each go 2/4,
and exactly two 0 ms bouts never trigger. The candidate
maintenance drills appear to gain 278 vs 125 K3 cycles,
yet capture confirms zero changed inputs in any first-K3
continuation. The same-state model maintenance outcomes agree.
Keep W-tap as the reference. Continue the separate frozen
distance-based release audit and diagnose why the existing
W-tap holds the same long runs before considering rule changes.

The [ordered delivery probes](elite-league/boxing-trade-skills.md#what-changes-when-a-position-update-crosses-a-client-tick)
retain nominal accepted-hit parity on all 72 qualified 80 ms
starts. A +0.5 ms shift applied only to early position
updates to the tested client lets the plain-release arms
match the hold in m7087 and m7102. A single update can move
to a later client processing slot by tens of milliseconds.
In m7102 one delayed update instead spoils the hold. Of 56
native candidate cuts, three improve score at all 25 route
and timing probes. None of 16 mirror cuts qualifies. No
cut adds 500 ms protection over W-tap at every probe.
The stable short-margin m7096 witness survives, so separate
replica timing from actual pursuit and recoil braking.

The [native long-streak starts](elite-league/boxing-trade-skills.md#how-plain-w-tap-starts-long-native-combos)
show that equal server impulse does not imply equal retreat.
In m7111 both W-tap players take 0.9495, but one brakes to
0.5697 before its first step while the other keeps full
recoil. Their first steps differ by 0.5072 blocks and the
advantaged player scores 66 fresh hits in a row. In m7100
stock W-tap creates the same asymmetry after a weak and a
strong launch. Doubles per enrolled 80 ms fresh-run start
are 39/253 for the close rule and 69/476 for the mirrors.
Treat these correlated rates as descriptive. Future matched
diagnoses should vary timing before the main-thread replica
update and preserve both known native recoil-brake mechanisms.

The [completed W-release confirmation](elite-league/boxing-double-findings.md#completed-w-release-regulation-and-maintenance-comparison)
passes all 72 physical audits. The distance rule goes 1/4,
2/4 and 2/4 with median margins −4, +0.5 and +25 at
0/20/80 ms. Plain W-tap mirrors go 4/4, 4/4 and 1/4.
The 80 ms candidate wins twice from A and loses twice from
B. Independent K3 drills for the separate 105 ms rule
change 86 inputs across seven drills and give 111 further
cycles with seven returns versus W-tap's 206 with five
returns. The [new model transfers and strict forks](elite-league/boxing-double-findings.md#which-clock-and-movement-changes-account-for-the-reset-result)
reverse the 80 ms seat advantage when rotating only the
client calendars. At nominal m7171 timing two ordinary
W-tap inputs keep the losing side even by avoiding the
early close brake and restarting one move later. A +0.5 ms
delivery change reverses the outcome. On new exact K3
forks, longer releases preserve m7153 B and give away
m7185 B by missing the next reachable window. Keep W-tap
as the maintenance reference and pursue a state-specific
return-denial margin ahead of the next fresh window.

The [paired one-input release study](elite-league/boxing-double-findings.md#first-release-and-reach-at-the-next-fresh-window)
finds two 80 ms regulation witnesses where skipping W-tap's
release reduces grounded recoil and enables the next sprint
contact brake. One is on a separate, previously exposed
calendar. Both hold under separately tested ±2 ms attack
and replica receipts. The K3 comparisons give no maintenance
gain from either release change. A fit on development
native inputs classifies the next displayed gap correctly in
447/479 separate first-resume samples, with 41/66 correct
within 0.35 blocks of the reach boundary against 36/66
from current gap. Preserve the difference between forecast
gap and a registered attack. No new live controller.

The [two-input reset ordering study](elite-league/boxing-double-findings.md#walking-or-sprinting-before-the-release)
matches physical click timestamps in 11,934 short comparisons.
Sprint first and sprint then release both produce 100–6
from m7198 A at all 17 joint receipt timings, versus
plain W-tap's median +21 and paired gain +61 to +113.
The extra early brake brings a weaker launch into the
first fresh window. The related m7143 A model cut gains
overall but loses in three phases. Walking first protects
the next hit on m7142 B at all phases and loses full
regulation margin at the median. On 10 exact K3 cuts
no variant has a gain at every phase and the best nominal
total ties W-tap. Treat the 100–6 as a selected model
witness. Separate the strong grounded recoil state from
airborne and already-advancing starts, and check which
own-view cues predict full score on new timing calendars.

The [prospective grounded recoil test](elite-league/boxing-double-findings.md#prospective-test-of-the-grounded-recoil-rule)
collects 12 new native W-tap clocks and qualifies 11 for
same-clock model comparisons. Across six nominal 80 ms
seat pairs the rule goes 3/6, as does W-tap, with paired
median −8.5 and range −29 to +84. At 0 and 20 ms the
nominal model moves and scores agree. The rule advances
our follow-up on all 24 nominal admitted cuts and exposes
an earlier opposing return on nine, including six damage
upgrades. In m7209 A its pursuit makes the same defending
click from a 3.331-block miss become a 2.979-block hit.
It accepts 101 ms before the improved own fresh hit.
K3/K5/K10 endpoints all agree with W-tap. This failure
calls for a causal estimate of return exposure before
the fresh window, including upgrades. Keep the frozen
prospective comparison as scored.

The [matched packet and movement return audit](elite-league/boxing-double-findings.md#which-earlier-returns-can-one-more-movement-input-deny)
links all 121 harmful recoil variants to a physical click
that misses in W-tap. Removing that attack before its
server drain keeps all early own hits and restores only
23 protected follow-ups. One next-head release protects
m7207 A in all 17 timings and scores a modeled 100–85
at nominal versus 93–100 for W-tap. The same fixed move
over all 24 full nominal forks loses a median two points
relative to W-tap. Even a hindsight veto for the early
return has median zero. In the factual 80 ms mirrors, 14
of 26 qualifying local release heads fall after the
first accepted return. The existing gate comes too late
to affect those exchanges. Diagnose prior approach before
more post-release corrections. Full match score and the
established-combo comparison remain separate.

The [pre-commit approach forks](elite-league/boxing-double-findings.md#moving-before-both-attacks-were-sent)
test all 14 factual 80 ms two-click exchanges from strict
native prefixes. At offsets 40 through 240 ms, none of the
tested words keep the own hit and make a protected double.
One m7203 A exchange works from the 290 and 340 ms cuts.
Three backsteps then release at the 309 ms actual lead
improve short margin by +3 to +5 over recomputed W-tap
across nine attack/position timing settings. At +2 ms own
attack receipts, an earlier press starts the four-hit run
at 2.989 blocks. Four backsteps miss it at 3.007. An
own-view resume rule transfers to eight other exposed
starts, all after 3–60 unanswered fresh hits. It changes
W-tap's 72/72 doubles to 1/72 and loses W-tap's next
contact in every nominal case. Future work should enroll
threatened first- and second-hit situations separately
from already established streaks. Preserve the K3/K5/K10
comparison and require independent native full scores.

The [short-streak resume study](elite-league/boxing-double-findings.md#protecting-the-third-hit-from-a-short-streak)
verifies 159 native-input forks. One additional seat-A
K2 case at 20 ms, m7204, uses two backsteps to reach the
third and fourth fresh hits. Its original defending
click changes from a 2.919-block gap to a 3.515-block
miss. Both this case and the m7203 A three-backstep-plus-
release word continue under all separately tested ±2,
±5 and ±10 ms receipt shifts against recomputed W-tap.
The 20 ms nominal short score ties the recorded-key
reference. The two words do not transfer to one another
or improve median margin across the separate sample.
m7203 loses its gain one decision head later. A local
cue count matches 13/15 real K2 heads, with one K3+
false admission. Nine of 51 sampled threatened returns
are already clicked at the observed resume. Prioritize a
causal separation budget for the two clicks and measure
the attainable retreat before adding another selector.

The [two-click spacing audit](elite-league/boxing-double-findings.md#how-much-retreat-keeps-a-hit-and-denies-the-return)
links 48 overlapping press pairs from the 159 native-input cuts.
Outside the two case-study clocks, seven words keep and score
the original own click and deny the original return in all
nine timings. A different defender click still breaks the
streak in 61 of those 63 variants. On m7205 A, another word
makes both original clicks miss and scores four additional
fresh hits to recomputed W-tap's zero at nominal timing. A
0.25 ms position delay moves the new scoring ray from 2.992
to 3.189 blocks. A 0.75 ms attack receipt delay keeps that ray
and moves the hit into the next drain. Both permit a later
return. Exhaustive short movement words at five starts and
late three-head changes never pass more than seven of nine
settings. Diagnose whether there is enough spacing to deny
this later return even when our first effective hit slips by
one drain. These are exposed, fixed-click model diagnostics.

The [target tracking comparison](elite-league/boxing-double-findings.md#a-late-return-against-live-target-tracking)
finds three sidestep words giving a double in 25/25 timings on
m7205 A with recorded aim. The late defender click misses at
2.294 blocks. With the defender tracking its visible target
at realistic mouse frame opportunities, that click contacts
in all 25. Repeating the 6,735-word search with both fighters
tracking gives the late click a gap above three in both weak
phases for 351 words. Another return still scores before the
second follow-up in those cases. The known m7203 A and
m7204 A longitudinal retreats do retain their positive
score and double in all 25 timings with tracking. Neither
repeats across all nine timings at any of the 11 other K2
cuts, and those 11 contain only three threatened starts.
Use separate new native schedules to enrich threatened K2
for both seats and require a safe window across multiple
potential defender presses. Keep recorded-aim findings as
diagnostics and grade against W-tap using recomputed aim.

The [prospective K2 clocks](elite-league/boxing-double-findings.md#prospective-native-clocks-put-the-return-before-the-retreat)
yield 24 native first-resume opportunities over 12 new
regulation mirrors. Twenty-three qualify for model comparison.
Neither frozen retreat protects two follow-ups and improves
score at any threatened cut. All ten factual resume threats
are physically clicked 11–80 ms before the resumption
decision. Of all 276 threatened two-hit anchors, 156 returns
were clicked before acceptance of hit two and 234 before our
first subsequent native decision. Every case has an own head
carrying the first hurt cue at least 150 ms before the return
click. Use that cue as the earlier intervention point while
requiring preservation of the second and third hits. Keep
these calendars as prospective evidence for the frozen K2
test. They become exposed for use in later investigations.

The [first visible cue forks](elite-league/boxing-double-findings.md#acting-on-the-first-visible-hurt-cue)
score 527 of 528 cuts, with an advance decision before the
original return press at all 425 qualified threatened cuts.
No fixed word gains both double and score across all nine
timings in the six-clock comparison panel. In the m7219 B
development case, a single W+Ctrl head holds sprint and
lets the next contact brake −0.449 to −0.269 blocks/tick.
That plus the initial movement gains a 500 ms second hit
versus 899 ms for matched W-tap and two further hits
before return at nominal time. Holding sprint without the
brake loses that early hit. A release plus the same
artificial brake gets it at 801 ms. The W+Ctrl word wins 25/25 receipt
variants. The separate m7222 B case wins 21/25, losing
to an earlier damage upgrade under later defender position
receipts. m7224 A wins 6/25, depending on own first-window
reach. Before tuning any gate again, diagnose the reciprocal
contact margins at the first fresh window and the earlier
upgrade presses. m7222 is now exposed for future diagnosis.

The [post-brake pulse and timing study](elite-league/boxing-double-findings.md#post-brake-movement-and-the-client-task-deadline)
tests 288 later moves after the first W+Ctrl. On m7219 B
two backsteps add a fresh hit in 7/25 independent timings,
but both the original and adjusted word lose their double
and score advantage in some of the sixteen joint ±2 ms
settings. A score-selected sidestep improves m7222 alone
and fails to transfer across the 18 other comparison cuts.
In m7222 and m7224 a single position packet shifted across
a recorded handler opportunity flips the critical ray. Its
native enqueue already led the observed drain by 5.883 or
11.524 ms. Holding that one handler sample fixed removes
each ray flip. Across 20,117 native opponent position
packets, 87.7% had at least 2 ms of enqueue lead. Treat the
previous timing shifts as handler-time stress. Next compare
a readiness perturbation through observed task-drain batches
and quantify model parity, particularly on packets generated
by a changed close approach. Do not tune the player gate to
compensate for shifts the native queue would absorb.

The [client queue replay](elite-league/boxing-double-findings.md#replay-by-client-queue-readiness)
links 12,990 position timing samples and preserves native
accepted hit order and launches on all 21 validation cuts.
It reproduces 1,042/1,059 exact position handler events.
The client queue monitor holds through the drain, so use
the late mapping for arrivals during a batch. Fitting
median enqueue residuals on six clocks predicts the
correct drain for 6,300/6,517 native source packets on
six separate clocks, versus 6,103 with the old residual.
With the queue adapter, m7222 B's sprint follow-up passes
all 25 position-only settings. m7224 A loses its earlier
single-route success, and m7219 B misses the first window
at nominal time once generated positions can apply at
recorded client task opportunities. Across the 18 separate
recoil cuts there is no robust double improvement, though
one sprint and one later-backstep cut improve accepted
score in every tested setting through different mechanisms.
The attack side of mixed timing settings still uses
handler shifts. Audit attack enqueue and server drain
readiness, then validate generated-packet dispatch against
actual altered native inputs before tuning an own-view gate.

The [server attack queue replay](elite-league/boxing-double-findings.md#replay-attack-arrival-through-the-server-queue)
links all 6,342 source attacks. It preserves the native
accepted sequence and launches in 21 recorded-input cuts.
On eight separate cuts from native W-tap mirrors and prior
distance-based W-release fights, hiding attack timing still reproduces the
accepted sequence and launches on all eight. It serves
319/323 attacks in their original batches.
m7222 B's frozen sprint tick now gains extra unanswered
fresh hits and improves accepted score in 52/53 matchups
with the same model W-tap, including all 16 joint ±2 ms
settings. Its short score improves at least +2 in all 53.
At +4.0 ms of own upload delay the decisive attack still
selects the target but moves one server drain later and
gives up the active double. A different earlier attack
restores the streak at +10 ms. None of the other 18 cuts
gets a robust double. The altered-input check is a replay
of observed play. Keep the candidate frozen pending a
prospective matched native intervention around the
first-step recoil and fresh-window phase.

The [prospective native sprint intervention](elite-league/boxing-double-findings.md#native-one-tick-sprint-intervention)
tests a single W+Ctrl head on the first local qualifying
release in 24 independent timed bouts. Two 80 ms seat A
starts convert, versus zero W-tap controls. m7238 and
m7244 get 40 and 24 further hits before censor or return.
Matched full-state replay gives four versus one follow-up
on both, with double and score gains in all 25 timing
settings. The native 80 ms median score is +1 versus +1.5
for W-tap; no promotion and no change to the maintenance
gate. In m7228 the same step and brake give W-tap an
upgrade at 459 ms, while the matched W-tap replay mostly
denies that press. At all 15 activations, the own-client
ordering of applied local and target hurt status agrees
with native fresh-hit order. It identifies all nine
already-returned starts and retains both native successes.
Test this ordering on comparison clocks before using it
for live decisions; then measure the early falling upgrade
window separately from own first-fresh reach.

The [hurt-order comparison and return boundary](elite-league/boxing-double-findings.md#validating-hurt-order-and-the-early-return-boundary)
validates all 63 eligible releases on 24 prior
W-tap mirrors. It identifies all 33 scored returns
already seen on the subject client. Requiring own
hurt first chooses 17 instead of 25 activations.
Matched replay short margins sum to +17 versus
+11 for the original admission, with medians zero
and one unknown under both. Newly selected m7209 B
at 80 ms gains +4 to +6 and improves doubles in
all 53 timing variants on a previously exposed
seat-B clock. That intervention still needs a prospective
native test. In m7227 and m7228, 42 later pulses
and all 64 first-three-head words fail to retain
own timely reach while denying the upgrade.
In m7228 reversing after the brake moves our body
0.525 farther at the opposing click but changes
its interpolated view by just 0.0026. Preserve
both native long-combo witnesses. Run a guarded
one-sprint versus W-tap test at native regulation,
record both actual click rates and the seat spread,
and keep the K3/K5/K10 promotion test separate.

The [guarded sprint comparison](elite-league/boxing-double-findings.md#prospective-native-regulation-and-maintenance-with-the-hurt-order-guard)
completed all 28 native first-to-100 bouts at closely matched actual rates
and 20 independent maintenance drills. The guard wins 5/14 with median
margin −3.5 versus W-tap's 11/14 and +2. Both permitted native sprint
activations are at 80 ms and give up a return before a double. All 21
rejected regulation heads are after a return. The 110 closed-loop paired
model matches show median paired gain zero, with large opposite seat
effects at 80 ms. Exact-prefix forks of the seven permitted native heads
produce no double and score improvement across all 25 timing variants.
No input changes occur during a scored native or common-state model
K3/K5/K10 continuation. Keep the one-tick sprint as a diagnostic and
retain W-tap as the reference. The new native tail audit traces a lost
window through 17 out-of-reach presses in m7244 and a sprint reduction
between knockback application and first movement on the 66th hit in
m7111. Build the next maintenance diagnosis around those concrete reach
and timing boundaries before adding controls.

The [established-combo native windows](elite-league/boxing-double-findings.md#where-established-w-tap-combos-give-the-return-away)
track both seats of 38 W-tap regulation mirrors. After at least three
unanswered fresh hits, 22 of 25 returns beat the next native fresh window,
including six clicks before the last outgoing anchor reached the server
and 16 between knockback application and the defender's first travel.
All nine recoveries that land five or more ticks after the prior fresh
opening give up a return in the following cycle. An existing own-view
fresh-window estimate plus range above three blocks identifies those
nine about 311–519 ms before the eventual return click. The measured
two-to-four-tick delays still show local contact near the prior window.

The [received-cue movement forks](elite-league/boxing-double-findings.md#acting-on-the-attackers-received-hurt-cue)
preserve m7111, m7207 and m7223 with a brief back/release/sprint at the
preceding visible hurt cue. At the final hit the return is already beyond
the tested movement's influence. The same cue rule on the wide m7244
precursor loses its next active cycle in all 25 timings. The registered
earliest K3 starts lose cycles in 25 variants and gain in zero for this
spacing change. Keep W-tap as reference and diagnose the spacing and
launch state that distinguish near mutual re-entry from wide missed
pursuit. The future-return-labeled examples are causal model evidence,
with none promoted as a playing policy.

The [pre-landing launch audit](elite-league/boxing-double-findings.md#a-fresh-hit-just-before-the-defender-lands)
links 671 native W-tap mirror windows to receiver physics. All nine
severe pursuit misses start when velocity is reapplied one step before
the defender lands, at 0.309409 blocks and −0.350413 blocks/tick. The
other 662 launches in this panel apply on the ground. The separate
m7244 witness combines an airborne reset with a much wider 5.361-block
gap at receipt. In the [matched launch forks](elite-league/boxing-double-findings.md#changing-launch-or-contact-before-the-wide-window),
deferring only S12 across the next movement recovers timely protected
reach in 202/250 wide-start variants versus 0/250 for stock W-tap. An
ordinary missed press gains active cycles and short margin jointly in
200/250, with one or more original opportunities lost. Three W-only
heads before the native press keep 141/150 original wide-panel shots
and recover 66/150 timely protected continuations. The same move costs
cycles in 64/100 variants on ordinary healthy starts. The [own-observation phase audit](elite-league/boxing-double-findings.md#can-the-attacker-spot-the-landing-step-in-time)
finds that scalar thresholds and bounded relay timing cannot reliably
separate the two receiver landing phases at the action head. Diagnose
local information about joint packet and movement phase or seek ordinary
inputs robust to both phases before a new policy or native promotion.

The [crossed landing movement screen](elite-league/boxing-double-findings.md#movement-on-both-sides-of-the-defenders-landing-step)
found no pre/post-hurt-cue word that improves maintenance and accepted
margin in both receipt phases without a timing loss on any of the four
development or six previously exposed comparison cuts. Under those
same tested controls, knowing which phase occurred raises the number
of development clock variants with a positive margin in both branches
and no cycle loss from 0/36 to 17/36. The crossed packet application is
an offline intervention. Preserve the late/missed original-shot
variants at m7257 and keep ordinary W-tap as the reference.

## November 20 MMC sumo input experiments

Across three matched PPO training seeds, removing direct own velocity cut the
score against six learned opponents from 50.8% to 24.7%, even as the W-tap
matchup stayed above 93%. With frozen weights and fresh clocks, removing just
vertical velocity cut the whole-field score from 67.1% to 49.5%. On both
native seats and asymmetric added RTTs, the intact actor went 2–1–1 against
shift-check and the vertical-blind actor went 0–2–2. Both swept W-tap 4–0.
Keep the vertical reading and the visible hurt timings in the sumo reference.
The eight-unit edge screen winner swept shift-check, then lost all four to it
on confirmation. Preserve the screening and confirmation records separately.
The four published bracket winners, individual motion and timing ablations,
and historical and current opponent scores are in the
[sumo experiment report](elite-league/sumo-inputs/README.md).

## November 19 measured sumo arena

Use the most frequent exact fighting surface from the MMC replay survey for
regulation sumo. On the 13×13 platform the players start eight blocks apart on
the gold block centers. Retain geometry metadata when comparing tournaments.
The previous 10-block lab disk and its six-block opening belong to the earlier
evidence. [Source measurements and coordinates](mmc-arena/README.md).

## November 19 matchup priority

Give ordinary 20-CPS W-tap and the weakest distinct opponent their own scores.
Seek a decisive W-tap win rate across seat and latency and repair the weakest
matchup without discarding the generalist field. Track true mirrors separately.
The [targeted tournament](elite-league/focus-protocol.json) freezes the current
weak opponents, comparison field and confirmation thresholds.

## November 19 overnight direction

Every completed tournament publishes its actual overall winner under a permanent
duel name on the main test server. Keep the best new explicit and neural entries
separately when an older bot wins. Grade broad performance against individual
historical opponents and opponents that change tactics within a duel. Use only
the bot's own observations for adaptation. The strongest independent human
players remain the ultimate test. Nomophobia supplies development evidence.

## November 19 tournament ladder

The grading opponents are independent, world-class human sumo and boxing
players. Nomophobia's recordings are authorized development examples from one
player. Train and refine both explicit and learned opponents across repeated
tournaments. Keep earlier opponents, examine winning and losing trades, and
run the prospective Minecraft comparisons before choosing a competitive bot.
The [league results](elite-league/RESULTS.md) give the matched W-tap scores,
physical-fidelity tests and status of the human evaluation.

## November 18 direction for boxing and sumo

The goal is a legal 1.8.9 bot that consistently beats 20 CPS W-tap in boxing
and sumo. The primary practice preset is provisional MMC with the sprint
bonus read at attack entry. Historic MMC matches used the last sprint toggle.
Test transfer to other knockback settings, including the Hypixel-like preset.
Use the main server for experiments. The owner permits restarts while playing.

Isolate a skill, establish its causal advantage over W-tap, then test how it
combines with the other skills. Start boxing with a no-click opening for the
defender, then measure maintenance from active opposition. Repeated local
sprint contacts can brake recoil several times. Account for every contact,
intervening movement and received velocity. When testing a model fork, replay
identical controls on both bots and compare both policy histories, queues, clock
and final state. Exact live Minecraft resumption is still unverified.
The first matched click-timing drill is in [recoil-drill](recoil-drill/README.md).
In that drill the general selective-click rule lost maintenance time against
W-tap in all seven common MMC model states. On the main server the three
selective-click MMC boxing entries drew a return before a fourth fresh hit.
A retrospective fork omitting just four forward-braking contacts yielded ten
additional hits versus W-tap's one in a single development fixture. Similar
omissions cost hits elsewhere. Train and validate a decision from own-view
history before making this a bot rule. In a separate main-server 10-block
sumo replication, Hold-W won three of four despite zero sprint bonuses on 25
follow-up hits. Plain W-tap won two of four mirrors and earned 36/40 bonuses.

The [trained click-pause experiment](learned-click-pause/README.md) froze a
four-tick decision from own-view features. It gained hits in 40 of 99 shared
three-hit MMC model starts and tied in 59. With recorded client tick intervals
substituted after the same landmarks, 25 of 372 covered continuations gained,
344 tied and three lost. Both full-reach native MMC boxing arms had a median
+3 whole-round hit margin. The trained arm never paused in the 48-round
full-reach benchmark. With a 2.25-block defender it took five real pauses.
W-tap completed 85 further hits across 21 entries and learned completed 70
across 18. One W-tap control sustained thirteen more hits after four successive
forward-braking contacts. This rule stays experimental. Calibrate when physical
contacts change return timing before extending it or claiming transfer.
The test phases and diagnostic donor recordings in this study are now exposed.

The [frozen pressure-policy experiment](sumo-rl/RESULTS.md) recorded 14/16
native MMC sumo wins against 20-CPS W-tap, versus 7/16 for plain W-tap.
On the subset shared with the movement-only control, pure Hold-W and the
learned arm both won 7/8. The separate native jump-reset opponent lost 8/8
to pressure and 2/8 to W-tap. In the boxing drill the learned arm completed
seven cycles across fourteen three-hit entries; W-tap completed nine across
eight. Plain W-tap had server sprint on every third-hit landmark while
pressure had it on none. Keep W-tap as the boxing reference. The held-out
human cohort is unplayed. The final clock donors m2375–m2382 are now exposed.

## HARD RULE: plain W-tap is the mandatory baseline

ALWAYS compare against plain W-tap under identical settings in both the model
and native Minecraft. Every results table gets a W-tap column. Match reach,
both RTTs, KB/hurt-time profile, CPS cap, reaction/aim, duration, initial state,
opening handicap and metric. Include several seeds/tick phases or native repeats
and show spread. Hold-W is an additional control, never a substitute for W-tap.
Historical unmatched results remain explicitly unqualified until backfilled.

CURRENT PRIMARY GATE (user maintenance clarification): survival once a combo
is established. Default landmark: three consecutive unanswered fresh hits AFTER
the defender starts clicking; sensitivity landmarks five and ten. At that state,
measure accepted-return failures per exposed hit-to-hit cycle, remaining fresh
hits/time, horizon survival, and right-censoring. Damage upgrades are returns.
Acquisition/entry probability is separate and secondary. Score return-only
survival and inactivity-qualified survival separately: inactivity is an
inter-fresh gap exceeding twice the native hurt window plus100ms. This is an
activity proxy, not proof of reach at each fresh window. Apply identical scoring
to W-tap; retain POV/readiness/missed-window diagnostics separately.

Fork candidate and W-tap from the SAME complete model midstate with primed
histories and queues. Native matched acquisition protocols do not reproduce
identical OS timing or packet queues; measure seed states/phases and label that
limitation. Separately conditioned survivor cohorts are descriptive, not causal
comparisons. Report trajectory/calendar spread; cycles in one trajectory are
correlated, and zero failures does not prove an infinite lock. Keep historical
fixed-start first-return results under their original definitions.

If the controller/search does not clearly beat plain W-tap on this task across
the tested spread, STOP ADDING CONTROLLER FEATURES. Diagnose what W-tap gets
right and what the candidate gets wrong: inputs/contact drag, sprint state,
spacing, launch/height phase and packet timing. A more elaborate controller or
one favorable sample is not progress. Resume development from a concrete causal
diagnosis grounded in matched baseline evidence, not another blind variation.

For oracle maintenance tests, fork candidate and baselines from the same complete
state, including queued packets and immunity. Prime baseline observation history
during acquisition so switching at the seed does not invent a new hurt cue.
Report whole-fight acquisition separately. A baseline must not receive stricter
or looser scoring than the oracle. The historical `combo-wtap20` policy defaults
to150ms reaction; explicitly label it and include a zero-delay plain W-tap
reference for zero-delay research controllers rather than silently changing it.

## Current follow-up: characterize established combo maintenance

LATEST PRIORITY — reproducible native seeded maintenance. Add a bot-only lab
command to declare and restore owning-client/server positions, velocities,
hurt state, sprint/facing, and both rendered replicas/caches. Compare candidate
and plain W-tap from the identical seed, with preparation traffic quiescence and
actual release phases audited. A synthetic empty-queue seed is not an arbitrary
saved JVM snapshot; do not claim copied OS scheduling or omitted pending work.
Search robust precomputed continuations from one-sided-reach states, optimizing
the worst denial margin over many timing samples while retaining fresh-window
own reach. Keep held-out calendars, UNKNOWNs, timing spreads, matched W-tap and
native replays. Native current-state real-time oracle is now DEPRIORITIZED;
precomputed own-input plans from seeded starts are explicitly allowed. The task
remains open until a watchable reproducible Hypixel-like lock is demonstrated,
or a rigorously scoped robust boundary is characterized and its opening knob
demonstrated. Work is under `seeded-maintenance-20261101/`.

SEEDED CHECKPOINT: `/lab seed` now restores182 declared native fields/caches;
six independent pairs-attempts m1459–m1464 pass literal, queue, packet-boundary
and scoped source/build qualification. These are asynchronous synthetic empty-
queue states, NOT original JVM snapshots. The frozen model plan gets2/2/2 freshs
before a return versus plain W-tap3/3/3 (separate1000ms inactivity endpoint2each).
Do not add controller features: reconstruct actual native clocks first. Defender
HEAD release is48–50ms after A, versus model22.143ms; startup HEAD→MOVE stalls
64–82ms warm down to7–20ms in later repeats without changing scores. Original
bad gzip copies are preserved; completed source archives qualify allsix.
No sealed calendars were opened. See seeded-maintenance native/paired-v1-findings.

ACTUAL-CLOCK CALIBRATION: importing m1459–62 native host ordering and conditional
stage/semantic service timings reproduces all four observed2/3/3/2 outcomes,
fresh/return stamps within2ms. Seven of eight client MOVE streams differ only
by arena-translation floating precision; warm WT defender max scalar error is
0.000376. Wire-only residuals put its first S12 one drain too early; measured
attack-to-emission/task timing fixes that. Strict opposite-policy counterfactuals
retain missing-cell UNKNOWNs. The separate73-tuple empirical sampler reproduces
native counts on development and timeline-unused m1463/64 (outcomes already
exposed), with3/32 UNKNOWN per arm in both splits. No redraw or hidden fallback.
Matching replica position-counter/goals leavesWTcounts unchanged and fails to
rescue the plan. Resolve/bound distinct-drain service-envelope composition before
search; do not optimize around unsupported schedules. No new controller or
native matches during these calibrations. See seeded-maintenance native/
actual-calendar and native/predictive-calendar; original m1415–18 remain SEALED.

WHOLE-DRAIN FOLLOW-UP: optional exact-FIFO native service templates remove the
distinct-envelope composition error; default/exact-recorded clock unchanged.
The original gate fails at WT5/32 training,7/32 validation UNKNOWN. A frozen
consensus-only wall-clock wrapper reduces these to3/32 and1/32 without choosing
an ambiguous action; long-stall/real control ambiguity stays UNKNOWN. Fine
service intervals still under-cover, confirmed prospectively by m1465–68.
That new matched-base native batch gives plan2/1 versus WT2/2, allfour setup
qualified and no first-return100ms timing warning. No lock or positive controller
result. Preserve all earlier failed gates. Search may next perform only the
bounded proposal-generation scope in seeded-maintenance native/robust-search/
proposal-scope.md after cache parity and the frozen2x2 diagnostic: full observed
service-support sensitivity, paired WT, no probability/fine-margin guarantee,
then frozen native confirmation. New m1465–68 are sampler/seed validation,
not training/example calendars. No real-time native oracle or human-client edits.

CONTINUATION SEARCH CHECKPOINT: actual-clock reconstruction of m1465–68 matches
all four observed hit counts,262 own MOVE and262 replica snapshots within2e-12
blocks; conditional timing is supported, predictive fine margins remain uncertain.
The frozen shared-word beam now yields3freshs on all eight development samples
versus W-tap2–3 (four wins/four ties), but no fourth hit or lock. It delivers
N/N/S; all fourth fresh-window rays miss after the sprint launch plus coasting.
The5-lane frontier dies after51 HEADs/3996 proposals, so this is not a universal
feasibility boundary. V1's terminal-retention bug is preserved; V2 changes only
final retention and reproduces all51 lane logs, rescoring173 words. No validation
or native promotion. Next diagnostic is a single ordinary-input suppression of
the third sprint re-arm from the same complete prefix, with primed W-tap and
confirmed delivered strength; no forced live sprint state. See native/
robust-search/search-findings.md and theory/next-transition-notes.md under
seeded-maintenance-20261101.

THIRD-HIT CAUSAL CHECK: removing Ctrl at HEAD17 also slows pursuit and loses
the third hit in4/8 samples. A one-tick W release at HEAD22 instead gives
STOP→C02→START and a normal third hit in all8. Crossing this release with a
primed W-tap continuation afterHEAD23 yields4–5 freshs versus original-seed
plain W-tap2–3 (eight training wins, both arms resolved), but all return within
3.40s. Same-pre22-checkpoint W-tap has6/8 missing-service-word UNKNOWNs; report
that separate continuation comparison as unresolved, not a win. Own fourth-
window reach is ample but return-denial margin is small; four early returns
are emitted before fourth-hit S12 applies. Next bounded search keeps the proven
released prefix through23 and branches ordinary inputs from24, with primed
own-observation W-tap as unassigned lookahead/final fallback. This addresses the
old default tail's missing pursuit/clicks; no new control axis or profile grid.
See native/robust-search/third-strength/release22-findings.md. No native/validation
promotion yet.

WT-TAIL CAPACITY CHECK: the fixed-prefix plus primed W-tap tail gets4 freshs
across eight training draws. Width-only16/64 searches retain4 and4–5 respectively,
versus original-seed W-tap2–3. Both frontiers die at HEAD38; all terminal children
have an accepted return, zero committed UNKNOWNs. Wider selection does not fix
the worst1.889s survival. Their first mutable HEAD24 follows the third C02 already
consumed at HEAD23. Next authorized single-axis test moves branching to preHEAD17,
keeping the established NN prefix through16, same width64/actions/ranks/calendars,
and known later-prefix words as terminal-only incumbents. Compare original-seed
W-tap and separately primed same-checkpoint W-tap; retain its UNKNOWNs. No profile,
ping or new control feature yet. See native/robust-search/wt-tail/capacity.

EARLIER-BOUNDARY CHECK: pre17 width64 does not rediscover the known4–5-hit
continuation; its best new resolved word has minimum3. Crucially, it prunes
the known useful prefix when assigning HEAD20 while all proposals are still
committed-safe. Its later return failure is therefore not evidence that the
earlier history cannot improve. Preserve that negative run. Next correction
protects the known FIXED32-input prefix as one branchable lane through32,
within the same64 total slots, never constructing a shared word from an
adaptive W-tap tail. Same ranks, controls, samples and budgets. Require full
clock/queue/primed-WT parity and selector retention/release checks. No held-out
or native promotion. See native/robust-search/wt-tail/early-boundary.

PROTECTED-PREFIX RESULT: the reserved fixed-history lane prevents the known
pruning failure. New49-input prefix plus primed WT tail gives5/5/5/8/5/5/5/5
freshs versus original-seed WT3/2/2/2/3/3/3/2. Activity-qualified4/4/4/7/4/4/5/5;
all return2.85–4.64s, seven cross1000ms inactivity. Same-pre17 primed WT comparison
has2wins/1loss/3ties/2UNKNOWN. Do not call this a maintenance lock or promote it.
Saved-trace diagnosis examines actual S12 application versus MOVE and airborne
sprint launches; no further feature search without a causal baseline comparison.
See native/robust-search/wt-tail/protected-prefix under seeded-maintenance.

LATENCY CAUSAL CHECK: frozen prior32 atRTT120/0 (only target latency changed)
and full-seed WT both get1fresh on all8 training samples. Earlier target KB moves
it out of the second click. Two ordinary W inputs atHEAD7/8 restore hit2 all8:
2/2/4/3U/3U/3U/2/2 versus same-pre7 primed WT2all. Three UNKNOWNs request absent
S19+own-metadata+S18 whole-drain support; never substitute/reroll these. Audit
full training archives before any new calibration. Existing m1463–68 are not
training; m1415–18 remain sealed. Native calibration for new service words is
measurement, not performance promotion. No native runs since m1465–68 yet.

NATIVE HANDOFF FIX: SeedPlanAdmission now ends with an explicit opening-only
input-word handoff, allowing the ordinary WT tail's normal click gates to resume.
Previously an out-of-range mask index suppressed all tail clicks. Full-word end
remains closed, null decision fails closed, already queued clicks survive, and
normal CPS/GUI/reach gates remain enforced.2024 admission and883 word checks
pass; client-only Java8 bridge changes only two classes. This does not change
old full-word experiments. Install only into idle lab bots, not the human client.

LATEST USER DIRECTION — prioritize maintenance over entry. Characterize the
provisional Hypixel preset and reference regimes lock8/m1364 and horizontal-only
hit2/m1272 separately. Start by rescoring existing complete native streams and
model runs at established-combo landmarks; investigate the Hypixel H2 119-hit
witness (m1396 calendar, seed4001), then exact model continuation forks and native
matched-acquisition continuations. Distinguish full-information plus future-clock
oracle, live current-information oracle, frozen action playback, and legal own-
view control. They solve different information/timing problems. Target deliverable:
a bounded map of launch/spacing/phase/ping/profile conditions, conditional break
rates with spread, mechanism, and watchable native candidate/W-tap references.
Freeze study scope and endpoints in `maintenance-20261101/plan.md` before results.
Do not infer global impossibility from a bounded unsuccessful search. m1415–m1418
remain SEALED; previously exposed transfer calendars are labeled accordingly.

MAINTENANCE CHECKPOINT — `maintenance-20261101/findings.md`: exact H2 witness
replay matches1199 HEAD states/119 fresh timestamps. Same-full-state forks after
3/5/10 freshs give oracle116/114/109 remaining freshs, primed W-tap2/3/2,
frozen legal4/5/4, C3 and feedback2/3/2. Changing only twelve future timing draws
after the five-hit seed gives frozen controls+own admission8–81 (all return),
WT3each; this is same-calendar sensitivity, not an oracle impossibility result.
One-HEAD strafe/admission2x2 adds5 in all four variants versus WT2: gate changes
coalesce into the same consume opportunity, lateral motion only delays return55ms.

New native conditional-handoff pairs m1432–m1443 share acquisition until a guarded
own third cue; all12 handoffs independently pass server certification. Actual
handoff is after3 freshs at lock8 and4 at hit2. Lock8 adds8/4/16 vs WT1/2/1;
none of the six first-return intervals crosses the100ms movement/server-tick
warning gate (smaller jitter remains). Hit2/V0 adds587/584 (horizon),25
(return) vs WT5/4/5. Long runs contain109–112ms tick gaps; short run has291ms
attacker gap and is timing-contaminated. No tuning/retry selection. m1431 is a
preserved invalid setup (arena preparation stall), not a combat result. New
native states/clocks are independent, NOT exact forks. Accepted-return damage
upgrades count; all fresh gaps/inactivity endpoints remain available. Native
current-state oracle remains pending behind the causal-observer/deadline gates
in `maintenance-20261101/native-oracle/prerequisite-plan.md`; do not substitute
future-calendar H2 or a position-only state reset. Human client unchanged;
server next-scenario profile restored to vanilla after the batch.

## Earlier follow-up: broaden inference and build legal control

FROZEN FEEDBACK NATIVE MAINTENANCE CHECK — new v2 m1448–m1453, Hypixel-like
hit9/RTT120: additional fresh hits2/0/2 versus primed plain W-tap2/1/1; all six
guarded native landmarks server-certified. No post-handoff interval exceeds100ms;
m1448 has earlier timing warnings and re-establishes only at30.505s after66
accepted returns. Stop adding controller features: no maintenance improvement.
All7,685 actual native policy proposals replay exactly on Java8. m1451's return
C02 was emitted45ms BEFORE handoff, then accepted36ms afterward. A three-hit
count alone does not certify a safe/viable state. Existing model forks occur at
third fresh SERVER_DRAIN; native handoffs follow own S19/nextHEAD64–110ms later.
Diagnose that checkpoint/queue distinction before interpreting model/native
differences. No score retiming or failed-run deletion. v1 m1444/m1445 remain
unqualified view/wrong-packaged-cap attempts; v2 fixes ClickRate packaging only,
with unchanged policy and51ms settings. See maintenance-20261101/findings.md and
native-feedback/v2. No sealed calendar was read.

MAINTENANCE STATE MAP — all 134 established transfer cuts now reconstruct with
identical full-state and primed-policy hashes (`maintenance-20261101/oracle-mechanism/state-map`).
At the third server fresh hit, every resolved BOTH-nearest-reach state (64 C3,
4 feedback) returns before another fresh under BOTH candidate and W-tap.
For A_ONLY feedback states, remaining fresh median6 [2,37] versus W-tap2 [1,3];
descriptive cycle failures20/254=7.9% versus20/60=33.3% across10 dependent-mirror
families. Keep22UNKNOWNs; these are exposed privileged labels, not a legal
classifier or iid risk estimate. Ground/descending phase alone does not separate
success. Moving the positive model cuts to received-cue/HEAD landmarks retains
5/31/37 versusWT2/1/2; H2 fifth-cue retains114 versusWT3, so handoff delay alone
does not explain the native gap. All later episodes in the six native feedback
games also stop by five fresh hits. Native m1451 is causally too late at handoff:
return begins35.23ms later, before any new input can traverse the fixed60ms
uplink; bound assumes the recorded processing schedule and ordinary controls.

Native oracle prerequisite now includes an executable causal-prefix ledger:
11 tests/72 suffix mutation checks, actual m1451 in-flight C02 and m1452
S12-before-MOVE cases recovered without future data. It explicitly rejects full
kernel import: incomplete server/service state and capture-start FIFO ambiguity
remain. See `maintenance-20261101/native-oracle/causal-prefix/README.md`.

PROSPECTIVE ORACLE BUDGET CHECK — canonical clock replacement preserves all
committed work and exact original candidate/W-tap suffixes at four checkpoints;
17 new scheduler tests and standard model regressions pass. Historical donor
splicing is independent of the poisoned eventual suffix on eight bindable
samples, but conditional native coverage is NOT validated. All128 bounded8ms
attempts use W-tap:64 deadline fallbacks during future binding (zero completed
rollouts),64 no-compatible-donor UNKNOWNs. All32 evaluation draws retained:
14 resolved identical W-tap pairs,18 UNKNOWNs. This is a compute/import/sampler
prerequisite failure, not oracle combat infeasibility or a reason to run native.
See `maintenance-20261101/native-oracle/budget/findings.md`; full native oracle
and exact native midstate reset remain pending.

LATEST USER DIRECTION — turn A/D into own-observation feedback on top of the
three-contact winner, and validate jump separately. Completed study:
`hypixel-inference-20261101/feedback-strafe/findings.md`. Next-window range
projection selects A/D/none and may cancel an unsafe strafe; original W/Ctrl/click
rules remain fixed.72 proposal slots/59unique, separate selection,540 frozen
transfer cases plus540 disabled controls, matched original word AND plain W-tap.
Selection50wins/0losses versus original does not transfer: MIDPOINT90ms4/40,
120ms14/34,140ms10/8 (ties/UNKNOWN in full tables). Median prefixes2/2/2 versus
original3/3/2 and W-tap1/2/2. No minute lock or native promotion; retain original.
Do not add features to this failed wrapper. Diagnose the estimate/control
boundary: nominal future reach has median0.399/p95=1.072block error against a
0.2block margin; ending in own reach does not ensure intervening return denial.
Training-only causal cases31vs18/WT2 and9vs27/WT2 show separation benefit and
late fresh-window failure respectively. Do not use transfer cases for examples.

JUMP CHECKPOINT — `jump-validation-20261101/findings.md`: canonical Control has
default-false jump; cooldown/decrement/release and native takeoff ordering exist
at MOVE.480 scoped native steps/11takeoffs match exactly, including8sprint and
3takeoffs after confirmed same-HEAD contact drag. Conditional living-entry and
travel-entry state is granted; this is not whole-match raw-key replay. Unsupported
flight/fluid/sneak/startup samples retained/excluded. Zero direct same-interval
S12→takeoff examples, so that boundary retains source/synthetic coverage only.
All540 archived no-jump C3/W-tap transfer baselines reproduce unchanged. Jump
was not mixed into the failed feedback search; any future jump policy needs its
own matched combat gate. No human/server restart; temporary probe removed.

PREVIOUS USER DIRECTION — search contact-cycle strafing, following the failed
simple family. The prior seven-parameter search included a constant strafe
bias; it did NOT cover contact-timed direction changes. New bounded study:
two-/three-contact W-release words, before/after release timing, alternating
A/D per contact or cycle, at provisional Hypixel/hit9, own RTT120 and opponent
RTT90/120/140. Preserve native51ms click spacing for both arms and acquisition.
Use the same frozen training/selection/previously-exposed transfer split and
matched plain W-tap; test mirror directions and no-strafe / W-tap-plus-strafe
ablations after freezing finalists. No future-calendar or opponent-POV input.
Jump remains off in this first pass because the canonical fight clock has no
validated client jump transition (ExactTravel starts downstream of it).
See `hypixel-inference-20261101/zest-structure-search/plan.md`.

CONTACT-CYCLE CHECKPOINT:128 proposal slots/109unique,3,072 training pairs,
288selection pairs and3,240 frozen transfer rows including mirrored orientations
and ablations. Two-contact alternating-A/D finalist fails a clear transfer gate:
MIDPOINT49wins/41losses/66ties/24UNKNOWN. Three-contact finalist selects no A/D;
MIDPOINT median fresh prefixes3/3/2 at opponent90/120/140 versus W-tap1/2/2;
best resolved transfer24, zero minute locks at any service bound. These are
previously exposed transfer calendars, not pristine native confirmation.
No native promotion. Training-only ablation identifies a real spacing tradeoff:
A/D improves one case5vs no-A/D2/WT2, but hurts another1vs no-A/D5/WT2 by missing
the next reach window. The benefit case denies via their own-view distance,
not an angular ray miss. Do not add fixed input words blindly; any next strafe
law should be motivated by this measured next-window range tradeoff.

LATEST USER DIRECTION — two parallel tracks (after the correlated-filter result):
first audit structural observer omissions before adding filter machinery. Use
observed opponent body/head-look correctly, and treat a received position
packet's source server tick as a discrete hypothesis with the native two-tick
tracker cadence and dirty/isAirBorne bypasses. Do not overwrite current hidden
yaw with delayed look or score a packet against an unrelated latest endpoint.
Development m1425/m1426 and transfer m1428/m1427 are all previously exposed;
this is a structural ablation, not pristine confirmation.

Independently, bypass the observer with direct parameter search over a small
legal own-observation controller family in the calibrated fight model. Freeze
training/selection/test calendars before outcomes, include matched W-tap and
UNKNOWNs, and test both hit9 and hit10. Training m1391/m1392/m1395/m1396,
selection m1397–m1400, and current-search test m1419–m1428 are distinct calendars;
the latter were previously exposed in observer work and are NOT pristine native
evidence. m1415–m1418 remain sealed. This explicitly authorizes the simple
controller search despite the still-failed filter gate; it does not authorize
using uncertified filter bounds as safety guarantees. No further controller
features after a failed matched comparison without a baseline-grounded diagnosis.
See `hypixel-inference-20261101/direct-policy-search/` and
`hypixel-inference-20261101/filter-structure/` for frozen plans and results.

COMPLETED TWO-TRACK RESULT: explicit source association fixes the development
quiet-opening W-tap coverage25%→100% (44eligible positions before4s), but full
transfer at native51ms remains inadequate: original candidate/W-tap78.5/20.2%,
source74.4/11.0%, source+look66.1/13.5%. No observer promotion. At a5.212s transfer
miss, yaw is within.261degrees and tracker history is present, but simulated
opponent contacts6–7 disagree with native5. Contact/timing history is the next
specific discrepancy; adding more angular conditioning is not justified.

The direct seven-parameter CEM family evaluated96 proposals across hit9/hit10,
with separate training/selection/current-search test calendars. Final180paired
test evaluations produce zero wins or minute locks. MIDPOINT hit9 gives0wins,
2losses,25ties,3UNKNOWN; hit10 gives0/0/27/3. Other service bounds do not rescue
the result. The best parameters return toward W-tap; keep exact plain W-tap as
fallback. No native promotion or additional controller features. A nontrivial
training proposal scores1/1/5/5/1/1/1/1 versus W-tap2each: its apparent gain
does not transfer. Training-only one-variable and physical traces identify the
failure mechanism: coasting removes approach momentum/POV separation, so a
return attack can leave before our next knockback reaches the opponent. Merely
being inside our own reach does not certify denial. Preserve the final test
set from action/example tuning; it is already exposed to result inspection.

Source-format audit during this work: m1419–m1428 native clients use a51ms
injection interval, whereas older `NativeCalendarEvaluation` tapes default to
50ms. New direct-search tapes explicitly set51ms for all arms/acquisition; the
filter tests the same correction separately. Historical model/native results
using those defaults were not matched on this detail. Do not silently rewrite
them or infer that recorded-input movement/knockback parity was invalidated.
The new loader preserves legacy defaults and has an explicit `CLICK_INTERVAL_NS`
field. New opening captures also use a declared saved-HEAD clock, now supported
by extraction without weakening the legacy event-order assertions.

LATEST CHECKPOINT — correlated pipeline filter: see
`hypixel-inference-20261101/correlated-filter/findings.md`. Complete canonical
clocks are persistent particles; own packets condition history with soft
likelihoods. Fixed200ms timing-prior segments and path Metropolis moves preserve
physics/queue correlations without nudging body or server motion. The finite
filter still FAILS the approximately99% coverage gate: new m1425/m1428 candidate
78.3% coverage versus m1426/m1427 W-tap10.7%; p95 horizontal diameters.624/1.132
blocks. A deferred-stage phase-proposal correction gives77.0% /10.7% on the
same now-exposed data; no parameter changes. Narrow W-tap estimates can be wrong
by several blocks. Unknown cue-order,
service-composition and near-zero-separation branches remain explicit; surviving
clouds do not certify their excluded mass. No controller was changed/promoted.

Fresh conditional checks pass5,121 travel steps and343 launches. Unchanged native
policies score1/5 versus matched W-tap2/2 before first return after4s; all primary
windows timing-clean, m1425 has a later whole-match flag. Own connections contain
zero opponent S12s. Privileged source-phase/launch-height inversion shows own
position packets can constrain launch Y by300ms (median width.015625 blocks/tick),
but timing association is granted, not solved. At0–150ms the new captures have
no informative post-launch positions in that diagnostic. Caps and floor clipping
can leave prior server velocity unidentified. Do not claim velocity is proven to
be the sole width driver: an early quiet-opening miss retains the correct K_y.

Next justified capability: recover correlated source/tick histories in a single
quiet opening, before the first lost hypothesis, across several hit intervals;
resolve unsupported service composition from native evidence. Do not add combat
controller features or widen a huge residual margin and claim success. m1425–28
are now exposed; m1415–18 remain unopened. A new estimator needs new frozen
validation. The final filter freeze is v2; earlier state-nudging/ESS-timed drift
prototypes were rejected, with chronology and an analysis-only inversion fix
recorded in that directory.

PREVIOUS CHECKPOINT — physics-based body hypotheses: the user's proposed
post-hit reachable-set observer has a frozen native-recording audit in
`hypixel-inference-20261101/reachable-body/`. Development m1419/m1420;
validation m1422/m1423/m1421/m1424 (previously exposed to the older observer,
not pristine prospective); m1415–m1418 remain sealed. Own packets only enter
hypotheses; other POVs are labels. All 5,124 held-out travel steps and 335 fresh
launch vectors are conditionally exact; all 335 true C02/sprint choices are
retained. The forward observer itself is NOT exact: latent server motion,
source/application schedule and contact behavior remain sampled/approximate.

Known-hold-W cue-time coverage is broad but useless: p95 horizontal diameter
3.089 candidate /5.303 W-tap blocks. Rolling own-packet conditioning at150ms
shrinks p95 diameter to1.052 /3.166 but current-body coverage falls to72.9% /67.2%,
available70/134 /67/196 eligible labels. At300ms many sets collapse; narrow
survivors are not certified. This FAILS the>=99% coverage/useful-width gate.
No controller changes or native reruns. Do not treat finite sampled hulls as
exhaustive reachable sets or this failure as an impossibility proof. The next
observer must preserve correlated feasible source state/server motion/schedule
in the calibrated fight pipeline before using its margins for control. More
inputs or narrower confidence bands are not a fix. Keep W-tap comparisons and
count all unavailable/fallback states. See its findings/report for widths by
time, coverage, availability, timing tails and exact limitations.

LATEST USER FOLLOW-UP (after the clean7/2/3 prospective result): run a small
native paired calibration at provisional preset=hypixel/hit9 with the frozen
revised controller versus identically configured W-tap. This is a calibration
check, not a lock demo or permission to tune on those outcomes. In parallel,
measure own-only estimates of the opponent's attack-ray distance using both
native POVs as offline truth. Freeze estimator fit/validation sets, test own
sent-position history/RTT/interpolation, and derive a safety margin larger than
validated uncertainty if precision is insufficient. Keep m1415–m1418 sealed
from controller examples and action/threshold diagnosis.

Native frozen-reference calibration is now complete: three hit9/120–120ms pairs,
candidate6/1/0 versus W-tap1/2/2 fresh hits before first return in4–64s. All six
primary windows timing-clean; later whole-match stalls in m1419/m1421 retained.
Reach/CPS/build/target checks passed. No minute lock. This does NOT clear the
native improvement gate; no more controller features until a causal diagnosis.
The zero-hit case was already inside the opponent's native reach at its first
waking click (ray2.707). Shared opening ends3.5s; opponent wakes4s. New captures
m1419–m1424 validate fixed own-only observer variants and margins calibrated on
older data. See `hypixel-inference-20261101/native-reference-calibration/`.

Observer checkpoint: own send history plus approximate tracker/interpolation
reconstruction reduces candidate-prefix 3D error in the opponent's view of us
from 0.813 to 0.116 blocks. Their current body remains the dominant uncertainty;
simple extrapolation worsens the candidate's critical S12-to-first-MOVE subset.
Frozen one-sided ray margins are about 1.9–2.9 blocks, too wide to certify the
roughly 0.127-block action differences at issue. These are retrospective
nowcast checks, not validated future bounds or proof of physical impossibility.
Before a new control rule, validate opponent-body forecasts and require both
return-hit denial across compatible states and our reach at the fresh window.
Uncertified states/fallbacks still count as ordinary outcomes. Full candidate
and W-tap errors are in `hypixel-inference-20261101/return-ray-estimator/`.

Previous priority/checkpoint: fix unsupported timing-service overlaps and validate the
scheduler against native recordings/fresh native calibration BEFORE more
controller work. Preserve old UNKNOWN outcomes, use fixed completion-bound
sensitivities rather than choosing by score, and retain genuine unsupported
timer/composition cases. Freeze example/diagnostic/held-out calendar sets. Never
add examples from held-out calendars; report never-example transfer separately
from same-calendar jitter. m1397 is now example/development; m1399 is diagnostic
development. m1391/1392/1398/1400 supplied no controller examples but outcomes
were already inspected, and1391/1392 are timing donors. New prospective native
calendars must stay sealed from controller example/threshold selection.
Derive the m1397/m1399 input distinction from mechanics and own observation
history (including pending contacts/acknowledgments), not more example rows.

Service-repair checkpoint: opt-in busy-client/monitor completion envelopes now
defer overlapping stages/FIFO enqueues rather than throwing cue-overlap.880
component assertions pass, including16 old/fresh native batches; legacy default
semantics remain unchanged. Frozen revised controller continues the old38-hit
prefix to41 before return (WT2 under LOWER/UPPER; MIDPOINT WT wall-phaseUNKNOWN).
Historical hit9 comparison UNKNOWNs fall10/24→4/24 LOWER/MIDPOINT,7/24 UPPER;
remaining cases are genuinely unmodeled merged task service or integer wall
phase. Do not choose a favorable bound to clear the promotion gate. Fresh
service spans exceed the old pool's range, so full generative calibration is
still open. No controller/example changes or native promotion this step.

Prospective controller holdouts m1415–m1418 are sealed from examples/action
diagnosis/threshold fitting. Frozen tables tested at hit9/hit10, seeds8000–8002,
all3 service bounds. Revised hit9 wins7/12,loses2,ties3,zeroUNKNOWN under every
bound (oldtable0wins/8losses/4ties); no minute locks. Hit10 wins none. Timing
capture donors were unchanged native lock8 vs WT (4/2 vs1/1 maintenancefresh);
these are NOT native tests of the revised table. One donor has a late170.5ms
stall retained/flagged. All native/client builds pinned, no human-client touch.

Mechanical decision: minimum opponent return-ray slack before its first
post-S12 MOVE. Same immediate hit times/strength: early W changes m1397's
pre-MOVE ray3.124→2.876 (return), m1399's3.346→3.069 (miss), then changes
downstream hit strength/spacing. Nearest-box margins are only~.0108/.0009;
current own observer errors exceed them. Pending-contact ledger repairs452ms
stale-cue error but is not yet a validated action selector. Next simulator
capability is FIFO per-task service composition with native validation, not
another controller patch. Findings: `hypixel-inference-20261101/service-backpressure/findings.md`.

Latest user continuation: diagnose the38-hit m1397seed7000hit9 oracle rescue
against the1-hit legal failure at matched states; widen the calibrated calendar
set to measure transfer. Once the legal controller beats plain W-tap across
most calibrated calendars at hit9, run a SMALL serialized native paired batch
on preset=hypixel. This authorizes native validation on a majority-improvement
gate; it supersedes the older requirement to first achieve80% full-minute locks.
Freeze the candidate and evaluation plan before the wider test, retain UNKNOWNs,
keep hit10 separate, and do not interrupt a human duel or touch live Hypixel.

Wider-transfer checkpoint: exact413-state rescue reconstruction reveals38 is
censored by CUE_SERVICE_OVERLAP at24.166s, not a completed minute. One-HEAD probes
isolate premature W resumption in m1397; coast then sprint rearm one HEAD later
preserves the rescued opening. Adding only its413 own-feature rows improves new
m1397 draws to9/9/19 (WT2/2/UNKNOWN) and previously unusedm1400 to6/9/UNKNOWN
(WT2/2/UNKNOWN). Full frozen8-calendar x3-seed hit9 batch: revised10wins/3losses/
1tie/10UNKNOWN, old7/7/1/9. Revised wins a majority of draws on4/8 calendars;
native gate (>12/24 resolved strict wins AND5/8 calendars) FAILS. Hit10 revised
0wins/6losses/10ties/8UNKNOWN. No native batch, no controller/model feature changes.
Remaining m1399 loses1/1/1vsWT2/2/2. Its firstHEAD instead needs EARLIER W:
one-key WT substitution then existing legal feedback raises1to4vsWT2. Do not
generalize the first case to always brake longer. Timing UNKNOWNs require a
validated busy-completion/FIFO extension, not guard bypass or favorable redraw.
Artifacts: `research/hypixel-inference-20261101/oracle-transfer/findings.md`.

Latest next step: close the oracle-to-legal gap by replaying the 79/51-hit
witnesses and comparing oracle, plain W-tap and legal decisions in the **same
physical states and observed histories**. Identify own-observable predictors of
the consequential interventions before adding rules. Distinguish arbitrary
search tie-breaks from useful actions, action agreement from closed-loop control,
and hindsight labels from legal runtime inputs. Keep the nine-tick inference
open: test each resulting controller against matched W-tap at BOTH hit9 and
hit10, with calibrated schedules and explicit UNKNOWNs. The user will collect
the proposed60-hit data manually when a partner is available; do not wait for it
to perform offline combo work.

Decision-distillation checkpoint: exact action-tape replay matches1300 physical
HEAD snapshots/all score fields. Same-state single-action probes find19/48 legal
replacements worse than the privileged oracle suffix; scalar range/height/cue
associations are insufficient and enumeration ties matter. The isolated
angular-preservation law adds no improvement. A legal nine-own-feature reference
feedback table (no server/other-client/future-clock/runtime reference timestamps)
recovers79/51 source scores. Combined-reference diagnostic hit9 gets73/4/4/15
versusWT2/2/1/2; hit10 gets0/0/1/0 versusWT1/1/1/0. Actual-input-copy ablation
does not improve it; retain original. Frozen confirmation on new7000/7001 draws:
hit9 m1395=17/10 vsWT2/2, m1396=15/15 vsWT2/2, m1397=1/1 vsWT2/2,
m1398=two shared acquisitionUNKNOWNs. Hit10 loses1/ties7; no minute-locks or
native promotion. H2 at the exact failed m1397seed7000hit9 entry gets38 versus
legal1/WT2; at m1395seed7000hit10 it gets0 versuslegal0/WT1. A remaining
legal decision/history/coverage gap is established, not an unobservability proof.
Do not tune generic rules or claim robust lock from73. Next compare the38-hit
rescue's actions and own history against the failed legal path. Full artifacts:
`research/hypixel-inference-20261101/oracle-distillation/findings.md`.

The user requests three concrete next steps after the provisional preset:

1. Expand knockback inference beyond four stationary launches to all 297
   plausible self-melee packets and other-player trajectories. Use the coupled
   mechanics, including prior **server** velocity, attacker sprint/yaw/position
   and packet processing uncertainty. Use held-out validation. Do not obtain a
   spurious fit by independently selecting a convenient latent velocity from
   each target S12. If horizontal coefficients/cap remain unidentifiable,
   specify the active recording needed: counts, victim/attacker states and
   which ambiguity each intervention resolves.
2. Challenge the nine-tick conclusion explicitly against clock drift, phase,
   packet staging, duplicate or synthetic hurt animations, and damage versus
   animation semantics. Use alternative hypotheses and known native labels;
   retain the distinction between an effective hit interval and exact internal
   server counters.
3. Continue on `preset=hypixel` toward a **legal own-observation controller**.
   Oracle witnesses establish available input authority but are not the target
   deliverable. Keep calibrated timing, matched W-tap and multiple seeds/phases.
   Use the early-entry and pre-knockback-movement diagnoses to guide the next
   simple feedback design, with explicit observation-leakage checks.

The current analyses are in `coupled-kb-fit`, `hit-delay-alternatives` and
`legal-maintenance` under `research/hypixel-inference-20261101`. No live Hypixel
actions or anti-AFK work are authorized by this follow-up.

Follow-up checkpoint: all 297 plausible self hits are retained (148/149 temporal
split). Broad latent-motion/direction freedom makes vanilla, native+.07 and the
rough pre-cap hypothesis all cover297/297; this is non-identification. Coupled
report-index propagation gives11 conditional contexts, but no shared tuple in
the declared243-grid passes development. Native arithmetic checks match220
preattack My labels and30 full launch vectors. Horizontal/cap coefficients are
not newly identified; use the active recording protocol instead of claiming a
precise fit from free nuisance states.

S1C watcher0 sprint metadata is available for all297 plausible attackers
(173true/124false); it agrees with all30 labeled native launches but is a delayed
proxy (median age496ms). Holding that proxy fixed leaves the conditional fit
failures unchanged. Do not say attacker sprint has no observable proxy, or treat
the proxy as guaranteed attack-time truth. Practical next data are20 isolated
normal,20 sprint and20 rising-normal contacts, recording before house entry;
attacker POV is optional stronger evidence, not a prerequisite.

The alternative timing audit retains all6110 statuses with stricter boundaries.
All308 paired own S12/status events share the same tick labels and306 intervals,
so the pattern is not animation-only. T8 andT9 fit; T10 fails repeated chains
under tight phase bounds, but wide staging or rare synthetic/reset events can
rescue it. Health-confirmed38 events and S03-only broad timing do not rejectT10.
Use “provisional effective hurt/launch opportunity”, not exact measured damage
immunity. The installed profile remains a declared approximation.

Legal work: the own-motion early-lateral law loses6/ties1 againstWT in7 resolved
paired cases, plus1 shared acquisitionUNKNOWN. Its8-tick W-rise guard blocks
early braking. A one-change removal gives earlier release but over-brakes and
loses throughput: loses5/ties2, sameUNKNOWN. No minute-locks for any arm. All
repeated baselines and own-stream replay/copy checks are exact. STOP this law;
no native promotion or extra strength/gain feature is justified. The next gap
is maintaining the next own-hit opportunity while preserving separation before
opponent movement, not maximizing either braking or knockback in isolation.
The separate linear own-view predictor fails on range margins (~.42 median,
~1.45 p95 held-out blocks); smoothing removes extreme errors but not false safety.

## Current priorities: Hypixel recordings (2026-11-01, afternoon)

These supersede the vanilla-first target and the earlier task order below:

1. Anti-AFK work is **finished and stopped at the user's request**. They will
   not use it on Hypixel or AFK there. Do not collect more AFK data or do more
   anti-AFK work. The already installed fix was motivated by enable at 12:06:27 to
   the Housing-to-lobby transfer at 12:14:15 local. Keep it enabled across
   same-connection JOIN/respawn world changes; turn it off on a real disconnect.
   Default randomized 30–90 second intervals, short randomized movement/sneak
   and smooth small look pulses, approximately net-zero displacement. Preserve
   GUI/chat/manual-input pauses. The user controls any client restart.
2. Infer Hypixel combat from the local `runtime/pov/remote-index.json` archives
   for `hypixel.net`. Establish packet observability from vanilla source and
   FORMAL-MODEL before fitting. Most evidence is passive observation of nearby
   fights: distinguish exact self S12, other-entity S12, tracker positions,
   interpolation, hurt cues and missing attacker/victim inputs. Validate the
   noise model (quantization, phase, jitter, randomness) and report uncertainty
   or non-identifiability for every parameter rather than inventing precision.
3. Build a clearly documented `hypixel` server preset from supported estimates
   and validate predicted trajectories against held-out recorded situations.
   Do not label arbitrary settings an exact Hypixel replica.
4. Make that measured Hypixel preset the primary combo target; vanilla becomes
   secondary. The tutorial at https://www.youtube.com/watch?v=mTeXebYfIIs supplies
   mechanism hints, not authoritative exact timing. Keep calibrated clocks,
   legal own-observation control and the matched W-tap gate above.

Continue B/C/D from the already recorded data, including the 310 own S12 packets.
Do not presume those 310 are all melee or AFK hits: check damage and nearby-player
context. Most overall evidence is other players' position/hurt packets. Compare
vanilla, a free fit with explicit identifiability limits, and the user's rough
third-party hypothesis: horizontal .4, vertical .36, verticalLimit .43075,
extraHorizontal .5, extraVertical .1 (native .4/.4/.4/.5/.1). The plugin was not
identified and its parameter semantics are not established. Explicitly test
retained-motion and cap-order interpretations against FORMAL-MODEL; do not adopt
the numbers as measured truth. Preserve uncertainty in the preset and findings.
If an identification gap genuinely needs more data, describe a short active-play
recording task to the user (for example, taking hits in a real fight); never
collect it through AFK automation.

The replay viewer is lower priority. Never touch the user's live Minecraft
client while they are on Hypixel: no reloads, commands or restarts. Read the
archive and logs only; stage installation for a manual restart. If disk is
tight, pause lab runs and deduplicate recordings before generating more.

Current Hypixel checkpoint: the conditional packet/trajectory fit favors native
base/horizontal behavior plus sprint extra Y .07 and effective fresh window9.
The rough .36/.43075/.1 parameterization fails the settled packet comparison
under vanilla-style recurrence; a universal final .43075 cap conflicts with16
damage-associated packets. Source-context checks support melee for297/308
hurt-associated own velocities, including all4settled cases. Other-player
identified-set fits support .43075 launches in25/31 informative screened arcs,
with hidden inputs/latency limits retained. See
`research/hypixel-inference-20261101/findings.md`; this is an inferred Housing
approximation, not an exact identified plugin implementation.

`preset=hypixel` is installed on the local lab. Two native component scenarios
verify normal/sprint packets and nine-tick immunity behavior; unchanged bot
client artifacts were pinned, and the user's Hypixel client was untouched.
First model transfer screen:18paired draws, existing legal controller ties
W-tap on all12resolved paths (0–2fresh prefixes);6acquisition cue-service
UNKNOWNs remain explicit. No minute-locks, no native controller promotion.
The mandatory gate now requires diagnosing those shared first returns and the
opening before adding controller features. Keep old lock8 results separate.
Exact paired diagnosis finds inherited contact history at150/0 and a return
click0.794ms before the first new-KB movement at120/120. A single shared W-tap
opening alternative does not help: candidate/WT stays0/0 in the former and
regresses2/2→0/0 in the latter. The full histories/default outputs are verified;
do not expand that failed opening axis. The frozen H2 oracle now has a120/120
conditional minute witness:119fresh versusWT2 (73normal/46sprint). Its unchanged
six-draw spread is1,1,1/1,119,1 versusWT1,1,1/1,2,1, with no actualUNKNOWNs or
resource stops. All five failures are early entries; robust qualification fails.
The witness has12eligible Bclicks with nearest-box range<3 but selected-ray
misses (minimum ray margin .000756); do not call it an aim-independent lock.
The entry deadline audit shows post4s ordinary movement cannot reach B's rendered
view before the fatal m1395 click; no equivalent exclusion holds for m1396.
A bounded two-case earlier-control test now forks both H2 and primed W-tap at
3.5s, keeps B quiet until4s, and scores only4–64s. Same profile120/120 and seed4000:
m1395 gets79 versusWT2, m1396 gets51 versusWT2, but0/2 full minutes. Each arm
excludes one extra pre4s fresh/strong hit; no actual UNKNOWN/resource stops.
Eight scoring and37 legacy checks pass, both passive early continuations recover
the old complete4s state/history, and all1199 decisions of the old119-hit witness
remain exact. These two diagnoses do not replace the original six-draw spread.
The79-hit failure is a definite maintenance closure: sampled S12 preparation
misses a movement opportunity, then B contacts0.770ms before first displacement,
ray2.660 and A vulnerable. Earlier safe three-particle forecasts miss that draw.
No counterfactual delay removal was tested. Next investigate spatial safety
through possible pre-movement opponent clicks; do not promote a legal/native
controller or claim robust maintenance yet. Search access includes full current
state/future host calendar; actual future jitter key stays hidden. See the
`oracle-early-entry` and `witness-mechanism/early-failure-note.md` reports in the
Hypixel inference folder. Both current agents' experiments are finished.
Native component replay passes the first8s of both profile fixtures exactly;
this is recorded-clock parity, not generalized timing or Hypixel identity.

## Earlier overnight mandate (2026-11-01)

### Latest priority: search against calibrated schedules

The user now requests robust oracle search first, then legal parameter search,
then the closest-to-vanilla settings region. This supersedes the one-recovery-test
limit below. Keep native trials paused until a candidate clears the model gate.

Use the frozen empirical timing law, complete physical acquisition, native input
opportunities and preserved queues. Test a fixed oracle algorithm across sampled
futures and calendars; do not select a different favorable trajectory after
seeing each outcome. The first conditional-calendar oracle may know full state
and the future host opportunity calendar, but its planning jitter samples must
not use the actual evaluation future seed. Label this privilege explicitly.
Separate a per-schedule clairvoyant witness from a robust stochastic controller.
A finite beam failure is a search limitation, never an impossibility proof.

Primary metric stays fresh hits before first accepted return. Minute-lock
qualification additionally requires zero returns, at least80% of nominal fresh
capacity, and no fresh-free gap over800ms (including horizon boundaries), for
both candidate and W-tap. UNKNOWN remains in the denominator, separately labeled.
Develop on m1391/m1392, tune on m1395/m1396; freeze choices before evaluating new
seeds1000–1019 on each m1397–m1400 calendar. Target at least16/20 minute-locks on
each evaluation calendar, not80% pooled by hiding a failing calendar. These are
conditional draws on four previously seen calendars, not80 native experiments.

Checkpoint: v1 passed4/4 development draws but only4/23 evaluated reserved draws
(3 UNKNOWN), making64/80 impossible even if all57 unrun draws succeeded. Preserve
that negative result. Version2 retains the prior plan suffix as an independently
reevaluated candidate; its selected diagnostic78→143 versus WT1 is not a robust
qualification. Its reserved test stopped with12/22 locks (2 UNKNOWN); m1398's
five real returns made16/20 unattainable. Exact failure replay also exposed a
return-truncated-gap tie-break that can reward earlier death, and safe forecasts
with no active continuation. Compare objective repair, longer horizon, and more
timing particles separately on development/tuning data before further reserved
evaluation. The four-arm screen is complete: v2/objective/K9 each2/4 locks,
horizon2s3/4, W-tap0/4. Objective-only regresses m1396 and is rejected; K9 adds
cost without rescue. The exploratory selection rule was explicitly adapted after
H2 outcomes, before K9 inspection; preserve that provenance. Frozen H2 then
passes8/8 new tuning draws, versus v2's7/8 and W-tap0/8. Its reserved test is now
finished at the frozen futility stop:38 strict locks in50 evaluated draws,
versus W-tap0/50. In total42/50 run the full minute without return (141–145 fresh);
four of those miss only the800ms gap guard by1.5–5.1ms. Two physical failures
are immediate inherited m1400 entries, and six paths are cue-service UNKNOWN.
Keep all six in the denominator and all30 unrun draws explicit. No later H2
maintenance return was observed, but the16/20-per-calendar gate did not pass.
The next validation target is native handler/drain completion and FIFO timing,
not another controller feature: cue assignment precedes main-thread completion,
so merely deferring to cue time is not a validated repair. Preserve this frozen
result; no legal parameter/native promotion yet.
The separate frozen-law72-setting sweep has0/288 minute locks versus W-tap0/288;
75 acquisitions are UNKNOWN, with another10 candidate /4 W-tap continuations
unsupported. Existing lock8 is still the best fully supported four-draw profile,
but its4–118 spread is not robust. Do not turn a finite beam's horizon/scoring
failure into a physical impossibility argument. See
[search findings](combo-robust-search-20261101.md).

Only after an oracle witness survives that gate, search own-observation spacing,
braking and hit-strength parameters. Every row includes identical-state W-tap.
Sweep RTT and near-vanilla KB/hit delay under the same calibrated law, report its
fixed-workload/coverage limitations and unknown mass, and promote only robust
legal candidates. If the gate fails, diagnose before adding features.

### Latest priority: native schedule calibration, then robustness

Current checkpoint: the frozen timing model passed a narrow fresh-failure check.
Reserved native pairs give candidate6/5 versus W-tap2/2, with clean acquisition
and primary prefixes; final W-tap has a later stall, retained. All four outcomes
are represented by the pre-outcome mixture. On held-out host calendars, native
candidate6/5 occur in19/20 and11/20 draws; W-tap2 in20/20 and9/20. All80 paired
draws resolve and fail before60s. The sampled native1399 critical ray plus all14
launches and body/view geometry occur in6/20 draws; only2/20 match all compared
physical timestamps. Keep the distinction. The older development ensemble's
4/80 candidate and1/80 W-tap unknowns remain visible.

This permits ONE mechanism-derived model-only recovery test, not a universal
scheduler validation or native promotion. Whole held-out calendars/queuefields
condition on measured workload; only the frozen old-calendar mixture was an
advance prediction. Four native observations cannot calibrate rare5min survival.
Legacy conditional exact replay remains24,683 events. No live controller change.

The m1395 complete4s acquisition×future seed0/3 crossover is complete. Candidate
19/19/1/1 versus primed W-tap1/1/2/2 follows the inherited prefix (including
retained startup-cost program), not the two future seeds. The same seventh
impulse lands before/after a drain and opposite sides of landing; bad prefix
loses six contacts. W-tap's Ctrl pursuit recovers contact at ray2.973391 where
candidate misses at3.002790, delivering the next fresh hit57.45ms earlier.
The maintenance own-ray>3.0 Ctrl overlay test is now complete. It preserves
19/19 and improves1/1 to5/3 versus matched WT1/1/2/2 in the four crossover cells.
The frozen eight-calendar seeds0–3 screen gives5 improved,27 unchanged,0 worse,
0 unknown,0 full60s locks; WT/acquisition result fields remain exact throughout.
Four of those gains (m1400) add a hit in the same drain as the return with zero
survival extension. Only m1395seed3 extends survival, by1.789s. m1398's unchanged
failure has own ray2.113 and key0 coast; the out-of-range recovery never fires.
Do not promote this model-only law as a robust solution. Later sprint hits can
cause outward misses and a return before the next attack reaches the server;
understand that pursuit/strength tradeoff before adding another feature. No
native variant run or live controller change. Full table and evidence are in
[recovery screen](poc-20261101/native-schedules/recovery-screen/findings.md). See
[current findings](combo-native-schedules-20261101.md).

User requests that evaluation clocks use native-recorded packet jitter, client
tick gaps, task-drain timing and opening variation, rather than the ideal
periodic schedules that falsely predicted reliable750-hit locks. Freeze the
controller until both candidate and paired W-tap native outcomes are represented
by the model distribution. Broad min/max overlap alone is insufficient; check
frequencies, failure mechanisms and held-out/fresh data. Separate network receipt
from task handling. Preserve correlated event order and generate gameplay inputs
and packets autonomously; recorded click counts/positions are not evaluation
inputs. See [the frozen plan](poc-20261101/native-schedules/preplan.md).

Also compare the three earlier150-hit successes with both new failed openings.
Test whether established locks withstand bad subsequent schedules, preserving
full state/queues when separating acquisition from maintenance. A deliberate
own-observation acquisition routine may be sufficient, but correlation in five
matches is not proof. Only after calibration may controller changes be tested,
including the opponent's click after S12 arrival but before movement.

### Current evidence after the strength/timing pass

Latest follow-up: [lock8 robustness and boundary](combo-lock8-robustness-20261101.md).
`/lab demo lock8` and `/duel lock8 boxing preset=lock8` are deployed. The frozen
20-run native robustness batch is complete: equal RTT0/0,60/60,120/120 does not
beat W-tap; the fresh300s150/0 pair is2 versus2 before first return, with clean
primary/acquisition clocks. The healthy command smoke is5 versus3. No rerolls
or controller changes. Existing150/150/150 native witnesses remain valid, but
robustness is not established. The periodic model gives750 in all14 five-minute
cases versus W-tap1–2, so actual timing/seed sensitivity needs causal explanation.
Do not resume feature tuning from the periodic success alone. All matches ended;
global combat defaults are restored1/1/10. Native logs and all failures retained.
Fresh six-trace12s conditional replay now completes24683 events with9243 state
rows bit-exact (nearest error≤4.52e−13), after fixing two adapter clock-boundary/
snapshot-ordering bugs. No kernel or controller change. Native failures are
contact before first knockback movement (m1368) or before next fresh (m1388),
with earlier launch/replica histories differing. Late identical-launch8→9/10
forks fail with no recorded missed fresh window: a scoped committed-view bound
shows the frozen law brakes too late, but earlier braking has reach slack.
This is NOT a proof against all vanilla controllers; no global empty corridor
has been established. See the new findings before adding features or sweeping.

See [combo-strength-phase-20261101.md](combo-strength-phase-20261101.md).
A legal own-hurt-cued spacing controller now maintains60s natively at own/B
RTT150/0,H1.1,V1,hit8:150/150/150 fresh versus W-tap2/2/2. Last two repeats are
fully timing-clean; first retains one108ms tick flag. Both use the same4s silent
opponent opening, whose free hits are excluded. Native3.0 reach and20CPS cap.
The successful controller uses normal hits; learning phase was unnecessary in
its causal ablation. The prepared S/N branch works mechanically but performs
poorly because its protocol adds50–100ms and unnecessary W-off movement.
The final432-setting,three-phase grid contains no9/10-tick success; this is NOT
an infeasibility proof. Keep the8-tick native witness as the new continuation
anchor. Acquisition without a handicap and broader ping robustness remain open.
No extra native/offline job is queued; server combat defaults restored1/1/10.

### Latest direction: legal-controller settings search before native trials

Next mechanism pass: add deliberate normal-versus-sprint hit selection on top
of the frozen spacing feedback, verifying actual delivered strength rather than
assuming Ctrl means a sprint hit. Use own received landing/height cues and
spacing; do not read the victim's true state. Separately estimate processing
phase from this client's incoming packet timing and test phase-aware click
admission. Distinguish observable server phase, opponent movement phase, and
which timing components ordinary inputs can actually change. A delayed click
still passes through the native input queue and cannot move an admitted attack
backwards in time. Keep the same settings/phase sweep and matched W-tap gate.
Only combine individually justified changes; a nominal wall-clock phase change
is not evidence that actual C02 consumption/service phase moved.

Search the coupled model first across near-vanilla settings, including BOTH
players' RTTs (initial values0/60/120/150ms), horizontal KB within±10%, vertical
KB within the same near-vanilla neighborhood, and fresh windows8–10 ticks.
The searched policy must use only its own client's observations and declared
settings; an oracle trajectory is not a qualifying legal feedback controller.
Require sustained fresh hits and zero accepted returns for60s across several
physical tick phases, with plain W-tap from identical complete states. Report
fresh gaps/readiness as well as scores so merely running away is not called a
combo lock. Keep acquisition separate and all free opening hits excluded.
Only the best one or two settings that pass the model qualification go native;
coordinate their compute/timing gates as before. Community ping claims motivate
the grid; they are not verified Hypixel constants.

Audit precisely which hidden information the105-hit vanilla oracle used:
victim state, latent server motion, future event ordering and in-flight views.
Distinguish that offline search access from the saved word's legal but brittle
runtime inputs. Investigate whether ordinary, own-observation-cued inputs can
FORCE a reproducible landing/flight state rather than estimate it precisely.
A proposed reset must preserve reach/denial and work against an active hold-W
opponent; an oracle state edit or newly disabled opponent is not maintenance.
Freeze simple laws before the settings sweep and diagnose failures against
W-tap before adding features. No RL/ML. The deliverable remains the closest
near-vanilla native lock and a mechanistic account of its breaking boundary.

### Current priority: continue the anchor toward vanilla

The user explicitly rejects zero vertical KB plus two-tick fresh damage as the
desired combat regime. Preserve that POC as an anchor only. The deliverable is
the closest-to-vanilla **natively demonstrated** lock with matched W-tap, plus
the mechanical reason it breaks at the next continuation step. Desired settings
are fresh-hit intervals around9–10 ticks and horizontal/vertical KB multipliers
within roughly0.9–1.1 of vanilla; do not claim these numbers are verified Hypixel
constants. Follow the hit-delay ladder2→4→6→8→10 (also examine9) and restore
vertical KB toward1. Separate one-axis causal changes from joint compensation.

The old always-W gap law may leave its control-authority range as the hit interval
grows. Diagnose its failures against W-tap, then change inputs only from that
mechanism. Failed acquisition is not maintenance infeasibility, and the boundary
of one controller is not a universal boundary. Model candidates remain proposals
until native repeated pairs confirm them with clean timing. Do not substitute
another far-from-vanilla POC or more tuning at hit2 for this continuation result.

### Earlier parallel POC direction (superseded by the near-vanilla target)

The earlier POC stage prioritized a legal own-observation controller that sustains a long
native lock against hold-W in **any declared scenario**, massively outperforming
matched plain W-tap, before walking settings back toward vanilla. Run distinct
GPT6.1Sol approaches: simplified analytic existence model, exact-kernel
relaxation/continuation, witness-to-legal feedback, and justified richer-input
search at a failure boundary. Astra continues the seven-hit vertical-cycle
analysis. That stage allowed easier settings first. The later continuation and
settings-first instructions above now take priority. All baseline and validation
gates remain mandatory.

Offline work may run in parallel with isolated owned files. Serialize native
research matches and coordinate CPU load so clocks remain meaningful. The
coordinator stops approaches that do not beat W-tap after a fair trial and focuses
effort on positive, explainable evidence. Each approach writes short findings.
See [worker ownership and experiment coordination](poc-20261101/COORDINATION.md).

Continue autonomously without questions while the user sleeps. Focus exclusively
on understanding combo dynamics; no RL or ML. Deliver either a near-indefinite
vanilla hold-W lock, or a strong, explicitly scoped argument for impossibility
plus a working demo with small declared knockback/damage-delay changes. Different
ping pairs are valid variants. Failure even under easier settings is evidence
that our understanding is incomplete, not proof that human Hypixel combos cannot
exist. Read the actual Zest teaching and distinguish verified behavior from
unverified Hypixel constants. Finding a tweak that enables a cycle identifies a
sufficient mechanism, not necessarily Hypixel's actual implementation.

The user's final overnight clarification explicitly treats a small-tweak demo
plus strong evidence against vanilla as a good outcome. Do not force vanilla
indefinitely after a validated model identifies a concrete obstruction. Quantify
the scope of that obstruction, seek the smallest enabling KB/hit-delay change,
expose it as an optional duel setting, and record a native demo. A failed bounded
search still does not constitute a universal impossibility proof.

Build a small coupled 1D model around ExactTravel; validate movement, server
motion, damage immunity, knockback, sprint transitions and client replicas against
native traces. Search for a feasible repeating hit-to-hit state/cycle, then test
the resulting ordinary inputs natively. Proving absence requires covering the
allowed state/input space, not just a finite parameter grid. Keep bookkeeping
proportionate. Write a short plain-English morning findings document with what
works, what fails, why, numbers and commands to watch the best demo.

## Success definition — latest clarification

### Network-model acceptance gate (latest correction, 2026-11-01)

Use one coupled fight model with ExactTravel inside it. ExactTravel alone is not
the fight model. Validate zero-added-ping native traces first, then introduce
ping. Zero added ping still has independent client/server clocks, queued packet
handling, server tracker cadence/quantization, and client interpolation. Include
client/server phase explicitly and preserve measured event order; inspect native
frame/task handling rather than assuming every packet applies only on a tick.
Model both directions of movement, attacks, sprint commands, metadata and velocity
delivery. Damage immunity runs on the server clock. Include yaw/strafe in the
eventual model, even when the initial calibration deliberately uses W-only.

Before accepting a cycle, report held-out errors in each POV's reach at clicks
and fresh readiness windows, and per-hit positions/knockback. Conditional replay
with observed packets is a component check, not a free-running fight prediction.
Cycle margins smaller than model error provide no evidence. Search remains paused
until this gate is met. Preserve failed/stalled fixtures separately from clean
clock fixtures instead of discarding inconvenient outcomes.

The user's illustrative tick pipeline was a sketch, not a specification.
`FORMAL-MODEL.md`, pinned native code and measured traces take precedence. In
particular, S12 is sent inside the attack; client handling uses main-thread task
drains; dirty/isAirBorne tracker updates can bypass the two-tick cadence.

### Revised control space and Zest hints (2026-11-01)

A/D and approach angle are now explicitly allowed and supersede the earlier
W-only restriction. Extend the same fight model to horizontal 2D and validate
input normalization, native aim/head yaw and directional knockback before using
those controls in a search. W-only fixtures remain useful simpler tests.

Treat community tutorial claims as hypotheses: W for two contacts, release for
the third; perhaps an S,N,S strength sequence, but determine actual local/server
sprint state rather than assuming this mapping. Also test the shorter two-contact
variant, selective clicking, and slight A/D angle. First latency variant after
zero-RTT validation: vanilla combat with the opponent around90–140ms RTT, before
changing knockback or immunity. Explain any benefit through delayed velocity,
movement and replica histories, not “ping increases the KB constant.”

The user further clarified that Zest may be Hypixel-specific. Vanilla is the
primary target; do not spend effort reproducing its recipe exactly. Use the
mechanism hints to derive and test vanilla controls from the validated model.

Latest search correction: both initial oracle drivers fixed strafe at zero.
Expand their ordinary-key branches to A/neutral/D before drawing conclusions
from full-reach failures. Then run a small, explicit factorial grid over
horizontal KB, vertical KB, fresh-hit ticks and opponent RTT. Map finite
maintenance witnesses and the unresolved region; distinguish failed acquisition
from failed maintenance search. A heuristic no-witness cell is not an
infeasibility certificate. Preserve a known successful trajectory as a search
regression so beam pruning cannot silently erase the positive control.

Keep acquisition (hit-selection, winning a trade, favorable first contact)
separate from maintaining an established lock. Hold-W remains the primary target;
reported escape inputs (S, sneak, blockhit, facing away) are later robustness
tests. Tutorial reference: https://www.youtube.com/watch?v=mTeXebYfIIs .

Keep the opponent unable to return a hit, and have them in our **own client's
legal reach when the next native fresh-damage window opens**, so we can keep
landing fresh hits promptly. Excursions outside our own reach during the
opponent's damage immunity are allowed. Merely waiting longer to click does
not postpone the game's damage window and must not turn a missed opportunity
into success.

Minecraft1.8 has target damage immunity, not the later weapon recharge system.
Use the actual native immunity/tick timeline for retrospective scoring; account
for the time from a local attack input to server processing. A stronger damage
upgrade during immunity is distinct from a fresh knockback hit. Do not count
crit upgrades or rod contacts as additional fresh sword contacts.

Track, separately:

- Longest regular fresh-hit chain with no accepted return; whether it ended or
  was censored by the test finishing. Long runs matter more than a percentage.
- For each fresh-damage window: when it opened, local reach around the relevant
  attack time, first eligible native contact, actual accepted hit, and avoidable
  delay/missed windows. An unavailable timing/POV observation is unknown.
- Defender reach opportunities from the defender's own POV throughout the
  chain, even when its actual click missed. Also report actual return hits.
- Time-weighted one-sided reach, own out-of-reach excursions during immunity,
  longest continuous one-sided interval, distance/momentum/height trajectories.
  These explain the result; continuous own reach is **not** a required success
  criterion after the user's latest clarification.
- Final full-reach boxing performance against hold-W, then W-tap, at equal CPS.
  A large reduced-reach winning score alone does not establish combo control.

The ultimate objective is a near-indefinite lock against native-reach hold-W,
ideally robust across pings and maintainable against any straight-running
opponent once the first one or two hits establish a favorable state. Do not
claim infinity from a finite match, or impossibility from unsuccessful tuning.

## Mechanics and control constraints

- Reference: real vanilla Minecraft1.8.9, normal speed, flat ground, large arena,
  no potions. Real headless Minecraft with the shared Forge practice mod is the
  primary backend; preserve legacy code/history. Do not substitute Mineflayer
  or an approximate engine for the reference experiment.
- Both research bots have the same **20 CPS ceiling**. The controller can click
  less or in bursts when justified. Earlier10CPS and human reaction limits were
  relaxed; perfect local aim and superhuman tactical precision are allowed.
- Initial calibration uses forward W/coast and ordinary sprint inputs. The latest
  control space also permits A/D and angle; keep aim noise disabled while testing
  those mechanics. Do not interpret the earlier W-only simplification as a ban.
- Strategic jumps are an allowed later control option, particularly when there
  is ample room. Test them as a separate expansion so their contribution is clear.
- Every issued click goes through normal native input, miss handling, collision,
  damage and drag. An immune contact can still slow the attacker. Never silently
  remove immune attempts or their physical consequences after input issuance.
- W-taps normally reset sprint after a landed hit; they are not arbitrary
  periodic releases. Timing the release relative to an incoming hit matters.
  Deliberately selecting a *normal* hit may instead require releasing W before
  it. A post-hit release cannot change the knockback already delivered.
- Movement, clicking and hit strength are coupled. Coasting may clear sprint;
  resuming W may re-arm sprint or trigger the native double-tap behavior. Verify
  actual local and server sprint state at contacts, not merely policy labels.
- A sprint hit into an airborne opponent can produce much larger displacement
  than a grounded launch because motion persists differently in the air. The
  initial impulse itself is not necessarily larger. Investigate strong hits
  near the ground and normal hits in the air as a phase-dependent hypothesis.

## Distance, momentum and observation

The useful framing is: advance while knocking the opponent backward, keep the
relative spacing controllable, and be ready to hit again when immunity expires.
Maximal knockback alone is not the objective. Nor is standing at a fixed gap
without accounting for velocities, height, remaining immunity, click drag and
future acceleration/braking.

Distances are native eye-to-expanded-hitbox distances measured independently on
each client. Three blocks means actual native3.0 selection, not center distance.
Positive advantage means **opponent's distance to us minus our distance to them**.
The user's rough .5-block advantage while advancing toward a still opponent,
and possibly1block when the opponent moves backward, are hypotheses to measure,
not universal constants. View delay/interpolation, speed, height and phase matter.

Use only the controlling client's observations and known configured pings in a
competitive policy. Never feed the other bot's private client view or server
state into live control. All POVs may be aligned by real host time and analyzed
offline. An explicitly labeled temporary oracle can diagnose feasibility, but
must not be presented as an autonomous legal controller.

## Method: build capabilities, then harden

Do not resume blind tournaments over unrelated variants. Use a predictive model
and successively harder problems:

1. Separate acquisition from maintenance. Fix/rest the starting geometry and
   record clock phase, first-hit state and any opening handicap. A target that
   misses the opening or begins attacking after a delay is a valid declared
   stepping stone. Clock phases are not identical merely because positions are.
2. Prefer reducing **opponent** reach (for example2.5, lower if needed) while
   keeping our reach3.0. Prove the intended spacing/ready-window capability,
   rather than celebrating a large score. Smaller knockback or altered damage
   delay are possible additional relaxations, clearly separated from vanilla.
3. Identify the smallest failed transition: initial contact, one knockback arc,
   next fresh window, then several cycles. Use exact conditional travel and
   recorded packets/inputs to validate predictions and understand the failure.
4. Use simple feedback/PID or model-based input search where appropriate. Choose
   state variables and tuning from the plant's response and reach constraints.
   A model-based search with explicit constraints is different from blind policy
   mutation. Failed searches require model/feasibility diagnosis.
5. Confirm a capability with frozen settings, both sides and longer runs before
   raising opponent reach toward3.0. Then broaden initial conditions and RTTs,
   and move from hold-W to W-tap. Keep acquisition and maintenance claims separate.
6. Make causal comparisons one mechanism at a time, inspect combinations only
   when there is a reason, and retain negative and timing-invalid evidence.

An exact movement kernel does not make the coupled fight/network model exact.
Report the tested scope and held-out errors. A rigorous impossibility argument
must cover the permitted controls/states, not just one failed controller.

## Human evidence and practical setup

Analyze Nomophobia's historical W-tap matches, especially rare long rod-diamond
combos. Inventory all recordings, including old server-only files. Distinguish
rod-assisted acquisition from subsequent sword-only maintenance; separate changed
reach, CPS, ping, equipment and missing client POVs. Human wins and existing
W-tap wins are useful sanity checks that a weak research controller is not the
best achievable behavior.

Keep matches/replays available for deliberate practice. Preserve server and both
client recordings with identities/builds and timing quality. Use more concurrent
matches only after demonstrating that laptop load does not contaminate clocks;
single serial diagnostics are appropriate during model development. Do not
interrupt a human duel to deploy/reload research code.

## Evidence pointers

- `research/combo-orbit-controller-spec.md`: implementation history and failures.
- `research/human-combo-findings-20261101.md`:254-match historical audit and POV
  qualifications; m1071 is a useful six-fresh-hit example.
- `research/curriculum-strict-audit-20261101.json`: retrospective **stronger**
  continuous-own-reach metric, retained as a secondary diagnostic.
- `research/launch-envelope-20261101.json`: conditional native-launch envelopes.
- `research/launch-phase-model-20261101.json`: simple visible-height predictor
  benchmark; failed transfer must be resolved before relying on it in control.

## Current setup boundary correction (v11)

The RTT120/0 calibration exposed client seed reset after fresh packet effects.
Initialize once before an eligible main-thread handler or HEAD, then keep
initialization distinct from first control HEAD. A later HEAD must preserve
received positions, velocities, hurt cues, click history and input queues.
Partial initialization failure is terminal for that match. Keep original strict
empty-queue qualification and separate causal-preservation evidence; newly emitted
packets queued before server initialization but handled afterward are not the
same failure as overwritten live effects. Do not import packet-stage releases
with an extractor that assumes release==HEAD or hardcodes RTT/seed.

Six v11 native calibration games m1480–85 show candidate1/1/2 versus WT2/1/1,
all182 fields exact, no overwritten effects, no cap/reach violations or scored
>100ms clock gaps. They are DEVELOPMENT, not held-out, and not a lock. The strict
empty-queue audit still rejects four for ordinary newly generated server-bound
queues. Preserve all attempts and the earlier invalid m1472–77 comparison.

All12 v11 client releases in this batch were input_HEAD; a separate native
packet-first branch check is still required. Do not count no observed overwrites
as native coverage of a branch that did not execute.

Follow-up: fixed four6s coincident-deadline probes m1486–89 all pass separate
causal state preservation; m1486B/m1488B exercise packet initialization before
HEAD, with replica-update retention measured32–33ms later. No received hurt cue
occurred in those gaps, so that native coverage is absent. The original strict
empty-queue labels remain unchanged. Exact reconstruction must support separate
initialization/control epochs and the containing drain before these calendars
can be model prediction evidence.

RECORDED-CLOCK FOLLOW-UP: both packet-first examples reconstruct through the
first return within1.34e-12 blocks. All six HEAD-first v3 native games
also reconstruct (291 own/291 replica snapshots,2.41e-12 maximum); the unchanged
conditional timing sampler includes all45 resolved observed-arm counts of48,
retaining three UNKNOWNs. Across96 paired arms five long-stall UNKNOWNs remain.
This conditions on actual host clocks and measured input/uplink costs, not a
validated general future-clock distribution. Host-calendar differences change
the same plan from1 to4 freshs. No native lock or new controller promotion.

POST-FOURTH CHECKPOINT: completed all64 frozen final words plus incumbent;
best local plan gives7/7/7/7/6/7/5/6 versus same-pre38 primed WT7/9/5/6/7/6/8/5,
with four prior fresh hits shared. All outcomes resolve, all candidate arms
return2.60–4.15s. Four wins/three losses/one tie is not clear maintenance
improvement. Do not substitute original-seed WT2–3 for this much stronger
same-checkpoint control. Preserve the earlier incomplete105/596 scoring pass.

The current own-window aimed-ray score samples at SERVER immunity reopening;
it is an instantaneous geometry diagnostic, not proof of a missed legal fresh
attack. The C02 accepted then may have been consumed earlier, and local aim may
refresh later. Likewise `own_ready_consumes` uses contemporaneous server state,
not eventual packet acceptance. Frozen historical scores stay unchanged. Before
another search, link actual selection/CONSUME→C02→server acceptance, with exact
packet order and explicit ambiguities; compare acceptance delay and contact
margin against same-checkpoint WT. Separate inherited exposure from a failure
only after the fixed word ends and its WT tail begins. No new controller feature
or native promotion follows from the failed opening-snapshot score alone.

CAUSAL CONTINUATION FOLLOW-UP: actual consume→C02→server attribution now feeds
the continuation objective. Extending the49-row word gives two wins/two losses/
four ties against same-pre49 W-tap and no five-second qualified sample. On the
worst development checkpoint, all18^3 HEAD49–51 words return at2598.267ms after
the same2516.527ms opponent contact. No UNKNOWNs or survivors. This finite family
retains current aim, own-cue click admission and51ms cap; it excludes backward
movement, jump and independent yaw. It is a checkpoint-specific late-control
bound, not combat impossibility. Find the earlier causal deadline: S12 must
affect a victim MOVE before the return CONSUME, not merely arrive before it.
Keep the separate lock8-derived declared seed experiment distinct from an exact
historical clone; missing old queues do not prevent testing a clearly declared,
coherent empty-queue state with matched W-tap.

EARLIER-CLICK CHECK: opening only gate46/47/48 preserves all eight fresh counts
and return times (56 arms, zero UNKNOWN). Worst-case early attempts miss at3.253
and3.060 blocks. The earlier valid contact sends S12 at2567.836ms, too late for
the required2466.011ms MOVE or2516.527ms return consume. Fatal B's rendered A
uses the report created after HEAD45; a newer report received before the click
only changes its interpolation goal, with interpolation occurring afterward.
These are exact observational packet chains, not a global earliest-control bound.
Same-pre38 W-tap reaches contact earlier and gives eight fresh hits versus five.
This motivates reusing the existing control family from that established state
with the corrected causal-contact objective, not adding another late click rule.

NOVEMBER2 CONTROLLED LOCK8 PROJECTION: one declared state from m1364's8025.994ms
policy marker, empty queues and matched wire bases, yields cold/warm coast
6/7/8/8 freshs versus same-complete-state WT2/2/1/1 across four development host
calendars, two draws each. All24 arms resolve through first return in a20s test;
all coast returns occur by3.053s. Old3s outcomes/event prefixes reproduce exactly.
Cold memory is sufficient for this bounded gain. Every coast fresh is normal;
all failures have S12 effect→B consume→B MOVE. Last own source ray is roughly
2.20–2.25, while B receives our older advancing positions after we already coast.
This is a promising declared start, not a native/indefinite-lock demonstration.
Use the identified earlier brake/resume history to justify only small control
changes; retain matched WT and the four-calendar rather than eight-independent-
phase interpretation. The corrected H2 pre38 search did not clearly beat WT and
is stopped; its discarded known-good prefix proves pruning failure, not combat
infeasibility. No held-out calendars were used by either new experiment.
