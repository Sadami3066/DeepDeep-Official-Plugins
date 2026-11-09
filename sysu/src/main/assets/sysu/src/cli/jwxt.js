const { run, HELP, COMMANDS } = require('../cmds');

// sysu jwxt —— 教务站点的参数解析
function main(argvIn = []) {

  if (argvIn.includes('--version') || argvIn.includes('-V')) {
    return console.log(require('../../package.json').version);
  }

  const BOOL = ['--send-code', '--json', '--all', '--today', '--tomorrow', '--dry-run', '--yes', '--syllabus', '--intro'];
  const VALUED = ['--week', '--semester', '--from', '--to', '--reason', '--note', '--days', '--file', '--num', '--campus', '--building', '--room', '--day', '--sections', '--code'];

    const argv = argvIn.slice();

  if (argv.includes('--help') || argv.includes('-h')) {
    console.log(HELP);
    process.exit(0);
  }

  const fail = (msg) => {
    console.error(`\x1b[31m${msg}\x1b[0m\n`);
    console.log(HELP);
    process.exit(1);
  };

  let cmd = 'schedule';
  const at = argv.findIndex((a) => COMMANDS.includes(a));
  if (at >= 0) cmd = argv.splice(at, 1)[0];
  else if (argv[0] && !argv[0].startsWith('-')) fail(`未知命令：${argv[0]}`);

  const unknown = argv.find((a) => a.startsWith('-') && ![...BOOL, ...VALUED, '-h', '--help'].includes(a));
  if (unknown) fail(`未知选项：${unknown}`);

  const value = (name) => {
    const i = argv.indexOf(name);
    if (i < 0) return undefined;
    const v = argv[i + 1];
    if (v === undefined || v.startsWith('-')) fail(`${name} 后面要跟一个值`);
    argv.splice(i, 2);
    return v;
  };

  const rawWeek = value('--week');
  const rawDays = value('--days');
  const rawSemester = value('--semester');
  const rawFrom = value('--from');
  const rawTo = value('--to');
  const rawReason = value('--reason');
  const rawNote = value('--note');
  const rawNum = value('--num');
  const rawCampus = value('--campus');
  const rawBuilding = value('--building');
  const rawRoom = value('--room');
  const rawDay = value('--day');
  const rawSections = value('--sections');
  const rawFile = value('--file');
  const rawCode = value('--code');

  let apply = false;
  if (cmd === 'leave') {
    const i = argv.indexOf('apply');
    if (i >= 0) {
      apply = true;
      argv.splice(i, 1);
    }
  }

  let name;
  if (cmd === 'course' || cmd === 'notice') {
    name = argv.find((a) => !a.startsWith('-'));
    if (cmd === 'course' && name === undefined) fail('jwxt course 要跟一个课程名，比如：jwxt course 国际贸易');
    if (name !== undefined) argv.splice(argv.indexOf(name), 1);
  }

  const stray = argv.filter((a) => !a.startsWith('-'));
  if (stray.length) fail(`多余参数：${stray.join(' ')}`);

  const week = rawWeek === undefined ? undefined : Number(rawWeek);
  const days = rawDays === undefined ? undefined : Number(rawDays);
  if (week !== undefined && (!Number.isInteger(week) || week < 1)) fail(`--week 要是 ≥1 的整数，收到：${rawWeek}`);
  if (days !== undefined && !(days > 0)) fail(`--days 要是正数，收到：${rawDays}`);

  const day = rawDay === undefined ? undefined : Number(rawDay);
  if (day !== undefined && !(day >= 1 && day <= 7)) fail(`--day 要 1~7（周一~周日），收到：${rawDay}`);
  if (rawNum !== undefined && !(Number(rawNum) > 0)) fail(`--num 要是正数，收到：${rawNum}`);

  run(cmd, {
    json: argv.includes('--json'),
    apply,
    name,
    id: cmd === 'notice' ? name : undefined,
    num: rawNum === undefined ? 10 : Number(rawNum),
    campus: rawCampus,
    building: rawBuilding,
    room: rawRoom,
    day,
    sections: rawSections,
    all: argv.includes('--all'),
    today: argv.includes('--today'),
    tomorrow: argv.includes('--tomorrow'),
    dryRun: argv.includes('--dry-run'),
    yes: argv.includes('--yes'),
    syllabus: argv.includes('--syllabus'),
    intro: argv.includes('--intro'),
    week,
    days,
    semester: rawSemester,
    from: rawFrom,
    to: rawTo,
    reason: rawReason,
    note: rawNote,
    file: rawFile,
    code: rawCode,
    sendCode: argv.includes('--send-code'),
  }).catch((err) => {
    console.error(`\x1b[31m${err.message}\x1b[0m`);
    process.exit(1);
  });

}

module.exports = { main, HELP, COMMANDS };
