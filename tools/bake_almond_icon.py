#!/usr/bin/env python3
"""Bakes Almond Native's icon for the Android app.

    tools/bake_almond_icon.py assets/logo/source/almond-native.png

Writes:

    assets/logo/almond-native.png
    scripts/android/res/mipmap-*/ic_launcher.png and ic_launcher_foreground.png,
                                the adaptive icon's logo
    scripts/android/res/values/colors.xml
                                the icon's background, the art's own
"""
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ANDROID = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def square(image, side):
    """the icon centred in a square of side, at its own aspect"""
    image = image.copy()
    image.thumbnail((side, side), Image.LANCZOS)
    out = Image.new("RGBA", (side, side))
    out.paste(image, ((side - image.width) // 2, (side - image.height) // 2))
    return out


def main(source):
    icon = Image.open(source).convert("RGBA")
    out = ROOT / "assets" / "logo"
    square(icon, 512).save(out / "almond-native.png", optimize=True)
    for density, side in ANDROID.items():
        folder = ROOT / "scripts" / "android" / "res" / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        square(icon, side).save(folder / "ic_launcher.png", optimize=True)
        # 108 dp, the logo filling the middle 72 dp a launcher shows
        canvas = Image.new("RGBA", (side * 9 // 4,) * 2)
        logo = square(icon, side * 9 // 4 * 70 // 108)
        canvas.paste(logo, ((canvas.width - logo.width) // 2,) * 2)
        canvas.save(folder / "ic_launcher_foreground.png", optimize=True)
    # the adaptive icon's background, the art's own, so that the logo's
    # square sits in it without a seam
    background = icon.getpixel((2, 2))
    colours = ROOT / "scripts" / "android" / "res" / "values" / "colors.xml"
    colours.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="icon_background">#{background[0]:02X}{background[1]:02X}{background[2]:02X}</color>\n'
        "</resources>\n"
    )
    print(f"== baked the icon, background #{background[0]:02X}{background[1]:02X}{background[2]:02X}")


if __name__ == "__main__":
    main(*sys.argv[1:2])
