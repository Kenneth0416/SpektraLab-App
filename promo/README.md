# 宣传片与下载页

```
promo/
  video/   宣传片：HTML + GSAP 的分镜，逐帧用无头 Chromium 渲染，ffmpeg 合成
  site/    下载页（纯静态，直接部署这个目录）
  CREDITS.md  素材来源与许可
```

## 宣传片

1920×1080、30 fps、约 2 分 16 秒，简体中文，普通话男声旁白。成片：`site/video/silvercam-promo.mp4`。

```bash
cd promo/video
npm install
node tools/make-qr.mjs https://silvercam.convert-md.com   # 片尾与下载页的二维码
node tools/shoot-site.mjs                                  # 下载页截图 -> 片尾
MINIMAX_API_KEY=... python3 audio/voiceover.py             # 旁白（成品已在 audio/vo/，改了稿子才需要重跑）
node render.mjs --cues                                     # 导出分镜与音效时间点
python3 audio/soundtrack.py out/cues.json out/soundtrack.wav   # 需要 numpy、scipy
node render.mjs                                            # -> out/silvercam-promo.mp4
python3 tools/make-srt.py out/cues.json ../site/video/silvercam-promo.zh-CN.srt   # 字幕
```

- 分镜在 `scenes/`，一场一个文件；时长都是整拍（90 BPM），画面切换落在配乐的拍子上。
- 所有动画挂在一条暂停的 GSAP 时间线上，任何一帧都能单独渲染：`node render.mjs --stills 12.5,48`。
- 应用名、网址改 `scenes/s07_logo.js`、`scenes/s13_cta.js` 里的文字即可重新渲染。

### 应用画面是怎么来的

构建环境没有模拟器，画面分两步生成，都用应用自己的代码：

```bash
# 0. <work>/src 放原图（2400×1600 的 JPEG，名称见 tools/promo/jobs.js），生成任务表
node tools/promo/jobs.js --tsv > <work>/jobs.tsv
# 1. 胶片样片：把应用的着色器和查找表导出，在 WebGL2 里按 FilmRenderer 的顺序跑
./gradlew :engine:test --tests '*PromoDumpTest' -Dspektra.promodump=<work>/dump
node tools/promo/render.js <work>/dump <work>/src <work>/png
# 2. 界面：Robolectric 渲染真实的 Compose 界面，预览区域填入第 1 步的画面
./gradlew :app:testDebugUnitTest --tests '*PromoScreenshotTest' -Dpromo.dir=<work>
# 3. 拷进宣传片和下载页
python3 promo/video/tools/collect-assets.py <work>
```

## 下载页

`site/index.html` 顶部的 `window.SILVERCAM` 是唯一要改的地方：`apk` 指向安装包（默认 `download/SilverCam-0.1.0.apk`），
`source` 指向源码仓库。页面不依赖任何外部资源。
