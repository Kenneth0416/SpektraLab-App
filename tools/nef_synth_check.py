#!/usr/bin/env python3
"""Check the synthetic NEF files written by NefTest against LibRaw (rawpy).

usage: python -I tools/nef_synth_check.py DIR

DIR is where `SPEKTRA_NEF_SYNTH_OUT=DIR ./gradlew :engine:test --tests '*NefTest*'` wrote the
files and `expected.tsv`. For every file this compares LibRaw's raw_image (CRC32 of little-endian
uint16), the per-position black level, white level, camera WB (R, B, G2 over G) and the visible / inset
crop with what the Kotlin test expects. Prints one line per file and exits non-zero on mismatch.
"""
import sys
import zlib

import numpy as np
import rawpy


def main():
    d = sys.argv[1]
    bad = 0
    lines = open(f"{d}/expected.tsv").read().strip().split("\n")[1:]
    for line in lines:
        name, crc, black, white, cam_mul, crop = line.split("\t")
        cam_mul = [float(v) for v in cam_mul.split(",")]
        black = [int(v) for v in black.split(",")]
        crop = [int(v) for v in crop.split(",")]
        problems = []
        try:
            with rawpy.imread(f"{d}/{name}") as r:
                img = np.ascontiguousarray(r.raw_image)
                got_crc = zlib.crc32(img.astype("<u2").tobytes())
                if got_crc != int(crc):
                    problems.append(f"mosaic crc {got_crc} != {crc}")
                s = r.sizes
                colors = r.raw_colors  # per raw pixel colour index into black_level_per_channel
                bl = [r.black_level_per_channel[colors[i // 2, i % 2]] for i in range(4)]
                if bl != black:
                    problems.append(f"black {bl} != {black}")
                if r.white_level != int(white):
                    problems.append(f"white {r.white_level} != {white}")
                cw = r.camera_whitebalance  # R G B G2, any scale
                for k in (0, 2, 3):
                    got, exp = cw[k] / cw[1], cam_mul[k] / cam_mul[1]
                    if abs(got - exp) > 1e-4:
                        problems.append(f"cam_mul[{k}]/G {got:.5f} != {exp:.5f}")
                if s.crop_width:
                    lc = [s.crop_left_margin, s.crop_top_margin, s.crop_width, s.crop_height]
                else:
                    lc = [s.left_margin, s.top_margin, s.width, s.height]
                if lc != crop:
                    problems.append(f"crop {lc} != {crop}")
        except Exception as e:  # noqa: BLE001 - report and continue
            problems.append(f"LibRaw failed: {e}")
        print(("OK   " if not problems else "FAIL ") + name + ("" if not problems else ": " + "; ".join(problems)))
        bad += bool(problems)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
