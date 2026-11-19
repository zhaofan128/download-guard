#!/usr/bin/env bash
# 一次产出桌面版两个包：
#   DownloadGuard-Desktop-<ver>-win64.zip   Windows 免安装（内置 JRE，双击 exe）
#   DownloadGuard-Desktop-<ver>.zip          跨平台（jar + 三个启动脚本，需要 Java 8+）
set -euo pipefail
cd "$(dirname "$0")"

VERSION="$(grep -oP 'VERSION = "\K[0-9.]+' src/main/java/com/dlguard/desktop/Engine.java)"
[ -n "$VERSION" ] || { echo "读不到 Engine.VERSION" >&2; exit 1; }
DIST="$(cd .. && pwd)/dist"
JAR="$DIST/DownloadGuard-Desktop-$VERSION.jar"

echo "== 编译 jar"
./build.sh

echo "== 跨平台包"
STAGE="$DIST/portable-staging/DownloadGuard-Desktop-$VERSION"
rm -rf "$DIST/portable-staging"
mkdir -p "$STAGE"
cp "$JAR" "$STAGE/"
cp start-windows.bat start-macos.command start-linux.sh "$STAGE/"
chmod +x "$STAGE/start-linux.sh" "$STAGE/start-macos.command"

cat > "$STAGE/使用说明.txt" <<EOF
下载守护 电脑版 $VERSION
Windows / macOS / Linux

一、怎么启动
  Windows   双击 start-windows.bat
  macOS     双击 start-macos.command（首次可能要在「系统设置 → 隐私与安全性」放行）
  Linux     终端执行 ./start-linux.sh
  也可以直接：java -jar DownloadGuard-Desktop-$VERSION.jar

  需要 Java 8 或更高版本（8 / 11 / 17 / 21 都可以）。没装的话：
  https://adoptium.net/
  Windows 用户如果不想装 Java，直接下载 -win64.zip 那个包，里面已经带好了。

二、启动之后
  浏览器会自动打开 http://127.0.0.1:8765/?token=...
  token 每次启动都不一样，别把带 token 的链接发给别人。
  关掉浏览器不会中断下载；在终端按 Ctrl+C 才退出（退出前会保存断点）。
  有桌面环境时托盘会有一个盾牌图标，右键可以打开界面或退出。

三、出问题了看哪里
  1. 双击 jar 只弹出「A Java Exception has occurred.」
     改用 start-windows.bat 启动，窗口里会打印 Java 版本和完整错误。
     日志：Windows 在 %USERPROFILE%\.dlguard-desktop\crash-*.log
           macOS / Linux 在 ~/.dlguard-desktop/crash-*.log
  2. 端口被占用
     java -jar DownloadGuard-Desktop-$VERSION.jar --port 9000
  3. 想用手机浏览器打开
     加 --lan 启动：java -jar DownloadGuard-Desktop-$VERSION.jar --lan
     终端会打印一个局域网链接，手机连同一个 WiFi 直接打开。

四、校验
   SHA-256 见 Release 页的 CHECKSUMS 文件。
EOF

# 记事本默认按 ANSI 打开无 BOM 的 UTF-8，中文会变成乱码；加 BOM 后 Windows 10/11 都能正确识别
python3 - "$STAGE/使用说明.txt" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
open(p, "w", encoding="utf-8-sig").write(s)
PY

python3 - "$STAGE" "$DIST/DownloadGuard-Desktop-$VERSION.zip" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
base = os.path.basename(stage.rstrip("/"))
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for dirpath, _, files in os.walk(stage):
        for name in sorted(files):
            full = os.path.join(dirpath, name)
            rel = os.path.join(base, os.path.relpath(full, stage))
            zi = zipfile.ZipInfo(rel, date_time=(2026, 11, 19, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, open(full, "rb").read())
print("zip:", out, os.path.getsize(out))
PY

echo "== Windows 免安装包"
./build-windows.sh

echo "== 校验和"
cd "$DIST"
sha256sum "DownloadGuard-Desktop-$VERSION.jar" \
          "DownloadGuard-Desktop-$VERSION.zip" \
          "DownloadGuard-Desktop-$VERSION-win64.zip" > "CHECKSUMS-$VERSION.txt"
cat "CHECKSUMS-$VERSION.txt"
