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

## Nodes (0.2.0)

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

## Teleport commands

Teleport commands (Phase 3) default to **op 2**. Regular players don't get them unless a group is
given the node.
