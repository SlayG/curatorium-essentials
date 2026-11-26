"""Rebuilds neoforge_channels.json: the network channels (and versions) the dev server's mods need.

Run it whenever the server's mods change: python3 -I discover_channels.py <server-port>

NeoForge refuses clients that don't have every required channel. The bot learns them from the
server itself: it connects claiming nothing, and the server's refusal names every missing channel;
it reconnects claiming those with a wrong version, and the refusal names the right versions and
directions. NeoForge's own configuration channels (always version 1) are added at the end.
"""
import json, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mcbot
from mcbot import Bot, varint, mcstr, read_nbt, flatten

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25570
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "neoforge_channels.json")


class Probe(Bot):
    claims = {}
    failures = []

    def neoforge_query(self):
        out = varint(2)
        for proto in (1, 4):
            out += varint(proto) + varint(len(self.claims))
            for cid, c in self.claims.items():
                out += mcstr(cid) + mcstr(c["version"])
                out += (b"\x01" + varint(c["flow"])) if c.get("flow") is not None else b"\x00"
                out += b"\x01"
        return out

    def on_config_payload(self, ident, b):
        if ident == "neoforge:modded_network_setup_failed":
            for _ in range(b.varint()):
                Probe.failures.append((b.string(), flatten(read_nbt(b))))
            return
        super().on_config_payload(ident, b)


def attempt(claims):
    Probe.claims, Probe.failures = claims, []
    try:
        Probe("Probe", PORT).close()
    except RuntimeError:
        pass
    return Probe.failures


chans = {}
for rnd in range(6):
    fails = attempt(chans)
    print("round %d: %d known, %d refused" % (rnd, len(chans), len(fails)))
    if not fails:
        break
    for cid, reason in fails:
        c = chans.setdefault(cid, {"version": "?", "flow": None})
        m = re.search(r"version\.mismatch:([^|>]*)\|([^|>]*)>", reason)
        if m:
            c["version"] = m.group(1) if m.group(1) != c["version"] else m.group(2)
        m = re.search(r"flow\.\w+\.(missing|mismatch):(\w+)", reason)
        if m:
            c["flow"] = 0 if m.group(2).lower().startswith("server") else 1
for cid, flow in [("config_file", 1), ("frozen_registry_sync_start", 1), ("frozen_registry", 1),
                  ("frozen_registry_sync_completed", None), ("known_registry_data_maps", 1),
                  ("extensible_enum_data", 1), ("feature_flags", 1), ("known_registry_data_maps_reply", 0),
                  ("extensible_enum_ack", 0), ("feature_flags_ack", 0)]:
    chans["neoforge:" + cid] = {"version": "1", "flow": flow}
json.dump(chans, open(OUT, "w"), indent=1, sort_keys=True)
print("wrote %d channels to %s" % (len(chans), OUT))
