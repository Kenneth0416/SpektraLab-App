// Screenshots the download page (../site) for the video's last scene, the page's social card and a phone-width check.
//   node tools/shoot-site.mjs
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');

const promo = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.json': 'application/json', '.mp4': 'video/mp4' };
const server = http.createServer((req, res) => {
  const p = path.join(promo, decodeURIComponent(req.url.split('?')[0]));
  if (!p.startsWith(promo) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch();

async function open(width, height, scale) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
  page.on('console', (m) => { if (m.type() === 'error') console.error('console', m.text()); });
  await page.goto(`${base}/site/index.html`);
  // this machine has no CJK serif / sans of the page's stack, so load the same families the video uses
  for (const f of ['noto-serif-sc/900', 'noto-serif-sc/700', 'noto-sans-sc/400', 'noto-sans-sc/700'])
    await page.addStyleTag({ url: `${base}/video/node_modules/@fontsource/${f}.css` });
  await page.addStyleTag({ content: 'html{scroll-behavior:auto} .lamp{animation:none}' });
  await page.evaluate(async () => {
    document.querySelectorAll('img').forEach((i) => { i.loading = 'eager'; });
    await Promise.all([...document.images].map((i) => i.decode().catch(() => {})));
    await document.fonts.ready;
  });
  await page.waitForTimeout(800);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (overflow > 0) console.error(`horizontal overflow at ${width}px: ${overflow}px`);
  return page;
}

let page = await open(1280, 900, 1.5);
await page.screenshot({ path: path.join(promo, 'video/assets/site.jpg'), type: 'jpeg', quality: 90, fullPage: true });
page = await open(1200, 630, 1);
await page.addStyleTag({ content: '.nav{display:none}' });
await page.screenshot({ path: path.join(promo, 'site/img/og.jpg'), type: 'jpeg', quality: 88 });
page = await open(390, 844, 2);
fs.mkdirSync(path.join(promo, 'video/out'), { recursive: true });
await page.screenshot({ path: path.join(promo, 'video/out/site-mobile.jpg'), type: 'jpeg', quality: 80, fullPage: true });
await browser.close(); server.close();
console.log('site shots written');
