# Forge bot operations

Implementation is on `codex/forge-bot-migration`. Native acceptance and parent-coordinated cutover are in progress; these commands do not claim that production already migrated. The legacy source baseline remains `codex/legacy-bots-before-forge` (`9e5624d`), and `--bot-backend legacy` remains executable from the new code.

## Build and run

```sh
python3 lab.py prepare
python3 lab.py build
python3 practice-client/build.py
python3 forge_runtime.py verify
python3 lab.py serve --bot-backend forge
```

Forge dependencies are enumerated with SHA-256 hashes in `practice-client/forge-runtime.lock.json`. The pinned installed Forge libraries and the official launcher's Java 8 runtime must exist. Missing/changed libraries or a failed sandbox/logging check stop startup; there is no fallback. Java 17 compiles shared sources with `--release 8`; the Forge JVM runs Java 8. Server instrumentation continues on Java 17.

`serve` builds the agent and, for Forge slots, the practice bridge/module before launching. HeadlessMC's graphics agent uses shaded ASM and the logging hardener only; it never installs the legacy gameplay transformer on Forge. Bots load exactly `practice-bridge.jar`, not arbitrary files from a human mods directory. The native game connects offline to a loopback proxy using its isolated `LabSlotN` identity.

Human Forge clients install the same artifacts with `python3 practice-client/build.py --install` from the production checkout. This installation changes files but does not reload a running JVM's bridge. Preserve the user's settings and restart their game once for a new bridge. Human mode is the default even when old lab-related JVM properties or saved configurations exist; only `practice.role=bot` with valid slot/directory/identity enables control. Human ToggleSprint never writes a bot's sprint input.

## Isolation and comparisons

Use another checkout with its own copied, hash-verified immutable dependencies and its own runtime. Do not share mutable build/runtime directories or use the live world's files for an isolated test.

```sh
python3 lab.py serve --bot-slots 2 --bot-backend forge --port-offset 1000 --frame-sleep-ms 1
python3 lab.py serve --bot-slots 2 --slot-backends forge,legacy --port-offset 1000
```

These alternatives expose the isolated human listener at `127.0.0.1:26565`, underlying server at `26566`, observer proxy at `26569`, and slots from `26570`. Do not run both commands in one checkout: an exclusive manager lock prevents that. Every Forge process additionally locks its slot directory. One to eight slots are supported. All slots must connect and report a fresh native heartbeat with their requested backend and zero policy errors before startup is called healthy.

Normal in-game `/duel`, `/rematch`, `/drill`, `/watch`, `/replay`, `/ping` and server `lab match` commands retain their existing meanings. Backends are assigned to slots, not policy names. Optional per-duel CPS/reach overrides remain explicit practice settings; default native reach is 3.0. Policies use only locally observed game state, never the separately streamed other-client/server views.

## Reload and rollback

Compatible module/config changes retain the existing local hot-reload workflow. Bot policy property files reload through existing match commands. Compiled controller/bridge/native-hook changes need a bot JVM restart:

```sh
python3 lab.py reload-bots
```

Only idle slots restart; active matches keep their running code. Wait for completion before measuring a new cohort. Server instrumentation changes need a complete lab restart. Changing the backend needs a complete lab restart too:

```sh
python3 lab.py stop
# Wait for manager.json to disappear and the launcher to exit.
python3 lab.py serve --bot-backend legacy
```

Reversing the final command to `--bot-backend forge` restores Forge. Recording formats/readers retain legacy support. No git reset, file deletion or human-game reinstall is needed for backend rollback.

## Evidence and checks

```sh
python3 forge_runtime.py verify
python3 practice-client/smoke.py --optifine --port 26565
python3 tests/forge_migration_report.py m0001 m0002 --output experiments/forge-migration-20261101/report.json
```

The smoke uses a separate sandboxed human-role Forge client and its own game directory. It tests ordinary human input, hotreload, direct views, same-entity two-second drills, replay/ghosts/seek/slow motion and sumo. It does not operate the user's real client.

The common archive writes one events stream and one pose index per client/source/match, with role/backend/build identity, integrity footer and drop counts. PLAY packets only are recorded; login/authentication packets remain excluded. The high-rate stream is diagnostic, bypasses artificial gameplay RTT, and never bypasses the normal gameplay connection for bot actions. Archives remain in `runtime/pov/<match>`, with standard replay files/results unchanged.

Independent client/server phases and Java 8 versus Java 17 scheduling can change scores and exact action times. Acceptance checks native mechanics, policy intent, input bounds and reliable practice/capture behavior rather than identical networked scores. Resource measurements and the completed native acceptance matrix live in `experiments/forge-migration-20261101`; use its actual tested concurrency rather than extrapolating legacy measurements.
