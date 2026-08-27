// 控制台脚本：只读接口无需登录，写接口带 HTTP Basic。
// 接口路径相对当前页面目录，兼容 /msgcenter 前缀。
const CONTEXT_PATH = window.location.pathname.replace(/\/[^/]*$/, '');
const HUB_BASE = `${CONTEXT_PATH}/api/hub`;

const el = (id) => document.getElementById(id);

function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// Compact large numbers so stat cards never overflow: 12,345 -> 1.2万, 120,000,000 -> 1.2亿.
function fmtCompact(n) {
  if (n === null || n === undefined) return '–';
  const v = Number(n);
  if (!isFinite(v)) return '–';
  if (Math.abs(v) >= 1e8) return (v / 1e8).toFixed(1) + '亿';
  if (Math.abs(v) >= 1e4) return (v / 1e4).toFixed(1) + '万';
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 0 });
}

const CHANNEL_LABEL = { 1: '邮件', 2: '短信', 3: '飞书' };
const SOURCE_LABEL = {
  'biz-account': '账户安全', 'biz-trade': '交易订单', 'biz-marketing': '营销触达',
  'biz-system': '系统告警',
};
const MSG_STATUS = {
  1: { label: '待推送', cls: 'bg-secondary' },
  2: { label: '处理中', cls: 'bg-blue' },
  3: { label: '成功', cls: 'bg-green' },
  4: { label: '失败', cls: 'bg-red' },
};
const PRIORITY_LABEL = { 1: '低', 2: '中', 3: '高', 4: '重试' };

function sourceLabel(id) { return SOURCE_LABEL[id] || id || '—'; }
function channelLabel(c) { return CHANNEL_LABEL[c] || '未知'; }
function statusBadge(status) {
  const s = MSG_STATUS[status] || { label: String(status), cls: 'bg-orange' };
  return `<span class="badge ${s.cls} text-white">${escapeHtml(s.label)}</span>`;
}

