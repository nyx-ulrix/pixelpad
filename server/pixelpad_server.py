"""PixelPad Desk: the Windows side of PixelPad. Receives stylus packets from the phone and turns them
into cursor moves, pen input, key presses or a virtual DualShock 4.

Packets are 16 bytes. Over USB they arrive on TCP (adb reverse has no UDP), over Wi-Fi on UDP.
  pen/trackpad/key: <BBBiiHbbB   mode, action, buttons, x, y, pressure, tiltx, tilty, ms since the previous pen sample
  controller:       <BHbbbbBB7x  mode, buttons, lx, ly, rx, ry, lt, rt
Modes: 0 trackpad, 1 tablet, 2 controller, 3 ping, 4 hello, 6 key (action = virtual key, buttons = modifiers, x = 0 tap / 1 down / 2 up),
       7 config (action = which setting of the pen cursor ring, x = value), 8 touch (action = 0 move / 1 down / 2 up, buttons = contact number,
       x, y = offset in PC pixels from where the cursor was when the first finger went down),
       9 record (tablet -> PC: action 1 = listen for one shortcut on the PC keyboard, 0 = cancel; PC -> tablet: action = virtual key,
       buttons = modifiers, x = 1 recorded / 2 nothing / 3 recording is switched off)
"""
import argparse, collections, ctypes, hashlib, heapq, hmac, json, os, re, shutil, socket, struct, subprocess, threading, time, urllib.request
from ctypes import wintypes as w

log = print  # the desktop app swaps this for its own log window
PEN_FMT, PAD_FMT, SIZE = "<BBBiiHbbB", "<BHbbbbBB7x", 16
TRACKPAD, TABLET, CONTROLLER, PING, HELLO, KEY, CONFIG, TOUCH, REC = 0, 1, 2, 3, 4, 6, 7, 8, 9
MAX_DEVICES = 4
FRAME_IN, FRAME_OUT, MARK_IN, MARK_OUT = 33, 37, 0xA5, 0xA6   # sealed packets: app -> PC, PC -> app (see class Pairing)
u32 = ctypes.windll.user32
u32.SetProcessDPIAware()

def _timer(on):
    """1 ms timer resolution while a tablet is connected (pen samples are replayed on time); the default when idle, to save power."""
    try: (ctypes.windll.winmm.timeBeginPeriod if on else ctypes.windll.winmm.timeEndPeriod)(1)
    except Exception: pass

# ---------- mouse and keyboard events ----------
class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", w.LONG), ("dy", w.LONG), ("data", w.DWORD), ("flags", w.DWORD),
                ("time", w.DWORD), ("extra", ctypes.c_void_p)]

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("vk", w.WORD), ("scan", w.WORD), ("flags", w.DWORD), ("time", w.DWORD), ("extra", ctypes.c_void_p)]

class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]

class INPUT(ctypes.Structure):
    _fields_ = [("type", w.DWORD), ("u", _U)]

def mouse(flags, dx=0, dy=0, data=0):
    i = INPUT(0); i.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, None)
    u32.SendInput(1, ctypes.byref(i), ctypes.sizeof(INPUT))

EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E}  # arrows, home/end, page up/down, insert/delete

def key(vk, up=False):
    i = INPUT(1); i.u.ki = KEYBDINPUT(vk, 0, (2 if up else 0) | (1 if vk in EXTENDED else 0), 0, None)
    u32.SendInput(1, ctypes.byref(i), ctypes.sizeof(INPUT))

_down = {}  # virtual key -> how many things are holding it

def kdown(vk):
    _down[vk] = _down.get(vk, 0) + 1
    if _down[vk] == 1: key(vk)

def kup(vk):
    n = _down.get(vk, 0)
    if n <= 1: _down.pop(vk, None); key(vk, True)
    else: _down[vk] = n - 1

def combo(*vks):
    for v in vks: kdown(v)
    for v in reversed(vks): kup(v)

MODS = ((1, 0x11), (2, 0x10), (4, 0x12), (8, 0x5B))  # ctrl, shift, alt, win

def key_event(vk, mods, phase):
    """phase 0 = tap, 1 = press and hold, 2 = release."""
    seq = [v for bit, v in MODS if mods & bit] + ([vk] if vk else [])
    if phase == 0: combo(*seq)
    elif phase == 1:
        for v in seq: kdown(v)
    else:
        for v in reversed(seq): kup(v)

MOVE, LDOWN, LUP, RDOWN, RUP, WHEEL, HWHEEL, MDOWN, MUP = 1, 2, 4, 8, 16, 0x800, 0x1000, 0x20, 0x40

def trackpad(a, x, y):
    """Movement is passed through untouched: Windows applies the user's own pointer speed and acceleration."""
    if a == 0: mouse(MOVE, x, y)
    elif a == 1: mouse(LDOWN)
    elif a == 2: mouse(LUP)
    elif a == 3: mouse(RDOWN); mouse(RUP)
    elif a == 7: mouse(RDOWN)
    elif a == 8: mouse(RUP)
    elif a == 9: mouse(MDOWN)
    elif a == 10: mouse(MUP)

# ---------- monitors ----------
_MONPROC = ctypes.WINFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(w.RECT), ctypes.c_void_p)

def monitors():
    """Every screen as (x, y, w, h); the first one is the main screen."""
    out = []
    def cb(hm, hdc, rc, data):
        r = rc.contents; out.append((r.left, r.top, r.right - r.left, r.bottom - r.top)); return 1
    u32.EnumDisplayMonitors(None, None, _MONPROC(cb), 0)
    main = (0, 0, u32.GetSystemMetrics(0), u32.GetSystemMetrics(1))
    return [main] + [m for m in out if m != main]

def virtual_screen():
    return (u32.GetSystemMetrics(76), u32.GetSystemMetrics(77), u32.GetSystemMetrics(78), u32.GetSystemMetrics(79))

