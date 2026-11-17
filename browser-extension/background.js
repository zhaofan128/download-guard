/**
 * DownloadGuard 浏览器接管 —— 后台服务（MV3 Service Worker）
 *
 * 它只做三件事：
 *   1. 发现本机正在运行的 DownloadGuard（回环端口区间 + 配对令牌）；
 *   2. 浏览器开始下载时取消它，把 URL / Referer / Cookie / UA 投给 DownloadGuard；
 *   3. 投递失败或用户要求「归还」时，用浏览器原生下载重新发起，并对该站点冷却 5 分钟。
 *
 * 不做资源嗅探、不读页面内容、不记录浏览历史：扩展拿到的只有「浏览器自己要下的那个文件」。
 *
 * 与 DownloadGuard 的协议（本机回环，端口区间 8765-8794，与桌面版默认端口一致）：
 *   GET  /api/hook/ping   → { app:"dlguard", api:1, paired:bool, needPair:bool }
 *   POST /api/hook/pair   → { code:"12345678" } → { ok, token }
 *   POST /api/hook        → X-DG-Hook-Token: <token> + JSON 载荷 → { ok, id, name }
 *
 * 为什么需要配对码：本机端口对任何扩展都是可达的，只靠「Origin 是 chrome-extension://」
 * 无法区分「用户自己装的 DownloadGuard 扩展」和「随便一个扩展」。配对码只显示在
 * DownloadGuard 的设置页，等于用户显式授权。
 *
 * 端口范围为什么是 8765-8794：桌面版默认监听 8765（Settings.port），被占用时
 * 从 8765 起顺延最多 30 个端口，即 8765-8794。扩展必须先试用户手动填的端口
 * （popup → 高级），再扫这个范围，否则永远连不上默认启动的服务。
 */

'use strict';

const PORT_START = 8765;
const PORT_END = 8794;
const COOLDOWN_MS = 5 * 60 * 1000;
const MAX_RECENT = 20;

/** 允许投递的协议：只认 http/https，file:// 与 data: 交给浏览器自己处理 */
const OK_SCHEME = /^https?:/i;

/** 明显不该接管的类型：blob/data 与浏览器内部页 */
const SKIP_URL = /^(blob:|data:|chrome:|edge:|about:|devtools:)/i;

/** 常见二段后缀，用于「归还后冷却哪个站点」的判断 */
const TWO_LEVEL_TLDS = new Set([
  'com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn', 'ac.cn',
  'co.uk', 'org.uk', 'gov.uk', 'ac.uk', 'me.uk',
  'co.jp', 'ne.jp', 'or.jp', 'ac.jp',
  'com.hk', 'com.tw', 'com.au', 'net.au', 'org.au',
  'com.sg', 'com.my', 'com.ph', 'com.vn', 'com.id', 'com.th',
  'co.kr', 'co.in', 'co.nz', 'co.za', 'co.il',
  'com.br', 'com.mx', 'com.ar', 'com.tr', 'com.ua', 'com.ru',
]);

/** { port, token } | null —— 命中后缓存，401 时失效重探 */
let bridge = null;

const log = (...a) => console.log('[DownloadGuard]', ...a);

// ---------------------------------------------------------------- 设置

// hijack 默认关：接管所有浏览器下载是有感行为，必须由用户显式开启（配对后弹窗确认）
const DEFAULT_SETTINGS = { enabled: true, hijack: false, manualPort: 0, skipList: [] };

async function getSettings() {
  const got = await chrome.storage.local.get(DEFAULT_SETTINGS);
  return Object.assign({}, DEFAULT_SETTINGS, got);
}

async function setSettings(patch) {
  await chrome.storage.local.set(patch);
}

async function getToken() {
  const got = await chrome.storage.local.get({ hookToken: '' });
  return got.hookToken || '';
}

// ---------------------------------------------------------------- 站点与冷却

function hostOf(url) {
  try { return new URL(url).hostname; } catch (e) { return ''; }
}

