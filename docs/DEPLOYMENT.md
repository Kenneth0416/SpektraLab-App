# 網站部署

本文件記錄 `https://silvercam.convert-md.com` 現有的部署方式，不是新的部署方案。這台機器上還有課堂站與 media hub；更新銀鹽工坊時只能替換它自己的靜態目錄，不能重啟課堂站的 Caddy，也不能改其他 DNS 記錄。

原生應用的版本號、更新 JSON 與簽名規則見 [ANDROID_UPDATES.md](ANDROID_UPDATES.md)。網站下載與應用內更新必須使用同一個 `Silvercam-Android.apk`。

## 伺服器

| 項目 | 值 |
| --- | --- |
| 主機 | `root@85.189.44.184`（舊 IP 為 `207.57.120.204`） |
| SSH | `~/.ssh/id_ed25519`，使用 `IdentitiesOnly=yes` |
| DNS | Cloudflare 區域 `convert-md.com`，A 記錄 `silvercam` → `85.189.44.184`，灰雲（DNS only） |
| 網站容器 | `silvercam-web`，映像 `nginx:alpine`，`--restart unless-stopped` |
| 公開入口 | 現有容器 `ai-live-classroom-caddy-1` 的 80/443 |

Cloudflare 橙色雲代理不能連到這台來源站，會回傳 522。這條記錄必須維持灰雲，由來源站自己提供 HTTPS。

## 目錄與掛載

網站容器的掛載：

- `/opt/silvercam-web/html` → `/usr/share/nginx/html`
- `/opt/silvercam-web/nginx.conf` → `/etc/nginx/conf.d/default.conf`

主機的 18090 埠口映射到容器的 80，但外部防火牆沒有開放這個埠口。公開訪問不走這條路。

Caddy 與 `silvercam-web` 都連接到 Docker 網路 `ai-live-classroom_default`。實際載入的設定在容器內的 `/tmp/silvercam.caddy`，因為掛載的 Caddyfile 是唯讀的：

```caddyfile
silvercam.convert-md.com {
	tls /data/silvercam/le-cert.pem /data/silvercam/le-key.pem
	encode gzip
	reverse_proxy silvercam-web:80
}
```

憑證是 Let's Encrypt 簽發的 `silvercam.convert-md.com` 單一主機憑證，放在 Caddy 資料卷的 `/data/silvercam/le-cert.pem` 與 `/data/silvercam/le-key.pem`。同一個目錄中的 `cert.pem` 與 `key.pem` 是舊的 Cloudflare Origin CA，瀏覽器不信任，不能拿來替換。

`/opt/ai-live-classroom/backend/deploy/compose/Caddyfile` 也有同一個銀鹽工坊區塊，作為持久備份。只重新載入這個唯讀檔不會保留目前容器內已經生效的其他路由；靜態更新不需要重載 Caddy。

## 更新靜態網站

先按 [ANDROID_UPDATES.md](ANDROID_UPDATES.md) 完成原生 APK 與更新檔的構建與校驗，然後在要發布的 worktree 執行：

```sh
npm ci
npm run android:build
npm run android:verify
npm run build
```

發布前先在伺服器留下目前目錄：

```sh
ssh -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes root@85.189.44.184 \
  'cp -a /opt/silvercam-web/html /opt/silvercam-web/html-before-$(date -u +%Y%m%dT%H%M%SZ)'
```

先同步資產、APK 與對應來源，保留現有更新 JSON；確認遠端 APK 與來源檔雜湊符合本次發布資料後，最後才上傳 JSON：

```sh
rsync -az --delete --delay-updates --exclude '/updates/android.json' --timeout=180 \
  -e "ssh -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes" \
  dist/ root@85.189.44.184:/opt/silvercam-web/html/

# 核對遠端 APK、來源 ZIP 的 SHA-256 後才執行：
rsync -az --timeout=180 \
  -e "ssh -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes" \
  dist/updates/android.json root@85.189.44.184:/opt/silvercam-web/html/updates/android.json
```

這是逐檔部署，不是跨檔案的原子切換；`--delay-updates` 只縮短替換窗口。既有可變檔名 APK 與舊 JSON 之間仍可能短暫雜湊不一致，應安排維護窗口並立即完成最後一步。不可在資產尚未傳完時先發布新 JSON。

`--delete` 會移除新 `dist/` 中不存在的舊檔，包括舊的哈希資產與舊 APK。靜態更新不需要重建容器，也不需要重載 Caddy。若替換的是目錄本身而不是目錄內容，Docker 的掛載仍指向舊 inode，這時才需要只重啟 `silvercam-web`：

```sh
docker restart silvercam-web
```

不要重啟 `ai-live-classroom-caddy-1`。

## Nginx 緩存

目前的 `/opt/silvercam-web/nginx.conf`：

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;
    location /assets/ {
        try_files $uri =404;
        add_header Cache-Control "public, max-age=31536000, immutable";
    }
    location = /updates/android.json {
        try_files $uri =404;
        add_header Cache-Control "no-store, max-age=0" always;
    }
    location /downloads/ {
        try_files $uri =404;
        add_header Cache-Control "no-cache" always;
    }
    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache";
    }
}
```

Vite 生成的 `/assets/index-<hash>` 等檔名含內容哈希，可以長期緩存；但 `public/assets/` 也會複製到同一路徑，其中固定名稱的圖片並無內容哈希，若修改它們，應同步版本化檔名或調整對應緩存規則。更新 JSON、APK 與 HTML 不能長期緩存，否則應用會看到舊版本。修改 nginx 設定後，先在容器內執行 `nginx -t`，成功後再 `nginx -s reload`；不要為了這個設定重建容器。修改前複製一份，例如 `nginx.conf.before-<release>`。

## 發布後核對

```sh
curl -fsS https://silvercam.convert-md.com/ | grep -o 'assets/index-[^"]*'
curl -fsSI https://silvercam.convert-md.com/updates/android.json
curl -fsSI https://silvercam.convert-md.com/downloads/Silvercam-Android.apk
```

三個結果都要是 HTTP 200。JSON 的 `Cache-Control` 應為 `no-store`，APK 應為 `no-cache`。下載 APK 後，它的 SHA-256 必須與 `android.json` 和這次構建產物相同。再確認：

- 證書的主機名是 `silvercam.convert-md.com`，簽發者是 Let's Encrypt。
- `ai-live-classroom-caddy-1` 的啟動時間沒有改變。
- `cn-ai-class.convert-md.com` 與 `ai-class.convert-md.com` 仍然指向原來的課堂站。

## 回退

把發布前複製的目錄換回去：

```sh
ssh -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes root@85.189.44.184 \
  'rm -rf /opt/silvercam-web/html && cp -a /opt/silvercam-web/html-before-<timestamp> /opt/silvercam-web/html && docker restart silvercam-web'
```

這裡沒有版本目錄或象徵連結。替換目錄後必須重啟 `silvercam-web`，讓掛載看到新的目錄 inode。若 nginx 設定也改過，先恢復對應的 `nginx.conf.before-<release>`，執行 `nginx -t` 後再 reload。
