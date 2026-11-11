// spektrafilm polaroid development: GLSL port (+ Kotlin coefficients) vs the Python original.
const { chromium } = require('playwright'); const fs = require('fs'); const dir = process.argv[2] || __dirname;
(async () => {
  const ref = JSON.parse(fs.readFileSync(dir + '/polaroid_ref.json'));
  const kcoef = JSON.parse(fs.readFileSync(dir + '/polaroid_coef.json'));
  let ce = 0; ref.coef.forEach((row, i) => row.forEach((v, j) => { ce = Math.max(ce, Math.abs(v - kcoef[i][j])); }));
  console.log('coefficients Kotlin vs Python: max abs diff', ce.toExponential(2));
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await b.newPage();
  const r = await page.evaluate(({ vs, fs, ref, kcoef }) => {
    const gl = document.createElement('canvas').getContext('webgl2'); gl.getExtension('EXT_color_buffer_float');
    const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); return sh; };
    const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(p);
    const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
    const H = ref.img.length, W = ref.img[0].length;
    // upload rows bottom-up so texel row 0 = image bottom (y-up uv, like the app)
    const bytes = new Uint8Array(W * H * 4);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) for (let c = 0; c < 4; c++)
      bytes[((H - 1 - y) * W + x) * 4 + c] = c < 3 ? ref.img[y][x][c] : 255;
    const src = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, src);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, W, H, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
    // maxima exactly as Polaroid.maxima (step 1)
    const dens = v => Math.min(6, Math.max(0, -Math.log(Math.pow(Math.max(v / 255, 1e-4), 2.2))));
    const dm = [0, 0, 0], cm = [0, 0, 0]; let nm = 0;
    for (let i = 0; i < W * H; i++) { const d = [0, 1, 2].map(c => dens(bytes[i * 4 + c])); const n = Math.min(...d);
      for (let c = 0; c < 3; c++) { dm[c] = Math.max(dm[c], d[c]); cm[c] = Math.max(cm[c], d[c] - n); } nm = Math.max(nm, n); }
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, W, H, 0, gl.RGBA, gl.FLOAT, null);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    gl.viewport(0, 0, W, H); gl.useProgram(p); const L = n => gl.getUniformLocation(p, n);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, src); gl.uniform1i(L('uSrc'), 0);
    gl.uniform3fv(L('uDensityMax'), dm); gl.uniform3fv(L('uChromaMax'), cm); gl.uniform1f(L('uNeutralMax'), nm);
    const out = [];
    ref.ts.forEach((tt, k) => {
      // coefficients for this t from the Python reference (Kotlin ones are checked above)
      const coef = ref.coef.length && Number.isInteger(tt * 10) ? kcoef[Math.round(tt * 10)] : null;
      gl.uniform1fv(L('uCoef'), new Float32Array(coef || ref.coef[0]));
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const px = new Float32Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.FLOAT, px);
      let e = 0;
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) for (let c = 0; c < 3; c++)
        e = Math.max(e, Math.abs(px[((H - 1 - y) * W + x) * 4 + c] - ref.frames[k][y][x][c]));
      out.push({ t: tt, maxErr: e, usedKotlinCoef: !!coef });
    });
    return out;
  }, { vs: fs.readFileSync(dir + '/vertex.glsl', 'utf8'), fs: fs.readFileSync(dir + '/polaroid.glsl', 'utf8'), ref, kcoef });
  r.forEach(x => console.log(`t=${x.t}: max |GLSL - python| = ${x.maxErr.toExponential(2)} (0..1 scale)${x.usedKotlinCoef ? '' : ' [skipped: no coefficient sample]'}`));
  await b.close();
})();