async function fetchJson(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function setConnected(ok) {
  el('connText').textContent = ok ? '后端在线' : '后端离线';
  el('connBadge').classList.toggle('offline', !ok);
}

// ============================================================================
// AUTH: public read-only view; write actions require login. The backend guards
// every write endpoint with HTTP Basic (/msg/** and /api/hub/simulate). Login
// here collects username+password, verifies against a harmless authenticated
// GET, and keeps them for the tab's lifetime (sessionStorage only).
// ============================================================================

const SESSION_KEY = 'msghub.ops';
let session = null;

function loadSession() {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (_) { return null; }
}
function saveSession(s) {
  session = s;
  if (s) sessionStorage.setItem(SESSION_KEY, JSON.stringify(s));
  else sessionStorage.removeItem(SESSION_KEY);
}
function isLoggedIn() { return !!(session && session.user && session.pass); }
function opsAuthHeaders() {
  if (!isLoggedIn()) return {};
  return { Authorization: 'Basic ' + btoa(`${session.user}:${session.pass}`) };
}

// Verify a credential against an authenticated, side-effect-free endpoint.
async function verifyCredential(user, pass) {
  const res = await fetch(`${CONTEXT_PATH}/msg/get_template?templateId=__login_probe__`, {
    headers: { Authorization: 'Basic ' + btoa(`${user}:${pass}`) },
  });
  if (res.status === 200) return true;
  if (res.status === 401 || res.status === 403) return false;
  throw new Error(`验证服务异常 HTTP ${res.status}`);
}

function applyAuthState() {
  const on = isLoggedIn();
  el('loginNavBtn').classList.toggle('d-none', on);
  el('userMenu').classList.toggle('d-none', !on);
  el('readonlyBanner').classList.toggle('d-none', on);
  if (on) {
    el('userMenuName').textContent = session.user;
    el('userAvatar').textContent = session.user.substring(0, 2).toUpperCase();
  }
  document.querySelectorAll('.requires-auth').forEach((elm) => {
    elm.disabled = !on;
    if (!on) elm.setAttribute('title', '请先登录'); else elm.removeAttribute('title');
  });
  document.querySelectorAll('.requires-auth-badge').forEach((b) => b.classList.toggle('d-none', on));
}

function openLoginModal() {
  el('loginError').textContent = '';
  el('loginOverlay').classList.remove('d-none');
  document.body.classList.add('modal-open-lite');
  setTimeout(() => el('loginUser').focus(), 50);
}
function closeLoginModal() {
  el('loginOverlay').classList.add('d-none');
  document.body.classList.remove('modal-open-lite');
}

async function handleLogin(e) {
  e.preventDefault();
  const user = el('loginUser').value.trim();
  const pass = el('loginPass').value;
  const btn = el('loginSubmit');
  const errEl = el('loginError');
  errEl.textContent = '';
  if (!user || !pass) { errEl.textContent = '请输入用户名和密码'; return; }
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>登录中…';
  try {
    const ok = await verifyCredential(user, pass);
    if (!ok) { errEl.textContent = '用户名或密码错误'; return; }
    saveSession({ user, pass });
    applyAuthState();
    closeLoginModal();
    el('loginPass').value = '';
    loadStats();
    if (currentViewFromHash() === 'messages') loadMessages();
    if (currentViewFromHash() === 'messaging') loadTemplates();
  } catch (err) {
    errEl.textContent = err.message;
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<i class="ti ti-login-2 me-1"></i>登录';
  }
}

function handleLogout(e) {
  if (e) e.preventDefault();
  saveSession(null);
  applyAuthState();
  mineTemplates = [];
  pendingSelectTemplateId = null;
  renderTemplateOptions();
  loadStats();
  if (currentViewFromHash() === 'messages') loadMessages();
}

function ensureLoggedIn() {
  if (isLoggedIn()) return true;
  openLoginModal();
  return false;
}

// ============================================================================
// Dashboard: platform KPIs + two charts, all from the DB (/api/hub/stats).
// ============================================================================

let channelChart;
let sourceChart;
const CHART_COLORS = ['#2f6fed', '#2fb344', '#f76707', '#ae3ec9', '#17a2b8', '#f59f00', '#d63939'];

function renderChannelChart(byChannel) {
  const labels = byChannel.map((x) => x.name);
  const data = byChannel.map((x) => x.count);
  if (channelChart) {
    channelChart.data.labels = labels;
    channelChart.data.datasets[0].data = data;
    channelChart.update();
    return;
  }
  channelChart = new Chart(el('channelChart'), {
    type: 'doughnut',
    data: { labels, datasets: [{ data, backgroundColor: CHART_COLORS }] },
    options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom' } } },
  });
}

function renderSourceChart(bySource) {
  const labels = bySource.map((x) => x.name);
  const data = bySource.map((x) => x.count);
  if (sourceChart) {
    sourceChart.data.labels = labels;
    sourceChart.data.datasets[0].data = data;
    sourceChart.update();
    return;
  }
  sourceChart = new Chart(el('sourceChart'), {
    type: 'bar',
    data: { labels, datasets: [{ label: '消息量', data, backgroundColor: '#2f6fed', borderRadius: 6 }] },
    options: {
      responsive: true, maintainAspectRatio: false, indexAxis: 'y',
      plugins: { legend: { display: false } },
      scales: { x: { beginAtZero: true, ticks: { precision: 0 } } },
    },
  });
}

async function loadStats() {
  try {
    const s = await fetchJson(`${HUB_BASE}/stats`, { headers: opsAuthHeaders() });
    el('statToday').textContent = fmtCompact(s.todayTotal);
    el('statSuccessRate').textContent = s.todayTotal > 0 ? Math.round(s.successRate * 100) + '%' : '—';
    el('statPending').textContent = fmtCompact(s.todayPending);
    el('statFailed').textContent = fmtCompact(s.todayFailed);
    el('statSources').textContent = fmtCompact(s.connectedSources);
    el('statGrand').textContent = fmtCompact(s.grandTotal);
    renderChannelChart(s.byChannel || []);
    renderSourceChart(s.bySource || []);
    setConnected(true);
  } catch (err) {
    setConnected(false);
  }
}

// ============================================================================
// Messages: DB-backed recent message list (/api/hub/messages).
// ============================================================================

function renderMessages(rows) {
  el('msgTableBody').innerHTML = rows.map((m) => `
    <tr>
      <td class="text-secondary" style="white-space:nowrap">${escapeHtml(m.createTime)}</td>
      <td>${escapeHtml(sourceLabel(m.sourceId))}</td>
      <td>${escapeHtml(channelLabel(m.channel))}</td>
      <td class="text-truncate" style="max-width:160px">${escapeHtml(m.to)}</td>
      <td class="text-truncate" style="max-width:160px">${escapeHtml(m.subject)}</td>
      <td>${statusBadge(m.status)}</td>
    </tr>
  `).join('') || '<tr><td colspan="6" class="text-secondary text-center py-4">暂无消息，去「业务模拟」生成一批</td></tr>';
}

async function loadMessages() {
  try {
    const rows = await fetchJson(`${HUB_BASE}/messages?limit=100`, { headers: opsAuthHeaders() });
    renderMessages(rows);
    setConnected(true);
  } catch (err) {
    setConnected(false);
  }
}

// ============================================================================
// Business simulator (/api/hub/simulate).
// ============================================================================

function renderSimSummary(result) {
  el('simSummaryCard').classList.remove('d-none');
  el('simSummaryHead').textContent =
    `共提交 ${result.total} 条，失败 ${result.failed} 条，耗时 ${result.elapsedMillis} ms`;
  el('simSummaryBody').innerHTML = (result.lines || []).map((l) => `
    <tr>
      <td>${escapeHtml(l.name)}</td>
      <td>${escapeHtml(l.channel)}</td>
      <td class="text-end">${l.submitted}</td>
      <td class="text-end ${l.failed ? 'text-red' : 'text-secondary'}">${l.failed}</td>
    </tr>
  `).join('') || '<tr><td colspan="4" class="text-secondary">无</td></tr>';
}

async function runSimulation(e) {
  e.preventDefault();
  if (!ensureLoggedIn()) return;
  const btn = el('runSimBtn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>生成中…';
  try {
    const params = new URLSearchParams({
      count: el('simCount').value,
      includeLark: el('includeLark').checked,
    });
    const result = await fetchJson(`${HUB_BASE}/simulate?${params.toString()}`, {
      method: 'POST', headers: opsAuthHeaders(),
    });
    renderSimSummary(result);
    setConnected(true);
    // Refresh dashboard + message list so the new traffic shows up immediately.
    loadStats();
    loadMessages();
  } catch (err) {
    setConnected(false);
    alert('模拟失败：' + err.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<i class="ti ti-player-play me-1"></i>生成消息';
  }
}

// ============================================================================
// Interactive single-send: pick a line, edit its fields, send ONE message the
// user assembled by hand (/api/hub/sample to prefill, /api/hub/emit to send).
// ============================================================================

const BIZ_LINES = [
  { source: 'biz-account', name: '账户安全', channel: '短信', priority: 3, icon: 'ti-shield-lock', color: 'blue',
    desc: '验证码 · 异地登录 · 改密', fields: ['event', 'detail'],
    toHint: '短信渠道：填手机号。当前为日志桩，点发送即显示“已发送”。' },
  { source: 'biz-trade', name: '交易订单', channel: '短信', priority: 2, icon: 'ti-shopping-cart', color: 'green',
    desc: '下单 · 支付 · 发货 · 退款', fields: ['event', 'orderNo', 'detail'],
    toHint: '短信渠道：填手机号。当前为日志桩，点发送即显示“已发送”。' },
  { source: 'biz-marketing', name: '营销触达', channel: '邮件', priority: 1, icon: 'ti-discount', color: 'pink',
    desc: '优惠券 · 大促 · 积分', fields: ['event', 'detail'],
    toHint: '邮件渠道：填邮箱。当前为日志桩，点发送即显示“已发送”。' },
  { source: 'biz-system', name: '系统告警', channel: '飞书', priority: 3, icon: 'ti-server-bolt', color: 'red',
    desc: '错误率 · 延迟 · 资源', fields: ['event', 'detail'],
    toHint: '飞书渠道：会真实推送到群！收件人任意（如 ops）。' },
];
const FIELD_LABEL = { event: '事件', detail: '详情', orderNo: '订单号' };
const PRIORITY_TEXT = { 1: '低', 2: '中', 3: '高' };
let selectedLine = null;

function bizLineBySource(source) { return BIZ_LINES.find((l) => l.source === source) || null; }

function renderBizCards() {
  el('bizLineCards').innerHTML = BIZ_LINES.map((l) => `
    <div class="col-6 col-lg-3">
      <div class="card biz-card" data-source="${l.source}" role="button" tabindex="0">
        <div class="card-body">
          <div class="biz-icon bg-${l.color}-lt"><i class="ti ${l.icon}"></i></div>
          <div class="biz-name">${escapeHtml(l.name)}</div>
          <div class="biz-desc">${escapeHtml(l.desc)}</div>
          <div class="biz-meta"><span class="badge bg-${l.color}-lt">${escapeHtml(l.channel)}</span><span class="badge bg-secondary-lt">优先级 ${PRIORITY_TEXT[l.priority]}</span></div>
        </div>
      </div>
    </div>
  `).join('');
  el('bizLineCards').querySelectorAll('.biz-card').forEach((card) => {
    const pick = () => selectBizLine(card.dataset.source);
    card.addEventListener('click', pick);
    card.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(); } });
  });
}

