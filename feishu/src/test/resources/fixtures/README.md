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
