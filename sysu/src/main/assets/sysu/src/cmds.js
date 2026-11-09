const client = require('./client');

const WIDE = /[\u1100-\u115F\u2E80-\u303E\u3041-\u33FF\u3400-\u4DBF\u4E00-\u9FFF\uA960-\uA97F\uAC00-\uD7FF\uF900-\uFAFF\uFE10-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]/;
const width = (s) => [...String(s)].reduce((n, c) => n + (WIDE.test(c) ? 2 : 1), 0);
const pad = (s, n, right = false) => {
  const fill = ' '.repeat(Math.max(0, n - width(s)));
  return right ? fill + s : s + fill;
};

function table(rows, aligns = []) {
  const widths = rows[0].map((_, i) => Math.max(...rows.map((r) => width(r[i] ?? ''))));
  return rows
    .map((row) => row.map((cell, i) => pad(String(cell ?? ''), widths[i], aligns[i] === 'right')).join('  ').trimEnd())
    .join('\n');
}

const dim = (s) => `\x1b[2m${s}\x1b[0m`;

function progress(label) {
  if (!process.stderr.isTTY) return { update() {}, done() {} };
  const frames = '⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏';
  let text = label;
  let i = 0;
  const draw = () => process.stderr.write(`\r\x1b[2K\x1b[2m${frames[i++ % frames.length]} ${text}\x1b[0m`);
  const timer = setInterval(draw, 90);
  timer.unref();
  draw();
  return {
    update: (t) => (text = t),
    done: () => {
      clearInterval(timer);
      process.stderr.write('\r\x1b[2K');
    },
  };
}

// 不用 toLocaleDateString：精简版 Node 没有 sv-SE 数据，会退化成 m/d/yyyy
const today = () => new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
const addDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 864e5).toISOString().slice(0, 10);

async function weekOf(term, date) {
  const current = await client.getCurrentWeek(term);
  const { startTime } = await client.getWeekRange(term, current);
  const offset = (Date.parse(`${date}T00:00:00Z`) - Date.parse(`${startTime}T00:00:00Z`)) / 864e5;
  return current + Math.floor(offset / 7);
}

const timeOf = (item, times) => (item.type === 'exam' && item.time) || client.sectionTime(item.sections, times);

function printSchedule(days, times = {}) {
  if (!days.length) return console.log('  （无课）');
  const label = (i) => `${i.sections} 节 ${timeOf(i, times)}`.trim();
  const padTo = Math.max(...days.flatMap((d) => d.items.map((i) => width(label(i))))) + 2;
  for (const [n, day] of days.entries()) {
    console.log(`${n ? '\n' : ''}${day.weekday} ${day.date.slice(5)}`);
    for (const item of day.items) {
      const mark = item.type === 'exam' ? '\x1b[1m[考试]\x1b[0m ' : '';
      console.log(`  ${pad(label(item), padTo)}${mark}${item.name}`);
      const meta = [item.teacher, item.place].filter(Boolean).join(' · ');
      if (meta) console.log(dim(`${' '.repeat(padTo + 2)}${meta}`));
    }
  }
}

async function schedule({ week, today: onlyToday, tomorrow, json }) {
  await client.ensureLogin();
  const { acadYearSemester: term } = await client.getTerm();

  const date = onlyToday || tomorrow ? addDays(today(), tomorrow ? 1 : 0) : null;
  const target = week ?? (date ? await weekOf(term, date) : await client.getCurrentWeek(term));
  const [range, times] = await Promise.all([
    client.getWeekRange(term, target),
    client.getSectionTimes(term),
  ]);
  const days = (await client.getSchedule(term, target)).filter((d) => !date || d.date === date);

  if (json) return console.log(JSON.stringify({ term, week: target, ...range, days }, null, 2));

  const title = date ? `${onlyToday ? '今天' : '明天'} ${date}` : `${term} · 第 ${target} 周`;
  console.log(dim(`${title}（${range.startTime} ~ ${range.endTime.slice(5)}）`));
  printSchedule(days, times);
}

function summarize(semester, courses) {
  const credit = courses.reduce((n, c) => n + (Number(c.scoCredit) || 0), 0);
  const graded = courses.filter((c) => typeof c.scoPoint === 'number');
  const points = graded.reduce((n, c) => n + c.scoPoint * c.scoCredit, 0);
  const total = graded.reduce((n, c) => n + c.scoCredit, 0);
  return { semester, count: courses.length, credit, gpa: total ? points / total : null };
}