/** eTLD+1 的启发式实现；不引第三方库，命中二段后缀时取三段 */
function rootDomainOf(host) {
  if (!host) return '';
  const h = String(host).toLowerCase().replace(/\.$/, '');
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) return h;
  if (h.startsWith('[')) return h;
  const parts = h.split('.');
  if (parts.length <= 2) return h;
  const last2 = parts.slice(-2).join('.');
  if (TWO_LEVEL_TLDS.has(last2)) return parts.slice(-3).join('.');
  return last2;
}

/** 冷却用「发起下载的页面」锚定；拿不到页面才退回资源域名 */
function cooldownRoot(pageUrl, resourceUrl) {
  return rootDomainOf(hostOf(pageUrl) || hostOf(resourceUrl));
}

async function getCooldowns() {
  const got = await chrome.storage.local.get({ cooldowns: {} });
  return got.cooldowns || {};
}

async function isCoolingDown(root) {
  if (!root) return false;
  const map = await getCooldowns();
  const until = map[root] || 0;
  if (!until) return false;
  if (until <= Date.now()) {
    delete map[root];
    await chrome.storage.local.set({ cooldowns: map });
    return false;
  }
  return true;
}

async function setCooldown(root, ms) {
  if (!root) return;
  const map = await getCooldowns();
  map[root] = Date.now() + ms;
  await chrome.storage.local.set({ cooldowns: map });
}

async function clearCooldown(root) {
  const map = await getCooldowns();
  delete map[root];
  await chrome.storage.local.set({ cooldowns: map });
}

// ---------------------------------------------------------------- 发现本机服务

/** 逐个端口探测；只认 app === "dlguard" 的响应，避免误连同一区间的别的程序 */
async function ensureBridge(force) {
  if (bridge && !force) return bridge;
  bridge = null;
  const st = await getSettings();
  // 用户手动填的端口优先：改过 --port 的桌面版不在默认区间里
  if (st.manualPort > 0 && st.manualPort < 65536) {
    const hit = await probe(st.manualPort);
    if (hit) {
      bridge = hit;
      return bridge;
    }
  }
  for (let p = PORT_START; p <= PORT_END; p++) {
    const hit = await probe(p);
    if (hit) {
      bridge = hit;
      return bridge;
    }
  }
  return null;
}

async function probe(p) {
  try {
    const r = await fetch('http://127.0.0.1:' + p + '/api/hook/ping', {
      method: 'GET',
      cache: 'no-store',
    });
    if (!r.ok) return null;
    const j = await r.json();
    if (j && j.app === 'dlguard') return { port: p };
  } catch (e) {
    /* 端口没监听，继续 */
  }
  return null;
}

function invalidateBridge() {
  bridge = null;
}

// ---------------------------------------------------------------- 与 DownloadGuard 通信

async function postJson(path, body, withToken) {
  const b = await ensureBridge(false);
  if (!b) return { ok: false, error: 'no_service' };
  const headers = { 'Content-Type': 'application/json' };
  if (withToken) {
    const token = await getToken();
    if (!token) return { ok: false, error: 'need_pair' };
    headers['X-DG-Hook-Token'] = token;
  }
  try {
    const r = await fetch('http://127.0.0.1:' + b.port + path, {
      method: 'POST',
      cache: 'no-store',
      headers,
      body: JSON.stringify(body || {}),
    });
    if (r.status === 401 || r.status === 403) {
      const j = await r.json().catch(() => null);
      return { ok: false, error: (j && j.error) || 'forbidden', status: r.status };
    }
    if (!r.ok) {
      const j = await r.json().catch(() => null);
      return { ok: false, error: (j && j.error) || ('http_' + r.status), status: r.status };
    }
    return await r.json();
  } catch (e) {
    invalidateBridge();
    return { ok: false, error: 'network' };
  }
}

