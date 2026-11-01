# Human combo evidence relevant to the maintenance curriculum

The inventory snapshot contains 254 completed Nomophobia matches against
policies whose names contain `wtap`, including 179 `rod_diamond` matches.
It includes historical server-only replays and recent complete POV archives.
The strongest transferable example located is m1071: six fresh sword hits with
no sword return, grounded human movement, and no rod use during the sword chain.
Its opening was rod-assisted and its opponent clicked at 10 CPS.

## Counting and exclusions

[The scanner](human-combo-inventory.py) reads result metadata and historical
server replay hit events without changing runtime. Its
[inventory](human-combo-inventory-20261101.json) preserves every screened match,
configured reach, ping, equipment mode, available POVs and each extracted chain.

A regular chain counts explicit fresh sword hits, excludes stronger immune-frame
damage upgrades and rod contacts, breaks on any accepted opponent sword damage,
and permits at most 1,000 ms between fresh sword contacts. A separate unbounded
episode list removes that gap restriction. Old records without an explicit
upgrade classification remain unclassified accepted hits. Hits sharing the
opponent's return server tick/timestamp are flagged and excluded from an
additional stricter count. These are finite no-return chains, not automatic
proofs of uninterrupted geometric denial.

The unbounded episodes did not reveal a full-reach chain above six fresh sword
hits. Rod contacts and damage upgrades can make a scoreboard streak substantially
larger than its fresh-sword count.

| Match | Fresh sword chain | Match interval | Context and limitation |
| --- | ---: | --- | --- |
| m1071 | 6 | 20.594–23.693 s | Full reach, 0/0 added RTT, 10 CPS Wtap; rod opening, no rods during chain; all three POVs |
| m0996 | 6 | 6.091–9.494 s | Full reach, boxing, 0/0, 10 CPS `wtap-control`; server replay; prior incoming knockback |
| m1148 | 6 | 17.546–20.891 s | Full reach, boxing, 0/0; opponent **7 CPS**, all three POVs |
| m0688 | 6 ordered, 5 excluding return tick | 39.298–42.396 s | Full reach, rod kit, human/bot RTT100/0; no rods during or previous1 s; old server replay; last hit shares return tick |
| m0958 | 5 | 15.441–18.136 s | Full reach, rod opening, no rods during chain; archived bot POV |
| m0932 | 5 | 1.340–3.891 s | Full reach, rod opening, no rods during chain |
| m1003 | 5 | 16.436–18.791 s | Full reach; two rod contacts during this sword chain |

The ten-hit chain in m0628 (3.695–9.395 s) used **1.5-block opponent reach**.
The seven-hit examples in m0629 used **1.0-block opponent reach**. m0633 has
six hits at 0.894–4.444 s with **2.5-block opponent reach**. These are useful
curriculum examples, not evidence for equal three-block reach.

Historical builds and equipment differ. For example, m1071 used wooden swords
and protection0; m0688 used diamond swords and protection4. No ranking here
claims controlled comparability. Older missing native evidence is not a pass.

## m1071: the strongest recent maintenance example

The full no-return episode starts with rod contacts at 19.240 and 19.994 s.
The six subsequent fresh sword contacts are:

| Time s | Sprint on server | Human height | Target height | True horizontal gap | S12 vertical launch |
| ---: | --- | ---: | ---: | ---: | ---: |
| 20.594 | Yes | 0 | 0 | 3.139 | .46075 |
| 21.289 | Yes | 0 | 0 | 3.397 | .46075 |
| 21.944 | Yes | 0 | .115 | 3.368 | .27625 |
| 22.490 | Yes | 0 | 0 | 3.423 | .46075 |
| 23.163 | Yes | 0 | .115 | 3.110 | .27625 |
| 23.693 | Yes | 0 | 0 | 3.049 | .46075 |

Heights are above the y=4 floor. The opponent returns at 23.744 s, with true
horizontal separation down to about 2.501. No rod contact occurs during the
six-sword sequence. The same low airborne .27625 launch seen in m1156 appears
twice here, with another human fresh hit afterward. A low launch is therefore
a meaningful phase perturbation, but is not by itself sufficient to explain
immediate failure.

The human's 62 native tick-start input samples during the chain contain:

