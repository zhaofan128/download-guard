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
FILES=("DownloadGuard-Desktop-$VERSION.jar")
APK_COUNT=0
for f in DownloadGuard-"$VERSION"-*.apk; do
  FILES+=("$f")
  APK_COUNT=$((APK_COUNT + 1))
done

if [ ! -f "${FILES[0]}" ]; then
  echo "找不到 ${FILES[0]}" >&2
  exit 1
fi

# jar 里的 Implementation-Version 必须和文件名一致，否则说明 dist 里躺的是旧包
JAR_VERSION="$(unzip -p "${FILES[0]}" META-INF/MANIFEST.MF | tr -d '\r' \
  | awk -F': ' '/^Implementation-Version/{print $2}')"
if [ "$JAR_VERSION" != "$VERSION" ]; then
  echo "${FILES[0]} 里的版本是 '${JAR_VERSION}'，与 $VERSION 不符" >&2
  exit 1
fi

sha256sum "${FILES[@]}" > CHECKSUMS.txt

if [ "$APK_COUNT" -eq 0 ]; then
  echo "注意：dist 下没有 DownloadGuard-$VERSION-*.apk，CHECKSUMS.txt 目前只含桌面版 jar。" >&2
  echo "      Android 包要用正式 keystore 出（./gradlew :app:assembleRelease），" >&2
  echo "      再改名为 DownloadGuard-$VERSION-arm64.apk / DownloadGuard-$VERSION-universal.apk" >&2
  echo "      放进 dist 后重跑本脚本，否则这个 Release 的校验清单是不完整的。" >&2
fi

echo "已生成 dist/CHECKSUMS.txt（$VERSION，$((APK_COUNT + 1)) 个文件）："
cat CHECKSUMS.txt
