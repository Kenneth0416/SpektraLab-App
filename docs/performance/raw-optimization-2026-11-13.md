# RAW 第一輪優化實作與驗證 — 2026-11-13

已落地 gain map 預計算、高光重建配置消除，以及拍攝／匯入共用小型線性預覽。完整 JVM／Robolectric 回歸與原生 Debug APK 建置已通過。**同機修改後量測尚未完成：V2545A 在基準量測後從 ADB 離線，因此目前不宣稱真機加速百分比。**

原始問題、量測限制與優先順序見[調研報告](raw-capture-2026-11-13.md)。原始碼基線為 `c99483f`。本報告的 `evidence/` 連結是本機調研紀錄；產生的測試輸出、裝置資訊與私人樣本不納入版本控制。下方歷史量測不是本輪修改後的效能證據。

## 實作內容

### 1. 保留原像素結果的引擎優化

- [`PreparedGainMap.kt`](../../engine/src/main/kotlin/app/spektralab/raw/PreparedGainMap.kt) 預先計算每個座標軸的插值索引與權重，保留原本 Double → Float 的轉換及四點插值運算順序。
- [`RawDeveloper.kt`](../../engine/src/main/kotlin/app/spektralab/raw/RawDeveloper.kt) 只準備實際使用的 gain map；同一 map 在通道間共用。座標表為 O(裁切寬 + 裁切高)，每次沖洗的軸陣列預算上限 2 MiB，包含每張 map 256 bytes 的物件標頭／對齊保留額，超過部分沿用原算法。此次 4064×3056、四張 map 的有效負載約 334 KiB。`workingBytes(RawImage, ...)` 計入相同的選取、去重及上限規則；`longEdgeWithin(RawImage, ...)` 先扣除軸陣列額度再選擇輸出尺寸，無法容納最小 rendition 時明確失敗。重度縮圖仍保留完整 crop 的軸陣列估算。
- 高光重建改用區域 scalar，消除每像素 `FloatArray(3)`，保持原本浮點運算順序。
- 全尺寸去馬賽克、鏡頭校正、降噪及輸出解析度的設定保持原有語意；本輪沒有關閉校正或改成低解析度成片。

### 2. 先準備可共用的預覽

- [`RawShooter.kt`](../../app/src/main/java/app/spektralab/camera/RawShooter.kt) 在既有 RAW 記憶體許可內，解析第一張 DNG，建立小型線性預覽，再交給 AppState；多幀繼續使用已解析的 RAW 規劃合成。單幀的完成通知也排在預覽工作之後。
- [`RawPreview.kt`](../../app/src/main/java/app/spektralab/data/RawPreview.kt) 僅保留縮小後的線性像素、原始方向尺寸及測光值，不持有 sensor 陣列或 RAW 許可。測光使用與舊匯入一致的 512-edge 沖洗結果；快取長邊限制為 800。
- [`Media.importItem`](../../app/src/main/java/app/spektralab/data/Media.kt) 收到拍攝預覽後，可直接建立 LibraryItem，不必重新解析整張 RAW 或再次等待 `HeavyWork`。普通 RAW 匯入也把原本已沖洗的小圖存入共用快取。
- [`AppState.kt`](../../app/src/main/java/app/spektralab/ui/AppState.kt) 先準備快取再發布 LibraryItem。多幀合成後，先以合成 URI 存放合成預覽，再切換來源；第一張與合成來源不混用像素。
- 圖庫縮圖、編輯器的既有 quick 路徑與樣片皆可讀取該預覽，完整沖洗及自動匯出仍使用原始來源。圖庫與相機的縮圖 `produceState` 也加入 URI 作為重載鍵，使同一 ID 從第一張切換到 merged 時會刷新。

### 3. 快取與記憶體保護

[`QuickSources.kt`](../../app/src/main/java/app/spektralab/data/QuickSources.kt) 保留既有 24 MiB 記憶體快取上限；以 item ID + URI SHA-256 隔離不可變來源。這也避免不同 URI 的 `String.hashCode()` 碰撞；最近 256 個 URI 的雜湊另行快取，避免 UI 重組時反覆計算。每個讀者取得獨立 ByteBuffer 游標，磁碟寫入使用 duplicate 並確保寫完全部內容。`forget` 與讀寫共用同步保護。

全解析度工作仍受 `HeavyWork`／`RawMemoryGate` 保護，未放開大型任務並行。新預覽只繞過不需要再次讀取 sensor 的路徑。實際裝置的峰值記憶體與長時間熱狀態仍待後續實測。

