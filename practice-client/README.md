# PvP Practice client (Forge 1.8.9)

This is our own client mod. It includes ToggleSprint, a potion-effect HUD,
localhost packet/input/view recording, and a diagnostic link between the human
client, bot client and server. Practice displays and analysis live in a reloadable
module. No third-party HUD mod is required.

## Use

Launch the **Forge 1.8.9** installation once after installing the bridge. The
ordinary Sprint key (currently **R**) toggles sprint; it starts enabled. Its
cyan label appears in the top right, with potion names and remaining durations
underneath. Controls → Sprint changes the toggle key too. This holds the normal
sprint input; vanilla decides whether the player can actually sprint. It does
not press forward, attack, aim, alter reach, or bypass blocking slowdown.

Client commands work without server permissions. `/p` is an alias for `/practice`
with all the same subcommands, for example `/p advantage on`:

| Command | Action |
| --- | --- |
| `/practice reload` | Reload the module and its configuration immediately |
| `/practice status` | Show reload, capture and server-link status |
| `/practice sprint on` / `off` | Enable/disable ToggleSprint |
| `/practice potions on` / `off` | Show/hide potion timers |
| `/practice metrics on` / `off` | Show observed speeds, target gap/height, and blocking time |
| `/practice record on` / `off` | Start/stop local client-observation CSV recording |
| `/practice capture on` / `off` | Enable/disable the detailed localhost capture |
| `/practice link on` / `off` | Enable/disable localhost coordination |
| `/practice mark <text>` | Timestamp a named marker on client and server |
| `/practice ping` | Record a clock/round-trip probe |
| `/practice opening on` / `off` | Show/hide geometric opening lead (on by default) |
| `/practice opening large` / `small` | Prominent centered opening HUD, or compact text |
| `/practice advantage on` / `off` | Both client-view distances and your reach advantage, live and in replay (on by default) |
| `/p range yours on` / `off` | Indicate when the opponent is within your client-view reach; blue inner crosshair brackets by default |
| `/p range theirs on` / `off` | Indicate when you are within the opponent's client-view reach; subtle red outer crosshair brackets by default |
| `/p range both on` / `off` | Enable/disable both range indicators together |
| `/p range yours crosshair` / `edge` / `screen` | Choose a style and enable it; substitute `theirs` or `both` to change the other indicator(s) |
| `/p range theirs color FF5555` | Set a six-digit RGB color independently for either side |
| `/p range theirs opacity 12` | Set opacity from 0–100 percent; `auto` restores the style's default |
| `/p range theirs pulse on` / `off` | Optional brief brightness pulse when entering range; off by default |
| `/practice reach on` / `off` | Smooth translucent reach fields: cyan for your client, amber for the bot |
| `/practice ghost bot on` / `off` | Faint amber silhouette at the bot's own client-rendered position |
| `/practice ghost me on` / `off` | Faint pink silhouette where the bot's client renders you |
| `/practice debug on` / `off` | Show direct-stream delivery age, draw age, clock RTT and recent latency statistics |

Server commands `/drill near`, `/drill medium`, `/drill far` and `/drill mixed`
start repeated rod-diamond approaches against wtap, from center-to-center gaps of
4.5, 6 and 9 blocks. Append another bot name to change opponents. Mixed cycles the
three distances. Each attempt resets five seconds after the first accepted sword
hit, or after twelve seconds without one. Repeats reset directly in the arena,
with a visible two-second center-screen countdown (`1.0` → `GO!`), without a lobby
detour. The same bot and arena are reused when available. Repeats only teleport,
heal, and clear old rods/momentum: the existing player entities and kits stay
loaded, so the bot does not disappear and respawn during each countdown. `/stop` or
`/drill stop` exits. Your saved ping and the bot's previous duel ping are retained.
During setup only, players are held at their starting positions and pre-cast rods
are cleared. Once combat starts, normal movement and combat apply.

