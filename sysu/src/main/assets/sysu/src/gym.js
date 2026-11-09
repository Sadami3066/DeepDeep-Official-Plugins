// 中山大学体育场馆预订系统（gym.sysu.edu.cn）
//
// 三层结构里的"令牌层"：
//   sysu login        → 拿到 CAS 的 TGC（身份）
//   gym.ensureToken() → 用 TGC 换本站 OAuth access_token（1 小时），过期自动续
//   业务命令           → 带 Bearer 调 /api/*
//
// 传输只用 Node 内置 https：WAF（SafeLine）的门槛是 JS 挑战的 SHA1 工作量证明，
// 解出来后带浏览器头就能过，不需要伪装 TLS，也不需要额外二进制。
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const https = require('node:https');
const sso = require('./sso');

const BASE = 'https://gym.sysu.edu.cn';
const CONFIG_DIR = path.join(os.homedir(), '.config/sysu');
const JAR = path.join(CONFIG_DIR, 'gym.jar');
const TOKEN_FILE = path.join(CONFIG_DIR, 'gym.json');
const TOKEN_TTL = 3500;

const UA =
  'Mozilla/5.0 (Linux; Android 13; 22101316UCP) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36';

const ensureDir = () => fs.mkdirSync(CONFIG_DIR, { recursive: true });

// ── cookie jar ────────────────────────────────────────────────
function loadJar() {
  try {
    const j = JSON.parse(fs.readFileSync(JAR, 'utf8'));
    return j && typeof j === 'object' ? j : {};
  } catch {
    return {};
  }
}

function saveJar(jar) {
  ensureDir();
  fs.writeFileSync(JAR, JSON.stringify(jar, null, 2), { mode: 0o600 });
  fs.chmodSync(JAR, 0o600);
}

const cookieHeader = (jar) => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');

// ── 基础请求 ──────────────────────────────────────────────────
function once(url, { method = 'GET', body, headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const h = {
      'User-Agent': UA,
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      'Accept-Encoding': 'gzip',
      'sec-ch-ua': '"Chromium";v="156", "Google Chrome";v="156", "Not=A?Brand";v="24"',
      'sec-ch-ua-mobile': '?1',
      'sec-ch-ua-platform': '"Android"',
      Cookie: cookieHeader(loadJar()),
      ...headers,
    };
    const req = https.request({ method, host: u.host, path: u.pathname + u.search, headers: h, timeout: 30000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        let buf = Buffer.concat(chunks);
        if (res.headers['content-encoding'] === 'gzip') {
          try {
            buf = zlib.gunzipSync(buf);
          } catch {}
        }
        const jar = loadJar();
        for (const line of res.headers['set-cookie'] || []) {
          const [pair] = line.split('; ');
          const i = pair.indexOf('=');
          if (i > 0) jar[pair.slice(0, i)] = pair.slice(i + 1);
        }
        saveJar(jar);
        resolve({ status: res.statusCode, headers: res.headers, body: buf.toString('utf8') });
      });
    });
    req.on('timeout', () => req.destroy(new Error('请求超时')));
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

// 雷池 WAF：SHA1 工作量证明
function solveChallenge(html) {
  const m = html.match(/var prefix = '([^']+)';\s*\/\/arg1\s*var leading_zero_bit = (\d+)/);
  if (!m) return false;
  const prefix = m[1];
  const zeros = '0'.repeat(Number(m[2]));
  const toBin = (hex) => [...hex].map((c) => parseInt(c, 16).toString(2).padStart(4, '0')).join('');
  let cnt = 0;
  let suffix = '0';
  for (;;) {
    suffix = cnt.toString(16);
    if (toBin(crypto.createHash('sha1').update(prefix + suffix).digest('hex')).startsWith(zeros)) break;
    cnt += 1;
  }
  const jar = loadJar();
  jar.safeline_bot_challenge_ans = `${jar.safeline_bot_challenge || ''}${suffix}`;
  saveJar(jar);
  return true;
}

