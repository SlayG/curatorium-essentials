# LuckPerms setup

**Use LuckPerms for NeoForge 5.4.150.** 5.4.140, the build Modrinth lists for 1.21.1, kicks every
player on join on NeoForge 1.21.1 ("Invalid player data"; LuckPerms issues #3963, #4235, #4259,
#4335). 5.4.150 is labelled 1.21.4 but runs on 1.21.1.

Curatorium Essentials checks EssentialsX-style permission nodes. Each node has a default that
applies when LuckPerms has no setting for it:

- **everyone**: every player has it.
- **op 2**: ops at level 2 or higher (staff).
- **op 4**: ops at level 4 (server management).

Anything set in LuckPerms (true or false, on the player or one of their groups, wildcards such as
`essentials.*` included) overrides the default. The console always uses the defaults, and has
level 4.

Run these in the server console (or in game with `/lp`).

## Groups

```
lp creategroup staff
lp group staff parent add default
lp user <name> parent add staff
```

Prefixes and suffixes for chat (`{PREFIX}`/`{SUFFIX}` in `chat.format`), with `&` colour codes:

```
lp group default meta setprefix "&7[Member] "
lp group staff meta setprefix "&c[Staff] "
```

A whole chat format per group goes in `config.toml`, `chat.groupFormats`, keyed by the
player's primary LuckPerms group.

## Common grants

Let staff use the staff commands without op:

```
lp group staff permission set essentials.socialspy true
lp group staff permission set essentials.broadcast true
lp group staff permission set essentials.whois true
lp group staff permission set essentials.seen.extra true
lp group staff permission set essentials.helpop.receive true
lp group staff permission set essentials.chat.spy true
```

Let everyone use nicknames (without colours) or colours in chat:

```
lp group default permission set essentials.nick true
lp group default permission set essentials.chat.color true
```

## Nodes (0.2.0: chat and messaging)

`<cmd>.others` nodes let a player use the command on someone else.

### Everyone

| Node | What it allows |
|---|---|
| `essentials.msg`, `essentials.r` | `/msg` (aliases `/tell`, `/w`, `/m`, `/pm`, `/whisper`…), `/r` |
| `essentials.rtoggle`, `essentials.msgtoggle` | `/rtoggle` (who `/r` answers), `/msgtoggle` (block private messages) |
| `essentials.ignore` | `/ignore` |
| `essentials.mail`, `essentials.mail.send` | `/mail read`, `/mail clear`, `/mail send` |
| `essentials.me`, `essentials.helpop` | `/me`, `/helpop` |
| `essentials.afk`, `essentials.afk.message`, `essentials.afk.auto` | `/afk`, an AFK message, being marked AFK automatically |
| `essentials.list`, `essentials.motd`, `essentials.rules`, `essentials.info`, `essentials.customtext`, `essentials.help` | Information commands (and the MOTD on join) |
| `essentials.ping`, `essentials.ping.others` | `/ping` |
| `essentials.playtime`, `essentials.playtime.others` | `/playtime` |
| `essentials.seen`, `essentials.realname` | `/seen`, `/realname` |
| `essentials.getpos`, `essentials.depth`, `essentials.compass` | Own position, height, facing |
| `essentials.toggleshout`, `essentials.chat.shout`, `essentials.chat.question` | Shouting (`!`) and questions (`?`) with local chat on |
| `essentials.chat.url`, `essentials.msg.url` | Clickable links in chat and private messages |

### Op 2 (staff)

| Node | What it allows |
|---|---|
| `essentials.essentials` | `/essentials`, `/essentials version` |
| `essentials.socialspy` | `/socialspy`: see everyone's private messages |
| `essentials.broadcast`, `essentials.broadcastworld` | `/broadcast`, `/broadcastworld` |
| `essentials.nick`, `essentials.nick.others` | `/nick` for yourself, for others |
| `essentials.nick.color`, `.format`, `.magic` | Colours, formats (`&l` etc.), `&k` in nicknames |
| `essentials.whois`, `essentials.near`, `essentials.near.others` | `/whois`, `/near` |
| `essentials.seen.extra`, `essentials.seen.ip` | IP and logout location in `/seen`; `/seen <ip>` |
| `essentials.getpos.others`, `essentials.afk.others` | Other players' position; marking others AFK |
| `essentials.afk.kickexempt` | Never kicked for AFK |
| `essentials.mail.sendtemp`, `essentials.mail.sendall`, `essentials.mail.nolimit` | Expiring mail, mail to everyone, no mail rate limit |
| `essentials.helpop.receive` | Receives `/helpop` messages |
| `essentials.chat.spy` | Sees local chat from out of range |
| `essentials.chat.color`, `.format`, `.magic` | Codes in chat |
| `essentials.msg.color`, `.format`, `.magic` | Codes in private messages |
| `essentials.msgtoggle.bypass` | Messages players who blocked messages |
| `essentials.ignore.exempt` | Can't be ignored |
| `essentials.rtoggle.others`, `essentials.msgtoggle.others`, `essentials.socialspy.others`, `essentials.toggleshout.others` | Those toggles for other players |

### Op 4 (server management)

| Node | What it allows |
|---|---|
| `essentials.essentials.reload` | `/essentials reload` |
| `essentials.commandcooldowns.bypass` | Skip every command cooldown (or `.bypass.<command>` for one) |
| `essentials.nocommandcost.all` | Use priced commands for free (or `essentials.nocommandcost.<command>` for one) |
| `essentials.nick.allowlong` | Nicknames longer than `names.nickMaxLength` |
| `essentials.near.maxexempt` | `/near` past `misc.nearMaxRadius` |

## Nodes (0.3.0: moderation and admin)

Every Phase 2 command is **op 2** unless listed under op 4 below, apart from `/suicide`,
`/itemdb` and `/recipe` (everyone) and `/kit` (everyone, but each kit needs its own node).
The command node is `essentials.<command>`; `/gmc`, `/gms`, `/gma`, `/gmsp` use
`essentials.gamemode.<mode>`.

| Node | What it allows | Default |
|---|---|---|
| `essentials.ban.offline`, `essentials.mute.offline`, `essentials.togglejail.offline` | Ban, mute or jail players who are offline | op 2 |
| `essentials.ban.exempt`, `essentials.kick.exempt`, `essentials.mute.exempt`, `essentials.jail.exempt`, `essentials.kill.exempt`, `essentials.sudo.exempt`, `essentials.lightning.exempt`, `essentials.nuke.exempt`, `essentials.kickall.exempt` | Can't be banned, kicked, muted… by others | op 4 |
| `essentials.ban.notify`, `essentials.banip.notify`, `essentials.kick.notify`, `essentials.mute.notify`, `essentials.jail.notify` | Sees ban, kick, mute and jail announcements | op 2 |
| `essentials.vanish.see` | Sees vanished players (entity, tab list, `/list`, join and quit messages) | op 2 |
| `essentials.gamemode.<survival\|creative\|adventure\|spectator>`, `essentials.gamemode.others` | Each game mode; changing others' | op 2 |
| `essentials.invsee.modify`, `essentials.enderchest.modify` | Change what's in someone's inventory or ender chest (without it, view only) | op 2 |
| `essentials.invsee.preventmodify` | Nobody can change this player's inventory through `/invsee` | op 4 |
| `essentials.clearinventory.others`, `essentials.clearinventory.all` | `/ci <player>`; `/ci *` (op 4) | op 2 / op 4 |
| `essentials.itemspawn.exempt` | `/i` and `/give` items on `items.spawnBlacklist` | op 4 |
| `essentials.itemname.color`, `.format`, `.magic` (also `essentials.itemlore.*`, `essentials.editsign.*`) | Codes in item names, lore and signs | op 2 |
| `essentials.enchantments.allowunsafe` | Levels above normal, and enchantments on items that can't take them | op 4 |
| `essentials.repair.all` | `/repair all` | op 2 |
| `essentials.spawnmob.<entity>`, `essentials.spawnmob.nolimit` | Spawning each mob (`essentials.spawnmob.zombie`…); past `world.spawnmobLimit` | op 2 / op 4 |
| `essentials.kits.<name>` | Using kit `<name>` (`/kit`, the kit GUI) | op 2 |
| `essentials.kit.others`, `essentials.kit.exemptdelay` | Giving kits to others; ignoring kit delays | op 2 / op 4 |
| `essentials.break.bedrock` | `/break` on bedrock | op 4 |

Op 4 commands: `/kickall`, `/sudo`, `/unlimited`, `/createkit`, `/delkit`, `/kitreset`,
`/backup`, `/nuke`, `/antioch`, `/break`.

Give everyone a starter kit:

```
lp group default permission set essentials.kits.starter true
```

## Vanilla commands with the same name

Essentials replaces a vanilla command only when its name is in `commands.overrideVanilla`
(default: `msg tell w me tp teleport give kick ban ban-ip pardon pardon-ip time weather
gamemode kill list help`). The others keep vanilla's version and Essentials' is reachable
through an alias: `/i` (`/item`), `/eenchant` (`/enchant`), `/erecipe` (`/recipe`),
`/ci` (`/clear`), `/exp` (`/xp`). Add a name to the list to switch it over.

## Nodes (0.4.0: teleports and spawn)

**Every teleport command is op 2**, homes, warps and `/spawn` included: regular players get none
of them unless a group is given the node. Command nodes are `essentials.<command>` (`/homes`
uses `essentials.home`).

| Node | What it allows | Default |
|---|---|---|
| `essentials.tp.others` | `/tp` moving anyone but yourself | op 2 |
| `essentials.home.others`, `essentials.sethome.others`, `essentials.delhome.others`, `essentials.renamehome.others` | Another player's homes, as `player:home` | op 2 |
| `essentials.sethome.multiple.unlimited` | No home limit (otherwise `homes.defaultMax`, or `homes.groupMax` for a group) | op 4 |
| `essentials.warp.others`, `essentials.setwarp.overwrite` | Sending others to a warp; moving an existing warp | op 2 |
| `essentials.warps.<name>` | That warp, when `warps.perWarpPermission` is on | op 2 |
| `essentials.spawn.others`, `essentials.back.others`, `essentials.tpr.others`, `essentials.tptoggle.others`, `essentials.tpauto.others` | Those commands for someone else | op 2 |
| `essentials.back.ondeath` | `/back` to where you died | op 2 |
| `essentials.teleport.timer.bypass`, `essentials.teleport.cooldown.bypass` | No warmup; no cooldown | op 4 |
| `essentials.tptoggle.override` | Teleport to (or pull) players who turned teleports off | op 4 |
| `essentials.teleport.safety.bypass` | Land exactly where sent, even if unsafe | nobody |
| `essentials.teleport.bypass-siege` | Teleport in or out of a claim under siege | nobody (not even ops) |

Let a group use homes and `/spawn` (for example, if teleports are ever opened up):

```
lp group default permission set essentials.home true
lp group default permission set essentials.sethome true
lp group default permission set essentials.delhome true
lp group default permission set essentials.spawn true
```

## Nodes (0.5.0: economy)

| Node | What it allows | Default |
|---|---|---|
| `essentials.balance`, `essentials.pay`, `essentials.paytoggle`, `essentials.payconfirmtoggle` | Own balance; paying; refusing payments; confirming payments | everyone |
| `essentials.balance.others`, `essentials.paytoggle.others` | Someone else's balance or toggle | op 2 |
| `essentials.eco` | Minting and removing coins | op 4 |
| `essentials.kit.nocost` | Kits without paying their price | op 4 |
| `essentials.signs.create.<kind>` | Writing a working shop sign of that kind (`buy`, `sell`, `trade`, `kit`, `warp`, `heal`, `free`, `disposal`, `balance`, `time`, `weather`, `enchant`, `repair`, `spawnmob`, `gamemode`) | op 2 |
| `essentials.signs.use.<kind>` | Using one | everyone, except `warp` (teleports are op-only), `spawnmob` and `gamemode`: op 2 |
| `essentials.signs.break` | Breaking a shop sign | op 2 |

## Nodes (0.6.0: Discord)

| Node | What it allows | Default |
|---|---|---|
| `essentials.discord` | `/discord`, the invite link | everyone |
| `essentials.discordbroadcast` | `/discordbroadcast <chat\|staff\|console\|id> <message>` | op 2 |

Who can run server commands from Discord (`/execute` and the console channel) isn't a LuckPerms
node: it's the Discord roles listed in `commandRoles` in `discord.toml`. Discord staff-channel
messages reach players with `essentials.helpop.receive`.

## Nodes (0.7.0: Discord Link)

| Node | What it allows | Default |
|---|---|---|
| `essentials.link`, `essentials.unlink` | Linking and unlinking your own account | everyone |
| `essentials.unlink.others` | `/unlink <player>` | op 2 |
| `essentials.link.bypass` | Joining without linking when `link.required` is on | op 4 |
