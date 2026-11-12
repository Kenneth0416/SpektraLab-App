#!/usr/bin/env python3
"""Decode a raw file with LibRaw (rawpy) and print what ArwTest compares, one JSON object.

usage: python -I tools/arw_libraw_check.py FILE
Used by engine ArwTest when SPEKTRA_RAWPY names a python that has rawpy: the synthetic ARW files
the test encodes are decoded here, proving the in-test encoders write what LibRaw reads.
"""
import json
import sys
import zlib

import numpy as np
import rawpy

with rawpy.imread(sys.argv[1]) as r:
    s = r.sizes
    m = np.ascontiguousarray(r.raw_image.astype('<u2'))
    print(json.dumps({
        'raw_width': s.raw_width, 'raw_height': s.raw_height, 'width': s.width, 'height': s.height,
        'crop': [s.crop_left_margin, s.crop_top_margin, s.crop_width, s.crop_height],
        'flip': s.flip,
        'crc': zlib.crc32(m.tobytes()),
        'black': [int(x) for x in r.black_level_per_channel],
        'white': int(r.white_level),
        'wb': [float(x) for x in r.camera_whitebalance],
        'pattern': r.raw_pattern.tolist(),
    }))
