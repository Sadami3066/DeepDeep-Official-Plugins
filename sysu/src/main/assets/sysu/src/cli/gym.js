// sysu gym —— 体育场馆预订（gym.sysu.edu.cn）
const gym = require('../gym');
const { red, green, dim, bold, width, pad, cut } = require('../style');

const C = { red, green, dim, bold };

const HELP = `sysu gym                  我的账户 + 校区
sysu gym types            场馆类型（可按 --campus 过滤）
sysu gym venues <类型>     某类型下的场地
sysu gym slots <类型>      可预约时段（--days 3 / --date 2026-11-10）
sysu gym mine             我的预约（--days 30）
sysu gym book --type 乒乓球 --venue 场地2 --date 2026-11-10 --start 08:00 --end 09:00
                          下单（默认 dry-run，加 --yes 才真提交）
sysu gym cancel <预约号>   取消预约（--yes 免确认）
sysu gym login [--code N] 重新换 token
sysu gym token            看当前 token 状态

<类型> 可以写名称关键字（如 乒乓球）或完整 id。
首次使用会自动用 sysu login 留下的 TGC 换 token；要企业微信验证码时加 --code。`;

const today = () => new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
const addDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 864e5).toISOString().slice(0, 10);
const timeShort = (s) => String(s || '').slice(0, 5);

async function campusMap(opts) {
  const list = await gym.campuses(opts);
  return new Map(list.map((c) => [c.Identity, c.Name]));
}

async function resolveType(input, opts) {
  const list = await gym.types(opts);
  if (!input) return list;
  const hit = list.find((t) => t.Identity === input) || list.filter((t) => t.Name.includes(input));
  if (Array.isArray(hit)) return hit;
  return [hit];
}

async function showOverview(opts) {
  const [me, cmap, types] = await Promise.all([gym.me(opts), campusMap(opts), gym.types(opts)]);
  if (opts.json) return console.log(JSON.stringify({ me, campuses: [...cmap.values()], types }, null, 2));
  console.log(`${bold(me.Name || me.Identity)}  ·  ${me.Type || ''}  ·  积分 ${me.Credits ?? '?'}  ·  现金 ${me.CashWallet ?? '?'}`);
  console.log(dim(`校区：${[...cmap.values()].join(' / ')}`));
  console.log(dim(`${types.length} 个场馆类型  ·  sysu gym types 看全部`));
}

async function showTypes(opts) {
  const [cmap, list] = await Promise.all([campusMap(opts), gym.types(opts)]);
  let rows = list;
  if (opts.campus) rows = rows.filter((t) => (cmap.get(t.Campus) || '').includes(opts.campus));
  if (opts.json) return console.log(JSON.stringify(rows, null, 2));
  for (const t of rows) {
    console.log(`${cut(t.Name, 30)}  ${dim(cmap.get(t.Campus) || '')}`);
  }
  console.log(dim(`\n共 ${rows.length} 个  ·  sysu gym venues <关键字> 看场地`));
}

async function showVenues(input, opts) {
  const list = await resolveType(input, opts);
  if (!list.length) return console.log('没匹配到场馆类型');
  for (const t of list) {
    const vs = await gym.venues(t.Identity, opts);
    console.log(bold(t.Name));
    for (const v of vs) console.log(`  ${cut(v.Name, 40)}  容量 ${v.Capacity ?? '-'}`);
    console.log('');
  }
}

async function showSlots(input, opts) {
  const list = await resolveType(input, opts);
  if (!list.length) return console.log('没匹配到场馆类型');
  const start = opts.date || today();
  const end = opts.date ? opts.date : addDays(start, Math.max(0, opts.days - 1));
  for (const t of list) {
    const data = await gym.slots(t.Identity, start, end, opts);
    console.log(bold(`${t.Name}  ${start} ~ ${end}`));
    let any = false;
    for (const v of data) {
      const open = (v.Timeslots || []).filter((s) => s.AvailableCapacity > 0);
      if (!open.length) continue;
      any = true;
      console.log(`  ${cut(v.VenueName, 30)}`);
      const byDate = new Map();
      for (const s of open) {
        if (!byDate.has(s.Date)) byDate.set(s.Date, []);
        byDate.get(s.Date).push(`${timeShort(s.Start)}-${timeShort(s.End)}(${s.AvailableCapacity})`);
      }
      for (const [d, arr] of byDate) console.log(dim(`    ${d}  ${arr.join(' ')}`));
    }
    if (!any) console.log(dim('  这段时间没有空位'));
    console.log('');
  }
}