# ---------- tablet: Windows' built-in synthetic pen device (pressure, tilt, hover) ----------
class POINTER_INFO(ctypes.Structure):
    _fields_ = [("pointerType", w.DWORD), ("pointerId", ctypes.c_uint32), ("frameId", ctypes.c_uint32),
                ("pointerFlags", ctypes.c_uint32), ("sourceDevice", w.HANDLE), ("hwndTarget", w.HWND),
                ("ptPixelLocation", w.POINT), ("ptHimetricLocation", w.POINT),
                ("ptPixelLocationRaw", w.POINT), ("ptHimetricLocationRaw", w.POINT),
                ("dwTime", w.DWORD), ("historyCount", ctypes.c_uint32), ("InputData", ctypes.c_int32),
                ("dwKeyStates", w.DWORD), ("PerformanceCount", ctypes.c_uint64), ("ButtonChangeType", ctypes.c_uint32)]

class POINTER_PEN_INFO(ctypes.Structure):
    _fields_ = [("pointerInfo", POINTER_INFO), ("penFlags", ctypes.c_uint32), ("penMask", ctypes.c_uint32),
                ("pressure", ctypes.c_uint32), ("rotation", ctypes.c_uint32),
                ("tiltX", ctypes.c_int32), ("tiltY", ctypes.c_int32)]

class POINTER_TOUCH_INFO(ctypes.Structure):
    _fields_ = [("pointerInfo", POINTER_INFO), ("touchFlags", ctypes.c_uint32), ("touchMask", ctypes.c_uint32),
                ("rcContact", w.RECT), ("rcContactRaw", w.RECT), ("orientation", ctypes.c_uint32), ("pressure", ctypes.c_uint32)]

class _PTU(ctypes.Union):
    _fields_ = [("touch", POINTER_TOUCH_INFO), ("pen", POINTER_PEN_INFO)]

class POINTER_TYPE_INFO(ctypes.Structure):
    _fields_ = [("type", w.DWORD), ("u", _PTU)]

u32.CreateSyntheticPointerDevice.restype = ctypes.c_void_p
u32.DestroySyntheticPointerDevice.argtypes = [ctypes.c_void_p]
u32.InjectSyntheticPointerInput.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32]
NEW, INRANGE, INCONTACT, FIRST, DOWN, UPDATE, UP = 1, 2, 4, 0x10, 0x10000, 0x20000, 0x40000

