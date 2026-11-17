# DownloadGuard 下载接管（浏览器扩展）

把浏览器里的下载交给 **DownloadGuard**：网盘网页点「下载」时，链接连同 **Referer、登录 Cookie、UA** 一起投递到本机，由 DownloadGuard 多线程下载。

## 它解决什么

网盘直链有三个特点，少一个都下不下来：

1. **要登录态**——URL 本身不带身份，靠 Cookie 认人；
2. **要 Referer**——防盗链会校验来源页；
3. **有时效**——几分钟就过期，复制到别处再下只会得到 403 或登录页。

浏览器扩展是唯一能在「下载发生的那一刻」同时拿到这三样东西的地方。所以接管必须在浏览器侧做，下载在 DownloadGuard 里做。

## 安装

1. 启动 DownloadGuard（桌面版），打开界面 → **设置 → 浏览器接管**，记下 8 位配对码。
2. 点「下载浏览器扩展」，得到一个 zip，解压到固定目录（别放回收站，Chrome 每次启动都会读它）。
3. Chrome / Edge 地址栏打开 `chrome://extensions`（Edge 是 `edge://extensions`）。
4. 打开右上角「开发者模式」→「加载已解压的扩展程序」→ 选解压出来的文件夹。
5. 点工具栏的 DownloadGuard 图标，填入配对码 → 配对。

没配对之前扩展不会接管任何下载：它探测到服务在跑，但拿不到投递令牌。

## 工作原理

```
浏览器开始下载
      │  chrome.downloads.onCreated
      ▼
扩展取消这次下载（cancel + erase）
      │  POST /api/hook  { url, referer, cookie, userAgent, size }
      ▼
DownloadGuard 127.0.0.1:37650-37659
      │  校验 X-DG-Hook-Token + Origin
      ▼
建任务 → 分段下载（带 Referer / Cookie）→ 校验 → 落盘
```

投递成功扩展角标显示 ✓；失败则**把下载还给浏览器**（重新发起原生下载），并对该站点冷却 5 分钟，避免反复抢。

## 安全设计

| 风险 | 处理 |
| --- | --- |
| 任意网页伪造请求 | 服务端只接受 `Origin: chrome-extension://…`，网页改不了自己的 Origin；且只对 `/api/hook*` 三个路径开口，其它路径仍是严格同源 |
| 任意扩展伪造投递 | 必须先在设置页拿 8 位配对码换令牌；配对失败与令牌失败共用同一套限流（第 8 次 429） |
| 令牌权限过大 | 投递令牌只能建下载任务，不能暂停/删除/改设置/读文件 |
| Cookie 落盘 | Cookie 只在内存里传给请求头，不写进 `tasks.json`、不写进候选列表 |
| 端口被别的程序占用 | 只认 `/api/hook/ping` 返回 `app: "dlguard"` 的响应，端口区间 37650-37659 |
| 误接管 | 关掉总开关、或点「归还浏览器下载」后该站点冷却 5 分钟 |

扩展本身不做资源嗅探、不读页面 DOM、不记录浏览历史——它只处理「浏览器自己要下的那个文件」。

## 文件

| 文件 | 作用 |
| --- | --- |
| `manifest.json` | MV3 清单（`downloads` / `cookies` / `storage` / `contextMenus`） |
| `background.js` | 端口探测、配对、接管、归还、冷却、右键菜单 |
| `popup.html/.css/.js` | 状态、配对、开关、最近投递、冷却站点 |
| `icons/` | 16/32/48/128 图标 |

## 与 YunX 的关系

功能形态参考了 [tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop) 的浏览器插件（IDM 式「本机回环 + 端口探测 + 令牌」思路），**代码是独立实现的**，没有复制其源文件。

YunX 使用 AGPL-3.0，DownloadGuard 使用 MIT。两者许可证不兼容，因此这里只借鉴公开的技术思路（回环 HTTP 桥、接管后挂起决策），协议字段、错误处理、安全模型、配对机制都是本项目自己的设计。特此说明，避免许可证上的含糊。

## 已知限制

- 只接管 http/https；`blob:`、`data:` 由浏览器自己处理。
- 浏览器内置下载器的「另存为」对话框弹出后再取消，偶尔会留一个 0 字节占位文件，手动删掉即可。
- 需要 Chrome/Edge 102+（MV3 Service Worker）。
- 扩展未上架商店，只能「加载已解压的扩展程序」，因此每次浏览器大版本更新后可能要重新确认一次。