function selectBizLine(source) {
  const line = bizLineBySource(source);
  if (!line) return;
  selectedLine = line;
  el('bizLineCards').querySelectorAll('.biz-card').forEach((c) => c.classList.toggle('selected', c.dataset.source === source));
  el('emitEmpty').classList.add('d-none');
  el('emitForm').classList.remove('d-none');
  el('emitLineInfo').innerHTML =
    `<i class="ti ${line.icon} me-1"></i><strong>${escapeHtml(line.name)}</strong> · 渠道 ${escapeHtml(line.channel)} · 优先级 ${PRIORITY_TEXT[line.priority]}`;
  el('emitToHint').textContent = line.toHint;
  renderEmitFields(line, {});
  el('emitTo').value = '';
  applyAuthState();
  if (isLoggedIn()) loadSample(source);
}

function renderEmitFields(line, data) {
  el('emitFields').innerHTML = line.fields.map((key) => `
    <div class="mb-2">
      <label class="form-label text-secondary small mb-1">${escapeHtml(FIELD_LABEL[key] || key)}</label>
      <input class="form-control" data-key="${key}" value="${escapeHtml(data[key] || '')}" placeholder="${escapeHtml(FIELD_LABEL[key] || key)}">
    </div>
  `).join('');
}

function collectEmitFields() {
  const data = {};
  el('emitFields').querySelectorAll('input[data-key]').forEach((i) => { data[i.dataset.key] = i.value; });
  return data;
}

