# Test bots

A tiny Minecraft 1.21.1 client (`mcbot.py`, offline mode, no client mods) and an RCON client
(`rcon.py`), for testing commands on the dev server with real players.

1. In `run/server.properties`: `online-mode=false`, `enforce-secure-profile=false`,
   `enable-rcon=true`, an `rcon.password`, and the ports you want (`server-port`, `rcon.port`).
2. `gradlew.bat runServer`, with LuckPerms 5.4.150 in `run/mods`.
3. `python -I run_steps.py ../../run <server-port> phase1_all.txt`

A steps file has one action per line: `<Bot> <chat or /command>`, `console <cmd> ;; <cmd>`,
`sleep <seconds>`, `move <Bot>`, `reconnect <Bot>` (also connects a new bot). Bots `Alice` and
`Bob` join first; Alice is made op. The output shows every message each bot and the console saw.
