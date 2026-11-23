# PvP Practice client (Forge 1.8.9)

This is our own client mod. It includes ToggleSprint, a potion-effect HUD,
opt-in remote and localhost packet/input/view recording, and a diagnostic link between the human
client, bot client and server. Practice displays and analysis live in a reloadable
module. No third-party HUD mod is required.

The bridge displays the server's bot policy labels above live and replay players.
Two copies of the same policy are named `wtap-a` and `wtap-b`, for example. Full
research policy names remain readable; real account names, UUIDs and recorded
participant identities stay unchanged. Match lists, spectator follow controls
and results use the same labels. Labels are cleared when a bot slot leaves a
match. This bridge change needs one client restart after installation. Unmodded
clients still show `LabSlotN` above live bots because vanilla player rendering
ignores custom-name metadata; replay profiles have a readable 16-character
fallback, preserving the side suffix for long names.

## Use

Launch the **Forge 1.8.9** installation once after installing the bridge. The
ordinary Sprint key (currently **R**) toggles sprint; it starts enabled. Its
cyan label appears in the top right, with potion names and remaining durations
underneath. Controls → Sprint changes the toggle key too. This holds the normal
sprint input; vanilla decides whether the player can actually sprint. It does
not press forward, attack, aim, alter reach, or bypass blocking slowdown.

Client commands work without server permissions. Type `/p` or `/practice` for
the help menu. Click a category or type a command such as `/p help replay` or
`/p help range`. Clicking a command in a category puts it in your chat box.

| Command | Action |
| --- | --- |
| `/practice reload` | Reload the module and its configuration immediately |
| `/practice status` | Show reload, capture and server-link status |
| `/practice sprint on` / `off` | Enable/disable ToggleSprint |
| `/p oldsneak on` / `off` | Smooth the camera as you sneak and stand back up (on by default) |
| `/p dynamicfov off` / `on` | Keep your chosen FOV at different movement speeds or restore speed-dependent FOV. Bow zoom still works. |
| `/p hurtcam off` / `on` | Turn damage camera tilt off or restore it (on by default) |
| `/practice potions on` / `off` | Show/hide potion timers |
| `/practice metrics on` / `off` | Show observed speeds, target gap/height, and blocking time |
| `/practice record on` / `off` | Start/stop local client-observation CSV recording |
| `/p remote record on` / `off` | Opt in/out of remote-server recording (default off); local archive upload only |
| `/p remote crosshair on` / `off` | Toggle the public-server KB brake flash and your reach brackets together |
| `/p remote crosshair brake on` / `off` | Toggle just the public-server KB brake flash |
| `/p remote crosshair reach on` / `off` | Toggle just your public-server reach brackets |
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
with a visible two-second center-screen countdown (`2.0` → `GO!`), without a lobby
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
scroll wheel, then click. Slots are play/pause, event, recorded tick, one-second seek,
speed, viewpoint, settings, combos and exit. Left-click goes back or slower and
right-click goes forward or faster. Event and tick steps pause in the selected
view. The action bar shows the exact timestamp and selected record. Settings
opens with either click or `/replay menu`. Exit is right-click only. The practice
mod keeps your own toolbar visible while following either replay actor.

The menu switches server/A-client/B-client POVs and independently toggles both
actors as seen by each client (four ghosts), plus two optional server ghosts.
`/replay pov human`, `/replay pov bot`, `/replay pov server` work from chat, as do
`/replay ghost human-self on`, `human-opponent`, `bot-self`, `bot-opponent`,
`/replay ghost human on` (both), and `/replay ghost off`.
The selected POV chooses the recorded positions; camera follow/free is separate.
The keystrokes HUD follows the selected human or bot POV, including in free
camera. It shows that participant's WASD, Space, Sprint, mouse buttons and one-second CPS
at the playhead, or **Inputs unavailable** if their archive lacks inputs. The
free camera in Server POV shows your normal live keystrokes.
Use `/p keystrokes off` or `/p keystrokes on` to hide or show it.

