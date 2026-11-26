"""Minimal offline-mode Minecraft 1.21.1 (protocol 767) client for testing server commands.

Logs in, answers keep-alives/pings/known-packs, and records every system chat message as text.
"""
import io, socket, struct, threading, time, uuid, zlib, hashlib

PROTOCOL = 767


def varint(n):
    out = b""
    n &= 0xFFFFFFFF
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def mcstr(s):
    b = s.encode("utf-8")
    return varint(len(b)) + b


class Buf:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def read(self, n):
        v = self.d[self.i:self.i + n]
        self.i += n
        return v

    def varint(self):
        n = shift = 0
        while True:
            b = self.d[self.i]
            self.i += 1
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        if n & 0x80000000:
            n -= 1 << 32
        return n

    def string(self):
        n = self.varint()
        return self.read(n).decode("utf-8", "replace")

    def u(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(">" + fmt, self.read(size))[0]

    def rest(self):
        return self.d[self.i:]


def read_nbt(buf, tag=None):
    """Network NBT: root tag type then payload (no name)."""
    if tag is None:
        tag = buf.u("b")
        if tag == 0:
            return None
    return _payload(buf, tag)


def _payload(buf, tag):
    if tag == 1: return buf.u("b")
    if tag == 2: return buf.u("h")
    if tag == 3: return buf.u("i")
    if tag == 4: return buf.u("q")
    if tag == 5: return buf.u("f")
    if tag == 6: return buf.u("d")
    if tag == 7: return buf.read(buf.u("i"))
    if tag == 8:
        n = buf.u("H")
        return buf.read(n).decode("utf-8", "replace")
    if tag == 9:
        t = buf.u("b")
        n = buf.u("i")
        return [_payload(buf, t) for _ in range(n)]
    if tag == 10:
        out = {}
        while True:
            t = buf.u("b")
            if t == 0:
                return out
            name = buf.read(buf.u("H")).decode("utf-8", "replace")
            out[name] = _payload(buf, t)
    if tag == 11: return [buf.u("i") for _ in range(buf.u("i"))]
    if tag == 12: return [buf.u("q") for _ in range(buf.u("i"))]
    raise ValueError("bad nbt tag %d" % tag)


def flatten(c):
    """Text of a chat component given as NBT."""
    if c is None:
        return ""
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "".join(flatten(x) for x in c)
    if isinstance(c, dict):
        s = ""
        if "" in c:
            s += flatten(c[""])
        if "text" in c:
            s += str(c["text"])
        if "translate" in c:
            args = [flatten(a) for a in c.get("with", [])]
            s += "<" + c["translate"] + (":" + "|".join(args) if args else "") + ">"
        if "extra" in c:
            s += flatten(c["extra"])
        return s
    return str(c)


def offline_uuid(name):
    return uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3)


# Names to recognise in player-info and entity packets (filled in as bots are created).
KNOWN = {}

MENUS = ["generic_9x1", "generic_9x2", "generic_9x3", "generic_9x4", "generic_9x5", "generic_9x6", "generic_3x3",
         "crafter_3x3", "anvil", "beacon", "blast_furnace", "brewing_stand", "crafting", "enchantment", "furnace",
         "grindstone", "hopper", "lectern", "loom", "merchant", "shulker_box", "smithing", "smoker",
         "cartography_table", "stonecutter"]


