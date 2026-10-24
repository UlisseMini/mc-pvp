# Hand-written controllers and the optimal-best-response experiment

The reference is the actual client/server engine. A predictive model is useful
for choosing actions, but cannot establish Minecraft results until those actions
are evaluated in that engine. Every physical click goes through normal key input,
raycasting and attack handling. No click is filtered merely because the target
is believed to be immune.

## What sustains a combo

A useful state includes both players' movement and height, both local rendered
opponent positions, sprint states, damage-immunity phases, client/server clock
phases, and packets in flight. One scalar server distance is insufficient.

The goal is to arrange the next useful attack while preventing the opponent's
next attack from becoming a trade. Delivering the first hit alone does not prove
this: the opponent may already have an attack in flight. Knockback must reach
their client and alter the subsequent movement/targeting before it can remove a
future opportunity.

Releasing W does not stop movement instantly. On constant-friction ground, a
coasting velocity approximately follows v[n+1] = lambda*v[n]. Ignoring the tiny
velocity cutoff, the remaining displacement is v/(1-lambda), with v defined just
before the first movement integration. This gives a first-principles reason to
release W before crossing a desired spacing. Airborne friction and acceleration
differ, so a grounded calculation is only a heuristic during knockback.

The useful pursuit asymmetry is clearest in one dimension. A is behind B; both
move in the positive direction. Let g be their current separation, vA/vB their
velocities, and L_BA/L_AB the ages of the remote positions rendered by the other
client. Locally, A sees approximately g - vB*L_BA, while B sees approximately
g + vA*L_AB. Thus A can be in normal reach while B is outside normal reach. No
reach modification is required. When the players approach head-on, the signs
change and the same advantage need not exist.

Ignoring acceleration for one short horizon, a one-sided pursuit interval is:

    R_B - vA*L_AB < g <= R_A + vB*L_BA

R_A/R_B are effective horizontal eye-to-hitbox reach limits at the current
heights, not a replacement for Minecraft's raycast. The actual boxes, aim ray,
motion, interpolation and server acceptance still decide the hit. A return
attack already sent cannot be undone by making the current interval favorable.

Position age is not just network ping. Early offline alignment of both actual client
logs in m0069, m0070 and m0077 found best-fit remote horizontal position ages of
180–230 ms at 20/100 ms added RTT. Symmetric network transit contributes about
60 ms; native tracking/interpolation and event phases add more. This is a
coarse fit to moving samples, not a constant per-packet latency measurement.
These early matches are provisional because continuous native entity ticking
was not yet checked. Use qualified matches from m0121 onward for model tuning.
`analyze_views.py` reproduces the fit without giving either deployed bot access
to its opponent's private client state.

The model-guided Zest variants estimate a moving interval from delayed observed
velocities, known RTT, calibrated position age and vertical reach geometry.
They brake before projected separation crosses their chosen interval position,
including approximate coasting. An optional reactive post-hit reset attempts
to re-establish separation when the predicted interval collapses. These remain
heuristic controllers, to be selected and checked in native games.

Sprinting changes the outgoing hit's knockback, and attack handling changes the
attacker's own velocity and sprint state. In the 1.8.9 client, the remote-player
damage stub returns success locally; a sprint attack can therefore slow the
attacker even when the server rejects damage. Multiplying forward velocity by
0.6 can hurt pursuit; multiplying backward knockback velocity by 0.6 can help
recovery. Consequently immunity-based blanket click suppression is incorrect.

The recorded `m0025` run contains a concrete example: an ordinary client attack
changed horizontal velocity from -0.899875 to -0.539925 and ended sprinting while
the target's visible hurt timer was 9. The previous server-accepted attack was
83 ms earlier; the next was 469 ms later. The velocity ratio is exactly 0.6.
The native attack log is saved in `experiments/immune-click-example.json`.

## Controlled best responses to W-tap

The `momentum` controller keeps the fixed physical click clock. With every
optional feature off, it reproduces the delayed post-hit50ms W-tap. Separate
features add a further reset delay, alternating-hit resets, a predicted pursuit
spacing brake, safe-chase reset skipping, an opening movement delay, or normal
A/D alternation. All tactical input comes from the same150ms-old observations;
own previously issued click times can be used immediately for planning.