async function loadSample(source) {
  try {
    const ev = await fetchJson(`${HUB_BASE}/sample?source=${encodeURIComponent(source)}`, { headers: opsAuthHeaders() });
    if (selectedLine && selectedLine.source === source) {
      renderEmitFields(selectedLine, ev.data || {});
      el('emitTo').value = ev.to || '';
    }
  } catch (_) { /* 未登录/后端异常时静默：用户可自行填写 */ }
}

function renderEmitResult(r) {
  const badge = r.ok ? '<span class="badge bg-green text-white">已发送</span>' : '<span class="badge bg-red text-white">失败</span>';
  const box = el('emitResult');
  box.classList.remove('text-center', 'py-5', 'text-secondary');
  box.innerHTML = `
    <div class="d-flex align-items-center gap-2 mb-3">${badge}<span class="text-secondary">${escapeHtml(r.name)} · ${escapeHtml(r.channel)}</span></div>
    <div class="mb-2"><div class="text-secondary small">收件人</div><div>${escapeHtml(r.to)}</div></div>
    <div class="mb-2"><div class="text-secondary small">最终报文（模板渲染后，真正投递的文本）</div>
      <pre class="emit-preview">${escapeHtml(r.content)}</pre></div>
    ${r.ok
      ? `<div class="text-secondary small">消息号 ${escapeHtml(r.msgId)} · 已进入内核，可在「消息记录」查看状态流转。</div>`
      : `<div class="text-danger small">原因：${escapeHtml(r.error || '未知错误')}</div>`}
  `;
}

