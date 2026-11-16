"""Synthesises the promo's soundtrack (music + sound effects) from scratch, so there is nothing to license.

    python3 audio/soundtrack.py out/cues.json out/soundtrack.wav

cues.json comes from `node render.mjs --cues`: the film's duration, the time of the "drop" (logo reveal)
and every effect cue the scenes registered. 90 BPM, so one beat is 2/3 s and the scenes cut on beats.
"""
import json, sys, wave
import numpy as np
from scipy import signal

SR = 48000
BPM = 90.0
BEAT = 60.0 / BPM
BAR = 4 * BEAT
rng = np.random.default_rng(1977)

cues = json.load(open(sys.argv[1]))
DUR = cues["duration"] + 1.5          # ring-out after the last frame is trimmed by ffmpeg -shortest
DROP = cues.get("drop", 42.667)       # beat section starts here
OUTRO = cues.get("outro", DUR - 12)   # beat section ends here
N = int(DUR * SR)
t_all = np.arange(N) / SR


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def butter(x, fc, kind="low", order=2):
    sos = signal.butter(order, np.asarray(fc) / (SR / 2), kind, output="sos")
    return signal.sosfilt(sos, x)


def env_adsr(n, a, d, s, r):
    a, d, r = int(a * SR), int(d * SR), int(r * SR)
    e = np.full(n + r, s)
    e[:a] = np.linspace(0, 1, a, endpoint=False)
    e[a:a + d] = np.linspace(1, s, d, endpoint=False)
    e[n:] = s * np.linspace(1, 0, r) ** 2
    return e


class Bus:
    def __init__(self):
        self.b = np.zeros((2, N + SR * 8))

    def add(self, at, x, gain=1.0, pan=0.0):
        i = int(at * SR)
        if i < 0 or i >= N:
            return
        x = np.atleast_2d(x)
        if x.shape[0] == 1:
            l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
            x = np.vstack([x[0] * l, x[0] * r]) * 1.414
        n = min(x.shape[1], self.b.shape[1] - i)
        self.b[:, i:i + n] += x[:, :n] * gain

    def out(self):
        return self.b[:, :N]


pad, bass, pluck, drums, fx, hiss = Bus(), Bus(), Bus(), Bus(), Bus(), Bus()

# ---------------------------------------------------------------- harmony
# D minor, two bars per chord: Dm9  Bbmaj7  Fmaj7  C6/9
CHORDS = [
    (38, [62, 65, 69, 72, 76]),
    (34, [58, 62, 65, 69, 72]),
    (41, [60, 64, 65, 69, 72]),
    (36, [60, 62, 64, 67, 69]),
]
n_bars = int(np.ceil(DUR / BAR))
drop_bar = int(round(DROP / BAR))
outro_bar = int(round(OUTRO / BAR))


