# 官网（静态站）

四个页面、零依赖、零外部请求：不引 CDN、不引 Google Fonts、不埋统计。
把整个 `site/` 目录拷到任何静态托管上就能跑，也可以直接用 `file://` 打开预览。

```
site/
├── index.html      产品首页
├── privacy.html    隐私政策（商店后台必填的那个 URL）
├── terms.html      用户协议
├── support.html    帮助 / FAQ / 联系方式
├── 404.html        404
├── site.config.js  ← 只改这一个文件
├── assets/
│   ├── style.css
│   ├── app.js
│   ├── icon-512.png
│   └── feature-graphic.png
├── robots.txt
└── sitemap.xml
```

## 一、要改的只有三行

打开 `site.config.js`：

| 字段 | 填什么 | 备注 |
|---|---|---|
| `operator` | 你的真实姓名，或营业执照上的公司全称 | 必须与 APP 备案、软件著作权上的主体**完全一致** |
| `email` | 能长期收信的邮箱 | 商店审核、用户投诉都用它 |
| `icp` | APP 备案号，例如 `京ICP备2025000000号-1A` | 没拿到就留空，页脚显示「办理中」 |

改完刷新页面即生效，不用构建。三项都填好后，页面顶部那条黄色提示会自动消失。

> 页脚备案号会自动变成指向 `beian.miit.gov.cn` 的链接，这是工信部对已备案网站的展示要求。

## 二、本地预览

```bash
python3 -m http.server 8000 --directory site
# 打开 http://localhost:8000
```

直接双击 `index.html` 也能看，只是相对路径和 404 页不会按服务器行为工作。

## 三、部署

### 方式 A：GitHub Pages（已经配好，推上去就发）

仓库里已有 `.github/workflows/pages.yml`，推送到 `main` 且改动 `site/**` 时自动发布：

1. 仓库 **Settings → Pages → Build and deployment → Source** 选 **GitHub Actions**
2. push 一次，等 Actions 跑完
3. 地址形如 `https://<用户名>.github.io/download-guard/`

注意：`github.io` 在国内部分网络下访问不稳定，**不适合作为备案与商店审核的正式地址**，只适合自测与给朋友看。

### 方式 B：自己的域名 + 境内服务器（备案走这条）

备案要求域名解析到境内、且服务器在该接入商名下。最省事的组合是「一台最低配轻量服务器 / 一个 OSS 静态站点 + 一个已备案域名」。

**Nginx 最小配置：**

```nginx
server {
    listen 443 ssl http2;
    server_name example.com;

    ssl_certificate     /etc/letsencrypt/live/example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/example.com/privkey.pem;

    root /var/www/downloadguard;
    index index.html;

    location / {
        try_files $uri $uri/ =404;
    }

    error_page 404 /404.html;

    # 静态资源长缓存，HTML 不缓存，改了立刻生效
    location ~* \.(css|js|png|svg|ico)$ {
        expires 7d;
        add_header Cache-Control "public";
    }
    location ~* \.html$ {
        add_header Cache-Control "no-cache";
    }
}
```

上传：

```bash
rsync -avz --delete site/ user@server:/var/www/downloadguard/
```

**对象存储（OSS / COS）：** 把 `site/` 内容传到 Bucket 根目录，开启静态网站托管，默认首页 `index.html`、默认 404 页 `404.html`，再绑自定义域名并开 HTTPS。

## 四、备案时会被问到的三件事

1. **网站备案 ≠ APP 备案。** 网站备案拿到的是 `京ICP备xxx号-1`（挂在域名上）；APP 备案拿到的是 `京ICP备xxx号-1A`（挂在应用上）。商店后台要填的是**后者**。本页脚展示哪个都行，建议两个都写。
2. **主体一致性。** 域名持有人、备案主体、软著著作权人、商店开发者账号，四者名称必须一致；个人主体就都用身份证姓名。
3. **隐私政策 URL 要能直接打开。** 不能是网盘链接、不能要登录、不能是 PDF 下载。`https://你的域名/privacy.html` 这种最稳。

## 五、发布前自检

- [ ] `site.config.js` 里三项都填了，页面顶部黄色提示消失
- [ ] 页脚能看到备案号，点进去是 `beian.miit.gov.cn`
- [ ] 四个页面在手机窄屏下没有横向滚动条
- [ ] `privacy.html` 里 6 项权限的描述与实际 APK 一致（用 `aapt dump permissions` 核对）
- [ ] `sitemap.xml` / `robots.txt` 里的域名换成你的正式域名
- [ ] HTTPS 能正常打开，证书没有过期
- [ ] 用无痕窗口打开一次，确认没有登录态依赖

## 六、换域名要改的地方

| 文件 | 改什么 |
|---|---|
| `robots.txt` | `Sitemap:` 那一行 |
| `sitemap.xml` | 四个 `<loc>` |
| `index.html` / 其他页 | `og:image` 建议改成绝对地址，分享卡片才会显示图 |

其余链接都是相对路径，换域名不用动。
