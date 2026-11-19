const { block, dateTime, kv, table } = require('./output');

const text = (value) => {
  if (value === null || value === undefined) return '';
  const out = String(value).trim();
  return out && out !== 'undefined' && out !== 'null' ? out : '';
};

const number = (value) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
};

const fixed = (value, digits = 1) => {
  const n = number(value);
  return n === null ? text(value) : n.toFixed(digits);
};

const dash = (value) => text(value) || '—';

function schedule(data) {
  const header = [`第 ${text(data.week)} 周`, [text(data.startTime), text(data.endTime)].filter(Boolean).join(' ~ ')]
    .filter(Boolean)
    .join(' · ');
  const days = Array.isArray(data.days) ? data.days : [];
  if (!days.length) return block(header, ['这段时间没有课']);
  const chunks = days.map((day) => {
    const items = Array.isArray(day.items) ? day.items : [];
    const title = [text(day.date), text(day.weekday)].filter(Boolean).join(' ');
    if (!items.length) return `${title}  没有课`;
    const rows = items.map((item) => {
      const exam = item.type === 'exam';
      const when = exam ? text(item.time) : text(item.sections) ? `第 ${text(item.sections)} 节` : '';
      const name = exam ? `[考试] ${dash(item.name)}` : dash(item.name);
      return ['  ' + [when, name].filter(Boolean).join('  '), text(item.place), text(item.teacher)]
        .filter(Boolean)
        .join(' · ');
    });
    return [title, ...rows].join('\n');
  });
  return block(header, [chunks.join('\n\n')]);
}

function scores(data) {
  const semesters = Array.isArray(data.semesters) ? data.semesters : [];
  if (!semesters.length) return '没有查到成绩。';
  return semesters
    .map((semester) => {
      const courses = Array.isArray(semester.courses) ? semester.courses : [];
      const header = [
        dash(semester.semester),
        semester.gpa != null ? `绩点 ${fixed(semester.gpa, 2)}` : null,
        semester.credit != null ? `学分 ${fixed(semester.credit, 1)}` : null,
        courses.length ? `${courses.length} 门` : null,
      ]
        .filter(Boolean)
        .join(' · ');
      if (!courses.length) return header;
      const rows = [['课程', '学分', '成绩', '绩点']];
      for (const course of courses) {
        rows.push([
          dash(course.scoCourseName),
          text(course.scoCredit) || '—',
          text(course.scoFinalScore) || '—',
          text(course.scoPoint) || '—',
        ]);
      }
      return `${header}\n${table(rows)}`;
    })
    .join('\n\n');
}

function credits(data) {
  const plans = Array.isArray(data.plans) ? data.plans : [];
  if (!plans.length) return '没有查到学分信息。';
  return plans
    .map((plan) => {
      const header = [text(plan.grade), text(plan.trainCateCode), text(plan.teachPlanNumber)].filter(Boolean).join(' · ');
      const rows = [
        ['总学分', plan.finishCredit, plan.minGraduationCredit],
        ['公共必修', plan.publicFinishCredit, plan.publicMinCredit],
        ['专业必修', plan.majorFinishCredit, plan.majorMinCredit],
        ['专业选修', plan.majorSelectFinishCredit, plan.majorSelectMinCredit],
        ['公共选修', plan.publicSelectFinishCredit, plan.publicSelectMinCredit],
        ['荣誉课程', plan.honorCourseFinishCredit, plan.honorCourseMinCredit],
      ]
        .filter(([, done, need]) => text(done) || text(need))
        .map(([label, done, need]) => `${label}  ${text(done) || '0'} / ${text(need) || '—'}`);
      if (text(plan.finishCreditRate)) rows.push(`完成比例  ${fixed(plan.finishCreditRate, 1)}%`);
      return block(header, rows);
    })
    .join('\n\n');
}

