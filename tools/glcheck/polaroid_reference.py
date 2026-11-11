"""Reference frames of spektrafilm_gui's polaroid animation for tools/glcheck/polaroid.js.

    python polaroid_reference.py --spektrafilm /path/to/spektrafilm --out /tmp/glcheck
"""
import argparse, json, os, sys
import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("--spektrafilm", required=True)
ap.add_argument("--out", required=True)
a = ap.parse_args()
sys.path.insert(0, os.path.join(a.spektrafilm, "src"))
from spektrafilm_gui.polaroid_animation import render_polaroid_frame, layer_coefficients

rng = np.random.default_rng(3)
img = (rng.random((24, 32, 3)) ** 1.5 * 255).astype(np.uint8)
img[0, 0] = (255, 255, 255); img[0, 1] = (3, 3, 3)
ts = [0.0, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0]
frames = [render_polaroid_frame(img, t).tolist() for t in ts]
json.dump({"img": img.tolist(), "ts": ts, "frames": frames,
           "coef": [layer_coefficients(i / 10).tolist() for i in range(11)]},
          open(os.path.join(a.out, "polaroid_ref.json"), "w"))
print("ok")
