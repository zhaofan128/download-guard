/* DownloadGuard 桌面版前端：零框架、零构建，一个文件装下全部交互。 */
(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const DG_TOKEN = ($('meta[name="dg-token"]') || {}).content || '';

  const state = {
    data: null,
    view: 'all',
    filter: 'all',
    search: '',
    limit: 60,
    drawerId: null,
    drawerKey: '',
    dirTarget: null,
    dirCurrent: '',
    settingsLoaded: false,
    lastSpeeds: new Array(12).fill(-1),
    fallbackTimer: null,
    sse: null,
    editSettings: false
  };

  const STATUS = {
    queued: { text: '排队中', chip: 'accent', cls: 'is-paused' },
    downloading: { text: '下载中', chip: 'accent', cls: '' },
    paused: { text: '已暂停', chip: 'mute', cls: 'is-paused' },
    verifying: { text: '校验中', chip: 'warn', cls: 'is-paused' },
    completed: { text: '已完成', chip: 'good', cls: 'is-done' },
    error: { text: '失败', chip: 'bad', cls: 'is-error' },
    cancelled: { text: '已取消', chip: 'mute', cls: 'is-paused' }
  };

  const KIND_ICON = { HTTP: 'i-download', HLS: 'i-link', BT: 'i-magnet' };

  // ---------------------------------------------------------------- 工具

  const esc = (s) => String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

  function fmtSize(bytes) {
    if (bytes == null || bytes < 0) return '—';
    if (bytes < 1024) return bytes + ' B';
    const kb = bytes / 1024;
    if (kb < 1024) return kb.toFixed(1) + ' KB';
    const mb = kb / 1024;
    if (mb < 1024) return mb.toFixed(1) + ' MB';
    return (mb / 1024).toFixed(2) + ' GB';
  }

  function fmtSpeed(bps) {
    if (!bps || bps <= 0) return '—';
    return fmtSize(bps) + '/s';
  }

  function fmtEta(sec) {
    if (!sec || sec <= 0 || sec > 86400 * 7) return '—';
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = Math.floor(sec % 60);
    if (h > 0) return `${h} 小时 ${m} 分`;
    if (m > 0) return `${m} 分 ${s} 秒`;
    return `${s} 秒`;
  }

  function fmtTime(ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function pct(task) {
    if (task.status === 'completed') return 100;
    if (!task.size) return 0;
    return Math.max(0, Math.min(100, (task.done / task.size) * 100));
  }

  function icon(name, cls = '') {
    return `<svg class="${cls}" aria-hidden="true"><use href="#${name}"/></svg>`;
  }

  async function api(path, body, method = 'POST') {
    const opt = { method, headers: { 'X-DG-Token': DG_TOKEN } };
    if (body !== undefined) {
      opt.body = JSON.stringify(body);
      opt.headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(path, opt);
    let data = null;
    try { data = await res.json(); } catch (e) { data = null; }
    if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
    return data;
  }

  function toast(msg, kind = '') {
    const box = $('#toasts');
    const el = document.createElement('div');
    el.className = 'toast ' + kind;
    el.innerHTML = icon(kind === 'bad' ? 'i-alert' : kind === 'good' ? 'i-check' : 'i-zap') + `<span>${esc(msg)}</span>`;
    box.appendChild(el);
    setTimeout(() => {
      el.style.transition = 'opacity .3s, transform .3s';
      el.style.opacity = '0';
      el.style.transform = 'translateY(6px)';
      setTimeout(() => el.remove(), 320);
    }, 3200);
  }

  // ---------------------------------------------------------------- 主题

  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('dg-theme', theme); } catch (e) { /* 隐私模式禁用了存储 */ }
  }

  function currentTheme() {
    return document.documentElement.dataset.theme || 'auto';
  }

  // ---------------------------------------------------------------- 数据

  async function loadState() {
    try {
      const data = await api('/api/state', undefined, 'GET');
      applyState(data);
    } catch (e) {
      toast('连接本地服务失败：' + e.message, 'bad');
    }
  }

  function connectStream() {
    if (!window.EventSource) { startFallback(); return; }
    try {
      const es = new EventSource('/api/events');
      state.sse = es;
      es.onopen = () => stopFallback();
      es.onmessage = (ev) => {
        try { applyState(JSON.parse(ev.data)); } catch (e) { /* 半包 */ }
      };
      es.onerror = () => {
        // EventSource 自己会重连；同时开一个轮询兜底，避免界面卡在旧数据
        startFallback();
      };
    } catch (e) {
      startFallback();
    }
  }

  function startFallback() {
    if (state.fallbackTimer) return;
    state.fallbackTimer = setInterval(loadState, 1500);
  }

  function stopFallback() {
    if (state.fallbackTimer) {
      clearInterval(state.fallbackTimer);
      state.fallbackTimer = null;
    }
  }

  function applyState(data) {
    if (!data) return;
    if (data.patch) {
      // 增量帧：只带变了的任务。合并进本地列表，静态字段（URL、保存路径、分段明细）保持原样
      if (!state.data) return;
      const map = new Map(state.data.tasks.map((t) => [t.id, t]));
      (data.tasks || []).forEach((t) => {
        const old = map.get(t.id);
        if (old) Object.assign(old, t);
        else map.set(t.id, t);
      });
      (data.removed || []).forEach((id) => map.delete(id));
      data.tasks = Array.from(map.values());
      data.settings = data.settings || state.data.settings;
      data.env = state.data.env;
      data.stats = data.stats || state.data.stats;
      data.version = data.version || state.data.version;
      data.port = state.data.port;
    }
    if (!data.tasks) return;
    state.data = data;
    if (!state.settingsLoaded) {
      fillSettings(data.settings, data.env);
      state.settingsLoaded = true;
    } else if (!state.editSettings) {
      // 用户没在改设置时，跟着服务端值走（比如另一个窗口改了）
      fillSettings(data.settings, data.env, true);
    }
    $('#brandVersion').textContent = data.version || '';
    $('#sideDir').textContent = data.settings.saveDir;
    $('#sideDir').title = data.settings.saveDir;
    $('#sideSpeed').querySelector('b').textContent = fmtSpeed(data.stats.speed);
    const tasks = data.tasks || [];
    const pausable = tasks.some((t) => t.status === 'downloading' || t.status === 'queued');
    const resumable = tasks.some((t) => t.status === 'paused');
    const pauseAll = $('#pauseAllBtn');
    const resumeAll = $('#resumeAllBtn');
    if (pauseAll) pauseAll.disabled = !pausable;
    if (resumeAll) resumeAll.disabled = !resumable;
    renderStats(data);
    renderList();
    updateNavCounts();
    if (state.drawerId) renderDrawer(state.data.tasks.find((t) => t.id === state.drawerId));
    updateSpark(data.stats.speed);
  }

  // ---------------------------------------------------------------- 渲染：统计

  function renderStats(data) {
    const s = data.stats;
    $('#statSpeed').textContent = fmtSpeed(s.speed);
    $('#statActive').textContent = s.downloading;
    $('#statActiveHint').textContent = s.downloading > 0 ? '正在并行传输' : '队列空闲';
    $('#statConn').textContent = data.tasks.filter((t) => t.verify === 'ok').length;
    const done = data.tasks.filter((t) => t.status === 'completed');
    const bytes = done.reduce((a, t) => a + (t.size || 0), 0);
    $('#statDone').textContent = done.length;
    $('#statDoneHint').textContent = '累计 ' + fmtSize(bytes);
  }

  function updateSpark(speed) {
    const arr = state.lastSpeeds;
    const v = speed || 0;
    if (arr.some((x) => x < 0)) {
      // 第一次渲染：用当前值铺满，曲线从水平开始，而不是从 0 拉一根斜线
      for (let i = 0; i < arr.length; i++) if (arr[i] < 0) arr[i] = v;
    }
    arr.push(v);
    arr.shift();
    // 用 1.4 倍峰值做上限：恒定速度时曲线落在中上部，而不是贴着顶边变成一条框
    const max = Math.max(...arr, 1);
    const ceiling = max * 1.85;
    const w = 120, h = 34, pad = 4;
    const pts = arr.map((v, i) => [
      (i / (arr.length - 1)) * w,
      h - pad - (v / ceiling) * (h - pad * 2)
    ]);
    const d = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');
    const line = $('#spark .spark-line');
    const area = $('#spark .spark-area');
    if (line) line.setAttribute('d', d);
    if (area) area.setAttribute('d', d + ` L ${w} ${h} L 0 ${h} Z`);
    const dot = $('#spark .spark-dot');
    if (dot) {
      dot.setAttribute('cx', pts[pts.length - 1][0].toFixed(1));
      dot.setAttribute('cy', pts[pts.length - 1][1].toFixed(1));
    }
  }

  function updateNavCounts() {
    const tasks = state.data ? state.data.tasks : [];
    const active = tasks.filter((t) => ['downloading', 'queued', 'verifying'].includes(t.status)).length;
    const done = tasks.filter((t) => t.status === 'completed').length;
    $('#cntAll').textContent = tasks.length;
    $('#cntActive').textContent = active;
    $('#cntDone').textContent = done;
  }

  // ---------------------------------------------------------------- 渲染：列表

  function visibleTasks() {
    let list = state.data ? state.data.tasks.slice() : [];
    const view = state.view === 'settings' ? 'all' : state.view;
    if (view === 'active') list = list.filter((t) => ['downloading', 'queued', 'verifying', 'paused'].includes(t.status));
    if (view === 'done') list = list.filter((t) => t.status === 'completed');
    if (state.filter === 'active') list = list.filter((t) => ['downloading', 'queued', 'verifying', 'paused'].includes(t.status));
    if (state.filter === 'done') list = list.filter((t) => t.status === 'completed');
    if (state.search.trim()) {
      const q = state.search.trim().toLowerCase();
      list = list.filter((t) => (t.name || '').toLowerCase().includes(q) || (t.url || '').toLowerCase().includes(q));
    }
    // 进行中优先；已完成/已取消按完成时间倒序。分页后这一条很重要：
    // 一个任务刚下完就从「下载中」组跳到「已完成」组，如果按创建时间排，
    // 它会掉到几百条记录后面，用户以为文件没了。
    const rank = { downloading: 0, verifying: 1, queued: 2, paused: 3, error: 4, completed: 5, cancelled: 6 };
    const stamp = (t) => (t.status === 'completed' || t.status === 'cancelled')
      ? (t.finishedAt || t.createdAt || 0)
      : (t.createdAt || 0);
    list.sort((a, b) => (rank[a.status] ?? 9) - (rank[b.status] ?? 9) || stamp(b) - stamp(a));
    return list;
  }

  function renderList() {
    const list = $('#taskList');
    const all = visibleTasks();
    // 活跃/暂停/失败的任务永远全部渲染，再往后带 60 条历史。
    // 否则「刚下完」的任务一进入已完成组，就可能被上百条历史记录挤出可见区。
    const alive = all.filter((t) => ['downloading', 'verifying', 'queued', 'paused', 'error'].includes(t.status)).length;
    const cap = Math.max(state.limit, alive + 60);
    const tasks = all.slice(0, cap);
    const empty = $('#emptyState');
    empty.hidden = all.length > 0;
    list.hidden = all.length === 0;

    const existing = new Map($$('.task', list).map((el) => [el.dataset.id, el]));
    let cursor = list.firstElementChild;
    for (const t of tasks) {
      let el = existing.get(t.id);
      if (!el) {
        el = createTask(t);
        el.classList.add('enter');   // 只有新卡片播入场动画
        el.addEventListener('animationend', () => el.classList.remove('enter'), { once: true });
        list.insertBefore(el, cursor);
      } else {
        existing.delete(t.id);
        // 位置没变就不要动它：appendChild 会先摘再插，WebKit 会把 CSS 动画重头播一遍，
        // 结果是每次状态刷新整列卡片都闪一下
        if (el !== cursor) list.insertBefore(el, cursor);
        else cursor = cursor.nextElementSibling;
      }
      updateTask(el, t);
    }
    existing.forEach((el) => el.remove());

    const more = $('#listMore');
    if (more) {
      const rest = all.length - tasks.length;
      more.hidden = rest <= 0;
      if (rest > 0) more.textContent = `还有 ${rest} 个任务，点击加载`;
    }
  }

  function createTask(t) {
    const el = document.createElement('article');
    el.className = 'task';
    el.dataset.id = t.id;
    el.tabIndex = 0;
    el.innerHTML = `
      <div class="task-icon"><svg aria-hidden="true"><use href="#i-download"/></svg></div>
      <div class="task-main">
        <div class="task-top">
          <h3 class="task-name"></h3>
          <span class="chip mute"></span>
        </div>
        <div class="task-meta"></div>
        <div class="progress"><i></i></div>
        <div class="task-bottom"><span class="live"></span><span class="rest"></span><span class="err"></span></div>
      </div>
      <div class="task-actions"></div>`;
    el._refs = {
      icon: $('.task-icon', el),
      iconUse: $('.task-icon use', el),
      name: $('.task-name', el),
      chip: $('.chip', el),
      meta: $('.task-meta', el),
      bar: $('.progress > i', el),
      live: $('.live', el),
      rest: $('.rest', el),
      err: $('.err', el),
      actions: $('.task-actions', el)
    };
    el.addEventListener('click', (ev) => {
      const btn = ev.target.closest('[data-act]');
      if (btn) {
        ev.stopPropagation();
        doAction(btn.dataset.act, t.id, btn);
        return;
      }
      openDrawer(t.id);
    });
    el.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' && !ev.target.closest('[data-act]')) openDrawer(t.id);
    });
    return el;
  }

  function updateTask(el, t) {
    const st = STATUS[t.status] || STATUS.paused;
    // 没变就整张卡片都不碰。300 个任务时这一句省掉的是每秒几千次样式写入
    const sig = t.status + '|' + t.done + '|' + t.size + '|' + t.speed + '|' + t.eta
      + '|' + (t.stage || '') + '|' + (t.error || '') + '|' + (t.verify || '')
      + '|' + (t.signature || '') + '|' + (t.name || '') + '|' + (t.finishedAt || 0)
      + '|' + (t.connections || 0) + '|' + (t.warning || '');
    if (el._sig === sig) return;
    el._sig = sig;

    const ref = el._refs;
    el.className = 'task ' + st.cls;
    ref.icon.className = 'task-icon kind-' + (t.kind || 'HTTP').toLowerCase()
      + (t.status === 'completed' ? ' state-done' : t.status === 'error' ? ' state-error' : '');
    ref.iconUse.setAttribute('href', '#' + (KIND_ICON[t.kind] || 'i-download'));

    if (ref.name.textContent !== t.name) ref.name.textContent = t.name || '未命名任务';
    ref.name.title = t.name || '';
    ref.chip.className = 'chip ' + st.chip;
    ref.chip.textContent = st.text;

    const meta = [];
    meta.push(t.size ? `<span><b>${fmtSize(t.size)}</b></span>` : `<span><b>大小未知</b></span>`);
    if (t.kind === 'HLS') meta.push('<span>HLS 分片</span>');
    if (t.kind === 'BT') meta.push('<span>BT / 磁力</span>');
    if (t.kind === 'HTTP' && t.connections) meta.push(`<span>${t.connections} 连接</span>`);
    if (t.verify === 'ok' && t.sha256) meta.push('<span>SHA-256 与基准一致</span>');
    if (t.signature) {
      const label = t.signature === 'verified' ? 'APK 签名已验证'
        : t.signature === 'failed' ? 'APK 签名校验失败'
          : 'APK 签名未验证';
      meta.push(`<span>${label}</span>`);
    }
    const metaHtml = meta.join('<span class="dot">·</span>');
    if (ref.meta.innerHTML !== metaHtml) ref.meta.innerHTML = metaHtml;

    const p = pct(t);
    // 服务器没给 Content-Length：显示不确定进度，不伪装成 0% 或某个假百分比
    const unknown = !t.size && (t.status === 'downloading' || t.status === 'queued');
    el.classList.toggle('is-unknown', unknown);
    ref.bar.style.width = unknown ? '100%' : p.toFixed(2) + '%';

    if (t.status === 'downloading') {
      ref.live.textContent = fmtSpeed(t.speed);
      ref.rest.textContent = t.stage || (t.size ? `剩余 ${fmtEta(t.eta)}` : '正在获取文件信息');
      ref.err.textContent = '';
    } else if (t.status === 'verifying') {
      ref.live.textContent = '校验中';
      ref.rest.textContent = t.stage || '计算哈希';
      ref.err.textContent = '';
    } else if (t.status === 'completed') {
      ref.live.textContent = '已完成';
      ref.rest.textContent = `${fmtSize(t.size)} · ${fmtTime(t.finishedAt)}`;
      ref.err.textContent = '';
    } else if (t.status === 'error') {
      ref.live.textContent = '失败';
      ref.rest.textContent = '';
      ref.err.textContent = t.error || '未知错误';
      ref.err.title = t.error || '';
    } else if (t.status === 'queued') {
      ref.live.textContent = '排队中';
      ref.rest.textContent = '等待空闲槽位';
      ref.err.textContent = '';
    } else {
      ref.live.textContent = st.text;
      ref.rest.textContent = t.size ? `${fmtSize(t.done)} / ${fmtSize(t.size)}` : '';
      ref.err.textContent = '';
    }

    const actsHtml = actionsFor(t);
    if (ref.actions._html !== actsHtml) {
      ref.actions.innerHTML = actsHtml;
      ref.actions._html = actsHtml;
    }
  }

  function actionsFor(t) {
    const a = [];
    if (t.status === 'downloading' || t.status === 'queued') {
      a.push(`<button class="icon-btn" data-act="pause" title="暂停" aria-label="暂停">${icon('i-pause')}</button>`);
    } else if (t.status === 'paused') {
      a.push(`<button class="icon-btn" data-act="resume" title="继续" aria-label="继续">${icon('i-play')}</button>`);
    } else if (t.status === 'error') {
      a.push(`<button class="icon-btn" data-act="retry" title="重试" aria-label="重试">${icon('i-refresh')}</button>`);
    }
    if (t.status === 'completed') {
      a.push(`<button class="icon-btn" data-act="open" title="打开文件" aria-label="打开文件">${icon('i-external')}</button>`);
    }
    if (t.finalPath || t.dir) {
      a.push(`<button class="icon-btn" data-act="reveal" title="所在文件夹" aria-label="所在文件夹">${icon('i-folder')}</button>`);
    }
    a.push(`<button class="icon-btn del" data-act="delete" title="删除任务" aria-label="删除任务">${icon('i-trash')}</button>`);
    return a.join('');
  }

  // ---------------------------------------------------------------- 操作

  async function doAction(act, id, btn) {
    const t = state.data && state.data.tasks.find((x) => x.id === id);
    if (!t) return;
    try {
      if (act === 'delete') {
        const ok = confirm(t.status === 'completed'
          ? '删除任务记录？\n\n已下载的文件会保留在磁盘上。'
          : '删除这个任务？\n\n未下完的临时文件会一起清理。');
        if (!ok) return;
        await api('/api/tasks/' + id, undefined, 'DELETE');
        if (state.drawerId === id) closeDrawer();
        toast('已删除');
        await loadState();
        return;
      }
      if (act === 'open' || act === 'reveal') {
        await api(`/api/tasks/${id}/${act}`);
        toast(act === 'open' ? '已交给系统打开' : '已在文件夹中打开');
        return;
      }
      await api(`/api/tasks/${id}/${act}`);
      if (btn) btn.disabled = true;
      await loadState();
    } catch (e) {
      toast(e.message, 'bad');
    }
  }

  // ---------------------------------------------------------------- 详情

  function openDrawer(id) {
    state.drawerId = id;
    const t = state.data.tasks.find((x) => x.id === id);
    if (!t) return;
    renderDrawer(t);
    $('#drawer').classList.add('show');
    $('#scrim').classList.add('show');
  }

  function closeDrawer() {
    state.drawerId = null;
    state.drawerKey = '';
    $('#drawer').classList.remove('show');
    if (!$('#newSheet').classList.contains('show') && !$('#dirSheet').classList.contains('show')) {
      $('#scrim').classList.remove('show');
    }
  }

  function renderDrawer(t) {
    if (!t) return;
    const st = STATUS[t.status] || STATUS.paused;
    const segs = t.segments || [];

    // 结构指纹：只有这些字段变了才重建 DOM。速度与进度走下面的原地更新，
    // 否则抽屉打开时每秒重写一次 innerHTML，按钮会被替换，正在点的鼠标就落空了。
    const key = [t.id, t.status, t.name, t.size, t.verify, t.signature, t.warning, t.error,
      t.finalPath, t.dir, t.url, t.sha256, t.connections, t.createdAt, t.finishedAt,
      t.signer, t.signerSubject, t.signatureDetail, segs.length].join('|');

    if (key !== state.drawerKey) {
      state.drawerKey = key;

      const rows = [
        ['文件大小', t.size ? fmtSize(t.size) : '未知（服务器未提供 Content-Length）'],
        ['已下载', fmtSize(t.done) + (t.size ? `（${pct(t).toFixed(1)}%）` : '')],
        ['连接数', t.connections || '—'],
        ['开始时间', fmtTime(t.createdAt)],
        ['结束时间', t.finishedAt ? fmtTime(t.finishedAt) : '—'],
        ['来源类型', t.kind],
        ['状态', st.text]
      ];
      const grid = rows.map(([k, v]) => `<div><span>${k}</span><b>${esc(v)}</b></div>`).join('');
      const segGrid = segs.length > 1 ? `<div class="detail-block"><span>分段（共 ${segs.length} 段，颜色越满越接近完成）</span>
        <div class="seg-grid">${segs.map((s) => {
          const q = s.end >= s.start ? Math.min(100, (s.done / (s.end - s.start + 1)) * 100) : 0;
          return `<i style="--p:${q.toFixed(1)}%" title="${(q).toFixed(1)}%"></i>`;
        }).join('')}</div></div>` : '';

      const hashBlock = t.sha256 ? `
        <div class="detail-block"><span>SHA-256</span>
          <div class="copy-row"><code>${esc(t.sha256)}</code>
          <button class="icon-btn" data-copy="${esc(t.sha256)}" aria-label="复制 SHA-256">${icon('i-copy')}</button></div>
        </div>` : '';

      const sigState = t.signature === 'verified' ? '已验证'
        : t.signature === 'failed' ? '校验失败'
          : t.signature === 'unsupported' ? '未验证' : '未验证';
      const sigBlock = (t.signer || t.signatureDetail) ? `
        <div class="detail-block"><span>APK 签名（${esc(t.scheme || '未知方案')}）· ${sigState}</span>
          ${t.signer ? `<div class="copy-row"><code>${esc(t.signer)}</code>
          <button class="icon-btn" data-copy="${esc(t.signer)}" aria-label="复制证书指纹">${icon('i-copy')}</button></div>` : ''}
          ${t.signerSubject ? `<code>${esc(t.signerSubject)}</code>` : ''}
          ${t.signatureDetail ? `<code>${esc(t.signatureDetail)}</code>` : ''}
        </div>` : '';

      const errBlock = t.status === 'error' && t.error ? `<div class="error-box">${esc(t.error)}</div>` : '';
      const warnBlock = t.warning ? `<div class="warning-box">${esc(t.warning)}</div>` : '';
      const verifyBlock = t.verify === 'mismatch'
        ? `<div class="error-box">哈希与期望值不一致，文件${t.finalPath ? '已隔离到：' + esc(t.finalPath) : '未通过校验'}</div>` : '';

      let acts = '';
      if (t.status === 'downloading' || t.status === 'queued') acts += `<button class="ghost-btn" data-act="pause">${icon('i-pause')}暂停</button>`;
      if (t.status === 'paused' || t.status === 'error') acts += `<button class="primary-btn" data-act="${t.status === 'error' ? 'retry' : 'resume'}">${icon('i-play')}${t.status === 'error' ? '重试' : '继续'}</button>`;
      if (t.status === 'completed') acts += `<button class="primary-btn" data-act="open">${icon('i-external')}打开文件</button>`;
      if (t.finalPath || t.dir) acts += `<button class="ghost-btn" data-act="reveal">${icon('i-folder')}所在文件夹</button>`;

      $('#drawerTitle').textContent = st.text + ' · 详情';
      $('#drawerBody').innerHTML = `
        <div class="detail-name">${esc(t.name || '未命名任务')}</div>
        ${errBlock}${warnBlock}${verifyBlock}
        <div class="detail-progress">
          <div class="progress"><i style="width:${t.size ? pct(t).toFixed(2) + '%' : '100%'}"></i></div>
          <div class="task-bottom"><span class="live">${t.status === 'downloading' ? fmtSpeed(t.speed) : st.text}</span>
          <span class="done">${t.size ? fmtSize(t.done) + ' / ' + fmtSize(t.size) : fmtSize(t.done)}</span></div>
        </div>
        <div class="detail-actions">${acts}</div>
        <div class="detail-grid">${grid}</div>
        ${segGrid}
        <div class="detail-block"><span>下载地址</span>
          <div class="copy-row"><code>${esc(t.url || '—')}</code>
          <button class="icon-btn" data-copy="${esc(t.url || '')}" aria-label="复制链接">${icon('i-copy')}</button></div>
        </div>
        <div class="detail-block"><span>保存位置</span>
          <div class="copy-row"><code>${esc(t.finalPath || t.dir || '—')}</code>
          <button class="icon-btn" data-copy="${esc(t.finalPath || t.dir || '')}" aria-label="复制路径">${icon('i-copy')}</button></div>
        </div>
        ${hashBlock}${sigBlock}`;

      $$('[data-copy]', $('#drawerBody')).forEach((btn) => {
        btn.addEventListener('click', async () => {
          try {
            await navigator.clipboard.writeText(btn.dataset.copy);
            toast('已复制', 'good');
          } catch (e) {
            toast('复制失败，请手动选择', 'bad');
          }
        });
      });
      $$('[data-act]', $('#drawerBody')).forEach((btn) => {
        btn.addEventListener('click', () => doAction(btn.dataset.act, t.id, btn));
      });

      state.drawerRefs = {
        bar: $('#drawerBody .detail-progress .progress > i'),
        live: $('#drawerBody .detail-progress .live'),
        done: $('#drawerBody .detail-progress .done'),
        segs: $$('#drawerBody .seg-grid i')
      };
    }

    const refs = state.drawerRefs || {};
    if (refs.bar) refs.bar.style.width = t.size ? pct(t).toFixed(2) + '%' : '100%';
    if (refs.live) refs.live.textContent = t.status === 'downloading' ? fmtSpeed(t.speed) : st.text;
    if (refs.done) refs.done.textContent = t.size ? fmtSize(t.done) + ' / ' + fmtSize(t.size) : fmtSize(t.done);
    if (refs.segs && refs.segs.length) {
      segs.forEach((s, i) => {
        const el = refs.segs[i];
        if (!el) return;
        const q = s.end >= s.start ? Math.min(100, (s.done / (s.end - s.start + 1)) * 100) : 0;
        el.style.setProperty('--p', q.toFixed(1) + '%');
        el.title = q.toFixed(1) + '%';
      });
    }
  }

  // ---------------------------------------------------------------- 视图切换

  function setView(view) {
    state.view = view;
    state.limit = 60;
    const isSettings = view === 'settings';
    $('#viewDownloads').hidden = isSettings;
    $('#viewSettings').hidden = !isSettings;
    $$('.nav-item, .tabbar button').forEach((el) => {
      el.classList.toggle('is-active', el.dataset.view === view);
    });
    const titles = { all: ['全部任务', '多连接、断点续传，下完自动校验'], active: ['进行中', '正在下载与排队中的任务'], done: ['已完成', '校验通过的文件'], settings: ['设置', '改完即时生效'] };
    $('#pageTitle').textContent = titles[view][0];
    $('#pageSub').textContent = titles[view][1];
    if (view === 'active' || view === 'done') {
      state.filter = view === 'active' ? 'active' : 'done';
      $$('.seg-filter button').forEach((b) => b.classList.toggle('is-active', b.dataset.filter === state.filter));
    }
    $('#fabBtn').style.display = view === 'settings' ? 'none' : '';
    $('#newTaskBtn').hidden = isSettings;
    $('#saveTopBtn').hidden = !isSettings;
    // 设置页没有可搜的东西，隐藏搜索框，把移动端顶栏位置让给「保存」
    document.querySelectorAll('.search').forEach((el) => { el.hidden = isSettings; });
    window.scrollTo({ top: 0, behavior: 'instant' in window ? 'instant' : 'auto' });
    renderList();
  }

  // ---------------------------------------------------------------- 设置

  async function refreshHookInfo() {
    try {
      const info = await api('/api/hook/info', undefined, 'GET');
      const codeEl = $('#hookPairCode');
      if (codeEl && document.activeElement !== codeEl) codeEl.value = info.pairCode || '';
      const note = $('#hookPairNote');
      if (note) {
        note.textContent = info.paired
          ? '已配对：扩展可以直接投递。重置后旧扩展会失效。'
          : '还没配对：打开扩展图标填入上面的 8 位数字。';
        note.className = 'inline-note' + (info.paired ? ' ok' : '');
      }
    } catch (e) {
      /* 设置页没打开时不必提示 */
    }
  }

  function fillSettings(s, env, onlyIfIdle) {
    if (!s) return;
    const set = (id, v) => {
      const el = $(id);
      if (!el) return;
      if (document.activeElement === el) return;
      if (el.type === 'checkbox') el.checked = !!v;
      else el.value = v == null ? '' : v;
    };
    set('#setDir', s.saveDir);
    set('#setConcurrent', s.maxConcurrent);
    set('#setConn', s.connections);
    set('#setLimit', s.speedLimitKB);
    set('#setUA', s.userAgent);
    set('#setReferer', s.referer);
    set('#setProxy', s.proxy);
    set('#setAutoResume', s.autoResume);
    set('#setVerifyApk', s.verifyApk);
    set('#setQuarantine', s.quarantineOnMismatch);
    set('#setHlsRemux', s.hlsRemux);
    set('#setAria2', s.aria2Path);
    set('#setHookEnabled', s.hookEnabled);
    refreshHookInfo();
    $('#valConcurrent').textContent = s.maxConcurrent;
    $('#valConn').textContent = s.connections;
    $('#newDir').value = $('#newDir').value || s.saveDir;
    $('#newConn').value = $('#newConn').value || s.connections;
    const lanUrl = env && env.lanUrl ? env.lanUrl : '';
    $('#lanNote').innerHTML = lanUrl
      ? '已开启：手机连同一 WiFi，打开 <a href="' + esc(lanUrl) + '" target="_blank" rel="noopener">' + esc(lanUrl) + '</a>'
      : (s.lan ? '已开启，重启桌面版后生效' : '当前仅本机可访问（手机想用就加 --lan 重启）');
    $('#lanNote').className = 'inline-note' + (s.lan ? ' ok' : '');
    const envLan = $('#envLan');
    if (envLan) {
      if (lanUrl) {
        envLan.innerHTML = '<a href="' + esc(lanUrl) + '" target="_blank" rel="noopener">' + esc(lanUrl) + '</a>';
      } else {
        envLan.textContent = '未开启';
      }
    }
    if (env) {
      $('#envVersion').textContent = 'Desktop ' + (state.data ? state.data.version : '');
      $('#envOs').textContent = env.os || '—';
      $('#envJava').textContent = env.java || '—';
      $('#envBase').textContent = env.baseDir || '—';
      $('#envFfmpeg').textContent = env.ffmpeg || '未检测到（HLS 保留 .ts）';
      $('#envAria2').textContent = env.aria2 ? '已检测到：' + env.aria2 : '未检测到 aria2c，磁力任务会提示安装';
      $('#envAria2').className = 'inline-note' + (env.aria2 ? ' ok' : '');
    }
    const theme = s.theme || 'auto';
    $$('.theme-choice button').forEach((b) => b.classList.toggle('is-active', b.dataset.themeChoice === theme));
  }

  function collectSettings() {
    return {
      saveDir: $('#setDir').value.trim(),
      maxConcurrent: Number($('#setConcurrent').value),
      connections: Number($('#setConn').value),
      speedLimitKB: Number($('#setLimit').value) || 0,
      userAgent: $('#setUA').value.trim(),
      referer: $('#setReferer').value.trim(),
      proxy: $('#setProxy').value.trim(),
      theme: ($$('.theme-choice button').find((b) => b.classList.contains('is-active')) || {}).dataset?.themeChoice || 'auto',
      lan: state.data ? state.data.settings.lan : false,
      port: state.data ? state.data.settings.port : 8765,
      autoResume: $('#setAutoResume').checked,
      verifyApk: $('#setVerifyApk').checked,
      quarantineOnMismatch: $('#setQuarantine').checked,
      hlsRemux: $('#setHlsRemux').checked,
      aria2Path: $('#setAria2').value.trim(),
      hookEnabled: $('#setHookEnabled').checked
    };
  }

  async function saveSettings() {
    try {
      const body = collectSettings();
      // 保存目录走单独接口：服务端会拒绝在普通设置里偷偷换写入根目录
      if (state.data && body.saveDir !== state.data.settings.saveDir) {
        await api('/api/settings/save-dir', { dir: body.saveDir });
      }
      await api('/api/settings', body);
      state.editSettings = false;
      toast('设置已保存', 'good');
      $('#saveHint').textContent = '已保存 ' + new Date().toLocaleTimeString();
      await loadState();
    } catch (e) {
      toast(e.message, 'bad');
    }
  }

  // ---------------------------------------------------------------- 新建任务

  function openNew() {
    $('#newHint').textContent = '';
    $('#newUrls').value = '';
    if (state.data) {
      $('#newDir').value = state.data.settings.saveDir;
      $('#newConn').value = state.data.settings.connections;
    }
    $('#newSheet').classList.add('show');
    $('#scrim').classList.add('show');
    setTimeout(() => $('#newUrls').focus(), 120);
  }

  function closeSheets() {
    $('#newSheet').classList.remove('show');
    $('#dirSheet').classList.remove('show');
    if (!state.drawerId) $('#scrim').classList.remove('show');
  }

  async function submitNew(ev) {
    ev.preventDefault();
    const raw = $('#newUrls').value;
    const urls = raw.split(/\s+/).map((s) => s.trim()).filter(Boolean);
    if (!urls.length) { $('#newHint').textContent = '先粘贴一个链接'; return; }
    const dir = $('#newDir').value.trim();
    const conn = Number($('#newConn').value) || 8;
    const sha = $('#newSha').value.trim();
    try {
      await api('/api/tasks', { urls, dir, connections: conn, sha256: sha });
      closeSheets();
      toast(urls.length > 1 ? `已添加 ${urls.length} 个任务` : '已开始下载', 'good');
      setView('all');
      await loadState();
    } catch (e) {
      $('#newHint').textContent = e.message;
      toast(e.message, 'bad');
    }
  }

  // ---------------------------------------------------------------- 目录选择

  async function openBrowser(targetInput) {
    state.dirTarget = targetInput;
    const start = targetInput.value.trim() || (state.data ? state.data.settings.saveDir : '');
    $('#dirSheet').classList.add('show');
    $('#scrim').classList.add('show');
    await browseTo(start);
  }

  async function browseTo(path) {
    try {
      const data = await api('/api/browse?path=' + encodeURIComponent(path || ''), undefined, 'GET');
      state.dirCurrent = data.path;
      $('#dirPath').value = data.path;
      const items = [];
      if (data.parent) {
        items.push(`<button class="dir-item" data-path="${esc(data.parent)}">${icon('i-back')}<span>上一级</span></button>`);
      }
      (data.dirs || []).forEach((d) => {
        items.push(`<button class="dir-item" data-path="${esc(d.path)}">${icon('i-folder')}<span>${esc(d.name)}</span></button>`);
      });
      if (!data.dirs || !data.dirs.length) {
        items.push('<div class="dir-item" style="cursor:default;color:var(--text-3)">这个目录下没有子文件夹</div>');
      }
      $('#dirList').innerHTML = items.join('');
      $$('#dirList [data-path]').forEach((btn) => {
        btn.addEventListener('click', () => browseTo(btn.dataset.path));
      });
      $('#dirUp').onclick = () => data.parent && browseTo(data.parent);
    } catch (e) {
      toast(e.message, 'bad');
    }
  }

  // ---------------------------------------------------------------- 拖拽

  function setupDrop() {
    let depth = 0;
    window.addEventListener('dragenter', (e) => {
      e.preventDefault();
      depth++;
      document.body.style.outline = '2px dashed var(--accent)';
      document.body.style.outlineOffset = '-10px';
    });
    window.addEventListener('dragleave', () => {
      depth = Math.max(0, depth - 1);
      if (!depth) document.body.style.outline = '';
    });
    window.addEventListener('dragover', (e) => e.preventDefault());
    window.addEventListener('drop', async (e) => {
      e.preventDefault();
      depth = 0;
      document.body.style.outline = '';
      const files = Array.from(e.dataTransfer.files || []);
      const torrent = files.find((f) => f.name.toLowerCase().endsWith('.torrent'));
      if (torrent) {
        try {
          const buf = await torrent.arrayBuffer();
          const res = await fetch('/api/torrent?name=' + encodeURIComponent(torrent.name), {
            method: 'POST',
            headers: { 'Content-Type': 'application/octet-stream', 'X-DG-Token': DG_TOKEN },
            body: buf
          });
          const data = await res.json();
          if (!res.ok) throw new Error(data.error || '上传失败');
          toast('种子已加入：' + torrent.name, 'good');
          setView('all');
          await loadState();
        } catch (err) {
          toast(err.message, 'bad');
        }
        return;
      }
      const text = e.dataTransfer.getData('text/uri-list') || e.dataTransfer.getData('text/plain');
      if (text && /^(https?|magnet):/i.test(text.trim())) {
        openNew();
        $('#newUrls').value = text.trim();
      }
    });
  }

  // ---------------------------------------------------------------- 事件绑定

  function bind() {
    $$('.nav-item, .tabbar button').forEach((el) => {
      el.addEventListener('click', () => setView(el.dataset.view));
    });
    $$('.seg-filter button').forEach((el) => {
      el.addEventListener('click', () => {
        state.filter = el.dataset.filter;
        state.limit = 60;
        $$('.seg-filter button').forEach((b) => b.classList.toggle('is-active', b === el));
        renderList();
      });
    });
    $('#searchInput').addEventListener('input', (e) => {
      state.search = e.target.value;
      state.limit = 60;
      renderList();
    });
    const more = $('#listMore');
    if (more) {
      more.addEventListener('click', () => {
        state.limit += 120;
        renderList();
      });
    }
    $('#newTaskBtn').addEventListener('click', openNew);
    $('#emptyNewBtn').addEventListener('click', openNew);
    $('#fabBtn').addEventListener('click', openNew);
    $('#newForm').addEventListener('submit', submitNew);
    $('#drawerClose').addEventListener('click', closeDrawer);
    $('#drawerMore').addEventListener('click', () => {
      if (state.drawerId) doAction('delete', state.drawerId);
    });
    $('#scrim').addEventListener('click', () => { closeSheets(); closeDrawer(); });
    $$('[data-close]').forEach((el) => el.addEventListener('click', closeSheets));

    $('#themeBtn').addEventListener('click', () => {
      const order = ['auto', 'light', 'dark'];
      const next = order[(order.indexOf(currentTheme()) + 1) % 3];
      applyTheme(next);
      $$('.theme-choice button').forEach((b) => b.classList.toggle('is-active', b.dataset.themeChoice === next));
      toast(next === 'auto' ? '跟随系统主题' : next === 'light' ? '浅色主题' : '深色主题');
    });

    $$('.theme-choice button').forEach((b) => {
      b.addEventListener('click', () => {
        applyTheme(b.dataset.themeChoice);
        $$('.theme-choice button').forEach((x) => x.classList.toggle('is-active', x === b));
      });
    });

    $('#browseBtn').addEventListener('click', () => openBrowser($('#setDir')));
    $('#newBrowse').addEventListener('click', () => openBrowser($('#newDir')));
    $('#dirPick').addEventListener('click', () => {
      if (state.dirTarget && state.dirCurrent) {
        state.dirTarget.value = state.dirCurrent;
        if (state.dirTarget.id === 'setDir') {
          state.editSettings = true;
          $('#saveHint').textContent = '目录已改，记得保存设置';
        }
      }
      closeSheets();
    });

    ['#setDir', '#setConcurrent', '#setConn', '#setLimit', '#setUA', '#setReferer', '#setProxy',
      '#setAutoResume', '#setVerifyApk', '#setQuarantine', '#setHlsRemux', '#setAria2'].forEach((sel) => {
      const el = $(sel);
      if (!el) return;
      el.addEventListener('input', () => {
        state.editSettings = true;
        $('#saveHint').textContent = '有未保存的修改';
        if (sel === '#setConcurrent') $('#valConcurrent').textContent = el.value;
        if (sel === '#setConn') $('#valConn').textContent = el.value;
      });
      el.addEventListener('change', () => {
        state.editSettings = true;
        if (sel === '#setConcurrent') $('#valConcurrent').textContent = el.value;
        if (sel === '#setConn') $('#valConn').textContent = el.value;
      });
    });

    $('#saveSettings').addEventListener('click', saveSettings);
    $('#copyPairCode').addEventListener('click', async () => {
      const code = $('#hookPairCode').value;
      if (!code) return;
      try {
        await navigator.clipboard.writeText(code);
        toast('配对码已复制', 'good');
      } catch (e) {
        toast('复制失败，请手动选中', 'bad');
      }
    });
    $('#downloadExtension').addEventListener('click', () => {
      // 走会话 Cookie 鉴权，浏览器直接下载 zip
      const a = document.createElement('a');
      a.href = '/api/hook/extension.zip';
      a.download = 'DownloadGuard-extension.zip';
      document.body.appendChild(a);
      a.click();
      a.remove();
      toast('已开始下载扩展，解压后加载', 'good');
    });
    $('#resetPair').addEventListener('click', async () => {
      try {
        const r = await api('/api/hook/reset-pair', {});
        $('#hookPairCode').value = r.pairCode || '';
        toast('已重置，扩展需要重新配对', 'good');
        refreshHookInfo();
      } catch (e) {
        toast(e.message, 'bad');
      }
    });
    $('#saveTopBtn').addEventListener('click', saveSettings);
    $('#resetSettings').addEventListener('click', async () => {
      if (!confirm('恢复默认设置？保存目录会回到系统下载文件夹。')) return;
      try {
        await api('/api/settings', {
          saveDir: '', maxConcurrent: 3, connections: 8, speedLimitKB: 0,
          userAgent: navigator.userAgent, referer: '', proxy: '', theme: 'auto',
          lan: state.data.settings.lan, port: state.data.settings.port,
          autoResume: true, verifyApk: true, quarantineOnMismatch: true, hlsRemux: true, aria2Path: ''
        });
        state.settingsLoaded = false;
        state.editSettings = false;
        await loadState();
        toast('已恢复默认', 'good');
      } catch (e) {
        toast(e.message, 'bad');
      }
    });
    $('#openDirBtn').addEventListener('click', () => api('/api/open-dir').catch((e) => toast(e.message, 'bad')));
    $('#openDataDir').addEventListener('click', () => api('/api/open-dir').catch(() => {}));
    $('#pauseAllBtn').addEventListener('click', async () => {
      try {
        const r = await api('/api/tasks/all/pause');
        await loadState();
        toast(r.affected ? `已暂停 ${r.affected} 个任务` : '没有正在进行的任务');
      } catch (e) {
        toast(e.message, 'bad');
      }
    });
    $('#resumeAllBtn').addEventListener('click', async () => {
      try {
        const r = await api('/api/tasks/all/resume');
        await loadState();
        toast(r.affected ? `已继续 ${r.affected} 个任务` : '没有暂停中的任务');
      } catch (e) {
        toast(e.message, 'bad');
      }
    });
    $('#detectAria2').addEventListener('click', async () => {
      await loadState();
      const env = state.data.env;
      toast(env.aria2 ? '已检测到 aria2c' : '没找到 aria2c，可按设置页说明安装', env.aria2 ? 'good' : 'bad');
      $('#envAria2').textContent = env.aria2 ? '已检测到：' + env.aria2 : '未检测到 aria2c，磁力任务会提示安装';
      $('#envAria2').className = 'inline-note' + (env.aria2 ? ' ok' : '');
    });
    $('#clearBtn').addEventListener('click', async () => {
      if (!confirm('清理已完成、失败和已取消的记录？文件不会被删除。')) return;
      try {
        await api('/api/clear');
        await loadState();
        toast('已清理');
      } catch (e) {
        toast(e.message, 'bad');
      }
    });

    document.addEventListener('keydown', (e) => {
      const typing = /input|textarea|select/i.test(document.activeElement.tagName);
      if (e.key === 'Escape') {
        closeSheets();
        closeDrawer();
        if (document.activeElement === $('#searchInput')) $('#searchInput').blur();
        return;
      }
      if (typing) return;
      if (e.key === '/') {
        e.preventDefault();
        $('#searchInput').focus();
      } else if (e.key === 'n' || e.key === 'N') {
        e.preventDefault();
        openNew();
      }
    });

    window.addEventListener('focus', loadState);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) loadState();
    });
  }

  // ---------------------------------------------------------------- 启动

  function boot() {
    let theme = 'auto';
    try { theme = localStorage.getItem('dg-theme') || 'auto'; } catch (e) { /* ignore */ }
    applyTheme(theme);
    bind();
    setupDrop();
    loadState().then(connectStream);
    // 本机与 HTTPS 才能装 PWA；局域网 http 下浏览器不允许注册，静默跳过
    if ('serviceWorker' in navigator &&
        (location.protocol === 'https:' || ['127.0.0.1', 'localhost'].includes(location.hostname))) {
      navigator.serviceWorker.register('sw.js').catch(() => {});
    }
    // 主题按钮的图标与设置项保持一致
    $$('.theme-choice button').forEach((b) => b.classList.toggle('is-active', b.dataset.themeChoice === theme));
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