The Sprint tile lights when Minecraft's sprint binding is held, including by
ToggleSprint. New recordings display the participant's key or mouse binding.
Older bot policies with recorded decisions show **Sprint**. **SPR ?** means the
recording has no evidence for that input. In a bot POV, the extra lines show
local sprint, the last outgoing START or STOP sprint packet and its age, and
whether the bot allowed a click. LMB flashes on an actual press.

With **/p sumocoach → Settings → Live coaching** and the successful-brake
**Flash** enabled, the first verified brake after an incoming launch shows
green brackets for 160 ms. Further brakes in that exchange show **×2**, **×3**,
etc. Server replays use the selected participant’s client archive. Pausing or
stepping backward restores the feedback at that instant. New human lab
recordings also capture the aim-miss cue. Its amber outline lasts 180 ms.

A separate label below the crosshair appears for 300 ms after an outgoing fresh
melee hit confirmed by the lab referee. **Sprint KB** means sprint powered the
hit. **Base KB** means the server saw sprint off. Choose **Esc → Edit HUD →
Sprint KB cue**, or use `/p sprintkb on` and `/p sprintkb off`. Server replays
read this verdict from the selected participant’s hits. The local offline viewer
can show verdicts saved in new lab recordings. Public MMC recordings lack the
referee feed.
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
JSONL files stay in `config/pvp-practice/captures/`. Local recording uses an actual
loopback Minecraft connection; remote recording requires the separate opt-in below. It starts at the first PLAY packet, before initial
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
Nothing leaves this computer. Remote captures may transfer to the local lab receiver
as described below. Captures are not screenshots or video.

The asynchronous local writer has a 2,048-record/32 MiB pending limit, rotates at
256 MiB of uncompressed records per file, and permits 128 linked parts per session.
It requires at least 512 MiB free space at each new part. Old captures are never
deleted automatically. Overflow, cap exhaustion, observer errors and module-event
drops are explicitly recorded; `/practice status` reports writer failures.
`writer_complete` only describes writer integrity, not perfect replay fidelity.
Use the first file when analyzing a session; the analyzer follows linked parts.
Turning capture back on mid-connection starts a segment marked as missing the
initial connection state. Reconnect for a capture containing the initial world.

The offline viewer below rebuilds a recorded client world for replay and analysis;
it does **not** promise pixel-perfect reconstruction. Entity
snapshots are capped at 128 (with a truncation flag); packets remain captured.
We do not save resource-pack binaries or every rendered frame. Native handler
hooks observe work but still have CPU cost, measured in snapshot timing fields.

## Live BedWars forecasts

Open **`/p forecast`** for the in-game controls. Turn on the overlay, pick a
model, and enable any combination of 5, 10, 30, 60 and 120 seconds. Enter one or
more player names or team colors and select **Done**. When both filters are set,
either match is included. Leave both fields blank to use every player whose
position your client currently knows. Each horizon has its own color in the HUD.
Tiles use separate stripes when several horizons are enabled. The combined map
averages the selected players' probabilities.

Choose **Heatmap**, **Ghosts**, or **Both** under **Display**. In ghost modes,
set **Ghost min %** to the minimum probability in a local hotspot and **Max
ghosts** to cap the total across horizons. The defaults are 25% and three
ghosts. Each ghost stands at a predicted feet block in its 3D hotspot. The
label shows the probability in the surrounding neighborhood, up to 48 blocks
wide and 12 blocks high. A bin contributes once per horizon. Nearby
predictions at different horizons share one silhouette, with separate colored
labels such as `10s 43%` and `60s 28%`. Hotspots at substantially different
heights can show separate ghosts in the same X/Z column.

