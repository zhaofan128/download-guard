# Xposed 回传链路 · 真机测试清单

3.2.0 的三处硬阻断（signature 权限、getContext() 不存在、系统下载不删）都是**静态阅读就能发现**、
但 CI 完全覆盖不到的问题。这份清单是 3.2.1 修复后的真机复核项，全部手动，无自动化。
**没有真机验证过的功能不要在 README / Release 里写成「已实测」。**

## 前置条件

- 真机 + LSPosed（或 LSPatch）已激活；目标浏览器/网盘 App 加入作用域。
- 主 App（com.dlguard）与 Xposed 模块（com.dlguard.xposed）**用同一发布密钥签名**（3.2.1 的
  Release 包默认满足；自己改代码重新签名时，两个都要用同一个 keystore）。
- 验证签名：`apksigner verify --print-certs` 两个 APK 的 certificate DN 一致（CN=DownloadGuard）。

## 必测项

| # | 场景 | 预期 | 判失败标准 |
| --- | --- | --- | --- |
| 1 | 网盘 App（走 DownloadManager 的，如系统自带浏览器、文件管理器）里点下载一个 APK | 候选列表出现该链接（来源 Xposed·DownloadManager）；主 App 日志出现「收到模块回传链接（dm）」 | 候选列表没有、或 Xposed 日志出现「拒绝非宿主转发」 |
| 2 | 同上，关闭「自动接收网盘下载」 | 候选列表有记录；系统下载器那份**照常下载**，文件不丢 | 系统下载被删/消失 |
| 3 | 同上，开启「自动接收网盘下载」 | DownloadGuard 建任务并下载；**系统下载器里那一份被删除**；不会同一文件下两遍 | 出现两份下载；或系统那份没删 |
| 4 | 同 3，但把网盘 App 从最近任务杀掉后立刻点下载（宿主进程死） | 至少不丢文件：要么两份都保留，要么模块删掉系统份且 DownloadGuard 有任务 | 系统下载消失且 DownloadGuard 也没有任务（文件凭空蒸发） |
| 5 | Chrome（自带下载器，不走 DownloadManager）里点下载 APK | 候选列表出现（来源 Xposed·WebView）；**不会自动建任务**（即使开了自动接收）；Chrome 自己那份正常下载 | 自动建任务导致同一文件下两遍 |
| 6 | 任意非目标 App 尝试向 `com.dlguard.xposed.action.RELAY` 广播（可用 `adb shell am broadcast` 模拟，包名不在名单里） | 模块日志「拒绝非宿主转发」，主 App 不出现新候选 | 候选列表被污染 |
| 7 | 点击候选列表里的记录手动建任务 | 带 Referer/UA 下载成功；Cookie 不在候选列表文件里（`captured_links.tsv` 检查无 cookie 列） | 下载 403；文件里有 Cookie 明文 |
| 8 | 下载中途断网/切飞行模式再恢复 | 任务可恢复或报中文错误（「连接中断，下载不完整，请重试」一类），不出现 `java.io.IOException: Premature EOF` 这类裸异常 | 错误文案透出英文异常名 |

## 已知边界（如实告知用户）

- Chrome 等**自带下载器**的浏览器无法从 Xposed 层取消其内部下载：自动接收只对
  **系统下载器（DownloadManager）**路径生效；浏览器内置下载只进候选列表，需要手动点。
- 宿主进程被杀时确认广播收不到，系统那份会保留（宁可下两遍不丢文件）。
- 模块 relay 是导出组件（宿主没有 signature 权限，只能这样借道）；它校验调用方 UID
  必须是名单内的宿主包，名单之外直接丢弃。

## 记录格式

每次真机验证把结果写回（表格 + 机型/ROM/LSPosed 版本），失败的场景标红并附 Xposed 日志
（LSPosed 管理器 → 日志，搜 `[DownloadGuard-Xposed]`）与主 App 的「收到模块回传链接」日志。
