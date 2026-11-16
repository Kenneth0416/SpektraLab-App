// Small helpers shared by the scenes. Everything is driven by one paused GSAP timeline so that
// any frame can be rendered on its own (no CSS animations, no wall-clock time).
export const $ = (root, sel) => root.querySelector(sel);
export const $$ = (root, sel) => [...root.querySelectorAll(sel)];

/** Wraps every character of [data-split] elements in a clipping box so it can rise into place. */
export function split(root) {
  for (const el of $$(root, '[data-split]')) {
    const text = el.textContent; el.textContent = '';
    for (const ch of text) {
      if (ch === ' ') { const s = document.createElement('span'); s.className = 'sp'; el.appendChild(s); continue; }
      const c = document.createElement('span'); c.className = 'c';
      const i = document.createElement('i'); i.textContent = ch; c.appendChild(i); el.appendChild(c);
    }
  }
}
export const glyphs = (root, sel) => $$(root, `${sel} .c > i`);

/** Characters rise from below their own baseline box. */
export function rise(tl, els, at, o = {}) {
  tl.fromTo(els, { yPercent: 115 }, { yPercent: 0, duration: o.d ?? 0.8, ease: o.ease ?? 'expo.out', stagger: o.s ?? 0.04 }, at);
}
export function sink(tl, els, at, o = {}) {
  tl.to(els, { yPercent: -115, duration: o.d ?? 0.5, ease: 'power3.in', stagger: o.s ?? 0.02 }, at);
}
/** Soft entrance for blocks. */
export function up(tl, els, at, o = {}) {
  tl.fromTo(els, { autoAlpha: 0, y: o.y ?? 36 }, { autoAlpha: 1, y: 0, duration: o.d ?? 0.8, ease: 'power3.out', stagger: o.s ?? 0.08 }, at);
}
export function out(tl, els, at, o = {}) {
  tl.to(els, { autoAlpha: 0, y: o.y ?? -24, duration: o.d ?? 0.45, ease: 'power2.in', stagger: o.s ?? 0.03 }, at);
}
/** Stroke draws itself (elements need pathLength="1"). */
export function draw(tl, els, at, o = {}) {
  tl.fromTo(els, { strokeDasharray: 1, strokeDashoffset: 1, autoAlpha: 0 },
    { strokeDashoffset: 0, autoAlpha: 1, duration: o.d ?? 0.6, ease: o.ease ?? 'power2.inOut', stagger: o.s ?? 0.1 }, at);
}
/** Four red crop marks around a box, like the editor's preview corners. */
export function cropmarks(x, y, w, h, cls = '') {
  return `<div class="abs cm ${cls}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px">
    <div class="cropmark a" style="left:0;top:0"></div><div class="cropmark b" style="right:0;top:0"></div>
    <div class="cropmark c" style="left:0;bottom:0"></div><div class="cropmark d" style="right:0;bottom:0"></div></div>`;
}
export function phone(cls, x, y, h, src, extra = '') {
  const w = Math.round((h - 26) * 9 / 20 + 26);
  return `<div class="phone ${cls}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px"><div class="screen"><img src="${src}">${extra}</div></div>`;
}
/** Deterministic PRNG so grain is identical between runs and between render workers. */
export function mulberry(a) {
  return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
}

/** Sound cues: scenes mark where the soundtrack should place an effect (see audio/soundtrack.py). */
export function cue(type, at, gain = 1) { (window.__cues ||= []).push({ type, t: +at.toFixed(3), gain }); }

/** One beat of the soundtrack (90 BPM). Scene lengths are whole beats so cuts land on the music. */
export const B = 60 / 90;
