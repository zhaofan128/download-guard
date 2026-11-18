# APP 备案 · 信息速填表

> 这张表把备案系统会问的字段一次性列齐，照着复制即可。**提交、人脸核验、短信验证码必须你本人操作。**
> 生成时间：2026-11-18 · 对应安装包：`dist/store-submission/DownloadGuard-3.4.0-store.apk`

## 一、主体与应用

| 字段 | 值 | 说明 |
|---|---|---|
| 应用名称 | 下载守护 | 必须与软著、商店后台一致 |
| 包名 | `com.dlguard` | 三个渠道完全一致 |
| 版本 | 3.4.0（versionCode 36） | 与安装包内一致 |
| 应用分类 | 工具 > 下载 | 个人主体可选非经营性工具类 |
| 运营主体 | 待填（与身份证/营业执照一致） | 姓名或公司全称 |
| 联系邮箱 | 待填 | 与隐私政策里写的一致 |
| 隐私政策 URL | https://zhaofan128.github.io/download-guard/privacy.html | 备案后换成你自己的域名，见下 |

## 二、签名信息（备案系统必填）

**签名 MD5**（去掉冒号、小写）：

```
791fde7035931b344dbcbf281e525b89
```

**APP 公钥**（Base64，DER 编码的 RSA 公钥，4096 位）：

```
MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA4gye0Tp7bRgAeY5ixLjMd+jSlKKOsesMpLfkdV9L9xqkBlDRk3tMtP/ziW+h4J4BtJMbsDFvq9Qu/ekHBW1vtZs20FHl52DecHI4ErnrIp7kI5PljhEZa6/qDlV5QIASsPeTXCnivG7IvrjKxLSq0J6ECxEv7mmcqpWSgD/VCjdqB+mXMD/Q/sOkKNdks78hJI1G+G+U4LhUFc1oz+Ei8tV4TSSF8jMEQ+vxC8VaQyttkW7NFJlaVFIihXTLsZFazJOhsAguCbkrGjh7s6faeHTSkaCtEBUD/bO3B7oO+82jrYZjsvqlj9ChViAWlznurYaz5hymxkMexd/+nC1soyyc+eUqXbeOqBL/zaf6zVjIBOLGpWQjKktPQIQ9+d0NMUMW2E6HCzWg442Nr8KSJptT/mqF3rs2NN3PO6Bsg4ZhDUFPJpOdB3JGbxIAe7VTjjizAa2jlHEXKVkWHCcQDNYFTPm1VFCSyoOKzhgkW7CjyFNmeG0/IOP3PclgACmD9SHI6wpK1hBNnbiq9FvomaXkNVA082w/PN1CKXZy/x4X/lk9vZbmYEnXReHvrVyNgk6Sfr9lFTLboe45POfPtB+OlirxodlE56Clvj+SrUytF1Xi8mPJhF1tUtX6sWor60EkVp9XtqBLIumv94OZ2Bno67ACrv000M3ONw6PDNkCAwEAAQ==
```

> 若控制台要的是「证书」而不是「公钥」，用下面这串（DER 编码证书的 Base64，1900 字符）：

