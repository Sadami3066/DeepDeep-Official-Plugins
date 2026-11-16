const client = require('./client');

// 不用 toLocaleDateString：精简版 Node 没有 sv-SE 数据，会退化成 m/d/yyyy
const today = () => new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
const addDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 864e5).toISOString().slice(0, 10);

async function weekOf(term, date) {
  const current = await client.getCurrentWeek(term);
  const { startTime } = await client.getWeekRange(term, current);
  const offset = (Date.parse(`${date}T00:00:00Z`) - Date.parse(`${startTime}T00:00:00Z`)) / 864e5;
  return current + Math.floor(offset / 7);
}

async function schedule({ week, today: onlyToday, tomorrow }) {
  await client.ensureLogin();
  const { acadYearSemester: term } = await client.getTerm();

  const date = onlyToday || tomorrow ? addDays(today(), tomorrow ? 1 : 0) : null;
  const target = week ?? (date ? await weekOf(term, date) : await client.getCurrentWeek(term));
  const range = await client.getWeekRange(term, target);
  const days = (await client.getSchedule(term, target)).filter((d) => !date || d.date === date);

  console.log(JSON.stringify({ term, week: target, ...range, days }, null, 2));
}

function summarize(semester, courses) {
  const credit = courses.reduce((n, c) => n + (Number(c.scoCredit) || 0), 0);
  const graded = courses.filter((c) => typeof c.scoPoint === 'number');
  const points = graded.reduce((n, c) => n + c.scoPoint * c.scoCredit, 0);
  const total = graded.reduce((n, c) => n + c.scoCredit, 0);
  return { semester, count: courses.length, credit, gpa: total ? points / total : null };
}

async function scores({ semester, all }) {
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

  console.log(JSON.stringify({ ok: true, semesters: results }, null, 2));
}

async function credits() {
  await client.ensureLogin();
  const plans = await client.getCredits();
  console.log(JSON.stringify({ ok: true, plans }, null, 2));
}

