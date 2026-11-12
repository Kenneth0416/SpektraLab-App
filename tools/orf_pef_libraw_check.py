#!/usr/bin/env python3
"""Decode the synthetic ORF / PEF files written by OrfTest / PefTest (env SPEKTRA_SYNTH_DUMP=dir)
with LibRaw (rawpy) and print what the tests pin: CRC32 of the little-endian raw_image,
black level per channel, white level, WB, inset crop.

  SPEKTRA_SYNTH_DUMP=/tmp/synth ./gradlew :engine:test --tests '*OrfTest*' --tests '*PefTest*'
  /home/user/rawref/venv/bin/python -I tools/orf_pef_libraw_check.py /tmp/synth
"""
import sys, zlib, glob, os
import numpy as np
import rawpy

for path in sorted(glob.glob(os.path.join(sys.argv[1], '*'))):
    if not path.lower().endswith(('.orf', '.pef')):
        continue
    try:
        with rawpy.imread(path) as r:
            m = r.raw_image
            s = r.sizes
            print(os.path.basename(path), 'crc=0x%08x' % zlib.crc32(m.astype('<u2').tobytes()), 'shape=%s' % (m.shape,),
                  'black=%s' % list(r.black_level_per_channel), 'white=%d' % r.white_level,
                  'wb=%s' % [round(float(x), 5) for x in r.camera_whitebalance],
                  'pattern=%s' % r.raw_pattern.tolist(),
                  'crop=%s' % ((s.crop_left_margin, s.crop_top_margin, s.crop_width, s.crop_height),),
                  'visible=%s' % ((s.left_margin, s.top_margin, s.width, s.height),), 'flip=%d' % s.flip)
    except Exception as e:
        print(os.path.basename(path), 'ERROR', e)
