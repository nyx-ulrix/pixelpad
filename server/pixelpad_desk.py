"""PixelPad Desk: window app for the PC side of PixelPad (retro pixel style)."""
VERSION = "1.3.0"   # keep in step with versionName in android/app/build.gradle.kts (test_server.py checks), and with the release tag
import ctypes, functools, json, math, os, re, secrets, socket, subprocess, sys, threading, time, tkinter as tk
from urllib.parse import quote
from tkinter import scrolledtext
import segno
import pixelpad_server as ns
import pixel_icons as pi


def res(name):
    """A bundled file: next to the exe when frozen, in assets/ when run from source."""
    base = getattr(sys, "_MEIPASS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets"))
    return os.path.join(base, name)

# one copy at a time: a second launch just brings the first one back. A named mutex decides (unlike a port, nobody can squat it); the loopback
# port is only how the second copy tells the first to show itself. After an update the new copy waits for the old one to leave.
_k32 = ctypes.WinDLL("kernel32", use_last_error=True)
_k32.CreateMutexW.restype = ctypes.c_void_p; _k32.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
_k32.CloseHandle.argtypes = [ctypes.c_void_p]
def _first_copy():
    h = _k32.CreateMutexW(None, 0, "Local\\PixelPadDesk")
    if ctypes.get_last_error() != 183: return h or True   # 183 = ERROR_ALREADY_EXISTS
    _k32.CloseHandle(h); return None
_mutex = _first_copy()
if not _mutex and "--updated" in sys.argv:
    for _ in range(40):
        time.sleep(0.5); _mutex = _first_copy()
        if _mutex: break
if not _mutex:
    try: socket.socket(socket.AF_INET, socket.SOCK_DGRAM).sendto(b"show", ("127.0.0.1", 47771))
    finally: sys.exit(0)
_lock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try: _lock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1); _lock.bind(("127.0.0.1", 47771))
except OSError: _lock = None   # something else has the port: run anyway, just without the wake-up channel
INK, PAPER, LILAC, PINK, HOT, GREEN, BABY = "#2F6FE0", "#F7FBFF", "#D9C8FF", "#FFB8E6", "#E84FB0", "#B8E986", "#BFE3FA"
# the standard Switch colours, the same list as the tablet app (a device sends an index into it; the Desk never picks one for a device)
THEMES = [("NEON BLUE", (0xD8, 0xF6, 0xFF), (0x7E, 0xD8, 0xF5), "#0AB9E6"), ("NEON RED", (0xFF, 0xE0, 0xDC), (0xFF, 0x8F, 0x84), "#FF3C28"),
          ("NEON GREEN", (0xE3, 0xFF, 0xDC), (0x8E, 0xEA, 0x7A), "#1EDC00"), ("NEON PINK", (0xFF, 0xE0, 0xEA), (0xFF, 0x8F, 0xB4), "#FF3278"),
          ("NEON YELLOW", (0xFC, 0xFF, 0xD0), (0xEE, 0xF5, 0x6A), "#E6FF00"), ("NEON PURPLE", (0xF3, 0xDC, 0xFF), (0xD5, 0x8C, 0xF5), "#B400E6"),
          ("NEON ORANGE", (0xFF, 0xEA, 0xD6), (0xFF, 0xB4, 0x70), "#FF8200"), ("GREY", (0xED, 0xED, 0xED), (0xB5, 0xB5, 0xB5), "#828282")]
TOP, BOTTOM, QRINK, WARN = (0xC9, 0xEE, 0xFF), (0xFF, 0xC9, 0xEA), "#12306B", "#B0206E"
NO_COLOUR = "#C8CCD4"   # shown until a device tells us its own colour
F, FS, FB = ("Courier New", 10, "bold"), ("Courier New", 9, "bold"), ("Courier New", 13, "bold")
CFG = os.path.join(os.environ.get("APPDATA", "."), "PixelPadDesk", "settings.json")

root = tk.Tk(); root.title("PIXELPAD DESK")
try: root.iconbitmap(res("pixelpad.ico"))
except Exception: pass
SC = root.winfo_fpixels("1i") / 96.0                      # UI scale: this process is DPI aware, so pixel sizes must scale too
sw, sh = root.winfo_screenwidth(), root.winfo_screenheight()
root.geometry(f"{min(int(1000 * SC), int(sw * .92))}x{min(int(760 * SC), int(sh * .88))}+{int(sw * .04)}+{int(sh * .04)}")
root.minsize(int(700 * SC), int(520 * SC))
T = max(3, round(2.5 * SC))                                # border thickness
IS = max(16, int(18 * SC))                                 # icon size

def load():
    try:
        with open(CFG) as f: return json.load(f)
    except (OSError, ValueError): return {}

cfg = load()
if not isinstance(cfg, dict): cfg = {}
def num(key, default, lo, hi, f=int):
    """A number from the settings file, kept inside lo..hi; the default if it is missing or isn't a number."""
    try: return min(hi, max(lo, f(cfg.get(key, default))))
    except (TypeError, ValueError, OverflowError): return default
_theme = num("theme", -1, -1, len(THEMES) - 1)
theme = [_theme if _theme >= 0 else None]   # the Desk's own background colour; until you pick one it keeps the classic blue-to-pink
if theme[0] is not None: TOP, BOTTOM = THEMES[theme[0]][1], THEMES[theme[0]][2]

def save():
    try:
        os.makedirs(os.path.dirname(CFG), exist_ok=True)
        with open(CFG, "w") as f:
            json.dump({"open": {k: w["visible"] for k, w in reg.items()}, "speed": srv.speed, "area_mode": srv.area_mode,
                       "custom": list(srv.custom), "screen": screen_idx[0], "highlight": hl["on"], "hl_size": hl["size"], "hl_style": hl["style"], "hl_color": hl["color"], "hl_thick": hl["thick"], "pair_key": pair_key[0], "allow_legacy": srv.allow_legacy, "theme": theme[0] if theme[0] is not None else -1, "pc_name": pc_name.get().strip()[:20] or "MY PC"}, f)
    except OSError: pass

def tip(w, text):
    """A small hint on hover, for buttons that are only a picture."""
    t = []
    def hide(_=None):
        while t: t.pop().destroy()
    def show(e):
        hide(); h = tk.Toplevel(w); h.overrideredirect(True); h.attributes("-topmost", True)
        tk.Label(h, text=text, bg=PAPER, fg=INK, font=FS, highlightthickness=2, highlightbackground=INK, padx=4).pack()
        h.geometry(f"+{e.x_root + 12}+{e.y_root + 20}"); t.append(h)
    w.bind("<Enter>", show, add="+"); w.bind("<Leave>", hide, add="+"); w.bind("<Button-1>", hide, add="+")

def pic(w, names, color=INK, px=None):
    """Show an icon (or a row of them) on a label or button. Buttons centre it; a picture with words puts them side by side."""
    w.config(image=pi.get(names, px or IS, color), compound="left")

def pill(parent, text, cmd, color=PINK, icon=None, hint=None, **kw):
    b = tk.Label(parent, text=text, bg=color, fg=INK, font=kw.pop("font", F), padx=int(10 * SC if text else 8 * SC), pady=int(3 * SC), cursor="hand2",
                 highlightthickness=T, highlightbackground=INK, **kw)
    b.base = color
    b.bind("<Button-1>", lambda e: cmd()); b.bind("<Enter>", lambda e: b.config(bg=HOT)); b.bind("<Leave>", lambda e: b.config(bg=b.base))
    if icon: pic(b, icon)
    if hint: tip(b, hint)
    return b