function exams(data) {
  const exams = Array.isArray(data.exams) ? data.exams : [];
  if (!exams.length) return '没有查到考试安排。';
  const rows = exams.map((exam) => {
    const when = [text(exam.date), text(exam.weekday), text(exam.time)].filter(Boolean).join(' ');
    const where = [text(exam.place), text(exam.teacher)].filter(Boolean).join(' · ');
    return [when, dash(exam.name), where, text(exam.week) ? `第 ${text(exam.week)} 周` : ''].filter(Boolean).join('  ');
  });
  return rows.join('\n');
}

function lmsCourses(data) {
  const courses = Array.isArray(data.courses) ? data.courses : [];
  if (!courses.length) return '没有在修课程。';
  return courses
    .map((course) => {
      const progress = text(course.progress);
      return [text(course.id), dash(course.name), progress ? `进度 ${progress}%` : '', course.hidden ? '[隐藏]' : '']
        .filter(Boolean)
        .join(' · ');
    })
    .join('\n');
}

function lmsTodo(data) {
  const items = Array.isArray(data.items) ? data.items : [];
  if (!items.length) return `最近 ${text(data.days) || 14} 天没有待办。`;
  return items
    .map((item) =>
      [dateTime(item.at), text(item.course), dash(item.name), text(item.action)].filter(Boolean).join('  '),
    )
    .join('\n');
}

function lmsCourse(data) {
  const sections = Array.isArray(data.sections) ? data.sections : [];
  if (!sections.length) return `课程 ${text(data.course)} 没有内容。`;
  const chunks = sections.map((section) => {
    const modules = Array.isArray(section.modules) ? section.modules : [];
    const rows = modules.map((module, index) =>
      [`${index + 1}.`, dash(module.name), text(module.mod) ? `[${text(module.mod)}]` : '', text(module.after)]
        .filter(Boolean)
        .join(' '),
    );
    return block(`${dash(section.section)}`, rows);
  });
  return block(`课程 ${text(data.course)}`, [chunks.join('\n\n')]);
}

function lmsFiles(data) {
  const files = Array.isArray(data.files) ? data.files : [];
  if (!files.length) return `课程 ${text(data.course)} 没有可下载文件。`;
  return block(
    `课程 ${text(data.course)} · ${files.length} 个文件`,
    files.map((file, index) => `${index + 1}. ${dash(file.name)}${text(file.file) ? ` (${text(file.file)})` : ''}`),
  );
}

function lmsDownload(data) {
  const lines = [];
  if (data.dryRun) {
    lines.push(`预览：课程 ${text(data.course)} 共 ${text(data.total)} 个文件`);
    for (const file of data.files || []) lines.push(`  ${dash(file.name)}`);
    return lines.join('\n');
  }
  lines.push(`课程 ${text(data.course)} · 已下载 ${(data.downloaded || []).length} / ${text(data.total)}`);
  if (text(data.dir)) lines.push(`目录  ${text(data.dir)}`);
  for (const file of data.downloaded || []) lines.push(`  ${dash(file.name)}`);
  for (const file of data.failed || []) lines.push(`  失败  ${dash(file.name)}：${text(file.error)}`);
  return lines.join('\n');
}

function gymInfo(data) {
  const lines = [];
  const me = data.me || {};
  for (const [label, keys] of [
    ['账号', ['Name', 'UserName', 'UserNo', 'IdentityName']],
    ['校区', ['CampusName']],
  ]) {
    const value = keys.map((key) => text(me[key])).filter(Boolean)[0];
    if (value) lines.push(`${label}  ${value}`);
  }
  const campuses = (data.campuses || []).map((c) => text(c)).filter(Boolean);
  if (campuses.length) lines.push(`校区列表  ${campuses.join(' / ')}`);
  const types = Array.isArray(data.types) ? data.types : [];
  if (types.length) {
    lines.push('场馆类型');
    for (const type of types) lines.push(`  ${dash(type.Name)}${text(type.Identity) ? `  ${text(type.Identity)}` : ''}`);
  }
  return lines.length ? lines.join('\n') : null;
}

