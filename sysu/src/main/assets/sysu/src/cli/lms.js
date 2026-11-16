// sysu lms —— 在线教学平台（Moodle）
const lms = require('../lms');
const { downloadDir } = require('../paths');

const HELP = `sysu lms                   我的课程
sysu lms courses           同上（显式写法）
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
数据命令默认输出 JSON；--json 只为兼容旧命令保留，不改变输出。
数据来自 Moodle 的 ajax 接口；会话由 CAS 的 TGC 自动换取，失效会静默重试一次。`;

async function showCourses(opts) {
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const list = await lms.courses(lmsc);
  console.log(JSON.stringify({ ok: true, courses: list }, null, 2));
}

async function showTodo(opts) {
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const list = await lms.events(lmsc, { days: opts.days });
  console.log(JSON.stringify({ ok: true, days: opts.days, items: list }, null, 2));
}

async function showCourse(opts) {
  if (!opts.id) throw new Error('用法：sysu lms course <id>（id 从 sysu lms courses 看）');
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const sections = await lms.contents(lmsc, opts.id);
  console.log(JSON.stringify({ ok: true, course: opts.id, sections }, null, 2));
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
  if (argv.includes('--help') || argv.includes('-h')) return console.log(HELP);

  let cmd = argv.shift() || 'courses';
  if (cmd === 'list') cmd = 'courses';
  if (!['courses', 'todo', 'course', 'whoami', 'login', 'files', 'get', 'assignments', 'grades', 'notices', 'help'].includes(cmd)) {
    if (/^\d+$/.test(cmd)) {
      argv.unshift(cmd);
      cmd = 'course';
    } else {
      throw new Error(`未知命令：${cmd}\n\n${HELP}`);
    }
  }
  if (cmd === 'help') return console.log(HELP);
  if (cmd === 'login') {
    const lmsc = await lms.connect({ renew: true, code: opts.code });
    return console.log(JSON.stringify({ ok: true, userid: lmsc.userid || null }, null, 2));
  }
  if (cmd === 'whoami') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const list = await lms.courses(lmsc);
    return console.log(JSON.stringify({ ok: true, userid: lmsc.userid || null, courses: list.length }, null, 2));
  }
  if (cmd === 'files') {
    opts.id = argv.find((a) => /^\d+$/.test(a)) || null;
    if (!opts.id) throw new Error('用法：sysu lms files <课程 id>');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const list = await lms.collectFiles(lmsc, opts.id);
    return console.log(JSON.stringify({ ok: true, course: opts.id, files: list }, null, 2));
  }
  if (cmd === 'get') {
    opts.id = argv.find((a) => /^\d+$/.test(a)) || null;
    if (!opts.id) throw new Error('用法：sysu lms get <课程 id> [--out 目录] [--only 关键词]');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const all = await lms.collectFiles(lmsc, opts.id);
    const list = opts.only ? all.filter((f) => f.name.includes(opts.only) || f.url.includes(encodeURIComponent(opts.only))) : all;
    if (!list.length) {
      return console.log(JSON.stringify({ ok: true, dryRun: opts.dryRun, course: opts.id, total: 0, files: [] }, null, 2));
    }
    if (opts.dryRun) {
      return console.log(JSON.stringify({
        ok: true,
        dryRun: true,
        course: opts.id,
        total: list.length,
        files: list.map((f) => ({ name: f.file || f.name, url: f.url })),
      }, null, 2));
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
    return console.log(JSON.stringify(output, null, 2));
  }
  if (cmd === 'assignments') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const cid = argv.find((a) => /^\d+$/.test(a));
    const list = await lms.assignments(lmsc, cid);
    return console.log(JSON.stringify({ ok: true, assignments: list }, null, 2));
  }
  if (cmd === 'grades') {
    const cid = argv.find((a) => /^\d+$/.test(a));
    if (!cid) throw new Error('用法：sysu lms grades <课程 id>');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const rows = await lms.grades(lmsc, cid);
    return console.log(JSON.stringify({
      ok: true,
      course: cid,
      published: rows.some((r) => r.grade && r.grade !== '-'),
      grades: rows,
    }, null, 2));
  }
  if (cmd === 'notices') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const cid = argv.find((a) => /^\d+$/.test(a));
    const list = await lms.notices(lmsc, { courseid: cid, num: opts.num });
    return console.log(JSON.stringify({ ok: true, notices: list }, null, 2));
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
