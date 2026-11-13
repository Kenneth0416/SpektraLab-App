from model_config import model_path, model_options
"""Force-align each selected line; emit per-subtitle-chunk start/end (seconds, clip-relative).
usage: align.py <dir>  (qwen3-asr venv) -> <dir>/align.json"""
import sys, json, glob, re, torch
from qwen_asr import Qwen3ForcedAligner

D = sys.argv[1]
path = model_path("ALIGNER")
al = Qwen3ForcedAligner.from_pretrained(path, **model_options(torch))
lines = json.load(open(f"{D}/narration.json"))

def norm(s):
    # what the aligner keeps: CJK chars + latin/digit words
    return re.findall(r"[\u4e00-\u9fff]|[A-Za-z0-9]+", s)

out = {}
for ln in lines:
    tts = ln["tts"]
    res = al.align(audio=f"{D}/sel/{ln['id']}.wav", text=tts, language="Chinese")[0]
    items = [(it.text, it.start_time, it.end_time) for it in res]
    chunks = ln["sub"].split("|")
    tchunks = ln["tts_chunks"].split("|")
    counts = [len(al.aligner_processor.encode_timestamp(c, "Chinese")[0]) for c in tchunks]
    assert sum(counts) == len(items), (ln["id"], counts, len(items))
    bounds, acc = [], 0
    for c in counts:
        bounds.append((acc, acc + c - 1)); acc += c
    segs = [{"text": chunks[i], "start": items[a][1], "end": items[b][2]} for i, (a, b) in enumerate(bounds)]
    out[ln["id"]] = {"segments": segs, "tokens": items}
    print(ln["id"], [(s["text"][:8], s["start"], s["end"]) for s in segs], flush=True)
json.dump(out, open(f"{D}/align.json", "w"), ensure_ascii=False, indent=1)
