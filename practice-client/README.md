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

Client commands work without server permissions:

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
block or a timing verdict. No estimated combat advantage is claimed yet.

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

The `PvpLab|v1` custom-payload channel uses Minecraft's existing connection; it
opens no additional listener. The server accepts versioned `hello`, `telemetry`,
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

Minecraft picks it up automatically. The API accepts immutable snapshots and
events and returns HUD text, rectangles/history bars, an optional edge flash,
and whether to hold ordinary Sprint input. Modules can maintain state, calculate
feedback, draw per frame, add `/practice` subcommands, play local sounds and send
markers/clock probes. They also receive chat/action-bar and linked server events.
Modules do not register their own Forge listeners.

`PracticeExtension.event()` receives packet metadata and Base64 payloads up to
16 KiB, handler/input events, snapshots and `link_state`/`link_pong`/marker replies.
Larger packet bodies stay in the full disk recording and have an explicit
`module_payload_omitted` flag in live events. This lets future modules decode
ordinary combat packets without adding a new core hook. `render()` returns an
optional per-frame HUD; `PracticeServices` supplies the sound/marker/probe methods.
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
sends markers/probes, performs ordinary clicks, stops the duel and exits. Test files go under
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