def chord_at(bar):
    return CHORDS[(bar // 2) % 4]


def saw(f, n, detune=0.0, phase=0.0):
    ph = (f * (1 + detune) * np.arange(n) / SR + phase) % 1.0
    return 2 * ph - 1


# ---------------------------------------------------------------- pad
for bar in range(0, n_bars, 2):
    root, notes = chord_at(bar)
    at = bar * BAR
    n = int(2 * BAR * SR)
    e = env_adsr(n, 1.1, .6, .8, 2.2)
    in_beat = drop_bar <= bar < outro_bar
    cut = 700 if bar < 4 else (1100 if not in_beat else 2100)
    lvl = .5 if bar < 2 else 1.0
    for ch in (0, 1):
        x = np.zeros(len(e))
        for k, m in enumerate(notes):
            for d in (-.004, .0, .0035):
                x += saw(hz(m), len(e), d + (.0011 if ch else -.0011) * (k + 1), rng.random())
        x = butter(x, cut, order=2) * e
        x = x * (1 + .12 * np.sin(2 * np.pi * .23 * np.arange(len(e)) / SR + ch * 1.7))
        st = np.zeros((2, len(e)))
        st[ch] = x
        pad.add(at, st, .034 * lvl)

# opening drone: low D with a slow fifth above it
n = int(min(DROP, DUR) * SR)
tt = np.arange(n) / SR
dr = np.sin(2 * np.pi * hz(26) * tt) * .5 + np.sin(2 * np.pi * hz(38) * tt + .4) * .3 + butter(saw(hz(38), n, .003), 240) * .25
dr *= np.minimum(tt / 2.5, 1) * np.minimum((tt[-1] - tt) / 3.0, 1)
bass.add(0, dr, .09)

# ---------------------------------------------------------------- bass
for bar in range(4, n_bars):
    root, _ = chord_at(bar)
    at = bar * BAR
    if drop_bar <= bar < outro_bar:
        # syncopated eighths, ducked by the kick
        for step, (on, octv) in enumerate([(1, 0), (0, 0), (0, 0), (1, 0), (0, 0), (1, 12), (1, 0), (0, 0)]):
            if not on:
                continue
            n = int(BEAT * .5 * 1.6 * SR)
            tt = np.arange(n) / SR
            f = hz(root + octv)
            x = np.tanh(2.2 * (np.sin(2 * np.pi * f * tt) + .35 * np.sin(4 * np.pi * f * tt))) * np.exp(-tt * 4.5)
            x[:96] *= np.linspace(0, 1, 96)
            bass.add(at + step * BEAT / 2, x, .22)
    elif bar >= outro_bar or bar >= 5:
        n = int(BAR * SR)
        tt = np.arange(n) / SR
        x = np.sin(2 * np.pi * hz(root) * tt) * np.minimum(tt / .08, 1) * np.exp(-tt * .7)
        bass.add(at, x, .17)

# ---------------------------------------------------------------- plucked arpeggio
ARP = [0, 2, 4, 1, 3, 4, 2, 3]


def bell(m, dur=1.4, bright=1.0):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    f = hz(m)
    mod = np.sin(2 * np.pi * f * 3.01 * tt) * np.exp(-tt * 9) * 1.6 * bright
    x = np.sin(2 * np.pi * f * tt + mod) * np.exp(-tt * 3.6) + .3 * np.sin(2 * np.pi * 2 * f * tt) * np.exp(-tt * 7)
    x[:48] *= np.linspace(0, 1, 48)
    return x


for bar in range(1, n_bars):
    _, notes = chord_at(bar)
    at = bar * BAR
    in_beat = drop_bar <= bar < outro_bar
    if bar < 5:  # sparse: two notes a bar
        for step, idx in ((0, 4), (5, 2)):
            pluck.add(at + step * BEAT / 2, bell(notes[idx] + 12, 2.2, .6), .10, pan=-.4 + .8 * (step > 0))
        continue
    if bar in (drop_bar - 1,):  # breath before the drop
        continue
    for step in range(8):
        if not in_beat and step in (1, 6):
            continue
        idx = ARP[(step + 3 * (bar % 2)) % 8]
        swing = .035 if step % 2 else 0
        vel = (.16 if in_beat else .12) * (1.0 if step % 4 == 0 else .72)
        pluck.add(at + step * BEAT / 2 + swing, bell(notes[idx] + 12, 1.2, 1.25 if in_beat else .8), vel, pan=np.sin(step * 1.9 + bar) * .55)

# ping-pong dotted-eighth delay on the plucks
pb = pluck.b.copy()
d = int(BEAT * .75 * SR)
for k in range(1, 5):
    g = .42 ** k
    src = pb[::-1] if k % 2 else pb
    pluck.b[:, d * k:] += butter(src[:, :-d * k], 2600) * g

# ---------------------------------------------------------------- drums
def kick():
    n = int(.36 * SR)
    tt = np.arange(n) / SR
    f = 46 + 95 * np.exp(-tt * 34)
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 9.5)
    x += butter(rng.standard_normal(n), 3000, "high") * np.exp(-tt * 380) * .25
    return np.tanh(x * 1.8)


def rim():
    n = int(.16 * SR)
    tt = np.arange(n) / SR
    x = butter(rng.standard_normal(n), [1400, 4200], "band") * np.exp(-tt * 46)
    x += np.sin(2 * np.pi * 1780 * tt) * np.exp(-tt * 90) * .5
    return x


def hat(dur=.045):
    n = int(dur * 4 * SR)
    tt = np.arange(n) / SR
    return butter(rng.standard_normal(n), 7500, "high") * np.exp(-tt / dur)


duck = np.ones(N + SR * 8)
for bar in range(drop_bar, outro_bar):
    at = bar * BAR
    last = bar == outro_bar - 1
    for b in ((0, 2.5) if not last else (0,)):
        drums.add(at + b * BEAT, kick(), .50)
        i = int((at + b * BEAT) * SR)
        m = int(.26 * SR)
        duck[i:i + m] = np.minimum(duck[i:i + m], .35 + .65 * np.linspace(0, 1, m) ** 1.5)
    for b in (1, 3):
        drums.add(at + b * BEAT, rim(), .16, pan=.15)
    for step in range(8):
        swing = .035 if step % 2 else 0
        open_ = (step == 7 and bar % 4 == 3)
        drums.add(at + step * BEAT / 2 + swing, hat(.11 if open_ else .03), (.075 if step % 2 == 0 else .05) * (1.4 if open_ else 1), pan=-.25)

# ---------------------------------------------------------------- effects
def fx_whoosh(dur=.7, up=True):
    """Noise swept through a stack of bands: each band peaks a little later than the one below."""
    n = int(dur * SR)
    tt = np.arange(n) / SR
    x = rng.standard_normal(n)
    out = np.zeros(n)
    bands = 300 * 14 ** np.linspace(0, 1, 9)
    for k, fc in enumerate(bands):
        c = (k + .5) / len(bands)
        c = c if up else 1 - c
        e = np.exp(-((tt / dur - c) / .16) ** 2)
        out += butter(x, [fc * .75, min(fc * 1.4, SR / 2 - 100)], "band") * e
    return out * np.sin(np.pi * tt / dur) ** 1.5


def fx_shutter():
    n = int(.3 * SR)
    x = np.zeros(n)
    for at, g in ((0, 1.0), (.058, .8)):
        m = int(.03 * SR)
        tt = np.arange(m) / SR
        c = butter(rng.standard_normal(m), [1800, 6500], "band") * np.exp(-tt * 190) * g
        i = int(at * SR)
        x[i:i + m] += c
    tt = np.arange(n) / SR
    x += np.sin(2 * np.pi * 150 * tt) * np.exp(-tt * 46) * .5
    return x


def fx_tick(f=2400):
    n = int(.03 * SR)
    tt = np.arange(n) / SR
    return (np.sin(2 * np.pi * f * tt) + butter(rng.standard_normal(n), 5000, "high") * .5) * np.exp(-tt * 260)


def fx_advance():
    n = int(.8 * SR)
    x = np.zeros(n)
    at = 0.0
    for k in range(9):
        c = fx_tick(1700 + k * 60) * (.5 + k * .05)
        i = int(at * SR)
        x[i:i + len(c)] += c
        at += .075 - k * .004
    m = int(.12 * SR)
    tt = np.arange(m) / SR
    i = int(at * SR)
    x[i:i + m] += np.sin(2 * np.pi * 110 * tt) * np.exp(-tt * 40) * 1.2
    return x


def fx_flicker():
    n = int(.6 * SR)
    tt = np.arange(n) / SR
    x = np.zeros(n)
    for a, b in ((0, .06), (.14, .2), (.29, .6)):
        g = (tt >= a) & (tt < b)
        x[g] += (np.sign(np.sin(2 * np.pi * 100 * tt[g])) * .25 + np.sin(2 * np.pi * 3520 * tt[g]) * np.exp(-(tt[g] - a) * 30) * .5)
    x = butter(x, [90, 6000], "band")
    x[int(.29 * SR):] *= np.exp(-(tt[int(.29 * SR):] - .29) * 9)
    return x


def fx_rise(dur=2.6):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    p = tt / dur
    f = 110 * 2 ** (p * 2.0)
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * .35 + fx_whoosh(dur, True) * 1.2
    return x * p ** 2


def fx_boom():
    n = int(2.2 * SR)
    tt = np.arange(n) / SR
    f = 36 + 70 * np.exp(-tt * 7)
    return np.tanh(np.sin(2 * np.pi * np.cumsum(f) / SR) * 2.4) * np.exp(-tt * 2.1)


def fx_pop(m=81):
    n = int(.12 * SR)
    tt = np.arange(n) / SR
    f = hz(m) * (1 + .5 * np.exp(-tt * 60))
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 34)


