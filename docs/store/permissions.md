# 权限说明与审核话术

> 商店后台的「权限说明」输入框通常限 200 字以内，下面每项都给了可直接粘贴的短句。
> 审核员真正关心的是三件事：**这个权限对应哪个可见功能、什么时候触发、拒绝后还能不能用**。

## 商店版的全部权限（6 个 uses-permission + 1 个可选无障碍服务）

### 1. `android.permission.INTERNET`

- **对应功能**：下载文件、网页嗅探
- **触发时机**：用户点击「开始下载」或打开嗅探页时
- **审核话术**：
  > 用于连接用户输入的下载地址并传输文件。应用不包含任何自有服务器，请求直接发往用户指定的站点；不用于广告、统计或后台上报。

### 2. `android.permission.ACCESS_NETWORK_STATE`

- **对应功能**：「仅 Wi-Fi 下载」开关、网络切换后自动重试
- **触发时机**：下载开始前与网络变化时
- **审核话术**：
  > 读取当前网络类型，用于执行用户开启的「仅 Wi-Fi 下载」限制。不上传网络信息。

### 3. `android.permission.POST_NOTIFICATIONS`

- **对应功能**：下载进度、下载完成、失败重试通知
- **触发时机**：任务开始后；Android 13+ 首次启动时弹系统授权框
- **审核话术**：
  > 展示下载进度与完成提醒。用户拒绝后下载仍可正常进行，仅缺少通知。通知内容只包含文件名与进度，不含个人信息。

### 4. `android.permission.FOREGROUND_SERVICE`

- **对应功能**：退到后台继续下载
- **触发时机**：下载任务运行期间
- **审核话术**：
  > 下载是用户主动发起的长时间任务，使用前台服务避免切到后台后被系统冻结。前台通知可见且可暂停/取消。

### 5. `android.permission.FOREGROUND_SERVICE_DATA_SYNC`

- **对应功能**：同上，Android 14+ 必须声明具体类型
- **触发时机**：同上
- **审核话术**：
  > 声明前台服务的类型为数据传输（dataSync），与下载功能一致。未声明会导致 Android 14+ 启动前台服务时崩溃。

### 6. `android.permission.WAKE_LOCK`

- **对应功能**：下载收尾阶段（整文件哈希校验、写盘）防止设备休眠中断
- **触发时机**：仅在任务收尾的短时间窗口持有局部唤醒锁
- **审核话术**：
  > 大文件下载到 100% 后需要计算哈希并落盘，短暂持有局部唤醒锁避免休眠导致文件损坏。不用于常驻保活。

### 7. 无障碍服务 `StoreLinkAccessibilityService`（可选，默认关闭）

这一项不是 `uses-permission`，而是服务的绑定权限 `android.permission.BIND_ACCESSIBILITY_SERVICE` 写在 `<service>` 上。审核查权限表看不到它，需要看组件表。

- **对应功能**：在浏览器里点击或长按下载链接时自动加入下载候选
- **触发时机**：仅当用户在本应用设置页手动开启，且在**浏览器**中点击/长按链接时
- **默认状态**：关闭。不开也能正常下载（手动粘贴、系统分享都不受影响）
- **审核话术**（可直接粘贴）：
  > 本应用是下载工具，核心动作是把一个 URL 变成文件。安卓浏览器里复制下载链接需要「长按 → 复制 → 切回应用 → 粘贴」四步，普通用户经常失败。本服务只做一件事：用户点击或长按下载链接时，读取那一条链接的地址，省掉复制粘贴。范围严格限定在浏览器应用内，不包含任何聊天或社交应用；只订阅点击与长按事件，不订阅页面切换与内容变化，不会持续读取屏幕；不模拟点击、不读取键盘输入、不记录浏览历史、不上传任何内容。用户可随时在应用内或系统设置中关闭。

**与完整版同名能力的区别**（审核常问）：

| 维度 | 完整版 | 商店版 |
|---|---|---|
| 包名白名单 | 浏览器 + 16 个含内嵌 WebView 的社交/网盘应用 | 只列浏览器 |
| 事件类型 | 点击、长按、页面切换、内容变化 | 只剩点击、长按 |
| 读取方式 | 点击时读节点 + 页面切换时轻量扫描 | 只在点击回调内读一次，最多 120 个节点 |
| 配置位置 | `xml/accessibility_service_config` | `xml/accessibility_service_config_store`（商店 APK 内不含前者） |

