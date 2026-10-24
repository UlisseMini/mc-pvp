# Exact conditional travel audit

The acceptance target for a deterministic transition is **bit equality**, not a
distance such as .25 m. A tolerance remains useful for describing how a failed
rollout differs; it cannot establish correct native mechanics.

`src/lab/ExactTravel.java` is an independent diagnostic, not an integration into
`ComboModel`. It reproduces the ordinary dry, loaded, flat-floor branch of the
pinned client `EntityLivingBase.moveEntityWithHeading`, `pr.g(FF)`, from its entry
to its return. `tests/ExactTravelCheck.java` supplies standalone regressions and
an offline TSV bridge. It does not call native game objects or use later state.

## Measured result before the new AABB trace

The implemented kernel was checked against **4,804** archived one-step samples
from m0360, m0362, m0367, and m0370, both clients. Inputs were recorded g-entry
position/motion/onGround, raw float strafe/forward, body yaw, ground-speed getter,
and air factor. The known ordinary floor was at absolute Y=4. Existing traces
check water/lava/ladder; other scope assumptions came from the controlled setup.
No later state was injected into the prediction.

| Output | Bit-identical samples | Largest absolute error |
| --- | ---: | ---: |
| motionX | 4804/4804 | 0 |
| motionY | 4804/4804 | 0 |
| motionZ | 4804/4804 | 0 |
| onGround | 4804/4804 | 0 |
| posY | 4804/4804 | 0 |
| posX | 3933/4804 | 9.094947017729282e−13, at most 1 ULP |
| posZ | 3953/4804 | 3.552713678800501e−15, at most 32 ULP |

Old traces do not contain retained AABB endpoints. These comparisons reconstruct
an initial box using native float width .6 and height 1.8, so the X/Z result is
**not exact position validation**. The kernel accepts the actual box; fresh traces
must test all six endpoints and positions without reconstruction. ULP counts near
zero can look large despite tiny absolute differences; neither statistic replaces
bit equality.

The subsequent predeclared fresh suite
[`exact-parity-v2.json`](exact-parity-v2.json), m0383–m0387, recorded actual boxes.
**All 2,091 steps match all 12 double bit patterns and all four ground/collision
flags**, with no failures or unsupported exclusions: 25,092 double comparisons
and 8,364 boolean comparisons. This includes walk/sprint/coast/strafe, 265 air
entries, 10 takeoffs and 9 landings, and RTTs 0/0, 20/100 and 100/20. A complete
native retained-box landing from m0385 is now a standalone golden regression.
This establishes the specified conditional travel transition on those cases;
the intervening event and packet composition remains a separate test.

## Source arithmetic that matters

The audit uses the official client bytecode and local MCP name mapping.

1. **Entry point:** `pr.m()` zeros motion components with magnitude `< .005`
   before calling `pr.g(FF)`. The g-entry probe is already downstream. Applying
   the cutoff inside this conditional predictor would be an extra operation.
   Its raw strafe/forward arguments already contain the living-update .98 damping;
   do not multiply them by .98 again.
2. **Ground acceleration:** in `pr.g`, offsets 396–501, compute float
   `f=.6f*.91f` on the ordinary ground, float
   `k=.16277136f/(f*f*f)`, then float `a=getAIMoveSpeed()*k`. Air uses its supplied
   float `jumpMovementFactor`. Do not replace k by exactly 1 or promote these
   operations to doubles. The native sprint getter in these traces prints
   `.13000001`, which must be parsed back to a float, not replaced by `.13`.
3. **Input rotation:** `pk.a(FFF)` computes float
   `l=strafe*strafe+forward*forward`; returns if `l<1e-4f`; takes
   `(float)Math.sqrt((double)l)`; clamps that length to at least 1; divides
   acceleration by it; scales both float inputs. Thus diagonal normalization and
   the small-input cutoff have specified float rounding and branch boundaries.
4. **Trig:** `MathHelper` has a 65,536-element float sine table initialized by
   `(float)Math.sin((double)i*Math.PI*2.0/65536.0)`. Yaw radians are float
   `yaw*3.1415927f/180.0f`. Sine index is
   `(int)(radians*10430.378f)&65535`; cosine adds `16384.0f` **before** casting.
   These casts truncate, including negative values. The rotated increment is
   evaluated as floats, then widened once before addition to double motion.
   Double `Math.sin/cos(yaw)` is a different transition.
