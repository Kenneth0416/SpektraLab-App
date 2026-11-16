import { $, $$, split, glyphs, rise, up, out, cue, B } from '../lib.js';
import A from '../assets.js';

// Three physical effects, each shown off -> on with a real render from the app's engine.
const CARDS = [
  ['光晕', '片基反射 + 乳剂散射，高光才会泛红（演示：强度 ×3）', A.halOff, A.halOn],
  ['颗粒', '逐层的泊松随机模型，随画幅缩放（演示：半格 ×1.6）', A.grainOff, A.grainOn],
  ['片边 · 日期', '片边颜色，是片基走完印放链路的真实颜色', A.borderOff, A.borderOn, 'contain'],
];
const W = 540, H = 560, X = [120, 690, 1260], Y = 300;

export default {
  id: 's10', chapter: '03 · 细节', dur: 10 * B, over: 0.35,
  html: `
  <div class="fill dark"></div>
  <div class="abs kicker k" style="left:120px;top:110px">03 · 细节 Details</div>
  <div class="abs h2" style="left:114px;top:148px;font-size:86px"><span class="ln hl" data-split>光晕、颗粒、片边，都按物理来</span></div>
  ${CARDS.map(([a, b, off, on, fit = 'cover'], i) => `
    <div class="abs card" style="left:${X[i]}px;top:${Y}px;width:${W}px;height:${H}px;overflow:hidden;background:#000;box-shadow:0 40px 90px rgba(0,0,0,.6)">
      <img class="abs" src="${off}" style="inset:0;width:100%;height:100%;object-fit:${fit}">
      <img class="abs on" src="${on}" style="inset:0;width:100%;height:100%;object-fit:${fit};background:#0d0807">
      <div class="abs bar" style="left:0;top:0;width:4px;height:100%;background:var(--safe);box-shadow:0 0 20px 4px rgba(255,59,34,.8)"></div>
      <div class="abs st mono" style="left:18px;bottom:18px;font-size:18px;letter-spacing:.16em;padding:8px 14px;background:rgba(13,8,7,.75);color:#fff"><span class="so">OFF</span></div>
    </div>
    <div class="abs cap" style="left:${X[i]}px;top:${Y + H + 28}px;width:${W}px">
      <div class="h3" style="font-size:46px"><span class="mono" style="font-size:20px;color:var(--safe);vertical-align:middle;margin-right:14px">0${i + 1}</span>${a}</div>
      <div style="font-size:24px;color:var(--dim);margin-top:8px;white-space:nowrap">${b}</div>
    </div>`).join('')}
  `,
  build(tl, root, t) {
    split(root);
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .4 }, t);
    up(tl, $(root, '.k'), t + .2, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.hl'), t + .3, { s: .03 });
    tl.fromTo($$(root, '.card'), { y: 90, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: .9, ease: 'expo.out', stagger: .12 }, t + .5);
    up(tl, $$(root, '.cap'), t + .9, { s: .12, y: 20 });
    $$(root, '.card').forEach((c, i) => {
      const at = t + (2.5 + i * 2) * B, on = c.querySelector('.on'), bar = c.querySelector('.bar');
      tl.set(on, { clipPath: 'inset(0 100% 0 0)' }, t);
      tl.set(bar, { autoAlpha: 0 }, t);
      tl.set(bar, { autoAlpha: 1 }, at);
      tl.to(on, { clipPath: 'inset(0 0% 0 0)', duration: .9, ease: 'power3.inOut' }, at);
      tl.fromTo(bar, { left: 0 }, { left: W, duration: .9, ease: 'power3.inOut' }, at);
      tl.set(bar, { autoAlpha: 0 }, at + .9);
      tl.set(c.querySelector('.so'), { textContent: 'ON', color: '#FF3B22' }, at + .45);
      tl.fromTo(on, { scale: 1.0 }, { scale: 1.06, duration: 4, ease: 'none' }, at);
      cue('whoosh', at, .7); cue('tick', at + .45);
    });
    tl.to(root, { opacity: 0, duration: .35, ease: 'power2.in' }, t + this.dur - .35);
  },
};
