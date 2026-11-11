// Burst merge gather: Shaders.MERGE in WebGL2 against BurstMerge.gather on the CPU.
const { chromium } = require('playwright'); const fs = require('fs'); const dir = process.argv[2] || __dirname;
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await b.newPage();
  const b64 = n => fs.readFileSync(dir + '/' + n).toString('base64');
  const r = await page.evaluate(({ vs, fsrc, m, raw, flow, robust, kernel, expected }) => {
    const bytes = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
    const gl = document.createElement('canvas').getContext('webgl2');
    const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); return sh; };
    const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fsrc)); gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    gl.useProgram(p);
    const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    const tex = (unit, target, name) => { const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(target, t);
      for (const k of [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER]) gl.texParameteri(target, k, gl.NEAREST);
      gl.uniform1i(gl.getUniformLocation(p, name), unit); };
    const alts = m.frames - 1;
    tex(0, gl.TEXTURE_2D_ARRAY, 'uRaw');
    gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.R16UI, m.w, m.h, m.frames, 0, gl.RED_INTEGER, gl.UNSIGNED_SHORT, new Uint16Array(bytes(raw).buffer));
    tex(1, gl.TEXTURE_2D_ARRAY, 'uFlow');
    gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.RG32F, m.tiles[0], m.tiles[1], alts, 0, gl.RG, gl.FLOAT, new Float32Array(bytes(flow).buffer));
    tex(2, gl.TEXTURE_2D_ARRAY, 'uRobust');
    gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.R8, m.guide[0], m.guide[1], alts, 0, gl.RED, gl.UNSIGNED_BYTE, bytes(robust));
    tex(3, gl.TEXTURE_2D, 'uKernel');
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB32F, m.kern[0], m.kern[1], 0, gl.RGB, gl.FLOAT, new Float32Array(bytes(kernel).buffer));
    const L = n => gl.getUniformLocation(p, n);
    gl.uniform1i(L('uFrames'), m.frames); gl.uniform2i(L('uSize'), m.w, m.h); gl.uniform2i(L('uGuide'), ...m.guide); gl.uniform2i(L('uKern'), ...m.kern);
    gl.uniform2i(L('uTiles'), ...m.tiles); gl.uniform1f(L('uOrigin'), m.origin); gl.uniform1f(L('uStride'), m.stride); gl.uniform4i(L('uCfa'), ...m.cfa);
    gl.uniform4fv(L('uBlack'), new Float32Array(m.black)); gl.uniform4fv(L('uScale'), new Float32Array(m.scale));
    // rendered in strips, like the app does
    const strip = 50; const out = new Uint32Array(m.w * m.h * 4);
    const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE4); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA16UI, m.w, strip);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('RGBA16UI not renderable');
    for (let y0 = 0; y0 < m.h; y0 += strip) {
      const rows = Math.min(strip, m.h - y0);
      gl.viewport(0, 0, m.w, strip); gl.uniform1i(L('uRow0'), y0); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.readPixels(0, 0, m.w, rows, gl.RGBA_INTEGER, gl.UNSIGNED_INT, out.subarray(y0 * m.w * 4, (y0 + rows) * m.w * 4));
    }
    const exp = new Uint16Array(bytes(expected).buffer);
    let max = 0, sum = 0;
    for (let i = 0; i < m.w * m.h; i++) for (let c = 0; c < 3; c++) { const d = Math.abs(out[i * 4 + c] - exp[i * 3 + c]); max = Math.max(max, d); sum += d; }
    return { max, mean: sum / (m.w * m.h * 3), err: gl.getError(), sample: [out[4 * (m.w * 40 + 30)], exp[3 * (m.w * 40 + 30)]] };
  }, { vs: fs.readFileSync(dir + '/vertex.glsl', 'utf8'), fsrc: fs.readFileSync(dir + '/merge.glsl', 'utf8'), m: JSON.parse(fs.readFileSync(dir + '/merge.json', 'utf8')),
       raw: b64('merge_raw.bin'), flow: b64('merge_flow.bin'), robust: b64('merge_robust.bin'), kernel: b64('merge_kernel.bin'), expected: b64('merge_expected.bin') });
  console.log(`merge gather GPU vs CPU (of 65535): max diff ${r.max}, mean ${r.mean.toFixed(3)}, gl error ${r.err}, sample ${r.sample}`);
  await b.close();
})();
