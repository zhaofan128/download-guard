# 提交给审核员的备注

> 商店后台一般有「备注 / 审核说明」输入框，限 500–1000 字。下面按应用分节，可整段粘贴。
> 原则：只写**可验证的事实**，不写「已测试」「保证」这类无法举证的话。

## 一、应用定位（所有商店通用）

> 本应用是一个纯本地下载工具：用户输入或分享一个文件地址，应用用多线程把文件下载到手机本地。没有账号体系、没有社交、没有支付、没有广告，也不接入任何统计或推送 SDK。全部网络请求直接发往用户提供的地址，不经过开发者服务器。

## 二、无障碍服务的必要性说明（重点）

> 本应用申请了无障碍服务「下载链接识别」，用途单一：**在浏览器里点击或长按下载链接时，读取该链接地址并加入下载候选**。
>
> 必要性：安卓浏览器复制下载链接需要「长按 → 复制 → 切回应用 → 粘贴」四步，普通用户在小屏上经常操作失败。该服务把这四步压缩成一次点击。
>
> 范围限制（可在 APK 内直接核对）：
> 1. 包名白名单只包含浏览器（Chrome、Edge、Firefox、UC、夸克、QQ 浏览器、华为/小米/OPPO 自带浏览器等），**不包含微信、QQ、钉钉、微博、抖音等任何聊天或社交应用**。
> 2. 配置文件 `res/xml/accessibility_service_config_store.xml` 中，`accessibilityEventTypes` 只有 `typeViewClicked|typeViewLongClicked`，没有页面切换与内容变化事件——系统不会把页面内容推送给本服务。
> 3. **未声明 `canPerformGestures`**，服务在系统层面不具备点击、滑动、输入能力，无法操作其他应用。
> 4. 未声明 `canRequestFilterKeyEvents`，不读取键盘输入。
> 5. 只在点击回调内读取一次节点树（上限 120 个节点），不做定时扫描。
>
> 数据流向：命中的链接只写入内存中的候选列表，用户点击「下载」后才发起网络请求；不记录浏览历史、不截图、不上传。
>
> 可关闭性：应用内设置页开关可一键停用（调用 `disableSelf()`），也可在「系统设置 → 无障碍 → 下载链接识别」中关闭。关闭后下载、嗅探、站点规则等功能均不受影响。
>
> 该服务**默认关闭**，用户不开启也能正常使用全部核心功能。

## 三、常见问询的标准答复

**Q：为什么下载器需要无障碍？是不是在监控用户？**

> 不是。服务的包名白名单只有浏览器，社交与聊天应用不在其中；事件类型只有点击与长按，没有页面内容订阅；没有 `canPerformGestures`，无法模拟操作。这三条都可以在 APK 的 manifest 与 xml 配置里直接核对。

**Q：是否会读取用户在浏览器里输入的账号密码？**

> 不会。服务只读被点击节点及其祖先节点（最多 6 层）的 `text` 与 `contentDescription`，且只提取其中匹配 `https?://` 的片段。密码输入框在无障碍树中本就是掩码字符，不构成可读文本；服务也没有订阅文本变化事件。

**Q：为什么允许 HTTP 明文？**

> 部分下载站只提供 HTTP 直链，强制 HTTPS 会让这些链接完全不可用。应用在嗅探页优先加载 HTTPS；下载地址由用户提供，应用不主动降级。

**Q：是否会下载盗版或侵权内容？**

> 应用不提供任何内容源、搜索或推荐，只负责传输用户自己提供的地址。应用内没有「破解」「会员加速」「绕过付费墙」类功能，也不内置任何站点的解析规则。

**Q：应用内为什么有「站点规则」？是否在帮助绕过风控？**

> 站点规则解决的是**正常下载被 403 拒绝**的问题：很多下载站要求请求带上正确的 Referer 与 Cookie，否则拒绝服务。规则只是把这些请求头按域名保存下来，等价于浏览器自动携带的信息，不涉及伪造身份或绕过付费。

**Q：为什么 targetSdk 是 34？**

> 当前版本在 Android 14（API 34）完成适配与回归；升级到 35 需要重新验证前台服务、通知与存储行为，计划在下一版本完成。

## 四、可自查的核验命令

审核或开发者本人可用下面几条确认上述声明：

```bash
AAPT=$(ls $ANDROID_HOME/build-tools/*/aapt2 | tail -1)
APK=app/build/outputs/apk/store/release/app-store-release.apk

# 1) 权限：只有 6 个常规权限，无 MANAGE_EXTERNAL_STORAGE / REQUEST_INSTALL_PACKAGES
$AAPT dump permissions "$APK"

# 2) 组件：只应出现 StoreLinkAccessibilityService，不含 DownloadAccessibilityService
$AAPT dump xmltree "$APK" --file AndroidManifest.xml | grep -i "AccessibilityService"

# 3) 无障碍配置：事件类型只有点击与长按，无 canPerformGestures
$AAPT dump xmltree "$APK" --file res/<配置文件名>.xml
# 配置文件名会被资源混淆，先用下一条定位
$AAPT dump resources "$APK" | grep "xml/accessibility"
```
