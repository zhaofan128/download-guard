#!/usr/bin/env bash
# 构建 DownloadGuard Desktop：纯 javac + jar，不依赖 Gradle / Maven / 网络。
set -euo pipefail

cd "$(dirname "$0")"

VERSION="3.2.0"
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

# 浏览器扩展打进 jar：用户装完 jar 就能在设置页一键下载扩展，
# 不需要再去 clone 仓库。扩展本身是纯静态文件，不参与编译。
EXT_SRC="../browser-extension"
FIXED_MTIME="200001010000.00"
if [ -d "$EXT_SRC" ]; then
  rm -rf "$OUT/extension"
  mkdir -p "$OUT/extension"
  cp -R "$EXT_SRC/." "$OUT/extension/"
  # 先把文件 mtime 钉死再打 zip：zip 条目时间取的是文件 mtime，
  # 只在打包后 touch zip 本身没用，两次构建的 zip 内部时间戳还是会不同。
  find "$OUT/extension" -type f -exec touch -t "$FIXED_MTIME" {} +
  rm -f "$CLASSES/extension.zip"
  # 文件名里没有空格，用命令替换足够；条目顺序排序，保证可复现
  # --no-manifest 必须有：否则 jar 会塞一个带「当前时间」的 META-INF/MANIFEST.MF 进去，
  # 每次构建的 zip 都不同，整个 jar 的可复现性就断了（扩展也不需要 manifest）。
  ( cd "$OUT/extension" && jar --create --no-manifest --file "../../$CLASSES/extension.zip" \
      $(find . -mindepth 1 -type f | sed 's|^\./||' | LC_ALL=C sort) >/dev/null 2>&1 ) \
    || echo "警告：扩展打包失败，jar 里将不含 extension.zip" >&2
fi

mkdir -p "$CLASSES/META-INF"
cat > "$CLASSES/META-INF/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Main-Class: com.dlguard.desktop.Main
Implementation-Title: DownloadGuard Desktop
Implementation-Version: $VERSION

EOF

# 可复现构建：把 class/资源/manifest 的 mtime 统一钉死，再按字典序把条目喂给 jar。
# 只钉 mtime 不够——jar 按遍历顺序写条目，不同文件系统的 readdir 顺序不同；
# 而且用 --manifest 让 jar 自己生成 MANIFEST.MF 时，那条目会带当前时间，
# 同样的源码能打出不同字节，CHECKSUMS.txt 就失去意义。
find "$CLASSES" -exec touch -t "$FIXED_MTIME" {} +

# manifest 必须排在第一位，其余按字典序，保证跨机器条目顺序一致
JAR_ARGS=(-C "$CLASSES" META-INF/MANIFEST.MF)
while IFS= read -r entry; do
  case "$entry" in
    META-INF|META-INF/MANIFEST.MF) continue ;;
  esac
  JAR_ARGS+=(-C "$CLASSES" "$entry")
done < <(cd "$CLASSES" && find . -mindepth 1 | sed 's|^\./||' | LC_ALL=C sort)

jar --create --file "$JAR" --no-manifest "${JAR_ARGS[@]}" 2>/dev/null \
  || jar cfM "$JAR" "${JAR_ARGS[@]}"

echo "已生成：$JAR"
ls -lh "$JAR"