Under **Heatmap**, choose **3D** for translucent 16×4×16-block volumes at their
forecast heights, or **Sum over Y** for the horizontal probability
painted on the loaded terrain. The 3D display draws up to 80 of the strongest
bins per horizon. Ghosts use predicted height in either heatmap projection.
**Both** dims the heatmap so silhouettes stand out. Display choices save
locally when you select **Done**. The 3D features require the updated model
bundle.

| Command | Action |
| --- | --- |
| `/p forecast on` / `/p forecast off` | Toggle the overlay for the current Minecraft launch. |
| `/p forecast horizons 10,60` | Show both 10s and 60s. |
| `/p forecast display both` | Choose `heatmap`, `ghosts`, or `both`. |
| `/p forecast projection 3d` | Choose `3d` or `sum-y` for the heatmap. |
| `/p forecast threshold 35` | Show only hotspots with at least 35% probability. Set 0–100. |
| `/p forecast ghost-limit 2` | Cap the total number of silhouettes. Set 1–8. |
| `/p forecast models` | List installed model IDs. |
| `/p forecast model anil` | Use the adaptive last-layer model. |
| `/p forecast players me,SomePlayer` | Select yourself and another player by name. |
| `/p forecast teams red,blue` | Select colors or scoreboard team IDs. |
| `/p forecast players clear` / `/p forecast teams clear` | Clear one filter. |
| `/p forecast status` | Check the current model, match and overlay status. |
| `/p forecast attach 130` | Attach after joining late and use 130 seconds as the elapsed time. |
| `/p forecast reload` | Reload `config/pvp-practice/bedwars-forecast.bin.gz`. |

The overlay starts off and reads the same player position features used in the
replay benchmark. It starts tracking at the BedWars instructions in chat. It can
estimate the clock from the initial Diamond II countdown if you join after the
start. The attach command lets you supply an elapsed-time estimate later. The
model uses completed outcomes from this match, grouped into 30-second updates
with up to 32 observations per player and horizon. Longer forecasts need longer
to collect usable outcomes. Changing a model, horizon or filter retains the
observations already available for the match. A new match clears that history.

The overlay reads loaded entities and the scoreboard from your client. The
height-summed heatmap uses loaded terrain. The 3D elements occupy forecast
coordinates and are occluded by the world geometry. The overlay works
independently of remote recording and sends no packets. Predictions about
players leaving view are limited by the server's entity tracking, and the
estimated match clock can differ from the replay clock.

## Adaptive bow aim

After launching Forge 1.8.9, enter `/p bowaim on` to enable the assist.
Draw your bow while looking near an opponent. The assist favors the closest
player directly under your aim and can also pick a nearby lead angle. It nudges
your view up to 3° from your own aim by default. The limit applies throughout
the draw. In BedWars it uses the player's squad color, including when one
squad has several scoreboard IDs. It waits for a confirmed enemy color.
Release normally when you want to shoot.

Use `/p bowaim linear` to try the straight-line movement comparison,
`/p bowaim off` for manual aim, or `/p bowaim status` to check the current
mode. The assist starts off each launch.

| Command | Action |
| --- | --- |
| `/p bowaim preview` | Show the trajectory with manual aiming. |
| `/p bowaim trajectory on` | Show the trajectory whenever the bow is drawn. |
| `/p bowaim trajectory auto` | Show it when the assist is enabled. |
| `/p bowaim trajectory off` | Hide the trajectory. |
| `/p bowaim intercept on` | Show a box and time estimate where your current shot is predicted to meet the target. |
| `/p bowaim limit 2` | Set the maximum camera correction in degrees, from 0 to 8. |
| `/p bowaim chance on` | Show estimated hit chance above the crosshair in adaptive mode. |
| `/p bowaim autorelease on` | Release once per held draw at full charge if the estimated hit chance is high enough. |
| `/p bowaim threshold 80` | Set the auto-release hit chance from 1% to 100%. The default is 75%. |