async function showMine(opts) {
  const list = await gym.bookings({ days: opts.days }, opts);
  if (opts.json) return console.log(JSON.stringify(list, null, 2));
  if (!list.length) return console.log(`近 ${opts.days} 天没有预约`);
  for (const b of list) {
    const fmt = (iso) => (iso ? new Date(new Date(iso).getTime() + 8 * 3600e3).toISOString().slice(5, 16).replace('T', ' ') : '?');
    const when = `${fmt(b.StartDateTime)}-${fmt(b.EndDateTime).slice(6)}`;
    console.log(`${when}  ${cut(b.VenueName || b.VenueTypeName || '', 30)}  ${dim(b.BookingId || '')}  ${dim(b.Status || '')}`);
  }
  console.log(dim(`\n共 ${list.length} 条`));
}

async function main(argvIn = []) {
  const argv = argvIn.slice();
  const opts = { json: argv.includes('--json'), days: 3, campus: null, date: null, code: null };
  const takeValue = (name) => {
    const i = argv.indexOf(name);
    if (i < 0) return;
    opts[name.slice(2)] = argv[i + 1];
    argv.splice(i, 2);
  };
  takeValue('--days');
  takeValue('--campus');
  takeValue('--date');
  takeValue('--code');
  for (const f of ['--json']) {
    const i = argv.indexOf(f);
    if (i >= 0) argv.splice(i, 1);
  }
  if (argv.includes('--help') || argv.includes('-h')) return console.log(HELP);

  let cmd = argv.shift() || 'overview';
  const arg = argv.find((a) => !a.startsWith('-'));
  if (cmd === 'overview') return showOverview(opts);
  if (cmd === 'types') return showTypes(opts);
  if (cmd === 'venues') return showVenues(arg, opts);
  if (cmd === 'slots') return showSlots(arg, opts);
  if (cmd === 'mine') {
    if (!argvIn.includes('--days')) opts.days = 30;
    return showMine(opts);
  }
  if (cmd === 'book') {
    for (const k of ['--type', '--venue', '--date', '--start', '--end', '--charge']) {
      const i = argv.indexOf(k);
      if (i >= 0) { opts[k.slice(2)] = argv[i + 1]; argv.splice(i, 2); }
    }
    if (!opts.type || !opts.venue || !opts.date || !opts.start || !opts.end) {
      throw new Error('用法：sysu gym book --type 乒乓球 --venue 场地2 --date 2026-11-10 --start 08:00 --end 09:00 [--yes]');
    }
    const [t] = await resolveType(opts.type, {});
    if (!t) throw new Error('没找到场馆类型：' + opts.type);
    const vs = await gym.venues(t.Identity, {});
    const v = vs.find((x) => x.Identity === opts.venue) || vs.find((x) => x.Name.includes(opts.venue));
    if (!v) throw new Error(`类型「${t.Name}」里没有场地：${opts.venue}`);
    const profile = await gym.me({});
    const dryRun = !argv.includes('--yes');
    const r = await gym.book(
      { typeId: t.Identity, typeName: t.Name, venueId: v.Identity, venueName: v.Name, date: opts.date, start: opts.start, end: opts.end, charge: opts.charge || 0, dryRun, profile },
      {},
    );
    if (r.dryRun) {
      console.log('DRY-RUN（未提交）');
      console.log(JSON.stringify(r.payload, null, 2));
      console.log(dim('\n确认无误后加 --yes 才会真正下单'));
      return undefined;
    }
    console.log(JSON.stringify(r.result, null, 2));
    return undefined;
  }
  if (cmd === 'cancel') {
    if (!arg) throw new Error('用法：sysu gym cancel <预约号>');
    const list = await gym.bookings({ days: 60 }, {});
    const b = list.find((x) => x.Identity === arg || x.BookingId === arg);
    if (!b) throw new Error('没找到这条预约：' + arg);
    const fmt = (iso) => new Date(new Date(iso).getTime() + 8 * 3600e3).toISOString().slice(5, 16).replace('T', ' ');
    console.log(`${b.BookingId}  ${b.VenueName}  ${fmt(b.StartDateTime)}-${fmt(b.EndDateTime).slice(6)}`);
    if (!argv.includes('--yes')) return console.log(dim('确认取消加 --yes'));
    const r = await gym.cancel(b.Identity, {});
    console.log(JSON.stringify(r, null, 2));
    return undefined;
  }
  if (cmd === 'login') {
    const t = await gym.ensureToken({ force: true, code: opts.code });
    console.log(`token 已更新  ·  ${t.slice(0, 16)}…`);
    return undefined;
  }
  if (cmd === 'token') {
    const fs = require('node:fs');
    const path = require('node:path');
    const os = require('node:os');
    const f = path.join(os.homedir(), '.config/sysu/gym.json');
    try {
      const t = JSON.parse(fs.readFileSync(f, 'utf8'));
      const left = Math.round((3500 * 1000 - (Date.now() - t.at)) / 1000);
      return console.log(`token 还有 ${left} 秒到期  ·  ${t.access_token.slice(0, 16)}…`);
    } catch {
      return console.log('还没有 token，跑 sysu gym 会自动换');
    }
  }
  throw new Error(`未知命令：${cmd}\n\n${HELP}`);
}

module.exports = { main, HELP };
