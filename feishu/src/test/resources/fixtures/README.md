# lark-cli 输出夹具

夹具由 `lark-cli 1.0.100`（linux-amd64，sha256 与 release `checksums.txt` 一致）真实执行命令产生：

```bash
LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1 \
  lark-cli im +chat-list --format json
LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1 \
  lark-cli im +chat-messages-list --chat-id <oc_xxx> --no-reactions --format json
LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1 \
  lark-cli calendar +agenda --start 2026-11-16 --end 2026-11-17 --format json
LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1 \
  lark-cli drive +search --query 周报 --format json
LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1 \
  lark-cli docs +fetch --doc doccnAbc123 --doc-format markdown --format json
LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1 \
  lark-cli task +get-my-tasks --complete=false --format json
```

采集环境没有可用的飞书登录态，因此 CLI 通过本地 TLS 代理连接到 mock OpenAPI，仅替换了上游 API 响应数据；信封、字段名、时间格式、分页与 `meta` 均由 CLI 自身生成。`chats-with-notice.json` 在成功信封上补了文档描述的 `_notice.update`，用于验证 notice 剥离。

`docs-search.json` 按真实返回形状保留 `title_highlighted`、`summary_highlighted` 与 `result_meta`（token/url/owner/doc_types），文档内容为脱敏假数据。`messages-rich.json` 与 `mail-triage-error.json` 也是脱敏构造：前者覆盖发送者 i18n 名、`<p>`/`<card>` 正文与系统消息占位符，后者覆盖邮箱未开通的 4013/15180001 错误。

`mail-triage.json`、`mail-message.json`、`messages-search.json`、`*-create-result.json`、`messages-send-result.json`、`mail-send-*-result.json` 与 `auth-status.json` 是按 `lark-cli 1.0.100` 的 `--help`、内嵌 reference 文档与真机输出形状构造的夹具（未重新接 mock proxy 采集）：信封、分页字段与错误结构沿用同版 CLI；写入结果里的 id/url 为占位值。
