"""Decode synthetic raw files with LibRaw (rawpy) and compare against the in-test encoders.

    ./gradlew :engine:test --tests '*Rw2*' --tests '*Srw*' -Dspektra.rawdump=/tmp/synth
    python tools/synth_raw_check.py /tmp/synth

The Rw2 / Srw tests write every synthetic file they build as <name>.rw2 / <name>.srw and, where
the encoder knows the pixels it wrote, <name>.raw16 (uint16 little endian, raw_width x raw_height).
For each file this prints LibRaw's CRC32 of raw_image (the value pinned in the test), its levels,
white balance and crop, and checks raw_image == <name>.raw16 when that exists.
"""
import glob
import os
import sys
import zlib

import numpy as np
import rawpy

d = sys.argv[1]
bad = 0
for path in sorted(glob.glob(os.path.join(d, "*.rw2")) + glob.glob(os.path.join(d, "*.srw"))):
    name = os.path.splitext(os.path.basename(path))[0]
    try:
        raw = rawpy.RawPy()
        raw.open_file(path)
    except Exception as e:  # noqa: BLE001
        print(f"{name}: LibRaw cannot open: {e}")
        bad += 1
        continue
    s = raw.sizes
    try:
        raw.unpack()
        img = raw.raw_image
        crc = zlib.crc32(np.ascontiguousarray(img).astype("<u2").tobytes())
    except Exception as e:  # noqa: BLE001  (geometry-only files carry no pixel data)
        img, crc = None, 0
        print(f"{name}: (no pixels: {e})")
    print(f"{name}: {s.raw_width}x{s.raw_height} crc32={crc} (0x{crc:08x})")
    if img is not None:
        print(f"   black={raw.black_level_per_channel} white={raw.white_level} wb={list(raw.camera_whitebalance)} "
              f"pattern={raw.raw_pattern.tolist()} flip={s.flip}")
    print(f"   crop(l,t,w,h)=({s.crop_left_margin},{s.crop_top_margin},{s.crop_width},{s.crop_height}) "
          f"margins(l,t)=({s.left_margin},{s.top_margin}) visible={s.width}x{s.height}")
    dev = os.path.join(d, name + ".developed.txt")
    if os.path.exists(dev) and s.flip == 0 and img is not None:
        ref = raw.postprocess(gamma=(1, 1), no_auto_bright=True, use_camera_wb=True, output_bps=16,
                              output_color=rawpy.ColorSpace.sRGB, demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                              highlight_mode=rawpy.HighlightMode.Clip, user_flip=0).astype(np.float64) / 65535
        lines = open(dev).read().split("\n")
        w, h, cx, cy = map(int, lines[0].split())
        mine = np.array([[float(v) for v in l.split()] for l in lines[1:1 + w * h]]).reshape(h, w, 3)
        m = 6  # skip the demosaic borders
        a = mine[m:-m, m:-m]
        oy, ox = cy - s.top_margin, cx - s.left_margin  # LibRaw's output starts at its own margins
        b = ref[oy + m:oy + m + a.shape[0], ox + m:ox + m + a.shape[1]]
        scale = (a * b).sum() / (b * b).sum()
        print("   developed vs LibRaw: exposure scale %.4f, max abs diff %.4f, mean %.5f"
              % (scale, np.abs(a - b * scale).max(), np.abs(a - b * scale).mean()))
        print("   mean chromaticity ours", (a.mean((0, 1)) / a.mean()).round(4), "LibRaw", (b.mean((0, 1)) / b.mean()).round(4))
    exp = os.path.join(d, name + ".raw16")
    if os.path.exists(exp) and img is not None:
        want = np.fromfile(exp, dtype="<u2")
        got = img.reshape(-1)
        ok = want.size == got.size and np.array_equal(want, got)
        print("   mosaic equals encoder output:", ok)
        if not ok:
            bad += 1
            if want.size == got.size:
                diff = np.nonzero(want != got)[0]
                print("   first differing pixels:", diff[:10], want[diff[:10]], got[diff[:10]])
sys.exit(1 if bad else 0)
