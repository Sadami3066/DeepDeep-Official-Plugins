package io.github.whywood.deepdeep.plugin.zanao

import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun literal(zh: String, en: String, default: String = zh): LocalizedText =
    localizedText(mapOf("default" to default, "zh" to zh, "en" to en))

internal fun PluginHost.t(zh: String, en: String): String = text(literal(zh, en))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String =
    text(literal(zh, en), *args)

internal const val PAGE_ID = "main"
internal const val ICON_ASSET = "icon.png"
internal const val ZANAO_PACKAGE = "com.comingx.zanao"
internal const val DEFAULT_ALIAS = "sysu"
internal const val HELPER_PACKAGE = "io.github.whywood.vpnspike"
internal const val HELPER_ASSET = "token-helper.apk"

internal const val SECRET_TOKEN = "token"
internal const val SECRET_DEVICE = "device"
internal const val SETTING_ALIAS = "alias"
internal const val SETTING_CAPTURE_ACTIVE = "capture_active"
internal const val SETTING_PREVIOUS_PROXY = "capture_previous_proxy"
internal const val CAPTURE_TIMEOUT_MS = 180_000L
internal const val CAPTURE_SERVICE_ID = "zanao-capture"

internal val PAGE_TITLE = literal("校园集市", "Campus Market")
internal val ZANAO_CLI_SKILL = """
---
name: zanao-cli
description: 赞噢校园集市 CLI。用户问校园集市帖子、热门、搜索、评论、点赞、消息、个人资料、发帖，或要查看帖子/评论的发布者资料、按昵称或头像检索历史帖子时使用；命令是 skill 同目录的 zanao.py。
---

# 赞噢校园集市 CLI

本 skill 自带 `zanao.py`，不要假设系统 PATH 里有 `zanao`。
CLI 与本 SKILL.md 同目录，进入该目录后执行：

```bash
python3 ./zanao.py --version
python3 ./zanao.py import-token
python3 ./zanao.py ping
python3 ./zanao.py list --n 10
python3 ./zanao.py hot
python3 ./zanao.py search 相机
python3 ./zanao.py info <thread_id>
python3 ./zanao.py comments <thread_id>
python3 ./zanao.py profile --tid <thread_id>
python3 ./zanao.py toplist
python3 ./zanao.py likelist <thread_id>
python3 ./zanao.py tags
python3 ./zanao.py me
python3 ./zanao.py messages
python3 ./zanao.py categories
```

作者资料与历史检索：

```bash
# 发布者资料：单条查 / 批量查 / 原始返回
python3 ./zanao.py profile --tid <thread_id>
python3 ./zanao.py profile --cid <comment_id>
python3 ./zanao.py profile --file ids.txt --json
python3 ./zanao.py profile --tid <thread_id> --raw
```

`search` 与 `scan` 的区别：

- `search <关键词>`：单次查询，直接返回服务端当前结果，适合「找某类内容」。
- `scan`：批量任务，多关键词 × 多页 + 板块回溯，逐条做作者比对，适合「把某人的历史帖子捞全」。

```bash
# 批量检索：昵称匹配 + 头像片段比对
python3 ./zanao.py scan -n "昵称" --seed <thread_id> --days 30
python3 ./zanao.py scan -n "昵称" --avatar "http://b1.cdn.zanao.com/upload/.../xxx.jpg@!sm_w100_h100" --full
python3 ./zanao.py scan -n "昵称" --walk-only --days 2      # 只走板块回溯
python3 ./zanao.py scan -n "昵称" --cache-only              # 24h 缓存直接出结果
```

`scan` 默认 fast：种子帖提 6 个词 + 12 个通用词，每个词翻 30 页；`--full` 用全部通用词、每词 25 页，并追加 6 个板块的历史回溯（4000 页/板块上限），`--days` 限定检索范围。

也可以直接用同目录的启动器：

```bash
sh ./zanao ping
sh ./zanao hot
sh ./zanao categories
```

图片先上传拿 img_paths，再带进评论或帖子（评论和发帖都支持多图，逗号分隔）：

```bash
python3 ./zanao.py upload ./a.jpg ./b.png          # 每行输出一个 img_paths
python3 ./zanao.py comment <thread_id> --content "..." \
  --images "upload/2026/11/14/xxx.png" --yes
python3 ./zanao.py post --title "标题" --content "正文" --cate 校园趣事 \
  --images "upload/2026/11/14/xxx.png" --yes
```

写操作默认 dry-run，确认后加 `--yes`：

```bash
python3 ./zanao.py like <thread_id> --yes
python3 ./zanao.py unlike <thread_id> --yes
python3 ./zanao.py comment <thread_id> --content "..." --yes
python3 ./zanao.py comment <thread_id> --images "..." --yes   # 纯图片评论
python3 ./zanao.py post --title "标题" --content "正文" --cate 校园趣事 --yes
```

Token 由捕捉器自动写到 `/sdcard/Download/Zanao/zanao-token.json`，CLI 会自动读取最新文件；也可以手动 `import-token` 写入 `~/.zanao.json`。不要把 token 发给别人或写进日志。
"""
