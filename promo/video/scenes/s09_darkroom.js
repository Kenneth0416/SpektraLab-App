import { $, $$, split, glyphs, rise, up, out, phone, cropmarks, cue, B } from '../lib.js';
import A from '../assets.js';

// The editor: one photograph, advanced through film stock after film stock like frames on a roll.
const F = { x: 700, y: 120, w: 1080, h: 720 };
const films = A.hero.films;

export default {
  id: 's09', chapter: '02 · 暗房', dur: 16 * B, over: 0.35,
  html: `
  <div class="fill dark"></div>
  ${phone('ph', 150, 96, 888, A.editorFilm, `<img class="s2" src="${A.editorPaper}"><img class="s3" src="${A.editorHalation}">`)}
  <div class="abs frame" style="left:${F.x}px;top:${F.y}px;width:${F.w}px;height:${F.h}px;overflow:hidden;background:#000">
    <img class="abs orig" src="${A.hero.original}" style="inset:0;width:100%;height:100%;object-fit:cover">
    ${films.map(([, , src], i) => `<img class="abs fm fm${i}" src="${src}" style="inset:0;width:100%;height:100%;object-fit:cover">`).join('')}
    <div class="abs wipe" style="left:0;top:0;width:4px;height:100%;background:var(--safe);box-shadow:0 0 22px 4px rgba(255,59,34,.8)"></div>
  </div>
  ${cropmarks(F.x - 22, F.y - 22, F.w + 44, F.h + 44, 'cmk')}
  <div class="abs tag0 mono" style="left:${F.x + 26}px;top:${F.y + 24}px;font-size:20px;letter-spacing:.14em;padding:10px 16px;background:rgba(13,8,7,.72);color:#fff">原图 · 数码直出</div>
  <div class="abs names" style="left:${F.x}px;top:${F.y + F.h + 44}px;width:${F.w}px;height:150px;overflow:hidden">
    ${films.map(([name, paper], i) => `<div class="abs nm nm${i}" style="left:0;top:0;width:100%">
      <div style="font-family:var(--mono);font-weight:700;font-size:64px;letter-spacing:-.03em;color:#fff;white-space:nowrap">${name}</div>
      <div style="font-size:26px;color:var(--dim);margin-top:8px;white-space:nowrap">相纸　<span style="color:var(--text)">${paper}</span></div></div>`).join('')}
  </div>
  <div class="abs cnt mono" style="left:${F.x + F.w - 300}px;top:${F.y + F.h + 58}px;width:300px;text-align:right;font-size:22px;letter-spacing:.14em;color:var(--safe)"><span class="ci">01</span> / 18 款胶片</div>
  <div class="abs big" style="left:${F.x + F.w - 520}px;top:${F.y + F.h + 100}px;width:520px;text-align:right;font-family:var(--serif);font-weight:900;font-size:50px;white-space:nowrap">18 款胶片 × 8 款相纸</div>
  `,
  build(tl, root, t) {
    split(root);
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .4 }, t);
    tl.fromTo($(root, '.ph'), { y: 120, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 1.0, ease: 'expo.out' }, t + .1);
    tl.fromTo($(root, '.frame'), { clipPath: 'inset(50% 0 50% 0)' }, { clipPath: 'inset(0% 0 0% 0)', duration: .9, ease: 'expo.out' }, t + .25);
    tl.fromTo($(root, '.cmk'), { scale: 1.18, autoAlpha: 0 }, { scale: 1, autoAlpha: 1, duration: .8, ease: 'expo.out' }, t + .4);
    up(tl, $(root, '.tag0'), t + .8, { y: 10, d: .4 });
    up(tl, [$(root, '.cnt'), $(root, '.big')], t + 1.0, { y: 14, d: .5 });
    const fm = $$(root, '.fm'), nm = $$(root, '.nm'), ci = $(root, '.ci');
    tl.set(fm, { autoAlpha: 0 }, t); tl.set(nm, { autoAlpha: 0 }, t);
    // first stock: the safelight line wipes the digital original into film
    const W0 = t + 3 * B;
    tl.set(fm[0], { autoAlpha: 1, clipPath: 'inset(0 100% 0 0)' }, W0);
    tl.to(fm[0], { clipPath: 'inset(0 0% 0 0)', duration: 1.0, ease: 'power3.inOut' }, W0);
    tl.fromTo($(root, '.wipe'), { left: 0, autoAlpha: 1 }, { left: F.w, duration: 1.0, ease: 'power3.inOut' }, W0);
    tl.set($(root, '.wipe'), { autoAlpha: 0 }, W0 + 1.0);
    tl.to($(root, '.tag0'), { autoAlpha: 0, duration: .25 }, W0 + .1);
    tl.fromTo(nm[0], { autoAlpha: 0, y: 60 }, { autoAlpha: 1, y: 0, duration: .5, ease: 'expo.out' }, W0 + .25);
    cue('whoosh', W0, .9);
    // then the roll advances: each new frame pushes the last one out of the gate
    for (let i = 1; i < films.length; i++) {
      const at = t + (3 + 2 * i) * B;
      tl.fromTo(fm[i], { autoAlpha: 1, xPercent: 100 }, { xPercent: 0, duration: .5, ease: 'expo.inOut' }, at - .25);
      tl.to(fm[i - 1], { xPercent: -100, duration: .5, ease: 'expo.inOut' }, at - .25);
      tl.to(nm[i - 1], { autoAlpha: 0, y: -60, duration: .3, ease: 'power2.in' }, at - .25);
      tl.fromTo(nm[i], { autoAlpha: 0, y: 60 }, { autoAlpha: 1, y: 0, duration: .45, ease: 'expo.out' }, at);
      tl.set(ci, { textContent: String(i + 1).padStart(2, '0') }, at);
      tl.fromTo($(root, '.cmk'), { scale: 1.03 }, { scale: 1, duration: .4, ease: 'power2.out' }, at);
      cue('tick', at, 1.6); cue('shutter', at, .35);
    }
    tl.fromTo($(root, '.s2'), { autoAlpha: 0 }, { autoAlpha: 1, duration: .35 }, t + 7 * B);
    tl.fromTo($(root, '.s3'), { autoAlpha: 0 }, { autoAlpha: 1, duration: .35 }, t + 11 * B);
    tl.to(root, { opacity: 0, duration: .35, ease: 'power2.in' }, t + this.dur - .35);
  },
};