/** 配对：用设置页显示的 8 位数字换投递令牌 */
async function pair(code) {
  const r = await postJson('/api/hook/pair', { code: String(code || '').trim() }, false);
  if (r && r.ok && r.token) {
    await chrome.storage.local.set({ hookToken: r.token });
    return { ok: true };
  }
  return r || { ok: false, error: 'pair_failed' };
}

// ---------------------------------------------------------------- 载荷

async function cookieHeader(url) {
  try {
    const list = await chrome.cookies.getAll({ url });
    if (!list || !list.length) return '';
    return list.map((c) => c.name + '=' + c.value).join('; ');
  } catch (e) {
    return '';
  }
}

function fileNameFromUrl(url) {
  try {
    const u = new URL(url);
    const seg = u.pathname.substring(u.pathname.lastIndexOf('/') + 1);
    const name = decodeURIComponent(seg || '');
    return name || '';
  } catch (e) {
    return '';
  }
}

/**
 * 组装投递载荷。
 *
 * Cookie 是网盘直链能下下来的前提：URL 本身通常只有几分钟有效期，且必须带登录态。
 * 它只随这次请求发给本机 127.0.0.1，不经过任何远程服务器。
 */
async function buildPayload(info) {
  const url = info.url;
  return {
    url,
    fileName: info.fileName || fileNameFromUrl(url),
    size: info.size > 0 ? info.size : 0,
    referer: info.referer || '',
    pageUrl: info.pageUrl || '',
    userAgent: (self.navigator && self.navigator.userAgent) || '',
    cookie: await cookieHeader(url),
    source: 'browser',
  };
}

// ---------------------------------------------------------------- 归还浏览器

async function redownload(payload) {
  const base = { url: payload.url, conflictAction: 'uniquify' };
  if (payload.fileName) base.filename = 'DownloadGuard/' + payload.fileName;
  try {
    if (payload.referer) {
      await chrome.downloads.download(Object.assign({
        headers: [{ name: 'Referer', value: payload.referer }],
      }, base));
    } else {
      await chrome.downloads.download(base);
    }
  } catch (e) {
    try {
      await chrome.downloads.download({ url: payload.url });
    } catch (e2) {
      log('归还失败', e2 && e2.message);
    }
  }
}

// ---------------------------------------------------------------- 接管

chrome.downloads.onCreated.addListener((item) => {
  void onCreated(item);
});

async function onCreated(item) {
  try {
    // 自己重发的（归还、手动）不能再接管，否则死循环
    if (item.byExtensionId && item.byExtensionId === chrome.runtime.id) return;
    const url = item.finalUrl || item.url || '';
    if (!OK_SCHEME.test(url) || SKIP_URL.test(url)) return;

    const st = await getSettings();
    if (!st.enabled || !st.hijack) return;

    const root = cooldownRoot(item.referrer || '', url);
    // 站点跳过名单：命中的站点永远不接管（在 popup 里维护）
    const skipList = (st.skipList || []).map((s) => String(s).toLowerCase());
    if (root && skipList.includes(root.toLowerCase())) return;
    if (await isCoolingDown(root)) return;

    // 服务没运行就完全不干预：浏览器下载照常
    const b = await ensureBridge(false);
    if (!b) return;
    if (!(await getToken())) return;

    // 探测到决定之间文件可能已经下完，已完成的不要抢
    const found = await chrome.downloads.search({ id: item.id });
    const cur = found && found[0];
    if (!cur || cur.state !== 'in_progress') return;

    chrome.downloads.cancel(item.id, () => {
      void chrome.runtime.lastError;
      chrome.downloads.erase({ id: item.id }, () => void chrome.runtime.lastError);
    });

    const payload = await buildPayload({
      url,
      fileName: item.filename ? item.filename.split(/[\\/]/).pop() : '',
      size: item.fileSize > 0 ? item.fileSize : (item.totalBytes > 0 ? item.totalBytes : 0),
      referer: item.referrer || '',
      pageUrl: item.referrer || '',
    });

    const r = await postJson('/api/hook', payload, true);
    if (r && r.ok) {
      badge('✓', '#2e86ff');
      await rememberRecent({ name: r.name || payload.fileName, at: Date.now(), ok: true });
      log('已投递', r.id, r.name);
    } else {
      // 投递失败必须还原成浏览器下载，不能让文件凭空消失
      log('投递失败，归还浏览器', r && r.error);
      await setCooldown(root, COOLDOWN_MS);
      await redownload(payload);
      badge('!', '#e0554a');
      await rememberRecent({ name: payload.fileName, at: Date.now(), ok: false, error: r && r.error });
    }
  } catch (e) {
    log('接管异常', e && e.message);
  }
}

