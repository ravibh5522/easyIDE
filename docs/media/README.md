# Media

Everything the README's Tour section shows. Nothing here ships in the app.

| Path | What it is |
|---|---|
| `easyide-tour.mp4` | the 54 s tour film, 1920x1080, H.264 + AAC |
| `easyide-tour-preview.gif` | 14 s looping preview of the film, used inline in the README |
| `easyide-tour-poster.png` | a still from the git scene |
| `screenshots/01..07-*.png` | 1920x1200 captures from `v0.2.0-beta.8` (Waydroid, 2560x1600, Ember Night theme) |
| `source/*.mp4` | the raw screen recordings the film is cut from (`adb shell screenrecord`) |
| `source/score.m4a` | the synthesized soundtrack |
| `icon-512.png` | the app icon, used in the film's logo scene |

## Regenerate

```
python3 tools/make-tour-audio.py     # source/score.m4a
python3 tools/make-tour-video.py     # easyide-tour.mp4 and the poster
python3 tools/make-tour-video.py --still 33.0    # one frame to /tmp/tour-still.png
```

The storyboard (what happens when, captions, camera moves, callouts) is data in
`tools/tour/scenes.py`; drawing is in `tools/make-tour-video.py` and `tools/tour/fx.py`. Needs
Python 3 with Pillow and ffmpeg; the full render takes a few minutes on 10 cores.

## Capture notes

- Recordings and screenshots come from a Waydroid session set to 2560x1600 at 320 dpi
  (`adb shell wm size 2560x1600; adb shell wm density 320`), then reset with `wm size reset` and
  `wm density reset`.
- Screenshots are taken with the soft keyboard hidden.
