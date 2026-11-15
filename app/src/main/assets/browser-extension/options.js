/** DownloadGuard 下载桥 - 选项页逻辑 */

const serverInput = document.getElementById("server");
const tokenInput = document.getElementById("token");
const statusEl = document.getElementById("status");

function baseUrl(server) {
  let s = (server || "").trim();
  if (!s) return "";
  if (!/^https?:\/\//i.test(s)) s = "http://" + s;
  if (!/:\d+/.test(s.replace(/^https?:\/\//i, ""))) s = s.replace(/\/$/, "") + ":8787";
  return s.replace(/\/$/, "");
}

function setStatus(text, ok) {
  statusEl.textContent = text;
  statusEl.style.color = ok ? "#4ade80" : "#f87171";
}

async function load() {
  const cfg = await chrome.storage.sync.get({ server: "", token: "" });
  serverInput.value = cfg.server || "";
  tokenInput.value = cfg.token || "";
}

document.getElementById("save").addEventListener("click", async () => {
  const server = baseUrl(serverInput.value);
  const token = tokenInput.value.trim();
  if (!server) return setStatus("请填写手机服务地址", false);
  await chrome.storage.sync.set({ server, token });
  serverInput.value = server;
  setStatus("已保存", true);
});

document.getElementById("test").addEventListener("click", async () => {
  const server = baseUrl(serverInput.value);
  if (!server) return setStatus("请先填写手机服务地址", false);
  setStatus("正在连接…", true);
  try {
    const resp = await fetch(server + "/api/ping", { cache: "no-store" });
    const data = await resp.json();
    if (data.ok) {
      setStatus("连接成功：" + data.name + " v" + (data.version || "?") , true);
    } else {
      setStatus("服务异常：" + JSON.stringify(data), false);
    }
  } catch (e) {
    setStatus("连接失败：手机不可达或插件未开启（" + (e.message || e) + "）", false);
  }
});

load();
