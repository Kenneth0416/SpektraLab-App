#!/usr/bin/env python3
"""LibRaw reference values for CR3 files, as pinned in engine/src/test/.../Cr3*Test.kt.

Needs rawpy (LibRaw). For each file prints the CRC32 of raw_image (little-endian uint16, the
whole frame incl. optical black), the visible area, LibRaw flip, CFA pattern (relative to the
visible area), black per channel (R, G, B, G2), white level, linear_max and as-shot WB.

    python3 tools/cr3_reference.py FILE.CR3 [...]

Synthetic files come from the tests: SPEKTRA_CR3_SYNTH_OUT=<dir> ./gradlew :engine:test --tests '*Cr3Test*'
"""
import sys
import zlib

import rawpy


def main(paths):
    for path in paths:
        try:
            with rawpy.imread(path) as r:
                m = r.raw_image.astype('<u2')
                s = r.sizes
                print(path)
                print('  raw %dx%d  crc32 0x%08x  min %d max %d' % (s.raw_width, s.raw_height, zlib.crc32(m.tobytes()), m.min(), m.max()))
                print('  visible left %d top %d  %dx%d  flip %d' % (s.left_margin, s.top_margin, s.width, s.height, s.flip))
                print('  pattern %s  black %s  white %d  linear_max %s' % (
                    r.raw_pattern.tolist(), r.black_level_per_channel, r.white_level, r.camera_white_level_per_channel))
                print('  as-shot wb %s' % (r.camera_whitebalance,))
        except Exception as e:  # truncated / unsupported: LibRaw refuses it
            print(path, 'LibRaw error:', e)


if __name__ == '__main__':
    main(sys.argv[1:])
