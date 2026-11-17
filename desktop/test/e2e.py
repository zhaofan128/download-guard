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
JAR = os.path.join(ROOT, "dist", "DownloadGuard-Desktop-3.1.1.jar")
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


class ChunkedHandler(http.server.BaseHTTPRequestHandler):
    """没有 Content-Length 的 chunked 响应：旧版会把 1MB 文件下成 1 字节。"""

    protocol_version = "HTTP/1.1"
    payload = b""

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for i in range(0, len(self.payload), 65536):
            chunk = self.payload[i:i + 65536]
            self.wfile.write(("%x\r\n" % len(chunk)).encode() + chunk + b"\r\n")
        self.wfile.write(b"0\r\n\r\n")

    def log_message(self, *a):
        pass


class TruncatedHandler(http.server.BaseHTTPRequestHandler):
    """声明 1MB，实际只发一半就断开：必须失败，不能 completed。"""

    protocol_version = "HTTP/1.1"
    payload = b""

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(self.payload)))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        self.wfile.write(self.payload[: len(self.payload) // 2])
        self.wfile.flush()
        self.close_connection = True

    def log_message(self, *a):
        pass


class HlsHandler(http.server.BaseHTTPRequestHandler):
    """3 个分片的 HLS 点播；playlist 由测试动态改，用于验证指纹。"""

    protocol_version = "HTTP/1.1"
    playlist = ""
    segments = []
    delay = 0.0

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if HlsHandler.delay:
            time.sleep(HlsHandler.delay)
        if path.endswith(".m3u8"):
            body = self.playlist.encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        m = re.search(r"seg(\d+)\.ts$", path)
        if m:
            idx = int(m.group(1))
            body = self.segments[idx] if idx < len(self.segments) else b""
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *a):
        pass


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
        self.token = ""

    def read_token(self):
        try:
            with open(os.path.join(self.home, "instance.port"), encoding="utf-8") as f:
                for line in f:
                    if line.startswith("token="):
                        return line.split("=", 1)[1].strip()
        except Exception:
            pass
        return ""

    def start(self):
        self.proc = subprocess.Popen(
            ["java", "-jar", JAR, "--no-browser", "--port", str(self.port),
             "--home", self.home, "--dir", self.dir],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        for _ in range(80):
            self.token = self.read_token()
            try:
                self.req("/api/version")
                return
            except Exception:
                time.sleep(0.3)
        raise RuntimeError("桌面版没起来")

    def kill(self):
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGKILL)
            self.proc.wait(timeout=10)
        self.proc = None

    def req(self, path, method="GET", body=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json", "X-DG-Token": self.token}
        r = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
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
            check("退化成单流", len(t["segments"]) <= 1, f"实际 {len(t['segments'])} 段")
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


def raw_request(port, text, timeout=10.0, max_bytes=200_000):
    """绕过 urllib 直接发原始 HTTP：需要精确控制 Host / Origin 这类头。"""
    s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    try:
        s.sendall(text.encode())
        data = b""
        while len(data) < max_bytes:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            data += chunk
        return data.decode("utf-8", "replace")
    finally:
        s.close()


def post_body(host, port, path, body, token="", extra="", content_type="application/json"):
    payload = json.dumps(body)
    token_line = f"X-DG-Token: {token}\r\n" if token else ""
    return (f"POST {path} HTTP/1.1\r\nHost: {host}\r\n"
            f"Content-Type: {content_type}\r\n{token_line}"
            f"Content-Length: {len(payload.encode())}\r\n"
            f"Connection: close\r\n{extra}\r\n{payload}")


def status_line(resp):
    return resp.split("\r\n", 1)[0]


def scenario_local_service_guard(tmp, payload):
    """本机服务不能变成「任意网页都能用的下载器 + 文件写入器」。

    浏览器对 127.0.0.1 的简单请求不触发预检，页面里的一个 <form> 就能把
    POST 打到 /api/tasks；DNS rebinding 还能让 Host 变成攻击者域名。
    这里逐条验证：令牌、Origin、Content-Type、目录白名单、限流。
    """
    print("\n[4] 本地服务鉴权 / 来源 / 目录白名单")
    port = free_port()
    RangeHandler.payload = payload
    RangeHandler.filename = "guard.bin"

    home = os.path.join(tmp, "home4")
    out = os.path.join(tmp, "out4")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)

    with Server(RangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            c.settings(speedLimitKB=256, maxConcurrent=2)
            url = f"http://127.0.0.1:{port}/guard.bin"
            tok = c.token
            check("实例写了访问令牌", len(tok) >= 32, f"len={len(tok)}")

            resp = raw_request(c.port, f"GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:{c.port}\r\n"
                                     f"Connection: close\r\n\r\n")
            check("无令牌 API 被拒绝", status_line(resp).startswith("HTTP/1.1 403"), status_line(resp))

            resp = raw_request(c.port, f"GET / HTTP/1.1\r\nHost: 127.0.0.1:{c.port}\r\n"
                                     f"Connection: close\r\n\r\n")
            check("无令牌首页被拒绝", status_line(resp).startswith("HTTP/1.1 403"), status_line(resp))
            check("拒绝页不包含令牌", tok not in resp, "令牌被写进了错误页")

            resp = raw_request(c.port, f"GET /?token={tok} HTTP/1.1\r\nHost: 127.0.0.1:{c.port}\r\n"
                                     f"Connection: close\r\n\r\n")
            check("首次带令牌打开返回 302", status_line(resp).startswith("HTTP/1.1 302"), status_line(resp))
            check("设置 HttpOnly + SameSite=Strict Cookie",
                  "HttpOnly" in resp and "SameSite=Strict" in resp, resp[:400])
            check("跳转后地址不含令牌", "Location: /\r\n" in resp, resp[:400])

            # 用 Cookie 再打开首页：token 只注入到同源页面的 meta，不进 URL
            req = urllib.request.Request(c.base + "/", headers={"Cookie": f"dg_token={tok}"})
            with urllib.request.urlopen(req, timeout=10) as r:
                page = r.read().decode("utf-8", "replace")
            check("Cookie 可打开首页", 'name="dg-token"' in page, page[:200])
            check("首页不把令牌放进 URL", f"?token={tok}" not in page, "")

            resp = raw_request(c.port, post_body(f"evil.example:{c.port}", c.port, "/api/tasks",
                                                 {"url": url, "dir": out}, tok))
            check("陌生 Host 被拒绝（DNS 重绑定）", status_line(resp).startswith("HTTP/1.1 403"),
                  status_line(resp))

            resp = raw_request(c.port, post_body(f"127.0.0.1:{c.port}", c.port, "/api/tasks",
                                                 {"url": url, "dir": out}, tok,
                                                 extra="Origin: http://evil.example\r\n"))
            check("跨站 Origin 被拒绝", status_line(resp).startswith("HTTP/1.1 403"), status_line(resp))

            resp = raw_request(c.port, post_body(f"127.0.0.1:{c.port}", c.port, "/api/tasks",
                                                 {"url": url, "dir": out}, tok,
                                                 extra="Sec-Fetch-Site: cross-site\r\n"))
            check("cross-site 请求被拒绝", status_line(resp).startswith("HTTP/1.1 403"), status_line(resp))

            resp = raw_request(c.port, post_body(f"127.0.0.1:{c.port}", c.port, "/api/tasks",
                                                 {"url": url, "dir": out}, tok,
                                                 content_type="text/plain"))
            check("text/plain 被 415 拒绝", status_line(resp).startswith("HTTP/1.1 415"), status_line(resp))

            resp = raw_request(c.port, post_body(f"127.0.0.1:{c.port}", c.port, "/api/tasks",
                                                 {"url": url, "dir": "/tmp"}, tok))
            check("白名单外目录被拒绝", status_line(resp).startswith("HTTP/1.1 400"), status_line(resp))

            resp = raw_request(c.port, post_body(f"127.0.0.1:{c.port}", c.port, "/api/tasks",
                                                 {"url": url, "dir": out + "/../"}, tok))
            check(".. 目录逃逸被拒绝", status_line(resp).startswith("HTTP/1.1 400"), status_line(resp))

            link = os.path.join(out, "escape-link")
            try:
                if not os.path.lexists(link):
                    os.symlink("/tmp", link)
                resp = raw_request(c.port, post_body(f"127.0.0.1:{c.port}", c.port, "/api/tasks",
                                                     {"url": url, "dir": link}, tok))
                check("符号链接逃逸被拒绝", status_line(resp).startswith("HTTP/1.1 400"),
                      status_line(resp))
            except OSError as e:
                print(f"  · 无法创建符号链接，跳过：{e}")

            resp = raw_request(c.port, f"GET /api/open-dir HTTP/1.1\r\nHost: 127.0.0.1:{c.port}\r\n"
                                     f"X-DG-Token: {tok}\r\nConnection: close\r\n\r\n")
            check("打开目录不接受 GET", status_line(resp).startswith("HTTP/1.1 405"), status_line(resp))

            resp = raw_request(c.port, post_body(f"127.0.0.1:{c.port}", c.port, "/api/tasks",
                                                 {"url": url, "dir": out}, tok))
            check("同源 JSON + 令牌放行", " 200 " in status_line(resp), status_line(resp))
            first_id = ""
            try:
                first_id = json.loads(resp.split("\r\n\r\n", 1)[1])["ids"][0]
            except Exception:
                pass

            ids = [c.add(url, connections=4) for _ in range(2)]
            if first_id:
                ids.append(first_id)
            time.sleep(1.5)
            r = c.req("/api/tasks/all/pause", "POST")
            check(f"全部暂停影响 {len(ids)} 个任务", r.get("affected") == len(ids), str(r))
            time.sleep(1.0)
            phases = [c.task(i)["status"] for i in ids]
            check("全部暂停后没有任务在跑",
                  all(s in ("paused", "completed") for s in phases), str(phases))
            r = c.req("/api/tasks/all/resume", "POST")
            check(f"全部继续影响 {len(ids)} 个任务", r.get("affected") == len(ids), str(r))

            # SSE 曾经一直 404：前端写的是 /api/events，服务端只认 /events
            resp = raw_request(c.port, f"GET /api/events HTTP/1.1\r\nHost: 127.0.0.1:{c.port}\r\n"
                                     f"X-DG-Token: {tok}\r\n\r\n", timeout=3.0)
            check("SSE 事件流可用", "text/event-stream" in resp and "data: " in resp,
                  resp[:120].replace("\r\n", " | "))

            # 连续错误令牌必须触发窗口限流（本机也是攻击面）
            codes = []
            for _ in range(8):
                r = raw_request(c.port, f"GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:{c.port}\r\n"
                                       f"X-DG-Token: wrong-token\r\nConnection: close\r\n\r\n")
                codes.append(status_line(r).split(" ")[1])
            check("连续错误令牌触发 429", codes[-1] == "429", str(codes))

            for i in ids:
                try:
                    c.req(f"/api/tasks/{i}", "DELETE")
                except Exception:
                    pass
        finally:
            c.kill()


def scenario_cli(tmp, payload):
    """命令行投递：脚本/CI 不需要模拟浏览器点击。"""
    print("\n[5] 命令行投递（--add / --status）")
    port = free_port()
    RangeHandler.payload = payload[:2 * 1024 * 1024]
    RangeHandler.filename = "cli.bin"

    home = os.path.join(tmp, "home5")
    out = os.path.join(tmp, "out5")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)

    with Server(RangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            r = subprocess.run(
                ["java", "-jar", JAR, "--home", home, "--add",
                 f"http://127.0.0.1:{port}/cli.bin", "--wait", "--quiet"],
                capture_output=True, text=True, timeout=120)
            check("--add --wait 退出码 0", r.returncode == 0,
                  f"code={r.returncode} err={r.stderr.strip()}")
            target = os.path.join(out, "cli.bin")
            check("文件已落地", os.path.isfile(target), str(os.listdir(out)))
            if os.path.isfile(target):
                check("内容一致", sha(open(target, "rb").read()) == sha(RangeHandler.payload), "")

            r = subprocess.run(["java", "-jar", JAR, "--home", home, "--status"],
                               capture_output=True, text=True, timeout=30)
            check("--status 列出任务", r.returncode == 0 and "cli.bin" in r.stdout, r.stdout.strip())
        finally:
            c.kill()


def wait_status(c, tid, statuses, timeout=90):
    deadline = time.time() + timeout
    t = None
    while time.time() < deadline:
        t = c.task(tid)
        if t and t["status"] in statuses:
            return t
        time.sleep(0.4)
    return t


def newest_file(directory):
    files = [os.path.join(directory, n) for n in os.listdir(directory)
             if os.path.isfile(os.path.join(directory, n))]
    return max(files, key=os.path.getmtime) if files else ""


def scenario_chunked(tmp, payload):
    """没有 Content-Length 的 1MB 响应，必须落成 1MB，不能是 1 字节。"""
    print("\n[6] chunked（无 Content-Length）")
    data = payload[: 1024 * 1024]
    ChunkedHandler.payload = data
    port = free_port()
    home = os.path.join(tmp, "home6")
    out = os.path.join(tmp, "out6")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    with Server(ChunkedHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            tid = c.add(f"http://127.0.0.1:{port}/chunked.bin", connections=8)
            t = c.wait_done(tid, timeout=60)
            check("chunked 任务完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
            check("chunked 记录的实际大小是 1MB", t["size"] == len(data), str(t["size"]))
            path = os.path.join(out, "chunked.bin")
            got = open(path, "rb").read() if os.path.exists(path) else b""
            check("chunked 落地不是 1 字节", len(got) == len(data), f"{len(got)} 字节")
            check("chunked 内容一致", sha(got) == sha(data), "内容被写坏")
        finally:
            c.kill()


def scenario_truncated(tmp, payload):
    """声明 1MB 只给 512KB：必须失败，不能 completed。"""
    print("\n[7] 截断响应")
    data = payload[: 1024 * 1024]
    TruncatedHandler.payload = data
    port = free_port()
    home = os.path.join(tmp, "home7")
    out = os.path.join(tmp, "out7")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    with Server(TruncatedHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            tid = c.add(f"http://127.0.0.1:{port}/trunc.bin", connections=4)
            t = c.wait_done(tid, timeout=60)
            check("截断任务失败而不是完成", t["status"] == "error", f"{t['status']} {t.get('error','')}")
            check("截断任务有可读错误", "不完整" in (t.get("error") or "") or "中断" in (t.get("error") or ""),
                  t.get("error", ""))
            check("截断文件没有落地", not [n for n in os.listdir(out) if n.startswith("trunc")],
                  str(os.listdir(out)))
        finally:
            c.kill()


def scenario_hls(tmp, payload):
    """HLS 半截分片 / 播放列表变更都不能拼进成品。"""
    print("\n[8] HLS 分片原子落盘与清单指纹")
    segs = [payload[i * 200000:(i + 1) * 200000] for i in range(3)]
    HlsHandler.segments = segs
    HlsHandler.playlist = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n"
                           "#EXTINF:1,\nseg0.ts\n#EXTINF:1,\nseg1.ts\n"
                           "#EXTINF:1,\nseg2.ts\n#EXT-X-ENDLIST\n")
    port = free_port()
    home = os.path.join(tmp, "home8")
    out = os.path.join(tmp, "out8")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    with Server(HlsHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            # 1) 正常下载
            tid = c.add(f"http://127.0.0.1:{port}/index.m3u8", connections=4)
            t = c.wait_done(tid, timeout=90)
            check("HLS 正常完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
            path = os.path.join(out, "index.ts")
            got = open(path, "rb").read() if os.path.exists(path) else b""
            check("HLS 拼接结果正确", sha(got) == sha(b"".join(segs)), f"{len(got)} 字节")

            # 2) 暂停后把分片截断，并留下 .part：恢复时必须重下该片
            HlsHandler.delay = 0.8
            c.settings(speedLimitKB=80, maxConcurrent=2)
            tid2 = c.add(f"http://127.0.0.1:{port}/index.m3u8", connections=4)
            time.sleep(0.3)
            c.req(f"/api/tasks/{tid2}/pause", "POST")
            wait_status(c, tid2, ("paused",), timeout=15)
            c.kill()
            work = os.path.join(home, "parts", tid2 + "-hls")
            seg1 = os.path.join(work, "1.seg")
            if os.path.exists(seg1):
                with open(seg1, "r+b") as f:
                    f.truncate(64)
            with open(os.path.join(work, "2.seg.part"), "wb") as f:
                f.write(b"half-written")
            c2 = Client(c.port, home, out)
            c2.start()
            try:
                c2.req(f"/api/tasks/{tid2}/resume", "POST")
                t = c2.wait_done(tid2, timeout=90)
                check("半截分片恢复后完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
                names = [n for n in os.listdir(out) if n.startswith("index")]
                files = sorted((os.path.join(out, n) for n in names), key=os.path.getmtime)
                got = open(files[-1], "rb").read() if files else b""
                check("半截分片没有进入成品", sha(got) == sha(b"".join(segs)), f"{len(got)} 字节")
            finally:
                c2.kill()

            # 3) 播放列表变更：必须全量重下，不能复用旧序号分片
            c3 = Client(free_port(), home, out)
            c3.start()
            try:
                HlsHandler.delay = 0.8
                c3.settings(speedLimitKB=80, maxConcurrent=2)
                tid3 = c3.add(f"http://127.0.0.1:{port}/index.m3u8", connections=4)
                time.sleep(0.3)
                c3.req(f"/api/tasks/{tid3}/pause", "POST")
                wait_status(c3, tid3, ("paused",), timeout=15)
                c3.kill()
                segs2 = [b"NEW-SEG-0" * 1000, b"NEW-SEG-1" * 1000, b"NEW-SEG-2" * 1000]
                HlsHandler.segments = segs2
                # 真正改变清单文本（URL 查询串），确保指纹变化
                HlsHandler.playlist = HlsHandler.playlist.replace(".ts\n", ".ts?v=2\n")
                c4 = Client(c3.port, home, out)
                c4.start()
                try:
                    c4.req(f"/api/tasks/{tid3}/resume", "POST")
                    t = c4.wait_done(tid3, timeout=90)
                    check("清单变更后完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
                    names = [n for n in os.listdir(out) if n.startswith("index")]
                    files = sorted((os.path.join(out, n) for n in names), key=os.path.getmtime)
                    got = open(files[-1], "rb").read() if files else b""
                    check("清单变更后是新内容", sha(got) == sha(b"".join(segs2)), f"{len(got)} 字节")
                finally:
                    c4.kill()
            finally:
                HlsHandler.segments = segs
                HlsHandler.delay = 0.0
        finally:
            c.kill()


def scenario_resume_meta(tmp, payload):
    """part 缺失 / tasks.json 损坏时，不能产出全零 completed。"""
    print("\n[9] 断点元数据一致性")
    data = payload[: 8 * 1024 * 1024]
    RangeHandler.payload = data
    RangeHandler.filename = "resume.bin"
    port = free_port()
    home = os.path.join(tmp, "home9")
    out = os.path.join(tmp, "out9")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    with Server(RangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            c.settings(speedLimitKB=400, maxConcurrent=2)
            tid = c.add(f"http://127.0.0.1:{port}/resume.bin", connections=8)
            time.sleep(3.0)
            c.req(f"/api/tasks/{tid}/pause", "POST")
            wait_status(c, tid, ("paused",), timeout=15)
            c.kill()

            part = os.path.join(home, "parts", tid + ".part")
            check("暂停后 part 存在", os.path.exists(part), part)
            os.remove(part)

            c2 = Client(c.port, home, out)
            c2.start()
            try:
                t = c2.task(tid)
                check("part 缺失后断点清零", t["status"] == "paused", t["status"])
                check("part 缺失后 done 归零", t["done"] == 0, str(t["done"]))
                c2.req(f"/api/tasks/{tid}/resume", "POST")
                t = c2.wait_done(tid, timeout=120)
                check("part 缺失后重下完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
                got = open(os.path.join(out, "resume.bin"), "rb").read()
                check("part 缺失后内容正确", sha(got) == sha(data), "产出了错误内容")
                check("不是全零文件", got != b"\0" * len(got), "文件全零")
            finally:
                c2.kill()

            # tasks.json 损坏、meta 完好 → 从 meta 恢复
            c3 = Client(free_port(), home, out)
            c3.start()
            try:
                c3.settings(speedLimitKB=400, maxConcurrent=2)
                tid2 = c3.add(f"http://127.0.0.1:{port}/resume.bin", connections=8)
                time.sleep(2.0)
                c3.req(f"/api/tasks/{tid2}/pause", "POST")
                wait_status(c3, tid2, ("paused",), timeout=15)
                c3.kill()
                with open(os.path.join(home, "tasks.json"), "w", encoding="utf-8") as f:
                    f.write("{ this is not json")
                c4 = Client(c3.port, home, out)
                c4.start()
                try:
                    t = c4.task(tid2)
                    check("tasks.json 损坏后从 meta 恢复任务", t is not None, "任务丢失")
                    if t:
                        check("恢复后为暂停状态", t["status"] == "paused", t["status"])
                        check("恢复后 URL 正确", t["url"].endswith("/resume.bin"), t["url"])
                        c4.req(f"/api/tasks/{tid2}/resume", "POST")
                        t = c4.wait_done(tid2, timeout=120)
                        check("恢复后可继续下载", t["status"] == "completed",
                              f"{t['status']} {t.get('error','')}")
                finally:
                    c4.kill()
            finally:
                c3.kill()
        finally:
            c.kill()


def scenario_queue(tmp, payload):
    """20 个任务、并发上限 3：任何时刻 running 都不能超过 3。"""
    print("\n[10] 队列并发上限")
    RangeHandler.payload = payload[: 64 * 1024]
    RangeHandler.filename = "q.bin"
    port = free_port()
    home = os.path.join(tmp, "home10")
    out = os.path.join(tmp, "out10")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    with Server(RangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            c.settings(speedLimitKB=32, maxConcurrent=3)
            ids = [c.add(f"http://127.0.0.1:{port}/q{i}.bin", connections=2) for i in range(20)]
            peak = 0
            for _ in range(60):
                running = sum(1 for i in ids if c.task(i)["status"] == "downloading")
                peak = max(peak, running)
                if peak > 3:
                    break
                time.sleep(0.1)
            check("并发不超过 maxConcurrent=3", peak <= 3, f"峰值 {peak}")
            c.req("/api/tasks/all/pause", "POST")
        finally:
            c.kill()


def scenario_sse_flood(tmp):
    """SSE 长连接不能把普通 API 的线程池占满。"""
    print("\n[11] SSE 连接上限")
    port = free_port()
    home = os.path.join(tmp, "home11")
    out = os.path.join(tmp, "out11")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    c = Client(free_port(), home, out)
    c.start()
    socks = []
    try:
        for _ in range(20):
            s = socket.create_connection(("127.0.0.1", c.port), timeout=3)
            s.sendall((f"GET /api/events HTTP/1.1\r\nHost: 127.0.0.1:{c.port}\r\n"
                       f"X-DG-Token: {c.token}\r\n\r\n").encode())
            s.settimeout(3)
            try:
                first = s.recv(64).decode("utf-8", "replace")
            except socket.timeout:
                first = ""
            socks.append((s, first))
        codes = [line.split(" ")[1] for _, line in socks if line.startswith("HTTP/")]
        check("SSE 有成功连接", codes.count("200") >= 1, str(codes))
        check("SSE 超出上限返回 503", "503" in codes, str(codes))
        start = time.time()
        c.req("/api/version")
        elapsed = time.time() - start
        check("20 个 SSE 连接下 API 仍能 1 秒内响应", elapsed < 1.0, f"{elapsed:.2f}s")
    finally:
        for s, _ in socks:
            try:
                s.close()
            except Exception:
                pass
        c.kill()


def scenario_apk(tmp):
    """真实签名 APK 必须 verified；篡改后必须 failed，不能显示通过。"""
    print("\n[12] APK v2 验签")
    fixture = os.path.join(ROOT, "desktop", "test", "fixtures", "signed.apk")
    if not os.path.exists(fixture):
        check("APK 样本存在", False, fixture)
        return
    signed = open(fixture, "rb").read()
    start = signing_block_start(signed)
    tampered = bytearray(signed)
    if start > 0:
        at = min(len(tampered) - 1, start + 8 + 8 + 4 + 40)
        tampered[at] ^= 0x5A
    port = free_port()
    home = os.path.join(tmp, "home12")
    out = os.path.join(tmp, "out12")
    os.makedirs(home, exist_ok=True)
    os.makedirs(out, exist_ok=True)

    RangeHandler.payload = signed
    RangeHandler.filename = "signed.apk"
    with Server(RangeHandler, port):
        c = Client(free_port(), home, out)
        c.start()
        try:
            tid = c.add(f"http://127.0.0.1:{port}/signed.apk", connections=2)
            t = c.wait_done(tid, timeout=60)
            check("签名 APK 完成", t["status"] == "completed", f"{t['status']} {t.get('error','')}")
            check("签名 APK 验签通过", t.get("signature") == "verified", str(t.get("signatureDetail")))
        finally:
            c.kill()

    port2 = free_port()
    RangeHandler.payload = bytes(tampered)
    RangeHandler.filename = "tampered.apk"
    home2 = os.path.join(tmp, "home12b")
    out2 = os.path.join(tmp, "out12b")
    os.makedirs(home2, exist_ok=True)
    os.makedirs(out2, exist_ok=True)
    with Server(RangeHandler, port2):
        c = Client(free_port(), home2, out2)
        c.start()
        try:
            tid = c.add(f"http://127.0.0.1:{port2}/tampered.apk", connections=2)
            t = c.wait_done(tid, timeout=60)
            check("篡改 APK 任务失败", t["status"] == "error", f"{t['status']} {t.get('error','')}")
            check("篡改 APK 不显示通过", t.get("signature") != "verified", t.get("signature"))
            check("篡改 APK 已隔离", not [n for n in os.listdir(out2) if n.endswith(".apk")],
                  str(os.listdir(out2)))
        finally:
            c.kill()


def signing_block_start(b):
    """从 APK 字节里找签名块起点；与 Java 侧算法一致。"""
    eocd = b.rfind(b"PK\x05\x06")
    if eocd < 0:
        return -1
    import struct
    cd = struct.unpack_from("<I", b, eocd + 16)[0]
    if cd < 32 or cd > len(b):
        return -1
    size = struct.unpack_from("<Q", b, cd - 24)[0]
    start = cd - size - 8
    if start < 0 or b[cd - 16:cd] != b"APK Sig Block 42":
        return -1
    return start


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
        scenario_local_service_guard(tmp, payload)
        scenario_cli(tmp, payload)
        scenario_chunked(tmp, payload)
        scenario_truncated(tmp, payload)
        scenario_hls(tmp, payload)
        scenario_resume_meta(tmp, payload)
        scenario_queue(tmp, payload)
        scenario_sse_flood(tmp)
        scenario_apk(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"\n结果：{ok_count} 通过 / {fail_count} 失败")
    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