async function scores({ semester, all, json }) {
  await client.ensureLogin();
  const { acadYearSemester: term } = await client.getTerm();
  const pick = async (s) => {
    const courses = (await client.getScores(s)) || [];
    return { ...summarize(s, courses), courses };
  };

  let results = [];
  if (semester) {
    results = [await pick(semester)];
  } else {
    const [{ grade }] = await client.getCredits();
    if (all) {
      for (let s = `${grade}-1`; s <= term; s = client.nextSemester(s)) {
        const r = await pick(s);
        if (r.courses.length) results.push(r);
      }
    } else {
      for (let s = term; s >= `${grade}-1`; s = client.prevSemester(s)) {
        const r = await pick(s);
        if (r.courses.length) { results = [r]; break; }
      }
    }
  }

  if (json) return console.log(JSON.stringify(results, null, 2));
  if (!results.length) return console.log(dim('还没有任何成绩'));

  results.forEach((r, i) => {
    if (i) console.log();
    if (!r.courses.length) return console.log(dim(`${r.semester} 还没有成绩`));
    console.log(dim(`${r.semester} · ${r.count} 门 · ${r.credit.toFixed(1)} 学分` +
      (r.gpa === null ? '' : ` · 加权绩点 ${r.gpa.toFixed(3)}`)));
    console.log(table(
      [['课程', '学分', '成绩', '绩点'],
       ...r.courses.map((c) => [
         c.scoCourseName,
         Number(c.scoCredit).toFixed(1),
         c.scoFinalScore,
         typeof c.scoPoint === 'number' ? c.scoPoint.toFixed(1) : '-',
       ])],
      ['left', 'right', 'right', 'right'],
    ));
  });
}

const fixed = (n) => Number(n || 0).toFixed(1);

const CATEGORIES = [
  ['专业必修', 'majorMinCredit', 'majorFinishCredit'],
  ['专业选修', 'majorSelectMinCredit', 'majorSelectFinishCredit'],
  ['公共必修', 'publicMinGraduationCredit', 'publicFinishCredit'],
  ['公共选修', 'publicSelectMinCredit', 'publicSelectFinishCredit'],
  ['荣誉课程', 'honorCourseMinCredit', 'honorCourseFinishCredit'],
];

async function credits({ json }) {
  await client.ensureLogin();
  const plans = await client.getCredits();
  if (json) return console.log(JSON.stringify(plans, null, 2));

  plans.forEach((p, i) => {
    if (i) console.log();
    console.log(dim(`${p.grade} 级 · ${p.professionNumber}`));
    console.log(table([
      ['毕业学分', `${fixed(p.finishCredit)} / ${p.minGraduationCredit}`, `${p.finishCreditRate}%`],
      ...CATEGORIES.filter(([, min]) => p[min]).map(([label, min, done]) => [label, `${fixed(p[done])} / ${p[min]}`, '']),
    ], ['left', 'right', 'right']));
  });
}

const daysFromNow = (date) => Math.round((Date.parse(`${date}T00:00:00Z`) - Date.parse(`${today()}T00:00:00Z`)) / 864e5);

async function exams({ json }) {
  await client.ensureLogin();
  const { acadYearSemester: term } = await client.getTerm();
  const spin = progress('读取整学期课表');
  const list = await client.getExams(term, (d, t) => spin.update(`读取整学期课表 ${d}/${t} 周`));
  spin.done();

  if (json) return console.log(JSON.stringify({ term, exams: list }, null, 2));
  if (!list.length) return console.log(dim(`${term} 还没有已排期的考试`));

  console.log(dim(`${term} · 共 ${list.length} 场考试（含结课考、缓补考）`));
  console.log();
  console.log(table([
    ['日期', '星期', '节次', '时间', '课程', '地点', '距今天'],
    ...list.map((e) => {
      const left = daysFromNow(e.date);
      return [
        e.date,
        e.weekday.replace('星期', '周'),
        e.sections,
        e.time,
        e.name,
        shortPlace(e.place),
        left > 0 ? `${left} 天后` : left === 0 ? '就是今天' : '已过',
      ];
    }),
  ]));
}