The preview follows the direction you're actually aiming at the current draw
strength. It uses the arrow's gravity and drag and stops at terrain or a
predicted intercept. The chance estimate uses that same direction and
draw strength. Auto-release starts off, requires adaptive mode and a held
use key, and still lets you release manually at any point. Options save
locally. With detailed remote recording enabled, the capture stores the draw,
selected target, and displayed estimate at release for shot review.
[Bridge and probability tests](../research/bow-aim/bridging/RESULTS.md)

## Remote recording

`/p remote record on` enables the detailed recorder on your current and future
remote connections; `/p remote record off` stops it. It starts **off** on every Minecraft launch. It stays enabled across connections in that client
session until turned off. The command does not reload the module or change your
current ToggleSprint preference. `/p status` reports this setting, capture writer,
and local upload/backlog status. These are intercepted client commands; they are
not sent as chat to the Minecraft server. Turning it on after joining marks the
capture as missing the connection's initial state. Reconnect with recording on
if you need the initial PLAY world/chunks. Login/authentication remain excluded;
ordinary PLAY chat, names/UUIDs and server address are recorded.

Remote recording observes the same raw inbound/outbound PLAY bytes, packet-handler
entry/return, input/click/tick/render state and task-drain timing. Own-view pose
samples identify the crosshair target (or nearest player within six blocks) by
entity/name, and full entity snapshots retain other nearby players. No lab match,
bot or practice-server handshake is needed. Files are tagged `role=human-remote`
and with the configured server host and actual connected socket endpoint. A single client's timestamps,
S12s and keepalive/transaction observations can support timing estimates and
bounds; they cannot reveal exact one-way latency or private server processing.
The background writer encodes detached snapshots and packet copies to the same
JSONL format. Its pending limit is 8,192 records or an estimated 96 MiB of
retained data. Capture status and the file footer report queue drops.

**The recorder adds zero Minecraft packets on remote connections.** It does not
send the lab's C17 hello, telemetry, markers, clock probes or replay commands.
The guard checks the current real socket, including during a server switch,
regardless of whether a prior capture is still active. The practice-only overlays
(range indicators, reach fields, opening/advantage, ghosts, metrics, hurt flash,
replay/debug) are suppressed remotely even if their local settings are on.
ToggleSprint, potion timers and the opt-in BedWars forecast remain available.

Captures stay in local `config/pvp-practice/captures/` gzip files. Closed parts
are offered approximately every two seconds to **literal `127.0.0.1:8766`** over
an independent HTTP side channel, with proxies and redirects disabled. The
receiver binds only loopback, checks bounded gzip/JSON payloads and safe session
names, and offers no filesystem download endpoint. No recording goes through the
Minecraft connection or leaves this computer. The lab starts this receiver on
its next launch; it can also be started independently, without a Minecraft server
restart:

```sh
python3 practice-client/capture_ingest.py
```

If the lab/receiver is down, the local gzip and `remote-outbox` descriptors remain.
The background worker retries when it returns, including after a client restart.
Uploads are checksum acknowledged and deduplicated; local source files are kept
**even after acknowledgement**. Disk/queue limits and incomplete recordings stay
explicit. Remote parts rotate every two seconds or 16 MiB, with a 10,000-part
session cap and the usual 512 MiB free-space guard. Recording off stops new
capture; already authorized local backlog transfer may finish.

The receiver writes the same `runtime/pov/` archive used by lab analysis/replay:
`r<32 hexadecimal digits>/manifest.json`, a full `a-client-*-events.jsonl.gz`
stream and own-view `*-poses.jsonl.gz` indexes. `runtime/pov/remote-index.json`
lists remote sessions. These are **one-client recordings**: opponent-client and
authoritative server POVs are absent, never fabricated. They can be inspected or
exported with the existing archive tool:

```sh
python3 pov_archive.py r0123456789abcdef0123456789abcdef --full
python3 pov_archive.py r0123456789abcdef0123456789abcdef --export a
```

