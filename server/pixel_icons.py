"""The desktop app's pixel icons (the same drawings as the tablet app), rendered with Pillow and cached for tkinter."""
import math
from PIL import Image, ImageDraw, ImageTk

_cache = {}

def get(names, px, color="#2F6FE0"):
    """A tkinter image of icon `names` (a name, or a tuple of names drawn side by side), px pixels tall. Cached, so it stays alive."""
    if isinstance(names, str): names = (names,)
    key = (names, px, color)
    if key not in _cache:
        gap = px // 4
        im = Image.new("RGBA", (px * len(names) + gap * (len(names) - 1), px), (0, 0, 0, 0))
        for i, n in enumerate(names):
            nm, _, col = n.partition(":")                       # "swatch:#FF6FC8" draws that icon in its own colour
            im.paste(render(nm, px, col or color), (i * (px + gap), 0))
        _cache[key] = ImageTk.PhotoImage(im)
    return _cache[key]

def render(name, px, color):
    im = Image.new("RGBA", (px, px), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    w = max(2, px // 9); s = px * 0.42; cx = cy = px / 2
    P = lambda a, b: (cx + a * s, cy + b * s)
    def ln(a, b, c, e): d.line([P(a, b), P(c, e)], fill=color, width=w)
    def poly(pts, closed=True):
        q = [P(*t) for t in pts]; d.line(q + ([q[0]] if closed else []), fill=color, width=w, joint="curve")
    def box(a, b, c, e): d.rectangle([P(a, b), P(c, e)], outline=color, width=w)
    def circ(a, b, r, fill=False):
        (x0, y0), (x1, y1) = P(a - r, b - r), P(a + r, b + r)
        d.ellipse([x0, y0, x1, y1], outline=None if fill else color, fill=color if fill else None, width=w)
    def arc(a, b, r, start, end):
        (x0, y0), (x1, y1) = P(a - r, b - r), P(a + r, b + r)
        d.arc([x0, y0, x1, y1], start, end, fill=color, width=w)
    def rot(pts, deg):
        c, s_ = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        return [(a * c - b * s_, a * s_ + b * c) for a, b in pts]

    if name == "plus": ln(-1, 0, 1, 0); ln(0, -1, 0, 1)
    elif name == "minus": ln(-1, 0, 1, 0)
    elif name == "x": ln(-.8, -.8, .8, .8); ln(-.8, .8, .8, -.8)
    elif name == "check": ln(-.9, 0, -.3, .7); ln(-.3, .7, .9, -.7)
    elif name in ("menu",): ln(-1, -.7, 1, -.7); ln(-1, 0, 1, 0); ln(-1, .7, 1, .7)
    elif name in ("pen", "edit"):
        poly(rot([(-.3, -.95), (.3, -.95), (.3, .3), (-.3, .3)], -45)); poly(rot([(-.3, .3), (.3, .3), (0, .95)], -45)); poly(rot([(-.3, -.5), (.3, -.5)], -45), False)
    elif name == "touchpad": box(-1, -.7, 1, .7); ln(-1, .3, 1, .3); ln(0, .3, 0, .7)
    elif name == "gamepad": box(-1, -.5, 1, .6); ln(-.6, .05, -.2, .05); ln(-.4, -.15, -.4, .25); circ(.3, .05, .05, True); circ(.65, -.15, .05, True)
    elif name in ("present", "screen"): box(-1, -.8, 1, .4); ln(0, .4, 0, .9); ln(-.5, .9, .5, .9)
    elif name == "cursor": poly([(-.6, -.9), (-.6, .55), (-.25, .2), (.05, .9), (.3, .78), (0, .12), (.42, .12)])
    elif name in ("keys", "windows"): box(-.9, -.9, -.1, -.1); box(.1, -.9, .9, -.1); box(-.9, .1, -.1, .9); box(.1, .1, .9, .9)
    elif name == "keyboard": box(-1, -.6, 1, .6); [circ(x, -.2, .04, True) for x in (-.6, 0, .6)]; ln(-.5, .3, .5, .3)
    elif name == "warn": poly([(0, -.9), (.95, .8), (-.95, .8)]); ln(0, -.25, 0, .3); circ(0, .55, .04, True)
    elif name == "power": arc(0, 0, .8, -60, 240); ln(0, -1, 0, -.1)
    elif name == "retry": arc(0, 0, .8, -60, 220); ln(-.61, -.51, -.1, -.6); ln(-.61, -.51, -.75, 0)
    elif name == "gear":
        circ(0, 0, .5)
        for k in range(8): a = k * math.pi / 4; ln(.75 * math.cos(a), .75 * math.sin(a), math.cos(a), math.sin(a))
    elif name == "usb": ln(0, -1, 0, .6); ln(0, -1, -.4, -.5); ln(0, -1, .4, -.5); circ(0, .85, .25); ln(0, .1, .7, -.2); ln(.7, -.2, .7, -.5)
    elif name == "wifi":
        for k in (1, 2, 3):
            r = .45 * k; (x0, y0), (x1, y1) = P(-r, .8 - r), P(r, .8 + r); d.arc([x0, y0, x1, y1], 225, 315, fill=color, width=w)
        circ(0, .8, .06, True)
    elif name == "bt": poly([(-.5, -.5), (.5, .5), (0, 1), (0, -1), (.5, -.5), (-.5, .5)], False)
    elif name == "qr": box(-1, -1, -.2, -.2); box(.2, -1, 1, -.2); box(-1, .2, -.2, 1); circ(.4, .4, .05, True); circ(.9, .4, .05, True); circ(.6, .9, .05, True)
    elif name == "camera": box(-.9, -.6, .9, .7); circ(0, .05, .35); ln(-.4, -.6, -.25, -.9); ln(-.25, -.9, .25, -.9); ln(.25, -.9, .4, -.6)
    elif name == "lock": box(-.75, -.1, .75, .95); arc(0, -.4, .45, 180, 360)
    elif name == "gesture": poly([(-.9, .6), (-.3, .6), (.1, -.2), (.7, -.2)], False); ln(.7, -.2, .4, -.5); ln(.7, -.2, .4, .1)
    elif name == "left": ln(.8, 0, -.8, 0); ln(-.8, 0, -.2, -.6); ln(-.8, 0, -.2, .6)
    elif name == "right": ln(-.8, 0, .8, 0); ln(.8, 0, .2, -.6); ln(.8, 0, .2, .6)
    elif name == "pulse": poly([(-1, 0), (-.5, 0), (-.25, -.8), (.15, .8), (.4, 0), (1, 0)], False)
    elif name == "crop":
        for sx in (-1, 1):
            for sy in (-1, 1): ln(.9 * sx, .9 * sy, .3 * sx, .9 * sy); ln(.9 * sx, .9 * sy, .9 * sx, .3 * sy)
    elif name in ("eye", "eyeoff"):
        poly([(-1, 0), (-.5, -.5), (.5, -.5), (1, 0), (.5, .5), (-.5, .5)]); circ(0, 0, .25)
        if name == "eyeoff": ln(-.8, .8, .8, -.8)
    elif name == "copy": box(-.9, -.9, .3, .3); box(-.3, -.3, .9, .9)
    elif name == "trash": box(-.6, -.5, .6, .9); ln(-.9, -.6, .9, -.6); ln(-.3, -.6, -.3, -.9); ln(-.3, -.9, .3, -.9); ln(.3, -.9, .3, -.6); ln(0, -.2, 0, .6)
    elif name == "ring": circ(0, 0, .7); ln(0, -1, 0, -.6); ln(0, 1, 0, .6); ln(-1, 0, -.6, 0); ln(1, 0, .6, 0)
    elif name == "crosshair": ln(-1, 0, 1, 0); ln(0, -1, 0, 1); circ(0, 0, .3)
    elif name == "dot": circ(0, 0, .5, True)
    elif name == "rec": circ(0, 0, .85); circ(0, 0, .35, True)
    elif name == "swatch": circ(0, 0, .85, True); d.ellipse([P(-.85, -.85), P(.85, .85)], outline="#2F6FE0", width=w)   # a filled dot in the colour you asked for
    else: box(-.8, -.8, .8, .8)   # unknown icon: an empty box, so a typo is visible
    return im