**Opening lead is geometric range-entry lead, not a prediction of who lands a hit.**
It compares each client's own eye-to-opponent-expanded-hitbox distance against
its reach, accounting for height. Positive means you entered range first.
Actual aim, invulnerability, click timing, and queued attacks are excluded.
Crossings are bracketed between outside/inside samples; the HUD reports the
midpoint and `+/-` half-width, not invented exact millisecond precision. It keeps
the last eight approaches and rearms after both views remain outside for 200 ms.
A one-sided opening shows a growing lower bound. Missing samples invalidate a
crossing rather than producing a precise-looking answer.

**Reach advantage = d1 − d2**, where d1 is the distance from the opponent's eyes to
its local representation of you, and d2 is the distance from your eyes to your
local representation of the opponent. Positive favors you. Both are the shortest
3D eye-to-expanded-hitbox distance using each client's logical hit-test state,
including the native collision border. The ideal-aim boundary is 3.0 blocks;
aim, obstructions, click timing and immunity still decide whether an attack works.
With equal height and matching settled views the difference is zero. Vanilla's
position quantization can leave a small residual even when both stop moving;
height/pose differences can also make the two distances asymmetric. We display
the measurements, rather than subtracting an arbitrary stationary baseline.
The replay readout stays oriented to the recorded human (or A for bot-vs-bot),
regardless of which viewpoint the camera follows. This is a geometric distance
advantage; modified reach caps are a separate setup parameter.

The two **range indicators** are independent of the numeric HUD, reach fields,
and each other. Both start enabled: blue inner brackets when you can geometrically
reach them in your view, subtler red outer brackets when they can geometrically reach you
in theirs. They use logical eye-to-expanded-hitbox distances and each actor's
actual reach cap (normally 3.0), including height. Aim, walls, immunity and click
timing are not tested. A signal switches off immediately outside range; visual
fading never extends the threshold. Missing/stale opponent data hides its signal
without hiding yours. The same calculations work during replay, always oriented
to the recorded human (or A for bot-vs-bot), independent of the selected camera.

Choose `crosshair`, `edge`, or `screen` for either indicator. When both use edges,
your signal occupies the top/bottom and theirs the left/right. Crosshair signals
use separate inner/outer brackets; when both use screen tints, those brackets
also identify the two signals. Automatic opacity is 85% for your brackets, 55% for theirs, 12% at the
outer edge fading inward, and 6% for full-screen tint. Optional entry pulses
brighten for 180 ms of live/replay time and freeze when replay is paused. Seeking
or first receiving a view does not manufacture an entry pulse. Commands save
your choices, show brief HUD feedback, and take effect without restarting.
`/p range` shows usage. All these commands also work under `/practice range`.

Reach fields are filled translucent spheres with a soft inner boundary and no
rings, spokes, target boxes or diagnostic lines. They follow frame-interpolated
render positions. These fields and ghosts are visual references; their cross-view
intersection is **not** a valid hit test. Numeric distances use logical positions
and are never smoothed across tick transitions. Ghosts are simple translucent
player-shaped guides, not copied skins. Both ghosts are independent of the reach,
advantage and opening toggles. Unavailable or stale bot data hides its overlays;
there is no delay label on the normal HUD.

## Multi-view replay and shared archives

`/replay` opens your latest completed match. Select hotbar items with 1–9 or the
scroll wheel, then click. Slots are play/pause, rewind, forward, speed, viewpoint,
camera, ghosts, combos and exit. Left/right seek 1/5 seconds; speed clicks decrease/
increase the multiplier; combo clicks select previous/next. Right-click the ghost
item or use `/replay menu` for the native settings screen. Exit is right-click only.

The menu switches server/A-client/B-client POVs and independently toggles both
actors as seen by each client (four ghosts), plus two optional server ghosts.
`/replay pov human`, `/replay pov bot`, `/replay pov server` work from chat, as do
`/replay ghost human-self on`, `human-opponent`, `bot-self`, `bot-opponent`,
`/replay ghost human on` (both), and `/replay ghost off`.
The selected POV chooses the recorded positions; camera follow/free is separate.
Reach fields and the numeric advantage use the same observation and drawing
functions as live play. Clients with no recorded data are explicitly unavailable;
older 5 Hz recordings are labeled. Playback interpolates visual poses between
samples, and sample-and-holds logical hit-test positions. It is a state replay,
not pixel-identical video or a simulation rerun. World/lighting, skin, particles,
first-person hands and all animation details are not reconstructed exactly.

