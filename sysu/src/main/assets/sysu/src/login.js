const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('./http');
const session = require('./session');

const CAS = 'https://cas.sysu.edu.cn';
const BASE = `${CAS}/esc-sso`;
const CONFIG_DIR = path.join(os.homedir(), '.config/sysu');
const MFA_PATH = path.join(CONFIG_DIR, 'jwxt/mfa.json');
const CRED_FILE = path.join(CONFIG_DIR, 'credentials');
const MFA_TTL_MS = 10 * 60 * 1000;

const jar = new Map();
const absorb = (res, host) => {
  for (const line of res.headers['set-cookie'] || []) {
    const [pair] = line.split('; ');
    const i = pair.indexOf('=');
    if (i < 0) continue;
    const domain = ((line.match(/Domain=([^;]+)/i) || [])[1] || host).replace(/^\./, '');
    jar.set(pair.slice(0, i), { value: pair.slice(i + 1), domain });
  }
};
const cookieFor = (host) =>
  [...jar.entries()]
    .filter(([, c]) => host.endsWith(c.domain))
    .map(([n, c]) => `${n}=${c.value}`)
    .join('; ');

async function call(url, { method = 'GET', body, referer } = {}) {
  const host = new URL(url).host;
  const opts = { cookie: cookieFor(host), referer: referer || `${BASE}/login/page` };
  const res = method === 'POST' ? await http.post(url, body, opts) : await http.get(url, opts);
  absorb(res, host);
  let json = null;
  try {
    json = JSON.parse(res.body);
  } catch {}
  return { status: res.status, loc: res.headers.location, json, setCookie: res.headers['set-cookie'] || [] };
}

function readCredentials() {
  const file = CRED_FILE;
  try {
    const cred = Object.fromEntries(
      fs
        .readFileSync(file, 'utf8')
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith('#'))
        .map((l) => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]),
    );
    if (!cred.netid || !cred.password) throw new Error('缺 netid 或 password');
    return cred;
  } catch (err) {
    throw new Error(`读不到凭据 ${file}：${err.message}\n账号密码由用户在 DeepDeep 的「中山大学教务」插件页填写。`);
  }
}

const codeHint = () => console.log('  10 分钟内用 4 位验证码完成登录：sysu login --code 1234');

const mfaParams = (mfa) => ({
  appId: mfa?.searchParams.get('appId') || '',
  appUrl: mfa?.searchParams.get('appUrl') || '',
});

function clearMfa() {
  try {
    fs.unlinkSync(MFA_PATH);
  } catch {}
}

function saveMfa(netid, mfa) {
  const { appId, appUrl } = mfaParams(mfa);
  const data = {
    netid,
    at: Date.now(),
    appId,
    appUrl,
    cookies: [...jar.entries()].map(([name, cookie]) => ({ name, value: cookie.value, domain: cookie.domain })),
  };
  fs.mkdirSync(path.dirname(MFA_PATH), { recursive: true });
  fs.writeFileSync(MFA_PATH, JSON.stringify(data, null, 2), { mode: 0o600 });
  fs.chmodSync(MFA_PATH, 0o600);
}

function readMfa(netid) {
  let data;
  try {
    data = JSON.parse(fs.readFileSync(MFA_PATH, 'utf8'));
  } catch {
    clearMfa();
    return null;
  }
  const valid =
    data &&
    data.netid === netid &&
    Array.isArray(data.cookies) &&
    typeof data.at === 'number' &&
    Date.now() - data.at <= MFA_TTL_MS;
  if (!valid) {
    clearMfa();
    return null;
  }
  return data;
}

function restoreJar(cookies) {
  jar.clear();
  for (const item of cookies) {
    if (typeof item?.name !== 'string' || typeof item?.value !== 'string' || typeof item?.domain !== 'string') continue;
    jar.set(item.name, { value: item.value, domain: item.domain });
  }
}

async function passwordAuth(cred) {
  await call(`${BASE}/login?service=${encodeURIComponent(session.SERVICE)}`);
  const policy = await call(`${BASE}/api/v3/auth/policy`);
  const { publicKey, publicKeyId } = policy.json?.data?.param || {};
  if (!publicKey) throw new Error('拿不到统一认证的公钥');

  const pem = `-----BEGIN PUBLIC KEY-----\n${publicKey.replace(/(.{64})/g, '$1\n').trim()}\n-----END PUBLIC KEY-----\n`;
  const encrypted = crypto
    .publicEncrypt({ key: pem, padding: crypto.constants.RSA_PKCS1_PADDING }, Buffer.from(cred.password, 'utf8'))
    .toString('base64');

  const dl = await call(`${BASE}/api/v3/auth/doLogin`, {
    method: 'POST',
    body: { authType: 'webLocalAuth', dataField: { username: cred.netid, password: encrypted, publicKeyId } },
  });
  if (dl.json?.code !== '0') throw new Error(`账密没通过：${dl.json?.msg || '未知错误'}`);
  console.log('  ✓ 账号密码');

  const next = await call(`${BASE}/login?service=${encodeURIComponent(session.SERVICE)}`);
  const mfa = /mfaLogin/.test(next.loc || '') ? new URL(next.loc, CAS) : null;
  return { next, mfa };
}

