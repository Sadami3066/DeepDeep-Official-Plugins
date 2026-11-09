// sysu lms —— 在线教学平台（Moodle）
const lms = require('../lms');
const sso = require('../sso');

const { red, green, dim, bold, width, pad, cut } = require('../style');
const { downloadDir } = require('../paths');
const C = { red, green, dim, bold };

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
--json   输出 JSON
数据来自 Moodle 的 ajax 接口；会话由 CAS 的 TGC 自动换取，失效会静默重试一次。`;

const fmtDate = (d) => (d ? `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}` : '—');

async function showCourses(opts) {
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const list = await lms.courses(lmsc);
  if (opts.json) return console.log(JSON.stringify(list, null, 2));
  if (!list.length) return console.log('没有选课记录（或者课程都隐藏了）');
  const rows = list.map((c) => [String(c.id), cut(c.name, 34), c.progress === null ? '—' : `${c.progress}%`, c.end || '—']);
  const head = ['ID', '课程', '进度', '结课'];
  const w = [6, 34, 6, 12].map((n, i) => Math.max(n, ...rows.map((r) => width(r[i])), width(head[i])));
  console.log(C.bold(head.map((h, i) => pad(h, w[i])).join('  ')));
  for (const r of rows) console.log(r.map((v, i) => pad(v, w[i])).join('  '));
  console.log(C.dim(`\n共 ${list.length} 门  ·  sysu lms course <id> 看详情`));
}

async function showTodo(opts) {
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const list = await lms.events(lmsc, { days: opts.days });
  if (opts.json) return console.log(JSON.stringify(list, null, 2));
  if (!list.length) return console.log(`未来 ${opts.days} 天没有待办`);
  list.sort((a, b) => (a.at?.getTime() || 0) - (b.at?.getTime() || 0));
  for (const e of list) {
    const when = e.at ? fmtDate(e.at) : '—';
    const days = e.at ? Math.ceil((e.at - Date.now()) / 864e5) : null;
    const flag = days !== null && days <= 2 ? C.red(when) : when;
    console.log(`${flag}  ${cut(e.name, 40)}`);
    if (e.course) console.log(C.dim(`         ${cut(e.course, 40)}`));
  }
  console.log(C.dim(`\n共 ${list.length} 项`));
}

async function showCourse(opts) {
  if (!opts.id) throw new Error('用法：sysu lms course <id>（id 从 sysu lms courses 看）');
  const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
  const sections = await lms.contents(lmsc, opts.id);
  if (opts.json) return console.log(JSON.stringify(sections, null, 2));
  if (!sections.length) return console.log('这门课没有可见内容');
  for (const sec of sections) {
    console.log(C.bold(sec.section || '(未命名)'));
    if (!sec.modules.length) console.log(C.dim('  （空）'));
    for (const m of sec.modules) {
      const tag = C.dim(`[${m.mod}]`);
      console.log(`  ${tag} ${cut(m.name, 52)}`);
      if (opts.urls && m.url) console.log(C.dim(`        ${m.url}`));
    }
    console.log('');
  }
}

async function main(argvIn = []) {
  const argv = argvIn.slice();
  const opts = {
    json: argv.includes('--json'),
    renew: argv.includes('--renew'),
    urls: argv.includes('--urls'),
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
  for (const flag of ['--json', '--renew', '--urls']) {
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
    console.log(`换票成功  ·  userid ${lmsc.userid || '?'}`);
    return undefined;
  }
  if (cmd === 'whoami') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const list = await lms.courses(lmsc);
    if (opts.json) return console.log(JSON.stringify({ userid: lmsc.userid, courses: list.length }, null, 2));
    console.log(`LMS userid：${lmsc.userid || '?'}`);
    console.log(`在修课程：${list.length} 门`);
    return undefined;
  }
  if (cmd === 'files') {
    opts.id = argv.find((a) => /^\d+$/.test(a)) || null;
    if (!opts.id) throw new Error('用法：sysu lms files <课程 id>');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const list = await lms.collectFiles(lmsc, opts.id);
    if (opts.json) return console.log(JSON.stringify(list, null, 2));
    if (!list.length) return console.log('这门课没有可下载的文件');
    for (const f of list) console.log(`  ${C.dim(`[${f.mod}]`)} ${cut(f.name, 46)}\n        ${C.dim(f.url)}`);
    console.log(C.dim(`\n共 ${list.length} 个  ·  sysu lms get ${opts.id} 全部下载`));
    return undefined;
  }
  if (cmd === 'get') {
    opts.id = argv.find((a) => /^\d+$/.test(a)) || null;
    if (!opts.id) throw new Error('用法：sysu lms get <课程 id> [--out 目录] [--only 关键词]');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const all = await lms.collectFiles(lmsc, opts.id);
    const list = opts.only ? all.filter((f) => f.name.includes(opts.only) || f.url.includes(encodeURIComponent(opts.only))) : all;
    if (!list.length) return console.log('没有匹配的文件');
    if (opts.dryRun) {
      for (const f of list) console.log(`  ${f.name}`);
      return console.log(C.dim(`\n共 ${list.length} 个（--dry-run 未下载）`));
    }
    const dir = opts.out || require('node:path').join(downloadDir(), `lms-${opts.id}`);
    console.log(C.dim(`下载到 ${dir}`));
    let ok = 0;
    let bytes = 0;
    for (const f of list) {
      process.stdout.write(`  ${cut(f.name, 40)} … `);
      try {
        const r = await lms.download(lmsc, f.url, dir, { referer: `${lms.BASE}/course/view.php?id=${opts.id}` });
        ok += 1;
        bytes += r.size;
        console.log(`${(r.size / 1048576).toFixed(1)} MB`);
      } catch (err) {
        console.log(C.red(`失败：${err.message}`));
      }
    }
    console.log(C.dim(`\n完成 ${ok}/${list.length}，共 ${(bytes / 1048576).toFixed(1)} MB`));
    return undefined;
  }
  if (cmd === 'assignments') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const cid = argv.find((a) => /^\d+$/.test(a));
    const list = await lms.assignments(lmsc, cid);
    if (opts.json) return console.log(JSON.stringify(list, null, 2));
    if (!list.length) return console.log('没有作业');
    for (const a of list) {
      const mark = a.submitted ? C.green('已交') : C.red('未交');
      console.log(`${mark}  ${cut(a.name, 34)}`);
      console.log(C.dim(`      ${cut(a.course, 34)}  ${a.due ? `剩余 ${a.due}` : ''}`));
      if (a.files?.length) console.log(C.dim(`      文件：${a.files.join(', ')}`));
    }
    const missing = list.filter((a) => !a.submitted).length;
    console.log(C.dim(`
