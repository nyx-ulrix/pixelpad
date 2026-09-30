"""Generates the PixelPad icon (a pixel-art pen on the pastel grid) for Android and Windows.
Run:  python assets/make_icon.py
Writes the Android vector icons under android/app/src/main/res and assets/pixelpad.png + pixelpad.ico for the PC app."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
N = 24
SKY, BLUSH = (0xC9, 0xEE, 0xFF), (0xFF, 0xC9, 0xEA)
COL = {"O": (0x2F, 0x6F, 0xE0), "W": (0xF7, 0xFB, 0xFF), "P": (0xFF, 0xB8, 0xE6), "H": (0xE8, 0x4F, 0xB0), "Y": (0xD9, 0xC8, 0xFF)}

def art():
    """The pen: a 3-pixel-wide diagonal with a lilac nib, white body, pink band and cap, and a blue outline."""
    g = [["." for _ in range(N)] for _ in range(N)]
    fill = {}
    for x in range(16):
        for y in range(16):
            d, t = x + y - 15, x - y
            if abs(d) <= 1 and -9 <= t <= 9:
                fill[(x + 4, y + 4)] = "O" if t <= -8 else "Y" if t <= -5 else "W" if t <= 3 else "H" if t <= 5 else "P"
    for (x, y) in list(fill):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in fill: g[y + dy][x + dx] = "O"
    for (x, y), c in fill.items(): g[y][x] = c
    for cx, cy, c in ((6, 6, "Y"), (18, 17, "H"), (17, 5, "Y")):   # sparkles
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            if g[cy + dy][cx + dx] == ".": g[cy + dy][cx + dx] = c
    return g

def bg_color(y):
    t = y / (N - 1)
    return tuple(round(a + (b - a) * t) for a, b in zip(SKY, BLUSH))

def hexc(c): return "#%02X%02X%02X" % c

def vector(paths):
    body = "\n".join(f'    <path android:fillColor="{c}" android:pathData="{d}" />' for c, d in paths)
    return ('<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="108dp" android:height="108dp" android:viewportWidth="{N}" android:viewportHeight="{N}">\n{body}\n</vector>\n')

def runs(g):
    out = {}
    for y, row in enumerate(g):
        x = 0
        while x < N:
            c = row[x]
            if c == ".": x += 1; continue
            e = x
            while e < N and row[e] == c: e += 1
            out.setdefault(c, []).append(f"M{x},{y}h{e - x}v1h-{e - x}z"); x = e
    return [(hexc(COL[c]), "".join(d)) for c, d in out.items()]

def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f: f.write(text)

g = art()
bg = [(hexc(bg_color(y)), f"M0,{y}h{N}v1h-{N}z") for y in range(N)]
grid = "".join(f"M{i * 4},0h0.12v{N}h-0.12z" for i in range(1, 6)) + "".join(f"M0,{i * 4}h{N}v0.12h-{N}z" for i in range(1, 6))
bg.append(("#66FFFFFF", grid))
write(os.path.join(RES, "drawable", "ic_launcher_background.xml"), vector(bg))
write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), vector(runs(g)))
write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"),
      '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
      '    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n')
write(os.path.join(RES, "mipmap-anydpi", "ic_launcher.xml"),  # Android 7: the same two layers stacked
      '<?xml version="1.0" encoding="utf-8"?>\n<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
      '    <item android:drawable="@drawable/ic_launcher_background" />\n    <item android:drawable="@drawable/ic_launcher_foreground" />\n</layer-list>\n')

# Windows: png + multi-size ico with a blue frame around the pastel grid
from PIL import Image
im = Image.new("RGB", (N, N))
for y in range(N):
    for x in range(N):
        c = COL.get(g[y][x]) or bg_color(y)
        if x in (0, N - 1) or y in (0, N - 1): c = COL["O"]
        elif (x % 4 == 0 or y % 4 == 0) and g[y][x] == ".": c = tuple((v + 255) // 2 for v in c)
        im.putpixel((x, y), c)
big = im.resize((256, 256), Image.NEAREST)
os.makedirs(os.path.join(ROOT, "assets"), exist_ok=True)
big.save(os.path.join(ROOT, "assets", "pixelpad.png"))
big.save(os.path.join(ROOT, "assets", "pixelpad.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("icons written")
