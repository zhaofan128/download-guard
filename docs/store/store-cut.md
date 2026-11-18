# 商店版裁剪说明

> 同一份代码编出两个变体，包名与签名相同，能力范围不同。
> 这不是「运行时关掉」，而是在**清单合并与源码编译阶段就分离**：商店包里不存在那些组件、权限与配置。

## 一、机制

```kotlin
productFlavors {
    create("full")  { buildConfigField("boolean", "STORE_BUILD", "false") }
    create("store") { buildConfigField("boolean", "STORE_BUILD", "true") }
}
```

三层裁剪：

| 层 | 手段 | 效果 |
|---|---|---|
| 清单 | `app/src/store/AndroidManifest.xml` 用 `tools:node="remove"` | 组件、权限、Provider 在合并阶段消失 |
| 源码 | 完整版独占类放在 `app/src/full/java/` | 商店 APK 的 dex 里根本没有这些类 |
| 资源 | 完整版专属 xml 放在 `app/src/full/res/xml/` | 商店 APK 里不会出现那份更宽的配置 |
| 运行时 | `BuildConfig.STORE_BUILD` 分支 | 少数共享类里的死路径不执行（双保险） |

## 二、商店版移除的能力

| 能力 | 完整版 | 商店版 | 移除理由 |
|---|---|---|---|
| Shizuku 特权 | 静默授权、写公共目录、开启无障碍 | 移除（含权限、Provider、meta-data、queries） | 系统提权通道，国内外商店均不接受 |
| 通知监听 | 从其他应用下载通知取链接 | 移除 | 读取其他应用通知，必拒 |
| 剪贴板后台监听 | 回前台/通知栏读取并识别链接 | 移除 | 后台读剪贴板，必拒 |
| Xposed 回传 | `CapturedUrlReceiver` + 签名权限 | 移除（类也不进包） | 与提权模块联动 |
| APK 安装 | 调起系统安装器 | 移除 `REQUEST_INSTALL_PACKAGES`，改用系统文件管理器 | 应用分发资质 |
| 全盘文件访问 | `MANAGE_EXTERNAL_STORAGE` + 目录守护 | 移除，只写应用私有目录 | 商店重点审核项 |
| 开机自启 / 精确闹钟 / 看门狗 | 服务自愈 | 移除 | 非核心用途的保活 |
| 接力网关 | 本地 HTTP 代理 | 移除 | 代理形态需额外说明 |
| 插件市场 / 脚本插件 | 在线安装、URL 重写脚本 | 移除，只保留内置能力 | 动态代码执行 |
| 跨应用抓取 | 16 个社交/网盘应用内嵌页 | 移除 | 无法向审核解释 |

## 三、商店版保留的无障碍（重点）

完整版的无障碍服务能力较宽（跨应用 + 页面扫描），商店版换成**独立实现** `StoreLinkAccessibilityService`，两条实现不共享代码：

| 维度 | 完整版 `DownloadAccessibilityService` | 商店版 `StoreLinkAccessibilityService` |
|---|---|---|
| 包名白名单 | 浏览器 + 16 个社交/网盘应用 | 只列浏览器 |
| 事件类型 | 点击、长按、窗口切换、内容变化 | 只有点击、长按 |
| 读取方式 | 点击读节点 + 页面切换时轻量扫描（2 秒节流） | 只在点击回调内读一次，上限 120 节点 |
| 配置资源 | `xml/accessibility_service_config` | `xml/accessibility_service_config_store` |
| 所在源集 | `app/src/full/`（类、清单、res 全在 full） | `app/src/main/`，仅 store 清单注册 |
| 默认状态 | 关闭 | 关闭 |

商店包里 `DownloadAccessibilityService` **既没有类，也没有清单节点，也没有配置文件**。这一点可以用下面的命令验证：

```bash
# 只应输出 StoreLinkAccessibilityService
$AAPT dump xmltree app-store-release.apk --file AndroidManifest.xml | grep -i AccessibilityService

# 只应输出 xml/accessibility_service_config_store
$AAPT dump resources app-store-release.apk | grep "xml/accessibility"

# dex 里不应有 DownloadAccessibilityService 这个类
unzip -p app-store-release.apk classes.dex | strings | grep -c DownloadAccessibilityService
```

## 四、行为差异（用户可感知）

| 场景 | 完整版 | 商店版 |
|---|---|---|
| 下载保存位置 | 公共 `Download/DownloadGuard`（需全盘权限，可改） | 应用私有 `Android/data/com.dlguard/files/Download` |
| 导出文件 | 直接在公共目录 | 通过应用内「打开 / 分享」交给系统 |
| 安装 APK | 应用内调起安装器 | 跳系统文件管理器 |
| 目录守护 | 监听公共下载目录，自动校验与修复 | 无 |
| 浏览器链接抓取 | 可选跨应用 | 仅浏览器 |
| 插件 | 可安装第三方脚本 | 只有内置能力 |

## 五、构建与验证

```bash
./gradlew :app:assembleStoreRelease   # 商店包
./gradlew :app:assembleFullRelease    # 完整版

# 单测：full 212 个，store 200 个（少了被移出源集的 12 个）
./gradlew :app:testFullDebugUnitTest
./gradlew :app:testStoreDebugUnitTest
```

`app/src/testFull/java/` 下放的是完整版独占类的测试（如 `CapturedUrlReceiverTest`），store 变体编译单测时不会包含它们——这也是「类真的不在包里」的旁证。
