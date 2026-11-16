import { $, $$, split, glyphs, rise, up, out, draw, phone, cue, B } from '../lib.js';
import A from '../assets.js';

const POINTS = [
  ['实时胶片取景', '预览走的就是同一条 GPU 胶片管线'],
  ['原片存 RAW（DNG）', '设置随时可改，原片不动'],
  ['多帧合成', '暗处连拍几帧，合成一张更干净的原片'],
  ['过片扳手', '拍一张，扳一下，像机械相机那样'],
];

export default {
  id: 's11', chapter: '04 · 相机', dur: 10 * B, over: 0.35,
  html: `
  <div class="fill dark"></div>
  <div class="abs halo" style="left:900px;top:-100px;width:1300px;height:1300px;border-radius:50%;background:radial-gradient(circle, rgba(255,59,34,.2) 0%, transparent 62%)"></div>
  <div class="abs kicker k" style="left:120px;top:150px">04 · 相机 Camera</div>
  <div class="abs h2" style="left:114px;top:190px;font-size:112px"><span class="ln hl" data-split>取景，就是成片</span></div>
  ${POINTS.map(([a, b], i) => `<div class="abs pt" style="left:120px;top:${388 + i * 124}px;display:flex;gap:26px;align-items:flex-start">
      <svg class="draw" width="44" height="44" viewBox="0 0 44 44" style="margin-top:8px;flex:none"><path pathLength="1" d="M6 24 L18 36 L39 8"/></svg>
      <div><div class="h3" style="font-size:44px">${a}</div><div style="font-size:26px;color:var(--dim);margin-top:4px;white-space:nowrap">${b}</div></div></div>`).join('')}
  ${phone('ph', 1180, 96, 888, A.camera, `<img class="adv" src="${A.cameraAdvance}"><div class="fl abs" style="inset:0;background:#fff"></div>`)}
  ${phone('ph2', 1560, 250, 640, A.exportSheet)}
  <div class="abs more mono" style="left:120px;top:940px;font-size:21px;letter-spacing:.12em;color:rgba(245,221,210,.7)">还有：视频胶片化（1080p / 4K）· 折叠屏双栏 · 配方 · 后台批量冲印</div>
  `,
  build(tl, root, t) {
    split(root);
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .4 }, t);
    up(tl, $(root, '.k'), t + .2, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.hl'), t + .3, { s: .045 });
    tl.fromTo($(root, '.ph2'), { y: 160, autoAlpha: 0, rotation: 10 }, { y: 0, autoAlpha: 1, rotation: 6, duration: 1.1, ease: 'expo.out' }, t + .35);
    tl.fromTo($(root, '.ph'), { y: 160, autoAlpha: 0, rotation: -6 }, { y: 0, autoAlpha: 1, rotation: -3, duration: 1.1, ease: 'expo.out' }, t + .2);
    tl.to($(root, '.ph'), { y: -12, rotation: -2, duration: 5, ease: 'sine.inOut' }, t + 1.3);
    $$(root, '.pt').forEach((p, i) => {
      const at = t + (2 + i * 1.5) * B;
      up(tl, p, at, { y: 22, d: .5 });
      draw(tl, p.querySelector('path'), at + .1, { d: .35 }); cue('pop', at, 1 + i * .08);
    });
    // the shutter fires, the frame is spent, the lever is pulled
    const S = t + 5 * B;
    tl.set($(root, '.fl'), { autoAlpha: 0 }, t);
    tl.fromTo($(root, '.fl'), { autoAlpha: .85 }, { autoAlpha: 0, duration: .35, ease: 'power2.out' }, S); cue('shutter', S, 1.2);
    tl.fromTo($(root, '.adv'), { autoAlpha: 0 }, { autoAlpha: 1, duration: .15 }, S + .05);
    cue('advance', S + 1.2);
    tl.to($(root, '.ph'), { x: 14, duration: .12, yoyo: true, repeat: 5, ease: 'sine.inOut' }, S + 1.2);
    up(tl, $(root, '.more'), t + 7 * B, { y: 12, d: .5 });
    tl.to(root, { opacity: 0, duration: .35, ease: 'power2.in' }, t + this.dur - .35);
  },
};
