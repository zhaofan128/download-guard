'use strict';

const $ = (id) => document.getElementById(id);

async function send(msg) {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage(msg, (resp) => {
      void chrome.runtime.lastError;
      resolve(resp || { ok: false });
    });
  });
}

function fmtTime(ts) {
  const d = new Date(ts);
  const p = (n) => String(n).padStart(2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes());
}

function fmtLeft(ms) {
  const sec = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? m + ' 分 ' + s + ' 秒' : s + ' 秒';
}

async function render() {
  const st = await send({ type: 'status' });
  const dot = $('dot');
  dot.className = 'dot ' + (st.connected ? 'on' : 'off');
  $('state').textContent = st.connected
    ? ('已连接 · 端口 ' + st.port)
    : '未检测到 DownloadGuard';

  const paired = !!st.paired;
  $('pairBox').classList.toggle('hidden', !st.connected || paired);

  if (st.settings) {
    $('enabled').checked = !!st.settings.enabled;
    $('hijack').checked = !!st.settings.hijack;
  }

  // 最近投递
  const recent = await send({ type: 'recent' });
  const list = $('list');
  list.innerHTML = '';
  const items = (recent && recent.recent) || [];
  if (!items.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = '还没有记录';
    list.appendChild(li);
  } else {
    for (const it of items) {
      const li = document.createElement('li');
      const name = document.createElement('span');
      name.className = 'name';
      name.textContent = it.name || '(未命名)';
      name.title = it.name || '';
      const tag = document.createElement('span');
      tag.className = 'tag ' + (it.ok ? 'ok' : 'bad');
      tag.textContent = it.ok ? '已投递' : '已归还';
      li.appendChild(name);
      li.appendChild(tag);
      list.appendChild(li);
    }
  }

  // 冷却站点
  const cool = await send({ type: 'cooldowns' });
  const map = (cool && cool.cooldowns) || {};
  const now = Date.now();
  const roots = Object.keys(map).filter((k) => map[k] > now);
  $('coolBox').classList.toggle('hidden', roots.length === 0);
  const coolList = $('coolList');
  coolList.innerHTML = '';
  for (const root of roots) {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = root;
    const btn = document.createElement('button');
    btn.textContent = fmtLeft(map[root] - now);
    btn.style.background = 'transparent';
    btn.style.color = 'var(--muted)';
    btn.style.padding = '0';
    btn.title = '点击立即恢复接管';
    btn.onclick = async () => {
      await send({ type: 'clearCooldown', root });
      render();
    };
    li.appendChild(name);
    li.appendChild(btn);
    coolList.appendChild(li);
  }
}

$('pairBtn').onclick = async () => {
  const code = $('code').value.trim();
  if (!/^\d{8}$/.test(code)) {
    $('pairHint').textContent = '配对码是 8 位数字';
    return;
  }
  $('pairBtn').disabled = true;
  const r = await send({ type: 'pair', code });
  $('pairBtn').disabled = false;
  if (r && r.ok) {
    $('pairHint').textContent = '';
    render();
  } else {
    const map = {
      forbidden: '配对码不对（或试错太多次，等一分钟）',
      no_service: '没有连上 DownloadGuard，请先启动它',
      network: '连接被拒绝，检查 DownloadGuard 是否在运行',
    };
    $('pairHint').textContent = map[r && r.error] || '配对失败：' + ((r && r.error) || '未知原因');
  }
};

$('enabled').onchange = async (e) => {
  await send({ type: 'set', patch: { enabled: e.target.checked } });
};

$('hijack').onchange = async (e) => {
  await send({ type: 'set', patch: { hijack: e.target.checked } });
};

$('code').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') $('pairBtn').click();
});

render();
setInterval(render, 2000);
