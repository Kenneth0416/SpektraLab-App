// Renders the promo frame by frame: a local static server, N headless Chromium pages each
// seeking the GSAP master timeline, JPEG frames on disk, then ffmpeg (video + soundtrack).
//
//   node render.mjs                 full render  -> out/spektralab-promo.mp4
//   node render.mjs --stills 3,9.5  PNG stills at the given seconds -> out/stills/
//   node render.mjs --from 12 --to 20   partial render -> out/part.mp4 (no audio)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(ROOT, 'out');
const FRAMES = process.env.FRAMES_DIR || path.join(OUT, 'frames');
const FPS = 30, W = 1920, H = 1080;
const args = process.argv.slice(2);
const arg = (k) => { const i = args.indexOf(k); return i < 0 ? null : args[i + 1]; };
const WORKERS = +(arg('--workers') || 4);

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.woff': 'font/woff', '.json': 'application/json' };
const server = http.createServer((req, res) => {
  const p = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/index.html`;

const browser = await chromium.launch({ args: ['--force-color-profile=srgb', '--disable-lcd-text', '--font-render-hinting=none'] });
async function openPage() {
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  page.on('pageerror', (e) => console.error('pageerror', e.message));
  page.on('console', (m) => { if (m.type() === 'error') console.error('console', m.text()); });
  await page.goto(url);
  await page.waitForFunction('window.__ready === true', null, { timeout: 120000 });
  return page;
}

const first = await openPage();
const duration = await first.evaluate('window.__duration');
console.log('duration', duration.toFixed(2), 's');

if (args.includes('--cues')) {
  // timing sheet for audio/soundtrack.py
  const data = await first.evaluate(() => ({ duration: window.__duration, marks: window.__marks, cues: window.__cues || [] }));
  const at = (id) => data.marks.find((m) => m.id === id)?.t;
  data.drop = at('s07'); data.outro = at('s13');
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'cues.json'), JSON.stringify(data, null, 1));
  console.log('cues', data.cues.length, 'drop', data.drop, 'outro', data.outro);
  await browser.close(); server.close(); process.exit(0);
}
if (args.includes('--stills')) {
  const dir = path.join(OUT, 'stills'); fs.mkdirSync(dir, { recursive: true });
  const list = (arg('--stills') || '').split(',').filter(Boolean).map(Number);
  const times = list.length ? list : Array.from({ length: Math.ceil(duration / 2) }, (_, i) => i * 2 + 1);
  for (const t of times) {
    await first.evaluate((t) => window.__seek(t), t);
    await first.screenshot({ path: path.join(dir, `t${t.toFixed(2).padStart(6, '0')}.jpg`), type: 'jpeg', quality: 88 });
  }
  console.log('stills', times.length);
  await browser.close(); server.close(); process.exit(0);
}

const from = Math.round((+arg('--from') || 0) * FPS);
const to = Math.round((arg('--to') ? +arg('--to') : duration) * FPS);
fs.rmSync(FRAMES, { recursive: true, force: true }); fs.mkdirSync(FRAMES, { recursive: true });
const t0 = Date.now();
let next = from, done = 0;
async function work(page) {
  for (;;) {
    const f = next++; if (f >= to) return;
    await page.evaluate((t) => window.__seek(t), f / FPS);
    await page.screenshot({ path: path.join(FRAMES, `f${String(f - from).padStart(5, '0')}.jpg`), type: 'jpeg', quality: 97 });
    if (++done % 150 === 0) console.log(`${done}/${to - from} frames  ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  }
}
const pages = [first, ...(await Promise.all(Array.from({ length: WORKERS - 1 }, openPage)))];
await Promise.all(pages.map(work));
await browser.close(); server.close();
console.log('frames done in', ((Date.now() - t0) / 1000).toFixed(0), 's');

const partial = from > 0 || arg('--to');
const audio = path.join(OUT, 'soundtrack.wav');
const target = path.join(OUT, partial ? 'part.mp4' : 'silvercam-promo.mp4');
const ff = ['-y', '-v', 'error', '-framerate', String(FPS), '-i', path.join(FRAMES, 'f%05d.jpg')];
if (!partial && fs.existsSync(audio)) ff.push('-i', audio, '-c:a', 'aac', '-b:a', '256k', '-shortest');
ff.push('-c:v', 'libx264', '-preset', 'slow', '-crf', arg('--crf') || '17', '-pix_fmt', 'yuv420p',
  '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709', '-movflags', '+faststart', target);
const r = spawnSync('ffmpeg', ff, { stdio: 'inherit' });
console.log(r.status === 0 ? `wrote ${target}` : 'ffmpeg failed');
process.exit(r.status);
