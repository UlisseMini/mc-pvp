# First causal divergence in m0362

This is an offline diagnosis of Wtap versus a sprinting, non-clicking defender
at declared RTT 0/0. It changes no native controls or game physics.

The recorded-clock replay stays within approximately 5 cm on the defender until
its first landing after three knockback contacts. At that landing, vanilla sends
a velocity packet from fall damage. The previous model omitted the event.

## Evidence

Times below are relative to `1792811654110` ms, the first replay clock event.
The archived sources are `traces/m0362-a.jsonl.gz`, `traces/m0362-b.jsonl.gz` and
`traces/m0362-server.jsonl.gz` in this directory.

| Time | Native evidence |
|---|---|
| 1,690 ms | Third player hit; defender receives sprint knockback. |
| 2,254 ms | Defender's client lands; model position error is .04974 m. |
| 2,292 ms | Server creates S12 without an enclosing player attack. Defender is grounded, lastDamage=1, hurtResistantTime=19. PvP score stays 3–0. |
| 2,293 ms | Defender applies S12=(-.080125,-.078375,.007625). |
| 2,307 ms | Next defender travel starts with exactly that velocity. Old model has retained positive horizontal client drift; position error jumps to .19788 m. |

At the server event, native latent server motion is
(-.0801937770065539,-.0784000015258789,.0076632667962979635).
Clamping/encoding with vanilla's integer 8000 rule reproduces the packet.
The client trajectory previously had positive x motion; this packet reverses
that component without any new attacking-player contact.

## Verified source chain

The pinned official 1.8.9 server binary establishes:

1. `NetHandlerPlayServer.processPlayer` calls
   `EntityPlayerMP.handleFalling(deltaY, reportedOnGround)` after accepting C03.
   The server player's ordinary `updateFallState` override is empty, so the
   reported motion supplies fall distance, not its latent gravity displacement.
2. On landing, `Entity.updateFallState` calls the floor's `onFallen` with the
   already accumulated float fallDistance, then resets it. It does not add the
   last landing delta first. The ordinary block delegates to `fall(distance,1)`.
3. `EntityLivingBase.fall` computes `ceil((distance−3−jumpBoost)*multiplier)` and
   calls `attackEntityFrom(DamageSource.fall, damage)` if positive.
4. The ordinary damage-immunity rules apply. Fresh damage sets H=20, L=damage,
   emits status2 and calls `setBeenAttacked`. With default zero knockback
   resistance, this sets velocityChanged. There is no source entity, so it does
   not run base knockback. A damage upgrade does none of those fresh-only steps.
5. `EntityTrackerEntry.updatePlayerList` checks velocityChanged outside its
   frequency-gated position branch. Its `sendPacketToTrackedPlayersAndTrackedEntity`
   sends S12 from current motion to both watchers and the victim, then clears
   velocityChanged. Pure fall damage has no outer player attack to restore motion.

Obfuscated audit entries: `lm.a(ip)`, `lf.a(double,boolean)`,
`pk.a(double,boolean,afh,cj)`, `afh.a(adm,cj,pk,float)`, `pr.e(float,float)`,
`pr.a(ow,float)`, `pr.ac()`, `lh.a(List)`, and `lh.b(ff)`.

The same hurt animation is visible to the attacker. Consequently an outgoing
hurt cue does not by itself prove a successful player hit. Treating this cue
as a confirmed player attack would insert an additional imaginary knockback.

## Isolated model comparison

`Config.modelFallDamage` defaults false. The offline runner accepts
`-Dlab.model_fall_damage=true`; its TSV input remains unchanged. The new path
uses reconstructed C03 descent, ordinary immunity, a copied velocityChanged
flag, and a deferred tracker flush. It neither injects the recorded S12 nor
adds an environmental event to the PvP score.

Both runs use the same archived initial bodies/rendered views, subsequent A/B
clock/input/click-consumption events, and server clock. Head-yaw mode is on;
the server timestamp shift is 0 ms. No position, velocity, hurt cue or hit result
after initialization enters prediction. Settings and the exact model hash are
saved in [fall-damage-diagnosis.json](fall-damage-diagnosis.json).

| Prediction horizon | Defender error, fall off | Defender error, fall on |
|---|---:|---:|
| 2.0 s | .04656 m | .04656 m |
| 2.3 s | .19788 m | .04865 m |
| 3.0 s | .42312 m | .04882 m |
| 5.0 s | 1.38272 m | .04557 m |
| 9.0 s | 25.24694 m | 1.60088 m |

The enabled model predicts one fall event at exactly 2,292 ms, damage1, H19.
The first error over .1 m moves from 2,307 to 5,660 ms. That later event still
requires diagnosis; this comparison does not validate long-horizon composition.

Pure regressions cover both victims, default-off behavior, threshold and final
landing-delta ordering, fresh/rejected/upgrade immunity branches, status cues,
score separation, copies, velocity overwrite off the position-tracking cadence,
and deferred flush after multiple C03 packets. They also cover an accepted
player upgrade consuming a fall's still-pending velocityChanged flag: S12 then
includes any sprint bonus and the outer attack restores pre-attack server motion.

The minimal further native check is a predeclared airborne-knockback sequence
whose accumulated reported descent crosses three blocks before landing, with
no player attack around landing. Record the fall source/damage, pre/post H/L,
velocityChanged, tracker send and victim application. A rejected fall during
H>10 with L≥fall damage is a complementary control: no new hurt status or S12
should be caused by that rejected damage. No coefficient fitting is needed.
