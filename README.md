# 銀鹽工坊 / Silvercam — 手机胶片暗房

> **名称声明**：**Silvercam**（繁体中文：**銀鹽工坊**；简体中文：**银盐工坊**）为本项目为区分上游而采用的展示品牌名，尚未进行商标检索与注册（not trademark-cleared），本项目不主张且不声明任何商标权利。源码仓库保留原路径：[Kenneth0416/SpektraLab-App](https://github.com/Kenneth0416/SpektraLab-App)。

手机端胶片模拟工具的交互原型与展示网站：照片、视频、相机取景共用一套「胶片 × 相纸」参数。

画面与质感以 [spektrafilm](https://github.com/andreavolpato/spektrafilm)（Andrea Volpato）和 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab)（James Qiu）为参考与移植来源。本项目为**独立、非官方的下游开源项目**，未获得上游项目作者或项目的赞助、认可或背书。仓库包含完整展示与下载网站、Android 互动原型、Kotlin/GLSL 引擎与工具链。

## 展示与下载网站

网站基于 React、TypeScript 与 Vite，包含响应式 landing page、可交互的胶片预览和独立下载页。全站支持繁体中文、简体中文与英文，可通过页头语言选择器或 `?lang=zh-Hant`、`?lang=zh-Hans`、`?lang=en` 切换；选择会保存在 localStorage，并同步更新页面 metadata。

```sh
npm install
npm run dev
```

打开终端输出的本地地址（默认 `http://localhost:5173`）。生产构建：

```sh
npm run build
npm run preview
```

`npm run dev` 和 `npm run build` 会自动运行 `scripts/package-downloads.mjs`，在 `public/downloads/` 生成真实 ZIP 包（`Silvercam-App-source.zip` 完整源码包与 `Silvercam-interaction-prototype.zip` 纯交互原型包，均内嵌 LICENSE 与 NOTICE 说明）；若 APK 已构建，也会同步 `Silvercam-Android.apk` 的大小与 SHA-256 元数据。下载页直接提供这些静态文件，不依赖第三方网盘。

## Android APK

`android/` 是独立的 Android 互动原型，使用本地 WebView 加载内置暗房界面，支持三语、六款胶片、四个场景、曝光 / 颗粒 / 光晕、按住对比及配方。全部素材离线内置，不声明网络权限，也不访问真实相机或系统相册。

构建需要 JDK 17、Android SDK，以及 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`（macOS 默认会尝试 `~/Library/Android/sdk`）：

```sh
npm run android:build
```

命令通过 Gradle wrapper 构建 debug-signed APK，并输出至 `public/downloads/Silvercam-Android.apk`（应用内部 ID 为 `app.spektralab.prototype`，版本 `0.1.0`，最低 Android 8.0 / API 26）。这是用于安装与体验的原型，不是 Play Store 正式发行版。

部署时需支持 SPA 回退到 `index.html`；`public/_redirects` 已兼容使用这一规则的静态托管服务。

## 当前状态

- 这是交互原型；仓库提供可安装的 Android APK，但还不是正式发行应用。
- 预览里的光晕、颗粒、柔焦是用 CSS 近似的占位效果。
- 取景画面和素材是四张插画，没有接真实摄像头和系统相册。
- 导入候选、导出进度、移除都是模拟的。

## 流程

图库 → 编辑 → 导出，相机是图库的一个入口。

- **图库**：网格浏览，按照片 / 视频筛选；「选择」后可批量粘贴设置、套用配方、导出、移除。
- **导入**：相册（照片和视频，可多选）或文件（RAW）；只选一项时直接进入编辑。
- **编辑**：按住画面看原图，撤销 / 重做，复制 / 粘贴设置，配方。
- **相机**：照片或视频，拍完带着当前设置进图库，原片保留。
- **导出**：照片选 JPEG / HEIF / TIFF 与三档尺寸，视频选 1080p / 4K；存到相册或交给系统分享，可放到后台继续。

## 视觉方向：灯箱与暗房

- **灯箱（图库、导入）**：乳白色发光的底，打开时像灯管一样闪亮。照片三张一条排在带齿孔和片边字的底片条上；导入候选是白色幻灯片夹。
- **红蜡笔**：多选是手绘红圈加手写序号，改过的照片有红勾，当前筛选项下有一笔红线。
- **暗房（编辑、相机）**：进入时亮底淡出成近黑，安全灯红点呼吸；画面四角是红色裁切角标，照片本身不染色。
- **刻度尺**：滑杆是带刻度的尺加指针，读数用等宽数字；黄滤和品红滤的刻度带颜色，表示画面偏去的方向。
- **强调色跟着胶卷走**：读数、选中项、导出按钮使用当前胶卷的颜色。
- **配方**：米色索引卡，手写名字。

字体：Long Cang（手写）、Martian Mono（等宽数字）、Noto Sans SC（正文），均来自 Google Fonts。

## 可调参数

| 工具 | 选项 |
|---|---|
| 胶片 | 六款胶卷（名称为示例，以引擎自带档案为准） |
| 相纸 | 自动 / Endura / 2383 / 不印相，印相曝光，眩光 |
| 曝光 | 胶片曝光，减冲到迫冲 +2 |
| 光晕 | 强度、范围 |
| 颗粒 | 强度、粗细 |
| 柔焦 | Black Pro-Mist / Pro-Mist / Glimmerglass / CineBloom，强度 |
| 滤色 | 黄 / 品红滤色（CC） |
| 画幅 | 半格、135、XPan、6×6、6×7，片边，日期后背 |

暂未放入：DIR 耦合剂、分层 gamma、显影液耗尽、预闪、扫描锐化、光谱上采样。

## 文件

- `prototype/Main.dc.html`：原型源文件（界面标记 + 交互逻辑）。
- `prototype/canvas.json`：画布索引。
- `prototype/assets/`：四张场景插画与颗粒纹理。
- `android/`：可离线安装的 Android WebView 原型与 Gradle 构建配置。
- `src/`、`public/`：三语展示网站与 APK / ZIP 下载页。

`Main.dc.html` 是 Claude Design 画布的组件格式，依赖画布运行时（`support.js`），图片以 `/_blob/<id>` 引用画布里已上传的素材。直接用浏览器打开这个文件不会运行；把它当作交互规格和实现参考来读。素材与 blob 的对应关系：

| 文件 | 画布内引用 |
|---|---|
| `scene-dusk.svg` | `/_blob/7110bff8c10e1d558b9258aa40dbaeca` |
| `scene-window.svg` | `/_blob/14c0013cb8d226803fe222b1e3be5e23` |
| `scene-beach.svg` | `/_blob/7b48ac6a6c161f2d37cb4ec2781c9e06` |
| `scene-neon.svg` | `/_blob/5f39396bed078cf8ec7644d6b94352e8` |
| `grain.svg` | `/_blob/bdbee43f1d4a21ddbdfc3d615f2302c1` |

## 许可与归属 (Licensing & Attribution)

本项目采用代码与光学数据分离的双轨开源许可，详见根目录 [NOTICE.md](NOTICE.md) 与 [LICENSE](LICENSE)：

1. **软件代码 (GPLv3)**：
   - 包含 Android 应用、Kotlin/JVM 引擎 (`engine/`)、GLSL 着色器、网页应用 (`src/`)、构建脚本与工具 (`tools/`)。
   - 依据 [GNU General Public License v3.0](LICENSE) 开源。引擎与管线移植自 Andrea Volpato 的 [spektrafilm](https://github.com/andreavolpato/spektrafilm) (v0.3.4)。
   - **GPL 对应源码 (Corresponding Source)**：分发二进制程序（如 APK）时，必须提供与该构建完全对应的实际源码。通往仓库默认 `main` 分支的通用链接并不保证与历史二进制完全一致，因此请使用随版本发布的对应源码压缩包 (`Silvercam-App-source.zip`) 或对应 commit tag。
2. **胶片 / 相纸剖面与光学数据 (CC BY-SA 4.0)**：
   - `app/src/main/assets/spektra/` 下由 spektrafilm 烘焙生成的配置文件、LUT 与参数数据依据 [CC BY-SA 4.0](app/src/main/assets/spektra/SPEKTRAFILM_LICENSE.txt) 授权，遵循 Andrea Volpato 的作者前言（Preamble v1.1）。
   - **非约束性倡议说明**：作者前言中关于禁止商业 AI 训练与禁止转卖独立 LUT 包的条款，属作者公开倡议与期望（"These are not legal requirements..."），非 CC BY-SA 4.0 核心法律条款的强制限制；二进制数据包内部未逐个嵌入文本头亦不构成侵权。
3. **输出作品自由（无 Copyleft 传染）**：
   - 用户使用 Silvercam 处理或导出的照片、视频**不产生 Copyleft 传染**；输出成果完全归创作者所有，可自由用于个人或商业用途，无需署名或向软件作者分成。
4. **第三方字体与资源**：
   - Long Cang：SIL Open Font License 1.1 (Google Fonts)
   - Martian Mono：SIL Open Font License 1.1 (Evil Martians)
   - Noto Sans：SIL Open Font License 1.1 (Google Fonts)
   - Lucide 图标：ISC License

## 待解决

- 视频实时性：倾向「LUT 实时预览 + 导出时逐帧补颗粒和光晕」。
- 是否改成免导入（直接读系统相册）。
- 导出面板的水印、元数据、色彩空间。
