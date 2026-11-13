"""Build the canonical v3 film and embed the current timeline, from any cwd."""
from pathlib import Path
import json
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def sync_timeline(root=ROOT):
    root = Path(root)
    timeline = json.loads((root / 'timeline.json').read_text(encoding='utf-8'))
    if not isinstance(timeline.get('duration'), (int, float)) or timeline['duration'] <= 0:
        raise ValueError('timeline.duration must be positive')
    # v1 is the canonical source for the unchanged characteristic-curve data.
    data = (root / 'v1/data.js').read_text(encoding='utf-8')
    _, separator, static = data.partition('\n')
    if not separator or not static.startswith('window.CURVE='):
        raise ValueError('Missing canonical characteristic-curve data')
    (root / 'film/data.js').write_text(
        'window.TL=' + json.dumps(timeline, ensure_ascii=False) + ';\n' + static,
        encoding='utf-8')


def build():
    subprocess.run([sys.executable, str(HERE / 'make_v3.py')], check=True)
    sync_timeline()


if __name__ == '__main__':
    build()
