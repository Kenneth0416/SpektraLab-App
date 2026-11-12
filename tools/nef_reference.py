#!/usr/bin/env python3
"""LibRaw reference values for NefSamplesTest.

usage: python -I tools/nef_reference.py FILE...   (needs rawpy, e.g. /home/user/rawref/venv/bin/python)

Prints per file: raw size, CRC32 of raw_image (full frame, little-endian uint16, not black-subtracted),
CFA at the crop origin, black per channel, white, camera WB (R G B G2), crop (inset crop when LibRaw has
one, else the visible area), LibRaw flip, XYZ->camera [0][0], and the mean R/G, B/G of a LibRaw render
(camera WB, linear sRGB, half size, central quarter, unclipped pixels) used by developsLikeLibRaw.
"""
import sys
import zlib

import numpy as np
import rawpy


def main():
    for f in sys.argv[1:]:
        with rawpy.imread(f) as r:
            s = r.sizes
            img = np.ascontiguousarray(r.raw_image)
            crc = zlib.crc32(img.astype("<u2").tobytes())
            if s.crop_width:
                crop = (s.crop_left_margin, s.crop_top_margin, s.crop_width, s.crop_height)
            else:
                crop = (s.left_margin, s.top_margin, s.width, s.height)
            desc = r.color_desc.decode()
            cfa = "".join(desc[r.raw_colors[crop[1] + i // 2, crop[0] + i % 2]] for i in range(4))
            colors = r.num_colors
            print(f"{f.split('/')[-1]}: {s.raw_width}x{s.raw_height} crc=0x{crc:08x} cfa@crop={cfa} "
                  f"black={r.black_level_per_channel} white={r.white_level} wb={r.camera_whitebalance} "
                  f"crop={crop} flip={s.flip} xyz00={r.rgb_xyz_matrix[0][0]:.4f}")
        if colors != 3:
            continue
        with rawpy.imread(f) as r:
            out = r.postprocess(use_camera_wb=True, output_color=rawpy.ColorSpace.sRGB, gamma=(1, 1),
                                no_auto_bright=True, output_bps=16, user_flip=0, half_size=True).astype(np.float64)
        h, w, _ = out.shape
        c = out[h // 4:3 * h // 4, w // 4:3 * w // 4].reshape(-1, 3)
        c = c[c.max(axis=1) < 60000]
        m = c.mean(axis=0)
        print(f"    render R/G {m[0] / m[1]:.4f} B/G {m[2] / m[1]:.4f}")


if __name__ == "__main__":
    main()
