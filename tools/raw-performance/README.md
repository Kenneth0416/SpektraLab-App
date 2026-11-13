# RAW stage benchmark

This diagnostic loads the **installed native Android APK**, or a candidate APK supplied with `--apk`, in a separate `app_process`, then calls its RAW/film methods. It does not install or replace the app, capture a photo, change preferences, or write MediaStore items. The original DNG stays on the device. Only timing, output dimensions/byte counts, and SHA-256 checksums are returned.

The research results are in [the 2026-11-13 report](../../docs/performance/raw-capture-2026-11-13.md).

Requirements: JDK 17, Android SDK platform 35 and build-tools 35.0.0, Python 3.9+, one authorized adb device (or `--serial`), and the installed `app.spektralab` APK. Compile against an engine version with the same public method signatures as that APK. The script defaults to cached Kotlin stdlib 2.1.21; `--stdlib` can override it. On macOS with Homebrew JDK 17, set `JAVA_HOME=/opt/homebrew/opt/openjdk@17` if needed.

```sh
./gradlew :engine:jar

# Builds the tiny Java/D8 runner, pushes a unique temporary dex,
# discovers the installed base.apk, runs the mode, and removes the dex.
python3 tools/raw-performance/run.py raw \
  --raw /sdcard/DCIM/Silvercam/EXISTING_CAPTURE.dng \
  --output /tmp/raw-stages.log

# Compare a freshly built APK on the same device without installing it.
./gradlew :app:assembleDebug
python3 tools/raw-performance/run.py raw \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --raw /sdcard/DCIM/Silvercam/EXISTING_CAPTURE.dng \
  --output /tmp/raw-candidate.log

python3 tools/raw-performance/run.py lut --output /tmp/raw-lut.log

python3 tools/raw-performance/run.py render \
  --raw /sdcard/DCIM/Silvercam/EXISTING_CAPTURE.dng \
  --output /tmp/raw-render.log
```

Use an existing Bayer camera DNG for the RAW investigation. The measured fixture was 4080×3072, with a 4064×3056 active crop; the tool is not a memory-budgeted general-purpose RAW viewer. Do not run multiple modes concurrently on the same phone, since they would compete for CPU, GPU and memory and invalidate timing comparisons.

| Mode | What it measures |
|---|---|
| `raw` | Two rounds of file read, successful DNG parse, development without chroma denoise, then denoise. Requests edges 512/640/1600 in fast mode and 2048/4096 in quality mode. Prints **actual** output dimensions and SHA-256 of every developed RGBA half-float buffer after denoise (checksum time is outside stage timers). Bayer-only. |
| `lut` | Engine data initialization, 36³/48³ output LUT creation, and reuse of a previously built LUT. Uses assets in the installed APK. No DNG required. |
| `render` | Full RAW development, default Portra 400 setup, EGL/renderer initialization, GPU film rendering/readback, JPEG 95 and PNG encoding. Output is a counting stream, so encode times exclude file I/O. Render edges are 4096, 2048, 4096. |
| `gain` | Two paired runs of development with original gain maps and with gain maps removed, without chroma denoise. **A cost-attribution ablation that changes the image, not a quality-preserving optimization.** |
| `mapped` | Two rounds of quality development and chroma denoise using `posix_fallocate` + file mapping, similar to the app export allocation. Scratch files under `/data/local/tmp` are immediately unlinked. Bayer-only. |

Every successful run ends with `BENCH_COMPLETE`; failed decode/encode is an error. A supplied candidate APK is pushed under a unique temporary filename and deleted along with the runner afterward. Measurements use `System.nanoTime()`. The runner asks for GC between large output allocations, outside measured sections, to avoid retaining preceding outputs. This differs from real app scheduling and must be disclosed when interpreting results. `render` uses the default look and a fixed exposure matching the investigation photo; it does not read the app's library or preferences.

`app_process` timings are **not end-to-end app benchmarks**. They omit the app's coroutine/memory queue, UI, active camera stream, MediaStore operations, EXIF writing, and production lifecycle. Runtime compilation/profile state, shader caches, thermal conditions, and heap settings differ. Do not extrapolate a desktop JVM number to a phone, or add these stage timings to claim an exact explanation of a historical capture. To validate a product change, instrument the normal app path and use release/profileable builds with repeated cold/warm journeys.

The source file can also be compiled manually with `javac` against `engine/build/libs/engine.jar`, `android.jar`, and Kotlin stdlib, then converted with D8 (`--min-api 29`). `run.py` automates those commands using structured subprocess arguments; it does not change the installed application's compilation mode or performance settings.