def flag(b, icon, on):
    """An on/off button: green with a tick when on, plain with a cross when off."""
    b.base = GREEN if on else PAPER; b.config(bg=b.base); pic(b, (icon, "check" if on else "x"))

def hint(parent, text, **kw):
    """An explanation, shown as plain text."""
    return label(parent, text=text, font=FS, wraplength=int(kw.pop("wrap", 420) * SC), **kw)

def label(parent, **kw):
    kw.setdefault("bg", PAPER); kw.setdefault("fg", INK); kw.setdefault("font", F)
    return tk.Label(parent, anchor="w", justify="left", **kw)

w0, h0 = ns.u32.GetSystemMetrics(0), ns.u32.GetSystemMetrics(1)
mons = ns.monitors()
screens = [("MAIN SCREEN", mons[0])] + ([("ALL SCREENS", ns.virtual_screen())] if len(mons) > 1 else []) + [(f"SCREEN {i}", m) for i, m in enumerate(mons[1:], 2)]
srv = ns.Server(mons[0], num("speed", 1.0, 0.01, 99.0, float))
screen_idx = [num("screen", 0, 0, len(screens) - 1)]
srv.monitor = screens[screen_idx[0]][1]
if cfg.get("area_mode") in ("full", "custom"): srv.area_mode = cfg["area_mode"]
PORT = srv.open(7777) or 7777  # the first free port from 7777 up; the QR code and the USB link follow it
_c = cfg.get("custom")
if isinstance(_c, list) and len(_c) == 4 and all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in _c) and _c[2] > 0 and _c[3] > 0: srv.custom = tuple(int(v) for v in _c)
overlay_on = False
srv.allow_record = False   # never remembered: recording a shortcut listens to this keyboard, so it is switched on by hand each time
# the pairing key (see Pairing in pixelpad_server.py): made on first run, kept in the settings; the QR code carries it. Old apps without one are refused unless allowed.
_key = str(cfg.get("pair_key", ""))
pair_key = [_key if re.fullmatch("[0-9a-f]{32}", _key) else secrets.token_hex(16)]
srv.pair, srv.allow_legacy = ns.Pairing(bytes.fromhex(pair_key[0])), bool(cfg.get("allow_legacy", False))
pc_name = tk.StringVar(value=str(cfg.get("pc_name", "MY PC")).strip()[:20] or "MY PC")   # the name the tablet saves this PC under (you choose it)
hl = srv.ring   # shared with the tablet, which can change it too
hl.update(on=bool(cfg.get("highlight", True)), size=num("hl_size", 90, 50, 220), style=num("hl_style", 0, 0, 2),
          color=num("hl_color", 0, 0, 5), thick=num("hl_thick", 3, 1, 8), win=None, cv=None, down=None, sig=None)
RING_COLORS = [(HOT, "PINK"), (INK, "BLUE"), (LILAC, "LILAC"), (GREEN, "GREEN"), ("#FFFFFF", "WHITE"), ("#FF3B30", "RED")]
RING_STYLES = ["ring", "crosshair", "dot"]

# ---------- toolbar (reopen closed windows, open settings) ----------
bar = tk.Frame(root, bg=PAPER, highlightthickness=T, highlightbackground=INK); bar.pack(fill="x")
canvas = tk.Canvas(root, highlightthickness=0); canvas.pack(fill="both", expand=True)
bg = canvas
reg, order, page = {}, [], "dash"

# ---------- window factory ----------
def window(key, title, color, icon):
    """Pixel window: hard offset shadow, thick border, coloured title bar with a working x. Placed by layout()."""
    shadow = tk.Frame(bg, bg=INK)
    win = tk.Frame(bg, bg=PAPER, highlightbackground=INK, highlightthickness=T)
    s_item = bg.create_window(0, 0, window=shadow, anchor="nw", state="hidden")
    w_item = bg.create_window(0, 0, window=win, anchor="nw", state="hidden")
    tb = tk.Frame(win, bg=color); tb.pack(fill="x")
    ti = tk.Label(tb, text=" " + title, bg=color, fg=INK, font=F); pic(ti, icon, INK, int(22 * SC)); ti.pack(side="left", padx=8, pady=2)
    if key != "settings":
        x = tk.Label(tb, bg=HOT, cursor="hand2", padx=4); pic(x, "x", PAPER); x.pack(side="right", padx=4, pady=2)
        x.bind("<Button-1>", lambda e, k=key: set_visible(k, False))
    tk.Frame(win, bg=INK, height=3).pack(fill="x")
    body = tk.Frame(win, bg=PAPER); body.pack(fill="both", expand=True, padx=int(10 * SC), pady=int(6 * SC))
    reg[key] = {"items": (s_item, w_item), "visible": cfg.get("open", {}).get(key, True), "color": color, "title": title}
    order.append(key)
    return body

def set_visible(key, on):
    reg[key]["visible"] = on; save(); layout(); refresh_bar()

