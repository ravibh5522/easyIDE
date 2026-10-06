#!/usr/bin/env python3
"""Render the README tour film: python3 tools/make-tour-video.py [--still SECONDS]

Every frame is drawn with PIL and piped to ffmpeg. The storyboard (what happens when) is
tools/tour/scenes.py; this file only knows how to draw it. Inputs:

  docs/media/screenshots/   still captures
  docs/media/source/*.mp4   screen recordings made with `adb shell screenrecord`
  docs/media/source/score.m4a   optional soundtrack, see tools/make-tour-audio.py

Output: docs/media/easyide-tour.mp4 and docs/media/easyide-tour-poster.png.
"""
import math
import subprocess
import sys
from multiprocessing import Pool
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
from tour import scenes as S  # noqa: E402
from tour.fx import (AMBER, FPS, H, INK, MUTED, ROOT, TEXT, W, Pose, clamp01, ease_back, ease_in,  # noqa: E402
                     ease_io, ease_out, font, glow_blob, keyed, lerp, make_grain, make_vignette, paste_center,
                     pulse, remap, rounded_mask, text_tile)

SHOTS = ROOT / "docs/media/screenshots"
SOURCE = ROOT / "docs/media/source"
CACHE = Path("/tmp/easyide-tour-cache")
OUT = ROOT / "docs/media/easyide-tour.mp4"
POSTER = ROOT / "docs/media/easyide-tour-poster.png"
AUDIO = SOURCE / "score.m4a"
ICON = ROOT / "docs/media/icon-512.png"

SCREEN_W, SCREEN_H, PAD, BEZEL_R, SCREEN_R = 1200, 750, 22, 38, 20
BEZEL = (8, 7, 6)
BG_TOP, BG_BOTTOM = (24, 19, 15), (10, 8, 7)

F_TITLE = font("geist_semibold.ttf", 70)
F_SUB = font("geist_regular.ttf", 32)
F_CHIP = font("geist_mono_regular.ttf", 24)
F_CALL = font("geist_medium.ttf", 28)
F_HERO = font("geist_semibold.ttf", 132)
F_BIG = font("geist_semibold.ttf", 96)
F_MONO = font("geist_mono_regular.ttf", 54)
F_MONO_S = font("geist_mono_regular.ttf", 34)
F_GHOST = font("geist_semibold.ttf", 360)
F_WORD = font("geist_semibold.ttf", 54)

LAYOUT = {"right": (1290, 520, .84, 110), "left": (640, 520, .84, 1260), "center": (960, 448, 1.0, 0)}


class Media:
    """Stills, cached video frames and the synthetic typing source."""

    def __init__(self):
        self.stills, self.frames = {}, {}
        self.typing_base = {}

    def shot(self, name):
        if name not in self.stills:
            self.stills[name] = Image.open(SHOTS / name).convert("RGB")
        return self.stills[name]

    def clip(self, key, t):
        folder = CACHE / key
        if not folder.exists():
            extract(key)
        count = len(list(folder.glob("*.jpg")))
        idx = max(0, min(count - 1, int(t * FPS))) + 1
        path = folder / f"{idx:05d}.jpg"
        if path not in self.frames:
            if len(self.frames) > 24:
                self.frames.pop(next(iter(self.frames)))
            self.frames[path] = Image.open(path).convert("RGB")
        return self.frames[path]

    def typing(self, name, t):
        """The code editor screenshot with its code typed in character by character."""
        final = self.shot(name)
        if name not in self.typing_base:
            blank = final.copy()
            bg = final.getpixel((1800, 700))
            ImageDraw.Draw(blank).rectangle(S.CODE_BOX, fill=bg)
            tx0, ty0, tx1, ty1 = S.TOAST_BOX
            blank.paste(final.crop((tx0, ty0 - 90, tx1, ty1 - 90)), (tx0, ty0))
            self.typing_base[name] = blank
        img = self.typing_base[name].copy()
        counts, row, col = typed_counts(t)
        gutter_w = S.CODE_TEXT_X - S.CODE_BOX[0]
        for i, n in enumerate(counts):
            if i > row:
                break
            y0 = int(S.CODE_ROW_Y + i * S.CODE_ROW_H)
            y1 = int(y0 + S.CODE_ROW_H)
            img.paste(final.crop((S.CODE_BOX[0], y0, S.CODE_TEXT_X, y1)), (S.CODE_BOX[0], y0))
            if n:
                x1 = int(S.CODE_TEXT_X + n * S.CODE_CHAR_W)
                img.paste(final.crop((S.CODE_TEXT_X, y0, x1, y1)), (S.CODE_TEXT_X, y0))
        done = row >= len(S.CODE_LINE_CHARS) - 1 and counts[-1] >= S.CODE_LINE_CHARS[-1]
        if done:
            img.paste(final.crop(S.CODE_BOX), S.CODE_BOX[:2])
            img.paste(final.crop(S.TOAST_BOX), S.TOAST_BOX[:2]) if t > typing_end() + 0.5 else None
        if t >= 0 and (not done or int(t * 2) % 2 == 0):
            x = S.CODE_TEXT_X + col * S.CODE_CHAR_W
            y = S.CODE_ROW_Y + row * S.CODE_ROW_H
            ImageDraw.Draw(img).rectangle((x, y + 2, x + 3, y + 26), fill=AMBER)
        return img


