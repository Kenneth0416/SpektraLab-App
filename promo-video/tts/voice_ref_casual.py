from model_config import model_path, model_options
import sys, time, torch, soundfile as sf
from qwen_tts import Qwen3TTSModel
from pathlib import Path
OUT = sys.argv[1]
Path(OUT).mkdir(parents=True, exist_ok=True)
INSTRUCT = ("一位三十多岁的中国男性摄影师，标准普通话，嗓音偏低、温和。"
            "像在工作室里跟朋友聊天一样自然随意，语速中等，语气放松，带一点笑意，有自然的停顿和轻重，不念稿、不播音腔。")
TEXT = "手机照片套个胶片滤镜，颜色是挺像的，可看着总觉得哪儿不对。我琢磨了很久，问题其实出在光上。"
m = Qwen3TTSModel.from_pretrained(model_path("VOICE_DESIGN"), **model_options(torch))
for i, seed in enumerate([7, 21, 42, 99]):
    torch.manual_seed(seed)
    wavs, sr = m.generate_voice_design(text=TEXT, instruct=INSTRUCT, language="Chinese")
    sf.write(f"{OUT}/cref_{i}.wav", wavs[0], sr)
    print(i, seed, "dur", round(len(wavs[0]) / sr, 2), flush=True)
