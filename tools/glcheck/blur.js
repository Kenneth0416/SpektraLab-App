// Impulse response of the renderer's pyramid blur (same level/sigma rules as
// FilmRenderer.blurOctave) versus an ideal Gaussian: encircled energy.
const { chromium } = require('playwright'); const fs = require('fs'); const dir = process.argv[2] || __dirname;
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await b.newPage();
  const r = await page.evaluate(({ vs, down, blur }) => {
    const gl = document.createElement('canvas').getContext('webgl2'); gl.getExtension('EXT_color_buffer_float'); gl.getExtension('OES_texture_float_linear');
    const prog = fsrc => { const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); return sh; };
      const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fsrc)); gl.linkProgram(p); return p; };
    const pDown = prog(down), pBlur = prog(blur);
    const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
    const target = (w, h, data) => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, w, h, 0, gl.RGBA, gl.FLOAT, data || null);
      const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
      return { t, fb, w, h }; };
    const N = 512; const img = new Float32Array(N * N * 4); const c = N / 2; img[(c * N + c) * 4] = 1;
    const A = target(N, N, img);
    const draw = (p, dst, bindings) => { gl.bindFramebuffer(gl.FRAMEBUFFER, dst.fb); gl.viewport(0, 0, dst.w, dst.h); gl.useProgram(p); bindings(); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); };
    const levels = []; let cur = A;
    while (Math.min(cur.w, cur.h) > 6) { const t = target((cur.w + 1) >> 1, (cur.h + 1) >> 1); const src = cur;
      draw(pDown, t, () => { gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, src.t); gl.uniform1i(gl.getUniformLocation(pDown, 'uSrc'), 0); });
      levels.push(t); cur = t; }
    function blurPass(src, dst, sigma, dx, dy) {
      const R = Math.min(28, Math.max(1, Math.ceil(3 * sigma)));
      const g = []; for (let i = 0; i <= R + 1; i++) g.push(i <= R ? Math.exp(-i * i / (2 * sigma * sigma)) : 0);
      let norm = g[0]; for (let i = 1; i <= R; i++) norm += 2 * g[i];
      const off = new Float32Array(16), w = new Float32Array(16); w[0] = g[0] / norm; let taps = 1;
      for (let k = 1; k <= R && taps < 16; k += 2) { const ww = g[k] + g[k + 1]; off[taps] = (k * g[k] + (k + 1) * g[k + 1]) / ww; w[taps] = ww / norm; taps++; }
      draw(pBlur, dst, () => { gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, src); const L = n => gl.getUniformLocation(pBlur, n);
        gl.uniform1i(L('uSrc'), 0); gl.uniform2f(L('uStep'), dx, dy); gl.uniform1i(L('uTaps'), taps); gl.uniform1fv(L('uOffsets'), off); gl.uniform1fv(L('uWeights'), w); });
    }
    const out = [];
    for (const sigma of [1.5, 3, 6, 12, 24, 48]) {
      const j = Math.min(levels.length, Math.max(1, Math.floor(Math.log2(sigma))));
      const src = levels[j - 1]; const sl = sigma / Math.pow(2, j); const s2 = Math.sqrt(Math.max(sl * sl - 0.11, 0.05));
      const tmp = target(src.w, src.h), dst = target(src.w, src.h);
      blurPass(src.t, tmp, s2, 1 / src.w, 0); blurPass(tmp.t, dst, s2, 0, 1 / src.h);
      // upsample to full res (bilinear) like the combine/develop passes do
      const up = target(N, N); draw(pDown, up, () => { gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, dst.t); gl.uniform1i(gl.getUniformLocation(pDown, 'uSrc'), 0); });
      const px = new Float32Array(N * N * 4); gl.bindFramebuffer(gl.FRAMEBUFFER, up.fb); gl.readPixels(0, 0, N, N, gl.RGBA, gl.FLOAT, px);
      let total = 0, mx = 0, my = 0, vv = 0; for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) { const v = px[(y * N + x) * 4]; total += v; mx += v * x; my += v * y; }
      mx /= total; my /= total;
      for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) { const v = px[(y * N + x) * 4]; vv += v * ((x - mx) ** 2 + (y - my) ** 2); }
      out.push({ sigma, level: j, total, measuredSigma: Math.sqrt(vv / total / 2), center: [mx - c, my - c] });
    }
    return out;
  }, { vs: fs.readFileSync(dir + '/vertex.glsl', 'utf8'), down: fs.readFileSync(dir + '/down.glsl', 'utf8'), blur: fs.readFileSync(dir + '/blur.glsl', 'utf8') });
  for (const x of r) console.log(`sigma ${x.sigma} (level ${x.level}): measured ${x.measuredSigma.toFixed(2)}, energy ${x.total.toFixed(4)}, centroid shift ${x.center.map(v => v.toFixed(2)).join(',')}`);
  await b.close();
})();