async function exams() {
  await client.ensureLogin();
  const { acadYearSemester: term } = await client.getTerm();
  const list = await client.getExams(term);
  console.log(JSON.stringify({ term, exams: list }, null, 2));
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

async function leave({ apply, from, to, reason, note, days, file, dryRun, yes }) {
  await client.ensureLogin();
  const [summary, records, reasons] = await Promise.all([
    client.getLeaveSummary(),
    client.getLeaveRecords(),
    client.getLeaveReasons(),
  ]);

  if (!apply && !from && !to && !reason) {
    console.log(JSON.stringify({ ok: true, summary, records: records.rows }, null, 2));
    return;
  }

  if (!from || !to || !reason || !note) {
    throw new Error(
      '提交请假要同时给全：--from "2026-10-08 下午" --to "2026-10-09 上午" --reason 病假 --note "原因说明" --file 附件',
    );
  }

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
  if (!dryRun && !attach) {
    throw new Error(`请假必须带附件（学校系统硬性要求）：--file <${client.ATTACH_TYPES.join(' / ')} 文件>`);
  }
  if (!dryRun && !yes) throw new Error('提交请假要加 --yes；先用 --dry-run 预览');

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

  const warnings = [];
  const used = summary.curStuLeaveDaysCount + count;
  if (used > summary.curSemesterDaysCount / 3) {
    warnings.push(`本学期累计请假将达 ${used} 天，超过学期总天数的 1/3，学校规则是要办休学`);
  }
  if (attach && !attach.ascii) {
    const asStored = [...attach.name]
      .map((c) => (/^[\x20-\x7e]$/.test(c) ? c : '?'.repeat(Buffer.byteLength(c))))
      .join('');
    warnings.push(`附件名不是纯 ASCII，教务系统会把它存成 ${asStored}；想让人看清就先重命名成英文`);
  }

  const output = {
    ok: true,
    dryRun,
    ...payload,
    attachment: attach ? { name: attach.name, size: attach.size, ascii: attach.ascii } : null,
    warnings,
  };

  if (dryRun) {
    console.log(JSON.stringify(output, null, 2));
    return;
  }

  const att = await client.uploadLeaveFile(file);
  payload.fileName = att.fileName;
  payload.filePath = att.filePath;
  await client.applyLeave(payload);
  console.log(JSON.stringify({ ...output, submitted: true, fileName: att.fileName, filePath: att.filePath }, null, 2));
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

async function notice({ id, num }) {
  await client.ensureLogin();
  if (id) {
    const html = await client.getNoticeDetail(id);
    const text = stripHtml(html);
    if (!text) throw new Error(`公告 ${id} 没有正文（id 对不对？先用 jwxt notice 看列表）`);
    console.log(JSON.stringify({ id, text }, null, 2));
    return;
  }
  const data = await client.getNotices(num);
  console.log(JSON.stringify(data, null, 2));
}

const SECTIONS = [
  'oneSection', 'twoSection', 'threeSection', 'fourSection', 'fiveSection', 'sixSection', 'sevenSection', 'eightSection',
  'nineSection', 'tenSection', 'elevenSection', 'twelveSection', 'thirteenSection', 'fourteenSection', 'fifteenSection', 'sixteenSection',
];

function freeRooms(rows, sections, day) {
  const [a, b] = sections.split('-').map(Number);
  if (!Number.isInteger(a) || !Number.isInteger(b) || a < 1 || b < a || b > SECTIONS.length) {
    throw new Error(`--sections 要写成 9-11（1~${SECTIONS.length} 节），收到：${sections}`);
  }
  const want = SECTIONS.slice(a - 1, b);
  const out = [];
  for (const row of rows) {
    if (day && Number(row.dayWeek) !== day) continue;
    if (want.every((key) => !(row[key] && row[key].occupyReason))) out.push(String(row.classroomNum));
  }
  return [...new Set(out)];
}

async function room({ campus, building, room, week, day, sections }) {
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
  let roomCode;
  if (room) {
    const list = await client.getClassrooms(camp.id, build.id, room);
    const hit = list.find((r) => r.code === room) || list[0];
    if (!hit) throw new Error(`${build.name} 里没有「${room}」这间教室`);
    roomId = hit.id;
    roomCode = hit.code || room;
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

  const output = { campus: camp, building: build, week: targetWeek, rows };
  if (day) output.day = day;
  if (sections) {
    const visible = roomCode ? rows.filter((r) => String(r.classroomNum) === String(roomCode)) : rows;
    output.sections = sections;
    output.freeRooms = freeRooms(visible, sections, day);
  }
  console.log(JSON.stringify(output, null, 2));
}

async function ta() {
  await client.ensureLogin();
  const res = await client.getAssistantInfo();
  console.log(JSON.stringify(res, null, 2));
}

const normalize = (s) => String(s).replace(/[（(][^）)]*[）)]/g, '').replace(/\s+/g, '').toLowerCase();

async function course({ name, week }) {
  if (!name) throw new Error('要查哪门课？课程码或名字都行：jwxt course LN3015 / jwxt course 国际贸易');
  await client.ensureLogin();
  const term = await client.getTerm();
  const keyword = normalize(name);
  const isCode = !/[\u4e00-\u9fff]/.test(name);

  const [timetable, allScores] = await Promise.all([
    client.getSemesterTimetable(term.acadYearSemester, 24, 10),
    client.getAllScores(term.acadYearSemester),
  ]);
  const myScores = allScores.filter(
    (s) => normalize(s.scoCourseName).includes(keyword) || normalize(s.scoCourseName) === keyword,
  );

  let outline = null;
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
      const options = candidates.map((n) => {
        const hit = timetable.flatMap((d) => d.items).filter((i) => i.name === n);
        return { name: n, teacher: hit[0]?.teacher || null, sessions: hit.length };
      });
      console.log(JSON.stringify({
        ok: true,
        term: term.acadYearSemester,
        query: name,
        ambiguous: true,
        candidates: options,
      }, null, 2));
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
      courseNum = row.courseNumber;
      how = source;
      outline = await client.getCourseOutline(courseNum).catch(() => null);
    };

    if (fromGrade) {
      courseNum = fromGrade.scoCourseNumber;
      how = 'grade';
      outline = await client.getCourseOutline(courseNum).catch(() => null);
    } else if (exact.length === 1) {
      await use(exact[0], 'exact');
    } else if (exact.length > 1) {
      for (const row of exact) {
        const o = await client.getCourseOutline(row.courseNumber).catch(() => null);
        const names = [o?.outlineInfo?.teacherName, ...(o?.teacherList || []).map((t) => t.name)].filter(Boolean);
        if (names.some((n) => teachers.some((t) => n.includes(t)))) {
          outline = o;
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

  const output = {
    term: term.acadYearSemester,
    course: courseName,
    courseNum,
    how,
    outline: outline?.outlineInfo || null,
    syllabus: outline?.scheduleList || [],
    sessions: mine,
    exams: myExams,
    scores,
  };
  if (ambiguous.length) {
    output.ambiguous = ambiguous.map((c) => ({
      courseNumber: c.courseNumber,
      courseName: c.courseName,
      unit: c.establishUnitNumberName,
      credit: c.credit,
      semester: c.buildSchoolSemester,
    }));
  }
  console.log(JSON.stringify(output, null, 2));
}

const HELP = `sysu jwxt                      本周课表
sysu jwxt schedule --week N    第 N 周  ·  --today / --tomorrow 只看今天/明天
sysu jwxt scores               成绩（默认最近有成绩的学期）· --all 全部 · --semester 2025-2
sysu jwxt credits              毕业学分进度
sysu jwxt exams                考试安排
sysu jwxt course LN3015        某门课（课程码或课名；返回大纲、上课、考试与成绩）
sysu jwxt leave                请假记录
sysu jwxt leave apply --from "2026-10-08 下午" --to "2026-10-09 上午"
                 --reason 事假|病假|丧假 --note "说明" --file 附件
                 [--days N] [--dry-run 预览 / --yes 提交]
sysu jwxt notice [id]          教务/院系公告（给 id 看全文）· --num N
sysu jwxt room [选项]          空闲教室
sysu jwxt ta                   助教信息
sysu login                     统一登录（jwxt 与 lms 共用，一次搞定）
                 --code 1234   企业微信验证码（10 分钟内有效）

空闲教室
  --building <名>   --room <号>   --week N（默认本周）
  --campus <名>     默认南校园
  --day <1-7>       只看某天        --sections 9-11  反过来找这节空着的教室
  不带 --room 时返回全部教室的占用数据

数据命令默认输出 JSON；--json 只为兼容旧命令保留，不改变输出。
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
