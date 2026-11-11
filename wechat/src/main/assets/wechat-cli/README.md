# wechat-cli

把指定日期范围的微信聊天完整导出给模型。CLI 只做数据工作，不做摘要、不做 TopK、不做重要性判断。

## 数据流

```text
微信加密库
  EnMicroMsg.db + -wal + -shm
        │  sync：root 读取，稳定复制，sqlcipher 解密
        ▼
<data>/current/plain.db
        │  ingest：全量规范化，raw 留在库里
        ▼
<data>/wechat.db
        │  export --from --to：清洗、分组、时间排序
        ▼
<data>/exports/<range>.md / .json
```

## 依赖

- Node 24+
- `sqlcipher`（Alpine: `apk add sqlcipher`）
- Root（读微信私有目录）

## 命令

```bash
wechat sync --data ~/.wechat
wechat ingest --data ~/.wechat          # 默认增量
wechat ingest --data ~/.wechat --full   # 全量重扫

wechat export --date today --format md --data ~/.wechat
wechat export --from 2026-11-01 --to 2026-11-10 --format json --data ~/.wechat

wechat doctor --data ~/.wechat
wechat purge --data ~/.wechat --keep 3
```

`export --out -` 输出到 stdout。

## 导出内容

- 当天/范围内的 `message` 表全部记录
- 个人、群聊、公众号、文件传输助手、支付、服务通知全部保留
- `kind` 只做标注，不做过滤
- 不清洗掉任何一条消息
- `text` 是清洗后的可读内容
- `raw` 保留在 `wechat.db`，不进入导出文件
- 图片/语音/视频/文件/链接/位置/撤回都有明确占位
- 按会话分组，会话内按时间排序

## 数据目录

```text
<data>/
├── versions/<id>/plain.db
├── current -> versions/<id>
├── plain.db -> current/plain.db
├── manifest.json
├── wechat.db
└── exports/
    ├── 2026-11-10.md
    └── 2026-11-01_2026-11-10.json
```

权限：目录 `0700`，数据库和导出文件 `0600`。

## 边界

- 只覆盖微信本地库 `message` 表
- 撤回后本地已删的消息、未同步到本机的历史无法恢复
- 媒体文件本体不导出，只导出数据库里的路径/元数据
- CLI 不做摘要；模型读完整导出后自行总结
