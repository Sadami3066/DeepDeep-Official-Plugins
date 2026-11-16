// sysu gym —— 体育场馆预订（gym.sysu.edu.cn）
const gym = require('../gym');

const HELP = `sysu gym                  我的账户 + 校区
sysu gym types            场馆类型（--campus / --all）
sysu gym venues <类型>     某类型下的场地（--venue 过滤）
sysu gym slots <类型>      可预约时段（默认 1 天，--days 2 / --date 2026-11-10 / --venue 场地）
sysu gym mine             我的预约（--days 30）
sysu gym book --type 乒乓球 --venue 场地2 --date 2026-11-10 --start 08:00 --end 09:00
                          下单（默认 dry-run，加 --yes 才真提交）
sysu gym cancel <预约号>   取消预约（--yes 免确认）
sysu gym login [--code N] 换取 token（--force 强制刷新）
sysu gym token            当前 token 状态

<类型> 可以写名称关键字（如 乒乓球）或完整 id。
数据命令默认输出 JSON；--json 只为兼容旧命令保留，不改变输出。`;

const today = () => new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
const addDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 864e5).toISOString().slice(0, 10);
const timeShort = (s) => String(s || '').slice(0, 5);
const maxAdvance = (type) => type?.BookingRules?.[0]?.MaxAdvanceBookingDays ?? 2;
const windowEnd = (type, start) => addDays(start, maxAdvance(type));
const clean = (s) =>
  String(s || '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&[a-z#0-9]+;/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim();

const isTest = (t) => /^test/i.test(String(t?.Name || '').trim());
const visibleTypes = (list, opts) => (opts.all ? list : list.filter((t) => !isTest(t)));

function noticeOf(t) {
  const text = clean(t?.Description);
  if (!text) return '';
  const hit = text.split(/[。！!；;]/).find((s) => /暂停|停开|维护|闭馆|活动/.test(s));
  return hit ? hit.slice(0, 72) : '';
}

function shortNames(names) {
  if (names.length < 2) return names;
  let prefix = names[0];
  for (const n of names) {
    while (prefix && !n.startsWith(prefix)) prefix = prefix.slice(0, -1);
  }
  const at = Math.max(prefix.lastIndexOf('-'), prefix.lastIndexOf('—'), prefix.lastIndexOf('–'));
  const base = at >= 0 ? prefix.slice(0, at + 1) : '';
  return names.map((n) => (base ? n.slice(base.length).replace(/^[\s·-]+/, '') || n : n));
}

function pickFee(list, profile, start) {
  const role = profile?.Type || '';
  const covers = (f) => {
    const ts = f?.Timeslot || {};
    return (!ts.StartTime || ts.StartTime <= start) && (!ts.EndTime || start < ts.EndTime);
  };
  return (
    list.find((f) => role && f.UserRole === role && covers(f)) ||
    list.find((f) => (!f.UserRole || covers(f))) ||
    list[0] ||
    null
  );
}

const venueMatch = (venue, keyword) =>
  !keyword || venue.Identity === keyword || String(venue.Name || '').includes(keyword);

async function campusMap(opts) {
  const list = await gym.campuses(opts);
  return new Map(list.map((c) => [c.Identity, c.Name]));
}

async function resolveType(input, opts) {
  const list = visibleTypes(await gym.types(opts), opts);
  if (!input) return list;
  const hit = list.find((t) => t.Identity === input) || list.filter((t) => t.Name.includes(input));
  return Array.isArray(hit) ? hit : [hit];
}

async function showOverview(opts) {
  const [me, cmap, all] = await Promise.all([gym.me(opts), campusMap(opts), gym.types(opts)]);
  const types = visibleTypes(all, opts);
  console.log(JSON.stringify({ ok: true, me, campuses: [...cmap.values()], types }, null, 2));
}

async function showTypes(opts) {
  const [cmap, all] = await Promise.all([campusMap(opts), gym.types(opts)]);
  let rows = visibleTypes(all, opts);
  if (opts.campus) rows = rows.filter((t) => (cmap.get(t.Campus) || '').includes(opts.campus));
  console.log(JSON.stringify({ ok: true, types: rows }, null, 2));
}

async function showVenues(input, opts) {
  const list = await resolveType(input, opts);
  if (!list.length) throw new Error('没匹配到场馆类型');
  const types = [];
  for (const t of list) {
    let vs;
    try {
      vs = await gym.venues(t.Identity, opts);
    } catch (err) {
      types.push({ id: t.Identity, name: t.Name, error: err.message });
      continue;
    }
    if (opts.venue) vs = vs.filter((v) => venueMatch(v, opts.venue));
    const short = shortNames(vs.map((v) => v.Name));
    types.push({
      id: t.Identity,
      name: t.Name,
      venues: vs.map((v, i) => ({ id: v.Identity, name: v.Name, shortName: short[i], capacity: v.Capacity ?? null })),
    });
  }
  const errors = types.filter((t) => t.error).map((t) => ({ id: t.id, name: t.name, error: t.error }));
  console.log(JSON.stringify({ ok: types.some((t) => !t.error), types, ...(errors.length ? { errors } : {}) }, null, 2));
}

async function showSlots(input, opts) {
  const list = await resolveType(input, opts);
  if (!list.length) throw new Error('没匹配到场馆类型');
  const start = opts.date || today();
  const profile = await gym.me(opts).catch(() => null);
  const types = [];

  for (const t of list) {
    const days = Number(opts.days) || 1;
    const asked = opts.date ? opts.date : addDays(start, Math.max(0, days - 1));
    const limit = windowEnd(t, start);
    const end = asked > limit ? limit : asked;

    let data;
    try {
      data = await gym.slots(t.Identity, start, end, opts);
    } catch (err) {
      types.push({ id: t.Identity, name: t.Name, error: err.message });
      continue;
    }

    const feeList = await gym.fees(t.Identity, opts).catch(() => []);
    const fee = pickFee(feeList, profile, '08:00');
    const matched = opts.venue
      ? data.filter((v) => venueMatch({ Identity: v.VenueId, Name: v.VenueName }, opts.venue))
      : data;
    const short = shortNames(matched.map((v) => v.VenueName));
    const venues = [];

    for (let i = 0; i < matched.length; i += 1) {
      const v = matched[i];
      const slots = (v.Timeslots || [])
        .filter((s) => s.AvailableCapacity > 0)
        .map((s) => ({ date: s.Date, start: timeShort(s.Start), end: timeShort(s.End), available: s.AvailableCapacity }));
      if (slots.length) venues.push({ id: v.VenueId, name: v.VenueName, shortName: short[i], slots });
    }

    const notice = venues.length ? '' : noticeOf(t);
    types.push({ id: t.Identity, name: t.Name, window: { start, end }, fee, notice, venues });
  }

  const errors = types.filter((t) => t.error).map((t) => ({ id: t.id, name: t.name, error: t.error }));
  console.log(JSON.stringify({ ok: types.some((t) => !t.error), types, ...(errors.length ? { errors } : {}) }, null, 2));
}

async function showMine(opts) {
  const list = await gym.bookings({ days: opts.days }, opts);
  console.log(JSON.stringify({ ok: true, bookings: list }, null, 2));
}

async function run(argvIn = []) {
  const argv = argvIn.slice().filter((arg) => arg !== '--json');
  const opts = { all: argv.includes('--all'), days: 1, campus: null, date: null, venue: null, code: null, force: false };
  const takeValue = (name) => {
    const i = argv.indexOf(name);
    if (i < 0) return;
    opts[name.slice(2)] = argv[i + 1];
    argv.splice(i, 2);
  };
  const takeFlag = (name) => {
    const i = argv.indexOf(name);
    if (i < 0) return;
    opts[name.slice(2)] = true;
    argv.splice(i, 1);
  };
  takeValue('--days');
  takeValue('--campus');
  takeValue('--date');
  takeValue('--code');
  takeValue('--charge');
  takeFlag('--all');
  takeFlag('--force');
  if (argv.includes('--help') || argv.includes('-h')) return console.log(HELP);

  const cmd = argv.shift() || 'overview';
  if (cmd === 'venues' || cmd === 'slots') takeValue('--venue');
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
    for (const k of ['--type', '--venue', '--date', '--start', '--end']) {
      const i = argv.indexOf(k);
      if (i >= 0) { opts[k.slice(2)] = argv[i + 1]; argv.splice(i, 2); }
    }
    if (!opts.type || !opts.venue || !opts.date || !opts.start || !opts.end) {
      throw new Error('用法：sysu gym book --type 乒乓球 --venue 场地2 --date 2026-11-10 --start 08:00 --end 09:00 [--yes]');
    }
    const [t] = await resolveType(opts.type, opts);
    if (!t) throw new Error('没找到场馆类型：' + opts.type);
    const first = today();
    const last = windowEnd(t, first);
    if (opts.date < first || opts.date > last) {
      throw new Error(`该场地只能提前 ${maxAdvance(t)} 天预约（${first} ~ ${last}），${opts.date} 不在窗口内`);
    }
    const vs = await gym.venues(t.Identity, opts);
    const v = vs.find((x) => x.Identity === opts.venue) || vs.find((x) => x.Name.includes(opts.venue));
    if (!v) throw new Error(`类型「${t.Name}」里没有场地：${opts.venue}`);

    const profile = await gym.me(opts);
    const feeList = await gym.fees(t.Identity, opts).catch(() => []);
    const fee = pickFee(feeList, profile, opts.start);
    const charge = opts.charge != null ? Number(opts.charge) : (fee?.CreditFee ?? 0);
    const dryRun = !argv.includes('--yes');
    const r = await gym.book(
      { typeId: t.Identity, typeName: t.Name, venueId: v.Identity, venueName: v.Name, date: opts.date, start: opts.start, end: opts.end, charge, dryRun, profile },
      opts,
    );

    if (!dryRun && Number(r.result?.Code) !== 200) {
      console.log(JSON.stringify({ ok: false, error: { message: r.result?.Result || '预约失败', raw: r.result } }, null, 2));
      process.exitCode = 1;
      return undefined;
    }
    return console.log(JSON.stringify({ ok: true, dryRun, charge, ...(dryRun ? { payload: r.payload } : { result: r.result }) }, null, 2));
  }
  if (cmd === 'cancel') {
    if (!arg) throw new Error('用法：sysu gym cancel <预约号>');
    const list = await gym.bookings({ days: 60 }, opts);
    const b = list.find((x) => x.Identity === arg || x.BookingId === arg);
    if (!b) throw new Error('没找到这条预约：' + arg);
    const dryRun = !argv.includes('--yes');
    const r = dryRun ? null : await gym.cancel(b.Identity, opts);
    return console.log(JSON.stringify({
      ok: true,
      dryRun,
      booking: { id: b.Identity, bookingId: b.BookingId, venue: b.VenueName, start: b.StartDateTime, end: b.EndDateTime },
      ...(dryRun ? {} : { result: r }),
    }, null, 2));
  }
  if (cmd === 'login') {
    const before = gym.readToken();
    const token = await gym.ensureToken({ force: opts.force || Boolean(opts.code), code: opts.code });
    const after = gym.readToken();
    const left = after ? Math.max(0, Math.round((3500 * 1000 - (Date.now() - after.at)) / 60000)) : 0;
    const reused = before?.access_token === token && !opts.force && !opts.code;
    return console.log(JSON.stringify({ ok: true, valid: true, minutes: left, refreshed: !reused }, null, 2));
  }
  if (cmd === 'token') {
    const t = gym.readToken();
    const left = t ? Math.max(0, Math.round((3500 * 1000 - (Date.now() - t.at)) / 60000)) : 0;
    return console.log(JSON.stringify({ ok: true, valid: Boolean(t && left > 0), minutes: left }, null, 2));
  }
  throw new Error(`未知命令：${cmd}\n\n${HELP}`);
}

async function main(argvIn = []) {
  return run(argvIn);
}

module.exports = { main, HELP };
