# HGAuto 1.2.0 — Java 25 build target

HGAuto is an automation add-on for **Paper 1.21.11** and **HungerGameSSS v4.5.9** (the release you confirmed works on your server). HungerGameSSS remains in charge of the actual gameplay: countdown, cage release, loot, custom procedural dungeons, feast, deathmatch, winner handling, and its arena reset system.

## Commands

Player commands:

- `/br play solo` — join/create the solo queue (single command suitable for an NPC)
- `/br play duo` — join/create the duo queue (single command suitable for an NPC)
- `/br play squad` — join/create the squad queue (single command suitable for an NPC)
- Short aliases `/solo`, `/duo`, and `/squad` remain available
- `/br join [solo|duo|squad]` — explicit join command
- `/br leave` — leave the queue/current HungerGameSSS arena
- `/br status` — show current match details
- `/team create <name>`, `/team invite <player>`, `/team join <name>`, `/team leave`, `/team open`, `/team disband` — HungerGameSSS's native team commands. HGAuto adds a mode-aware team-size guard for teams created while a match mode is selected.

Admin commands (permission `hgauto.admin`, default OP):

- `/br setlobby` — stand at the desired hub/post-match location first; run this once after installing
- `/br start` — force start the active waiting match if its minimum player count is met
- `/br cancel` — cancel a waiting match (a running match is deliberately not force-deleted)
- `/br reload` — reload HGAuto's config

## Installation

1. Use Paper **1.21.11** with **Java 25**.
2. Install HungerGameSSS v4.5.9, which you reported works on your server. HGAuto uses the documented `/hg create`, `/hg setcornucopia`, `/hg addspawn`, `/hg join`, and `/hg start` command interface.
3. Put `target/hgauto-1.2.0.jar` in `plugins/` alongside HungerGameSSS.
4. Start the server, then stand in the separate lobby world and run `/br setlobby`.
5. Configure NPC click actions as player commands: `/br play solo`, `/br play duo`, or `/br play squad`. These are single-line commands; no command chaining is needed.

## What happens when the first player joins

HGAuto creates a new normal world using a fresh random seed and a unique world name; temporarily grants the initiating player only the HungerGameSSS admin permission needed during setup; runs `/hg create`, `/hg setcornucopia <arena> classic`, and `/hg addspawn` at configurable points around the centre; returns the player to their original location; and joins them using `/hg join <arena>`. The Cornucopia is centred at block X/Z 0,0. When a queue reaches that mode's minimum player count, HGAuto invokes `/hg start <arena>` through the server console after the configured short delay.

Only **one active match** is managed at a time. Players choosing another mode while a match is waiting/running are kept in a next-match queue. The manager is intentionally stored as one `MatchSession`; a future multi-match version can replace that with a map of sessions.

HGAuto creates 24 spawn points by default because the published HungerGameSSS setup guide documents registering 2–24 spawn points and its default arena maximum is 24. Do not set this higher until you have verified that your installed HungerGameSSS build and its `max-players` config support it.

## Configuration

Edit `plugins/HGAuto/config.yml`:

- `cornucopia-theme`: `random` by default. Each newly created match independently chooses one of the 13 themes supported by HungerGameSSS v4.5.9: `classic`, `ultimate`, `speedsilver`, `ancient`, `void`, `royal`, `industrial`, `modern`, `crystal`, `jungle`, `end`, `nether`, or `grand`. Set a specific theme instead of `random` to force it.
- `spawn-points`: number of `/hg addspawn` points.
- `spawn-radius`: distance of player spawn points from centre.
- `max-players`: HGAuto queue cap; should match the arena cap in HungerGameSSS.
- `launch-delay-seconds`: delay before HGAuto sends `/hg start` once enough players have joined.
- `modes.solo.min-players`, `modes.duo.min-players`, `modes.squad.min-players`: queue thresholds (defaults 2, 4, 8).
- `end-detection.*`: fallback detection based on the game world remaining empty after a match. This expects HungerGameSSS to send participants back to its configured post-match lobby at match end.
- `world-unload-delay-seconds`: delay before unloading the completed world. World folders are retained intentionally to avoid deleting data while HungerGameSSS may still be resetting structures. Since each match gets a new unique world name and seed, the next match still gets a fresh world.

## GrimAC

Install [GrimAC from its official Modrinth page](https://modrinth.com/plugin/grimac) as a separate plugin; don't shade/bundle its jar into HGAuto. Choose the **Bukkit/Paper** build whose changelog explicitly includes Minecraft **1.21.11**, put it in `plugins/`, restart, and check `/grim version` and the server console. Configure GrimAC separately for your server's permissions and alert/mitigation preferences. HGAuto declares GrimAC as an optional soft dependency but does not patch or modify GrimAC's checks.

## Build from source

Requires Maven and **JDK 25**:

```bash
mvn package
```

The Paper API is `provided`; it is not included in the plugin jar. Source is under `src/main/java`, and plugin resources are under `src/main/resources`.

## Important test notes

This source revision was prepared without a live Paper server or the HungerGameSSS v4.5.9 jar in the build environment, so runtime integration (especially command return values, world setup, and match-end reset timing) still needs a test server before production. This Java 25-targeted source revision was prepared without a live Paper server or the HungerGameSSS v4.5.9 jar in the build environment. Compilation must be run with JDK 25 and Maven in an environment that can resolve the Paper API dependency. Do not use the old 1.0.0 JAR expecting these changes. Start with a backup and a few test accounts. If `/hg create`, `/hg setcornucopia`, `/hg addspawn`, or `/hg start` changed in your specific HungerGameSSS build, check its console log before running publicly. The add-on deliberately does not delete world folders automatically.