The installer sets a trusted local `archive.directory` to the lab's `runtime/pov`.
Every new duel gets `runtime/pov/<match>/manifest.json`, per-client compressed
`*-poses.jsonl.gz` indexes (up to 60 Hz, actual times preserved), and separate full
`*-events.jsonl.gz` streams. The human stream includes raw PLAY packet bytes,
application/input/tick/render observations; bot streams include raw PLAY bytes,
poses and available decision/physics probes. The server stores raw PLAY packets
per participant plus its existing authoritative tick/combat replay. Initial pose
and equipment snapshots and ordinary equipment packets supplement the recording.
No login/authentication packets are captured. Files are local and available to
both server and client; no server-supplied paths or executable downloads are used.
`/practice capture off` stops the human archives too. Bot/server capture remains
owned by the lab. Each writer has bounded queues, disk-space and size limits;
footers expose drops/caps/completion, and the inspector exposes packet omissions.
Missing source/segment/footer is not a complete recording.

```sh
python3 pov_archive.py m1033 --full
python3 pov_archive.py m1033 --export a > /tmp/human-poses.jsonl
python3 pov_archive.py m1033 --export b --events > /tmp/bot-events.jsonl
```

The bridge's `replay_frame` event exposes all available views, clock, labels,
ghost selection and computed reach advantage on every render. This keeps future
analysis displays independent of whether observations come from live or replay.

Settings are in `config/pvp-practice/settings.properties` inside the Minecraft
game directory. Changes reload about once per second while Minecraft is running;
`/practice reload` makes it immediate. Change `hud.rightInset`, `hud.top`,
`hud.color`, `sprint.label`, or `potions.top` to customize the HUD. Invalid
configuration or a broken replacement JAR keeps the last working module.
Reloading resets the module's session counters and toggle state to
`sprint.startEnabled`, and starts a new recording segment if recording is enabled.

Optional metrics and the small CSV recording start **off**. Detailed localhost
capture and coordination start **on**. Speeds are client-observed position changes
per game tick × 20, not server velocities. The target is the player under the
crosshair, otherwise the nearest player within six blocks. Gap is horizontal
center-to-center distance, **not attack reach**. Blocking time counts sampled
client ticks, not server-confirmed blocked damage. The optional red edge flash
(`feedback.hurtFlash=true`) indicates an observed hurt animation, not a verified
block or a timing verdict. Opening lead has the narrower geometric meaning above.

The small CSV files stay in `config/pvp-practice/recordings/`. Detailed compressed
JSONL files stay in `config/pvp-practice/captures/`. Recording uses only an actual
loopback Minecraft connection. It starts at the first PLAY packet, before initial
world chunks are applied, and captures:

- Original inbound/outbound uncompressed packet ID + body bytes, Base64-encoded,
  with wall and monotonic timestamps. Buffers are copied without advancing their
  reader indexes or reserializing live packet objects.
- Packet-handler entry/return, packet identity and thread. Netty entry can merely
  schedule main-thread work; `main_thread` distinguishes application on the game
  thread. Some keepalive work genuinely runs on Netty.
- Gameplay keyboard/mouse events and vanilla left/right click before/after state.
- Tick start/end snapshots and render-view samples capped at 60 Hz: positions,
  motion, height, bounding boxes, aim/raycast, interpolation targets, blocking,
  item use, held items/armor, hunger, selected slot and camera settings.
- Match identity, server snapshots, latest human/bot views, markers and clock probes.
- Direct view/geometry/opening/debug snapshots capped at 20 Hz (`practice_views`),
  including original bot sequence/time. This is not a recording of every fast-stream frame.

**Login/authentication traffic and typed GUI text are excluded. Local PLAY chat
and commands are included in the raw packets.** Player names/UUIDs are present.
Nothing is uploaded. Captures are not screenshots or video.

