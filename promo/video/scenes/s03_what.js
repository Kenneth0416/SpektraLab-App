import { $, $$, split, glyphs, rise, up, out, draw, cue, B } from '../lib.js';

// What spektrafilm is, shown on its real desktop GUI.
export default {
  id: 's03', chapter: '起点 · SPEKTRAFILM', dur: 12 * B, over: 0.4,
  html: `
  <div class="fill dark"></div>
  <div class="abs kicker k" style="left:120px;top:236px">它是什么</div>
  <div class="abs h2" style="left:114px;top:284px"><span class="ln a" data-split>把一卷胶片，</span><span class="ln b" data-split>当成物理来算</span></div>
  <div class="abs body p1" style="left:120px;top:570px;width:640px">从相机 RAW 出发，走完<b>负片曝光 → 显影 → 放大机印相 → 相纸 → 扫描</b>的完整流程。</div>
  <div class="abs body p2" style="left:120px;top:770px;width:640px"><b>不是滤镜</b>，也不是凭感觉调出来的 LUT。</div>
  <div class="abs wrap" style="left:850px;top:176px;width:1130px;height:706px">
    <div class="window" style="inset:0">
      <img class="abs g1" src="assets/spektrafilm/gui_screenshot.jpg" style="inset:0">
      <img class="abs g2" src="assets/spektrafilm/gui_grain_magnified.jpg" style="inset:0">
    </div>
    <div class="abs hi" style="left:73.6%;top:43.2%;width:25%;height:7.3%;border:4px solid var(--safe);border-radius:10px;box-shadow:0 0 30px rgba(255,59,34,.5)"></div>
    <svg class="abs draw arrow" style="left:70%;top:-3%" width="200" height="330" viewBox="0 0 200 330"><path pathLength="1" d="M20 10 C 110 60, 150 190, 142 312"/><path pathLength="1" d="M116 284 L142 316 L166 280"/></svg>
    <div class="abs hand n1" style="left:34%;top:-9%;font-size:62px;transform:rotate(-3deg)">胶片 × 相纸档案</div>
    <div class="abs hand n2" style="left:4%;top:102%;font-size:62px;transform:rotate(-2deg)">放大 12 倍：每一颗颗粒都是算出来的</div>
  </div>
  <div class="credit">截图：spektrafilm 桌面 GUI（README）</div>
  `,
  build(tl, root, t) {
    split(root);
    const wrap = $(root, '.wrap');
    tl.fromTo(wrap, { autoAlpha: 0, x: 260, rotationY: -34, rotationX: 8, transformPerspective: 2200, transformOrigin: '0% 50%' },
      { autoAlpha: 1, x: 0, rotationY: -13, rotationX: 3, duration: 1.3, ease: 'expo.out' }, t + .1);
    tl.to(wrap, { x: -46, rotationY: -9, duration: 7.2, ease: 'none' }, t + 1.0);
    up(tl, $(root, '.k'), t + .35, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.a'), t + .5, { s: .05 });
    rise(tl, glyphs(root, '.b'), t + .85, { s: .05 });
    up(tl, $(root, '.p1'), t + 1.5);
    up(tl, $(root, '.p2'), t + 2.5);
    up(tl, $(root, '.credit'), t + 1.6, { y: 0 });
    cue('whoosh', t + .1, .8); cue('pop', t + 2.1); cue('draw', t + 2.45); cue('shutter', t + 4.6, .7);
    // callout on the profile rows
    tl.fromTo($(root, '.hi'), { autoAlpha: 0, scale: 1.25 }, { autoAlpha: 1, scale: 1, duration: .45, ease: 'back.out(2)' }, t + 2.1);
    up(tl, $(root, '.n1'), t + 2.3, { y: 16, d: .5 });
    draw(tl, $$(root, '.arrow path'), t + 2.45, { d: .35, s: .25 });
    // cut to the magnified grain
    tl.to([$(root, '.hi'), $(root, '.n1'), $(root, '.arrow')], { autoAlpha: 0, duration: .25 }, t + 4.5);
    tl.fromTo($(root, '.g2'), { autoAlpha: 0, scale: 1.12 }, { autoAlpha: 1, scale: 1, duration: .7, ease: 'power3.out' }, t + 4.6);
    up(tl, $(root, '.n2'), t + 5.2, { y: 16, d: .5 });
    out(tl, [$(root, '.k'), $(root, '.h2'), $(root, '.p1'), $(root, '.p2'), $(root, '.credit')], t + this.dur - .55, { d: .35 });
    tl.to(wrap, { autoAlpha: 0, scale: .94, duration: .45, ease: 'power2.in' }, t + this.dur - .5);
  },
};
