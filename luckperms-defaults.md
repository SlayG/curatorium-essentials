# LuckPerms setup

Curatorium Essentials checks EssentialsX-style permission nodes. Each node has a default that
applies when LuckPerms has no setting for it:

- **everyone**: every player has it.
- **op 2**: ops at level 2 or higher (staff commands).
- **op 4**: ops at level 4 (server management).

Anything set in LuckPerms (true or false, on the player or one of their groups, wildcards such as
`essentials.*` included) overrides the default. The console always uses the defaults, and has
level 4.

Run these in the server console (or in game with `/lp`). The node lists grow with each phase.

## Groups

```
lp creategroup staff
lp group staff parent add default
lp user <name> parent add staff
```

## Nodes so far (0.1.0)

| Node | Default | What it allows |
|---|---|---|
| `essentials.essentials` | op 2 | `/essentials`, `/essentials version` |
| `essentials.essentials.reload` | op 4 | `/essentials reload` |
| `essentials.commandcooldowns.bypass` | op 4 | Skip every command cooldown (or `.bypass.<command>` for one) |
| `essentials.nocommandcost.all` | op 4 | Use priced commands for free (or `essentials.nocommandcost.<command>` for one) |

Example: let staff see the version:

```
lp group staff permission set essentials.essentials true
```

## Teleport commands

Teleport commands (Phase 3) default to **op 2**. Regular players don't get them unless a group is
given the node.
