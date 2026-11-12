# GPU shader checks

The legacy `pipeline.js`, `grain.js`, and `blur.js` use headless Chromium through
Playwright. The ground-glass regression suite uses the installed **Ego lite** browser
and its own task Space; it does not launch Playwright, install a browser, change global
browser settings, or require an HTTP server.

Generate the production shader exports (coordinate Gradle with other workers):

```bash
./gradlew :engine:test -Dspektra.gldump=/tmp/glcheck   # shaders + look tables + CPU expectations
```

Then, from the repository root:

```bash
node tools/glcheck/run_ground_glass.cjs /tmp/glcheck
```

The runner invokes `ego-browser skill` before browser operations, loads absolute shader
paths, evaluates the self-contained `groundGlassCheck` function in an Ego task Space,
prints a JSON report, and exits nonzero on missing inputs, compilation, framebuffer,
GL, numerical assertion, or Ego errors. On success it closes the task Space; on a
browser error Ego retains the same named Space for diagnosis/retry. All GL resources
and the context are released in `finally`, including assertion failures. Read the
installed ego-browser skill before using the runner as an agent.

Required exports: `vertex.glsl`, **`ground_glass_down.glsl`**, `blur.glsl`, and
`ground_glass.glsl`. An absent new downsample export is a dependency failure, not a
reason to silently use the legacy `down.glsl`. The downsample contract is `uSrc` plus
normalized `vUv`, exact source-footprint area integration, and two consecutive
`ceil(source / 2)` reductions. Blur uses the production sigma-4 paired Gaussian;
clarity uses `smoothstep(0, 1, uAmount)`.

## Ground-glass coverage and tolerances

Three configurations run the same production shader programs:

- **Production RGBA8, default DITHER enabled:** the first suite verifies that a fresh
  WebGL context enables `DITHER` and leaves it untouched, matching FilmRenderer.
- **Deterministic RGBA8, DITHER disabled:** repeats every production case with
  dithering explicitly disabled, retaining a reproducible rounding reference.
  Both RGBA8 configurations use byte uploads, RGBA8 source and every intermediate/output,
  and `UNSIGNED_BYTE` readback. Inputs are quantized before upload. An independent
  double-precision CPU oracle integrates piecewise-constant pixels by overlapping
  rectangle area, uses an unpaired integer Gaussian convolution, and quantizes after
  every pass. Thus the oracle includes quantization loss, including a single point
  disappearing after blur; point energy is deliberately not asserted in RGBA8.
- **Analytic RGBA32F:** requires `EXT_color_buffer_float` and
  `OES_texture_float_linear`. These float results describe the filter's mathematical
  properties, not production precision. Float area comparisons allow `2e-5` absolute
  error. Bilinear sampling has a separate data-dependent bound for eight fractional
  interpolation bits: each blur adds `(1 - centreWeight) * maxAxisSlope / 256`, and
  reconstruction adds `(maxXSlope + maxYSlope) / 256`. Normalized subsequent filters
  cannot amplify that error. Flat fields retain the tighter `1e-5` bound. The
  256-square impulse tests retain energy within 0.002,
  measured sigma between 15 and 18 full-resolution pixels, and centroid displacement
  at most 1.501 pixels on both axes for all sixteen reduction-cell phases.

All three configurations test square dimensions **255, 256, 257, 258**, tiny rectangles (including
1x1, 1x7, 7x1, 2x3, 3x2, 5x9), checkerboard and stripe polarity/phase variants, ramps,
low-contrast one-byte patterns, finite-area bright patches, edges, and representable
constant levels 0, 64, 128, 191, 255. All sixteen point phases run at all four large
sizes, with each stage checked against the CPU oracle. RGB equality, opaque alpha,
and finite output are checked as well. These dimensions exercise the direct core;
production caps the source's long edge at 1280 before this core.

RGBA8 stage comparisons allow **1, 2, 3, 4, 5 byte levels** respectively after the two
reductions, two blur axes, and reconstruction. Each positive normalized filter is a
supremum-norm contraction: it cannot amplify prior pixel error. Each new pass gets at
most one byte level for CPU/GPU tie-breaking and sampling arithmetic at an 8-bit
rounding boundary. For enabled dithering, selecting either adjacent quantization code
also differs from the nearest-rounding CPU reference by at most one code per pass;
therefore the same cumulative stage budgets apply to both configurations. This is an
acceptance bound, not an assertion that every driver's unspecified dither algorithm
must satisfy it. The budget is cumulative, rather than a loose relative error for
dark pixels. Constants have a separate, tighter one-byte bound. Clarity checks
0, .25, .5, .75, 1 against **0, .15625, .5, .84375, 1** over an 8x8 output tile: within
one byte for enabled dithering (either adjacent code), half a byte for disabled
dithering (nearest rounding), plus numerical epsilon, or `1e-6` for float.
No float-only result substitutes for a byte test.

Each report includes the queried dither state, clarity tile minima/maxima, and a
rolling signature of byte readbacks for each RGBA8 configuration. Matching signatures
mean this suite observed no difference; they do not establish that dithering is
implemented, nor that another driver will behave identically. WebGL/ANGLE may ignore
or implement dithering differently for RGBA8 framebuffer textures. Android driver
validation remains necessary even when both laptop configurations pass.

A negative-control reference evaluates the **old single bilinear fetch** on the
257-square checkerboard and requires its disagreement with the area oracle to exceed
even the full pipeline's five-byte budget. This proves that the checkerboard inputs
reject the aliasing regression without weakening the production assertions. Every
stage is compared before blur can hide a bad reduction.

`GroundGlassTest.kt` still checks kernel energy, compactness, and offsets. Its former
`4 * sigma == 16` assertion was tautological; it is replaced with the measured second
moment reconstructed from the actual bilinear-pair weights and offsets (expected
truncated sigma approximately 3.962646), alongside the browser's measured GPU width.
The same unit-test file checks `GroundGlassSizing.sourceSize`: HD and 4K become
1280x720, portrait inputs become 720x1280, tiny and in-cap inputs stay unchanged,
and extreme aspect ratios/large integer sizes stay positive without upscaling.

**Still needed on Android hardware:** measure offscreen pass GPU time, sustained camera
frame pacing, thermals, memory, and driver correctness at representative portrait and
landscape sizes up to the 1280 source cap. Laptop WebGL correctness and timing are not
mobile performance evidence.

## Legacy tools

These commands remain available unchanged; they require Node, Playwright, and Chromium:

```bash
cp tools/glcheck/*.js /tmp/glcheck/
node /tmp/glcheck/pipeline.js   # expose -> couplers -> develop -> print/scan LUT, vs CPU (8-bit error)
node /tmp/glcheck/grain.js      # sublayer Poisson grain: mean / std vs CPU model
node /tmp/glcheck/blur.js /tmp/glcheck   # pyramid Gaussian: sigma and energy
node /tmp/glcheck/merge.js /tmp/glcheck  # burst merge gather vs CPU (16-bit error)
node /tmp/glcheck/tiles.js /tmp/glcheck  # tiled (TilePlan ops) vs untiled render: max / mean difference, at seams vs elsewhere
```
