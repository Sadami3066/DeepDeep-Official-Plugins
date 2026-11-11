package io.github.whywood.deepdeep.plugin.wechat

internal object WechatContent {
    fun skillMarkdown(): String = SKILL_MARKDOWN

    fun promptBody(status: WechatStatus?): String {
        if (status == null) {
            return "微信导出插件状态未知。同步、导出与 root 权限状态都在 DeepDeep 的「微信导出」插件页处理；不要向用户索要微信密码或数据库密钥。"
        }
        if (!status.linuxAvailable) {
            return "微信导出当前不可用：缺少 Linux 运行环境。让用户到 设置 → 运行环境 安装或启用后，再打开「微信导出」插件页。"
        }
        if (status.nodeVersion == null || status.nodeTooOld) {
            return "微信导出当前不可用：Linux 里的 Node.js 缺失或低于 24。让用户到 设置 → 运行环境 安装 Node.js 24+。"
        }
        if (!status.installed || status.outdated) {
            return "微信导出 CLI 尚未安装或版本不是最新（当前 ${status.cliVersion ?: "未安装"}，期望 ${WechatCli.VERSION}）。让用户打开「微信导出」插件页，点「安装/更新 CLI」。"
        }
        return buildString {
            appendLine("微信导出 CLI 已安装。")
            appendLine("同步：`wechat sync --data /root/.wechat`，然后 `wechat ingest --data /root/.wechat`。")
            appendLine("导出：`wechat export --from YYYY-MM-DD --to YYYY-MM-DD --format md --data /root/.wechat`。")
            appendLine("导出内容包含个人、群聊、公众号、文件传输助手、服务通知和支付记录；CLI 不做筛选、TopK 或摘要。")
            appendLine("拿到导出文件后读取完整记录，由你自己决定如何总结；不要把 raw 数据库或密钥发给模型。")
            appendLine("当前快照：${if (status.hasSnapshot) "有" else "无"}；规范化库：${if (status.hasNormalized) "有" else "无"}。")
        }
    }

    private val SKILL_MARKDOWN = """
        ---
        name: deepdeep-wechat
        description: 导出指定日期范围的完整微信聊天记录（个人、群聊、公众号、文件传输助手、服务通知、支付），交给 Agent 阅读和总结；CLI 只做数据清洗和全量导出，不做筛选或摘要。
        ---

        # 微信完整导出

        CLI：`wechat`，数据目录 `/root/.wechat`，需要 Root。

        ## 同步

        ```bash
        wechat sync --data /root/.wechat
        wechat ingest --data /root/.wechat
        ```

        ## 导出

        ```bash
        wechat export --from 2026-11-01 --to 2026-11-10 --format md --data /root/.wechat
        wechat export --date today --format json --data /root/.wechat
        ```

        默认输出到 `/root/.wechat/exports/`，也可以用 `--out`。

        ## 读什么

        导出文件是清洗后的完整记录：

        - 所有会话和所有消息都在
        - 公众号、文件传输助手、服务通知、支付记录都在
        - 图片/语音/视频/文件/链接有明确占位
        - `raw` 只在 `wechat.db`，不进入导出文件
        - 导出不按重要性过滤，不做 TopK

        读取导出文件后，按用户要求总结；不要自行假设 CLI 已经筛过重点。

        ## 边界

        只覆盖微信本地 `message` 表；撤回后本地已删、未同步历史和媒体文件本体无法恢复。
    """.trimIndent()
}
