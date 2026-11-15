/**
 * DownloadGuard 浏览器下载桥（Chromium MV3 扩展）
 *
 * 工作方式（参考 gopeed 浏览器插件的思路）：
 *  1. 浏览器产生下载 → onCreated 事件拿到 URL；
 *  2. 把 URL POST 到手机 DownloadGuard 的局域网接口（http://<手机IP>:8787/api/download）；
 *  3. 手机确认接收成功后，取消浏览器自己的下载，避免双份流量。
 *
 * 只在「设置里配置了服务器地址」时才会拦截——不配置就是完全无感的空扩展。
 * 不拦截 blob:/data: 与扩展自身发起的下载。
 */

const DEFAULT_PORT = 8787;

async function getConfig() {
  return await chrome.storage.sync.get({ server: "", token: "" });
}

function baseUrl(server) {
  let s = (server || "").trim();
  if (!s) return "";
  if (!/^https?:\/\//i.test(s)) s = "http://" + s;
  // 只给了 IP 时补默认端口
  if (!/:\d+/.test(s.replace(/^https?:\/\//i, ""))) s = s.replace(/\/$/, "") + ":" + DEFAULT_PORT;
  return s.replace(/\/$/, "");
}

function fileNameFrom(item) {
  try {
    if (item.filename) return decodeURIComponent(item.filename.split(/[\\/]/).pop());
  } catch (e) {}
  try {
    const u = new URL(item.url);
    const last = u.pathname.split("/").pop();
    return last ? decodeURIComponent(last) : undefined;
  } catch (e) {
    return undefined;
  }
}

async function forwardDownload(item) {
  const cfg = await getConfig();
  const base = baseUrl(cfg.server);
  if (!base) return { ok: false, reason: "unconfigured" };

  const url = item.url || "";
  if (!/^https?:/i.test(url)) return { ok: false, reason: "unsupported-scheme" };
  // 扩展自身发起的下载（例如导出插件包）不再转发，否则会自我循环
  if (item.byExtensionId) return { ok: false, reason: "by-extension" };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const resp = await fetch(base + "/api/download", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-DG-Token": cfg.token || ""
      },
      body: JSON.stringify({ url, name: fileNameFrom(item) }),
      signal: controller.signal
    });
    clearTimeout(timer);
    const data = await resp.json().catch(() => ({}));
    if (resp.ok && data.ok) {
      // 手机已接手：取消浏览器下载
      try { await chrome.downloads.cancel(item.id); } catch (e) {}
      try { await chrome.downloads.erase({ id: item.id }); } catch (e) {}
      return { ok: true };
    }
    return { ok: false, reason: data.error || ("HTTP " + resp.status) };
  } catch (e) {
    clearTimeout(timer);
    return { ok: false, reason: "手机不可达：" + (e && e.message ? e.message : e) };
  }
}

chrome.downloads.onCreated.addListener((item) => {
  forwardDownload(item).then((result) => {
    if (!result.ok && result.reason !== "unconfigured" &&
        result.reason !== "unsupported-scheme" && result.reason !== "by-extension") {
      console.warn("[DownloadGuard] 转发失败，浏览器继续原下载：", result.reason);
    }
  });
});
