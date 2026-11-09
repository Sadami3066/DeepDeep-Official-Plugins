// 跨平台的下载目录：优先环境变量，其次 ~/Downloads，最后当前目录
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function downloadDir() {
  if (process.env.SYSU_DOWNLOAD_DIR) return process.env.SYSU_DOWNLOAD_DIR;
  const shared = '/storage/emulated/0/Download';
  try {
    if (fs.statSync(shared).isDirectory()) return shared;
  } catch {}
  const home = os.homedir();
  for (const p of [path.join(home, 'Downloads'), path.join(home, 'download')]) {
    try {
      if (fs.statSync(p).isDirectory()) return p;
    } catch {}
  }
  return path.join(home, 'Downloads');
}

module.exports = { downloadDir };