**配置层的自证**（审核员可直接核对 APK 内的 xml）：

```
accessibilityEventTypes = typeViewClicked|typeViewLongClicked
accessibilityFlags      = flagReportViewIds
canRetrieveWindowContent = true
（未声明 canPerformGestures / canRequestFilterKeyEvents）
```

`canPerformGestures` 缺失意味着服务在系统层面就不具备点击、滑动、输入的能力——这一点比任何文字说明都硬。

## 商店版明确不申请的权限

审核如果对照完整版文档发现差异，用下面这段解释：

> 商店版为上架合规做了功能裁剪：移除 Shizuku 特权、通知监听、剪贴板监听、APK 安装、全盘文件访问、开机自启与本地代理网关。无障碍服务保留但改成最小实现——只对浏览器生效、只订阅点击事件、不具备模拟操作能力，详见上一节。完整版通过 GitHub 单独分发，两者包名相同但功能范围不同。

| 权限 | 完整版用途 | 商店版处理 |
|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | 监听公共 Download 目录、直接写入公共目录 | 移除；改存应用私有目录，经系统分享导出 |
| `REQUEST_INSTALL_PACKAGES` | 下载完成后调起系统安装器 | 移除；不提供安装入口 |
| `BIND_ACCESSIBILITY_SERVICE` | 浏览器 + 社交应用内嵌页，含页面扫描 | 保留最小实现：仅浏览器、仅点击事件；完整版配置不进商店包 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 从其他应用下载通知里取链接 | 移除 |
| `moe.shizuku.manager.permission.API_V23` | 免手点授权、静默开启无障碍 | 移除（含 Provider 与 meta-data） |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复守护服务 | 移除 |
| `SCHEDULE_EXACT_ALARM` | 看门狗定时自愈 | 移除 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 目录守护服务 | 移除；改为 `dataSync` |

## 常见驳回点与应对

**Q：为什么允许 HTTP 明文流量？**
> 大量下载站只提供 HTTP 直链，强制 HTTPS 会导致这些链接完全不可用。应用在嗅探页优先加载 HTTPS；下载地址由用户提供，应用不主动降级。可在后续版本增加「仅允许 HTTPS」开关。

**Q：为什么 targetSdk 是 34 而不是最新？**
> 当前版本在 Android 14（API 34）上完成适配与测试；升级 targetSdk 需要重新回归前台服务、通知与存储行为，计划在下一个版本完成。若商店强制要求 35，请先提审前告知，我需要单独做适配构建。

**Q：应用是否会读取其他应用的文件？**
> 不会。商店版只写入自己的应用私有目录 `Android/data/com.dlguard/files/Download`，不申请任何外部存储读取权限。

**Q：是否有用户生成内容、社交、支付？**
> 没有。应用是单机工具，无账号、无评论、无支付、无广告。

**Q：为什么需要前台服务？**
> 下载是用户主动发起的持续任务。前台服务用于在切到后台时继续传输，通知栏可见，用户可随时暂停或取消。

## 提交前命令核验

```bash
AAPT=$(ls $ANDROID_HOME/build-tools/*/aapt2 | tail -1)
APK=app/build/outputs/apk/store/release/app-store-release.apk

# 权限清单：应只有 6 个
$AAPT dump permissions "$APK"

# 敏感组件：只应命中 StoreLinkAccessibilityService 一处
$AAPT dump xmltree "$APK" --file AndroidManifest.xml \
  | grep -iE "shizuku|accessibility|notificationlistener|clipboard|install_packages|MANAGE_EXTERNAL|relay"

# 无障碍配置：商店包只应有 _store 这一份，且事件类型只有点击
$AAPT dump resources "$APK" | grep "xml/accessibility"
# 期望输出：resource 0x... xml/accessibility_service_config_store

# 签名
$(ls $ANDROID_HOME/build-tools/*/apksigner | tail -1) verify --print-certs "$APK"
```
