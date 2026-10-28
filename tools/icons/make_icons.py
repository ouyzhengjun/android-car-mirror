#!/usr/bin/env python3
"""Regenerates the launcher icons from the company logo.

Source: https://static.txzing.com/javaoss/wx/txz-website-2/img/logo-txz.webp
The logo is a white rounded badge with the 行 mark punched out (transparent), so the icon is
built by inverting that alpha inside the badge silhouette: white tile, near-black glyph.

Usage: python3 tools/icons/make_icons.py   (from the repository root)
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res')
INK = (9, 9, 16, 255)
GLYPH_RATIO_LEGACY = 0.78   # matches the badge's own padding
GLYPH_RATIO_ADAPTIVE = 0.58  # stays inside the 66dp safe zone of the 108dp canvas


def load_glyph():
    src = Image.open(os.path.join(HERE, 'logo-txz.webp')).convert('RGBA')
    alpha = src.getchannel('A')
    w, h = src.size
    silhouette = Image.new('L', (w, h), 0)
    ImageDraw.Draw(silhouette).rounded_rectangle(
        [0, 0, w - 1, h - 1], radius=int(w * 0.233), fill=255)
    glyph = Image.new('L', (w, h), 0)
    for y in range(h):
        for x in range(w):
            if silhouette.getpixel((x, y)) > 128 and alpha.getpixel((x, y)) < 128:
                glyph.putpixel((x, y), 255)
    return glyph


def white_tile(size):
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=255)
    return Image.composite(Image.new('RGBA', (size, size), (255, 255, 255, 255)),
                           Image.new('RGBA', (size, size), (0, 0, 0, 0)), mask)


def paint(base, glyph, size, ratio):
    inner = int(round(size * ratio))
    scaled = glyph.resize((inner, inner), Image.LANCZOS)
    offset = (size - inner) // 2
    base.paste(Image.new('RGBA', (inner, inner), INK), (offset, offset), scaled)
    return base


def main():
    glyph = load_glyph()
    legacy = [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]
    for density, size in legacy:
        icon = paint(white_tile(size), glyph, size, GLYPH_RATIO_LEGACY)
        icon.save(os.path.join(RES, 'mipmap-' + density, 'ic_launcher.png'))
        icon.save(os.path.join(RES, 'mipmap-' + density, 'ic_launcher_round.png'))
    adaptive = [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]
    for density, size in adaptive:
        foreground = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        paint(foreground, glyph, size, GLYPH_RATIO_ADAPTIVE)
        foreground.save(os.path.join(RES, 'mipmap-' + density, 'ic_launcher_foreground.png'))
    print('icons regenerated')


if __name__ == '__main__':
    main()
