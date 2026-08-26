#!/usr/bin/env python3
"""
Encodes the promo frames rendered by PromoVideoTest into an MP4.

Run first:
    ./gradlew :app:testDebugUnitTest --tests '*PromoVideoTest*' -Ppromo

Then:
    python3 tools/build_promo.py
"""
import os
import subprocess
import sys

import imageio_ffmpeg

SRC = "app/build/promo"
OUT = "dist/Codefall-promo.mp4"
FPS = 30


def main():
    if not os.path.isdir(SRC) or not os.listdir(SRC):
        sys.exit(f"no frames in {SRC} - render them first (see the docstring)")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

    cmd = [
        ffmpeg, "-y",
        "-framerate", str(FPS),
        "-i", os.path.join(SRC, "f%05d.png"),
        "-c:v", "libx264",
        "-preset", "slow",
        "-crf", "18",
        # yuv420p is the pixel format every player and upload pipeline accepts.
        "-pix_fmt", "yuv420p",
        # Rain is high-frequency detail; a keyframe every second keeps it from
        # smearing when a player seeks or a platform re-encodes.
        "-g", str(FPS),
        "-movflags", "+faststart",
        OUT,
    ]
    print(" ".join(cmd[:1]) + " ... -> " + OUT)
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(result.stderr[-3000:])

    size = os.path.getsize(OUT)
    print(f"wrote {OUT}  ({size / 1_000_000:.1f} MB)")


if __name__ == "__main__":
    main()
