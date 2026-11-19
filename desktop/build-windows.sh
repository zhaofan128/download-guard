#!/usr/bin/env bash
# 打 Windows 免安装版：DownloadGuard.exe + 内置 JRE 8 + jar。
#
# 为什么不在 Windows 上打：launch4j 自带 PE 头模板和交叉链接器，Linux 上就能生成
# 真正的 .exe（不是自解压壳）。运行机器不需要装 Java，双击 exe 直接起。
#
# 依赖（只需一次）：
#   Windows JRE 8：https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jre/x64/windows/
#   Launch4j 3.50：https://sourceforge.net/projects/launch4j/files/launch4j-3/3.50/
#
# 用法：
#   JRE_ZIP=~/.cache/dlguard/jre8-win64.zip L4J_DIR=/opt/launch4j ./build-windows.sh
set -euo pipefail
cd "$(dirname "$0")"

VERSION="$(grep -oP 'VERSION = "\K[0-9.]+' src/main/java/com/dlguard/desktop/Engine.java)"
[ -n "$VERSION" ] || { echo "读不到 Engine.VERSION" >&2; exit 1; }

DIST="$(cd .. && pwd)/dist"
JAR="$DIST/DownloadGuard-Desktop-$VERSION.jar"
[ -f "$JAR" ] || { echo "先跑 ./build.sh 生成 $JAR" >&2; exit 1; }

JRE_ZIP="${JRE_ZIP:-$HOME/.cache/dlguard/jre8-win64.zip}"
L4J_DIR="${L4J_DIR:-/opt/launch4j}"
if [ ! -f "$JRE_ZIP" ]; then
  cat >&2 <<EOF
缺少 Windows JRE 8 压缩包：$JRE_ZIP

  mkdir -p "\$(dirname "$JRE_ZIP")"
  curl -L -o "$JRE_ZIP" \\
    https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jre/x64/windows/OpenJDK8U-jre_x64_windows_hotspot_8u512b09.zip
EOF
  exit 1
fi
[ -x "$L4J_DIR/launch4j" ] || { echo "缺少 Launch4j：$L4J_DIR/launch4j" >&2; exit 1; }

STAGE="$DIST/win-staging/DownloadGuard-$VERSION"
APP="$STAGE/app"
RUNTIME="$STAGE/runtime"
rm -rf "$DIST/win-staging"
mkdir -p "$APP" "$RUNTIME"

echo "== 解出内置 JRE"
unzip -q "$JRE_ZIP" -d "$DIST/win-staging/jre-tmp"
SRC_JRE="$(find "$DIST/win-staging/jre-tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
cp -a "$SRC_JRE"/. "$RUNTIME"/
rm -rf "$DIST/win-staging/jre-tmp"
# 源码包里的说明文档对运行没用，留着只占空间
rm -rf "$RUNTIME/legal" "$RUNTIME/demo" "$RUNTIME/sample" 2>/dev/null || true

echo "== 放 jar"
cp "$JAR" "$APP/"

