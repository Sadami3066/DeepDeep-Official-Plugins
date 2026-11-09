// 终端排版小工具（等宽对齐 + 中文宽度 + 颜色）
const WIDE = /[^\x00-\xff]/;
const width = (s) => [...String(s)].reduce((n, c) => n + (WIDE.test(c) ? 2 : 1), 0);
const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - width(s)));
const cut = (s, n) => {
  const chars = [...String(s)];
  let out = '';
  let w = 0;
  for (const c of chars) {
    const cw = WIDE.test(c) ? 2 : 1;
    if (w + cw > n) return `${out}…`;
    out += c;
    w += cw;
  }
  return out;
};
const red = (s) => `\x1b[31m${s}\x1b[0m`;
const green = (s) => `\x1b[32m${s}\x1b[0m`;
const dim = (s) => `\x1b[2m${s}\x1b[0m`;
const bold = (s) => `\x1b[1m${s}\x1b[0m`;

module.exports = { WIDE, width, pad, cut, red, green, dim, bold };