class Pen:
    def __init__(self, srv, owner):
        self.owner = owner
        self.dev = u32.CreateSyntheticPointerDevice(3, 1, 1)  # PT_PEN, 1 contact, default feedback
        if not self.dev: raise OSError("could not create the pen device (needs Windows 10+)")
        self.srv, self.inrange, self.contact, self.pos, self.last = srv, False, False, None, 0.0
        self.lastpos = (0, 0)
        self.mbtn = 0   # the mouse button (8 left, 16 right, 32 middle) the pen is holding down while it acts as a mouse, else 0

    def close(self):
        self._mouse_release()
        if self.dev: u32.DestroySyntheticPointerDevice(ctypes.c_void_p(self.dev)); self.dev = None

    def _map(self, buttons, x, y):
        """Where on the PC screen the pen is: absolute (0..65535 across the area) or relative (movement, like a mouse)."""
        ax, ay, aw, ah = self.srv.area()
        if buttons & 4:  # relative: x, y are movement deltas in phone pixels, like a mouse
            if self.pos is None or not self.inrange:
                pt = w.POINT(); u32.GetCursorPos(ctypes.byref(pt)); self.pos = [pt.x, pt.y]
            k = self.srv.rel_scale(self.owner.phone)
            self.pos[0] = min(max(self.pos[0] + x * k, ax), ax + aw - 1)
            self.pos[1] = min(max(self.pos[1] + y * k, ay), ay + ah - 1)
            return int(self.pos[0]), int(self.pos[1])
        return ax + min(x * aw // 65535, aw - 1), ay + min(y * ah // 65535, ah - 1)   # never one pixel past the edge

    def _mouse_release(self):
        if self.mbtn: mouse({8: LUP, 16: RUP, 32: MUP}[self.mbtn]); self.mbtn = 0

    def _as_mouse(self, a, buttons, ov, x, y):
        """A mouse-button shortcut is held on the tablet: the pen is a mouse for now. It moves the cursor, and touching presses that button."""
        self.last = time.time()
        if self.inrange: self.send(3, 0, 0, 0, 0, 0, 0)   # hand over: lift the real pen out of range so Windows sees only the mouse
        if a == 3: self._mouse_release(); return
        px, py = self._map(buttons, x, y)
        u32.SetCursorPos(px, py); self.lastpos = (px, py)
        if a == 1 and not self.mbtn: self.mbtn = ov; mouse({8: LDOWN, 16: RDOWN, 32: MDOWN}[ov])
        elif a != 1: self._mouse_release()

    def send(self, a, buttons, x, y, pressure, tx, ty):
        """buttons: 1 = pen barrel button, 2 = eraser end, 4 = x and y are relative movement, 8 / 16 / 32 = act as the left / right / middle mouse button instead of a pen."""
        ov = buttons & 56
        if self.mbtn and ov != self.mbtn: self._mouse_release()   # the shortcut was let go (or changed) mid-stroke
        if ov: return self._as_mouse(a, buttons, ov, x, y)
        if a == 3 and not self.inrange: return
        self.last = time.time()
        px, py = self._map(buttons, x, y)
        t = 0 if a == 3 else (2 if a == 1 else 1)  # 0 leave, 1 hover, 2 touching
        if t == 0:   # leaving: stay where the pen last was (the leave packet carries no position)
            px, py = self.lastpos
            if self.contact:  # lift the pen off the surface first, then let it leave
                self.contact = False
                self._frame(INRANGE | UP, px, py, buttons, pressure, tx, ty)
        else: self.lastpos = (px, py)
        f = 0 if t == 0 else INRANGE
        if t and not self.inrange: f |= NEW
        if t == 2: f |= INCONTACT | FIRST | (UPDATE if self.contact else DOWN)
        elif self.contact: f |= UP
        else: f |= UPDATE
        if t == 0: f = UPDATE
        self.inrange, self.contact = t > 0, t == 2
        self._frame(f, px, py, buttons, pressure, tx, ty)

    def _frame(self, f, px, py, buttons, pressure, tx, ty):
        i = POINTER_TYPE_INFO(); i.type = 3
        p = i.u.pen; pi = p.pointerInfo
        pi.pointerType, pi.pointerId, pi.pointerFlags = 3, 0, f
        pi.ptPixelLocation.x, pi.ptPixelLocation.y = px, py
        p.penFlags = (1 if buttons & 1 else 0) | (6 if buttons & 2 else 0)  # PEN_FLAG_BARREL; PEN_FLAG_INVERTED | PEN_FLAG_ERASER
        p.penMask = 1 | 4 | 8                  # pressure, tilt x, tilt y
        p.pressure, p.tiltX, p.tiltY = min(pressure * 1024 // 65535, 1024), tx, ty
        u32.InjectSyntheticPointerInput(self.dev, ctypes.byref(i), 1)

class Touch:
    """Forwards two or more fingers to Windows as real touch contacts around the cursor, so Windows' own touch gestures
    (scroll, pinch, three- and four-finger swipes) work exactly as you have them set up. No shortcuts are involved."""
    def __init__(self, srv):
        self.dev = u32.CreateSyntheticPointerDevice(2, 10, 1)  # PT_TOUCH, up to 10 contacts, default feedback
        if not self.dev: raise OSError("could not create the touch device (needs Windows 10+)")
        self.srv, self.c, self.anchor = srv, {}, None          # c: contact number -> [x, y, state]

    def event(self, phase, cid, ox, oy):
        if phase == 1 and not self.c:
            pt = w.POINT(); u32.GetCursorPos(ctypes.byref(pt)); self.anchor = (pt.x, pt.y)
        if self.anchor is None: return
        mx, my, mw, mh = self.srv.monitor
        x = min(max(self.anchor[0] + ox, mx), mx + mw - 1); y = min(max(self.anchor[1] + oy, my), my + mh - 1)
        if phase == 1 and cid not in self.c: self.c[cid] = [x, y, "down"]
        elif cid in self.c:
            self.c[cid][0], self.c[cid][1] = x, y
            if phase == 2: self.c[cid][2] = "up"
        else: return
        self._inject()

    def _inject(self):
        items = sorted(self.c.items()); n = len(items)
        arr = (POINTER_TYPE_INFO * n)()
        for k, (cid, (x, y, st)) in enumerate(items):
            arr[k].type = 2  # PT_TOUCH
            t = arr[k].u.touch; pi = t.pointerInfo
            pi.pointerType, pi.pointerId = 2, cid
            pi.pointerFlags = {"down": NEW | INRANGE | INCONTACT | DOWN, "move": INRANGE | INCONTACT | UPDATE, "up": UP}[st]
            pi.ptPixelLocation.x, pi.ptPixelLocation.y = x, y
            t.touchMask = 1 | 2 | 4  # contact area, orientation, pressure
            t.rcContact.left, t.rcContact.top, t.rcContact.right, t.rcContact.bottom = x - 3, y - 3, x + 3, y + 3
            t.orientation, t.pressure = 90, 512
        u32.InjectSyntheticPointerInput(self.dev, ctypes.byref(arr), n)
        for cid in [k for k, v in self.c.items() if v[2] == "up"]: del self.c[cid]
        for v in self.c.values():
            if v[2] == "down": v[2] = "move"

    def release_all(self):
        for v in self.c.values(): v[2] = "up"
        if self.c: self._inject()

    def close(self):
        if self.dev: u32.DestroySyntheticPointerDevice(ctypes.c_void_p(self.dev)); self.dev = None

# ---------- recording a shortcut from the PC keyboard ----------
_LRESULT = ctypes.c_ssize_t
_HOOKPROC = ctypes.WINFUNCTYPE(_LRESULT, ctypes.c_int, w.WPARAM, w.LPARAM)
_MODBITS = {0x10: 2, 0xA0: 2, 0xA1: 2, 0x11: 1, 0xA2: 1, 0xA3: 1, 0x12: 4, 0xA4: 4, 0xA5: 4, 0x5B: 8, 0x5C: 8}
u32.SetWindowsHookExW.argtypes = [ctypes.c_int, _HOOKPROC, w.HINSTANCE, w.DWORD]; u32.SetWindowsHookExW.restype = ctypes.c_void_p
u32.CallNextHookEx.argtypes = [ctypes.c_void_p, ctypes.c_int, w.WPARAM, w.LPARAM]; u32.CallNextHookEx.restype = _LRESULT
u32.UnhookWindowsHookEx.argtypes = [ctypes.c_void_p]
_k32 = ctypes.WinDLL("kernel32"); _k32.GetModuleHandleW.argtypes = [w.LPCWSTR]; _k32.GetModuleHandleW.restype = w.HMODULE

class KBDLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [("vkCode", w.DWORD), ("scanCode", w.DWORD), ("flags", w.DWORD), ("time", w.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

class KeyRecorder:
    """Listens for ONE shortcut on the PC keyboard, only when the tablet asks for it, for at most 15 seconds, and swallows those
    keys so nothing else happens on the PC. Esc alone cancels. Turn it off in PixelPad Desk's settings."""
    def __init__(self): self.busy, self.stop = False, False

    def start(self, done, timeout=15.0):
        if self.busy: return False
        self.busy, self.stop = True, False
        threading.Thread(target=self._run, args=(done, timeout), daemon=True).start()
        return True

    def cancel(self): self.stop = True

    def _run(self, done, timeout):
        down, seen, result = set(), [0], [None]
        def mods(): return sum({_MODBITS[v] for v in down})
        def proc(n, wp, lp):
            if n == 0:
                k = ctypes.cast(lp, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
                if not (k.flags & 0x10):  # our own injected keys pass through
                    press, vk = wp in (0x100, 0x104), k.vkCode
                    if vk in _MODBITS:
                        if press: down.add(vk); seen[0] |= mods()
                        else:
                            down.discard(vk)
                            if not down and result[0] is None and seen[0]: result[0] = (0, seen[0])   # a lone modifier
                    elif press and result[0] is None:
                        if vk == 0x1B and not mods(): self.stop = True   # Esc on its own cancels
                        else: result[0] = (vk, mods())
                    return 1
            return u32.CallNextHookEx(None, n, wp, lp)
        cb = _HOOKPROC(proc)  # keep a reference so it isn't collected while the hook is live
        hook = u32.SetWindowsHookExW(13, cb, _k32.GetModuleHandleW(None), 0)
        msg = w.MSG(); end = time.time() + timeout
        try:
            while hook and not self.stop and result[0] is None and time.time() < end:
                while u32.PeekMessageW(ctypes.byref(msg), None, 0, 0, 1): u32.TranslateMessage(ctypes.byref(msg)); u32.DispatchMessageW(ctypes.byref(msg))
                time.sleep(0.005)
        finally:
            if hook: u32.UnhookWindowsHookEx(hook)
            self.busy = False
        done(result[0])

# ---------- controller: ViGEm DualShock 4 ----------
BTN = ["CROSS", "CIRCLE", "SQUARE", "TRIANGLE", "SHOULDER_LEFT", "SHOULDER_RIGHT", "THUMB_LEFT", "THUMB_RIGHT", "SHARE", "OPTIONS"]
DPAD = {(1, 0, 0, 0): "NORTH", (1, 0, 0, 1): "NORTHEAST", (0, 0, 0, 1): "EAST", (0, 1, 0, 1): "SOUTHEAST",
        (0, 1, 0, 0): "SOUTH", (0, 1, 1, 0): "SOUTHWEST", (0, 0, 1, 0): "WEST", (1, 0, 1, 0): "NORTHWEST"}

class Pad:
    def __init__(self):
        import vgamepad as vg  # needs the ViGEmBus driver: https://github.com/nefarius/ViGEmBus/releases
        self.vg, self.pad = vg, vg.VDS4Gamepad()

    def send(self, b, lx, ly, rx, ry, lt, rt):
        vg, p = self.vg, self.pad
        for i, n in enumerate(BTN):
            (p.press_button if b >> i & 1 else p.release_button)(getattr(vg.DS4_BUTTONS, "DS4_BUTTON_" + n))
        for bit, n in ((10, "PS"), (11, "TOUCHPAD")):
            (p.press_special_button if b >> bit & 1 else p.release_special_button)(getattr(vg.DS4_SPECIAL_BUTTONS, "DS4_SPECIAL_BUTTON_" + n))
        d = DPAD.get(tuple(b >> k & 1 for k in (12, 13, 14, 15)), "NONE")
        p.directional_pad(getattr(vg.DS4_DPAD_DIRECTIONS, "DS4_BUTTON_DPAD_" + d))
        # the packet has up as positive; the DualShock 4 axis has up as negative (its raw 0 is up), so up/down are flipped here
        p.left_joystick_float(lx / 127, -ly / 127); p.right_joystick_float(rx / 127, -ry / 127)
        p.left_trigger(lt); p.right_trigger(rt)
        p.update()

# ---------- pairing: sealed packets ----------
class Pairing:
    """Pairing security. The Desk shows a pairing key (QR code); with it every 16-byte packet travels in a sealed frame, so nobody else on the
    network or the USB tunnel can send input to this PC, read what is sent, or replay a recording of it.
      app -> PC (33 bytes): 0xA5, session id (4), counter (4), packet XOR keystream (16), tag (8)
      PC -> app (37 bytes): 0xA6, this run's nonce (8), counter (4), packet XOR keystream (16), tag (8)
    The keystream and the tag are HMAC-SHA256 of the key over a label, the nonce and the counters (the tag covers the encrypted packet and is cut
    to 8 bytes). The nonce is new every time the Desk starts, so nothing recorded earlier works; a counter per session id makes a frame usable once
    (sliding window of 64, for UDP reordering). An app learns the nonce from a reply to a "discovery" ping, sealed with the nonce 0, which is only
    ever accepted for a ping and creates nothing. The same scheme in Kotlin is android/.../Seal.kt: change them together."""
    def __init__(self, key):
        self.key, self.nonce, self.rc = bytes(key), os.urandom(8), 0
        self.sessions, self.lock = collections.OrderedDict(), threading.Lock()

    def _h(self, *parts): return hmac.new(self.key, b"".join(parts), hashlib.sha256).digest()

    def open(self, f):
        """(16-byte packet, is_discovery) for a genuine, new frame from an app, else None."""
        if len(f) != FRAME_IN or f[0] != MARK_IN: return None
        sid, ctr, ct, tag = f[1:5], f[5:9], f[9:25], f[25:33]
        for nonce, disc in ((self.nonce, False), (bytes(8), True)):
            if hmac.compare_digest(self._h(b"T", nonce, f[:1], sid, ctr, ct)[:8], tag): break
        else: return None
        pt = bytes(a ^ b for a, b in zip(ct, self._h(b"E", nonce, sid, ctr)))
        if disc: return (pt, True) if pt[0] == PING else None   # nothing but a ping may be sealed with the nonce 0, and it changes no state
        return (pt, False) if self._fresh(int.from_bytes(sid, "little"), int.from_bytes(ctr, "little")) else None

    def _fresh(self, sid, c):
        with self.lock:
            st = self.sessions.get(sid)
            if st is None:
                if len(self.sessions) >= 256: self.sessions.popitem(last=False)
                self.sessions[sid] = [c, 1]; return True
            self.sessions.move_to_end(sid)
            top, bits = st
            if c > top:
                st[1] = ((bits << (c - top)) | 1) & (2 ** 64 - 1) if c - top < 64 else 1; st[0] = c; return True
            gap = top - c
            if gap >= 64 or bits >> gap & 1: return False   # too old, or already seen: a replay
            st[1] = bits | 1 << gap; return True

    def seal(self, pt):
        """A 16-byte packet for the app as a 37-byte sealed frame."""
        with self.lock: self.rc = (self.rc + 1) & 0xFFFFFFFF; rc = self.rc.to_bytes(4, "little")
        ct = bytes(a ^ b for a, b in zip(pt, self._h(b"R", self.nonce, rc)))
        return bytes([MARK_OUT]) + self.nonce + rc + ct + self._h(b"S", bytes([MARK_OUT]), self.nonce, rc, ct)[:8]

# ---------- plumbing ----------
class Device:
    """One connected phone/tablet. Each gets a player slot (1-4) and its own virtual controller."""
    def __init__(self, key, slot, transport):
        self.key, self.slot, self.transport = key, slot, transport
        self.mode, self.phone, self.rtt_us, self.count, self.pen, self.pad = None, None, None, 0, None, None
        self.held = set()  # keys the tablet is holding down
        self.reply, self.rlock = None, threading.Lock()
        self.sealer = None  # set when this device talks sealed: replies are then sealed too
        self.play_t = 0.0  # when the last pen packet is due to be played
        self.last_pad = None  # the last controller packet, so an unchanged one is not sent to ViGEm again
        self.touch, self.touch_failed = None, False
        self.colour = None  # the colour the tablet says it is (an index, 0-15), taken only from the tablet; None until it says
        self.pen_failed = self.pad_failed = False  # log a missing driver once, not on every packet
        self.last_seen = time.time()

    def send(self, b):
        """Sends a packet back to this tablet (replies can come from more than one thread)."""
        with self.rlock:
            if self.reply: self.reply(self.sealer(b) if self.sealer else b)

class Playout:
    """Replays pen packets with the spacing the tablet measured (the last byte), after a small fixed delay. Wi-Fi delivers packets
    in bursts; playing them back evenly is what makes strokes look smooth instead of jerky."""
    def __init__(self, srv):
        self.srv, self.heap, self.cv, self.seq = srv, [], threading.Condition(), 0
        threading.Thread(target=self._run, daemon=True).start()

    def push(self, dev, d, delay):
        now, gap = time.time(), d[15] / 1000.0
        if gap >= 0.25 or now - dev.play_t > 0.25: t = now + delay      # a fresh stroke (or a long pause): start a new clock
        else: t = max(dev.play_t + gap, now)                            # keep the tablet's spacing, but never play in the past
        t = max(dev.play_t, min(t, now + delay * 2 + 0.03))             # a long burst is squeezed rather than delayed without limit; order is kept
        dev.play_t = t
        with self.cv:
            heapq.heappush(self.heap, (t, self.seq, dev, d)); self.seq += 1; self.cv.notify()

    def _run(self):
        while True:
            with self.cv:
                while True:
                    if not self.heap: self.cv.wait(); continue
                    wait = self.heap[0][0] - time.time()
                    if wait <= 0: break
                    self.cv.wait(wait)
                _, _, dev, d = heapq.heappop(self.heap)
            with self.srv.lock:
                if dev.key in self.srv.devices:
                    try: self.srv._pen(dev, d)
                    except Exception as e: self.srv.last_error = str(e)

class Server:
    def __init__(self, monitor, speed=1.0):
        self.monitor, self.speed, self.lock = monitor, speed, threading.Lock()
        self.area_mode, self.custom = "full", monitor  # full = the whole selected screen | custom = a rectangle you choose
        self.devices, self.dev_lock = {}, threading.Lock()
        self.last_error, self.bind_errors, self.udp_ok, self.tcp_ok = "", [], False, False
        self.port, self.tsock, self.usock = None, None, None
        self.recorder, self.allow_record = KeyRecorder(), False   # off until switched on in the Desk: it listens to this keyboard
        self.pair, self.allow_legacy, self._warned = None, True, 0.0   # pair: set by the Desk. Without it (or with allow_legacy) plain 16-byte packets are accepted as before
        self.smooth_ms = 20; self.play = Playout(self)   # pen packets are replayed with the tablet's own spacing after this delay
        self.ring = {"on": True, "size": 90, "style": 0, "color": 0, "thick": 3}  # the pen cursor ring; the tablet can change it too
        threading.Thread(target=self.reaper, daemon=True).start()

    # -- ports: the first free one, so a second copy or another program never blocks us --
    def open(self, start=7777, tries=40):
        last = None
        for port in range(start, start + tries):
            t = u = None
            try:
                t = socket.socket(); t.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1); t.bind(("127.0.0.1", port)); t.listen(8)
                u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1); u.bind(("0.0.0.0", port))
            except OSError as e:
                last = e
                for s in (t, u):
                    if s: s.close()
                continue
            self.port, self.tsock, self.usock, self.tcp_ok, self.udp_ok = port, t, u, True, True
            if port != start: log(f"port {start} was busy, using {port}")
            return port
        self.bind_errors.append(f"no free port near {start}: {last}"); log("could not open a port:", last)
        return None

    def run(self):
        threading.Thread(target=self.udp_loop, daemon=True).start()
        threading.Thread(target=self.tcp_loop, daemon=True).start()

    # -- devices --
    def device(self, key, transport):
        with self.dev_lock:
            d = self.devices.get(key)
            if d: return d
            # the same tablet reconnecting over Wi-Fi arrives from a new port: it replaces its old entry instead of taking a second player slot
            stale = [k for k in self.devices if k[0] == "udp" and key[0] == "udp" and k[1] == key[1]]
        for k in stale: self.drop(k)
        with self.dev_lock:
            d = self.devices.get(key)
            if d: return d
            free = [n for n in range(1, MAX_DEVICES + 1) if n not in {x.slot for x in self.devices.values()}]
            if not free:
                if not getattr(self, "_full", 0) > time.time(): log(f"a 5th device tried to connect; only {MAX_DEVICES} are supported")
                self._full = time.time() + 10
                return None
            d = self.devices[key] = Device(key, free[0], transport)
            if len(self.devices) == 1: _timer(True)
            log(f"player {d.slot} connected ({transport})")
            return d

    def drop(self, key):
        with self.dev_lock:
            d = self.devices.pop(key, None)
            if d and not self.devices: _timer(False)
            if len(self.devices) == 1: next(iter(self.devices.values())).slot = 1   # a lone controller is always player 1, at the top
        if d:
            with self.lock:
                if d.pen and d.pen.inrange: d.pen.send(3, 0, 0, 0, 0, 0, 0)
                if d.pen: d.pen.close()
                if d.touch: d.touch.release_all(); d.touch.close()
                for vk, mods in list(d.held): key_event(vk, mods, 2)  # let go of anything still held
                d.held.clear()
                d.pad = d.pen = None  # dropping the reference unplugs the virtual controller
            log(f"player {d.slot} disconnected")

    def reaper(self):
        while True:
            time.sleep(1)
            for k, d in list(self.devices.items()):
                if d.transport == "wifi" and time.time() - d.last_seen > 5: self.drop(k)

    @property
    def last_seen(self): return max((d.last_seen for d in self.devices.values()), default=0.0)

    # -- where the tablet's active area lands on the PC: the whole selected screen unless you pick a rectangle --
    def area(self, phone=None):
        """x, y, w, h in pixels."""
        return self.custom if self.area_mode == "custom" else self.monitor

    def rel_scale(self, phone=None):
        """PC pixels per phone pixel, so a full swipe across the tablet's active area crosses the whole screen area."""
        return self.area()[2] / (phone[0] if phone else 1000) * self.speed

    # -- packets --
    def _pong(self, d, slot):
        aw, ah = self.area()[2:]
        out = bytearray(d); out[1] = slot; out[7:11] = struct.pack("<I", (min(aw, 65535) << 16) | min(ah, 65535)); return bytes(out)

    def discover(self, d, key_, reply):
        """A discovery ping (sealed with the nonce 0): answer it so the app learns this run's nonce. Nothing is created and nothing changes."""
        dev = self.devices.get(key_)
        reply(self.pair.seal(self._pong(d, dev.slot if dev else 0)))

    def refuse_plain(self):
        if time.time() - self._warned > 30:
            self._warned = time.time()
            log("a device without a pairing key tried to connect and was refused. Update PixelPad on it and scan the QR code again, or turn on ALLOW OLD APPS in Settings (not safe).")

    def handle(self, d, reply, key_, transport, secure=False):
        dev = self.device(key_, transport)
        if not dev: return
        dev.last_seen = time.time(); dev.reply = reply; dev.sealer = self.pair.seal if secure and self.pair else None
        mode = d[0]
        if mode == PING:
            dev.rtt_us = struct.unpack(PEN_FMT, d)[4]
            if 1 <= d[13] <= 16: dev.colour = d[13] - 1   # the phone's own colour rides in every ping (its tilt-x byte); we only record it
            dev.send(self._pong(d, dev.slot)); return
        if mode == HELLO:
            x, y = struct.unpack(PEN_FMT, d)[3:5]
            if x > 0 and y > 0: dev.phone = (x, y)
            return
        if mode == REC:
            def answer(status, vk=0, mods=0, dev=dev): dev.send(struct.pack(PEN_FMT, REC, vk, mods, status, 0, 0, 0, 0, 0))
            if d[1] == 0: self.recorder.cancel()
            elif not self.allow_record: answer(3)
            else:
                log("recording a shortcut for the controller device: press it on this keyboard")
                if not self.recorder.start(lambda res: answer(1, res[0], res[1]) if res else answer(2)): answer(2)
            return
        if mode == CONFIG:
            act, val = d[1], struct.unpack(PEN_FMT, d)[3]
            r = self.ring
            if act == 1: r["on"] = bool(val)
            elif act == 2: r["size"] = max(40, min(300, val))
            elif act == 3: r["style"] = val % 3
            elif act == 4: r["color"] = val % 6
            elif act == 5: r["thick"] = max(1, min(8, val))
            elif act == 6: self.smooth_ms = max(0, min(80, val))
            elif act == 7: dev.colour = val if 0 <= val < 16 else dev.colour   # older apps sent the colour this way; per device, unlike the ring settings
            return
        with self.lock:
            dev.count += 1
            try:
                if mode == TOUCH:
                    _, phase, cid, ox, oy, *_ = struct.unpack(PEN_FMT, d)
                    if not dev.touch:
                        if dev.touch_failed: return
                        try: dev.touch = Touch(self)
                        except Exception as e: dev.touch_failed = True; self.last_error = str(e); log("touch gestures unavailable:", e); return
                    dev.touch.event(phase, cid, ox, oy)
                    return
                if mode == KEY:
                    _, vk, mods, phase, *_ = struct.unpack(PEN_FMT, d)
                    if phase == 1:
                        if (vk, mods) in dev.held: return
                        dev.held.add((vk, mods))
                    elif phase == 2:
                        if (vk, mods) not in dev.held: return  # already released (the tablet sends releases twice)
                        dev.held.discard((vk, mods))
                    key_event(vk, mods, phase)
                    return
                if mode == TRACKPAD and dev.mode == TABLET and d[1] in (1, 2, 3, 7, 8, 9, 10):  # a tablet button's click: not a mode change
                    trackpad(d[1], 0, 0); return
                if mode != dev.mode:
                    if dev.mode == TABLET and dev.pen: dev.pen.send(3, 0, 0, 0, 0, 0, 0)
                    dev.mode = mode
                    if time.time() - getattr(dev, "_modelog", 0) > 1:   # at most once a second, so a flood of mode changes can't flood the log
                        dev._modelog = time.time(); log(f"player {dev.slot} mode:", ["trackpad", "tablet", "controller"][mode] if mode < 3 else mode)
                if mode == TRACKPAD:
                    _, a, _, x, y, *_ = struct.unpack(PEN_FMT, d)
                    trackpad(a, x, y)
                elif mode == TABLET:
                    if self.smooth_ms > 0: self.play.push(dev, d, self.smooth_ms / 1000.0)
                    else: self._pen(dev, d)
                elif mode == CONTROLLER:
                    if not dev.pad:
                        if dev.pad_failed: return
                        try: dev.pad = Pad()
                        except Exception as e: dev.pad_failed = True; self.last_error = str(e); log("controller mode unavailable (is ViGEmBus installed?):", e); return
                    if d == dev.last_pad: return
                    dev.last_pad = d
                    dev.pad.send(*struct.unpack(PAD_FMT, d)[1:])
            except Exception as e:
                self.last_error = str(e); log("error:", e)

    def _pen(self, dev, d):
        """Injects one pen packet. Call with self.lock held."""
        if not dev.pen:
            if dev.pen_failed: return
            try: dev.pen = Pen(self, dev)
            except Exception as e: dev.pen_failed = True; self.last_error = str(e); log("tablet mode unavailable:", e); return
        dev.pen.send(*struct.unpack(PEN_FMT, d)[1:8])

    def udp_loop(self):
        s = self.usock
        while True:
            try: d, addr = s.recvfrom(64)
            except ConnectionResetError: continue  # Windows reports a closed phone socket this way; it is not fatal
            except OSError: return
            try:
                if len(d) == SIZE:   # a plain, unauthenticated packet: only from an old app, and only if allowed
                    if self.pair and not self.allow_legacy: self.refuse_plain(); continue
                    self.handle(d, lambda b, a=addr: s.sendto(b, a), ("udp",) + addr, "wifi")
                elif len(d) == FRAME_IN and self.pair and (o := self.pair.open(d)):
                    key_, reply = ("udp",) + addr, lambda b, a=addr: s.sendto(b, a)
                    if o[1]: self.discover(o[0], key_, reply)
                    else: self.handle(o[0], reply, key_, "wifi", True)
            except Exception as e: self.last_error = str(e)

    def tcp_loop(self):
        while True:
            try: c, peer = self.tsock.accept()
            except OSError:
                if self.tsock.fileno() == -1: return
                time.sleep(0.1); continue
            threading.Thread(target=self.serve_tcp, args=(c, ("tcp",) + peer), daemon=True).start()

    def serve_tcp(self, c, key_):
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1); buf = b""
        try:
            while (r := c.recv(4096)):
                buf += r
                while buf:
                    if buf[0] == MARK_IN and self.pair:   # a sealed frame (a plain packet never starts with this byte)
                        if len(buf) < FRAME_IN: break
                        f, buf = buf[:FRAME_IN], buf[FRAME_IN:]
                        if not (o := self.pair.open(f)): continue
                        if o[1]: self.discover(o[0], key_, c.sendall)
                        else: self.handle(o[0], c.sendall, key_, "usb", True)
                    else:
                        if self.pair and not self.allow_legacy: self.refuse_plain(); return
                        if len(buf) < SIZE: break
                        self.handle(buf[:SIZE], c.sendall, key_, "usb"); buf = buf[SIZE:]
        except Exception: pass   # a dead connection or anything odd ends this connection only
        finally:
            self.drop(key_)
            try: c.close()
            except OSError: pass

# ---------- updates: the latest release on GitHub ----------
REPO = "nyx-ulrix/pixelpad"
RELEASES = f"https://api.github.com/repos/{REPO}/releases/latest"
DOWNLOADS = f"https://github.com/{REPO}/releases/download/"
MAX_EXE = 100 << 20   # the exe is about 35 MB: refuse anything absurd

def newer_version(a, b):
    """Is version a newer than b? Compared number by number, so 1.1.10 is newer than 1.1.9."""
    pa, pb = [int(x) for x in re.findall(r"\d+", a)], [int(x) for x in re.findall(r"\d+", b)]
    n = max(len(pa), len(pb)); return pa + [0] * (n - len(pa)) > pb + [0] * (n - len(pb))

def latest_release():
    """(version, exe download url, checksums url, release page) of the newest GitHub release, or None if it can't be read. Only this project's own
    release files count, and without a published checksum file there is no exe to swap in (the page is offered instead)."""
    try:
        req = urllib.request.Request(RELEASES, headers={"Accept": "application/vnd.github+json", "User-Agent": "PixelPadDesk"})
        with urllib.request.urlopen(req, timeout=6) as r: j = json.load(r)
        ver = j["tag_name"].lstrip("v")
        urls = {a["name"]: a["browser_download_url"] for a in j.get("assets", []) if str(a.get("browser_download_url", "")).startswith(DOWNLOADS)}
        exe, sums = urls.get(f"PixelPadDesk-{ver}.exe"), urls.get("SHA256SUMS.txt")
        if not sums: exe = None
        page = j.get("html_url") if str(j.get("html_url", "")).startswith(f"https://github.com/{REPO}/") else None
        return ver, exe, sums, page
    except Exception: return None

def fetch_update(exe_url, sums_url, dest, progress=None):
    """Downloads the new exe to dest and checks it against the release's checksum file. Returns True only if it is intact: a missing checksum file,
    a missing line for this exe, a bad hash, an oversized file or a plain-http redirect all fail. progress(bytes so far, total or 0) is called
    from this thread about ten times a second."""
    try:
        req = lambda u: urllib.request.Request(u, headers={"User-Agent": "PixelPadDesk"})
        if not sums_url: return False
        name = os.path.basename(exe_url.split("?")[0])
        with urllib.request.urlopen(req(sums_url), timeout=15) as r: sums = r.read(1 << 16).decode("utf-8", "replace")
        want = next((p[0].lower() for l in sums.splitlines() if len(p := l.split()) == 2 and p[1].lstrip("*") == name), "")
        if not re.fullmatch(r"[0-9a-f]{64}", want): return False
        h = hashlib.sha256()
        with urllib.request.urlopen(req(exe_url), timeout=30) as r, open(dest, "wb") as f:
            if r.geturl().startswith("http://"): raise ValueError("insecure redirect")
            total, got, shown = int(r.headers.get("Content-Length") or 0), 0, 0.0
            if total > MAX_EXE: raise ValueError("too big")
            while chunk := r.read(1 << 16):
                got += len(chunk)
                if got > MAX_EXE: raise ValueError("too big")
                f.write(chunk); h.update(chunk)
                if progress and time.time() - shown > 0.1: shown = time.time(); progress(got, total)
            if progress: progress(got, total or got)
        if h.hexdigest() != want: os.remove(dest); return False
        return True
    except Exception:
        try: os.remove(dest)
        except OSError: pass
        return False

PHONE_PORT = 7777  # the port the phone dials on itself; adb reverse forwards it to whichever port the PC found

SYS32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")

def adb_path():
    """adb.exe from the SDK folders or PATH, but never from the current folder (shutil.which would look there first)."""
    dirs = [os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools"), os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools")] + os.environ.get("PATH", "").split(os.pathsep)
    return next((p for d in dirs if d and os.path.isabs(d) for p in [os.path.join(d, "adb.exe")] if os.path.isfile(p)), None)

def adb(*args):
    p = adb_path()
    if not p: return None
    try: return subprocess.run([p, *args], capture_output=True, text=True, creationflags=0x08000000, timeout=5).stdout
    except subprocess.TimeoutExpired: return ""   # a stuck adb must never freeze the checker

def adb_serials():
    return [l.split()[0] for l in (adb("devices") or "").splitlines()[1:] if l.strip().endswith("device")]

def adb_reverse(pc_port):
    """Set up the USB link for every connected device."""
    ser = adb_serials()
    if adb_path() is None: log(f"adb not found; run:  adb reverse tcp:{PHONE_PORT} tcp:{pc_port}")
    want = f"tcp:{PHONE_PORT} tcp:{pc_port}"
    for sn in ser:
        if want not in (adb("-s", sn, "reverse", "--list") or ""): adb("-s", sn, "reverse", f"tcp:{PHONE_PORT}", f"tcp:{pc_port}")   # only when missing
    return ser

def local_ips():
    try: return sorted({i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET) if not i[4][0].startswith("127.")})
    except OSError: return []

