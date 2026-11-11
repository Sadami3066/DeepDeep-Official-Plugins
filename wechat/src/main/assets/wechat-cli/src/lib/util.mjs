import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';

export const TZ = 'Asia/Shanghai';
export const HOME = os.homedir();
export const DEFAULT_DATA_DIR = process.env.WECHAT_DATA_DIR || path.join(HOME, '.wechat');
export const DEFAULT_SOURCE = process.env.WECHAT_SOURCE || '/data/data/com.tencent.mm';

export function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

export function md5(value) {
  return crypto.createHash('md5').update(value, 'utf8').digest('hex');
}

export function nowId() {
  return new Date().toISOString().replace(/[:.]/g, '-') + '-' + crypto.randomBytes(3).toString('hex');
}

export function dateOf(ms = Date.now()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(ms);
  const year = parts.find((part) => part.type === 'year')?.value;
  const month = parts.find((part) => part.type === 'month')?.value;
  const day = parts.find((part) => part.type === 'day')?.value;
  return `${year}-${month}-${day}`;
}

export function dayRange(input = 'today') {
  const date =
    input === 'today' ? dateOf() :
    input === 'yesterday' ? dateOf(Date.now() - 86_400_000) :
    String(input);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new Error('date must be YYYY-MM-DD, today, or yesterday');
  }
  const start = Date.parse(date + 'T00:00:00+08:00');
  if (!Number.isFinite(start) || dateOf(start) !== date) {
    throw new Error('invalid date: ' + date);
  }
  return { date, start, end: start + 86_400_000, timezone: TZ };
}

export function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  try { fs.chmodSync(dir, 0o700); } catch {}
  return dir;
}

export function atomicWrite(file, data) {
  ensureDir(path.dirname(file));
  const buf = Buffer.isBuffer(data) ? data : Buffer.from(
    typeof data === 'string' ? data : JSON.stringify(data, null, 2) + '\n',
    'utf8',
  );
  const tmp = file + '.tmp-' + process.pid + '-' + crypto.randomBytes(3).toString('hex');
  let fd;
  try {
    fd = fs.openSync(tmp, 'wx', 0o600);
    fs.writeFileSync(fd, buf);
    fs.fsyncSync(fd);
    fs.closeSync(fd);
    fd = undefined;
    if (!fs.readFileSync(tmp).equals(buf)) throw new Error('write verify failed: ' + file);
    fs.renameSync(tmp, file);
    try { fs.chmodSync(file, 0o600); } catch {}
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
    fs.rmSync(tmp, { force: true });
  }
}

export function readJSON(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

export function parseArgs(argv = process.argv.slice(2)) {
  const result = { _: [] };
  const booleans = new Set(['json', 'help', 'dry-run', 'all', 'quiet', 'full']);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      result._.push(arg);
      continue;
    }
    const eq = arg.indexOf('=');
    const key = arg.slice(2, eq < 0 ? undefined : eq);
    if (booleans.has(key)) {
      if (eq >= 0) throw new Error('--' + key + ' does not take a value');
      result[key] = true;
      continue;
    }
    const value = eq >= 0 ? arg.slice(eq + 1) : argv[++i];
    if (!value || value.startsWith('--')) throw new Error('--' + key + ' requires a value');
    result[key] = value;
  }
  return result;
}

export function dataDir(args = {}) {
  return path.resolve(args.data || process.env.WECHAT_DATA_DIR || DEFAULT_DATA_DIR);
}

export function sourceDir(args = {}) {
  return path.resolve(args.source || process.env.WECHAT_SOURCE || DEFAULT_SOURCE);
}

export function print(value, args = {}) {
  if (args.json) process.stdout.write(JSON.stringify(value, null, 2) + '\n');
  return value;
}

export function log(...args) {
  console.error(...args);
}

export function relativeLink(target, linkPath) {
  const rel = path.relative(path.dirname(linkPath), target);
  return rel || '.';
}

export function symlinkAtomic(target, linkPath) {
  const tmp = linkPath + '.link-' + process.pid + '-' + crypto.randomBytes(3).toString('hex');
  fs.symlinkSync(target, tmp);
  try { fs.renameSync(tmp, linkPath); }
  finally { fs.rmSync(tmp, { force: true }); }
}

export function stripTags(value) {
  return String(value ?? '')
    .replace(/<!\[CDATA\[|\]\]>/g, '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

export function xmlValue(value, tag) {
  const m = String(value ?? '').match(new RegExp('<' + tag + '(?:\\s[^>]*)?>([\\s\\S]*?)</' + tag + '>', 'i'));
  return m ? stripTags(m[1]) : null;
}

export function xmlAttr(value, tag, attr) {
  const m = String(value ?? '').match(new RegExp('<' + tag + '\\b[^>]*\\b' + attr + '="([^"]*)"', 'i'));
  return m ? stripTags(m[1]) : null;
}
