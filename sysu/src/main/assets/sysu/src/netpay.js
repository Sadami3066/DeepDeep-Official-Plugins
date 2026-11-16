// 网费在线系统（netpay.sysu.edu.cn）
// 登录走 CAS：/netpay/casLogin；登录后是 JSP 会话（JSESSIONID）。
// 数据接口：/netpay/c/site/{info,initPay,orders}
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('./http');
const sso = require('./sso');

const BASE = 'https://netpay.sysu.edu.cn';
const SERVICE = `${BASE}/netpay/casLogin`;
const CONFIG_DIR = path.join(os.homedir(), '.config/sysu');
const SESSION_FILE = path.join(CONFIG_DIR, 'netpay.json');

function load() {
  try {
    const d = JSON.parse(fs.readFileSync(SESSION_FILE, 'utf8'));
    return d?.cookies?.length ? d : null;
  } catch {
    return null;
  }
}

function save(jar) {
  fs.mkdirSync(CONFIG_DIR, { recursive: true });
  fs.writeFileSync(SESSION_FILE, JSON.stringify({ capturedAt: new Date().toISOString(), cookies: jar.list() }, null, 2), { mode: 0o600 });
  fs.chmodSync(SESSION_FILE, 0o600);
}

function jarFrom(data) {
  const jar = new sso.Jar();
  for (const c of data.cookies) jar.map.set(c.name, c);
  return jar;
}

async function login({ code } = {}) {
  const { jar } = await sso.login(SERVICE, { label: '网费', code });
  save(jar);
  return jar;
}

async function ensure() {
  const data = load();
  return data ? jarFrom(data) : login();
}

