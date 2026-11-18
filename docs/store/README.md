# 应用商店上架材料包

> 这是给「下载守护」（商店版）准备的全套提交材料。
> **代码已经完成裁剪并构建出可提交的 APK**，剩下的实名、软著、备案、上传必须由你本人完成——原因见文末「我做不了的部分」。

## 一、当前可提交的构建

| 项目 | 值 |
|---|---|
| 文件 | `app/build/outputs/apk/store/release/app-store-release.apk` |
| 包名 | `com.dlguard` |
| 版本 | 3.4.0（versionCode 36） |
| 应用名 | 下载守护 |
| 签名 | `CN=DownloadGuard, OU=Release, O=DownloadGuard`，SHA-256 `4ed7649e…cb67f`（与 GitHub 发布同一把密钥） |
| 体积 | 约 70 MB（含 arm64 / armeabi-v7a / x86 / x86_64） |
| minSdk / targetSdk | 26 / 34 |

最终 APK 的权限只有 6 个：

```
POST_NOTIFICATIONS
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
INTERNET
ACCESS_NETWORK_STATE
WAKE_LOCK
```

另有一个**可选的**无障碍服务 `StoreLinkAccessibilityService`（系统设置里显示为「下载链接识别」），默认关闭，只对浏览器生效、只订阅点击事件、不具备模拟操作能力。它不是 `uses-permission`，查权限表看不到，需要看组件表——详见 [`permissions.md`](permissions.md) 第 7 节。

已确认**不存在**：Shizuku、通知监听、剪贴板监听、MANAGE_EXTERNAL_STORAGE、REQUEST_INSTALL_PACKAGES、开机自启、精确闹钟、Xposed 回传、接力网关，以及完整版那份含社交包名与页面扫描的无障碍实现。

重新构建命令：

```bash
./gradlew :app:assembleStoreRelease
# 完整版（GitHub 分发，功能不裁剪）
./gradlew :app:assembleFullRelease
```

## 二、两条分发线

| 通道 | 包含能力 | 分发方式 | 商店审核 |
|---|---|---|---|
| `full` | Shizuku 特权、无障碍抓取（含社交应用内嵌页）、通知监听、剪贴板监听、Xposed 回传、全盘目录、接力网关 | GitHub Release / 官网 | 不上架 |
| `store` | 多线程下载、断点续传、HLS、网页嗅探、站点规则、分享入口、浏览器链接识别（最小无障碍） | 各应用商店 | 本材料包 |

两个变体共用同一包名与签名，但**不要同时安装**（包名相同会互相覆盖）。商店版覆盖安装到完整版上时，Shizuku 等能力会消失，这是预期行为。

## 三、必须你本人完成的四件事

| 事项 | 为什么我做不了 | 预估周期 | 费用 |
|---|---|---|---|
| **主体实名** | 需要身份证、人脸核验、手机验证码；个人或个体工商户/企业主体 | 1–3 个工作日 | 0 |
| **软件著作权** | 需要签章/签字、身份证明；商店（尤其个人开发者）普遍强制 | 普通 30–60 工作日，加急 5–30 工作日 | 0（自办）/ 300–1500（加急） |
| **APP 备案** | 需在接入服务商（阿里云/腾讯云/华为云）系统提交，法人或负责人人脸核验；多数省份要求企业或个体工商户主体 | 管局 10–20 个工作日 | 0 |
| **商店后台提交** | 需要开发者账号登录、短信/邮箱验证码、可能的应用认领 | 审核 1–7 个工作日 | 0（国内商店） |

**没有这三样，国内主流商店无法上架**：软著（权属证明）、APP 备案号（工信部）、隐私政策公网地址。这不是审核偏好，是硬门槛。

## 四、材料清单

| 文件 | 用途 |
|---|---|
| [`privacy-policy.md`](privacy-policy.md) | 隐私政策，需发布到公网 URL 后填入商店后台 |
| [`terms.md`](terms.md) | 用户协议，可与隐私政策同页 |
| [`listing.md`](listing.md) | 应用名称、一句话简介、详细描述、关键词、分类 |
| [`permissions.md`](permissions.md) | 6 个权限 + 无障碍服务的逐项说明与审核话术 |
| [`screenshots.md`](screenshots.md) | 截图规格与拍摄脚本（需真机补拍） |
| [`review-notes.md`](review-notes.md) | 提交给审核员的备注模板 |
| [`filing-guide.md`](filing-guide.md) | 软著、APP 备案、各商店提交的逐步指引 |
| [`filing-sheet.md`](filing-sheet.md) | 备案系统要填的包名、签名 MD5、公钥、SHA-256，已按 3.4.0 安装包生成 |
| [`../site-deploy.md`](../site-deploy.md) | 官网与隐私政策静态站的部署说明（源码在 `site/`） |
| [`store-cut.md`](store-cut.md) | 商店版裁剪了什么、为什么，以及技术验证方法 |
| `assets/` | 512 图标、圆形图标、1024×500 宣传图 |

素材重新生成：

```bash
python3 docs/store/make_assets.py
```

## 五、上线前自检

- [ ] 把 `privacy-policy.md` 里所有 `【】` 占位符替换成真实主体信息
- [ ] 隐私政策发布到公网 HTTPS 地址，浏览器能直接打开
- [ ] APK 用 `apksigner verify` 验签通过
- [ ] 真机安装后，设置页不出现 Shizuku / 剪贴板 / 接力 / 跨应用抓取入口
- [ ] 真机安装后，「浏览器链接识别（无障碍）」开关存在且默认关闭；打开先弹用途说明，确认后跳到系统无障碍页能勾选成功
- [ ] 无障碍开启后，在 Chrome 里点一个 .apk 直链能进候选；在微信里点链接**不应**有任何反应（验证范围限定生效）
- [ ] 从系统设置里关闭该服务后，回到应用设置页，开关同步变为关闭
- [ ] 真机下载一个文件，确认落在应用私有目录且能通过「打开」导出
- [ ] 商店后台填写的应用名与软著名称一致（不一致会被要求补充说明）
- [ ] 截图不含手机号、真实姓名、其他商店 Logo、版权内容

## 六、我做不了的部分（明确边界）

我完成了：代码裁剪、双通道构建、权限核验、签名核验、图标与宣传图、隐私政策/协议/文案/权限说明/审核备注/备案指引/截图脚本。

我没有也不能完成：代替你实名、签章、人脸核验、注册开发者账号、提交备案、点击商店后台的「发布」。这些环节全部绑定真实身份与法律责任，任何声称能「全程代办」的说法都不成立。
