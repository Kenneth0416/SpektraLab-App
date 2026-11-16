# 宣传片与下载页的素材来源

## spektrafilm

`video/assets/spektrafilm/` 与 `site/img/spektrafilm-*` 下的图片来自
[spektrafilm](https://github.com/andreavolpato/spektrafilm)（Andrea Volpato，GPLv3）仓库的 README：
项目横幅、桌面 GUI 截图（含 12 倍放大的颗粒）、Kodak Portra 400 / Portra Endura 的数据图、光晕测试图。
README 中的流程示意图改编自教科书，这里没有使用，片中的流程图是重新绘制的。

> Film modeling powered by spektrafilm by Andrea Volpato.

## 样片原图

样片都是真实照片，经本应用的胶片着色器渲染（`tools/promo/render.js`）。原图取自三个开源壁纸集：

| 片中名称 | 作者 | 来源 | 许可 |
|---|---|---|---|
| scooter（黄色踏板车）、bridge（粉色拱桥夜景） | Aabhas Lall | ubuntubudgie/budgie-wallpapers | CC BY-SA 4.0 |
| bulbs（灯泡） | Kari Shea | budgie-wallpapers（StockSnap） | CC0 |
| cafe（咖啡） | Matt Hoffman | budgie-wallpapers（StockSnap） | CC0 |
| beach、hikers | Brodie Vissers | budgie-wallpapers（Magdeleine） | CC0 |
| waterfall | Daria Lisovtsova | budgie-wallpapers（Magdeleine） | CC0 |
| mandarins | Erwan Hesry | budgie-wallpapers（Unsplash） | Unsplash |
| sign（Colorado 路牌） | Kait Herzog | pop-os/wallpapers（Unsplash） | Unsplash |
| canyon（羚羊峡谷） | Ashim D’Silva | elementary/wallpapers | 见该仓库 LICENSE.md |
| valley、pier、lantern | 见 elementary/wallpapers 的署名 | elementary/wallpapers | 见该仓库 LICENSE.md |
| portrait（叶间人像） | spektrafilm 的测试图 `img/test/portrait_leaves_32bit_linear_prophoto_rgb.tif` | spektrafilm | 随 spektrafilm 仓库 |

Aabhas Lall 的两张照片按 CC BY-SA 4.0 使用：由它们渲染出的样片同样以 CC BY-SA 4.0 提供，发布视频时请在简介里保留署名。

## 界面截图

`shot_*` 是应用自己的 Compose 界面代码在 Robolectric 下离屏渲染的（`app/src/test/.../PromoScreenshotTest.kt`），不是真机截屏：
构建环境没有模拟器。GL 预览和相机取景在这种环境下不出画面，所以这两块区域填入的是同一设置下由应用着色器渲染出的画面；
相机取景用的是照片，不是实时相机信号。图库缩略图走的是应用自己的缩略图缓存。

## 字体与音乐

- 思源宋体 / 思源黑体（Noto Serif SC、Noto Sans SC）、Martian Mono、Long Cang：SIL OFL。
- 旁白由 MiniMax 语音合成（speech-2.8-hd，音色「温润男声」）生成，脚本与成品在 `video/audio/voiceover.py`、`video/audio/vo/`。
- 配乐与音效由 `video/audio/soundtrack.py` 从零合成，没有使用任何现成音乐或采样。
