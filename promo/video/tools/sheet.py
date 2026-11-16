"""Contact sheet of preview stills: python3 tools/sheet.py out/sheet.jpg 33 36.5 ... (seconds, after `node render.mjs --stills ...`)."""
import sys, os
from PIL import Image
out, times = sys.argv[1], sys.argv[2:]
d = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'out', 'stills')
ims = [Image.open(os.path.join(d, f't{float(t):06.2f}.jpg')).resize((960, 540), Image.LANCZOS) for t in times]
rows = (len(ims) + 1) // 2
s = Image.new('RGB', (1920, rows * 540))
for i, im in enumerate(ims):
    s.paste(im, ((i % 2) * 960, (i // 2) * 540))
s.save(out, quality=86)
