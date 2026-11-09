// sysu netpay —— 网费在线系统
const netpay = require('../netpay');
const { red, dim, bold, width, pad, cut } = require('../style');
const { downloadDir } = require('../paths');
const path = require('node:path');

const C = { red, dim, bold };

const HELP = `sysu netpay               账户信息 + 网络服务（到期、状态）
sysu netpay orders        订单记录（--num N，默认 10）
sysu netpay renew         续费 1 个月（默认 dry-run，--yes 建单）
                          --months N 续 N 个月 · --out 支付页路径
sysu netpay cancel <单号>  取消未付款订单
sysu netpay login         重新登录（会话坏了时）

数据来自 netpay.sysu.edu.cn；登录复用 sysu login 的 CAS 身份。
名下没有网络服务时，服务列表会显示“无”。`;

async function main(argvIn = []) {
  const argv = argvIn.slice();
  const opts = { json: argv.includes('--json'), num: 10, months: 1, money: 30, yes: argv.includes('--yes') };
  const ni = argv.indexOf('--num');
  if (ni >= 0) {
    opts.num = Number(argv[ni + 1]);
    argv.splice(ni, 2);
  }
  for (const f of ['--json', '--yes']) {
    const i = argv.indexOf(f);
    if (i >= 0) argv.splice(i, 1);
  }
  for (const k of ['--months', '--money', '--out']) {
    const i = argv.indexOf(k);
    if (i >= 0) { opts[k.slice(2)] = k === '--out' ? argv[i + 1] : Number(argv[i + 1]); argv.splice(i, 2); }
  }
  if (argv.includes('--help') || argv.includes('-h')) return console.log(HELP);

  const cmd = argv[0] || 'info';

  if (cmd === 'login') {
    await netpay.login();
    console.log('已重新登录 netpay');
    return undefined;
  }

  if (cmd === 'info') {
    const [info, services] = await Promise.all([netpay.info(), netpay.services()]);
    if (opts.json) return console.log(JSON.stringify({ info, services }, null, 2));
    console.log(`${bold(info.xm || info.netid)}  ·  ${info.sn || ''}  ·  ${info.bmmc || ''}`);
    if (!services.length) {
      console.log(C.red('名下没有网络服务'));
      return undefined;
    }
    console.log('');
    for (const s of services) {
      console.log(`${cut(s.service, 26)}  ${s.status}  到期 ${s.expire}`);
      console.log(C.dim(`  ${s.address || ''}  ${s.mac || ''}`));
    }
    return undefined;
  }

  if (cmd === 'renew') {
    const svc = (await netpay.services())[0];
    if (!svc) return console.log('名下没有可续费的网络服务');
    if (!svc.id) return console.log('服务没有可续费 ID（可能还没进续费窗口）');
    const months = Number(opts.months || 1);
    const money = Number(opts.money || 30) * months;
    console.log(`${svc.service}  到期 ${svc.expire}  续 ${months} 个月  ${money} 元`);
    if (!opts.yes) {
      console.log(dim('\nDRY-RUN：确认后加 --yes 建单（只生成未付款订单）'));
      return undefined;
    }
    const r = await netpay.prepareRenew({ serviceId: svc.id, months, money });
    const fs = require('node:fs');
    const file = opts.out || path.join(downloadDir(), 'netpay-pay.html');
    const form = `<form id="f" method="post" action="${r.action}">` +
      r.inputs.map(([k, v]) => `<input type="hidden" name="${k}" value="${String(v).replace(/"/g, '&quot;')}">`).join('') +
      `</form><script>document.getElementById('f').submit()</script>`;
    fs.writeFileSync(file, form);
    console.log(`订单已生成（未付款），支付页：${file}`);
    console.log(dim('用 Chrome 打开它即可付款；不付会自动关闭。'));
    return undefined;
  }
  if (cmd === 'cancel') {
    const id = argv[1];
    if (!id) throw new Error('用法：sysu netpay cancel <订单号>');
    const st = await netpay.cancelOrder(id);
    console.log(st === 200 ? '已取消' : `HTTP ${st}`);
    return undefined;
  }
  if (cmd === 'orders') {
    const list = await netpay.orders();
    if (opts.json) return console.log(JSON.stringify(list, null, 2));
    if (!list.length) return console.log('没有订单');
    for (const o of list.slice(0, opts.num)) {
      console.log(`${o.time}  ${cut(o.service || '', 22)}  ${o.amount}  ${o.status}  ${dim(o.id)}`);
    }
    console.log(C.dim(`\n共 ${list.length} 条  ·  --num N 看更多`));
    return undefined;
  }

  throw new Error(`未知命令：${cmd}\n\n${HELP}`);
}

module.exports = { main, HELP };
