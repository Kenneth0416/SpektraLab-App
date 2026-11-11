"""Cross-check the Kotlin DNG reader/developer against LibRaw (rawpy).

    ./gradlew :engine:test --tests '*DngTest*' -Dspektra.rawdump=/tmp/rawcheck
    python tools/rawcheck.py /tmp/rawcheck
"""
import sys
import numpy as np
import rawpy

d = sys.argv[1]
raw = rawpy.imread(d + "/synthetic.dng")
print("LibRaw accepts the file:", raw.raw_image.shape, "black", raw.black_level_per_channel, "white", raw.white_level,
      "pattern", raw.raw_pattern.tolist())
ref = raw.postprocess(gamma=(1, 1), no_auto_bright=True, use_camera_wb=True, output_bps=16,
                      output_color=rawpy.ColorSpace.sRGB, demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                      highlight_mode=rawpy.HighlightMode.Clip, user_flip=0).astype(np.float64) / 65535
lines = open(d + "/developed.txt").read().split("\n")
w, h = map(int, lines[0].split())
mine = np.array([[float(v) for v in l.split()] for l in lines[1:1 + w * h]]).reshape(h, w, 3)
m = 6
a, b = mine[m:-m, m:-m], ref[m:h - m, m:w - m]
# LibRaw scales exposure by its own white-balance normalisation; compare after matching the mean
scale = (a * b).sum() / (b * b).sum()
print("exposure scale LibRaw -> ours: %.4f" % scale)
print("max abs diff (after scale): %.4f, mean: %.5f" % (np.abs(a - b * scale).max(), np.abs(a - b * scale).mean()))
print("mean chromaticity ours ", (a.mean((0, 1)) / a.mean()).round(4), " LibRaw", (b.mean((0, 1)) / b.mean()).round(4))
