from model_config import model_path, model_options
"""Clone ref_0 voice (VoiceDesign male) onto every narration line with the Base model.
usage: clone_lines.py <dir> [ids...]   -> <dir>/lines/<id>_<take>.wav"""
import sys, json, os, time, torch, soundfile as sf
from qwen_tts import Qwen3TTSModel

D = sys.argv[1]
only = set(sys.argv[2:])
TAKES = int(os.environ.get("TAKES", "2"))
BASE = model_path("TTS_BASE")
REF = os.environ.get("REF", "ref_0.wav")
REF_TEXT = os.environ.get("REF_TEXT", "手机拍的照片，套一层胶片滤镜，为什么总差一口气？因为滤镜只改了颜色，光，还是数码的光。")

lines = json.load(open(f"{D}/narration.json"))
os.makedirs(f"{D}/lines", exist_ok=True)
m = Qwen3TTSModel.from_pretrained(BASE, **model_options(torch))
prompt = m.create_voice_clone_prompt(ref_audio=f"{D}/{REF}", ref_text=REF_TEXT)
for ln in lines:
    if only and ln["id"] not in only:
        continue
    for k in range(TAKES):
        torch.manual_seed(1000 + k)
        t = time.time()
        wavs, sr = m.generate_voice_clone(text=ln["tts"], language="Chinese", voice_clone_prompt=prompt)
        sf.write(f"{D}/lines/{ln['id']}_{k}.wav", wavs[0], sr)
        print(ln["id"], k, "gen", round(time.time() - t, 1), "dur", round(len(wavs[0]) / sr, 2), flush=True)
