#!/usr/bin/env python3
"""桌面版端到端测试：真实 HTTP 服务 + 真实下载引擎，验证「下载完整性」这类不能靠单测覆盖的事。

只依赖 Python 3 标准库。用法：

    python3 desktop/test/e2e.py [jar 路径]

三个场景都是「以前真出过问题」的：
  1. 服务器忽略 Range（永远 200 全量）时暂停再续传 —— 曾把文件头的数据写到中间，长度对内容错
  2. 服务器支持 Range 时杀进程重启 —— 验证断点记录的字节位置可信
  3. 中文文件名与 Content-Disposition 的落地名

跑完退出码非 0 即失败，可直接进 CI。
"""
import hashlib
import http.server
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, "dist", "DownloadGuard-Desktop-3.0.0.jar")
SIZE = 24 * 1024 * 1024  # 24 MB，够跨多个 8 MB 边界，又不至于让 CI 太慢

ok_count = 0
fail_count = 0


def check(name, cond, detail=""):
    global ok_count, fail_count
    if cond:
        ok_count += 1
        print(f"  \u2713 {name}")
    else:
        fail_count += 1
        print(f"  \u2717 {name}  {detail}")


def make_payload():
    """内容是确定性伪随机，便于失败时定位偏移。"""
    out = bytearray(SIZE)
    x = 12345
    for i in range(SIZE):
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
        out[i] = (x >> 16) & 0xFF
    return bytes(out)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class RangeHandler(http.server.BaseHTTPRequestHandler):
    """正经支持 Range 的服务器。"""

    payload = b""
    filename = "range.bin"

    def disposition(self):
        """非 ASCII 名按 RFC 5987 发 filename*，ASCII 名照常发 filename。"""
        try:
            self.filename.encode("latin-1")
            return f'attachment; filename="{self.filename}"'
        except UnicodeEncodeError:
            quoted = urllib.parse.quote(self.filename, safe="")
            return f"attachment; filename=\"download.bin\"; filename*=UTF-8''{quoted}"

    def do_GET(self):
        rng = self.headers.get("Range")
        total = len(self.payload)
        if rng:
            m = re.match(r"bytes=(\d+)-(\d*)", rng)
            if m:
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else total - 1
                end = min(end, total - 1)
                body = self.payload[start:end + 1]
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Disposition", self.disposition())
                self.end_headers()
                self.wfile.write(body)
                return
        self.send_response(200)
        self.send_header("Content-Length", str(total))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Disposition", self.disposition())
        self.end_headers()
        self.wfile.write(self.payload)

    def log_message(self, *a):
        pass


class NoRangeHandler(RangeHandler):
    """故意忽略 Range 的服务器：永远 200 + 全量。"""

    def do_GET(self):
        total = len(self.payload)
        self.send_response(200)
        self.send_header("Content-Length", str(total))
        self.send_header("Content-Disposition", self.disposition())
        self.end_headers()
        self.wfile.write(self.payload)


class Server:
    def __init__(self, handler, port):
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
        self.httpd.allow_reuse_address = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *a):
        self.httpd.shutdown()


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Client:
    """跟桌面版来回通信的最小客户端。"""

    def __init__(self, port, home, save_dir):
        self.base = f"http://127.0.0.1:{port}"
        self.home = home
        self.dir = save_dir
        self.proc = None
        self.port = port

    def start(self):
        self.proc = subprocess.Popen(
            ["java", "-jar", JAR, "--no-browser", "--port", str(self.port),
             "--home", self.home, "--dir", self.dir],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        for _ in range(60):
            try:
                self.req("/api/version")
                return
            except Exception:
                time.sleep(0.4)
        raise RuntimeError("桌面版没起来")

    def kill(self):
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGKILL)
            self.proc.wait(timeout=10)
        self.proc = None

    def req(self, path, method="GET", body=None):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method,
                                   headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r, timeout=20) as resp:
            return json.load(resp)

    def settings(self, **kw):
        s = self.req("/api/state")["settings"]
        s.update(kw)
        self.req("/api/settings", "POST", s)

    def add(self, url, connections=8):
        return self.req("/api/tasks", "POST",
                        {"urls": [url], "dir": self.dir, "connections": connections})["ids"][0]

    def task(self, tid):
        for t in self.req("/api/state")["tasks"]:
            if t["id"] == tid:
                return t
        return None

    def wait_done(self, tid, timeout=180):
        t = None
        deadline = time.time() + timeout
        while time.time() < deadline:
            t = self.task(tid)
            if t and t["status"] in ("completed", "error"):
                return t
            time.sleep(1)
        return t