共 ${list.length} 份，未交 ${missing} 份`));
    return undefined;
  }
  if (cmd === 'grades') {
    const cid = argv.find((a) => /^\d+$/.test(a));
    if (!cid) throw new Error('用法：sysu lms grades <课程 id>');
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const rows = await lms.grades(lmsc, cid);
    if (opts.json) return console.log(JSON.stringify(rows, null, 2));
    for (const r of rows) {
      const indent = '  '.repeat(Math.max(0, r.level - 1));
      const grade = r.grade && r.grade !== '-' ? r.grade : '';
      console.log(`${indent}${r.kind === 'cat' ? C.bold(r.name) : r.name}${grade ? '  ' + grade : ''}`);
    }
    return undefined;
  }
  if (cmd === 'notices') {
    const lmsc = await lms.connect({ renew: opts.renew, code: opts.code });
    const cid = argv.find((a) => /^\d+$/.test(a));
    const list = await lms.notices(lmsc, { courseid: cid, num: opts.num });
    if (opts.json) return console.log(JSON.stringify(list, null, 2));
    if (!list.length) return console.log('没有公告');
    for (const n of list) console.log(`  ${cut(n.title, 46)}\n      ${C.dim(cut(n.course, 30))} · ${n.url}`);
    return undefined;
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