function gymSlots(data) {
  const types = Array.isArray(data.types) ? data.types : [];
  const errors = Array.isArray(data.errors) ? data.errors : [];
  if (!types.length && !errors.length) return '没有查到场馆。';
  const chunks = [];
  for (const type of types) {
    if (type.error) {
      chunks.push(`${dash(type.name)}  查询失败：${text(type.error)}`);
      continue;
    }
    const head = [dash(type.name), type.window ? `${text(type.window.start)} ~ ${text(type.window.end)}` : null]
      .filter(Boolean)
      .join(' · ');
    const fee = type.fee && (text(type.fee.CreditFee) || text(type.fee.CashFee))
      ? `费用  ${text(type.fee.CreditFee) ? `积分 ${fixed(type.fee.CreditFee, 1)}/小时` : `￥${fixed(type.fee.CashFee, 1)}/小时`}`
      : null;
    const venues = Array.isArray(type.venues) ? type.venues : [];
    if (!venues.length) {
      chunks.push(block(head, [fee, text(type.notice) ? `提示  ${text(type.notice)}` : null, '没有空位']));
      continue;
    }
    const rows = [['日期', '时间', '场地', '剩余']];
    for (const venue of venues) {
      for (const slot of venue.slots || []) {
        rows.push([
          text(slot.date),
          `${text(slot.start)}-${text(slot.end)}`,
          text(venue.shortName) || dash(venue.name),
          text(slot.available),
        ]);
      }
    }
    chunks.push(block(head, [fee, table(rows)]));
  }
  for (const error of errors) chunks.push(`${dash(error.name)}  查询失败：${text(error.error)}`);
  return chunks.join('\n\n');
}

function netpayInfo(data) {
  const services = Array.isArray(data.services) ? data.services : [];
  const lines = [];
  const info = data.info || {};
  for (const [key, value] of Object.entries(info)) {
    if (value === null || value === undefined) continue;
    if (typeof value === 'object') continue;
    lines.push(`${key}  ${value}`);
  }
  if (!services.length) {
    lines.push('名下没有网络服务。');
  } else {
    lines.push('服务');
    for (const service of services) {
      const head = [dash(service.service), text(service.status)].filter(Boolean).join(' · ');
      const detail = [
        text(service.expireText) ? `到期 ${text(service.expireText)}` : text(service.expire) ? `到期 ${text(service.expire)}` : '',
        text(service.address) ? `地址 ${text(service.address)}` : '',
        text(service.mac) ? `MAC ${text(service.mac)}` : '',
        text(service.daysLeft) ? `剩 ${text(service.daysLeft)} 天` : '',
      ].filter(Boolean);
      lines.push(`  ${head}${detail.length ? `  ${detail.join(' · ')}` : ''}`);
    }
  }
  return lines.join('\n');
}

function netpayOrders(data) {
  const orders = Array.isArray(data.orders) ? data.orders : [];
  if (!orders.length) return '没有订单记录。';
  const rows = [['订单号', '金额', '方式', '时间', '状态']];
  for (const order of orders) {
    rows.push([text(order.id), text(order.amount), text(order.method), text(order.time), text(order.status)]);
  }
  return table(rows);
}

function loginStatus(data) {
  const lines = [];
  if (data.ok) lines.push('登录成功。');
  if (data.needMfa) lines.push('企业微信验证码已发出：打开「中山大学 CLI」插件页输入，或执行 sysu login --code <验证码>。');
  if (data.already) lines.push('当前已是登录状态。');
  for (const key of ['jwxt', 'lms']) {
    if (data[key] === true) lines.push(`${key === 'jwxt' ? '教务' : '在线教学'}  已连接`);
    else if (data[key] === false) lines.push(`${key === 'jwxt' ? '教务' : '在线教学'}  未连接`);
  }
  if (text(data.lmsNote)) lines.push(`在线教学  ${text(data.lmsNote)}`);
  if (text(data.hint) && !data.ok && !data.needMfa) lines.push(text(data.hint));
  return lines.length ? lines.join('\n') : null;
}

module.exports = {
  schedule,
  scores,
  credits,
  exams,
  lmsCourses,
  lmsTodo,
  lmsCourse,
  lmsFiles,
  lmsDownload,
  gymInfo,
  gymSlots,
  netpayInfo,
  netpayOrders,
  loginStatus,
};
