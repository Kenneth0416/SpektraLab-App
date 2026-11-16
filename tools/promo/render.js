// Runs real photos through the app's film shaders in headless Chromium (WebGL2 /
// SwiftShader): the pass order of FilmRenderer.render with the look tables that
// PromoDumpTest wrote. Usage: node render.js <dumpDir> <srcDir> <outDir> [filter]
const { chromium } = require('playwright');
const fs = require('fs');
const { jobs } = require('./jobs.js');
const [dump, srcDir, outDir, filter] = process.argv.slice(2);

(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  page.on('console', m => console.log('page:', m.text()));
  const shaders = {};
  for (const n of ['vertex', 'expose', 'down', 'blur', 'combine', 'corr', 'develop', 'final']) shaders[n] = fs.readFileSync(`${dump}/${n}.glsl`, 'utf8');
  fs.mkdirSync(outDir, { recursive: true });
  const index = [];
  for (const j of jobs) {
    if (filter && !j.out.includes(filter)) continue;
    const c = JSON.parse(fs.readFileSync(`${dump}/look_${j.out}.json`, 'utf8'));
    const jpeg = fs.readFileSync(`${srcDir}/${j.photo}.jpg`).toString('base64');
    const t0 = Date.now();
    const r = await page.evaluate(render, { shaders, c, f: j.framing, jpeg, seed: 1000 + index.length });
    if (r.error) { console.log(j.out, 'ERROR', r.error); continue; }
    fs.writeFileSync(`${outDir}/${j.out}.png`, Buffer.from(r.png, 'base64'));
    index.push({ file: j.out, photo: j.photo, film: c.film, paper: c.paper || '(scan film)', look: j.framing.look, autoEv: r.autoEv,
      width: j.framing.ow, height: j.framing.oh, imageRect: j.framing.rect, crop: j.crop || null, frame: j.frame || null });
    console.log(j.out, `${j.framing.ow}x${j.framing.oh}`, 'autoEv', r.autoEv.toFixed(2), 'gl', r.glError, `${Date.now() - t0} ms`);
  }
  fs.writeFileSync(`${outDir}/rendered.json`, JSON.stringify(index, null, 1));
  await browser.close();
})();

