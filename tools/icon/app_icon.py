#!/usr/bin/env python3
"""Draws SatMap's app icon (SatMap/Assets.xcassets/AppIcon.appiconset/AppIcon.png).

The white quadcopter of proposal 1 ("drone over the bush") hovering over the bush and sandy gravel road of proposal 5
("bush road"), seen from above. No text. Drawn at 4096 px, saved at 1024 px.

    python3 tools/icon/app_icon.py <output.png>
"""
import math
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

S = 4096
OUT = 1024
OUT_PATH = sys.argv[1] if len(sys.argv) > 1 else "AppIcon.png"

rng = random.Random(7)
random.seed(20261027)


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(int(round(lerp(a, b, t))) for a, b in zip(c1, c2))


# ---------------------------------------------------------------- palette
GROUND_TOP = hexrgb("#1f3418")
GROUND_BOT = hexrgb("#0f1c0c")
# (dark, light) pairs per tree: muted savanna greens
TREE_COLS = [(hexrgb(a), hexrgb(b)) for a, b in (
    ("#1a3317", "#41672d"), ("#1d3819", "#4a7333"), ("#18301a", "#3c6230"),
    ("#213c1b", "#527838"), ("#1b3518", "#456c30"))]
ROAD_MID = hexrgb("#e2bb82")
ROAD_EDGE = hexrgb("#b9864f")
WHITE = (255, 255, 255)
ACCENT = hexrgb("#FFD60A")


# ---------------------------------------------------------------- road path
def bezier(p0, p1, p2, p3, t):
    u = 1 - t
    x = u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0]
    y = u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]
    return x, y


P0 = (0.74 * S, 1.12 * S)
P1 = (1.10 * S, 0.60 * S)
P2 = (-0.10 * S, 0.42 * S)
P3 = (0.30 * S, -0.12 * S)

N = 3000
road_pts = [bezier(P0, P1, P2, P3, i / (N - 1)) for i in range(N)]
ROAD_W = 0.165 * S


