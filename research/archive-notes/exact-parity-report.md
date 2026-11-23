# Exact native parity: achieved scope and next gate

The target is identical floating-point bits and discrete events for identical
complete state and ordered inputs. A .25-block error is a failed exact replay,
even if it is an improvement over an earlier model. No tolerance is used in the
new travel or replication checks below.

## Fresh evidence

Five preregistered native scenarios, m0383–m0387, use ordinary 1.8.9 clients and
the official server: no-click crossing at added RTT 20/100, walking/sprinting/
coasting/strafing at 0/0, W-tap against an approaching **non-clicking** opponent
at 20/100, and crossing holdouts at 0/0 and 100/20. No parameter optimization was run.

| Conditional transition | Native samples | Mismatches |
| --- | ---: | ---: |
| Travel: position, velocity and retained AABB, 12 doubles per step | 2,091 | 0 bits |
| Travel: ground and collision flags, 4 booleans per step | 2,091 | 0 |
| Remote interpolation: position and rebuilt AABB | 2,091 | 0 bits |
| Position packet application: integer accumulator, target and remaining steps | 1,458 | 0 |
| S12 velocity overwrite | 33 | 0 bits |
| Tracker counter and quantized-position accumulator | 2,090 | 0 |
| Sent position packet coordinates from tracker pre-state | 1,021 | 0 |

Travel includes 265 airborne entries, 10 takeoffs and 9 landings. All 25,092 tested
double values and 8,364 boolean flags match; no unsupported travel samples were
discarded. Source-level golden tests additionally exercise strict floor contact,
retained-box history, the upstream .005 cutoff and rejection of unsupported world
branches. The 15 automated lab/model tests passed.

The kernel is `src/lab/ExactTravel.java`, separate from the approximate
`ComboModel`. Native float multiplication/division order, float trig lookup,
absolute coordinates and retained bounding-box endpoints are essential. Exact
velocities alone were insufficient: reconstructing a box from its center loses
rounding history. See [the arithmetic audit](exact-travel-audit.md).

Evidence: [fixture plan/results](exact-parity-v2.json),
[source-pinned rerun](exact-parity-v2-source-pinned-check.json),
[replication checks](exact-parity-v2-replication.json), and the compressed native
streams in [traces/](traces/). The suite's agent JAR was fixed throughout. The
initial plan did not pin the separate validator source files; the explicit
source-pinned rerun preserves the same traces and zero-tolerance gate. Future
plans pin those source files before execution too.
The exact version of the test bridge used for those checks is preserved under
`validator-source-archive/` by SHA-256. The current test file adds a regression
using m0385's complete native landing box; its prediction bridge is unchanged.

The deployed diagnostic also now mirrors the native post-movement loaded-chunk
check, including truncating coordinate casts and the chunk's loaded flag. Its
separate source-pinned smoke fixture, m0388, passed another 321 exact travel
steps and all recorded replication checks; see
`exact-parity-chunk-check.json`. The core five-case counts above remain unchanged.

An initial extra fixture, m0382, exposed a postprocessor assumption that the first
server replay row was the start event. Countdown probes can precede it. Readers
now select the explicit start event; that failed postprocessing attempt and its
native data remain preserved in `exact-parity-v1.json` rather than being erased.

## What the packet probes establish

The tracer records each player's tracker counter/flags, per-recipient send
invocation, Netty receipt, client main-thread application, and frame order. These
are read-only diagnostics; policies cannot consume them.

There were 558 position/look sends outside the simple counter-period schedule in
the five cases. The source gate is counter-period **or** `isAirBorne` **or** dirty
metadata. In the 20/100 no-click crossing case, m0383, 126 off-period position/look
sends had `isAirBorne=true` and one had dirty metadata instead. `isAirBorne` is an
internal velocity-update flag; it does not mean that the player is geometrically
off the ground. Modeling only a shared two-tick clock is therefore incorrect.

Network receipt and main-thread application are distinct. For example, m0383's
median receipt-to-application waits were 5.12 ms and 7.71 ms, with maxima 13.67 ms and
14.47 ms. Configured RTT alone does not determine those waits. Send invocation is
before dispatch through NetworkManager/Netty, so its measured delay also includes
dispatch, the proxy and decoding. Identical/ambiguous payload matches are excluded
from transport-delay estimates and counted explicitly; metadata contents are not
yet logged and do not supply transport timing estimates.

The gate/accumulator checks above do **not** certify all tracker state or predict
every packet type. They also do not validate rotation interpolation or metadata
contents. Packet application checks condition on the recorded packet payload;
tracker checks condition on recorded tracker pre-state. These are component
tests, not a full-forward replay.

## Remaining work, in order

1. **Build one exact, short, uncorrected movement-to-view replay.** A holds
   W+Ctrl for 600 ms, then releases/coasts; B stands still. No clicks; 20/100 RTT;
   about 3 s from settled initialization. Separation avoids player pushing. Start
   from full client/server/tracker snapshots and pending queues. Record client
   C03 send caches, sprint/sneak send state, frame/tick boundaries and task drains.
   Generate subsequent movement, aim, outgoing packet types/payloads, tracker
   updates and remote interpolation internally. Compare every event; stop at the
   first mismatch. Do not supply later native positions as corrections.
2. **Add one interaction at a time.** First one sword contact and local sprint
   drag; then immunity, sprint resets, airborne/landing transitions, entity
   pushing, fall damage and simultaneous incoming/outgoing attacks. Require exact
   packet integers and hit/miss/acceptance decisions, including reach boundaries.
3. **Require uncorrected held-out whole-duel parity.** Exercise both sides of the
   RTT asymmetry and independent tick/frame phases. A correct final score cannot
   excuse an earlier state/event mismatch. Only then use this model to search for
   combo-preserving control policies.

The shared policy model is deliberately not certified by the new conditional
kernel. Its relative-coordinate representation, simplified float constants and
tick-batched event API must change for exact replay. Initializing a full replay
requires more than replacing its movement function.

An offline replay can be deterministic given the actual exogenous event order.
A live bot cannot know future OS scheduling or packet arrivals, so it needs a
declared timing distribution/bounds on top of exact transitions. That uncertainty
does not permit arithmetic error in a transition with fully known inputs.

## Reproduction

```sh
python3 -m unittest -v test_lab test_model
python3 parity.py validate m0385
python3 replication_check.py exact-parity-v2
python3 parity.py run NEW_EVIDENCE_NAME
```

New runs require the existing sandboxed lab and its eight native clients. The
native clients still use ordinary controls, no more than 10 physical CPS, and
150 ms tactical delay. No bot policy strength or infinite-combo claim follows from
these mechanics checks.
