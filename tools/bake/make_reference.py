"""Generate reference vectors from spektrafilm for the Kotlin engine tests.

Each case runs the spektrafilm pipeline with spatial and stochastic effects
off (the per-pixel part of the model) and stores the input colours together
with every intermediate tap, so the Kotlin port can be checked stage by stage.

    python tools/bake/make_reference.py --spektrafilm /path/to/spektrafilm \
        --out engine/src/test/resources/reference
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

from spk import SpkWriter

CASES = [
    dict(name="portra400_endura", film="kodak_portra_400", paper="kodak_portra_endura"),
    dict(name="ektar_supra_shifted", film="kodak_ektar_100", paper="kodak_supra_endura",
         ev=1.0, print_exposure=1.3, y_shift=10.0, m_shift=-5.0),
    dict(name="vision500t_2383", film="kodak_vision3_500t", paper="kodak_2383", ev=-1.0),
    dict(name="velvia_scan", film="fujifilm_velvia_100", paper="kodak_portra_endura", scan_film=True),
    dict(name="gold_premier_gamma", film="kodak_gold_200", paper="kodak_endura_premier", gamma=1.15),
    dict(name="portra400_negative_scan", film="kodak_portra_400", paper="kodak_portra_endura",
         scan_film=True),
    dict(name="portra400_no_couplers", film="kodak_portra_400", paper="kodak_portra_endura",
         couplers=False),
    dict(name="c200_crystal", film="fujifilm_c200", paper="fujifilm_crystal_archive_typeii"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spektrafilm", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    sys.path.insert(0, os.path.join(args.spektrafilm, "src"))
    from spektrafilm.runtime.params_builder import init_params, digest_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    os.makedirs(args.out, exist_ok=True)
    rng = np.random.default_rng(7)
    ramp = np.repeat(np.geomspace(0.002, 4.0, 24)[:, None], 3, axis=1)
    rand = rng.random((120, 3)) ** 2.2
    sat = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [1, 1, 0], [0, 1, 1], [1, 0, 1],
                    [0.8, 0.3, 0.1], [0.1, 0.4, 0.9], [0.9, 0.6, 0.5], [0.05, 0.3, 0.05]], float)
    rgb = np.concatenate([ramp, rand, sat, sat * 3.0, sat * 0.1])

    for case in CASES:
        params = init_params(case["film"], case["paper"])
        params.camera.auto_exposure = False
        params.debug.deactivate_spatial_effects = True
        params.debug.deactivate_stochastic_effects = True
        params.io.input_color_space = "sRGB"
        params.io.input_cctf_decoding = False
        params.io.output_color_space = "sRGB"
        params.io.output_cctf_encoding = False
        params.io.scan_film = case.get("scan_film", False)
        params.camera.exposure_compensation_ev = case.get("ev", 0.0)
        params.enlarger.print_exposure = case.get("print_exposure", 1.0)
        params.enlarger.y_filter_shift = case.get("y_shift", 0.0)
        params.enlarger.m_filter_shift = case.get("m_shift", 0.0)
        params.film_render.density_curve_gamma = case.get("gamma", 1.0)
        params.film_render.dir_couplers.active = case.get("couplers", True)
        params = digest_params(params)
        pipe = SimulationPipeline(params)
        img = rgb[:, None, :]
        w = SpkWriter()
        w.string("case", json.dumps(case))
        w.array("rgb_in", rgb)
        taps = ["log_e_film", "cmy_film"] + ([] if params.io.scan_film else ["log_e_print", "cmy_print"])
        for tap in taps:
            w.array(tap, np.asarray(pipe.process(img, collect=tap))[:, 0, :])
        w.array("rgb_out", np.asarray(pipe.process(img))[:, 0, :])
        w.array("filters_cmy", [params.enlarger.c_filter_neutral, params.enlarger.m_filter_neutral,
                                params.enlarger.y_filter_neutral])
        w.write(os.path.join(args.out, case["name"] + ".spk"))
        print(case["name"], "ok")


if __name__ == "__main__":
    main()
