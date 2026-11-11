import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { atomicWrite, dayRange } from './util.mjs';

export function exportRange(dbFile, from, to, format = 'md') {
  const first = dayRange(from).date;
  const last = dayRange(to).date;
  if (first > last) throw new Error('from must be <= to');
  const db = new DatabaseSync(dbFile, { readOnly: true });
  try {
    const rows = db.prepare(
      'SELECT * FROM messages WHERE day >= ? AND day <= ? ORDER BY create_time, msg_id',
    ).all(first, last);
    const byKind = {};
    const conversations = new Map();
    const messages = [];

    for (const row of rows) {
      byKind[row.kind] = (byKind[row.kind] || 0) + 1;
      const conversation = conversations.get(row.talker) || {
        id: row.talker,
        name: row.conversation_name,
        kind: row.kind,
        isGroup: !!row.is_group,
        count: 0,
        lastAt: row.create_time,
      };
      conversation.count++;
      conversation.lastAt = row.create_time;
      conversations.set(row.talker, conversation);

      messages.push({
        msgId: row.msg_id,
        serverId: row.server_id,
        talker: row.talker,
        conversation: row.conversation_name,
        kind: row.kind,
        isGroup: !!row.is_group,
        isSend: !!row.is_send,
        senderId: row.sender_id,
        sender: row.sender_name,
        type: row.type,
        typeName: row.type_name,
        createTime: row.create_time,
        time: new Date(row.create_time).toISOString(),
        text: row.text,
        quote: row.quote || null,
        url: row.url || null,
        fileName: row.file_name || null,
        mediaPath: row.media_path || null,
        status: row.status,
      });
    }

    const conversationList = [...conversations.values()]
      .sort((a, b) => b.lastAt - a.lastAt);
    const data = {
      schemaVersion: 1,
      from: first,
      to: last,
      timezone: 'Asia/Shanghai',
      coverage: {
        messages: rows.length,
        conversations: conversationList.length,
        byKind,
      },
      conversations: conversationList,
      messages,
    };
    return { data, markdown: renderMarkdown(data) };
  } finally {
    db.close();
  }
}

export function renderMarkdown(data) {
  const lines = [];
  lines.push(`# 微信完整记录 ${data.from} → ${data.to}`, '');
  lines.push(`> 共 ${data.coverage.messages} 条 / ${data.coverage.conversations} 个会话`, '');
  lines.push('> 类型：' + Object.entries(data.coverage.byKind)
    .map(([kind, count]) => `${kind} ${count}`).join('、'), '');

  const grouped = new Map();
  for (const message of data.messages) {
    const list = grouped.get(message.talker) || [];
    list.push(message);
    grouped.set(message.talker, list);
  }

  for (const conversation of data.conversations) {
    lines.push('', `## ${conversation.name} · ${conversation.kind}`, '');
    lines.push(`> ${conversation.count} 条 · ${conversation.isGroup ? '群聊' : '会话'}`, '');
    for (const message of grouped.get(conversation.id) || []) {
      const time = formatTime(message.createTime);
      const sender = message.isSend ? '我' : message.sender;
      let text = String(message.text || '').replace(/\r\n/g, '\n');
      text = text.replace(/\n/g, '\n  ');
      lines.push(`- ${time} ${sender}：${text}`);
      if (message.quote) lines.push(`  > 引用：${String(message.quote).replace(/\n/g, ' ')}`);
      if (message.url) lines.push(`  > 链接：${message.url}`);
      if (message.fileName) lines.push(`  > 文件：${message.fileName}`);
    }
  }
  lines.push('', '---', '');
  lines.push('原始 content 保留在 wechat.db 的 raw 字段，不在此文件中输出。');
  return lines.join('\n') + '\n';
}

function formatTime(ms) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(new Date(ms));
  const value = (type) => parts.find((part) => part.type === type)?.value || '00';
  return `${value('year')}-${value('month')}-${value('day')} ${value('hour')}:${value('minute')}:${value('second')}`;
}

export function writeExport(data, markdown, out, format) {
  if (out === '-') {
    process.stdout.write(format === 'json' ? JSON.stringify(data, null, 2) + '\n' : markdown);
    return null;
  }
  const target = path.resolve(out);
  atomicWrite(target, format === 'json' ? data : markdown);
  return target;
}