function leaveDays(begin, end) {
  const ms = end - begin;
  if (ms <= 0) return 0;
  let days = Math.floor(ms / 864e5);
  const hours = Math.floor((ms % 864e5) / 36e5);
  if (hours === 4) days += 0.5;
  if (hours === 10 || hours === 22) days += 1;
  return days;
}

const leaveType = (days) => (days < 8 ? ['1', '短假'] : days <= 40 ? ['2', '长假'] : ['3', '休学']);

function parseWhen(input, isBegin) {
  const m = /^(\d{4}-\d{2}-\d{2})(?:\s*(上午|下午))?$/.exec(String(input).trim());
  if (!m) throw new Error(`日期写成 2026-10-08 或 "2026-10-08 下午"，收到：${input}`);
  const am = m[2] !== '下午';
  const time = isBegin ? (am ? '08:00:00' : '14:00:00') : am ? '12:00:00' : '18:00:00';
  return `${m[1]} ${time}`;
}

function ask(question) {
  if (!process.stdin.isTTY) return Promise.resolve(false);
  const rl = require('node:readline').createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(question, (a) => (rl.close(), resolve(/^y(es)?$/i.test(a.trim())))));
}

async function leave({ apply, from, to, reason, note, days, file, dryRun, yes, json }) {
  await client.ensureLogin();
  const [summary, records, reasons, roll] = await Promise.all([
    client.getLeaveSummary(),
    client.getLeaveRecords(),
    client.getLeaveReasons(),
    client.getStudentRoll(),
  ]);

  if (!apply && !from && !to && !reason) {
    if (json) return console.log(JSON.stringify({ summary, records: records.rows }, null, 2));
    console.log(dim(`${roll.basicName} · ${roll.studentNumber} · ${roll.rollGrade} 级 · 已请假 ${summary.curStuLeaveDaysCount} 天`));
    console.log();
    console.log(table([
      ['学年学期', '申请日期', '原因', '类型', '开始', '结束', '天数', '审批'],
      ...(records.rows || []).map((r) => [r.semester, r.askLeaveApplyDate, r.askLeaveReasonName, r.askLeaveTypeName, r.askLeaveBeginTime, r.askLeaveEndTime, `${r.askLeaveDaysCount} 天`, r.approveStatusName]),
    ]));
    return;
  }

  if (!from || !to || !reason || !note) {
    throw new Error(
      '提交请假要同时给全：--from "2026-10-08 下午" --to "2026-10-09 上午" --reason 病假 --note "原因说明" --file 附件',
    );
  }

  const preview = dryRun || json;
  const begin = parseWhen(from, true);
  const end = parseWhen(to, false);
  const count = days === undefined ? leaveDays(new Date(begin.replace(/-/g, '/')), new Date(end.replace(/-/g, '/'))) : Number(days);
  if (!count) throw new Error('这段时间算不出请假天数，检查一下开始/结束时间');
  if (count > 40) throw new Error('单次请假超过 40 天要办休学，这个工具不做');
  const [typeCode, typeName] = leaveType(count);

  const found = reasons.find((r) => r.dataName === reason || r.dataNumber === String(reason));
  if (!found) throw new Error(`请假原因只能是：${reasons.map((r) => r.dataName).join(' / ')}`);

  const beginDate = begin.slice(0, 10);
  const endDate = end.slice(0, 10);
  const [semBegin, semEnd] = await Promise.all([
    client.getTermByDate(beginDate).catch(() => null),
    client.getTermByDate(endDate).catch(() => null),
  ]);
  if (!semBegin || !semEnd) {
    throw new Error(`请假日期要在学年学期内；${!semBegin ? beginDate : endDate} 查不到对应学期`);
  }
  if (semBegin.acadYearSemester !== semEnd.acadYearSemester) {
    throw new Error(
      `开始日期在 ${semBegin.acadYearSemester}、结束日期在 ${semEnd.acadYearSemester}，跨学期的申请这个工具不做`,
    );
  }

  const attach = file ? client.checkAttachFile(file) : null;
  if (!preview && !attach) {
    throw new Error(`请假必须带附件（学校系统硬性要求）：--file <${client.ATTACH_TYPES.join(' / ')} 文件>`);
  }

  const payload = {
    semester: semEnd.acadYearSemester,
    askLeaveBeginDate: begin,
    askLeaveEndDate: end,
    askLeaveDaysCount: count,
    askLeaveTypeCode: typeCode,
    askLeaveTypeName: typeName,
    askLeaveReasonCode: found.dataNumber,
    askLeaveReasonExplanation: note,
    whetherStuApply: '1',
  };

  if (json) return console.log(JSON.stringify(payload, null, 2));

  console.log(dim('请假申请（提交前确认）'));
  console.log(table([
    ['学年学期', payload.semester],
    ['时间', `${begin} → ${end}`],
    ['天数', `${count} 天${days === undefined ? '（自动算）' : ''}`],
    ['类型', typeName],
    ['原因', found.dataName],
    ['说明', note],
    ['附件', attach ? `${attach.name}（${Math.max(1, Math.round(attach.size / 1024))} KB）` : '未指定'],
  ], ['left', 'left']));

  const used = summary.curStuLeaveDaysCount + count;
  if (used > summary.curSemesterDaysCount / 3) {
    console.log(`\n\x1b[33m⚠ 本学期累计请假将达 ${used} 天，超过学期总天数的 1/3，学校规则是要办休学\x1b[0m`);
  }

  if (attach && !attach.ascii) {
    const asStored = [...attach.name]
      .map((c) => (/^[\x20-\x7e]$/.test(c) ? c : '?'.repeat(Buffer.byteLength(c))))
      .join('');
    console.log(
      `\n\x1b[33m⚠ 附件名不是纯 ASCII，教务系统会把它存成 ${asStored}` +
        `（服务端只认 ASCII 文件名，网页上传也一样）。想让人看清就先重命名成英文。\x1b[0m`,
    );
  }

  if (dryRun) return console.log(`\n${dim('--dry-run：没提交，附件也没上传。')}`);

  const ok = yes || (await ask('\n提交后会生成一条要老师审批的请假单。确认提交？(y/N) '));
  if (!ok) return console.log(dim('已取消，什么都没提交，附件也没上传。'));

  const bar = progress('上传附件…');
  const att = await client.uploadLeaveFile(file).finally(() => bar.done());
  payload.fileName = att.fileName;
  payload.filePath = att.filePath;

  await client.applyLeave(payload);
  console.log('\n✅ 已提交。用 jwxt leave 看审批进度。');
}

