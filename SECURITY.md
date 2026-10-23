# Security model

The server is private to this computer. All Minecraft and management listeners
bind to IPv4 loopback. No router changes, public tunnels, RCON, or query service.

## Trusted inputs

1. The existing Java and Python installations.
2. Mojang's official version manifest, client/server jars, libraries, and assets.
3. HeadlessMC LWJGL source at commit
   `c83e908335e0a23552a273143a219ec5600b9be9`.
4. ASM and ASM-tree, pinned Maven Central artifacts.
5. Data-only MCP mappings for Minecraft 1.8.9, from Forge's Maven server.
6. This repository's control, match management, and sandbox code.

The HeadlessMC authentication launcher is not used. Bot processes have synthetic
local identities and no Microsoft account tokens. Use your normal trusted
Minecraft launcher for your own account.

## Build

HeadlessMC is compiled with the standard Java compiler and annotation processing
disabled. Its Lombok annotations are replaced by small, recorded source
transformations (static utility members and explicit constructors). LaunchWrapper
integration is excluded. This avoids executing upstream build scripts, Gradle
plugins, or annotation processors. Upstream MIT attribution is retained.

Downloads are verified against official supplied hashes where available, then
recorded with SHA-256 in `dependencies.lock.json`. Build and launch recheck the
cached dependencies against that committed lock. A hash guarantees the same
bytes on future runs, not that the publisher is trustworthy. No automatic
dependency updates. The offline replays use only embedded data and code.

## Runtime

Before third-party game/agent code is run, the macOS process sandbox must pass
checks for allowed local networking and denied access to an outside canary file,
outside writes, and outbound public networking. Java processes can read the
project and permitted system paths, and write only under the lab runtime. The
test confirms that a canary outside those paths cannot be read or written.
The profile permits only the chosen Java binary to execute child programs.
Processes receive a separate home directory and a clean environment without
inherited account tokens. The match manager is our own Python code.

Mojang's logging configuration and official legacy server Log4j mitigation are
applied explicitly. The local agent additionally disables legacy `JndiLookup`
methods. A controlled loopback probe verified that lookup returned null and
opened zero connections. Old Minecraft includes other old libraries; sandboxing and
loopback-only networking reduce exposure but do not establish absence of bugs.
The sandbox is defense in depth, not a virtual machine or a security audit.

No upstream performance optimization mod is used. Combat/movement methods are
left intact; our hooks inject controls, record observations, restore match
health after real damage, and count accepted hits. This is a small custom agent,
so implementation mistakes can affect experiment validity; telemetry checks and
real client tests are included. There is no proof of exact equivalence to a
graphically rendered client in every scenario.

Loopback restricts access to this computer; it does not authenticate other local
programs. Offline mode is needed for synthetic bot identities. Do not change
the binding or expose these old binaries to a public network. One shared
human latency proxy is intended for a single human player in this version.

## Sources

- https://github.com/headlesshq/headlessmc/tree/c83e908335e0a23552a273143a219ec5600b9be9/headlessmc-lwjgl
- https://www.minecraft.net/en-us/article/important-message--security-vulnerability-java-edition
- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
