const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('./http');

const CONFIG_DIR = path.join(os.homedir(), '.config/sysu');
const SESSION_PATH = path.join(CONFIG_DIR, 'jwxt/session.json');
const JWXT = 'https://jwxt.sysu.edu.cn/jwxt';
const CAS = 'https://cas.sysu.edu.cn/esc-sso/login';
const SERVICE = `${JWXT}/api/sso/cas/login?pattern=student-login`;

function read() {
  try {
    const data = JSON.parse(fs.readFileSync(SESSION_PATH, 'utf8'));
    return data.cookies?.length ? data : null;
  } catch {
    return null;
  }
}

function save({ cookies, tgc, tgcAt }) {
  const prev = read() || {};
  const data = {
    capturedAt: new Date().toISOString(),
    cookies,
    tgc: tgc || prev.tgc || '',
    tgcAt: tgcAt || (tgc ? new Date().toISOString() : prev.tgcAt) || null,
  };
  fs.mkdirSync(path.dirname(SESSION_PATH), { recursive: true });
  fs.writeFileSync(SESSION_PATH, JSON.stringify(data, null, 2), { mode: 0o600 });
  fs.chmodSync(SESSION_PATH, 0o600);
  return data;
}

const toHeader = (cookies) => cookies.map((c) => `${c.name}=${c.value}`).join('; ');

function cookieHeader() {
  const data = read();
  if (!data) throw new Error(`没有会话文件：${SESSION_PATH}\n先登录一次：sysu login`);
  return toHeader(data.cookies);
}

async function renew() {
  const data = read();
  if (!data?.tgc) throw new Error('没有 TGC，无法自动续期');

  const cas = await http.get(`${CAS}?service=${encodeURIComponent(SERVICE)}`, { cookie: `TGC=${data.tgc}` });
  const ticket = cas.headers.location || '';
  if (!ticket.includes('ticket=')) throw new Error('TGC 已失效');

  const step1 = await http.get(ticket);
  const cookies = http.parseCookies(step1.headers['set-cookie']);

  if (step1.headers.location) {
    const step2 = await http.get(step1.headers.location, { cookie: toHeader(cookies) });
    cookies.push(...http.parseCookies(step2.headers['set-cookie']));
  }

  if (!cookies.some((c) => c.name === 'LYSESSIONID')) throw new Error('续期没拿到 LYSESSIONID');
  save({ cookies });
  return cookies;
}

async function isLoggedIn() {
  try {
    const res = await http.get(`${JWXT}/api/login/status`, { cookie: cookieHeader(), referer: `${JWXT}/` });
    return JSON.parse(res.body).data === 1;
  } catch {
    return false;
  }
}

async function ensureLogin() {
  if (!read()) throw new Error(`没有会话文件：${SESSION_PATH}\n先登录一次：sysu login`);
  if (await isLoggedIn()) return;
  if (read()?.tgc) {
    try {
      await renew();
      if (await isLoggedIn()) return void console.error('（会话已用 TGC 静默续期）');
    } catch (err) {
      throw new Error(`会话失效，自动续期失败（${err.message}）。重新登录：sysu login`);
    }
  }
  throw new Error('会话已失效。重新登录：sysu login');
}

module.exports = { SESSION_PATH, JWXT, SERVICE, read, save, cookieHeader, renew, ensureLogin, isLoggedIn };