const stripHtml = (html) =>
  String(html || '')
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, '')
    .replace(/<\/(p|div|li|tr|h[1-6])>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();

async function notice({ id, num, json }) {
  await client.ensureLogin();
  if (id) {
    const html = await client.getNoticeDetail(id);
    const text = stripHtml(html);
    if (json) return console.log(JSON.stringify({ id, text }, null, 2));
    if (!text) throw new Error(`公告 ${id} 没有正文（id 对不对？先用 jwxt notice 看列表）`);
    return console.log(text);
  }
  const data = await client.getNotices(num);
  if (json) return console.log(JSON.stringify(data, null, 2));
  for (const [key, label] of [['education', '教务部公告'], ['college', '院系公告']]) {
    const rows = data[key] || [];
    if (!rows.length) continue;
    console.log(dim(`\n  ${label}`));
    console.log(table(rows.map((n) => [n.deliveryDate, n.title]), ['left', 'left']));
  }
  console.log(dim('\n  jwxt notice <id> 看全文  ·  --num N 控制条数'));
}

const SECTIONS = [
  'oneSection', 'twoSection', 'threeSection', 'fourSection', 'fiveSection', 'sixSection', 'sevenSection', 'eightSection',
  'nineSection', 'tenSection', 'elevenSection', 'twelveSection', 'thirteenSection', 'fourteenSection', 'fifteenSection', 'sixteenSection',
];

function compress(nums) {
  const runs = [];
  for (const n of [...nums].sort((a, b) => a - b)) {
    const last = runs[runs.length - 1];
    if (last && n === last[1] + 1) last[1] = n;
    else runs.push([n, n]);
  }
  return runs.map(([a, b]) => (a === b ? `${a}` : `${a}-${b}`));
}

function freeRanges(row) {
  const busy = [];
  SECTIONS.forEach((k, i) => {
    const cell = row[k];
    if (cell && cell.occupyReason) busy.push(i + 1);
  });
  const free = SECTIONS.map((_, i) => i + 1).filter((n) => !busy.includes(n));
  return compress(free);
}

async function room({ campus, building, room, week, day, sections, json }) {
  await client.ensureLogin();
  const term = await client.getTerm();
  const targetWeek = week || (await client.getCurrentWeek(term.acadYearSemester));

  const campuses = await client.getCampuses();
  const camp = campus ? campuses.find((c) => c.campusName.includes(campus)) : campuses.find((c) => c.campusName.includes('南校园')) || campuses[0];
  if (!camp) throw new Error(`没找到校区「${campus}」，有：${campuses.map((c) => c.campusName).join(' / ')}`);

  const buildings = await client.getBuildings(camp.id);
  const build = building
    ? buildings.find((b) => b.name.includes(building) || b.code === building)
    : buildings.find((b) => b.name.includes('第一教学楼'));
  if (!build) throw new Error(`没找到教学楼「${building}」，本校区有：${buildings.slice(0, 12).map((b) => b.name).join(' / ')} …`);

  let roomId;
  if (room) {
    const list = await client.getClassrooms(camp.id, build.id, room);
    const hit = list.find((r) => r.code === room) || list[0];
    if (!hit) throw new Error(`${build.name} 里没有「${room}」这间教室`);
    roomId = hit.id;
  }

  const param = {
    campusId: camp.id,
    teachingBuildID: build.id,
    yearTerm: term.acadYearSemester,
    weekA: String(targetWeek),
    weekB: String(targetWeek),
    dayWeeks: [],
    weekOrTime: 'week',
  };
  if (roomId) param.classroomID = roomId;
  const res = await client.getClassroomOccupancy(param);
  const rows = res.data || [];

  if (json) return console.log(JSON.stringify({ campus: camp, building: build, week: targetWeek, rows }, null, 2));
  if (!rows.length) return console.log(dim('没查到占用数据（教室或周次不对？）'));

  console.log(dim(`${camp.campusName} · ${build.name}${room ? ` ${room}` : ''} · ${term.acadYearSemester} 第 ${targetWeek} 周`));

  if (sections) {
    const [a, b] = sections.split('-').map(Number);
    const want = SECTIONS.slice(a - 1, b);
    const byRoom = new Map();
    for (const r of rows) if (!day || Number(r.dayWeek) === day) {
      const free = want.every((k) => !(r[k] && r[k].occupyReason));
      if (free) byRoom.set(r.classroomNum, true);
    }
    const freeRooms = [...byRoom.keys()];
    if (!freeRooms.length) return console.log(dim(`\n  第 ${sections} 节没有空教室`));
    console.log(`\n  第 ${sections} 节空闲的教室（${freeRooms.length} 间）：`);
    console.log('  ' + freeRooms.join('  '));
    return;
  }

  const slots = new Map();
  for (const r of rows) {
    if (day && Number(r.dayWeek) !== day) continue;
    const free = freeRanges(r);
    const busy = [];
    SECTIONS.forEach((k, i) => {
      const reason = r[k] && r[k].occupyReason;
      if (!reason) return;
      const last = busy[busy.length - 1];
      if (last && last.reason === reason && last.end === i) last.end = i + 1;
      else busy.push({ reason, start: i + 1, end: i + 1 });
    });
    const busyText = busy.map((b) => `${b.start === b.end ? b.start : `${b.start}-${b.end}`}节 ${b.reason}`);
    if (!slots.has(r.classroomNum)) slots.set(r.classroomNum, []);
    slots.get(r.classroomNum).push({ date: r.date, dayWeek: r.dayWeek, seats: r.seats, free, busyText });
  }
  for (const [num, days] of slots) {
    console.log(`\n  ${num} 教室（${days[0].seats} 座）`);
    console.log(table(days.map((d) => [
      `周${'一二三四五六日'[d.dayWeek - 1]} ${d.date.slice(5)}`,
      d.free.length ? `${d.free.join(', ')} 节空闲` : '全天占满',
      d.busyText.length ? dim(d.busyText.join(' · ')) : '',
    ]), ['left', 'left', 'left']));
    if (!room) break;
  }
  if (!room) console.log(dim('\n  加 --room <号> 看指定教室，加 --sections 9-11 反过来找空教室'));
}

async function ta({ json }) {
  await client.ensureLogin();
  const res = await client.getAssistantInfo();
  const rows = res.rows || res.data || [];
  if (json) return console.log(JSON.stringify(res, null, 2));
  if (!rows.length) return console.log(dim('没有助教信息（没当过助教就是空的）'));
  console.log(table([
    ['学年学期', '课程名称', '课程编码', '教学班号', '任课教师', '助教职责'],
    ...rows.map((r) => [r.semester, r.courseName, r.courseNum, r.classNumber, r.teacherName, r.jobDuty || '-']),
  ]));
}

const normalize = (s) => String(s).replace(/[（(][^）)]*[）)]/g, '').replace(/\s+/g, '').toLowerCase();

