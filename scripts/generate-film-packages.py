"""Generate small local SVG illustrations inspired by film packaging (not scans).
Run from the repository root. No remote assets or image-processing dependencies.
"""
from pathlib import Path
from html import escape

# stock, brand, series, speed/model, carton, label, ink
PACKAGES = [
    ('kodak_portra_400', 'KODAK', 'PORTRA', '400', '#E3C592', '#AE813F', '#30241C'),
    ('kodak_portra_160', 'KODAK', 'PORTRA', '160', '#EDD9AE', '#B49A68', '#30241C'),
    ('kodak_portra_800', 'KODAK', 'PORTRA', '800', '#DBB274', '#815037', '#30241C'),
    ('kodak_ektar_100', 'KODAK', 'EKTAR', '100', '#B62E30', '#F5CE4D', '#FFF6DC'),
    ('kodak_gold_200', 'KODAK', 'GOLD', '200', '#F4C936', '#AB342B', '#342416'),
    ('kodak_ultramax_400', 'KODAK', 'ULTRAMAX', '400', '#F3CC36', '#245AA0', '#243248'),
    ('fujifilm_c200', 'FUJIFILM', 'FUJICOLOR', 'C200', '#F2F0DF', '#258B4A', '#183B2A'),
    ('fujifilm_pro_400h', 'FUJIFILM', 'PRO', '400H', '#E8E8D9', '#1F694C', '#254538'),
    ('fujifilm_xtra_400', 'FUJIFILM', 'SUPERIA', 'X-TRA 400', '#37A257', '#244D36', '#102F24'),
    ('kodak_vision3_50d', 'KODAK', 'VISION3', '50D', '#32332E', '#D9B64C', '#F5ECD8'),
    ('kodak_vision3_250d', 'KODAK', 'VISION3', '250D', '#32332E', '#688A51', '#F5ECD8'),
    ('kodak_vision3_200t', 'KODAK', 'VISION3', '200T', '#32332E', '#527FAD', '#F5ECD8'),
    ('kodak_vision3_500t', 'KODAK', 'VISION3', '500T', '#32332E', '#AE604C', '#F5ECD8'),
    ('kodak_verita_200d', 'KODAK', 'VERITA', '200D', '#39383C', '#9D91B6', '#F5ECD8'),
    ('fujifilm_velvia_100', 'FUJIFILM', 'Velvia', '100', '#E8E8DB', '#A63872', '#313A35'),
    ('fujifilm_provia_100f', 'FUJIFILM', 'PROVIA', '100F', '#E8E8DB', '#3764A1', '#313A35'),
    ('kodak_ektachrome_100', 'KODAK', 'EKTACHROME', 'E100', '#EBC540', '#2769A3', '#263540'),
    ('kodak_kodachrome_64', 'KODAK', 'KODACHROME', '64', '#E9BB39', '#AE342A', '#392920'),
]


def svg(stock, brand, series, speed, carton, label, ink):
    cinema = 'vision3' in stock or 'verita' in stock
    slide = any(s in stock for s in ('velvia', 'provia', 'ektachrome', 'kodachrome'))
    category = 'MOTION PICTURE' if cinema else 'COLOR REVERSAL' if slide else 'COLOR NEGATIVE'
    brand_ink = '#21653D' if brand == 'FUJIFILM' else '#B33425'
    series_size = 14 if len(series) > 9 else 18 if len(series) > 7 else 25
    speed_size = 18 if len(speed) > 5 else 36 if len(speed) > 3 else 42
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="160" height="168" viewBox="0 0 160 168">
  <title>{escape(brand + ' ' + series + ' ' + speed)}</title>
  <path d="M20 22L130 22L146 36L146 151L36 151L20 137Z" fill="#000000" opacity=".16"/>
  <path d="M16 18L126 18L142 30L32 30Z" fill="{carton}"/>
  <path d="M126 18L142 30L142 146L126 134Z" fill="{label}"/>
  <path d="M126 18L142 30L142 146L126 134Z" fill="#000000" opacity=".23"/>
  <rect x="16" y="18" width="110" height="128" rx="2" fill="{carton}"/>
  <rect x="16" y="18" width="110" height="27" rx="2" fill="#F5EAD0"/>
  <text x="24" y="36" font-family="sans-serif" font-size="13" font-weight="bold" fill="{brand_ink}">{brand}</text>
  <path d="M16 47H126" stroke="{ink}" stroke-opacity=".18"/>
  <text x="24" y="61" font-family="sans-serif" font-size="6" letter-spacing="1" fill="{ink}">{category}</text>
  <text x="24" y="86" font-family="sans-serif" font-size="{series_size}" font-weight="bold" fill="{ink}">{series}</text>
  <text x="24" y="123" font-family="sans-serif" font-size="{speed_size}" font-weight="bold" fill="{ink}">{speed}</text>
  <rect x="16" y="132" width="110" height="14" fill="{label}"/>
  <text x="24" y="142" font-family="sans-serif" font-size="7" font-weight="bold" fill="#FFFFFF">{'35 mm / ECN-2' if cinema else '135 / 36 EXP'}</text>
  <path d="M134 42V123" stroke="#FFFFFF" stroke-opacity=".3" stroke-width="2"/>
  <path d="M16 19H125" stroke="#FFFFFF" stroke-opacity=".4"/>
</svg>
'''


if __name__ == '__main__':
    out = Path(__file__).resolve().parents[1] / 'app/src/main/assets/film-packages'
    out.mkdir(parents=True, exist_ok=True)
    for package in PACKAGES:
        (out / (package[0] + '.svg')).write_text(svg(*package))
    print(f'Generated {len(PACKAGES)} film package SVGs')