The local server's `/replay` match selector still requires a lab duel/server
recording. The separate offline client viewer below can open remote captures,
with their one-POV and missing-world limitations kept visible.

Bridge/API changes require one Minecraft restart after installation. The new
settings and capture commands then apply immediately; no server restart or bot
reload is needed.

## Offline viewer (replay-mod-lite v1)

Open **`/practice replays`** or **`/p replays`**, the **Local replays** title-screen
button, or **F8**. The client registers only `/practice` and its `/p` alias.
It never registers or intercepts `/replay`; that command remains the server's.
You can browse while connected. Playback starts after the live connection closes
and runs locally.

With remote recording enabled, completed Minemen Club Sumo matches get a local
**Watch replay** link in chat. Clicking it performs the normal Minecraft
disconnect from MMC, waits for the recording to finish writing, and opens the
selected match offline. Saved matches also appear under **Show MMC Sumo only**
in **Local replays** by opponent and result.

While watching an offline replay, click **Return to MMC** in the **Esc** menu
or **F8** controls to close the replay and reconnect to Minemen Club. The button
uses your current MMC server entry or one from your saved multiplayer list,
with `mineman.club` as the fallback.

MMC Sumo clips show the arena at the five-second countdown and stop at the
recorded result. Preparation packets stay outside the visible timeline. The
viewer prepares arena terrain sent just after the countdown and can restore
matching terrain from earlier packets in the same capture when MMC reuses a chunk.

The browser lists local capture files and the existing lab/remote archive with
recorded date, server and duration. A `≈` duration is a file-time estimate before
loading; the loaded timeline uses recorded timestamps. Lab entries use their
full-session capture, when still present, to reconstruct the initial world, then
open at the selected match. Raw capture playback begins at the stream's start.

Playback controls provide 0.1x through 4x speed, recorded tick stepping,
±5 seconds and a clickable event timeline. **Space** pauses or resumes.
Hold **Left/Right** to scrub backward or forward at the selected speed. **[**
slows down and **]** speeds up through the same presets shown in F8.
**,** steps back one recorded tick and **.** steps forward. **Shift + Left/Right**
also steps by tick. **Up/Down** switches to the
previous or next Sumo match. Adjacent short matches are preloaded. **O** toggles
replay text. While rewinding, recorded movement plays backward smoothly and
the world is restored to the new time on release. Historical sound, effect and
particle packets are suppressed during that restore. F8 reopens
controls after closing them. Choose **First person**, **Spectator**, or **Follow
target** in F8. Spectator is a detached camera. Use W/A/S/D to fly, the mouse to
look, and E/Q to move up or down. **Recenter** returns the spectator camera
near the recorded player. F5 is a shortcut between first person and spectator.
Following a target is a camera based on **your recorded view**, not the target's
private client POV. Target selection can change during a recording.

In **F8 → Display**, switch each overlay on or off for the selected camera. Your
velocity, velocity and packet log, knockback counter and crosshair flash, reach
fields, range indicators, keystrokes and diagnostics each have a switch. The
choices are saved separately for each camera.
Press **/** in a replay for local commands. **/p reach on** and **/p reach off**
control reach fields, and **/p crosshair on** and **/p crosshair off** control the
in-range brackets. The recoil flash has its own Display switch.

The keystrokes HUD shows W/A/S/D, Space, the bound Sprint input, and both mouse buttons, with separate
one-second click counts. Use **/p keystrokes on** or **/p keystrokes off** for live
play and server replays. Local replays use recorded key and click events at the playhead, so their
CPS stays on the recording's time scale at every playback speed. The replay HUD
shows keystrokes when the capture contains input events. First-person
playback also uses the recorded swing for the native fist animation.
Open **Esc → Edit HUD** or use **/p hud** in live play. In a local replay, choose
**F8 → Edit HUD**. Drag the keystrokes, sprint indicator, Sumo stats and Sumo
cue text. The positions are saved and adapt to the GUI size.

