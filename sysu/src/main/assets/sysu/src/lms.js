// 中山大学在线教学平台（Moodle）客户端。
//
// 登录：CAS 换票 → MoodleSession；之后所有数据都走 Moodle 自己的 ajax 接口
// （/lib/ajax/service.php?sesskey=...），拿的是 JSON，不用解析页面。
const http = require('./http');
const sso = require('./sso');

const BASE = 'https://lms.sysu.edu.cn';
const SERVICE = `${BASE}/login/index.php?authCAS=CAS`;
const NAME = 'lms';

class Lms {
  constructor(session) {
    this.jar = session.jar;
    this.sesskey = session.sesskey;
    this.userid = session.userid;
  }

  cookie() {
    return this.jar.header(new URL(BASE).host);
  }

  async get(url, { referer = `${BASE}/my/` } = {}) {
    const res = await http.get(url.startsWith('http') ? url : BASE + url, {
      cookie: this.cookie(),
      referer,
    });
    this.jar.absorb(res, new URL(url.startsWith('http') ? url : BASE + url).host);
    return res;
  }

  // 页面里抠 M.cfg 的 sesskey / userid
  static parseConfig(html) {
    const sesskey = (html.match(/"sesskey"\s*:\s*"([^"]+)"/) || [])[1] || (html.match(/sesskey=([A-Za-z0-9]+)/) || [])[1];
    const userid =
      Number(
        (html.match(/"userid"\s*:\s*"?(\d+)"?/) || [])[1] ||
          (html.match(/user\/(?:view|profile)\.php\?id=(\d+)/) || [])[1] ||
          0,
      ) || null;
    return { sesskey, userid };
  }

  async refreshConfig() {
    const res = await this.get('/my/');
    const { sesskey, userid } = Lms.parseConfig(res.body);
    if (!sesskey) {
      // 掉登录了：可能是 cookie 过期
      const err = new Error('LMS 会话已失效');
      err.code = 'RELOGIN';
      throw err;
    }
    this.sesskey = sesskey;
    this.userid = userid || this.userid;
    sso.saveSession(NAME, { jar: this.jar, finalUrl: `${BASE}/my/` });
    return this;
  }

  // 调 Moodle 的 ajax 服务（session 鉴权，不需要 wstoken）
  async ajax(methodname, args = {}) {
    const url = `${BASE}/lib/ajax/service.php?sesskey=${encodeURIComponent(this.sesskey)}&info=${encodeURIComponent(methodname)}`;
    const body = JSON.stringify([{ index: 0, methodname, args }]);
    const res = await http.postBinary(url, body, 'application/json', { cookie: this.cookie(), referer: `${BASE}/my/`, timeout: 30000 });
    this.jar.absorb(res, new URL(url).host);
    let json;
    try {
      json = JSON.parse(res.body);
    } catch {
      const err = new Error(`LMS 返回的不是 JSON（HTTP ${res.status}），可能掉登录了`);
      err.code = 'RELOGIN';
      throw err;
    }
    const first = Array.isArray(json) ? json[0] : json;
    if (!first) throw new Error('LMS 返回空结果');
    if (first.error) {
      const e = first.exception || {};
      const err = new Error(`LMS 接口 ${methodname} 报错：${e.message || first.errorcode || '未知'}`);
      err.code = first.errorcode === 'servicerequireslogin' ? 'RELOGIN' : null;
      throw err;
    }
    return first.data;
  }
}

async function connect({ renew = false, code, interactive = true } = {}) {
  let cached = renew ? null : sso.loadSession(NAME);
  if (!cached) {
    const fresh = await sso.login(SERVICE, { label: 'LMS', code, interactive });
    cached = { jar: fresh.jar, finalUrl: fresh.finalUrl };
    sso.saveSession(NAME, cached);
  }
  const lms = new Lms(cached);
  try {
    return await lms.refreshConfig();
  } catch (err) {
    if (err.code !== 'RELOGIN' || renew) throw err;
    // 静默重登一次
    const fresh = await sso.login(SERVICE, { label: 'LMS', code, interactive });
    sso.saveSession(NAME, fresh);
    return new Lms({ jar: fresh.jar }).refreshConfig();
  }
}

// ── 业务方法 ──────────────────────────────────────────────────────
const strip = (html) =>
  String(html || '')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ')
    .trim();

