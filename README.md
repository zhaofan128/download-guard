# 浏览器下载接管 · DownloadGuard

一个不需要 root 的 Android 下载工具：**把浏览器里点不动的下载按钮接过来**，用 48 连接把文件拉快，下完再逐字节校验，确认没坏才交给你。

> 很多下载站的「下载」按钮是 JS 绑定的，长按复制不到真实地址；很多浏览器下大文件只给单线程；很多 APK 下完才发现签名被改过。这三件事，它一起解决。

- Kotlin + Coroutines + Jetpack Compose（Material 3）
- 最低 Android 8.0（API 26），compileSdk / targetSdk 34
- 无需 root；Shizuku、LSPosed 均为**可选**增强，不装不影响任何核心功能
- 30 个 JVM 单元测试，覆盖分块调度器的并发一致性与真实 APK 的 v2 签名摘要重算

---

## 它解决什么问题

| 痛点 | 做法 |
| --- | --- |
| 下载站的下载按钮没有 `href`，点一下走 JS，复制链接只能复制到网页地址 | 内置 WebView 嗅探页，三路并行抓真实地址：`DownloadListener` + 导航拦截 + DOM 扫描打分 |
| 直链有防盗链，换个 UA 就 403 | 嗅探时把来源页 `Referer` 与登录态 `Cookie` 一并带给下载器 |
| 浏览器单线程下载，大文件几百 KB/s | 48 条独立 TCP + 块级调度 + 工作窃取，尾段不再掉速 |
| 下完的 APK 装不上，才知道包是坏的 | 下载完成立即做五级校验，坏了自动隔离并给出重下入口 |
| 手机厂商的「安装前安全检测」把第三方 APK 判成「已损坏」 | 标准系统安装器路径 + 权限引导；被 ROM 拦死时可选用 Shizuku 直装 |

---

## 功能

### 一、下载完整性守护

常驻前台服务盯着下载目录，文件落盘后自动走完五级校验：

1. **大小** — 与 DownloadManager / MediaStore 声明的字节数比对，短了直接判 `TRUNCATED`
2. **ZIP 结构** — 扫尾部 64KB 找 EOCD，校验中央目录偏移与长度，识别截断
3. **全条目 CRC32** — 遍历每个 entry 流式计算 CRC 与声明值比对，同时核对解压字节数
4. **APK 解析** — `PackageManager.getPackageArchiveInfo()`，`.apks/.xapk` 会先解出 base.apk
5. **签名** — APK Signature Scheme v2 分块摘要重算（1MB 分块、`0xa5` 叶子 / `0x5a` 根）+ 公钥验签 + 证书公钥一致性；`.apks` 内全部 split 的证书集合必须一致

结论只有 `OK` 和具体错误类型，**不会把「没验成」写成「通过」**。损坏文件移动到隔离区保留原文件名与时间戳，从不删除用户文件。

监控用三通道互补：`FileObserver` 递归监听 + MediaStore `ContentObserver` + 4 秒轮询，覆盖 25+ 个常见下载路径（含微信、QQ、UC、夸克、网盘客户端）。文件大小连续 3 秒无变化才判定下载结束，避免下到一半就校验。

### 二、内置加固下载器

- **48 连接**上限，块级调度而非固定分段：文件先切成比连接数更多的细块，空闲连接先领新块，领不到就去「偷」慢连接未完成区间的后半段
- **尾段不掉速**：允许拆分尚未开始的大块（256KB、6 层），解决「越下越慢最后 50KB/s」
- **断点续传**：`.part.meta` 记录每块进度，校验 `ETag` / `Last-Modified`，服务器文件变了自动从头
- **自动测速**：1/2/4/8/16/32/48 全阶梯实测，固定 48MB 同窗口字节级对比，首字节到达才开始计时，每档 0.8 秒；当前设置已经达标就不降级
- **未知长度降级**：服务器拒绝 `HEAD` 和 `Range` 时，自动退成单连接流式写入，而不是直接失败
- **fsync 策略**：大块强制落盘做可靠性锚点，尾段小块交给 4 秒全局 ticker，不为了「安全」把 eMMC 打成随机写
- **错误可读**：403 会说「链接可能过期或需要浏览器验证」，并给一个「用网页模式打开」的按钮，让 WebView 过完验证后带 Cookie 重下

### 三、网页链接嗅探

粘贴网页地址时不会盲下 HTML，而是先探测内容类型，是网页就交给嗅探页：

- `WebView.setDownloadListener` 捕获页面自己发起的下载
- `shouldOverrideUrlLoading` 拦截跳转型下载
- 注入脚本扫 `a[href]` / `data-url` / `onclick`，按扩展名与权重排序成候选列表
- 抓到链接后连同当前页 `Referer` 和 `CookieManager` 里的 Cookie 一起交给下载器

`blob:` 开头的地址抓不到，这是 WebView 的边界，会明确提示而不是假装在下载。

### 四、安装包安装（标准路径）

