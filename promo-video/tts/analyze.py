from model_config import model_path, model_options
"""Pitch (F0) + loudness + ASR check for narration clips.
usage: analyze.py <wav> [<wav> ...]  (run with qwen3-asr venv)"""
import sys, glob, numpy as np, librosa, torch
from qwen_asr import Qwen3ASRModel

asr_path = model_path("ASR")
model = Qwen3ASRModel.from_pretrained(asr_path, **model_options(torch))
for p in sys.argv[1:]:
    y, sr = librosa.load(p, sr=16000)
    f0, vf, _ = librosa.pyin(y, fmin=60, fmax=400, sr=sr)
    f0 = f0[vf]
    rms = librosa.feature.rms(y=y)[0]
    r = model.transcribe(audio=p, language="Chinese")
    txt = r[0].text if hasattr(r[0], "text") else r[0]
    print(f"{p.split('/')[-1]}: dur={len(y)/sr:.2f}s F0 median={np.median(f0):.0f}Hz p10={np.percentile(f0,10):.0f} p90={np.percentile(f0,90):.0f} peak={np.abs(y).max():.2f}")
    print("   ASR:", txt, flush=True)
