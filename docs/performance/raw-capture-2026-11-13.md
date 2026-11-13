# RAW 拍攝後等待時間調研

日期：2026-11-13。程式碼基準：`c99483f`，調研開始時工作目錄乾淨。範圍是根目錄 Gradle 工程的原生 `app/`，不是 `android/` WebView 原型。

本報告記錄歷史調研；`evidence/` 連結僅指向本機產生的紀錄，不隨原始碼提交。裝置資訊、產生檔及私人影像樣本不納入版本控制。相機選項的描述以當時基線為準；後續 main 已加入 JPEG／RAW／burst 格式選擇。

**結論：目前最值得改善的是 CPU RAW 沖洗與工作排程。最近這張是單幀 RAW，約半秒已取得 DNG；App 隨後自動進行全尺寸膠片沖印，而預覽、縮圖、下一張 RAW 的處理又可能等待同一把記憶體鎖。**

這次調研階段有接上真機，因此下列結論包含實際拍攝紀錄及同機分段量測。調研階段新增的內容只有本報告、量測證據與獨立 benchmark；當時沒有修改產品影像算法、重新安裝 App 或改變使用者設定。後續經使用者授權的實作與驗證進度，另見 [RAW 第一輪優化](raw-optimization-2026-11-13.md)。

**實際拍攝紀錄**

裝置：V2545A／MT6993、Android 16，GPU 為 Mali-G1-Ultra MC12。已安裝套件為 `app.spektralab` 0.1.0、`DEBUGGABLE`。相機偏好 `multiFrame=false`；最近一筆 `requestedFrames=1`、ISO 389。APK 雜湊與環境已保存於 [context.json](evidence/raw-2026-11-13/context.json)；沒有可驗證的 APK 內嵌 commit，因此工作目錄程式碼與安裝包的來源 commit 一致性未認證，分段量測使用的是安裝包本身的實作。

| 事件 | 裝置本地時間 | 相對拍攝請求 |
|---|---|---:|
| `RawShooter` 送出拍攝請求 | 13:53:23.300 | 0 |
| `received=1/1 dngReady=true` | 13:53:23.788 | 0.488 秒 |
| 原始 DNG 檔案最後修改 | 13:53:23.867965568 | 約 0.568 秒 |
| 膠片 JPEG 檔案最後修改 | 13:53:54.471965568 | 約 31.172 秒 |

原始檔 25,097,452 bytes；感光元件尺寸 4080×3072，裁切後 4064×3056，直向顯示為 3056×4064，約 1,242 萬像素。原片含 4 張鏡頭陰影增益圖與感光元件噪聲模型。JPEG 為 2,270,127 bytes。App 的匯出佇列已清空，沒有失敗項目。

檔案修改時間只能作為「成片寫入的近似時間」，不是 UI 首幀或 MediaStore 完成通知的精確 trace。它足以證明當次主要等待發生在 DNG 取得之後。單幀紀錄不能用多幀合成解釋。

**同機分段量測**

以獨立 `app_process` 載入手機上已安裝 APK 的類別，讀取同一張 DNG；沒有把照片拉到電腦，也沒有產生新的相冊成片。用 `System.nanoTime()` 計時，輸出尺寸與解碼成功皆檢查。RAW 與鏡頭校正對照各做兩輪；編碼的輸出流只計算位元組數，不含儲存裝置 I/O。

