#!/usr/bin/env python3
"""静态检查：浏览器扩展的端口发现范围必须覆盖桌面服务的默认端口与顺延窗口。

3.2.0 的教训：扩展只扫 37650-37659，而 Settings.port 默认 8765 且 Server 从
默认端口起顺延 30 个——默认启动的服务永远连不上，而 e2e 直接用真实端口调
/api/hook/*，测不到「扩展能不能找到服务」。这条检查在 CI 与本地都可以跑，
把两个数字的来源锁在同一份代码上。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def fail(msg: str) -> None:
    print(f"端口检查失败：{msg}", file=sys.stderr)
    sys.exit(1)


def read_int(path: Path, pattern: str, what: str) -> int:
    text = path.read_text(encoding="utf-8")
    m = re.search(pattern, text)
    if not m:
        fail(f"在 {path} 里找不到 {what}（模式 {pattern}）")
    return int(m.group(1))


def main() -> None:
    start = read_int(
        ROOT / "browser-extension/background.js",
        r"PORT_START\s*=\s*(\d+)",
        "扩展端口区间起点",
    )
    end = read_int(
        ROOT / "browser-extension/background.js",
        r"PORT_END\s*=\s*(\d+)",
        "扩展端口区间终点",
    )
    default_port = read_int(
        ROOT / "desktop/src/main/java/com/dlguard/desktop/Settings.java",
        r"public int port\s*=\s*(\d+)",
        "桌面服务默认端口",
    )
    window = read_int(
        ROOT / "desktop/src/main/java/com/dlguard/desktop/Server.java",
        r"for \(int i = 0; i < (\d+); i\+\+\)",
        "桌面服务顺延窗口",
    )

    if start > end:
        fail(f"扩展端口区间非法：{start} > {end}")
    if not (start <= default_port <= end):
        fail(f"扩展扫描区间 [{start}, {end}] 没有覆盖服务默认端口 {default_port}")
    if default_port + window - 1 > end:
        fail(
            f"扩展扫描区间 [{start}, {end}] 没有覆盖服务的完整顺延窗口 "
            f"{default_port}-{default_port + window - 1}"
        )
    print(
        f"端口检查通过：扩展扫描 {start}-{end}，服务默认 {default_port}，"
        f"顺延窗口 {window} 个（{default_port}-{default_port + window - 1}）"
    )


if __name__ == "__main__":
    main()
