let jsonMode = false;

function setJsonMode(on) {
  jsonMode = on === true;
}

function isJsonMode() {
  return jsonMode;
}

function visualWidth(text) {
  let width = 0;
  for (const ch of String(text)) {
    const code = ch.codePointAt(0);
    const wide =
      (code >= 0x1100 && code <= 0x115f) ||
      (code >= 0x2e80 && code <= 0xa4cf) ||
      (code >= 0xac00 && code <= 0xd7a3) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xfe30 && code <= 0xfe4f) ||
      (code >= 0xff00 && code <= 0xff60) ||
      (code >= 0xffe0 && code <= 0xffe6);
    width += wide ? 2 : 1;
  }
  return width;
}

function pad(text, width) {
  const value = String(text ?? '');
  return value + ' '.repeat(Math.max(0, width - visualWidth(value)));
}

function table(rows, gap = 2) {
  const columns = Math.max(...rows.map((row) => row.length));
  const widths = [];
  for (let i = 0; i < columns; i += 1) {
    widths[i] = Math.max(...rows.map((row) => visualWidth(row[i] ?? '')));
  }
  return rows
    .map((row) =>
      row
        .map((cell, i) => (i === row.length - 1 ? String(cell ?? '') : pad(cell, widths[i])))
        .join(' '.repeat(gap))
        .trimEnd(),
    )
    .join('\n');
}

function emit(payload, render) {
  if (jsonMode) {
    console.log(JSON.stringify(payload, null, 2));
    return;
  }
  let text = null;
  if (typeof render === 'function') {
    try {
      text = render();
    } catch {
      text = null;
    }
  }
  console.log(text == null || text === '' ? JSON.stringify(payload, null, 2) : text);
}

function cleanMessage(value) {
  return String(value ?? '').replace(/\u001b\[[0-9;]*[a-zA-Z]/g, '').trim() || '未知错误';
}

function fail(error) {
  const message = cleanMessage(error && error.message ? error.message : error);
  const payload = { ok: false, error: { message } };
  if (error && error.code != null) payload.error.code = error.code;
  if (error && error.needMfa) {
    payload.needMfa = true;
    if (error.hint) payload.hint = String(error.hint);
  }
  if (jsonMode) {
    console.log(JSON.stringify(payload, null, 2));
  } else {
    console.error(`错误：${message}`);
  }
  process.exitCode = 1;
}

function block(title, lines) {
  const body = lines.filter((line) => line != null && line !== '').join('\n');
  if (!title) return body;
  return body ? `${title}\n${body}` : title;
}

function valueText(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function kv(lines) {
  return lines
    .map(([label, value]) => {
      const text = valueText(value);
      return text === '' ? null : `${label}  ${text}`;
    })
    .filter(Boolean)
    .join('\n');
}

function dateTime(value) {
  if (!value) return '';
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const pad2 = (n) => String(n).padStart(2, '0');
  const shifted = new Date(date.getTime() + 8 * 3600e3);
  const mm = pad2(shifted.getUTCMonth() + 1);
  const dd = pad2(shifted.getUTCDate());
  const hh = pad2(shifted.getUTCHours());
  const mi = pad2(shifted.getUTCMinutes());
  return `${mm}-${dd} ${hh}:${mi}`;
}

module.exports = {
  setJsonMode,
  isJsonMode,
  visualWidth,
  pad,
  table,
  emit,
  fail,
  block,
  kv,
  valueText,
  dateTime,
  cleanMessage,
};
