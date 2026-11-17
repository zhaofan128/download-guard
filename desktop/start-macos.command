#!/usr/bin/env bash
# macOS 双击启动。首次运行若提示"无法验证开发者"，右键 → 打开，或执行：
#   xattr -d com.apple.quarantine DownloadGuard-Desktop-*.jar
cd "$(dirname "$0")" || exit 1

# 不写死版本号：发版时只换 jar，脚本不用跟着改
JAR="$(ls -1 DownloadGuard-Desktop-*.jar ../dist/DownloadGuard-Desktop-*.jar 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$JAR" ]; then
  osascript -e 'display alert "缺少程序文件" message "本目录下没有找到 DownloadGuard-Desktop-*.jar，请把 jar 和本脚本放在同一个文件夹里。"' 2>/dev/null \
    || echo "缺少程序文件：本目录下没有 DownloadGuard-Desktop-*.jar"
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  osascript -e 'display alert "需要 Java 11 或更高版本" message "请先安装 Java：brew install --cask temurin，装好后重新双击本文件。"' 2>/dev/null \
    || echo "需要 Java 11+：brew install --cask temurin"
  exit 1
fi

exec java -jar "$JAR" "$@"
