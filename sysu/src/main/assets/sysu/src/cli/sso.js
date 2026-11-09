// sysu sso —— 用统一认证（CAS）的 TGC 换任意站点的登录态
const sso = require('../sso');

const HELP = `sysu sso <站点地址>        换票并打印该站点的 Cookie
sysu sso <站点地址> --json 机器可读输出
sysu sso --ticket <地址>   只打印 service ticket

例子：
  sysu sso https://lms.sysu.edu.cn/login/index.php?authCAS=CAS
  sysu sso https://jwxt.sysu.edu.cn/jwxt/api/sso/cas/login?pattern=student-login

凭据来自一次 sysu login 留下的 TGC（~/.config/sysu/jwxt/session.json）。`;

const mask = (v) => (v.length <= 8 ? '••••' : `${v.slice(0, 4)}…${v.slice(-2)}（${v.length}）`);

async function main(argvIn = []) {
  const argv = argvIn.slice();
  const take = (flag) => {
    const i = argv.indexOf(flag);
    if (i < 0) return false;
    argv.splice(i, 1);
    return true;
  };
  const json = take('--json');
  const ticketOnly = take('--ticket');
  if (argv.includes('--help') || argv.includes('-h') || !argv.length) return console.log(HELP);

  const service = argv.find((a) => /^https?:\/\//.test(a));
  if (!service) throw new Error(`要一个完整的站点地址（http:// 或 https:// 开头）\n\n${HELP}`);

  if (ticketOnly) {
    const t = await sso.ticket(service);
    if (json) return console.log(JSON.stringify(t, null, 2));
    return console.log(t.ticket);
  }

  const { jar, finalUrl } = await sso.login(service, { label: '目标站点' });
  const cookies = jar.list();
  if (json) {
    return console.log(JSON.stringify({ service, finalUrl, cookies: cookies.map((c) => ({ ...c, value: mask(c.value) })) }, null, 2));
  }
  console.log(`站点：${finalUrl}`);
  console.log(`Cookie ${cookies.length} 条：`);
  for (const c of cookies) console.log(`  ${c.name} = ${mask(c.value)}`);
  console.log('\n（值为脱敏显示。）');
}

module.exports = { main, HELP };
