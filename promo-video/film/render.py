"""Deterministic Ego seek render -> fresh PNG frames -> silent MP4."""
import argparse
import json
import math
from pathlib import Path
import subprocess
from driver import ROOT, browser_options, browser_kwargs, fresh_run, run_browser, verify_frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fps', type=float, default=60)
    parser.add_argument('--start', type=float, default=0)
    parser.add_argument('--duration', type=float, help='Bound the render for a smoke test')
    parser.add_argument('--max-frames', type=int)
    parser.add_argument('--frames-only', action='store_true')
    parser.add_argument('--output', type=Path, help='Video destination; bounded runs default to their run directory')
    browser_options(parser)
    args = parser.parse_args()
    total = json.loads((ROOT / 'timeline.json').read_text())['duration']
    duration = args.duration if args.duration is not None else total - args.start
    if not all(math.isfinite(x) for x in (args.fps, args.start, duration)) or args.fps <= 0 or duration <= 0 or not 0 <= args.start < total:
        parser.error('fps and duration must be positive; start must be inside the timeline')
    if args.max_frames is not None and args.max_frames <= 0:
        parser.error('max-frames must be positive')
    count = math.ceil(min(duration, total - args.start) * args.fps)
    if args.max_frames is not None:
        count = min(count, args.max_frames)
    out = fresh_run(args.output_dir)
    (out / 'manifest.json').write_text(json.dumps(dict(fps=args.fps, start=args.start, count=count, film=str(args.film.resolve())), indent=2))
    run_browser([args.start + i / args.fps for i in range(count)], out, **browser_kwargs(args))
    verify_frames(out, count)
    print(f'Captured {count} frames in {out}')
    if not args.frames_only:
        bounded = args.start != 0 or args.duration is not None or args.max_frames is not None
        target = args.output or (out / 'film-silent.mp4' if bounded else ROOT / 'build/film-silent.mp4')
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(args.fps),
                        '-i', str(out / '%06d.png'), '-frames:v', str(count), '-c:v', 'libx264',
                        '-preset', 'slow', '-crf', '17', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                        str(target)], check=True)
        print(target)


if __name__ == '__main__':
    main()
