# Shared Forge practice mod: headless bot migration

Status: approved for implementation by the user, 2026-11-01. This is an implementation contract, not a claim that migration is already complete.

User clarification (takes precedence over strict legacy parity): **functional equivalence is the target.** Small differences are acceptable when the bots behave substantially the same. Prefer simpler shared code and ordinary Forge integration over elaborate reproduction of incidental legacy timing. Exact action timestamps, callback phase identity and bit-for-bit legacy decisions are not acceptance gates. Preserve real native mechanics, policy intent/settings, bot/human separation and reliable practice/recording/replay behavior; diagnose and document meaningful differences.

## Objective

Run production bot slots as real HeadlessMC Minecraft Java 1.8.9 clients with the same Forge practice bridge and practice module artifacts used by human clients. Share observation, capture, archive, transport, reach mathematics and replay support. Add an explicitly enabled bot controller to that mod. Preserve normal human behavior by default, retain the legacy bot backend for comparison and rollback, and migrate the default backend only after documented validation.

The user authorizes implementation, local builds, tests and eventual deployment. They explicitly requested a subagent so the parent can handle other tasks concurrently. Coordinate deployment with the parent; concurrent agents must not restart or rebuild the live installation independently.

Baseline: `9e5624d` on `codex/headless-pvp-lab`, including sumo, two-second seamless drills, all existing bot policies and all previous practice displays/replays. Do not delete the old source. Preserve a named Git baseline and an executable legacy backend; Git history alone is not an adequate operational rollback.

## Current architecture and integration points

- `lab_runtime.py` supervises the server and eight `LabSlotN` clients, isolated game directories, loopback proxies and the filesystem sandbox. `lab.py` builds the Java 17 server/legacy agent without Gradle or annotation processors.
- `src/lab/Agent.java` transforms pinned obfuscated vanilla methods for bot controls and passive observation. It also supplies HeadlessMC hooks. Server instrumentation is separate in responsibility but currently in the same jar.
- `src/lab/Client.java` owns policy state, client-local observations, target identity checks, normal key inputs, aim, click scheduling and bot lifecycle. Controllers/model classes and science probes have existing unit/native parity tests. Several use Java 17 syntax/APIs.
- `practice-client/shim/practice/bridge/PracticeBridge.java` runs human UI/input glue and installs `PracticeCapture` as the `ObserverHooks` sink. `PracticeTransformer` instruments Forge/SRG methods; the mod compiles for Java 8.
- `PracticeCapture`, `GameObservations`, `PracticeViews`, `ReplayPresentation` and `src/practice` already provide shared capture, replay, mathematics and transport components. Bot equivalents include `BotArchive`, `ArchiveWire`, `FastPov`, `ScienceTrace` and `ReplicationTrace`.
- `PracticeCapture` currently labels archive roles as `human`; this must become explicit metadata. Its match routing is based on server link state; that alone is too late for a bot with artificial ping to capture the entire countdown/entry.
- `PovLink`, `PovArchives`, `PovReplayData`, `ServerReplay`, `ReplayToolbar` and `pov_archive.py` consume the three recordings. Preserve these interfaces or version and adapt them deliberately.
- `practice-client/smoke.py` already runs a real sandboxed headless Java 8 Forge client, with optional OptiFine. Reuse the proven launch path and dependency locks instead of introducing a new launcher or plugin. Its HeadlessAgent shades ASM away from Forge's ASM version.
- The latest native test exercises three seamless drills, two client POVs/four ghosts, replay controls, sumo geometry/equipment and replay cleanup. Evidence: `experiments/sumo-20261101/verification.json`.

## Scope and non-goals

Implement the runtime migration, shared client services, explicit bot role, behavior-comparison instrumentation, regression tests, operational flags, documentation and rollback. Keep `/bots`, `/duel`, `/rematch`, `/drill`, `/matches`, `/watch`, `/replay`, `lab match`, `lab <mode>`, ping persistence and per-duel overrides working.

