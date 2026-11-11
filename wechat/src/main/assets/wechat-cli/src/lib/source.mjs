import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';
import {
  ensureDir,
  md5,
  nowId,
  sha256,
  symlinkAtomic,
  atomicWrite,
  readJSON,
  relativeLink,
  sourceDir,
} from './util.mjs';

const DB = 'EnMicroMsg.db';
const WAL = DB + '-wal';
const SHM = DB + '-shm';
const PREFS = 'auth_info_key_prefs.xml';
const DEVICE_PREFS = 'WLOGIN_DEVICE_INFO.xml';

export function wechatAccount(source = sourceDir()) {
  const prefsDir = path.join(source, 'shared_prefs');
  const prefsFile = path.join(prefsDir, PREFS);
  if (!fs.existsSync(prefsFile)) throw new Error('missing WeChat prefs: ' + prefsFile);
  const prefs = fs.readFileSync(prefsFile, 'utf8');
  const match = prefs.match(/name=["']_auth_uin["'][^>]*value=["'](-?\d+)["']/);
  if (!match) throw new Error('cannot find _auth_uin in ' + prefsFile);
  const uin = match[1];
  const unsigned = String(Number(uin) >>> 0);
  for (const candidate of [...new Set([uin, unsigned])]) {
    const hash = md5('mm' + candidate);
    const dir = path.join(source, 'MicroMsg', hash);
    if (fs.existsSync(path.join(dir, DB))) {
      return { uin: candidate, hash, dir, prefsDir, prefsFile };
    }
  }
  throw new Error('cannot find current WeChat account directory under ' + path.join(source, 'MicroMsg'));
}

function deviceCandidates(source, account) {
  const values = [];
  try {
    const xml = fs.readFileSync(path.join(account.prefsDir, DEVICE_PREFS), 'utf8');
    const match = xml.match(/name=["']imei["'][^>]*>([0-9a-fA-F]+)</);
    if (match) values.push(Buffer.from(match[1], 'hex').toString('latin1'));
  } catch {}
  try {
    const result = spawnSync('/system/bin/getprop', ['ro.ril.oem.imei'], { encoding: 'utf8' });
    if (result.status === 0) {
      for (const line of result.stdout.trim().split('\n')) if (line) values.push(line);
    }
  } catch {}
  values.push('1234567890ABCDEF');
  return [...new Set(values)];
}

function sqlcipher(dbFile, sql) {
  const result = spawnSync('sqlcipher', [dbFile], {
    input: sql,
    encoding: 'utf8',
    timeout: 120_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || 'sqlcipher failed').trim());
  }
  return result.stdout;
}

function copyIfExists(from, to) {
  if (!fs.existsSync(from)) return false;
  fs.copyFileSync(from, to);
  return true;
}

function stableCopy(account, rawDir) {
  ensureDir(rawDir);
  const sourceFiles = [DB, WAL, SHM].map((name) => path.join(account.dir, name));
  const destination = sourceFiles.map((file) => path.join(rawDir, path.basename(file)));
  let last = '';
  for (let attempt = 0; attempt < 4; attempt++) {
    const before = sourceFiles.map((file) => fs.existsSync(file) ? sha256(fs.readFileSync(file)) : null);
    sourceFiles.forEach((file, index) => copyIfExists(file, destination[index]));
    const after = sourceFiles.map((file) => fs.existsSync(file) ? sha256(fs.readFileSync(file)) : null);
    const stable = before.every((hash, index) => hash === after[index]);
    if (stable) {
      last = before.join(',');
      break;
    }
    if (attempt === 3) throw new Error('WeChat database changed while copying; try again');
  }
  copyIfExists(account.prefsFile, path.join(rawDir, PREFS));
  copyIfExists(path.join(account.prefsDir, DEVICE_PREFS), path.join(rawDir, DEVICE_PREFS));
  return { sourceHash: sha256(last), files: fs.readdirSync(rawDir) };
}

function decrypt(rawDir, output) {
  const work = path.join(rawDir, '.decode');
  ensureDir(work);
  const sourceDb = path.join(work, 'source.db');
  fs.copyFileSync(path.join(rawDir, DB), sourceDb);
  copyIfExists(path.join(rawDir, WAL), sourceDb + '-wal');
  copyIfExists(path.join(rawDir, SHM), sourceDb + '-shm');

  let hit = null;
  outer:
  for (const device of deviceCandidates(path.dirname(rawDir), { prefsDir: rawDir })) {
    for (const uin of [String(accountUin(rawDir))]) {
      for (const compatibility of [1, 3, 4]) {
        const key = crypto.createHash('md5').update(device + uin, 'utf8').digest('hex').slice(0, 7);
        try {
          const out = sqlcipher(
            sourceDb,
            `PRAGMA key='${key}'; PRAGMA cipher_compatibility=${compatibility}; SELECT count(*) FROM sqlite_master;`,
          );
          if (/\d+\s*$/.test(out)) {
            hit = { key, compatibility };
            break outer;
          }
        } catch {}
      }
    }
  }
  if (!hit) throw new Error('WeChat key not found; database is encrypted with an unsupported key');

  const destination = output.replace(/'/g, "''");
  sqlcipher(
    sourceDb,
    `PRAGMA key='${hit.key}'; PRAGMA cipher_compatibility=${hit.compatibility}; ` +
      `ATTACH DATABASE '${destination}' AS plaintext KEY ''; ` +
      `SELECT sqlcipher_export('plaintext'); DETACH DATABASE plaintext;`,
  );

  const check = new DatabaseSync(output, { readOnly: true });
  try {
    const row = check.prepare('SELECT count(*) AS n FROM sqlite_master').get();
    if (!row || Number(row.n) < 1) throw new Error('decrypted database is empty');
  } finally {
    check.close();
  }
  fs.rmSync(work, { recursive: true, force: true });
}

function accountUin(rawDir) {
  const prefs = fs.readFileSync(path.join(rawDir, PREFS), 'utf8');
  const match = prefs.match(/name=["']_auth_uin["'][^>]*value=["'](-?\d+)["']/);
  if (!match) throw new Error('cannot find _auth_uin in snapshot');
  return match[1];
}

export function currentSnapshot(data) {
  const current = path.join(data, 'current');
  if (!fs.existsSync(current)) throw new Error('no snapshot; run `wechat sync` first');
  const dir = fs.realpathSync(current);
  const plain = path.join(dir, 'plain.db');
  if (!fs.existsSync(plain)) throw new Error('snapshot has no plain.db');
  const manifest = readJSON(path.join(dir, 'manifest.json'));
  return { dir, plain, manifest };
}

export function prune(data, keep = 3) {
  const versions = path.join(data, 'versions');
  if (!fs.existsSync(versions)) return [];
  const current = fs.existsSync(path.join(data, 'current'))
    ? path.basename(fs.realpathSync(path.join(data, 'current')))
    : null;
  const dirs = fs.readdirSync(versions).filter((name) => {
    try { return fs.statSync(path.join(versions, name)).isDirectory(); } catch { return false; }
  }).sort();
  const keepSet = new Set(dirs.slice(-Math.max(1, keep)));
  if (current) keepSet.add(current);
  const removed = [];
  for (const name of dirs) {
    if (keepSet.has(name)) continue;
    fs.rmSync(path.join(versions, name), { recursive: true, force: true });
    removed.push(name);
  }
  return removed;
}

export function sync(options = {}) {
  const data = path.resolve(options.data);
  const source = path.resolve(options.source);
  ensureDir(data);
  const account = wechatAccount(source);
  const id = nowId();
  const stage = path.join(data, 'versions', id);
  const raw = path.join(stage, 'raw');
  ensureDir(raw);
  try {
    const capture = stableCopy(account, raw);
    const plain = path.join(stage, 'plain.db');
    decrypt(raw, plain);
    fs.chmodSync(plain, 0o600);
    if (!options.keepRaw) fs.rmSync(raw, { recursive: true, force: true });
    const manifest = {
      schemaVersion: 1,
      status: 'ok',
      collectedAt: new Date().toISOString(),
      account: account.uin,
      accountHash: account.hash,
      source,
      sourceHash: capture.sourceHash,
      files: {
        'plain.db': { sha256: sha256(fs.readFileSync(plain)), size: fs.statSync(plain).size },
      },
      warnings: [],
    };
    atomicWrite(path.join(stage, 'manifest.json'), manifest);
    symlinkAtomic(path.join('versions', id), path.join(data, 'current'));
    symlinkAtomic(path.join('current', 'plain.db'), path.join(data, 'plain.db'));
    symlinkAtomic(path.join('current', 'manifest.json'), path.join(data, 'manifest.json'));
    const removed = prune(data, options.keep ?? 3);
    return { status: 'ok', dir: stage, account: account.uin, removed, manifest };
  } catch (error) {
    fs.rmSync(stage, { recursive: true, force: true });
    throw error;
  }
}
