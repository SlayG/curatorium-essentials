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

1. ~~Do homes, warps and `/spawn` count as "teleport"?~~ **Decided (2026-11-26): yes.** Every teleport command, homes, warps and `/spawn` included, is op-only by default.
2. ~~Paying offline players.~~ **Decided (2026-11-26): hold the coins in escrow** and hand them over at the recipient's next login. **Changed in Phase 7:** `/pay` needs both players within 20 blocks, so only `/eco give` (and `/pay` with `economy.payRange = 0`) still holds coins for offline players.
3. ~~`/balancetop`.~~ **Decided (2026-11-26): left out.**
4. ~~Ender chest.~~ **Decided (2026-11-26): not counted or spent** (`economy.countEnderChest` stays off by default).
5. ~~Discord bot.~~ **Done (2026-11-26):** the bot (Ashru) exists and its token is in the dev server's `discord.toml`, which is never committed.

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
- **Data**: per-player JSON at `<world>/curatorium_essentials/userdata/<uuid>.json` (homes, nick, ignores, mute/jail state, last location, playtime, flags); `warps/`, `jails.json`, `kits.json`, `spawn.json`. Saved asynchronously, flushed on stop.
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

## Phase 2 — Moderation and admin (0.3.0) — done

Tested on the dev server with two bot players (the bot now also reports tab-list, entity and
GUI packets) and LuckPerms 5.4.150: bans, temp bans and IP bans (and the vanilla rejoin
message), kicks, timed mutes blocking chat, `/msg` and `/mail send`, jails (commands blocked,
pulled back after moving, released), vanish (hidden from tab list, entity tracking, `/list`,
`/msg` and join/quit messages, also across a relog), god, fly, game modes, heal, feed, speed,
exp, kill, suicide, burn, sudo, `/invsee`, `/enderchest`, item and enchant commands, condense,
recipe and disposal GUIs, every workstation, power tools, time/ptime, weather/pweather,
lightning, spawnmob, remove, gc, the fun commands, kits (GUI claim, delays, once-only, per-kit
LuckPerms node). Then checked in a real vanilla 1.21.1 client (no mods): `/unlimited` placing,
`/editsign`, `/tree` and `/bigtree`, the kit and recipe GUIs, `/skull`, and moving items in
`/invsee` (live updates, locked filler slots). Not tested yet: `/backup` with a real script.

Notes from building it:
- Bans live in vanilla's `banned-players.json`/`banned-ips.json`, so expiry and the login check are vanilla's.
- Vanish needs mixins (entity tracking, tab list and join/quit messages at login); sounds and particles a vanished player makes still reach others.
- `/item`, `/enchant`, `/recipe`, `/clear`, `/xp` stay vanilla unless added to `commands.overrideVanilla`; Essentials' versions answer to `/i`, `/eenchant`, `/erecipe`, `/ci`, `/exp`.
- `/give`, `/kill`, `/gamemode` take vanilla's arguments (selectors work); `/give` without an amount gives one, `/i` a full stack.
- `/unlimited` covers blocks only. Power tools run on right click, and on left click of a block or entity (`{player}` = the player hit); left-clicking air never reaches the server.
- `/ptime` and `/pweather` are resent every second (every tick while the real rain fades), since the server keeps sending the real time and weather.

Planned:

