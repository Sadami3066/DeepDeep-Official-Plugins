// sysu lms —— 在线教学平台（Moodle）
const lms = require('../lms');
const { downloadDir } = require('../paths');
const output = require('../output');
const format = require('../format');

const HELP = `sysu lms courses           我的课程
sysu lms todo [--days N]   待办 / 作业截止（默认 14 天）
sysu lms course <id>       一门课的内容（作业、资料、测验…）
sysu lms whoami            当前 LMS 账号
sysu lms files <id>        列出这门课能下载的文件
sysu lms get <id>          下载全部文件（--out 目录 · --only 关键词 · --dry-run）
                           默认存到 ~/Downloads/lms-<id>，可用 SYSU_DOWNLOAD_DIR 覆盖
sysu lms assignments       作业总览（状态 / 剩余时间 / 已交文件）· 可跟课程 id
sysu lms grades <id>       课程成绩单
sysu lms notices           课程公告（--num N，默认 5）
sysu lms login [--code N]  强制重新换票（要 MFA 时加 --code）

--code N 企业微信验证码（会话过期且该站点要求二次验证时用）
默认输出可读文本；--json 输出 JSON。
数据来自 Moodle 的 ajax 接口；会话由 CAS 的 TGC 自动换取，失效会静默重试一次。`;

async function showCourses(opts) {
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const list = await lms.courses(lmsc);
  const payload = { ok: true, courses: list };
  output.emit(payload, () => format.lmsCourses(payload));
}

async function showTodo(opts) {
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const list = await lms.events(lmsc, { days: opts.days });
  const payload = { ok: true, days: opts.days, items: list };
  output.emit(payload, () => format.lmsTodo(payload));
}

async function showCourse(opts) {
  if (!opts.id) throw new Error('用法：sysu lms course <id>（id 从 sysu lms courses 看）');
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const sections = await lms.contents(lmsc, opts.id);
  const payload = { ok: true, course: opts.id, sections };
  output.emit(payload, () => format.lmsCourse(payload));
}

