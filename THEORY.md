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

Sprinting changes the outgoing hit's knockback, and attack handling changes the
attacker's own velocity and sprint state. In the 1.8.9 client, the remote-player
damage stub returns success locally; a sprint attack can therefore slow the
attacker even when the server rejects damage. Multiplying forward velocity by
0.6 can hurt pursuit; multiplying backward knockback velocity by 0.6 can help
recovery. Consequently immunity-based blanket click suppression is incorrect.

## Initial hand-written policies

- `hold-w`: W and the sprint key, fixed click clock. Establishes what the game
  does automatically without extra W releases.
- `wtap`: a scheduled short W release at a fixed rhythm. An intentionally simple
  known opponent for best-response experiments.
- `hit-reset`: a W release after the bot has reacted to a visible hit cue.
- `spacing`: estimate closing speed from delayed visible observations, predict
  near-future gap, account for coasting, and use hysteresis to choose W or release.
- `predictive`: add a declared latency-dependent lead to that spacing forecast.
  This is an initial heuristic, not a claimed optimal controller.
- `burst`: explicit click bursts, capped at 10 CPS, combined with spacing. The
  engine processes every scheduled attempt, including rejected damage attempts.
- `phase-spacing`: forecast the next ordinary equal-damage hit window from
  delayed visible hit cues and declared ping, then adjust movement toward it.
  Critical hits and tick/packet phase uncertainty limit this forecast.

The sprint key is held whenever W is held. Vanilla can therefore re-enable
sprint after an attack without an additional W-tap. In this experiment a W-tap
also changes approach speed and spacing; an improvement cannot automatically be
attributed to sprint resetting alone.

All tactical samples are at least 150 ms old. Aim is the separately permitted
perfect-aim abstraction and uses only the currently visible local entity.

## Optimal against a fixed opponent

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

The practical first search should optimize one release interval (start and end)
and an optional click-burst interval around the next exchange against `wtap`.
Then test whether its advantage persists over the full 100-hit match. Keep
opponent-specific best responses distinct from league-robust champions; both
are saved as normal policies that a human can challenge.

## Primary code consulted

- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/entity/player/EntityPlayer.java
- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/client/entity/EntityOtherPlayerMP.java
- https://github.com/Marcelektro/MavenMCP-1.8.9/blob/master/src/main/java/net/minecraft/entity/EntityLivingBase.java

These are readable decompiled references. The experiment executes Mojang's
verified official binaries, not those source mirrors.