The asynchronous writer has a 2,048-record/32 MiB pending limit, rotates at
256 MiB of uncompressed records per file, and permits 128 linked parts per session.
It requires at least 512 MiB free space at each new part. Old captures are never
deleted automatically. Overflow, cap exhaustion, observer errors and module-event
drops are explicitly recorded; `/practice status` reports writer failures.
`writer_complete` only describes writer integrity, not perfect replay fidelity.
Use the first file when analyzing a session; the analyzer follows linked parts.
Turning capture back on mid-connection starts a segment marked as missing the
initial connection state. Reconnect for a capture containing the initial world.

This is the recording foundation for replay and analysis, **not a finished
client replay player or a promise of pixel-perfect reconstruction**. Entity
snapshots are capped at 128 (with a truncation flag); packets remain captured.
We do not save resource-pack binaries or every rendered frame. Native handler
hooks observe work but still have CPU cost, measured in snapshot timing fields.

## Three-view coordination

The `PvpLab|v1` custom-payload channel uses Minecraft's existing connection. The
server accepts versioned `hello`, `telemetry`,
`marker` and `ping` data only from loopback connections. It binds identity to the
actual player connection, limits messages to 30,000 bytes and 20/second/player,
bounds JSON nesting and uses bounded queues. There is no remote-command or
remote-code message type.

At 5 Hz the human client gets authoritative server state and the latest views
from both clients, tagged with match ID, sender, sample time and server receipt
time. Live human telemetry contains only the duel participants, keeping messages
small; full local captures include other loaded entities. Bot telemetry is
observational and is not supplied to bot policies. Live messages pass through
the ordinary artificial-ping proxy, so their ages must be respected.

For human duels, the bot also opens an ephemeral **127.0.0.1-only TCP listener**
with a fresh 128-bit per-match token, announced through the practice channel.
This carries fixed-size numeric snapshots directly to the human client and
bypasses gameplay ping and the server tick queue. No new dependencies, command
messages, Java object deserialization or executable downloads are involved.
Readers authenticate, use TCP_NODELAY, calibrate monotonic clock offset using
eight timestamp probes, and exchange bounded immutable snapshots. The stream
closes on match change. Reading and writing happen on daemon workers; no socket
I/O blocks Minecraft's game thread. Bot policies never read this diagnostic link.

`/practice debug on` separates **sample → receive** from **sample → draw**, showing
current values and rolling mean/p95 over the last 600 samples/frames. The first
includes snapshot construction, producer handoff and socket delivery; the second
also includes waiting for your render callback. Clock uncertainty is approximately
half the best probe RTT. Physics-tick age is separate: game logic updates at 20 Hz
even when the stream delivers in under a millisecond. Draw age does not include
GPU presentation/display scanout. Low transport delay cannot guarantee a 1 ms
visible update on a 60 Hz screen. No extrapolation disguises sample age.

For duels started after the human link is established, the referee also enables
the bot's existing detailed native movement, interpolation, click and network
traces. Server files (`runtime/replays/<match>.jsonl`) include `pov_client`,
`pov_server` and `practice_marker` rows. Full bot traces remain in that bot's
`runtime/bots/<slot>/events.jsonl`. The existing server `/replay` still works.

On this one computer, all processes share UNIX wall-clock milliseconds. Join on
that clock plus match/participant identity. **Do not compare `System.nanoTime()`
origins between JVMs.** Clock probes record client send/receive and server
receive/send times; they expose queueing and configured latency, not permission
to assume a symmetric network on other machines.

Analyze a capture without displaying chat or payloads:

```sh
python3 practice-client/analyze_capture.py /path/to/first-capture.jsonl.gz \
  --server-replay runtime/replays/m0990.jsonl
```

The report checks packet byte counts and segment footers and summarizes packet
application delay, view age, clock-probe RTT, three-view coverage and errors.
Initial joins/chunk loading can cause larger delays than steady combat; inspect
the timestamps before treating an aggregate delay as combat latency.

## Build and live updates

From the repository root:

