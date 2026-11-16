import { $, $$, split, glyphs, rise, up, out, phone, cue, B } from '../lib.js';
import A from '../assets.js';
import { PHONE } from './s06_bridge.js';

// The app's launcher icon (ic_launcher_foreground.xml), redrawn as SVG.
export const ICON = (size) => `<svg width="${size}" height="${size}" viewBox="14 14 80 80">
  <rect x="14" y="14" width="80" height="80" rx="18" fill="#1B1A17" stroke="rgba(245,221,210,.28)" stroke-width="1"/>
  <path fill="#2a2823" d="M28,34h52v40H28z"/><path fill="#F5DDD2" d="M34,40h40v28H34z"/>
  <path fill="#E9C48A" d="M34,56 L46,46 L58,58 L64,52 L74,62 L74,68 L34,68z"/><circle cx="66" cy="44" r="4" fill="#D4281C"/>
  <path fill="#F5F3EB" d="M31,28h4v4h-4z M39,28h4v4h-4z M47,28h4v4h-4z M55,28h4v4h-4z M63,28h4v4h-4z M71,28h4v4h-4z M31,76h4v4h-4z M39,76h4v4h-4z M47,76h4v4h-4z M55,76h4v4h-4z M63,76h4v4h-4z M71,76h4v4h-4z"/></svg>`;

export default {
  id: 's07', chapter: 'SPEKTRALAB', dur: 8 * B, over: 0.4,
  html: `
  <div class="fill dark"></div>
  <div class="abs halo" style="left:820px;top:-160px;width:1400px;height:1400px;border-radius:50%;background:radial-gradient(circle, rgba(255,59,34,.30) 0%, rgba(255,59,34,.07) 40%, transparent 66%)"></div>
  ${phone('ph', PHONE.x, PHONE.y, PHONE.h, A.editorFilm)}
  <div class="abs ic" style="left:150px;top:214px">${ICON(150)}</div>
  <div class="abs wm" style="left:144px;top:400px;font-family:var(--mono);font-weight:700;font-size:126px;letter-spacing:-.045em;color:#fff;white-space:nowrap"><span class="ln w" data-split>SpektraLab</span></div>
  <div class="abs h3 tag" style="left:150px;top:574px;font-size:54px;font-weight:900"><span class="ln tg" data-split>把一整间暗房，装进手机</span></div>
  <div class="abs based" style="left:150px;top:700px;display:flex;align-items:center;gap:20px">
    <div class="pill" style="font-family:var(--sans);font-size:32px;font-weight:500;padding:16px 32px;border-color:rgba(255,59,34,.7)">基于 <b style="font-family:var(--mono);font-weight:700;color:#fff;margin:0 10px">spektrafilm</b> 开发</div>
  </div>
  <div class="abs sbar spectrum" style="left:150px;top:806px;width:640px;height:6px;border-radius:3px;transform-origin:0 50%"></div>
  <div class="abs meta mono" style="left:150px;top:836px;font-size:21px;letter-spacing:.14em;color:rgba(245,221,210,.72)">安卓原生 · Kotlin + OpenGL ES 移植 · 开源 GPLv3</div>
  <div class="fill white" style="background:#FFF3EA"></div>
  `,
  build(tl, root, t) {
    split(root);
    cue('boom', t); cue('shutter', t, .8);
    tl.fromTo($(root, '.white'), { opacity: .9 }, { opacity: 0, duration: .7, ease: 'power2.out' }, t);
    const ph = $(root, '.ph');
    tl.to(ph, { left: 1270, top: 150, rotation: 5, duration: 1.3, ease: 'expo.out' }, t + .05);
    tl.to(ph, { y: -16, rotation: 4, duration: 4.2, ease: 'sine.inOut' }, t + 1.2);
    tl.fromTo($(root, '.halo'), { scale: .6, autoAlpha: 0 }, { scale: 1, autoAlpha: 1, duration: 1.4, ease: 'expo.out' }, t);
    tl.fromTo($(root, '.ic'), { scale: 0, rotation: -30 }, { scale: 1, rotation: 0, duration: .8, ease: 'back.out(1.8)' }, t + .2);
    rise(tl, glyphs(root, '.w'), t + .35, { s: .045, d: .9 });
    rise(tl, glyphs(root, '.tg'), t + .95, { s: .035 });
    up(tl, $(root, '.based'), t + 1.6, { y: 24 }); cue('pop', t + 1.6);
    tl.fromTo($(root, '.sbar'), { scaleX: 0 }, { scaleX: 1, duration: 1.1, ease: 'expo.out' }, t + 1.9);
    up(tl, $(root, '.meta'), t + 2.2, { y: 12, d: .5 });
    out(tl, [$(root, '.ic'), $(root, '.wm'), $(root, '.tag'), $(root, '.based'), $(root, '.sbar'), $(root, '.meta')], t + this.dur - .5, { d: .35, s: .02 });
    tl.to(ph, { autoAlpha: 0, x: 120, duration: .4, ease: 'power2.in' }, t + this.dur - .45);
  },
};
