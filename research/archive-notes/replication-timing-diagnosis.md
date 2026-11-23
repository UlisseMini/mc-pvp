# Unequal-RTT counterexample: m0370 first contact

The complete current replay fails on fresh holdout m0370, Wtap A versus a
sprinting, non-clicking B at RTTs **20/100 ms**. This failure remains with the
ground-acceleration correction, head-yaw factor, fall-damage factor, recorded
client/server clocks, and monotonic click-consumption mapping. No phase was fitted
and no source or native process was changed for this diagnosis.

The first causal branch is a false predicted local contact. Conditional movement
and ray equations can pass while an incorrect replicated target position changes
whether an attack occurs.

## First divergent transition

Times are relative to epoch `1792812255670` ms. Exact native records and compact
model event rows are in `replication-timing-diagnosis.json`; the full held-out
comparison is `native-mechanisms-holdout-clock-replay-head-yaw-fall-damage-monotonic-clicks.json`.

At A537, position error is only .001372 m. Before A587's physical click is
consumed, the native target is rendered at X=4098.05787037037; the model has
X=4097.96450617284, **.093364 m closer**. Own X differs by .000114 m.

The native probe at 562 ms records the same eye position, head direction and
target box retained until that tick's click. Its recorded eye/look intersect the
expanded target box at **3.0407065 m**, beyond the native 3 m segment. The probe
reports MISS and there is no native `engine_attack` at A587. The model computes
**2.9476961 m**, queues an attack and applies sprint-contact self-drag. Native A
keeps its velocity, producing the first .060427 m post-travel error at A587.
This click's consuming tick is correctly assigned; it is not another wall-time tie.

| Event | Native (ms) | Replay (ms) |
| --- | ---: | ---: |
| First local contact | 682 | 587 |
| First accepted server hit | 716 | 616 |
| Victim velocity arrival | 772 | Queued for 666, consumed at 716 |
| First launched B travel | 773 | 716 |

Native B receives `(.895,.46075,-.09325)` at 772 ms. At 716 ms it is still on the
floor, while the replay already launches it; position error becomes 1.148657 m.
Subsequent immunity and contact differences descend from the earlier false ray
contact. Altering knockback coefficients would not address that first cause.

## Replication evidence and unresolved causes

The model starts a shared tracker phase at zero and emits ordinary position
updates only when `serverTicks % 2 == trackerPhase`. Native position-goal receipts
already disagree before combat:

| Native receipt (ms) | Received goal X−4093 | Matching recorded server position time | Model comparison |
| ---: | ---: | --- | --- |
| 238 | 5.84375 | 212 ms | Model sends this goal at 212; consumes/interpolates at A223 |
| 274 | 5.65625 | 263 ms | Model skips the 263-ms positional update |
| 379 | 5.15625 | 363 ms | Model skips 363; its latest goal came from 312 |
| 486 | 4.90625 | 417 **or** 464 ms | The server position is unchanged at these two samples; emission time is unresolved |
| 575 | 4.34375 | 562 ms | Model skips 562; its goal remains 4.625 from 512 |

The server-time column identifies possible source snapshots by quantized position;
**it is not a measured packet-emission timestamp**. Matching 263/363/562 suggests
a regular tracker parity opposite the model's initialization. The extra 212 goal
has a distinct source-supported candidate cause: B starts server sprint at 212.

Official 1.8.9 server bytecode establishes:

- `EntityTrackerEntry.updatePlayerList`, `lh.a(List<wn>)`, offsets 302–334,
  enters its positional-update branch when **counter modulo frequency is zero,
  OR `isAirBorne`, OR `DataWatcher.hasObjectChanged()`**. The model currently
  represents only the modulo case.
- `Entity.setSprinting`, `pk.d(boolean)`, writes flag 3 through `setFlag`;
  `pk.b(int,boolean)` calls `DataWatcher.updateObject`.
- `DataWatcher.updateObject`, `pz.b(int,T)`, marks the watcher changed when its
  value changes. A server sprint transition can therefore permit a positional
  update outside the regular cadence, provided the ordinary packet conditions
  within that branch are satisfied.
- `updateCounter` belongs to each tracker entry, not to the match timer. Its
  initial phase cannot be inferred simply by setting replay tick zero. Entry
  replacement during untrack/retrack resets its lifetime; the subsequent number
  of server updates before combat still matters. A and B need not share a phase.

These source facts do **not** prove which flag caused any specific unlogged send.
Other changed metadata or `isAirBorne` can open the same branch. Conversely, a
known tracker phase alone would not reproduce forced sends.

Packet/event draining is a third, separate uncertainty. For the first goal, a
212-ms emission plus nominal 10-ms recipient delay is eligible by A223 in the
model; the native remote-update hook runs at 238, between A223 and A275. That
can delay the first actual interpolation by a whole client tick. The hook records
application, not network arrival or emission. Proxy delay, transport scheduling,
and the next main-thread frame drain remain unresolved. The model's fixed
half-RTT eligibility threshold is insufficient evidence of actual application.

## Smallest next measurement

Use a short predeclared **no-click W+Ctrl approach at 20/100 RTT**. It isolates
replication and the first reach crossing before damage or combat feedback.

1. At settled initialization, record each tracker entry's `updateCounter`,
   `updateFrequency`, last sent integer XYZ, last on-ground flag, `isAirBorne`,
   and watcher-dirty state. These are offline validation inputs, not policy input.
2. Around each tracker update, record those flags before mutation and every
   emitted positional packet: entity ID, relative/absolute kind, integer XYZ or
   deltas, ground flag, server tick and monotonic time. Preserve whether the
   regular modulo gate was open independently of forced flags.
3. For each recipient, record packet receipt and main-thread application, with
   local monotonic time and client frame/tick phase. Retain the existing remote
   goal and interpolation-before/after traces.

This distinguishes three falsifiable explanations: wrong initial per-entry
phase; omitted dirty/airborne sends; or an eligible packet applied after the
modeled tick. Derive initial phase from the measured entry state, never by
selecting whichever final trajectory looks best. Check other RTTs as held-out
cases after the transition rules are specified.

Current checkpoint: several elementary transitions and two causal corrections
are supported by native evidence. The **complete latency/replication/combat
composition is explicitly rejected by fresh holdouts**. The m0362 improvement
does not establish general trajectory accuracy or a combo invariant.
