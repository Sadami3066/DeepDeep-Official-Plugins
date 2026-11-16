// sysu sso —— 用统一认证（CAS）的 TGC 换任意站点的登录态
const sso = require('../sso');

const HELP = `sysu sso <站点地址>        换票并返回该站点的 Cookie
sysu sso --ticket <地址>   只返回 service ticket

例子：
  sysu sso https://lms.sysu.edu.cn/login/index.php?authCAS=CAS
  sysu sso https://jwxt.sysu.edu.cn/jwxt/api/sso/cas/login?pattern=student-login

数据命令默认输出 JSON；--json 只为兼容旧命令保留，不改变输出。
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
    return console.log(JSON.stringify({ ticket: t.ticket, location: t.location }, null, 2));
  }

  const { jar, finalUrl } = await sso.login(service, { label: '目标站点' });
  const cookies = jar.list();
  console.log(JSON.stringify({ service, finalUrl, cookies: cookies.map((c) => ({ ...c, value: mask(c.value) })) }, null, 2));
}

module.exports = { main, HELP };