### Sumo Coach

Open **Esc → Sumo Coach** or type **/p sumocoach**. Turn on **Live coaching** in the
lab for a two-line HUD and separate switches for the successful brake flash,
sprint-off sound and note, miss timer sound and note, unclicked opening sound and
note, and silent aim-miss outline. The brake sound button cycles through grouped
pitch, grouped volume, grouped tones, first brake, every brake, and off. Grouped
audio plays when the six-move window ends. The flash and multiplier update on
contact. Volume and all switches are saved with your local settings.

**KB saved** is the estimated reduction in peak backward travel over the first
six moves after a hit. It compares your confirmed brakes with the same recorded
movement and footing with the cuts removed. The match card sums complete,
unobstructed windows and shows how many hits contributed.
**Ideal** on the HUD uses the same comparison with one brake per observed
movement tick when the crosshair selected the opponent and the miss timer
allowed an attack. It keeps the measured inputs, footing and hitbox openings.

The **Brake alert** plays when an on-target sprint-off click would have reduced
peak backward travel by at least half a block. It compares the observed slide
with just that one brake added, keeping the measured movement and later cuts.
It plays when the exchange closes, within six moves in ordinary play.
**Costly missed brakes** on the match card counts affected exchanges and shows
how many followed a W-up move. In **Sounds**, click a sound name to change it
and use **Play** to hear it. Each cue has its own choice, including the three
grouped tones and the aim miss, which starts silent.

**Current** shows the live match counts. **Analysis** lists up to four of the
biggest slides in match order, with observed footing, W timing, and the brake
events within each slide. The timeline can include multiple events such as a
sprint-off contact, an on-target opening without a click, and a later brake.
Each event is timed from the incoming hit. When the recorded motion supports
it, the card estimates how much less you would have slid by fixing that one
moment. Choose **Help** on the card for the terms and how to read the estimates.
A recent hit before a loss is included when measured.
**Where you gained ground** estimates how much farther you would have slid
without your brakes. After a lab match, the card can also match your server hits
to the bot's archived movement and show how it responded.
Lab analyses can include the server sprint flag on fresh accepted hits. Open a
result's **Match analysis** link or type **/p sumoanalysis**. **Recent** keeps up
to 20 completed matches across launches.
Interruptions leave the last completed result available.

An older lab server can provide its sprint totals through the existing local
match archive. Near-edge recovery measures flat floor support roughly 2.5
blocks or less along the push, with one second to regain footing.

MMC summaries use the existing Sumo bookmark detector with **/p remote record on**.
Lab summaries use the match ID and combat boundary from the local practice link.
The three grouped sounds and the live HUD are lab feedback. MMC post-match
summaries use the recorded local view of inputs and movement.

The viewer feeds safe recorded inbound PLAY packets into native Minecraft
handlers using a fake play connection with **no channel or socket**. Both outbound
send methods discard every reply, movement packet, chat and mod payload. JOIN
creates a local client world without Forge's online handshake. Resource-pack
requests, custom channels, disconnect screens and compression negotiation are
not executed. The viewer uses recorded skin IDs and Minecraft's local skin cache.
An older capture with no usable local skin falls back to the default. Playback
suspends recording and live lab coordination and blocks ordinary
attack/use/drop/inventory/hotbar gameplay actions so they cannot edit the
reconstructed world; offline overlays have
their own permission and do not enable any live-remote practice features.

Where available, packets use their recorded **main-thread handler entry time**;
otherwise the viewer explicitly reports wire-time fallback. Recorded pose samples
drive the own-player camera, observed actors and reach readouts. Render frames
interpolate positions and angles between nearby recorded ticks. Teleports
snap to the new position, and hit, packet, and velocity readouts keep their
recorded timestamps. This is client
reconstruction with pose correction, not a new combat simulation or a claim that
vanilla interpolation, animation, particles or lighting are identical between
recording and playback. Pausing holds world time, player poses and rendering
interpolation steady. Particles and animated textures can still advance. The
first-person hand uses the replay camera's view and the recorded swing. New
captures also include the rendered arm lag and skin model when available.

