import { $, $$, split, glyphs, rise, up, out, cue, B, V } from '../lib.js';

// The port is checked stage by stage against spektrafilm itself (README, "验证").
export default {
  id: 's12', chapter: '05 · 对照', dur: 10 * B, over: 0.35,
  html: `
  <div class="fill dark"></div>
  <div class="abs kicker k" style="left:0;top:190px;width:1920px;text-align:center">逐级对照 spektrafilm 原版</div>
  <div class="abs num" style="left:0;top:250px;width:1920px;text-align:center;font-family:var(--mono);font-weight:700;font-size:330px;letter-spacing:-.06em;color:#fff;line-height:1.1"><span class="n">1.00</span><span style="color:var(--safe)">%</span></div>
  <div class="abs bar spectrum" style="left:560px;top:654px;width:800px;height:8px;border-radius:4px"></div>
  <div class="abs h3 l1" style="left:0;top:700px;width:1920px;text-align:center;font-size:52px">输出与原版的平均误差（sRGB）</div>
  <div class="abs l2 mono" style="left:0;top:800px;width:1920px;text-align:center;font-size:23px;letter-spacing:.1em;color:rgba(245,221,210,.72)">8 组 胶片 × 相纸 × 参数　·　胶片密度误差 &lt; 0.001　·　离线验证</div>
  `,
  build(tl, root, t) {
    const n = $(root, '.n'), v = { x: 1 };
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .35 }, t);
    up(tl, $(root, '.k'), t + .15, { y: 14, d: .5 });
    tl.fromTo($(root, '.num'), { scale: .86, autoAlpha: 0 }, { scale: 1, autoAlpha: 1, duration: .8, ease: 'expo.out' }, t + .2);
    // VO: 准不准？(1.5) 我们和原版逐级对照过，平均误差，(4.9) 只有万分之四。
    tl.to(v, { x: .04, duration: 3.0, ease: 'power3.out', onUpdate: () => { n.textContent = v.x.toFixed(2); } }, t + V + 1.5);
    for (let i = 0; i < 7; i++) cue('tick', t + V + 1.5 + i * .18 * (1 + i * .25), .8);
    tl.fromTo($(root, '.bar'), { scaleX: 0 }, { scaleX: 1, duration: 1.0, ease: 'expo.out' }, t + V + 3.6);
    up(tl, [$(root, '.l1'), $(root, '.l2')], t + V + 3.9, { s: 1.0, y: 20 });
    tl.to(root, { opacity: 0, duration: .35, ease: 'power2.in' }, t + this.dur - .35);
  },
};
