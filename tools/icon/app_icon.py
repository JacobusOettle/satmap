"""Draws SatMap's app icon (SatMap/Assets.xcassets/AppIcon.appiconset/AppIcon.png).

Satellite-style farm patchwork seen from above, two white field-block
outlines, a cyan dotted track weaving between them and ending at the blue
location dot. No contour lines, no text.

    python3 tools/icon/app_icon.py <output folder>   # writes blocks-and-track.png (1024 px) and small previews
"""
import math
import os
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

S = 4096
OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else "."
SLUG = "blocks-and-track"

random.seed(20261027)

ROT = math.radians(-14)
CX = CY = S / 2.0
COS, SIN = math.cos(ROT), math.sin(ROT)


def to_canvas(u, v):
    return (CX + u * COS - v * SIN, CY + u * SIN + v * COS)


def to_field(x, y):
    dx, dy = x - CX, y - CY
    return (dx * COS + dy * SIN, -dx * SIN + dy * COS)


def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def jitter_col(c, amt):
    return tuple(max(0, min(255, int(ch + random.uniform(-amt, amt)))) for ch in c)


# --------------------------------------------------------------------------
# 1. Field patchwork (in rotated "field space")
# --------------------------------------------------------------------------
ROAD = hex2rgb("#93825e")
FIELD_TYPES = {
    "orchard": dict(ground=hex2rgb("#5b6336"), tree=hex2rgb("#41582a")),
    "pasture": dict(ground=hex2rgb("#4d6a2b")),
    "bush": dict(ground=hex2rgb("#6c6542"), tree=hex2rgb("#4b5530")),
    "soil": dict(ground=hex2rgb("#6e6148")),
    "dry": dict(ground=hex2rgb("#7f7649")),
}

cols = [-3500, -2300, -1580, -250, 1250, 2550, 3500]
fixed_rows = {
    2: [-650, 600],    # column holding block A
    3: [450, 1550],    # column holding block B
}
cells = []  # (u0, v0, u1, v1, type)
type_cycle = ["orchard", "pasture", "soil", "orchard", "dry", "bush", "pasture", "orchard"]
ti = 0
for ci in range(len(cols) - 1):
    u0, u1 = cols[ci], cols[ci + 1]
    bounds = []
    if ci in fixed_rows:
        a, b = fixed_rows[ci]
        v = a
        while v > -3500:
            bounds.insert(0, v)
            v -= random.uniform(1050, 1500)
        bounds.insert(0, v)
        bounds.append(b)
        v = b
        while v < 3500:
            v += random.uniform(1050, 1500)
            bounds.append(v)
    else:
        v = -3500 + random.uniform(-600, 0)
        bounds.append(v)
        while v < 3500:
            v += random.uniform(1050, 1600)
            bounds.append(v)
    for va, vb in zip(bounds, bounds[1:]):
        cells.append([u0, va, u1, vb, type_cycle[ti % len(type_cycle)]])
        ti += 3

base = Image.new("RGB", (S, S), ROAD)
d = ImageDraw.Draw(base)
INSET = 22  # half road width

for c in cells:
    u0, v0, u1, v1, t = c
    spec = FIELD_TYPES[t]
    g = jitter_col(spec["ground"], 10)
    poly = [to_canvas(u0 + INSET, v0 + INSET), to_canvas(u1 - INSET, v0 + INSET),
            to_canvas(u1 - INSET, v1 - INSET), to_canvas(u0 + INSET, v1 - INSET)]
    d.polygon(poly, fill=g)
    if t == "orchard":
        sp = 64
        r = 21
        u = u0 + INSET + sp * 0.7
        while u < u1 - INSET - sp * 0.4:
            v = v0 + INSET + sp * 0.7
            while v < v1 - INSET - sp * 0.4:
                x, y = to_canvas(u + random.uniform(-6, 6), v + random.uniform(-6, 6))
                rr = r * random.uniform(0.8, 1.15)
                d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=jitter_col(spec["tree"], 8))
                v += sp
            u += sp
    elif t == "bush":
        n = int((u1 - u0) * (v1 - v0) / 5000)
        for _ in range(n):
            u = random.uniform(u0 + INSET + 40, u1 - INSET - 40)
            v = random.uniform(v0 + INSET + 40, v1 - INSET - 40)
            x, y = to_canvas(u, v)
            rr = random.uniform(10, 28)
            d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=jitter_col(spec["tree"], 10))