The timeline marks outbound attack **attempts**, inbound S12 velocity vectors
(exact packet shorts divided by 8000), and entity hurt-status events. The HUD
shows observed hurt ticks/elapsed time and time since attack. These are evidence,
not proof of accepted damage or an exact remote-server immunity timer. A nearby
attack→self-S12 interval is labelled **unconfirmed**; it does not establish that
your attack caused the incoming velocity. Matched inventory transaction IDs can
show a measured round trip including server processing. Keepalive response delay
alone is not RTT, and one-way latency is unavailable from a single recording.

Both perspectives show **KB reductions**. A green timeline mark and a brief
first-person crosshair flash indicate paired click samples with measured
horizontal motion scaled to 0.6 of its pre-click value. This cuts X and Z motion
by 40% and leaves Y motion alone. It can happen during the target's server damage
cooldown. The reduction counter tracks cuts while moving backward. **After last hit** groups cuts after the latest
recorded own horizontal velocity application while the own hurt animation is
active and motion still points along the received recoil. Use **Previous KB cut**
and **Next KB cut** to jump between marks. The total counts observed local motion
reductions, including contacts during target immunity.

The **Velocity** HUD displays the latest recorded own X/Y/Z motion in
blocks per tick. It lists S12 velocity,
S27 explosion motion, S08 corrections, S19 hurt, and outgoing C02 attack and C0B
sprint packets. S12 values include horizontal magnitude. The attack entry shows
the recorded local sprint flag at click time and the measured motion change.
Server-applied sprint bonuses require separate server evidence. New recordings
also bracket local jumps and movement steps. Older captures show unexplained
changes at the recorded sample or tick boundary. **Previous change** and
**Next change** step through these observations.

Existing reach fields use recorded positions, suppressing stale own samples
after 250 ms and displaying sample age. Field radius is labelled as an assumed
three blocks when the recording does not establish a custom reach. True reach advantage requires both
client POVs in a linked archive; a remote-only capture explicitly says the
opponent POV/advantage is unavailable. No opponent or authoritative server view
is invented. Missing samples remain missing.

A capture that began after joining, lacks JOIN, or has missing linked parts shows
**MISSING INITIAL WORLD** or another explicit warning. The viewer can display its
recorded poses in an empty reconstruction, but absent chunks/entities cannot be
recovered. Record before joining for a complete world. Packets over the recorder's
2 MiB limit are skipped with a warning; missing chunks are possible. Load bounds
are 128 MiB of packet bytes, 1 GiB inflated input, 10,000 retained pose records,
a 32 MiB encoded-byte estimate for retained poses, and 10,000 linked parts.
These are the defaults for a 512 MiB heap. Packet/inflated-input/pose budgets
scale in rounded 512 MiB heap units up to eight times these values for a normal
2–4 GiB client; the individual 2 MiB packet and 10,000-part caps stay fixed.
These are explicit load errors, never silently truncated scientific playback.
Long housing sessions may still exceed v1 limits; no arbitrary-length playback
is promised. A memory failure is reported without modifying the recording.
Poses retain recorded tick-end samples (or up to 20 Hz view samples when no tick
stream exists); unrelated actors retain only visual fields to bound memory.
The optional independent linked-POV index is bounded to 20,000 samples/128 MiB.
Exceeding a bound stops loading with an explicit message. Capture files are
never modified by playback.

