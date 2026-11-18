#!/usr/bin/env python3
"""
静态检查：主 App 与 Xposed 模块两侧的「已知宿主包名」列表必须完全一致。

  - app/src/full/java/com/dlguard/service/CapturedUrlReceiver.kt  → KNOWN_HOST_PACKAGES
    （3.4.0 起该文件属于 full 源集：商店版不接收 Xposed 回传，类不进商店包）
  - xposed/src/main/java/com/dlguard/xposed/GuardHook.kt          → TARGET_PACKAGES

确认回执按宿主包名定向发送、模块按列表拒绝转发；两侧不同步就会出现
「模块放行了宿主、主 App 却拒绝给宿主回执」的静默断裂。改列表时两处都要改，
本检查保证漏改一侧时 CI 直接红。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

FILES = {
    "主 App (KNOWN_HOST_PACKAGES)": ROOT / "app/src/full/java/com/dlguard/service/CapturedUrlReceiver.kt",
    "Xposed 模块 (TARGET_PACKAGES)": ROOT / "xposed/src/main/java/com/dlguard/xposed/GuardHook.kt",
}


def extract(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    for name in ("KNOWN_HOST_PACKAGES", "TARGET_PACKAGES"):
        m = re.search(name + r"\s*=\s*setOf\(([^)]*)\)", text)
        if m:
            return set(re.findall(r'"([a-zA-Z0-9._]+)"', m.group(1)))
    raise SystemExit(f"{path}: 没找到 KNOWN_HOST_PACKAGES/TARGET_PACKAGES 列表")


def main() -> int:
    lists = {label: extract(path) for label, path in FILES.items()}
    (app, module) = lists.values()
    ok = True
    if not app:
        print("主 App 列表为空"); ok = False
    if app - module:
        print("仅在主 App 列表里（模块会拒绝转发）:", sorted(app - module)); ok = False
    if module - app:
        print("仅在模块列表里（主 App 会拒绝回执）:", sorted(module - app)); ok = False
    print(f"宿主列表一致性: {len(app)} 个包名，{'一致' if ok else '不一致'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
