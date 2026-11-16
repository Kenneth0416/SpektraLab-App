import { $, $$, split, glyphs, rise, sink, up, out, cue, B } from '../lib.js';
import A from '../assets.js';

// The turn: from a desktop Python tool to a phone. The window itself becomes the handset.
export const PHONE = { x: 782, y: 236, w: 356, h: 760 };

export default {
  id: 's06', chapter: '移植 · PORT', dur: 8 * B, over: 0.1,
  html: `
  <div class="fill dark"></div>
  <div class="abs h2" style="left:0;top:64px;width:1920px;text-align:center;font-size:84px"><span class="ln a" data-split>但它，住在桌面的 Python 里。</span></div>
  <div class="abs h2" style="left:0;top:64px;width:1920px;text-align:center;font-size:84px"><span class="ln b" data-split>我们把它，搬进了手机。</span></div>
  <div class="abs morph" style="left:438px;top:228px;width:1044px;height:652px;border-radius:14px;overflow:hidden;background:#000;border:0px solid #0b0b0c;box-shadow:0 60px 140px rgba(0,0,0,.7), 0 0 0 2px rgba(245,221,210,.16)">
    <img class="abs g" src="assets/spektrafilm/gui_screenshot.jpg" style="inset:0;width:100%;height:100%;object-fit:cover">
    <img class="abs e" src="${A.editorFilm}" style="inset:0;width:100%;height:100%;object-fit:cover">
  </div>
  <div class="abs q mono" style="left:0;top:930px;width:1920px;text-align:center;font-size:22px;color:rgba(245,221,210,.7)">“Full resolution export is very slow at the moment.”<span style="color:var(--dim)">　—— spektrafilm README</span></div>
  `,
  build(tl, root, t) {
    split(root);
    const m = $(root, '.morph');
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .4 }, t);
    tl.fromTo(m, { scale: .92, autoAlpha: 0 }, { scale: 1, autoAlpha: 1, duration: .9, ease: 'expo.out' }, t + .05);
    rise(tl, glyphs(root, '.a'), t + .2, { s: .035 });
    up(tl, $(root, '.q'), t + 1.0, { y: 12, d: .5 });
    sink(tl, glyphs(root, '.a'), t + 2.45, { s: .015, d: .38 });
    out(tl, $(root, '.q'), t + 2.45, { y: 0, d: .3 });
    rise(tl, glyphs(root, '.b'), t + 2.8, { s: .04 });
    cue('whoosh', t + 2.65, 1.2);
    tl.to(m, { left: PHONE.x, top: PHONE.y, width: PHONE.w, height: PHONE.h, borderRadius: 58, borderWidth: 13, duration: 1.15, ease: 'expo.inOut' }, t + 2.7);
    tl.fromTo($(root, '.e'), { autoAlpha: 0 }, { autoAlpha: 1, duration: .45, ease: 'power2.inOut' }, t + 3.15);
    tl.to($(root, '.g'), { scale: 1.6, duration: 1.15, ease: 'expo.inOut' }, t + 2.7);
    sink(tl, glyphs(root, '.b'), t + this.dur - .42, { s: .012, d: .36 });
    cue('rise', t + this.dur);
  },
};
