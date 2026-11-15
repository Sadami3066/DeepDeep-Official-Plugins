package io.github.whywood.deepdeep.plugin.wecom

internal object WecomObservation {
    const val MAX_ROWS = 60
    const val MAX_CONTENT_CHARS = 8000

    fun build(command: WecomCommand, parsed: WecomParsed): String = when (parsed) {
        is WecomParsed.Groups -> groups(command, parsed)
        is WecomParsed.Messages -> messages(command, parsed)
        is WecomParsed.Docs -> docs(command, parsed)
        is WecomParsed.DocContent -> docContent(command, parsed)
        is WecomParsed.Schedules -> schedules(command, parsed)
        is WecomParsed.Todos -> todos(command, parsed)
        is WecomParsed.Contacts -> contacts(command, parsed)
    }

    private fun groups(command: WecomCommand, parsed: WecomParsed.Groups): String = buildString {
        append("企业微信群聊会话：共 ").append(parsed.count).append(" 个")
        command.contextLabel?.let { append("，时间范围 ").append(it) }
        appendLine()
        if (parsed.items.isEmpty()) {
            append("没有符合条件的群聊会话。")
        } else {
            parsed.items.take(MAX_ROWS).forEachIndexed { index, item ->
                append(index + 1).append(". ")
                append(item.name?.takeIf { it.isNotBlank() } ?: "未命名会话")
                item.chatId?.let { append(" | chat_id=").append(it) }
                item.lastMessageTime?.let { append(" | 最近消息 ").append(it) }
                item.messageCount?.let { append(" | ").append(it).append(" 条消息") }
                appendLine()
            }
            if (parsed.items.size > MAX_ROWS) {
                append("另有 ").append(parsed.items.size - MAX_ROWS).append(" 个会话未列出。").appendLine()
            }
        }
        append(paginationNote(parsed.hasMore, parsed.nextCursor))
    }

    private fun messages(command: WecomCommand, parsed: WecomParsed.Messages): String = buildString {
        append("企业微信会话消息：共 ").append(parsed.count).append(" 条")
        command.contextLabel?.let { append("，").append(it) }
        appendLine()
        if (parsed.items.isEmpty()) {
            append("该时间范围内没有消息。")
        } else {
            parsed.items.take(MAX_ROWS).forEachIndexed { index, item ->
                append(index + 1).append(". ")
                item.sendTime?.let { append(it).append(" ") }
                val sender = item.senderName?.takeIf { it.isNotBlank() }
                    ?: item.senderId?.takeIf { it.isNotBlank() }
                    ?: "未知成员"
                append(sender)
                item.senderId?.takeIf { it.isNotBlank() && sender != it }?.let { append("（").append(it).append("）") }
                append("：")
                append(item.summary?.takeIf { it.isNotBlank() } ?: messageTypeLabel(item.type))
                item.mediaId?.takeIf { it.isNotBlank() }?.let { append(" | media_id=").append(it) }
                appendLine()
            }
            if (parsed.items.size > MAX_ROWS) {
                append("另有 ").append(parsed.items.size - MAX_ROWS).append(" 条消息未列出。").appendLine()
            }
        }
        append(paginationNote(parsed.hasMore, parsed.nextCursor))
    }

    private fun docs(command: WecomCommand, parsed: WecomParsed.Docs): String = buildString {
        append("企业微信文档搜索：共 ").append(parsed.count).append(" 篇")
        command.contextLabel?.let { append("，关键词 ").append(it) }
        appendLine()
        if (parsed.items.isEmpty()) {
            append("没有找到符合条件的文档。")
        } else {
            parsed.items.take(MAX_ROWS).forEachIndexed { index, item ->
                append(index + 1).append(". ")
                append(item.name?.takeIf { it.isNotBlank() } ?: "未命名文档")
                item.type?.let { append("（").append(docTypeLabel(it)).append("）") }
                item.docId?.let { append(" | docid=").append(it) }
                item.creatorName?.let { append(" | 创建人 ").append(it) }
                item.createTime?.let { append(" | 创建于 ").append(it) }
                item.modifyTime?.let { append(" | 修改于 ").append(it) }
                item.url?.let { append(" | ").append(it) }
                appendLine()
            }
            if (parsed.items.size > MAX_ROWS) {
                append("另有 ").append(parsed.items.size - MAX_ROWS).append(" 篇文档未列出。").appendLine()
            }
        }
        append(paginationNote(parsed.hasMore, parsed.nextCursor))
    }

    private fun docContent(command: WecomCommand, parsed: WecomParsed.DocContent): String = buildString {
        append("企业微信文档内容")
        parsed.name?.let { append("：").append(it) }
        parsed.version?.let { append(" | 版本 ").append(it) }
        command.docId?.let { append(" | docid=").append(it) }
        parsed.url?.let { append(" | ").append(it) }
        appendLine()
        val content = parsed.content.trim()
        if (content.isEmpty()) {
            append("文档内容为空。")
        } else {
            appendLine()
            if (content.length > MAX_CONTENT_CHARS) {
                append(content.take(MAX_CONTENT_CHARS))
                appendLine()
                append("（内容较长，已截取前 ").append(MAX_CONTENT_CHARS).append(" 字，原文共 ")
                append(content.length).append(" 字）")
            } else {
                append(content)
            }
        }
    }

