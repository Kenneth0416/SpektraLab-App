import { $, $$, split, glyphs, rise, up, out, cue, B } from '../lib.js';

const GH = `<svg width="34" height="34" viewBox="0 0 16 16" fill="#F5DDD2"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z"/></svg>`;

// The origin: spektrafilm's own banner, full bleed.
export default {
  id: 's02', chapter: '起点 · SPEKTRAFILM', dur: 10 * B, over: 0.5,
  html: `
  <div class="fill bgw"><img class="abs bg" src="assets/spektrafilm/banner.jpg" style="left:-535px;top:-70px;width:2990px;height:1080px"></div>
  <div class="fill shade" style="background:linear-gradient(180deg, rgba(13,8,7,.38) 0%, transparent 26%, transparent 52%, rgba(13,8,7,.94) 96%)"></div>
  <div class="abs kicker k" style="left:120px;top:722px">Open source · GPLv3 · Python 参考实现</div>
  <div class="abs h2" style="left:116px;top:762px;font-size:84px"><span class="ln hl" data-split>一切，始于一个开源项目</span></div>
  <div class="abs row" style="left:120px;top:912px;display:flex;align-items:center;gap:34px">
    <div class="pill">${GH}<span>github.com/andreavolpato/<b style="color:#fff;font-weight:700">spektrafilm</b></span></div>
    <div class="by" style="font-size:28px;color:#e8cfc4;white-space:nowrap">作者　<b style="font-weight:700;color:#fff">Andrea Volpato</b></div>
  </div>
  <div class="credit">图：spektrafilm 仓库 README</div>
  `,
  build(tl, root, t) {
    split(root);
    tl.fromTo(root, { opacity: 0 }, { opacity: 1, duration: .55, ease: 'power2.out' }, t);
    tl.fromTo($(root, '.bg'), { scale: 1.12, x: 30 }, { scale: 1.07, x: 0, duration: 6.9, ease: 'power2.out' }, t);
    tl.fromTo($(root, '.bg'), { filter: 'blur(26px) brightness(1.5)' }, { filter: 'blur(0px) brightness(1)', duration: 1.1, ease: 'power2.out' }, t);
    up(tl, $(root, '.k'), t + 1.5, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.hl'), t + 1.65, { s: .04 });
    up(tl, [$(root, '.pill'), $(root, '.by')], t + 2.5, { y: 22, s: .14 });
    up(tl, $(root, '.credit'), t + 3.0, { y: 0, d: .6 });
    out(tl, [$(root, '.k'), $(root, '.h2'), $(root, '.row'), $(root, '.credit')], t + this.dur - .55, { d: .4 });
    tl.to($(root, '.bg'), { filter: 'blur(30px) brightness(.4)', duration: .6, ease: 'power2.in' }, t + this.dur - .5);
  },
};