5. **Collision and position:** `pk.d(DDD)` clips and offsets the retained AABB
   in Y, X, Z order. On a full plane with no horizontal obstruction, only downward
   Y clips. Native uses a strict comparison between the gap and requested Y;
   exactly touching the plane without clipping does not set onGround. Each offset
   adds to both endpoints separately. `pk.m()` then resets
   `posX=(minX+maxX)/2`, `posY=minY`, `posZ=(minZ+maxZ)/2`.
   Replacing this with `pos+=motion`, or rebuilding the box from its center every
   tick, discards native rounding history. A unit test demonstrates equal-center
   boxes that yield different next centers with identical motion.
6. **Landing/friction:** default block landing clears vertical motion when it
   clips. Friction is sampled again before movement, so a ground-origin launch
   uses ground friction and a landing from air uses air friction for that call.
   After movement, native performs `motionY-=.08`, then
   `motionY*=.9800000190734863`, then horizontal motion times the widened float
   friction. The float `.6f*.91f` widens to `.546000063419342`; the double literal
   `.54600006` is not equivalent.
7. **Loaded columns:** post-movement client gravity checks both block-loaded and
   chunk `isLoaded` state. Its block position uses `(int)posX` and `(int)posZ`,
   truncation toward zero, rather than floor; this matters at negative coordinates.
   The independent kernel explicitly rejects an unloaded branch.

`setPosition`, `pk.b(DDD)`, constructs a **new** box using float `width/2` widened
to doubles. The helper `ExactTravel.boxAt` reproduces that operation for actual
initialization or synthetic fixtures; it is not a substitute for a recorded
pre-existing box.

## Necessary recorded state and limits

For this conditional kernel, record exact entry position/motion, onGround,
six AABB endpoints, float body yaw, raw g arguments, ground/air acceleration,
slipperiness, declared plane top, and loaded-column state. Log exit position,
motion, box and horizontal/vertical/combined collision flags for comparison.
Also record noClip, web, sneaking, water/lava/ladder to enforce scope; record
width, height and stepHeight to audit box construction and unexpected stepping.
The declared plane top is `floor_top_y=4`; `support_block_y=3` is a different
quantity. Retain absolute world coordinates: subtracting an arena origin changes
the rounding of large-coordinate AABB calculations.

The predictor requires an ordinary full solid plane, open horizontal space, no
mount or flight, and ordinary block callbacks. It rejects recorded noClip, web,
sneak, liquid, ladder, unloaded-column and initial-floor-penetration states.
Horizontal obstacles, ceilings, slipperiness boundaries and special landing
blocks require a real collision-world model; supplying this plane predictor for
them would be a caller scope error. It does not claim parity for limb animation,
sound, fall bookkeeping, entity pushing after g, or arbitrary game state.

Decimal JSON is sufficient when the logger preserves Java round-trip strings and
the reader restores the original type: `Float.parseFloat` for float fields,
`Double.parseDouble` for doubles. Compare `Double.doubleToRawLongBits` and
`Float.floatToRawIntBits`, preserving signed zero. Recording raw bit patterns too
would make serialization mistakes easier to diagnose. Pin the native jar, JVM
version/architecture and pure-kernel version; a sine-table bit hash is a useful
cross-runtime check if those change.

## Staged exact-replay acceptance criteria

1. **Conditional travel:** every declared supported fresh sample must match all
   six motion/position doubles, all six box endpoints, and all four collision/
   ground flags bit for bit. Report unsupported samples and reasons explicitly;
   do not silently drop them. Test ground start, coast, sprint transitions, air,
   launch, landing, negative/noncardinal yaw, diagonal input and input thresholds.
2. **Conditional packet and combat transitions:** require exact packet type and
   integer payload, immunity/damage transitions, sprint flag and velocity result.
   Initialize the exact needed state; inspect the first differing intermediate
   operation when any output differs. Quantization makes a small scalar residual
   insufficient to establish packet parity.