async function main(argvIn = []) {
  const argv = argvIn.slice().filter((arg) => arg !== '--json');
  const opts = {
    renew: argv.includes('--renew'),
    days: 14,
    id: null,
    code: null,
    out: null,
    only: null,
    num: 5,
    dryRun: argv.includes('--dry-run'),
  };
  for (const name of ['--out', '--only']) {
    const i = argv.indexOf(name);
    if (i >= 0) {
      opts[name.slice(2)] = argv[i + 1];
      argv.splice(i, 2);
    }
  }
  const ci = argv.indexOf('--code');
  if (ci >= 0) {
    opts.code = argv[ci + 1];
    argv.splice(ci, 2);
    if (!/^\d{4,8}$/.test(String(opts.code || ''))) throw new Error('--code 要跟 4~8 位数字');
  }
  const ni = argv.indexOf('--num');
  if (ni >= 0) {
    opts.num = Number(argv[ni + 1]);
    argv.splice(ni, 2);
  }
  const di = argv.indexOf('--days');
  if (di >= 0) {
    opts.days = Number(argv[di + 1]);
    argv.splice(di, 2);
    if (!(opts.days > 0)) throw new Error('--days 要是正数');
  }
  for (const flag of ['--renew', '--urls']) {
    const i = argv.indexOf(flag);
    if (i >= 0) argv.splice(i, 1);
  }
  if (argv.includes('--help') || argv.includes('-h') || !argv.length) return console.log(HELP);

  let cmd = argv.shift();
  if (cmd === 'list') cmd = 'courses';
  if (!['courses', 'todo', 'course', 'whoami', 'login', 'files', 'get', 'assignments', 'grades', 'notices', 'help'].includes(cmd)) {
    if (/^\d+$/.test(cmd)) {
      argv.unshift(cmd);
      cmd = 'course';
    } else {
      throw new Error(`未知命令：${cmd}（用 sysu lms --help 看可用命令）`);
    }
  }
  if (cmd === 'help') return console.log(HELP);
  if (cmd === 'login') {
    const lmsc = await lms.connect({ renew: true, code: opts.code });
    const payload = { ok: true, userid: lmsc.userid || null };
    return output.emit(payload, () => `在线教学 已重新登录${payload.userid ? `（${payload.userid}）` : ''}`);
  }
  if (cmd === 'whoami') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const list = await lms.courses(lmsc);
    const payload = { ok: true, userid: lmsc.userid || null, courses: list.length };
    return output.emit(payload, () => `账号 ${payload.userid || '未知'} · 在修课程 ${payload.courses} 门`);
  }
  if (cmd === 'files') {
    opts.id = argv.find((a) => /^\d+$/.test(a)) || null;
    if (!opts.id) throw new Error('用法：sysu lms files <课程 id>');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const list = await lms.collectFiles(lmsc, opts.id);
    const payload = { ok: true, course: opts.id, files: list };
    return output.emit(payload, () => format.lmsFiles(payload));
  }
  if (cmd === 'get') {
    opts.id = argv.find((a) => /^\d+$/.test(a)) || null;
    if (!opts.id) throw new Error('用法：sysu lms get <课程 id> [--out 目录] [--only 关键词]');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const all = await lms.collectFiles(lmsc, opts.id);
    const list = opts.only ? all.filter((f) => f.name.includes(opts.only) || f.url.includes(encodeURIComponent(opts.only))) : all;
    if (!list.length) {
      return require('../output').emit({ ok: true, dryRun: opts.dryRun, course: opts.id, total: 0, files: [] }, () => '没有可下载文件。');
    }
    if (opts.dryRun) {
      return require('../output').emit({
        ok: true,
        dryRun: true,
        course: opts.id,
        total: list.length,
        files: list.map((f) => ({ name: f.file || f.name, url: f.url })),
      }, () => format.lmsDownload({
        ok: true,
        dryRun: true,
        course: opts.id,
        total: list.length,
        files: list.map((f) => ({ name: f.file || f.name, url: f.url })),
      }));
    }
    const dir = opts.out || require('node:path').join(downloadDir(), `lms-${opts.id}`);
    const downloaded = [];
    const failed = [];
    for (const f of list) {
      try {
        const r = await lms.download(lmsc, f.url, dir, { referer: `${lms.BASE}/course/view.php?id=${opts.id}` });
        downloaded.push({ name: r.name, size: r.size, path: r.path });
      } catch (err) {
        failed.push({ name: f.file || f.name, error: err.message });
      }
    }
    const output = {
      ok: failed.length === 0,
      dryRun: false,
      course: opts.id,
      dir,
      total: list.length,
      downloaded,
      failed,
    };
    if (failed.length) process.exitCode = 1;
    return require('../output').emit(output, () => format.lmsDownload(output));
  }
  if (cmd === 'assignments') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const cid = argv.find((a) => /^\d+$/.test(a));
    const list = await lms.assignments(lmsc, cid);
    return require('../output').emit({ ok: true, assignments: list });
  }
  if (cmd === 'grades') {
    const cid = argv.find((a) => /^\d+$/.test(a));
    if (!cid) throw new Error('用法：sysu lms grades <课程 id>');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const rows = await lms.grades(lmsc, cid);
    return require('../output').emit({
      ok: true,
      course: cid,
      published: rows.some((r) => r.grade && r.grade !== '-'),
      grades: rows,
    });
  }
  if (cmd === 'notices') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const cid = argv.find((a) => /^\d+$/.test(a));
    const list = await lms.notices(lmsc, { courseid: cid, num: opts.num });
    return require('../output').emit({ ok: true, notices: list });
  }
  if (cmd === 'courses') return showCourses(opts);
  if (cmd === 'todo') return showTodo(opts);
  if (cmd === 'course') {
    opts.id = argv.find((a) => /^\d+$/.test(a)) || null;
    return showCourse(opts);
  }
  return undefined;
}

module.exports = { main, HELP };
