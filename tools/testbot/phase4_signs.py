"""Phase 4 sign shops: an op writes each sign through the sign editor, Bob (not op) uses it.

python3 -I phase4_signs.py <run dir> <server port>
"""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcbot import Bot

RUN, PORT = sys.argv[1], int(sys.argv[2])
HERE = os.path.dirname(os.path.abspath(__file__))
X, Y, Z = 300, 151, 298  # the sign; the players stand south of it, facing it


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
                print("   %-6s| %s" % (b.name, m))


alice, bob = Bot("Alice", PORT), Bot("Bob", PORT)
rcon("op Alice", "difficulty peaceful", "forceload add 290 290 310 310", "fill 296 150 296 304 150 304 stone",
     "tp Alice 300.5 151 300.5 180 0", "tp Bob 301.5 151 300.5 180 0", "clear Bob", "clear Alice",
     "give Alice oak_sign 16")
time.sleep(1)
show(alice, bob)


def sign(lines, writer=alice):
    print("\n## %s writes a sign: %s" % (writer.name, " / ".join(lines)))
    rcon("setblock %d %d %d air" % (X, Y, Z))
    if writer is not alice:
        rcon("item replace entity %s weapon.mainhand with oak_sign" % writer.name)
    writer.use_on(X, Y - 1, Z, 1)
    time.sleep(0.6)
    writer.write_sign(X, Y, Z, lines)
    show(alice, bob)


def click(note):
    print("\n## Bob uses it (%s)" % note)
    bob.use_on(X, Y, Z, 3)
    show(alice, bob)
    bob.command("/bal")
    show(bob)


rcon("eco give Bob 1cog")
sign(["[Buy]", "2", "diamond", "10"])
click("buys 2 diamonds for 10 spurs")
sign(["[Sell]", "1", "diamond", "20"])
click("sells 1 diamond for 20 spurs")
click("sells the other one")
click("has none left")
sign(["[Trade]", "4 cobblestone", "1 iron_ingot", ""])
rcon("give Bob cobblestone 8")
click("trades 4 cobblestone for an iron ingot")
sign(["[Heal]", "", "", "free"])
rcon("damage Bob 6")
click("free heal")
sign(["[Balance]", "", "", ""])
click("balance")
sign(["[Free]", "apple", "3", ""])
click("3 free apples")
sign(["[Buy]", "1", "diamond", "5crown"])
click("can't afford 5 crowns")
sign(["[Warp]", "spawn", "", ""])
sign(["[Buy]", "1", "not_an_item", "5"])
sign(["[Buy]", "1", "diamond", "5"], writer=bob)
click("Bob's own sign isn't a shop")
sign(["[Disposal]", "", "", ""])
click("disposal")
rcon("setblock %d %d %d air" % (X, Y, Z), "fill 296 150 296 304 150 304 air", "forceload remove 290 290 310 310", "clear Bob")
alice.close()
bob.close()
