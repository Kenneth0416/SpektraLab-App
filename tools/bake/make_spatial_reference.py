"""Reference data for the spatial/stochastic parts of the Kotlin engine.

* impulse responses of spektrafilm's halation and diffusion filters
  (encircled energy curves per channel)
* grain statistics (mean / std of developed density on flat patches)

    python tools/bake/make_spatial_reference.py --spektrafilm /path/to/spektrafilm \
        --out engine/src/test/resources/spatial
"""
from __future__ import annotations

import argparse
import copy
import os
import sys

import numpy as np

from spk import SpkWriter

RADII = np.array([1, 2, 3, 5, 8, 12, 20, 32, 50, 80, 120, 200])


def encircled(img):
    n = img.shape[0]
    c = n // 2
    y, x = np.mgrid[:n, :n]
    r = np.sqrt((x - c) ** 2 + (y - c) ** 2)
    out = np.zeros((len(RADII), 3))
    for i, R in enumerate(RADII):
        m = r <= R
        out[i] = img[m].sum(axis=0)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spektrafilm", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    sys.path.insert(0, os.path.join(args.spektrafilm, "src"))
    from spektrafilm.runtime.params_builder import init_params, digest_params
    from spektrafilm.model.diffusion import apply_halation_um, apply_diffusion_filter_um
    from spektrafilm.model.develop import develop
    os.makedirs(args.out, exist_ok=True)

    n = 801
    impulse = np.zeros((n, n, 3))
    impulse[n // 2, n // 2, :] = 1.0

    w = SpkWriter()
    w.array("radii", RADII)
    # halation, portra 400 (still, strong AH) with amplified strength to make the tail measurable
    params = digest_params(init_params("kodak_portra_400", "kodak_portra_endura"))
    hal = copy.deepcopy(params.film_render.halation)
    hal.halation_amount = 4.0
    for px in (5.0, 20.0):
        out = apply_halation_um(impulse.copy(), hal, px)
        w.array(f"halation_px{px:g}", encircled(out))
    # diffusion filters
    for fam in ("black_pro_mist", "cinebloom", "glimmerglass", "pro_mist"):
        df = copy.deepcopy(params.camera.diffusion_filter)
        df.active = True
        df.filter_family = fam
        df.strength = 1.0
        out = apply_diffusion_filter_um(impulse.copy(), df, 20.0)
        w.array(f"diffusion_{fam}_px20", encircled(out))

    # grain statistics on flat patches (develop with couplers off, grain on)
    p = digest_params(init_params("kodak_portra_400", "kodak_portra_endura"))
    p.film_render.dir_couplers.active = False
    p.film_render.grain.blur = 0.0
    film = p.film
    le = film.data.log_exposure
    levels = np.array([-1.5, -0.8, 0.0, 0.8, 1.6])
    for px in (6.0, 20.0):
        stats = []
        for lv in levels:
            log_raw = np.full((160, 160, 3), lv)
            dens = develop(log_raw, px, le, film.data.density_curves, film.data.density_curves_layers,
                           p.film_render.dir_couplers, p.film_render.grain, film.info.type)
            p0 = copy.deepcopy(p.film_render.grain)
            p0.active = False
            dens0 = develop(log_raw[:2, :2], px, le, film.data.density_curves, film.data.density_curves_layers,
                            p.film_render.dir_couplers, p0, film.info.type)
            stats.append(np.concatenate([dens0[0, 0], dens.reshape(-1, 3).mean(0), dens.reshape(-1, 3).std(0)]))
        w.array(f"grain_px{px:g}", np.array(stats))
    w.array("grain_levels", levels)
    w.write(os.path.join(args.out, "spatial.spk"))
    print("ok")


if __name__ == "__main__":
    main()
