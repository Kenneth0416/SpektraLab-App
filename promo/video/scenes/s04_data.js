import { $, $$, split, glyphs, rise, up, out, draw, cropmarks, cue, B, V } from '../lib.js';

// The evidence: the real Portra 400 data plot from the spektrafilm README, laid on the lightbox.
const IMG = { x: 120, y: 318, s: 1.4 };                      // 1200x400 figure drawn at 1680x560
const PANELS = [[58, 392], [455, 790], [853, 1190]].map(([a, b]) => ({ x: IMG.x + a * IMG.s - 14, w: (b - a) * IMG.s + 28 }));
const LABELS = [
  ['光谱感光度', '每层乳剂，对哪些波长的光敏感'],
  ['特性曲线', '曝光多少，就生成多少密度'],
  ['染料吸收光谱', '显影后的染料，吸收哪些光'],
];

export default {
  id: 's04', chapter: '依据 · DATA-SHEET', dur: 18 * B, over: 0.4, cls: 'lightbox',
  html: `
  <div class="abs kicker k" style="left:120px;top:128px">Kodak Portra 400 · data-sheet E4050</div>
  <div class="abs h2" style="left:114px;top:166px;font-size:86px"><span class="ln hl" data-split>颜色的依据：厂商的胶片数据表</span></div>
  <div class="abs plot" style="left:${IMG.x}px;top:${IMG.y}px;width:1680px;height:560px">
    <img src="assets/spektrafilm/example_data_kodak_portra_400.png" style="width:100%;height:100%;mix-blend-mode:multiply">
  </div>
  ${cropmarks(PANELS[0].x, IMG.y + 40, PANELS[0].w, 500, 'focus')}
  ${LABELS.map(([a, b], i) => `
    <div class="abs lab" style="left:${PANELS[i].x}px;top:892px;width:${PANELS[i].w}px;text-align:center">
      <div class="h3" style="font-size:42px"><span class="mono" style="font-size:22px;color:var(--red);vertical-align:middle;margin-right:14px">0${i + 1}</span>${a}</div>
      <div style="font-size:25px;color:var(--grey);margin-top:6px;white-space:nowrap">${b}</div>
    </div>`).join('')}
  <svg class="abs draw ring" style="left:${PANELS[0].x + 30}px;top:880px" width="430" height="84" viewBox="0 0 430 84"><ellipse pathLength="1" cx="215" cy="42" rx="205" ry="34" transform="rotate(-2 215 42)"/></svg>
  <div class="credit">图：spektrafilm README · Kodak Portra 400 数据</div>
  `,
  build(tl, root, t) {
    split(root);
    // lights on: the tube stutters twice before it holds
    tl.fromTo(root, { opacity: 0 }, { keyframes: [{ opacity: .75, duration: .06 }, { opacity: .12, duration: .08 }, { opacity: .9, duration: .06 }, { opacity: .35, duration: .09 }, { opacity: 1, duration: .28 }] }, t);
    cue('flicker', t);
    // VO: 颜色的依据…数据表。(4.0) 每层乳剂对什么光敏感，(6.8) 曝光多少、出多少密度，(9.2) 染料又会吸收哪些光。
    const L = [V + 4.0, V + 6.8, V + 9.2];
    cue('draw', t + L[2] + .6);
    up(tl, $(root, '.k'), t + .45, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.hl'), t + .55, { s: .03 });
    tl.fromTo($(root, '.plot'), { clipPath: 'inset(0 100% 0 0)' }, { clipPath: 'inset(0 0% 0 0)', duration: 1.5, ease: 'power3.inOut' }, t + .9);
    tl.fromTo($(root, '.plot'), { y: 26 }, { y: 0, duration: 1.5, ease: 'power3.out' }, t + .9);
    up(tl, $(root, '.credit'), t + 1.6, { y: 0 });
    const focus = $(root, '.focus'), labs = $$(root, '.lab'), ring = $(root, '.ring');
    tl.fromTo(focus, { autoAlpha: 0, scale: 1.12 }, { autoAlpha: 1, scale: 1, duration: .45, ease: 'power3.out' }, t + L[0] - .25);
    labs.forEach((l, i) => {
      const at = t + L[i];
      if (i > 0) {
        tl.to(focus, { left: PANELS[i].x, width: PANELS[i].w, duration: .7, ease: 'expo.inOut' }, at - .45);
        tl.to(ring, { left: PANELS[i].x + (PANELS[i].w - 430) / 2, duration: 0 }, at - .01);
      } else tl.set(ring, { left: PANELS[0].x + (PANELS[0].w - 430) / 2 }, at - .01);
      up(tl, l, at, { y: 20, d: .5 }); cue('tick', at);
      if (i > 0) tl.to(labs[i - 1], { opacity: .38, duration: .4 }, at);
    });
    draw(tl, $(root, '.ring ellipse'), t + L[2] + .6, { d: .5 });
    tl.to(labs, { opacity: 1, duration: .4 }, t + L[2] + 1.5);
    tl.to(root, { opacity: 0, duration: .4, ease: 'power2.in' }, t + this.dur - .35);
  },
};
