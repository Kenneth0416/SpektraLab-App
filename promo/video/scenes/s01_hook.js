import { $, $$, split, glyphs, rise, sink, up, draw, cue, B, V } from '../lib.js';

// Cold open in the darkroom: the claim the whole film argues for.
// VO: 你有没有想过，(1.8) 胶片的颜色，其实不是调出来的。(4.8) 是算出来的。
export default {
  id: 's01', chapter: 'SILVERCAM · 暗房', dur: 12 * B, over: 0.7, cls: '',
  html: `
  <div class="fill dark"></div>
  <div class="abs glowdot" style="left:-180px;top:-140px;width:900px;height:900px;border-radius:50%;background:radial-gradient(circle, rgba(255,59,34,.34) 0%, rgba(255,59,34,.08) 38%, transparent 68%)"></div>
  <div class="abs lamp" style="left:200px;top:246px;width:22px;height:22px;border-radius:50%;background:var(--safe);box-shadow:0 0 28px 8px rgba(255,59,34,.75)"></div>
  <div class="abs kicker k" style="left:246px;top:244px">Safelight on · 安全灯</div>
  <div class="abs h1" style="left:192px;top:340px"><span class="ln l1" data-split>胶片的颜色，</span></div>
  <div class="abs h1" style="left:192px;top:524px"><span class="ln l2a" data-split>不是调出来的。</span></div>
  <svg class="abs draw strike" style="left:180px;top:560px" width="1090" height="120" viewBox="0 0 1090 120">
    <path pathLength="1" d="M10 70 C 220 40, 520 86, 1070 52"/></svg>
  <div class="abs h1 accent" style="left:192px;top:524px"><span class="ln l2b" data-split>是算出来的。</span></div>
  <div class="abs bar spectrum" style="left:200px;top:760px;width:1180px;height:10px;border-radius:5px;transform-origin:0 50%"></div>
  <div class="abs ticks mono" style="left:200px;top:788px;width:1180px;font-size:17px;color:rgba(245,221,210,.6)">
    ${[380, 480, 580, 680, 780].map((n, i) => `<span class="abs tk" style="white-space:nowrap;left:${i * 24.4}%">${n}${i === 4 ? '&nbsp;nm' : ''}</span>`).join('')}
  </div>
  <div class="abs sub mono" style="left:200px;top:850px;font-size:22px;letter-spacing:.2em;color:rgba(245,221,210,.75)">SPECTRAL FILM SIMULATION · 光谱胶片模拟</div>
  `,
  build(tl, root, t) {
    split(root);
    const lamp = $(root, '.lamp'), glow = $(root, '.glowdot');
    // the safelight strikes like a tube: two false starts, then steady and breathing
    tl.fromTo([lamp, glow], { autoAlpha: 0 }, { keyframes: [{ autoAlpha: .9, duration: .05 }, { autoAlpha: .1, duration: .07 }, { autoAlpha: .7, duration: .05 }, { autoAlpha: .2, duration: .08 }, { autoAlpha: 1, duration: .25 }] }, t + .25);
    tl.fromTo(glow, { scale: .92 }, { scale: 1.08, duration: 1.6, ease: 'sine.inOut', repeat: 3, yoyo: true }, t + .8);
    const end = t + this.dur - .75, v = t + V;
    cue('flicker', t + .25); cue('draw', v + 3.6, 1.4); cue('whoosh', end - .1);
    up(tl, $(root, '.k'), t + .55, { y: 10, d: .5 });
    rise(tl, glyphs(root, '.l1'), v + 1.7, { s: .055 });
    rise(tl, glyphs(root, '.l2a'), v + 2.5, { s: .05 });
    draw(tl, $(root, '.strike path'), v + 3.6, { d: .45, ease: 'power3.in' });
    sink(tl, glyphs(root, '.l2a'), v + 4.3, { s: .018, d: .4 });
    tl.to($(root, '.strike'), { autoAlpha: 0, y: -90, duration: .4, ease: 'power3.in' }, v + 4.3);
    rise(tl, glyphs(root, '.l2b'), v + 4.6, { s: .06, d: .9 });
    tl.fromTo($(root, '.bar'), { scaleX: 0 }, { scaleX: 1, duration: 1.2, ease: 'expo.out' }, v + 4.9);
    tl.fromTo($$(root, '.tk'), { autoAlpha: 0, y: -8 }, { autoAlpha: 1, y: 0, duration: .4, stagger: .09 }, v + 5.1);
    up(tl, $(root, '.sub'), v + 5.5, { y: 14, d: .6 });
    // exit: text lifts away, the spectrum line opens into the whole frame
    sink(tl, [...glyphs(root, '.l1'), ...glyphs(root, '.l2b')], end, { s: .012, d: .42 });
    tl.to([$(root, '.k'), $(root, '.ticks'), $(root, '.sub'), lamp], { autoAlpha: 0, duration: .3 }, end);
    tl.to($(root, '.bar'), { left: -200, top: -200, width: 2320, height: 1480, borderRadius: 0, filter: 'blur(60px) saturate(1.3)', duration: .85, ease: 'expo.inOut' }, end + .1);
  },
};