async function courses(lms) {
  let data;
  try {
    data = await lms.ajax('core_course_get_enrolled_courses_by_timeline_classification', {
      offset: 0,
      limit: 100,
      classification: 'all',
      sort: 'fullname',
    });
  } catch (err) {
    if (err.message.includes('not found') || err.code === 'RELOGIN') throw err;
    // 退路：同一个方法的 inprogress 分类（部分 Moodle 只开这个）
    data = await lms.ajax('core_course_get_enrolled_courses_by_timeline_classification', {
      offset: 0,
      limit: 100,
      classification: 'inprogress',
      sort: 'fullname',
    });
  }
  return (data.courses || []).map((c) => ({
    id: c.id,
    name: c.fullname || c.fullnamedisplay || c.shortname,
    short: c.shortname,
    progress: typeof c.progress === 'number' ? c.progress : null,
    start: c.startdate ? new Date(c.startdate * 1000).toISOString().slice(0, 10) : null,
    end: c.enddate ? new Date(c.enddate * 1000).toISOString().slice(0, 10) : null,
    hidden: Boolean(c.hidden),
  }));
}

async function events(lms, { days = 14 } = {}) {
  const now = Math.floor(Date.now() / 1000);
  const to = now + days * 86400;
  const data = await lms.ajax('core_calendar_get_action_events_by_timesort', {
    limitnum: 50,
    timesortfrom: now - 86400,
    timesortto: to,
    limittononsuspendedevents: true,
  });
  return (data.events || []).map((e) => ({
    name: e.name,
    course: e.course?.fullname || e.course?.shortname || '',
    at: e.timesort ? new Date(e.timesort * 1000) : null,
    url: e.url || null,
    action: e.action?.name || e.eventtype || '',
  }));
}

// 课程内容：优先 ajax；不行就解析课程页
async function contents(lms, courseid) {
  try {
    const data = await lms.ajax('core_course_get_contents', { courseid });
    if (Array.isArray(data)) {
      return data.map((sec) => ({
        section: strip(sec.name) || (sec.section ? `第 ${sec.section} 节` : ''),
        modules: (sec.modules || []).map((m) => ({
          mod: m.modname,
          name: strip(m.name),
          url: m.url || null,
          after: m.dates?.[0]?.label || '',
        })),
      }));
    }
  } catch {
    /* 落到 HTML */
  }
  const res = await lms.get(`/course/view.php?id=${courseid}`, { referer: `${BASE}/my/courses.php` });
  if (!/mod\//.test(res.body) && /login\/index\.php/.test(res.body)) {
    const err = new Error('LMS 会话已失效');
    err.code = 'RELOGIN';
    throw err;
  }
  const seen = new Set();
  const modules = [];
  const re = /<a[^>]+href="([^"]*\/mod\/([a-z]+)\/view\.php\?id=(\d+)[^"]*)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(res.body))) {
    const [, url, mod, id, inner] = m;
    const key = `${mod}:${id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    modules.push({ mod, name: strip(inner) || id, url: url.startsWith('http') ? url : BASE + url, after: '' });
  }
  return [{ section: '(页面解析)', modules }];
}

// ── 文件 ────────────────────────────────────────────────────────
// 资源页可能直接 303 到 pluginfile.php，也可能是个文件夹页面（里面一堆链接）。
async function collectFiles(lms, courseid) {
  const sections = await contents(lms, courseid);
  const files = new Map();
  for (const sec of sections) {
    for (const m of sec.modules) {
      if (!m.url) continue;
      let res;
      try {
        res = await lms.get(m.url, { referer: `${BASE}/course/view.php?id=${courseid}` });
      } catch {
        continue;
      }
      const add = (u, name) => {
        if (!files.has(u)) files.set(u, { name: name || m.name, mod: m.mod, url: u });
      };
      if (res.headers.location) add(new URL(res.headers.location, m.url).toString());
      const re = /href="([^"]*pluginfile\.php[^"]*)"/gi;
      let mm;
      while ((mm = re.exec(res.body))) {
        add(new URL(mm[1].replace(/&amp;/g, '&'), m.url).toString());
      }
    }
  }
  return [...files.values()];
}

function fileNameFrom(headers, url) {
  const cd = headers['content-disposition'] || '';
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (star) return decodeURIComponent(star[1]);
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  if (plain) return decodeURIComponent(plain[1]);
  return decodeURIComponent(new URL(url).pathname.split('/').pop() || 'file');
}

const safeName = (s) => String(s).replace(/[\\/:*?"<>|]/g, '_').slice(0, 120);

// 跟着跳转把文件拿下来；返回 {name, size, path}
async function download(lms, url, dir, { referer } = {}) {
  const fs = require('node:fs');
  const path = require('node:path');
  let target = url;
  let res;
  for (let i = 0; i < 6; i += 1) {
    const host = new URL(target).host;
    res = await http.getBinary(target, { cookie: lms.jar.header(host), referer: referer || `${BASE}/my/` });
    lms.jar.absorb(res, host);
    if (!res.headers.location) break;
    target = new URL(res.headers.location, target).toString();
  }
  if (res.status !== 200) throw new Error(`下载失败 HTTP ${res.status}`);
  const name = safeName(fileNameFrom(res.headers, target));
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, name);
  fs.writeFileSync(file, res.body);
  return { name, size: res.body.length, path: file };
}

module.exports = { BASE, SERVICE, Lms, connect, courses, events, contents, collectFiles, download, strip };

// ── 作业 / 成绩 / 公告 ───────────────────────────────────────────
function parseAssignPage(html) {
  const text = strip(html);
  const i = text.indexOf('作业状态');
  const seg = i >= 0 ? text.slice(i, i + 260) : '';
  const submitted = /已提交/.test(seg);
  const graded = /已评分|已批改/.test(seg);
  const due = (seg.match(/剩余时间\s*(.*?)\s*(最后修改|评分状态|提交评论)/) || [])[1] || '';
  const last = (seg.match(/最后修改\s*(\S+\s*\S*\s*\S*\s*\S*)/) || [])[1] || '';
  const files = [...seg.matchAll(/文件提交\s+([^\s，,]+)/g)].map((m) => m[1]);
  return { submitted, graded, due, last, files };
}

async function assignments(lms, courseid) {
  const courses = courseid ? [{ id: Number(courseid), name: String(courseid) }] : await coursesOf(lms);
  const out = [];
  for (const co of courses) {
    let secs = [];
    try {
      secs = await contents(lms, co.id);
    } catch {
      continue;
    }
    for (const sec of secs) {
      for (const m of sec.modules) {
        if (m.mod !== 'assign' || !m.url) continue;
        let info = {};
        try {
          const r = await lms.get(m.url, { referer: `${BASE}/course/view.php?id=${co.id}` });
          info = parseAssignPage(r.body);
        } catch {}
        out.push({ courseid: co.id, course: co.name, name: m.name, url: m.url, ...info });
      }
    }
  }
  return out;
}

async function coursesOf(lms) {
  const list = await courses(lms);
  return list.map((c) => ({ id: c.id, name: c.name }));
}

function parseGradeRows(html) {
  const rows = [];
  for (const chunk of html.split('<tr').slice(1)) {
    const idm = chunk.match(/id='(cat|row)_(\d+)_\d+'/);
    if (!idm) continue;
    const titleAttr = (chunk.match(/gradeitemheader"\s+title="([^"]+)"/) || [])[1];
    const name = titleAttr ? strip(titleAttr) : strip((chunk.match(/column-itemname[^>]*>([\s\S]*?)<\/th>/) || [])[1] || '');
    if (!name) continue;
    const cell = (cls) => {
      const m = chunk.match(new RegExp(`column-${cls}'[^>]*>([\\s\\S]*?)<\\/t[dh]>`));
      return m ? strip(m[1]) : '';
    };
    const level = Number((chunk.match(/level(\d)/) || [])[1] || 1);
    rows.push({ kind: idm[1], name, level, weight: cell('weight'), grade: cell('grade'), range: cell('range'), contribution: cell('contributiontocoursetotal') });
  }
  return rows;
}