async function render({ shaders, c, f, jpeg, seed }) {
  const cv = document.createElement('canvas');
  const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true });
  if (!gl) return { error: 'no webgl2' };
  if (!gl.getExtension('EXT_color_buffer_float')) return { error: 'no color_buffer_float' };
  gl.getExtension('OES_texture_float_linear');
  const compile = fsrc => {
    const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); return sh; };
    const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, shaders.vertex)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fsrc));
    gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p)); return p;
  };
  const P = {};
  try { for (const n of Object.keys(shaders)) if (n !== 'vertex') P[n] = compile(shaders[n]); } catch (e) { return { error: e.message }; }
  const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const vao = gl.createVertexArray(); gl.bindVertexArray(vao); gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
  const draw = () => gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  function tex2d(w, h, data, internal = gl.RGBA16F) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    if (internal === gl.RGBA8) gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
    else gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, gl.RGBA, gl.FLOAT, data);
    return t;
  }
  function target(w, h, internal = gl.RGBA16F) {
    const t = tex2d(w, h, null, internal);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo');
    return { t, fb, w, h };
  }
  const bind = tg => { gl.bindFramebuffer(gl.FRAMEBUFFER, tg.fb); gl.viewport(0, 0, tg.w, tg.h); };
  let unit = 0;
  const use = p => { gl.useProgram(p); unit = 0; };
  const L = (p, n) => gl.getUniformLocation(p, n);
  function sampler(p, n, t, tgt = gl.TEXTURE_2D) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(tgt, t); gl.uniform1i(L(p, n), unit); unit++; }
  const T3 = m => [m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]];
  const rgba = (a, ch) => { const n = a.length / ch, o = new Float32Array(n * 4); for (let i = 0; i < n; i++) for (let k = 0; k < 4; k++) o[i * 4 + k] = k < ch ? a[i * ch + k] : 1; return o; };
  const fr = Math.fround;

  // ---- source photo: sRGB bitmap uploaded top row first as GL_SRGB8_ALPHA8 (GlWorker.uploadSrgb)
  const blob = await (await fetch('data:image/jpeg;base64,' + jpeg)).blob();
  const bmp = await createImageBitmap(blob, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' });
  const SW = bmp.width, SH = bmp.height;
  const input = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, input);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.SRGB8_ALPHA8, gl.RGBA, gl.UNSIGNED_BYTE, bmp);

  // ---- auto exposure: MediaStore.autoExposure(Bitmap) + AutoExposure.centerWeightedEv
  const s = 192 / Math.max(SW, SH), aw = Math.max(1, Math.floor(SW * s)), ah = Math.max(1, Math.floor(SH * s));
  const c2 = new OffscreenCanvas(aw, ah).getContext('2d'); c2.imageSmoothingQuality = 'high'; c2.drawImage(bmp, 0, 0, aw, ah);
  const sp = c2.getImageData(0, 0, aw, ah).data;
  const lin = v => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  let sum = 0, wsum = 0; const le = Math.max(aw, ah), nw = aw / le, nh = ah / le;
  for (let y = 0; y < ah; y++) for (let x = 0; x < aw; x++) {
    const xx = (x / aw - 0.5) * nw, yy = (y / ah - 0.5) * nh, m = Math.exp(-(xx * xx + yy * yy) / (2 * 0.2 * 0.2)), i = (y * aw + x) * 4;
    sum += m * (0.2126 * lin(sp[i]) + 0.7152 * lin(sp[i + 1]) + 0.0722 * lin(sp[i + 2])); wsum += m;
  }
  const expo = sum / wsum / 0.184;
  const autoEv = expo > 0 ? Math.min(8, Math.max(-8, -Math.log2(expo))) : 0;

  // ---- look tables (FilmRenderer.setLook)
  const texTc = tex2d(c.tcSize, c.tcSize, rgba(c.tcLut, 3));
  const texCurves = tex2d(c.curveSize, 1, rgba(c.curves, 3));
  const texCurves0 = tex2d(c.curveSize, 1, rgba(c.curves0, 3));
  const texLayers = tex2d(c.grain.size, 3, rgba(c.grain.table, 3));
  const zero = tex2d(1, 1, new Float32Array(4));
  const lut = gl.createTexture(); gl.bindTexture(gl.TEXTURE_3D, lut);
  for (const pn of [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER]) gl.texParameteri(gl.TEXTURE_3D, pn, gl.LINEAR);
  for (const pn of [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T, gl.TEXTURE_WRAP_R]) gl.texParameteri(gl.TEXTURE_3D, pn, gl.CLAMP_TO_EDGE);
  gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGBA16F, c.lutN, c.lutN, c.lutN, 0, gl.RGBA, gl.FLOAT, rgba(c.lut, 3));

  // ---- FilmRenderer.render
  const W = f.iw, H = f.ih;
  const uv = [f.crop[2] - f.crop[0], 0, f.crop[0], 0, -(f.crop[3] - f.crop[1]), 1 - f.crop[1], 0, 0, 1]; // Framing.bitmapUvTransform
  const curvesU = p => { sampler(p, 'uCurves', texCurves); sampler(p, 'uCurves0', texCurves0);
    gl.uniform2f(L(p, 'uCurveAxis'), c.curveX0, c.curveX1); gl.uniform1f(L(p, 'uCurveSize'), c.curveSize); };
  function blurPass(srcT, dst, sigma, dx, dy) {
    const R = Math.min(28, Math.max(1, Math.ceil(3 * sigma)));
    const g = []; for (let i = 0; i <= R + 1; i++) g.push(i <= R ? Math.exp(-i * i / (2 * sigma * sigma)) : 0);
    let norm = g[0]; for (let i = 1; i <= R; i++) norm += 2 * g[i];
    const off = new Float32Array(16), w = new Float32Array(16); w[0] = g[0] / norm; let taps = 1;
    for (let k = 1; k <= R && taps < 16; k += 2) { const ww = g[k] + g[k + 1]; off[taps] = (k * g[k] + (k + 1) * g[k + 1]) / ww; w[taps] = ww / norm; taps++; }
    bind(dst); const p = P.blur; use(p); sampler(p, 'uSrc', srcT);
    gl.uniform2f(L(p, 'uStep'), dx, dy); gl.uniform1i(L(p, 'uTaps'), taps); gl.uniform1fv(L(p, 'uOffsets'), off); gl.uniform1fv(L(p, 'uWeights'), w); draw();
  }
  const T = (w, h, fmt) => target(w, h, fmt);
  const A = T(W, H); bind(A);
  { const p = P.expose; use(p); sampler(p, 'uInput', input);
    gl.uniformMatrix3fv(L(p, 'uUvTransform'), false, T3(uv)); gl.uniformMatrix3fv(L(p, 'uToXyz'), false, T3(c.inputMatrix));
    sampler(p, 'uTcLut', texTc); gl.uniform1f(L(p, 'uTcSize'), c.tcSize); gl.uniform1f(L(p, 'uExposure'), c.exposure * Math.pow(2, autoEv)); draw(); }
  const pyramid = s0 => { const out = []; let cur = s0, i = 0;
    while (Math.min(cur.w, cur.h) > 6 && i < 12) { const t = T((cur.w + 1) >> 1, (cur.h + 1) >> 1); bind(t);
      const p = P.down; use(p); sampler(p, 'uSrc', cur.t); draw(); out.push(t); cur = t; i++; }
    if (!out.length) out.push(s0); return out; };
  const blurOctave = (levels, sigma) => {
    const j = Math.min(levels.length, Math.max(1, Math.floor(Math.log(sigma) / Math.log(2))));
    const sl0 = levels[j - 1], sl = sigma / Math.pow(2, j), s2 = Math.sqrt(Math.max(sl * sl - 0.11, 0.05));
    const tmp = T(sl0.w, sl0.h), dst = T(sl0.w, sl0.h);
    blurPass(sl0.t, tmp, s2, 1 / sl0.w, 0); blurPass(tmp.t, dst, s2, 0, 1 / sl0.h); return dst; };
  const combine = (dst, base, baseW, terms) => { bind(dst); const p = P.combine; use(p); sampler(p, 'uBase', base);
    gl.uniform3fv(L(p, 'uBaseW'), baseW); const n = Math.min(terms.length, 10); gl.uniform1i(L(p, 'uCount'), n);
    const w = new Float32Array(30); for (let i = 0; i < 10; i++) { sampler(p, 'uT' + i, i < n ? terms[i][0].t : zero); if (i < n) for (let k = 0; k < 3; k++) w[i * 3 + k] = terms[i][1][k]; }
    gl.uniform3fv(L(p, 'uW'), w); draw(); };
  const levels = pyramid(A), l1 = levels[0];
  const m = c.expMix, idW = m.identity.map(fr), fineW = [0, 0, 0], blurred = [];
  m.sigmas.forEach((sg, k) => { const wk = m.weights[k].map(fr);
    if (sg < 1) { for (let q = 0; q < 3; q++) fineW[q] = fr(fineW[q] + wk[q]); return; }
    blurred.push([blurOctave(levels, sg), wk]); });
  let sTex = zero;
  if (blurred.length) { const sT = T(l1.w, l1.h); combine(sT, zero, [0, 0, 0], blurred); sTex = sT.t; }
  let cbTex = zero;
  if (c.couplers) {
    const c1 = T(l1.w, l1.h); bind(c1);
    { const p = P.corr; use(p); sampler(p, 'uL1', l1.t); sampler(p, 'uS', sTex); gl.uniform3fv(L(p, 'uIdW'), idW.map((v, q) => fr(v + fineW[q]))); curvesU(p);
      gl.uniformMatrix3fv(L(p, 'uCoupler'), false, c.coupler); gl.uniform3fv(L(p, 'uDmax'), c.dmax); gl.uniform1i(L(p, 'uPositive'), c.positive ? 1 : 0); draw(); }
    const cLevels = [c1].concat(pyramid(c1)); let base = 0; const cBlur = [];
    c.corrMix.sigmas.forEach((sg, k) => { const wk = c.corrMix.weights[k][0];
      if (sg < 1) { base += wk; return; } cBlur.push([blurOctave(cLevels, sg), [wk, wk, wk].map(fr)]); });
    base += c.corrMix.identity[0];
    const cb = T(l1.w, l1.h); combine(cb, c1.t, [base, base, base].map(fr), cBlur); cbTex = cb.t;
  }
  const D = T(W, H); bind(D);
  const g = c.grain;
  { const p = P.develop; use(p); sampler(p, 'uRaw', A.t); sampler(p, 'uS', sTex); sampler(p, 'uCb', cbTex);
    curvesU(p); gl.uniform3fv(L(p, 'uIdW'), idW); gl.uniform3fv(L(p, 'uFineW'), fineW); gl.uniform2f(L(p, 'uTexel'), 1 / W, 1 / H);
    gl.uniform1i(L(p, 'uCouplers'), c.couplers ? 1 : 0); gl.uniform1i(L(p, 'uGrain'), g.active ? 1 : 0);
    if (g.active) {
      sampler(p, 'uLayers', texLayers); gl.uniform1f(L(p, 'uGrainAmount'), g.amount); gl.uniform3fv(L(p, 'uTableHi'), g.hi);
      gl.uniform3fv(L(p, 'uLayerDmin'), g.dmin); gl.uniform3fv(L(p, 'uLayerDmax'), g.dmax); gl.uniform3fv(L(p, 'uLayerN'), g.n);
      gl.uniform3fv(L(p, 'uUniformity'), g.unif); gl.uniform3fv(L(p, 'uDmin'), g.densMin); gl.uniform1ui(L(p, 'uSeed'), seed);
    }
    draw(); }
  // final: print + scan + display, 8-bit, flipped for top-down readback (GlWorker.renderPhoto)
  const O = T(f.ow, f.oh, gl.RGBA8); bind(O);
  { const p = P.final; use(p); sampler(p, 'uDensity', D.t);
    sampler(p, 'uLut', lut, gl.TEXTURE_3D); gl.uniform3fv(L(p, 'uLutLo'), c.lutLo); gl.uniform3fv(L(p, 'uLutHi'), c.lutHi);
    gl.uniform1f(L(p, 'uLutSize'), c.lutN); gl.uniform1f(L(p, 'uGlare'), c.glare); gl.uniform2f(L(p, 'uTexel'), 1 / W, 1 / H);
    const sg = g.active ? g.blurPx : 0; let gb = [1, 0, 0];
    if (sg > 0) { const g1 = Math.exp(-1 / (2 * sg * sg)), n = 1 + 2 * g1, w0 = 1 / n, w1 = g1 / n; gb = [w0 * w0, w0 * w1, w1 * w1]; }
    gl.uniform3fv(L(p, 'uGrainBlur'), gb);
    gl.uniform1i(L(p, 'uOriginal'), 0); gl.uniform4fv(L(p, 'uImageRect'), f.rect); gl.uniform3fv(L(p, 'uBorder'), c.border);
    gl.uniform1i(L(p, 'uFlipY'), 1); gl.uniform1i(L(p, 'uOverlay'), 0); sampler(p, 'uOverlayTex', zero); sampler(p, 'uInput', zero); draw(); }
  const px = new Uint8ClampedArray(f.ow * f.oh * 4); gl.readPixels(0, 0, f.ow, f.oh, gl.RGBA, gl.UNSIGNED_BYTE, px);
  const glError = gl.getError();
  const oc = new OffscreenCanvas(f.ow, f.oh); oc.getContext('2d').putImageData(new ImageData(px, f.ow, f.oh), 0, 0);
  const ab = await (await oc.convertToBlob({ type: 'image/png' })).arrayBuffer();
  const u8 = new Uint8Array(ab); let bin = ''; for (let i = 0; i < u8.length; i += 32768) bin += String.fromCharCode.apply(null, u8.subarray(i, i + 32768));
  gl.getExtension('WEBGL_lose_context')?.loseContext();
  return { png: btoa(bin), autoEv, glError };
}
