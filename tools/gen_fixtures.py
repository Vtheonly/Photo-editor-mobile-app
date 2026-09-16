#!/usr/bin/env python3
"""Deterministic real-media fixture generator for the Recovery Scanner test suite.

Outputs to app/src/test/resources/fixtures/ plus a fixtures.json manifest
(size + sha256 per file) used by tests for ground-truth comparison.
All inputs are deterministic (no RNG without fixed seeds), so re-running
this script produces byte-identical fixtures.
"""
import hashlib
import io
import json
import os
import struct
import subprocess
import sys

import numpy as np
from PIL import Image

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "repo"))
OUT = os.path.join(REPO, "app", "src", "test", "resources", "fixtures")
os.makedirs(OUT, exist_ok=True)


def gradient_rgb(w: int, h: int, noise: int = 0) -> Image.Image:
    x = np.tile(np.linspace(0, 255, w, dtype=np.uint8), (h, 1))
    y = np.tile(np.linspace(0, 255, h, dtype=np.uint8)[:, None], (1, w))
    b = ((x.astype(np.int32) + y.astype(np.int32)) % 255).astype(np.uint8)
    if noise:
        rng = np.random.default_rng(12345)
        x = x.astype(np.int16)
        for ch, arr in enumerate((x, y, b)):
            n = rng.integers(-noise, noise + 1, size=arr.shape, dtype=np.int16)
            out = np.clip(arr.astype(np.int32) + n, 0, 255).astype(np.uint8)
            if ch == 0:
                x = out
            elif ch == 1:
                y = out
            else:
                b = out
    return Image.fromarray(np.dstack([x, y, b]).astype(np.uint8), "RGB")


def save(img: Image.Image, name: str, **kw):
    path = os.path.join(OUT, name)
    img.save(path, **kw)
    print(f"  {name:32s} {os.path.getsize(path):>9,} bytes")


def run_ffmpeg(out_name: str, args: list[str]):
    path = os.path.join(OUT, out_name)
    cmd = ["ffmpeg", "-y", "-loglevel", "error"] + args + [path]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  ffmpeg FAILED for {out_name}: {r.stderr.strip()[:200]}", file=sys.stderr)
        return False
    print(f"  {out_name:32s} {os.path.getsize(path):>9,} bytes")
    return True


