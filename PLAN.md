# Curatorium Essentials — plan

A NeoForge 21.1.265 (Minecraft 1.21.1) **server-only** mod that brings EssentialsX's features
to the Creatorium server: core commands, EssentialsX Chat, Spawn, the economy (on Create:
Numismatics instead of Vault), Discord and Discord Link.

- Mod id `curatorium_essentials`, folder `Creatorium-Mods/curatorium-essentials`, group `dev.curatorium.essentials`.
- License **GPL-3.0**: it ports code and behaviour from EssentialsX (GPL-3.0), and credits it in the README and the mod's description.
- Source of truth for features: the EssentialsX `2.x` branch (https://github.com/EssentialsX/Essentials).
- Each phase below is its own release (0.1.0, 0.2.0, …), tested on a local server before it goes live.

## Decisions already made

| Topic | Decision |
|---|---|
| Permissions | **LuckPerms for NeoForge** goes on the server. The mod checks NeoForge permission nodes (`essentials.<command>`, `essentials.<command>.others`, …), matching EssentialsX's node names so its docs still apply. |
| Licensing | GPL-3.0, public repo on the user's GitHub. |
| Teleport commands | **Op-only by default**: they need permission level 2 unless LuckPerms grants the node. Regular players don't get them. |
| Vault | Replaced by **physical Numismatics coins** in the player's inventory. No virtual balances and no Numismatics bank accounts: every price takes coins from the inventory, every payout gives coins into it. |
| Protect, AntiBuild | **Not included.** The server handles protection itself (Curatorium Claims). |
| GeoIP, XMPP | Not included (GeoIP needs an outside database licence, XMPP is effectively dead). |
| Server-only | Clients never need the mod (details below). |

## Still open (answer before the phase that needs it)

1. **Do homes, warps and `/spawn` count as "teleport"?** The plan treats them as op-only too, along with `/back`, `/top`, `/tpr`. If players should keep `/spawn` (or homes), that's one line in the default permission setup.
2. **Paying offline players (Phase 4).** With physical coins only, `/pay` and `/eco give` need the recipient online, since the coins go into their inventory. Default: refuse if they're offline. Alternative: hold the coins and hand them over at their next login.
3. **`/balancetop` (Phase 4).** Only coins a player carries can be counted, not coins in chests. Default: rank online players by carried coins, plus each offline player's carried coins at their last logout, labelled "carried coins". Alternative: leave `/balancetop` out.
4. **Ender chest (Phase 4).** Default: coins in the ender chest are not counted or spent. Can be switched on in config.
5. **Discord bot.** Needs a bot application on the Discord developer portal and a token pasted into the config (the user does this; the token never goes in chat or the repo).

## How server-only works here

Clients join without the mod as long as it never sends them anything a vanilla client can't
read. So the mod:

- registers **no** items, blocks, entities, menus, sounds or custom network packets;
- uses only **vanilla command argument types** (custom ones break unmodded clients' command trees);
- builds every GUI (kits, warps, `/invsee`, `/anvil`, …) from vanilla menu types;
- sends all text as server-side messages from its own `messages` file (clients don't have its language file), with colours via `&` codes and MiniMessage-style tags in config;
- declares itself server-only in `neoforge.mods.toml` (`displayTest = "IGNORE_SERVER_VERSION"`).

## Foundation (Phase 0 — 0.1.0) — done

Tested on the dev server with LuckPerms 5.4.140, from the console over RCON: the mod loads,
`/essentials` (and its aliases) works, messages can be overridden and fall back to the built-in
text, config is generated, and data is saved on shutdown. Not tested yet (needs a player in
game): LuckPerms checks for a player, cooldowns, command costs and the coin wallet, and
replacing vanilla commands. These get tested with Phase 1.

- Gradle project copied from `curatorium-exchange`'s setup; compiles against `C:/Creatorium/Mod Repo` (Numismatics) and the LuckPerms API from Maven (`net.luckperms:api`, compile-only).
- LuckPerms for NeoForge goes in `Mod Repo` for local testing (only needed to run, not to build). Use 5.4.150; see Phase 1.
- **Data**: per-player JSON at `<world>/curatorium_essentials/userdata/<uuid>.json` (homes, nick, mail, ignores, mute/jail state, last location, playtime, flags); `warps/`, `jails.json`, `kits.json`, `worth.json`, `spawn.json`. Saved asynchronously, flushed on stop.
- **Config**: `config/curatorium_essentials/` — `config.toml` (mirrors EssentialsX `config.yml` sections), `chat.toml`, `economy.toml`, `discord.toml`, `messages.properties`, `motd.txt`, `rules.txt`, `info.txt`.
- **Permission layer**: registers every node with the NeoForge PermissionAPI, with a default (op level or everyone). LuckPerms then overrides per group. Ships a `luckperms-defaults.md` with the commands to set up `default` and `staff` groups.
- **Shared services**: cooldowns and warmups, command costs, player lookup (online/offline, by nick), user cache, `/essentials reload`, `/essentials version`.

## Phase 1 — Chat and messaging (0.2.0) — done

Tested on the dev server with three bot players (a small Python client for 1.21.1) and
LuckPerms 5.4.150. Everything below works, including replacing vanilla `/msg`, `/tell`, `/w`,
`/me`, `/list` and `/help`, LuckPerms prefixes in chat, a per-group chat format, local chat
with spy, shouting and questions, auto-AFK, cooldowns, and nicknames that persist across
reconnects. Not tested yet: the AFK kick, clickable links and the tab list as a real client
draws them, `/mail sendall`, and command costs (Numismatics isn't on the dev server).

- **LuckPerms version.** LuckPerms **5.4.140**, the only build Modrinth lists for 1.21.1, kicks
  every player on join on NeoForge 1.21.1 ("Invalid player data"; LuckPerms issues #3963, #4235,
  #4259, #4335). **5.4.150** (labelled 1.21.4) works on 1.21.1 and is what the server should
  use. A known side effect from those threads is a warning in the LuckPerms web editor's context
  suggestions.
- **Above-head names** stay the real name: that needs a client mod. Nicknames show everywhere
  else (chat, join/leave and death messages, `/msg`, tab list).
- **Offline lookups** (`/seen`, `/mail send`, `/ignore`, `/playtime`) only find players who have
  joined this server (from `usercache.json` and the mod's own player files), so they never
  stall the server on a Mojang lookup.

EssentialsX Chat:
- Chat format per LuckPerms group, using `{DISPLAYNAME}`, `{PREFIX}`, `{SUFFIX}`, `{GROUP}`, `{WORLDNAME}`, `{MESSAGE}`; colour/format permission per player.
- Local chat with a radius, `!` prefix to shout, `?` for question, `/toggleshout`.
- Nicknames and prefixes also shown in the tab list.
- Works with NoChatReports (both send chat as server messages).

Messaging and info commands: `msg`, `r`, `rtoggle`, `msgtoggle`, `socialspy`, `ignore`, `mail`, `me`, `helpop`, `broadcast`, `broadcastworld`, `afk` (with auto-AFK and kick), `nick`, `realname`, `list`, `motd`, `rules`, `info`/`customtext`, `help`, `ping`, `playtime`, `seen`, `whois`, `getpos`, `depth`, `compass`, `near`.

## Phase 2 — Moderation and admin (0.3.0)

- Bans and mutes: `ban`, `tempban`, `banip`, `tempbanip`, `unban`, `unbanip`, `kick`, `kickall`, `mute` (timed, with reason); uses the vanilla ban list so `/pardon` still works.
- Jails: `setjail`, `deljail`, `jails`, `togglejail`, `jailedplayers` — jailed players are held in place, can't use commands, timer counts online or real time.
- Player state: `vanish` (hidden from tab list, join/quit messages and other players), `god`, `fly`, `gamemode`, `heal`, `feed`, `speed`, `exp`, `kill`, `suicide`, `rest`, `burn`, `sudo`.
- Inventory and items: `invsee`, `enderchest`, `clearinventory` (+ confirm toggle), `give`, `item`, `more`, `itemdb`, `itemname`, `itemlore`, `enchant`, `repair`, `hat`, `condense`, `unlimited`, `skull`, `book`, `recipe`, `disposal`, `powertool`, `powertoollist`, `powertooltoggle`, `editsign`.
- Portable workstations (vanilla menus that don't need the block): `workbench`, `anvil`, `cartographytable`, `grindstone`, `loom`, `smithingtable`, `stonecutter`.
- World: `time`, `ptime`, `weather`, `pweather`, `thunder`, `lightning`, `remove` (entities by type and radius), `spawnmob`, `spawner`, `tree`, `bigtree`, `gc` (TPS, memory, loaded chunks), `backup` (runs a configured script).
- Fun (all op-only by default): `beezooka`, `kittycannon`, `fireball`, `firework`, `nuke`, `antioch`, `ice`, `break`.
- Kits: `kit`, `createkit`, `delkit`, `showkit`, `kitreset`; GUI kit picker; per-kit cooldown, one-time kits, first-join kit. Costs arrive in Phase 4.

## Phase 3 — Teleport and Spawn (0.4.0) — op-only by default

- Requests: `tpa`, `tpahere`, `tpaall`, `tpaccept`, `tpdeny`, `tpacancel`, `tpauto`, `tptoggle`.
- Direct: `tp`, `tphere`, `tpall`, `tpo`, `tpohere`, `tppos`, `tpoffline`, `top`, `bottom`, `jump`, `world`, `back` (also after death).
- Homes: `sethome`, `home`, `delhome`, `renamehome`, max homes per LuckPerms group; GUI list. Warps: `setwarp`, `warp`, `delwarp`, `warpinfo`, per-warp permission.
- Random teleport: `tpr`, `settpr` (radius and centre per dimension, avoids water/lava, respects the world border).
- Spawn module: `setspawn`, `spawn`, per-group spawns, new-player spawn, respawn-at-spawn option.
- Teleport warmup (cancel on move/damage), cooldown, and safe-location checks.
- Safety hook to Curatorium Claims: block teleports into or out of a claim under siege, even for op-granted users, unless they have `essentials.teleport.bypass-siege` (only matters if teleport is ever opened up).

## Phase 4 — Economy on physical Numismatics coins (0.5.0)

Money is only the coin items players carry: spur = 1, bevel = 8, sprocket = 16, cog = 64,
crown = 512, sun = 4096 (spurs). The mod keeps no balances of its own and doesn't touch
Numismatics bank accounts.

- **One wallet class** does all of it, built the same way as Curatorium Exchange's `Coins`:
  - *count*: total value of coins in the main inventory and offhand;
  - *take*: checks first and changes nothing if the player can't afford it; spends the smallest coins first and gives change in the fewest coins (e.g. paying 3 spurs with a cog returns 61 spurs' worth of change);
  - *give*: the fewest coins possible, dropping at the player's feet whatever doesn't fit.
  
  Every take/give is logged to `logs/curatorium_essentials-economy.log`.
- `balance` (coins carried, shown as "3 crowns, 2 cogs (1,664 spurs)"), `balancetop` (see open question 3).
- `pay`: moves coins from the payer's inventory into the recipient's, both online (see open question 2); `paytoggle`, `payconfirmtoggle`.
- `eco give` (mints coins into a player's inventory) and `eco take` (removes coins), admin-only. `eco set`/`eco reset` are dropped, since they only make sense for virtual balances.
- Amounts are typed as spurs (`1664`) or with coin names (`3crown 2cog`).
- `worth`, `setworth`, `sell` (hand, inventory, by item) using `worth.json`: the items are taken and coins given.
- Sign shops on vanilla signs: `[Buy]`, `[Sell]`, `[Trade]`, `[Kit]`, `[Warp]`, `[Heal]`, `[Free]`, `[Disposal]`, `[Balance]`, `[Time]`, `[Weather]`, `[Enchant]`, `[Repair]`, plus `[Spawnmob]`/`[Gamemode]` for admins. Creating each kind needs a permission. Prices are taken from and paid into the clicking player's inventory.
- Command costs and kit costs, paid from the inventory; the command doesn't run if the player can't pay.
- "Starting balance" becomes a starting coin kit given on first join.

## Phase 5 — Discord (0.6.0)

Bundles JDA (the Java Discord library EssentialsX Discord uses), shaded and relocated into the
jar (~8–10 MB larger).

- Two-way chat relay (Minecraft ↔ a Discord channel), with player heads as avatars via webhook.
- Join, leave, death, advancement, server start/stop, kick/ban and mute messages, each routable to its own channel.
- Staff channel (helpop, staff chat), console channel (log stream; commands from a configured Discord role only).
- Discord slash commands: `/list`, `/execute` (role-locked), `/msg`.
- In-game: `discord` (invite link), `discordbroadcast`.
- Respects vanish, mutes, ignore and the chat filter; mentions limited so players can't ping `@everyone`.

## Phase 6 — Discord Link (0.7.0)

- `/link` in game gives a short code; the player runs `/link <code>` in Discord (or DMs the bot). `/unlink` either side.
- Role sync both ways between Discord roles and LuckPerms groups (configured map), on link, join and role change.
- Optional nickname sync (Discord nickname = Minecraft name).
- Optional "must link to join" mode; unlinked players get the code in the kick message.
- Links stored in `<world>/curatorium_essentials/discord-links.json`.

## Phase 7 — Cleanup (0.8.0 → 1.0.0)

- Fill gaps against the EssentialsX command list (core, Chat, Spawn, Discord, Discord Link), then 1.0.0.

## Testing and deployment

- Each phase is tested on a local NeoForge server with LuckPerms and Numismatics, joined from the normal Creatorium client pack **without this mod**, to prove it stays server-only.
- Then the jar goes to the live server (`CreatoriumMain01/Minecraft/mods`) and LuckPerms with it — only when the user says so. Since clients don't need it, the Modrinth profiles don't change.
- Add a row to `Creatorium-Mods/README.md` and an entry to `ROADMAP.md` once Phase 0 lands.

## GitHub

The repo will be public (GPL-3.0). The GitHub CLI (`gh`) is installed. Before the first push:

1. The user runs `gh auth login` (GitHub.com, HTTPS, browser login), then `gh auth setup-git` so git pushes use that login.
2. Commits are signed with the GitHub account's name and its private `users.noreply.github.com` address, set for this repo only.
3. The repo (`curatorium-essentials`, public) is created with `gh repo create` once the user confirms the name.