echo "== 生成 ico"
python3 - "$STAGE/icon.ico" <<'PY'
import sys
from PIL import Image
src = "src/main/resources/web/icon-512.png"
im = Image.open(src).convert("RGBA")
im.save(sys.argv[1], sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("  icon.ico", im.size)
PY

echo "== 写 Launch4j 配置"
cat > "$DIST/win-staging/l4j.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <dontWrapJar>true</dontWrapJar>
  <headerType>gui</headerType>
  <jar>app\\DownloadGuard-Desktop-$VERSION.jar</jar>
  <outfile>$STAGE/DownloadGuard.exe</outfile>
  <errTitle>下载守护</errTitle>
  <cmdLine></cmdLine>
  <chdir>.</chdir>
  <priority>normal</priority>
  <downloadUrl>https://adoptium.net/</downloadUrl>
  <supportUrl>https://github.com/zhaofan128/download-guard/issues</supportUrl>
  <stayAlive>false</stayAlive>
  <restartOnCrash>false</restartOnCrash>
  <icon>$STAGE/icon.ico</icon>
  <classPath>
    <mainClass>com.dlguard.desktop.Main</mainClass>
  </classPath>
  <jre>
    <path>runtime</path>
    <bundledJre64Bit>true</bundledJre64Bit>
    <bundledJreAsFallback>false</bundledJreAsFallback>
    <minVersion>1.8.0</minVersion>
    <jdkPreference>preferJre</jdkPreference>
    <runtimeBits>64</runtimeBits>
  </jre>
  <versionInfo>
    <fileVersion>$VERSION.0</fileVersion>
    <txtFileVersion>$VERSION</txtFileVersion>
    <fileDescription>下载守护 电脑版</fileDescription>
    <copyright>Copyright (c) DownloadGuard</copyright>
    <productVersion>$VERSION.0</productVersion>
    <txtProductVersion>$VERSION</txtProductVersion>
    <productName>下载守护</productName>
    <companyName>DownloadGuard</companyName>
    <internalName>DownloadGuard</internalName>
    <originalFilename>DownloadGuard.exe</originalFilename>
  </versionInfo>
</launch4jConfig>
EOF

echo "== 生成 exe"
"$L4J_DIR/launch4j" "$DIST/win-staging/l4j.xml"
rm -f "$STAGE/icon.ico"
file "$STAGE/DownloadGuard.exe"

echo "== 使用说明"
cat > "$STAGE/使用说明.txt" <<'EOF'
下载守护 电脑版（Windows 64 位，免安装）

一、怎么启动
  双击 DownloadGuard.exe。浏览器会自动打开界面。
  不用装 Java：runtime 目录里已经带了完整的 Java 8 运行环境。

二、为什么没有黑窗口
  exe 是图形程序，启动后不弹控制台。程序在托盘里（任务栏右下角，盾牌图标），
  右键托盘图标可以「打开下载界面」或「退出（保存断点）」。

三、目录说明
  DownloadGuard.exe   启动器
  app\                程序本体（jar）
  runtime\            内置 Java 运行环境，不要删

  整个文件夹可以放到任意位置（D:\Tools\DownloadGuard 之类），
  但不要只把 exe 单独拷走，它依赖同目录的 app 和 runtime。

四、出问题了看哪里
  1. 双击没反应 / 提示缺 DLL
     先确认是 64 位 Windows；32 位系统跑不了这个包。
  2. 端口被占用（默认 8765）
     开始菜单 → 运行 → cmd，执行：
       DownloadGuard.exe --port 9000
     或改设置里的端口后重启。
  3. 想看详细日志
     在 cmd 里执行 DownloadGuard.exe，会以前台方式运行并打印全部输出。
     崩溃日志在 %USERPROFILE%\.dlguard-desktop\crash-*.log。

五、卸载
  退出托盘图标，删掉整个文件夹即可。
  下载记录在 %USERPROFILE%\.dlguard-desktop，要一并清除就删这个目录。
EOF

# 记事本默认按 ANSI 打开无 BOM 的 UTF-8，中文会变成乱码；加 BOM 后 Windows 10/11 都能正确识别
python3 - "$STAGE/使用说明.txt" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
open(p, "w", encoding="utf-8-sig").write(s)
PY

echo "== 打包"
python3 - "$STAGE" "$DIST/DownloadGuard-Desktop-$VERSION-win64.zip" <<'PY'
import os, sys, zipfile, hashlib
stage, out = sys.argv[1], sys.argv[2]
root = os.path.dirname(stage.rstrip("/"))
base = os.path.basename(stage.rstrip("/"))
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for dirpath, _, files in os.walk(stage):
        for name in sorted(files):
            full = os.path.join(dirpath, name)
            rel = os.path.join(base, os.path.relpath(full, stage))
            zi = zipfile.ZipInfo(rel, date_time=(2026, 11, 19, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (0o644 if not name.endswith(".exe") else 0o755) << 16
            z.writestr(zi, open(full, "rb").read())
print("zip:", out, os.path.getsize(out))
print("sha256:", hashlib.sha256(open(out, "rb").read()).hexdigest())
PY

echo "完成：$DIST/DownloadGuard-Desktop-$VERSION-win64.zip"
