#!/usr/bin/env python3
"""Build pinned web fonts (--build); verify shipped glyphs offline (default).
Requires Node >=22 with type stripping and Python fonttools[woff].
No Android files or download archives are changed.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import urllib.request
import zipfile
from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'public/fonts'
SOURCES = {
    'wenkai.ttf': ('https://github.com/lxgw/LxgwWenKai/releases/download/v1.530/LXGWWenKai-Regular.ttf', '2d73eea94ad808999d90c0000c239a84a33b09d4bc6ab6c5b8bbf1eda8a7fc8b'),
    'wenkai-ofl.txt': ('https://raw.githubusercontent.com/lxgw/LxgwWenKai/v1.530/OFL.txt', '1a25e35da1031c6c3436fde545bb9cb5aca954e9873afe510c834b8b79bd21a0'),
    'martian.zip': ('https://github.com/evilmartians/mono/releases/download/v1.1.0/martian-mono-1.1.0-woff2.zip', '24f4bb3c3c786bc9b285be95c5ca380a37464a6b0becd9670a7249dff8ac301d'),
    'martian-ofl.txt': ('https://raw.githubusercontent.com/evilmartians/mono/v1.1.0/OFL.txt', 'ddafd2c3f37ef1d83ef284ca63e159befd3820850c5e9eded14ca52eee92c256'),
}
MONO = {400: 'MartianMono-StdRg.woff2', 500: 'MartianMono-StdMd.woff2', 700: 'MartianMono-StdBd.woff2'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def corpus():
    code = """
import { translations } from './src/i18n/translations.ts';
import { handwritingCopy } from './src/typography/handwriting.ts';
console.log(JSON.stringify(Object.fromEntries(Object.entries(translations).map(([locale,m]) => [locale,Object.values(handwritingCopy(m)).join('')]))));
"""
    return json.loads(subprocess.check_output(['node', '--experimental-strip-types', '--input-type=module', '-e', code], cwd=ROOT, text=True))


def required(texts):
    # Printable ASCII is kept for small future English edits, plus all rendered labels.
    return set(range(32, 127)) | {ord(c) for text in texts.values() for c in text}


def build(source_dir, texts):
    raw = {}
    for name, (url, digest) in SOURCES.items():
        data = (source_dir / name).read_bytes() if source_dir else urllib.request.urlopen(url, timeout=60).read()
        if sha(data) != digest:
            raise ValueError(f'Upstream checksum mismatch: {name}')
        raw[name] = data
    OUT.mkdir(parents=True, exist_ok=True)
    font = TTFont(io.BytesIO(raw['wenkai.ttf']), recalcTimestamp=False)
    missing = required(texts) - set(font.getBestCmap())
    if missing:
        raise ValueError(f'Upstream does not cover required characters: {sorted(missing)}')
    options = subset.Options()
    options.name_IDs = ['*']
    options.name_languages = ['*']
    options.name_legacy = True
    sub = subset.Subsetter(options=options)
    sub.populate(unicodes=required(texts))
    sub.subset(font)
    # OFL modified font: use a distinct primary name, not any upstream Reserved Font Name.
    names = {1: 'Silvercam Hand Web', 2: 'Regular', 3: 'SilvercamHandWeb-1.530-subset',
             4: 'Silvercam Hand Web Regular', 6: 'SilvercamHandWeb-Regular',
             16: 'Silvercam Hand Web', 17: 'Regular', 18: 'Silvercam Hand Web Regular',
             21: 'Silvercam Hand Web', 22: 'Regular'}
    for record in font['name'].names:
        if record.nameID in names:
            record.string = names[record.nameID].encode(record.getEncoding())
    font.flavor = 'woff2'
    font.save(OUT / 'silvercam-hand-regular.woff2')
    archive = zipfile.ZipFile(io.BytesIO(raw['martian.zip']))
    for weight, member in MONO.items():
        (OUT / f'martian-mono-{weight}.woff2').write_bytes(archive.read(member))
    (OUT / 'OFL-WenKai.txt').write_bytes(raw['wenkai-ofl.txt'])
    (OUT / 'OFL-MartianMono.txt').write_bytes(raw['martian-ofl.txt'])
    files = sorted(OUT.glob('*.woff2')) + sorted(OUT.glob('OFL-*.txt'))
    manifest = {
        'handSourceVersion': 'LXGW WenKai 1.530 Regular',
        'handFamily': 'Silvercam Hand Web',
        'monoSourceVersion': 'Martian Mono 1.1.0',
        'sources': {name: {'url': url, 'sha256': digest} for name, (url, digest) in SOURCES.items()},
        'files': {p.name: {'bytes': p.stat().st_size, 'sha256': sha(p.read_bytes())} for p in files},
    }
    (OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')


def verify(texts):
    manifest = json.loads((OUT / 'manifest.json').read_text())
    for name, info in manifest['files'].items():
        data = (OUT / name).read_bytes()
        assert sha(data) == info['sha256'] and len(data) == info['bytes'], f'Artifact changed: {name}'
    for filename, source in [('OFL-WenKai.txt', 'wenkai-ofl.txt'), ('OFL-MartianMono.txt', 'martian-ofl.txt')]:
        assert sha((OUT / filename).read_bytes()) == SOURCES[source][1], 'Preserve complete upstream OFL text'
    font = TTFont(OUT / 'silvercam-hand-regular.woff2')
    cmap = set(font.getBestCmap())
    missing = required(texts) - cmap
    assert not missing, 'Rebuild hand subset; missing: ' + ', '.join(f'{chr(c)} U+{c:04X}' for c in sorted(missing))
    assert font['name'].getDebugName(1) == 'Silvercam Hand Web'
    assert font['OS/2'].usWeightClass == 400
    for weight in MONO:
        mono = TTFont(OUT / f'martian-mono-{weight}.woff2')
        assert mono['OS/2'].usWeightClass == weight
        assert set(range(32, 127)) <= set(mono.getBestCmap())
    print(json.dumps({'handCmap': len(cmap), 'requiredCodepoints': len(required(texts)),
                      'locales': {locale: {'uniqueCharacters': len(set(text)), 'missing': []} for locale, text in texts.items()},
                      'checkmarkCovered': 0x2713 in cmap,
                      'fontBytes': sum(p.stat().st_size for p in OUT.glob('*.woff2'))}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--source-dir', type=Path, help='Optional offline cache with pinned upstream files')
    args = parser.parse_args()
    text = corpus()
    if args.build:
        build(args.source_dir, text)
    verify(text)
