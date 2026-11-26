# Curatorium Essentials

A **server-only** NeoForge 21.1.265 (Minecraft 1.21.1) port of [EssentialsX](https://github.com/EssentialsX/Essentials)
for the Creatorium server: its commands, chat, spawn, economy and Discord features.

- **Server-only**: players don't need the mod. It registers no items, blocks, menus or network channels.
- **Permissions** come from [LuckPerms](https://luckperms.net/) when it's installed, with the same node names as EssentialsX. Without LuckPerms, nodes fall back to op levels. See [luckperms-defaults.md](luckperms-defaults.md).
- **Money** is [Create: Numismatics](https://modrinth.com/mod/create-numismatics) coins carried in the player's inventory. There are no virtual balances: prices take coins from the inventory and payouts give coins into it.

What's done and what's next: [PLAN.md](PLAN.md).

## Status

**0.1.0, Phase 0 (foundation).** The only command is `/essentials` (`/ess`): `version` and `reload`.
Everything else arrives in later phases.

## Installing

Put the jar in the server's `mods` folder, with LuckPerms for NeoForge alongside it. Numismatics
is optional; without it, the economy is off. Nothing goes in the client pack.

## Files

| File | What it is |
|---|---|
| `config/curatorium_essentials/config.toml` | Settings: disabled commands, which vanilla commands to replace, cooldowns, command costs. Reloads by itself when saved. |
| `config/curatorium_essentials/messages.properties` | Every message the mod shows, with `&` colour codes. Run `/essentials reload` after editing. |
| `<world>/curatorium_essentials/` | Player data (`userdata/<uuid>.json`) and, in later phases, warps, jails, kits and so on. |
| `logs/curatorium_essentials-economy.log` | Every coin taken or given by the mod. |

## Building

You need a Java 21 JDK.

```
gradlew.bat build
```

The jar appears in `build\libs\`. It compiles against the Numismatics and Create jars in the
folder set by `server_mods_dir` in `gradle.properties`, and against the LuckPerms API from Maven
Central.

`gradlew.bat runServer` starts a dev server in `run/`. Put LuckPerms (and anything else you want
to test with) in `run/mods`.

## License

GPL-3.0 ([LICENSE](LICENSE)). This mod ports features and behaviour from EssentialsX, which is
GPL-3.0, by the EssentialsX team and contributors.
