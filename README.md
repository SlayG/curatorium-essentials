# Curatorium Essentials

A **server-only** NeoForge 21.1.265 (Minecraft 1.21.1) port of [EssentialsX](https://github.com/EssentialsX/Essentials)
for the Creatorium server: its commands, chat, spawn, economy and Discord features.

- **Server-only**: players don't need the mod. It registers no items, blocks, menus or network channels.
- **Permissions** come from [LuckPerms](https://luckperms.net/) when it's installed, with the same node names as EssentialsX. Without LuckPerms, nodes fall back to op levels. See [luckperms-defaults.md](luckperms-defaults.md).
- **Money** is [Create: Numismatics](https://modrinth.com/mod/create-numismatics) coins carried in the player's inventory. There are no virtual balances: prices take coins from the inventory and payouts give coins into it.

What's done and what's next: [PLAN.md](PLAN.md).

## Status

**0.6.0, Phase 5 (Discord).** A Discord bot in the server: two-way chat relay (player heads as
webhook avatars), join/leave/death/advancement/start/stop and moderation messages routed per type,
a staff channel for `/helpop`, a console channel streaming the log and taking commands from
chosen roles, the slash commands `/list`, `/msg` and `/execute`, and `/discord` and
`/discordbroadcast` in game. It needs a bot of your own; see [Discord setup](#discord-setup).

**0.5.0, Phase 4 (economy).** Money is the Numismatics coins players carry: `/balance`, `/pay`
(with change, and held until the next login for offline players), `/eco give|take`, kit and
command prices, starting coins, and EssentialsX sign shops on vanilla signs (`[Buy]`, `[Sell]`, `[Trade]`, `[Kit]`, `[Warp]`, `[Heal]` and more).

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
| `config/curatorium_essentials/config.toml` economy section | Ender chest counting, starting coins, `/pay` confirmation and minimum, the shop-sign colour. |
| `config/curatorium_essentials/discord.toml` | The Discord bot: token, server and channel IDs, where each message type goes, webhook and avatar, command roles, status, invite link. Restarts the bot by itself when the token changes. Keep it private. |
| `<world>/curatorium_essentials/` | Player data (`userdata/<uuid>.json`, homes and held coins included), `jails.json`, `kits.json`, `warps.json`, `spawns.json`, `tpr.json`. |
| `logs/curatorium_essentials-economy.log` | Every coin taken or given by the mod. |

## Discord setup

1. Create an application at https://discord.com/developers/applications and add a bot. On the
   **Bot** page, turn on the **Message Content Intent** and copy the token (Reset Token).
2. Invite it (OAuth2 → URL Generator) with the `bot` and `applications.commands` scopes and the
   View Channels, Send Messages, Read Message History, Embed Links and Manage Webhooks permissions.
   Private channels and categories need the bot added to them too.
3. In `config/curatorium_essentials/discord.toml` set `enabled = true`, the `token` (or the
   `CURATORIUM_DISCORD_TOKEN` environment variable), your server ID as `guild`, and the chat,
   staff and console channel IDs (Developer Mode on, right-click, Copy ID). Add role IDs to
   `commandRoles` to let those roles run commands. Saving the file starts the bot.

Use text channels rather than threads: Discord doesn't let webhook messages into threads, and it
archives quiet ones. The console channel shows players' IP addresses, so keep it staff-only.

## Building

You need a Java 21 JDK.

```
./gradlew build
```

The jar appears in `build/libs/` (the `-slim` one is without JDA and won't run Discord). It compiles against the Numismatics and Create jars in the
folder set by `server_mods_dir` in `gradle.properties`, and against the LuckPerms API from Maven
Central.

`./gradlew runServer` starts a dev server in `run/`. Put LuckPerms (and anything else you want
to test with) in `run/mods`.

The mod uses a few small mixins (server-side only) for vanish: hiding a vanished player's
entity, tab-list entry and join/quit messages.

## License

GPL-3.0 ([LICENSE](LICENSE)). This mod ports features and behaviour from EssentialsX, which is
GPL-3.0, by the EssentialsX team and contributors.
