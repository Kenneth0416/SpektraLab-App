#!/usr/bin/env python3
"""Run stage benchmarks against an installed APK without installing/replacing it.

The RAW remains on the device. Only a temporary dex runner is pushed; it is
removed after the process exits. `mapped` also creates immediately unlinked
scratch mappings. `render` encodes to a counting stream, never MediaStore.
Requires JDK 17, Android SDK 35, an already-built engine/build/libs/engine.jar,
and the project's cached Kotlin stdlib 2.1.21.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["raw", "lut", "gain", "render", "mapped"])
    parser.add_argument("--raw", default="-", help="Existing absolute DNG path on device; required except for lut")
    parser.add_argument("--serial", help="adb device serial; omit only when exactly one device is connected")
    parser.add_argument("--package", default="app.spektralab")
    parser.add_argument("--apk", type=Path, help="Benchmark a candidate local APK without installing it")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    parser.add_argument("--stdlib", type=Path, help="Override path to kotlin-stdlib.jar")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.mode != "lut" and not args.raw.startswith("/"):
        parser.error("--raw must name an existing absolute DNG path on the device")
    root = Path(__file__).resolve().parents[2]
    engine = root / "engine/build/libs/engine.jar"
    android = args.sdk / "platforms/android-35/android.jar"
    d8 = args.sdk / "build-tools/35.0.0/d8"
    stdlib = args.stdlib
    if stdlib is None:
        matches = list((Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.21").glob("*/kotlin-stdlib-2.1.21.jar"))
        if len(matches) != 1:
            parser.error("Pass --stdlib with the Kotlin stdlib jar used by the project")
        stdlib = matches[0]
    for path in (engine, android, d8, stdlib):
        if not path.is_file():
            parser.error(f"Missing {path}; build :engine:jar / install the required SDK first")
    adb = [str(args.sdk / "platform-tools/adb")]
    if args.serial:
        adb += ["-s", args.serial]
    remote = f"/data/local/tmp/spektra-raw-bench-{uuid.uuid4().hex[:12]}.zip"
    if args.apk:
        if not args.apk.is_file():
            parser.error(f"Missing APK: {args.apk}")
        apk = remote.removesuffix(".zip") + ".apk"
    else:
        packages = subprocess.check_output(adb + ["shell", "pm", "path", args.package], text=True).splitlines()
        apk = next((p.removeprefix("package:").strip() for p in packages if p.endswith("/base.apk")), None)
        if not apk:
            parser.error("No installed base.apk found")
    with tempfile.TemporaryDirectory(prefix="spektra-raw-bench-") as tmp:
        directory = Path(tmp)
        classes = directory / "classes"
        classes.mkdir()
        jar = directory / "runner.jar"
        dex = directory / "runner.zip"
        subprocess.run(["javac", "-cp", os.pathsep.join(map(str, [engine, android, stdlib])), "-d", str(classes),
                        str(Path(__file__).with_name("RawStageBench.java"))], check=True)
        subprocess.run(["jar", "cf", str(jar), "-C", str(classes), "."], check=True)
        subprocess.run([str(d8), "--min-api", "29", "--lib", str(android), "--classpath", str(engine),
                        "--classpath", str(stdlib), "--output", str(dex), str(jar)], check=True)
        subprocess.run(adb + ["push", str(dex), remote], check=True)
        try:
            if args.apk:
                subprocess.run(adb + ["push", str(args.apk), apk], check=True)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            command = shlex.join(["env", f"CLASSPATH={remote}:{apk}", "app_process", "/system/bin", "RawStageBench", args.mode, args.raw, apk])
            print(f"Running {args.mode}; output: {args.output}", flush=True)
            with args.output.open("w") as log:
                subprocess.run(adb + ["shell", command], stdout=log, stderr=subprocess.STDOUT, check=True)
            if "BENCH_COMPLETE" not in args.output.read_text():
                raise RuntimeError("Benchmark did not report completion; inspect output")
        finally:
            subprocess.run(adb + ["shell", "rm", "-f", remote] + ([apk] if args.apk else []), check=False)


if __name__ == "__main__":
    main()
