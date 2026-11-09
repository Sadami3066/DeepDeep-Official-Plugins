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

async function login() {
  const { jar } = await sso.login(SERVICE, { label: '网费' });
  save(jar);
  return jar;
}

async function ensure() {
  const data = load();
  return data ? jarFrom(data) : login();
}

async function get(pathname, jar, referer = `${BASE}/netpay/index.jsp`) {
  const res = await http.get(BASE + pathname, { cookie: jar.header('netpay.sysu.edu.cn'), referer });
  jar.absorb(res, 'netpay.sysu.edu.cn');
  if (/casLogin|\/cas\/login/.test(res.headers.location || '')) {
    const fresh = await login();
    const retry = await http.get(BASE + pathname, { cookie: fresh.header('netpay.sysu.edu.cn'), referer });
    return retry.body;
  }
  return res.body;
}

const strip = (h) => String(h || '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();

async function info() {
  const jar = await ensure();
  const body = await get('/netpay/c/site/info', jar);
  const end = body.indexOf('\n===');
  const json = JSON.parse((end > 0 ? body.slice(0, end) : body).trim());
  return json;
}

async function services() {
  const jar = await ensure();
  const html = await get('/netpay/c/site/stopAndResumeList?personal=1', jar);
  const rows = [...html.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/gi)].map((m) => m[1]);
  const out = [];
  for (const r of rows) {
    const cells = [...r.matchAll(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi)].map((m) => strip(m[1]));
    if (cells.length < 8 || /序号|没有查询到/.test(cells[0] + cells[1])) continue;
    const idMatch = r.match(/stop\(['"]([^'"]+)['"]\s*,\s*([^)]+)\)/);
    out.push({
      no: cells[0],
      service: cells[1],
      address: cells[2],
      mac: cells[3],
      dept: cells[4],
      user: cells[5],
      status: cells[6],
      expire: cells[7],
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
