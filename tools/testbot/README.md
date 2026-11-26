# Test bots

A tiny Minecraft 1.21.1 client (`mcbot.py`, offline mode, no client mods) and an RCON client
(`rcon.py`), for testing commands on the dev server with real players.

1. In `run/server.properties`: `online-mode=false`, `enforce-secure-profile=false`,
   `enable-rcon=true`, an `rcon.password`, and the ports you want (`server-port`, `rcon.port`).
2. `./gradlew runServer`, with LuckPerms 5.4.150 in `run/mods`.
3. `python3 -I run_steps.py ../../run <server-port> phase2_all.txt`

A steps file has one action per line: `<Bot> <chat or /command>`, `console <cmd> ;; <cmd>`,
`sleep <seconds>`, `move <Bot> [blocks]`, `reconnect <Bot>` (also connects a new bot),
`disconnect <Bot>`, `click <Bot> <slot>` and `close <Bot>` (the open GUI), `use <Bot>` (right-click
the held item), `state <Bot>` (health, food, time, rain, flight and position as the bot last saw them).
Bots also report tab-list adds/removes, players' entities appearing and disappearing, and GUIs opening. Bots `Alice` and
`Bob` join first; Alice is made op. The output shows every message each bot and the console saw.

## Servers with mods (Create, Numismatics…)

NeoForge refuses vanilla clients once a mod with required network channels is installed, so the
bot answers NeoForge's login handshake like a modded client: it claims the channels listed in
`neoforge_channels.json` and acknowledges the registry, config and data-map sync. Regenerate that
file whenever the dev server's mods change:

```
python3 -I discover_channels.py <server-port>
```

The bot still understands only vanilla packets; anything modded it receives is ignored.

`phase4_signs.py` tests sign shops: an op writes each sign through the sign editor and Bob uses it
(`python3 -I phase4_signs.py ../../run <server-port>`). `phase4_break_start.py` tests breaking shop signs and starting coins (`... <server-port> <never-seen name>`, with `economy.startingCoins` set). Steps files can also use
`useon <Bot> <x> <y> <z> <face>` to right-click a block.
