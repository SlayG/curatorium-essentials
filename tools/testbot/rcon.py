import socket, struct, sys, re
props = dict(l.strip().split("=", 1) for l in open(sys.argv[1]) if "=" in l and not l.startswith("#"))
s = socket.create_connection(("127.0.0.1", int(props["rcon.port"])), timeout=10)
def pkt(i, t, body):
    d = struct.pack("<ii", i, t) + body.encode() + b"\0\0"
    s.sendall(struct.pack("<i", len(d)) + d)
    n = struct.unpack("<i", s.recv(4))[0]
    buf = b""
    while len(buf) < n: buf += s.recv(n - len(buf))
    return buf[8:-2].decode("utf-8", "replace")
pkt(1, 3, props["rcon.password"])
for cmd in sys.argv[2:]:
    print("> " + cmd); print(re.sub("§.", "", pkt(2, 2, cmd)) or "(no output)")
