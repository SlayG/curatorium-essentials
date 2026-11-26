"""Phase 4: breaking shop signs (op vs not op) and starting coins for a new player.

python3 -I phase4_break_start.py <run dir> <server port> <new player name>
Set economy.startingCoins first; the name must never have joined.
"""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcbot import Bot

RUN, PORT, NEW = sys.argv[1], int(sys.argv[2]), sys.argv[3]
HERE = os.path.dirname(os.path.abspath(__file__))
X, Y, Z = 300, 151, 298


def rcon(*cmds):
    out = subprocess.run([sys.executable, "-I", os.path.join(HERE, "rcon.py"), os.path.join(RUN, "server.properties"), *cmds],
                         capture_output=True, text=True).stdout
    for line in out.strip().splitlines():
        if line.strip():
            print("   console| " + line)


def show(*bots):
    time.sleep(0.8)
    for b in bots:
        for m in b.take():
            if "AFK" not in m and "could not read" not in m:
                print("   %s| %s" % (b.name, m))


alice, bob = Bot("Alice", PORT), Bot("Bob", PORT)
rcon("op Alice", "deop Bob", "forceload add 290 290 310 310", "fill 296 150 296 304 150 304 stone",
     "tp Alice 300.5 151 300.5 180 0", "tp Bob 301.5 151 300.5 180 0", "clear Alice", "give Alice oak_sign 4")
time.sleep(1)
show(alice, bob)


def place(lines):
    rcon("setblock %d %d %d air" % (X, Y, Z))
    alice.use_on(X, Y - 1, Z, 1)
    time.sleep(0.6)
    alice.write_sign(X, Y, Z, lines)
    show(alice, bob)


def block():
    out = subprocess.run([sys.executable, "-I", os.path.join(HERE, "rcon.py"), os.path.join(RUN, "server.properties"),
                          "execute if block %d %d %d minecraft:oak_sign" % (X, Y, Z)], capture_output=True, text=True).stdout
    print("   => sign " + ("STILL THERE" if "passed" in out else "BROKEN"))


for mode in ("survival", "creative"):
    print("\n## Bob (not op, %s) tries to break a [Heal] shop sign" % mode)
    place(["[Heal]", "", "", ""])
    rcon("gamemode %s Bob" % mode)
    bob.dig(X, Y, Z, 3, 0)
    if mode == "survival":
        time.sleep(2.0)          # a sign takes ~1.5 s by hand
        bob.dig(X, Y, Z, 3, 2)
    show(bob)
    block()

print("\n## Bob breaks a plain sign (not a shop)")
place(["just", "a", "sign", ""])
rcon("gamemode creative Bob")
bob.dig(X, Y, Z, 3, 0)
show(bob)
block()

print("\n## Alice (op) breaks a shop sign")
place(["[Heal]", "", "", ""])
rcon("gamemode creative Alice")
alice.dig(X, Y, Z, 3, 0)
show(alice)
block()
rcon("gamemode survival Alice", "gamemode survival Bob")

print("\n## %s joins for the first time" % NEW)
new = Bot(NEW, PORT)
time.sleep(1.5)
show(new)
new.command("/bal")
show(new)
print("\n## %s rejoins (no second gift)" % NEW)
new.close()
time.sleep(1.5)
new = Bot(NEW, PORT)
time.sleep(1.5)
new.command("/bal")
show(new)
for b in (alice, bob, new):
    b.close()
