"""Shared Ego-only, localhost render driver. No browser profile or executable overrides."""
from contextlib import contextmanager
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import math
import subprocess
import tempfile
import threading

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
REPO = ROOT.parent


def fresh_run(parent=None):
    parent = Path(parent or ROOT / 'frames').resolve()
    parent.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix='run-', dir=parent))


def validate_times(times):
    if not times or any(not math.isfinite(t) or t < 0 for t in times):
        raise ValueError('Provide finite, nonnegative capture times')


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


@contextmanager
def serve():
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(REPO)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}'
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def run_browser(times, out, *, film=HERE / 'film.html', space='Spektra integration verification',
                keep_space=False, inspect=None, timeout=3600):
    """Return diagnostics; CLI failure or a required asset failure aborts the run.

    Every capture belongs to this invocation's unique directory. The caller only
    encodes after this checked subprocess succeeds and all exact paths exist.
    """
    validate_times(times)
    film = Path(film).resolve()
    relative = film.relative_to(REPO).as_posix()
    out = Path(out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    with serve() as origin:
        config = dict(url=f'{origin}/{relative}#still', times=times, out=str(out),
                      space=space, keep=keep_space, inspect=inspect)
        script = 'const config = ' + json.dumps(config) + ';\n' + r'''
const fs = await import('node:fs/promises');
const task = await taskSpace(config.space);
console.log('EGO_SPACE_ID=' + task.spaceId);
const page = task.page('p1');
await page.cdp('Page.addScriptToEvaluateOnNewDocument', {source: `
window.__seekRender=true;
window.addEventListener('error', e=>{window.__renderError=e.message});
window.addEventListener('unhandledrejection', e=>{window.__renderError=String(e.reason)});
`});
await page.cdp('Emulation.setDeviceMetricsOverride', {
  width:1920, height:1080, deviceScaleFactor:1, mobile:false
});
await page.goto(config.url);
await page.waitForFunction(()=>window.__ready===true || !!window.__renderError, undefined, {timeout:30000});
const state = await page.evaluate(()=>({ready:window.__ready, error:window.__renderError}));
if (state.error || !state.ready) throw new Error(state.error || 'Film did not become ready');
const results=[];
for (let i=0; i<config.times.length; i++) {
  const t=config.times[i];
  await page.evaluate(async t=>{
    window.__seek(t);
    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    if(window.__renderError) throw new Error(window.__renderError);
  }, t);
  if (config.inspect) {
    results.push({time:t, items:await page.evaluate('('+config.inspect+')('+JSON.stringify(t)+')')});
  } else {
    const image=await page.cdp('Page.captureScreenshot', {
      format:'png', captureBeyondViewport:false, fromSurface:true
    });
    await fs.writeFile(config.out+'/'+String(i).padStart(6,'0')+'.png', Buffer.from(image.data,'base64'));
  }
}
console.log('RENDER_RESULT='+JSON.stringify({spaceId:task.spaceId, results}));
if (!config.keep) await task.finish({keep:[]});
'''
        result = subprocess.run(['ego-browser', 'nodejs', '-e', script], text=True,
                                capture_output=True, timeout=timeout)
        # Keep the Space ID visible even when a later browser operation fails.
        output = result.stdout + result.stderr  # Ego may emit console output on stderr.
        print(output, end='')
        if result.returncode:
            raise RuntimeError(f'Ego render failed ({result.returncode}): {output}')
        payloads = [line.removeprefix('RENDER_RESULT=') for line in output.splitlines()
                    if line.startswith('RENDER_RESULT=')]
        if not payloads:
            raise RuntimeError('Ego returned no completion receipt')
        if not inspect:
            verify_frames(out, len(times))
        return json.loads(payloads[-1])


def verify_frames(out, count):
    expected = {f'{i:06d}.png' for i in range(count)}
    actual = {p.name for p in Path(out).glob('*.png') if p.stat().st_size > 0}
    if actual != expected:
        raise RuntimeError(f'Incomplete capture: expected {count}, got {len(actual)} frames')


def browser_options(parser):
    parser.add_argument('--film', type=Path, default=HERE / 'film.html')
    parser.add_argument('--space', default='Spektra integration verification')
    parser.add_argument('--keep-space', action='store_true', help='Coordinator will finish this Space later')
    parser.add_argument('--output-dir', type=Path, help='Parent of a fresh run directory')


def browser_kwargs(args):
    return dict(film=args.film, space=args.space, keep_space=args.keep_space)
