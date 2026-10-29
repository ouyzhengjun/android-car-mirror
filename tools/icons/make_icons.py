#!/usr/bin/env python3
"""Generates the launcher icons: a white cast glyph on a blue tile.

The glyph is drawn from scratch (screen outline, origin dot and two signal
arcs), so no external asset is needed. Shapes are rendered at 4x and
downsampled for smooth edges.

Usage: python3 tools/icons/make_icons.py   (from the repository root)
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res')
BLUE = (37, 99, 235, 255)    # #2563EB, also used by @color/ic_launcher_background
WHITE = (255, 255, 255, 255)
SS = 4                        # supersampling factor

LEGACY_RATIO = 0.72
ROUND_RATIO = 0.62
ADAPTIVE_RATIO = 0.68         # keeps the glyph inside the 66dp adaptive safe zone


def draw_glyph(size):
    s = size * SS
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([s * 0.14, s * 0.22, s * 0.86, s * 0.74],
                        radius=s * 0.10, outline=WHITE, width=round(s * 0.076))
    cx, cy = s * 0.26, s * 0.60
    dot = s * 0.050
    d.ellipse([cx - dot, cy - dot, cx + dot, cy + dot], fill=WHITE)
    for radius in (s * 0.13, s * 0.215):
        d.arc([cx - radius, cy - radius, cx + radius, cy + radius],
              start=270, end=360, fill=WHITE, width=round(s * 0.064))
    return img.resize((size, size), Image.LANCZOS)


def tile(size, circular=False):
    mask = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(mask)
    if circular:
        d.ellipse([0, 0, size - 1, size - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=255)
    return Image.composite(Image.new('RGBA', (size, size), BLUE),
                           Image.new('RGBA', (size, size), (0, 0, 0, 0)), mask)


def compose(size, ratio, circular=False):
    base = tile(size, circular)
    inner = int(round(size * ratio))
    base.alpha_composite(draw_glyph(inner), ((size - inner) // 2, (size - inner) // 2))
    return base


def main():
    legacy = [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]
    for density, size in legacy:
        compose(size, LEGACY_RATIO).save(
            os.path.join(RES, 'mipmap-' + density, 'ic_launcher.png'))
        compose(size, ROUND_RATIO, circular=True).save(
            os.path.join(RES, 'mipmap-' + density, 'ic_launcher_round.png'))
    adaptive = [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]
    for density, size in adaptive:
        inner = int(round(size * ADAPTIVE_RATIO))
        foreground = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        foreground.alpha_composite(draw_glyph(inner), ((size - inner) // 2, (size - inner) // 2))
        foreground.save(os.path.join(RES, 'mipmap-' + density, 'ic_launcher_foreground.png'))
    print('icons regenerated')


if __name__ == '__main__':
    main()
