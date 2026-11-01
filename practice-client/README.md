# PvP Practice client (Forge 1.8.9)

This is our own small client mod. It includes ToggleSprint and a potion-effect
HUD, plus a reloadable module for future practice displays and measurements.
No third-party HUD mod is required.

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
| `/practice status` | Show reload status and configuration location |
| `/practice sprint on` / `off` | Enable/disable ToggleSprint |
| `/practice potions on` / `off` | Show/hide potion timers |
| `/practice metrics on` / `off` | Show observed speeds, target gap/height, and blocking time |
| `/practice record on` / `off` | Start/stop local client-observation CSV recording |

Settings are in `config/pvp-practice/settings.properties` inside the Minecraft
game directory. Changes reload about once per second while Minecraft is running;
`/practice reload` makes it immediate. Change `hud.rightInset`, `hud.top`,
`hud.color`, `sprint.label`, or `potions.top` to customize the HUD. Invalid
configuration or a broken replacement JAR keeps the last working module.
Reloading resets the module's session counters and toggle state to
`sprint.startEnabled`, and starts a new recording segment if recording is enabled.

Metrics and recording start **off**. Speeds are client-observed position changes
per game tick × 20, not server velocities. The target is the player under the
crosshair, otherwise the nearest player within six blocks. Gap is horizontal
center-to-center distance, **not attack reach**. Blocking time counts sampled
client ticks, not server-confirmed blocked damage. The optional red edge flash
(`feedback.hurtFlash=true`) indicates an observed hurt animation, not a verified
block or a timing verdict. No estimated combat advantage is claimed yet.

Recordings contain timestamps, numeric entity IDs, positions, displacements,
ground/block state, health, and hurt ticks. They remain in
`config/pvp-practice/recordings/`; account tokens and chat are not recorded.
The timestamps support later alignment with our server/bot recordings, but this
first bridge samples client ticks rather than individual network packets.

## Build and live updates

From the repository root:

```sh
python3 practice-client/build.py --install
```

After changing the code in `module/`, rebuild just the reloadable module:

```sh
python3 practice-client/build.py --module-only --install
```

Minecraft picks it up automatically. The stable API accepts immutable snapshots
and returns HUD lines, an optional edge flash, and whether to hold ordinary
Sprint input. Modules can maintain state, calculate practice feedback, and write
local recordings. They also receive client chat/action-bar events for future
server-feedback integration. Modules do not register their own Forge listeners.

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
invalid-reload recovery, CSV shape, and replacement of **actual bytecode in the
same running JVM**. The checks also run under Minecraft's Java 8 runtime.

With the local practice server running, use:

```sh
python3 practice-client/smoke.py
```

This starts a separate headless Forge 1.8.9 client as `PracticeCheck`, verifies
event dispatch, HUD rendering callbacks, normal sprint input, client commands,
and live reload, then exits. It never starts a duel. Test files go under
`runtime/practice-forge-smoke`; a macOS process sandbox restricts filesystem
access and networking to localhost. The smoke test's own Forge probe and
HeadlessMC agent are **not installed** in the user's client.

The installed mod opens no HTTP/WebSocket port, downloads no code, runs no
subprocesses, and reads no account/session data. Only a fixed local module path
is loaded; server messages cannot select or replace executable code. A local
Java module has the client's normal Java permissions: it is **not a sandbox**.
Only put code we built/reviewed into that module path. A separate fresh
classloader enables replacement and releases the old module on successful
reload; malformed replacement code is never made active if loading/configuration
fails. Running code errors release the mod's sprint input and disable the module
until a successful reload.
