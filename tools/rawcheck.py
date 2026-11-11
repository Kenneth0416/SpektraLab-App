"""Cross-check the Kotlin DNG reader/developer against LibRaw (rawpy).

    ./gradlew :engine:test --tests '*Dng*' -Dspektra.rawdump=/tmp/rawcheck
    python tools/rawcheck.py /tmp/rawcheck

For every <name>.dng in the directory: if <name>.raw16 exists (the samples our reader
decoded, uint16 little endian), it must equal LibRaw's raw_image exactly; if a developed
image exists (<name>.txt, or developed.txt for synthetic.dng) its colour is compared with
LibRaw's linear sRGB development.
"""
import glob
import os
import sys
import numpy as np
import rawpy


def compare_developed(raw, txt):
    ref = raw.postprocess(gamma=(1, 1), no_auto_bright=True, use_camera_wb=True, output_bps=16,
                          output_color=rawpy.ColorSpace.sRGB, demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                          highlight_mode=rawpy.HighlightMode.Clip, user_flip=0).astype(np.float64) / 65535
    lines = open(txt).read().split("\n")
    w, h = map(int, lines[0].split())
    mine = np.array([[float(v) for v in l.split()] for l in lines[1:1 + w * h]]).reshape(h, w, 3)
    m = 6
    a = mine[m:-m, m:-m]
    b = ref[m:m + a.shape[0], m:m + a.shape[1]]
    # LibRaw scales exposure by its own white-balance normalisation; compare after matching the mean
    scale = (a * b).sum() / (b * b).sum()
    print("  exposure scale LibRaw -> ours: %.4f" % scale)
    print("  max abs diff (after scale): %.4f, mean: %.5f" % (np.abs(a - b * scale).max(), np.abs(a - b * scale).mean()))
    print("  mean chromaticity ours ", (a.mean((0, 1)) / a.mean()).round(4), " LibRaw", (b.mean((0, 1)) / b.mean()).round(4))


d = sys.argv[1]
ok = True
for path in sorted(glob.glob(os.path.join(d, "*.dng"))):
    name = os.path.splitext(path)[0]
    raw = rawpy.imread(path)
    print(os.path.basename(path), "- LibRaw:", raw.raw_image.shape, "black", raw.black_level_per_channel,
          "white", raw.white_level, "pattern", raw.raw_pattern.tolist() if raw.raw_pattern is not None else "linear RGB")
    if os.path.exists(name + ".raw16"):
        mine = np.fromfile(name + ".raw16", dtype="<u2").reshape(raw.raw_image.shape)
        diff = int(np.abs(mine.astype(np.int64) - raw.raw_image.astype(np.int64)).max())
        print("  raw samples vs LibRaw: max diff", diff)
        ok &= diff == 0
    txt = name + ".txt" if os.path.exists(name + ".txt") else os.path.join(d, "developed.txt")
    if os.path.basename(path) == "synthetic.dng" or os.path.exists(name + ".txt"):
        compare_developed(rawpy.imread(path), txt)
if os.path.exists(d + "/merged.dng"):
    raw = rawpy.imread(d + "/merged.dng")
    ref = raw.raw_image.astype(np.float64) / raw.white_level
    lines = open(d + "/merged_camera.txt").read().split("\n")
    w, h = map(int, lines[0].split())
    mine = np.array([[float(v) for v in l.split()] for l in lines[1:1 + w * h]]).reshape(h, w, 3)
    print("merged Linear DNG: LibRaw reads", ref.shape, "camera RGB max abs diff %.5f, mean %.6f"
          % (np.abs(mine - ref).max(), np.abs(mine - ref).mean()))

print("raw samples identical to LibRaw" if ok else "RAW SAMPLE MISMATCH")
sys.exit(0 if ok else 1)