    private fun schedules(command: WecomCommand, parsed: WecomParsed.Schedules): String = buildString {
        append("企业微信日程：共 ").append(parsed.count).append(" 个")
        command.contextLabel?.let { append("，时间范围 ").append(it) }
        appendLine()
        if (parsed.items.isEmpty()) {
            append("该时间范围内没有日程。")
        } else {
            parsed.items.take(MAX_ROWS).forEachIndexed { index, item ->
                append(index + 1).append(". ")
                append(item.subject?.takeIf { it.isNotBlank() } ?: "未命名日程")
                if (item.allDay) append("（全天）")
                item.beginTime?.let { begin ->
                    append(" | ").append(begin)
                    item.endTime?.takeIf { it.isNotBlank() }?.let { append(" 至 ").append(it) }
                }
                item.location?.takeIf { it.isNotBlank() }?.let { append(" | 地点 ").append(it) }
                item.meetingRoom?.takeIf { it.isNotBlank() }?.let { append(" | 会议室 ").append(it) }
                if (item.attendees.isNotEmpty()) {
                    append(" | 参与人 ").append(item.attendees.joinToString("、"))
                }
                item.calendarName?.takeIf { it.isNotBlank() }?.let { append(" | 日历 ").append(it) }
                item.scheduleId?.let { append(" | schedule_id=").append(it) }
                appendLine()
            }
            if (parsed.items.size > MAX_ROWS) {
                append("另有 ").append(parsed.items.size - MAX_ROWS).append(" 个日程未列出。").appendLine()
            }
        }
    }

    private fun todos(command: WecomCommand, parsed: WecomParsed.Todos): String = buildString {
        append("企业微信待办：共 ").append(parsed.count).append(" 个")
        command.contextLabel?.let { append("，").append(it) }
        appendLine()
        if (parsed.items.isEmpty()) {
            append("没有待处理的事项。")
        } else {
            parsed.items.take(MAX_ROWS).forEachIndexed { index, item ->
                append(index + 1).append(". ")
                append(item.title?.takeIf { it.isNotBlank() } ?: "未命名待办")
                append(" [").append(todoStatusLabel(item.status)).append("]")
                item.todoId?.let { append(" | todo_id=").append(it) }
                item.deadline?.takeIf { it.isNotBlank() }?.let { append(" | 截止 ").append(it) }
                item.creator?.takeIf { it.isNotBlank() }?.let { append(" | 创建人 ").append(it) }
                if (item.followers.isNotEmpty()) {
                    append(" | 参与人 ").append(item.followers.joinToString("、"))
                }
                item.updateTime?.takeIf { it.isNotBlank() }?.let { append(" | 更新于 ").append(it) }
                item.description?.takeIf { it.isNotBlank() }?.let { append(" | 描述 ").append(it) }
                appendLine()
            }
            if (parsed.items.size > MAX_ROWS) {
                append("另有 ").append(parsed.items.size - MAX_ROWS).append(" 个待办未列出。").appendLine()
            }
        }
        append(paginationNote(parsed.hasMore, parsed.nextCursor))
    }

    private fun contacts(command: WecomCommand, parsed: WecomParsed.Contacts): String = buildString {
        append("企业微信通讯录：共 ").append(parsed.count).append(" 位成员")
        command.contextLabel?.let { append("，关键词 ").append(it) }
        appendLine()
        if (parsed.items.isEmpty()) {
            append("没有找到符合条件的成员。")
        } else {
            parsed.items.take(MAX_ROWS).forEachIndexed { index, item ->
                append(index + 1).append(". ")
                append(item.name?.takeIf { it.isNotBlank() } ?: "未知成员")
                item.userId?.let { append("（").append(it).append("）") }
                if (item.departments.isNotEmpty()) append(" | ").append(item.departments.joinToString("/"))
                item.position?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                item.email?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                item.alias?.takeIf { it.isNotBlank() }?.let { append(" | 英文名 ").append(it) }
                appendLine()
            }
            if (parsed.items.size > MAX_ROWS) {
                append("另有 ").append(parsed.items.size - MAX_ROWS).append(" 位成员未列出。").appendLine()
            }
        }
    }

    private fun paginationNote(hasMore: Boolean, nextCursor: String?): String = when {
        hasMore && !nextCursor.isNullOrBlank() -> "还有更多数据，可用 cursor=$nextCursor 继续拉取。"
        hasMore -> "还有更多数据，可调整时间范围或分页参数继续拉取。"
        else -> "已返回全部结果。"
    }

    private fun messageTypeLabel(type: String?): String = when (type) {
        "text" -> "[文本]"
        "image" -> "[图片]"
        "file" -> "[文件]"
        "voice" -> "[语音]"
        "video" -> "[视频]"
        "mixed" -> "[图文]"
        else -> "[消息]"
    }

    internal fun todoStatusLabel(status: String?): String = when (status) {
        "proceed" -> "进行中"
        "finished" -> "已完成"
        "deleted" -> "已删除"
        else -> "未知状态"
    }

    internal fun todoUserStatusLabel(status: String?): String = when (status) {
        "accept" -> "已接受"
        "finished" -> "已完成"
        "reject" -> "已拒绝"
        "removed" -> "已移除"
        "notshow" -> "未展示"
        else -> "未知状态"
    }

    internal fun docTypeLabel(type: String?): String = when (type) {
        "doc" -> "文档"
        "sheet" -> "表格"
        "smartsheet" -> "智能表格"
        "smartpage" -> "智能主页"
        "ppt" -> "幻灯片"
        "slide" -> "幻灯片"
        "pdf" -> "PDF"
        "collect" -> "收集表"
        "mind" -> "思维导图"
        "flow" -> "流程图"
        "journal" -> "日记"
        else -> type ?: "文档"
    }
}