```sh
python3 practice-client/build.py --install
```

After changing the code in `module/`, rebuild just the reloadable module:

```sh
python3 practice-client/build.py --module-only --install
```

Minecraft picks up module-only updates automatically. Full bridge installations stage a
`practice-module-next.jar`, activated at the next client start, so an older running
bridge never loads a module that requires the new API. The API accepts immutable snapshots and
events and returns HUD text, rectangles/history bars, an optional edge flash,
and whether to hold ordinary Sprint input. Modules can maintain state, calculate
feedback, draw per frame (including bounded world-space lines, translucent boxes and filled spheres), add `/practice` subcommands, play local sounds and send
markers/clock probes. They also receive chat/action-bar and linked server events.
Modules do not register their own Forge listeners.

`PracticeExtension.event()` receives packet metadata and Base64 payloads up to
16 KiB, handler/input events, snapshots and `link_state`/`link_pong`/marker replies.
Larger packet bodies stay in the full disk recording and have an explicit
`module_payload_omitted` flag in live events. This lets future modules decode
ordinary combat packets without adding a new core hook. `render()` returns an
optional per-frame HUD; `PracticeServices` supplies the sound/marker/probe methods.
`world()` returns the world overlay; `practice_views` events arrive directly on
the render thread so overlays do not wait for the next 20 Hz client tick.
Events are delivered on the game thread. Process them briefly or offload math
using only immutable copies, never live game objects.

Changing the bridge's event hooks, the API, or adding new game capabilities still
requires rebuilding the bridge and restarting Minecraft. This is local module
replacement, not injection into Lunar or arbitrary replacement of Minecraft.

The Python build uses the installed JDK, pinned official Minecraft/Forge
artifacts and ASM for build-time name mapping. It does not execute upstream
Gradle scripts or annotation processors. The installed bridge and module contain
only our own classes; build dependencies and HeadlessMC are not bundled into them.
Sources use Forge's stable SRG names to avoid an additional Gradle toolchain.

## Verification and security

The build checks toggling, GUI behavior, placement, configuration reload,
invalid-reload recovery, CSV shape, immutable events, compressed writer completion,
cap/rotation boundaries, and replacement of **actual bytecode in the same JVM**.

With the local practice server running, use:

```sh
python3 practice-client/smoke.py
# Include the exact OptiFine L5 download previously verified against optifine.net:
python3 practice-client/smoke.py --optifine
```

This starts a separate headless Forge 1.8.9 client as `PracticeCheck`, verifies
event dispatch, HUD rendering callbacks, normal sprint input, client commands,
live reload and native observer hooks. It starts a short boxing duel against
wtap with 80 ms bot RTT, checks that server/human/bot views arrive together,
checks the direct stream and both world overlays, then cycles all three entry
distances and verifies that `/stop` prevents another round. It logs measured
delivery/draw timing. It then replays that match, switches all POVs, draws all four
ghosts and both fields, and tests native hotbar pause/seek/menu/exit and survival
restoration. Test files go under
`runtime/practice-forge-smoke`; a macOS process sandbox restricts filesystem
access and networking to localhost. The smoke test's own Forge probe and
HeadlessMC agent are **not installed** in the user's client.

The practice mod opens no HTTP/WebSocket port, downloads no code, runs no
subprocesses, and reads no account/session data. Only a fixed local module path
is loaded; server messages cannot select or replace executable code. A local
Java module has the client's normal Java permissions: it is **not a sandbox**.
Only put code we built/reviewed into that module path. A separate fresh
classloader enables replacement and releases the old module on successful
reload; malformed replacement code is never made active if loading/configuration
fails. Running code errors release the mod's sprint input and disable the module
until a successful reload.

The multi-view integration run passed 43 native checks with OptiFine, including
1,280 paused-frame actor/pose comparisons with zero positional error and complete
three-source archives with zero writer drops. See
[the verification report](../experiments/replay-views-20261031/verification.json).
This checks native render callbacks and coordinates in HeadlessMC, not a pixel
screenshot or exact reconstruction of first-person hands/particles.