合成預覽重用已在記憶體中的 merged samples，位於 DNG writer 的 half-float 量化之前；因此它是即時顯示用近似預覽。最後成片仍讀取保存後的 DNG，本輪的逐位元相容主張適用於對同一 RAW 輸入執行的引擎沖洗，並非聲稱合成預覽與重新讀取 DNG 的預覽逐位元一致。

## 已完成驗證

執行命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/kennethkwok/Library/Android/sdk \
./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug --console=plain
```

結果：記憶體預算修正後，重新執行上述完整命令，Engine／App 回歸及 Debug APK 建置全部成功。另以 Python AST 檢查 benchmark runner 語法，以 JDK 17、Android 35 及 engine JAR 編譯 `RawStageBench.java` 成功；未執行裝置 benchmark。

| 範圍 | 通過 | 略過 | 失敗 |
|---|---:|---:|---:|
| Engine | 184 | 23 | 0 |
| App / Robolectric | 103 | 0 | 0 |

略過項目沿用原本外部樣本／選跑量測條件，未為本輪修改刪除或放寬測試。最新結果來自 `engine/build/test-results/test/`、`app/build/test-results/testDebugUnitTest/` 的 XML；這些產生檔不納入版本控制。`evidence/` 內的 metadata／Gradle 紀錄屬於預算修正前的歷史驗證，不代表本次 APK。

新增驗證包含：

- 不同 gain 網格、單點軸、正負 origin、邊界 clamp、奇數裁切，逐點比較原算法的 Float bits。
- 修改前 engine JAR 產生固定 golden：8192 組高光輸入（功能開／關）及 8 種方向 × full/binned 的 16 組完整輸出。修改後 SHA-256 全部一致；[原始 golden](evidence/raw-optimization-2026-11-13/before-desktop-golden.txt)。
- RAW 許可被另一工作持有時，帶入預覽的 import、記憶體與磁碟 cache 讀取仍完成；關閉 proxy 不會誤釋放該許可，大型工作必須等真正持有者釋放。
- 透過真實 AppState callback → import → Library，驗證多幀首張在鎖未釋放時已入庫、可讀 quick pixels，且不提早自動匯出。
- URI 雜湊碰撞隔離、merged 新來源、Buffer 游標隔離、快取刪除、尺寸界限與原始尺寸保留。
- 合成預覽的黑白點、RGB gain plane、白平衡與旋轉方向。
- 300 張 linear-plane maps 跨越 2 MiB cap 後的 fallback 像素結果、超長座標軸直接 fallback、共享／未使用 map 不重複計費，以及 heavily binned 輸出的完整 crop 軸陣列預算。

限制：callback 測試直接呼叫 AppState 的 saved callback，尚未取代真機 CameraX producer 的成功／失敗／關閉時序驗證。整合到 main 後仍須驗證 JPEG／單張 RAW／burst 選擇及 fold finder；本分支未覆寫 main 的新版相機 UI。

新增 callback 測試曾因 Robolectric 的 Android 主 Looper 暫停而逾時。診斷確認 proxy 已寫入，排空主 Looper 後 Library 立即出現資料；測試補上事件佇列驅動，保留 5 秒期限、持鎖條件及所有斷言。既有 `BurstSourceRetentionTest` 僅補入 callback 新增的 nullable 參數，原保留來源斷言不變。

## 真機量測狀態

本輪修改前，已在原 V2545A / MT6993 / Android 16，對原檔
`/sdcard/DCIM/Silvercam/EXISTING_CAPTURE.dng`（範例路徑，實際樣本不納入版本控制）完成兩輪基準並記錄輸出 SHA-256：

| 路徑 | 本輪修改前 | 修改後 |
|---|---:|---|
| 完整 3056×4064 RAW，含 chroma denoise（edge=4096） | 10.870 / 10.997 s | 待手機重新連線 |
| 1528×2032 fast RAW，含降噪 | 2.699 / 2.621 s | 待手機重新連線 |
| 509×677 小預覽，含降噪（edge=512） | 0.679 / 0.426 s | 待手機重新連線 |

[完整修改前真機紀錄](evidence/raw-optimization-2026-11-13/before-device-raw.txt)包含 `BENCH_COMPLETE`。完整輸出的 SHA-256 為：

```text
e74bed09070a4567d67c2afc472f4cce7f2c12849667bdb09bc3ac95ca87d3b5
```

嘗試候選 APK 時，ADB 回報 `no devices/emulators found`；之後 `adb devices -l`、`adb mdns services` 與 USB 枚舉也未找到此手機。[失敗原因紀錄](evidence/raw-optimization-2026-11-13/candidate-run-disconnected.txt)已保存。尚未取得修改後 Android timing/checksum，未安裝或替換手機上的 App。

已安裝 APK 的 source commit 尚未驗證；基準是手機上原有版本，候選是本工作目錄建置版本。之後的兩組分段量測仍為獨立 `app_process`，不能直接換算成快門到 UI／相簿的總等待時間，亦不能承諾歷史約 31 秒會降到特定秒數。

## 產物與接續步驟

原生 APK：`app/build/outputs/apk/debug/app-debug.apk`。APK 與產生的 metadata 不納入版本控制；需量測時請對當次 APK 重新記錄雜湊。

[`run.py`](../../tools/raw-performance/run.py) 已新增 `--apk`，可不安裝候選 APK，在同一手機上比較；`raw` 模式新增降噪後 RGBA half-float SHA-256，checksum 計算不計入 stage timer。暫存 APK/dex 會在執行結束後清除。

手機恢復連線後執行：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 python3 tools/raw-performance/run.py raw \
  --serial DEVICE_SERIAL \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --raw /sdcard/DCIM/Silvercam/EXISTING_CAPTURE.dng \
  --output docs/performance/evidence/raw-optimization-2026-11-13/after-device-raw.txt
```

