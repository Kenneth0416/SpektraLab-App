// Runs the app's GLSL ES 3.00 shaders in headless Chromium (WebGL2) and compares
// the per-pixel pipeline with the Kotlin CPU reference.
const { chromium } = require('playwright');
const fs = require('fs');
const dir = __dirname;
const src = n => fs.readFileSync(`${dir}/${n}.glsl`, 'utf8');
(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  page.on('console', m => console.log('page:', m.text()));
  const shaders = {};
  for (const n of ['vertex', 'expose', 'down', 'blur', 'combine', 'corr', 'develop', 'final']) shaders[n] = src(n);
  const looks = ['a', 'b', 'c'].map(k => JSON.parse(fs.readFileSync(`${dir}/look_${k}.json`, 'utf8')));
  const result = await page.evaluate(({ shaders, looks }) => {
    const cv = document.createElement('canvas');
    const gl = cv.getContext('webgl2');
    if (!gl) return { error: 'no webgl2' };
    if (!gl.getExtension('EXT_color_buffer_float')) return { error: 'no color_buffer_float' };
    gl.getExtension('OES_texture_float_linear');
    const log = [];
    function prog(fs) {
      const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); return sh; };
      const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, shaders.vertex)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs));
      gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p)); return p;
    }
    const P = {};
    for (const n of ['expose', 'down', 'blur', 'combine', 'corr', 'develop', 'final']) {
      try { P[n] = prog(shaders[n]); log.push(`compiled ${n}`); } catch (e) { return { error: `${n}: ${e.message}` }; }
    }
    const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const vao = gl.createVertexArray(); gl.bindVertexArray(vao); gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
    function tex2d(w, h, data, filter) {
      const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, filter === gl.NEAREST ? gl.RGBA32F : gl.RGBA16F, w, h, 0, gl.RGBA, gl.FLOAT, data); return t;
    }
    const rgba = (a, ch) => { const n = a.length / ch, o = new Float32Array(n * 4); for (let i = 0; i < n; i++) { for (let c = 0; c < 4; c++) o[i * 4 + c] = c < ch ? a[i * ch + c] : 1; } return o; };
    function target(w, h, fmt) {
      const t = tex2d(w, h, null, gl.LINEAR);
      if (fmt === 'rgba8') { gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null); }
      const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
      return { t, fb, w, h };
    }
    function bind(tg) { gl.bindFramebuffer(gl.FRAMEBUFFER, tg ? tg.fb : null); gl.viewport(0, 0, tg.w, tg.h); }
    let unit = 0;
    function use(p) { gl.useProgram(p); unit = 0; }
    const L = (p, n) => gl.getUniformLocation(p, n);
    function sampler(p, n, t, target = gl.TEXTURE_2D) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(target, t); gl.uniform1i(L(p, n), unit); unit++; }
    // row-major -> column-major (WebGL forbids transpose=true)
    const T3 = m => [m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]];
    const draw = () => gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const out = [];
    for (const s of looks) {
      const W = s.colors.length / 3, H = 1;
      const input = tex2d(W, H, rgba(s.colors, 3), gl.NEAREST);
      const tc = tex2d(s.tcSize, s.tcSize, rgba(s.tcLut, 3), gl.LINEAR);
      const curves = tex2d(s.curveSize, 1, rgba(s.curves, 3), gl.LINEAR);
      const curves0 = tex2d(s.curveSize, 1, rgba(s.curves0, 3), gl.LINEAR);
      const zero = tex2d(1, 1, new Float32Array(4), gl.LINEAR);
      const lut = gl.createTexture(); gl.bindTexture(gl.TEXTURE_3D, lut);
      for (const pn of [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER]) gl.texParameteri(gl.TEXTURE_3D, pn, gl.LINEAR);
      for (const pn of [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T, gl.TEXTURE_WRAP_R]) gl.texParameteri(gl.TEXTURE_3D, pn, gl.CLAMP_TO_EDGE);
      gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGBA16F, s.lutN, s.lutN, s.lutN, 0, gl.RGBA, gl.FLOAT, rgba(s.lut, 3));
      const A = target(W, H), C = target(W, H), Cb = target(W, H), D = target(W, H), O = target(W, H, 'rgba8');
      // expose
      bind(A); let p = P.expose; use(p); sampler(p, 'uInput', input);
      gl.uniformMatrix3fv(L(p, 'uUvTransform'), false, T3([1, 0, 0, 0, 1, 0, 0, 0, 1]));
      gl.uniformMatrix3fv(L(p, 'uToXyz'), false, T3(s.inputMatrix));
      sampler(p, 'uTcLut', tc); gl.uniform1f(L(p, 'uTcSize'), s.tcSize); gl.uniform1f(L(p, 'uExposure'), s.exposure); draw();
      const curvesU = p => { sampler(p, 'uCurves', curves); sampler(p, 'uCurves0', curves0); gl.uniform2f(L(p, 'uCurveAxis'), s.curveX0, s.curveX1); gl.uniform1f(L(p, 'uCurveSize'), s.curveSize); };
      // couplers (at full res here: flat pixels, no diffusion)
      bind(C); p = P.corr; use(p); sampler(p, 'uL1', A.t); sampler(p, 'uS', zero); gl.uniform3f(L(p, 'uIdW'), 1, 1, 1); curvesU(p);
      gl.uniformMatrix3fv(L(p, 'uCoupler'), false, s.coupler); gl.uniform3fv(L(p, 'uDmax'), s.dmax); gl.uniform1i(L(p, 'uPositive'), s.positive ? 1 : 0); draw();
      bind(Cb); p = P.combine; use(p); sampler(p, 'uBase', C.t); gl.uniform3f(L(p, 'uBaseW'), 1, 1, 1); gl.uniform1i(L(p, 'uCount'), 0);
      for (let i = 0; i < 10; i++) sampler(p, 'uT' + i, zero); draw();
      bind(D); p = P.develop; use(p); sampler(p, 'uRaw', A.t); sampler(p, 'uS', zero); sampler(p, 'uCb', Cb.t); curvesU(p);
      gl.uniform3f(L(p, 'uIdW'), 1, 1, 1); gl.uniform3f(L(p, 'uFineW'), 0, 0, 0); gl.uniform2f(L(p, 'uTexel'), 1 / W, 1 / H);
      gl.uniform1i(L(p, 'uCouplers'), s.couplers ? 1 : 0); gl.uniform1i(L(p, 'uGrain'), 0); draw();
      bind(O); p = P.final; use(p); sampler(p, 'uInput', input); sampler(p, 'uDensity', D.t); sampler(p, 'uLut', lut, gl.TEXTURE_3D);
      gl.uniformMatrix3fv(L(p, 'uUvTransform'), false, T3([1, 0, 0, 0, 1, 0, 0, 0, 1]));
      gl.uniform3fv(L(p, 'uLutLo'), s.lutLo); gl.uniform3fv(L(p, 'uLutHi'), s.lutHi); gl.uniform1f(L(p, 'uLutSize'), s.lutN);
      gl.uniform1f(L(p, 'uGlare'), s.glare); gl.uniform2f(L(p, 'uTexel'), 1 / W, 1 / H); gl.uniform3f(L(p, 'uGrainBlur'), 1, 0, 0);
      gl.uniform1i(L(p, 'uOriginal'), 0); gl.uniform4f(L(p, 'uImageRect'), 0, 0, 1, 1); gl.uniform3f(L(p, 'uBorder'), 0, 0, 0);
      gl.uniform1i(L(p, 'uFlipY'), 0); gl.uniform1i(L(p, 'uOverlay'), 0); sampler(p, 'uOverlayTex', zero); draw();
      const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
      const enc = v => { v = Math.min(1, Math.max(0, v)); return 255 * (v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055); };
      let maxErr = 0, sum = 0;
      for (let i = 0; i < W; i++) for (let c = 0; c < 3; c++) { const e = Math.abs(px[i * 4 + c] - enc(s.expected[i * 3 + c])); maxErr = Math.max(maxErr, e); sum += e; }
      out.push({ maxErr, meanErr: sum / (W * 3), err: gl.getError() });
    }
    return { log, out };
  }, { shaders, looks });
  console.log(JSON.stringify(result, null, 1));
  await browser.close();
})();
