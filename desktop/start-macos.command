#!/usr/bin/env bash
# macOS 双击启动。首次运行若提示"无法验证开发者"，右键 → 打开，或执行：
#   xattr -d com.apple.quarantine DownloadGuard-Desktop-3.0.0.jar
cd "$(dirname "$0")" || exit 1
JAR="DownloadGuard-Desktop-3.0.0.jar"

if ! command -v java >/dev/null 2>&1; then
  osascript -e 'display alert "需要 Java 11 或更高版本" message "请先安装 Java：brew install --cask temurin，装好后重新双击本文件。"' 2>/dev/null \
    || echo "需要 Java 11+：brew install --cask temurin"
  exit 1
fi

exec java -jar "$JAR" "$@"