async function sendEmit(e) {
  e.preventDefault();
  if (!ensureLoggedIn()) return;
  if (!selectedLine) return;
  const btn = el('emitSendBtn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>发送中…';
  try {
    const r = await fetchJson(`${HUB_BASE}/emit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...opsAuthHeaders() },
      body: JSON.stringify({ source: selectedLine.source, to: el('emitTo').value, data: collectEmitFields() }),
    });
    renderEmitResult(r);
    setConnected(true);
    loadStats();
    loadMessages();
  } catch (err) {
    setConnected(false);
    const box = el('emitResult');
    box.classList.remove('text-center', 'py-5');
    box.innerHTML = `<div class="text-danger">发送失败：${escapeHtml(err.message)}</div>`;
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<i class="ti ti-send me-1"></i>发送这条';
  }
}

// ============================================================================
// Templates + manual send: talk to the /msg/** kernel (wrapped {code,message,data}).
// ============================================================================

async function fetchMsgJson(path, options = {}) {
  const res = await fetch(`${CONTEXT_PATH}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...opsAuthHeaders(), ...(options.headers || {}) },
  });
  const body = await res.json().catch(() => null);
  if (!res.ok || !body || body.code !== 0) {
    throw new Error((body && body.message) || `HTTP ${res.status}`);
  }
  return body.data;
}

function addTemplateVarRow(key = '', value = '') {
  const row = document.createElement('div');
  row.className = 'row g-2 mb-1 tpl-var-row';
  row.innerHTML = `
    <div class="col-5"><input class="form-control form-control-sm" placeholder="变量名，如 event" value="${escapeHtml(key)}"></div>
    <div class="col-6"><input class="form-control form-control-sm" placeholder="变量值" value="${escapeHtml(value)}"></div>
    <div class="col-1"><button type="button" class="btn btn-outline-danger btn-sm">×</button></div>
  `;
  row.querySelector('button').addEventListener('click', () => row.remove());
  el('tplVarsContainer').appendChild(row);
}

function collectTemplateData() {
  const data = {};
  document.querySelectorAll('#tplVarsContainer .tpl-var-row').forEach((row) => {
    const inputs = row.querySelectorAll('input');
    const key = inputs[0].value.trim();
    if (key) data[key] = inputs[1].value;
  });
  return data;
}

let mineTemplates = [];
let pendingSelectTemplateId = null;

function extractTemplateVars(content) {
  const names = [];
  const seen = new Set();
  const re = /\$\{([^{}]+)\}/g;
  const text = content || '';
  let m;
  while ((m = re.exec(text))) {
    const name = String(m[1] || '').trim();
    if (name && !seen.has(name)) {
      seen.add(name);
      names.push(name);
    }
  }
  return names;
}

function templateOptionLabel(t) {
  const ch = CHANNEL_LABEL[t.channel] || ('渠道' + t.channel);
  return `${t.name || '(未命名)'} · ${ch}`;
}

function renderTemplateOptions() {
  const select = el('msgTemplateSelect');
  if (!select) return;
  const q = (el('msgTplFilter').value || '').trim().toLowerCase();
  const current = pendingSelectTemplateId || el('msgTemplateId').value || select.value;
  const filtered = mineTemplates.filter((t) => {
    if (!q) return true;
    return String(t.name || '').toLowerCase().includes(q);
  });
  if (!isLoggedIn()) {
    select.innerHTML = '<option value="">请先登录后选择模板</option>';
    return;
  }
  if (!filtered.length) {
    select.innerHTML = '<option value="">没有匹配的模板</option>';
    el('msgTemplateId').value = '';
    return;
  }
  select.innerHTML = '<option value="">请选择模板</option>' + filtered.map((t) =>
    `<option value="${escapeHtml(t.templateId)}">${escapeHtml(templateOptionLabel(t))}</option>`
  ).join('');
  if (current && filtered.some((t) => t.templateId === current)) {
    select.value = current;
    const needVars = el('tplVarsContainer').children.length === 0;
    applyTemplateById(current, needVars);
  }
}

