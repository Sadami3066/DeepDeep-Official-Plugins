// sysu netpay —— 网费在线系统
const netpay = require('../netpay');
const { downloadDir } = require('../paths');
const path = require('node:path');
const output = require('../output');
const format = require('../format');

const HELP = `sysu netpay info          账户信息 + 网络服务（到期、状态）
sysu netpay orders        订单记录（--num N，默认 10）
sysu netpay renew         续费 1 个月（默认 dry-run，--yes 建单）
                          --months N 续 N 个月 · --out 支付页路径
sysu netpay cancel <单号>  取消未付款订单
sysu netpay login [--code N] 重新登录（会话坏了时）

数据来自 netpay.sysu.edu.cn；登录复用 sysu login 的 CAS 身份。
默认输出可读文本；--json 输出 JSON。
名下没有网络服务时，服务列表为空。`;

async function main(argvIn = []) {
  const argv = argvIn.slice().filter((arg) => arg !== '--json');
  const opts = { num: 10, months: 1, money: 30, yes: argv.includes('--yes') };
  const ni = argv.indexOf('--num');
  if (ni >= 0) {
    opts.num = Number(argv[ni + 1]);
    argv.splice(ni, 2);
  }
  const ci = argv.indexOf('--code');
  if (ci >= 0) {
    opts.code = argv[ci + 1];
    argv.splice(ci, 2);
    if (!/^\d{4,8}$/.test(String(opts.code || ''))) throw new Error('--code 要跟 4~8 位数字');
  }
  const yi = argv.indexOf('--yes');
  if (yi >= 0) argv.splice(yi, 1);
  for (const k of ['--months', '--money', '--out']) {
    const i = argv.indexOf(k);
    if (i >= 0) { opts[k.slice(2)] = k === '--out' ? argv[i + 1] : Number(argv[i + 1]); argv.splice(i, 2); }
  }
  if (argv.includes('--help') || argv.includes('-h') || !argv.length) return console.log(HELP);

  const cmd = argv[0] || 'info';

  if (cmd === 'login') {
    await netpay.login({ code: opts.code });
    return output.emit({ ok: true }, () => '网费已重新登录。');
  }

  if (cmd === 'info') {
    const info = await netpay.info();
    const services = await netpay.services();
    const payload = { info, services };
    return output.emit(payload, () => format.netpayInfo(payload));
  }

  if (cmd === 'renew') {
    const svc = (await netpay.services())[0];
    if (!svc) {
      return output.emit({ ok: true, renewable: false, message: '名下没有可续费的网络服务' }, () => '名下没有可续费的网络服务。');
    }
    if (!svc.id) {
      return output.emit({
        ok: true,
        renewable: false,
        service: svc.service,
        message: '服务没有可续费 ID（可能还没进续费窗口）',
      }, () => '服务没有可续费 ID（可能还没进续费窗口）。');
    }
    const months = Number(opts.months || 1);
    const money = Number(opts.money || 30) * months;
    const payload = { ok: true, service: svc.service, expire: svc.expire, months, money, dryRun: !opts.yes };
    if (!opts.yes) {
      return output.emit(payload, () => `预览（没有建单）\n${svc.service} · 续 ${months} 个月 · ￥${money}\n确认后加 --yes 生成未付款订单。`);
    }
    const r = await netpay.prepareRenew({ serviceId: svc.id, months, money });
    const fs = require('node:fs');
    const file = opts.out || path.join(downloadDir(), 'netpay-pay.html');
    const form = `<form id="f" method="post" action="${r.action}">` +
      r.inputs.map(([k, v]) => `<input type="hidden" name="${k}" value="${String(v).replace(/"/g, '&quot;')}">`).join('') +
      `</form><script>document.getElementById('f').submit()</script>`;
    fs.writeFileSync(file, form);
    return output.emit({ ...payload, dryRun: false, payPage: file }, () => `已生成未付款订单\n${svc.service} · 续 ${months} 个月 · ￥${money}\n支付页  ${file}`);
  }
  if (cmd === 'cancel') {
    const id = argv[1];
    if (!id) throw new Error('用法：sysu netpay cancel <订单号>');
    const st = await netpay.cancelOrder(id);
    const cancelled = st === 200;
    const payload = { ok: cancelled, order: id, status: st, cancelled };
    if (!cancelled) {
      payload.error = { message: `取消失败（HTTP ${st}）` };
      process.exitCode = 1;
    }
    return output.emit(payload, () => (cancelled ? `已取消订单 ${id}` : `取消失败（HTTP ${st}）`));
  }
  if (cmd === 'orders') {
    const list = await netpay.orders();
    const payload = { ok: true, total: list.length, orders: list.slice(0, opts.num) };
    return output.emit(payload, () => format.netpayOrders(payload));
  }

  throw new Error(`未知命令：${cmd}（用 sysu netpay --help 看可用命令）`);
}

module.exports = { main, HELP };
