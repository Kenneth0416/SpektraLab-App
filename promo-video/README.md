# Promo film pipeline

The current 119.5-second film is **v3**. `film/make_v3.py` transforms the canonical photographer-oriented markup in `v1/film.html`; `film/build.py` runs that generator and writes `film/data.js` from the current `timeline.json` plus the unchanged characteristic-curve data in `v1/data.js`. Paths resolve from the scripts, so the build works from any working directory. The legacy v2 fragments and `patch_v3b.py` are not build steps; the latter is a retired one-shot patch.

The generator preserves the film's existing visuals and story. Its installation card now points to the available APK on the project homepage.

## Dependencies

- Python 3.10+ and Node.js (the tests exercise the browser readiness code with Node).
- Locally installed Ego lite and `ego-browser` CLI. Before browser work, read the installed Ego skill and run `ego-browser skill`. All browser work uses `ego-browser nodejs`, a task Space, and a temporary localhost HTTP server. There is no Playwright dependency or separate browser launch.
- FFmpeg and ffprobe on PATH for encoding, timeline audio assembly, and mixing.
- Assets already tracked elsewhere in this repository: `public/assets/scenes/*.jpg`, `app/src/main/res/font/long_cang.ttf`, and `app/src/main/res/font/martian_mono.ttf`. Keep the promo folder in this repository so its relative asset references resolve.
- Optional speech generation: a separate environment with compatible `torch`, `qwen-tts`, `qwen-asr`, `soundfile`, `numpy`, and `librosa`. These are unnecessary for a silent film build or the unit tests. Use versions compatible with your installed Qwen model SDK and hardware.

Commands below run from the repository root; absolute script paths also work.

## Build, test, and render

```sh
python3 promo-video/film/build.py
python3 -m unittest discover -s promo-video/tests -v
ego-browser skill
python3 promo-video/film/render.py --fps 2 --start 14 --max-frames 2 --frames-only
python3 promo-video/film/stills.py 9.5 18.5 115
python3 promo-video/film/occlusion.py --duration 2 --step 0.5
# Full silent film (slower: one controlled Ego page)
python3 promo-video/film/render.py --fps 60
```

Each render/stills/occlusion invocation gets a new `frames/run-*` or `scratch/run-*` directory. Nothing is reused from a previous input or FPS. No files are deleted automatically. Full rendering writes `build/film-silent.mp4` only after the browser succeeds and every exact frame is present. Frames are 1920×1080 PNG, then encoded as H.264. `--duration`, `--max-frames`, and `--frames-only` bound smoke tests. A bounded render without `--frames-only` writes its short video inside the fresh run directory, preserving the full film. `--output` explicitly selects another video destination.

The shared driver accepts `--film`, `--output-dir`, and `--space`. Use one Space for an overall goal: pass `--keep-space` for intermediate checks, then resume the same `--space` and finish it when the goal is complete. The driver prints `EGO_SPACE_ID`. On errors it leaves the Space available for inspection and exits nonzero; recover within that Space. A normal successful standalone invocation finishes its Space. Required image decode failures and runtime errors prevent readiness/capture. Occlusion output is a diagnostic heuristic, saved as JSON in its run directory; it is not a proof that every scene is free of overlap.

## Optional narration and assembly

`tts/narration.json` and `tts/align.json` contain the current script and clip-relative timing. Existing selected takes in `tts/sel/s1.wav` through `s10.wav` are local generated inputs, ignored by git. They are required to rebuild the voiceover:

```sh
python3 promo-video/tts/build_timeline.py
python3 promo-video/film/build.py
```

Timeline assembly probes the selected audio, validates spacing, creates `audio/voiceover.wav`, then updates both `timeline.json` and `film/data.js`. If takes change, align the selected takes again before assembly. The committed timeline allows silent rendering without any audio or model installation.

Models default to Hugging Face IDs and **offline cache-only loading**. Supply a local model directory or another ID via:

- `PROMO_VOICE_DESIGN_MODEL` (default `Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign`)
- `PROMO_TTS_BASE_MODEL` (default `Qwen/Qwen3-TTS-12Hz-1.7B-Base`)
- `PROMO_ALIGNER_MODEL` (default `Qwen/Qwen3-ForcedAligner-0.6B`)
- `PROMO_ASR_MODEL` (default `Qwen/Qwen3-ASR-1.7B`)

`PROMO_DEVICE=auto` chooses CUDA, then MPS, then CPU. CPU/MPS use float32; CUDA uses bfloat16 where supported, otherwise float16. Override with a device such as `cpu`, `mps`, or `cuda:0`. Loading large float32 models requires sufficient memory. No model download is needed for repository validation; a deliberate future download requires explicitly setting `HF_HUB_OFFLINE=0`.

```sh
python3 promo-video/tts/voice_ref_casual.py promo-video/tts
# Listen and choose a reference; this example chooses cref_0.wav.
REF=cref_0.wav REF_TEXT='手机照片套个胶片滤镜，颜色是挺像的，可看着总觉得哪儿不对。我琢磨了很久，问题其实出在光上。' \
  python3 promo-video/tts/clone_lines.py promo-video/tts
# Listen and copy chosen lines/<id>_<take>.wav to sel/<id>.wav, then:
python3 promo-video/tts/align.py promo-video/tts
python3 promo-video/tts/build_timeline.py
```

Voice samples are stochastic outputs; regenerated timing must be reviewed against the film's fixed scene timing. To mix the full-length silent video, provide local music and all sound files named in `audio/sfx-cues.tsv` explicitly:

```sh
SFX=/absolute/local/sfx BGM=/absolute/local/music.mp3 bash promo-video/audio/mix.sh
```

This writes `audio/sfx.wav`, `audio/master.wav`, and `build/silvercam-promo.mp4`. Music and sound effects are not bundled, fetched, or uploaded by this pipeline.

## Minimal source/assets to stage

Stage only this reproducible source set (about 5 MB including sample JPEGs), not the existing 332 MB working folder:

- `.gitignore`, `README.md`, `timeline.json`
- `v1/film.html`, `v1/data.js`
- `film/build.py`, `film/make_v3.py`, `film/patch_v3b.py`, `film/driver.py`, `film/render.py`, `film/stills.py`, `film/occlusion.py`
- `tests/test_pipeline.py`
- `samples/*.jpg` (the processed photo assets; `falls_pro400h_crystal_nodir.jpg` is an unused historical alternative and can be omitted)
- Optional audio source pipeline: `tts/*.py`, `tts/narration.json`, `tts/align.json`, `audio/mix.sh`, `audio/sfx-cues.tsv`

Generated `film/film.html` and `film/data.js`, videos, WAV/MP3 files, frames, scratch material, caches, and obsolete v2/demo files are ignored. Existing planning documents may be staged separately for historical context, but are not required to build. No existing files need to be deleted.
