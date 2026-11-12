#!/usr/bin/env python3
"""Compare the engine's development of real raw samples with LibRaw's, for every reader at once.

    SPEKTRA_RAW_SAMPLES=... ./gradlew :engine:test --tests '*RawReaderTest.dumpDevelopedSamplesForLibRaw' \\
        -Dspektra.rawdump=/tmp/devcmp --rerun
    /home/user/rawref/venv/bin/python -I tools/develop_compare.py /tmp/devcmp

For each <name>.json / <name>.f32 written by the test: prints the levels and crop next to LibRaw's, then
LibRaw renders the file linear (camera WB, sRGB, no auto-bright, highlights clipped, no flip), its image is
box-averaged to the engine's cells over the engine's crop, and the per-channel mean ratios (ours / LibRaw)
over pixels where no channel is near clipping are printed. Differences come from the levels (printed), from
the colour model (the engine adapts the as-shot white with Bradford in XYZ, see RawColor; LibRaw scales in
camera space, and the two agree only for daylight white balance) and, a little, from the demosaic.
"""
import glob
import json
import os
import sys

import numpy as np
import rawpy


def main():
    d = sys.argv[1]
    for js in sorted(glob.glob(os.path.join(d, '*.json'))):
        meta = json.load(open(js))
        hdr = np.fromfile(js[:-5] + '.f32', dtype='<i4', count=2)
        w, h = int(hdr[0]), int(hdr[1])
        ours = np.fromfile(js[:-5] + '.f32', dtype='<f4', offset=8).reshape(h, w, 3).astype(np.float64)
        with rawpy.imread(meta['file']) as r:
            s = r.sizes
            ref = r.postprocess(use_camera_wb=True, no_auto_bright=True, gamma=(1, 1), output_bps=16, user_flip=0,
                                output_color=rawpy.ColorSpace.sRGB, highlight_mode=rawpy.HighlightMode.Clip).astype(np.float64) / 65535
            lr = dict(black=list(r.black_level_per_channel), white=r.white_level,
                      wb=[round(v / r.camera_whitebalance[1], 4) for v in r.camera_whitebalance[:3]],
                      visible=[s.left_margin, s.top_margin, s.width, s.height],
                      crop=[s.crop_left_margin, s.crop_top_margin, s.crop_width, s.crop_height])
        cx, cy, cw, ch = meta['crop']
        k = meta['cell']
        ox, oy = cx - lr['visible'][0], cy - lr['visible'][1]
        name = os.path.basename(js)[:-5]
        print('%s\n  ours   crop %s black %s white %g wb %s' % (name, meta['crop'], [round(b, 2) for b in meta['black']], meta['white'],
                                                              [round(v, 4) for v in meta['wb']]))
        print('  LibRaw visible %s crop %s black %s white %d wb %s' % (lr['visible'], lr['crop'], lr['black'], lr['white'], lr['wb']))
        if ox < 0 or oy < 0 or ox + w * k > ref.shape[1] or oy + h * k > ref.shape[0]:
            print('  crop outside LibRaw image, skipped')
            continue
        b = ref[oy:oy + h * k, ox:ox + w * k].reshape(h, k, w, k, 3).mean(axis=(1, 3))
        mx = ref[oy:oy + h * k, ox:ox + w * k].reshape(h, k, w, k, 3).max(axis=(1, 3))
        ok = (mx.max(axis=2) < 0.9) & (b.min(axis=2) > 0.002) & (ours.max(axis=2) < 0.9)
        ok[:2, :] = ok[-2:, :] = False
        ok[:, :2] = ok[:, -2:] = False
        a, bb = ours[ok], b[ok]
        ratio = a.mean(axis=0) / bb.mean(axis=0)
        print('  pixels %d  mean ratio ours/LibRaw R %.4f G %.4f B %.4f   mean|diff|/mean %.4f' %
              (ok.sum(), ratio[0], ratio[1], ratio[2], np.abs(a - bb).mean() / bb.mean()))


if __name__ == '__main__':
    main()
