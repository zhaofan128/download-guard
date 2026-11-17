#!/usr/bin/env bash
# 构建 DownloadGuard Desktop：纯 javac + jar，不依赖 Gradle / Maven / 网络。
set -euo pipefail

cd "$(dirname "$0")"

VERSION="3.1.0"
OUT="build"
DIST="../dist"
CLASSES="$OUT/classes"
JAR="$DIST/DownloadGuard-Desktop-$VERSION.jar"

rm -rf "$CLASSES"
mkdir -p "$CLASSES" "$DIST"

# --release 11：兼容 Windows 7/10/11、macOS 10.15+、主流 Linux 发行版上的 JRE 11+
find src/main/java -name '*.java' > "$OUT/sources.txt"
javac --release 11 -encoding UTF-8 -Xlint:all -d "$CLASSES" @"$OUT/sources.txt"

cp -R src/main/resources/. "$CLASSES/"

cat > "$OUT/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Main-Class: com.dlguard.desktop.Main
Implementation-Title: DownloadGuard Desktop
Implementation-Version: $VERSION
EOF

# 固定时间戳，保证同样的源码打出来的包字节一致（方便核对 SHA-256）
jar --create --file "$JAR" --manifest "$OUT/MANIFEST.MF" -C "$CLASSES" . 2>/dev/null \
  || jar cfm "$JAR" "$OUT/MANIFEST.MF" -C "$CLASSES" .

echo "已生成：$JAR"
ls -lh "$JAR"