async function request(url, { method = 'GET', body, headers, follow = false, maxHops = 8 } = {}) {
  let current = url;
  let res;
  for (let hop = 0; hop < (follow ? maxHops : 1); hop += 1) {
    res = await once(current, { method, body, headers });
    for (let i = 0; i < 3; i += 1) {
      if (!/var prefix = '[^']+';\s*\/\/arg1/.test(res.body)) break;
      if (!solveChallenge(res.body)) break;
      res = await once(current, { method, body, headers });
    }
    if (!follow || !res.headers.location) return res;
    current = new URL(res.headers.location, current).toString();
  }
  return res;
}

const apiUrl = (p) => (p.startsWith('http') ? p : BASE + (p.startsWith('/') ? p : `/${p}`));

async function api(p, { method = 'GET', body, token } = {}) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await request(apiUrl(p), { method, body, headers });
  if (res.status === 401) {
    const err = new Error('gym token 失效');
    err.code = 'RELOGIN';
    throw err;
  }
  if (res.status >= 400) throw new Error(`HTTP ${res.status} ${p}\n${res.body.slice(0, 200)}`);
  try {
    return JSON.parse(res.body);
  } catch {
    return res.body;
  }
}

// ── token ─────────────────────────────────────────────────────
function readToken() {
  try {
    const t = JSON.parse(fs.readFileSync(TOKEN_FILE, 'utf8'));
    return t?.access_token ? t : null;
  } catch {
    return null;
  }
}

function saveToken(token) {
  ensureDir();
  fs.writeFileSync(TOKEN_FILE, JSON.stringify({ access_token: token, at: Date.now() }, null, 2), { mode: 0o600 });
  fs.chmodSync(TOKEN_FILE, 0o600);
}