接著核對每個 pass/edge 的 checksum、尺寸和 stage timing，更新此表格及收益。冷／暖啟動、P50/P95、實際相機並行負載、峰值記憶體與完整 UI／MediaStore 時間，仍需要正常 App 路徑的實機測試；目前沒有用單元測試耗時代替這些數據。

## 後續：串流 DNG 與有界平行沖洗

另以獨立 commit 移植 `d14bf75`／`c5db71d` 的串流及 band 演算法，保留本分支的 allocator、gain-table 預算、hardened format readers 與 main 的相機格式策略，沒有整份覆蓋舊版 Media／RawDeveloper。

- Seekable Bayer／linear DNG strips 從 descriptor 分段讀取，保留 AssetFileDescriptor 的 offset／length。非 seekable provider、tiles、lossless JPEG、X-Trans 及其他 maker formats 保留受檔案大小／heap headroom 限制的 whole-file fallback。
- 開啟 metadata 時仍檢查 sensor sample 數、segment 數與 strip 範圍。串流 deflate strip 的壓縮資料及解壓 scratch 各限制在 4 MiB；超過時只能走既有有上限的 fallback。裁切只用到 strip 的前幾列時，也驗證完整 bounded strip 的 trailer。
- Bayer／linear 沖洗與 chroma passes 使用固定兩個 worker，source band 約 64 列，Malvar 另取兩列 halo。保留原浮點運算及加總順序；既有 gain-table preparation 不重複實作。X-Trans 沿用既有 band 演算法並加入取消檢查。
- `workingBytes(RawImage, ...)` 計入 gain tables、每個 worker 的解碼 scratch、sample bands 與 Malvar halos。`longEdgeWithin` 使用 image-aware 候選估算，無法容納非空結果時明確失敗。輸出仍可由 caller 的 mapped-buffer allocator 提供；不改 tiled export 的配置路徑。
- 取消發生時不再退回 system decoder；平行工作先全部結束才關閉 descriptor 或釋放 HeavyWork。等待 HeavyWork 的 editor load 也能取消，不另設平行 RAW admission gate。
- 保留現行 linear `fast` 的解析度語意，沒有移植舊分支強制至少 2×2 binning 的策略。小 heap 測試明確要求 2032 edge；不把較小輸出混充同尺寸加速。

驗證包含 legacy／whole／streamed 的逐 bit 比較、各 strip layout、late metadata、毀損／超大 strips、mapped allocator、provider slices、worker drain、取消與有限 heap subprocess。12 MP synthetic merged DNG 的 2032-edge preview 使用 48 MiB Java heap／32 MiB direct cap；完整輸出使用 96 MiB Java heap／104 MiB direct cap。這些是桌面 JVM 的分開上限，並非 Android 裝置整體記憶體或速度保證。私人影像、產生的 DNG／APK 與測試紀錄不納入提交。

串流整合後的完整驗證：`:engine:test :engine:jar :app:testDebugUnitTest :app:assembleDebug` 通過，Engine 201 通過／23 略過，App 105 通過，零失敗。既有 benchmark Java API 編譯及 Python 語法檢查也通過。相機正常運作路徑的真機 timing／記憶體仍待量測，不宣稱量測到裝置加速。
