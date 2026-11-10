"""Bake spektrafilm data for the native Android engine.

Runs the spektrafilm reference implementation (Python) and exports, per stock,
everything the Kotlin port needs that is fixed for that stock:

* film profiles: density curves, sublayer curves, dye spectra, base density
  and the Hanatos 2025 spectral-upsampling LUT (``tc_lut``) as computed by
  spektrafilm's ``SpectralLUTService`` (sensitivity adaptation and input gamut
  compression included), plus the CAT16 sRGB -> XYZ matrix it uses;
* print profiles: spectral sensitivities, the fitted print density curves and
  dye spectra;
* scan LUTs: print (or film) CMY density -> linear sRGB, evaluated with
  spektrafilm's ``ScanningStage`` (viewing illuminant, CMFS, CAT, CAM16-UCS
  output gamut compression);
* global data: enlarger lamp, dichroic filters, neutral print filter database.

Everything user-adjustable (enlarger filtration, print exposure, film exposure,
DIR couplers, grain, halation, diffusion filters) is computed on device.

Usage::

    python tools/bake/bake_spektrafilm.py --spektrafilm /path/to/spektrafilm \
        --out app/src/main/assets/spektra
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np

from spk import SpkWriter

SCAN_LUT_SIZE = 33
NEG_SCAN_LUT_SIZE = 25


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spektrafilm", required=True, help="path to the spektrafilm checkout")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    sys.path.insert(0, os.path.join(args.spektrafilm, "src"))

    import colour
    from spektrafilm.runtime.params_builder import init_params, digest_params
    from spektrafilm.runtime.pipeline import SimulationPipeline
    from spektrafilm.profiles.io import load_profile
    from spektrafilm.model.illuminants import standard_illuminant
    from spektrafilm.model.color_filters import custom_dichroic_filters
    from spektrafilm.utils.spectral_upsampling import _illuminant_to_xy
    from spektrafilm.utils.morph_curves import _evaluate_fitted_density
    from spektrafilm.utils.io import read_neutral_print_filters
    from spektrafilm.config import SPECTRAL_SHAPE

    profiles_dir = os.path.join(args.spektrafilm, "src", "spektrafilm", "data", "profiles")
    stocks = sorted(f[:-5] for f in os.listdir(profiles_dir) if f.endswith(".json"))
    films, papers = [], []
    for s in stocks:
        p = load_profile(s)
        (films if p.info.stage == "filming" else papers).append(s)

    os.makedirs(os.path.join(args.out, "film"), exist_ok=True)
    os.makedirs(os.path.join(args.out, "paper"), exist_ok=True)

    def make_pipeline(film, paper, scan_film=False):
        params = init_params(film, paper)
        params.debug.lut_mode = True
        params.camera.auto_exposure = False
        params.io.input_color_space = "sRGB"
        params.io.input_cctf_decoding = False
        params.io.output_color_space = "sRGB"
        params.io.output_cctf_encoding = False
        params.io.scan_film = scan_film
        params.print_render.glare.active = False
        params = digest_params(params)
        return SimulationPipeline(params)

    def scan_lut(pipe, lo, hi, n):
        axes = [np.linspace(lo[c], hi[c], n) for c in range(3)]
        grid = np.stack(np.meshgrid(*axes, indexing="ij"), axis=-1).reshape(n * n, n, 3)
        rgb = pipe._scanning_stage._density_to_rgb(grid, use_lut=False)
        return np.asarray(rgb).reshape(n, n, n, 3)

    index = {"films": [], "papers": []}

    # ---------------------------------------------------------------- global
    g = SpkWriter()
    g.array("wavelengths", SPECTRAL_SHAPE.wavelengths)
    g.array("illuminant/TH-KG3", standard_illuminant("TH-KG3"))
    g.array("dichroic_filters", custom_dichroic_filters.filters)
    npf = read_neutral_print_filters()
    for paper, by_ill in npf.items():
        for film, cmy in by_ill.get("TH-KG3", {}).items():
            g.array(f"neutral_filters/{paper}/{film}", np.asarray(cmy, dtype=float))
    g.write(os.path.join(args.out, "global.spk"))

    # ----------------------------------------------------------------- films
    for film in films:
        prof = load_profile(film)
        target = prof.info.target_print or "kodak_portra_endura"
        pipe = make_pipeline(film, target, scan_film=False)
        sens = np.nan_to_num(10 ** np.asarray(prof.data.log_sensitivity))
        tc_lut = pipe._lut_service.get_filming_tc_lut(sens)

        ref_xy = _illuminant_to_xy(prof.info.reference_illuminant)
        m = colour.RGB_to_XYZ(np.eye(3), colourspace="sRGB", apply_cctf_decoding=False,
                              illuminant=ref_xy, chromatic_adaptation_transform="CAT16")
        m_srgb_to_xyz = np.asarray(m).T  # xyz = M @ rgb

        curves = np.asarray(prof.data.density_curves)
        norm_curves = curves - np.nanmin(curves, axis=0)
        lo = np.full(3, -0.08)
        hi = np.nanmax(norm_curves, axis=0) + 0.08

        scan_pipe = make_pipeline(film, target, scan_film=True)
        n = SCAN_LUT_SIZE if prof.is_positive else NEG_SCAN_LUT_SIZE
        lut = scan_lut(scan_pipe, lo, hi, n)

        w = SpkWriter()
        info = prof.info
        for k in ("stock", "name", "type", "use", "antihalation", "reference_illuminant",
                  "viewing_illuminant"):
            w.string(f"info/{k}", getattr(info, k))
        w.string("info/target_print", info.target_print or "")
        w.array("log_exposure", prof.data.log_exposure)
        w.array("density_curves", curves)
        w.array("density_curves_layers", prof.data.density_curves_layers)
        w.array("channel_density", prof.data.channel_density)
        w.array("base_density", prof.data.base_density)
        w.array("tc_lut", tc_lut, half=True)
        w.array("m_srgb_to_xyz", m_srgb_to_xyz)
        w.array("scan_lut_range", np.stack([lo, hi]))
        w.array("scan_lut", lut, half=True)
        w.write(os.path.join(args.out, "film", f"{film}.spk"))
        index["films"].append({"stock": film, "name": info.name, "type": info.type,
                               "use": info.use, "target_print": info.target_print or ""})
        print("film", film, tc_lut.shape, lut.shape)

    # ---------------------------------------------------------------- papers
    for paper in papers:
        prof = load_profile(paper)
        pipe = make_pipeline("kodak_portra_400", paper, scan_film=False)
        model_curves = _evaluate_fitted_density(prof.data.log_exposure, prof.data.density_curves_model,
                                                prof.info.type)
        lo = np.nanmin(model_curves, axis=0) - 0.02
        hi = np.nanmax(model_curves, axis=0) + 0.02
        lut = scan_lut(pipe, lo, hi, SCAN_LUT_SIZE)
        w = SpkWriter()
        info = prof.info
        for k in ("stock", "name", "type", "viewing_illuminant"):
            w.string(f"info/{k}", getattr(info, k))
        w.array("log_exposure", prof.data.log_exposure)
        w.array("log_sensitivity", prof.data.log_sensitivity)
        w.array("density_curves", model_curves)
        w.array("channel_density", prof.data.channel_density)
        w.array("base_density", prof.data.base_density)
        w.array("scan_lut_range", np.stack([lo, hi]))
        w.array("scan_lut", lut, half=True)
        w.write(os.path.join(args.out, "paper", f"{paper}.spk"))
        index["papers"].append({"stock": paper, "name": info.name, "type": info.type,
                                "support": info.support})
        print("paper", paper, lut.shape)

    with open(os.path.join(args.out, "index.json"), "w") as f:
        json.dump(index, f, indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
