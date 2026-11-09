const fs = require('node:fs');
const path = require('node:path');
const http = require('./http');
const session = require('./session');

const BASE = session.JWXT;
const ATTACH_TYPES = ['jpg', 'jpeg', 'png', 'gif', 'pdf'];
const ATTACH_MIME = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  pdf: 'application/pdf',
};
const DAY_KEYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

const api = (pathname) => call(pathname);

function call(pathname, { method = 'GET', body } = {}) {
  const p = pathname.startsWith('/') ? pathname : `/${pathname}`;
  const options = { cookie: session.cookieHeader(), referer: BASE + '/' };
  const promise = method === 'POST' ? http.post(BASE + p, body, options) : http.get(BASE + p, options);
  return unwrap(pathname, promise);
}

function callBinary(pathname, buffer, contentType, timeout = 60000) {
  const p = pathname.startsWith('/') ? pathname : `/${pathname}`;
  const options = { cookie: session.cookieHeader(), referer: BASE + '/', timeout };
  return unwrap(pathname, http.postBinary(BASE + p, buffer, contentType, options));
}

function unwrap(pathname, promise) {
  return promise.then((res) => {
    if (res.status !== 200) {
      throw new Error(`HTTP ${res.status} · ${pathname}\n${res.body.slice(0, 300)}`);
    }
    let json;
    try {
      json = JSON.parse(res.body);
    } catch {
      throw new Error(`返回的不是 JSON · ${pathname}\n${res.body.slice(0, 200)}`);
    }
    if (json.code !== 200) throw new Error(`${json.message || '接口报错'}（code ${json.code}）`);
    return json.data;
  });
}

const getTerm = () => api('/base-info/acadyearterm/showNewAcadlist');
const getTermByDate = (date) => api(`/base-info/acadyearterm/getacadyeartermbydate?date=${date}`);

const getCurrentWeek = async (term) => {
  const d = await api(`/base-info/school-calender/weekly?academicYear=${term}`);
  return Number(d.nowWeekly);
};

const getWeekRange = async (term, week) => {
  const data = await api(`/base-info/school-calender?academicYear=${term}&weekly=${week}`);
  if (!data) throw new Error(`第 ${week} 周不在教学周范围内`);
  return data;
};

async function getSectionTimes(term) {
  const list = await api(`/base-info/AcadyeartermSet/minorName?schoolYear=${term}`);
  return Object.fromEntries((list || []).map((s) => [s.sectionNumber, { start: s.startTime, end: s.endTime }]));
}

function sectionTime(sections, times) {
  const nums = String(sections).match(/\d+/g)?.map(Number) || [];
  const first = times[nums[0]];
  const last = times[nums[nums.length - 1]];
  return first && last ? `${first.start}~${last.end}` : '';
}

function parseCell(cell) {
  const text = String(cell);
  const field = (key) => (text.match(new RegExp(`${key}:([^;]*)`)) || [, ''])[1].trim();
  const isExam = !text.includes('kcmc:') && text.includes('ksmc:');
  const [, date = '', weekday = ''] = field(isExam ? 'mobileKssj' : 'skrq').split('/');
  return {
    type: isExam ? 'exam' : 'class',
    date,
    weekday,
    name: field(isExam ? 'ksmc' : 'kcmc'),
    teacher: field(isExam ? 'jkry' : 'rkjs'),
    place: field(isExam ? 'ksdd' : 'skdd'),
    time: isExam ? (text.match(/kssj:([^;]+)/) || [, ''])[1].trim() : '',
  };
}

function compress(sections) {
  const sorted = [...sections].sort((a, b) => a - b);
  const runs = [];
  for (const n of sorted) {
    const last = runs[runs.length - 1];
    if (last && n === last[1] + 1) last[1] = n;
    else runs.push([n, n]);
  }
  return runs.map(([a, b]) => (a === b ? `${a}` : `${a}-${b}`));
}

