// sysu —— 中山大学校园服务统一入口
//
// 设计：sysu <站点> <命令>。加新站点只要在 SITES 里注册一个模块，
// 模块导出的 main(argv) 自己处理参数，互不影响。
const TOP = {
  login: {
    desc: '统一登录：账密 + 企业微信验证码，一次登录所有站点',
    main: () => require('./cli/login').main,
  },
};

const SITES = {
  jwxt: {
    desc: '本科教务系统：课表、成绩、学分、考试、空教室、请假',
    main: () => require('./cli/jwxt').main,
  },
  lms: {
    desc: '在线教学平台（Moodle）：课程、待办、课程内容',
    main: () => require('./cli/lms').main,
  },
  netpay: {
    desc: '网费在线：网络服务状态、到期日、订单记录',
    main: () => require('./cli/netpay').main,
  },
  gym: {
    desc: '体育场馆预订：场馆、空闲时段、我的预约',
    main: () => require('./cli/gym').main,
  },
  sso: {
    desc: '用统一认证换任意站点的登录态（图书馆、体育、其它系统）',
    main: () => require('./cli/sso').main,
  },
};

const VERSION = require('../package.json').version;

function usage() {
  const rows = Object.entries(SITES).map(([name, s]) => `  ${(name + '        ').slice(0, 8)}  ${s.desc}`);
  console.log(`sysu ${VERSION} —— 中山大学校园服务 CLI

用法：sysu <命令|站点> [选项]

全局：
  sysu login            统一登录（账密 + 企微验证码，一次搞定所有站点）

站点：
${rows.join('\n')}

常用：
  sysu jwxt              本周课表
  sysu jwxt --help       教务的全部命令
  sysu lms               我的课程
  sysu lms todo          作业与待办
  sysu gym               体育场馆（账户 / 类型 / 时段 / 预约）
  sysu netpay            校园网服务与网费订单
  sysu sso <地址>        换任意站点的登录态

站点数据命令默认输出 JSON；--json 只为兼容旧命令保留，不改变输出。`);
}

function errorJson(err) {
  const raw = err && err.message ? String(err.message) : String(err || '未知错误');
  const message = raw.replace(/\u001B\[[0-9;]*[A-Za-z]/g, '').trim() || '未知错误';
  const payload = { ok: false, error: { message } };
  if (err && err.code != null) payload.error.code = err.code;
  if (err && err.needMfa) {
    payload.needMfa = true;
    if (err.hint) payload.hint = String(err.hint);
  }
  console.log(JSON.stringify(payload, null, 2));
  process.exitCode = 1;
}

function dispatch(run, argv) {
  try {
    const out = run(argv);
    if (out && typeof out.catch === 'function') return out.catch(errorJson);
    return out;
  } catch (err) {
    errorJson(err);
    return undefined;
  }
}

function main(argvIn = []) {
  const argv = argvIn.slice().filter((arg) => arg !== '--json');
  if (!argv.length) return usage();
  if (argv[0] === '--version' || argv[0] === '-v') return console.log(VERSION);
  if (argv[0] === '--help' || argv[0] === '-h' || argv[0] === 'help') return usage();

  const name = argv.shift();
  if (TOP[name]) return dispatch(TOP[name].main(), argv);
  const site = SITES[name];
  if (!site) {
    errorJson(Object.assign(new Error(`未知站点：${name}（用 sysu --help 看可用站点）`), { code: 'UNKNOWN_SITE' }));
    return undefined;
  }
  return dispatch(site.main(), argv);
}

module.exports = { main, usage, SITES, VERSION };
