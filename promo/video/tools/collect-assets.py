"""Copies the app imagery the video and the download page use out of the promo work directory.

    python3 tools/collect-assets.py <work>      # <work> = the -Dpromo.dir of PromoScreenshotTest (png/, png_overlay/, shots/, src/)

Screens come from app/src/test/.../PromoScreenshotTest.kt, film renders from tools/promo/render.js.
"""
import json, os, sys
from PIL import Image

work = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
app = os.path.join(here, '..', 'assets', 'app')
site = os.path.join(here, '..', '..', 'site', 'img')
os.makedirs(app, exist_ok=True); os.makedirs(site, exist_ok=True)


def load(rel):
    return Image.open(os.path.join(work, rel)).convert('RGB')


def save(im, name, long_edge=None, q=92, dirs=(app,)):
    if long_edge and max(im.size) > long_edge:
        s = long_edge / max(im.size)
        im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    for d in dirs:
        im.save(os.path.join(d, name), quality=q, subsampling=0, optimize=True)


# ---- screens (the app's own Compose UI)
SHOTS = ['library', 'library_select', 'library_export_sheet', 'library_recipes_sheet', 'editor_film_portra400', 'editor_film_gold200',
         'editor_film_velvia100', 'editor_film_vision500t', 'editor_paper_2383', 'editor_halation', 'editor_grain',
         'editor_diffusion_cinebloom', 'editor_filter_yellow', 'editor_frame_filmedge_date', 'editor_export_sheet',
         'camera_c200_ready', 'camera_wind', 'camera_night', 'camera_tools', 'unfolded_editor_film']
for s in SHOTS:
    save(load(f'shots/{s}.png'), f'shot_{s}.jpg', q=93)

# ---- film renders (the app's shaders)
HERO = 'scooter'
FILMS = ['portra400', 'gold200', 'ektar100', 'vision500t_2383', 'velvia100', 'kodachrome64']
save(load(f'src/{HERO}.jpg'), 'hero_original.jpg', 2000)
for f in FILMS:
    save(load(f'png/{HERO}__{f}.png'), f'hero_{f}.jpg', 2000)
for name in ['valley__gold200', 'canyon__velvia100', 'bridge__vision500t_2383', 'portrait__portra400', 'pier__ektar100', 'bulbs__portra400',
             'cafe__c200', 'beach__pro400h', 'lantern__vision500t_2383', 'hikers__kodachrome64', 'waterfall__provia100f', 'mandarins__ektar100']:
    save(load(f'png/{name}.png'), f'r_{name}.jpg', 1800)
# the pipeline's stages on one frame
save(load('src/canyon.jpg'), 'stage_original.jpg', 1200)
save(load('png/canyon__stage_negative.png'), 'stage_negative.jpg', 1200)
save(load('png/canyon__stage_print.png'), 'stage_print.jpg', 1200)
# details: crops at a scale where the effect reads on a 1080p frame
def crop(rel, box, name):
    save(load(rel).crop(box), name, 1100, q=94)
crop('png/bulbs__halation_off__portra800.png', (120, 60, 1320, 1304), 'hal_off.jpg')
crop('png/bulbs__halation_on__portra800.png', (120, 60, 1320, 1304), 'hal_on.jpg')
g = load('png/portrait__grain_off__portra800_crop.png')
w, h = g.size
box = (int(w * .33), int(h * .44), int(w * .33) + 620, int(h * .44) + 643)
crop('png/portrait__grain_off__portra800_crop.png', box, 'grain_off.jpg')
crop('png/portrait__grain_on__portra800_crop.png', box, 'grain_on.jpg')
b0, b1 = load('png/scooter__portra400.png'), load('png_overlay/scooter__portra400__filmedge_date.png')
# same framing for both: the plain print padded to the bordered one's canvas
pad = Image.new('RGB', b1.size, (13, 8, 7))
s = min(b1.width / b0.width, b1.height / b0.height)
r = b0.resize((round(b0.width * s), round(b0.height * s)), Image.LANCZOS)
pad.paste(r, ((b1.width - r.width) // 2, (b1.height - r.height) // 2))
save(pad, 'border_off.jpg', 1600); save(b1, 'border_on.jpg', 1600)

# ---- download page
for src, dst in [('shot_library', 'shot-library'), ('shot_editor_film_portra400', 'shot-editor'), ('shot_camera_c200_ready', 'shot-camera'),
                 ('shot_library_select', 'shot-library-select'), ('shot_editor_paper_2383', 'shot-editor-paper'),
                 ('shot_editor_halation', 'shot-editor-halation'), ('shot_editor_export_sheet', 'shot-export')]:
    save(Image.open(os.path.join(app, src + '.jpg')), dst + '.png'.replace('.png', '.jpg'), 1600, q=88, dirs=(site,))
save(Image.open(os.path.join(app, 'hero_original.jpg')), 'compare-original.jpg', 1800, q=88, dirs=(site,))
save(Image.open(os.path.join(app, 'hero_portra400.jpg')), 'compare-film.jpg', 1800, q=88, dirs=(site,))
GALLERY = [('r_valley__gold200', 'Kodak Gold 200'), ('r_canyon__velvia100', 'Fujifilm Velvia 100'), ('r_bridge__vision500t_2383', 'Vision3 500T × 2383'),
           ('r_pier__ektar100', 'Kodak Ektar 100'), ('r_bulbs__portra400', 'Kodak Portra 400'), ('r_cafe__c200', 'Fujifilm C200'),
           ('r_beach__pro400h', 'Fujifilm Pro 400H'), ('r_hikers__kodachrome64', 'Kodak Kodachrome 64'), ('r_lantern__vision500t_2383', 'Vision3 500T × 2383')]
items = []
for i, (src, film) in enumerate(GALLERY):
    save(Image.open(os.path.join(app, src + '.jpg')), f'gallery-{i + 1}.jpg', 1200, q=86, dirs=(site,))
    items.append({'file': f'gallery-{i + 1}.jpg', 'film': film})
json.dump({'compare': 'Kodak Portra 400', 'items': items,
           'credits': 'Aabhas Lall（CC BY-SA 4.0）、Kari Shea、Matt Hoffman、Brodie Vissers、Daria Lisovtsova、Erwan Hesry、Kait Herzog、Ashim D’Silva 等，'
                      '取自 elementary、Ubuntu Budgie、Pop!_OS 的开源壁纸集；人像为 spektrafilm 的测试图。详见仓库 promo/CREDITS.md。'},
          open(os.path.join(site, 'gallery.json'), 'w'), ensure_ascii=False, indent=1)
print('collected', len(os.listdir(app)), 'video assets,', len(os.listdir(site)), 'site images')
