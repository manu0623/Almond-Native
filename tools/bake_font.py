#!/usr/bin/env python3
"""Bakes the menu font into assets/fonts/menu.font.

    tools/bake_font.py MPLUSRounded1c-Medium.ttf assets/fonts/menu.font

M PLUS Rounded 1c comes from https://github.com/google/fonts (ofl/mplusrounded1c).
The file holds one alpha atlas and, for each size, its glyphs:

    b"PNF2", u16 atlas width, u16 atlas height, u16 sizes,
    u16 atlas pixels to a panel pixel
    per size: u16 pixels, u16 line height, u16 ascent, u16 glyphs
        per glyph: u32 code point, u16 x, u16 y, u16 width, u16 height,
                   i16 left, i16 top (from the baseline), u16 advance
    atlas width * atlas height alpha bytes
"""
import struct
import sys

from PIL import Image, ImageDraw, ImageFont

SIZES = (15, 13)
# the atlas is baked this many times the panel's pixels: the menu is then
# drawn at this same scale, so its text reaches the screen at the screen's
# resolution rather than magnified from the panel's
SCALE = 3
CHARACTERS = [chr(c) for c in range(32, 127)] + [chr(c) for c in range(160, 256)] + list("…←→↑↓")
WIDTH = 512 * SCALE


def main(ttf, out):
    fonts = [ImageFont.truetype(ttf, size * SCALE) for size in SIZES]
    placed, x, y, row = [], 1, 1, 0
    for font in fonts:
        glyphs = []
        for char in CHARACTERS:
            left, top, right, bottom = font.getbbox(char, anchor="ls")
            width, height = max(right - left, 0), max(bottom - top, 0)
            if x + width + 1 > WIDTH:
                x, y, row = 1, y + row + 1, 0
            glyphs.append((char, x, y, width, height, left, top, round(font.getlength(char))))
            x, row = x + width + 1, max(row, height)
        placed.append(glyphs)
    height = y + row + 1
    atlas = Image.new("L", (WIDTH, height), 0)
    draw = ImageDraw.Draw(atlas)
    out_bytes = bytearray(b"PNF2" + struct.pack("<HHHH", WIDTH, height, len(fonts), SCALE))
    for font, size, glyphs in zip(fonts, SIZES, placed):
        ascent, descent = font.getmetrics()
        out_bytes += struct.pack("<HHHH", size * SCALE, ascent + descent, ascent, len(glyphs))
        for char, gx, gy, w, h, left, top, advance in glyphs:
            if w and h:
                draw.text((gx - left, gy - top), char, font=font, fill=255, anchor="ls")
            out_bytes += struct.pack("<IHHHHhhH", ord(char), gx, gy, w, h, left, top, advance)
    out_bytes += atlas.tobytes()
    open(out, "wb").write(out_bytes)
    print(f"{out}: {WIDTH}x{height} atlas, {len(out_bytes)} bytes")


if __name__ == "__main__":
    main(*sys.argv[1:3])