def typing_end():
    return sum(S.CODE_LINE_CHARS) / S.TYPING_CPS + S.TYPING_GAP * len(S.CODE_LINE_CHARS)


def typed_counts(t):
    """Characters typed per code line after t seconds, plus the cursor row and column."""
    lines = S.CODE_LINE_CHARS
    counts, clock = [0] * len(lines), 0.0
    for i, n in enumerate(lines):
        if t < clock:
            return counts, i, 0
        span = n / S.TYPING_CPS
        if t < clock + span:
            counts[i] = int((t - clock) * S.TYPING_CPS)
            return counts, i, counts[i]
        counts[i] = n
        clock += span + S.TYPING_GAP
        if t < clock:
            return counts, min(i + 1, len(lines) - 1), 0
    return counts, len(lines) - 1, counts[-1]


def extract(key):
    folder = CACHE / key
    folder.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(SOURCE / f"{key}.mp4"), "-vf", "fps=30",
                    "-q:v", "3", str(folder / "%05d.jpg")], check=True)


class Renderer:
    def __init__(self):
        self.media = Media()
        base = Image.new("RGB", (W, H))
        px = ImageDraw.Draw(base)
        for y in range(H):
            px.line([(0, y), (W, y)], fill=tuple(int(lerp(a, b, y / H)) for a, b in zip(BG_TOP, BG_BOTTOM)))
        self.bg = base
        self.glow = Image.new("RGBA", (W + 600, H + 400), (0, 0, 0, 0))
        self.glow.alpha_composite(glow_blob((900, 800), AMBER + (80,), 150), (40, 20))
        self.glow.alpha_composite(glow_blob((900, 700), (255, 190, 120, 46), 150), (1100, 600))
        fw, fh = SCREEN_W + 2 * PAD, SCREEN_H + 2 * PAD
        self.bezel = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
        mask = rounded_mask((fw, fh), BEZEL_R)
        self.bezel.paste(Image.new("RGBA", (fw, fh), BEZEL + (255,)), (0, 0), mask)
        rim = ImageDraw.Draw(self.bezel)
        rim.rounded_rectangle((0, 0, fw - 1, fh - 1), BEZEL_R, outline=(66, 57, 50, 255), width=2)
        rim.ellipse((fw // 2 - 5, PAD // 2 - 5, fw // 2 + 5, PAD // 2 + 5), fill=(28, 25, 22, 255))
        self.screen_mask = rounded_mask((SCREEN_W, SCREEN_H), SCREEN_R)
        self.shadow = Image.new("RGBA", (fw + 300, fh + 300), (0, 0, 0, 0))
        ImageDraw.Draw(self.shadow).rounded_rectangle((150, 190, 150 + fw, 190 + fh), BEZEL_R, fill=(0, 0, 0, 190))
        self.shadow = self.shadow.filter(ImageFilter.GaussianBlur(50))
        self.vignette, self.grain = make_vignette(), make_grain()
        self.icon = Image.open(ICON).convert("RGBA")
        self.hits = [s["t0"] for s in S.SCENES[1:]] + [S.COLD_SLAM]
        for sc in S.SCENES:
            self.hits += [sc["t0"] + t for t, _ in sc.get("slams", [])]

    # ----- building blocks -------------------------------------------------------------
    def background(self, t):
        a = t * 0.3
        ox, oy = int(150 + 120 * math.sin(a)), int(120 + 90 * math.cos(a * .8))
        out = self.bg.copy().convert("RGBA")
        crop = self.glow.crop((ox, oy, ox + W, oy + H))
        k = 0.65 + 0.5 * pulse(t, S.BEAT, 5)
        out.alpha_composite(Image.merge("RGBA", (*crop.split()[:3], crop.getchannel("A").point(lambda v: int(min(255, v * k))))))
        return out

    def screen_view(self, src, cam):
        cx, cy, zoom = cam
        w, h = src.width / zoom, src.height / zoom
        x0 = min(max(cx * src.width - w / 2, 0), src.width - w)
        y0 = min(max(cy * src.height - h / 2, 0), src.height - h)
        view = src.resize((SCREEN_W, SCREEN_H), Image.BILINEAR, box=(x0, y0, x0 + w, y0 + h))
        return view, (x0, y0, w, h)

    def card(self, src, cam):
        view, box = self.screen_view(src, cam)
        card = self.bezel.copy()
        card.paste(view, (PAD, PAD), self.screen_mask)
        return card, box

    def place(self, layer, card, pose, shadow=True):
        tile, (left, top) = pose.warp(card)
        if shadow:
            sh = self.shadow.resize((int(self.shadow.width * pose.scale), int(self.shadow.height * pose.scale)))
            layer.alpha_composite(sh, (int(pose.cx - sh.width / 2 + 24), int(pose.cy - sh.height / 2 + 52)))
        layer.alpha_composite(tile, (left, top))

    def source(self, sc, lt):
        kind, *args = sc["src"]
        if kind == "shot":
            return self.media.shot(args[0])
        if kind == "clip":
            return self.media.clip(args[0], remap(args[1], lt))
        return self.media.typing(args[0], lt - sc["typing_at"])

    def callouts(self, layer, sc, lt, pose, box):
        x0, y0, w, h = box
        for c in sc.get("callouts", []):
            age = lt - c["t"]
            if age < 0:
                continue
            u, v = c["at"]
            sx, sy = (u - x0) / w * SCREEN_W, (v - y0) / h * SCREEN_H
            if not (0 <= sx <= SCREEN_W and 0 <= sy <= SCREEN_H):
                continue
            ax, ay = pose.project(sx - SCREEN_W / 2, sy - SCREEN_H / 2)
            px, py = ax + c["dx"], ay + c["dy"]
            d = ImageDraw.Draw(layer)
            grow = ease_out(age / 0.35)
            d.line([(ax, ay), (lerp(ax, px, grow), lerp(ay, py, grow))], fill=AMBER + (230,), width=3)
            ring = (age * 1.4) % 1.0
            r = 9 + 26 * ring
            d.ellipse((ax - r, ay - r, ax + r, ay + r), outline=AMBER + (int(200 * (1 - ring)),), width=3)
            d.ellipse((ax - 9, ay - 9, ax + 9, ay + 9), fill=AMBER + (255,))
            if age > 0.25:
                pop = ease_back((age - 0.25) / 0.4)
                tile = text_tile(c["text"], F_CALL, TEXT)
                pill = Image.new("RGBA", (tile.width + 40, 58), (0, 0, 0, 0))
                ImageDraw.Draw(pill).rounded_rectangle((0, 0, pill.width - 1, 57), 29, fill=INK + (232,), outline=AMBER + (255,), width=2)
                pill.alpha_composite(tile, (20, 14))
                pill = pill.resize((max(1, int(pill.width * pop)), max(1, int(pill.height * pop))), Image.BICUBIC)
                paste_center(layer, pill, px, py)

    def caption(self, layer, sc, lt, align_x, anchor):
        """Chapter chip, ghost number, title lines and sub-title with staggered pops."""
        chip = f"{sc['chapter'] + 1:02d} / {S.CHAPTERS[sc['chapter']]}"
        a = ease_out((lt - 0.1) / 0.5)
        layer.alpha_composite(text_tile(chip, F_CHIP, AMBER, a), (int(align_x - 8), 228) if anchor == "l" else (int(align_x - 100), 226))
        ghost = text_tile(f"{sc['chapter'] + 1:02d}", F_GHOST, TEXT, 0.05 * a)
        layer.alpha_composite(ghost, (int(align_x - 40) if anchor == "l" else int(align_x - 700), 160))
        lines = sc["title"].split("\n")
        y = 360
        for i, line in enumerate(lines):
            k = (lt - 0.2 - 0.14 * i) / 0.55
            pop = ease_back(k)
            tile = text_tile(line, F_TITLE, TEXT, ease_out(k), pop if pop > 0 else 0.01)
            layer.alpha_composite(tile, (int(align_x - 8), int(y + (1 - ease_out(k)) * 30 - tile.height * .5)))
            y += 82
        for j, line in enumerate(sc["sub"].split("\n")):
            k = ease_out((lt - 0.7 - 0.12 * j) / 0.6)
            layer.alpha_composite(text_tile(line, F_SUB, MUTED, k), (int(align_x - 8), int(y + 30 + j * 44 + (1 - k) * 16)))

    # ----- scenes ----------------------------------------------------------------------
    def tablet_scene(self, sc, lt):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        lt = max(0.0, lt)
        cx, cy, scale, text_x = LAYOUT[sc["layout"]]
        side = 1 if sc["layout"] == "right" else -1 if sc["layout"] == "left" else 0
        enter = ease_out(lt / 1.0)
        pose = Pose(yaw=sc["yaw"] - side * 30 * (1 - enter) + 2.2 * math.sin(lt * 1.2), pitch=2.0 * math.sin(lt * .9) + 4 * (1 - enter),
                    scale=scale * (0.9 + 0.1 * enter), cx=cx + side * 360 * (1 - enter), cy=cy + 7 * math.sin(lt * 1.6) + (1 - enter) * 60)
        cam = keyed(sc["cam"], lt) if "cam" in sc else (.5, .5, 1.0)
        card, box = self.card(self.source(sc, lt), cam)
        self.place(layer, card, pose)
        self.callouts(layer, sc, lt, pose, box)
        if sc["layout"] == "center":
            self.center_caption(layer, sc, lt)
            self.slams(layer, sc, lt, pose)
        else:
            self.caption(layer, sc, lt, text_x, "l")
        return layer

    def center_caption(self, layer, sc, lt):
        k = ease_out((lt - 0.2) / 0.5)
        layer.alpha_composite(text_tile(sc["title"], F_BIG, TEXT, k, ease_back((lt - .2) / .6) or .01), (80, 900 - 60 + int((1 - k) * 24)))
        if "slams" not in sc:
            layer.alpha_composite(text_tile(sc["sub"].replace("\n", " "), F_SUB, MUTED, k), (84, 990))

    def slams(self, layer, sc, lt, pose):
        words = sc["slams"]
        x = 1020
        for i, (t, word) in enumerate(words):
            age = lt - t
            active = age >= 0 and (i == len(words) - 1 or lt < words[i + 1][0])
            pop = ease_back(age / 0.45) if age >= 0 else 0
            tile = text_tile(word, F_WORD, AMBER if active else TEXT, 1.0 if age >= 0 else 0.22, max(0.05, 0.85 + 0.25 * pop if age >= 0 else 0.85))
            layer.alpha_composite(tile, (x, 905 - tile.height // 2 + 25))
            x += tile.width + 28
            if 0 <= age < 0.8:
                r = age / 0.8
                d = ImageDraw.Draw(layer)
                rr = 160 + 560 * ease_out(r)
                d.ellipse((pose.cx - rr, pose.cy - rr, pose.cx + rr, pose.cy + rr), outline=AMBER + (int(120 * (1 - r)),), width=3)

    def term_scene(self, sc, lt):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        lt = max(0.0, lt)
        frame = self.media.clip(sc["src"][1], remap(sc["src"][2], lt))
        crop = frame.crop(S.TERMINAL_CROP)
        pad = 34
        card = Image.new("RGBA", (crop.width + 2 * pad, crop.height + 2 * pad + 40), (0, 0, 0, 0))
        ImageDraw.Draw(card).rounded_rectangle((0, 0, card.width - 1, card.height - 1), 30, fill=(20, 16, 13, 255), outline=AMBER + (255,), width=3)
        for i, col in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
            ImageDraw.Draw(card).ellipse((pad + i * 34, 22, pad + i * 34 + 18, 40), fill=col)
        card.paste(crop, (pad, pad + 34))
        enter = ease_out(lt / 0.9)
        pose = Pose(yaw=sc["yaw"] + 24 * (1 - enter), pitch=3 * math.sin(lt) - 6 * (1 - enter), scale=lerp(1.2, 1.3, lt / sc["dur"]), cx=960, cy=400 + 8 * math.sin(lt * 1.5) + (1 - enter) * 120)
        glow = glow_blob((1800, 700), AMBER + (70,), 120)
        layer.alpha_composite(glow, (int(pose.cx - 900), int(pose.cy - 350)))
        self.place(layer, card, pose)
        k = ease_out((lt - 0.3) / 0.6)
        layer.alpha_composite(text_tile(sc["title"], F_BIG, TEXT, k), (int(W / 2 - 560), 770 + int((1 - k) * 30)))
        layer.alpha_composite(text_tile(sc["sub"], F_SUB, MUTED, ease_out((lt - .8) / .6)), (int(W / 2 - 560) + 4, 880))
        x = W / 2 - 560
        for i, chip in enumerate(["Ubuntu noble", "apt", "git", "python3", "offline"]):
            age = lt - (1.5 + 0.5 * i)
            if age < 0:
                continue
            tile = text_tile(chip, F_CHIP, TEXT)
            pill = Image.new("RGBA", (tile.width + 36, 48), (0, 0, 0, 0))
            ImageDraw.Draw(pill).rounded_rectangle((0, 0, pill.width - 1, 47), 24, fill=INK + (235,), outline=AMBER + (255,), width=2)
            pill.alpha_composite(tile, (18, 8))
            pop = ease_back(age / 0.4)
            pill = pill.resize((max(1, int(pill.width * pop)), max(1, int(pill.height * pop))), Image.BICUBIC)
            layer.alpha_composite(pill, (int(x), 962 - pill.height // 2))
            x += pill.width + 18
        layer.alpha_composite(text_tile(f"{sc['chapter'] + 1:02d} / {S.CHAPTERS[sc['chapter']]}", F_CHIP, AMBER, ease_out((lt - .1) / .5)), (int(W / 2 - 570), 700))
        return layer

    def cold_scene(self, lt):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        if lt < S.COLD_SLAM:
            y, last_end, cur_y = 360, 0.0, 360
            for start, text, tone in S.COLD_LINES:
                n = int(clamp01((lt - start) / (0.032 * len(text) + 0.01)) * len(text)) if lt >= start else 0
                if lt >= start:
                    shown = text[:n]
                    col = TEXT if tone == "text" else MUTED
                    d.text((230, y), "> ", font=F_MONO, fill=AMBER + (255,), anchor="ls")
                    d.text((230 + 66, y), shown, font=F_MONO, fill=col + (255,), anchor="ls")
                    cur_y, last_end = y, 230 + 66 + d.textlength(shown, font=F_MONO)
                    if text == S.COLD_LINES[-1][1] and lt > S.COLD_STRIKE:
                        k = ease_out((lt - S.COLD_STRIKE) / 0.25)
                        d.line([(296, y - 20), (lerp(296, 296 + d.textlength(text, font=F_MONO), k), y - 20)], fill=AMBER + (255,), width=6)
                y += 120
            blink = (int(lt * 2) % 2 == 0) or lt < 0.9
            if blink and lt < S.COLD_STRIKE + 0.3:
                bx = last_end + 10 if lt >= 0.9 else W / 2 - 19
                by = cur_y if lt >= 0.9 else 540
                d.rectangle((bx, by - 54, bx + 30, by + 4), fill=AMBER + (255,))
        else:
            age = lt - S.COLD_SLAM
            drop = ease_out(age / 0.35)
            size = int(lerp(760, 300, drop))
            icon = self.icon.resize((size, size), Image.LANCZOS)
            tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            tile.paste(icon, (0, 0), rounded_mask((size, size), size // 5))
            paste_center(layer, tile, W / 2, lerp(520, 330, drop))
            word = "easyIDE"
            x0 = W / 2 - d.textlength(word, font=F_HERO) / 2
            for i, ch in enumerate(word):
                k = (age - 0.35 - 0.06 * i) / 0.4
                if k > 0:
                    t = text_tile(ch, F_HERO, TEXT, ease_out(k), ease_back(k) or .01)
                    layer.alpha_composite(t, (int(x0 + d.textlength(word[:i], font=F_HERO) - 8), int(560 + (1 - ease_out(k)) * 50)))
            for j, line in enumerate(S.TAGLINE):
                k = ease_out((age - 1.0 - 0.25 * j) / 0.6)
                layer.alpha_composite(text_tile(line, F_SUB, MUTED if j == 0 else AMBER, k), (int(W / 2 - 250), 760 + j * 48))
            if age < 0.7:
                flash = Image.new("RGBA", (W, H), (255, 210, 160, int(200 * (1 - age / 0.7))))
                layer.alpha_composite(flash)
        return layer

    def outro_scene(self, sc, lt):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        lt = max(0.0, lt)
        decks = [("04-editor.png", 410, 26, .6, 0.0), ("06-extensions.png", 1510, -26, .6, 0.25), ("05-git-and-terminal.png", 960, 0, .76, 0.5)]
        for name, cx, yaw, scale, delay in sorted(decks, key=lambda d: d[3]):
            k = ease_out((lt - delay) / 0.9)
            pose = Pose(yaw=yaw * (0.4 + 0.6 * k), pitch=3 * math.sin(lt + cx), scale=scale, cx=cx, cy=345 + (1 - k) * 500 + 8 * math.sin(lt * 1.4 + cx))
            card, _ = self.card(self.media.shot(name), (.5, .5, 1.0))
            self.place(layer, card, pose)
        k = ease_out((lt - 0.8) / 0.6)
        layer.alpha_composite(text_tile("Code anywhere.", F_HERO, TEXT, k, ease_back((lt - .8) / .7) or .01), (int(W / 2 - 480), 640 + int((1 - k) * 36)))
        facts = [("Android 8.0+  -  arm64 and x86_64  -  AGPL-3.0", MUTED, F_SUB, 880), ("github.com/ravibh5522/easyIDE", AMBER, F_MONO_S, 940), ("t.me/+yMH4w5rX1gZhOTM1", MUTED, F_MONO_S, 995)]
        for i, (text, col, fnt, y) in enumerate(facts):
            kk = ease_out((lt - 1.6 - 0.3 * i) / 0.5)
            layer.alpha_composite(text_tile(text, fnt, col, kk), (int(W / 2 - 480), y - 30 + int((1 - kk) * 14)))
        return layer

    # ----- final composition -----------------------------------------------------------
    def scene_layer(self, sc, lt):
        kind = sc["kind"]
        if kind == "cold":
            return self.cold_scene(lt)
        if kind == "tablet":
            return self.tablet_scene(sc, lt)
        if kind == "term":
            return self.term_scene(sc, lt)
        return self.outro_scene(sc, lt)

    def chrome(self, out, t, active):
        if active is None:
            return
        d = ImageDraw.Draw(out)
        x = 96
        for i, name in enumerate(S.CHAPTERS):
            on = i == active
            col = AMBER if on else (96, 88, 80)
            d.text((x, 1036), f"{i + 1:02d} {name}", font=F_CHIP, fill=col + (255,), anchor="ls")
            x += int(d.textlength(f"{i + 1:02d} {name}", font=F_CHIP)) + 38
        d.rectangle((96, 1052, W - 96, 1054), fill=(60, 53, 47, 255))
        d.rectangle((96, 1052, 96 + (W - 192) * clamp01(t / S.TOTAL), 1054), fill=AMBER + (255,))

    def shake(self, t):
        dx = dy = 0.0
        for n, hit in enumerate(self.hits):
            age = t - hit
            if 0 <= age < 0.45:
                amp = 16 * math.exp(-9 * age)
                dx += amp * math.sin(age * 90 + n)
                dy += amp * math.cos(age * 70 + n * 2)
        return int(dx), int(dy)

    def frame(self, t):
        out = self.background(t)
        tr = S.TRANSITION
        active = None
        sdx, sdy = self.shake(t)
        for i, sc in enumerate(S.SCENES):
            a0, a1 = sc["t0"] - (tr / 2 if i else 0), sc["t0"] + sc["dur"] + (tr / 2 if i < len(S.SCENES) - 1 else 0)
            if not a0 <= t <= a1:
                continue
            fade_in = clamp01((t - a0) / tr) if i else 1.0
            fade_out = clamp01((a1 - t) / tr) if i < len(S.SCENES) - 1 else 1.0
            layer = self.scene_layer(sc, t - sc["t0"])
            offset = int((1 - ease_out(fade_in)) * 260 - (1 - ease_out(fade_out)) * 260)
            vis = min(fade_in, fade_out)
            if vis < 1:
                layer.putalpha(layer.getchannel("A").point(lambda v, f=vis: int(v * f)))
            layer = smear(layer, offset, sdx, sdy)
            out.alpha_composite(layer)
            if sc.get("chapter") is not None and sc["kind"] != "cold" and fade_in >= 0.5 and fade_out >= 0.5:
                active = sc["chapter"]
        for i in range(1, len(S.SCENES)):
            p = (t - (S.SCENES[i]["t0"] - tr / 2)) / tr
            if 0 <= p <= 1:
                x = lerp(W + 80, -80, p)
                d = ImageDraw.Draw(out)
                d.rectangle((x - 3, 0, x + 3, H), fill=AMBER + (255,))
                out.alpha_composite(glow_blob((360, H), AMBER + (90,), 60), (int(x - 180), 0))
        self.chrome(out, t, active)
        rgb = out.convert("RGB")
        rgb = ImageChops.multiply(rgb, Image.merge("RGB", (self.vignette,) * 3))
        shift = int(t * 37) % 40
        grain = ImageChops.offset(self.grain, shift, shift // 2)
        return ImageChops.add(rgb, Image.merge("RGB", (grain,) * 3), 1.0, -128)


def smear(layer, dx, sx, sy):
    """Slide the layer by dx (+ screen shake), smeared horizontally when moving fast."""
    def shifted(offset):
        return layer.transform(layer.size, Image.AFFINE, (1, 0, -(offset + sx), 0, 1, -sy))
    if abs(dx) < 8:
        return shifted(dx)
    acc = shifted(dx)
    for k in range(1, 4):
        acc = Image.blend(acc, shifted(int(dx * (1 - k / 7))), 1 / (k + 1))
    return acc


_R = None


def _init():
    global _R
    _R = Renderer()


def _render(n):
    return _R.frame(n / FPS).tobytes()


def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--still":
        r = Renderer()
        r.frame(float(sys.argv[2])).save("/tmp/tour-still.png")
        return
    for key in {s["src"][1] for s in S.SCENES if s.get("src", ("",))[0] == "clip"}:
        if not (CACHE / key).exists():
            extract(key)
    frames = int(S.TOTAL * FPS)
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-"]
    if AUDIO.exists():
        cmd += ["-i", str(AUDIO), "-c:a", "aac", "-b:a", "192k", "-shortest"]
    cmd += ["-c:v", "libx264", "-preset", "medium", "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(OUT)]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    with Pool(10, initializer=_init) as pool:
        for chunk in pool.imap(_render, range(frames), chunksize=6):
            enc.stdin.write(chunk)
    enc.stdin.close()
    enc.wait()
    Renderer().frame(33.0).save(POSTER, optimize=True)
    print(f"{frames} frames, {S.TOTAL}s -> {OUT}")


if __name__ == "__main__":
    main()
