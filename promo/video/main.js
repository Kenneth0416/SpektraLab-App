import { mulberry } from './lib.js';
import scenes from './scenes/index.js';

const FPS = 30;
gsap.registerPlugin(CustomEase);
const tl = gsap.timeline({ paused: true });
const host = document.getElementById('scenes');
const chapter = document.getElementById('chapter'), counter = document.getElementById('counter');
const marks = [];
let t0 = 0;
scenes.forEach((s, i) => {
  const root = document.createElement('section');
  root.className = `scene ${s.cls || ''}`; root.id = s.id; root.style.zIndex = 10 + i;
  root.innerHTML = s.html; host.appendChild(root);
  // a scene stays up `over` seconds into the next one so transitions can overlap
  tl.set(root, { autoAlpha: 1 }, t0);
  tl.set(root, { autoAlpha: 0 }, t0 + s.dur + (s.over || 0));
  s.build(tl, root, t0);
  marks.push({ t: t0, label: s.chapter, id: s.id });
  t0 += s.dur;
});
const duration = t0;
tl.set({}, {}, duration);

// film grain: a few noise tiles, one picked per frame at a jittered offset
const grain = document.getElementById('grain');
const tiles = [];
{
  const rnd = mulberry(1977);
  for (let k = 0; k < 6; k++) {
    const c = document.createElement('canvas'); c.width = c.height = 512;
    const g = c.getContext('2d'), d = g.createImageData(512, 512);
    for (let i = 0; i < d.data.length; i += 4) {
      const v = 128 + (rnd() + rnd() + rnd() - 1.5) * 150;
      d.data[i] = d.data[i + 1] = d.data[i + 2] = v; d.data[i + 3] = 255;
    }
    g.putImageData(d, 0, 0); tiles.push(`url(${c.toDataURL()})`);
  }
}
window.__seek = (t) => {
  tl.seek(Math.min(t, duration - 1e-4), false);
  const f = Math.round(t * FPS), r = mulberry(f * 7919 + 13);
  grain.style.backgroundImage = tiles[f % tiles.length];
  grain.style.backgroundPosition = `${Math.floor(r() * 512)}px ${Math.floor(r() * 512)}px`;
  let m = marks[0]; for (const k of marks) if (k.t <= t + 1e-6) m = k;
  chapter.textContent = m.label;
  counter.textContent = String(marks.indexOf(m) + 1).padStart(2, '0') + ' / ' + String(marks.length).padStart(2, '0');
};
window.__duration = duration;
window.__marks = marks;

async function ready() {
  await Promise.all([...document.images].map((im) => im.decode().catch(() => console.error('image failed', im.src))));
  // touch the whole timeline once so every font subset that any scene needs gets requested
  for (let t = 0; t < duration; t += 0.5) window.__seek(t);
  await document.fonts.ready;
  const all = document.body.innerText;
  for (const f of ['900 40px "Noto Serif SC"', '700 40px "Noto Serif SC"', '400 40px "Noto Sans SC"', '500 40px "Noto Sans SC"',
    '700 40px "Noto Sans SC"', '400 40px "Long Cang"', '400 40px "Martian Mono"', '500 40px "Martian Mono"', '700 40px "Martian Mono"'])
    await document.fonts.load(f, all);
  await document.fonts.ready;
  window.__seek(0);
  window.__ready = true;
  if (location.search.includes('play')) {
    const s = performance.now();
    (function loop() { window.__seek(((performance.now() - s) / 1000) % duration); requestAnimationFrame(loop); })();
  }
}
ready();