async function getSchedule(term, week) {
  const rows = await api(
    `/timetable-search/classTableInfo/selectStudentClassTable?academicYear=${term}&weekly=${week}`,
  );

  const merged = new Map();
  for (const row of rows || []) {
    for (const day of DAY_KEYS) {
      if (!row[day]) continue;
      const cell = parseCell(row[day]);
      const key = [cell.date, cell.type, cell.name, cell.teacher, cell.place].join('|');
      if (!merged.has(key)) merged.set(key, { ...cell, sections: [] });
      merged.get(key).sections.push(row.section);
    }
  }

  const byDate = new Map();
  for (const { date, weekday, ...item } of merged.values()) {
    if (!byDate.has(date)) byDate.set(date, { date, weekday, items: [] });
    byDate.get(date).items.push({ ...item, sections: compress(item.sections).join(', ') });
  }

  return [...byDate.values()]
    .sort((a, b) => a.date.localeCompare(b.date))
    .map((d) => ({ ...d, items: d.items.sort((a, b) => parseInt(a.sections) - parseInt(b.sections)) }));
}

async function getAllScores(currentTerm) {
  const [{ grade }] = await getCredits();
  const out = [];
  for (let sem = `${grade}-1`; sem <= currentTerm; sem = nextSemester(sem)) {
    const list = (await getScores(sem).catch(() => [])) || [];
    for (const c of list) out.push({ semester: sem, ...c });
  }
  return out;
}

const getScores = (semester) => api(`/achievement-manage/score-check/listV2?semester=${semester}`);
const getCredits = () => api('/achievement-manage/score-check/credit-situation');

async function getSemesterTimetable(term, weeks = 24, concurrency = 10, onProgress) {
  const days = [];
  const queue = Array.from({ length: weeks }, (_, i) => i + 1);
  let done = 0;
  const worker = async () => {
    for (let week = queue.shift(); week; week = queue.shift()) {
      let list = [];
      try {
        list = await getSchedule(term, week);
      } catch {
        list = [];
      }
      for (const day of list) days.push({ week, ...day });
      onProgress?.(++done, weeks);
    }
  };
  await Promise.all(Array.from({ length: concurrency }, worker));
  return days.sort((a, b) => a.date.localeCompare(b.date));
}

async function getExams(term, onProgress) {
  const days = await getSemesterTimetable(term, 24, 10, onProgress);
  const exams = [];
  for (const day of days) {
    for (const item of day.items) {
      if (item.type === 'exam') exams.push({ week: day.week, date: day.date, weekday: day.weekday, ...item });
    }
  }
  return exams.sort((a, b) => a.date.localeCompare(b.date) || parseInt(a.sections) - parseInt(b.sections));
}

async function findCourses(name) {
  const data = await call('/base-info/courseLibrary/showCourseLibrary', {
    method: 'POST',
    body: { param: { courseName: name }, pageNo: 1, pageSize: 30, total: true },
  });
  return data?.rows || [];
}

async function getCourseOutline(courseNum) {
  try {
    const data = await call(
      `/training-programe/courseoutline/getalloutlineinfo?courseNum=${encodeURIComponent(courseNum)}&auditStatus=99`,
    );
    return data?.outlineInfo ? data : null;
  } catch {
    return null;
  }
}

const getLeaveReasons = () => api('/base-info/codedata/findcodedataNames?datableNumber=436');
const getLeaveSummary = () => api('/reports-register/askLeaveAgg/selfLeaveDaysInfo');

const getLeaveRecords = () =>
  call('/reports-register/askLeaveAgg/selfAskLeaveInfoList', {
    method: 'POST',
    body: { param: {}, pageNo: 1, pageSize: 50, total: true },
  });

const getStudentRoll = () => api('/student-status/countrystu/studentRollView');
const applyLeave = (payload) => call('/reports-register/askLeaveAgg/applyLeave', { method: 'POST', body: payload });

