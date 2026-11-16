import { $, $$, split, glyphs, rise, up, out, cue, B } from '../lib.js';

// The pipeline, drawn fresh (the README's figure is adapted from a textbook, so it is not reused).
const X0 = 210, DX = 300, Y = 452;
const STEPS = [
  ['场景光', 'RAW 线性数据'], ['负片曝光', '光谱感光度'], ['显影', '成色剂 · 颗粒 · 光晕'],
  ['放大机印相', '黄 / 品红滤色'], ['相纸显影', '相纸特性曲线'], ['扫描', '成片'],
];

export default {
  id: 's05', chapter: '流程 · PIPELINE', dur: 12 * B, over: 0.4,
  html: `
  <div class="fill dark"></div>
  <div class="abs kicker k" style="left:120px;top:150px">The pipeline · 暗房里的每一步</div>
  <div class="abs h2" style="left:114px;top:188px;font-size:84px"><span class="ln hl" data-split>一张照片，走完整条暗房流程</span></div>
  <div class="abs rail" style="left:${X0}px;top:${Y - 2}px;width:${DX * 5}px;height:4px;background:rgba(245,221,210,.16)"></div>
  <div class="abs beam spectrum" style="left:${X0}px;top:${Y - 3}px;width:${DX * 5}px;height:6px;transform-origin:0 50%;box-shadow:0 0 24px rgba(255,120,60,.5)"></div>
  ${STEPS.map(([a, b], i) => `
    <div class="abs node" style="left:${X0 + i * DX - 34}px;top:${Y - 34}px;width:68px;height:68px;border-radius:50%;border:3px solid rgba(245,221,210,.4);background:var(--dark)">
      <div class="abs core" style="inset:9px;border-radius:50%;background:var(--safe);box-shadow:0 0 26px 6px rgba(255,59,34,.6)"></div></div>
    <div class="abs lbl" style="left:${X0 + i * DX - 150}px;top:${Y + 58}px;width:300px;text-align:center">
      <div class="mono" style="font-size:18px;color:var(--safe);letter-spacing:.2em">0${i + 1}</div>
      <div class="h3" style="font-size:40px;margin-top:6px">${a}</div>
      <div style="font-size:23px;color:var(--dim);margin-top:6px;white-space:nowrap">${b}</div>
    </div>`).join('')}
  <div class="abs stages" style="left:0;top:690px;width:1920px;height:300px"></div>
  `,
  build(tl, root, t) {
    split(root);
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .45 }, t);
    up(tl, $(root, '.k'), t + .2, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.hl'), t + .3, { s: .03 });
    tl.fromTo($(root, '.rail'), { scaleX: 0, transformOrigin: '0 50%' }, { scaleX: 1, duration: .9, ease: 'expo.out' }, t + .7);
    tl.fromTo($$(root, '.node'), { scale: 0 }, { scale: 1, duration: .5, ease: 'back.out(2.2)', stagger: .07 }, t + .8);
    const T0 = t + 1.4, STEP = B;
    tl.fromTo($(root, '.beam'), { scaleX: 0 }, { scaleX: 1, duration: STEP * 5, ease: 'none' }, T0);
    $$(root, '.node').forEach((n, i) => {
      const at = T0 + i * STEP - .05;
      tl.fromTo(n.querySelector('.core'), { scale: 0 }, { scale: 1, duration: .35, ease: 'back.out(3)' }, at);
      tl.to(n, { borderColor: '#FF3B22', duration: .2 }, at);
      up(tl, $$(root, '.lbl')[i], at, { y: 18, d: .45 }); cue('pop', at, 1 + i * .1);
    });
    this.stages?.(tl, root, T0, STEP);
    tl.to(root, { opacity: 0, duration: .4, ease: 'power2.in' }, t + this.dur - .35);
  },
};
