"""Place narration clips on the master timeline -> ../timeline.json + ../audio/voiceover.wav"""
import json, subprocess, os, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'film'))
from build import sync_timeline

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(D)
START = {  # absolute start of each line (s)
    "s1": 1.2, "s2": 12.4, "s3": 26.4, "s4": 38.4, "s5": 48.2, "s6": 60.6,
    "s7": 77.0, "s8": 87.2, "s9": 96.8, "s10": 107.4,
}
TOTAL = 119.5
lines = json.load(open(f"{D}/narration.json"))
al = json.load(open(f"{D}/align.json"))

def dur(p):
    return float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p]).decode())

tl = {"duration": TOTAL, "lines": [], "subs": []}
prev_end = 0
for ln in lines:
    i = ln["id"]; s = START[i]; d = dur(f"{D}/sel/{i}.wav")
    assert s >= prev_end + 0.3, (i, s, prev_end)
    prev_end = s + d
    tl["lines"].append({"id": i, "start": round(s, 3), "end": round(s + d, 3), "tokens": [[t, round(s + a, 3), round(s + b, 3)] for t, a, b in al[i]["tokens"]]})
    for seg in al[i]["segments"]:
        tl["subs"].append({"text": seg["text"], "start": round(s + seg["start"], 3), "end": round(s + seg["end"] + 0.25, 3)})
# clip overlapping subtitle ends
for a,b in zip(tl["subs"],tl["subs"][1:]):
    a["end"]=min(a["end"],round(b["start"]-0.05,3))
assert prev_end < TOTAL - 2.5, prev_end

os.makedirs(f"{ROOT}/audio", exist_ok=True)
inputs, filt = [], []
for k, ln in enumerate(lines):
    inputs += ["-i", f"{D}/sel/{ln['id']}.wav"]
    ms = int(START[ln["id"]] * 1000)
    filt.append(f"[{k}]adelay={ms}|{ms}[d{k}]")
mix = "".join(f"[d{k}]" for k in range(len(lines))) + f"amix=inputs={len(lines)}:normalize=0,apad=whole_dur={TOTAL}[v]"
subprocess.check_call(["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", ";".join(filt) + ";" + mix, "-map", "[v]", "-ar", "48000", "-ac", "1", "-t", str(TOTAL), f"{ROOT}/audio/voiceover.wav"])
# Publish metadata only after audio assembly has succeeded.
Path(ROOT, 'timeline.json').write_text(json.dumps(tl, ensure_ascii=False, indent=1), encoding='utf-8')
sync_timeline(ROOT)
for l in tl["lines"]:
    print(l["id"], l["start"], l["end"])
print("voice ends", prev_end, "total", TOTAL)
