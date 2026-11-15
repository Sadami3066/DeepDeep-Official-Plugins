package io.github.whywood.deepdeep.plugin.wecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomCommandsTest {
    @Test
    fun parsesChatGroupsListWithQuotedTimes() {
        val command = WecomCommands.parse(
            "wecom-cli chat groups list --begin-time '2026-11-08 00:00:00' --end-time \"2026-11-15 00:00:00\"",
        )
        assertNotNull(command)
        assertEquals(WecomCommandKind.CHAT_GROUPS, command!!.kind)
        assertEquals("2026-11-08 00:00:00", command.beginTime)
        assertEquals("2026-11-15 00:00:00", command.endTime)
        assertEquals("2026-11-08 00:00:00 至 2026-11-15 00:00:00", command.contextLabel)
    }

    @Test
    fun parsesChatMessagesWithChatId() {
        val command = WecomCommands.parse(
            "HOME=/root wecom-cli chat messages list --chat-id wrOgQhDgAA001 --begin-time '2026-11-08 00:00:00' --end-time '2026-11-15 00:00:00'",
        )
        assertNotNull(command)
        assertEquals(WecomCommandKind.CHAT_MESSAGES, command!!.kind)
        assertEquals("wrOgQhDgAA001", command.chatId)
        assertTrue(command.contextLabel!!.contains("会话 wrOgQhDgAA001"))
        assertFalse(command.toString().contains("wrOgQhDgAA001"))
    }

    @Test
    fun parsesDocAndCalendarAndTodoAndContact() {
        assertEquals(
            WecomCommandKind.DOC_SEARCH,
            WecomCommands.parse("wecom-cli doc search --keywords 接口文档")?.kind,
        )
        assertEquals(
            WecomCommandKind.DOC_CONTENT,
            WecomCommands.parse("wecom-cli doc contents get --docid DOC_MOCK_001")?.kind,
        )
        assertEquals(
            WecomCommandKind.CALENDAR_SCHEDULES,
            WecomCommands.parse("wecom-cli calendar schedules list --begin-time '2026-11-16 00:00:00'")?.kind,
        )
        assertEquals(
            WecomCommandKind.TODO_LIST,
            WecomCommands.parse("wecom-cli todo list --limit 5")?.kind,
        )
        assertEquals(
            WecomCommandKind.CONTACT_USERS,
            WecomCommands.parse("wecom-cli contact users search --keywords '张三'")?.kind,
        )
    }

    @Test
    fun acceptsAliasesAndAbsolutePath() {
        assertEquals(
            WecomCommandKind.CHAT_GROUPS,
            WecomCommands.parse("/usr/local/bin/wecom-cli chats groups list")?.kind,
        )
        assertEquals(
            WecomCommandKind.DOC_SEARCH,
            WecomCommands.parse("wecom-cli docs search --keywords 方案")?.kind,
        )
    }

    @Test
    fun rejectsPipesRedirectionAndMultipleCliCalls() {
        assertNull(WecomCommands.parse("wecom-cli chat groups list | head -20"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list > /tmp/out.json"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list; wecom-cli todo list"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list && echo done"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list 2>&1"))
        assertNull(WecomCommands.parse("echo $(wecom-cli todo list)"))
    }

    @Test
    fun rejectsUnsupportedShapes() {
        assertNull(WecomCommands.parse("wecom-cli --help"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list --dry-run"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list --page-count 2"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list -o /tmp/out.json"))
        assertNull(WecomCommands.parse("wecom-cli message send --chat-id abc"))
        assertNull(WecomCommands.parse("ls -la"))
        assertNull(WecomCommands.parse(""))
        assertNull(WecomCommands.parse("wecom-cli"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list --begin-time 'unterminated"))
    }

    @Test
    fun rejectsOversizedCommand() {
        val oversized = "wecom-cli todo list --keywords " + "a".repeat(5000)
        assertNull(WecomCommands.parse(oversized))
    }

    @Test
    fun acceptsEnvPrefixQuotesAndAbsolutePath() {
        val command = WecomCommands.parse(
            "HOME=/root /usr/local/bin/wecom-cli chats messages list --chat-id abc --begin-time '2026-11-08 00:00:00'",
        )
        assertNotNull(command)
        assertEquals(WecomCommandKind.CHAT_MESSAGES, command!!.kind)
        assertEquals("abc", command.chatId)
        assertEquals("2026-11-08 00:00:00", command.beginTime)
    }

    @Test
    fun rejectsShellMetacharactersAndUnbalancedQuotes() {
        assertNull(WecomCommands.parse("wecom-cli chat groups list &"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list (x)"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list --keywords \"unterminated"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list \\"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list\nwecom-cli todo list"))
        assertNull(WecomCommands.parse("wecom-cli chat groups list -- --keywords x"))
    }
}