async function login({ code } = {}) {
  const authz = `${BASE}/authsport/Account/Auth?response_type=token&client_id=sysu_2021&redirect_uri=${encodeURIComponent(`${BASE}/#`)}`;
  let res = await request(authz, { headers: { Accept: 'text/html' } });
  let service = null;
  for (let i = 0; i < 8; i += 1) {
    const loc = res.headers.location || '';
    const direct = loc.match(/#access_token=([^&]+)/);
    if (direct) {
      saveToken(decodeURIComponent(direct[1]));
      return readToken();
    }
    if (/cas\.sysu\.edu\.cn\/cas\/login/.test(loc)) {
      service = new URL(loc).searchParams.get('service');
      break;
    }
    if (!loc) break;
    res = await request(new URL(loc, BASE).toString(), { follow: false });
  }
  if (!service) throw new Error('gym 登录跳转异常：没拿到 CAS service');

  const t = await sso.ticket(service, { code, interactive: false });
  let url = `${service}${service.includes('?') ? '&' : '?'}ticket=${encodeURIComponent(t.ticket)}`;
  for (let i = 0; i < 8; i += 1) {
    const r = await request(url, { headers: { Accept: 'text/html' } });
    const loc = r.headers.location || '';
    const m = loc.match(/#access_token=([^&]+)/);
    if (m) {
      saveToken(decodeURIComponent(m[1]));
      return readToken();
    }
    if (!loc) break;
    url = new URL(loc, url).toString();
  }
  throw new Error('gym 换 token 失败：最后没拿到 access_token');
}

async function ensureToken({ force = false, code } = {}) {
  const t = readToken();
  if (!force && t && Date.now() - t.at < TOKEN_TTL * 1000) return t.access_token;
  return (await login({ code })).access_token;
}

async function withApi(fn, opts = {}) {
  let token = await ensureToken(opts);
  try {
    return await fn(token);
  } catch (err) {
    if (err.code !== 'RELOGIN') throw err;
    token = await ensureToken({ ...opts, force: true });
    return fn(token);
  }
}

const me = (o) => withApi((t) => api('/api/Credit/Me', { token: t }), o);
const campuses = (o) => withApi((t) => api('/api/Campus/active', { token: t }), o);
const types = (o) => withApi((t) => api('/api/venuetype/all', { token: t }), o);
const sportTypes = (o) => withApi((t) => api('/api/sporttypes', { token: t }), o);
const venues = (typeId, o) => withApi((t) => api(`/api/venue/type/${typeId}`, { token: t }), o);
const slots = (typeId, start, end, o) =>
  withApi((t) => api(`/api/venue/available-slots/range?venueTypeId=${typeId}&start=${start}&end=${end}`, { token: t }), o);

function bookings({ days = 30, waiting = false } = {}, o) {
  const today = new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
  const end = new Date(Date.now() + 8 * 3600e3 + days * 864e5).toISOString().slice(0, 10);
  return withApi(
    (t) => api(`/api/BookingRequestVenue?all=false&startDate=${today}&endDate=${end}&waitingList=${waiting}`, { token: t }),
    o,
  );
}

// ── 预约 token（wasm 离线计算） ──────────────────────────────
// BookingId = "<hash>.<秒级时间戳>.<salt>"；salt 只是客户端盐，服务端不校验来源
let wasmMemo = null;
async function wasm() {
  if (wasmMemo) return wasmMemo;
  const file = path.join(__dirname, '..', 'assets', 'wasm_token.wasm');
  const mod = await WebAssembly.compile(fs.readFileSync(file));
  const inst = await WebAssembly.instantiate(mod, {
    './wasm_token_bg.js': { __wbindgen_init_externref_table() {} },
  });
  wasmMemo = inst.exports;
  return wasmMemo;
}

const secret = () => process.env.SYSU_GYM_SECRET || crypto.randomBytes(16).toString('hex');

async function bookingId(identity, ts = Math.floor(Date.now() / 1000)) {
  const ex = await wasm();
  const put = (s) => {
    const b = Buffer.from(s, 'utf8');
    const p = ex.__wbindgen_malloc(b.length || 1, 1);
    new Uint8Array(ex.memory.buffer).set(b, p);
    return [p, b.length];
  };
  const [a, al] = put(identity);
  const [c, cl] = put(secret());
  const [p, l] = ex.gen_token(a, al, ts, c, cl);
  return Buffer.from(new Uint8Array(ex.memory.buffer).slice(p, p + l)).toString('utf8');
}

const uuid = () =>
  'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0;
    return (ch === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });

async function book({ typeId, typeName, venueId, venueName, date, start, end, charge = 0, dryRun = true, profile }, opts = {}) {
  const token = await ensureToken(opts);
  const identity = uuid();
  const payload = {
    Identity: identity,
    BookingId: await bookingId(identity),
    UserId: profile?.Id || profile?.UserId || '',
    VenueTypeId: typeId,
    VenueBookings: [{ VenueId: venueId, VenueName: venueName, TimeSlots: [{ Date: new Date(`${date}T00:00:00.000Z`).toISOString(), Start: start, End: end }] }],
    Participants: [],
    Status: 'Accepted',
    Description: typeName,
    CreatedAt: new Date().toISOString(),
    UpdatedAt: new Date().toISOString(),
    ActionedBy: profile?.UserId || '',
    IsCash: false,
    Charge: Number(charge) || 0,
  };
  if (dryRun) return { dryRun: true, payload };
  return { dryRun: false, result: await api('/api/BookingRequestVenue', { method: 'POST', token, body: JSON.stringify(payload) }) };
}

async function cancel(identity, opts = {}) {
  const token = await ensureToken(opts);
  return api(`/api/BookingRequestVenue/${identity}`, { method: 'DELETE', token });
}

module.exports = {
  BASE,
  request,
  api,
  login,
  ensureToken,
  me,
  campuses,
  types,
  sportTypes,
  venues,
  slots,
  bookings,
  book,
  cancel,
  bookingId,
  secret,
};
