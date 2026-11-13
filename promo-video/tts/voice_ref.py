from model_config import model_path, model_options
import sys, time, torch, soundfile as sf
from qwen_tts import Qwen3TTSModel

from pathlib import Path
OUT = sys.argv[1]
Path(OUT).mkdir(parents=True, exist_ok=True)
INSTRUCT = ("一位四十岁左右的中国男性纪录片旁白，标准普通话，嗓音低沉温暖、略带磁性，"
            "语速中等偏慢，吐字清晰，语气平静克制、娓娓道来，像在暗房里给朋友讲胶片。")
TEXT = "手机拍的照片，套一层胶片滤镜，为什么总差一口气？因为滤镜只改了颜色，光，还是数码的光。"

t = time.time()
m = Qwen3TTSModel.from_pretrained(model_path("VOICE_DESIGN"), **model_options(torch))
print("load", time.time() - t, flush=True)
for i, seed in enumerate([7, 21, 42]):
    torch.manual_seed(seed)
    t = time.time()
    wavs, sr = m.generate_voice_design(text=TEXT, instruct=INSTRUCT, language="Chinese")
    sf.write(f"{OUT}/ref_{i}.wav", wavs[0], sr)
    print(i, seed, "gen", round(time.time() - t, 1), "dur", round(len(wavs[0]) / sr, 2), flush=True)
