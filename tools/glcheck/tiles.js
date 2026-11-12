// Tiled vs untiled render through the app's shaders (WebGL2 / SwiftShader).
// Untiled: a transcription of FilmRenderer.render. Tiled: executes the TilePlan ops that
// the Kotlin planner produced (tiles_<case>.json, written by GlDumpTest), the same list
// FilmRenderer.renderTiled executes on the device. Prints the difference of the two
// outputs (float targets, in 8-bit steps), split by distance to the tile seams.
const { chromium } = require('playwright');
const fs = require('fs');
const dir = process.argv[2] || __dirname;
const only = process.argv[3];

(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  page.on('console', m => console.log('page:', m.text()));
  const shaders = {};
  for (const n of ['vertex', 'expose', 'down', 'blur', 'combine', 'corr', 'develop', 'final',
    'down_tiled', 'combine_tiled', 'develop_tiled', 'final_tiled']) shaders[n] = fs.readFileSync(`${dir}/${n}.glsl`, 'utf8');
  const cases = fs.readdirSync(dir).filter(f => /^tiles_.*\.json$/.test(f)).map(f => f.slice(6, -5))
    .filter(n => !only || n === only).sort();
  const runs = [];
  for (const name of cases) {
    runs.push({ name, opts: {} });
    // grain off: what is left is the smooth part (blurs, couplers, LUT); with grain on, a
    // density change far below a half-float step can still move a Poisson draw by one grain
    runs.push({ name, opts: { noGrain: true } });
    // control: grain seeded by the tile's own pixel position instead of the image's
    if (name === 'halo') runs.push({ name, opts: { localGrain: true } });
  }
  for (const run of runs) {
    const c = JSON.parse(fs.readFileSync(`${dir}/tiles_${run.name}.json`, 'utf8'));
    const r = await page.evaluate(check, { shaders, c, opts: run.opts });
    if (r.error) { console.log(run.name, 'ERROR', r.error); continue; }
    const label = run.name + (run.opts.localGrain ? ' (control: tile-local grain seed)' : run.opts.noGrain ? ' (grain off)' : '');
    console.log(`${label}: ${c.W}x${c.H} image, ${c.ow}x${c.oh} output, tile ${c.tile}, tiled levels ${c.levels}, margin ${c.margin} px, ${c.ops.length} ops`);
    console.log(`  max |diff| ${r.max.toFixed(4)} (8-bit steps) at ${r.at}, mean ${r.mean.toExponential(2)}; ` +
      `8-bit values differing: ${r.diff8} of ${r.n} px (max ${r.max8}; ${r.over2} px by more than 2); near seams (<=2 px) mean ${r.seam.toExponential(2)}, elsewhere ${r.inner.toExponential(2)}; ` +
      `untiled rerun max ${r.rerun.toFixed(4)}; GL errors ${r.glError}`);
  }
  await browser.close();
})();

