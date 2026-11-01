# Timed blocking and practice feedback — 2026-10-31

`/duel blockhit_timing diamond` is available on the local 1.8.9 server.
The first repeated batch beat hold-W 3/3, but won only 1/3 against each W-tap
baseline. It blocks a majority of accepted incoming melee hits; it has **not**
demonstrated a consistent advantage over W-tap.

## Implemented practice features

- Results and the player's bottom bar use **blocked incoming melee hits / all
  incoming melee hits**. Missed block attempts do not enter this percentage.
  Accepted critical upgrades count; rejected attacks and rod contacts do not.
- **Damage blocked %** follows hit block %: saved HP / (melee HP lost + saved HP).
  This is a sum of same-hit counterfactuals, not a whole-fight simulation without
  blocking. Healing, environmental damage and zero-damage rod contacts are excluded.
- `Blocking saved: X hearts (Y normal hits)` sums each blocked hit's saved HP
  divided by a full noncritical, unblocked hit from the actual weapon against
  the same armor, Resistance and sampled Protection roll. The reference unit
  excludes absorption, remaining-HP caps and partial immunity deltas; actual
  saved HP retains those effects. Thus a lethal clipped hit does not redefine
  what a full normal hit means.
- Temporary colored action-bar feedback reports `BLOCKED · held ~80 ms`,
  `EARLY · released ~60 ms before hit`, or `LATE · blocked ~90 ms after hit`.
  Early/late requires an unblocked melee hit within 250 ms of a guard edge;
  the nearer edge wins. Successful blocks show hold duration, not optimality.
  Server timing includes network and tick scheduling, rounded to 10 ms. The
  feedback keeps block percentages and HP visible, then expires after one second.
  `/blocktiming off` hides it. No client mod or resource pack is needed.
- Actual native block reduction plays a private, quiet `random.anvil_land`
  (volume .25, pitch 1.6). `/blocksound test` previews it; `/blocksound off` mutes it.

## Controller and assumptions

The bot uses native movement/attack/use-item keys, client-POV perfect aim, a
10 CPS ceiling and native three-block reach. It has zero artificial reaction
delay, as authorized for these later experiments. No server combat state is
available to the policy.

It takes an opening trade, then predicts the next fresh incoming hit from
the local receipt of vanilla's S19 hurt status. Expected command time is
`hurt_status_receipt + 500 ms - own RTT`; the command window starts 75 ms early
and ends 100 ms late. It releases after a fresh hurt cue and avoids blocking
outside the projected approach range. When the expected window passes without
a hit, it alternates 150 ms released / 200 ms guarding until resynchronized.
It normally holds forward and sprint; pilots separately tested adding W-taps.

S19 distinguishes fresh hits from health updates caused by a stronger critical
inside the immunity interval. In m0681–m0683, all 129 fresh hits produced the
native cue within 60 ms; none of 21 critical upgrades produced a false fresh cue.
See [cue audit](../experiments/blockhit-20261031-195348/hurt-status-check.json).

## Repeated final-policy batch

Real native `diamond` duels, Protection IV diamond armor, diamond sword, normal
speed, 20/20 ms added RTT. Three games per opponent, reversed allocation on
the second replicate. All nine passed input/reach/identity/build qualification.

| Opponent | Wins | Blocked / incoming melee | Block % |
| --- | ---: | ---: | ---: |
| hold-W | 3/3 | 62/97 | 63.9% |
| W-tap, normal 150 ms reaction | 1/3 | 130/205 | 63.4% |
| W-tap, zero extra reaction delay | 1/3 | 122/196 | 62.2% |

[Frozen configuration, results and raw traces](../experiments/blockhit-20261031-195946-939bd0/report.json).
Example replays: [hold-W win m0698](../recordings/m0698.html),
[W-tap win m0696](../recordings/m0696.html),
[W-tap loss m0703](../recordings/m0703.html).

Additional single-game W-tap samples: 0/0 ms was a simultaneous-death draw
(61.3% blocked); bot100/opponent20 lost (57.4%); bot20/opponent100 won (51.2%).
These are too few games to establish a ping-dependent ranking.

Longer guards did not solve W-tap: the 125 ms lead / 500 ms late pilot blocked
75% but lost 33–80 hits. Extra W-tap pilots and no-block controls are retained
in the `blockhit-20261031-195519-*` and `blockhit-20261031-195750-*` directories.
Those one-game pilots are exploratory, not isolated causal estimates.

### Native client/server guard mismatch

Native-client blocking duty was only 11–21% by opponent in the repeated batch,
but it must not be interpreted as server-side guard duty. Server guard
intervals had median 104 ms, p95 1599 ms and maximum 3196 ms, despite much
shorter intended key pulses.

A read-only trace in the follow-up batch confirms a native inventory-reference
path: `EntityPlayer.onUpdate` sees that the current sword instance differs
from `itemInUse`, then invokes `clearItemInUse`. For example, m0720 changed
from used sword damage31 to held sword damage29 while the use key was still
down. Normal key releases instead trace through `PlayerControllerMP.onStoppedUsingItem`.
The pinned vanilla tick only sends that release when the client still thinks
it is using an item. This explains how local cancellation can leave the
server guarding until a subsequent normal release. No policy uses the server
guard state or deliberately forces this mismatch, and no combat patch was
introduced to remove it. Timing coaching describes actual server guard edges.

The [follow-up trace batch](../experiments/blockhit-20261031-201133-d7491a/report.json)
adds `block_use_clear` records with item identity, durability and native call
path. The [guard cancellation audit](../experiments/blockhit-20261031-201133-d7491a/guard-cancellation-audit.json)
aligns those cancellations with server guard intervals. This diagnostic supplies
no inputs to the controller. The follow-up again won against hold-W and lost
against W-tap; it is additional to the nine-game batch above.

## Validation

- [Native checks](../experiments/blockhit-native-checks.json): 40,320 normal-hit
  equivalence cases against unmodified 1.8.9 mitigation with real equipment
  combinations for **every armor total 0–20**, both swords, Protection IV,
  Resistance, criticals, partial immunity upgrades, absorption and lethal caps.
  Full normal-hit reference damage matches the native float result exactly;
  HP saving/equivalents use a 1e-6 tolerance for float arithmetic.
- The same check bundle passes 9,600 paired native block/crit counterfactuals,
  plus controller, block ratio, damage fraction, feedback attribution and
  click-ceiling regressions.
- [Live practice test](../experiments/combat-stats-20261031-200620-9764f3/report.json):
  **132/132 checks passed**, including normal-hit sums, damage percentages,
  rod exclusions, actual-client action bars and the received metallic sound.
  Covers diamond, rod_diamond and wooden-sword diamond_box.
- [Focused native feedback test](../experiments/block-feedback-20261031-200905/report.json):
  **BLOCKED, EARLY and LATE all received by a real client**, using ordinary
  100 ms guard pulses every 275 ms in diamond_box.

Earlier prototype m0673 is excluded for a stale opponent client build.
The abandoned `blockhit-20261031-195514` collection is excluded for a runner
output-directory collision; m0685 was queued but not collected as a qualified
trial. The runner now uses UUID-suffixed directories and requires all eligible
idle bot clients to use the current build. Poor qualified pilot results are
retained rather than discarded.