function weeksLabel(weeks) {
  const runs = [];
  for (const w of [...new Set(weeks)].sort((a, b) => a - b)) {
    const last = runs[runs.length - 1];
    if (last && w === last[1] + 1) last[1] = w;
    else runs.push([w, w]);
  }
  return `第 ${runs.map(([a, b]) => (a === b ? a : `${a}-${b}`)).join(', ')} 周`;
}

const shortPlace = (p) => String(p || '').replace(/^(南|东|北|珠海|深圳)校园/, '');

function para(label, text) {
  const lines = String(text || '')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  if (!lines.length) return;
  console.log(dim(`\n  ${label}`));
  for (const line of lines) console.log(`    ${line}`);
}

async function course({ name, week, syllabus, intro, json }) {
  if (!name) throw new Error('要查哪门课？课程码或名字都行：jwxt course LN3015 / jwxt course 国际贸易');
  await client.ensureLogin();
  const term = await client.getTerm();
  const keyword = normalize(name);
  const isCode = !/[\u4e00-\u9fff]/.test(name);

  const spin = progress('读取整学期课表与历年成绩');
  const [timetable, times, allScores] = await Promise.all([
    client.getSemesterTimetable(term.acadYearSemester, 24, 10, (d, t) => spin.update(`读取课表 ${d}/${t} 周，同时拉历年成绩`)),
    client.getSectionTimes(term.acadYearSemester),
    client.getAllScores(term.acadYearSemester),
  ]);
  spin.done();
  const myScores = allScores.filter(
    (s) => normalize(s.scoCourseName).includes(keyword) || normalize(s.scoCourseName) === keyword,
  );

  let outline = null;
  let info = null;
  let courseNum = null;
  let courseName = name;
  let how = 'given';
  let ambiguous = [];

  if (isCode) {
    outline = await client.getCourseOutline(name);
    if (!outline) throw new Error(`${name} 查不到教学大纲（课程码对不对？）`);
    courseNum = outline.outlineInfo.courseNum;
    courseName = outline.outlineInfo.courseName;
  } else {
    const candidates = [
      ...new Set(
        timetable
          .filter((d) => !week || d.week === week)
          .flatMap((d) => d.items)
          .filter((i) => i.type === 'class' && normalize(i.name).includes(keyword))
          .map((i) => i.name),
      ),
    ];
    if (candidates.length > 1) {
      console.log(`「${name}」在本学期匹配到 ${candidates.length} 门课，说具体一点：\n`);
      for (const n of candidates) {
        const hit = timetable.flatMap((d) => d.items).filter((i) => i.name === n);
        console.log(`  ${n}  ·  ${hit[0].teacher}  ·  共 ${hit.length} 次课`);
      }
      return;
    }
    courseName = candidates[0] || name;

    const teachers = [
      ...new Set(
        timetable.flatMap((d) => d.items).filter((i) => normalize(i.name) === normalize(courseName)).map((i) => i.teacher).filter(Boolean),
      ),
    ];
    const fromGrade = myScores.find((s) => s.scoCourseNumber);
    const library = await client.findCourses(courseName).catch(() => []);
    const exact = library.filter((c) => normalize(c.courseName) === normalize(courseName));

    const use = async (row, source) => {
      info = row;
      courseNum = row.courseNumber;
      how = source;
      outline = await client.getCourseOutline(courseNum).catch(() => null);
    };

    if (fromGrade) {
      courseNum = fromGrade.scoCourseNumber;
      how = 'grade';
      info = exact.find((c) => c.courseNumber === courseNum) || null;
      outline = await client.getCourseOutline(courseNum).catch(() => null);
    } else if (exact.length === 1) {
      await use(exact[0], 'exact');
    } else if (exact.length > 1) {
      for (const row of exact) {
        const o = await client.getCourseOutline(row.courseNumber).catch(() => null);
        const names = [o?.outlineInfo?.teacherName, ...(o?.teacherList || []).map((t) => t.name)].filter(Boolean);
        if (names.some((n) => teachers.some((t) => n.includes(t)))) {
          outline = o;
          info = row;
          courseNum = row.courseNumber;
          how = 'teacher';
          break;
        }
      }
      if (!courseNum) ambiguous = exact;
    } else {
      const loose = library.filter((c) => normalize(c.courseName).includes(keyword));
      if (loose.length === 1) await use(loose[0], 'loose');
    }
  }

  const wanted = normalize(courseName);
  const mine = [];
  for (const day of timetable) {
    if (week && day.week !== week) continue;
    for (const item of day.items) {
      if (item.type !== 'class' || normalize(item.name) !== wanted) continue;
      mine.push({ week: day.week, date: day.date, weekday: day.weekday, ...item });
    }
  }

  const myExams = timetable
    .flatMap((d) => d.items.filter((i) => i.type === 'exam').map((i) => ({ week: d.week, date: d.date, weekday: d.weekday, ...i })))
    .filter((e) => normalize(e.name) === wanted);
  const scores = allScores.filter((s) => normalize(s.scoCourseName).includes(wanted));

  if (!outline && !mine.length && !scores.length && !myExams.length) {
    throw new Error(
      isCode ? `${name} 查不到：没有教学大纲，课表和历史成绩里也没有（课程码对不对？）` : `没找到「${name}」：课表、成绩、课程库里都没有`,
    );
  }

  if (json) {
    return console.log(JSON.stringify({
      term: term.acadYearSemester,
      course: courseName,
      courseNum,
      outline: outline?.outlineInfo || null,
      syllabus: outline?.scheduleList || [],
      sessions: mine,
      exams: myExams,
      scores,
    }, null, 2));
  }

  const o = outline?.outlineInfo;
  console.log(`\x1b[1m${courseName}\x1b[0m${o?.courseEngName ? `  ·  ${o.courseEngName}` : ''}`);

  const facts = [
    courseNum,
    (o?.credit || info?.credit) && `${Number(o?.credit ?? info?.credit).toFixed(1)} 学分 / ${o?.totalHours ?? info?.totalHours} 学时`,
    o?.courseTypeName || info?.courseCategoryNumberName,
    o?.establishUnitNumberName || info?.establishUnitNumberName,
    o?.languageName,
    o?.planClassSize && `容量 ${o.planClassSize}`,
  ].filter(Boolean);
  if (facts.length) console.log(dim(`  ${facts.join(' · ')}`));

  if (how === 'teacher') console.log(dim('  （课程码是按授课教师对上的）'));
  if (!courseNum) {
    console.log(dim('  ⚠ 没能确定课程码，所以查不到大纲'));
    if (ambiguous.length) {
      console.log(dim('    课程库里有这些同名课，用课程码查：'));
      for (const c of ambiguous.slice(0, 6)) {
        console.log(dim(`      ${c.courseNumber}  ${c.establishUnitNumberName}  ${Number(c.credit).toFixed(1)} 学分  ${c.buildSchoolSemester} 学期开课`));
      }
    }
  }

  const teach = [
    o?.teacherName && `教师 ${o.teacherName}`,
    o?.coursePrincipal && `课程负责人 ${o.coursePrincipal}`,
    o?.currentFlowNum && `大纲 ${o.currentFlowNum}`,
    o?.lecturesCreHours && `讲授 ${o.lecturesCreHours} 学时`,
    o?.tutorialsCreHours && `辅导 ${o.tutorialsCreHours} 学时`,
  ].filter(Boolean);
  if (teach.length) console.log(dim(`  ${teach.join(' · ')}`));

  if (mine.length) {
    console.log(dim(`\n  上课 · ${term.acadYearSemester}`));
    const slots = new Map();
    for (const s of mine) {
      const key = [s.weekday, s.sections, s.place].join('|');
      if (!slots.has(key)) slots.set(key, { ...s, weeks: [] });
      slots.get(key).weeks.push(s.week);
    }
    console.log(table([...slots.values()]
      .sort((a, b) => a.weekday.localeCompare(b.weekday) || parseInt(a.sections) - parseInt(b.sections))
      .map((s) => [
        `${s.weekday.replace('星期', '周')} ${s.sections} 节`,
        client.sectionTime(s.sections, times),
        shortPlace(s.place),
        weeksLabel(s.weeks),
      ])));
  } else {
    console.log(dim(`\n  本学期${week ? `第 ${week} 周` : ''}没有排课`));
  }

  if (myExams.length) {
    console.log(dim('\n  考试'));
    console.log(table(myExams.map((e) => [e.date, e.weekday.replace('星期', '周'), `${e.sections} 节`, e.time, shortPlace(e.place)])));
  }

  if (scores.length) {
    console.log(dim('\n  成绩'));
    console.log(table(scores.map((s) => [s.semester, s.scoCourseName, s.scoFinalScore, s.scoPoint ?? '-', `${Number(s.scoCredit).toFixed(1)} 学分`])));
  }

  if (intro) {
    para('课程简介', o?.courseContentInChinese);
    para('课程简介（英文）', o?.courseContentInEnglish);
    para('课程目标与要求', o?.courseObjectiveAndRequirement);
    para('先修课程', o?.cambrid);
    para('考核方式', o?.evaluationMethod);
    para('教学安排', o?.teachArrangement);
    para('教学方法', o?.teachMethod);
    para('教材', o?.courseTextBook);
    para('参考书', o?.referenceBook);
  }

  const schedule = outline?.scheduleList || [];
  const byWeek = schedule.some((w) => w.weekNum);
  if (syllabus && schedule.length) {
    console.log(dim(`\n  教学进度 · ${schedule.length} ${byWeek ? '周' : '讲'}`));
    [...schedule]
      .sort((a, b) => (byWeek ? Number(a.weekNum) - Number(b.weekNum) : Number(a.sort) - Number(b.sort)))
      .forEach((w, i) => {
        const no = byWeek ? `第 ${String(w.weekNum).padStart(2)} 周` : `第 ${String(i + 1).padStart(2)} 讲`;
        console.log(`    ${no}  ${w.sectionDesignation || ''}${w.teachingHours ? `  （${w.teachingHours} 学时）` : ''}`);
        for (const [label, text] of [['内容', w.teachingMainContent], ['要素', w.courseElements], ['重点', w.keyPoints]]) {
          if (!text) continue;
          String(text)
            .split(/\r?\n/)
            .map((l) => l.trim())
            .filter(Boolean)
            .forEach((line, n) => console.log(dim(`        ${n === 0 ? label : '    '}  ${line}`)));
        }
      });
  }

  const tips = [];
  if (!intro && o?.courseContentInChinese) tips.push('--intro 看简介/考核方式/教材');
  if (!syllabus && schedule.length) tips.push(`--syllabus 看逐周进度（${schedule.length} ${byWeek ? '周' : '讲'}）`);
  if (tips.length) console.log(dim(`\n  ${tips.join('  ·  ')}`));
}

