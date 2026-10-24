#!/usr/bin/env python3
"""Draws the desktop app icons (same design as the Android adaptive icon) with Pillow.

Writes desktopApp/icons/skgram.{png,ico,icns} and desktopApp/src/main/resources/skgram.png.
"""
import os
from PIL import Image, ImageDraw

S = 1024  # work at high resolution, then downscale for smooth edges


def draw():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # rounded-square background with a diagonal blue gradient
    grad = Image.new("RGBA", (S, S))
    gp = grad.load()
    a, b = (0x12, 0x8A, 0xFA), (0x4F, 0xD0, 0xF7)
    for y in range(S):
        for x in range(0, S, 4):
            t = (x + y) / (2 * S)
            c = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)
            for dx in range(4):
                gp[x + dx, y] = c
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([40, 40, S - 40, S - 40], radius=220, fill=255)
    img.paste(grad, (0, 0), mask)

    over = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    k = S / 108  # Android vector viewport is 108 units
    P = lambda pts: [(x * k, y * k) for x, y in pts]
    # slanted stripes
    d.polygon(P([(-10, 108), (20, 108), (80, 0), (50, 0)]), fill=(255, 255, 255, 26))
    d.polygon(P([(40, 108), (56, 108), (116, 0), (100, 0)]), fill=(255, 255, 255, 18))
    # halo ring and rays
    gold = (0xFF, 0xD6, 0x6B, 255)
    d.ellipse([35 * k, 18 * k, 73 * k, 32 * k], outline=gold, width=int(3.2 * k))
    d.rectangle([53 * k, 12 * k, 55 * k, 17 * k], fill=gold)
    d.polygon(P([(31, 21), (32.4, 19.6), (35.6, 22.8), (34.2, 24.2)]), fill=gold)
    d.polygon(P([(77, 21), (75.6, 19.6), (72.4, 22.8), (73.8, 24.2)]), fill=gold)
    # paper plane
    d.polygon(P([(28, 58), (80, 38), (70, 80), (56, 69), (50, 77), (48, 66)]), fill=(255, 255, 255, 255))
    d.polygon(P([(48, 66), (76, 42), (56, 69), (50, 77)]), fill=(0xCF, 0xE8, 0xFF, 255))
    over.putalpha(Image.composite(over.getchannel("A"), Image.new("L", (S, S), 0), mask))
    return Image.alpha_composite(img, over)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    icons = os.path.join(root, "desktopApp/icons")
    res = os.path.join(root, "desktopApp/src/main/resources")
    os.makedirs(icons, exist_ok=True)
    os.makedirs(res, exist_ok=True)
    big = draw()
    png = big.resize((512, 512), Image.LANCZOS)
    png.save(os.path.join(icons, "skgram.png"))
    big.resize((256, 256), Image.LANCZOS).save(os.path.join(res, "skgram.png"))
    big.save(os.path.join(icons, "skgram.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    big.save(os.path.join(icons, "skgram.icns"))
    print("icons written")


if __name__ == "__main__":
    main()