Fresh-hit timing matters independently of damage upgrades. A falling critical
can upgrade damage during the target's ordinary immunity period. A sprint
upgrade consumes the server sprint state without a fresh base-knockback event.
An early post-hit reset can therefore be spent before the next fresh hit. The
late-reset experiment starts with an extra delay of max(0,200-ownRTT)ms. A
separate phase variant estimates the next fresh local attack near visible cue
time+500-ownRTT-25ms, projects the normal105ms physical clock onto native50ms
ticks, and releases on the preceding predicted click tick. It resumes on the
intervening non-click tick: within a native tick the attack precedes the sprint
state transmission. This controls movement keys only; immune clicks still run.
The cue time and click forecast are estimates, so native evaluation remains
necessary.

The `reset_walk` factor retains W and releases only Ctrl during the reset.
On a real local contact the attack itself cancels local sprint; leaving Ctrl
released lets the normal STOP command occur while W still supplies forward
acceleration. Ctrl returns on the next non-click tick. An air miss does not
cancel sprint, so this is not a guaranteed reset; no sprint flags are written
directly. Its factorial comparison is ordinary W-tap, phase timing only,
Ctrl-only reset only, and phase timing plus Ctrl-only reset. This tests whether
timing and retaining forward movement help separately or through interaction.

The model screen changes one setting at a time. A second model knob charges
for input switches; the old0.15 cost was tiny beside a1000 hit reward. A separate
restriction can remove WALK from proposed plans without altering replayed input
history. Each experiment snapshots policies, build hash and planned fixtures,
balances starting sides, checkpoints completed rounds, and reports fresh hits
separately from upgrades. Selection games and fresh confirmation games must
remain separate. Promising combinations should receive a full two-factor
comparison: neither feature, A only, B only, and both, at matched ping settings.

## Initial hand-written policies

- `hold-w`: W and the sprint key, fixed click clock. Establishes what the game
  does automatically without extra W releases.
- `wtap`: arm a single short W release after a delayed visible landed-hit cue.
- `trade-wtap`: arm from that same landed-hit cue, prefer releasing after a
  recently received hit, and use a bounded reply wait when an opponent does not
  return the hit. Both cues obey the 150 ms tactical reaction delay.
- `periodic-tap`: a fixed-rhythm control experiment. Earlier recordings called
  this `wtap`; their analysis labels are corrected and raw logs are retained.
- `hit-reset`: the older confirmed-hit W-release timing, in the W-tap family.
- `spacing`: estimate closing speed from delayed visible observations, predict
  near-future gap, account for coasting, and use hysteresis to choose W or release.
- `predictive`: add a declared latency-dependent lead to that spacing forecast.
  This is an initial heuristic, not a claimed optimal controller.
- `burst`: explicit click bursts, capped at 10 CPS, combined with spacing. The
  engine processes every scheduled attempt, including rejected damage attempts.
- `phase-spacing`: forecast the next ordinary equal-damage hit window from
  delayed visible hit cues and declared ping, then adjust movement toward it.
  Critical hits and tick/packet phase uncertainty limit this forecast.

The sprint key is held whenever W is held. Client sprinting and server sprinting
must be distinguished. Input attacks run before the client's living update;
holding the sprint key can restore the local sprint flag before the movement
packet code compares it with its last transmitted sprint state. That does not
guarantee a fresh START_SPRINTING packet after the server cleared sprint on a
hit. A W release and re-press makes a real stop/start transition. W-tapping is
therefore a sprint reset as well as a change to approach speed and spacing.
The server and client sprint flags are recorded separately in the traces.

## Zest cycles

The tutorial-derived controllers release W for a selected contact in a two- or
three-contact cycle, rather than resetting sprint after each contact. A delayed
visible hurt cue arms the release before a forecast next window. Normal clicks
continue throughout that window. A/D versions add only ordinary lateral key
input; W-only versions preserve the simplified action space. Entry and recovery
may deliberately wait for a received-hit cue, still delayed by 150 ms.

