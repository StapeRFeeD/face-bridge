"""Renders the Face Bridge launcher icon in the PICO OS 5 dock-icon style.

Reference: the system app icons on PICO OS 5.13 (Friends, Store, Library, Files, Motion Tracker):
- wide 228 x 192 canvas (1.1875:1), rounded rectangle with radius ~20 % of the height;
- saturated background with a gentle diagonal gradient;
- one bold, soft, slightly three-dimensional glyph in a lighter tint of the background colour,
  with a soft drop shadow and a top highlight.

Output: app/src/main/res/mipmap-*/ic_launcher.png. Run: python3 tools/make_icon.py
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
W, H = 228 * 4, 192 * 4          # supersampled master
SIZES = {"mdpi": (76, 64), "hdpi": (114, 96), "xhdpi": (152, 128), "xxhdpi": (228, 192), "xxxhdpi": (304, 256)}


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def gradient(w, h, c0, c1):
    """Diagonal gradient top-left c0 → bottom-right c1."""
    y, x = np.mgrid[0:h, 0:w]
    t = ((x / w) * 0.45 + (y / h) * 0.55)[..., None]
    arr = np.array(c0, float) * (1 - t) + np.array(c1, float) * t
    return Image.fromarray(arr.astype("uint8"), "RGBA")


def rounded_mask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=255)
    return m


def glyph_mask(w, h, scale=1.0):
    """Face glyph: thick rounded ring + two eyes + smile (white on black mask)."""
    m = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(m)
    cx, cy = w / 2, h / 2 + 4 * 4
    R = 62 * 4 * scale
    stroke = int(19 * 4 * scale)
    d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=255, width=stroke)
    er = 11 * 4 * scale
    for ex in (cx - 23 * 4 * scale, cx + 23 * 4 * scale):
        ey = cy - 14 * 4 * scale
        d.ellipse([ex - er, ey - er, ex + er, ey + er], fill=255)
    sw = int(12 * 4 * scale)
    box = [cx - 30 * 4 * scale, cy - 22 * 4 * scale, cx + 30 * 4 * scale, cy + 32 * 4 * scale]
    d.arc(box, start=30, end=150, fill=255, width=sw)
    # round caps on the smile
    import math
    for ang in (30, 150):
        a = math.radians(ang)
        rx, ry = (box[2] - box[0]) / 2 - sw / 2, (box[3] - box[1]) / 2 - sw / 2
        px, py = cx + rx * math.cos(a), (box[1] + box[3]) / 2 + ry * math.sin(a)
        d.ellipse([px - sw / 2, py - sw / 2, px + sw / 2, py + sw / 2], fill=255)
    return m


def render():
    bg0, bg1 = (64, 132, 255, 255), (24, 58, 196, 255)          # PICO-style saturated blue
    base = gradient(W, H, bg0, bg1)
    # soft inner vignette for depth
    vign = Image.new("L", (W, H), 0)
    ImageDraw.Draw(vign).ellipse([-W * 0.2, -H * 0.35, W * 1.2, H * 1.15], fill=255)
    vign = vign.filter(ImageFilter.GaussianBlur(W * 0.08))
    dark = Image.new("RGBA", (W, H), (10, 24, 90, 255))
    base = Image.composite(base, dark, vign.point(lambda v: 150 + v * 105 // 255))

    g = glyph_mask(W, H)
    # drop shadow
    shadow = g.filter(ImageFilter.GaussianBlur(26)).point(lambda v: v * 0.55)
    off = Image.new("L", (W, H), 0)
    off.paste(shadow, (0, 22))
    base = Image.composite(Image.new("RGBA", (W, H), (8, 20, 80, 255)), base, off)
    # glyph body: light tint with vertical gradient (lighter top) = soft volume
    body = gradient(W, H, (236, 244, 255, 255), (164, 196, 255, 255))
    base = Image.composite(body, base, g)
    # top highlight on the glyph
    hl = Image.new("L", (W, H), 0)
    ImageDraw.Draw(hl).ellipse([W * 0.2, -H * 0.25, W * 0.8, H * 0.55], fill=110)
    hl = Image.composite(hl.filter(ImageFilter.GaussianBlur(40)), Image.new("L", (W, H), 0), g)
    base = Image.composite(Image.new("RGBA", (W, H), (255, 255, 255, 255)), base, hl)
    # subtle inner edge darkening at the glyph bottom
    edge = g.filter(ImageFilter.GaussianBlur(10))
    edge = Image.eval(Image.fromarray((np.array(g, int) - np.array(edge, int)).clip(0, 255).astype("uint8")), lambda v: v)
    base = Image.composite(Image.new("RGBA", (W, H), (120, 150, 230, 255)), base, edge.point(lambda v: v // 3))

    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.paste(base, (0, 0), rounded_mask(W, H, int(H * 0.2)))
    for name, size in SIZES.items():
        d = os.path.join(ROOT, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        out.resize(size, Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
    out.resize((456, 384), Image.LANCZOS).save(os.path.join(os.path.dirname(__file__), "..", "docs", "icon-preview.png"))
    print("written", ", ".join(f"{k} {v[0]}x{v[1]}" for k, v in SIZES.items()))


if __name__ == "__main__":
    render()
