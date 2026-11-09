const https = require('node:https');
const tls = require('node:tls');
const { YR2, ROOT_YR } = require('./ca');

const CA = [...tls.rootCertificates, YR2, ROOT_YR];
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36';

function get(url, { cookie = '', referer = '', timeout = 20000 } = {}) {
  return send('GET', url, { cookie, referer, timeout });
}

function post(url, body, { cookie = '', referer = '', timeout = 20000 } = {}) {
  return send('POST', url, { cookie, referer, timeout, body: JSON.stringify(body) });
}

function postBinary(url, body, contentType, { cookie = '', referer = '', timeout = 60000, headers = {} } = {}) {
  return send('POST', url, { cookie, referer, timeout, body, contentType, headers });
}

function getBinary(url, { cookie = '', referer = '', timeout = 60000 } = {}) {
  return new Promise((resolve, reject) => {
    const { host, pathname, search } = new URL(url);
    const req = https.request(
      {
        method: 'GET',
        host,
        path: pathname + search,
        ca: CA,
        headers: { Cookie: cookie, 'User-Agent': UA, ...(referer && { Referer: referer }) },
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () =>
          resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }),
        );
      },
    );
    req.setTimeout(timeout, () => req.destroy(new Error('下载超时')));
    req.on('error', reject);
    req.end();
  });
}

function send(method, url, { cookie = '', referer = '', timeout = 20000, body, contentType, headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const { host, pathname, search } = new URL(url);
    const req = https.request(
      {
        method,
        host,
        path: pathname + search,
        ca: CA,
        headers: {
          Cookie: cookie,
          'User-Agent': UA,
          Accept: 'application/json, text/plain, */*',
          ...(referer && { Referer: referer }),
          ...(body && {
            'Content-Type': contentType || 'application/json;charset=UTF-8',
            'Content-Length': Buffer.byteLength(body),
          }),
          ...headers,
        },
      },
      (res) => {
        let text = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => (text += chunk));
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: text }));
      },
    );
    req.setTimeout(timeout, () => req.destroy(new Error('请求超时（网络或代理问题）')));
    req.on('error', (err) => {
      if (/CERT|SSL|UNABLE_TO_GET_ISSUER/.test(err.code || '')) {
        return reject(new Error(`${err.message}\n教务系统的证书链可能换过了，更新 src/ca.js。`));
      }
      reject(err);
    });
    if (body) req.write(body);
    req.end();
  });
}

const parseCookies = (setCookie = []) =>
  setCookie.map((line) => {
    const [pair] = line.split('; ');
    const i = pair.indexOf('=');
    return { name: pair.slice(0, i), value: pair.slice(i + 1) };
  });

module.exports = { get, getBinary, post, postBinary, parseCookies, CA, UA };
