# 原生 Android 应用更新

适用于包名 `app.spektralab` 的原生应用。`android/` 构建的 `app.spektralab.prototype` 是另一个应用，继续使用原来的下载文件，不能覆盖升级原生应用。

## 用户体验

- 每次回到前台时判断是否需要检查；距上次尝试至少 24 小时才发起自动请求。成功和失败都会计入，避免离线时反复请求。
- 请求在后台进行，连接和读取超时均为 5 秒，响应最多 64 KiB。失败不弹窗，也不影响拍照、导入和冲印。
- 新版提示显示版本和与应用语言对应的更新说明；自动提示等待回到空闲的灯箱或设置页，不打断相机、编辑、选择、导入、冲印和其他弹层。
- 「稍后提醒」、关闭对话框或成功打开下载链接后，至少 24 小时不再自动提醒。这个时间保存在本机，重启应用仍生效。
- 设置 → 应用更新显示已安装版本、检查状态和「检查更新」。手动检查绕过时间限制；进行中的请求会合并。
- 用 Android 内部的数字 `versionCode` 比较版本；不比较 `0.9` / `0.10` 这类字符串。相同或更旧的版本不提醒。包名不匹配、非 HTTPS / 不同主机的下载地址、损坏的 JSON、设备系统不满足新版要求都不会引导安装。
- 点击「前往更新」打开浏览器下载 APK，由用户确认覆盖安装。应用不申请安装包权限，也不自行下载或安装。

## 更新源

集中配置文件：`release/android.properties`。

当前仓库没有配置官网域名，因此默认使用项目自己 GitHub 仓库的静态文件：

```
https://raw.githubusercontent.com/Kenneth0416/SpektraLab-App/main/public/updates/android.json
```

首次发布前可以把 `updateBaseUrl` 改成官网 HTTPS 基址，例如 `https://your-actual-domain`，不要带末尾 `/`。**这只是示例，不能直接作为实际地址发布。** APK 会将 `${updateBaseUrl}/updates/android.json` 编译进应用；生成的 JSON 会指向同一基址下的 `/downloads/Silvercam-Native.apk`。

已经发出去的 APK 会继续请求它当时编译进去的地址。更换域名时，旧地址必须持续提供版本 JSON，使旧客户端能下载新 APK；本客户端要求直接 HTTP 200 的 HTTPS JSON，不能用 301/302 跳转代替。使用 GitHub 默认地址时，必须把下面的发布文件提交并推送到 `main`，仅在本地构建或只部署网站不会更新 GitHub 上的内容。GitHub 的可达性取决于用户网络，正式面向大陆分发时宜先配置可达的官网地址。

## 发布一个新版

1. 修改 `release/android.properties`：递增 `versionCode`（例如 2 → 3），更新 `versionName`（例如 0.1.1 → 0.1.2）。不要在原来的版本号下替换已发布 APK。
2. 更新 `release/android-notes.json` 的 `en`、`zh-Hans`、`zh-Hant` 三份更新说明。
3. 使用原来的签名密钥构建：

   ```sh
   npm ci
   npm run android:build:native
   npm run build
   ```

4. 一起发布下列文件（网站构建已将它们包含在 `dist/` 中）：

   - `public/downloads/Silvercam-Native.apk`
   - `public/downloads/Silvercam-Native-source.zip`
   - `public/downloads/Silvercam-Native.provenance.json`
   - `public/updates/android.json`

   网站应使用原子部署；逐文件上传时先传 APK 和对应源码，最后传更新 JSON。`public/_headers` 为版本 JSON 设置禁止长期缓存；其他托管平台应配置同等缓存策略，并让静态 JSON 路径优先于 SPA 回退。

5. 发布后检查版本 JSON 能返回 JSON（不是 SPA 的 HTML），APK 链接能下载；在保留旧安装数据的设备上打开应用，或者点设置里的「检查更新」。无需推送服务器。

构建脚本读取实际 APK 的包名、版本、最低系统要求和签名证书，并与发布配置比较。发布元数据绑定 APK 和对应源码 ZIP 的 SHA-256。后续只构建网站不会把尚未构建的版本配置或更新说明误报给用户。已有原生包时，签名变化、版本倒退、同版本替换不同 APK 都会使构建失败。源码在 Android 构建过程中发生变化也会拒绝替换下载文件。

当前项目的 release 构建仍使用原有 debug 签名配置；此变更没有更换密钥。首次正式发行前应确定长期保管的签名密钥；已经分发过的原生 APK 必须沿用对应签名才能保留数据覆盖升级。打包检查能核对仓库里的上一个原生 APK，不能证明它与所有历史渠道分发的 APK 相同。首次安装本次新功能仍需人工通知旧用户；没有更新功能的历史 APK 无法被追溯补上提醒。

## 验证

```sh
npm run test:updates
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'app.spektralab.update.*' \
  --tests 'app.spektralab.ui.Update*Test' \
  --tests 'app.spektralab.ui.SettingsScreenTest' \
  --tests 'app.spektralab.ui.SettingsNavigationTest'
```

单元测试覆盖时间限制、重启后稍后提醒、手动重试、请求合并、版本比较、系统兼容性、错误包名、错误数据和下载地址、HTTP 错误与大小限制；Compose 测试覆盖入口、检查状态、弹窗动作、浏览器打不开和三语文案。`UpdateFlowTest` 串联自动检查、真实本地偏好存储、设置页重试、更新对话框和浏览器 Intent，并在 `app/build/update-snapshots/` 输出界面截图。发布测试覆盖包名/版本一致性、摘要绑定、缺失发布文件以及签名/版本升级约束。