const HELP = `sysu jwxt                      本周课表
sysu jwxt schedule --week N    第 N 周  ·  --today / --tomorrow 只看今天/明天
sysu jwxt scores               成绩（默认最近有成绩的学期）· --all 全部 · --semester 2025-2
sysu jwxt credits              毕业学分进度
sysu jwxt exams                考试安排
sysu jwxt course LN3015        某门课（课程码或课名）· --intro 简介/考核/教材 · --syllabus 逐周
sysu jwxt leave                请假记录
sysu jwxt leave apply --from "2026-10-08 下午" --to "2026-10-09 上午"
                 --reason 事假|病假|丧假 --note "说明" --file 附件
                 [--days N] [--dry-run] [--yes]
sysu jwxt notice [id]          教务/院系公告（给 id 看全文）· --num N
sysu jwxt room [选项]          空闲教室
sysu jwxt ta                   助教信息
sysu login                     统一登录（jwxt 与 lms 共用，一次搞定）
                 --code 1234   企业微信验证码（10 分钟内有效）

空闲教室
  --building <名>   --room <号>   --week N（默认本周）
  --campus <名>     默认南校园
  --day <1-7>       只看某天        --sections 9-11  反过来找这节空着的教室

--json  输出 JSON（请假时只预览、不上传附件）
附件   jpg / jpeg / png / gif / pdf，真提交时必填，交了撤不回来
会话失效会自动续期；坏了才要 sysu login`;

async function run(cmd, opts) {
  if (cmd === 'help') return console.log(HELP);
  await { schedule, scores, credits, exams, course, leave, notice, room, ta }[cmd](opts);
}

module.exports = {
  run,
  HELP,
  COMMANDS: ['schedule', 'scores', 'credits', 'exams', 'course', 'leave', 'notice', 'room', 'ta', 'help'],
};
