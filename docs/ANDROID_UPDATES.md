# 原生 Android 应用更新

网站下载与应用内更新共同分发包名 `app.spektralab` 的原生应用，最低 Android 10（API 29），需要 OpenGL ES 3.0。`android/` 中包名为 `app.spektralab.prototype` 的 WebView 原型保留作历史参考，不再由发布命令构建，也不能覆盖升级原生应用。

## 用户体验

- 每次回到前台时判断是否需要检查；距上次尝试至少 24 小时才发起自动请求。成功和失败都会计入，避免离线时反复请求。
- 请求在后台进行，连接和读取超时均为 5 秒，响应最多 64 KiB。失败不弹窗，也不影响拍照、导入和冲印。
- 新版提示显示版本和与应用语言对应的更新说明；自动提示等待回到空闲的灯箱或设置页，不打断相机、编辑、选择、导入、冲印和其他弹层。
- 「稍后提醒」、关闭对话框或成功打开下载链接后，至少 24 小时不再自动提醒。这个时间保存在本机，重启应用仍生效。
- 设置 → 应用更新显示已安装版本、检查状态和「检查更新」。手动检查绕过时间限制；进行中的请求会合并。
- 用数字 `versionCode` 比较版本。相同或更旧的版本不提醒。包名不匹配、非 HTTPS / 不同主机的下载地址、损坏的 JSON、设备系统不满足新版要求都不会引导安装。
- 点击「前往更新」打开浏览器下载 APK，由用户确认覆盖安装。应用不申请安装包权限，也不自行下载或安装。APK 的签名和升级兼容性由 Android 安装器验证；应用不会下载文件来核对 JSON 中的 SHA-256。

## 唯一发布路径

`npm run android:build` 与 `npm run android:build:native` 是同一路径的别名，都构建根目录 `:app:assembleRelease`。网站按钮与更新 JSON 必须引用同一个 `Silvercam-Android.apk`，不能分别更新两个发布渠道。发布产物为：

- `public/downloads/Silvercam-Android.apk`
- `public/downloads/Silvercam-App-source.zip`
- `public/downloads/Silvercam-Android.provenance.json`
- `public/updates/android.json`

不再生成或使用 `Silvercam-Native.apk`、`Silvercam-Native-source.zip`、`Silvercam-Native.provenance.json`。如工作目录中有旧实验产物，应在最终部署前移出 `public/`，防止 Vite 复制过期文件。不要将它们作为新的发布基线。

构建脚本从实际 APK 读取包名、版本、最低系统要求与签名，不解析 Gradle 中的版本字面量。版本和更新 URL 来自 `release/android.properties`；三语说明来自 `release/android-notes.json`。这些内容与源码同时取快照。源码在构建过程中变化时拒绝替换下载文件。

构建前核对现有网站 APK/源码/来源文件的摘要，然后读取旧 APK 的真实包名、版本和证书。旧网站下载即使使用历史来源格式，只要已是原生包，也必须通过签名连续性、版本不倒退和不同 APK 必须递增版本号的检查。历史 WebView 包可以一次迁移为原生包，因为它们的应用 ID 不同；旧原型用户需要另外安装原生应用。

## 防止网站发布旧 APK

`npm run android:verify`、`npm run package:downloads`、`npm run dev` 和 `npm run build` 均执行共享校验：

1. APK 与对应源码 ZIP 的 SHA-256 必须与来源文件一致，来源文件列表本身的摘要必须有效。
2. 来源文件中的 native input digest 必须与当前原生构建输入一致：`app/`、`engine/`、`tools/`、`release/`、Gradle 配置和 wrapper，以及原生构建/归档脚本。文件内容、路径、增删和 Unix 模式均参与摘要。原生输入变化但没有重新构建时，网站构建直接失败。
3. 更新 JSON 必须与这一个 APK 的版本、URL、SHA-256 和更新说明完全匹配。缺文件、旧版本 JSON、旧 APK 或中断写入产生的混合文件均拒绝发布。