function checkAttachFile(file) {
  const name = path.basename(file);
  const ext = (name.includes('.') ? name.split('.').pop() : '').toLowerCase();
  if (!ATTACH_TYPES.includes(ext)) throw new Error(`附件只收 ${ATTACH_TYPES.join(' / ')}，收到：${name}`);
  let stat;
  try {
    stat = fs.statSync(file);
  } catch {
    throw new Error(`读不到附件：${file}`);
  }
  if (!stat.isFile()) throw new Error(`附件不是文件：${file}`);
  if (!stat.size) throw new Error(`附件是空文件：${file}`);
  const ascii = /^[\x20-\x7e]+$/.test(name);
  return { name, ext, size: stat.size, ascii };
}

async function uploadLeaveFile(file) {
  const { name, ext } = checkAttachFile(file);
  const data = fs.readFileSync(file);
  const boundary = `----jwxt${Date.now().toString(16)}${Math.random().toString(16).slice(2, 10)}`;
  const body = Buffer.concat([
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${name}"\r\n` +
        `Content-Type: ${ATTACH_MIME[ext]}\r\n\r\n`,
      'utf8',
    ),
    data,
    Buffer.from(`\r\n--${boundary}--\r\n`, 'utf8'),
  ]);
  const res = await callBinary(
    '/reports-register/askLeaveAgg/importFile',
    body,
    `multipart/form-data; boundary=${boundary}`,
  );
  if (!res?.fileName || !res?.filePath) throw new Error(`附件上传没返回 fileName/filePath：${JSON.stringify(res)}`);
  return res;
}

const getNotices = (num = 10) => api(`/system-manage/info-delivery?status=1&number=${num}`);
const getNoticeDetail = (id) => api(`/system-manage/info-delivery/noticeId?id=${encodeURIComponent(id)}`);

const getCampuses = () => api('/base-info/campus/findCampusNamesBox');
const getBuildings = (campusId) => api(`/base-info/teaching-building/pull?campusId=${campusId}`);
const getClassrooms = (campusId, buildingId, keyword = '') =>
  api(`/base-info/classroom/queryclassroombymulticondition?campusId=${campusId}&buildingId=${buildingId}&classroomCode=${encodeURIComponent(keyword)}`);

const getClassroomOccupancy = (param) =>
  call('/schedule/agg/classroomOccupy/pageCheckList', {
    method: 'POST',
    body: { pageNo: 1, pageSize: 999, total: true, param },
  });

const getAssistantInfo = () =>
  call('/assistant-manage/assistantInfoQuery/pageList', {
    method: 'POST',
    body: { pageNo: 1, pageSize: 100, total: true, param: {} },
  });

const prevSemester = (s) => {
  const [y, t] = s.split('-').map(Number);
  return t === 1 ? `${y - 1}-2` : `${y}-1`;
};
const nextSemester = (s) => {
  const [y, t] = s.split('-').map(Number);
  return t === 1 ? `${y}-2` : `${y + 1}-1`;
};

module.exports = {
  SESSION_PATH: session.SESSION_PATH,
  ensureLogin: session.ensureLogin,
  getTerm,
  getTermByDate,
  getCurrentWeek,
  getWeekRange,
  getSectionTimes,
  sectionTime,
  getSchedule,
  getSemesterTimetable,
  getExams,
  findCourses,
  getCourseOutline,
  getScores,
  getAllScores,
  getCredits,
  getNotices,
  getNoticeDetail,
  getCampuses,
  getBuildings,
  getClassrooms,
  getClassroomOccupancy,
  getAssistantInfo,
  getLeaveReasons,
  getLeaveSummary,
  getLeaveRecords,
  getStudentRoll,
  applyLeave,
  ATTACH_TYPES,
  checkAttachFile,
  uploadLeaveFile,
  prevSemester,
  nextSemester,
};