- 41 W-only samples and 16 samples with forward and strafe both zero.
- Four early samples involving A-strafe, then predominantly straight pursuit.
- One reduced-forward sample; no jump input anywhere in the chain.
- 21 physical attack-click events across 3.099 s, about 6.8 CPS in this interval.

The later W releases are concrete, locally recorded actions:

| Release s | Resume s | Duration ms | Release after prior fresh sword ms |
| ---: | ---: | ---: | ---: |
| 21.477 | 21.670 | 193 | 188 |
| 22.070 | 22.229 | 159 | 126 |
| 22.617 | 22.776 | 159 | 127 |
| 23.172 | 23.318 | 146 | 9 |

The human also makes shorter earlier releases. This is not simply a fixed
one-update W-tap. It suggests observing forward momentum and spacing over the
middle of the knockback arc, then using roughly three updates of coasting when
needed. It does not prove that copying these durations alone reproduces the
sequence: click rate, aim, initial strafe, rod setup and replication phase differ.

All 30 recorded opponent click consumptions within the chain miss. However,
nearest-eye-to-expanded-box distance is within3 at the 20.601 and23.684 s probes
(2.771 and2.865 respectively). Thus the whole six-hit chain is not a continuous
all-aim denial certificate. The interior sampled denial contains four fresh
contacts. Increasing opponent CPS can catch initial/terminal leakage and alter
contact drag, so this 10-CPS recording does not settle the20-CPS task.

The relevant human and bot archived event streams both end with
`dropped=0`, `size_capped=false`, and `writer_complete=true`.
Native probe samples and exact input edges are preserved in
[the POV diagnostic extract](human-combo-pov-diagnostics-20261101.json).

## Other examples and jumping

m0688 contains a six-event ordering chain after an incoming hit102ms before its
start. The human first rises to about1.485 blocks; this cannot be attributed to
a deliberate jump from server positions alone because incoming knockback is
already present. Five hits precede the eventual shared-tick trade. Four of its
later contacts again occur near the target's floor/landing phase.

m0996 also starts shortly after incoming knockback; its later fresh hits are
all server-sprint attacks at target heights0 or.115. The old server replay can
show positions, fresh event timing and impulse-related state, but cannot prove
the human's click decisions, exact visual range, or jump key.

m1148's six-hit chain has no jump inputs either. It has49 W-only,13 no-forward
and4 reduced-forward samples;12 human clicks and22 bot clicks in the interval.
The bot makes one local contact and has several reachable probes, although
none becomes accepted return damage before the chain ends. Its7-CPS opponent
makes it a useful weaker-capability case, not an equal20-CPS benchmark.

Strategic jumping remains a possible later control expansion. These strongest
recent observed chains provide no evidence that jumping is necessary. Keep the
current W-only curriculum isolated until spacing and hit phase are understood.

## Recommendation for the easy maintenance stage

Stay with the user's single-capability ladder: candidate reach3, opponent
continuous hold-W+Ctrl at20 CPS and reach2.5, one fixed opening and one low RTT
cell. First diagnose the running continuous-click spacing controller. If the
failure is the earlier airborne hit/low-launch phase, add only the planned
minimum-age attack guard, and remove assumed .6 drag while clicks are gated.
Do not simultaneously change CPS, jump, aim, rod behavior, pings and opponent
policy.

The human evidence supplies a second, targeted repair hypothesis if needed:
longer midpoint coasting can recover a low-launch cycle while keeping a later
sprint attack available. The detector should use own local momentum, target
visible height/slope and predicted range, not an oracle for target server
motion or the opponent's current reach.

Require three independent60-second post-opening runs with no accepted opponent
return and at least60 fresh own contacts, no contact gap over1,000ms, verified
native input/reach/cadence, and explicit sampled geometry margins. Before
claiming stable maintenance, inspect cycle gap/height drift rather than relying
only on streak length. Increase opponent reach by0.1 only after the current
stage passes; hold the controller, opening and RTT fixed until a specific
failure justifies a change. Reach changes can alter opponent contact drag, so
each new stage needs actual native validation. Equal-reach and ping variation
come later. Finite qualification is a capability milestone, not an infinity
proof.