async function sendCode(cred) {
  const sent = await call(`${BASE}/api/v3/webWorkWechatMsgAuth/send?username=${encodeURIComponent(cred.netid)}`);
  if (sent.json?.code !== '0') throw new Error(`企微验证码没发出去：${sent.json?.msg || '未知错误'}`);
  console.log('  ✓ 验证码已发送到企业微信');
}

async function submitCode(cred, params, code) {
  if (!/^\d{4}$/.test(String(code))) throw new Error(`验证码要是 4 位数字，收到：${code}`);
  const { appId = '', appUrl = '' } = params || {};
  await call(`${BASE}/api/v3/auth/queryUserValid?username=${encodeURIComponent(cred.netid)}&authType=webWorkWechatMsgAuth`);
  const sub = await call(`${BASE}/app/upgradelogin`, {
    method: 'POST',
    referer: `${CAS}/login/mfaLogin.html`,
    body: {
      authType: 'webWorkWechatMsgAuth',
      dataField: { username: cred.netid, password: '', msgCode: code, vcode: '', appId, appUrl },
      redirectUri: '',
    },
  });
  if (sub.json?.code !== '0') throw new Error(`二次验证没过：${sub.json?.msg || '未知错误'}`);
  console.log('  ✓ 二次验证');
  return call(`${BASE}/login?service=${encodeURIComponent(session.SERVICE)}`);
}

async function resumeMfa(cred, code, saved) {
  restoreJar(saved.cookies);
  const next = await call(`${BASE}/login?service=${encodeURIComponent(session.SERVICE)}`);
  if (!/mfaLogin/.test(next.loc || '')) throw new Error('上次的登录会话已失效');
  const params = mfaParams(new URL(next.loc, CAS));
  return submitCode(
    cred,
    { appId: params.appId || saved.appId || '', appUrl: params.appUrl || saved.appUrl || '' },
    code,
  );
}

async function finishLogin(firstNext) {
  const next = firstNext || (await call(`${BASE}/login?service=${encodeURIComponent(session.SERVICE)}`));
  const ticket = ((next.loc || '').match(/[?&]ticket=(ST-[^&]+)/) || [])[1];
  if (!ticket) throw new Error('换票失败：没拿到 service ticket');

  const step1 = await call(`${session.SERVICE}&ticket=${ticket}`, { referer: `${CAS}/` });
  const cookies = http.parseCookies(step1.setCookie);
  if (step1.loc) {
    const step2 = await call(new URL(step1.loc, session.SERVICE).toString(), { referer: session.SERVICE });
    cookies.push(...http.parseCookies(step2.setCookie));
  }
  if (!cookies.some((c) => c.name === 'LYSESSIONID')) throw new Error('没换到教务系统的会话');
  session.save({ cookies, tgc: jar.get('TGC')?.value });
  clearMfa();
  console.log(`  ✓ 教务系统会话已写入 ${session.SESSION_PATH}`);
  if (await session.isLoggedIn()) console.log('  ✓ 登录态正常');
  else throw new Error('会话拿到了但登录态不对');
}

async function login({ code: given, sendCode: onlySend } = {}) {
  const cred = readCredentials();
  if (!onlySend && given && !/^\d{4}$/.test(String(given))) {
    throw new Error(`验证码要是 4 位数字，收到：${given}`);
  }

  if (onlySend || !given) {
    const { next, mfa } = await passwordAuth(cred);
    if (!mfa) {
      console.log('  ✓ 不需要验证码');
      return finishLogin(next);
    }
    await sendCode(cred);
    try {
      saveMfa(cred.netid, mfa);
      console.log('  ✓ 本次登录会话已保留，可以继续下一步');
    } catch (err) {
      console.error(`  ! 本次登录会话没能保留（${err.message}），仍可直接用 --code 尝试`);
    }
    codeHint();
    return;
  }

  const saved = readMfa(cred.netid);
  if (saved) {
    let resumed = null;
    try {
      resumed = await resumeMfa(cred, given, saved);
    } catch (err) {
      console.error(`  ! 沿用上次登录会话失败（${err.message}），改用完整登录流程`);
      jar.clear();
    }
    if (resumed) return finishLogin(resumed);
  }

  const { next, mfa } = await passwordAuth(cred);
  if (!mfa) {
    console.log('  ✓ 不需要验证码');
    return finishLogin(next);
  }
  const finalNext = await submitCode(cred, mfaParams(mfa), given);
  return finishLogin(finalNext);
}

module.exports = { login };
