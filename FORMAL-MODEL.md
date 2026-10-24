# A falsifiable model of native Minecraft 1.8.9 melee

Audit date: 2026-10-23. This specifies the experiment before selecting a strategy.
The reference is Mojang's pinned **1.8.9 binaries**, not recollection, a simplified
simulator, or results from another server. `ComboModel` is an approximation to
this specification; it is not its definition.

**Validation status:** the transitions identified below as **code-checked** were
checked against the local official bytecode and MCP name mappings. Native
evidence includes 23 controlled scenarios and the archived traces linked in
Section 10. This document does **not** claim that the complete forward model has
passed the native validation suite below. Conditional checks and uncorrected
rollouts are reported separately.

## 1. Experiment, legal inputs, and quantities to predict

Two ordinary survival clients duel on a loaded, flat floor with normal block
slipperiness, full hunger, no armor, effects, enchantments, mounts, water or
ladders. The lab equips unenchanted diamond swords. Health is restored for
measurement, while ordinary damage acceptance, resistance and knockback run.
One scoreboard point means an accepted attack, including a damage upgrade;
it does not mean one fresh knockback event or equal health damage.

The primary action space is W and Ctrl, optionally normal A/D. Jump is excluded
from this baseline; jump-reset claims require a separately declared experiment.
Aim uses the current **local rendered** opponent and the lab's actual aiming
rule. It never aims at a server or other client's private position. Physical
attack presses are independent short press/releases, at least 100 ms apart.
That is the default action constraint. The later user-requested `wtap-cps*`
experiment explicitly declares 15/20/25/30 CPS ceilings for its high-rate side;
the general bound is `ceil(1e9 / declared_CPS)` nanoseconds between completed
physical inputs. Normal input queuing and vanilla tick processing remain intact.
Keep the fixed click process running through misses and immunity; the controller
does not filter attempts using hit results. Nominal 10 CPS is an upper bound:
record the actual injection times and frame/tick stalls.
The later [burst-clicking cohorts](research/burst-clicking-results.md) explicitly
test selective input schedules. They retain ordinary processing of every issued
click; they do not cancel an immune contact inside the engine.

At decision time t, tactics may use sampled own and visible-opponent state only
from timestamps s <= t-D, plus the declared policy/RTTs and our already
issued inputs. Known input timing and an open-loop plan are available immediately.
D is a declared per-policy observation delay: 150 ms in the original experiments,
and 0 ms in the newer motion/critical/fast cohorts after the user's rule change.
It is separate from network/view latency and from permitted current-view aiming.

Predict separately: local trajectory, each rendered remote trajectory, raycast
availability, local contact slowdown, accepted fresh hits, damage upgrades,
velocity packet values/arrival, sprint commands, and fresh hurt cues. A final
score alone cannot establish that these transitions are correct.

## 2. State and event time

Use a timed, discrete-event system with continuous-valued positions and
velocities. There are three clocks: A's native client ticks, B's ticks, and
server ticks; frames/input injections and packet dispatches add events between
them. Nominal tick period is 50 ms. Actual timestamps, processing order, and
stall/catch-up behavior must be recorded rather than rounded into one shared
tick index.

For i in {A,B}, j != i, retain:

| State | Meaning |
|---|---|
| C_i = (p_i,v_i,g_i,yaw_i,pitch_i) | Local position, **stored post-movement motion**, onGround and look. Position displacement per tick is not generally v_i. |
| headYaw_i, prevHeadYaw_i | Living-entity head yaw used by native `getLook`, distinct from the frame's requested rotationYaw; retain the relevant previous angles for partial-tick rays. |
| s_i, sent_i | Local sprint flag and last sprint flag transmitted by that client. |
| aGround_i, aAir_i, tap_i, priorForward_i | Current movement-speed attribute, cached air acceleration, and double-tap/input history needed to predict transitions. |
| R_ij = (r,q,n,orientation) | i's rendered copy of j, interpolation target and remaining interpolation steps. |
| S_i = (P_i,M_i,G_i,Yaw_i,F_i,Sprint_i) | Server position, **server motion**, onGround, last received look, fallDistance and sprint flag. These differ from C_i. |
| H_i,L_i,hurt_i | Server hurtResistantTime, last full incoming damage, and the separate local hurt animation state. |
| T_i | Tracker baseline quantized position/look, tick counter, ground state and dirty/isAirBorne/velocityChanged flags. |
| Q, K_i | Ordered network/main-thread tasks and physical click/input history, including queued presses. |

An exactly reproducible native state also includes collision/world state,
floating-point fields, packet subtype/send thresholds, RNG state and event
ordering. A compact model that omits them must identify the affected tests.

For the lab latency proxy, modeled one-way legs are RTT_i/2 plus the measured
transport/dispatch residual. Do not assume arbitrary Internet RTT implies equal
upload/download delays. Preserve per-connection packet order; order between
connections is an observed event, not a universal A-before-B rule.

**Native causal order, code-checked:**

1. Network packets become main-thread work. Processing is tied to task drains;
   “every packet is applied at the start of the next physics tick” is a useful
   approximation, not an exact timing law.
2. A consumed entity click sends C02 ATTACK and executes the local attack before
   the local player's living update. The attack can clear local sprint.
3. The living update reads movement keys, applies sprint eligibility, movement
   and collisions. `onUpdateWalkingPlayer` subsequently compares local sprint
   with `sent_i`, sends START/STOP if different, then sends movement/look data.
