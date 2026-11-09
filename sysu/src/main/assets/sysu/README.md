# sysu — 中山大学校园服务 CLI

一个入口，多个站点：`sysu <站点> <命令>`。纯 Node 实现，零第三方依赖，只用内置模块。

```sh
sysu login             # 一次登录，所有站点共用
sysu jwxt              # 本周课表
sysu lms               # 在线教学平台的课
sysu sso <地址>        # 用统一认证换任意站点的登录态
```

| 命令 | 能干什么 |
|---|---|
| `login` | 统一登录：账密 + 企业微信验证码，登录一次后 jwxt / lms / sso 共用 |
| `jwxt` | 课表、成绩、绩点、学分、考试、课程大纲、公告、空闲教室、请假 |
| `lms` | 在线教学平台（Moodle）：课程列表、待办/作业截止、课程内容 |
| `gym` | 体育场馆：账户、场馆类型、空闲时段、我的预约 |
| `netpay` | 网费在线：网络服务状态、到期日、缴费订单 |
| `sso` | CAS 换票器：任何接统一认证的中大站点都能免密进去 |

## 安装

需要 Node 22 以上。教务登录要读企业微信验证码通知，另需 `termux-api` 包与 Termux:API 应用。

```sh
mkdir -p ~/projects ~/bin ~/.config/sysu
tar xzf sysu-*.tar.gz -C ~/
mv ~/sysu ~/projects/sysu
cd ~/projects/sysu

ln -sf ~/projects/sysu/bin/sysu ~/bin/sysu

mkdir -p ~/.config/sysu
cp config/credentials ~/.config/sysu/credentials   # 填 netid / password
chmod 600 ~/.config/sysu/credentials
```

`~/bin` 在 PATH 里就能直接敲 `sysu`。没在的话把 `export PATH="$HOME/bin:$PATH"` 加进 `~/.bashrc`。

`bin/sysu` 的 shebang 是 `#!/usr/bin/env node`；Termux 里 node 在 `/data/data/com.termux/files/usr/bin/node`，也能正常解析。

## 登录

```sh
sysu login             # 账密；需要验证码时会把 CAS 会话暂存 10 分钟
sysu login --code 1234 # 输入企业微信收到的 4 位码，接着完成
sysu login --force     # 已登录时强制重来
```

登录分三层：`sysu login`（身份/TGC，全校通用）→ `sysu gym login`、`sysu lms login`（换本站 token，只有有独立令牌的站点才需要）→ 业务命令。教务没有独立令牌，所以没有 `sysu jwxt login`。

验证码中断也不怕：第一次执行会把 CAS 会话写进 `~/.config/sysu/jwxt/mfa.json`（0600，10 分钟有效），
拿到码后用 `--code` 续上，不用重新输密码。插件页的登录卡片读的就是这个文件。

一次登录会留下两张票：

- `~/.config/sysu/jwxt/session.json` —— 教务会话 + **CAS 的 TGC**
- TGC 是全校统一认证的钥匙，`sysu lms`、`sysu sso` 都靠它免密

会话失效会自动静默续期；TGC 也过期了才需要重新 `sysu login`。

## 配置目录

```
~/.config/sysu/
  credentials          学号 + 密码（0600）
  jwxt/session.json    教务会话 + CAS 的 TGC
  jwxt/mfa.json        验证码中断时的临时会话（10 分钟）
  lms.json             LMS 会话
  gym.json / gym.jar   gym token 与 WAF cookie
```

没有历史版本兼容：配置只用 `~/.config/sysu/`。

## 教务（`sysu jwxt`）

```sh
sysu jwxt                      # 本周课表
sysu jwxt schedule --week 8    # 指定周  ·  --today / --tomorrow
sysu jwxt scores               # 成绩 + 加权绩点  ·  --all 全部学期
sysu jwxt credits              # 毕业学分进度
sysu jwxt exams                # 整学期考试安排
sysu jwxt course LN3015        # 一门课：上课/考试/成绩  ·  --intro  ·  --syllabus
sysu jwxt notice [id]          # 公告，给 id 看全文  ·  --num N
sysu jwxt room                 # 空闲教室  ·  --sections 9-11 找这节空着的教室
sysu jwxt ta                   # 助教信息
sysu jwxt leave                # 请假记录与审批状态
```

`sysu jwxt --help` 看全部选项，任何命令加 `--json` 出结构化数据。

## 在线教学平台（`sysu lms`）

```sh
sysu lms                    # 我的课程（ID / 名称 / 进度 / 结课时间）
sysu lms todo               # 未来 14 天的作业与待办  ·  --days 30
sysu lms course <id>        # 一门课的内容（作业、资料、测验、讨论区…）  ·  --urls
sysu lms whoami             # 当前 LMS 账号与课程数
sysu lms files <id>         # 这门课能下载哪些文件
sysu lms get <id>           # 全部下载（--out 目录 · --only 关键词 · --dry-run）
sysu lms login [--code N]   # 只重建 LMS 会话（要 MFA 时加 --code）
```

