package app.spektralab.gl

/**
 * GLSL ES 3.00 programs for the spektrafilm pipeline. Each pass mirrors one
 * stage of the Kotlin engine (`engine/`), which is validated against the
 * Python reference:
 *
 *  expose   linear sRGB -> film raw exposure (Hanatos 2025 upsampling LUT)
 *  down     2x2 pyramid reduction
 *  blur     separable Gaussian (one octave of a Gaussian mixture)
 *  combine  weighted sum of blurred octaves
 *  corr     DIR coupler inhibitor amount (half resolution)
 *  develop  density curves, DIR couplers, sublayer Poisson grain
 *  final    film density -> print -> scan 3D LUT, glare, sRGB, border/overlay
 */
object Shaders {
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
"""

    private const val EXTERNAL_HEADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
#define EXTERNAL 1
"""

    /** Input sampling shared by expose and original: photos are sRGB textures (linear on fetch),
     * video frames come from an external OES texture with sRGB-like transfer. */
    private const val INPUT = """
#ifdef EXTERNAL
uniform samplerExternalOES uInput;
#else
uniform sampler2D uInput;
#endif
uniform mat3 uUvTransform;
vec3 srgbToLinear(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}
vec3 linearToSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}
vec3 inputLinear(vec2 uv) {
    vec2 s = (uUvTransform * vec3(uv, 1.0)).xy;
#ifdef EXTERNAL
    return srgbToLinear(texture(uInput, s).rgb);
#else
    return texture(uInput, s).rgb;
#endif
}
"""

    private const val EXPOSE_BODY = """
in vec2 vUv;
out vec4 fragColor;
uniform mat3 uToXyz;
uniform sampler2D uTcLut;
uniform float uTcSize;
uniform float uExposure;
void main() {
    vec3 rgb = inputLinear(vUv);
    vec3 xyz = uToXyz * rgb;
    float s = xyz.x + xyz.y + xyz.z;
    float d = max(s, 1e-10);
    float cx = xyz.x / d;
    float cy = xyz.y / d;
    float ty = clamp(cy / max(1.0 - cx, 1e-10), 0.0, 1.0);
    float tx = clamp((1.0 - cx) * (1.0 - cx), 0.0, 1.0);
    vec2 uv = (vec2(ty, tx) * (uTcSize - 1.0) + 0.5) / uTcSize;
    vec3 raw = texture(uTcLut, uv).rgb * s * uExposure;
    fragColor = vec4(max(raw, vec3(0.0)), 1.0);
}
"""

    val EXPOSE = HEADER + INPUT + EXPOSE_BODY
    val EXPOSE_EXTERNAL = EXTERNAL_HEADER + INPUT + EXPOSE_BODY