# ---------- background + reflow ----------
last_size = [0, 0]
def draw_bg(W, H):
    bg.delete("art")
    def grad(y): t = y / max(H, 1); return tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM))
    def hexc(c): return "#%02x%02x%02x" % c
    def wash(y): return hexc(tuple((v + 255) // 2 for v in grad(y)))
    step = 6
    for y in range(0, H, step): bg.create_rectangle(0, y, W, y + step, fill=hexc(grad(y)), outline="", tags="art")
    g = max(16, int(24 * SC))
    for y in range(0, H, g): bg.create_line(0, y, W, y, fill=wash(y), tags="art")
    for x in range(0, W, g):
        for y in range(0, H, g * 4): bg.create_line(x, y, x, min(H, y + g * 4), fill=wash(y + g * 2), tags="art")
    bg.tag_lower("art")

def layout(_=None):
    W, H = bg.winfo_width(), bg.winfo_height()
    if W < 50 or H < 50: return
    if (W, H) != tuple(last_size): last_size[:] = [W, H]; draw_bg(W, H)
    show = ["settings"] if page == "settings" else [k for k in order if k != "settings" and reg[k]["visible"]]
    for k in order:
        for it in reg[k]["items"]: bg.itemconfigure(it, state="normal" if k in show else "hidden")
    bg.delete("empty")
    if not show:
        bg.create_text(W / 2, H / 2, text="ALL WINDOWS ARE CLOSED\nUSE THE BUTTONS AT THE TOP TO OPEN THEM", fill=INK, font=FB, justify="center", tags="empty")
        return
    m = int(16 * SC); gap = int(16 * SC); off = int(6 * SC)
    cols = 1 if (len(show) == 1 or page == "settings" or W < 620 * SC) else 2
    rows = math.ceil(len(show) / cols)
    cw = (W - 2 * m - (cols - 1) * gap) / cols; ch = (H - 2 * m - off - (rows - 1) * gap) / rows
    for i, k in enumerate(show):
        r, c = divmod(i, cols)
        x, y, w, h = m + c * (cw + gap), m + r * (ch + gap), cw, ch
        if cols == 2 and i == len(show) - 1 and len(show) % 2 == 1: x, w = m, W - 2 * m  # odd one out spans the row
        s_item, w_item = reg[k]["items"]
        bg.coords(s_item, x + off, y + off); bg.itemconfigure(s_item, width=int(w - off), height=int(h - off))
        bg.coords(w_item, x, y); bg.itemconfigure(w_item, width=int(w - off), height=int(h - off))

_after = [None]
def on_resize(_):
    if _after[0]: root.after_cancel(_after[0])
    _after[0] = root.after(40, layout)
bg.bind("<Configure>", on_resize)

# ---------- window: status + players ----------
a = window("status", "PIXELPAD DESK", PINK, "pulse")
status = label(a, font=FB); status.pack(fill="x")
latency = label(a, fg=HOT); pic(latency, "pulse", HOT); latency.pack(fill="x", pady=(2, 6))
players = [label(a) for _ in range(ns.MAX_DEVICES)]
for p in players: p.pack(fill="x")
label(a, text="UP TO 4 DEVICES = 4 CONTROLLERS", font=FS).pack(fill="x", side="bottom")

# ---------- window: control area (map of the PC screen) ----------
b = window("area", "WHERE THE CONTROLLER DEVICE REACHES", LILAC, "crop")
mrow = tk.Frame(b, bg=PAPER); mrow.pack(fill="x")
mode_pills = {}
def fit_custom():
    x, y, w, h = srv.custom; mx, my, mw, mh = srv.monitor
    if not (mx <= x and my <= y and x + w <= mx + mw and y + h <= my + mh): srv.custom = srv.monitor

def set_mode(m):
    if m == "custom": fit_custom()
    srv.area_mode = m; save(); draw_map(); update_overlay()
for key, ic, txt in (("full", "screen", "WHOLE SCREEN"), ("custom", "crop", "CUSTOM RECTANGLE")):
    mode_pills[key] = pill(mrow, txt, lambda k=key: set_mode(k), LILAC, ic); mode_pills[key].ic = ic; mode_pills[key].pack(side="left", padx=(0, 6))
def pick_screen(name):
    i = [n for n, _ in screens].index(name); screen_idx[0] = i; srv.monitor = screens[i][1]
    fit_custom()
    save(); draw_map(); update_overlay()
scr_var = tk.StringVar(value=screens[screen_idx[0] if screen_idx[0] < len(screens) else 0][0])
scr_menu = tk.OptionMenu(mrow, scr_var, *[n for n, _ in screens], command=pick_screen)
scr_menu.config(bg=PAPER, fg=INK, font=FS, highlightbackground=INK, relief="solid"); scr_menu.pack(side="right")
keep_shape = tk.BooleanVar(value=True)
keep_btn = tk.Checkbutton(b, text=" KEEP CONTROLLER DEVICE SHAPE WHILE RESIZING", variable=keep_shape, bg=PAPER, fg=INK, font=FS, selectcolor=LILAC, activebackground=PAPER, anchor="w"); pic(keep_btn, "lock")
keep_btn.pack(fill="x")
inuse = label(b, font=FS, fg=HOT); inuse.pack(fill="x", side="bottom")
srow = tk.Frame(b, bg=PAPER); srow.pack(fill="x", side="bottom", pady=(4, 2))
overlay_btn = pill(srow, "SHOW ON MY SCREEN", lambda: toggle_overlay(), BABY, "eye"); overlay_btn.pack(side="left")
mm = tk.Canvas(b, bg=PAPER, highlightthickness=T, highlightbackground=INK, cursor="fleur"); mm.pack(fill="both", expand=True, pady=(6, 0))
drag = {}

def current_phone():
    return next((d.phone for d in sorted(srv.devices.values(), key=lambda x: x.slot) if d.phone), None)

def map_geom():
    W, H = max(mm.winfo_width(), 60), max(mm.winfo_height(), 60)
    mx, my, mw, mh = srv.monitor; pad = 10 * SC
    k = min((W - 2 * pad) / mw, (H - 2 * pad) / mh)
    return k, (W - mw * k) / 2, (H - mh * k) / 2

def draw_map(*_):
    mm.delete("all")
    k, ox, oy = map_geom(); mx, my, mw, mh = srv.monitor
    mm.create_rectangle(ox, oy, ox + mw * k, oy + mh * k, fill=BABY, outline=INK, width=T)
    mm.create_text(ox + 6, oy + 4, anchor="nw", text=f"YOUR SCREEN {mw}x{mh}", fill=INK, font=FS)
    x, y, w, h = srv.area(current_phone())
    x0, y0, x1, y1 = ox + (x - mx) * k, oy + (y - my) * k, ox + (x + w - mx) * k, oy + (y + h - my) * k
    mm.create_rectangle(x0, y0, x1, y1, fill=PINK, outline=HOT, width=T, stipple="gray50")
    mm.create_rectangle(x0, y0, x1, y1, outline=HOT, width=T)
    hs = 7 * SC
    mm.create_rectangle(x1 - hs, y1 - hs, x1 + hs, y1 + hs, fill=HOT, outline=INK, width=2)  # drag this corner to resize
    mm.create_image((x0 + x1) / 2, (y0 + y1) / 2, image=pi.get("pen", int(30 * SC), WARN))
    for key, pl in mode_pills.items(): sel = srv.area_mode == key; pl.base = HOT if sel else LILAC; pl.config(bg=pl.base); pic(pl, pl.ic, PAPER if sel else INK)

def mm_down(e):
    k, ox, oy = map_geom(); x, y, w, h = srv.area(current_phone())
    mx, my = srv.monitor[0], srv.monitor[1]
    px, py = (e.x - ox) / k + mx, (e.y - oy) / k + my; hs = 14 * SC / k
    drag.clear()
    if abs(px - (x + w)) < hs and abs(py - (y + h)) < hs: drag.update(kind="size")
    elif x <= px <= x + w and y <= py <= y + h: drag.update(kind="move", dx=px - x, dy=py - y)
    else: return
    drag.update(rect=(x, y, w, h))

def mm_move(e):
    if not drag: return
    k, ox, oy = map_geom(); mx, my, mw, mh = srv.monitor
    px, py = (e.x - ox) / k + mx, (e.y - oy) / k + my; x, y, w, h = drag["rect"]
    if drag["kind"] == "move":
        x, y = min(max(px - drag["dx"], mx), mx + mw - w), min(max(py - drag["dy"], my), my + mh - h)
    else:
        nw = min(max(px - x, 64), mx + mw - x)
        ph = current_phone(); ratio = (ph[1] / ph[0]) if ph else h / w   # height / width of the tablet's active area
        nh = nw * ratio if keep_shape.get() else min(max(py - y, 64), my + mh - y)
        if nh > my + mh - y: nh = my + mh - y; nw = nh / ratio if keep_shape.get() else nw
        w, h = nw, nh
    srv.area_mode = "custom"; srv.custom = (int(x), int(y), int(w), int(h))
    custom.set(",".join(map(str, srv.custom))); draw_map(); update_overlay()

def mm_up(_):
    if drag: save()
    drag.clear()
mm.bind("<Button-1>", mm_down); mm.bind("<B1-Motion>", mm_move); mm.bind("<ButtonRelease-1>", mm_up); mm.bind("<Configure>", draw_map)

# ---------- on-screen outline of the active area ----------
ov = {"win": None, "cv": None, "rect": None}
def set_click_through(win):
    try:
        u = ctypes.windll.user32; hwnd = u.GetParent(win.winfo_id())
        style = u.GetWindowLongW(hwnd, -20); u.SetWindowLongW(hwnd, -20, style | 0x80000 | 0x20 | 0x80 | 0x08000000)  # layered, transparent, tool window
    except Exception: pass

def update_overlay():
    if not overlay_on:
        if ov["win"]: ov["win"].destroy(); ov.update(win=None, cv=None, rect=None)
        return
    rect = srv.area(current_phone())
    if ov["win"] and ov["rect"] == rect: return
    x, y, w, h = rect
    if not ov["win"]:
        wn = tk.Toplevel(root); wn.overrideredirect(True); wn.attributes("-topmost", True)
        wn.config(bg="#010101"); wn.attributes("-transparentcolor", "#010101")
        cv = tk.Canvas(wn, bg="#010101", highlightthickness=0); cv.pack(fill="both", expand=True)
        ov.update(win=wn, cv=cv); wn.update_idletasks(); set_click_through(wn)
    ov["win"].geometry(f"{w}x{h}+{x}+{y}"); cv = ov["cv"]; cv.delete("all")
    t = max(4, int(5 * SC))
    cv.create_rectangle(t, t, w - t, h - t, outline=HOT, width=t * 2)
    cv.create_rectangle(t * 2, t * 2, t * 2 + int(34 * SC), t * 2 + int(30 * SC), fill=PAPER, outline=INK, width=2)
    cv.create_image(t * 2 + int(17 * SC), t * 2 + int(15 * SC), image=pi.get("pen", int(24 * SC), INK))
    ov["rect"] = rect

def toggle_overlay():
    global overlay_on
    overlay_on = not overlay_on
    overlay_btn.config(text="HIDE FROM MY SCREEN" if overlay_on else "SHOW ON MY SCREEN"); pic(overlay_btn, "eyeoff" if overlay_on else "eye")
    update_overlay(); save()

# ---------- window: can't connect ----------
c = window("checklist", "CAN'T CONNECT? CHECK HERE", GREEN, "check")
checks = scrolledtext.ScrolledText(c, height=4, font=FS, bg=PAPER, fg=INK, relief="flat", wrap="word", state="disabled")
checks.tag_config("ok", background=GREEN); checks.tag_config("warn", background=LILAC)
checks.tag_config("fail", background=PINK, foreground=WARN); checks.tag_config("hint", foreground=WARN, lmargin1=36, lmargin2=36)
results, report = [], ""

last_results = [None]

def show_checks():
    global report
    if results == last_results[0]: return   # nothing changed: leave the text (and any selection) alone
    last_results[0] = list(results)
    lines = []; top = checks.yview()[0]
    checks.config(state="normal"); checks.delete("1.0", "end")
    for st, msg, hint in sorted(results, key=lambda r: r[0] == "ok"):   # what needs attention first
        checks.image_create("end", image=pi.get({"ok": "check", "warn": "warn", "fail": "x"}[st], IS, INK), padx=4); checks.tag_add(st, "end-2c", "end-1c"); checks.insert("end", " " + msg + "\n")
        lines.append(f"[{st}] {msg}" + (f" -> {hint}" if st != "ok" and hint else ""))
        if st != "ok" and hint: checks.insert("end", hint + "\n", "hint")
    checks.yview_moveto(top); checks.config(state="disabled"); report = "\n".join(lines)

def copy_report():
    root.clipboard_clear(); root.clipboard_append(report); log("report copied")

def usb():
    threading.Thread(target=lambda: log(f"USB link set up for {len(ns.adb_reverse(PORT))} device(s)"), daemon=True).start()

br = tk.Frame(c, bg=PAPER); br.pack(fill="x", side="bottom", pady=(6, 0))
pill(br, "RECONNECT USB", usb, LILAC, "usb").pack(side="left", padx=(0, 6))
pill(br, "COPY REPORT", copy_report, BABY, "copy").pack(side="left")
checks.pack(fill="both", expand=True)

# ---------- window: QR ----------
d = window("connect", "WI-FI / BLUETOOTH", BABY, ("wifi", "bt"))
def compute_ips():
    found, primary = ns.local_ips(), ""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("10.255.255.255", 1)); primary = s.getsockname()[0]; s.close()
    except OSError: pass
    return [primary] + [i for i in found if i != primary] if primary else found

ips = compute_ips()
ip = tk.StringVar(value=ips[0] if ips else "")   # the address inside the QR code; it is never drawn as text
net = tk.StringVar(value="NETWORK 1")
label(d, text="THE CONTROLLER DEVICE SCANS THIS ON START, OR: SETTINGS > CONNECTION > SCAN", font=FS, wraplength=int(300 * SC)).pack(fill="x", side="bottom")
flow = label(d); pic(flow, ("present", "right", "qr", "right", "camera", "right", "check"), INK, int(26 * SC)); flow.pack(side="bottom")
menu = tk.OptionMenu(d, net, "NETWORK 1"); menu.config(bg=PAPER, fg=INK, font=FS, highlightbackground=INK, relief="solid")
# the name the QR code carries: the tablet offers it as the default when you save this PC (you can still change it there)
nrow = tk.Frame(d, bg=PAPER); nrow.pack(fill="x", side="top", pady=(0, 6))
nl = label(nrow, text=" NAME ON THE CONTROLLER DEVICE"); pic(nl, "present"); nl.pack(side="left")
name_entry = tk.Entry(nrow, textvariable=pc_name, font=F, relief="solid"); name_entry.pack(side="left", fill="x", expand=True, padx=(8, 0))
def commit_name(_=None):
    pc_name.set(pc_name.get().strip()[:20] or "MY PC"); save()
name_entry.bind("<FocusOut>", commit_name); name_entry.bind("<Return>", commit_name)
code_lbl = label(d, font=FS); code_lbl.pack(fill="x", side="bottom")
def show_code(): code_lbl.config(text=" PAIRING CODE  " + " ".join(pair_key[0][i:i + 4] for i in range(0, 32, 4)).upper()); pic(code_lbl, "lock")
show_code()
qr = tk.Canvas(d, bg=PAPER, highlightthickness=T, highlightbackground=INK); qr.pack(fill="both", expand=True)

def pick_net(lab):
    n = int(lab.split()[-1]) - 1
    if n < len(ips): ip.set(ips[n])

def rebuild_menu():
    """Only when this PC is on more than one network: a numbered choice of which one the QR code is for."""
    m = menu["menu"]; m.delete(0, "end")
    for n in range(max(1, len(ips))):
        lab = f"NETWORK {n + 1}"; m.add_command(label=lab, command=lambda l=lab: (net.set(l), pick_net(l)))
    if len(ips) > 1: menu.pack(fill="x", side="bottom", pady=4, before=qr)
    else: menu.pack_forget()

def refresh_ips():
    """The network can change after start (Wi-Fi coming up late, a new network): keep the code current."""
    global ips
    new = compute_ips()
    if new == ips: return
    ips = new
    if ip.get() not in new: ip.set(new[0] if new else ""); net.set("NETWORK 1")
    rebuild_menu()
rebuild_menu()

@functools.lru_cache(maxsize=8)
def qr_matrix(url): return segno.make(url, error="m").matrix

def draw_qr(*_):
    qr.delete("all")
    W, H = qr.winfo_width(), qr.winfo_height(); size = min(W, H)
    if not ip.get():
        qr.create_text(W // 2, H // 2, text="NO NETWORK", fill=INK, font=FB); return
    if size < 40: return
    m = qr_matrix(f"pixelpad://{ip.get()}:{PORT}?name={quote(pc_name.get().strip()[:20] or 'MY PC')}&k={pair_key[0]}"); n = len(m)
    cell = max(2, size // (n + 8)); ox, oy = (W - n * cell) // 2, (H - n * cell) // 2
    qr.create_rectangle(ox - 4 * cell, oy - 4 * cell, ox + (n + 4) * cell, oy + (n + 4) * cell, fill="white", outline="")  # quiet zone
    for y, r in enumerate(m):
        for x, v in enumerate(r):
            if v: qr.create_rectangle(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, fill=QRINK, outline=QRINK)
_qr_job = [None]
def draw_qr_soon(*_):
    if _qr_job[0]: root.after_cancel(_qr_job[0])
    _qr_job[0] = root.after(80, draw_qr)   # not on every pixel of a resize
ip.trace_add("write", draw_qr); pc_name.trace_add("write", draw_qr_soon); qr.bind("<Configure>", draw_qr_soon)

# ---------- settings page (exact numbers, speed, log) ----------
st = window("settings", "SETTINGS", BABY, "gear")
custom, speed = tk.StringVar(value=",".join(map(str, srv.custom))), tk.StringVar(value=str(srv.speed))
grid = tk.Frame(st, bg=PAPER); grid.pack(fill="x")
g0 = label(grid, text=" CONTROLLER DEVICE AREA X,Y,W,H"); pic(g0, "crop"); g0.grid(row=0, column=0, sticky="w", pady=2)
tk.Entry(grid, textvariable=custom, width=24, font=F, relief="solid").grid(row=0, column=1, padx=8)
g1 = label(grid, text=" RELATIVE PEN SPEED"); pic(g1, "cursor"); g1.grid(row=1, column=0, sticky="w", pady=2)
tk.Entry(grid, textvariable=speed, width=24, font=F, relief="solid").grid(row=1, column=1, padx=8)
g2 = label(grid, text=" PORT (USB TCP / WI-FI UDP)"); pic(g2, ("usb", "wifi")); g2.grid(row=2, column=0, sticky="w", pady=2)
label(grid, text=str(PORT)).grid(row=2, column=1, sticky="w", padx=8)
g3 = label(grid, text=" THIS PC'S NAME (SHOWN ON THE CONTROLLER DEVICE)"); pic(g3, "present"); g3.grid(row=3, column=0, sticky="w", pady=2)
tk.Entry(grid, textvariable=pc_name, width=24, font=F, relief="solid").grid(row=3, column=1, padx=8)

def apply():
    try:
        rect = tuple(map(int, custom.get().split(",")))
        if len(rect) != 4 or rect[2] <= 0 or rect[3] <= 0: raise ValueError
        if rect != tuple(srv.custom): srv.custom = rect; srv.area_mode = "custom"   # only if you changed the rectangle
        sp = float(speed.get())
        if not 0 < sp < 100: raise ValueError
        srv.speed = sp
        pc_name.set(pc_name.get().strip()[:20] or "MY PC")
        save(); draw_map(); update_overlay(); log("applied")
    except ValueError:
        log("could not read those values")

def reopen_all():
    for k in reg: reg[k]["visible"] = True
    save(); layout(); refresh_bar()

arow = tk.Frame(st, bg=PAPER); arow.pack(fill="x", pady=8)
pill(arow, "APPLY", apply, GREEN, "check").pack(side="left", padx=(0, 6))
pill(arow, "REOPEN ALL WINDOWS", reopen_all, LILAC, "windows").pack(side="left", padx=(0, 6))
trow = tk.Frame(st, bg=PAPER); trow.pack(fill="x", pady=(0, 8))

def set_theme(i):
    """The Desk's own background colour; click the button to step through them."""
    global TOP, BOTTOM
    theme[0] = i % len(THEMES); TOP, BOTTOM = THEMES[theme[0]][1], THEMES[theme[0]][2]
    pic(theme_pill, "swatch:" + THEMES[theme[0]][3])
    if last_size[0]: draw_bg(*last_size)
    save()
theme_pill = pill(trow, "THEME COLOUR", lambda: set_theme((theme[0] if theme[0] is not None else -1) + 1), PAPER); theme_pill.pack(side="left")
pic(theme_pill, "swatch:" + (THEMES[theme[0]][3] if theme[0] is not None else "#E84FB0"))
# -- pen cursor ring: follows the cursor, but only while the tablet's pen is in range --
def hl_sig(): return (hl["on"], hl["size"], hl["style"], hl["color"], hl["thick"])

def hl_draw(pressed=False):
    cv = hl["cv"]
    if not cv: return
    S = hl["size"]; cv.delete("all"); t = max(2, S // 14) * hl["thick"] // 3 or 2
    col = RING_COLORS[hl["color"] % 6][0]; edge = INK if col != INK else "#FFFFFF"; m = S // 2
    if hl["style"] == 2:   # dot
        r = max(6, S // 5)
        if pressed: cv.create_oval(m - 2 * r, m - 2 * r, m + 2 * r, m + 2 * r, outline=col, width=t)
        cv.create_oval(m - r, m - r, m + r, m + r, fill=col, outline=edge, width=2)
    else:
        if pressed: cv.create_oval(t, t, S - t, S - t, fill=col, outline=col, stipple="gray50")
        if hl["style"] == 0:   # ring
            cv.create_oval(t, t, S - t, S - t, outline=col, width=t)
            cv.create_oval(t * 2, t * 2, S - t * 2, S - t * 2, outline=edge, width=2)
            for x0, y0, x1, y1 in ((m, 0, m, t * 3), (m, S - t * 3, m, S), (0, m, t * 3, m), (S - t * 3, m, S, m)):
                cv.create_line(x0, y0, x1, y1, fill=edge, width=2)
        else:                  # crosshair
            cv.create_line(0, m, S, m, fill=col, width=t); cv.create_line(m, 0, m, S, fill=col, width=t)
            cv.create_oval(m - S // 8, m - S // 8, m + S // 8, m + S // 8, outline=edge, width=2)
    hl["down"] = pressed

def hl_apply():
    hl["sig"] = hl_sig()
    if not hl["on"]:
        if hl["win"]: hl["win"].destroy(); hl.update(win=None, cv=None)
        return
    if hl["win"]: hl["win"].destroy()
    S = hl["size"]; wn = tk.Toplevel(root); wn.overrideredirect(True); wn.attributes("-topmost", True)
    wn.config(bg="#010101"); wn.attributes("-transparentcolor", "#010101")
    cv = tk.Canvas(wn, width=S, height=S, bg="#010101", highlightthickness=0); cv.pack()
    hl.update(win=wn, cv=cv); wn.update_idletasks(); set_click_through(wn); hl_draw(); wn.withdraw()

def pen_position():
    """Where the pen is hovering or touching (the screen pixel the tablet maps it to), or None when no pen is in range.
    This is the pen's own position, not the mouse cursor's."""
    now = time.time()
    for d in list(srv.devices.values()):
        if d.pen and d.pen.inrange and now - d.pen.last < 2: return d.pen.lastpos
    return None

def hl_tick():
    """The ring shows only while the pen is detected, and sits exactly where the pen is. Otherwise it is hidden."""
    if hl_sig() != hl["sig"]: hl_apply(); hl_refresh_labels()
    wn = hl["win"]; pos = None
    if wn:
        pos = pen_position()
        if pos:
            S = hl["size"]
            if hl.get("last") != (pos, S): hl["last"] = (pos, S); wn.geometry(f"{S}x{S}+{pos[0] - S // 2}+{pos[1] - S // 2}")
            if wn.state() == "withdrawn": wn.deiconify(); set_click_through(wn)
            pressed = bool(ns.u32.GetAsyncKeyState(1) & 0x8000 or ns.u32.GetAsyncKeyState(2) & 0x8000)
            if pressed != hl["down"]: hl_draw(pressed)
        elif wn.state() != "withdrawn": wn.withdraw()
    root.after(16 if pos else 120, hl_tick)   # 60 Hz only while a pen is being tracked

def toggle_hl():
    hl["on"] = not hl["on"]; flag(hl_pill, "ring", hl["on"]); hl_apply(); save()

def hl_size(d):
    hl["size"] = max(50, min(220, hl["size"] + d)); hl_size_lbl.config(text=f"SIZE {hl['size']}"); hl_apply(); save()

def hl_cycle(key, n):
    hl[key] = (hl[key] + 1) % n; hl_apply(); hl_refresh_labels(); save()

def hl_thick(d):
    hl["thick"] = max(1, min(8, hl["thick"] + d)); hl_apply(); hl_refresh_labels(); save()

def hl_refresh_labels():
    flag(hl_pill, "ring", hl["on"])
    hl_size_lbl.config(text=f"SIZE {hl['size']}")
    pic(hl_style_pill, RING_STYLES[hl["style"]]); pic(hl_color_pill, "swatch", RING_COLORS[hl["color"]][0])
    hl_thick_lbl.config(text=f"THICKNESS {hl['thick']}")

hrow = tk.Frame(st, bg=PAPER); hrow.pack(fill="x", pady=(0, 8))
hl_pill = pill(hrow, "PEN CURSOR RING", toggle_hl, GREEN, "ring"); hl_pill.pack(side="left", padx=(0, 6))
pill(hrow, "", lambda: hl_size(-10), BABY, "minus", "Smaller").pack(side="left")
hl_size_lbl = label(hrow, text=f"SIZE {hl['size']}"); hl_size_lbl.pack(side="left", padx=6)
pill(hrow, "", lambda: hl_size(10), BABY, "plus", "Bigger").pack(side="left")
hrow2 = tk.Frame(st, bg=PAPER); hrow2.pack(fill="x", pady=(0, 8))
hl_style_pill = pill(hrow2, "", lambda: hl_cycle("style", 3), LILAC, "ring", "Shape"); hl_style_pill.pack(side="left", padx=(0, 6))
hl_color_pill = pill(hrow2, "", lambda: hl_cycle("color", 6), PAPER, "swatch", "Colour"); hl_color_pill.pack(side="left", padx=(0, 6))
pill(hrow2, "", lambda: hl_thick(-1), BABY, "minus", "Thinner").pack(side="left")
hl_thick_lbl = label(hrow2, text=""); hl_thick_lbl.pack(side="left", padx=6)
pill(hrow2, "", lambda: hl_thick(1), BABY, "plus", "Thicker").pack(side="left")
hl_refresh_labels()

# -- running in the background --
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
def autostart_on():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k: winreg.QueryValueEx(k, "PixelPadDesk"); return True
    except OSError: return False

def toggle_autostart():
    import winreg
    cmd = f'"{sys.executable}" --tray' if getattr(sys, "frozen", False) else f'"{sys.executable.replace("python.exe", "pythonw.exe")}" "{os.path.abspath(__file__)}" --tray'
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if autostart_on(): winreg.DeleteValue(k, "PixelPadDesk")
        else: winreg.SetValueEx(k, "PixelPadDesk", 0, winreg.REG_SZ, cmd)
    flag(auto_pill, "windows", autostart_on())

brow = tk.Frame(st, bg=PAPER); brow.pack(fill="x", pady=(0, 8))
auto_pill = pill(brow, "START WITH WINDOWS", toggle_autostart, LILAC, "windows"); flag(auto_pill, "windows", autostart_on()); auto_pill.pack(side="left", padx=(0, 6))
pill(brow, "QUIT PIXELPAD DESK", lambda: quit_app(), PINK, "power").pack(side="left", padx=(0, 6))
def toggle_record():
    srv.allow_record = not srv.allow_record; flag(rec_pill, "keyboard", srv.allow_record); save()
rec_pill = pill(brow, "CONTROLLER DEVICES MAY RECORD SHORTCUTS", toggle_record, BABY, "keyboard"); flag(rec_pill, "keyboard", srv.allow_record); rec_pill.pack(side="left")
hint(st, "CLOSING THE WINDOW KEEPS PIXELPAD RUNNING IN THE TRAY (BOTTOM RIGHT OF THE TASKBAR).", wrap=700).pack(fill="x", pady=(0, 8))
# -- pairing --
prow = tk.Frame(st, bg=PAPER); prow.pack(fill="x", pady=(0, 4))
def toggle_legacy():
    srv.allow_legacy = not srv.allow_legacy; flag(legacy_pill, "lock", srv.allow_legacy); save()
legacy_pill = pill(prow, "ALLOW OLD APPS WITHOUT PAIRING (NOT SAFE)", toggle_legacy, PAPER, "lock"); flag(legacy_pill, "lock", srv.allow_legacy); legacy_pill.pack(side="left", padx=(0, 6))
def new_code():
    from tkinter import messagebox
    if not messagebox.askyesno("PixelPad Desk", "Make a new pairing code? Every device paired with the old one has to scan the QR code again."): return
    pair_key[0] = secrets.token_hex(16); srv.pair = ns.Pairing(bytes.fromhex(pair_key[0]))
    for k in list(srv.devices): srv.drop(k)
    show_code(); draw_qr(); save(); log("new pairing code made: devices must scan the QR code again")
pill(prow, "NEW PAIRING CODE", new_code, LILAC, "retry").pack(side="left")
hint(st, "EVERY PACKET IS SIGNED AND ENCRYPTED WITH THE PAIRING CODE IN THE QR CODE, SO ONLY DEVICES THAT SCANNED IT CAN CONTROL THIS PC. SHARE THE CODE ONLY WITH YOUR OWN DEVICES. OLD APPS (BEFORE 1.3.0) HAVE NO CODE.", wrap=700).pack(fill="x", pady=(0, 8))
# -- version and updates (the logic is with the update code below) --
label(st, text="VERSION AND UPDATES").pack(fill="x")
vcard = tk.Frame(st, bg=PAPER); vcard.pack(fill="x", pady=(2, 8))
vl = label(vcard, text=f" PIXELPAD DESK {VERSION}"); pic(vl, "gear"); vl.pack(fill="x")
vstat = label(vcard, text=" CHECKING GITHUB...", font=FS); vstat.pack(fill="x", pady=(2, 4))
vbw, vbh = round(320 * SC), round(20 * SC)
vbar = tk.Canvas(vcard, width=vbw, height=vbh, bg=PAPER, highlightthickness=T, highlightbackground=INK)   # shown only while downloading
vfill = vbar.create_rectangle(0, 0, 0, vbh, fill=HOT, width=0)
vbtn = pill(vcard, "CHECK FOR UPDATES", lambda: v_click(), GREEN, "retry"); vbtn.pack(anchor="w")
label(st, text="LOG").pack(fill="x")
out = scrolledtext.ScrolledText(st, height=6, font=FS, bg=PAPER, fg=INK, relief="solid"); out.pack(fill="both", expand=True)
lrow = tk.Frame(st, bg=PAPER); lrow.pack(fill="x", pady=(6, 0))
pill(lrow, "", lambda: out.delete("1.0", "end"), PINK, "trash", "Clear the log").pack(side="left", padx=(0, 6))
pill(lrow, "", lambda: (root.clipboard_clear(), root.clipboard_append(out.get("1.0", "end"))), BABY, "copy", "Copy the log").pack(side="left")

def log(*a):
    def put():
        out.insert("end", time.strftime("%H:%M:%S ") + " ".join(map(str, a)) + "\n"); out.see("end")
        if int(out.index("end-1c").split(".")[0]) > 500: out.delete("1.0", "2.0")   # keep the last 500 lines: a flood can't grow it without end
    root.after(0, put)
ns.log = log

# ---------- toolbar buttons ----------
bar_pills = {}
for key, ic, txt in (("connect", "qr", "CONNECT"), ("status", "pulse", "STATUS"), ("checklist", "check", "CHECKLIST"), ("area", "crop", "DEVICE AREA")):
    bar_pills[key] = pill(bar, txt, lambda k=key: set_visible(k, not reg[k]["visible"]), reg[key]["color"], ic)
    bar_pills[key].pack(side="left", padx=int(4 * SC), pady=int(3 * SC))

def toggle_page():
    global page
    page = "dash" if page == "settings" else "settings"
    settings_pill.config(text="BACK" if page == "settings" else "SETTINGS"); pic(settings_pill, "left" if page == "settings" else "gear"); layout(); refresh_bar()
settings_pill = pill(bar, "SETTINGS", toggle_page, BABY, "gear"); settings_pill.pack(side="right", padx=int(4 * SC), pady=int(3 * SC))

def refresh_bar():
    for k, pl in bar_pills.items():
        on = reg[k]["visible"] and page == "dash"
        pl.base = reg[k]["color"] if on else PAPER; pl.config(bg=pl.base)

# ---------- live updates ----------
last = {}
map_sig = [None]
tick_n = [0]

def _tick():
    tick_n[0] += 1
    if tick_n[0] % 20 == 0: refresh_ips()
    if not ui_visible[0]: update_overlay(); return   # hidden in the tray: only the on-screen outline needs care
    if update_found[0] and not update_asked[0]: update_asked[0] = True; root.after(200, ask_update)
    now = time.time(); devs = sorted(srv.devices.values(), key=lambda x: x.slot)
    status.config(text=(" CONNECTED" if devs else " WAITING..."), fg=INK if devs else HOT)
    pic(status, ("check",) + tuple(dict.fromkeys(x.transport for x in devs)) if devs else "retry", INK if devs else HOT, int(26 * SC))
    r = next((x.rtt_us for x in devs if x.rtt_us and x.rtt_us > 0), None)
    n = 0 if r is None else min(10, int(r / 2000) + 1)
    latency.config(text=(" RTT " + "■" * n + "□" * (10 - n) + f" {r / 1000:.1f} MS") if r else " RTT ----------")
    by = {x.slot: x for x in devs}
    for i, p in enumerate(players, 1):
        x = by.get(i)
        if not x: p.config(text=f"P{i}  ---- EMPTY ----"); pic(p, "swatch:" + NO_COLOUR); continue   # an empty slot has no colour: none is made up for it
        rate = (x.count - last.get(i, (0, now))[0]) / max(now - last.get(i, (0, now - 1))[1], 1e-3); last[i] = (x.count, now)
        pic(p, ("swatch:" + (THEMES[x.colour % len(THEMES)][3] if x.colour is not None else NO_COLOUR), x.transport, {0: "touchpad", 1: "pen", 2: "gamepad"}.get(x.mode, "dot")))   # their colour first
        p.config(text=f"P{i}  RTT {x.rtt_us / 1000 if x.rtt_us and x.rtt_us > 0 else 0:4.1f}MS {rate:4.0f}/S")
    ph = current_phone(); ax, ay, aw, ah = srv.area(ph)
    inuse.config(text=(f"DEVICE {ph[0]}x{ph[1]}  ->  {aw}x{ah} AT {ax},{ay}" if ph else f"AREA {aw}x{ah} AT {ax},{ay} (WAITING FOR A CONTROLLER DEVICE)"))
    sig = (srv.area(ph), srv.monitor, srv.area_mode)
    if page == "dash" and reg["area"]["visible"] and not drag and sig != map_sig[0]: map_sig[0] = sig; draw_map()
    update_overlay()

def tick():
    try: _tick()
    except Exception as ex: log("screen refresh:", ex)
    finally: root.after(500, tick)

ui_visible = [True]   # False while the window is hidden in the tray

def checker():
    global results
    while True:
        try:
            wifi_only = bool(srv.devices) and all(d.transport == "wifi" for d in list(srv.devices.values()))
            if not wifi_only: ns.adb_reverse(PORT)   # keep the cable link alive; nothing to do over Wi-Fi
            if ui_visible[0] and reg["checklist"]["visible"] and page == "dash":   # the checklist is only worked out while someone can see it
                results = ns.diagnose(srv, PORT)
                root.after(0, show_checks)
        except Exception as ex:
            log("check failed:", ex)
        time.sleep(3 if not srv.devices else 15)   # looking for a tablet: often; connected: rarely

srv.run()
threading.Thread(target=checker, daemon=True).start()
log(f"Listening on port {PORT}")
try:
    import pystray
    from PIL import Image
    tray = pystray.Icon("pixelpad", Image.open(res("pixelpad.png")), "PixelPad Desk",
                        menu=pystray.Menu(pystray.MenuItem("Open PixelPad Desk", lambda: root.after(0, show_window), default=True),
                                          pystray.MenuItem("Quit", lambda: root.after(0, quit_app))))
    tray.run_detached()
except Exception as ex:
    tray = None; log("no tray icon:", ex)

told_tray = [False]

def show_window():
    ui_visible[0] = True; root.deiconify(); root.lift(); root.focus_force()

def hide_window():
    if tray:
        ui_visible[0] = False; root.withdraw()
        if not told_tray[0]:   # say once that it's still running
            told_tray[0] = True
            try: tray.notify("PixelPad Desk is still running here. Click the icon to open it, or right-click to quit.", "PixelPad Desk")
            except Exception: pass
    else: root.iconify()

# ---------- updates: on start, look for a newer release on GitHub and offer it; Settings > version and updates does the same on request ----------
update_found, update_asked = [None], [False]

def progress_text(got, total):
    if got < 0: return "RESTARTING..."
    if total and got < total: return f"DOWNLOADING {got * 100 // total}%  ({got / 1048576:.1f} / {total / 1048576:.1f} MB)"
    return "CHECKING THE DOWNLOAD..." if total else f"DOWNLOADING {got / 1048576:.1f} MB"

def do_update(exe, sums, show, fail):
    """Downloads the new exe (show(got, total) reports progress, show(-1, -1) means restarting), checks it, then swaps it in and starts it.
    The download goes to a private folder next to the exe (same drive, so the swap is an atomic rename), and the checked file is the one that is
    swapped in. fail(text) is called if anything goes wrong; both run on the window's thread."""
    def work():
        import shutil, tempfile
        me = sys.executable; folder = os.path.dirname(me)
        try: tmp = tempfile.mkdtemp(prefix="pixelpad-", dir=folder)
        except OSError: root.after(0, fail, "PixelPad Desk can't write next to its own exe. Move it to a folder you own and try again."); return
        new, old = os.path.join(tmp, "PixelPadDesk.exe"), me + ".old"
        try:
            if not ns.fetch_update(exe, sums, new, lambda g, t: root.after(0, show, g, t)):
                root.after(0, fail, "The update could not be downloaded or did not check out. Nothing was changed."); return
            try:
                try: os.remove(old)
                except OSError: pass
                os.replace(me, old)           # Windows lets a running exe be renamed, not overwritten
                try: os.replace(new, me)
                except OSError: os.replace(old, me); raise
            except OSError:
                root.after(0, fail, "The update downloaded but could not be swapped in. Nothing was changed."); return
            shutil.rmtree(tmp, ignore_errors=True)   # now, not in finally: quitting below ends the process first
            # a fresh start, not a re-use of this copy's unpacked files: PyInstaller leaves variables in the environment that would make the new copy share them
            env = {k: v for k, v in os.environ.items() if not k.startswith("_MEI") and not k.startswith("_PYI")}; env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
            try: subprocess.Popen([me, "--updated"] + (["--tray"] if "--tray" in sys.argv else []), cwd=folder, close_fds=True, creationflags=0x00000008, env=env,
                                  stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)   # the new copy waits for this one to leave
            except OSError:
                try: os.replace(me, new); os.replace(old, me)
                except OSError: pass
                root.after(0, fail, "The update was installed but PixelPad Desk could not start it. Open PixelPad Desk again."); return
            root.after(0, show, -1, -1); root.after(0, quit_app)
        finally: shutil.rmtree(tmp, ignore_errors=True)
    threading.Thread(target=work, daemon=True).start()

def ask_update():
    """Called once the window is on screen (not while hidden in the tray)."""
    from tkinter import messagebox
    ver, exe, sums, page = update_found[0]
    frozen = getattr(sys, "frozen", False)
    if not messagebox.askyesno("PixelPad Desk", f"A new version, {ver}, is out (you have {VERSION}).\n\n" + ("Update now? PixelPad Desk restarts when it is done." if frozen and exe else "Open the download page?")):
        return
    if not (frozen and exe):
        import webbrowser; webbrowser.open(page or "https://github.com/nyx-ulrix/pixelpad/releases/latest"); return
    note = tk.Toplevel(root); note.title("UPDATING"); note.config(bg=PAPER, highlightthickness=T, highlightbackground=INK)
    msg = tk.Label(note, text="  STARTING THE DOWNLOAD...  ", bg=PAPER, fg=INK, font=FB, pady=10); msg.pack(padx=18)
    bw, bh = round(320 * SC), round(20 * SC)
    bar = tk.Canvas(note, width=bw, height=bh, bg=PAPER, highlightthickness=T, highlightbackground=INK); bar.pack(padx=18, pady=(0, 16))
    fill = bar.create_rectangle(0, 0, 0, bh, fill=HOT, width=0)
    note.geometry(f"+{root.winfo_x() + 80}+{root.winfo_y() + 80}")
    def show(got, total):
        msg.config(text=f"  {progress_text(got, total)}  ")
        bar.coords(fill, 0, 0, bw * got // total if total and got >= 0 else 0, bh)
    do_update(exe, sums, show, lambda text: (note.destroy(), messagebox.showerror("PixelPad Desk", text)))

# -- Settings > version and updates --
v_busy = [False]

def v_idle(text):
    v_busy[0] = False; vbar.pack_forget(); vstat.config(text=" " + text.upper())
    vbtn.config(text=f"UPDATE TO {update_found[0][0]}" if update_found[0] else "CHECK FOR UPDATES")

def v_show(got, total):
    if got >= 0 and not vbar.winfo_manager(): vbar.pack(anchor="w", pady=(0, 6), before=vbtn)
    vstat.config(text=" " + progress_text(got, total))
    vbar.coords(vfill, 0, 0, vbw * got // total if total and got >= 0 else 0, vbh)

def v_check():
    v_busy[0] = True; vstat.config(text=" CHECKING GITHUB...")
    def work():
        r = ns.latest_release()
        def done():
            if r and ns.newer_version(r[0], VERSION): update_found[0] = r; v_idle(f"VERSION {r[0]} IS OUT")
            elif r: update_found[0] = None; v_idle("YOU HAVE THE LATEST VERSION")
            else: v_idle("COULDN'T REACH GITHUB. CHECK THE INTERNET AND TRY AGAIN")
        root.after(0, done)
    threading.Thread(target=work, daemon=True).start()

def v_click():
    if v_busy[0]: return
    if not update_found[0]: v_check(); return
    ver, exe, sums, page = update_found[0]
    if not (getattr(sys, "frozen", False) and exe):   # running from source: nothing to swap, so open the download page
        import webbrowser; webbrowser.open(page or "https://github.com/nyx-ulrix/pixelpad/releases/latest"); return
    v_busy[0] = True; vstat.config(text=" STARTING THE DOWNLOAD...")
    do_update(exe, sums, v_show, v_idle)

v_check()   # on start: also what offers the update prompt (the window asks once, when it is on screen)

def quit_app():
    try:
        if tray: tray.stop()
    finally:
        for k in list(srv.devices): srv.drop(k)
        os._exit(0)

def wake_listener():
    while True:
        try: d, _ = _lock.recvfrom(16)
        except OSError: return
        if d == b"show": root.after(0, show_window)

def clean_old():
    """After an update the previous exe is left as PixelPadDesk.exe.old: remove it once the old copy has gone."""
    old = sys.executable + ".old"
    for _ in range(60):
        try: os.remove(old); return
        except FileNotFoundError: return
        except OSError: time.sleep(1)
if getattr(sys, "frozen", False): threading.Thread(target=clean_old, daemon=True).start()

root.protocol("WM_DELETE_WINDOW", hide_window)
if _lock: threading.Thread(target=wake_listener, daemon=True).start()
order[:] = ["connect", "status", "checklist", "area", "settings"]   # a new user needs the QR code first
root.update_idletasks(); layout(); refresh_bar(); draw_map(); tick(); hl_apply(); hl_tick()
if "--tray" in sys.argv and tray: ui_visible[0] = False; root.withdraw()
save()   # the pairing key is made on the first run: keep it
root.mainloop()