```
MIIFizCCA3OgAwIBAgIISAYMIxj+EPYwDQYJKoZIhvcNAQEMBQAwczELMAkGA1UEBhMCQ04xEDAOBgNVBAgTB0JlaWppbmcxEDAOBgNVBAcTB0JlaWppbmcxFjAUBgNVBAoTDURvd25sb2FkR3VhcmQxEDAOBgNVBAsTB1JlbGVhc2UxFjAUBgNVBAMTDURvd25sb2FkR3VhcmQwIBcNMjYxMTE3MTYyNTA2WhgPMjA1NjExMDkxNjI1MDZaMHMxCzAJBgNVBAYTAkNOMRAwDgYDVQQIEwdCZWlqaW5nMRAwDgYDVQQHEwdCZWlqaW5nMRYwFAYDVQQKEw1Eb3dubG9hZEd1YXJkMRAwDgYDVQQLEwdSZWxlYXNlMRYwFAYDVQQDEw1Eb3dubG9hZEd1YXJkMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA4gye0Tp7bRgAeY5ixLjMd+jSlKKOsesMpLfkdV9L9xqkBlDRk3tMtP/ziW+h4J4BtJMbsDFvq9Qu/ekHBW1vtZs20FHl52DecHI4ErnrIp7kI5PljhEZa6/qDlV5QIASsPeTXCnivG7IvrjKxLSq0J6ECxEv7mmcqpWSgD/VCjdqB+mXMD/Q/sOkKNdks78hJI1G+G+U4LhUFc1oz+Ei8tV4TSSF8jMEQ+vxC8VaQyttkW7NFJlaVFIihXTLsZFazJOhsAguCbkrGjh7s6faeHTSkaCtEBUD/bO3B7oO+82jrYZjsvqlj9ChViAWlznurYaz5hymxkMexd/+nC1soyyc+eUqXbeOqBL/zaf6zVjIBOLGpWQjKktPQIQ9+d0NMUMW2E6HCzWg442Nr8KSJptT/mqF3rs2NN3PO6Bsg4ZhDUFPJpOdB3JGbxIAe7VTjjizAa2jlHEXKVkWHCcQDNYFTPm1VFCSyoOKzhgkW7CjyFNmeG0/IOP3PclgACmD9SHI6wpK1hBNnbiq9FvomaXkNVA082w/PN1CKXZy/x4X/lk9vZbmYEnXReHvrVyNgk6Sfr9lFTLboe45POfPtB+OlirxodlE56Clvj+SrUytF1Xi8mPJhF1tUtX6sWor60EkVp9XtqBLIumv94OZ2Bno67ACrv000M3ONw6PDNkCAwEAAaMhMB8wHQYDVR0OBBYEFHS8PIuZNaCprlCdaeAVeL81thJ9MA0GCSqGSIb3DQEBDAUAA4ICAQCncifA8vNHf3Cp/ZU1O4neWP8PJOiIXzCCCp97+Hv7rAwTtrdKkPSWEz9n8uud4cw+bEQYAMZ6POY6gzdauJZw0qi+VH8FS8Ix6dTiWZN/Ylx0xu3ueg9cK0t47T0KJOx6FgVaaHVvmveigDHFGIiQW+zezcwICVkf4KNfU7UUIaDSf3ANsefwyx62o4MDLDoA8ip+pDu3uhpxiU6RQNx3D/Z61VaGfcpt9VnI96Sj+fj3b4eKC/KHqbjowd9Lvz5voT2QoiuVhXZZyuRfhgMK/DyZuaCxLz47pEar800+3kGJaGlpIrIZMETt+MODJubO9XqOPi7BTeqVxeO64VdppiJdJEegDy9TtERLwQwEDoy+5XcS3WOghEPCB3tUwfWMfIOEyBjaJ1ouyLe2S5Purk2bROSr63hcnlPJmXdMUGgpTrzXQ2RJfm3Rzog1bKpXYs347w9ecl8nTFfzGKuonASO0Jk2kyltbDvtVwxdOmJD3/dTCX+r5K0yROCt3kdUt8diL10atC+HvjJfMZupJrK9gd6ro3NNKzkSlGj0gUCcgDcN0K/dsmNdsMP31kpFmZj0qXuGt+kjnLk5L6VhSLp/UBaN6+mnDh8lugnfy6YUpquKUeBb2SHPdEb0B+lyW6TVSV1YUbPJdoChuYNU17YEboVBv7BIQgjY5uO+Ew==
```

**证书主体**：`CN=DownloadGuard, OU=Release, O=DownloadGuard, L=Beijing, ST=Beijing, C=CN`
**SHA-256 指纹**：`4E:D7:64:9E:51:61:4F:CB:21:3C:E5:6F:6E:01:9C:CF:1C:5D:87:D8:BB:A3:FB:2B:1A:90:B3:46:DF:CC:B6:7F`
**签名算法**：SHA384withRSA（APK Signature Scheme v2+v3）

## 三、安装包

| 字段 | 值 |
|---|---|
| 文件名 | `DownloadGuard-3.4.0-store.apk` |
| 大小 | 73,357,994 字节（约 70 MB） |
| SHA-256 | `0c291668fce280ff484f7fa66c3bb8cbb271f26ed92d10d7eba7e69d05d6bcce` |
| minSdk / targetSdk | 26 / 34 |
| 权限数 | 6（见下） |

权限清单：

```
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
android.permission.POST_NOTIFICATIONS
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_DATA_SYNC
android.permission.WAKE_LOCK
```

## 四、备案系统里「应用简介」可直接用这段

> 下载守护是一款本地运行的多线程下载工具，支持分段并发下载、断点续传、网页链接识别与按站点保存请求头参数。应用不提供任何内容源，不含账号体系、广告与统计 SDK，下载记录与文件仅保存在用户设备本地。包名 com.dlguard，版本 3.4.0。

## 五、三个容易填错的地方

1. **签名必须用最终发布的那把 keystore。** 现在这把是 `keystore/release.jks`（别名 `dlguard`）。以后换签名 = 备案信息与安装包不一致，会被要求重新备案。
2. **主体名称四处一致**：域名持有人、备案主体、软著著作权人、商店开发者账号。个人主体就都写身份证姓名。
3. **隐私政策 URL 不能是网盘链接、不能要登录、不能是 PDF 下载页。** `https://你的域名/privacy.html` 这种最稳；`github.io` 只作临时验证用，正式提交前换成已备案域名。

## 六、拿到备案号之后

1. 打开 `site/site.config.js`，把 `icp` 填成 `京ICP备xxxxxxxx号-1A`
2. 提交并推送，Pages 会自动重新发布，页脚立刻显示备案号并链接工信部
3. App 内「设置 → 关于」补一行备案号文本（需要重新构建，改 `SettingScreen` 的关于区块）
4. 商店后台的「备案号」字段填同一个号

> 网站备案号（`-1`）与 APP 备案号（`-1A`）不是同一个，别填混。
