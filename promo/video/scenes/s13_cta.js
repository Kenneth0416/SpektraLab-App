import { $, $$, split, glyphs, rise, up, out, draw, phone, cue, B, V } from '../lib.js';
import A from '../assets.js';
import { ICON } from './s07_logo.js';
import QR from '../assets/qr.js';

export const URL_TEXT = 'silvercam.convert-md.com';

// Call to action: the download page, the address, a code to scan, and the credit spektrafilm asks for.
export default {
  id: 's13', chapter: '下载 · DOWNLOAD', dur: 16 * B, over: 0, cls: 'lightbox',
  html: `
  <div class="abs browser" style="left:96px;top:150px;width:900px;height:700px;border-radius:16px;overflow:hidden;background:#fff;box-shadow:0 50px 110px rgba(60,40,20,.34), 0 0 0 1.5px rgba(27,26,23,.14)">
    <div class="abs" style="left:0;top:0;width:100%;height:52px;background:#e9e5d8;display:flex;align-items:center;gap:9px;padding-left:20px">
      <i style="width:13px;height:13px;border-radius:50%;background:#d4281c"></i><i style="width:13px;height:13px;border-radius:50%;background:#e9a65a"></i><i style="width:13px;height:13px;border-radius:50%;background:#8a8679"></i>
      <div class="mono" style="margin-left:22px;flex:1;margin-right:20px;height:32px;border-radius:16px;background:#f8f6ef;display:flex;align-items:center;padding-left:18px;font-size:16px;color:#4a463e;letter-spacing:0">https://${URL_TEXT}</div>
    </div>
    <div class="abs" style="left:0;top:52px;width:100%;height:648px;overflow:hidden"><img class="pg" src="${A.site}" style="display:block;width:100%"></div>
  </div>
  <div class="abs kicker k" style="left:1080px;top:168px">现在，人人都可以体验</div>
  <div class="abs h2" style="left:1074px;top:206px;font-size:104px"><span class="ln hl" data-split>去冲一卷试试</span></div>
  <div class="abs url" style="left:1080px;top:392px;font-family:var(--mono);font-weight:700;font-size:49px;letter-spacing:-.04em;color:var(--ink);white-space:nowrap"><span class="ln u" data-split>${URL_TEXT}</span></div>
  <svg class="abs draw ul" style="left:1074px;top:452px" width="760" height="30" viewBox="0 0 760 30"><path pathLength="1" d="M6 16 C 180 4, 420 26, 752 10"/></svg>
  <div class="abs qr" style="left:1080px;top:520px;width:250px;height:250px;padding:16px;background:#fff;box-shadow:0 18px 40px rgba(60,40,20,.2)">${QR}</div>
  <div class="abs side" style="left:1372px;top:528px;width:460px">
    <div style="display:flex;align-items:center;gap:18px">${ICON(72)}<div style="font-family:var(--mono);font-weight:700;font-size:40px;letter-spacing:-.04em">SilverCam</div></div>
    <div class="body" style="font-size:27px;margin-top:22px;line-height:1.75">安卓 10 及以上<br>开源 · GPLv3<br><span class="hand" style="font-size:44px">扫码，或者直接输网址</span></div>
  </div>
  <div class="abs foot" style="left:96px;top:912px;width:1728px;display:flex;justify-content:space-between;align-items:center;border-top:2px solid rgba(27,26,23,.16);padding-top:26px">
    <div class="mono" style="font-size:22px;letter-spacing:.02em;color:var(--ink)">Film modeling powered by <b style="font-weight:700">spektrafilm</b> by Andrea Volpato</div>
    <div style="text-align:right"><div class="mono" style="font-size:19px;color:var(--grey)">github.com/andreavolpato/spektrafilm</div><div style="font-size:17px;color:var(--grey);margin-top:6px">样片原图：Aabhas Lall（CC BY-SA 4.0）等，名单见下载页</div></div>
  </div>
  `,
  build(tl, root, t) {
    split(root);
    cue('flicker', t);
    tl.fromTo(root, { opacity: 0 }, { keyframes: [{ opacity: .8, duration: .06 }, { opacity: .15, duration: .08 }, { opacity: .9, duration: .06 }, { opacity: .4, duration: .08 }, { opacity: 1, duration: .25 }] }, t);
    const br = $(root, '.browser');
    tl.fromTo(br, { y: 120, autoAlpha: 0, rotationY: 16, transformPerspective: 2000 }, { y: 0, autoAlpha: 1, rotationY: 5, duration: 1.2, ease: 'expo.out' }, t + .15);
    tl.to(br, { rotationY: 2, duration: 8, ease: 'none' }, t + 1.35);
    // the page scrolls on its own, slowly
    tl.fromTo($(root, '.pg'), { y: 0 }, { y: () => -Math.max(0, $(root, '.pg').offsetHeight - 648), duration: 7.6, ease: 'sine.inOut' }, t + 2.0);
    up(tl, $(root, '.k'), t + .5, { y: 14, d: .5 });
    rise(tl, glyphs(root, '.hl'), t + .6, { s: .05 });
    // VO: 现在，谁都可以来试试。(2.8) 打开 silvercam.convert-md.com，(7.1) 去冲一卷吧。
    rise(tl, glyphs(root, '.u'), t + V + 2.8, { s: .022, d: .6 });
    draw(tl, $(root, '.ul path'), t + V + 4.4, { d: .6 }); cue('draw', t + V + 4.4, 1.4);
    tl.fromTo($(root, '.qr'), { scale: .7, autoAlpha: 0, rotation: -8 }, { scale: 1, autoAlpha: 1, rotation: -2, duration: .7, ease: 'back.out(1.8)' }, t + V + 5.0); cue('pop', t + V + 5.0);
    up(tl, $(root, '.side'), t + V + 5.3, { y: 20 });
    up(tl, $(root, '.foot'), t + V + 6.2, { y: 16, d: .7 });
  },
};
