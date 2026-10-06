"""The storyboard for the tour video: pure data, no drawing.

Music is 120 BPM, so a beat is 0.5 s and a bar is 2 s; every scene starts on a bar line so
cuts, callouts and slams land on the beat (tools/make-tour-audio.py uses the same grid).

Camera keys are (t, cx, cy, zoom) over the 1920x1200 source frame; callout anchors (u, v) are
source pixels and (dx, dy) place the label relative to the anchor in canvas pixels.
"""

BPM = 120
BEAT = 60.0 / BPM
BAR = 4 * BEAT
TRANSITION = 0.36  # seconds a cut takes, centred on the bar line

COLD_LINES = [  # (start, text, colour) typed one after another in the cold open
    (0.9, "your tablet has the screen.", "text"),
    (2.5, "the keyboard.", "text"),
    (3.5, "the battery.", "text"),
    (4.4, "but nothing to build with.", "muted"),
]
COLD_STRIKE = 5.6   # the last line gets crossed out
COLD_SLAM = 6.0     # logo hits on bar 4
TAGLINE = ("A real IDE with a real Linux userland,", "for Android tablets.")

CHAPTERS = ["START", "CREATE", "CODE", "EDIT", "GIT", "TERMINAL", "THEMES"]

# Pixel boxes inside the recorded frames (1920x1200 source).
TERMINAL_CROP = (528, 372, 1920, 572)
CODE_BOX = (525, 98, 1920, 445)     # code lines of 03-python-editor.png
CODE_TEXT_X, CODE_ROW_Y, CODE_ROW_H, CODE_CHAR_W = 606, 101, 28.5, 12.75
CODE_LINE_CHARS = [32, 0, 21, 0, 0, 13, 12, 61, 0, 0, 26, 22]
TOAST_BOX = (492, 1038, 1428, 1112)
TYPING_CPS = 90
TYPING_GAP = 0.06  # pause at the end of each line

SCENES = [
    {"kind": "cold", "t0": 0, "dur": 8},
    {"kind": "tablet", "t0": 8, "dur": 4, "chapter": 0, "layout": "right", "yaw": -16,
     "title": "Open straight\ninto your work.", "sub": "Projects, branches and\nenvironments in one place.",
     "src": ("shot", "01-home.png"),
     "cam": [(0, .30, .30, 1.7), (2.4, .5, .5, 1.0)],
     "callouts": [
         {"t": 1.4, "text": "Recent projects", "at": (330, 215), "dx": 120, "dy": 120},
         {"t": 2.0, "text": "Ubuntu + Python, ready", "at": (1380, 340), "dx": -80, "dy": -110},
     ]},
    {"kind": "tablet", "t0": 12, "dur": 8, "chapter": 1, "layout": "left", "yaw": 14,
     "title": "A new project\nin seconds.", "sub": "Name it, pick where it lives,\npick a Linux. Done.",
     "src": ("clip", "create", [(0, 2.2), (8, 9.75)]),
     "cam": [(0, .22, .09, 2.3), (0.9, .22, .09, 2.3), (2.2, .5, .17, 2.3), (6.0, .5, .17, 2.3), (6.8, .5, .34, 1.6),
             (7.6, .5, .34, 1.6), (8, .5, .5, 1.0)],
     "callouts": [
         {"t": 3.0, "text": "Type a name", "at": (700, 205), "dx": 300, "dy": -85},
         {"t": 4.0, "text": "Local storage or a folder", "at": (1000, 300), "dx": 280, "dy": 85},
     ]},
    {"kind": "tablet", "t0": 20, "dur": 6, "chapter": 2, "layout": "right", "yaw": -14,
     "title": "Real language\nservers.", "sub": "pyright and ruff run on the device.\nErrors underline as you type.",
     "src": ("typing", "03-python-editor.png"), "typing_at": 0.8,
     "cam": [(0, .42, .20, 1.7), (3.8, .42, .20, 1.7), (5.2, .5, .5, 1.0)],
     "callouts": [
         {"t": 4.2, "text": "Diagnostics inline", "at": (700, 125), "dx": 140, "dy": 120},
         {"t": 4.9, "text": "pyright + ruff: ready", "at": (1330, 1180), "dx": -150, "dy": -90},
     ]},
    {"kind": "tablet", "t0": 26, "dur": 4, "chapter": 3, "layout": "left", "yaw": 15,
     "title": "Built for\ntouch.", "sub": "Tabs, breadcrumbs, a real file tree,\nsyntax colours for 229 languages.",
     "src": ("clip", "workspace", [(0, 15.2), (4, 19.6)]),
     "cam": [(0, .62, .40, 1.15), (3.8, .5, .5, 1.0)],
     "callouts": [
         {"t": 0.9, "text": "File tree", "at": (200, 420), "dx": 120, "dy": 90},
         {"t": 1.7, "text": "Syntax colours", "at": (1100, 480), "dx": 60, "dy": -150},
     ]},
    {"kind": "tablet", "t0": 30, "dur": 6, "chapter": 4, "layout": "right", "yaw": -15,
     "title": "Your history,\ndrawn.", "sub": "Branches, tags and commits\nright next to your code.",
     "src": ("clip", "workspace", [(0, 8.8), (6, 14.2)]),
     "cam": [(0, .17, .45, 1.8), (3.0, .17, .45, 1.8), (5.8, .5, .5, 1.0)],
     "callouts": [
         {"t": 1.0, "text": "Commit graph", "at": (140, 360), "dx": 190, "dy": -120},
         {"t": 2.0, "text": "Branch and tag labels", "at": (410, 320), "dx": 220, "dy": 150},
     ]},
    {"kind": "term", "t0": 36, "dur": 6, "chapter": 5, "yaw": -7,
     "title": "A real Ubuntu terminal.", "sub": "git, python3, apt. Offline. No root.",
     "src": ("clip", "terminal", [(0, 1.0), (6, 10.2)])},
    {"kind": "tablet", "t0": 42, "dur": 6, "chapter": 6, "layout": "center", "yaw": 0,
     "title": "Make it yours.", "sub": "Ember Night, Morning Linen, accent colours,\ndensity and fonts.",
     "src": ("clip", "theme", [(0, 5.4), (1.5, 6.9), (3.0, 9.1), (4.5, 11.2), (6, 12.6)]),
     "cam": [(0, .5, .5, 1.0), (6, .5, .5, 1.0)],
     "slams": [(1.5, "LIGHT"), (3.0, "DARK"), (4.5, "EMBER")]},
    {"kind": "outro", "t0": 48, "dur": 6},
]

TOTAL = SCENES[-1]["t0"] + SCENES[-1]["dur"]
