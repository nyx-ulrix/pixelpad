"""Controller profiles kept on the PC, links from Steam games (or any program) to a profile, and the watcher that notices a linked game starting.

A profile is a controller layout: a name, the template it starts from (ps, xbox, switch, joycon, fight) and where each control sits and how big it is
("id:x:y:size;..."), exactly as the app stores a layout. A fifth field, 0 or 1, says a control is hidden or shown against the template's default. Every connected device can list, load, save and delete them (packet mode 10, see the table below), so
a layout made on one device is there on all of them. Nothing here locks a device to a profile: a link only tells devices which profile a game likes, once,
when that game starts, and the device may follow it or not.

Packet mode 10, a 16-byte packet: [10, op, message id, chunk number, chunk count, 11 bytes of text]. Text longer than 11 bytes is sent as several chunks (the
last is padded with zeros). App -> PC: 1 list, 2 get (text = the profile number), 3 put (text = "id\\nname\\ntemplate\\nlayout", id 0 = a new profile), 4 delete
(text = the number). PC -> app: 0x81 list ("id<TAB>name" lines), 0x82 profile ("id\\nname\\ntemplate\\nlayout", empty if there is no such profile), 0x83 saved
("id\\nname", id 0 if refused), 0x84 deleted (the number, 0 if there was none), 0x85 switch ("id\\ngame": a linked game started, use that profile).
"""
import ctypes, json, os, re, threading, time
from ctypes import wintypes as w

PROFILE_MODE, CHUNK = 10, 11
LIST, GET, PUT, DELETE = 1, 2, 3, 4
R_LIST, R_PROFILE, R_SAVED, R_DELETED, R_SWITCH = 0x81, 0x82, 0x83, 0x84, 0x85
TEMPLATES = ("ps", "xbox", "switch", "joycon", "fight")
MAX_PROFILES, MAX_LIST, MAX_LAYOUT = 64, 32, 1000
BASE = os.path.join(os.environ.get("APPDATA", "."), "PixelPadDesk", "profiles")