base = base.filter(ImageFilter.GaussianBlur(4))


def noise_layer(n, size_up, blur=0):
    img = Image.frombytes("L", (n, n), random.randbytes(n * n))
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    return img.resize((size_up, size_up), Image.BICUBIC)


# low frequency mottling + fine grain
low = noise_layer(24, S, blur=1.2)
low = low.point(lambda p: int(max(0, min(255, 128 + (p - 128) * 3.0))))
fine = noise_layer(1024, S)
fine = fine.point(lambda p: int(128 + (p - 128) * 0.18))


def apply_mod(img, mod, strength):
    # multiply-ish modulation around mid-grey: factor = 1 + strength*(m-128)/128
    lut = [int(max(0, min(255, 128 + (i - 128) * strength))) for i in range(256)]
    m = mod.point(lut)
    m3 = Image.merge("RGB", (m, m, m))
    # overlay-ish: result = img * (m/128)
    out = ImageChops.multiply(img, m3)
    return ImageChops.add(out, out)  # *2 => img * m/128


base = apply_mod(base, low, 0.35)
base = apply_mod(base, fine, 1.0)

# grade: slightly darker + vignette so foreground pops
vig = Image.new("L", (S, S), 0)
vd = ImageDraw.Draw(vig)
vd.ellipse([-S * 0.25, -S * 0.25, S * 1.25, S * 1.25], fill=255)
vig = vig.filter(ImageFilter.GaussianBlur(S * 0.12))
dark = Image.new("RGB", (S, S), (14, 18, 10))
base = Image.composite(base, Image.blend(base, dark, 0.55), vig)
base = Image.blend(base, Image.new("RGB", (S, S), (20, 24, 16)), 0.12)

canvas = base.convert("RGBA")

# --------------------------------------------------------------------------
# 2. Field-block outlines
# --------------------------------------------------------------------------


def find_cell(x, y):
    u, v = to_field(x, y)
    for c in cells:
        if c[0] <= u < c[2] and c[1] <= v < c[3]:
            return c
    raise ValueError


def block_poly(c, cut_corner=None, cut=0.35, pad=110):
    u0, v0, u1, v1 = c[0] + pad, c[1] + pad, c[2] - pad, c[3] - pad
    pts = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
    if cut_corner is not None:
        i = cut_corner
        p = pts[i]
        prev = pts[i - 1]
        nxt = pts[(i + 1) % 4]
        a = (p[0] + (prev[0] - p[0]) * cut, p[1] + (prev[1] - p[1]) * cut)
        b = (p[0] + (nxt[0] - p[0]) * cut, p[1] + (nxt[1] - p[1]) * cut)
        pts = pts[:i] + [a, b] + pts[i + 1:]
    return [to_canvas(*q) for q in pts]


blockA = block_poly(find_cell(1150, 2250), cut_corner=2, cut=0.38)
blockB = block_poly(find_cell(2850, 2900), cut_corner=0, cut=0.38)
blocks = [blockA, blockB]

LINE_W = 96


def stroke_poly(draw, pts, width, fill):
    draw.line(pts + [pts[0]], fill=fill, width=width, joint="curve")
    r = width / 2
    for x, y in pts:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=fill)


# shadow + fill + stroke
sh = Image.new("L", (S, S), 0)
sd = ImageDraw.Draw(sh)
for b in blocks:
    stroke_poly(sd, [(x, y + 26) for x, y in b], LINE_W + 30, 150)
sh = sh.filter(ImageFilter.GaussianBlur(34))
canvas.paste((0, 0, 0, 255), (0, 0), sh)

fill = Image.new("RGBA", (S, S), (0, 0, 0, 0))
fd = ImageDraw.Draw(fill)
for b in blocks:
    fd.polygon(b, fill=(255, 255, 255, 34))
canvas = Image.alpha_composite(canvas, fill)

lines = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ld = ImageDraw.Draw(lines)
for b in blocks:
    stroke_poly(ld, b, LINE_W, (255, 255, 255, 255))
canvas = Image.alpha_composite(canvas, lines)