Build/install with `python3 practice-client/build.py --install`, then restart
Minecraft once. This stages the matching module for the next launch and does not
restart the server or change the running human client's module. The offline
verification harness is `practice-client/replay_smoke.py`; it uses one passive
local lobby guest for a pre-PLAY pretend-remote recording, then disconnects itself
and tests that recording plus an existing lab capture entirely offline. The
`--reuse-remote <first gzip>` option repeats the offline checks without connecting.
`--graphical` produces an actual OpenGL screenshot in an isolated offline client,
using existing pinned libraries/natives and no live connection.

Run `python3 practice-client/render_showcase.py` with the local Bow Aim and Sumo
captures available to regenerate the site showcase media. It saves 1440 × 810
MP4s and stills plus smaller GIFs under `build/practice-client/showcase-media/`.
Use `--clip` to regenerate one clip. Both offline renderers reload Minecraft
resources after restoring the OpenGL context to keep the terrain and item textures intact.

## Match event ledger

From the project folder, generate a report and local replay bookmarks with
`python3 match_ledger.py m2636` or with a full MMC Sumo bookmark ID.
`python3 match_ledger.py --list-mmc` lists recent MMC IDs and opponents.
Open the HTML it prints to scrub the round and inspect measured events,
interpretations, and unknowns for each exchange. The JSON export includes
source files and line numbers. Use `--exchange 3` for just one exchange and
its event rows.

In Minecraft, type **/p ledger <match ID>** and click an exchange. The link
opens or seeks the offline recording and pauses 250 ms before the first
event when the clip allows it. The browser report also has a copyable
Minecraft seek command for any selected time. Practice replays use the
configured archive directory. If a live practice connection is open,
the seek waits for you to disconnect. MMC playback uses the existing
disconnect and offline playback flow.

A practice trade is confirmed when reciprocal scoring hits share a server tick.
Other hits or MMC cues within 120 ms can be grouped for review. Same-side
sequences split when the gap exceeds 1.1 s. These grouping rules identify
moments to inspect. MMC's opponent hurt cues and outgoing attack attempts carry
no exact server acceptance, sprint state, or opponent POV. The report labels
first movement order only when it has a matching main-thread S12 application
and recorded travel hook on the same client clock.

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

For the remote recorder, use:

```sh
python3 practice-client/remote_checks.py
python3 practice-client/remote_smoke.py --wait-idle-seconds 180
```

The first test covers local receiver rejection, gzip integrity, retry after
receiver downtime/restart, checksum deduplication and proxy/redirect refusal.
The second uses a separate 512 MiB, low-priority Forge guest in the localhost
lobby, classifies that socket as remote, and checks commands, HUD and packet
counts without starting a match or changing another player.
Its probe is not installed in the human client. An actual external connection
is not used by this test.

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

The practice mod opens no HTTP/WebSocket listener, downloads no code, runs no
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

## Shared headless bot mode

The same `practice-bridge.jar` and `practice-module.jar` run in the human game and
HeadlessMC Forge bots. Ordinary launches always stay in human mode: they never
load a slot's controls, run autonomous aim/movement, or override native reach.
Only the lab launcher supplies the explicit bot role, isolated directory and
slot identity. ToggleSprint yields control to the bot input controller in that
role. No extra bot mod or third-party gameplay plugin is loaded.

The shared bridge captures packets, inputs, logical/render poses and the direct
local view stream. Policies read only their own ordinary client state; the
three-POV diagnostic stream is recording/display data, never a policy oracle.
Archives label the backend/role and record the bridge and actually loaded module
hashes. Bot-local match routing starts capture before delayed gameplay packets
arrive. `runtime/pov/<match>/manifest.json` remains the replay index.

Module/HUD/config edits still hot-reload. Changing native hooks, this bridge's
API, or compiled bot controllers requires restarting the affected Minecraft
process. `python3 lab.py reload-bots` replaces idle bot JVMs; it does not restart
the human game or server. Server instrumentation changes require a lab restart.
See `research/forge-bot-migration-spec.md` and the migration evidence for launch,
validation and rollback details. Legacy bots remain available with
`python3 lab.py serve --bot-backend legacy`.