class Bot:
    def __init__(self, name, port, host="127.0.0.1"):
        self.name = name
        KNOWN[offline_uuid(name)] = name
        self.entities = {}  # entity id -> player name, for players we know
        self.window = 0
        self.state_info = {}
        self.seq = 0
        self.messages = []
        self.lock = threading.Lock()
        self.threshold = -1
        self.state = "login"
        self.pos = None
        self.alive = True
        self.disconnect_reason = None
        self.sock = socket.create_connection((host, port), timeout=30)
        self.sock.settimeout(None)
        self.rfile = self.sock.makefile("rb")
        self.send_raw(0x00, varint(PROTOCOL) + mcstr(host) + struct.pack(">H", port) + varint(2))
        offline = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3)
        self.send_raw(0x00, mcstr(name) + offline.bytes)
        self.ready = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()
        if not self.ready.wait(60):
            raise RuntimeError(name + " did not reach play state: " + str(self.disconnect_reason))

    # ---------------------------------------------------------------- io
    def send_raw(self, pid, data):
        body = varint(pid) + data
        if self.threshold >= 0:
            if len(body) >= self.threshold:
                body = varint(len(body)) + zlib.compress(body)
            else:
                body = varint(0) + body
        self.sock.sendall(varint(len(body)) + body)

    def read_varint_stream(self):
        n = shift = 0
        while True:
            b = self.rfile.read(1)
            if not b:
                raise EOFError
            b = b[0]
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                return n

    def read_packet(self):
        length = self.read_varint_stream()
        data = self.rfile.read(length)
        if self.threshold >= 0:
            b = Buf(data)
            dlen = b.varint()
            data = zlib.decompress(b.rest()) if dlen else b.rest()
        b = Buf(data)
        return b.varint(), b

    # ---------------------------------------------------------------- protocol
    def loop(self):
        try:
            while self.alive:
                pid, b = self.read_packet()
                try:
                    getattr(self, "on_" + self.state)(pid, b)
                except (ValueError, IndexError, struct.error) as e:
                    self.event("[could not read packet 0x%02X: %s]" % (pid, e))
        except Exception as e:  # noqa
            if self.disconnect_reason is None:
                self.disconnect_reason = "connection closed: %r" % (e,)
        self.alive = False
        self.ready.set()

    def on_login(self, pid, b):
        if pid == 0x00:
            self.disconnect_reason = b.string()
            self.alive = False
        elif pid == 0x02:
            self.send_raw(0x03, b"")  # login acknowledged
            self.state = "config"
        elif pid == 0x03:
            self.threshold = b.varint()
        elif pid == 0x04:
            msg_id = b.varint()
            self.send_raw(0x02, varint(msg_id) + b"\x00")
        elif pid == 0x05:
            key = b.string()
            self.send_raw(0x04, mcstr(key) + b"\x00")

    def neoforge_query(self):
        """Our answer to NeoForge's channel query: every channel in neoforge_channels.json, as optional."""
        import json, os
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "neoforge_channels.json")
        chans = json.load(open(path)) if os.path.exists(path) else {}
        out = varint(2)
        for proto in (1, 4):  # PLAY, CONFIGURATION
            out += varint(proto) + varint(len(chans))
            for cid, c in chans.items():
                out += mcstr(cid) + mcstr(c["version"])
                out += (b"\x01" + varint(c["flow"])) if c.get("flow") is not None else b"\x00"
                out += b"\x01"  # optional on our side
        return out

    def on_config_payload(self, ident, b):
        """NeoForge's configuration handshake, answered like a modded client would."""
        reply = None
        if ident == "neoforge:register":
            reply = ("neoforge:register", self.neoforge_query())
        elif ident == "neoforge:frozen_registry_sync_completed":
            reply = (ident, b"")
        elif ident == "neoforge:extensible_enum_data":
            reply = ("neoforge:extensible_enum_ack", b"")
        elif ident == "neoforge:feature_flags":
            reply = ("neoforge:feature_flags_ack", b"")
        elif ident == "neoforge:known_registry_data_maps":
            n = b.varint()
            out = varint(n)
            for _ in range(n):
                reg = b.string()
                m = b.varint()
                ids = []
                for _ in range(m):
                    ids.append(b.string())
                    b.u("?")
                out += mcstr(reg) + varint(len(ids)) + b"".join(mcstr(i) for i in ids)
            reply = ("neoforge:known_registry_data_maps_reply", out)
        elif ident == "neoforge:modded_network_setup_failed":
            self.disconnect_reason = "NeoForge channel negotiation failed (regenerate neoforge_channels.json)"
        if reply:
            self.send_raw(0x02, mcstr(reply[0]) + reply[1])

    def on_config(self, pid, b):
        if pid == 0x01:  # custom payload
            self.on_config_payload(b.string(), b)
        elif pid == 0x00:  # cookie request
            self.send_raw(0x01, mcstr(b.string()) + b"\x00")
        elif pid == 0x02:
            self.disconnect_reason = flatten(read_nbt(b))
            self.alive = False
        elif pid == 0x03:
            self.send_raw(0x03, b"")
            self.state = "play"
        elif pid == 0x04:
            self.send_raw(0x04, b.read(8))
        elif pid == 0x05:
            self.send_raw(0x05, b.read(4))
        elif pid == 0x09:  # resource pack: say declined? accept to avoid kick
            pack_id = b.read(16)
            self.send_raw(0x06, pack_id + varint(3))  # accepted
            self.send_raw(0x06, pack_id + varint(0))  # successfully loaded
        elif pid == 0x0E:
            n = b.varint()
            packs = [(b.string(), b.string(), b.string()) for _ in range(n)]
            out = varint(len(packs))
            for ns, pid_, ver in packs:
                out += mcstr(ns) + mcstr(pid_) + mcstr(ver)
            self.send_raw(0x07, out)

    def on_play(self, pid, b):
        if pid == 0x26:
            self.send_raw(0x18, b.read(8))
        elif pid == 0x35:
            self.send_raw(0x27, b.read(4))
        elif pid == 0x1D:
            self.disconnect_reason = flatten(read_nbt(b))
            self.alive = False
        elif pid == 0x6C:
            text = flatten(read_nbt(b))
            overlay = b.u("?")
            if not overlay:
                with self.lock:
                    self.messages.append(text)
        elif pid == 0x39:
            with self.lock:
                self.messages.append("[signed player chat]")
        elif pid == 0x40:
            x, y, z = b.u("d"), b.u("d"), b.u("d")
            b.u("f"); b.u("f"); b.u("b")
            tid = b.varint()
            self.pos = [x, y, z]
            self.send_raw(0x00, varint(tid))
            self.ready.set()
        elif pid == 0x01:  # add entity
            eid = b.varint()
            u = uuid.UUID(bytes=b.read(16))
            if u in KNOWN:
                self.entities[eid] = KNOWN[u]
                self.event("[sees entity %s]" % KNOWN[u])
        elif pid == 0x42:  # remove entities
            for _ in range(b.varint()):
                eid = b.varint()
                if eid in self.entities:
                    self.event("[entity gone %s]" % self.entities.pop(eid))
        elif pid == 0x3D:  # player info remove
            names = [KNOWN.get(uuid.UUID(bytes=b.read(16)), "?") for _ in range(b.varint())]
            self.event("[tab remove %s]" % ",".join(names))
        elif pid == 0x3E:  # player info update
            actions = b.u("B")
            if actions & 1:  # ADD_PLAYER: first entry's uuid follows the count
                n = b.varint()
                u = uuid.UUID(bytes=b.read(16))
                self.event("[tab add %s%s]" % (KNOWN.get(u, "?"), "" if n == 1 else " +%d" % (n - 1)))
        elif pid == 0x33:  # open screen
            self.window = b.varint()
            mtype = b.varint()
            title = flatten(read_nbt(b))
            self.event("[open %s \"%s\"]" % (MENUS[mtype] if 0 <= mtype < len(MENUS) else mtype, title))
        elif pid == 0x12:  # container close
            self.event("[screen closed]")
        elif pid == 0x5D:  # set health
            self.state_info["health"] = round(b.u("f"), 1)
            self.state_info["food"] = b.varint()
            if self.state_info["health"] <= 0:
                self.event("[died; respawning]")
                self.send_raw(0x09, varint(0))  # client command: respawn
        elif pid == 0x64:  # set time
            b.u("q")
            self.state_info["time"] = b.u("q")
        elif pid == 0x22:  # game event
            ev = b.u("B")
            v = b.u("f")
            if ev in (1, 2):  # 1 = start raining, 2 = stop
                self.state_info["raining"] = ev == 1
            elif ev == 7:
                self.state_info["rain"] = round(v, 2)
            elif ev == 8:
                self.state_info["thunder"] = round(v, 2)
            elif ev == 3:
                self.state_info["gamemode"] = int(v)
        elif pid == 0x38:  # abilities
            flags = b.u("b")
            self.state_info["mayfly"] = bool(flags & 4)
            self.state_info["flyspeed"] = round(b.u("f"), 3)
        elif pid == 0x0C:  # chunk batch finished
            b.varint()
            self.send_raw(0x08, struct.pack(">f", 20.0))

    def event(self, text):
        with self.lock:
            self.messages.append(text)

    # ---------------------------------------------------------------- actions
    def click(self, slot):
        # window, state id, slot, button, mode (pickup), no changed slots, nothing carried
        self.send_raw(0x0E, struct.pack(">b", self.window) + varint(0) + struct.pack(">hb", slot, 0)
                      + varint(0) + varint(0) + varint(0))

    def close_screen(self):
        self.send_raw(0x0F, struct.pack(">b", self.window))

    def use_on(self, x, y, z, face=1):
        """Right-click a block (face: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east)."""
        self.seq += 1
        pos = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
        self.send_raw(0x38, varint(0) + struct.pack(">q", pos if pos < (1 << 63) else pos - (1 << 64)) + varint(face)
                      + struct.pack(">fff?", 0.5, 0.5, 0.5, False) + varint(self.seq))

    def dig(self, x, y, z, face=1, status=0):
        """Player action: 0 start digging (breaks at once in creative), 2 finish digging."""
        self.seq += 1
        pos = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
        self.send_raw(0x24, varint(status) + struct.pack(">q", pos if pos < (1 << 63) else pos - (1 << 64))
                      + struct.pack(">b", face) + varint(self.seq))

    def write_sign(self, x, y, z, lines, front=True):
        """Sends the text for a sign the server opened the editor for."""
        pos = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
        body = struct.pack(">q", pos if pos < (1 << 63) else pos - (1 << 64)) + (b"\x01" if front else b"\x00")
        for i in range(4):
            body += mcstr(lines[i] if i < len(lines) else "")
        self.send_raw(0x35, body)

    def use(self):
        self.seq += 1
        self.send_raw(0x39, varint(0) + varint(self.seq) + struct.pack(">ff", 0.0, 0.0))

    def command(self, cmd):
        self.send_raw(0x04, mcstr(cmd.lstrip("/")))

    def chat(self, text):
        self.send_raw(0x06, mcstr(text) + struct.pack(">qq", int(time.time() * 1000), 0) + b"\x00" + varint(0) + b"\x00\x00\x00")

    def move(self, dx=0.5):
        if self.pos:
            self.pos[0] += dx
            self.send_raw(0x1A, struct.pack(">ddd?", self.pos[0], self.pos[1], self.pos[2], True))

    def take(self):
        with self.lock:
            out, self.messages = self.messages, []
        return out

    def close(self):
        self.alive = False
        try:
            # shutdown, not just close: the reader's file object keeps the socket open otherwise,
            # and the server wouldn't notice the bot leaving.
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass
