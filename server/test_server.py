"""Protocol self-check for the PC side. Nothing touches your real mouse, keyboard or pen: Windows input calls are faked.
Run:  python server/test_server.py"""
import ctypes, os, struct, sys
import pixelpad_server as ns

PEN = "<BBBiiHbbB"
log_lines = []
ns.log = lambda *a: log_lines.append(a)

class FakeU32:
    def __init__(self): self.injected, self.touch, self.metrics = [], [], {0: 1920, 1: 1080}
    def CreateSyntheticPointerDevice(self, *a): return 1
    def DestroySyntheticPointerDevice(self, d): pass
    def InjectSyntheticPointerInput(self, dev, info, n):
        i = info._obj if hasattr(info, "_obj") else info
        if isinstance(i, ctypes.Array):   # touch contacts
            self.touch.append([(o.u.touch.pointerInfo.pointerId, o.u.touch.pointerInfo.pointerFlags, o.u.touch.pointerInfo.ptPixelLocation.x,
                                o.u.touch.pointerInfo.ptPixelLocation.y) for o in i]); return
        pi = i.u.pen.pointerInfo
        self.injected.append((pi.pointerFlags, pi.ptPixelLocation.x, pi.ptPixelLocation.y, i.u.pen.penFlags, i.u.pen.pressure))
    def GetCursorPos(self, p): p._obj.x, p._obj.y = 500, 400
    def SetCursorPos(self, x, y): self.cursor = (x, y)
    def GetSystemMetrics(self, k): return self.metrics.get(k, 0)

fake = FakeU32(); ns.u32 = fake
keys, mouse_calls = [], []
ns.key = lambda vk, up=False: keys.append((vk, up))
ns.mouse = lambda flags, dx=0, dy=0, data=0: mouse_calls.append((flags, dx, dy, data))

def pkt(mode, action=0, buttons=0, x=0, y=0, pressure=0, tx=0, ty=0, dt=0): return struct.pack(PEN, mode, action, buttons, x, y, pressure, tx, ty, dt)

# versions compare number by number, and the Desk's version matches the Android app's
assert ns.newer_version("1.1.10", "1.1.9") and ns.newer_version("1.2", "1.1.9") and not ns.newer_version("1.2.0", "1.2") and not ns.newer_version("1.1.3", "1.1.3")
import re as _re
_desk = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "pixelpad_desk.py"), encoding="utf-8").read()
_gradle = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "build.gradle.kts")
if os.path.exists(_gradle):
    assert _re.search(r'^VERSION = "([\d.]+)"', _desk, _re.M).group(1) == _re.search(r'versionName = "([\d.]+)"', open(_gradle, encoding="utf-8").read()).group(1), "Desk VERSION and versionName differ"

# the update download reports its progress, checks the checksum, and throws a tampered file away
import hashlib as _hl, tempfile as _tf, pathlib as _pl
_d = _pl.Path(_tf.mkdtemp()); (_d / "new.exe").write_bytes(os.urandom(300000))
(_d / "SHA256SUMS.txt").write_text(_hl.sha256((_d / "new.exe").read_bytes()).hexdigest() + "  new.exe\n")
_seen = []; _out = str(_d / "got.exe")
assert ns.fetch_update((_d / "new.exe").as_uri(), (_d / "SHA256SUMS.txt").as_uri(), _out, lambda g, t: _seen.append((g, t))) and os.path.getsize(_out) == 300000
assert _seen and _seen[-1] == (300000, 300000) and all(0 < g <= t for g, t in _seen), _seen
(_d / "SHA256SUMS.txt").write_text("0" * 64 + "  new.exe\n")
assert not ns.fetch_update((_d / "new.exe").as_uri(), (_d / "SHA256SUMS.txt").as_uri(), _out) and not os.path.exists(_out)

srv = ns.Server((0, 0, 1920, 1080), 1.0)
srv.smooth_ms = 0   # the checks below want packets injected at once; the playout is checked at the end
replies = []
def send(d, key="a", transport="wifi"): srv.handle(d, replies.append, key, transport)

# ping: echoes the timestamp, tells the tablet its player slot and the PC screen size
send(pkt(3, x=12345, y=777))
r = replies[-1]
assert r[1] == 1 and struct.unpack("<i", r[3:7])[0] == 12345, "ping echo / slot"
assert struct.unpack("<I", r[7:11])[0] == (1920 << 16) | 1080, "screen size in the reply"

# hello: the tablet's active-area size
send(pkt(4, x=800, y=500)); dev = srv.devices["a"]
assert dev.phone == (800, 500)

# keys: tap = down/up in the right order, hold = stays down until released, drop lets go
keys.clear(); send(pkt(6, 0x5A, 1, 0))                               # ctrl+z tap
assert keys == [(0x11, False), (0x5A, False), (0x5A, True), (0x11, True)], keys
keys.clear(); send(pkt(6, 0, 4, 1)); assert keys == [(0x12, False)] and (0, 4) in dev.held  # hold alt
srv.drop("a"); assert keys[-1] == (0x12, True), "held key released when the tablet disconnects"

# modifiers are counted: a held Ctrl survives a Ctrl+Z tap; a duplicate release is ignored
keys.clear(); send(pkt(6, 0, 1, 1), "k")                       # hold ctrl
send(pkt(6, 0x5A, 1, 0), "k")                                  # tap ctrl+z while ctrl is held
assert (0x11, True) not in keys and keys.count((0x11, False)) == 1, keys
send(pkt(6, 0, 1, 2), "k"); send(pkt(6, 0, 1, 2), "k")         # release twice (the tablet does this on purpose)
assert keys.count((0x11, True)) == 1, keys
srv.drop("k")

# tablet: absolute mapping covers the whole screen, edges stay on screen
send(pkt(4, x=800, y=500), "b"); send(pkt(1, 1, 0, 0, 0, 65535), "b")
send(pkt(1, 1, 0, 65535, 65535, 65535), "b")
flags, x0, y0, *_ = fake.injected[0]; flags2, x1, y1, *_ = fake.injected[1]
assert (x0, y0) == (0, 0) and (x1, y1) == (1919, 1079), (x0, y0, x1, y1)
# eraser + barrel flags and pressure scale
send(pkt(1, 1, 3, 100, 100, 65535), "b"); pf, pr = fake.injected[-1][3], fake.injected[-1][4]
assert pf == 1 | 6 and pr == 1024, (pf, pr)
# relative: scaled by screen / active width, clamped to the screen
send(pkt(1, 3, 0, 0, 0), "b"); n = len(fake.injected)                    # leave
send(pkt(1, 0, 4, 100, 50), "b")                                         # move by (100, 50) tablet px
_, rx, ry, *_ = fake.injected[-1]; k = 1920 / 800
assert (rx, ry) == (int(500 + 100 * k), int(400 + 50 * k)), (rx, ry)
send(pkt(1, 0, 4, 100000, 100000), "b"); assert fake.injected[-1][1:3] == (1919, 1079)

# leaving while touching: pen is lifted first (UP), at the last position, then leaves
send(pkt(1, 1, 0, 32768, 32768, 30000), "b"); n = len(fake.injected); send(pkt(1, 3), "b")
up, leave = fake.injected[n], fake.injected[n + 1]
assert up[0] & ns.UP and (up[1], up[2]) == (leave[1], leave[2]) and not leave[0] & ns.INRANGE, (up, leave)

# a tablet button's mouse click must not knock the tablet out of tablet mode
send(pkt(0, 7), "b"); send(pkt(0, 8), "b")
assert srv.devices["b"].mode == 1, "click while in tablet mode kept the mode"
assert (ns.RDOWN, 0, 0, 0) in mouse_calls and (ns.RUP, 0, 0, 0) in mouse_calls

# up to four devices, the fifth is ignored
for i in range(2, 5): send(pkt(3), f"d{i}")
before = len(srv.devices); replies.clear(); send(pkt(3), "d5")
assert before == 4 and not replies, "fifth device ignored"

for k in list(srv.devices): srv.drop(k)   # free the four slots used above

# raw multi-finger touch: contacts are placed around the cursor, in order, and lifted cleanly
send(pkt(8, 1, 0, 0, 0), "t"); send(pkt(8, 1, 1, 80, 0), "t")                 # two fingers down (cursor is at 500,400 in the fake)
send(pkt(8, 0, 0, 0, -30), "t"); send(pkt(8, 0, 1, 80, -30), "t")             # both move up by 30
send(pkt(8, 2, 0, 0, -30), "t"); send(pkt(8, 2, 1, 80, -30), "t")             # lift
T = fake.touch
assert T[0] == [(0, ns.NEW | ns.INRANGE | ns.INCONTACT | ns.DOWN, 500, 400)], T[0]
assert T[1][1] == (1, ns.NEW | ns.INRANGE | ns.INCONTACT | ns.DOWN, 580, 400) and T[1][0][1] == ns.INRANGE | ns.INCONTACT | ns.UPDATE, T[1]
assert T[-1] == [(1, ns.UP, 580, 370)] and not srv.devices["t"].touch.c, T[-1]
n = len(T); send(pkt(8, 2, 1, 80, -30), "t"); assert len(T) == n     # the tablet sends a release twice: the extra one does nothing
send(pkt(8, 1, 0, 0, 0), "t"); send(pkt(8, 1, 0, 5, 0), "t")            # a repeated DOWN for a finger that is down is only a move
assert T[-1][0][1] == ns.INRANGE | ns.INCONTACT | ns.UPDATE, T[-1]
srv.drop("t")

# the pen cursor ring can be changed from the tablet
send(pkt(7, 2, 0, 150), "r"); send(pkt(7, 3, 0, 1), "r"); send(pkt(7, 4, 0, 9), "r"); send(pkt(7, 1, 0, 0), "r")
assert srv.ring["size"] == 150 and srv.ring["style"] == 1 and srv.ring["color"] == 3 and srv.ring["on"] is False, srv.ring
send(pkt(7, 2, 0, 5000), "r"); assert srv.ring["size"] == 300
srv.drop("r")
send(pkt(3), "c"); assert srv.devices["c"].colour is None                      # nothing is assigned on the PC: no colour until the tablet says
send(pkt(3, 0, 0, 0, 0, 0, 3), "c"); assert srv.devices["c"].colour == 2        # the colour rides in every ping (byte 13 = index + 1)
send(pkt(3, 0, 0, 0, 0, 0, 8), "c"); assert srv.devices["c"].colour == 7        # all eight Switch colours come through unchanged
send(pkt(7, 7, 0, 5), "c"); assert srv.devices["c"].colour == 5                  # older apps sent it as a setting
srv.drop("c")

# a mouse-button shortcut held on the tablet: the pen becomes that mouse button (moves the cursor, touching presses it), then goes back to being a pen
for k in list(srv.devices): srv.drop(k)
send(pkt(1, 0, 0, 32768, 32768, 800), "p")                                       # a normal pen: hover, then touch
n0 = len(fake.injected); mouse_calls.clear()
send(pkt(1, 0, 16, 65535, 0, 0), "p")                                            # right-click shortcut held, pen hovering: only the cursor moves
assert fake.cursor == (1919, 0) and not mouse_calls, (fake.cursor, mouse_calls)
assert fake.injected[n0][0] == ns.UPDATE, fake.injected[n0:]                      # the real pen was lifted out of range first (a single leave frame)
n1 = len(fake.injected)
send(pkt(1, 1, 16, 32768, 32768, 900), "p"); assert mouse_calls == [(ns.RDOWN, 0, 0, 0)], mouse_calls   # touching = right button down
send(pkt(1, 1, 16, 40000, 32768, 900), "p"); assert fake.cursor[0] > 1000 and len(mouse_calls) == 1       # dragging keeps it down
send(pkt(1, 0, 16, 40000, 32768, 0), "p"); assert mouse_calls[-1] == (ns.RUP, 0, 0, 0), mouse_calls      # lifting releases it
assert len(fake.injected) == n1, "no pen frames while it acts as a mouse"
send(pkt(1, 1, 8, 1000, 1000, 900), "p"); send(pkt(1, 0, 0, 1000, 1000, 0), "p")                         # left shortcut let go mid-stroke: the button is released
assert (ns.LDOWN, 0, 0, 0) in mouse_calls and (ns.LUP, 0, 0, 0) in mouse_calls, mouse_calls
send(pkt(1, 0, 0, 20000, 20000, 0), "p"); assert fake.injected[-1][0] & ns.INRANGE                         # and the pen is a pen again
srv.drop("p")

# no app-made shortcuts on the trackpad: scroll / zoom / gesture actions are gone, plain clicks remain
mouse_calls.clear(); keys.clear()
for a in (4, 5, 6): send(pkt(0, a, 0, 3, 3), "m")
assert not mouse_calls and not keys, (mouse_calls, keys)
srv.drop("m")

# recording a shortcut from the PC keyboard (the keyboard hook itself is faked)
srv.recorder.start = lambda done: (done((0x5A, 1)), True)[1]   # start() returns True when it began
replies.clear(); send(pkt(9, 1), "q"); assert struct.unpack("<i", replies[-1][3:7])[0] == 3   # off by default: anyone on the network could ask
srv.allow_record = True; replies.clear(); send(pkt(9, 1), "q"); r = replies[-1]
assert r[0] == 9 and r[1] == 0x5A and r[2] == 1 and struct.unpack("<i", r[3:7])[0] == 1, r
srv.allow_record = False; send(pkt(9, 1), "q"); assert struct.unpack("<i", replies[-1][3:7])[0] == 3
srv.allow_record = True; srv.recorder.start = lambda done: (done(None), True)[1]; send(pkt(9, 1), "q"); assert struct.unpack("<i", replies[-1][3:7])[0] == 2
srv.drop("q")

# pen playout: packets come out in order, with the spacing the tablet measured, even if they arrive in a burst
import time
for k in list(srv.devices): srv.drop(k)
srv.smooth_ms = 20; n0 = len(fake.injected); t0 = time.time()
send(pkt(1, 0, 0, 100, 100, dt=255), "p")                       # fresh start
send(pkt(1, 0, 0, 200, 100, dt=30), "p"); send(pkt(1, 0, 0, 300, 100, dt=30), "p")   # arrive together, meant 30 ms apart
time.sleep(0.4)
got = fake.injected[n0:]
xs = [g[1] for g in got]; assert xs == [2, 5, 8], xs   # same order they were sent
srv.drop("p"); srv.smooth_ms = 0

# a lone tablet is always player 1: a Wi-Fi reconnect (new port) replaces the old entry, and the last one left moves up
for k in list(srv.devices): srv.drop(k)
send(pkt(3), ("udp", "1.2.3.4", 1000), "wifi"); send(pkt(3), ("udp", "1.2.3.4", 1001), "wifi")
assert [d.slot for d in srv.devices.values()] == [1] and len(srv.devices) == 1, [d.slot for d in srv.devices.values()]
send(pkt(3), ("udp", "5.6.7.8", 2000), "wifi"); assert sorted(d.slot for d in srv.devices.values()) == [1, 2]
srv.drop(("udp", "1.2.3.4", 1001)); assert [d.slot for d in srv.devices.values()] == [1]
for k in list(srv.devices): srv.drop(k)

# custom area and a chosen screen
srv.area_mode, srv.custom = "custom", (100, 200, 640, 480)
assert srv.area() == (100, 200, 640, 480)
srv.area_mode, srv.monitor = "full", (1920, 0, 2560, 1440)
assert srv.area() == (1920, 0, 2560, 1440)
# ---- pairing: sealed frames (the app's side is written out here, as android/.../Seal.kt does it) ----
import hashlib as _hl2, hmac as _hm
KEY = bytes(range(16))

class AppSeal:
    def __init__(self, key=KEY, sid=b"\x01\x02\x03\x04"): self.key, self.sid, self.ctr, self.nonce = key, sid, 0, bytes(8)
    def h(self, *p): return _hm.new(self.key, b"".join(p), _hl2.sha256).digest()
    def seal(self, pt, discovery=False, ctr=None):
        n = bytes(8) if discovery else self.nonce
        c = (self.ctr if ctr is None else ctr).to_bytes(4, "little")
        if ctr is None: self.ctr += 1
        ct = bytes(a ^ b for a, b in zip(pt, self.h(b"E", n, self.sid, c)))
        return b"\xA5" + self.sid + c + ct + self.h(b"T", n, b"\xA5", self.sid, c, ct)[:8]
    def open(self, f):
        assert len(f) == 37 and f[0] == 0xA6, f
        n, c, ct, tag = f[1:9], f[9:13], f[13:29], f[29:37]
        assert _hm.compare_digest(self.h(b"S", b"\xA6", n, c, ct)[:8], tag), "reply tag"
        return n, bytes(a ^ b for a, b in zip(ct, self.h(b"R", n, c)))

class FakeUdp:
    """Feeds datagrams to Server.udp_loop and records what it sends back."""
    def __init__(self, items): self.items, self.sent = list(items), []
    def recvfrom(self, n):
        if not self.items: raise OSError("done")
        return self.items.pop(0)
    def sendto(self, b, a): self.sent.append((b, a))

def run_udp(items):
    for k in list(srv.devices): srv.drop(k)
    fu = FakeUdp(items); srv.usock = fu; srv.udp_loop(); return fu.sent

srv.pair, srv.allow_legacy = ns.Pairing(KEY), False
A, ADDR = AppSeal(), ("9.9.9.9", 4000)
ping = pkt(3, x=4242)
# an unauthenticated (plain) packet is refused: no reply, no player slot
assert run_udp([(ping, ADDR)]) == [] and not srv.devices
# a discovery ping is answered with this run's nonce, sealed, and creates nothing
out = run_udp([(A.seal(ping, discovery=True), ADDR)])
assert len(out) == 1 and not srv.devices
nonce, pt = A.open(out[0][0]); assert nonce == srv.pair.nonce and pt[0] == 3 and struct.unpack("<i", pt[3:7])[0] == 4242
A.nonce = nonce
# only a ping may be sealed with the nonce 0 (a key press sealed that way, e.g. a replay with the nonce stripped, does nothing)
keys.clear(); assert run_udp([(A.seal(pkt(6, 0x5A, 1, 0), discovery=True), ADDR)]) == [] and keys == [] and not srv.devices
# a sealed ping with the real nonce creates the player and gets a sealed reply carrying the slot
out = run_udp([(A.seal(ping), ADDR)]); assert len(out) == 1 and list(srv.devices) == [("udp",) + ADDR]
assert A.open(out[0][0])[1][1] == 1, "slot in the sealed reply"
# a sealed key press works; the same frame sent again (a replay) does nothing
frame = A.seal(pkt(6, 0x5A, 1, 0)); keys.clear()
run_udp_keep = lambda items: (setattr(srv, "usock", FakeUdp(items)), srv.udp_loop())
run_udp_keep([(frame, ADDR)]); assert keys == [(0x11, False), (0x5A, False), (0x5A, True), (0x11, True)], keys
keys.clear(); run_udp_keep([(frame, ADDR)]); assert keys == [], "replayed frame must not run twice"
# a frame with a flipped bit, a wrong key, and one from another PC run are all dropped
bad = bytearray(A.seal(pkt(6, 0x5A, 1, 0))); bad[12] ^= 1
keys.clear(); run_udp_keep([(bytes(bad), ADDR)]); assert keys == []
run_udp_keep([(AppSeal(bytes(16)).seal(pkt(6, 0x5A, 1, 0)), ADDR)]); assert keys == []
old_run = AppSeal(); old_run.nonce = bytes(range(8)); run_udp_keep([(old_run.seal(pkt(6, 0x5A, 1, 0)), ADDR)]); assert keys == []
# frames may arrive out of order within the window, but not twice
f1, f2, f3 = A.seal(pkt(6, 0x5A, 1, 0)), A.seal(pkt(6, 0x5A, 1, 0)), A.seal(pkt(6, 0x5A, 1, 0))
keys.clear(); run_udp_keep([(f3, ADDR), (f1, ADDR), (f2, ADDR), (f2, ADDR)]); assert len(keys) == 12, len(keys)
# the sealed reply cannot be read without the key and is new each time
r1 = A.open(run_udp([(A.seal(ping), ADDR)])[0][0]); r2 = A.open(run_udp([(A.seal(ping), ADDR)])[0][0]); assert r1[1][1] == 1
# sealed TCP (USB): frames are read from the stream; a plain packet on a paired TCP link closes it
class FakeTcp:
    def __init__(self, data): self.data, self.out, self.closed = [data], b"", False
    def setsockopt(self, *a): pass
    def recv(self, n): return self.data.pop(0) if self.data else b""
    def sendall(self, b): self.out += b
    def close(self): self.closed = True
for k in list(srv.devices): srv.drop(k)
t = FakeTcp(A.seal(ping) + A.seal(pkt(6, 0x5A, 1, 0))[:10]); srv.serve_tcp(t, ("tcp", "127.0.0.1", 5)); assert len(t.out) == 37 and t.closed, len(t.out)
t = FakeTcp(ping); srv.serve_tcp(t, ("tcp", "127.0.0.1", 6)); assert t.out == b"" and t.closed and not srv.devices
# an old app (plain packets) works again when old apps are allowed
srv.allow_legacy = True; assert len(run_udp([(ping, ADDR)])) == 1
# the sealed key and a spoofed device cannot take player slots: five unauthenticated sources create nothing
srv.allow_legacy = False
assert run_udp([(ping, ("7.7.7.%d" % i, 1)) for i in range(5)]) == [] and not srv.devices
for k in list(srv.devices): srv.drop(k)
srv.pair, srv.allow_legacy = None, True

# the update download needs a checksum, with a line for this exact file
import pathlib as _pl2, tempfile as _tf2
_d2 = _pl2.Path(_tf2.mkdtemp()); (_d2 / "x.exe").write_bytes(b"hello" * 1000); _out2 = str(_d2 / "got.exe")
assert not ns.fetch_update((_d2 / "x.exe").as_uri(), None, _out2), "no checksum file: refused"
(_d2 / "S.txt").write_text(_hl2.sha256(b"other").hexdigest() + "  y.exe\n")
assert not ns.fetch_update((_d2 / "x.exe").as_uri(), (_d2 / "S.txt").as_uri(), _out2), "no line for this file: refused"
(_d2 / "S.txt").write_text(_hl2.sha256(b"hello" * 1000).hexdigest() + "  evil-x.exe\n")
assert not ns.fetch_update((_d2 / "x.exe").as_uri(), (_d2 / "S.txt").as_uri(), _out2), "a line for a differently named file: refused"
(_d2 / "S.txt").write_text(_hl2.sha256(b"hello" * 1000).hexdigest() + "  x.exe\n")
assert ns.fetch_update((_d2 / "x.exe").as_uri(), (_d2 / "S.txt").as_uri(), _out2) and os.path.getsize(_out2) == 5000
print("all protocol checks passed")
