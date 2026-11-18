#!/usr/bin/env bash
# 生成 dist/CHECKSUMS.txt：构建桌面版 jar，并把 dist 下同版本的 APK 一起算进去。
#
# 用法：./scripts/release-checksums.sh
# 输出：dist/CHECKSUMS.txt（发布时和 jar/APK 一起上传）
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="$(grep -o 'VERSION = "[^"]*"' desktop/src/main/java/com/dlguard/desktop/Engine.java \
  | head -n 1 | cut -d'"' -f2)"
if [ -z "$VERSION" ]; then
  echo "读不到版本号" >&2
  exit 1
fi

(
  cd desktop
  ./build.sh
)

cd dist
rm -f CHECKSUMS.txt
shopt -s nullglob

# 清单覆盖 dist 下所有发布产物：jar、APK、扩展包、源码快照。
# 用通配而不是写死文件名，是因为资产组合每次会变（有时带 APK，有时不带），
# 写死就会漏算——漏算的资产等于没有校验。
JAR="DownloadGuard-Desktop-$VERSION.jar"
if [ ! -f "$JAR" ]; then
  echo "找不到 $JAR" >&2
  exit 1
fi

# jar 里的 Implementation-Version 必须和文件名一致，否则说明 dist 里躺的是旧包
JAR_VERSION="$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' \
  | awk -F': ' '/^Implementation-Version/{print $2}')"
if [ "$JAR_VERSION" != "$VERSION" ]; then
  echo "$JAR 里的版本是 '${JAR_VERSION}'，与 $VERSION 不符" >&2
  exit 1
fi

FILES=()
APK_COUNT=0
DEBUG_APK_COUNT=0
for f in DownloadGuard-*; do
  [ -f "$f" ] || continue
  case "$f" in
    CHECKSUMS.txt) continue ;;
    *.apk) APK_COUNT=$((APK_COUNT + 1))
           case "$f" in *-debug.apk) DEBUG_APK_COUNT=$((DEBUG_APK_COUNT + 1)) ;; esac ;;
  esac
  FILES+=("$f")
done

# 排序保证同一批产物每次生成的清单行序一致，方便 diff
IFS=$'\n' FILES=($(printf '%s\n' "${FILES[@]}" | LC_ALL=C sort)); unset IFS

sha256sum "${FILES[@]}" > CHECKSUMS.txt

if [ "$APK_COUNT" -eq 0 ]; then
  echo "注意：dist 下没有 APK，CHECKSUMS.txt 目前只含桌面版与扩展包。" >&2
  echo "      Android 包要用正式 keystore 出：./gradlew :app:assembleFullRelease（完整版）
      或 ./gradlew :app:assembleStoreRelease（商店版），" >&2
  echo "      改名成 DownloadGuard-$VERSION-arm64.apk / -universal.apk 放进 dist 后重跑本脚本。" >&2
fi

if [ "$DEBUG_APK_COUNT" -gt 0 ]; then
  echo "警告：有 $DEBUG_APK_COUNT 个 APK 是 debug 签名（文件名含 -debug）。" >&2
  echo "      debug key 每台机器不同，用户无法用正式签名包覆盖安装，只能卸载重装。" >&2
  echo "      正式发布请用维护者 keystore 出包，文件名去掉 -debug。" >&2
fi

echo "已生成 dist/CHECKSUMS.txt（$VERSION，${#FILES[@]} 个文件）："
cat CHECKSUMS.txt
