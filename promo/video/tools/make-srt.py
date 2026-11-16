"""Subtitles for the narration: python3 tools/make-srt.py out/cues.json ../site/video/silvercam-promo.zh-CN.srt

Each narration line is cut at its punctuation; a piece's share of the line's time follows its length.
"""
import json, os, re, sys

here = os.path.dirname(os.path.abspath(__file__))
cues = json.load(open(sys.argv[1]))
vo = json.load(open(os.path.join(here, '..', 'audio', 'vo', 'vo.json')))['lines']
LEAD = 0.45


def stamp(t):
    ms = round(t * 1000)
    return f'{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}'


rows = []
for m in cues['marks']:
    line = vo.get(m['id'])
    if not line:
        continue
    parts = [p for p in re.split(r'(?<=[，。？：；])', line['text']) if p.strip()]
    merged = []
    for p in parts:   # keep pieces readable: join very short ones to the previous piece
        if merged and len(merged[-1]) + len(p) <= 16:
            merged[-1] += p
        else:
            merged.append(p)
    total = sum(len(p) for p in merged)
    t = m['t'] + LEAD
    for p in merged:
        d = line['dur'] * len(p) / total
        rows.append((t, t + d - 0.04, p.strip().rstrip('，。：；')))
        t += d
with open(sys.argv[2], 'w', encoding='utf-8') as f:
    for i, (a, b, text) in enumerate(rows, 1):
        f.write(f'{i}\n{stamp(a)} --> {stamp(b)}\n{text}\n\n')
print(len(rows), 'subtitles')
