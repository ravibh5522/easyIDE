"""Small drawing and maths helpers for the tour video (PIL only, no numpy)."""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONTS = ROOT / "services/mobile/app/src/main/res/font"

W, H, FPS = 1920, 1080, 30

AMBER = (255, 138, 61)
TEXT = (246, 239, 231)
MUTED = (178, 166, 154)
INK = (14, 11, 9)


def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size)


def clamp01(x):
    return max(0.0, min(1.0, x))


def lerp(a, b, t):
    return a + (b - a) * t


def ease_out(x):
    return 1 - (1 - clamp01(x)) ** 3


def ease_in(x):
    return clamp01(x) ** 3


def ease_io(x):
    x = clamp01(x)
    return 4 * x ** 3 if x < .5 else 1 - (-2 * x + 2) ** 3 / 2


def ease_back(x):
    """Overshoots then settles: the 'pop' used on titles and callouts."""
    x = clamp01(x)
    c1, c3 = 1.70158, 2.70158
    return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2


def keyed(keys, t):
    """Interpolate keyframes [(t, v1, v2, ...)] with ease-in-out between neighbours."""
    if t <= keys[0][0]:
        return tuple(keys[0][1:])
    for (t0, *a), (t1, *b) in zip(keys, keys[1:]):
        if t <= t1:
            k = ease_io((t - t0) / (t1 - t0))
            return tuple(lerp(x, y, k) for x, y in zip(a, b))
    return tuple(keys[-1][1:])


def remap(points, t):
    """Piecewise-linear time remap [(local, source), ...]; clamps outside the range."""
    if t <= points[0][0]:
        return points[0][1]
    for (t0, s0), (t1, s1) in zip(points, points[1:]):
        if t <= t1:
            return lerp(s0, s1, (t - t0) / (t1 - t0))
    return points[-1][1]


def pulse(t, period, decay=6.0):
    """1.0 on the beat, decaying to 0 before the next one."""
    return math.exp(-decay * ((t % period) / period))


def rounded_mask(size, radius):
    big = Image.new("L", (size[0] * 4, size[1] * 4), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius * 4, fill=255)
    return big.resize(size, Image.LANCZOS)


def solve_perspective(dst, src):
    """PIL PERSPECTIVE coefficients that map output pixels (dst quad) back to the source quad."""
    rows, rhs = [], []
    for (x, y), (u, v) in zip(dst, src):
        rows.append([x, y, 1, 0, 0, 0, -x * u, -y * u])
        rhs.append(u)
        rows.append([0, 0, 0, x, y, 1, -x * v, -y * v])
        rhs.append(v)
    n = 8
    for i in range(n):  # gaussian elimination with partial pivoting
        p = max(range(i, n), key=lambda r: abs(rows[r][i]))
        rows[i], rows[p], rhs[i], rhs[p] = rows[p], rows[i], rhs[p], rhs[i]
        for r in range(i + 1, n):
            f = rows[r][i] / rows[i][i]
            rows[r] = [a - f * b for a, b in zip(rows[r], rows[i])]
            rhs[r] -= f * rhs[i]
    c = [0.0] * n
    for i in reversed(range(n)):
        c[i] = (rhs[i] - sum(rows[i][j] * c[j] for j in range(i + 1, n))) / rows[i][i]
    return c


class Pose:
    """Yaw/pitch/roll of a flat card seen through a pinhole camera."""

    FOCAL = 2200.0

    def __init__(self, yaw=0.0, pitch=0.0, roll=0.0, scale=1.0, cx=W / 2, cy=H / 2):
        self.yaw, self.pitch, self.roll = map(math.radians, (yaw, pitch, roll))
        self.scale, self.cx, self.cy = scale, cx, cy

    def project(self, px, py):
        x, y, z = px * self.scale, py * self.scale, 0.0
        x, y = x * math.cos(self.roll) - y * math.sin(self.roll), x * math.sin(self.roll) + y * math.cos(self.roll)
        x, z = x * math.cos(self.yaw) + z * math.sin(self.yaw), -x * math.sin(self.yaw) + z * math.cos(self.yaw)
        y, z = y * math.cos(self.pitch) - z * math.sin(self.pitch), y * math.sin(self.pitch) + z * math.cos(self.pitch)
        k = self.FOCAL / (self.FOCAL + z)
        return self.cx + x * k, self.cy + y * k

    def warp(self, card):
        """Return (tile, (left, top)) of `card` seen with this pose."""
        w, h = card.size
        corners = [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]
        dst = [self.project(*c) for c in corners]
        left, top = int(min(p[0] for p in dst)) - 2, int(min(p[1] for p in dst)) - 2
        right, bottom = int(max(p[0] for p in dst)) + 3, int(max(p[1] for p in dst)) + 3
        local = [(x - left, y - top) for x, y in dst]
        coeffs = solve_perspective(local, [(0, 0), (w, 0), (w, h), (0, h)])
        tile = card.transform((right - left, bottom - top), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
        return tile, (left, top)


def text_tile(text, fnt, fill, alpha=1.0, scale=1.0, spacing=0):
    """Render text onto its own transparent tile, optionally scaled (for pops)."""
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    box = probe.textbbox((0, 0), text, font=fnt, anchor="ls")
    pad = 8
    tile = Image.new("RGBA", (box[2] - box[0] + 2 * pad, box[3] - box[1] + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(tile).text((pad - box[0], pad - box[1]), text, font=fnt, fill=fill + (int(255 * clamp01(alpha)),), anchor="ls")
    if scale != 1.0 and scale > 0:
        tile = tile.resize((max(1, int(tile.width * scale)), max(1, int(tile.height * scale))), Image.BICUBIC)
    return tile


def paste_center(layer, tile, cx, cy):
    layer.alpha_composite(tile, (int(cx - tile.width / 2), int(cy - tile.height / 2)))


def glow_blob(size, color, radius):
    blob = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(blob).ellipse((size[0] * .2, size[1] * .2, size[0] * .8, size[1] * .8), fill=color)
    return blob.filter(ImageFilter.GaussianBlur(radius))


def make_grain(seed=7):
    rnd = random.Random(seed)
    tile = Image.new("L", (W // 2, H // 2))
    tile.putdata([rnd.randint(124, 132) for _ in range(tile.width * tile.height)])
    return tile.resize((W, H), Image.NEAREST)


def make_vignette():
    v = Image.new("L", (W // 8, H // 8), 0)
    ImageDraw.Draw(v).ellipse((-v.width * .25, -v.height * .3, v.width * 1.25, v.height * 1.3), fill=255)
    v = v.filter(ImageFilter.GaussianBlur(14)).resize((W, H), Image.BICUBIC)
    return v.point(lambda p: int(255 - (255 - p) * 0.5))