_vig = [0.0, False]

def diagnose(srv, port):
    """Health checks as (status, message, fix) where status is ok / fail / warn."""
    r = []
    devs0 = list(srv.devices.values())
    wifi_only = bool(devs0) and all(d.transport == "wifi" for d in devs0)   # working over Wi-Fi: no need to nag about USB
    if not wifi_only:
        path = adb_path()
        r.append(("ok" if path else "fail", "ADB TOOL FOUND" if path else "ADB TOOL MISSING",
                  "Install Android platform-tools, or set the ANDROID_HOME variable." ))
        lines = [l.split() for l in (adb("devices") or "").splitlines()[1:] if l.strip()]
        ready = [l for l in lines if l[-1] == "device"]; unauth = [l for l in lines if l[-1] == "unauthorized"]
        if ready: r.append(("ok", "CONTROLLER DEVICE SEEN OVER USB", ""))   # not its serial number
        elif unauth: r.append(("fail", "CONTROLLER DEVICE FOUND BUT NOT ALLOWED", "Look at the controller device and tap ALLOW on the USB debugging prompt."))
        else: r.append(("fail", "NO CONTROLLER DEVICE OVER USB", "Use a data cable, turn on USB debugging (Developer options), then re-check. Wi-Fi works without a cable."))
        rev = f"tcp:{PHONE_PORT} tcp:{port}" in (adb("reverse", "--list") or "")
        r.append(("ok" if rev else "warn", "USB LINK IS SET UP" if rev else "USB LINK NOT SET UP", "Press RECONNECT USB."))
    busy = "Could not open a port. " + " ".join(srv.bind_errors)
    r.append(("ok" if srv.tcp_ok else "fail", f"LISTENING ON USB PORT {port}" if srv.tcp_ok else "NO USB PORT", busy))
    r.append(("ok" if srv.udp_ok else "fail", f"LISTENING ON WI-FI PORT {port}" if srv.udp_ok else "NO WI-FI PORT", busy))
    if getattr(srv, "_pen_ok", None) is None:
        probe = u32.CreateSyntheticPointerDevice(3, 1, 1)
        if probe: u32.DestroySyntheticPointerDevice(ctypes.c_void_p(probe))
        srv._pen_ok = bool(probe)
    dev = srv._pen_ok
    r.append(("ok" if dev else "fail", "PEN DEVICE READY (TABLET MODE)" if dev else "PEN DEVICE UNAVAILABLE", "Tablet mode needs Windows 10 version 1809 or newer."))
    if time.time() - _vig[0] > 30:   # the driver doesn't come and go: ask at most every 30 s
        _vig[0] = time.time()
        _vig[1] = "RUNNING" in subprocess.run([os.path.join(SYS32, "sc.exe"), "query", "ViGEmBus"], capture_output=True, text=True, creationflags=0x08000000).stdout
    vg = _vig[1]
    r.append(("ok" if vg else "warn", "CONTROLLER DRIVER READY" if vg else "CONTROLLER DRIVER NOT INSTALLED",
              "Only needed for controller mode. Install ViGEmBus from github.com/nefarius/ViGEmBus/releases."))
    devs = list(srv.devices.values())
    if devs: r.append(("ok", f"{len(devs)} DEVICE(S) TALKING TO THIS PC", ""))
    else: r.append(("fail", "NOTHING RECEIVED FROM THE CONTROLLER DEVICE", "Open PixelPad on the controller device and check Settings > Connection. Over Wi-Fi/Bluetooth: scan the QR code, and allow PixelPad Desk through Windows Firewall."))
    if srv.pair and srv._warned and time.time() - srv._warned < 120:
        r.append(("warn", "A DEVICE WITHOUT A PAIRING KEY WAS REFUSED", "Update PixelPad on it and scan the QR code again, or turn on ALLOW OLD APPS in Settings (not safe)."))
    if srv.last_error: r.append(("warn", "LAST ERROR: " + srv.last_error.upper(), ""))
    how = "WI-FI" if wifi_only else "USB" if devs and all(d.transport == "usb" for d in devs) else "USB + WI-FI"
    r.insert(0, ("ok", f"CONNECTED: {len(devs)} DEVICE(S) OVER {how}", "") if devs else ("warn", "WAITING FOR A CONTROLLER DEVICE", ""))
    return r

if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="PixelPad Desk (command line)")
    ap.add_argument("--port", type=int, default=7777, help="first port to try; the next free one is used if it is busy")
    ap.add_argument("--area", help="map the controller device to a fixed rectangle x,y,w,h in pixels (default: the whole main screen)")
    ap.add_argument("--speed", type=float, default=1.0, help="relative pen speed multiplier")
    a = ap.parse_args()
    srv = Server(monitors()[0], a.speed)
    if a.area: srv.area_mode, srv.custom = "custom", tuple(map(int, a.area.split(",")))
    port = srv.open(a.port)
    if port:
        adb_reverse(port); srv.run()
        log(f"PixelPad Desk ready on port {port}. Controller device area: {srv.area()}")
        while True: time.sleep(3600)
