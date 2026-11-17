#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1

# 不写死版本号：发版时只换 jar，脚本不用跟着改
JAR="$(ls -1 DownloadGuard-Desktop-*.jar ../dist/DownloadGuard-Desktop-*.jar 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$JAR" ]; then
  echo "缺少程序文件：本目录下没有 DownloadGuard-Desktop-*.jar"
  read -r -p "按回车退出..." _
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "需要 Java 11 或更高版本，例如：sudo apt install openjdk-17-jre"
  read -r -p "按回车退出..." _
  exit 1
fi

exec java -jar "$JAR" "$@"