const LOGIN_PAGE = /<form[^>]+action=["'][^"']*(?:casLogin|login)[^"']*["']|name=["']password["']|统一身份认证|id=["'](?:fm1|casLoginForm)["']/i;

function isLoginPage(res) {
  if (/casLogin|\/cas\/login/.test(res.headers.location || '')) return true;
  if (res.status !== 200 || res.headers.location) return false;
  const body = String(res.body || '');
  if (/^\s*[{[]/.test(body)) return false;
  return LOGIN_PAGE.test(body);
}

async function get(pathname, jar, referer = `${BASE}/netpay/index.jsp`, retried = false) {
  const res = await http.get(BASE + pathname, { cookie: jar.header('netpay.sysu.edu.cn'), referer });
  jar.absorb(res, 'netpay.sysu.edu.cn');
  if (isLoginPage(res)) {
    if (retried) throw new Error('网费会话已失效（返回登录页）。重新登录：sysu netpay login');
    const fresh = await login();
    return get(pathname, fresh, referer, true);
  }
  return res.body;
}

const strip = (h) => String(h || '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();

const EXPIRED_TEXT = /无法检验您的登陆信息|重新登陆系统|登录已失效|会话已失效/;

function isoDate(value) {
  const text = String(value || '').trim();
  if (!text) return null;
  const date = new Date(text);
  if (Number.isNaN(date.getTime())) return null;
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function expiredJson(json) {
  if (!json || typeof json !== 'object') return false;
  if (json.success === false || String(json.success) === 'false') return true;
  return EXPIRED_TEXT.test(String(json.msg || json.message || json.det || ''));
}

function expiredBody(body) {
  const text = String(body || '').trim();
  if (!text) return false;
  if (EXPIRED_TEXT.test(text)) return true;
  if (!/^[{[]/.test(text)) return false;
  try {
    return expiredJson(JSON.parse(text));
  } catch {
    return false;
  }
}

function parseInfo(body) {
  const text = String(body || '');
  const end = text.indexOf('\n===');
  const raw = (end > 0 ? text.slice(0, end) : text).trim();
  try {
    return JSON.parse(raw);
  } catch {
    throw new Error(`网费服务返回异常（不是 JSON，可能会话已失效）：${strip(raw).slice(0, 80)}`);
  }
}

function expiredError(json) {
  const message = String(json?.msg || json?.message || '无法检验您的登陆信息').trim().replace(/[。.!！]+$/, '');
  return new Error(`网费会话已失效：${message}。重新登录：sysu netpay login`);
}

async function info() {
  let jar = await ensure();
  let body = await get('/netpay/c/site/info', jar);
  if (expiredBody(body)) {
    jar = await login();
    body = await get('/netpay/c/site/info', jar);
  }
  const json = parseInfo(body);
  if (expiredJson(json)) throw expiredError(json);
  return json;
}

async function services() {
  let jar = await ensure();
  let html = await get('/netpay/c/site/stopAndResumeList?personal=1', jar);
  if (expiredBody(html)) {
    jar = await login();
    html = await get('/netpay/c/site/stopAndResumeList?personal=1', jar);
    if (expiredBody(html)) throw new Error('网费会话已失效（返回登录页）。重新登录：sysu netpay login');
  }
  const rows = [...html.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/gi)].map((m) => m[1]);
  const out = [];
  for (const r of rows) {
    const cells = [...r.matchAll(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi)].map((m) => strip(m[1]));
    if (cells.length < 8 || /序号|没有查询到/.test(cells[0] + cells[1])) continue;
    const idMatch = r.match(/stop\(['"]([^'"]+)['"]\s*,\s*([^)]+)\)/);
    const expireText = cells[7];
    out.push({
      no: cells[0],
      service: cells[1],
      address: cells[2],
      mac: cells[3],
      dept: cells[4],
      user: cells[5],
      status: cells[6],
      expire: isoDate(expireText),
      expireText,
      paused: cells[8] || '',
      id: idMatch ? idMatch[1] : null,
      daysLeft: idMatch ? Number(idMatch[2]) : null,
    });
  }
  return out;
}

async function orders() {
  const jar = await ensure();
  const html = await get('/netpay/c/site/orders', jar);
  const rows = [...html.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/gi)].map((m) => m[1]);
  const out = [];
  for (const r of rows) {
    const cells = [...r.matchAll(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi)].map((m) => strip(m[1]));
    if (cells.length < 6 || /订单号/.test(cells[0])) continue;
    out.push({ id: cells[0], owner: cells[1], amount: cells[2], method: cells[3], time: cells[4], status: cells[5], service: cells[6] });
  }
  return out;
}

function postForm(pathname, data, jar) {
  const body = Object.entries(data).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  return http.postBinary(BASE + pathname, body, 'application/x-www-form-urlencoded', {
    cookie: jar.header('netpay.sysu.edu.cn'),
    referer: `${BASE}/netpay/my/initPay.jsp`,
    timeout: 30000,
    headers: { 'X-Requested-With': 'XMLHttpRequest' },
  });
}

// 建一张未付款的续费单，返回支付表单 HTML
async function prepareRenew({ serviceId, months = 1, money = 30 }, jar) {
  const j = jar || (await ensure());
  const res = await postForm('/netpay/c/site/prepareOrder', {
    type: 'web',
    serviceIds: serviceId,
    months,
    moneys: money,
    poundage: 0,
  }, j);
  const text = res.body;
  const at = text.indexOf('<form');
  if (at < 0) {
    const msg = (text.match(/"msg"\s*:\s*"([^"]*)"/) || [])[1] || text.slice(0, 160);
    throw new Error(`建单失败：${msg}\n（服务端可能限制同一服务重复建单，等未付款订单关闭或窗口刷新后再试）`);
  }
  const html = text.slice(at);
  const action = (html.match(/action='([^']+)'/) || html.match(/action="([^"]+)"/) || [])[1];
  const inputs = [...html.matchAll(/<input[^>]*name='([^']+)'[^>]*value='([^']*)'/g)].map((m) => [m[1], m[2]]);
  return { action, inputs, html };
}

async function cancelOrder(orderId, type = 'web') {
  const jar = await ensure();
  const res = await postForm('/netpay/c/site/cancelOrder', { orderId, type }, jar);
  return res.status;
}

module.exports = { BASE, SERVICE, login, ensure, info, services, orders, prepareRenew, cancelOrder };
