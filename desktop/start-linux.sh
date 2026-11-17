#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1
JAR="DownloadGuard-Desktop-3.0.0.jar"

if ! command -v java >/dev/null 2>&1; then
  echo "需要 Java 11 或更高版本，例如：sudo apt install openjdk-17-jre"
  read -r -p "按回车退出..." _
  exit 1
fi

exec java -jar "$JAR" "$@"
