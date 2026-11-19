# lark-cli output fixtures

The fixtures come from running real commands with `lark-cli 1.0.100` (linux-amd64, sha256 matching the release `checksums.txt`):

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

The capture environment had no Feishu sign-in, so the CLI reached a mock OpenAPI through a local TLS proxy and only the upstream API response data was replaced; the envelope, field names, time formats, paging and `meta` all come from the CLI itself. `chats-with-notice.json` adds the documented `_notice.update` to a success envelope to test notice stripping.

`docs-search.json` keeps `title_highlighted`, `summary_highlighted` and `result_meta` (token/url/owner/doc_types) in the real response shape, with redacted fake document contents. `messages-rich.json` and `mail-triage-error.json` are also redacted constructions: the former covers sender i18n names, `<p>`/`<card>` bodies and system message placeholders, the latter the 4013/15180001 errors for a mailbox that is not activated.

`mail-triage.json`, `mail-message.json`, `messages-search.json`, `*-create-result.json`, `messages-send-result.json`, `mail-send-*-result.json` and `auth-status.json` are fixtures built from the `--help` output, embedded reference docs and real-device response shapes of `lark-cli 1.0.100` (not re-captured through the mock proxy): envelopes, paging fields and error structures follow the same CLI version; ids and urls in write results are placeholders.