async function loadTemplates() {
  if (!isLoggedIn()) {
    mineTemplates = [];
    renderTemplateOptions();
    return;
  }
  const name = (el('msgTplFilter').value || '').trim();
  const qs = name ? `?name=${encodeURIComponent(name)}` : '';
  try {
    const rows = await fetchMsgJson(`/msg/list_templates${qs}`);
    mineTemplates = Array.isArray(rows) ? rows : [];
    renderTemplateOptions();
    setConnected(true);
  } catch (err) {
    mineTemplates = [];
    renderTemplateOptions();
    el('msgTplHint').textContent = '加载模板失败：' + err.message;
  }
}

function applyTemplateById(templateId, resetVars = true) {
  if (!templateId) {
    el('msgTemplateId').value = '';
    return;
  }
  const tpl = mineTemplates.find((t) => t.templateId === templateId);
  if (!tpl) return;
  applyTemplate(tpl, resetVars);
}

function applyTemplate(tpl, resetVars = true) {
  pendingSelectTemplateId = tpl.templateId;
  el('msgTemplateId').value = tpl.templateId;
  el('msgSubject').value = tpl.subject || '';
  const select = el('msgTemplateSelect');
  if (select && select.value !== tpl.templateId) {
    select.value = tpl.templateId;
  }
  if (resetVars) {
    el('tplVarsContainer').innerHTML = '';
    const names = extractTemplateVars(tpl.content);
    if (names.length === 0) {
      addTemplateVarRow();
    } else {
      names.forEach((n) => addTemplateVarRow(n, ''));
    }
  }
  const ch = CHANNEL_LABEL[tpl.channel] || '未知渠道';
  el('msgTplHint').textContent = `已选「${tpl.name || ''}」· ${ch}。主题和变量已按模板同步，切换模板会覆盖下方内容。`;
  if (tpl.channel === 1) {
    el('msgToHint').textContent = '邮件：填写邮箱。多个地址用逗号或换行分隔，中台会逐封发送。';
  } else if (tpl.channel === 2) {
    el('msgToHint').textContent = '短信：填写手机号。批量名单由业务侧提供，中台按模板逐条投递（单次最多 200）。';
  } else if (tpl.channel === 3) {
    el('msgToHint').textContent = '飞书：收件人仅作审计记录，实际推送到已配置的机器人群。';
  }
}

async function createAndActivateTemplate(e) {
  e.preventDefault();
  if (!ensureLoggedIn()) return;
  const btn = el('tplSubmitBtn');
  btn.disabled = true;
  el('tplResult').textContent = '创建中…';
  try {
    const base = {
      name: el('tplName').value,
      channel: Number(el('tplChannel').value),
      sourceId: el('tplSourceId').value,
      subject: el('tplSubject').value,
      content: el('tplContent').value,
    };
    const templateId = await fetchMsgJson('/msg/create_template', { method: 'POST', body: JSON.stringify(base) });
    await fetchMsgJson('/msg/update_template', { method: 'POST', body: JSON.stringify({ ...base, templateId, status: 2 }) });
    pendingSelectTemplateId = templateId;
    el('tplResult').textContent = `已创建并启用：${templateId}，正在打开发送页…`;
    location.hash = '#/messaging';
  } catch (err) {
    el('tplResult').textContent = '失败：' + err.message;
  } finally {
    btn.disabled = false;
  }
}

async function sendMsg(e) {
  e.preventDefault();
  if (!ensureLoggedIn()) return;
  const btn = el('sendMsgBtn');
  btn.disabled = true;
  el('sendMsgResult').textContent = '提交中…';
  try {
    const priority = Number(el('msgPriority').value);
    const isTimer = el('isTimerMsg').checked;
    const body = {
      to: el('msgTo').value,
      subject: el('msgSubject').value,
      priority,
      templateId: el('msgTemplateId').value,
      templateData: collectTemplateData(),
    };
    if (isTimer) {
      const raw = el('sendAt').value;
      if (!raw) throw new Error('请选择定时发送时间');
      const ts = new Date(raw).getTime();
      if (Number.isNaN(ts)) throw new Error('时间格式不正确');
      body.sendTimestamp = ts;
    }
    const msgId = await fetchMsgJson('/msg/send_msg', { method: 'POST', body: JSON.stringify(body) });
    const n = String(body.to || '').split(/[,;\n\r]+/).map((s) => s.trim()).filter(Boolean).length;
    el('sendMsgResult').textContent = n > 1
      ? `已提交 ${n} 条，可在「消息记录」查看（末条编号 ${msgId}）`
      : `已提交，可在「消息记录」查看（编号 ${msgId}）`;
  } catch (err) {
    el('sendMsgResult').textContent = '失败：' + err.message;
  } finally {
    btn.disabled = false;
  }
}

