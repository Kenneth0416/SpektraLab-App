// Self-contained browser function. No browser launcher or Node dependencies inside.
// Inputs are the exported production shaders; see run_ground_glass.cjs.
function groundGlassCheck({ vs, down, blur, glass }) {
  const gl = document.createElement('canvas').getContext('webgl2');
  if (!gl) throw new Error('WebGL2 unavailable');
  const resources = [], results = [];
  const own = (kind, value) => { resources.push([kind, value]); return value; };
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  try {
    assert(gl.getExtension('EXT_color_buffer_float'), 'EXT_color_buffer_float required for separate analytic suite');
    assert(gl.getExtension('OES_texture_float_linear'), 'float linear filtering required for analytic suite');
    const defaultDitherEnabled=gl.isEnabled(gl.DITHER);
    assert(defaultDitherEnabled, 'fresh WebGL context must default to enabled DITHER');
    let readbackHash=2166136261;
    function program(fragment) {
      const p = own('Program', gl.createProgram());
      for (const [type, text] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fragment]]) {
        const s = own('Shader', gl.createShader(type));
        gl.shaderSource(s, text); gl.compileShader(s);
        assert(gl.getShaderParameter(s, gl.COMPILE_STATUS), gl.getShaderInfoLog(s)); gl.attachShader(p, s);
      }
      gl.linkProgram(p); assert(gl.getProgramParameter(p, gl.LINK_STATUS), gl.getProgramInfoLog(p)); return p;
    }
    const P = { down: program(down), blur: program(blur), glass: program(glass) };
    gl.bindVertexArray(own('VertexArray', gl.createVertexArray()));
    gl.bindBuffer(gl.ARRAY_BUFFER, own('Buffer', gl.createBuffer()));
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
    const gaussian = Array.from({ length: 13 }, (_, i) => Math.exp(-i * i / 32));
    const norm = gaussian[0] + 2 * gaussian.slice(1).reduce((a, b) => a + b, 0);
    const g = gaussian.map(v => v / norm), offsets = new Float32Array(16), weights = new Float32Array(16);
    weights[0] = g[0];
    for (let i = 1; i <= 6; i++) {
      const k = 2 * i - 1; weights[i] = g[k] + g[k + 1];
      offsets[i] = (k * g[k] + (k + 1) * g[k + 1]) / (g[k] + g[k + 1]);
    }
    function target(w, h, byte, values) {
      const t = own('Texture', gl.createTexture()); gl.bindTexture(gl.TEXTURE_2D, t);
      for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR],
        [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
      let data = null;
      if (values) {
        data = byte ? new Uint8Array(w * h * 4) : new Float32Array(w * h * 4);
        for (let i = 0; i < w * h; i++) {
          for (let c = 0; c < 3; c++) {
            const value=Array.isArray(values[i])?values[i][c]:values[i];
            data[4*i+c] = byte ? Math.round(value * 255) : value;
          }
          data[4*i+3] = byte ? 255 : 1;
        }
      }
      gl.texImage2D(gl.TEXTURE_2D, 0, byte ? gl.RGBA8 : gl.RGBA32F, w, h, 0, gl.RGBA, byte ? gl.UNSIGNED_BYTE : gl.FLOAT, data);
      const fb = own('Framebuffer', gl.createFramebuffer()); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
      assert(gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE, 'incomplete framebuffer');
      return { t, fb, w, h, byte };
    }
    function bind(p, name, src, unit = 0) {
      gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, src.t);
      gl.uniform1i(gl.getUniformLocation(p, name), unit);
    }
    function draw(p, dst, uniforms) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, dst.fb); gl.viewport(0, 0, dst.w, dst.h); gl.useProgram(p);
      uniforms(); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      assert(gl.getError() === gl.NO_ERROR, 'GL draw error');
    }
    function read(src) {
      const data = src.byte ? new Uint8Array(src.w * src.h * 4) : new Float32Array(src.w * src.h * 4);
      gl.bindFramebuffer(gl.FRAMEBUFFER, src.fb);
      gl.readPixels(0, 0, src.w, src.h, gl.RGBA, src.byte ? gl.UNSIGNED_BYTE : gl.FLOAT, data);
      assert(gl.getError() === gl.NO_ERROR, 'GL read error');
      if(src.byte) for(const value of data) readbackHash=Math.imul(readbackHash^value,16777619)>>>0;
      const values = new Float64Array(src.w * src.h);
      for (let i = 0; i < values.length; i++) {
        values[i] = data[4*i] / (src.byte ? 255 : 1);
        for (let c = 1; c < 3; c++) assert(data[4*i+c] === data[4*i], 'RGB channels diverged');
        assert(Math.abs(data[4*i+3] / (src.byte ? 255 : 1) - 1) < 1e-5, 'opaque alpha lost');
      }
      return { w: src.w, h: src.h, v: values };
    }
    const quantize = (v, byte) => byte ? Math.round(Math.max(0, Math.min(1, v)) * 255) / 255 : v;
    function image(w, h, fn, byte) {
      return { w, h, v: Float64Array.from({ length: w*h }, (_, i) => quantize(fn(i % w, Math.floor(i/w)), byte)) };
    }
    const at = (a, x, y) => a.v[Math.max(0, Math.min(a.h-1, y))*a.w + Math.max(0, Math.min(a.w-1, x))];
    function linear(a, x, y) {
      const ix = Math.floor(x), iy = Math.floor(y), fx = x-ix, fy = y-iy;
      return (at(a,ix,iy)*(1-fx)+at(a,ix+1,iy)*fx)*(1-fy) + (at(a,ix,iy+1)*(1-fx)+at(a,ix+1,iy+1)*fx)*fy;
    }
    // Independent oracle: integrate the piecewise-constant source over each destination
    // rectangle. No GLSL sampling offsets, texelFetch logic, or fixed 3x3 loop copied.
    function area(a, byte) {
      const w = Math.ceil(a.w/2), h = Math.ceil(a.h/2);
      return image(w, h, (x,y) => {
        const l=x*a.w/w, r=(x+1)*a.w/w, b=y*a.h/h, t=(y+1)*a.h/h;
        let sum=0;
        for (let sy=Math.floor(b); sy<Math.ceil(t); sy++) for (let sx=Math.floor(l); sx<Math.ceil(r); sx++)
          sum += at(a,sx,sy) * (Math.min(r,sx+1)-Math.max(l,sx)) * (Math.min(t,sy+1)-Math.max(b,sy));
        return sum/((r-l)*(t-b));
      }, byte);
    }
    // Unpaired integer convolution independently checks the production paired bilinear kernel.
    function convolution(a, horizontal, byte) {
      return image(a.w,a.h,(x,y) => {
        let sum = at(a,x,y)*g[0];
        for (let i=1;i<=12;i++) sum += g[i]*(at(a,x+(horizontal?i:0),y+(horizontal?0:i))+at(a,x-(horizontal?i:0),y-(horizontal?0:i)));
        return sum;
      },byte);
    }
    function compare(actual, expected, tolerance, label) {
      let max=0;
      for (let i=0;i<actual.v.length;i++) {
        assert(Number.isFinite(actual.v[i]), `${label}: non-finite pixel`);
        max=Math.max(max,Math.abs(actual.v[i]-expected.v[i]));
      }
      assert(max <= tolerance+1e-9, `${label}: max error ${max*255} byte levels exceeds ${tolerance*255}`);
      return max;
    }
    function releaseFrom(mark) {
      for (const [kind, value] of resources.splice(mark).reverse()) gl[`delete${kind}`](value);
    }
    function slopes(a) {
      let x=0,y=0;
      for(let row=0;row<a.h;row++)for(let col=0;col<a.w;col++) {
        x=Math.max(x,Math.abs(at(a,col+1,row)-at(a,col,row)));
        y=Math.max(y,Math.abs(at(a,col,row+1)-at(a,col,row)));
      }
      return {x,y};
    }
    function pipeline(input, byte, label) {
      const mark=resources.length;
      try {
        const sharp=target(input.w,input.h,byte,input.v); let src=sharp, cpu=input, stage=0;
        const errors=[];
        for (let i=0;i<2;i++) {
          cpu=area(cpu,byte); const dst=target(cpu.w,cpu.h,byte);
          draw(P.down,dst,()=>bind(P.down,'uSrc',src)); src=dst;
          errors.push(compare(read(src),cpu,byte ? ++stage/255 : 2e-5, `${label}/down${i+1}`));
        }
        let floatTolerance=2e-5;
        for (const horizontal of [true,false]) {
          const slope=slopes(cpu);
          floatTolerance+=(1-g[0])*(horizontal?slope.x:slope.y)/256;
          cpu=convolution(cpu,horizontal,byte); const dst=target(cpu.w,cpu.h,byte);
          draw(P.blur,dst,()=>{
            bind(P.blur,'uSrc',src); const L=n=>gl.getUniformLocation(P.blur,n);
            gl.uniform2f(L('uStep'),horizontal?1/src.w:0,horizontal?0:1/src.h);
            gl.uniform1i(L('uTaps'),7); gl.uniform1fv(L('uOffsets'),offsets); gl.uniform1fv(L('uWeights'),weights);
          }); src=dst;
          errors.push(compare(read(src),cpu,byte ? ++stage/255 : floatTolerance, `${label}/blur${horizontal?'X':'Y'}`));
        }
        const dst=target(input.w,input.h,byte);
        draw(P.glass,dst,()=>{bind(P.glass,'uSharp',sharp);bind(P.glass,'uBlurred',src,1);gl.uniform1f(gl.getUniformLocation(P.glass,'uAmount'),1);});
        const expected=image(input.w,input.h,(x,y)=>linear(cpu,(x+.5)*cpu.w/input.w-.5,(y+.5)*cpu.h/input.h-.5),byte);
        const actual=read(dst);
        // Texture interpolation may use only eight fractional bits. Bound its
        // error by the actual adjacent-texel slopes, not by a blanket epsilon.
        const slope=slopes(cpu);
        const reconstructionTolerance=floatTolerance+(slope.x+slope.y)/256;
        errors.push(compare(actual,expected,byte ? ++stage/255 : reconstructionTolerance,`${label}/reconstruction`));
        return { actual, errors };
      } finally { releaseFrom(mark); }
    }
    let negativeControlError=0;
    for (const {byte,dither,configuration} of [
      {byte:true,dither:true,configuration:'production default DITHER enabled'},
      {byte:true,dither:false,configuration:'deterministic DITHER disabled'},
      {byte:false,dither:false,configuration:'analytic only DITHER disabled'},
    ]) {
      // First suite leaves the fresh context's enabled state untouched.
      if(!dither) gl.disable(gl.DITHER);
      assert(gl.isEnabled(gl.DITHER)===dither,`${configuration}: unexpected GL state`);
      readbackHash=2166136261;
      let cases=0, maxError=0;
      // Retain the original unequal-channel flat field, which also detects channel swaps.
      const colorMark=resources.length;
      try {
        const color=byte?[64/255,128/255,191/255]:[.25,.5,.75];
        const sharp=target(9,7,byte,Array.from({length:63},()=>color)); let src=sharp;
        for(let i=0;i<2;i++) {
          const dst=target(Math.ceil(src.w/2),Math.ceil(src.h/2),byte);
          draw(P.down,dst,()=>bind(P.down,'uSrc',src));src=dst;
        }
        for(const horizontal of [true,false]) {
          const dst=target(src.w,src.h,byte);
          draw(P.blur,dst,()=>{
            bind(P.blur,'uSrc',src);const L=n=>gl.getUniformLocation(P.blur,n);
            gl.uniform2f(L('uStep'),horizontal?1/src.w:0,horizontal?0:1/src.h);
            gl.uniform1i(L('uTaps'),7);gl.uniform1fv(L('uOffsets'),offsets);gl.uniform1fv(L('uWeights'),weights);
          });src=dst;
        }
        const dst=target(9,7,byte);
        draw(P.glass,dst,()=>{bind(P.glass,'uSharp',sharp);bind(P.glass,'uBlurred',src,1);gl.uniform1f(gl.getUniformLocation(P.glass,'uAmount'),1);});
        const pixels=byte?new Uint8Array(63*4):new Float32Array(63*4);
        gl.readPixels(0,0,9,7,gl.RGBA,byte?gl.UNSIGNED_BYTE:gl.FLOAT,pixels);
        assert(gl.getError()===gl.NO_ERROR,'colored flat read failed');
        for(let i=0;i<63;i++)for(let c=0;c<4;c++)
          assert(Math.abs(pixels[i*4+c]/(byte?255:1)-(c===3?1:color[c]))<=(byte?1/255:1e-5),'colored flat preservation');
        cases++;
      } finally {releaseFrom(colorMark);}
      for (const [w,h] of [[255,255],[256,256],[257,257],[258,258],[1,1],[1,7],[7,1],[2,3],[3,2],[5,9]]) {
        const patterns=[['ramp',(x,y)=>(x+y)/Math.max(1,w+h-2)],
          ['low contrast',(x,y)=>(127+((x+y)%2))/255],
          ['bright patch',(x,y)=>x>=w*.3&&x<w*.6&&y>=h*.3&&y<h*.6?1:0],
          ['edge',(x)=>x<w/2?0:1]];
        for (const phase of [0,1]) {
          patterns.push([`checker${phase}`,(x,y)=>(x+y+phase)%2]);
          patterns.push([`stripes${phase}`,(x)=>(x+phase)%2]);
        }
        for (const value of [0,1,64/255,128/255,191/255]) patterns.push([`flat${value}`,()=>value]);
        for (const [name,fn] of patterns) {
          const input=image(w,h,fn,byte), label=`${byte?'RGBA8':'float'}/${w}x${h}/${name}`;
          const { actual, errors }=pipeline(input,byte,label); maxError=Math.max(maxError,...errors); cases++;
          if (name.startsWith('flat')) compare(actual,input,byte?1/255:1e-5,`${label}/constant`);
          if (!byte && name==='checker0' && w===257) {
            const reference=area(input,false);
            const old=image(reference.w,reference.h,(x,y)=>linear(input,(x+.5)*w/reference.w-.5,(y+.5)*h/reference.h-.5),false);
            for (let i=0;i<old.v.length;i++) negativeControlError=Math.max(negativeControlError,Math.abs(old.v[i]-reference.v[i]));
          }
        }
      }
      // All sixteen point locations within a 4x4 reduction cell. Byte points may
      // disappear after blur; compare their quantized pipeline, never demand energy.
      const impulses=[];
      for (const w of [255,256,257,258]) for (let py=0;py<4;py++) for (let px=0;px<4;px++) {
        const c=128,input=image(w,w,(x,y)=>x===c+px&&y===c+py?1:0,byte);
        const {actual,errors}=pipeline(input,byte,`${byte?'RGBA8':'float'}/${w}/point${px},${py}`); cases++;
        maxError=Math.max(maxError,...errors);
        if (!byte && w===256) {
          let energy=0,mx=0,my=0,variance=0;
          for(let y=0;y<w;y++)for(let x=0;x<w;x++){const v=actual.v[y*w+x];energy+=v;mx+=x*v;my+=y*v;}
          mx/=energy;my/=energy;
          for(let y=0;y<w;y++)for(let x=0;x<w;x++)variance+=actual.v[y*w+x]*((x-mx)**2+(y-my)**2);
          const sigma=Math.sqrt(variance/energy/2);
          assert(Math.abs(energy-1)<.002,`point energy ${energy}`);
          assert(sigma>15&&sigma<18,`measured sigma ${sigma}`);
          assert(Math.max(Math.abs(mx-c-px),Math.abs(my-c-py))<1.501,`point centroid ${mx},${my}`);
          impulses.push({phase:[px,py],energy,sigma,centre:[mx-c-px,my-c-py]});
        }
      }
      const mark=resources.length, mixes=[], mixRanges=[];
      try {
        const black=target(1,1,byte,[0]),white=target(1,1,byte,[1]),dst=target(8,8,byte);
        for(const amount of [0,.25,.5,.75,1]) {
          draw(P.glass,dst,()=>{bind(P.glass,'uSharp',black);bind(P.glass,'uBlurred',white,1);gl.uniform1f(gl.getUniformLocation(P.glass,'uAmount'),amount);});
          const values=read(dst).v,expected=amount*amount*(3-2*amount);
          // Dithering may select either adjacent code; nearest rounding without
          // dithering has the tighter half-code bound. Inspect a whole 8x8 tile.
          for(const value of values) assert(Math.abs(value-expected)<=(byte?(dither?1:.5)/255+1e-7:1e-6),`${configuration}/clarity ${amount}: ${value} expected ${expected}`);
          mixes.push(values[0]);mixRanges.push([Math.min(...values),Math.max(...values)]);
        }
      } finally {releaseFrom(mark);}
      results.push({format:byte?'RGBA8':'RGBA32F analytic only',configuration,ditherEnabled:gl.isEnabled(gl.DITHER),cases,maxErrorByteLevels:maxError*255,mixes,mixRanges,impulses,readbackSignature:byte?readbackHash:null});
    }
    // This error must exceed even the largest production pipeline budget (5/255).
    assert(negativeControlError>5/255,'checkerboard test failed to reject old single-fetch downsample');
    return {ok:true,defaultDitherEnabled,results,ditherReadbackSignaturesMatch:results[0].readbackSignature===results[1].readbackSignature,
      ditherCaveat:'DITHER is implementation-dependent; matching signatures do not prove a mobile driver uses the same quantization or dither pattern.',
      negativeControlErrorByteLevels:negativeControlError*255,renderer:gl.getParameter(gl.RENDERER)};
  } finally {
    for (const [kind,value] of resources.reverse()) gl[`delete${kind}`](value);
    gl.getExtension('WEBGL_lose_context')?.loseContext();
  }
}
if (typeof module !== 'undefined') module.exports = groundGlassCheck;