点校验完成的 APK，走 `FileProvider` + `ACTION_VIEW` 交给系统安装器，这是 Android 的标准做法，**不需要 root、不需要 Shizuku**。

- 未授权「安装未知应用」时弹窗引导，跳转开关页，返回 App 自动继续装
- 逐包 `grantUriPermission`，解决部分 ROM 安装器读不到 `content://` 导致的「解析包错误」
- 如果 ROM 的安装前检测把安装器拦死（报「安装包已损坏」），对话框里提供一个**可选的** Shizuku 直装入口，仅当设备上真的有 Shizuku 时出现

### 五、插件与浏览器扩展

- 插件市场支持搜索、分类、收藏、我的，市场源接 GitHub 仓库（`owner/repo` → raw 内容，`main` 失败回退 `master`）
- 插件配置用声明式 schema（`SWITCH` / `SLIDER` / `TEXT` / `CHOICE`），详情页自动渲染表单，无需为每个插件写 UI
- 局域网桥：手机开 `:8787`，24 位 token 鉴权，64KB 请求体上限，供电脑端把链接推给手机下载
- 浏览器扩展（MV3）打包进 assets，可一键导出 zip 到公共下载目录并深链唤起浏览器扩展管理页

### 六、可选增强

| 能力 | 依赖 | 不装的后果 |
| --- | --- | --- |
| 特权安装、静默授权 | Shizuku | 走系统安装器，多一次授权点击 |
| 原始 URL 回传、下载完成 hook | LSPosed 模块（独立 module，默认不构建） | 用分享入口或手动粘贴代替 |
| 无障碍链接捕获与心跳保活 | 无障碍服务 | 仅少一个入口，不影响下载与校验 |

---

## 安装

从 Releases 下载最新的 `DownloadGuard-x.y.z-release.apk`，直接安装。

首次启动按引导授予：

1. **所有文件访问权限** — 监控下载目录（Android 11+ 用 `MANAGE_EXTERNAL_STORAGE`，旁加载应用不受商店限制）
2. **通知权限** — 校验结果与常驻通知
3. **忽略电池优化**（建议）— 防止后台被系统冻结

装完把浏览器里的下载链接复制过来，或在浏览器里长按链接分享给「浏览器下载接管」。

---

## 构建

```bash
git clone <this-repo>
cd DownloadGuard
./gradlew :app:assembleDebug          # 调试包
./gradlew :app:assembleRelease        # 正式包（需要下面的签名配置）
./gradlew :app:testDebugUnitTest      # 30 个单元测试
```

环境：JDK 17+、Android SDK 34、Gradle 8.6、AGP 8.2.2、Kotlin 1.9.22。

**发布签名**：在项目根目录放 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=keystore/your-release.jks
storePassword=******
keyAlias=your-alias
keyPassword=******
```

没有这个文件时，`release` 会自动回退到 debug 签名，方便纯源码构建与本地验证。

可选构建 LSPosed 模块：`./gradlew -PenableXposed :xposed:assembleRelease`。

---

## 工程结构

```
DownloadGuard/
├── app/src/main/java/com/dlguard/
│   ├── core/        文件打开、链接嗅探、格式化、权限引导
│   ├── data/        Room 记录、设置存储、链接捕获库
│   ├── download/    ResumableDownloader、ChunkScheduler、ConcurrencyTuner、DownloadCenter
│   ├── integrity/   五级校验（EOCD / CRC32 / APK 解析 / v2 签名）
│   ├── plugin/      插件清单、配置 schema、市场源、局域网桥、扩展导出
│   ├── service/     前台守护服务、通知
│   ├── shizuku/     Shizuku 特权通道（可选）
│   ├── ui/          Compose 界面、嗅探页、液态玻璃组件
│   └── work/        WorkManager 兜底任务
├── app/src/test/    30 个 JVM 单元测试
├── xposed/          LSPosed 模块（可选，条件编译）
└── dist/            本地构建产物（不随仓库提交）
```

---

## 已知限制

- **`blob:` 链接抓不到**：媒体数据由页面 JS 生成，没有可下载的 URL
- **验证码 / 强人机校验**：WebView 可能过不去，需要回浏览器手动完成
- **一次性令牌**：带 `__cf_chl_tk` 等参数的地址几分钟就失效，403 时请回原页面重新获取
- **部分 ROM 的安装拦截**：厂商「安装前安全检测」可能拦下所有非商店 APK，需在系统设置里放行，或使用可选的 Shizuku 直装
- **`MANAGE_EXTERNAL_STORAGE`**：旁加载应用可用，但上架 Google Play 需要额外审核

---

## 合规声明

本项目只做**传输加速**与**完整性校验**：

- 不破解、不绕过任何网盘的会员限速或付费限制
- 不提供任何受版权保护内容的获取渠道
- 插件系统是中立容器，插件行为由插件作者负责
- 请只下载你有权获取的文件，并遵守当地法律与网站服务条款

---

## License

[MIT](LICENSE)
