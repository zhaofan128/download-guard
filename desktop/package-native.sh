#!/usr/bin/env bash
# 在目标系统上生成带精简 JRE 的原生安装包（jpackage，JDK 14+ 自带）。
# 交叉打包不受支持：要 Windows 安装包就在 Windows 上跑，要 dmg 就在 macOS 上跑。
set -euo pipefail
cd "$(dirname "$0")"

VERSION="3.1.1"
JAR="DownloadGuard-Desktop-$VERSION.jar"
OUT="native"

if ! command -v jpackage >/dev/null 2>&1; then
  echo "没有找到 jpackage，请安装 JDK 17 或更高版本（不是 JRE）。"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

case "$(uname -s)" in
  Darwin)
    jpackage --type dmg --name DownloadGuard --app-version "$VERSION" \
      --input . --main-jar "$JAR" --main-class com.dlguard.desktop.Main \
      --java-options "-Xmx512m" --dest "$OUT"
    ;;
  Linux)
    jpackage --type app-image --name DownloadGuard --app-version "$VERSION" \
      --input . --main-jar "$JAR" --main-class com.dlguard.desktop.Main \
      --java-options "-Xmx512m" --dest "$OUT"
    if command -v dpkg-deb >/dev/null 2>&1; then
      jpackage --type deb --name DownloadGuard --app-version "$VERSION" \
        --input . --main-jar "$JAR" --main-class com.dlguard.desktop.Main \
        --java-options "-Xmx512m" --dest "$OUT" || true
    fi
    ;;
  MINGW*|MSYS*|CYGWIN*)
    jpackage --type exe --name DownloadGuard --app-version "$VERSION" \
      --input . --main-jar "$JAR" --main-class com.dlguard.desktop.Main \
      --java-options "-Xmx512m" --dest "$OUT" --win-shortcut --win-menu
    ;;
  *)
    echo "当前系统不在支持列表里，仍可直接用 java -jar 运行。"
    exit 1
    ;;
esac

echo "产物在 $OUT/"
