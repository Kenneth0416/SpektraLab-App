"""Narration for the promo, synthesised with MiniMax T2A (Mandarin, male).

    MINIMAX_API_KEY=... python3 audio/voiceover.py            # all lines that are missing
    MINIMAX_API_KEY=... python3 audio/voiceover.py s03 s07    # redo these

Writes audio/vo/<scene>.mp3 (kept in the repo, so rendering needs no key) and audio/vo/vo.json with each
line's text and length. `say` is what is sent to the model: the same sentence as `text`, with pause marks
(<#seconds#>) and spellings that make it read names and the address the way a person would.
"""
import json, os, subprocess, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'vo')
HOST = os.environ.get('MINIMAX_HOST', 'https://api.minimaxi.com')
MODEL = os.environ.get('MINIMAX_MODEL', 'speech-2.8-hd')
VOICE = os.environ.get('MINIMAX_VOICE', 'Chinese (Mandarin)_Gentleman')

# scene id -> (subtitle text, text sent to the model)
LINES = {
    's01': ('你有没有想过，胶片的颜色，其实不是调出来的，是算出来的。',
            '你有没有想过，<#0.35#>胶片的颜色，其实不是调出来的。<#0.55#>是算出来的。'),
    's02': ('这件事，得从一个开源项目说起。它叫 spektrafilm，作者是 Andrea Volpato。',
            '这件事，得从一个开源项目说起。<#0.3#>它叫 spektra film，<#0.2#>作者是 Andrea Volpato。'),
    's03': ('它做的事情很硬核：从相机的 RAW 出发，把负片曝光、显影、放大机印相、相纸、扫描，一步一步，全按物理算一遍。就连颗粒，都是一颗一颗生成的。',
            '它做的事情很硬核。<#0.25#>从相机的 RAW 出发，把负片曝光、显影、放大机印相、相纸、扫描，<#0.2#>一步一步，全按物理算一遍。<#0.5#>就连颗粒，都是一颗一颗生成的。'),
    's04': ('颜色的依据，是厂商公开的胶片数据表：每层乳剂对什么光敏感，曝光多少、出多少密度，染料又会吸收哪些光。',
            '颜色的依据呢，是厂商公开的胶片数据表。<#0.35#>每层乳剂对什么光敏感，<#0.3#>曝光多少、出多少密度，<#0.3#>染料又会吸收哪些光。'),
    's05': ('所以一张照片，要先变成一张带着橙色色罩的负片，再过放大机，印到相纸上，最后才是你看到的成片。',
            '所以一张照片，要先变成一张带着橙色色罩的负片，<#0.3#>再过放大机，印到相纸上，<#0.3#>最后，才是你看到的成片。'),
    's06': ('不过，它一直住在电脑的 Python 里，出一张全尺寸的图，要等上好一阵。于是，我们把它搬进了手机。',
            '不过，它一直住在电脑的 Python 里，出一张全尺寸的图，要等上好一阵。<#0.6#>于是，我们把它，搬进了手机。'),
    's07': ('这就是 SilverCam。基于 spektrafilm 开发，把一整间暗房，装进你的手机。',
            '这就是 Silver Cam。<#0.45#>基于 spektra film 开发，<#0.2#>把一整间暗房，装进你的手机。'),
    's08': ('打开它，先看到的是灯箱。照片、RAW、视频，都摊在底片条上。拿红蜡笔一圈，就能批量冲印。',
            '打开它，先看到的是灯箱。<#0.3#>照片、RAW、视频，都摊在底片条上。<#0.35#>拿红蜡笔一圈，就能批量冲印。'),
    's09': ('进了暗房，十八款胶片、八款相纸，随你搭配。同一张照片，换一卷胶片，就是另一种脾气。',
            '进了暗房，<#0.2#>十八款胶片、八款相纸，随你搭配。<#0.45#>同一张照片，换一卷胶片，就是另一种脾气。'),
    's10': ('光晕、颗粒、片边，这些细节也不是贴上去的特效，而是按胶片的物理结构，算出来的。',
            '光晕、颗粒、片边，<#0.25#>这些细节，也不是贴上去的特效，<#0.25#>而是按胶片的物理结构，算出来的。'),
    's11': ('它还是一台相机。取景看到的，就是胶片的颜色；原片存 RAW，暗的地方多帧合成。拍完一张，还得自己扳一下过片扳手。',
            '它还是一台相机。<#0.35#>取景看到的，就是胶片的颜色。<#0.25#>原片存 RAW，暗的地方多帧合成。<#0.45#>拍完一张，还得自己扳一下过片扳手。'),
    's12': ('准不准？我们和原版逐级对照过，平均误差，只有万分之四。',
            '准不准？<#0.4#>我们和原版逐级对照过，平均误差，<#0.2#>只有万分之四。'),
    's13': ('现在，谁都可以来试试。打开 silvercam.convert-md.com，去冲一卷吧。',
            '现在，谁都可以来试试。<#0.4#>打开 silver cam 点 convert 横杠 M D 点 com，<#0.45#>去冲一卷吧。'),
}


def synth(say):
    body = {'model': MODEL, 'text': say, 'stream': False, 'language_boost': 'Chinese',
            'voice_setting': {'voice_id': VOICE, 'speed': 1.0, 'vol': 1.0, 'pitch': 0},
            'audio_setting': {'sample_rate': 32000, 'bitrate': 128000, 'format': 'mp3', 'channel': 1}}
    req = urllib.request.Request(HOST + '/v1/t2a_v2', json.dumps(body).encode(),
                                 {'Authorization': 'Bearer ' + os.environ['MINIMAX_API_KEY'].strip(), 'Content-Type': 'application/json'})
    d = json.load(urllib.request.urlopen(req, timeout=120))
    if d.get('base_resp', {}).get('status_code') != 0:
        raise SystemExit(f"MiniMax: {d.get('base_resp')}")
    return bytes.fromhex(d['data']['audio'])


def length(path):
    return float(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path],
                                capture_output=True, text=True).stdout)


os.makedirs(OUT, exist_ok=True)
redo = set(sys.argv[1:])
meta = {}
for sid, (text, say) in LINES.items():
    path = os.path.join(OUT, sid + '.mp3')
    if sid in redo or not os.path.exists(path):
        open(path, 'wb').write(synth(say))
        print('synthesised', sid)
    meta[sid] = {'text': text, 'dur': round(length(path), 3)}
json.dump({'model': MODEL, 'voice': VOICE, 'lines': meta}, open(os.path.join(OUT, 'vo.json'), 'w'), ensure_ascii=False, indent=1)
for k, v in meta.items():
    print(k, v['dur'])
