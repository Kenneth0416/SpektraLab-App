# GPU shader check

Runs the app's GLSL ES 3.00 programs in headless Chromium (WebGL2, SwiftShader)
and compares them with the Kotlin engine, so shader math and texture layouts can
be verified without an Android device.

```bash
./gradlew :engine:test -Dspektra.gldump=/tmp/glcheck   # shaders + look tables + CPU expectations
cp tools/glcheck/*.js /tmp/glcheck/
node /tmp/glcheck/pipeline.js   # expose -> couplers -> develop -> print/scan LUT, vs CPU (8-bit error)
node /tmp/glcheck/grain.js      # sublayer Poisson grain: mean / std vs CPU model
node /tmp/glcheck/blur.js /tmp/glcheck   # pyramid Gaussian: sigma and energy
```

Needs Node with `playwright` and a Chromium build.