Do not change the Minecraft/server version, policies, combat rules, reaction settings, default reach/CPS, packet latency semantics, sumo footprint or player UI as part of migration. Current practice overrides (up to 30 CPS and 1–5 block bot reach) are intentional user-controlled features and must remain labeled and scoped to bots. Do not restore obsolete 150 ms/10 CPS restrictions where existing policies explicitly override them. Do not introduce new tuning/evolution experiments to compensate for migration regressions.

Do not build Internet multiplayer transport, remote code loading, a general scripting console, a new replay engine, or a performance optimization project. A remote human can eventually use the common mod, but loopback-only coordination remains the supported deployment in this task. Do not require OptiFine for bots; keep compatibility testing for the human's existing Forge + OptiFine installation.

## Target design

### Explicit role and identity

One bridge jar and one module jar serve both roles. Default role is `human`. Bot mode requires explicit trusted local startup configuration, a validated slot identity and a loopback lab destination. A username or a packet sent by a server must never enable bot controls by itself. A missing/invalid bot config fails closed: release all bot-owned inputs and expose an actionable startup error.

Keep `LabSlotN` identities and existing match orchestration stable. Server-side participant type/slot registration, role in archive manifests and client runtime role remain distinct fields; ordinary human clients do not become scheduler-owned slots. Reject duplicate slot ownership. Report backend, role, slot, build hashes and relevant client/Forge versions in local status and match/archive metadata. Never include authentication tokens.

Human mode must neither instantiate the autonomous controller nor read a default slot's control file. It retains ordinary mouse/keyboard use, ToggleSprint, HUD, hotreload, recording and replay controls. A stale bot control file or saved module settings cannot turn a human client into a bot.

### Single input owner

Separate `human input + ToggleSprint` from `bot input controller`. In bot mode the human ToggleSprint hook must not overwrite sprint/W/block commands. There must be exactly one owner for autonomous forward/strafe/sprint/use/attack/aim decisions, with documented ordering relative to vanilla input polling and native `clickMouse`/`rightClickMouse` processing.

Use normal Minecraft key state/edge inputs and native hit testing. Preserve click attempts during immunity, miss cooldowns, blocking slowdown, release-use packet order, sprint resets and default native reach/occlusion. No direct movement/attack packets, motion edits, health edits, immunity skipping, or privileged server/opponent data in policy inputs. Only the server's already established setup/referee behavior may teleport/reset players.

For explicitly modified bot reach, retain the current narrowly scoped native ray-selection limit override; human mode always passes through the original limit. Missing transformer sites or incompatible signatures must fail visibly, not silently disable reach/input behavior.

Release inputs on idle, match end, target loss, disconnect, respawn, policy error, controller replacement and shutdown. Reset all per-match history/predictors/timers on identity changes. Preserve the native target watchdog and canonical entity checks so a duel cannot attack a stale actor or another match's participant.

### Shared controller logic, native adapter

Prefer a Java 8-compatible policy/controller core, fed by immutable local observation snapshots and returning input intents. Share existing algorithms and numerical helpers between backends where practical; preserve the archived baseline independently so extraction cannot invalidate the comparison.

The Forge adapter supplies mapped native state, appropriate callback placement, input injection and lifecycle. Reuse convenient Forge hooks when they preserve functional behavior; reproduce legacy hook placement only where it matters. Keep server Java 17 classes out of the human mod. Do not ship two complete independent implementations of the policies or capture pipeline. If temporary port duplication is unavoidable during extraction, enumerate it, verify equivalent strategy behavior, and consolidate before calling the migration complete. Semantic differences required by mappings/runtime versions must be documented and tested, not hidden in mechanical rewrites.

Inventory all supported policy kinds and configuration keys, including hold-w, standstill, ordinary/one-tick W-tap, hit-select, Zest variants, blockhit timing, spacing/model candidates, burst/CPS variants and scripted research probes. Preserve their semantics, inputs and existing logs. A policy that cannot be migrated is a cutover blocker, not a silent fallback to hold-w.