3. **Event composition:** provide initial client/server/tracker state and the
   externally recorded, ordered event schedule, including frames, input
   consumption, main-thread drains and packet deliveries. Predict packet payloads
   and local contacts internally; do not inject observed hit outcomes or corrected
   positions. Validate generated packets against recorded ones before applying
   their recorded delivery schedule. A missing/extra packet is already a failure.
   Require exact event-by-event state, stopping at the first mismatch.
4. **Uncorrected held-out execution:** after the components and ordering pass,
   replay unseen cases under those same rules. Final score, averaged position
   error, or an error threshold cannot override an earlier event mismatch.

Known past event schedules and unknown future scheduling are separate problems.
Native mechanics are deterministic conditional on sufficient state and ordered
inputs. A snapshot plus nominal RTT does not specify future operating-system
delays, TCP batching, render frames or task-drain order. An exact offline replay
may condition on those measured exogenous events; a live controller cannot read
future events. Its predictions must therefore state a scheduling model or bounds
and assess the resulting possible transitions. This uncertainty does not justify
approximating arithmetic in a transition whose inputs are already known.

The current achievement is exact archived conditional velocity parity and fresh
conditional **position, velocity, AABB and collision-flag parity**, plus native
golden and scope/boundary tests. Complete event-composition parity remains a
separate acceptance gate.

## Minimal safe integration into an offline composition test

Do not replace the existing shared `ComboModel.move` indiscriminately. It serves
client travel and an approximate server-motion path with different surrounding
operations. Introduce an explicitly selected offline exact path first; preserve
the current controller until the composition contract passes.

- Give each exact body a retained immutable Box, float body/head angles and float
  acceleration/cache state. Copies must preserve all of these. Initialize from a
  complete settled native snapshot. Retain the box after travel; construct a new
  one only at a native `setPosition` transition. The current server handler writes
  body positions directly at several points; exact box reset semantics need an
  explicit corresponding event there.
- Keep every exact body, server position, view and queued position in absolute
  world coordinates. `NativeClockReplay` and `ModelController` currently subtract
  an origin and floor Y=4. That is acceptable for the old approximation, but an
  exact path must avoid those arithmetic changes. Use an explicit floorTopY=4
  rather than the existing y=0 clamp. Convert only for presentation, never around
  each arithmetic operation. Observer translations and hurt confirmations must
  remain disabled in this uncorrected test.
- Model the wrapper around g separately: packet application at its actual drain;
  frame aim/click injection; local click consumption; one .005 cutoff; native
  sprint/input updates; exact travel; head/cache and packet-emission updates at
  their source positions. Feed the kernel actual float raw input generated by
  those transitions, not a double normalized direction vector.
- Source-derived default sprint acceleration is
  `(float)((double).1f*(1+.30000001192092896))`, yielding `.1300000101327896`.
  The sprint modifier's amount is the widened `.3f` in the native attribute.
  The post-travel air cache uses
  `(float)((double).02f+(double).02f*.3)`, yielding `.025999998673796654`.
  Current model literals `.13` and `.026` cannot provide exact wrapper parity.
- The current A/B/SERVER replay combines several operations into each event,
  applies eligible client packets at the tick start, and always creates a movement
  packet. Exact composition instead needs the native frame/task-drain and packet
  subtype/send-threshold events. `Client.frame` runs at `runGameLoop` entry;
  native scheduled tasks drain afterward, before one or more client ticks. Do not
  put that frame's packet application before its aim/input hook.

The smallest next end-to-end fixture is one moving A and a stationary B, no
clicks, RTT20/100: A follows a predeclared 600-ms W+Ctrl burst, then releases and
coasts, for about three seconds after settled initialization. Keep the initial
gap large enough to avoid body pushing. This isolates client movement → generated
C03 → server position → tracker send → recipient application/interpolation.

Use initial snapshots and the measured external event/delivery order, while
generating all later positions, aim and packet payloads internally. In addition
to tracker counters, initial client send caches are needed:
`lastReportedPosX/Y/Z`, `lastReportedYaw/Pitch`, `positionUpdateTicks`, and
`serverSprintState/serverSneakState`. Assert the first predicted packet payload,
server position and remote interpolation transition, then continue without
corrections. Supplying the later measured coordinates, packet position payloads,
or recorded hit decisions would instead be another conditional component test.