# ---------- text sent as 16-byte packets ----------
def chunks(op, mid, text):
    """The packets that carry text (op = what it is, mid = which message)."""
    data = text.encode("utf-8")
    n = max(1, -(-len(data) // CHUNK))
    if n > 255: return []
    return [bytes([PROFILE_MODE, op, mid & 255, i, n]) + data[i * CHUNK:(i + 1) * CHUNK].ljust(CHUNK, b"\0") for i in range(n)]


class Reassembler:
    """Collects the chunks of a few messages at a time and returns the text once every chunk of one has arrived."""
    def __init__(self, keep=6): self.parts, self.keep = {}, keep

    def add(self, pkt):
        op, mid, seq, total = pkt[1], pkt[2], pkt[3], pkt[4]
        if total == 0 or seq >= total: return None
        key = (op, mid)
        if key not in self.parts:
            if len(self.parts) >= self.keep: self.parts.pop(next(iter(self.parts)))
            self.parts[key] = (total, {})
        have = self.parts[key]
        if have[0] != total: self.parts.pop(key); return None
        have[1][seq] = bytes(pkt[5:16])
        if len(have[1]) < total: return None
        del self.parts[key]
        return b"".join(have[1][i] for i in range(total)).rstrip(b"\0").decode("utf-8", "replace")


# ---------- cleaning what a device sends ----------
def clean_name(s):
    s = re.sub(r"[\x00-\x1f\x7f\s]+", " ", str(s)).strip()
    return s[:20] or "PROFILE"

def clean_layout(text):
    """A layout string made safe and canonical ("id:x:y:size;..." with 3 decimals), or None if it isn't one."""
    if not isinstance(text, str) or len(text) > MAX_LAYOUT: return None
    out = []
    for part in text.split(";"):
        if not part: continue
        a = part.split(":")
        if len(a) not in (4, 5) or not re.fullmatch(r"[a-z0-9]{1,6}", a[0]) or (len(a) == 5 and a[4] not in ("0", "1")): return None
        try: x, y, r = float(a[1]), float(a[2]), float(a[3])
        except ValueError: return None
        if not (-0.2 <= x <= 1.2 and -0.2 <= y <= 1.2 and 0.01 <= r <= 0.45): return None
        out.append(f"{a[0]}:{x:.3f}:{y:.3f}:{r:.3f}" + (f":{a[4]}" if len(a) == 5 else ""))
        if len(out) > 40: return None
    return ";".join(out) if out else None


class Profiles:
    """The profile library and the game links, as small JSON files in one folder (nothing outside it is ever touched)."""
    def __init__(self, folder=BASE):
        self.folder, self.lock, self._lc = folder, threading.RLock(), (None, [])   # _lc: the last list and the folder's change time it was made at

    def _path(self, pid): return os.path.join(self.folder, f"{int(pid)}.json")

    def _write(self, path, obj):
        os.makedirs(self.folder, exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f: json.dump(obj, f)
        os.replace(tmp, path)

    def _read(self, path):
        with self.lock:   # not while a writer is swapping the file in (Windows refuses that while the file is open)
            try:
                with open(path, encoding="utf-8") as f: return json.load(f)
            except (OSError, ValueError): return None

    def ids(self):
        try: names = os.listdir(self.folder)
        except OSError: return []
        return sorted(int(n[:-5]) for n in names if re.fullmatch(r"[1-9][0-9]{0,4}\.json", n) and int(n[:-5]) <= 65535)

    def get(self, pid):
        if not isinstance(pid, int) or not 1 <= pid <= 65535: return None
        p = self._read(self._path(pid))
        if not isinstance(p, dict) or p.get("template") not in TEMPLATES: return None
        lay = clean_layout(p.get("layout"))
        return {"id": pid, "name": clean_name(p.get("name", "")), "template": p["template"], "layout": lay} if lay else None

    def list(self):
        """[(id, name)] sorted by name. Read again only when the folder has changed."""
        try: stamp = os.stat(self.folder).st_mtime_ns
        except OSError: return []
        if stamp == self._lc[0]: return list(self._lc[1])
        out = sorted(((p["id"], p["name"]) for p in (self.get(i) for i in self.ids()) if p), key=lambda t: (t[1].lower(), t[0]))
        self._lc = (stamp, out); return list(out)

    def put(self, name, template, layout, pid=0):
        """Saves a profile (a new one if pid is 0 or unknown) and returns its number, or 0 if it was refused."""
        lay = clean_layout(layout)
        if template not in TEMPLATES or not lay: return 0
        with self.lock:
            have = self.ids()
            if pid not in have:
                if len(have) >= MAX_PROFILES: return 0
                pid = next(i for i in range(1, 65536) if i not in have)
            self._write(self._path(pid), {"id": pid, "name": clean_name(name), "template": template, "layout": lay, "updated": time.time()})
        return pid

    def rename(self, pid, name):
        p = self.get(pid)
        return bool(p) and self.put(name, p["template"], p["layout"], pid) == pid

    def delete(self, pid):
        with self.lock:
            if pid not in self.ids(): return False
            try: os.remove(self._path(pid))
            except OSError: return False
            links = self.links()
            for kind in links:
                for k in [k for k, v in links[kind].items() if v == pid]: del links[kind][k]
            self._write(os.path.join(self.folder, "links.json"), links)
        return True

    # -- links: a Steam game (by app id) or a program (by exe name) -> the profile it likes --
    def links(self):
        d = self._read(os.path.join(self.folder, "links.json"))
        out = {"steam": {}, "exe": {}}
        if isinstance(d, dict):
            for kind in out:
                if isinstance(d.get(kind), dict):
                    out[kind] = {str(k)[:120]: v for k, v in d[kind].items() if isinstance(v, int) and not isinstance(v, bool) and 1 <= v <= 65535}
        return out

    def link(self, kind, key, pid):
        """Link a game or program to profile pid (0 removes the link)."""
        if kind not in ("steam", "exe"): return
        key = str(key).strip().lower() if kind == "exe" else str(int(key))
        with self.lock:
            links = self.links()
            if pid and pid in self.ids(): links[kind][key] = pid
            else: links[kind].pop(key, None)
            self._write(os.path.join(self.folder, "links.json"), links)

    def linked(self, kind, key):
        return self.links()[kind].get(str(key).lower() if kind == "exe" else str(key), 0)


# ---------- Steam ----------
def parse_vdf(text):
    """Steam's text format ("key" "value" and "key" { ... }) as nested dicts."""
    toks = [(a.replace("\\\\", "\\").replace('\\"', '"'), b) for a, b in re.findall(r'"((?:[^"\\]|\\.)*)"|([{}])', text)]
    pos = 0
    def block(depth=0):
        nonlocal pos
        d = {}
        if depth > 40: return d   # nothing real nests this deep
        while pos < len(toks):
            s, brace = toks[pos]; pos += 1
            if brace == "}": return d
            if brace == "{": continue
            if pos < len(toks) and toks[pos][1] == "{": pos += 1; d[s] = block(depth + 1)
            elif pos < len(toks): d[s] = toks[pos][0]; pos += 1
        return d
    return block()

def steam_root():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k: p = winreg.QueryValueEx(k, "SteamPath")[0]
        if os.path.isdir(p): return os.path.normpath(p)
    except OSError: pass
    for p in (r"C:\Program Files (x86)\Steam", r"C:\Program Files\Steam"):
        if os.path.isdir(p): return p
    return None

def steam_games(root=None):
    """[(app id, name)] of the games installed in every Steam library, by name. Steam's own tools are left out."""
    root = root or steam_root()
    if not root: return []
    libs = [root]
    try:
        with open(os.path.join(root, "steamapps", "libraryfolders.vdf"), encoding="utf-8", errors="replace") as f: lf = parse_vdf(f.read())
        for v in (lf.get("libraryfolders") or lf).values():
            if isinstance(v, dict) and isinstance(v.get("path"), str) and os.path.isdir(v["path"]): libs.append(os.path.normpath(v["path"]))
    except OSError: pass
    seen, out = set(), []
    for lib in dict.fromkeys(libs):
        try: names = os.listdir(os.path.join(lib, "steamapps"))
        except OSError: continue
        for n in names:
            m = re.fullmatch(r"appmanifest_(\d+)\.acf", n)
            if not m or int(m.group(1)) in seen: continue
            try:
                with open(os.path.join(lib, "steamapps", n), encoding="utf-8", errors="replace") as f: st = parse_vdf(f.read()).get("AppState", {})
            except OSError: continue
            name = str(st.get("name", "")).strip()
            if not name or re.search(r"redistributable|steamworks|steam linux runtime|^proton", name, re.I): continue
            seen.add(int(m.group(1))); out.append((int(m.group(1)), name[:60]))
    return sorted(out, key=lambda t: t[1].lower())

def steam_running():
    """The app id of the Steam game that is running now (Steam keeps it in the registry), or 0."""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k: app = int(winreg.QueryValueEx(k, "RunningAppID")[0])
        if app:   # Steam leaves RunningAppID behind if it is closed while a game runs: its own "Running" flag for the game says whether it really is
            try:
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, rf"Software\Valve\Steam\Apps\{app}") as a:
                    if int(winreg.QueryValueEx(a, "Running")[0]) != 1: return 0
            except (OSError, ValueError): pass
        return app
    except (OSError, ValueError): return 0


# ---------- running programs (only asked when a program is linked) ----------
class _PE(ctypes.Structure):
    _fields_ = [("dwSize", w.DWORD), ("cntUsage", w.DWORD), ("th32ProcessID", w.DWORD), ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", w.DWORD),
                ("cntThreads", w.DWORD), ("th32ParentProcessID", w.DWORD), ("pcPriClassBase", w.LONG), ("dwFlags", w.DWORD), ("szExeFile", w.WCHAR * 260)]

def running_exes():
    """The lower-case exe names of every running program."""
    k = ctypes.WinDLL("kernel32", use_last_error=True)
    k.CreateToolhelp32Snapshot.restype = ctypes.c_void_p; k.CreateToolhelp32Snapshot.argtypes = [w.DWORD, w.DWORD]
    k.Process32FirstW.argtypes = k.Process32NextW.argtypes = [ctypes.c_void_p, ctypes.POINTER(_PE)]; k.CloseHandle.argtypes = [ctypes.c_void_p]
    h = k.CreateToolhelp32Snapshot(2, 0)
    if not h or h == ctypes.c_void_p(-1).value: return set()
    out, e = set(), _PE(); e.dwSize = ctypes.sizeof(_PE)
    try:
        ok = k.Process32FirstW(h, ctypes.byref(e))
        while ok: out.add(e.szExeFile.lower()); ok = k.Process32NextW(h, ctypes.byref(e))
    finally: k.CloseHandle(h)
    return out


class Watcher:
    """Notices a linked game starting and calls on_switch(profile id, game name) once for it. It never repeats while the same game keeps running,
    never undoes what a device chose afterwards, and says nothing when the game ends."""
    def __init__(self, profiles, on_switch, names=lambda appid: "", running=None, exes=running_exes):
        self.profiles, self.on_switch, self.names = profiles, on_switch, names
        self.running, self.exes = running or steam_running, exes
        self.key, self.current, self.seq = None, None, 0   # what is running now that is linked; (profile id, game name) of it; a number for this start of it

    def poll(self):
        links = self.profiles.links()
        key = pid = None; game = ""
        app = self.running()
        if app and links["steam"].get(str(app)): key, pid, game = ("steam", app), links["steam"][str(app)], self.names(app) or f"STEAM {app}"
        elif links["exe"]:
            up = self.exes()
            for exe, p in links["exe"].items():
                if exe in up: key, pid, game = ("exe", exe), p, exe; break
        if key == self.key: return
        self.key = key
        self.current = (pid, game) if key else None
        if key: self.seq = int(time.time()); self.on_switch(pid, game)

    def start(self, every=2.0):
        def loop():
            while True:
                try: self.poll()
                except Exception: pass
                time.sleep(every)
        threading.Thread(target=loop, daemon=True).start()