def normals(pts):
    out = []
    for i in range(len(pts)):
        a = pts[max(0, i - 1)]
        b = pts[min(len(pts) - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = math.hypot(dx, dy) or 1
        out.append((-dy / L, dx / L))
    return out


road_nrm = normals(road_pts)


def stroke_mask(pts, radius, size=S):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    for x, y in pts:
        d.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
    return m


def offset_pts(pts, nrm, off):
    return [(x + nx * off, y + ny * off) for (x, y), (nx, ny) in zip(pts, nrm)]


coarse = road_pts[::10]


def dist_to_road(x, y):
    best = 1e18
    for px, py in coarse:
        d = (px - x) ** 2 + (py - y) ** 2
        if d < best:
            best = d
    return math.sqrt(best)


# ---------------------------------------------------------------- ground
def vertical_gradient(c_top, c_bot, size=S):
    g = Image.new("RGB", (1, 256))
    for i in range(256):
        g.putpixel((0, i), mix(c_top, c_bot, i / 255))
    return g.resize((size, size), Image.BICUBIC)


canvas = vertical_gradient(GROUND_TOP, GROUND_BOT)

# soft noise texture on ground (generated small, upscaled)
def noise_layer(n, size, amp, seed):
    r = random.Random(seed)
    im = Image.new("L", (n, n))
    im.putdata([int(128 + r.uniform(-amp, amp)) for _ in range(n * n)])
    return im.resize((size, size), Image.BICUBIC)


# ---------------------------------------------------------------- trees
trees = []
cell = 0.098 * S
y = -0.06 * S
row = 0
while y < 1.10 * S:
    x = -0.06 * S + (cell * 0.5 if row % 2 else 0)
    while x < 1.10 * S:
        cx = x + rng.uniform(-0.35, 0.35) * cell
        cy = y + rng.uniform(-0.35, 0.35) * cell
        r = rng.uniform(0.056, 0.080) * S
        dr = dist_to_road(cx, cy)
        if dr > ROAD_W / 2 + r * 0.80:
            trees.append((cx, cy, r))
        x += cell
    y += cell * 0.87
    row += 1

trees.sort(key=lambda t: t[1])

# one radial gradient, reused for every canopy (light spot at upper-left)
G = 512
grad = Image.new("L", (G, G), 0)
gdr = ImageDraw.Draw(grad)
for i in range(60, 0, -1):
    t = i / 60
    rr = G * 0.62 * t
    cx0, cy0 = G * 0.40, G * 0.36
    gdr.ellipse((cx0 - rr, cy0 - rr, cx0 + rr, cy0 + rr), fill=int(255 * (1 - t) ** 0.8))
grad = grad.filter(ImageFilter.GaussianBlur(10))

# canopy shadow layer
shadow = Image.new("L", (S, S), 0)
sd = ImageDraw.Draw(shadow)
for cx, cy, r in trees:
    ox, oy = r * 0.32, r * 0.40
    sd.ellipse((cx - r + ox, cy - r + oy, cx + r + ox, cy + r + oy), fill=230)
shadow = shadow.filter(ImageFilter.GaussianBlur(S * 0.010))

canopy = Image.new("RGBA", (S, S), (0, 0, 0, 0))
for cx, cy, r in trees:
    dark, lite = rng.choice(TREE_COLS)
    box = int(r * 2.6)
    m = Image.new("L", (box, box), 0)
    md = ImageDraw.Draw(m)
    c = box / 2
    md.ellipse((c - r * 0.78, c - r * 0.78, c + r * 0.78, c + r * 0.78), fill=255)
    for k in range(7):
        a = k / 7 * math.tau + rng.uniform(-0.3, 0.3)
        rr = r * rng.uniform(0.42, 0.55)
        ox, oy = math.cos(a) * r * 0.55, math.sin(a) * r * 0.55
        md.ellipse((c + ox - rr, c + oy - rr, c + ox + rr, c + oy + rr), fill=255)
    m = m.filter(ImageFilter.GaussianBlur(r * 0.025))
    g = grad.resize((box, box), Image.BICUBIC)
    tree = Image.composite(Image.new("RGB", (box, box), lite),
                           Image.new("RGB", (box, box), dark), g).convert("RGBA")
    tree.putalpha(m)
    canopy.alpha_composite(tree, (int(cx - c), int(cy - c)))

# ---------------------------------------------------------------- road
road_mask = stroke_mask(road_pts[::2], ROAD_W / 2)
road_mask = road_mask.filter(ImageFilter.GaussianBlur(S * 0.0025))

# colour across road: edge -> mid -> edge, via blurred inner mask
inner = stroke_mask(road_pts[::2], ROAD_W * 0.30).filter(ImageFilter.GaussianBlur(S * 0.02))
road_rgb = Image.composite(Image.new("RGB", (S, S), ROAD_MID),
                           Image.new("RGB", (S, S), ROAD_EDGE), inner)

# wheel ruts: two slightly darker soft lines
ruts = Image.new("L", (S, S), 0)
for off in (-ROAD_W * 0.20, ROAD_W * 0.20):
    rp = offset_pts(road_pts[::3], road_nrm[::3], off)
    m = stroke_mask(rp, ROAD_W * 0.045)
    ruts = ImageChops.lighter(ruts, m)
ruts = ruts.filter(ImageFilter.GaussianBlur(S * 0.006))
rut_col = Image.new("RGB", (S, S), hexrgb("#c49460"))
road_rgb = Image.composite(rut_col, road_rgb, ruts.point(lambda v: int(v * 0.55)))

# gravel speckle
gravel = Image.new("L", (S, S), 0)
gd = ImageDraw.Draw(gravel)
for _ in range(9000):
    i = rng.randrange(len(road_pts))
    px, py = road_pts[i]
    nx, ny = road_nrm[i]
    o = rng.uniform(-0.5, 0.5) * ROAD_W
    gx, gy = px + nx * o, py + ny * o
    gr = rng.uniform(3, 9)
    gd.ellipse((gx - gr, gy - gr, gx + gr, gy + gr), fill=rng.randint(60, 140))
road_rgb = Image.composite(Image.new("RGB", (S, S), hexrgb("#8f6a42")), road_rgb, gravel)
light = Image.new("L", (S, S), 0)
ld = ImageDraw.Draw(light)
for _ in range(6000):
    i = rng.randrange(len(road_pts))
    px, py = road_pts[i]
    nx, ny = road_nrm[i]
    o = rng.uniform(-0.45, 0.45) * ROAD_W
    gx, gy = px + nx * o, py + ny * o
    gr = rng.uniform(3, 8)
    ld.ellipse((gx - gr, gy - gr, gx + gr, gy + gr), fill=rng.randint(60, 140))
road_rgb = Image.composite(Image.new("RGB", (S, S), hexrgb("#f4dcb0")), road_rgb, light)

# ---------------------------------------------------------------- compose landscape
# ground noise
gn = noise_layer(256, S, 18, 3)
canvas = ImageChops.overlay(canvas, Image.merge("RGB", (gn, gn, gn)))

# road (under tree shadows so canopy overhang shades it)
canvas = Image.composite(road_rgb, canvas, road_mask)

# tree shadows
canvas = Image.composite(Image.new("RGB", (S, S), hexrgb("#08120a")), canvas,
                         shadow.point(lambda v: int(v * 0.75)))
canvas = canvas.convert("RGBA")
canvas.alpha_composite(canopy)

# ---------------------------------------------------------------- drone
CX, CY = S / 2, S / 2 - 40
OFF = 850          # rotor centre offset along x and y
R = 556            # rotor guard outer radius
RIM = 128          # guard ring thickness
ROTORS = [(CX - OFF, CY - OFF), (CX + OFF, CY - OFF),
          (CX - OFF, CY + OFF), (CX + OFF, CY + OFF)]
BW, BH = 860, 1320  # body


def arm_poly(rx, ry, w_body, w_tip):
    ux, uy = rx - CX, ry - CY
    L = math.hypot(ux, uy)
    ux, uy = ux / L, uy / L
    nx, ny = -uy, ux
    return [(CX + nx * w_body / 2, CY + ny * w_body / 2),
            (rx + nx * w_tip / 2, ry + ny * w_tip / 2),
            (rx - nx * w_tip / 2, ry - ny * w_tip / 2),
            (CX - nx * w_body / 2, CY - ny * w_body / 2)]


def drone_layers():
    solid = Image.new("L", (S, S), 0)
    sd = ImageDraw.Draw(solid)
    disc = Image.new("L", (S, S), 0)
    dd = ImageDraw.Draw(disc)

    for rx, ry in ROTORS:
        sd.polygon(arm_poly(rx, ry, 330, 190), fill=255)
    for rx, ry in ROTORS:
        sd.ellipse((rx - R, ry - R, rx + R, ry + R), fill=255)
        ri = R - RIM
        sd.ellipse((rx - ri, ry - ri, rx + ri, ry + ri), fill=0)
        dd.ellipse((rx - ri, ry - ri, rx + ri, ry + ri), fill=255)
    for rx, ry in ROTORS:
        # inner arm segment to motor + motor bell
        ux, uy = CX - rx, CY - ry
        L = math.hypot(ux, uy)
        ux, uy = ux / L, uy / L
        ex, ey = rx + ux * (R - RIM / 2), ry + uy * (R - RIM / 2)
        nx, ny = -uy, ux
        w = 170
        sd.polygon([(rx + nx * w / 2, ry + ny * w / 2), (ex + nx * w / 2, ey + ny * w / 2),
                    (ex - nx * w / 2, ey - ny * w / 2), (rx - nx * w / 2, ry - ny * w / 2)], fill=255)
        h = 150
        sd.ellipse((rx - h, ry - h, rx + h, ry + h), fill=255)

    # body: rounded hull
    sd.rounded_rectangle((CX - BW / 2, CY - BH / 2, CX + BW / 2, CY + BH / 2), radius=400, fill=255)

    # details drawn on top of the shaded white
    det = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dt = ImageDraw.Draw(det)
    # prop blur: two soft blade sweeps per rotor
    ri = R - RIM - 6
    for i, (rx, ry) in enumerate(ROTORS):
        a0 = [200, 250, 160, 290][i]
        for k in range(2):
            a = a0 + k * 180
            for j in range(10):  # fading trail
                dt.pieslice((rx - ri, ry - ri, rx + ri, ry + ri), a + j * 4, a + j * 4 + 5,
                            fill=(255, 255, 255, int(20 + 9 * j)))
    # motor caps
    for rx, ry in ROTORS:
        dt.ellipse((rx - 62, ry - 62, rx + 62, ry + 62), fill=(178, 186, 198, 255))
        dt.ellipse((rx - 26, ry - 26, rx + 26, ry + 26), fill=(120, 128, 140, 255))
    # top shell: crisp inset cover, a touch cooler than the frame
    m = 150
    dt.rounded_rectangle((CX - BW / 2 + m, CY - BH / 2 + 300, CX + BW / 2 - m, CY + BH / 2 - 330),
                         radius=BW / 2 - m, fill=(234, 238, 244, 255))
    dt.rounded_rectangle((CX - BW / 2 + m, CY - BH / 2 + 300, CX + BW / 2 - m, CY + BH / 2 - 330),
                         radius=BW / 2 - m, outline=(250, 251, 253, 255), width=14)
    # yellow tail status light (small pill at the rear)
    ty = CY + BH / 2 - 170
    gl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gl)
    gd.rounded_rectangle((CX - 210, ty - 90, CX + 210, ty + 90), radius=90, fill=(255, 214, 10, 120))
    gl = gl.filter(ImageFilter.GaussianBlur(40))
    det = Image.alpha_composite(det, gl)
    dt = ImageDraw.Draw(det)
    dt.rounded_rectangle((CX - 150, ty - 48, CX + 150, ty + 48), radius=48, fill=(255, 214, 10, 255))
    # front gimbal camera, mostly outside the hull so it reads as a camera not an eye
    gy = CY - BH / 2 + 30
    dt.rounded_rectangle((CX - 190, gy - 150, CX + 190, gy + 110), radius=110, fill=(34, 38, 46, 255))
    dt.ellipse((CX - 80, gy - 110, CX + 80, gy + 50), fill=(58, 76, 100, 255))
    dt.ellipse((CX - 50, gy - 92, CX - 8, gy - 50), fill=(190, 210, 235, 210))
    return solid, disc, det


def shade_white(mask):
    """Near-white with top-left light, soft bevel darkening near edges, crisp outline."""
    grad = Image.linear_gradient("L").rotate(45, resample=Image.BICUBIC)
    grad = grad.resize((S, S), Image.BICUBIC)
    lit = grad.point(lambda v: int(255 - v * 40 / 255))
    base = Image.merge("RGB", [lit, lit, lit.point(lambda v: min(255, v + 3))])
    inner = mask.filter(ImageFilter.GaussianBlur(40))
    edge = ImageChops.subtract(mask, inner).point(lambda v: int(min(255, v * 1.6) * 0.45))
    base = Image.composite(Image.new("RGB", (S, S), (150, 158, 170)), base, edge)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    # thin dark contour so it stays crisp over pale ground
    ring = ImageChops.subtract(mask.filter(ImageFilter.MaxFilter(15)), mask)
    outline = Image.new("RGBA", (S, S), (20, 26, 18, 0))
    outline.putalpha(ring.point(lambda v: int(v * 0.35)))
    return Image.alpha_composite(outline, out)


# ---------------------------------------------------------------- the drone over the bush road
solid, disc, det = drone_layers()

# shadow on the bush (hovering low: offset down-right, soft)
sh_alpha = ImageChops.lighter(solid, disc.point(lambda v: v // 4))
sh = sh_alpha.filter(ImageFilter.GaussianBlur(46))
sh = ImageChops.offset(sh, 120, 170).point(lambda v: int(v * 0.62))
drone_shadow = Image.new("RGBA", (S, S), (8, 12, 6, 0))
drone_shadow.putalpha(sh)
canvas = Image.alpha_composite(canvas, drone_shadow)

# translucent prop discs
disc_layer = Image.new("RGBA", (S, S), (240, 244, 248, 0))
disc_layer.putalpha(disc.point(lambda v: int(v * 0.18)))
canvas = Image.alpha_composite(canvas, disc_layer)

canvas = Image.alpha_composite(canvas, shade_white(solid))
canvas = Image.alpha_composite(canvas, det)

# ---------------------------------------------------------------- finishing
# light from top-left, vignette to bottom-right
vig = Image.new("L", (S, S), 0)
vd = ImageDraw.Draw(vig)
vd.ellipse((-0.25 * S, -0.25 * S, 1.25 * S, 1.25 * S), fill=255)
vig = vig.filter(ImageFilter.GaussianBlur(S * 0.12))
canvas = Image.composite(canvas, Image.alpha_composite(canvas, Image.new("RGBA", (S, S), (0, 0, 0, 110))), vig)

final = canvas.convert("RGB").resize((OUT, OUT), Image.LANCZOS)
final.save(OUT_PATH, optimize=True)
print("wrote", OUT_PATH)