### Common observation, capture and archives

Both roles use the same native observation/packet capture implementation, sequence IDs, logical/rendered pose conventions, archive writer, clock fields, bounded queues and integrity footers. Role-specific fields such as bot decisions remain additive events, not a separate recording engine. Preserve existing science/replication probes via passive adapters or explicit compatibility exports; audit every event consumed by research scripts.

There is exactly one packet/pose archive writer per client/source/match. Do not install the legacy bot transformer plus the new Forge transformer over the same methods. Retain needed headless/logging hardening independently. Assert transformer injection counts and observer errors.

Record both entities' logical and render poses, rotations/eye/AABB/border, motion, native input, relevant flags/hurt/sprint/block state, item/equipment, raw PLAY packet bytes and arrival/application order, click attempts/outcomes, policy observations/decisions and lifecycle. Keep login/auth packets excluded. Capture continues through the final exchange and closes with integrity status. Preserve explicit sampling rates, time gaps, dropped events and missing streams.

Match routing must support a trusted bot-control route available before combat start, while humans use the existing server link. Both routes feed the same recorder. Include correct side and role in metadata; record countdown and final transition even under artificial RTT. Do not start two archives when local routing and server state arrive at different times.

Existing recordings and current readers must still open. Either preserve schemas or add explicit versions and backward-compatible readers. Keep logical positions unsmoothed; interpolation is presentation-only. Archive all three POVs in the server-accessible local archive path. Test seek/rewind/slow motion, POV switching, ghosts, reach advantage and combo links using new bot recordings and historical recordings.

### Common local view service

Extract/share `LocalViewStream` publishing and pose sampling rather than retaining bot-only FastPov as a second implementation. Role-specific startup decides whether to publish; the service itself is common and suitable for a future second human. Preserve loopback binding, per-session tokens, stale-session rejection, bounded work and diagnostics.

The diagnostic stream bypasses artificial gameplay RTT as today. It must never supply opponent/server oracle state to the bot controller. Match IDs, source IDs and clocks must prevent cross-match view mixing. Headless clients need a native frame-loop sampling path that actually executes without an OpenGL world render callback. Verify observed delivery/pose age rather than claiming a 1 ms guarantee; preserve debug timing fields.

### Build, launch and security

Keep the existing pinned/hashes-verified dependencies and local build workflow. Reuse the verified installed/cached Forge + Java 8 launch arrangement from the smoke harness. Resolve assets/libraries from trusted local sources, stage required files into the project/cache as needed, and never copy launcher account stores, access tokens, personal settings or the entire home folder.

Forge's ASM and HeadlessMC's ASM must not conflict; retain shading. Preserve the legacy Log4j mitigation and prove it applies to the new Forge launch too. Production bots load only the necessary practice mod and headless shim; never load the smoke test controller or indiscriminately copy every human mod. Humans and bots must use the same practice bridge/module artifact hashes, with separate role-specific config.

Maintain per-slot game directories, clean environments, loopback-only network access, restricted writable directories, Java executable allowlists and process supervision. Adapt the sandbox for the actual Java 8 binary explicitly; do not grant broad home/network access to get Forge running. Run canary read/write/network checks against both JVM backends. Keep public server address `127.0.0.1:25565` and existing latency proxies/order unchanged.

Expose explicit `--bot-backend legacy|forge` (or an equally clear documented setting), and a test-only mixed-slot assignment mechanism so the same server can compare old/new clients. Record the selected backend for every slot/match. Before cutover, production defaults to legacy. After acceptance, default to Forge with a documented one-command legacy restart. No silent fallback in scientific matches: failed Forge launches are errors with diagnostics.

Stable Forge/coremod hook changes require a client process restart. Preserve hotreload of compatible practice-module/config changes. If adding reloadable controller policies is straightforward, use a versioned API and safe input release; it is not required to make every structural change reloadable. State exactly what requires restarting bots vs the user's human game. Never claim an already running human JVM loaded a new bridge jar merely because the file was installed.

