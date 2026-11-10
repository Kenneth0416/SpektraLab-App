# Bake spektrafilm data

The Android engine (`engine/`) is a Kotlin port of the spektrafilm runtime. The
parts that are fixed per stock and need colour-science (spectral upsampling LUT,
CAT16 matrices, scanner with CAM16-UCS gamut compression) are produced by running
spektrafilm itself and stored in `app/src/main/assets/spektra/`.

Baked from spektrafilm commit `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` (v0.3.4).

```bash
git clone https://github.com/andreavolpato/spektrafilm /tmp/spektrafilm
python3 -m venv /tmp/spk-venv && . /tmp/spk-venv/bin/activate
pip install "numpy~=2.4" "scipy~=1.17" "colour-science~=0.4.6" "scikit-image~=0.26" \
    "matplotlib~=3.10" "opt-einsum~=3.4.0" "numba~=0.64" pyfftw exiv2 rawpy lensfunpy OpenImageIO
cd tools/bake
python bake_spektrafilm.py --spektrafilm /tmp/spektrafilm --out ../../app/src/main/assets/spektra
python make_reference.py --spektrafilm /tmp/spektrafilm --out ../../engine/src/test/resources/reference
python make_spatial_reference.py --spektrafilm /tmp/spektrafilm --out ../../engine/src/test/resources/spatial
```

- `bake_spektrafilm.py` — film/paper data, `tc_lut`, scan LUTs, enlarger lamp,
  dichroic filters, neutral print filtration database (`*.spk`, see `spk.py`).
- `make_reference.py` — per-pixel pipeline taps for the engine tests.
- `make_spatial_reference.py` — halation / diffusion filter impulse responses and
  grain statistics for the engine tests.

Profile data and everything derived from it is CC BY-SA 4.0
(`app/src/main/assets/spektra/SPEKTRAFILM_LICENSE.txt`).
