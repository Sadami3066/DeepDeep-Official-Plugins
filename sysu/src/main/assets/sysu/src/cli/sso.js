// sysu sso —— 用统一认证（CAS）的 TGC 换任意站点的登录态
const sso = require('../sso');
const output = require('../output');

const HELP = `sysu sso <站点地址>        换票并返回该站点的 Cookie
sysu sso --ticket <地址>   只返回 service ticket

例子：
  sysu sso https://lms.sysu.edu.cn/login/index.php?authCAS=CAS
  sysu sso https://jwxt.sysu.edu.cn/jwxt/api/sso/cas/login?pattern=student-login

默认输出可读文本；--json 输出 JSON。
凭据来自一次 sysu login 留下的 TGC（~/.config/sysu/jwxt/session.json）。`;

const mask = (v) => (v.length <= 8 ? '••••' : `${v.slice(0, 4)}…${v.slice(-2)}（${v.length}）`);

async function main(argvIn = []) {
  const argv = argvIn.slice().filter((arg) => arg !== '--json');
  const ticketOnly = argv.includes('--ticket');
  if (ticketOnly) argv.splice(argv.indexOf('--ticket'), 1);
  if (argv.includes('--help') || argv.includes('-h') || !argv.length) return console.log(HELP);

  const service = argv.find((a) => /^https?:\/\//.test(a));
  if (!service) throw new Error(`要一个完整的站点地址（http:// 或 https:// 开头）\n\n${HELP}`);

  if (ticketOnly) {
    const t = await sso.ticket(service);
    const payload = { ticket: t.ticket, location: t.location };
    return output.emit(payload, () => `service ticket\n${payload.ticket}\n跳转  ${payload.location}`);
  }

  const { jar, finalUrl } = await sso.login(service, { label: '目标站点' });
  const cookies = jar.list();
  const payload = { service, finalUrl, cookies: cookies.map((c) => ({ ...c, value: mask(c.value) })) };
  output.emit(payload, () => {
    const lines = [`站点  ${payload.service}`, `落地  ${payload.finalUrl}`, 'Cookie'];
    for (const cookie of payload.cookies) lines.push(`  ${cookie.name}=${cookie.value}`);
    return lines.join('\n');
  });
}

module.exports = { main, HELP };
