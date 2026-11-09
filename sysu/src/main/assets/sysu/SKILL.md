---
name: sysu
description: 中山大学校园服务 CLI——教务系统（课表、成绩、学分、考试、空教室、请假）+ 在线教学平台（课程、待办、课程内容）+ 统一认证换票（任意中大站点）。用户问上课时间、成绩绩点、考试安排、作业截止、某门课有什么资料、空教室、请假审批、或想登进别的中大系统时用。命令是 `sysu`（没有 `jwxt` 这个入口了）。
---

# 中大校园服务（`sysu`）

```sh
sysu login                     # 统一登录（账密 + 企微验证码；jwxt/lms 共用）
sysu jwxt                      # 本周课表
sysu jwxt schedule --week 8    # 指定周  ·  --today / --tomorrow
sysu jwxt scores               # 成绩 + 加权绩点  ·  --all 全部学期
sysu jwxt credits              # 毕业学分进度
sysu jwxt exams                # 整学期考试安排
sysu jwxt course LN3015        # 一门课：上课/考试/成绩；也接受课名
                               #   --intro 简介/考核/教材  ·  --syllabus 逐周进度
sysu jwxt notice [id]          # 教务部/院系公告
sysu jwxt room                 # 空闲教室  ·  --sections 9-11 找自习室
sysu jwxt ta                   # 助教信息
sysu jwxt leave                # 请假记录与审批状态

sysu lms                       # 在线教学平台的课程（ID/名称/进度）
sysu lms todo                  # 作业与待办（默认 14 天）· --days 30
sysu lms course <id>           # 一门课的作业/资料/测验  ·  --urls
sysu lms files <id>            # 可下载文件清单
sysu lms get <id>              # 下载全部到 /storage/emulated/0/Download/lms-<id>
                               #   --out 目录 · --only 关键词 · --dry-run
sysu lms whoami                # 当前 LMS 账号

sysu gym                       # 体育场馆：账户 / 类型 / 时段 / 我的预约
sysu gym slots 乒乓球 --days 2  # 空位（括号里是剩余容量）
sysu gym mine                  # 我的预约

sysu netpay                    # 校园网服务状态与到期日
sysu netpay orders             # 网费订单
sysu netpay renew              # 续费：默认 dry-run，--yes 建未付款单

sysu sso <站点地址>            # 用统一认证换任意站点的登录态
```

全部选项看 `sysu jwxt --help` / `sysu lms --help`；任何命令加 `--json` 出结构化数据。

## 要记住的

- **`sysu jwxt leave apply` 是唯一的写操作，交了就撤不回来**——撤单只能找辅导员驳回。三重闸：先打印 → 敲 `y` 才提交 → 非交互终端一律当取消。`--dry-run` / `--json` 只预览。
- 请假**必须带附件**（jpg/jpeg/png/gif/pdf），**附件名只能是 ASCII**，中文会被存成一串 `?`。
- **课名会撞车**：同名课各学院各有一份。课程码最可靠；用课名时只在能唯一确定时才给大纲，否则列候选、不猜——这时候问用户要课程码，别自己指定一个。
- **绩点是自算口径**（只算有绩点的课，缓考与 P/F 不计），**不是学校官方口径**，别当权威数字汇报。
- `exams` 和 `course` 要几秒才出结果（有转圈提示），不是卡死。
- 登录只用 `sysu login`；需要验证码时第一次会把 CAS 会话存进 `~/.config/sysu/jwxt/mfa.json`（10 分钟），
  拿到码用 `sysu login --code 1234` 续上。不要向用户索要验证码，让他在插件页/终端自己输。
- 会话失效会自动静默续期；`sysu lms` 靠同一个 CAS TGC 免密进入。
- **LMS 首次登录要一次企业微信验证码**（站点级 MFA），之后 CAS 记住设备，自动重登不再要码；真过期了跑 `sysu login`。
- 下载课件走 `sysu lms get <id>`；先 `files` 看清楚，`--only` 可过滤，大文件（数据包）会几十 MB。
- **LMS 课程 ID 来自 `sysu lms`**，不要自己猜；课程页解析失败时会退回 HTML，结果可能不全，要说明。
- 续期/换票可能把浏览器里的登录顶掉，刷新一下就能免密回去。

## 体育场馆

- 纯 Node，无外部依赖；WAF 的 PoW 挑战由 CLI 自动处理。
- token 一小时过期，`sysu gym` 会自动用 TGC 续；要企微验证码时用 `sysu gym login --code 1234`。
- **预约是写操作**：`sysu gym book` 默认 dry-run 打印 payload，必须用户明确同意后才能加 `--yes`；取消用 `sysu gym cancel <预约号> --yes`，积分原路退回。

## 凭据

`~/.config/sysu/` 下：`credentials`（netid + 密码）、`jwxt/session.json`（教务会话 + **CAS 的 TGC**）、`lms.json`、`gym.json`，都是 0600。**TGC 等于全校统一认证钥匙（图书馆、校园网共用），不进任何输出，不外传。**
