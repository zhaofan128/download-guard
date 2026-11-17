# DownloadGuard Desktop

与 Android 版功能对等、代码各自独立的电脑版。**一个 jar + 三条启动脚本，Windows / macOS / Linux 通用**，不装运行时补丁、不写注册表、不依赖 Electron。

界面是本地起的网页（只监听 `127.0.0.1`），用系统自带浏览器渲染，所以：

- 字体、缩放、触控滚动全部跟随系统，Windows 上不会糊，macOS 上不会变成一块方角白板；
- 手机、平板连同一个 WiFi 就能打开同一套界面（`--lan`），**一份 UI 同时适配电脑和手机**；
- 升级只换一个 jar，不用卸载重装。

桌面版 3.2.0 与 Android 3.2.0 同版本号，功能对等但不共享代码：Android 端的 Shizuku、无障碍接管、通知栏这些系统能力在电脑上没有对应物，桌面端换成了文件夹监控式的工作流 + 浏览器直连。

---

## 一、怎么跑起来

### 前提：Java 11 或更高

```bash
java -version
```

没有的话：

| 系统 | 安装方式 |
| --- | --- |
| Windows | [Adoptium Temurin 17](https://adoptium.net/) 或微软商店搜 “Java” |
| macOS | `brew install --cask temurin` |
| Ubuntu / Debian | `sudo apt install openjdk-17-jre` |
| 其他 Linux | 发行版包管理器装 `java-17-openjdk` |

### 启动

| 系统 | 操作 |
| --- | --- |
| Windows | 双击 `start-windows.bat` |
| macOS | 双击 `start-macos.command`（首次可能要在「系统设置 → 隐私与安全性」里放行） |
| Linux | 双击或在终端执行 `./start-linux.sh` |
| 任何系统 | `java -jar DownloadGuard-Desktop-3.2.0.jar` |

启动后会自动打开浏览器。**首次必须用终端打印的完整链接（带 `?token=…`）打开**：服务端会种一个 HttpOnly、SameSite=Strict 的会话 Cookie，然后 302 到干净地址；没有令牌直接访问 `http://127.0.0.1:8765/` 会得到 403。令牌每次启动重新生成，Cookie 保留 30 天。

关掉浏览器不会退出下载，**回到终端按 `Ctrl + C` 才退出**（会先保存断点）。

### 手机 / 平板访问

```bash
java -jar DownloadGuard-Desktop-3.2.0.jar --lan
```

终端会打印一行带令牌的地址，例如：

```
手机/平板  http://192.168.1.5:8765/?token=3f9c2a...
```

手机连同一个 WiFi，浏览器打开这行地址即可。令牌是每次启动随机生成的，**没有令牌的请求一律 403**，同一局域网里的其他人打不开。

#### iPhone / iPad

Safari 打开地址后：**分享按钮 → 添加到主屏幕**。得到的图标是全屏无地址栏的独立窗口（已配好 `apple-touch-icon` 与状态栏安全区），观感和原生 App 一致。

两点要接受：
- 任务跑在电脑上，手机只是遥控器；手机关屏、退出浏览器都不影响下载。
- iOS 不允许侧载，也没有 JVM，所以 **iPhone 上不装任何东西**——这不是偷懒，是系统限制。

#### 鸿蒙

- **HarmonyOS 4.x 及更早**：兼容 Android 应用，直接装手机端 APK 即可，功能完整。
- **HarmonyOS NEXT（5.0+）**：已移除 AOSP 兼容层，APK 装不上。用华为浏览器打开上面的局域网地址，同样可以「添加到桌面」。

#### 其他手机

Android 8.0+ 装 APK；能开浏览器的设备（包括 Kindle Fire、智能电视的浏览器）都能访问桌面版，界面按 320px 到 4K 自适应。

---

## 二、能做什么

| 能力 | 说明 |
| --- | --- |
| 多连接分段下载 | 默认 8 连接，可 1–32；按文件大小自动分段，段与段之间用 `RandomAccessFile` 按偏移写，不会重叠 |
| 断点续传 | 分段进度写进 `parts/<id>.meta.json`（每 700ms）与 `tasks.json`；启动时两者合并、meta 优先，`part` 缺失或大小不符就清零重下；只续「上次退出时正在下载」的任务，**手动暂停的不会自己偷偷开工** |
| 队列与并发 | 同时下载 1–10 个任务，其余排队；完成一个自动补位；支持**全部暂停 / 全部继续** |
| 全局限速 | 所有任务共享一个令牌桶，限多少就是多少，不是每个连接各限一次 |
| HLS 点播 | `.m3u8` 主清单按码率挑最高一路，分片并发拉取后按序拼接；`AES-128` 用清单里的密钥解密；有 ffmpeg 时无转码封装成 mp4，失败保留 `.ts` |
| BT / 磁力 | 通过本机 `aria2c` 的 JSON-RPC 托管：磁力、`.torrent` 都支持，进度/速度/做种状态映射回任务列表。没装 aria2 时给一句能照做的安装提示，不假装支持 |
| 下载后校验 | 流式计算 SHA-256 + MD5；填了期望 SHA-256 就严格比对，不符自动移到 `quarantine/` |
| APK v2 验签 | 遍历 ZIP 条目核对 CRC；定位 APK Signing Block，解析 v2 signers，按规范重算 1MB 分块内容摘要，用证书公钥验签并核对证书与 signer 公钥。结果分 `verified` / `failed` / `unsupported`，**v1-only 与 v3-only 明确标「未验证」**；失败文件移隔离区。另有证书 SHA-256 指纹与主题供对照 |
| 浏览器接管 | 内置 Chrome/Edge 扩展（zip 已打进 jar，设置页一键下载）。网盘网页点下载时取消浏览器下载，把 URL + **Referer + 登录 Cookie + UA** 投递到本机，由这里分段下载。扩展只处理「浏览器自己要下的那个文件」，不做嗅探、不读页面；投递失败归还浏览器，该站点冷却 5 分钟 |
| 失败说人话 | 403 提示防盗链与 Referer，404 提示文件没了，429 提示降连接数，证书错误单独提示；不再只甩一个 `HttpStatusException` |
| 自适应界面 | 320px 手机到 4K 桌面：桌面左侧栏、平板图标栏、手机底部标签栏 + FAB；触控目标 ≥ 38px；支持深/浅色与减少动效 |

### 浏览器接管（3.2.0）

网盘直链不是「有个 URL 就能下」：它靠 Cookie 认人、靠 Referer 防盗链、几分钟就过期。这三样只有浏览器在「点下载」的那一刻同时拿得到，所以接管必须在浏览器侧做。

1. 设置 → 浏览器接管，记下 8 位配对码，点「下载浏览器扩展」；
2. 解压到固定目录，`chrome://extensions` 开开发者模式 → 加载已解压的扩展程序；
3. 点扩展图标填配对码。

之后在夸克/百度/阿里云盘网页里点下载，浏览器那个下载会被取消，任务直接出现在这里。任务详情会显示「来源：browser」与「已带登录 Cookie」（只显示有没有，不显示内容）。

投递协议（本机回环，端口 37650–37659）：

```
GET  /api/hook/ping   → { app:"dlguard", api:1, paired, needPair }
POST /api/hook/pair   → { code:"12345678" } → { ok, token }
POST /api/hook        → X-DG-Hook-Token + { url, fileName, size, referer, cookie, userAgent }
```

安全边界：只对这三个路径放开扩展来源（`Origin: chrome-extension://…`），其它路径仍然严格同源；投递令牌只能建任务，不能暂停/删除/改设置；Cookie 只进内存请求头，不落盘；配对失败与令牌失败共用同一套限流。

### 做不到什么

| 场景 | 现状 | 原因 |
| --- | --- | --- |
| DRM（Widevine / FairPlay） | 不支持 | 解密属于绕过版权保护 |
| HLS 直播 | 明确拒绝 | 直播清单会不断追加，按当前窗口拼出来的文件既不是完整节目也不可续传 |
| 浏览器内嵌播放器的 blob 地址 | 不支持 | 数据在页面内存里，没有可请求的 URL |
| 需要登录的站点 | 需手动填 Cookie/Referer | 桌面版不内置浏览器会话 |

---

## 三、命令行参数

```
java -jar DownloadGuard-Desktop-3.2.0.jar [选项]

  --port <端口>     指定端口，默认 8765（被占用会自动顺延）
  --lan             允许局域网设备访问（带随机令牌）
  --no-browser      启动后不打开浏览器
  --dir <目录>      默认保存目录
  --home <目录>     数据目录（任务、断点、设置），默认 ~/.dlguard-desktop
  --allow-host <名> 额外允许的 Host（逗号分隔），用自定义域名访问时才需要
  --selftest        跑内置自检（54 项），失败返回非 0
  --version         打印版本
  --help            显示帮助

命令行投递（配合正在运行的实例）：
  --add <链接>      投递一个下载任务，可重复
  --status          打印当前队列
  --wait            投递后等到任务结束，失败返回非 0
  --sha256 <值>     给投递的任务带上校验基准
  --connections <n> 投递任务的连接数（1~32）
  --quiet           只输出结果，不刷进度
```

例：给下载机用，不弹浏览器、限 9000 端口：

```bash
java -jar DownloadGuard-Desktop-3.2.0.jar --port 9000 --no-browser --dir /data/downloads
```

### 用命令行投递（不点界面）

桌面版本质上是一个本地服务，所以把它接到了命令行上——下载机、cron、CI 都不用去模拟浏览器点击：

```bash
# 投一个任务并等它下完；失败返回非 0，脚本里能直接判断
java -jar DownloadGuard-Desktop-3.2.0.jar --add https://example.com/big.iso --wait

# 看队列（状态、进度、速度、文件名）
java -jar DownloadGuard-Desktop-3.2.0.jar --status

# 带官方哈希投递，下完自动比对
java -jar DownloadGuard-Desktop-3.2.0.jar --add https://example.com/a.apk \
  --sha256 9f2c... --connections 16 --wait --quiet
```

前提是已经有一个实例在跑（`--add` 只和本机实例对话，不会自己起第二个进程抢断点）。
`--home` 要和实例一致；实例端口与访问令牌从数据目录的 `instance.port` 读取（权限 0600）。

---

## 四、文件都在哪

```
~/.dlguard-desktop/
├── settings.json        设置
├── tasks.json           任务列表（含进度快照）
├── parts/               未完成的 .part 与 .meta.json
└── quarantine/          校验不通过被隔离的文件
```

下载完成的文件在设置里的「保存目录」，默认是系统下载文件夹。

> 换机器时把 `~/.dlguard-desktop` 一起拷走，任务和断点都能接上（前提是保存目录路径一致）。

---

## 五、打包成原生安装包（可选）

仓库里带了 `package-native.sh`，在**目标系统上**执行即可用 JDK 自带的 `jpackage` 生成安装包：

```bash
./package-native.sh
```

| 在哪个系统执行 | 产物 |
| --- | --- |
| Windows | `.exe` 安装器 / `app-image` 目录 |
| macOS | `.dmg` |
| Linux | `.deb` / `.rpm` / `app-image` |

产物自带精简 JRE，用户不需要另装 Java。跨系统交叉打包不受支持，这是 `jpackage` 的限制。

---

## 六、常见问题

**浏览器打不开 / 端口被占用**
终端会显示实际端口；被占用时会自动顺延。也可以 `--port 9000` 指定。

**下载 403**
设置里填 Referer（来源页地址），必要时把 UA 换成浏览器的。有些站点还要求 Cookie，桌面版暂不支持导入 Cookie。

**磁力一直 0 B**
先看设置页「aria2c 路径」：没检测到就按提示安装；检测到了但一直没速度，多半是没人做种或磁力链接失效，界面在 6 分钟无数据后会明确提示。

**macOS 提示「无法验证开发者」**

```bash
xattr -d com.apple.quarantine DownloadGuard-Desktop-3.2.0.jar
```

**双击了两次，第二次一闪而过**
桌面版有单实例锁：检测到已有一个进程占着数据目录，会打印正在运行的地址并退出，避免两个进程同时写断点。要并行跑两个实例，给第二个加 `--home` 指向不同目录。

**界面是旧样子**
HTTP 缓存或 Service Worker 可能在作怪。强制刷新：`Ctrl/Cmd + Shift + R`。

---

## 七、测试

```bash
./build.sh                                   # 编译
java -Ddg.apk=test/fixtures/signed.apk -jar ../dist/DownloadGuard-Desktop-3.2.0.jar --selftest   # 54 项自检，含真实 APK 验签
python3 test/e2e.py                          # 99 项端到端检查（起真服务器、真下载、真杀进程）
```

`test/e2e.py` 覆盖的是单测够不着的那类问题：

| 场景 | 曾经出过的事 |
| --- | --- |
| 服务器忽略 Range，暂停再续传 | 服务器返回 200 全量，程序按断点偏移写入，**文件长度对、后半段内容全是错的** |
| 支持 Range，下载中杀进程重启 | 分段记录与实际字节位置对不上，拼出来是坏文件 |
| 中文文件名（RFC 5987） | 落地成 `download` 或 `%E4%B8%AD...` |
| 陌生 Host / 跨站 Origin 打 `/api/tasks` | 浏览器里任何页面都能往本机下载器塞任务，还能指定任意目录写入 |
| `--add` 命令行投递 | 脚本只能靠模拟浏览器点击，CI 里没法用 |
| `/api/events` 的 SSE 推送 | 前端写 `/api/events`、服务端只认 `/events`，事件流一直 404，界面其实在轮询 |

CI 里和编译、自检、启动烟雾测试一起跑，失败即红。

---

## 八、实现取舍

- **零第三方依赖**：只用 JDK 自带的 `com.sun.net.httpserver`、`HttpURLConnection`、`ZipFile`、`Cipher`。jar 约 190 KB（含图标），不会因为依赖漏洞被迫升级。
- **Java 11 字节码**：`--release 11`，Windows 7 + JRE 11 也能跑；没有用 record / 密封类这些新语法。
- **前端零构建**：一个 `index.html` + `app.css` + `app.js`，没有 npm、没有打包器，改一行刷新就生效。
- **状态推送用 SSE**：比轮询省电，比 WebSocket 简单；断线自动重连，另有 1.5 秒轮询兜底。
- **安全默认**：只监听 `127.0.0.1`；**本机模式也强制令牌**（32 字节 SecureRandom、Base64URL），首页设置 HttpOnly Cookie，状态变更还要 `X-DG-Token` 双提交；静态资源和接口走同一套鉴权。
- **保存目录白名单**：`dir` 必须在 `saveDir` 或用户显式添加的 `allowedDirs` 下，比较前解析真实路径，`..`、系统目录、盘符根目录、软链逃逸都拒绝；`/api/browse` 也只能在允许根目录内浏览。
- **CSRF 兜底**：Host / Origin / `Sec-Fetch-Site` 三重判定，服务端不响应跨源预检；POST 只接受 `application/json`（种子上传除外）；鉴权失败按 IP 限流（8 次/分钟）。
- **安全响应头**：CSP `default-src 'self'`、`X-Frame-Options: DENY`、`nosniff`、`Referrer-Policy: no-referrer`。
- **APK 验签失败**移隔离区而不是直接删；`unsupported` 只标「未验证」，不写成通过。
- **本机服务不等于只对本应用开放**：浏览器里的任何页面都能向 `127.0.0.1` 发简单请求，所以每个请求都要过 Host / Origin / `Sec-Fetch-Site` 三重判定，拦跨站写入与 DNS 重绑定；`/api/open-dir` 只接受 POST，避免被 `<img src>` 触发。

---

MIT License · 与 Android 版同一个仓库：<https://github.com/zhaofan128/download-guard>