首次进 LMS 会要一次企业微信验证码（站点级 MFA 策略，jwxt 不需要）；之后 CAS 记住设备，自动重登不再要码。

数据走 Moodle 自己的 ajax 接口（`/lib/ajax/service.php`），拿 JSON，不解析网页；课程页解析不出来时才退回 HTML。

## 体育场馆（`sysu gym`）

```sh
sysu gym                    # 账户 + 校区
sysu gym types              # 51 个场馆类型  ·  --campus 南校园
sysu gym venues 乒乓球       # 某类型的场地
sysu gym slots 乒乓球 --days 2
sysu gym slots 羽毛球 --date 2026-11-10
sysu gym mine               # 我的预约（默认 30 天）
sysu gym book --type 乒乓球 --venue 场地2 --date 2026-11-10 --start 08:00 --end 09:00
                            # 下单（默认 dry-run，--yes 才提交）
sysu gym cancel "#RB-XXXX"    # 取消预约（积分原路退回，--yes 免确认）
sysu gym login --code 1234  # token 过期且要 MFA 时
```

**零外部依赖**：gym 前面的雷池 WAF 用 SHA1 工作量证明挑战，CLI 内部自动解，纯 Node 就能过，不需要浏览器或任何二进制。

登录是三层：`sysu login` 拿 TGC → `sysu gym` 用 TGC 换本站 OAuth token（1 小时，自动续）→ 业务命令调 `/api/*`。

## 网费（`sysu netpay`）

```sh
sysu netpay              # 账户 + 网络服务（状态、到期日）
sysu netpay orders       # 缴费订单（--num N）
sysu netpay renew        # 续费（默认 dry-run，--yes 建未付款订单）
sysu netpay cancel <单号> # 取消未付款订单
                          # 支付页默认写到下载目录（SYSU_DOWNLOAD_DIR 可覆盖）
sysu netpay login        # 会话坏了时重登
```

登录走 CAS（`/netpay/casLogin`），会话存 `~/.config/sysu/netpay.json`。
续费需要系统把服务列进“可续费”窗口，`initPay` 为空时说明暂不可自助续费。

## 统一认证（`sysu sso`）

```sh
sysu sso https://lms.sysu.edu.cn/login/index.php?authCAS=CAS
sysu sso --ticket https://example.sysu.edu.cn/cas      # 只打印 ST
sysu sso ... --json                                    # 机器可读
```

输出会列出换到的 Cookie（值默认脱敏）。给新站点写模块时，用它先确认「这个站到底吃不吃 CAS」。

## 目录

| 路径 | 内容 |
|---|---|
| `bin/sysu` | 入口，`sysu <命令|站点>` 分发 |
| `src/sysu.js` | 站点注册表 + 顶层帮助 |
| `src/cli/*.js` | 各命令的参数解析与排版（jwxt / lms / gym / netpay / sso / login）|
| `src/cmds.js` `src/client.js` | 教务各子命令与接口封装 |
| `src/lms.js` | Moodle 客户端（ajax + HTML 回退）|
| `src/gym.js` | 体育场馆（WAF PoW、OAuth、预约、wasm token）|
| `src/netpay.js` | 网费在线（CAS 会话、服务状态、订单、建单）|
| `src/sso.js` | CAS 换票器 + cookie jar + 站点会话缓存 |
| `src/login.js` | 统一登录、MFA 状态机（mfa.json）|
| `src/session.js` | 教务会话票据读写与续期 |
| `src/http.js` `src/ca.js` | 请求与证书链处理 |
| `src/paths.js` `src/style.js` | 下载目录（跨平台）与终端排版 |
| `assets/wasm_token.wasm` | 预约 token 的 WebAssembly |
| `SKILL.md` `PROGRESS.md` | AI 调用说明 / 开发记录 |

## 加新站点

在 `src/sysu.js` 的 `SITES` 里加一行：

```js
tiyu: { desc: '体育场馆预约', main: () => require('./cli/tiyu').main },
```

再写 `src/cli/tiyu.js`，导出 `main(argv)` 和 `HELP`。登录态直接用 `sso.sessionFor('tiyu', '<站点 CAS 地址>')` 拿，不用重复实现换票。

## 注意

- `sysu jwxt leave apply` 是唯一的写操作，提交后无法自行撤回。先 `--dry-run` 看一眼。
- 请假必须带附件，附件名只能是 ASCII。
- 换票可能把浏览器里的登录顶掉（像单会话），刷新一下会 CAS 免密回去。
- `~/.config/` 下两个文件都含敏感票据，权限 0600，别外传。