An important native distinction: a higher-damage falling critical can be accepted
inside an existing hurt window without starting another hurt animation or full
knockback event. Thus three accepted damage events are not necessarily the
tutorial's three visible contacts. The controller counts visible hurt cues,
while the scoreboard consistently counts every server-accepted attack. The
server trace records damage, sprint state and hurt resistance at each accepted
attack so those cases can be separated in analysis.

Release lead, duration, click phase, airborne motion and packet/tick phase can all
move the resulting contact away from its forecast. A tutorial-inspired name is
not evidence of a successful combo controller. Measure both hit ratios and long
unanswered streaks against hold-W, then test fresh matches and ping conditions.
See [the source notes](experiments/ZEST-NOTES.md).

All tactical samples are at least 150 ms old. Aim is the separately permitted
perfect-aim abstraction and uses only the currently visible local entity.

## Optimal against a fixed opponent

### Measure winning play and sustained locks

The final objective is the actual hit score against a clicking hold-W opponent.
The next priority is continuous combo duration. Count a sustained lock only
when at least three fresh knockback contacts occur during continuous native
defender reach denial, with no accepted return attack and no contact gap above
one second. Brief intervals when neither player can hit can be part of a lock.
The non-clicking probe is a diagnostic stepping stone, never the final opponent.

### Diagnose the one-sided window

At each real host time t, let A(t) mean A's own client raycast selects B, and
B(t) mean B's own client raycast selects A. A useful diagnostic for A is:

    integral(A(t) AND NOT B(t)) / integral(A(t))

Both integrals are durations. A time when neither side can hit is outside the
denominator. Hurt immunity is deliberately excluded from these predicates:
the question is whether an ordinary attempt has native reach from that POV.
Actual accepted contacts and their cadence are tracked separately. Also report
total A-opportunity duration, paired sample coverage, and longest uninterrupted
one-sided window; a high ratio with almost no attack opportunity is insufficient.
Native probes are sampled about every 50 ms, with step interpolation and a
150 ms staleness cutoff. Thus this is a sampled-time estimate, not proof that no
sub-tick opportunity ever existed. The clients share the same host wall clock.

### Current model and observer

`ComboModel.java` tracks each client's actual estimated body and rendered remote
view separately, server motion, sprint flags, queued movement/attack/velocity
events, native-inspired friction, tracking quantization and interpolation.
The victim's knockback velocity comes from the **server entity's motion**, not
half its real local client velocity. The sprint bonus follows the attacker's
last server yaw. These distinctions matter especially after players cross.
Probe policies use a non-clicking W/Ctrl defender. `mpc-duel` also models the
defender's fixed physical click clock, local contact slowdown, server sprint
state, incoming hit immunity and delayed knockback delivery. Both directions
use the same event model. The model tracks integer immunity timers, last damage and server fall distance,
including accepted falling-critical upgrades inside an existing hurt window.
Those upgrades score without fresh base knockback or a new hurt animation.
The model still approximates collisions and event phases, so it cannot claim
an optimal best response.

Optional opponent models also reproduce the actual W-tap controller and a
stationary clicking control. W-tap receives its own modeled outgoing fresh-hurt
cue over its network leg, samples that cue locally, waits 150 ms, then releases
W/Ctrl for 50 ms. Clicks continue during the release. Critical upgrades do not
arm a new tap. These are simulated consequences of the known policy; the
candidate receives no private opponent state. The stationary control omits
movement input while retaining attacks, gravity and knockback.

`VisibleTracker.java` uses only eligible visible positions. If a remote entity
moves from p to q during one native tick, its interpolation target is
p + n*(q-p), with n in {1,2,3}. Candidate targets must lie on the world 1/32 grid.
Continuation resolves many otherwise ambiguous candidates. This reconstructs
an old visible interpolation goal, not the opponent's present position. It does
not read packet target fields. Ambiguity, missed ticks and source-time uncertainty
remain explicit. Every newly eligible native sample is fed before queue compaction.