网站文案、样式、文档和 `promo-video/` 不属于原生输入，不会无谓触发 APK 重建。此时网站继续提供该 APK 构建时捕获的完整对应源码，不把今天的网站源码冒充旧 APK 的构建来源。新 APK 构建时则重新捕获包括已合并应用修改在内的完整来源快照。未经新流程构建的历史来源文件不能通过最终发布校验，需执行一次原生构建迁移。

## 发布一个新版

先合并全部应用修改，再执行最终构建。每次应用更新都必须同时更新网站 APK：

1. 在 `release/android.properties` 中将 `versionCode` 提高到所有已分发原生版本之上，并更新 `versionName`。此次整合版本为 `3 / 0.1.2`，高于先前的 version 2 实验包；之后继续单调递增。
2. 更新 `release/android-notes.json` 的 `en`、`zh-Hans`、`zh-Hant` 三份说明，包含这次整合的全部用户可见变化。
3. 使用原来的签名密钥，在最终合并的源码上运行：

   ```sh
   npm ci
   npm run test:updates
   npm run android:build
   npm run android:verify
   npm run build
   ```

   离线 SDK/依赖齐全时，可在构建命令前加 `ANDROID_BUILD_OFFLINE=1`。`android:build:native` 与 `android:build` 完全等价。
4. 一起提交和部署上述四个发布文件。网站的 `dist/` 同时包含它们，网站下载元数据与更新 JSON 使用相同版本和 APK SHA-256。使用原子部署；逐文件上传时先传 APK、对应源码和来源文件，最后传 JSON。不要在来源代码集成尚未完成时提交临时二进制。
5. 发布后验证 JSON 直接返回 HTTP 200 JSON、APK 链接可下载、网页 APK SHA-256 与 feed 一致。在已安装旧版本且保留用户数据的设备上手动检查更新并覆盖安装。仅在本地构建不等于已发布。

`public/_headers` 为 JSON 设置禁止长期缓存，其他平台应配置同等策略并让静态 JSON 优先于 SPA 回退。发布后仍应验证实际托管结果。

## 更新源与签名

默认更新源为本项目 GitHub 静态文件：

```
https://raw.githubusercontent.com/Kenneth0416/SpektraLab-App/main/public/updates/android.json
```

`updateBaseUrl` 位于 `release/android.properties`，APK 编译进 `${updateBaseUrl}/updates/android.json`，feed 指向 `${updateBaseUrl}/downloads/Silvercam-Android.apk`。可以在首次发行前改成实际官网 HTTPS 基址。已经分发的 APK 会继续请求旧地址；更换域名时，旧地址必须继续提供直接 HTTP 200 的 JSON，不能用 301/302 替代。本客户端要求下载 URL 与 feed 同主机。使用默认 GitHub 地址时，必须把发布文件提交并推送到 `main`；仅部署网站不会更新该地址。网络可达性依用户环境而定。

当前 release 构建仍使用原有 debug 签名配置。正式发行前应确定长期保管的密钥；已分发原生 APK 必须沿用其签名才能保留数据升级。构建检查核对的是当前网站 APK，不能证明它与所有历史渠道一致。没有更新功能的旧版本无法被追溯补上提醒，首次升级需人工通知。

## 验证

```sh
npm run test:updates
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'app.spektralab.update.*' \
  --tests 'app.spektralab.ui.Update*Test' \
  --tests 'app.spektralab.ui.SettingsScreenTest' \
  --tests 'app.spektralab.ui.SettingsNavigationTest'
```

发布测试覆盖原生包身份/版本、摘要绑定、缺失或中断的发布文件、签名和版本升级限制、网站与 feed 的版本/摘要一致性、原生源码过期拒绝和网站文案变更豁免。Android 测试覆盖时间限制、重启后稍后提醒、手动重试、请求合并、版本和系统兼容性、错误数据、HTTP 错误与大小限制，以及设置入口、弹窗动作、浏览器 Intent 和三语文案。`UpdateFlowTest` 会在 `app/build/update-snapshots/` 输出测试界面截图。
