import { $, $$, split, glyphs, rise, up, out, draw, phone, cue, B } from '../lib.js';
import A from '../assets.js';

// The library: prints thrown on a lightbox, the real library screen in the handset.
const PRINTS = [[1080, 560, -7, 0], [1390, 600, 5, 1], [1230, 690, -2, 2]];

export default {
  id: 's08', chapter: '01 · 灯箱', dur: 8 * B, over: 0.35, cls: 'lightbox',
  html: `
  ${phone('ph', 220, 96, 888, A.library, `<img class="sel" src="${A.librarySelect}">`)}
  <div class="abs kicker k" style="left:790px;top:150px">01 · 灯箱 Lightbox</div>
  <div class="abs h2" style="left:784px;top:190px;font-size:104px"><span class="ln hl" data-split>底片，摊在灯箱上</span></div>
  <div class="abs body p" style="left:790px;top:354px;width:1060px">导入照片、RAW 和视频。<b>红蜡笔圈选</b>，批量套配方、批量冲印。</div>
  ${PRINTS.map(([x, y, r, i]) => `<div class="abs print" data-r="${r}" style="left:${x}px;top:${y}px;width:420px;padding:16px 16px 18px;background:#fbfaf4;box-shadow:0 26px 50px rgba(60,40,20,.28), 0 4px 10px rgba(60,40,20,.2)">
    <img src="${A.prints[i]}" style="display:block;width:100%;height:270px;object-fit:cover"></div>`).join('')}
  <svg class="abs draw ring" style="left:1206px;top:668px" width="470" height="350" viewBox="0 0 470 350"><ellipse pathLength="1" cx="235" cy="175" rx="222" ry="160" transform="rotate(-6 235 175)"/></svg>
  <div class="abs hand num" style="left:1640px;top:640px;font-size:96px">3</div>
  `,
  build(tl, root, t) {
    split(root);
    cue('flicker', t);
    tl.fromTo(root, { opacity: 0 }, { keyframes: [{ opacity: .8, duration: .06 }, { opacity: .15, duration: .08 }, { opacity: .9, duration: .06 }, { opacity: .4, duration: .08 }, { opacity: 1, duration: .25 }] }, t);
    tl.fromTo($(root, '.ph'), { y: 140, rotation: -5, autoAlpha: 0 }, { y: 0, rotation: -2, autoAlpha: 1, duration: 1.1, ease: 'expo.out' }, t + .15);
    tl.to($(root, '.ph'), { rotation: -1, y: -10, duration: 4.4, ease: 'sine.inOut' }, t + 1.25);
    up(tl, $(root, '.k'), t + .5, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.hl'), t + .6, { s: .04 });
    up(tl, $(root, '.p'), t + 1.2);
    // prints are tossed onto the table from the right, alternating spin, and settle
    $$(root, '.print').forEach((p, i) => {
      const r = +p.dataset.r, at = t + 1.5 + i * .28;
      tl.fromTo(p, { x: 900, y: -160 + i * 80, rotation: r + (i % 2 ? -38 : 34), autoAlpha: 0 }, { x: 0, y: 0, rotation: r, autoAlpha: 1, duration: .8, ease: 'power4.out' }, at);
      cue('whoosh_down', at, .5);
    });
    // selection mode: crayon ring, handwritten count
    tl.fromTo($(root, '.sel'), { autoAlpha: 0 }, { autoAlpha: 1, duration: .3 }, t + 3.0);
    draw(tl, $(root, '.ring ellipse'), t + 3.1, { d: .55 }); cue('draw', t + 3.1, 1.4);
    tl.fromTo($(root, '.num'), { scale: 0, rotation: -20 }, { scale: 1, rotation: 6, duration: .4, ease: 'back.out(3)' }, t + 3.6); cue('pop', t + 3.6);
    tl.to(root, { opacity: 0, duration: .35, ease: 'power2.in' }, t + this.dur - .35);
  },
};