// ============================================================================
// Wiring
// ============================================================================

renderBizCards();
el('emitForm').addEventListener('submit', sendEmit);
el('emitRandomBtn').addEventListener('click', () => {
  if (!ensureLoggedIn()) return;
  if (selectedLine) loadSample(selectedLine.source);
});
el('simForm').addEventListener('submit', runSimulation);
el('dashRefreshBtn').addEventListener('click', loadStats);
el('refreshMsgsBtn').addEventListener('click', loadMessages);
el('templateForm').addEventListener('submit', createAndActivateTemplate);
el('sendMsgForm').addEventListener('submit', sendMsg);
el('addVarBtn').addEventListener('click', () => addTemplateVarRow());
el('isTimerMsg').addEventListener('change', () => {
  el('timerTimeWrap').style.display = el('isTimerMsg').checked ? '' : 'none';
});
el('msgTemplateSelect').addEventListener('change', () => applyTemplateById(el('msgTemplateSelect').value));
el('msgTplFilter').addEventListener('input', renderTemplateOptions);

el('loginForm').addEventListener('submit', handleLogin);
el('loginNavBtn').addEventListener('click', openLoginModal);
el('readonlyLoginBtn').addEventListener('click', openLoginModal);
el('logoutBtn').addEventListener('click', handleLogout);
el('loginClose').addEventListener('click', closeLoginModal);
el('loginOverlay').addEventListener('click', (e) => { if (e.target === el('loginOverlay')) closeLoginModal(); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !el('loginOverlay').classList.contains('d-none')) closeLoginModal();
});

// ============================================================================
// View router: tiny hash-based switcher; loads data lazily per view.
// ============================================================================

const VIEW_META = {
  dashboard: { pretitle: 'Overview', title: '运行总览', onShow: loadStats },
  simulate: { pretitle: 'Simulator', title: '业务模拟' },
  messages: { pretitle: 'Records', title: '消息记录', onShow: loadMessages },
  templates: { pretitle: 'Templates', title: '模板配置' },
  messaging: { pretitle: 'Messaging', title: '发送与定时', onShow: loadTemplates },
};
const DEFAULT_VIEW = 'dashboard';

function currentViewFromHash() {
  const name = (location.hash || '').replace(/^#\/?/, '');
  return VIEW_META[name] ? name : DEFAULT_VIEW;
}

function showView(name) {
  const view = VIEW_META[name] ? name : DEFAULT_VIEW;
  document.querySelectorAll('section.view').forEach((s) => {
    s.classList.toggle('d-none', s.dataset.view !== view);
  });
  document.querySelectorAll('.app-sidebar .nav-link[data-view]').forEach((a) => {
    a.classList.toggle('active', a.dataset.view === view);
  });
  el('viewPretitle').textContent = VIEW_META[view].pretitle;
  el('viewTitle').textContent = VIEW_META[view].title;
  el('sidebarMenu').classList.remove('show');
  window.scrollTo(0, 0);
  if (typeof VIEW_META[view].onShow === 'function') VIEW_META[view].onShow();
}

window.addEventListener('hashchange', () => showView(currentViewFromHash()));
el('sidebarToggler').addEventListener('click', () => el('sidebarMenu').classList.toggle('show'));

showView(currentViewFromHash());

(async function initAuth() {
  const restored = loadSession();
  if (restored && restored.user && restored.pass) {
    try {
      const stillValid = await verifyCredential(restored.user, restored.pass);
      if (stillValid) session = restored; else saveSession(null);
    } catch (_) {
      session = restored;
    }
  }
  applyAuthState();
})();

// Initial load for the landing view (dashboard) is triggered by showView() above.