| 階段 | 觀測耗時 | 說明 |
|---|---:|---|
| 讀取約 25 MB DNG | 0.030–0.075 秒 | 含檔案快取影響 |
| DNG 解碼／解析 | 0.028–0.056 秒 | 已驗證成功，不只是呼叫返回 |
| 全尺寸沖洗，不含彩噪去除 | 6.50–6.56 秒 | 去馬賽克、白平衡、高光、鏡頭陰影、色彩、旋轉、half-float 輸出合計 |
| 全尺寸彩噪去除 | 4.50–4.51 秒 | `ChromaDenoise.apply` |
| 上述全尺寸沖洗合計 | 約 11.01–11.06 秒 | 約 1,242 萬像素 |
| 改用與匯出相似的檔案映射輸出 | 11.57–11.85 秒 | 兩輪完整沖洗；沒有消除 CPU 成本 |
| `fast=true`，輸出 1528×2032 | 2.67–2.72 秒 | 降低解析度／採 Bayer binning，畫質與尺寸有取捨 |
| 512／640 請求的匯入、縮圖沖洗 | 暖機約 0.43–0.44 秒 | 實際輸出 509×677；首次約 0.67 秒 |
| 36³／48³ LUT 建立 | 約 0.03–0.17 秒 | 冷啟動依測試程序不同；重用 LUT 約 1–4 ms |
| EGL／renderer 初始化 | 約 0.46 秒 | 與下列 render 分開計時 |
| GPU 膠片渲染＋讀回 | 0.42–0.69 秒 | 135 畫幅裁切後 2709×4064 |
| JPEG 95 編碼 | 0.228–0.246 秒 | 計數輸出流、不含 I/O |
| PNG 編碼 | 12.33–12.82 秒 | 同一張成片；約 15.5 MB |

GPU 2048 長邊成片的渲染＋讀回約 0.157 秒，JPEG 約 0.057 秒，PNG 約 3.145 秒。JPEG 對這次拍攝不是主要瓶頸；PNG 對有膠片顆粒的成片非常昂貴。

上述獨立程序並非當次 App 執行的完整追蹤：測試時沒有相機取景負載，記憶體上限 256 MiB，部分類別與 shader 已有快取；測試也在不同輸出之間主動 GC，該 GC 在計時區域之外。App 本身是 debug 版本，匯出使用檔案映射，且有 Compose、縮圖與編輯工作。**不能把上述數字硬加總成當次 31 秒，也不能把剩餘時間全部宣稱為降噪或 GC。** 尚缺當次的 lock-wait、各 stage、CPU 排程、UI 首幀 trace；本次足以定位可優先改善的工作，但還不是端到端耗時分攤或 P95 統計。

原始量測：[RAW](evidence/raw-2026-11-13/device-raw.txt)、[映射輸出](evidence/raw-2026-11-13/device-mapped.txt)、[LUT](evidence/raw-2026-11-13/device-lut.txt)、[GPU 與編碼](evidence/raw-2026-11-13/device-render.txt)。

**目前完整處理路徑**

```text
快門
  → CameraX RAW_SENSOR + 當幀 CaptureResult
  → 等待 HeavyWork / RawMemoryGate
  → DngCreator → ByteArrayOutputStream → toByteArray → MediaStore 原片
  → AppState.onShotSaved
  → Media.importItem：整份 DNG 讀入與解析 → 小尺寸 RAW 沖洗 → 測光
  → 加入圖庫
  → 自動排入完整沖印
      → HeavyWork 鎖
      → LUT → 再讀 DNG → 全尺寸 RAW 沖洗 → GPU 膠片 → JPEG/PNG → EXIF → 相冊

縮圖／初次進編輯器
  → 另外一次 Media.loadSource → 同一 HeavyWork 鎖 → RAW 讀取與沖洗
```

這些步驟是背景工作，但背景工作仍消耗 CPU，且同一個鎖會把互動需求排在重工作之後。`Media.loadSource` 在解碼結束就釋放鎖；已開啟的預覽沒有一直持鎖。真正長時間持鎖的是整個匯出，以及多幀拍攝至合成完成。

可核對來源：

- [RawShooter.kt](../../app/src/main/java/app/spektralab/camera/RawShooter.kt)：174（自動幀數）、222（DNG 建立）、279（取鎖）、331（通知首張）、353／381（釋放）、367（GPU gather）。
- [AppState.kt](../../app/src/main/java/app/spektralab/ui/AppState.kt)：137／148（首張與完成回呼）、161（自動沖印）。
- [Media.kt](../../app/src/main/java/app/spektralab/data/Media.kt)：151／157（載入與取鎖）、346（匯入仍沖洗 512 預覽）。
- [ExportManager.kt](../../app/src/main/java/app/spektralab/export/ExportManager.kt)：60（預設原尺寸）、187（完整匯出包在鎖內）、293／372（重新沖洗 RAW）、334／335（JPEG／PNG）。
- [SpektraApp.kt](../../app/src/main/java/app/spektralab/SpektraApp.kt)：142（縮圖再次載入 640）；[QuickSources.kt](../../app/src/main/java/app/spektralab/data/QuickSources.kt)：37／52（proxy 主要在編輯器預覽完成後才存在）；[EditorScreen.kt](../../app/src/main/java/app/spektralab/ui/EditorScreen.kt)：201（普通／放大預覽重新載入）。