def box(size: int, kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", size) + kind + payload


def handcrafted_heic(brand: bytes, extra_brands: list[bytes]) -> bytes:
    """Minimal ISO-BMFF stream with ftyp(brand) + meta + mdat + free.

    Not a decodable image, but structurally walkable by an ISO-BMFF parser,
    which is what the engine consumes for HEIC.
    """
    ftyp_payload = brand + struct.pack(">I", 0) + b"".join(extra_brands)
    ftyp = box(8 + len(ftyp_payload), b"ftyp", ftyp_payload)
    hdlr = box(8 + 21, b"hdlr", struct.pack(">I", 0) + b"pict" + b"\0" * 12 + b"scanner" + b"\0")
    pitm = box(8 + 6, b"pitm", struct.pack(">I", 0) + struct.pack(">H", 1))
    iinf = box(8 + 8, b"iinf", struct.pack(">I", 0) + struct.pack(">H", 0))
    meta_payload = struct.pack(">I", 0) + hdlr + pitm + iinf
    meta = box(8 + len(meta_payload), b"meta", meta_payload)
    mdat = box(8 + 64, b"mdat", bytes((i * 7 + 13) & 0xFF for i in range(64)))
    free = box(8 + 8, b"free", b"\0" * 8)
    return ftyp + meta + mdat + free


def main():
    print("== images ==")
    save(gradient_rgb(320, 240, noise=18), "jpeg_baseline.jpg", quality=85)
    save(gradient_rgb(64, 48), "jpeg_small.jpg", quality=70)          # < 10 KB
    save(gradient_rgb(1600, 1200, noise=10), "jpeg_large.jpg", quality=85)
    save(gradient_rgb(320, 240, noise=18), "jpeg_progressive.jpg", quality=85, progressive=True)
    exif = Image.Exif()
    exif[36867] = "2024:05:21 09:30:00"                                # DateTimeOriginal
    exif[306] = "2024:05:21 09:30:01"                                  # DateTime
    save(gradient_rgb(320, 240, noise=18), "jpeg_exif.jpg", quality=85, exif=exif)
    save(gradient_rgb(320, 240).convert("RGBA"), "png_rgba.png")
    save(gradient_rgb(1000, 750, noise=8), "png_large.png")
    save(gradient_rgb(200, 150), "bmp_24bit.bmp")
    save(gradient_rgb(200, 150), "gif89a_single.gif", comment=b"recovery-scanner fixture")
    gif87 = gradient_rgb(200, 150)
    gif87.save(os.path.join(OUT, "gif87a_single.gif"))
    with open(os.path.join(OUT, "gif87a_single.gif"), "r+b") as f:     # force 87a if PIL wrote 89a
        f.seek(3); f.write(b"87a")
    print(f"  {'gif87a_single.gif':32s} {os.path.getsize(os.path.join(OUT,'gif87a_single.gif')):>9,} bytes")
    frames = [gradient_rgb(200, 150, noise=(i + 1) * 6) for i in range(3)]
    frames[0].save(os.path.join(OUT, "gif_animated.gif"), save_all=True, append_images=frames[1:], duration=80, loop=0)
    print(f"  {'gif_animated.gif':32s} {os.path.getsize(os.path.join(OUT,'gif_animated.gif')):>9,} bytes")

    save(gradient_rgb(320, 240, noise=18), "webp_lossy.webp", quality=80, method=4)
    save(gradient_rgb(320, 240), "webp_lossless.webp", lossless=True)
    save(gradient_rgb(320, 240, noise=18), "webp_extended.webp", quality=80, exif=exif)
    anim = [gradient_rgb(200, 150, noise=(i + 1) * 6) for i in range(3)]
    anim[0].save(os.path.join(OUT, "webp_animated.webp"), save_all=True, append_images=anim[1:], duration=100, loop=0)
    print(f"  {'webp_animated.webp':32s} {os.path.getsize(os.path.join(OUT,'webp_animated.webp')):>9,} bytes")

    print("== handcrafted HEIC/HEIF brands ==")
    for name, brand, extra in [
        ("heic_brand.heic", b"heic", [b"mif1", b"heic"]),
        ("heix_brand.heic", b"heix", [b"mif1"]),
        ("mif1_brand.heif", b"mif1", [b"heic"]),
        ("msf1_brand.heif", b"msf1", [b"mif1"]),
    ]:
        data = handcrafted_heic(brand, extra)
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        print(f"  {name:32s} {len(data):>9,} bytes")

    print("== videos (ffmpeg) ==")
    base = ["-f", "lavfi", "-i", "testsrc=size=320x240:rate=24:duration=2"]
    run_ffmpeg("mp4_h264.mp4", base + ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart"])
    run_ffmpeg("mp4_mdat_first.mp4", base + ["-c:v", "libx264", "-pix_fmt", "yuv420p"])
    run_ffmpeg("mov_qt.mov", base + ["-c:v", "libx264", "-pix_fmt", "yuv420p"])
    run_ffmpeg("video_3gp.3gp", ["-f", "lavfi", "-i", "testsrc=size=176x144:rate=15:duration=2",
                                 "-c:v", "h263"])
    run_ffmpeg("video.mkv", base + ["-c:v", "libx264", "-pix_fmt", "yuv420p"])
    run_ffmpeg("video.webm", base + ["-c:v", "libvpx", "-b:v", "200k"])
    run_ffmpeg("mp4_hevc.mp4", base + ["-c:v", "libx265", "-pix_fmt", "yuv420p", "-tag:v", "hvc1"])
    run_ffmpeg("mp4_large.mp4", ["-f", "lavfi", "-i", "testsrc=size=640x480:rate=24:duration=8",
                                 "-c:v", "libx264", "-pix_fmt", "yuv420p"])

    print("== manifest ==")
    manifest = {}
    for name in sorted(os.listdir(OUT)):
        if name == "fixtures.json":
            continue
        data = open(os.path.join(OUT, name), "rb").read()
        manifest[name] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    with open(os.path.join(OUT, "fixtures.json"), "w") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    print(f"  {len(manifest)} fixtures -> fixtures.json")


if __name__ == "__main__":
    main()
