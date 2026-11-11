# SpektraLab App

安卓原生的胶片模拟工具：照片、视频共用一套「胶片 × 相纸」参数。成像引擎是 [spektrafilm](https://github.com/andreavolpato/spektrafilm)（光谱胶片模拟）的 Kotlin + OpenGL ES 移植。

## 当前状态

| 部分 | 状态 |
|---|---|
| 导入照片（相册多选 / 文件，含系统能解码的 RAW/DNG） | ✅ |
| 编辑：实时预览、按住看原图、撤销/重做、复制/粘贴设置、配方 | ✅ |
| 导出照片（JPEG / PNG，长边 2048 / 3400 / 全尺寸，全尺寸上限 4096 px 或 GPU 纹理上限），存相册或分享 | ✅ |
| 视频：导入、实时预览播放、逐帧重新生成颗粒导出（1080p / 4K，音轨原样保留） | ✅ |
| 折叠屏 / 平板适配（展开双栏、悬停与书本姿态按铰链分屏） | ✅ |
| 相机 | 暂未开放（入口保留，点了会提示） |

没有在真机上跑过：构建环境里没有模拟器。引擎和着色器做了离线验证（见下）。

## 构建

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :engine:test              # 引擎对照 spektrafilm 的测试
./gradlew :app:testDebugUnitTest    # 折叠屏布局测试（Robolectric）
```

需要 JDK 17+、Android SDK 35。最低 Android 10（API 29），GPU 需支持 OpenGL ES 3.0 和半精度浮点渲染目标。

## 结构

```
engine/        纯 Kotlin（JVM）模块：spektrafilm 运行时的移植 + GLSL 着色器源码
app/           Android 应用（Jetpack Compose + GLES 3.0 + MediaCodec）
  assets/spektra/   由 spektrafilm 烘焙出的胶片 / 相纸数据（*.spk）
tools/bake/    运行 spektrafilm（Python）导出数据和参考向量的脚本
tools/glcheck/ 在 WebGL2 里跑 app 着色器、对照 Kotlin 引擎的检查脚本
prototype/     早先的交互原型（Claude Design 画布格式）
```

## 引擎：怎么用 spektrafilm

spektrafilm 的流程是 线性 RGB → 胶片曝光 → 显影（DIR 耦合剂、颗粒）→ 放大机印相 → 相纸显影 → 扫描。移植方式：

- **固定不变、依赖 colour-science 的部分由 spektrafilm 自己算好**（`tools/bake/bake_spektrafilm.py`）：每款胶片的 Hanatos 2025 光谱上采样表（含感光度适配和输入色域压缩）、CAT16 矩阵、每款相纸 / 反转片的扫描表（观看光源、CMF、CAM16-UCS 输出色域压缩）、放大机光源、分色滤镜和中性滤色数据库。
- **所有可调的部分在手机上按 spektrafilm 的公式实时计算**（`engine/`）：胶片曝光、显影曲线与 gamma、DIR 耦合剂（含扩散）、放大机滤色与印相曝光的光谱积分、中灰归一化、相纸曲线、子层泊松-二项颗粒、乳剂散射 + 片基反射光晕、四种柔焦镜 PSF、眩光。
- GPU 上每帧：曝光 → 高斯金字塔模糊（光晕 / 柔焦 / 耦合剂扩散拟合为若干倍频高斯）→ 显影 + 颗粒 → 「胶片密度 → 印相 → 扫描」三维表 → sRGB。印相那一段在设置变化时在 CPU 上重新算成三维表（预览 36³，导出 48³）。

### 验证

- `ReferenceTest`：8 组胶片 × 相纸 × 参数，逐级对照 spektrafilm 的中间结果。胶片密度误差 < 0.001，输出平均误差 0.04%（sRGB），GPU 用的折叠三维表平均 0.1%。
- `SpatialTest`：光晕、四种柔焦镜的冲激响应（能量环曲线误差 < 1.6%）和颗粒均值 / 标准差都对照 spektrafilm 自己的空间代码。
- `tools/glcheck`：所有着色器在严格的 WebGL2 下编译（视频用的外部纹理版本除外，WebGL 没有这种纹理）；拍立得显影着色器与 spektrafilm Python 原版逐像素一致；GPU 逐像素结果和 Kotlin 引擎相差 < 1/255；GPU 颗粒的标准差与 CPU 模型一致；金字塔模糊的 σ 和能量守恒。

### 和 spektrafilm 的差异

- 光晕 / 柔焦 / 耦合剂扩散用倍频高斯混合近似（拟合误差见上），亚像素的散射核心并入恒等项。
- 颗粒在 λ > 12 时用正态近似泊松；微结构团聚（`micro_structure`）在手机分辨率下 spektrafilm 本身也不触发，未实现。
- 眩光取均值（spektrafilm 是随机对数正态场再模糊）。
- 未实现：预闪、扫描锐化、曲线形变（developer exhaustion 等）、黑白校正、相机 UV/IR 滤镜、Hanatos 2025 之外的上采样方法。
- 迫冲：Portra 800 用 spektrafilm 实测的迫冲档案；其他胶片按「少曝光 N 档 + gamma ×(1+0.15N)」近似。

## 边框与画布（spektrafilm GUI 的样式）

spektrafilm 的核心引擎不带边框，这些样式来自它的桌面 GUI，全部移植：

| spektrafilm | 这里 |
|---|---|
| `white_padding`（默认长边 3% 白边） | 「相纸白边」：同样的宽度，颜色是所选相纸未曝光处的模拟颜色；反转片 / 直接扫描时为纯白 |
| `gray_18_canvas`（#767676 画布） | 编辑页「18% 灰背景」（只影响观看） |
| `virtual_photo_paper_back`（相纸背面水印） | 照片显影前、图库缩略图未生成时的占位图 |
| `polaroid_animation`（拍立得式显影） | 打开照片时播放的显影动画（GLSL 移植，可关闭） |

「片边」（胶片名与格号）和「日期后背」是原型里的设计，不来自 spektrafilm；片边颜色按片基经过整条印放链路的真实颜色。

## 折叠屏

用 Jetpack WindowManager 读取铰链位置和姿态，编辑页按姿态切换布局：

| 状态 | 布局 |
|---|---|
| 外屏 / 普通手机 | 预览在上，工具面板在下 |
| 展开（宽度 ≥ 600dp）、平板 | 预览在左，工具在右侧栏（340–460dp） |
| 书本姿态（竖向铰链半开，或双屏） | 以铰链为界：左边预览，右边工具 |
| 悬停 / 桌面姿态（横向铰链半开） | 铰链以上是预览，以下是操作和工具；弹出面板也只在下半屏 |

- 图库每条底片在手机上排 3 张，展开后 4 张，大屏 6 张；底栏和弹出面板最宽 600dp 居中。
- 折叠 / 展开不重建 Activity（`configChanges`），编辑进度、当前工具和视频播放位置都保留。四个区域在组合树里的位置固定，GL 预览不会因换布局被销毁；万一 EGL 上下文丢了，会自动重新上传照片、查找表，并恢复视频播放位置。
- 预览解码分辨率跟随最大窗口尺寸（1600–3072 px），展开后的大屏依然清晰。
- `app/src/test/.../EditorLayoutTest.kt`（Robolectric）检查四种布局下各区域的位置，包括按铰链分屏。

## 交互

图库 → 编辑 → 导出，沿用原型的「灯箱与暗房」：

- **灯箱（图库）**：乳白发光底，三张一条排在带齿孔和片边字的底片条上，按日期分组；「选择」后红蜡笔圈选、手写序号，可批量粘贴设置、套用配方、导出、移除（只移出图库，相册原片不动）。改过的照片有红勾。
- **暗房（编辑）**：近黑背景、安全灯红点呼吸、红色裁切角标；刻度尺滑杆（双击复位），黄 / 品红滤色刻度带颜色表示画面偏去的方向；强调色跟着胶卷走。
- **导出**：在后台冲印，可以「后台继续」回去修图，顶部有进度胶囊。

| 工具 | 选项 |
|---|---|
| 胶片 | spektrafilm 自带的 18 款：Portra 160/400/800、Ektar 100、Gold 200、UltraMax 400、C200、Pro 400H、X-TRA 400、Vision3 50D/250D/200T/500T、Verita 200D、Velvia 100、Provia 100F、Ektachrome 100、Kodachrome 64 |
| 相纸 | 自动 / Portra / Supra / Ultra Endura / Endura Premier / Ektacolor Edge / Crystal Archive / 2383 / 2393 / 不印相；印相曝光、眩光。反转片直接扫描 |
| 曝光 | 胶片曝光（亮度由印相补回）；减冲 −1 到迫冲 +2 |
| 光晕 | 强度、范围（1.0 = 该胶片片基与防光晕层的原生值） |
| 颗粒 | 强度、颗粒粗细（随画幅物理缩放） |
| 柔焦 | Black Pro-Mist / Pro-Mist / Glimmerglass / CineBloom，强度 |
| 滤色 | 放大机黄 / 品红滤色（Kodak CC） |
| 画幅 | 半格、135、XPan、6×6、6×7；边框（无 / 相纸白边 / 片边）、日期后背；显示：18% 灰背景、显影动画 |

亮度按 spektrafilm 的中央重点测光自动定（导入时测一次）。

## 许可

- 代码：GPLv3（`LICENSE`）。引擎是 spektrafilm（GPLv3）的移植。
- `app/src/main/assets/spektra/` 下的胶片 / 相纸数据和由其生成的表：CC BY-SA 4.0，见 `SPEKTRAFILM_LICENSE.txt`。
- 字体 Long Cang（子集）、Martian Mono：SIL OFL，见 `app/src/main/assets/licenses/`。

Film modeling powered by [spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato.