The experimental ensemble maintains several server, opponent and tracker phases.
Each receives the same delayed observations and our own input history. A recovered
goal anchors a historical estimate near its inferred source time, then recorded
inputs are replayed forward. Phase hypotheses are ranked by their pre-correction
visible residuals. Only the selected estimate enters beam search. An optional diverse beam keeps
separate first-action quotas and quantized physical-state bins, including pending
causal events. This reduces duplicate histories but does not make pruning exact. Actual native
PvP scores determine whether this extra modeling improves play.

Even a diverse beam can prune a useful opening before its delayed payoff. In one
fully specified three-second model case, ordinary beam search predicted 6:6,
while a two-tick opening pause predicted 9:6. The extra points were critical
upgrades, not a sustained lock. An optional planner therefore also evaluates
complete sprint/pause/resume sequences and the shifted previous winning plan.
Each candidate still advances the same approximate event model and produces
ordinary W/Ctrl inputs; every native physical click continues. A simulated score
advantage must survive native games before it is treated as an improvement.

Score gains do not establish accurate combo control. In native match `m0232`
(100–75), post-match alignment found a 90th-percentile current rendered-target
prediction error of 0.59 m, and latent opponent-body error of 1.51 m. That is
large compared with a narrow reach-denial interval. The bot had five accepted
hits in its longest unanswered streak, but no qualified sustained reach-denial
lock. The private opponent log is used only to measure these errors afterward.
An observer's corrected one-tick residual also differs from its original 150 ms
forecast error; near-zero one-tick movement residuals do not prove precise
prediction across an incoming knockback impulse.

Newly observed outgoing and incoming hurt cues constrain each historical server hit to a tick-wide
interval after accounting for our network leg. A phase hypothesis chooses a
compatible hit time, rewinds before conflicting predicted impulses, and replays
the already issued inputs with one confirmed historical model event. This
repairs the model's latent velocity; it never changes the real client's motion,
emits an attack packet, or awards a real hit.

The reach-edge objective evaluates the **local ray at a predicted fresh contact**.
It penalizes contacts deep inside reach while retaining a reward for actual
contacts, and the one-sided-window objectives. The intended control target is
the outer edge at the next useful contact, accounting for momentum, remaining
travel before the knockback arrives, and known click commitments. It is not a
static server distance or an excuse to skip immune-frame clicks. All scheduled
physical clicks continue normally; only W/Ctrl choices change in these variants.

### Exactness limits

Known round-trip ping does not uniquely specify one-way latency. For the first
best-response study assume symmetric, constant one-way delays; specify server
and client clock phases, click phase, initial conditions, opponent policy and
its internal state, and any random seeds. Later average over unknown phases and
add jitter. Never reveal hidden server counters to the deployed bot.

For a fully specified finite model, the exact algorithm is dynamic programming:

1. Include delayed observations, pending input commitments and packet queues in
   the model state. Under partial information maintain a belief over states.
2. Enumerate legal W/sprint transitions and either the fixed click clock or the
   explicitly permitted click-timing choices (at least 100 ms between attempts).
3. Advance both clients, the fixed opponent, the network, and the server in their
   actual event order.
4. Assign terminal utility for reaching 100 hits before the opponent. Back up the
   best expected value over our legal actions at every preceding state.

This yields an optimal best response **within the specified model and legal
action/time discretization**. A short lookahead, coarser state grid, beam pruning,
or approximate physics removes that guarantee. We must label those variants and
test them in real matches. There is no justified universal rule such as 'always
wait to be hit first' or 'always click at the immunity boundary'.

One practical search can optimize a release interval (start and end)
and an optional click-burst interval around the next exchange against `wtap`.
Then test whether its advantage persists over the full 100-hit match. Keep
opponent-specific best responses distinct from league-robust champions; both
are saved as normal policies that a human can challenge.

## Primary code consulted

- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/entity/player/EntityPlayer.java
- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/client/entity/EntityOtherPlayerMP.java
- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/entity/EntityLivingBase.java
- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/client/entity/EntityPlayerSP.java
- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/client/Minecraft.java

These are readable decompiled references. The experiment executes Mojang's
verified official binaries, not those source mirrors.