**主要發現與改善方向**

1. **逐像素鏡頭陰影校正很昂貴，可在保留畫質下重寫。**

   `RawImage.kt:89` 每次 `GainMap.gain` 重新計算座標、double 除法、索引與雙線性權重；`RawDeveloper.kt:190` 在像素／顏色的內層遍歷 list。這張照片有 4 張增益圖，綠色還要平均兩張。

   對同一張感光元件資料暫時移除 gain maps，其他沖洗條件相同且不做彩噪去除：6.63／6.51 秒降為 2.57／2.58 秒，差約 3.93–4.06 秒。[對照紀錄](evidence/raw-2026-11-13/device-gain-ablation.txt)

   這是成本歸因實驗，**不是建議關掉校正**；關掉會改變暗角、亮度及顏色。優先方案是預先計算每列／欄的索引與權重，用固定索引存取增益圖，消除內層 iterator，保留原本插值公式與邊界規則。只需 O(width + height) 輔助表，不應為此新增整張 RGB float 增益圖。實際可省多少仍要量測，不能把移除功能的 4 秒全部當成已實現改善。

2. **大量短命配置加重 CPU 與 GC 負擔。**

   `RawDeveloper.kt:170` 的高光重建每次建立 `FloatArray(3)`，即使該像素沒有高光也已配置。全尺寸有約 1,242 萬次呼叫。校正 list 的 iterator 也位於熱迴圈。

   當次快門至 JPEG 修改時間之間，日誌有 17 次 GC 記錄，累計回收約 1,477 MiB 的 AllocSpace；這是累計回收量，**不是峰值記憶體**。並行 GC 記錄的總執行時間約 651 ms，也**不等於主執行緒暫停時間**。因此不能將 31 秒歸咎於 GC 暫停，但配置吞吐量確實值得消除。[原始 GC 證據](evidence/raw-2026-11-13/shot-gc.txt)

   使用標量或每個 worker 的可重用 scratch，把資料不變的計算搬出內層。先用逐像素相等／誤差界限測試驗證，再量測 allocation count 與總耗時。不要用手動 `System.gc()` 當產品加速方法。

3. **全尺寸彩色降噪是另一個大成本，而且目前大部分是單執行緒。**

   `RawDeveloper.kt:336–443` 包含多次全圖掃描、half-float 轉換、半尺寸與 1/8 尺寸的 5×5 bilateral filter，以及每個輸出像素的權重。這個尺寸的濾波與最後合成合計約有九千五百萬次指數函數求值量級，邊界略少。

   優先將彼此獨立的列／區塊分配到受控 worker pool，每個階段之間明確同步；重用輸出與 scratch，避免共享可變 buffer position。若 CPU 優化仍不足，將相同算法移到 NDK 的可向量化迴圈，或 GLES／compute shader。GPU 方案要連帶設計 RAW→線性 RGB→降噪→膠片的資料流，減少來回讀回。

   不能用粗略的「8 核所以快 8 倍」估算：手機核心異質、頻寬、溫控、相機取景與記憶體都會限制。減弱／略過降噪可作為明確的快速預覽模式，完整成片仍應保留高品質路徑。

4. **縮小輸出沒有對應縮小 RAW 工作量。**

   `RawDeveloper.kt:61` 判斷為 `fast || long / 2 >= maxLongEdge`。這張長邊 4064，半尺寸 2032，對 2048 的請求判斷為 false，因此仍然完整去馬賽克到 4064，再由 GPU 縮到 2048。

   實測 `edge=2048, fast=false` 仍輸出 3056×4064，花 11.10–11.21 秒；`fast=true` 的 1528×2032 輸出約 2.7 秒。兩者不能視為同畫質方案，但對「快速分享尺寸」而言，2032 與 2048 相差不到 1%，值得提供合適的縮小品質策略。

   另有整數向下取整問題：請求 512 實際輸出 677；請求 1600 實際輸出 2032。應明確定義「最大長邊」或「目標附近的 sensor binning」，避免預覽無意間做過多工作。若直接改用更大的 binning factor，必須驗證清晰度，不宜盲目用 ceil 令預覽驟降一級。

