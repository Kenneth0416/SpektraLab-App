"""Cross-check the Kotlin CR2 reader + RawDeveloper against a LibRaw (rawpy) render.

    SPEKTRA_RAW_SAMPLES=/path/to/samples ./gradlew :engine:test --tests '*Cr2*' -Dspektra.rawdump=/tmp/cr2check
    python tools/cr2check.py /tmp/cr2check /path/to/samples/CR2

Cr2Test writes <name>.dev for every real sample it decodes: an int32 LE width, height, the
crop origin relative to LibRaw's visible area (x, y, in developed pixels) and then the binned
(half-size) developed image as RGBA float16. Here the same file is rendered by LibRaw at half
size (linear sRGB, camera WB, no auto-brightness, highlight clip), both are averaged over
16x16 blocks and compared after matching the overall exposure (LibRaw normalises WB and
maximum its own way).
"""
import os
import sys
import numpy as np
import rawpy


def blocks(img, n=16):
    h, w = (img.shape[0] // n) * n, (img.shape[1] // n) * n
    return img[:h, :w].reshape(h // n, n, w // n, n, 3).mean((1, 3))


def main(dump_dir, sample_dir):
    for f in sorted(os.listdir(dump_dir)):
        if not f.endswith(".dev"):
            continue
        raw_path = os.path.join(sample_dir, f[:-4])
        hdr = np.fromfile(os.path.join(dump_dir, f), dtype="<i4", count=4)
        w, h, ox, oy = map(int, hdr)
        mine = np.fromfile(os.path.join(dump_dir, f), dtype="<f2", offset=16).astype(np.float64).reshape(h, w, 4)[..., :3]
        with rawpy.imread(raw_path) as r:
            ref = r.postprocess(gamma=(1, 1), no_auto_bright=True, use_camera_wb=True, output_bps=16, half_size=True,
                                output_color=rawpy.ColorSpace.sRGB, highlight_mode=rawpy.HighlightMode.Clip,
                                user_flip=0).astype(np.float64) / 65535
        ref = ref[oy:oy + h, ox:ox + w]
        hh, ww = min(h, ref.shape[0]), min(w, ref.shape[1])
        a, b = blocks(mine[:hh, :ww]), blocks(ref[:hh, :ww])
        scale = (a * b).sum() / (b * b).sum()
        rel = np.abs(a - b * scale) / max(a.mean(), 1e-6)
        ca = a / np.maximum(a.sum(-1, keepdims=True), 1e-9)
        cb = b / np.maximum(b.sum(-1, keepdims=True), 1e-9)
        lit = b.mean(-1) > 0.02
        print(f)
        print("  size ours %dx%d, LibRaw half-size %dx%d (after crop)" % (w, h, ref.shape[1], ref.shape[0]))
        print("  exposure scale LibRaw -> ours: %.4f" % scale)
        print("  block error / mean level: mean %.4f, p99 %.4f" % (rel.mean(), np.percentile(rel, 99)))
        unclipped = b.max(-1) * scale < 0.8
        print("  same on blocks below 80%% of white (%d%% of blocks): mean %.4f, p99 %.4f"
              % (100 * unclipped.mean(), rel[unclipped].mean(), np.percentile(rel[unclipped], 99)))
        print("  mean chromaticity ours", ca[lit].mean(0).round(4), "LibRaw", cb[lit].mean(0).round(4),
              " max block chroma diff %.4f" % np.abs(ca - cb)[lit].max())


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
