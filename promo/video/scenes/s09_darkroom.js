import { $, $$, split, glyphs, rise, up, out, phone, cropmarks, cue, B } from '../lib.js';
import A from '../assets.js';
import SW from '../assets/hero-swatches.js';

// The editor: one photograph, developed on film stock after film stock. The picture stays put and the
// safelight line sweeps the next stock over it, so the only thing that changes is the colour.
// The swatches on the right are sampled from each render at the five marked points (tools/collect-assets.py).
const F = { x: 700, y: 120, w: 1080, h: 720 };
const films = A.hero.films;
const SQ = 104, GAP = 20, SX = F.x + F.w + 30, SY = F.y + (F.h - 5 * SQ - 4 * GAP) / 2;

export default {
  id: 's09', chapter: '02 · 暗房', dur: 16 * B, over: 0.35,
  html: `
  <div class="fill dark"></div>
  ${phone('ph', 150, 96, 888, A.editorFilm, `<img class="s2" src="${A.editorVision}"><img class="s3" src="${A.editorVelvia}">`)}
  <div class="abs frame" style="left:${F.x}px;top:${F.y}px;width:${F.w}px;height:${F.h}px;overflow:hidden;background:#000">
    <img class="abs orig" src="${A.hero.original}" style="inset:0;width:100%;height:100%;object-fit:cover">
    ${films.map(([, , src], i) => `<img class="abs fm fm${i}" src="${src}" style="inset:0;width:100%;height:100%;object-fit:cover">`).join('')}
    <div class="abs wipe" style="left:0;top:0;width:4px;height:100%;background:var(--safe);box-shadow:0 0 22px 4px rgba(255,59,34,.8)"></div>
    ${SW.points.map(([x, y], i) => `<div class="abs pin" style="left:${x * F.w - 13}px;top:${y * F.h - 13}px;width:26px;height:26px;border-radius:50%;border:3px solid #fff;box-shadow:0 0 0 1.5px rgba(0,0,0,.55), inset 0 0 0 1.5px rgba(0,0,0,.4)">
      <span class="mono" style="position:absolute;left:28px;top:-3px;font-size:17px;color:#fff;text-shadow:0 1px 3px #000">${i + 1}</span></div>`).join('')}
  </div>
  ${cropmarks(F.x - 22, F.y - 22, F.w + 44, F.h + 44, 'cmk')}
  <div class="abs tag0 mono" style="left:${F.x + 26}px;top:${F.y + 24}px;font-size:20px;letter-spacing:.14em;padding:10px 16px;background:rgba(13,8,7,.72);color:#fff">原图 · 数码直出</div>
  ${SW.points.map((_, i) => `<div class="abs sw sw${i}" style="left:${SX}px;top:${SY + i * (SQ + GAP)}px;width:${SQ - 22}px;height:${SQ}px;background:${SW.original[i]};box-shadow:0 0 0 1.5px rgba(245,221,210,.25), 0 14px 30px rgba(0,0,0,.5)">
    <span class="mono" style="position:absolute;left:6px;top:4px;font-size:14px;color:#fff;text-shadow:0 1px 2px rgba(0,0,0,.8)">${i + 1}</span></div>`).join('')}
  <div class="abs swl mono" style="left:${SX - 4}px;top:${SY - 34}px;font-size:15px;letter-spacing:.12em;color:rgba(245,221,210,.7);white-space:nowrap">成片取样</div>
  <div class="abs names" style="left:${F.x}px;top:${F.y + F.h + 44}px;width:${F.w}px;height:150px;overflow:hidden">
    ${films.map(([name, paper], i) => `<div class="abs nm nm${i}" style="left:0;top:0;width:100%">
      <div style="font-family:var(--mono);font-weight:700;font-size:54px;letter-spacing:-.04em;color:#fff;white-space:nowrap">${name}</div>
      <div style="font-size:26px;color:var(--dim);margin-top:8px;white-space:nowrap">相纸　<span style="color:var(--text)">${paper}</span></div></div>`).join('')}
  </div>
  <div class="abs cnt mono" style="left:${F.x + F.w - 300}px;top:${F.y + F.h + 58}px;width:300px;text-align:right;font-size:22px;letter-spacing:.14em;color:var(--safe)">共 18 款胶片</div>
  <div class="abs big" style="left:${F.x + F.w - 520}px;top:${F.y + F.h + 100}px;width:520px;text-align:right;font-family:var(--serif);font-weight:900;font-size:40px;white-space:nowrap">18 款胶片 × 8 款相纸</div>
  `,
  build(tl, root, t) {
    split(root);
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .4 }, t);
    tl.fromTo($(root, '.ph'), { y: 120, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 1.0, ease: 'expo.out' }, t + .1);
    tl.fromTo($(root, '.frame'), { clipPath: 'inset(50% 0 50% 0)' }, { clipPath: 'inset(0% 0 0% 0)', duration: .9, ease: 'expo.out' }, t + .25);
    tl.fromTo($(root, '.cmk'), { scale: 1.18, autoAlpha: 0 }, { scale: 1, autoAlpha: 1, duration: .8, ease: 'expo.out' }, t + .4);
    up(tl, $(root, '.tag0'), t + .8, { y: 10, d: .4 });
    up(tl, [$(root, '.cnt'), $(root, '.big')], t + 1.0, { y: 14, d: .5 });
    tl.fromTo($$(root, '.pin'), { scale: 0 }, { scale: 1, duration: .4, ease: 'back.out(3)', stagger: .07 }, t + 1.0);
    tl.fromTo($$(root, '.sw'), { autoAlpha: 0, x: 30 }, { autoAlpha: 1, x: 0, duration: .5, ease: 'expo.out', stagger: .07 }, t + 1.1);
    up(tl, $(root, '.swl'), t + 1.2, { y: 0, d: .4 });
    const fm = $$(root, '.fm'), nm = $$(root, '.nm'), sw = $$(root, '.sw'), wipe = $(root, '.wipe');
    tl.set(fm, { autoAlpha: 0 }, t); tl.set(nm, { autoAlpha: 0 }, t); tl.set(wipe, { autoAlpha: 0 }, t);
    films.forEach((_, i) => {
      const at = t + (3 + 2 * i) * B, d = i === 0 ? 1.0 : .7;
      // the line sweeps left to right; each swatch takes its new colour as the line passes its sample point
      tl.set(fm[i], { autoAlpha: 1, clipPath: 'inset(0 100% 0 0)' }, at);
      tl.to(fm[i], { clipPath: 'inset(0 0% 0 0)', duration: d, ease: 'power2.inOut' }, at);
      tl.set(wipe, { autoAlpha: 1 }, at);
      tl.fromTo(wipe, { left: 0 }, { left: F.w, duration: d, ease: 'power2.inOut' }, at);
      tl.set(wipe, { autoAlpha: 0 }, at + d);
      sw.forEach((s, k) => {
        tl.to(s, { backgroundColor: SW.films[i][k], duration: .3, ease: 'power1.inOut' }, at + d * (.15 + .7 * SW.points[k][0]));
        tl.fromTo(s, { scale: 1 }, { scale: 1.1, duration: .14, yoyo: true, repeat: 1, ease: 'power2.out' }, at + d * (.15 + .7 * SW.points[k][0]));
      });
      if (i === 0) tl.to($(root, '.tag0'), { autoAlpha: 0, duration: .25 }, at + .1);
      else tl.to(nm[i - 1], { autoAlpha: 0, y: -60, duration: .3, ease: 'power2.in' }, at - .05);
      tl.fromTo(nm[i], { autoAlpha: 0, y: 60 }, { autoAlpha: 1, y: 0, duration: .45, ease: 'expo.out' }, at + .2);
      cue(i === 0 ? 'whoosh' : 'tick', at, i === 0 ? .9 : 1.6); if (i) cue('shutter', at, .35);
    });
    // the phone shows the film tab for the stock on screen
    tl.fromTo($(root, '.s2'), { autoAlpha: 0 }, { autoAlpha: 1, duration: .35 }, t + 7 * B);
    tl.fromTo($(root, '.s3'), { autoAlpha: 0 }, { autoAlpha: 1, duration: .35 }, t + 9 * B);
    tl.to(root, { opacity: 0, duration: .35, ease: 'power2.in' }, t + this.dur - .35);
  },
};