- Bans and mutes: `ban`, `tempban`, `banip`, `tempbanip`, `unban`, `unbanip`, `kick`, `kickall`, `mute` (timed, with reason); uses the vanilla ban list so `/pardon` still works.
- Jails: `setjail`, `deljail`, `jails`, `togglejail`, `jailedplayers` — jailed players are held in place, can't use commands, timer counts online or real time.
- Player state: `vanish` (hidden from tab list, join/quit messages and other players), `god`, `fly`, `gamemode`, `heal`, `feed`, `speed`, `exp`, `kill`, `suicide`, `rest`, `burn`, `sudo`.
- Inventory and items: `invsee`, `enderchest`, `clearinventory` (+ confirm toggle), `give`, `item`, `more`, `itemdb`, `itemname`, `itemlore`, `enchant`, `repair`, `hat`, `condense`, `unlimited`, `skull`, `book`, `recipe`, `disposal`, `powertool`, `powertoollist`, `powertooltoggle`, `editsign`.
- Portable workstations (vanilla menus that don't need the block): `workbench`, `anvil`, `cartographytable`, `grindstone`, `loom`, `smithingtable`, `stonecutter`.
- World: `time`, `ptime`, `weather`, `pweather`, `thunder`, `lightning`, `remove` (entities by type and radius), `spawnmob`, `spawner`, `tree`, `bigtree`, `gc` (TPS, memory, loaded chunks), `backup` (runs a configured script).
- Fun (all op-only by default): `beezooka`, `kittycannon`, `fireball`, `firework`, `nuke`, `antioch`, `ice`, `break`.
- Kits: `kit`, `createkit`, `delkit`, `showkit`, `kitreset`; GUI kit picker; per-kit cooldown, one-time kits, first-join kit. Costs arrive in Phase 4.

## Phase 3 — Teleport and Spawn (0.4.0) — op-only by default — done

Tested on the dev server with two bot players and LuckPerms 5.4.150: every teleport command is
hidden from a non-op; /tp in vanilla's forms, /tphere, /tpo, /tpall, /tppos, /tpoffline, /top,
/bottom, /world (overworld and Nether), /back; homes (set, limit, GUI, rename, delete, another
player's with `player:home`, `bed`); warps (set, move, info, GUI, sending someone, delete);
/setspawn and /spawn, respawning at /spawn without a bed; /tpr and /settpr; requests (/tpa,
/tpahere, /tpaall, accept and deny by name or newest, /tpacancel, /tpauto, clickable buttons);
/tptoggle; the warmup (cancelled by moving or by damage). Then checked in a real vanilla client:
the home and warp GUIs, safe landings, /back after death, /tpr, the warmup, and /tpa and /tpahere
with the clickable buttons. /world to the End tested in a real client (2026-11-26). Spawns tested
with new bot players (2026-11-26): a first join lands on `newPlayerSpawn` (default, or a named
spawn), later joins stay put, `/spawn` and respawning without a bed go to the player's primary-group
spawn (else default), and `spawnOnJoin` sends a returning player there. A `/spawnpoint` counts as a
bed and is left alone.

Siege block tested 2026-11-26 with Curatorium Claims 1.3.1 and a real siege (an op bot as the
defender, a Siege Engine powered by a Creative Motor): during the attack every teleport into or
out of the claim was refused, ops included: /spawn, /home, /tpa (accepted), /tpr, /top, /back,
/tp, /tppos and the console's /tp. With `essentials.teleport.bypass-siege` the bot teleported in
and out; after `/claims siege stop <owner>` teleports worked again without it. Found and fixed:
whoever moves someone else (the console, /tp, /tphere, /tpo) now hears why it was refused, not
just the player.

Notes from building it:
- The siege check reaches Curatorium Claims by reflection, so Essentials builds and runs without it. Nobody has `essentials.teleport.bypass-siege` unless LuckPerms grants it, ops included.
- Warmup (default 3 s) and cooldown apply to the "travel" teleports: home, warp, spawn, back, accepted requests, tpr. Staff teleports (tp, tphere, tppos…) are instant. Op 4 skips warmups and cooldowns.
- Home limits come from `homes.defaultMax` and `homes.groupMax` (checked with LuckPerms' `group.<name>` nodes).
- `/return` is a vanilla command in 1.21.1, so it stays vanilla; /back answers to `/back` and `/eback`.

Planned:

- Requests: `tpa`, `tpahere`, `tpaall`, `tpaccept`, `tpdeny`, `tpacancel`, `tpauto`, `tptoggle`.
- Direct: `tp`, `tphere`, `tpall`, `tpo`, `tpohere`, `tppos`, `tpoffline`, `top`, `bottom`, `jump`, `world`, `back` (also after death).
- Homes: `sethome`, `home`, `delhome`, `renamehome`, max homes per LuckPerms group; GUI list. Warps: `setwarp`, `warp`, `delwarp`, `warpinfo`, per-warp permission.
- Random teleport: `tpr`, `settpr` (radius and centre per dimension, avoids water/lava, respects the world border).
- Spawn module: `setspawn`, `spawn`, per-group spawns, new-player spawn, respawn-at-spawn option.
- Teleport warmup (cancel on move/damage), cooldown, and safe-location checks.
- Safety hook to Curatorium Claims: block teleports into or out of a claim under siege, even for op-granted users, unless they have `essentials.teleport.bypass-siege` (only matters if teleport is ever opened up).

## Phase 4 — Economy on physical Numismatics coins (0.5.0) — done

Tested on the dev server (now running Create 6.0.10 and Numismatics 1.1.0) with two bot players.
The bot had to learn NeoForge's login handshake for that: a server with Create refuses vanilla
clients. What was tested: /balance; /pay, including change (3 spurs paid from a cog leaves
61 in smaller coins), confirmation and refusals; paying and /eco give to an offline player (held,
then handed over at login with who sent what); /paytoggle; /eco take; a kit with a price; sign shops written
through the sign editor by an op: [Buy], [Sell], [Trade], [Heal], [Balance], [Free], [Disposal],
refusing invalid signs and non-ops' signs; the economy log; breaking shop signs (a non-op is
refused in survival and creative, an op can, plain signs aren't protected); starting coins on a
first join only. Tested in a real client: /eco give and every sign kind, including [Kit], [Warp],
[Time], [Weather], [Enchant], [Repair], [Spawnmob] and [Gamemode].

Notes from building it:
- Paying someone offline (and /eco give) takes the coins now and holds them in the player's data; they get them, with a line per payment, at their next login.
- /balancetop is left out, and ender chests aren't counted (decisions above).
- Sign shops are admin shops. A sign works only when someone with `essentials.signs.create.<kind>` writes it: its first line turns `economy.signColor` and it's waxed. Every edit is checked again, since vanilla keeps a line's colour when the text changes. Breaking one needs `essentials.signs.break`.
- /worth, /setworth and /sell were built and then removed: the server won't use them. The [Sell] sign stays, with its price on the sign.

Planned:

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
- ~~`worth`, `setworth`, `sell`~~ — dropped, not wanted on the server.
- Sign shops on vanilla signs: `[Buy]`, `[Sell]`, `[Trade]`, `[Kit]`, `[Warp]`, `[Heal]`, `[Free]`, `[Disposal]`, `[Balance]`, `[Time]`, `[Weather]`, `[Enchant]`, `[Repair]`, plus `[Spawnmob]`/`[Gamemode]` for admins. Creating each kind needs a permission. Prices are taken from and paid into the clicking player's inventory.
- Command costs and kit costs, paid from the inventory; the command doesn't run if the player can't pay.
- "Starting balance" becomes a starting coin kit given on first join.

## Phase 5 — Discord (0.6.0) — done

Tested on the dev server against the real Discord server (Curatorium of Secrets), with three test
threads as the chat, staff and console channels: the bot logs in, finds its channels and registers
its slash commands; in-game chat (shouts, since the dev server has local chat on), join, leave,
death, /helpop, /broadcast and /discordbroadcast arrive in the right channel, with `@everyone` not
pinging and markdown escaped; Discord chat and staff messages reach the game; /list, /msg and
/execute work; commands typed in the console channel by a command role run and are logged with the
member's name. The release jar (JDA relocated) was also run on a plain NeoForge 21.1.265 server with
Create, Numismatics and LuckPerms: it logs in and relays chat. Then, with real text channels:
webhook chat with the player's name and head, an advancement, mute/unmute, kick, ban/unban and
jail/release posts in the staff channel, /discord's invite link, and turning the bot on by saving
the config. Not tested yet: refusing /execute to someone without a command role.

Notes from building it:
- JDA 6.7.0 is bundled under `dev.curatorium.essentials.lib`; its voice libraries are left out. The jar is 13.6 MB.
- `config/curatorium_essentials/discord.toml` holds the token (or the `CURATORIUM_DISCORD_TOKEN` environment variable). Saving it with a new token, or turning the bot on or off, restarts the bot without a server restart.
- The bot needs the Message Content Intent turned on; without it Discord refuses the login and the log says so.
- Threads work as channels, but chat posted there comes from the bot (`**name**: message`), not a webhook with the player's head, and Discord archives quiet threads. Use text channels on the live server.
- `commandRoles` empty means nobody can run commands from Discord.
- "Respects ignore and the chat filter": the mod has no chat filter of its own; Discord only gets chat that other mods and mutes let through. Ignores are between players, and Discord users aren't players, so they don't apply.

Planned:

Bundles JDA (the Java Discord library EssentialsX Discord uses), shaded and relocated into the
jar (~8–10 MB larger).

- Two-way chat relay (Minecraft ↔ a Discord channel), with player heads as avatars via webhook.
- Join, leave, death, advancement, server start/stop, kick/ban and mute messages, each routable to its own channel.
- Staff channel (helpop, staff chat), console channel (log stream; commands from a configured Discord role only).
- Discord slash commands: `/list`, `/execute` (role-locked), `/msg`.
- In-game: `discord` (invite link), `discordbroadcast`.
- Respects vanish, mutes, ignore and the chat filter; mentions limited so players can't ping `@everyone`.

## Phase 6 — Discord Link (0.7.0) — done

Tested on the dev server with the real Discord server: linking with a code from /link (slash
command), unlinking, a LuckPerms group giving a Discord role (`groupsToRoles`) and unlinking taking
it away, a Discord role putting a player in a group (`rolesToGroups`), and "must link to join"
refusing an unlinked bot player at login with its code. The resync was tested with
`resyncMinutes = 1` by giving and then removing a Discord role through the API: the mapped group
was added, then removed, each at the next resync. Not tested yet: linking by direct message,
nickname sync and the member intent.

Notes from building it:
- Settings are in the `[link]` section of `discord.toml`. Each map goes one way: `rolesToGroups` (the role decides the group) and `groupsToRoles` (the group decides the role). A pair in both: Discord wins on join.
- Without the Server Members Intent (`memberIntent = false`, the default), Discord role changes are picked up on link, on join and every `resyncMinutes`; LuckPerms changes reach Discord at once either way.
- Codes are 6 characters without look-alikes (0/O, 1/I/L), last `codeMinutes`, and are refused if either account is already linked.
- "Must link to join" refuses at login, before the join message, and lets everyone in while the bot is offline; op 4 or `essentials.link.bypass` skips it.
- Discord never lets a bot rename the server owner, so nickname sync skips them.
- LuckPerms over RCON on the dev server logs commands but doesn't always save them; grant permissions in game, or in the database while the server is stopped.

Planned:

- `/link` in game gives a short code; the player runs `/link <code>` in Discord (or DMs the bot). `/unlink` either side.
- Role sync both ways between Discord roles and LuckPerms groups (configured map), on link, join and role change.
- Optional nickname sync (Discord nickname = Minecraft name).
- Optional "must link to join" mode; unlinked players get the code in the kick message.
- Links stored in `<world>/curatorium_essentials/discord-links.json`.

## Phase 7 — Cleanup (0.8.0 → 1.0.0)

Compared against the EssentialsX `2.x` command lists (2026-11-26): 147 of the 153 core commands were
here, and all of Chat, Spawn, Discord and Discord Link. Missing were `/ext` and `/potion` (now
added), `/balancetop` and `/worth`, `/setworth`, `/sell` (left out on purpose), and EssentialsX XMPP
(not included).

Done in 0.8.0, bot-tested on the dev server:
- Added `/ext` (`/extinguish`) and `/potion` (`clear`, `apply`, `effect:<effect> power:<n> duration:<seconds> [splash:true]`, per-effect nodes).
- Removed, as the server won't use them: `/nuke`; `/powertool`, `/powertoollist`, `/powertooltoggle`; `/ptime`, `/pweather`; `/mail` (Curatorium Claims handles mail); `/unlimited`. Their stored player data is dropped the next time a player's file is saved.
- `/pay` now works only between players in the same dimension within `economy.payRange` (20, matching Curatorium Exchange's ranges); a vanished player can't be paid by someone who can't see them. 0 lifts the limit; staff with `essentials.pay.bypassrange` (op 2) pay anyone, anywhere, offline players included.

Still to do before 1.0.0: anything else from the review, and the untested items listed under each phase.

## Testing and deployment

- Each phase is tested on a local NeoForge server with LuckPerms and Numismatics, joined from the normal Creatorium client pack **without this mod**, to prove it stays server-only.
- Then the jar goes to the live server (`CreatoriumMain01/Minecraft/mods`) and LuckPerms with it — only when the user says so. Since clients don't need it, the Modrinth profiles don't change.
- Add a row to `Creatorium-Mods/README.md` and an entry to `ROADMAP.md` once Phase 0 lands.

## GitHub

The repo will be public (GPL-3.0). The GitHub CLI (`gh`) is installed. Before the first push:

1. The user runs `gh auth login` (GitHub.com, HTTPS, browser login), then `gh auth setup-git` so git pushes use that login.
2. Commits are signed with the GitHub account's name and its private `users.noreply.github.com` address, set for this repo only.
3. The repo (`curatorium-essentials`, public) is created with `gh repo create` once the user confirms the name.
