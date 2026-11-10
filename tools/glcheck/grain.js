const { chromium } = require('playwright'); const fs = require('fs'); const dir = __dirname;
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await b.newPage();
  const g = JSON.parse(fs.readFileSync(dir + '/grain.json')); const look = JSON.parse(fs.readFileSync(dir + '/look_a.json'));
  const r = await page.evaluate(({ vs, fs, g, s }) => {
    const gl = document.createElement('canvas').getContext('webgl2'); gl.getExtension('EXT_color_buffer_float');
    const mk = (t, src) => { const sh = gl.createShader(t); gl.shaderSource(sh, src); gl.compileShader(sh); return sh; };
    const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(p);
    const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
    const tex = (w, h, d, inter) => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, inter || gl.RGBA16F, w, h, 0, gl.RGBA, gl.FLOAT, d); return t; };
    const rgba = (a) => { const n = a.length / 3, o = new Float32Array(n * 4); for (let i = 0; i < n; i++) { o[i*4]=a[i*3]; o[i*4+1]=a[i*3+1]; o[i*4+2]=a[i*3+2]; o[i*4+3]=1; } return o; };
    const W = 256, H = 256;
    const layers = tex(g.size, 3, rgba(g.table)); const curves = tex(s.curveSize, 1, rgba(s.curves));
    const target = tex(W, H, null, gl.RGBA32F); const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0); gl.viewport(0, 0, W, H);
    gl.useProgram(p); const L = n => gl.getUniformLocation(p, n);
    const out = [];
    for (const st of g.stats) {
      // log raw that develops to density st.d on the green curve: invert the curve numerically
      const K = s.curveSize; let lr = s.curveX0;
      for (let i = 0; i < K; i++) { if (s.curves[i*3+1] >= st.d) { lr = s.curveX0 + (s.curveX1 - s.curveX0) * i / (K - 1); break; } }
      const raw = Math.pow(10, lr);
      const rawTex = tex(1, 1, new Float32Array([raw, raw, raw, 1]));
      const z1 = tex(1, 1, new Float32Array(4)), z2 = tex(1, 1, new Float32Array(4));
      let u = 0; const smp = (n, t) => { gl.activeTexture(gl.TEXTURE0 + u); gl.bindTexture(gl.TEXTURE_2D, t); gl.uniform1i(L(n), u); u++; };
      smp('uRaw', rawTex); smp('uS', z1); smp('uCb', z2); smp('uCurves', curves); smp('uCurves0', curves); smp('uLayers', layers);
      gl.uniform2f(L('uCurveAxis'), s.curveX0, s.curveX1); gl.uniform1f(L('uCurveSize'), K);
      gl.uniform3f(L('uIdW'), 1, 1, 1); gl.uniform3f(L('uFineW'), 0, 0, 0); gl.uniform1i(L('uCouplers'), 0); gl.uniform1i(L('uGrain'), 1);
      gl.uniform1f(L('uGrainAmount'), 1); gl.uniform3fv(L('uTableHi'), g.hi); gl.uniform3fv(L('uLayerDmin'), g.dmin); gl.uniform3fv(L('uLayerDmax'), g.dmax);
      gl.uniform3fv(L('uLayerN'), g.ppu.map(v => v * st.px * st.px)); gl.uniform3fv(L('uUniformity'), g.unif); gl.uniform3fv(L('uDmin'), g.densMin); gl.uniform1ui(L('uSeed'), 7);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const px = new Float32Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.FLOAT, px);
      let sum = 0, sq = 0, bad = 0; const n = W * H;
      for (let i = 0; i < n; i++) { const v = px[i*4+1]; if (!isFinite(v)) bad++; sum += v; sq += v * v; }
      const m = sum / n;
      // CPU reference ran at exactly density st.d; GPU density is the curve sample nearest above it
      out.push({ px: st.px, d: st.d, gpuMean: m, gpuStd: Math.sqrt(sq / n - m * m), cpuMean: st.mean, cpuStd: st.std, bad });
    }
    return out;
  }, { vs: fs.readFileSync(dir + '/vertex.glsl', 'utf8'), fs: fs.readFileSync(dir + '/develop.glsl', 'utf8'), g, s: look });
  console.log(JSON.stringify(r, null, 1)); await b.close();
})();
