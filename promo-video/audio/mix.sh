#!/usr/bin/env bash
# voice + BGM (sidechain-ducked) + SFX -> build/silvercam-promo.mp4
set -euo pipefail
cd "$(dirname "$0")/.."
# Supply licensed local sound assets explicitly; no machine-specific skill cache.
: "${SFX:?Set SFX to the local directory containing the cue sound files}"
: "${BGM:?Set BGM to a local background-music file}"
[[ -d "$SFX" && -f "$BGM" ]] || { printf '%s\n' 'Missing SFX directory or BGM file' >&2; exit 1; }
DUR=$(python3 -c "import json;print(json.load(open('timeline.json'))['duration'])")

# 1. SFX bus
args=(); filt=""; mixl=""; i=0
while IFS=$'\t' read -r t f db; do
  [[ -z "$t" || "$t" == \#* ]] && continue
  args+=(-i "$SFX/$f"); ms=$(python3 -c "print(int(float('$t')*1000))")
  filt+="[$i:a]aresample=48000,aformat=channel_layouts=stereo,adelay=${ms}:all=1,volume=${db}dB[s$i];"; mixl+="[s$i]"; i=$((i+1))
done < audio/sfx-cues.tsv
ffmpeg -v error -y "${args[@]}" -filter_complex "${filt}${mixl}amix=inputs=$i:normalize=0,apad=whole_dur=${DUR}[o]" -map "[o]" -t "$DUR" -ar 48000 audio/sfx.wav

# 2. voice (light EQ + compression), BGM ducked by voice, master loudness
ffmpeg -v error -y -i audio/voiceover.wav -i "$BGM" -i audio/sfx.wav -filter_complex "\
[0:a]aresample=48000,highpass=f=70,equalizer=f=3200:t=q:w=1.2:g=2,acompressor=threshold=-20dB:ratio=3:attack=5:release=120:makeup=3dB,aformat=channel_layouts=stereo,asplit=2[v][vsc];\
[1:a]aresample=48000,atrim=0:${DUR},asetpts=PTS-STARTPTS,volume=0.32,afade=t=in:d=1.2,afade=t=out:st=$(python3 -c "print(${DUR}-3)"):d=3[b];\
[b][vsc]sidechaincompress=threshold=0.03:ratio=6:attack=40:release=450[bd];\
[v][bd][2:a]amix=inputs=3:normalize=0,loudnorm=I=-16:TP=-1.5:LRA=11[m]" \
  -map "[m]" -t "$DUR" -ar 48000 -ac 2 audio/master.wav

# 3. mux
ffmpeg -v error -y -i build/film-silent.mp4 -i audio/master.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest -movflags +faststart build/silvercam-promo.mp4
echo "✓ build/silvercam-promo.mp4"