4. The server processes ATTACK using the server state existing at that event.
   Later movement/START packets cannot retroactively change that attack.
5. A server player world update decrements resistance once; a C03-driven
   `onUpdateEntity` does not supply another resistance decrement.
6. Tracking produces remote-view updates; client processing sets interpolation
   targets, and remote-entity updates advance those targets.

Consequently a START sent on the same native tick as an attack is too late for
that attack. A server hit does not make its attacker immune. Two reciprocal
attacks can both succeed; winning the first packet race does not itself deny a
trade.

## 3. Client movement transition

Let horizontal raw input be (strafe,forward) = (S,F), with S in {-1,0,1},
F in {0,1}. For the stated baseline, no item-use/sneak multiplier applies.
Vanilla first multiplies both by .98, then `moveFlying` normalizes only if the
resulting norm exceeds 1:

    b = .98 (S,F)
    u = b / max(1, ||b||)
    R_yaw(u) = (u_strafe cos(yaw) - u_forward sin(yaw),
                u_forward cos(yaw) + u_strafe sin(yaw))

This normalization matters for A/D; adding independent full forward and strafe
accelerations is wrong. Vanilla uses floats and a trigonometric lookup table.

Before travel, components with magnitude < .005 are zeroed. In the dry, loaded,
collision-free horizontal branch, with onGround sampled before movement:

    f = .91 * blockSlipperiness   if grounded; otherwise .91
    a = aGround * .16277136 / f^3 if grounded; otherwise aAir
    v_h* = v_h + a R_yaw(u)
    p'   = collision_move(p, (v_h*, v_y))
    v_h' = f * horizontal motion remaining after collision
    v_y' = .9800000190734863 * (vertical motion after collision - .08)

For the ordinary floor, slipperiness=.6 and f≈.54600006. The current movement-speed
attribute aGround is .1 walking or .13 sprinting; settled aAir is .02 or .026.
W-only acceleration before friction is therefore approximately .098/.1274 on
ground and .0196/.02548 in air. **Ground acceleration changes on the same tick as
the sprint flag; air acceleration uses the previous update's cache.**

The distinction follows virtual dispatch: player `getAIMoveSpeed` (`wn.bI()`)
reads the movement-speed attribute directly, overriding the living-entity
getter (`pr.bI()`) that reads a cache. `setSprinting` changes that attribute
immediately, and the local player's sprint eligibility runs before travel.
`EntityPlayer.onLivingUpdate` updates the air factor only **after** superclass
travel. Its subsequent inherited ground-cache write does not determine player
ground acceleration. Therefore a grounded W+Ctrl start from rest moves about
.1274 on its first movement tick; an airborne sprint start uses .0196 on that
tick and .02548 on the next, absent other events.

Steady sprint displacement is .1274/(1-.546)≈.28062 blocks/tick; stored horizontal
motion is about .15322. A ground-origin upward impulse moves the player before
ground friction is applied on that first travel tick. It does not lose half
its first displacement before moving. Landing clips vertical motion and changes
the next tick's acceleration/friction branch.

For optional jump validation only, the ordinary jump sets v_y≈.42 and, if locally
sprinting, adds .2*(-sin(yaw),cos(yaw)) horizontally. It is an event before travel,
not a subtraction from an abstract “knockback amount.” Whether it reduces received
knockback depends on S12/attack/jump order. A baseline controller must not acquire
an undeclared jump input through the model.

## 4. Reach is a view-specific ray test

For a standing player, eye E=p+(0,1.62,0). The remote player's ordinary box is
width .6 and height 1.8, expanded by collision border .1. Approximately:

    B(r) = [r_x-.4,r_x+.4] × [r_y-.1,r_y+1.9] × [r_z-.4,r_z+.4]

Actual native float dimensions remain the reference near boundaries. Given the
chosen unit look direction l, intersect E+lambda*l with this AABB using all three
slab intervals. The near intersection must be within 3 blocks, with no nearer
blocking hit/entity. An eye inside the box is a special zero-distance case.
The authoritative opportunity predicate is native `getMouseOver` selecting j.

For freely optimized aim, the necessary-and-sufficient empty-scene geometric
test is dist(E,B)<=3, where each axis contributes its distance to the box interval.
The lab requests horizontal aim at the target center and vertical aim clamped
to [target feet+.15,target feet+1.65]. Intersecting that desired ray gives an ideal
aim classification. Nearest-box distance is a lower bound on its intersection
distance; neither quantity by itself establishes native selection.

**Source-confirmed aiming-state gap; native failure attribution pending:**
`getMouseOver` obtains its eye and look from the render-view entity. At partial
tick 1, the living-player override of `getLook` uses current rotationPitch and
**rotationYawHead**, not rotationYaw. `Client.frame` sets rotationYaw/pitch to
the requested aim, whereas `EntityPlayer.updateEntityActionState` synchronizes
rotationYawHead with rotationYaw during the living update, before travel. The
local player executes that branch. A target crossing between ticks can therefore
leave the native horizontal ray pointing in a previous direction even though
the desired center ray intersects a nearby box.

The ordinary mouse path does not remove this distinction: renderer
`updateCameraAndRender` passes mouse deltas to inherited `Entity.setAngles`,
which changes rotationYaw/pitch and their previous-angle fields, but not head
yaw. Directly synchronizing head yaw in the frame hook would change that path's
tick semantics. Model and measure the actual ray before considering any input
change; also record render-view identity, entity eligibility and actual AABB.