## Implementation phases and required checkpoints

1. **Audit/baseline.** Inventory hooks, policies, capture events, lifecycle, current launch versions and dependencies. Save baseline jar/source hashes and representative traces. Understand existing callback placement, especially frame clicks versus ticks, packet application and sprint changes, so accidental regressions can be distinguished from harmless differences. Send the parent a concise architecture/checkpoint report before major implementation.
2. **Role and shared services.** Add default-human/explicit-bot configuration, sole input ownership and common capture/transport routing. Add meaningful negative tests: human never controlled, controller cannot use diagnostic remote state, stale/invalid controls release inputs, no double recording.
3. **Forge bot adapter.** Port/share all supported policies, map necessary native hooks, retain scientific trace compatibility, and launch one isolated Forge slot. Match commands and custom reach/CPS/ping settings remain unchanged.
4. **Parity and integration.** Run the representative tests below; diagnose substantive discrepancies by event/input phase. Do not retune policies to hide a broken port or emulate incidental timing without a demonstrated functional reason. Keep old/new runtime differences explicit. Expand to mixed and eight-slot runs only once one slot is sound.
5. **Cutover preparation.** Provide changes, complete evidence, hashes, resource usage, known limits, exact launch/rollback commands and any human restart requirement. Rebase/merge the latest parent changes before final verification. Ask the parent to reserve a deployment window; no additional user permission is required for this already authorized migration.
6. **Deploy and verify.** Coordinate the live restart at idle/drill reset boundary. Switch the default to Forge only if acceptance criteria pass. Confirm eight healthy Forge bots, human duels, live views and new recordings. Retain legacy source/backend. Record the deployed commit/artifact hashes and rollback path.

## Validation and acceptance

### Component behavior and native input correctness

- Existing controller, click-rate, target-watchdog, model/physics, damage/rule and client math/capture/replay tests pass where affected. Use Java 8 runtime tests for code shipped to Forge, not only Java 17 compilation.
- Use representative timestamped observation/config sequences to compare baseline and migrated policy behavior: intended W-tap/reset logic, click gating/cadence, block timing and state resets. Exact equality is useful when cheap, but differences in precise action timestamps or internal states are acceptable if strategy intent and configured limits hold. Include critical immunity upgrades, missed attacks, block release, target replacement, changing pings, reaction windows and CPS/reach limits.
- Inspect native callback/order boundaries where needed to verify click dispatch, packet application, attack slowdown, W-tap and block activation work correctly. Legacy phase identity is not required. Existing tests of unchanged native physics/reach mathematics should retain their validated tolerances; this clarification permits different control scheduling, not incorrect mechanics or fabricated measurements.
- Never require or claim identical networked match scores: independent JVM/server phases vary. Report functional behavior checks and observed match outcomes separately. Do not attempt to prove statistical equality or emulate exact legacy decisions as a migration requirement.

### Live comparative matrix

Use controlled start positions/kit/configs, stable server tick rate, balanced sides and recorded ping assignments. Cover the categories below with a small representative set, not an exhaustive cross-product. Repeat a condition when needed to understand a meaningful discrepancy; there is no mandatory fixed repeat count for every configuration. Include:

- Representative legacy/legacy, mixed-backend and Forge/Forge hold-w versus W-tap matches, covering zero and asymmetric ping. Balance sides for comparisons that suggest a backend effect. Equal-policy cross-backend or reversed-ping matches are diagnostic follow-ups when needed, not a required full matrix.
- Standstill and a no-click moving target for reach/first-contact and input suppression checks; verify 3.0 reach boundary and at least one explicitly modified reach setup.
- 10 CPS and a representative higher configured rate (20 or 30), including miss cooldown and immunity clicks. Confirm actual native attempt cadence without unauthorized extra swings/attacks.
- `blockhit_timing` versus W-tap in diamond; inspect native blocked-damage events and use/release timing, not only win rate. Include a Zest strafe policy and a model-based policy.
- At least one completed native duel in every mode: boxing, diamond, diamond_box, rod_diamond and sumo. Verify kits, unbreakable items, sumo ring-out/full health, health/damage/block metrics and rod behavior unchanged.
- Capture comparison: packet counts/types/order, missing/drop/error counters, both logical/render pose fields, source/role IDs, first/last timestamps and archive footers. Extra Forge handshake/coordination packets may differ; explicitly whitelist/document them without hiding gameplay differences.

