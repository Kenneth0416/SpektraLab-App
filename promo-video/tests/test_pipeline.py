"""Focused regression tests; no browser, model download, or media encode required."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'film'))
from build import build, sync_timeline
from driver import fresh_run, run_browser, verify_frames


class PipelineTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'scratch').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / 'scratch')
        self.addCleanup(self.temp.cleanup)
        self.tmp = Path(self.temp.name)

    def test_frame_runs_never_reuse_cached_frames(self):
        first, second = fresh_run(self.tmp), fresh_run(self.tmp)
        self.assertNotEqual(first, second)
        (first / '000000.png').write_bytes(b'old input and fps')
        with self.assertRaises(RuntimeError):
            verify_frames(second, 1)
        (second / '000001.png').write_bytes(b'wrong frame index')
        with self.assertRaises(RuntimeError):
            verify_frames(second, 1)

    def test_failed_browser_never_accepts_existing_frames(self):
        (self.tmp / '000000.png').write_bytes(b'old capture')
        failed = subprocess.CompletedProcess([], 1, 'EGO_SPACE_ID=123\n', 'asset failed')
        with patch('driver.subprocess.run', return_value=failed):
            with self.assertRaisesRegex(RuntimeError, 'asset failed'):
                run_browser([0], self.tmp)

    def test_build_is_canonical_deterministic_from_another_cwd(self):
        command = [sys.executable, str(ROOT / 'film/build.py')]
        subprocess.run(command, cwd=self.tmp, check=True, capture_output=True)
        html = (ROOT / 'film/film.html').read_bytes()
        data = (ROOT / 'film/data.js').read_bytes()
        subprocess.run(command, cwd=self.tmp, check=True, capture_output=True)
        self.assertEqual(html, (ROOT / 'film/film.html').read_bytes())
        self.assertEqual(data, (ROOT / 'film/data.js').read_bytes())
        self.assertIn('<!-- S10 try it -->', html.decode())
        self.assertNotIn('现在还要自己编译安装', html.decode())
        embedded = json.loads(data.decode().splitlines()[0][len('window.TL='):-1])
        self.assertEqual(embedded, json.loads((ROOT / 'timeline.json').read_text()))

    def test_timeline_sync_uses_new_input_and_preserves_curve(self):
        (self.tmp / 'v1').mkdir()
        (self.tmp / 'film').mkdir()
        static = (ROOT / 'v1/data.js').read_text().partition('\n')[2]
        shutil.copyfile(ROOT / 'v1/data.js', self.tmp / 'v1/data.js')
        timeline = {'duration': 12, 'lines': [], 'subs': [{'text': 'new', 'start': 1, 'end': 2}]}
        (self.tmp / 'timeline.json').write_text(json.dumps(timeline))
        sync_timeline(self.tmp)
        data = (self.tmp / 'film/data.js').read_text()
        self.assertEqual(json.loads(data.splitlines()[0][10:-1]), timeline)
        self.assertEqual(data.partition('\n')[2], static)

    @unittest.skipUnless(shutil.which('node'), 'Node.js required for readiness regression')
    def test_required_asset_failure_blocks_readiness(self):
        source = (ROOT / 'v1/film.html').read_text()
        readiness = source[source.index('window.__ready=false;'):source.rindex('</script>')]
        for fails in (False, True):
            script = '''const assert=require('node:assert/strict');
const window=globalThis;
const location={hash:'#still',search:''};
let rendered=false;
function render(){rendered=true;}
const document={fonts:{ready:Promise.resolve()},images:[{
  getAttribute:()=> 'required.jpg',
  decode:()=> FAIL ? Promise.reject(new Error('bad image')) : Promise.resolve()
}]};
'''.replace('FAIL', str(fails).lower()) + readiness + '''
setImmediate(()=>{
 assert.equal(window.__ready, EXPECT);
 assert.equal(rendered, EXPECT);
 if(!EXPECT) assert.match(window.__renderError,/Required image failed: required.jpg/);
});
'''.replace('EXPECT', str(not fails).lower())
            subprocess.run(['node', '-e', script], check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
