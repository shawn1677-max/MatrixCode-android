#!/usr/bin/env python3
"""
Turns the renderer output in app/build/store/ into Play-listing-ready artwork in store/.

Play wants the feature graphic and screenshots as 24-bit PNG with no alpha channel,
and the icon as a 512x512 32-bit PNG. Bitmap.compress always writes RGBA, so the
frames are flattened onto black here.

Run after: ./gradlew :app:testDebugUnitTest --tests '*StoreAssetsTest*'
"""
import os
import random
import sys

from PIL import Image, ImageDraw

SRC = "app/build/store"
DST = "store"

GREEN = (0, 255, 65)
PALE = (200, 255, 214)


def flatten(src_name, dst_name):
    """Composite onto black and drop the alpha channel."""
    src = os.path.join(SRC, src_name)
    if not os.path.exists(src):
        sys.exit(f"missing {src} - run the StoreAssetsTest first")
    img = Image.open(src).convert("RGBA")
    bg = Image.new("RGB", img.size, (0, 0, 0))
    bg.paste(img, mask=img.split()[3])
    bg.save(os.path.join(DST, dst_name), "PNG")
    print(f"  {dst_name}  {bg.size[0]}x{bg.size[1]}  RGB")


def draw_rain(img, size, inset, seed):
    """The same stylised dash-columns used for the launcher icon."""
    random.seed(seed)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size - 1, size - 1],
                        radius=int(size * 0.22), fill=(0, 8, 3, 255))
    area = size - 2 * inset
    cols = 7
    cw = area / cols
    dash_w = max(1, int(cw * 0.52))
    dash_h = max(1, int(cw * 0.30))
    gap = cw * 0.52
    rows = int(area / gap)
    for c in range(cols):
        x = inset + c * cw + (cw - dash_w) / 2
        head = random.uniform(0.25, 1.0) * rows
        trail = random.randint(max(2, rows // 3), rows)
        for t in range(trail):
            row = int(head) - t
            if row < 0 or row >= rows:
                continue
            f = 1.0 - (t / trail)
            a = 255 if t == 0 else int(255 * (f ** 1.9))
            if a < 12:
                continue
            y = inset + row * gap
            d.rounded_rectangle([x, y, x + dash_w, y + dash_h],
                                radius=max(1, dash_h // 2),
                                fill=(PALE if t == 0 else GREEN) + (a,))


def build_icon():
    size = 512
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # Seed 7 matches the launcher icon art so the listing and the device agree.
    draw_rain(img, size, int(size * 0.14), seed=7)
    img.save(os.path.join(DST, "icon-512.png"), "PNG")
    print(f"  icon-512.png  512x512  RGBA")


def main():
    os.makedirs(DST, exist_ok=True)
    print("Play listing assets ->", DST)
    flatten("feature-raw.png", "feature-graphic-1024x500.png")
    for n in range(1, 6):
        match = [f for f in os.listdir(SRC) if f.startswith(f"screen-{n}-")]
        if match:
            flatten(match[0], match[0].replace("screen-", "screenshot-"))
    build_icon()


if __name__ == "__main__":
    main()