# --------------------------------------------------------------------------
# 3. Cyan dotted track (Catmull-Rom through waypoints)
# --------------------------------------------------------------------------
DOT_POS = (2760, 1340)
waypoints = [(-150, 3800), (700, 3560), (1400, 3260), (1800, 2830), (2010, 2450), (2240, 1950),
             (2470, 1560), DOT_POS]


def catmull(pts, steps=60):
    p = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for s in range(steps):
            t = s / steps
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                       + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                       + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    out.append(pts[-1])
    return out


curve = catmull(waypoints)
SPACING = 166
DOT_R = 50
dots = []
acc = 0.0
for (x0, y0), (x1, y1) in zip(curve, curve[1:]):
    seg = math.hypot(x1 - x0, y1 - y0)
    t = 0.0
    while acc + (seg - t) >= SPACING:
        t += SPACING - acc
        acc = 0.0
        f = t / seg
        dots.append((x0 + (x1 - x0) * f, y0 + (y1 - y0) * f))
    acc += seg - t

TRACK = (64, 224, 255, 255)
tsh = Image.new("L", (S, S), 0)
tsd = ImageDraw.Draw(tsh)
for x, y in dots:
    r = DOT_R + 14
    tsd.ellipse([x - r, y - r + 14, x + r, y + r + 14], fill=170)
tsh = tsh.filter(ImageFilter.GaussianBlur(16))
canvas.paste((0, 0, 0, 255), (0, 0), tsh)

tl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
tld = ImageDraw.Draw(tl)
for x, y in dots:
    tld.ellipse([x - DOT_R, y - DOT_R, x + DOT_R, y + DOT_R], fill=TRACK)
canvas = Image.alpha_composite(canvas, tl)

# --------------------------------------------------------------------------
# 4. Location dot
# --------------------------------------------------------------------------
x, y = DOT_POS
R_OUT, R_IN, R_HALO = 470, 340, 900

halo = Image.new("RGBA", (S, S), (0, 0, 0, 0))
hd = ImageDraw.Draw(halo)
# (halo removed: muddied the background)
canvas = Image.alpha_composite(canvas, halo)

shadow = Image.new("L", (S, S), 0)
shd = ImageDraw.Draw(shadow)
shd.ellipse([x - R_OUT, y - R_OUT + 60, x + R_OUT, y + R_OUT + 60], fill=190)
shadow = shadow.filter(ImageFilter.GaussianBlur(80))
canvas.paste((0, 0, 0, 255), (0, 0), shadow)

dl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
dd = ImageDraw.Draw(dl)
dd.ellipse([x - R_OUT, y - R_OUT, x + R_OUT, y + R_OUT], fill=(255, 255, 255, 255))
dd.ellipse([x - R_IN, y - R_IN, x + R_IN, y + R_IN], fill=(10, 132, 255, 255))
canvas = Image.alpha_composite(canvas, dl)

# soft inner shading on the blue disc: light from the top-left, a touch deeper bottom-right
disc = Image.new("L", (S, S), 0)
ImageDraw.Draw(disc).ellipse([x - R_IN, y - R_IN, x + R_IN, y + R_IN], fill=255)
hi = Image.new("L", (S, S), 0)
hr = R_IN * 0.9
hx, hy = x - R_IN * 0.38, y - R_IN * 0.42
ImageDraw.Draw(hi).ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=70)
hi = hi.filter(ImageFilter.GaussianBlur(R_IN * 0.35))
hi = ImageChops.multiply(hi, disc)
canvas.paste((120, 190, 255, 255), (0, 0), hi)
lo = Image.new("L", (S, S), 0)
lx, ly = x + R_IN * 0.55, y + R_IN * 0.6
ImageDraw.Draw(lo).ellipse([lx - hr, ly - hr, lx + hr, ly + hr], fill=60)
lo = lo.filter(ImageFilter.GaussianBlur(R_IN * 0.4))
lo = ImageChops.multiply(lo, disc)
canvas.paste((0, 70, 190, 255), (0, 0), lo)

final = canvas.convert("RGB").resize((1024, 1024), Image.LANCZOS)
os.makedirs(OUT_DIR, exist_ok=True)
final.save(os.path.join(OUT_DIR, SLUG + ".png"))
final.resize((180, 180), Image.LANCZOS).save(os.path.join(OUT_DIR, SLUG + "_180.png"))
final.resize((60, 60), Image.LANCZOS).save(os.path.join(OUT_DIR, SLUG + "_60.png"))
print("ok")
