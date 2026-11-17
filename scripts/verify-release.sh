#!/usr/bin/env bash
# 下载某个 Release 的全部资产，并按 CHECKSUMS.txt 校验 SHA-256。
#
# 用法：./scripts/verify-release.sh v3.1.1
# 依赖：gh（已登录）、sha256sum 或 shasum
set -euo pipefail

TAG="${1:-}"
if [ -z "$TAG" ]; then
  echo "用法：$0 <tag>，例如 $0 v3.1.1" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "下载 $TAG 的 Release 资产到 $TMP ..."
gh release download "$TAG" --dir "$TMP"

if [ ! -f "$TMP/CHECKSUMS.txt" ]; then
  echo "这个 Release 没有 CHECKSUMS.txt，无法校验。" >&2
  exit 1
fi

cd "$TMP"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c CHECKSUMS.txt
else
  # macOS 自带的是 shasum
  shasum -a 256 -c CHECKSUMS.txt
fi

echo "全部资产校验通过。"