5. **自動沖印與首張預覽互相等待，體感可先獨立改善。**

   每張照片都自動匯出，沒有單獨的「只存 RAW／延後沖印」設定。匯出拿到 `HeavyWork` 後，後到的縮圖、編輯器 RAW 載入及下一張 RAW 的記憶體取得都會等待。

   建議拍攝／匯入階段就保留 640–800 長邊的線性 proxy，圖庫與編輯器共用；優先把可觀看預覽送上畫面，再安排完整沖印。proxy 必須包含原片 URI／版本，合成換來源後正確失效。可增加「立即成片／背景延後／只存 RAW」設定，並為互動預覽與下一張拍攝安排優先權。

   這可以縮短看到照片的等待，但不代表全品質 JPEG 已經寫完。不可直接移除記憶體鎖：12.4 MP 的 RGBA16F 本身約 99 MB，還有 RAW、去馬賽克 plane、降噪、GPU 與輸出 bitmap。保留大型任務的記憶體預算，只讓有界的小 proxy 避開大型解碼隊列。

6. **多幀模式有另一個被程式註解掩蓋的阻塞。**

   `RawShooter` 從第一幀前拿到 `RawMemoryGate`，一直持有至合成 DNG 儲存完成。雖然 `onSaved` 提早觸發，`AppState.onShotSaved → Media.importItem → Media.loadSource` 又需要相同的鎖，首張不能真正立即解碼入庫。README 的「第一幀一到就可以看和調」與這條實際路徑不一致。

   正確改善是由既有持鎖拍攝工作產生小 proxy 與拍攝 metadata，再交給 UI；不應另開一個無限制 RAW 解碼來繞過保護。若要允許下一張，需獨立的有界 shot admission／記憶體策略；目前 `ShotAdmission` 明確等到合成完成才重新接受。

   多幀已有幀數 1／3／5 自適應、邊拍邊對齊、GPU gather、平行 DNG deflate，不能將「加 GPU」「加多核心壓縮」當成尚未做的工作。GPU gather 失敗會退回 CPU，且讀回後抽樣比對不通過也會重算；多幀應記錄 backend 與失敗原因。此次單幀紀錄沒有觸發這條路徑。

   可再評估把 camera plane 直接轉成 `RawImage`，避免每幀 DNG 編碼→複製→重新解析。必須完整保留 row stride、CFA、黑白電平、白平衡、矩陣、裁切、旋轉、增益圖、noise profile 及當幀 CaptureResult；不能以遺失色彩校正換取速度。這次原片取得只約半秒，所以應排在 CPU 沖洗之後。

**建議實作順序**

| 優先 | 工作 | 預期影響 | 畫質／風險與驗收 |
|---|---|---|---|
| P0 | 在現有管線加入 shot ID、單調時間與分段 trace | 補齊實際 31 秒分攤 | 分開 lock wait／CPU／GPU／encode／首幀／下一張可拍 |
| P1 | 高光重建取消每像素配置；增益圖座標與權重預算、直接索引 | 針對已測得約 4 秒的校正成本與配置熱點 | 優先保持數值一致；比較高光、色偏、暗角、旋轉、odd crop |
| P1 | 拍攝／匯入即建立共用 proxy，預覽優先於自動完整沖印 | 首張更早可看可調，也減少重複沖洗 | proxy 來源版本、曝光一致、取消與生命週期、記憶體上限 |
| P2 | 彩噪濾波分列並行，再評估 NDK 向量化 | 針對全尺寸約 4.5–5 秒階段 | 同算法畫質對照、核心數控制、連拍溫控與尾延遲 |
| P2 | 低解析度匯出真正使用適合的 RAW 縮小流程 | 已觀測 11 秒與 2.7 秒的不同品質路徑 | 明確快速品質檔，保留原 RAW 與全尺寸高品質選項 |
| P3 | GPU RAW 沖洗／降噪、減少 DNG 中轉及多幀資料複製 | 長期吞吐量與續航 | 工作量較大，需不同 GPU、CPU fallback 與像素誤差驗收 |