def fx_draw(dur=.45):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    x = butter(rng.standard_normal(n), [1500, 5200], "band")
    return x * np.sin(np.pi * tt / dur) * (.6 + .4 * np.sin(2 * np.pi * 31 * tt))


FX = {
    "whoosh": (lambda: fx_whoosh(.7, True), .16), "whoosh_down": (lambda: fx_whoosh(.6, False), .14),
    "shutter": (fx_shutter, .5), "tick": (fx_tick, .14), "advance": (fx_advance, .28), "flicker": (fx_flicker, .2),
    "rise": (fx_rise, .22), "boom": (fx_boom, .38), "pop": (fx_pop, .12), "draw": (fx_draw, .09),
}
for c in cues.get("cues", []):
    if c["type"] not in FX:
        continue
    make, g = FX[c["type"]]
    x = make()
    at = c["t"] - (2.6 if c["type"] == "rise" else 0)
    fx.add(at, x, g * c.get("gain", 1), pan=((hash(c["type"]) % 7) - 3) / 8)

# tape hiss with a little crackle
h = butter(rng.standard_normal((2, N)), [400, 9000], "band") * .006
cr = (rng.random(N) > .99985) * rng.standard_normal(N) * .05
h += butter(cr, 2500, "high")
hiss.add(0, h)

# ---------------------------------------------------------------- mix
dk = duck[:N]
wet_src = pad.out() * .7 + pluck.out() * .9 + fx.out() * .5 + drums.out() * .12
ir_n = int(2.6 * SR)
tt = np.arange(ir_n) / SR
ir = butter(rng.standard_normal((2, ir_n)), 4200) * np.exp(-tt * 2.3)
ir[:, :int(.012 * SR)] = 0
wet = np.vstack([signal.fftconvolve(wet_src[c], ir[c])[:N] for c in (0, 1)]) * .018
mix = pad.out() * dk + bass.out() * dk + pluck.out() + drums.out() + fx.out() + hiss.out() + wet
mix = butter(mix, 28, "high")
fade = np.minimum(t_all / .4, 1) * np.clip((DUR - t_all) / 3.2, 0, 1) ** 1.5
mix *= fade
peak = np.max(np.abs(mix))
mix = np.tanh(mix / peak * 1.7) / np.tanh(1.7) * 10 ** (-1.0 / 20)
rms = np.sqrt(np.mean(mix[:, int(DROP * SR):int(OUTRO * SR)] ** 2))
print(f"duration {DUR:.2f}s  drop {DROP:.2f}s  outro {OUTRO:.2f}s  beat-section RMS {20 * np.log10(rms):.1f} dBFS")
pcm = (mix.T * 32767).astype("<i2")
with wave.open(sys.argv[2], "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
