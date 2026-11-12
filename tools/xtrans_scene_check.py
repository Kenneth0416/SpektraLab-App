#!/usr/bin/env python3
"""Score the engine's X-Trans demosaic and LibRaw's on the synthetic scene of XTransTest.

usage: xtrans_scene_check.py DIR [NAME]
DIR holds NAME.RAF, NAME_truth.f32 and NAME_ours.f32 (written by XTransTest with SPEKTRA_RAF_DUMP=DIR;
NAME defaults to xtrans_scene).  LibRaw renders NAME.RAF in camera RGB (output_color=raw, linear,
WB 1:1:1, no auto-bright) with each X-Trans demosaic it has; prints PSNR (peak 1) against the truth for
LibRaw and for the engine, over the pixels at least 3 from the border.
Run with the rawpy venv:  /home/user/rawref/venv/bin/python -I tools/xtrans_scene_check.py DIR
"""
import os
import sys

import numpy as np
import rawpy


def load(p):
    w, h = np.fromfile(p, dtype='<i4', count=2)
    return np.fromfile(p, dtype='<f4', offset=8).reshape(int(h), int(w), 3).astype(np.float64)


def psnr(a, t, b=3):
    d = a[b:-b, b:-b] - t[b:-b, b:-b]
    return 10 * np.log10(1 / np.mean(d * d))


d = sys.argv[1]
name = sys.argv[2] if len(sys.argv) > 2 else 'xtrans_scene'
truth = load(os.path.join(d, name + '_truth.f32'))
ours = load(os.path.join(d, name + '_ours.f32'))
print('engine          : %.2f dB' % psnr(ours, truth))
for label, alg in (('LibRaw linear', rawpy.DemosaicAlgorithm.LINEAR), ('LibRaw 1-pass', rawpy.DemosaicAlgorithm.VNG),
                   ('LibRaw 3-pass', rawpy.DemosaicAlgorithm.AHD)):
    with rawpy.imread(os.path.join(d, name + '.RAF')) as r:
        img = r.postprocess(demosaic_algorithm=alg, output_color=rawpy.ColorSpace.raw, use_camera_wb=True,
                            no_auto_bright=True, gamma=(1, 1), output_bps=16, user_flip=0,
                            highlight_mode=rawpy.HighlightMode.Clip).astype(np.float64) / 65535
    print('%-16s: %.2f dB' % (label, psnr(img[:truth.shape[0], :truth.shape[1]], truth)))