async function grades(lms, courseid) {
  const r = await lms.get(`/grade/report/user/index.php?id=${courseid}`, { referer: `${BASE}/course/view.php?id=${courseid}` });
  if (r.status !== 200 || !/user-grade/.test(r.body)) {
    const err = new Error('打不开成绩单（可能没有权限或课程没开成绩）');
    err.code = 'NOGRADE';
    throw err;
  }
  return parseGradeRows(r.body);
}

async function notices(lms, { courseid, num = 5 } = {}) {
  const courses = courseid ? [{ id: Number(courseid), name: String(courseid) }] : await coursesOf(lms);
  const out = [];
  for (const co of courses) {
    let secs = [];
    try {
      secs = await contents(lms, co.id);
    } catch {
      continue;
    }
    const forums = [];
    for (const sec of secs) for (const m of sec.modules) if (m.mod === 'forum' && m.url) forums.push(m);
    for (const f of forums) {
      let r;
      try {
        r = await lms.get(f.url, { referer: `${BASE}/course/view.php?id=${co.id}` });
      } catch {
        continue;
      }
      const re = /<a[^>]+href="([^"]*discuss\.php\?d=\d+)"[^>]*>([\s\S]*?)<\/a>/gi;
      const seen = new Set();
      let m;
      while ((m = re.exec(r.body))) {
        const url = m[1].replace(/&amp;/g, '&');
        const title = strip(m[2]);
        if (!title || seen.has(url) || /^(回复|Reply)$/i.test(title)) continue;
        if (out.some((x) => x.url === url)) continue;
        seen.add(url);
        out.push({ courseid: co.id, course: co.name, forum: f.name, title, url });
        if (out.length > num * 50) break;
      }
      if (!courseid) break; // 每门课只看第一个论坛，避免刷屏
    }
  }
  return out.slice(0, num);
}

module.exports.assignments = assignments;
module.exports.grades = grades;
module.exports.notices = notices;
module.exports.parseAssignPage = parseAssignPage;