// ---------------------------------------------------------------- 徽标与最近记录

function badge(text, color) {
  try {
    chrome.action.setBadgeText({ text: text || '' });
    if (text && color) chrome.action.setBadgeBackgroundColor({ color });
    if (text) setTimeout(() => { try { chrome.action.setBadgeText({ text: '' }); } catch (e) {} }, 4000);
  } catch (e) { /* ignore */ }
}

async function rememberRecent(entry) {
  const got = await chrome.storage.local.get({ recent: [] });
  const list = got.recent || [];
  list.unshift(entry);
  await chrome.storage.local.set({ recent: list.slice(0, MAX_RECENT) });
}

// ---------------------------------------------------------------- 右键菜单

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    void chrome.runtime.lastError;
    chrome.contextMenus.create({ id: 'dg-link', title: '用 DownloadGuard 下载此链接', contexts: ['link'] });
    chrome.contextMenus.create({ id: 'dg-media', title: '用 DownloadGuard 下载此媒体', contexts: ['video', 'audio', 'image'] });
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const url = info.linkUrl || info.srcUrl || '';
  if (!OK_SCHEME.test(url)) return;
  const st = await getSettings();
  if (!st.enabled) return;
  const payload = await buildPayload({
    url,
    pageUrl: (tab && tab.url) || info.pageUrl || '',
    referer: (tab && tab.url) || info.pageUrl || '',
  });
  const r = await postJson('/api/hook', payload, true);
  if (r && r.ok) badge('✓', '#2e86ff');
  else badge('!', '#e0554a');
});

// ---------------------------------------------------------------- 消息接口（popup 用）

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  void (async () => {
    try {
      if (!msg || !msg.type) { sendResponse({ ok: false }); return; }
      if (msg.type === 'status') {
        const b = await ensureBridge(false);
        const token = await getToken();
        const st = await getSettings();
        sendResponse({
          ok: true,
          connected: !!b,
          port: b ? b.port : 0,
          paired: !!token,
          settings: st,
        });
        return;
      }
      if (msg.type === 'pair') {
        sendResponse(await pair(msg.code));
        return;
      }
      if (msg.type === 'set') {
        await setSettings(msg.patch || {});
        sendResponse({ ok: true });
        return;
      }
      if (msg.type === 'reprobe') {
        // 手动端口变了：作废桥缓存，下次 status 重新探测
        invalidateBridge();
        sendResponse({ ok: true });
        return;
      }
      if (msg.type === 'recent') {
        const got = await chrome.storage.local.get({ recent: [] });
        sendResponse({ ok: true, recent: got.recent || [] });
        return;
      }
      if (msg.type === 'cooldowns') {
        const map = await getCooldowns();
        sendResponse({ ok: true, cooldowns: map });
        return;
      }
      if (msg.type === 'clearCooldown') {
        await clearCooldown(msg.root || '');
        sendResponse({ ok: true });
        return;
      }
      if (msg.type === 'download') {
        const payload = await buildPayload({ url: msg.url, pageUrl: msg.pageUrl, referer: msg.pageUrl });
        sendResponse(await postJson('/api/hook', payload, true));
        return;
      }
      sendResponse({ ok: false, error: 'unknown' });
    } catch (e) {
      sendResponse({ ok: false, error: String((e && e.message) || e) });
    }
  })();
  return true; // 异步响应
});
