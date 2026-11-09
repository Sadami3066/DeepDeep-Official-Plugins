// sysu login —— 一次登录，所有站点共用
//
// 流程沿用插件页那套：账密 → 需要 MFA 时把 CAS 会话存进 mfa.json（10 分钟）
// → 页面/终端提示输入 4 位码 → sysu login --code 1234 续上。
// 登录完顺手把各站点会话预热：jwxt 续期、lms 换票。
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { login } = require('../login');
const session = require('../session');
const lms = require('../lms');
const { red, dim } = require('../style');

const MFA_PATH = path.join(os.homedir(), '.config/sysu/jwxt/mfa.json');

const HELP = `sysu login                统一登录（账密 + 企业微信验证码，一次搞定所有站点）
sysu login --code 1234    验证码已发时，用它续上（10 分钟内有效）
sysu login --force        强制重新登录（即使当前会话还活着）
sysu login --send-code    强制登录并发送验证码（插件页用的就是它）

  登录后 jwxt / lms 共用同一份 CAS 票据，不用分别登录。`;

async function main(argvIn = []) {
  const argv = argvIn.slice();
  const take = (f) => {
    const i = argv.indexOf(f);
    if (i < 0) return false;
    argv.splice(i, 1);
    return true;
  };
  const json = take('--json');
  const sendCode = take('--send-code');
  const force = take('--force') || sendCode;
  let code = null;
  const ci = argv.indexOf('--code');
  if (ci >= 0) {
    code = argv[ci + 1];
    argv.splice(ci, 2);
  }
  if (take('--help') || take('-h')) return console.log(HELP);
  if (code && !/^\d{4}$/.test(String(code))) throw new Error(`验证码要是 4 位数字，收到：${code}`);

  const already = !force && !code && (await session.isLoggedIn());
  if (already) {
    if (!json) console.log('已经登录了（加 --force 可强制重新登录）');
  } else {
    await login({ code: code || undefined, sendCode });
  }

  // 刚发出验证码：mfa.json 还在，等用户把码填回来
  if (fs.existsSync(MFA_PATH)) {
    const hint = '验证码已发到企业微信，10 分钟内执行：sysu login --code <4 位>';
    if (json) return console.log(JSON.stringify({ ok: false, needMfa: true, hint }, null, 2));
    return console.log(dim(hint));
  }

  // 预热各站点
  const status = { jwxt: false, lms: false, lmsNote: null };
  try {
    status.jwxt = await session.isLoggedIn();
  } catch {}

  try {
    await lms.connect({});
    status.lms = true;
  } catch (err) {
    status.lmsNote = err.message;
  }

  if (json) {
    return console.log(JSON.stringify({ ok: status.jwxt, needMfa: false, ...status }, null, 2));
  }
  if (!already) console.log(status.jwxt ? '  ✓ 教务系统就绪' : red('  ✗ 教务系统没通'));
  if (status.lms) console.log('  ✓ 在线教学平台就绪');
  else console.log(dim(`  · 在线教学平台：${status.lmsNote}`));
}

module.exports = { main, HELP };