The three deep-range classification failures in
[`m0367`'s validation](research/native-mechanisms-v2.json) occurred 37, 16 and
15 ms after the last recorded travel. Requested yaw changed by 147.36°, 179.96°
and 169.89° from that travel's yaw. Substituting the preceding travel yaw makes
all three recorded target boxes miss the ray, despite desired-ray distances
.20745, .00582 and .02439. This supports the hypothesis but does not directly
measure head yaw. The fresh holdout logs native `getLook(1)`, head yaw,
expanded AABB intersection and selected entity. In m0374 the desired ray is
2.998530 blocks while the measured-look ray is 3.000314, and native selection
misses. Head and body yaw differ by .1924 degrees. This directly demonstrates the
direction distinction, but the actual-look result lies inside the declared
boundary exclusion. It does not retroactively prove the cause of every
historical sharp-turn failure. All 11,619 fresh actual-look checks outside the
boundary exclusion agree across the holdout and entry/maintenance suites.

On an axis-aligned, level approach, the maximal center separation is about 3.4,
not 3.0; the ray itself is 3.0. The scalar approximation
`max(0,horizontal_center_gap-.4)` is not exact at arbitrary A/D angles. At 45° on
level ground, the center-directed ray permits about 3+.4*sqrt(2)=3.56569.

The ideal nearest-box low-ground threshold is h>1.9-1.62=.28 for the elevated
player's eye, not .18. The lower player's eye remains inside the raised expanded
box until h>1.72, not 1.62. The lab's fixed aim point can incur a small diagonal
ray cost earlier, so neither threshold replaces a native raycast test.

The independent server guard is squared **server position** distance <36 with
line of sight, <9 without. It does not grant permission to generate an ATTACK
from beyond the client's native ray. A frozen remote entity can create apparent
long-range server hits; such a run is invalid, not evidence of a technique.

## 5. Combat transition and the two velocity states

For the unenchanted lab sword, base attack damage d0=8. The code-checked critical
predicate requires server fallDistance>0, !onGround, no ladder/water/blindness,
no mount, and a living target. **It does not require !sprinting.** Thus d is 8 or 12
and a fresh sprint critical can have both critical damage and sprint knockback.

For victim state (H,L), ordinary damage acceptance is:

    if H > 10 and d <= L: reject
    if H > 10 and d >  L: accept upgrade, apply d-L; L←d; H unchanged
    if H <=10:           accept fresh, apply d; L←d; H←20

A fresh event sets hurtTime/maxHurtTime to 10 and emits the fresh hurt status;
an upgrade does not. H decrements on server player updates. In the compact
model's packet-before-update ordering, a fresh 20 becomes 19 at the end of that
server tick and the first equal-damage fresh opportunity is ten server ticks
later. This is a tick predicate, not permission to replace H with an independent
500ms wall-clock timer during stalls. Local hurtTime is neither H nor an exact
server timestamp.

For a fresh accepted hit, snapshot victim **server** motion M before damage.
For nondegenerate horizontal separation, let d_hat point from server attacker
position to server victim position, and l_hat=(-sin(serverYaw),cos(serverYaw)).
For ordinary players with zero knockback resistance:

    K_h = M_h/2 + .4000000059604645*d_hat
    K_y = min(M_y/2 + .4000000059604645, .4000000059604645)
    if server attacker is sprinting:
        K_h += .5*l_hat
        K_y += .1

The base direction and sprint direction can disagree after crossing because
server yaw is the last received look. “Perfect aim implies every component
points toward the current server target” is false.

Fresh damage sets velocityChanged. The outer player attack sends the victim
an S12 velocity packet, clears that flag, then **restores victim server motion
to M**. Packet components are:

    Q(K) = trunc_toward_zero(8000 * clamp(K,-3.9,3.9)) / 8000

On client handling, v_client←Q(K), an overwrite, not an addition to the client's
current velocity. The subsequent client attack/movement event may modify it.

An accepted sprint attack also multiplies the attacker's server horizontal
motion by .6 and clears server sprint. For a damage upgrade, the base knockback
and fresh status are omitted; the sprint bonus still adds to victim SERVER
motion and consumes attacker sprint. `addVelocity` sets isAirBorne, not
velocityChanged. In this isolated player-melee setting it therefore does not
itself send S12. Players are tracked with periodic velocity updates disabled.
A pre-existing velocityChanged flag, such as fresh fall damage earlier in the
same server packet drain, changes this conclusion: any accepted player attack
can then send S12 from the resulting motion, clear the flag and restore the
victim's pre-attack server motion, including on a damage upgrade.

The server is not a passive position store. `processPlayer` invokes
`EntityPlayerMP.onUpdateEntity`, advances native latent motion/collisions, checks
movement, and accepts/resolves the reported position. A grounded server copy
followed by a rising airborne C03 can call `jump()` even when the real client
was lifted by knockback. Server fallDistance follows reported falling movement;
the server player's ordinary simulated updateFallState override is empty.
Do not derive critical eligibility from latent server v_y alone.

**Landing damage is a separate native transition.** For the ordinary dry floor,
without flight, jump boost or damage-reducing equipment, the accepted C03 stream
updates the float fall-distance accumulator F as follows:

    if reported onGround:
        d_fall = max(0, ceil(F - 3.0f))
        if d_fall > 0: apply ordinary (H,L) damage acceptance
        F = 0
    else if reported delta_y < 0:
        F = float(F - delta_y)

The final landing packet's negative delta_y is not added before evaluating F.
Rising packets do not clear F, so successive knockback arcs without touching
ground can accumulate enough descent to cause damage even when no individual
arc rises three blocks. An accepted fall upgrade changes L and applies only the
damage difference; it does not reset H or emit a fresh hurt status. Fresh fall
damage sets H=20, emits the usual hurt status and marks velocityChanged. Since
the damage source has no attacking entity, there is **no base knockback**.

The tracker checks velocityChanged every tick, independently of ordinary
position-update cadence. It sends the current quantized **server motion** to
watchers and the victim, then clears the flag. Thus fall damage can overwrite
the victim's unrelated client velocity without adding an impulse. There is no
outer player-attack restoration on a pure fall. If further C03 packets run
before the tracker, the sent velocity is the motion at flush time. This is
implemented as the isolated, default-off `modelFallDamage` factor; it does not
increase the referee's PvP hit score.

In archived `m0362`, an unattributed-to-player S12 at relative time 2,292 ms has
payload (-.080125,-.078375,.007625), lastDamage=1 and H=19. The victim applies it
before travel at 2,307 ms. With otherwise identical recorded clocks and inputs,
enabling the fall factor predicts that event at 2,292 ms; defender position error
at 5 s drops from 1.38272 m to .04557 m. This validates this counterexample's
explanation, not the complete rollout: a separate error appears at 5,660 ms.
See [the diagnosis and source chain](research/fall-damage-diagnosis.md) and
[the comparison data](research/fall-damage-diagnosis.json).

Consequently .4/.5 vertical knockback is not universal. Conditional on
M_h=0,M_y≈-.0784, aligned yaw, the initial packet is approximately .4/.3608
ordinary or .9/.4608 sprint. Different server motion changes those values. For
an isolated v_y=.4608 launch from the floor, the stated recurrence predicts
height≈.8684 after ten movement steps; v_y=.5 instead predicts≈1.2447. Neither is
the universal height when a remote server immunity window expires.

Vanilla also checks knockback resistance probabilistically and substitutes a
random horizontal direction when separation squared is below 1e-4. Separated,
default-resistance players avoid those sources; “1.8 knockback has no randomness”
is too broad. `ComboModel` currently picks an arbitrary axis at degeneracy.

## 6. Local contact, sprint reset, and fixed clicking

The local target is `EntityOtherPlayerMP`, whose `attackEntityFrom` returns true.
Thus, with no knockback enchantment, an entity contact while **locally** sprinting
performs v_h←.6*v_h and s←false even if the later server attack is immune/rejected.
An air miss does not do this. Further attacks before local sprint is restored
do not apply another sprint multiplier. Native evidence:
[`immune-click-example.json`](experiments/immune-click-example.json) records
-.899875→-.539925, exactly*.6, 83 ms after the previous accepted attack.

Holding W+Ctrl can restore s during that same living update. If s==sent at
transmission time, no new START is sent, even though the server cleared its flag
on the earlier accepted hit. Three sprint values must remain distinct.

Own S1C entity metadata is another transition of the local sprint flag. In the
packet-linked `m0396` trade at 11,643–11,644 ms, A receives its velocity at
11,657 ms and then own metadata changes local sprint true→false before its
11,684 ms contact. That contact retains horizontal motion −.39275 rather than
applying .6; B's intervening contact does apply .6. See the
[double-hit evidence](research/double-hit-mechanisms.md) and
[exact packet links](research/trade-grounding.json). A composed simulator must
apply metadata in native packet order, before testing the click's sprint flag;
held Ctrl alone does not specify that flag at click consumption.

A W release ends local sprint. Releasing Ctrl alone does **not** end an existing
sprint. However, with W held continuously, Ctrl released on a contact tick lets
that contact's cancellation persist and produce STOP; Ctrl on a following
non-click tick can then produce START. A miss supplies no such cancellation.
Double-tap logic is relevant on forward-input rising edges, not continuously
held W. A W-up/reset and a contact-dependent Ctrl-only reset are different
experiments.

For the lab's short press/releases, native `sendClickBlockToController(false)`
clears leftClickCounter. Do not impose a persistent 500 ms miss lockout on this
input process. Log physical injection, native consumption/contact and server
acceptance as three different events. The actual 105-ish ms injection cadence
does not imply “exactly every other native tick.”

The actual Wtap opponent waits until its **own local outgoing fresh-hurt sample**
is at least 150 ms old, releases W/Ctrl for 50 ms, then resumes. Crit upgrades do not
arm another tap. Jitter can make eligibility 150–200 ms and a nominal 50 ms release
span more than one native tick. Its private cues/phases are latent consequences
of the known policy, not controller inputs. Neither “one tick after server hit”
nor “every Wtap hit is sprint” describes this opponent.

## 7. Replication and what the delay formula actually proves

Player trackers have updateFrequency=2, position quantization floor(32P), and a
movement-send threshold of4 quantized units on any axis (plus periodic refresh).
The update branch can also run for **isAirBorne or dirty metadata**, bypassing
the two-tick cadence. Ground-state changes/large displacement can choose the
teleport packet path. These flags and packet subtypes matter around combat.

Ordinary relative movement packets update an integer remote position accumulator
and set interpolation target q=accumulator/32 with n=3. On a remote entity update:

    r←r+(q-r)/n; n←n-1, if n>0

A later packet resets q,n before interpolation finishes. With constant velocity,
uniform two-tick deliveries, aligned updates and no quantization, this recurrence
can give a two-tick steady lag. It is not a universal three-tick pure delay:
thresholding, forced updates, startup, reversals, interpolation phase and packet
dispatch alter the response. Missing world/entity updates invalidate the model.

For a limited 1D explanatory approximation, take A left of B, true gap g, signed
speeds toward each other u_A,u_B, and effective view ages D_A (A sees B), D_B.
If speed/direction/height remain constant over those histories:

    apparentGap_A = g + u_B D_A
    apparentGap_B = g + u_A D_B
    trueGapLimit_A = R_A - u_B D_A
    trueGapLimit_B = R_B - u_A D_B
    advantage_A = (R_A-R_B) + u_A D_B - u_B D_A

Here R is the appropriate geometric **center-gap** limit, not automatically 3.
The brief's difference-of-approach-speeds interpretation is correct in this
restricted setting. The exact model must use position histories and two local
rays instead. Symmetric proxy legs make the sum of network delays equal both
ways; tracking/client phases need not make total view ages equal. Numerical
“extra reach by ping” tables are not engine constants.

Dividing a distance edge by closing speed predicts a contact-time difference
only under monotone, approximately constant closure, away from zero speed and
crossing. Click wait, upload/server processing, velocity delivery and the second
player's independent immunity still determine whether a trade occurs. It is not
a proof of a combo or of optimal hit selection.

## 8. Observer contract and gaps in the current implementation

The controller observes delayed C_A, rendered R_AB and local hurt cues. B's C_B,
both server motions/flags, B's view R_BA, packet goals, source ticks and arrival
queues are hidden hypotheses. Raw other-client/server logs and visible entity
`serverPos*`/interpolation-target fields are **validation-only**, not tactics.
Inference from the permitted history, including interpolation deconvolution,
is legal; it must retain ambiguity and be evaluated on later observations.
Knowing the opponent's policy does not reveal its exact private clock phase,
frame stalls, click realization or current state.

A local fresh cue at L constrains the server event near
`[L-ownRTT/2-50, L-ownRTT/2]` only under the specified one-dispatch-tick model.
The declared delay D determines when this evidence becomes usable; it is not added
to the inferred original hit time. The animation proves fresh **damage**, not
its source: fall damage emits the same cue and can trigger the native Wtap policy.
Historical player-hit confirmation must exclude that alternative first; otherwise
it invents player knockback from environmental damage. Even a justified player-hit
confirmation leaves timestamp, sprint and reconstructed impulse as estimates.
Current ±100ms predicted-attack suppression is a reconciliation heuristic, not
a vanilla combat rule.

`ComboModel` represents both local bodies/views, server motion, sprint caches,
critical upgrades, overwriting quantized impulses and delayed opponent Wtap.
Outstanding approximations include:

- Fixed 50 ms event grids and a chosen ordering between clocks; actual physical
  inputs and dispatch jitter must not be inferred from server TPS alone.
- Fixed defender click period/phase despite native jitter; confirmations currently
  do not rephase future defender clicks.
- Rigid two-tick tracking without all dirty/isAirBorne/teleport behaviors.
- Simplified floor/collision movement, omitted/approximate entity pushes,
  movement/look send thresholds, trigonometric/float details and degenerate RNG.
- Instantaneous desired-ray aiming without native head-yaw synchronization;
  section 4 identifies a source-confirmed state distinction and a pending native
  explanation of three deep-range reach mismatches.
- Uncertain remote-body source-time/velocity reconstruction. A tiny corrected
  one-step residual does not validate the uncorrected 150 ms forecast used to act.
- W-only forward simulation; A/D strategy claims require its full 2D input
  normalization and native validation. A beam search is not an optimality proof.

Freeze each issued forecast and compare it to the corresponding later native
sample **before assimilation**. Report one-tick, 150 ms and 500 ms errors separately,
and event-time errors separately from conditional transition errors. Never tune
away a physical-law discrepancy with policy weights.

## 9. A precise combo invariant

Let B(t) indicate that a fresh native raycast under B's permitted aiming rule
would select A at time t; immunity is excluded. Let h_n be consecutive **fresh**
A→B server contacts. Critical upgrades may occur between them but are not h_n.
Fix a maximum fresh-contact gap T_max before testing, for example 1,000 ms.
A finite sustained denial interval [t_0,t_1] requires the following. For its core
duration, use the first and last qualifying fresh contact as endpoints; report
any observed leading/trailing denial separately rather than inflating the lock.

    B(t)=false for every t in [t_0,t_1]
    no accepted B→A attack in that interval, including previously queued attacks
    at least three fresh A→B contacts in the interval
    h_(n+1)-h_n <= T_max for every consecutive pair

Specify the tested endpoints; do not extend a lock through an unobserved start,
tail or missing data. A 50 ms probe sequence establishes only **sampled denial**.
One observed B opportunity falsifies continuous denial; no observed opportunity
does not prove it. An unanswered score streak alone is weaker than this claim.

For an indefinite claim, cut the event system immediately after each complete
fresh server attack and define z_n = X(h_n+). X includes relative bodies/views,
server motion/sprint/resistance/lastDamage, all pending packets, physical-clock
phases, Wtap cue/pause state, the observation history needed by the declared D and the
controller's commitments. Translate out common horizontal position only in a
domain where the world/loaded-region assumptions are translation invariant.
No reduced state consisting only of gap and velocities is Markov here.

For a fixed legal observation-based policy pi and allowed timing disturbance w,
the first-return map is:

    (z_(n+1), tau_n, path_n) = R_pi,w(z_n)
    tau_n = h_(n+1)-h_n

R is undefined if there is no next fresh contact. To certify a robust invariant
set K, establish all of the following, for **every** z in K and every admissible
disturbance sequence, rather than only the nominal trajectory:

1. R is defined with 0 < tau <= T_max. The successful attack arises from an
   ordinary scheduled physical click with native A reach and server fresh-hit
   eligibility at processing time; it is not inserted as a confirmation.
2. Along the whole return path B(t)=false, and no B packet already in flight
   produces accepted damage. A useful stronger geometric certificate is
   `ray_B(t) >= 3+epsilon`, epsilon>0, using the actual selected ray/box geometry.
3. z_next is in K; all world-validity and input constraints remain satisfied.
4. pi selects the same action for states with identical permitted observation
   and issued-input histories. It cannot use hidden state to choose the action
   that makes a verification branch succeed.

Then induction proves continued fresh contacts and denial while the declared
model/domain assumptions hold. Entry is a separate obligation: a predeclared
legal opener must reach K. Allowing a setup trade is compatible with an entry
claim, but not with claiming denial before entry. K must bound clock phases,
packet ages and cue uncertainty as well as geometry. If jitter, latency or stalls
have no finite bound, this form of bounded-gap infinite certificate is unavailable.
For a belief-state controller, require its entire reachable physical-state set
to satisfy the path conditions, not just the best-fitting particle.

An interval/reachability calculation that encloses every return can establish
these obligations within a specified model. A finite rollout cannot. If each
return has conditional failure probability at least p>0, survival through N
returns is at most (1-p)^N; indefinite survival is not established by a long
sampled run. The familiar mean length 1/p additionally assumes a geometric
failure process; it is not a universal consequence of a measured reach edge.

**Minimal falsification experiment:** declare K, pi, T_max and disturbance bounds
first. Reach candidate seeds with a fixed ordinary opener, preserving both
clients' real approach/view/packet histories; do not initialize adjacent static
bodies and invent their past. Record the full checkpoint offline, then run one
complete return under the predeclared inputs. Repeat natural tick/click phases
and declared latency-bound endpoints. A checkpoint in K followed by any native
B opportunity, return attack, missed contact deadline, or next checkpoint outside
K is a counterexample. Capture event/frame-level reach changes and queued-attack
tails; otherwise label the weaker sampled test. Passing these seeds supports
only finite validation, not universal coverage of K or an infinite native lock.

## 10. Minimal native validation suite

Use predeclared key scripts and the fixed legal click clock; no policy search.
Change positions/loadouts only during ordinary experiment setup. Repeat both
roles and sweep a few starting tick phases, then RTT 0/0, 20/100, 100/20. First
validate conditional one-event transitions, then compositions.

| Case | Minimal setup and measurements | Falsifiable prediction / failure criterion |
|---|---|---|
| V0 integrity | Spawn, spectator→duel→rematch; stationary then move. Log canonical identity, loaded-list/chunk membership, target ticks and region loading. | Rendered target ticks advance and motion updates. Reject the entire run if frozen/stale; an accepted server-distance<6 hit is insufficient. |
| V1 movement | One player, no contacts: W, W+Ctrl, release, Ctrl-only transitions on ground and in air; then W+A/D. Record tick-entry/end p,v,onGround, movement-speed attribute and air factor. | Ground acceleration changes immediately with sprint; air acceleration changes after that tick's travel. First grounded sprint displacement from rest≈.1274, settled≈.28062/tick. W+A/D follows normalized input, not additive full accelerations. |
| V2 fresh impulse | One ordinary then one sprint fresh hit, initially separated and settled; repeat victim moving toward/away and airborne. Log server motion immediately before damage and after outer attack, S12 integer payload, client handler/end-tick motion. | Packet equals Q(K) from server M; server M is restored after fresh attack; client motion is overwritten. Reject any model using half the victim's live client motion. |
| V3 immunity/crit | Continuous fixed clicks; arrange an ordinary 8 hit followed by a falling 12 attack in H>10, plus a separate fresh sprint critical. | Upgrade applies 4, keeps H, gives no fresh cue/base-KB/S12 by itself, but consumes server sprint if present. Fresh sprint critical deals 12 and sends sprint KB. Equal damage rejects for H>10, accepts at H<=10. |
| V4 sprint/contact | Compare W+Ctrl held, one W-up, and one Ctrl-off contact tick followed by Ctrl-on non-click tick; repeat Ctrl-off with an air miss. | Contact produces*.6 and local sprint cancellation regardless of server immunity. Held Ctrl can avoid a new START. Ctrl-off MISS does not guarantee STOP. ATTACK precedes same-tick START. |
| V5 replication | One player follows constant-speed, stop/reverse and isolated-hit scripts while the other observes. Log packet emission/application and each remote update offline. | Quantized targets and 3-step recurrence reproduce rendered positions; identify ordinary 2-tick cadence and forced-update exceptions. A fitted constant lag must fail gracefully at reversal, not be promoted to a law. |
| V6 geometry | Stationary setup grid around axial gap 3.4 and diagonal gap 3.56569, then heights 0, .2, .3, .9, 1.5; use actual permitted aim. | Native selection agrees with slab intersection for the selected ray. Boundary disagreements outside float tolerance identify a ray/aim/occlusion error. |
| V7 composition | Short fixed two-player scripts with incoming and outgoing hits, no learned actions. Freeze predictions at each decision; log actual click cadence and task/physics timestamps. | Correct transition models compose into correctly timed local views, impulses, fresh hits and upgrades. Diagnose timing vs state error before scoring a strategy. |

**Exact-parity contract (supersedes the pilot tolerances):** a deterministic
transition must match all in-scope floating-point bit patterns, discrete flags,
packet types/integer payloads, and hit/miss decisions given identical complete
pre-state and ordered inputs. Neither 1e-4 nor .25 blocks establishes parity.
Record all failed or unsupported samples; do not exclude near-reach boundaries
from an exact-parity claim. Historical tolerance reports remain below as
diagnostic evidence, not certification.

The independent `ExactTravel` kernel now passes 2,091 fresh native steps in
`research/exact-parity-v2.json`: position, velocity, retained AABB and collision
flags, with zero tolerance. The source arithmetic and scope are documented in
`research/exact-travel-audit.md`. It is not yet integrated into the approximate
`ComboModel` rollout. Conditional packet application and interpolation checks
also pass; see `research/exact-parity-v2-replication.json` for their precise scope.

Complete composition must start from full client/server/tracker state, preserve
absolute coordinates and retained AABBs, and consume an explicitly ordered
external frame/tick/network schedule. It must generate later positions and
packet payloads itself; a missing/extra packet or one differing bit fails the
replay. Recorded schedules are legitimate exogenous replay inputs, whereas later
observed hits, positions and velocities cannot be injected as corrections.
Future OS/network scheduling is a separate uncertain input for a live policy.

**Verified pilot coverage:** [`m0358-validation.json`](research/m0358-validation.json)
reports 312 conditional movement samples, maximum position error
1.19792e-5 block and velocity error 6.54013e-6 block/tick; 219 interpolation steps
had zero recorded error, and all 300 checked reach classifications agreed.
There were no velocity-packet samples. These checks use recorded native pre-state,
movement inputs and interpolation goals; they validate those conditional functions,
not hidden-state reconstruction, full packet timing, every geometric boundary,
combat transitions, or a multi-step rollout. The subsequent predeclared suite is
tracked separately in [`native-mechanisms-v2.json`](research/native-mechanisms-v2.json).

**Completed native suites:** the initial 11 cases, six fresh asymmetric-RTT
cases and six entry/maintenance/crossing cases are archived in
[`research/README.md`](research/README.md). The 23 scenarios supplied 24,982
conditional movement steps and 1,121 fresh sword impulses within their declared
tolerances. This does not certify V0–V7 in full. The
[independent click-order ablation](research/click-order-diagnosis.md) plus the
fall transition reproduces all 14 fresh contacts in one 10 s, 0/0 case, but the
[fresh unequal-RTT counterexample](research/replication-timing-diagnosis.md)
rejects complete-model fidelity. Tracker state and dispatch remain unvalidated.

**Observed composition counterexample:** in the native
[`m0360-a` trace](research/traces/m0360-a.jsonl.gz), tick 565 is the first W+Ctrl
movement after the initial observation delay. Its movement pre-state has sprint
true, ground acceleration .13000001 and air acceleration .01999999955; horizontal
displacement is .12739998299. Tick 566's air factor is .02599999867. A full rollout
using the previous tick's ground factor starts about .0294 block behind, even
though conditional travel supplied with the measured factor passes. This caught
an earlier audit error that treated both factors as lagged; the corrected rule
is in section 3. Passing conditional arithmetic does not validate the input-to-
attribute transition that supplies its parameters.

Publish the fixture, binary/agent hashes, input trace, actual CPS/tick intervals,
full failure counts, and representative counterexamples. Label conditional
code checks, existing native evidence, and newly passed native cases separately.
Only after V0–V7 should long duels evaluate score, longest fresh-contact lock,
and the diagnostic time fraction `A_can_hit && !B_can_hit` among A opportunities.
The brief's simulated 10–2 scores and infinite-lock claims are hypotheses for
these experiments, not validation of native physics or attainable performance.

## 11. Reproducible source audit

The verified local SHA-1 fingerprints are:

- `.cache/client.jar`: `3870888a6c3d349d3771a3e9d16c9bf5e076b908`
- `.cache/server.jar`: `b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd`

Official download identities are pinned in `dependencies.lock.json`; names are
resolved through `.cache/joined.srg` and `.cache/mappings.properties`. Inspect
with the installed JDK's `javap -classpath .cache/client.jar -c -p CLASS`
(and the server jar for an independent server check). Principal audited entries:

| Mapped method | Official obfuscated entry | What it establishes |
|---|---|---|
| EntityPlayer.attackTargetEntityWithCurrentItem | `wn.f(pk)` | Critical predicate 104–181; accepted sprint bonus/drag 257–342; S12 and server-motion restoration 342–397. |
| EntityLivingBase.attackEntityFrom / knockBack | `pr.a(ow,float)` / `pr.a(pk,float,double,double)` | Resistance/upgrade branch; fresh status; random degeneracy/resistance; base KB. |
| EntityLivingBase.onLivingUpdate / moveEntityWithHeading | `pr.m()` / `pr.g(float,float)` | Input damping, normalization call, movement/friction/gravity order. |
| EntityPlayer.getAIMoveSpeed / onLivingUpdate | `wn.bI()` / `wn.m()` | Ground travel reads the current attribute through the overridden getter; air factor updates after superclass travel. |
| EntityPlayerSP.onLivingUpdate / onUpdateWalkingPlayer | `bew.m()` / `bew.p()` | Native sprint conditions, double-tap, transmitted sprint cache and packet order. |
| EntityLivingBase.getLook / EntityPlayer.updateEntityActionState | `pr.d(float)` / `wn.bK()` | Native ray uses head yaw; player head yaw synchronizes during living update before travel. |
| EntityRenderer.updateCameraAndRender / Entity.setAngles | `bfk.a(float,long)` / `pk.c(float,float)` | Mouse deltas update current/previous yaw and pitch, without directly synchronizing head yaw. |
| EntityOtherPlayerMP.attackEntityFrom / interpolation | `bex.a(ow,float)` / `bex.m()` | Local attack success and remote interpolation. |
| PlayerControllerMP.attackEntity | `bda.a(wn,pk)` | C02 enqueue before local attack. |
| NetHandlerPlayServer.processUseEntity / processPlayer | `lm.a(in)` / `lm.a(ip)` | Server reach guard, movement processing and rising-transition jump. |
| EntityPlayerMP.onUpdate / onUpdateEntity | `lf.t_()` / `lf.l()` | Resistance update and distinct C03-driven native update. |
| EntityPlayerMP.handleFalling / Entity.updateFallState / EntityLivingBase.fall | `lf.a(double,boolean)` / `pk.a(double,boolean,afh,cj)` / `pr.e(float,float)` | Reported descent accumulation, landing-before-reset order, ceil(F−3) damage and ordinary resistance acceptance. |
| EntityTracker / EntityTrackerEntry | `la.a(pk)` / `lh.a(List)` | Player frequency 2, velocity-update flag false, forced-update/position thresholds. |
| NetHandlerPlayClient movement / velocity | `bcy.a(gv)` / `bcy.a(hm)` | Remote n=3 target and velocity overwrite. |
| S12PacketEntityVelocity constructor | `hm(int,double,double,double)` | ±3.9 clamp and integer 8000 encoding. |
| EntityRenderer.getMouseOver / Entity collision border | `bfk.a(float)` / `pk.ao()` | Expanded-box ray intersection and native 3 m entity cutoff. |
| Minecraft.sendClickBlockToController | `ave.b(boolean)` | False immediately clears the miss counter. |

Bytecode offsets refer to the pinned binary, not source line numbers. Instrumented
logging must preserve these transitions; the referee's health restoration and
setup actions must remain explicit experiment exceptions.

## 12. A repeatable double is a return-window control problem

Use a stronger outcome than a short delay in the opponent's return. Given a
uniquely paired fresh trade `(A0,B0)`, discard its two attacks, then group the
remaining fresh attacks by server tick. A **true follow-up double** means the
first two nonempty groups contain A only: `A1,A2` before `B1`. A group containing
both players is a trade regardless of packet ordering inside that tick. Record
both inter-hit intervals; a double after a long disengagement is not an infinite
combo. End-of-match incomplete groups are censored. Critical damage upgrades
remain separate score events and still affect sprint/momentum.

For each actually consumed B click at local time `c` and its eventual server
processing event `r`, a fresh B return requires all of the following:

1. Native click consumption is permitted and its cached ray selects A.
2. The emitted ATTACK passes the ordinary server identity/visibility/distance
   checks at `r`.
3. A's resistance at `r` is at most 10 (and ordinary damage eligibility holds).

Within the experiment's ordinary alive/survival scope these are the fresh-return
gates. The full hit score additionally admits a larger-damage upgrade at H>10.
A's knockback packet cannot retroactively remove an already emitted ATTACK.
Even packet application is not displacement: B may consume another click before
its first movement with the new velocity. The relevant deadline is contact
generation, not the eventual scoreboard change or S12 receipt alone.

With fixed consumed click times, denying only those samples can produce a true
double. That does not establish phase-independent denial. To withstand uncertain
opponent click phase, the strategy must close every native contact window whose
ATTACK could arrive while A is damage-eligible, or demonstrate that its permitted timing
control robustly avoids them. A sufficient empty-scene geometric denial is
`distance(B_eye, B_rendered_A_expanded_box) > 3` for the whole relevant interval.
This is stronger than missing one cached ray. A must simultaneously retain its
own native contact opportunities when B becomes eligible, through two accepted
fresh hits. Both directions use their own view, not server separation.

For a controller with delayed observations, let `Belief_t` contain all native
states, queues and clock phases consistent with its allowed history. An
every-time claim over a declared initial-state/latency/jitter envelope requires
one legal observation-based policy to meet these event conditions for **every**
state/schedule in that envelope. Picking a different input using private current
state in each offline replay would not establish such a policy. If no allowed
input sequence works from some reachable trade state, a winning controller must
avoid entering that state; a later reactive reset cannot repair an emitted hit.

The [return-window investigation](research/trade-return-windows.md) identifies
the native gates in the recorded 20/20 ms RTT cohort. It is not a proof that a
guaranteed policy exists or is impossible. The
[focused response calculation](research/trade-coast-response.json) quantifies
local coasting and remote interpolation with specified boundary events held
fixed; it is not a closed-loop counterfactual duel.

The [unreset local replay](research/trade-local-replay-v1.json) starts each of 14
clients once and reproduces 13,209 consecutive native travel steps, 3,700 contact
events and 897 S12 applications with zero bit differences in all checked
fields. It consumes recorded contact/sprint decisions, aim, acceleration getters
and incoming packets. No subsequent native position or velocity is assimilated,
except the actual S12 overwrite operation. This extends conditional component
parity to the local trajectory composition; generating those boundary events
from the two input histories remains required for autonomous duel parity.