def scenario_no_range(tmp, payload):
    """服务器不支持 Range：暂停后恢复必须从头重下，且结果完整。"""
    print("\n[1] 服务器忽略 Range：暂停 → 恢复")
    port = free_port()
    RangeHandler.payload = payload
    NoRangeHandler.payload = payload
    NoRangeHandler.filename = "no-range.bin"

    home = os.path.join(tmp, "home1")
    out = os.path.join(tmp, "out1")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)

    with Server(NoRangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            c.settings(speedLimitKB=600, maxConcurrent=2)
            tid = c.add(f"http://127.0.0.1:{port}/no-range.bin", connections=8)
            time.sleep(4)
            t = c.task(tid)
            check("退化成单段", len(t["segments"]) == 1, f"实际 {len(t['segments'])} 段")
            check("已经开始下载", t["done"] > 0, f"done={t['done']}")

            c.req(f"/api/tasks/{tid}/pause", "POST")
            time.sleep(1.5)
            t = c.task(tid)
            paused_at = t["done"]
            check("暂停生效", t["status"] == "paused", t["status"])
            check("暂停时已下了一部分", paused_at > 0, f"done={paused_at}")

            c.req(f"/api/tasks/{tid}/resume", "POST")
            t = c.wait_done(tid)
            path = os.path.join(out, "no-range.bin")
            check("最终完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
            if os.path.exists(path):
                got = open(path, "rb").read()
                check("字节数一致", len(got) == len(payload), f"{len(got)} vs {len(payload)}")
                check("内容完全一致（不错位）", sha(got) == sha(payload),
                      "文件长度对但内容错，说明 200 全量被写到了错误偏移")
            else:
                check("文件已落地", False, path)
        finally:
            c.kill()


def scenario_range_restart(tmp, payload):
    """支持 Range：进程被杀后重启续传，结果必须完整。"""
    print("\n[2] 支持 Range：下载中杀进程 → 重启续传")
    port = free_port()
    RangeHandler.payload = payload
    RangeHandler.filename = "range.bin"

    home = os.path.join(tmp, "home2")
    out = os.path.join(tmp, "out2")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)

    with Server(RangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        c.settings(speedLimitKB=700, maxConcurrent=2)
        tid = c.add(f"http://127.0.0.1:{port}/range.bin", connections=8)
        time.sleep(5)
        t = c.task(tid)
        before = t["done"]
        check("多段并行", len(t["segments"]) == 8, f"实际 {len(t['segments'])} 段")
        check("已下载部分数据", before > 0, f"done={before}")
        c.kill()

        c2 = Client(c.port, home, out)
        c2.start()
        try:
            t = c2.task(tid)
            check("重启后识别为未完成", t["status"] in ("downloading", "queued", "paused"), t["status"])
            if t["status"] == "paused":
                c2.req(f"/api/tasks/{tid}/resume", "POST")
            t = c2.wait_done(tid)
            path = os.path.join(out, "range.bin")
            check("最终完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
            got = open(path, "rb").read() if os.path.exists(path) else b""
            check("字节数一致", len(got) == len(payload), f"{len(got)} vs {len(payload)}")
            check("内容完全一致", sha(got) == sha(payload), "续传拼出来的文件是坏的")
        finally:
            c2.kill()


def scenario_unicode_name(tmp, payload):
    """中文文件名不能变成 download / %E4%B8%AD..."""
    print("\n[3] 中文文件名")
    port = free_port()
    RangeHandler.payload = payload
    RangeHandler.filename = "中文 文件.bin"

    home = os.path.join(tmp, "home3")
    out = os.path.join(tmp, "out3")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)

    with Server(RangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            c.settings(speedLimitKB=0, maxConcurrent=2)
            tid = c.add(f"http://127.0.0.1:{port}/file.bin", connections=4)
            t = c.wait_done(tid, timeout=120)
            check("完成", t["status"] == "completed", t["status"])
            names = os.listdir(out)
            check("RFC 5987 中文名解析正确", any("中文" in n for n in names),
                  f"实际落地：{names}")
            check("没有百分号转义", not any("%" in n for n in names), f"{names}")
            got = open(os.path.join(out, names[0]), "rb").read() if names else b""
            check("内容一致", sha(got) == sha(payload), "文件内容被写坏")
        finally:
            c.kill()


def main():
    global JAR
    if len(sys.argv) > 1:
        JAR = sys.argv[1]
    if not os.path.exists(JAR):
        print(f"找不到 jar：{JAR}\n先执行 cd desktop && ./build.sh")
        return 2

    payload = make_payload()
    tmp = tempfile.mkdtemp(prefix="dg-e2e-")
    try:
        scenario_no_range(tmp, payload)
        scenario_range_restart(tmp, payload)
        scenario_unicode_name(tmp, payload)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"\n结果：{ok_count} 通过 / {fail_count} 失败")
    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