function check({ shaders, c, opts }) {
  const gl = document.createElement('canvas').getContext('webgl2');
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

  // ------------------------------------------------------------ GL helpers (GlUtil / RenderTarget)
  function tex2d(w, h, data, internal = gl.RGBA16F) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, gl.RGBA, gl.FLOAT, data); return t;
  }
  function target(w, h, internal = gl.RGBA16F) {
    const t = tex2d(w, h, null, internal);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo');
    return { t, fb, w, h };
  }
  const release = tg => { gl.deleteFramebuffer(tg.fb); gl.deleteTexture(tg.t); };
  const bind = tg => { gl.bindFramebuffer(gl.FRAMEBUFFER, tg.fb); gl.viewport(0, 0, tg.w, tg.h); };
  let unit = 0;
  const use = p => { gl.useProgram(p); unit = 0; };
  const L = (p, n) => gl.getUniformLocation(p, n);
  function sampler(p, n, t, tgt = gl.TEXTURE_2D) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(tgt, t); gl.uniform1i(L(p, n), unit); unit++; }
  const T3 = m => [m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]];
  const rgba = (a, ch) => { const n = a.length / ch, o = new Float32Array(n * 4); for (let i = 0; i < n; i++) for (let k = 0; k < 4; k++) o[i * 4 + k] = k < ch ? a[i * ch + k] : 1; return o; };
  const f = Math.fround;

  // ------------------------------------------------------------ look tables
  const texTc = tex2d(c.tcSize, c.tcSize, rgba(c.tcLut, 3));
  const texCurves = tex2d(c.curveSize, 1, rgba(c.curves, 3));
  const texCurves0 = tex2d(c.curveSize, 1, rgba(c.curves0, 3));
  const texLayers = tex2d(c.grain.size, 3, rgba(c.grain.table, 3));
  const zero = tex2d(1, 1, new Float32Array(4));
  const lut = gl.createTexture(); gl.bindTexture(gl.TEXTURE_3D, lut);
  for (const pn of [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER]) gl.texParameteri(gl.TEXTURE_3D, pn, gl.LINEAR);
  for (const pn of [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T, gl.TEXTURE_WRAP_R]) gl.texParameteri(gl.TEXTURE_3D, pn, gl.CLAMP_TO_EDGE);
  gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGBA16F, c.lutN, c.lutN, c.lutN, 0, gl.RGBA, gl.FLOAT, rgba(c.lut, 3));

  // ------------------------------------------------------------ source: scene-linear, rows top-down (as a LinearImage)
  const SW = c.sw, SH = c.sh;
  const src = new Float32Array(SW * SH * 4);
  let seed = 1;
  const rnd = () => { seed = (seed * 1103515245 + 12345) >>> 0; return seed / 4294967296; };
  for (let y = 0; y < SH; y++) for (let x = 0; x < SW; x++) {
    const i = (y * SW + x) * 4;
    const sky = y < SH * 0.45, n = 0.9 + 0.2 * rnd();
    let r = sky ? 0.35 + 0.2 * x / SW : 0.08 + 0.06 * Math.sin(x * 0.07) * Math.sin(y * 0.05) + 0.06;
    let g = sky ? 0.45 : 0.1 + 0.05 * Math.cos(x * 0.031 + y * 0.017) + 0.05;
    let b = sky ? 0.7 - 0.3 * y / SH : 0.05 + 0.03 * ((x >> 4) & 1);
    // small highlights (halation) and a hard bright edge (diffusion)
    if ((x % 97 < 3 && y % 83 < 3) || (x > SW * 0.62 && x < SW * 0.66 && y > SH * 0.5)) { r = 9; g = 8; b = 6; }
    src[i] = r * n; src[i + 1] = g * n; src[i + 2] = b * n; src[i + 3] = 1;
  }

  // ------------------------------------------------------------ passes shared by both renders (FilmRenderer)
  function curvesU(p) { sampler(p, 'uCurves', texCurves); sampler(p, 'uCurves0', texCurves0);
    gl.uniform2f(L(p, 'uCurveAxis'), c.curveX0, c.curveX1); gl.uniform1f(L(p, 'uCurveSize'), c.curveSize); }
  function expose(dst, input, uv) {
    const p = P.expose; use(p); sampler(p, 'uInput', input);
    gl.uniformMatrix3fv(L(p, 'uUvTransform'), false, T3(uv)); gl.uniformMatrix3fv(L(p, 'uToXyz'), false, T3(c.inputMatrix));
    sampler(p, 'uTcLut', texTc); gl.uniform1f(L(p, 'uTcSize'), c.tcSize); gl.uniform1f(L(p, 'uExposure'), c.exposure); draw();
  }
  function blurPass(srcT, dst, sigma, dx, dy) {
    const R = Math.min(28, Math.max(1, Math.ceil(3 * sigma)));
    const g = []; for (let i = 0; i <= R + 1; i++) g.push(i <= R ? Math.exp(-i * i / (2 * sigma * sigma)) : 0);
    let norm = g[0]; for (let i = 1; i <= R; i++) norm += 2 * g[i];
    const off = new Float32Array(16), w = new Float32Array(16); w[0] = g[0] / norm; let taps = 1;
    for (let k = 1; k <= R && taps < 16; k += 2) { const ww = g[k] + g[k + 1]; off[taps] = (k * g[k] + (k + 1) * g[k + 1]) / ww; w[taps] = ww / norm; taps++; }
    bind(dst); const p = P.blur; use(p); sampler(p, 'uSrc', srcT);
    gl.uniform2f(L(p, 'uStep'), dx, dy); gl.uniform1i(L(p, 'uTaps'), taps); gl.uniform1fv(L(p, 'uOffsets'), off); gl.uniform1fv(L(p, 'uWeights'), w); draw();
  }
  function corr(p, l1, s, idW) {
    use(p); sampler(p, 'uL1', l1); sampler(p, 'uS', s); gl.uniform3fv(L(p, 'uIdW'), idW); curvesU(p);
    gl.uniformMatrix3fv(L(p, 'uCoupler'), false, c.coupler); gl.uniform3fv(L(p, 'uDmax'), c.dmax); gl.uniform1i(L(p, 'uPositive'), c.positive ? 1 : 0); draw();
  }
  function developU(p, idW, fineW, tx, ty) {
    curvesU(p); gl.uniform3fv(L(p, 'uIdW'), idW); gl.uniform3fv(L(p, 'uFineW'), fineW); gl.uniform2f(L(p, 'uTexel'), tx, ty);
    const g = c.grain, grain = g.active && !opts.noGrain;
    gl.uniform1i(L(p, 'uCouplers'), c.couplers ? 1 : 0); gl.uniform1i(L(p, 'uGrain'), grain ? 1 : 0);
    if (grain) {
      sampler(p, 'uLayers', texLayers); gl.uniform1f(L(p, 'uGrainAmount'), g.amount); gl.uniform3fv(L(p, 'uTableHi'), g.hi);
      gl.uniform3fv(L(p, 'uLayerDmin'), g.dmin); gl.uniform3fv(L(p, 'uLayerDmax'), g.dmax); gl.uniform3fv(L(p, 'uLayerN'), g.n);
      gl.uniform3fv(L(p, 'uUniformity'), g.unif); gl.uniform3fv(L(p, 'uDmin'), g.densMin); gl.uniform1ui(L(p, 'uSeed'), c.seed);
    }
  }
  function grainBlurWeights(sigma) {
    if (sigma <= 0) return [1, 0, 0];
    const g1 = Math.exp(-1 / (2 * sigma * sigma)), n = 1 + 2 * g1, w0 = 1 / n, w1 = g1 / n;
    return [w0 * w0, w0 * w1, w1 * w1];
  }
  function printU(p) {
    sampler(p, 'uLut', lut, gl.TEXTURE_3D); gl.uniform3fv(L(p, 'uLutLo'), c.lutLo); gl.uniform3fv(L(p, 'uLutHi'), c.lutHi);
    gl.uniform1f(L(p, 'uLutSize'), c.lutN); gl.uniform1f(L(p, 'uGlare'), c.glare); gl.uniform2f(L(p, 'uTexel'), 1 / c.W, 1 / c.H);
    gl.uniform3fv(L(p, 'uGrainBlur'), grainBlurWeights(c.grain.active && !opts.noGrain ? c.grain.blurPx : 0));
    gl.uniform1i(L(p, 'uOriginal'), 0); gl.uniform4fv(L(p, 'uImageRect'), c.rect); gl.uniform3fv(L(p, 'uBorder'), c.border);
    gl.uniform1i(L(p, 'uFlipY'), c.flipY ? 1 : 0); gl.uniform1i(L(p, 'uOverlay'), 0); sampler(p, 'uOverlayTex', zero);
  }

  // ------------------------------------------------------------ untiled: FilmRenderer.render
  function untiled() {
    const W = c.W, H = c.H, made = [];
    const T = (w, h, fmt) => { const t = target(w, h, fmt); made.push(t); return t; };
    const input = tex2d(SW, SH, src);
    const A = T(W, H); bind(A); expose(A, input, c.uv);
    const pyramid = s => { const out = []; let cur = s, i = 0;
      while (Math.min(cur.w, cur.h) > 6 && i < 12) { const t = T((cur.w + 1) >> 1, (cur.h + 1) >> 1); bind(t);
        const p = P.down; use(p); sampler(p, 'uSrc', cur.t); draw(); out.push(t); cur = t; i++; }
      if (!out.length) out.push(s); return out; };
    const blurOctave = (levels, sigma) => {
      const j = Math.min(levels.length, Math.max(1, Math.floor(Math.log(sigma) / Math.log(2))));
      const s = levels[j - 1], sl = sigma / Math.pow(2, j), s2 = Math.sqrt(Math.max(sl * sl - 0.11, 0.05));
      const tmp = T(s.w, s.h), dst = T(s.w, s.h);
      blurPass(s.t, tmp, s2, 1 / s.w, 0); blurPass(tmp.t, dst, s2, 0, 1 / s.h); return dst; };
    const combine = (dst, base, baseW, terms) => { bind(dst); const p = P.combine; use(p); sampler(p, 'uBase', base);
      gl.uniform3fv(L(p, 'uBaseW'), baseW); const n = Math.min(terms.length, 10); gl.uniform1i(L(p, 'uCount'), n);
      const w = new Float32Array(30); for (let i = 0; i < 10; i++) { sampler(p, 'uT' + i, i < n ? terms[i][0].t : zero); if (i < n) for (let k = 0; k < 3; k++) w[i * 3 + k] = terms[i][1][k]; }
      gl.uniform3fv(L(p, 'uW'), w); draw(); };
    const levels = pyramid(A), l1 = levels[0];
    const m = c.expMix, idW = m.identity.map(f), fineW = [0, 0, 0], blurred = [];
    m.sigmas.forEach((sg, k) => { const wk = m.weights[k].map(f);
      if (sg < 1) { for (let q = 0; q < 3; q++) fineW[q] = f(fineW[q] + wk[q]); return; }
      blurred.push([blurOctave(levels, sg), wk]); });
    let sTex = zero;
    if (blurred.length) { const sT = T(l1.w, l1.h); combine(sT, zero, [0, 0, 0], blurred); sTex = sT.t; }
    let cbTex = zero;
    if (c.couplers) {
      const c1 = T(l1.w, l1.h); bind(c1); corr(P.corr, l1.t, sTex, idW.map((v, q) => f(v + fineW[q])));
      const cLevels = [c1].concat(pyramid(c1)); let base = 0; const cBlur = [];
      c.corrMix.sigmas.forEach((sg, k) => { const wk = c.corrMix.weights[k][0];
        if (sg < 1) { base += wk; return; } cBlur.push([blurOctave(cLevels, sg), [wk, wk, wk].map(f)]); });
      base += c.corrMix.identity[0];
      const cb = T(l1.w, l1.h); combine(cb, c1.t, [base, base, base].map(f), cBlur); cbTex = cb.t;
    }
    const D = T(W, H); bind(D); let p = P.develop; use(p); sampler(p, 'uRaw', A.t); sampler(p, 'uS', sTex); sampler(p, 'uCb', cbTex);
    developU(p, idW, fineW, 1 / W, 1 / H); draw();
    const O = T(c.ow, c.oh, gl.RGBA32F); bind(O); p = P.final; use(p); sampler(p, 'uDensity', D.t); printU(p); draw();
    const px = new Float32Array(c.ow * c.oh * 4); gl.readPixels(0, 0, c.ow, c.oh, gl.RGBA, gl.FLOAT, px);
    made.forEach(release); gl.deleteTexture(input);
    return px;
  }

  // ------------------------------------------------------------ tiled: execute the plan (FilmRenderer.renderTiled)
  function tiled() {
    const bufs = {};
    const buf = b => { let t = bufs[b.n]; if (!t || t.w !== b.w || t.h !== b.h) { if (t) release(t); t = bufs[b.n] = target(b.w, b.h); } return t; };
    const out = new Float32Array(c.ow * c.oh * 4);
    let srcTex = null, outT = null;
    for (const op of c.ops) {
      switch (op.op) {
        case 'Source': {
          const win = new Float32Array(op.w * op.h * 4);
          for (let y = 0; y < op.h; y++) win.set(src.subarray(((op.y + y) * SW + op.x) * 4, ((op.y + y) * SW + op.x + op.w) * 4), y * op.w * 4);
          if (srcTex) gl.deleteTexture(srcTex); srcTex = tex2d(op.w, op.h, win); break;
        }
        case 'Expose': bind(buf(op.dst)); expose(null, srcTex, op.uv); break;
        case 'Down': {
          const s = buf(op.src), d = buf(op.dst);
          gl.bindFramebuffer(gl.FRAMEBUFFER, d.fb); gl.viewport(op.vp.x - op.dst.x, op.vp.y - op.dst.y, op.vp.w, op.vp.h);
          const p = P.down_tiled; use(p); sampler(p, 'uSrc', s.t); gl.uniform4fv(L(p, 'uSrcMap'), op.map); draw(); break;
        }
        case 'Blur': { const s = buf(op.src), t = buf(op.tmp), d = buf(op.dst);
          blurPass(s.t, t, op.sigma, 1 / s.w, 0); blurPass(t.t, d, op.sigma, 0, 1 / s.h); break; }
        case 'Combine': {
          const ins = op.terms.map(t => buf(t.src).t), base = op.base ? buf(op.base).t : zero;
          bind(buf(op.dst)); const p = P.combine_tiled; use(p);
          sampler(p, 'uBase', base); gl.uniform4fv(L(p, 'uBaseMap'), op.baseMap); gl.uniform3fv(L(p, 'uBaseW'), op.baseW);
          gl.uniform1i(L(p, 'uCount'), op.terms.length);
          const w = new Float32Array(30), maps = new Float32Array(40);
          for (let i = 0; i < 10; i++) { sampler(p, 'uT' + i, ins[i] || zero); const t = op.terms[i]; if (!t) continue;
            for (let k = 0; k < 3; k++) w[i * 3 + k] = t.w[k]; for (let k = 0; k < 4; k++) maps[i * 4 + k] = t.map[k]; }
          gl.uniform3fv(L(p, 'uW'), w); gl.uniform4fv(L(p, 'uMap'), maps); draw(); break;
        }
        case 'Corr': { const l1 = buf(op.l1).t, s = op.s ? buf(op.s).t : zero; bind(buf(op.dst)); corr(P.corr, l1, s, op.idW); break; }
        case 'Develop': {
          const raw = buf(op.raw).t, s = op.s ? buf(op.s).t : zero, cb = op.cb ? buf(op.cb).t : zero;
          bind(buf(op.dst)); const p = P.develop_tiled; use(p);
          sampler(p, 'uRaw', raw); sampler(p, 'uS', s); sampler(p, 'uCb', cb);
          const m = c.expMix, idW = m.identity.map(f), fineW = [0, 0, 0];
          m.sigmas.forEach((sg, k) => { if (sg < 1) for (let q = 0; q < 3; q++) fineW[q] = f(fineW[q] + f(m.weights[k][q])); });
          developU(p, idW, fineW, op.texel[0], op.texel[1]);
          gl.uniform4fv(L(p, 'uRawMap'), op.rawMap); gl.uniform4fv(L(p, 'uSMap'), op.sMap); gl.uniform4fv(L(p, 'uCbMap'), op.cbMap);
          if (opts.localGrain) gl.uniform2ui(L(p, 'uOrigin'), 0, 0); else gl.uniform2ui(L(p, 'uOrigin'), op.dst.x, op.dst.y);
          draw(); break;
        }
        case 'Final': {
          const dens = op.density ? buf(op.density).t : zero;
          if (!outT || outT.w !== op.w || outT.h !== op.h) { if (outT) release(outT); outT = target(op.w, op.h, gl.RGBA32F); }
          bind(outT); const p = P.final_tiled; use(p); sampler(p, 'uDensity', dens); printU(p);
          gl.uniform4fv(L(p, 'uOutMap'), op.outMap); gl.uniform4fv(L(p, 'uDensMap'), op.densMap); draw();
          const px = new Float32Array(op.w * op.h * 4); gl.readPixels(0, 0, op.w, op.h, gl.RGBA, gl.FLOAT, px);
          for (let y = 0; y < op.h; y++) out.set(px.subarray(y * op.w * 4, (y + 1) * op.w * 4), ((op.y + y) * c.ow + op.x) * 4);
          break;
        }
        case 'Free': for (const n of op.names) if (bufs[n]) { release(bufs[n]); delete bufs[n]; } break;
      }
    }
    Object.values(bufs).forEach(release); if (outT) release(outT); if (srcTex) gl.deleteTexture(srcTex);
    return out;
  }

  const a = untiled();
  const err0 = gl.getError();
  const b = tiled();
  const err1 = gl.getError();
  const a2 = untiled();
  let max = 0, at = '', sum = 0, diff8 = 0, max8 = 0, over2 = 0, seamSum = 0, seamN = 0, inSum = 0, inN = 0, rerun = 0;
  for (let y = 0; y < c.oh; y++) for (let x = 0; x < c.ow; x++) {
    const i = (y * c.ow + x) * 4;
    const dx = Math.min(x % c.tile, c.tile - 1 - x % c.tile), dy = Math.min(y % c.tile, c.tile - 1 - y % c.tile);
    let pm = 0, any = false, big = false;
    for (let k = 0; k < 3; k++) {
      const d = Math.abs(a[i + k] - b[i + k]) * 255; pm = Math.max(pm, d); sum += d;
      const q = Math.abs(Math.round(Math.min(1, Math.max(0, a[i + k])) * 255) - Math.round(Math.min(1, Math.max(0, b[i + k])) * 255));
      max8 = Math.max(max8, q); rerun = Math.max(rerun, Math.abs(a[i + k] - a2[i + k]) * 255);
      if (q) any = true;
      if (q > 2) big = true;
    }
    if (any) diff8++;
    if (big) over2++;
    if (pm > max) { max = pm; at = `(${x},${y})`; }
    if (Math.min(dx, dy) <= 2) { seamSum += pm; seamN++; } else { inSum += pm; inN++; }
  }
  return { max, at, mean: sum / (c.ow * c.oh * 3), diff8, max8, over2, n: c.ow * c.oh, seam: seamSum / Math.max(seamN, 1), inner: inSum / Math.max(inN, 1),
    rerun, glError: [err0, err1, gl.getError()].join('/') };
}