    const val DOWN = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
void main() { fragColor = texture(uSrc, vUv); }
"""

    const val BLUR = """#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2 uStep;
uniform int uTaps;
uniform float uOffsets[16];
uniform float uWeights[16];
void main() {
    vec3 acc = uWeights[0] * texture(uSrc, vUv).rgb;
    for (int i = 1; i < 16; i++) {
        if (i >= uTaps) break;
        vec2 o = uStep * uOffsets[i];
        acc += uWeights[i] * (texture(uSrc, vUv + o).rgb + texture(uSrc, vUv - o).rgb);
    }
    fragColor = vec4(acc, 1.0);
}
"""

    const val MAX_COMBINE = 10

    val COMBINE: String = buildString {
        append(HEADER)
        append("in vec2 vUv;\nout vec4 fragColor;\nuniform sampler2D uBase;\nuniform vec3 uBaseW;\nuniform int uCount;\nuniform vec3 uW[$MAX_COMBINE];\n")
        for (i in 0 until MAX_COMBINE) append("uniform sampler2D uT$i;\n")
        append("void main() {\n    vec3 s = uBaseW * texture(uBase, vUv).rgb;\n")
        for (i in 0 until MAX_COMBINE) append("    if (uCount > $i) s += uW[$i] * texture(uT$i, vUv).rgb;\n")
        append("    fragColor = vec4(s, 1.0);\n}\n")
    }

    private const val CURVES = """
uniform sampler2D uCurves;
uniform sampler2D uCurves0;
uniform vec2 uCurveAxis;   // first / last log exposure (divided by gamma)
uniform float uCurveSize;
vec3 curveCoord(vec3 x) {
    vec3 t = clamp((x - uCurveAxis.x) / (uCurveAxis.y - uCurveAxis.x), 0.0, 1.0);
    return (t * (uCurveSize - 1.0) + 0.5) / uCurveSize;
}
vec3 lookupCurves(sampler2D tex, vec3 x) {
    vec3 u = curveCoord(x);
    return vec3(texture(tex, vec2(u.r, 0.5)).r, texture(tex, vec2(u.g, 0.5)).g, texture(tex, vec2(u.b, 0.5)).b);
}
vec3 log10v(vec3 v) { return log2(max(v, vec3(0.0)) + 1e-10) * 0.30102999566; }
"""

    val CORR = HEADER + CURVES + """
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uL1;
uniform sampler2D uS;
uniform vec3 uIdW;
uniform mat3 uCoupler;
uniform vec3 uDmax;
uniform int uPositive;
void main() {
    vec3 raw = uIdW * texture(uL1, vUv).rgb + texture(uS, vUv).rgb;
    vec3 d0 = lookupCurves(uCurves, log10v(raw));
    vec3 silver = uPositive == 1 ? uDmax - d0 : d0;
    fragColor = vec4(uCoupler * silver, 1.0);
}
"""

    val DEVELOP = HEADER + CURVES + """
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uRaw;
uniform sampler2D uS;
uniform sampler2D uCb;
uniform vec3 uIdW;
uniform vec3 uFineW;
uniform vec2 uTexel;
uniform int uCouplers;
// grain
uniform int uGrain;
uniform float uGrainAmount;
uniform sampler2D uLayers;
uniform vec3 uTableHi;
uniform vec3 uLayerDmin[3];
uniform vec3 uLayerDmax[3];
uniform vec3 uLayerN[3];
uniform vec3 uUniformity;
uniform vec3 uDmin;
uniform uint uSeed;

uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
float rnd(inout uint s) {
    s = pcg(s);
    return (float(s >> 8u) + 0.5) / 16777216.0;
}
float gauss(inout uint s) {
    float u1 = rnd(s);
    float u2 = rnd(s);
    return sqrt(-2.0 * log(u1)) * cos(6.28318530718 * u2);
}
float poisson(float lambda, inout uint s) {
    if (lambda > 12.0) return max(0.0, lambda + sqrt(lambda) * gauss(s));
    float l = exp(-lambda);
    float p = 1.0;
    float k = 0.0;
    for (int i = 0; i < 64; i++) {
        p *= rnd(s);
        if (p <= l) break;
        k += 1.0;
    }
    return k;
}

vec3 rawAt(vec2 uv) { return texture(uRaw, uv).rgb; }

void main() {
    vec3 c = rawAt(vUv);
    vec3 raw = uIdW * c + texture(uS, vUv).rgb;
    if (uFineW != vec3(0.0)) {
        // Gaussian sigma 0.75 px, 3x3
        vec3 e = rawAt(vUv + vec2(uTexel.x, 0.0)) + rawAt(vUv - vec2(uTexel.x, 0.0))
               + rawAt(vUv + vec2(0.0, uTexel.y)) + rawAt(vUv - vec2(0.0, uTexel.y));
        vec3 k = rawAt(vUv + uTexel) + rawAt(vUv - uTexel)
               + rawAt(vUv + vec2(uTexel.x, -uTexel.y)) + rawAt(vUv + vec2(-uTexel.x, uTexel.y));
        vec3 blur = 0.30119 * c + 0.12381 * e + 0.05090 * k;
        raw += uFineW * blur;
    }
    vec3 logRaw = log10v(raw);
    vec3 d;
    if (uCouplers == 1) d = lookupCurves(uCurves0, logRaw - texture(uCb, vUv).rgb);
    else d = lookupCurves(uCurves, logRaw);

    if (uGrain == 1) {
        uint s = pcg(uint(gl_FragCoord.x) + pcg(uint(gl_FragCoord.y) + pcg(uSeed)));
        vec3 t = clamp(d / uTableHi, 0.0, 1.0) * 127.0 / 128.0 + 0.5 / 128.0;
        vec3 acc = vec3(0.0);
        vec3 mean = vec3(0.0);
        for (int l = 0; l < 3; l++) {
            float v = (float(l) + 0.5) / 3.0;
            vec3 dl = vec3(texture(uLayers, vec2(t.r, v)).r, texture(uLayers, vec2(t.g, v)).g,
                           texture(uLayers, vec2(t.b, v)).b) + uLayerDmin[l];
            vec3 dmax = uLayerDmax[l];
            vec3 n = uLayerN[l];
            vec3 p = clamp(dl / dmax, 1e-6, 1.0 - 1e-6);
            vec3 sat = 1.0 - p * uUniformity * (1.0 - 1e-6);
            vec3 lambda = n * p / sat;
            vec3 od = dmax / n * sat;
            vec3 count = vec3(poisson(lambda.r, s), poisson(lambda.g, s), poisson(lambda.b, s));
            acc += count * od;
            mean += lambda * od;
        }
        d = mean + uGrainAmount * (acc - mean) - uDmin;
    }
    fragColor = vec4(d, 1.0);
}
"""

    private const val FINAL_BODY = """
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uDensity;
uniform vec2 uTexel;
uniform vec3 uGrainBlur;    // center / edge / corner weights of a 3x3 kernel
uniform sampler3D uLut;
uniform vec3 uLutLo;
uniform vec3 uLutHi;
uniform float uLutSize;
uniform float uGlare;
uniform vec4 uImageRect;    // image area in output uv (x0, y0, x1, y1), y up
uniform vec3 uBorder;
uniform int uOverlay;
uniform sampler2D uOverlayTex;
uniform int uFlipY;
uniform int uOriginal;

vec3 dens(vec2 uv) { return texture(uDensity, uv).rgb; }

void main() {
    vec2 p = vUv;
    if (uFlipY == 1) p.y = 1.0 - p.y;
    vec3 col;
    if (p.x < uImageRect.x || p.x > uImageRect.z || p.y < uImageRect.y || p.y > uImageRect.w) {
        col = uBorder;
    } else {
        vec2 iuv = (p - uImageRect.xy) / (uImageRect.zw - uImageRect.xy);
        if (uOriginal == 1) {
            col = linearToSrgb(inputLinear(iuv));
        } else {
            vec3 d = dens(iuv);
            if (uGrainBlur.x < 0.999) {
                vec3 e = dens(iuv + vec2(uTexel.x, 0.0)) + dens(iuv - vec2(uTexel.x, 0.0))
                       + dens(iuv + vec2(0.0, uTexel.y)) + dens(iuv - vec2(0.0, uTexel.y));
                vec3 k = dens(iuv + uTexel) + dens(iuv - uTexel)
                       + dens(iuv + vec2(uTexel.x, -uTexel.y)) + dens(iuv + vec2(-uTexel.x, uTexel.y));
                d = uGrainBlur.x * d + uGrainBlur.y * e + uGrainBlur.z * k;
            }
            vec3 t = clamp((d - uLutLo) / (uLutHi - uLutLo), 0.0, 1.0);
            vec3 c = (t * (uLutSize - 1.0) + 0.5) / uLutSize;
            vec3 rgb = texture(uLut, c.bgr).rgb + uGlare;
            col = linearToSrgb(rgb);
        }
    }
    if (uOverlay == 1) {
        vec4 o = texture(uOverlayTex, vec2(p.x, 1.0 - p.y));
        col = o.rgb + col * (1.0 - o.a);
    }
    fragColor = vec4(col, 1.0);
}
"""

    val FINAL = HEADER + INPUT + FINAL_BODY
    val FINAL_EXTERNAL = EXTERNAL_HEADER + INPUT + FINAL_BODY
}
