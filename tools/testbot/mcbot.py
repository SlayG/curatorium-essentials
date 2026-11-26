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


class Bot:
    def __init__(self, name, port, host="127.0.0.1"):
        self.name = name
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
                getattr(self, "on_" + self.state)(pid, b)
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

    def on_config(self, pid, b):
        if pid == 0x00:  # cookie request
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
        elif pid == 0x0C:  # chunk batch finished
            b.varint()
            self.send_raw(0x08, struct.pack(">f", 20.0))

    # ---------------------------------------------------------------- actions
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
            self.sock.close()
        except OSError:
            pass
