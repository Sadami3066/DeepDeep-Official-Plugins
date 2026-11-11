#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import {
  DEFAULT_SOURCE,
  atomicWrite,
  dataDir,
  dayRange,
  ensureDir,
  parseArgs,
  sourceDir,
} from '../src/lib/util.mjs';
import { currentSnapshot, prune, sync, wechatAccount } from '../src/lib/source.mjs';
import { ingest } from '../src/lib/ingest.mjs';
import { exportRange, writeExport } from '../src/lib/export.mjs';

const VERSION = '0.2.0';

const HELP = `wechat ${VERSION}

用法：
  wechat sync [--data DIR] [--source DIR] [--keep N] [--json]
  wechat ingest [--data DIR] [--full] [--json]

  wechat export --date today|yesterday|YYYY-MM-DD [--format md|json] [--out FILE]
  wechat export --from YYYY-MM-DD --to YYYY-MM-DD [--format md|json] [--out FILE]

  wechat doctor [--json]
  wechat purge [--data DIR] [--keep N] [--json]

默认数据目录：~/.wechat
默认微信目录：${DEFAULT_SOURCE}

说明：
  sync    采集微信加密库 + WAL，解密成 current/plain.db
  ingest  全量规范化写入 wechat.db；默认增量，--full 全量重扫
  export  按日期或日期范围导出全部消息，清洗但不过滤
  doctor  检查环境、账号、快照和规范化库
  purge   清理旧快照
`;

function normalizedPath(data) {
  return path.join(data, 'wechat.db');
}

function requireRoot() {
  if (typeof process.getuid === 'function' && process.getuid() !== 0) {
    throw new Error('wechat sync must run as root');
  }
}

function resolveRange(args) {
  if (args.from || args.to) {
    const from = dayRange(args.from || args.to).date;
    const to = dayRange(args.to || args.from).date;
    if (from > to) throw new Error('--from must be <= --to');
    return { from, to };
  }
  const date = dayRange(args.date || 'today').date;
  return { from: date, to: date };
}

function cmdSync(args) {
  const data = dataDir(args);
  const source = sourceDir(args);
  requireRoot();
  const result = sync({
    data,
    source,
    keep: args.keep ? Number(args.keep) : 3,
    keepRaw: !!args['keep-raw'],
  });
  const output = {
    status: result.status,
    snapshot: result.dir,
    account: result.account,
    removed: result.removed,
    collectedAt: result.manifest.collectedAt,
  };
  if (args.json) process.stdout.write(JSON.stringify(output, null, 2) + '\n');
  else process.stdout.write(
    `微信同步完成\n快照：${result.dir}\n账号：${result.account}\n采集：${result.manifest.collectedAt}\n`,
  );
  return output;
}

function cmdIngest(args) {
  const data = dataDir(args);
  const snapshot = currentSnapshot(data);
  const result = ingest(snapshot.plain, normalizedPath(data), { full: !!args.full });
  const output = {
    status: 'ok',
    normalized: result.normalized,
    inserted: result.inserted,
    scanned: result.scanned,
    total: result.total,
    full: result.full,
    collectedAt: snapshot.manifest.collectedAt,
  };
  if (args.json) process.stdout.write(JSON.stringify(output, null, 2) + '\n');
  else process.stdout.write(
    `规范化完成\n库：${result.normalized}\n本次扫描：${result.scanned}\n写入：${result.inserted}\n总计：${result.total}\n`,
  );
  return output;
}

function cmdExport(args) {
  const data = dataDir(args);
  const dbFile = normalizedPath(data);
  if (!fs.existsSync(dbFile)) throw new Error('no normalized database; run `wechat ingest` first');
  const range = resolveRange(args);
  const format = String(args.format || 'md').toLowerCase();
  if (!['md', 'json'].includes(format)) throw new Error('--format must be md or json');
  const result = exportRange(dbFile, range.from, range.to, format);
  const extension = format === 'json' ? 'json' : 'md';
  const name = range.from === range.to ? range.from : `${range.from}_${range.to}`;
  const out = args.out || path.join(data, 'exports', `${name}.${extension}`);
  ensureDir(path.dirname(out === '-' ? path.join(data, 'exports') : out));
  const written = writeExport(result.data, result.markdown, out, format);
  const output = {
    status: 'ok',
    from: range.from,
    to: range.to,
    format,
    out: written,
    messages: result.data.coverage.messages,
    conversations: result.data.coverage.conversations,
    byKind: result.data.coverage.byKind,
  };
  if (args.json) process.stdout.write(JSON.stringify(output, null, 2) + '\n');
  else process.stdout.write(
    `导出完成\n范围：${range.from} → ${range.to}\n消息：${output.messages} 条 / ${output.conversations} 个会话\n文件：${written || 'stdout'}\n`,
  );
  return output;
}

function cmdDoctor(args) {
  const data = dataDir(args);
  const source = sourceDir(args);
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: !!ok, detail: detail ? String(detail) : '' });
  add('node', Number(process.versions.node.split('.')[0]) >= 24, process.version);
  add('data dir', fs.existsSync(data), data);
  add('source', fs.existsSync(source), source);
  try {
    const account = wechatAccount(source);
    add('account', true, account.uin + ' / ' + account.hash);
  } catch (error) {
    add('account', false, error.message);
  }
  const sqlcipher = spawnSync('sqlcipher', ['--version'], { encoding: 'utf8' });
  add('sqlcipher', sqlcipher.status === 0, (sqlcipher.stdout || sqlcipher.stderr || '').split('\n')[0]);
  try {
    const snapshot = currentSnapshot(data);
    add('snapshot', true, snapshot.dir);
    add('plain.db', fs.existsSync(snapshot.plain), snapshot.plain);
  } catch (error) {
    add('snapshot', false, error.message);
  }
  add('normalized', fs.existsSync(normalizedPath(data)), normalizedPath(data));
  const output = { status: checks.every((check) => check.ok) ? 'ok' : 'degraded', version: VERSION, checks };
  if (args.json) process.stdout.write(JSON.stringify(output, null, 2) + '\n');
  else for (const check of checks) process.stdout.write(`${check.ok ? 'OK ' : '!! '}${check.name}: ${check.detail}\n`);
  return output;
}

function cmdPurge(args) {
  const removed = prune(dataDir(args), args.keep ? Number(args.keep) : 3);
  const output = { status: 'ok', removed };
  if (args.json) process.stdout.write(JSON.stringify(output, null, 2) + '\n');
  else process.stdout.write(`清理 ${removed.length} 个旧快照\n`);
  return output;
}

function main() {
  const args = parseArgs();
  const command = args._[0] || 'help';
  try {
    switch (command) {
      case 'sync': cmdSync(args); break;
      case 'ingest': cmdIngest(args); break;
      case 'export': cmdExport(args); break;
      case 'doctor': cmdDoctor(args); break;
      case 'purge': cmdPurge(args); break;
      case 'version': process.stdout.write(VERSION + '\n'); break;
      case 'help':
      case '--help':
        process.stdout.write(HELP);
        break;
      default:
        throw new Error('unknown command: ' + command + '\n' + HELP);
    }
  } catch (error) {
    if (args.json) process.stdout.write(JSON.stringify({ status: 'failed', error: error.message }, null, 2) + '\n');
    else process.stderr.write('wechat: ' + error.message + '\n');
    process.exitCode = 1;
  }
}

main();