沒有實作前，不承諾「整體快幾倍」。短期應先驗證 P1，並把「可看預覽」與「完整成片」分成兩個效能目標。可以設定預覽 1–2 秒為產品驗收目標，但這是目標，並非本次已達成的結果。

**現在不改程式就能做的選擇**

- 優先 JPEG，避免為帶顆粒的日常成片選 PNG。此次 JPEG 本來就啟用，因此它不是當次 31 秒的解法；之前相冊確實有 PNG 沖印紀錄，但本報告沒有拿那些紀錄替代當次量測。
- 日常拍攝維持單幀 `RAW`；低光且需要更乾淨畫面時才使用 `RAW×N`。目前 RAW 標記是單／多幀切換，不是 RAW／JPEG 切換。
- 若只是覺得影像慢慢浮現，可關掉顯影動畫：`Polaroid.DURATION_MS=1600`。這只省視覺等待，不加速沖洗。
- 若是取景變暗、快門不能再按，還要區分手動過片狀態；自動過片能省操作，不會改變 RAW 算法耗時。
- 選 2048 輸出會減少 GPU／編碼成本，但目前這張 RAW 仍走全尺寸 CPU 沖洗，不能預期按像素數等比例變快。

**後續驗證規格與本次驗證範圍**

使用 release + `profileable` 版本量測，保留 debug 版本作開發診斷。以同一組 12 MP、較高解析度 Bayer DNG，包含低光噪聲、高光、彩色邊緣、暗角與各旋轉；記錄冷／暖啟動、單幀／3 幀／5 幀、JPEG／PNG、原尺寸／小尺寸。持續拍攝至少一個固定批次，分別報 P50、P95、最大記憶體與 Thermal Status。

每張至少追蹤：capture request、image acquired、metadata ready、lock requested/acquired/released、DNG ready/saved、import proxy、first visible preview、next shot ready、RAW read/parse、demosaic/gain/color、denoise、GPU submit/readback、encode、EXIF、MediaStore ready。GPU submit 是非同步的，應包含 fence/readback 或 GPU timer，不能只量發命令的時間。

本次執行：

- 真機 RAW、gain ablation、mapped buffer、LUT、GPU 與 JPEG／PNG 分段測試均完成，`BENCH_COMPLETE`。
- 既有 `LinearDngIntegrationTest` 四項與 `BurstTest.mergingABurstBeatsASingleFrame` 通過；合成畫質檢查維持 29.44 → 33.57 dB。[輸出](evidence/raw-2026-11-13/engine-validation.txt)
- 桌面既有 `BurstTest.benchmark`：Apple M2 Max、JDK 17、12 logical cores、5 幀 4000×3000。暖機兩次 planning 552／399 ms，CPU gather 555／539 ms，DNG write 214／306 ms。[輸出](evidence/raw-2026-11-13/desktop-burst.txt) 不能外推為手機合成速度；該既有 benchmark 沒有檢查 read-back 是否非 null，因此不使用其 read-back 耗時作為成功解碼證據。
- 可重現工具與限制見 [tools/raw-performance](../../tools/raw-performance/README.md)。

**外部技術資料**

- Android [效能量測指南](https://developer.android.com/topic/performance/measuring-performance)：建議 release/profileable、固定編譯狀態及自訂 trace；debug 結果不可當作出貨效能。
- Android [Neon 支援](https://developer.android.com/ndk/guides/cpu-arm-neon)：arm64 支援 Neon；優先讓 Clang 向量化可攜式程式，不必一開始就手寫 assembly。
- Android [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)：改善指定路徑首次執行的解譯／JIT 成本，不能取代算法與配置優化，也不能直接套用官方一般案例百分比。
- Android [CameraX 拍照](https://developer.android.com/media/camera/camerax/take-photo)：capture mode 能取捨拍攝延遲與品質。本次取得 DNG 僅約半秒，優先級低於後處理。
- Android [Zero-Shutter Lag](https://developer.android.com/media/camera/camerax/take-photo/zsl)：文件描述 PRIVATE reprocessing／JPEG 流程，不應假定改一個常數便可加速此 RAW 管線。
