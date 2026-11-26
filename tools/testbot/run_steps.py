"""Drives bot players and the console through a steps file and prints what everyone saw."""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcbot import Bot

RUN = sys.argv[1]
PORT = int(sys.argv[2])
HERE = os.path.dirname(os.path.abspath(__file__))
STEPS = sys.argv[3]  # path to a steps file, or a file name next to this script


def rcon(*cmds):
    out = subprocess.run([sys.executable, "-I", os.path.join(HERE, "rcon.py"), os.path.join(RUN, "server.properties"), *cmds],
                         capture_output=True, text=True).stdout
    for line in out.strip().splitlines():
        print("   console| " + line)


bots = {}


def show():
    time.sleep(0.6)
    for name, b in bots.items():
        for m in b.take():
            print("   %-6s| %s" % (name, m))
        if not b.alive:
            print("   %-6s| DISCONNECTED: %s" % (name, b.disconnect_reason))


def do(name, cmd):
    print("\n## %s: %s" % (name, cmd))
    b = bots[name]
    if cmd.startswith("/"):
        b.command(cmd)
    else:
        b.chat(cmd)
    show()


def console(*cmds):
    print("\n## console: " + " ; ".join(cmds))
    rcon(*cmds)
    show()


bots["Alice"] = Bot("Alice", PORT)
bots["Bob"] = Bot("Bob", PORT)
console("op Alice")
show()
for b in bots.values():
    b.take()

for line in open(STEPS if os.path.isfile(STEPS) else os.path.join(HERE, STEPS), encoding="utf-8"):
    line = line.rstrip("\n")
    if not line or line.startswith("#"):
        continue
    who, _, cmd = line.partition(" ")
    if who == "console":
        console(*cmd.split(" ;; "))
    elif who == "sleep":
        time.sleep(float(cmd))
    elif who == "move":
        parts = cmd.split()
        bots[parts[0]].move(float(parts[1]) if len(parts) > 1 else 0.5)
        show()
    elif who == "click":
        name, slot = cmd.split()
        print("\n## %s clicks slot %s" % (name, slot))
        bots[name].click(int(slot))
        show()
    elif who == "close":
        bots[cmd].close_screen()
        show()
    elif who == "use":
        print("\n## %s uses held item" % cmd)
        bots[cmd].use()
        show()
    elif who == "useon":
        name, x, y, z, face = cmd.split()
        print("\n## %s right-clicks %s %s %s" % (name, x, y, z))
        bots[name].use_on(int(x), int(y), int(z), int(face))
        show()
    elif who == "state":
        print("\n## state of %s: %s pos=%s" % (cmd, bots[cmd].state_info, [round(v, 1) for v in bots[cmd].pos or []]))
    elif who == "disconnect":
        bots[cmd].close()
        del bots[cmd]
        print("\n## %s disconnected" % cmd)
        show()
    elif who == "reconnect":
        if cmd in bots:
            bots[cmd].close()
            time.sleep(1)
        try:
            bots[cmd] = Bot(cmd, PORT)
            print("\n## %s reconnected" % cmd)
        except RuntimeError as e:
            bots.pop(cmd, None)
            print("\n## %s could not join: %s" % (cmd, e))
        show()
    else:
        do(who, cmd)

for b in bots.values():
    b.close()
