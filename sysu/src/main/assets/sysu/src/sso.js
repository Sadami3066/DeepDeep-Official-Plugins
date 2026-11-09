// 全校统一认证（CAS）换票器。
//
// 原理：登录后 CAS 会下发 TGC（Ticket Granting Cookie），它跟具体站点无关。
// 拿 TGC 去 /esc-sso/login?service=<站点地址>，CAS 直接 302 回 <站点地址>?ticket=ST-xxx，
// 站点拿 ticket 自校验后发自己的会话 Cookie —— 全程不再要密码。
//
// 例外：部分站点（如 LMS）有更严的策略，换票时会再要一次企业微信验证码（MFA）。
// 这里也一并处理：发码 → 等码 → 验证 → 重新换票。
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const readline = require('node:readline/promises');
const http = require('./http');

const CAS = 'https://cas.sysu.edu.cn/esc-sso';
const CONFIG_DIR = path.join(os.homedir(), '.config/sysu');
const CRED_FILE = path.join(CONFIG_DIR, 'credentials');

const ensureDir = () => fs.mkdirSync(CONFIG_DIR, { recursive: true });

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function writeJson(file, data) {
  ensureDir();
  fs.writeFileSync(file, JSON.stringify(data, null, 2), { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

// TGC 从哪来：环境变量 > 新的统一会话文件 > 旧 jwxt 的会话文件
function tgc() {
  if (process.env.SYSU_TGC) return process.env.SYSU_TGC;
  const t = readJson(path.join(CONFIG_DIR, 'session.json'))?.tgc;
  if (t) return t;
  throw new Error('没有 TGC。先登录一次：sysu login');
}

const hasTgc = () => {
  try {
    return Boolean(tgc());
  } catch {
    return false;
  }
};

function credentials() {
  if (process.env.SYSU_NETID && process.env.SYSU_PASSWORD) {
    return { netid: process.env.SYSU_NETID, password: process.env.SYSU_PASSWORD };
  }
  try {
    const cred = Object.fromEntries(
      fs
        .readFileSync(CRED_FILE, 'utf8')
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith('#') && l.includes('='))
        .map((l) => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]),
    );
    if (!cred.netid || !cred.password) throw new Error('缺 netid 或 password');
    return cred;
  } catch (err) {
    throw new Error(`读不到凭据 ${CRED_FILE}：${err.message}\n（MFA 需要学号；普通的换票只用 TGC）`);
  }
}

// ── cookie jar ────────────────────────────────────────────────────
class Jar {
  constructor(entries = []) {
    this.map = new Map(entries);
  }
  static fromJson(list = []) {
    return new Jar(list.map((c) => [c.name, c]));
  }
  absorb(res, host) {
    for (const line of res.headers['set-cookie'] || []) {
      const [pair] = line.split('; ');
      const i = pair.indexOf('=');
      if (i < 0) continue;
      const domain = ((line.match(/Domain=([^;]+)/i) || [])[1] || host).replace(/^\./, '');
      this.map.set(pair.slice(0, i), { name: pair.slice(0, i), value: pair.slice(i + 1), domain });
    }
  }
  // cookies 是通用 Map（值为字符串）或本类的 map（值为对象），统一取
  header(host) {
    return [...this.map.values()]
      .filter((c) => typeof c === 'string' || !c.domain || !host || host === c.domain || host.endsWith(`.${c.domain}`))
      .map((c) => (typeof c === 'string' ? c : `${c.name}=${c.value}`))
      .join('; ');
  }
  list() {
    return [...this.map.values()].filter((c) => typeof c !== 'string');
  }
}

// ── CAS 请求 ──────────────────────────────────────────────────────
async function casOpen(service, jar = new Jar()) {
  const url = `${CAS}/login?service=${encodeURIComponent(service)}`;
  const res = await http.get(url, { cookie: `TGC=${tgc()}; ${jar.header('cas.sysu.edu.cn')}`, referer: `${CAS}/login/page` });
  jar.absorb(res, 'cas.sysu.edu.cn');
  return { res, jar, location: res.headers.location || '' };
}

// ── MFA（企业微信验证码） ─────────────────────────────────────────
async function mfaSend(jar) {
  const { netid } = credentials();
  const res = await http.get(`${CAS}/api/v3/webWorkWechatMsgAuth/send?username=${encodeURIComponent(netid)}`, {
    cookie: `TGC=${tgc()}; ${jar.header('cas.sysu.edu.cn')}`,
    referer: `${CAS}/login/mfaLogin.html`,
  });
  jar.absorb(res, 'cas.sysu.edu.cn');
  let json = {};
  try {
    json = JSON.parse(res.body);
  } catch {}
  if (json.code !== '0') throw new Error(`验证码没发出去：${json.msg || `HTTP ${res.status}`}`);
}

async function mfaVerify(jar, { appId, appUrl, code }) {
  const { netid } = credentials();
  const casCookie = `TGC=${tgc()}; ${jar.header('cas.sysu.edu.cn')}`;
  await http.get(`${CAS}/api/v3/auth/queryUserValid?username=${encodeURIComponent(netid)}&authType=webWorkWechatMsgAuth`, {
    cookie: casCookie,
  });
  const res = await http.post(
    `${CAS}/app/upgradelogin`,
    {
      authType: 'webWorkWechatMsgAuth',
      dataField: { username: netid, password: '', msgCode: String(code), vcode: '', appId, appUrl },
      redirectUri: '',
    },
    { cookie: casCookie, referer: `${CAS}/login/mfaLogin.html` },
  );
  jar.absorb(res, 'cas.sysu.edu.cn');
  let json = {};
  try {
    json = JSON.parse(res.body);
  } catch {}
  if (json.code !== '0') throw new Error(`二次验证没过：${json.msg || `code ${json.code}`}`);
}

async function askCode() {
  if (!process.stdin.isTTY) return null;
  const rl = readline.createInterface({ input: process.stdin, output: process.stderr });
  try {
    const ans = await rl.question('企业微信验证码：');
    return ans.trim();
  } finally {
    rl.close();
  }
}

// ── 换票 ──────────────────────────────────────────────────────────
// 只换 ticket，不跟跳转。需要 MFA 时抛出带 .mfa 的错误或自动处理。
async function ticket(service, { code, interactive = true } = {}) {
  let jar = new Jar();
  let { location } = await casOpen(service, jar);

  if (/mfaLogin/.test(location)) {
    const mfa = new URL(location);
    const appId = mfa.searchParams.get('appId');
    const appUrl = mfa.searchParams.get('appUrl');
    if (!code) await mfaSend(jar);
    const input = code || (await askCode());
    if (!input) {
      throw new Error(
        '这个站点需要企业微信二次验证，验证码已推送。\n' +
          '拿到后重跑，并加上 --code <4 位>（或直接在终端里跑，会提示你输入）',
      );
    }
    await mfaVerify(jar, { appId, appUrl, code: input });
    ({ location } = await casOpen(service, jar));
  }

  const m = location.match(/[?&]ticket=(ST-[^&]+)/);
  if (!m) {
    const relogin = /\/login\/(page|index)/.test(location);
    if (relogin) throw new Error('TGC 已失效（CAS 要求重新输入密码）。跑一次：sysu login');
    throw new Error(`换票失败：CAS 没有给 ticket（${location.slice(0, 80) || '无跳转'}）`);
  }
  return { ticket: decodeURIComponent(m[1]), location: new URL(location, service).toString(), jar };
}

// 顺着跳转走完，把沿途 Cookie 收进 jar
async function login(service, { label = 'service', code, interactive = true } = {}) {
  const first = await ticket(service, { code, interactive });
  const jar = first.jar;
  // 跟跳转过程中 CAS 可能再回跳一次，必须继续带 TGC
  jar.map.set('TGC', { name: 'TGC', value: tgc(), domain: 'cas.sysu.edu.cn' });
  let url = first.location;
  for (let i = 0; i < 10; i += 1) {
    const host = new URL(url).host;
    const res = await http.get(url, { cookie: jar.header(host), referer: service });
    jar.absorb(res, host);
    const next = res.headers.location;
    if (!next) break;
    url = new URL(next, url).toString();
  }
  if (!jar.list().length) throw new Error(`${label}：跳转完成但一个 Cookie 都没拿到`);
  return { jar, finalUrl: url };
}

// ── 站点会话缓存 ──────────────────────────────────────────────────
function loadSession(name) {
  const data = readJson(path.join(CONFIG_DIR, `${name}.json`));
  if (!data?.cookies?.length) return null;
  return { ...data, jar: Jar.fromJson(data.cookies) };
}

function saveSession(name, { jar, finalUrl }) {
  writeJson(path.join(CONFIG_DIR, `${name}.json`), {
    capturedAt: new Date().toISOString(),
    finalUrl,
    cookies: jar.list(),
  });
}

async function sessionFor(name, service, { label = name, renew = false, code, interactive = true } = {}) {
  if (!renew) {
    const cached = loadSession(name);
    if (cached) return cached;
  }
  const fresh = await login(service, { label, code, interactive });
  saveSession(name, fresh);
  return { ...fresh, capturedAt: new Date().toISOString() };
}

module.exports = {
  CAS,
  CONFIG_DIR,
  Jar,
  tgc,
  hasTgc,
  credentials,
  ticket,
  login,
  sessionFor,
  loadSession,
  saveSession,
  readJson,
  writeJson,
};