Report scores, first-contact times, accepted hit intervals, click attempts, W/sprint/use transitions, blocks, view validity, observer errors, tick/frame timings and capture coverage. Acceptance requires correct native controls/mechanics, recognizable equivalent strategies, preserved settings and reliable practice/recording/replay features. Small timing or outcome differences are acceptable. Investigate substantial unexplained behavior changes, but do not add compatibility machinery solely for exact legacy matching. Live samples support functional regression detection; they do not prove statistical equality or bit-for-bit execution.

### User-visible and operational checks

- Existing native Forge smoke test with OptiFine passes against Forge bots: normal human controls, HUD/hotreload, low-latency views, range cues, ghosts, POV/replay controls, sums and archive integrity.
- Three seamless mixed drills retain the same native bot entity and arena, with visible two-second countdown; no lobby flash, missing bot, duplicate match or delayed archive route.
- `/duel`, `/rematch` and `/stop`, persistent human ping, bot overrides, `/watch`, bot-vs-bot command assignment, disconnect/reconnect, death/respawn, stale target and process restart all work with correct role ownership.
- Replay new Forge/Forge, human/Forge and historical legacy matches. Exercise both client POVs/server POV and all four client ghosts with slow/fast playback and seeks. No double actors/writers or absent source silently called complete.
- Eight-slot smoke/soak with at least two concurrent matches. Measure memory/CPU, server tick timing, client tick/frame rate and recording gaps. Efficiency is secondary, but starvation affecting results is a blocker. Keep an explicit lower concurrency option if this laptop needs it; never silently run scientifically invalid overloaded games.
- Sandbox and legacy logging probes pass. Restart/rollback is exercised, not only documented. Production restart never leaves duplicate managers or bot identities.

## Evidence and deliverables

Commit implementation/tests/docs in reviewable steps. Keep `research/forge-bot-migration-spec.md` updated with justified deviations; do not weaken acceptance silently. Add an evidence directory with machine-readable configuration/results, runtime/source/artifact hashes, native checks, representative trace comparisons, archive integrity reports, resource/timing measurements and known limitations. Do not commit full noisy session/auth logs or huge recordings unnecessarily; reference local recording IDs and retain concise reproducible analyses.

Final handoff must distinguish implemented, tested, deployed and pending-human-restart states. Include exact commands to start Forge, start legacy, select test backends, duel bots, run the functional comparison checks and roll back. Mention any features that remain legacy-only as blockers rather than declaring the migration done.

## Concurrent-work ownership

The migration subagent works in its own managed Git worktree/branch. Never modify the parent's checkout, running build jars, human `.minecraft`, production bot directories or live server without a coordinated integration message. The parent remains available for unrelated tasks. Contact the parent before shared-file integration (`practice-client` bridge/core/transformer, `src/practice`, `lab_runtime.py`, `lab.py`, `src/lab` control/observation/replay code).

Use an isolated runtime/config/world with distinct loopback ports for migration tests when possible. Do not symlink the production runtime or mutable build output into the worktree. Verified immutable dependency caches may be copied/reused with checksums, but avoid concurrent writes and sandbox path assumptions. If an isolated server is needed and memory limits prevent running both, coordinate a bounded test window rather than stopping the live server unannounced.

The subagent owns implementation through validation; parent coordinates review/integration/deployment alongside other work. Send milestone updates and actionable blockers. Do not create additional agents or user-owned threads without coordination. User approvals already given cover this task; only ask for genuinely unavailable manual input or credentials, and do not invent a new approval requirement for routine code/build/test changes.
