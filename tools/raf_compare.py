#!/usr/bin/env python3
"""Compare an engine-developed X-Trans frame with a LibRaw (rawpy) render.

usage: raf_compare.py RAW_FILE DUMP.f32 [--crop X Y W H] [--bin K] [--png OUT_PREFIX]

DUMP.f32 is written by RafSampleTest.xt2Develop (SPEKTRA_RAF_DUMP=dir): little-endian int32 width,
height, then float32 linear sRGB triplets, upright, covering the RawImage crop.  LibRaw renders the
same file linear (gamma 1, no auto-bright, camera WB, sRGB primaries, highlights clipped) with its
default X-Trans demosaic (Markesteijn 3-pass); its output covers the visible area, so --crop gives the
engine crop inside it (default 18 18 5998 3998, the X-T2) and --bin K box-averages LibRaw's image to
compare a binned preview.  Prints per-channel mean ratio and error statistics over unclipped pixels.
Run with the rawpy venv:  /home/user/rawref/venv/bin/python -I tools/raf_compare.py ...
"""
import sys

import numpy as np
import rawpy


def main():
    a = sys.argv[1:]
    if len(a) < 2:
        raise SystemExit(__doc__)
    raw_path, dump = a[0], a[1]
    crop = (18, 18, 5998, 3998)
    k = 1
    png = None
    if '--crop' in a:
        i = a.index('--crop')
        crop = tuple(int(v) for v in a[i + 1:i + 5])
    if '--bin' in a:
        k = int(a[a.index('--bin') + 1])
    if '--png' in a:
        png = a[a.index('--png') + 1]
    hdr = np.fromfile(dump, dtype='<i4', count=2)
    w, h = int(hdr[0]), int(hdr[1])
    ours = np.fromfile(dump, dtype='<f4', offset=8).reshape(h, w, 3).astype(np.float64)
    with rawpy.imread(raw_path) as r:
        ref = r.postprocess(use_camera_wb=True, no_auto_bright=True, gamma=(1, 1), output_bps=16,
                            output_color=rawpy.ColorSpace.sRGB, highlight_mode=rawpy.HighlightMode.Clip,
                            user_flip=0).astype(np.float64) / 65535.0
    x, y, cw, ch = crop
    ref = ref[y:y + ch, x:x + cw]
    if k > 1:
        hh, ww = ch // k, cw // k
        ref = ref[:hh * k, :ww * k].reshape(hh, k, ww, k, 3).mean(axis=(1, 3))
    ref = ref[:h, :w]
    assert ref.shape == ours.shape, (ref.shape, ours.shape)
    ok = (ref.max(axis=2) < 0.9) & (ours.max(axis=2) < 0.9) & (ref.min(axis=2) > 0.002)
    print('pixels compared: %d of %d' % (ok.sum(), ok.size))
    for c, name in enumerate('RGB'):
        o, l = ours[..., c][ok], ref[..., c][ok]
        print('%s: mean ours %.5f libraw %.5f ratio %.4f' % (name, o.mean(), l.mean(), o.mean() / l.mean()))
    d = ours[ok] - ref[ok]
    rel = np.abs(d).mean() / ref[ok].mean()
    # error in a perceptual-ish space (sqrt), as PSNR with peak 1
    se = (np.sqrt(np.clip(ours[ok], 0, None)) - np.sqrt(np.clip(ref[ok], 0, None))) ** 2
    print('mean |diff| / mean = %.4f   PSNR(sqrt domain) = %.2f dB' % (rel, 10 * np.log10(1 / se.mean())))
    # chroma difference: compare r/g and b/g on brighter pixels
    br = ok & (ref[..., 1] > 0.02)
    for c, name in ((0, 'R/G'), (2, 'B/G')):
        q = np.median(ours[..., c][br] / np.maximum(ours[..., 1][br], 1e-6)) / np.median(ref[..., c][br] / ref[..., 1][br])
        print('%s median ratio ours/libraw: %.4f' % (name, q))
    # separate demosaic from colour pipeline differences (Bradford vs camera-space WB): fit a 3x3 map
    sub = np.flatnonzero(ok.ravel())[::17]
    A = ours.reshape(-1, 3)[sub]; B = ref.reshape(-1, 3)[sub]
    M, *_ = np.linalg.lstsq(A, B, rcond=None)
    fit = ours.reshape(-1, 3) @ M
    se2 = (np.sqrt(np.clip(fit.reshape(ours.shape)[ok], 0, None)) - np.sqrt(np.clip(ref[ok], 0, None))) ** 2
    print('after best-fit 3x3 colour map: mean |diff| / mean = %.4f   PSNR(sqrt domain) = %.2f dB' % (
        np.abs(fit.reshape(ours.shape)[ok] - ref[ok]).mean() / ref[ok].mean(), 10 * np.log10(1 / se2.mean())))
    print('fitted map (ours -> libraw):', np.round(M.T, 4).tolist())
    if png:
        try:
            from PIL import Image
        except ImportError:
            return
        for arr, suf in ((ours, 'ours'), (ref, 'libraw')):
            v = np.clip(arr * 4, 0, 1) ** (1 / 2.2)
            Image.fromarray((v * 255).astype(np.uint8)).save('%s_%s.png' % (png, suf))


if __name__ == '__main__':
    main()
