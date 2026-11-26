# Curatorium Essentials

A **server-only** NeoForge 21.1.265 (Minecraft 1.21.1) port of [EssentialsX](https://github.com/EssentialsX/Essentials)
for the Creatorium server: its commands, chat, spawn, economy and Discord features.

- **Server-only**: players don't need the mod. It registers no items, blocks, menus or network channels.
- **Permissions** come from [LuckPerms](https://luckperms.net/) when it's installed, with the same node names as EssentialsX. Without LuckPerms, nodes fall back to op levels. See [luckperms-defaults.md](luckperms-defaults.md).
- **Money** is [Create: Numismatics](https://modrinth.com/mod/create-numismatics) coins carried in the player's inventory. There are no virtual balances: prices take coins from the inventory and payouts give coins into it.

What's done and what's next: [PLAN.md](PLAN.md).

## Status

**0.4.0, Phase 3 (teleports and spawn).** Direct teleports (vanilla-compatible `/tp`), requests with
clickable accept/deny, `/back` (also after death), homes and warps with GUIs, `/spawn` per LuckPerms
group with respawning at spawn, random teleport, warmups that cancel on moving or damage, safe
landing spots, and no teleporting in or out of a Curatorium Claims claim under siege. Every
teleport is op-only by default.

**0.3.0, Phase 2 (moderation and admin).** Bans (in the vanilla ban lists, so `/pardon` still
works), mutes, jails, vanish, god/fly/gamemode/heal and the other player commands, `/invsee`,
item commands, power tools, portable workstations, time and weather (also per player), world
tools and kits with a GUI picker. Phase 1 brought EssentialsX Chat, nicknames, AFK, private
messages, mail and the info commands. Teleports, the economy and Discord arrive in later phases.

## Installing

Put the jar in the server's `mods` folder, with **LuckPerms for NeoForge 5.4.150** alongside it
(not 5.4.140, which kicks every player on join on 1.21.1; see [luckperms-defaults.md](luckperms-defaults.md)). Numismatics
is optional; without it, the economy is off. Nothing goes in the client pack.

## Files

| File | What it is |
|---|---|
| `config/curatorium_essentials/config.toml` | Settings: disabled commands, which vanilla commands to replace, cooldowns, command costs. Reloads by itself when saved. |
| `config/curatorium_essentials/config.toml` chat and names sections | Chat format, per-group formats, local chat radius, nickname prefix and length, tab list, AFK timings. |
| `config/curatorium_essentials/messages.properties` | Every message the mod shows, with `&` colour codes. Run `/essentials reload` after editing. |
| `config/curatorium_essentials/motd.txt`, `rules.txt`, `info.txt`, `custom.txt` | Text for `/motd` (also shown on join), `/rules`, `/info` and `/customtext`. Lines starting with `#` start a chapter. |
| `config/curatorium_essentials/config.toml` moderation, jail, items, world, kits sections | Mute-blocked commands, jail rules (allowed commands, radius, online-time sentences), item blacklist, `/clearinventory` confirmation, `/spawnmob` limit, the `/backup` script, the first-join kit. |
| `config/curatorium_essentials/config.toml` teleport, homes, warps, spawn, tpr sections | Warmup, cooldown, safe spots, request timeout, home limits per group, per-warp permissions, first-join and respawn spawn, random-teleport range. |
| `<world>/curatorium_essentials/` | Player data (`userdata/<uuid>.json`, homes included), `jails.json`, `kits.json`, `warps.json`, `spawns.json`, `tpr.json`. |
| `logs/curatorium_essentials-economy.log` | Every coin taken or given by the mod. |

## Building

You need a Java 21 JDK.

```
./gradlew build
```

The jar appears in `build/libs/`. It compiles against the Numismatics and Create jars in the
folder set by `server_mods_dir` in `gradle.properties`, and against the LuckPerms API from Maven
Central.

`./gradlew runServer` starts a dev server in `run/`. Put LuckPerms (and anything else you want
to test with) in `run/mods`.

The mod uses a few small mixins (server-side only) for vanish: hiding a vanished player's
entity, tab-list entry and join/quit messages.

## License

GPL-3.0 ([LICENSE](LICENSE)). This mod ports features and behaviour from EssentialsX, which is
GPL-3.0, by the EssentialsX team and contributors.
