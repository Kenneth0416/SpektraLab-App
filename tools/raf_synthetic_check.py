#!/usr/bin/env python3
"""Decode the synthetic RAF files written by RafTest.synthetic (SPEKTRA_RAF_DUMP=dir) with LibRaw.

usage: raf_synthetic_check.py DIR
Prints, per file, what RafTest.LIBRAW_SYNTHETIC pins: CRC32 of raw_image as little-endian uint16,
crop (left, top, width, height; the visible area when LibRaw has no inset crop), white level, black level, colours of the first visible pixels (raw_colors_visible, 3 = second green), plus margins, WB and the decode of any *.mosaic.u16 next to it.
Run with the rawpy venv:  /home/user/rawref/venv/bin/python -I tools/raf_synthetic_check.py DIR
"""
import glob
import os
import sys
import zlib

import numpy as np
import rawpy

for p in sorted(glob.glob(os.path.join(sys.argv[1], '*.RAF'))):
    name = os.path.basename(p)[:-4]
    try:
        with rawpy.imread(p) as r:
            m = np.ascontiguousarray(r.raw_image).astype('<u2')
            s = r.sizes
            n = 6 if len(r.raw_pattern.flatten()) == 36 or r.raw_pattern.shape[0] == 6 else 2
            # colour of the first n x n visible pixels, i.e. LibRaw COLOR(y, x).  (rawpy's raw_pattern and
            # raw_colors call COLOR with negative visible coordinates when left_margin > 6 and then read
            # outside LibRaw's xtrans array.)
            pat = [int(r.raw_color(y + s.top_margin, x + s.left_margin)) for y in range(n) for x in range(n)]
            crop = (s.crop_left_margin, s.crop_top_margin, s.crop_width, s.crop_height)
            if crop[2] == 0 or crop[3] == 0:  # no inset crop: the engine uses the visible area
                crop = (s.left_margin, s.top_margin, s.width, s.height)
            print('"%s" to Expected(0x%08xL, listOf(%d, %d, %d, %d), %df, %df, listOf(%s)),' % (
                name, zlib.crc32(m.tobytes()), crop[0], crop[1], crop[2], crop[3],
                r.white_level, r.black_level_per_channel[0], ', '.join(str(v) for v in pat)))
            print('    // %s raw %dx%d visible (%d, %d) %dx%d wb %s black %s' % (
                name, s.raw_width, s.raw_height, s.left_margin, s.top_margin, s.width, s.height,
                r.camera_whitebalance, r.black_level_per_channel))
    except Exception as e:  # noqa: BLE001
        print('// %s: LibRaw failed: %s' % (name, e))
