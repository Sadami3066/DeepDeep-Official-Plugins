import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import { dateOf } from './util.mjs';

export const TYPE_NAMES = {
  1: '文本', 3: '图片', 34: '语音', 37: '好友请求', 42: '名片', 43: '视频',
  47: '表情', 48: '位置', 49: '链接/文件/卡片', 50: '音视频通话',
  62: '短视频', 10000: '系统提示', 10002: '撤回',
};

const SYSTEM_TYPES = new Set([10000, 10002]);

function decodeEntities(value) {
  return String(value ?? '')
    .replace(/<!\[CDATA\[|\]\]>/g, '')
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, num) => String.fromCodePoint(Number(num)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

function strip(value) {
  const withoutCdata = String(value ?? '').replace(/<!\[CDATA\[|\]\]>/g, '');
  return decodeEntities(withoutCdata.replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();
}

function xmlTag(value, tag) {
  const match = String(value ?? '').match(new RegExp('<' + tag + '(?:\\s[^>]*)?>([\\s\\S]*?)</' + tag + '>', 'i'));
  return match ? strip(match[1]) : null;
}

function xmlAttr(value, tag, attr) {
  const match = String(value ?? '').match(new RegExp('<' + tag + '\\b[^>]*\\b' + attr + '="([^"]*)"', 'i'));
  return match ? strip(match[1]) : null;
}

function splitSender(content, group) {
  const text = String(content ?? '');
  const at = text.indexOf(':\n');
  if (group && at > 0 && at < 80) return { sender: text.slice(0, at), body: text.slice(at + 2) };
  return { sender: null, body: text };
}

export function baseType(type) {
  return Number(type) & 0xffff;
}

export function typeName(type) {
  return TYPE_NAMES[baseType(type)] || ('类型 ' + type);
}

export function conversationKind(talker, conversationName = '') {
  const id = String(talker || '');
  const name = String(conversationName || '');
  if (id === 'filehelper') return 'filehelper';
  if (id === 'weixin' || name.includes('微信支付')) return 'payment';
  if (id === 'notifymessage' || id === 'newsapp') return 'service';
  if (id.startsWith('gh_')) return 'official';
  if (id.endsWith('@chatroom')) return 'group';
  if (id.startsWith('wxid_')) return 'personal';
  return 'other';
}

export function parseMessage(type, content, isGroup) {
  const kind = baseType(type);
  const name = typeName(type);
  const { sender, body } = splitSender(content, isGroup);
  if (kind === 1) return { text: body, quote: null, url: null, fileName: null, sender, typeName: name };

  const quote = body.includes('<refermsg>')
    ? (xmlTag(body, 'content') || xmlTag(body, 'title') || xmlTag(body, 'refermsg'))
    : null;
  const title = xmlTag(body, 'title');
  const description = xmlTag(body, 'des');
  const fileName = xmlTag(body, 'filename');
  const url = xmlAttr(body, 'url', 'url') || xmlTag(body, 'url');
  let text = [title, description].filter(Boolean).join('\n');
  if (!text && fileName) text = '[文件] ' + fileName;
  if (!text && url) text = '[链接] ' + url;
  if (!text && kind === 3) text = '[图片]';
  if (!text && kind === 34) text = '[语音]';
  if (!text && kind === 43) text = '[视频]';
  if (!text && kind === 47) text = '[表情]';
  if (!text && kind === 48) text = '[位置]';
  if (!text && kind === 50) text = '[音视频通话]';
  if (!text && kind === 62) text = '[短视频]';
  if (!text && kind === 10002) text = '[撤回]';
  if (!text && SYSTEM_TYPES.has(kind)) text = strip(body);
  if (!text && !String(body).includes('<')) text = strip(body);
  return {
    text: text || '[无文本：' + name + ']',
    quote: quote || null,
    url: url || null,
    fileName: fileName || null,
    sender,
    typeName: name,
  };
}

export function openNormalized(file) {
  const db = new DatabaseSync(file);
  db.exec(`
    PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS messages (
      msg_id TEXT PRIMARY KEY,
      server_id TEXT,
      talker TEXT NOT NULL,
      conversation_name TEXT NOT NULL,
      kind TEXT NOT NULL,
      is_group INTEGER NOT NULL DEFAULT 0,
      is_send INTEGER NOT NULL DEFAULT 0,
      sender_id TEXT,
      sender_name TEXT,
      type INTEGER NOT NULL,
      type_name TEXT NOT NULL,
      create_time INTEGER NOT NULL,
      day TEXT NOT NULL,
      text TEXT NOT NULL,
      quote TEXT,
      url TEXT,
      file_name TEXT,
      media_path TEXT,
      status INTEGER,
      raw TEXT
    );
    CREATE INDEX IF NOT EXISTS messages_range ON messages(create_time, msg_id);
    CREATE INDEX IF NOT EXISTS messages_talker ON messages(talker, create_time);
    CREATE INDEX IF NOT EXISTS messages_day ON messages(day, create_time);
    CREATE TABLE IF NOT EXISTS meta (
      key TEXT PRIMARY KEY,
      value TEXT
    );
  `);
  return db;
}

function loadContacts(plain) {
  const map = new Map();
  try {
    const rows = plain.prepare('SELECT username, conRemark, nickname, alias FROM rcontact').all();
    for (const row of rows) {
      const name = row.conRemark || row.nickname || row.alias || row.username;
      if (row.username) map.set(row.username, name);
    }
  } catch {}
  return map;
}

export function ingest(plainFile, normalizedFile, { full = false } = {}) {
  const plain = new DatabaseSync(plainFile, { readOnly: true });
  const db = openNormalized(normalizedFile);
  let inserted = 0;
  let scanned = 0;
  try {
    const contacts = loadContacts(plain);
    const last = Number(db.prepare('SELECT MAX(create_time) AS last FROM messages').get()?.last || 0);
    const useIncremental = !full && last > 0;
    const rows = useIncremental
      ? plain.prepare(
          'SELECT CAST(msgId AS TEXT) AS msgId, CAST(msgSvrId AS TEXT) AS msgSvrId, type, status, isSend, createTime, talker, content, imgPath ' +
          'FROM message WHERE createTime >= ? ORDER BY createTime, msgId',
        ).all(last - 2 * 86_400_000)
      : plain.prepare(
          'SELECT CAST(msgId AS TEXT) AS msgId, CAST(msgSvrId AS TEXT) AS msgSvrId, type, status, isSend, createTime, talker, content, imgPath ' +
          'FROM message ORDER BY createTime, msgId',
        ).all();

    db.exec('BEGIN');
    try {
      const insert = db.prepare(`
        INSERT INTO messages
          (msg_id, server_id, talker, conversation_name, kind, is_group, is_send,
           sender_id, sender_name, type, type_name, create_time, day, text,
           quote, url, file_name, media_path, status, raw)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(msg_id) DO UPDATE SET
          conversation_name=excluded.conversation_name,
          kind=excluded.kind,
          sender_name=excluded.sender_name,
          type_name=excluded.type_name,
          text=excluded.text,
          quote=excluded.quote,
          url=excluded.url,
          file_name=excluded.file_name,
          media_path=excluded.media_path,
          status=excluded.status,
          raw=excluded.raw
      `);
      for (const row of rows) {
        scanned++;
        const talker = String(row.talker ?? '');
        const isGroup = talker.endsWith('@chatroom');
        const parsed = parseMessage(row.type, row.content, isGroup);
        const isSend = Number(row.isSend) ? 1 : 0;
        const senderId = isSend ? 'me' : (isGroup ? (parsed.sender || talker) : talker);
        const senderName = isSend ? '我' : (contacts.get(senderId) || senderId);
        const conversationName = contacts.get(talker) || talker;
        const createTime = Number(row.createTime || 0);
        insert.run(
          String(row.msgId ?? ''),
          String(row.msgSvrId ?? ''),
          talker,
          conversationName,
          conversationKind(talker, conversationName),
          isGroup ? 1 : 0,
          isSend,
          senderId,
          senderName,
          Number(row.type || 0),
          parsed.typeName,
          createTime,
          dateOf(createTime),
          parsed.text,
          parsed.quote,
          parsed.url,
          parsed.fileName,
          String(row.imgPath ?? '') || null,
          Number(row.status ?? 0),
          String(row.content ?? ''),
        );
        inserted++;
      }
      db.prepare('INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)').run('last_ingest', new Date().toISOString());
      db.exec('COMMIT');
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
    const total = Number(db.prepare('SELECT count(*) AS n FROM messages').get()?.n || 0);
    return { status: 'ok', inserted, scanned, total, normalized: normalizedFile, full: !useIncremental };
  } finally {
    plain.close();
    db.close();
    try { fs.chmodSync(normalizedFile, 0o600); } catch {}
  }
}
