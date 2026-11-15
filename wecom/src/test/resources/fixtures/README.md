# 真机 CLI 采样

来源：`@wecom/cli-linux-arm64` 1.3.4（2026-09-23T11:47:37Z f9b2815）官方静态 aarch64 二进制，2026-11-15 在 jbizr44lkbnbromb 上通过 App 自带 proot rootfs 直接执行（未安装到设备、未登录任何企业微信账号）。

| 文件 | 采集方式 | 说明 |
|---|---|---|
| `help.txt` | `wecom-cli --help` | 真实输出，服务目录与选项 |
| `dry-run-and-auth-attempts.txt` | `--dry-run` / 未授权真实调用 | 真实输出：dry-run 请求体、错误 envelope、`auth show --status` |
| `error-unauthorized.json` | 无凭据调用数据接口 | 真实输出；CLI 退出码非 0 |
| `catalog.json` | 真实 `POST https://qyapi.weixin.qq.com/cli/service/discovery` | 真实服务目录，来自 CLI 自身缓存 |
| `service-chat.json` | 真实 service discovery（chat） | 真实服务 schema，说明响应字段 |
| `chat-groups.json` | 数据接口成功输出 | CLI 真实输出格式（缩进 JSON，退出码 0）；响应体按官方 schema 构造，非真实账号数据 |
| `chat-messages.json` | 同上 | 同上 |
| `doc-search.json` | 同上 | 同上 |
| `doc-content.json` | 同上 | 同上 |
| `calendar-schedules.json` | 同上 | 同上 |
| `todo-list.json` | 同上 | 同上 |
| `contact-users.json` | 同上 | 同上 |
| `registry-latest.json` | 真实 `GET https://registry.npmjs.org/@wecom/cli-linux-arm64/latest` | 更新检查样例：version / dist.tarball / dist.integrity（sha512）；校验和与 1.3.4 官方 tarball 一致 |

成功样例的数据接口响应由本地 mock 服务按 CLI 自带 schema 构造，只为固定 CLI 的 stdout 形态（缩进 JSON、无包裹、无提示行）。没有可用的已授权企业微信账号，因此真实账号响应未经采集；解析器的字段依据取自 `service-chat.json` 等真实 schema 与静态资源里的 `--doc` 声明。
