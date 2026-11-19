package io.github.whywood.superpi.plugin.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailPromptTest {
    private val account = MailAccount(
        id = "account-1",
        providerId = "custom",
        email = "user@example.com",
        displayName = "User",
        color = 0,
        createdAt = 1L,
    )

    @Test
    fun promptDescribesRulesAndPresentInstruction() {
        val text = mailPromptBody(
            MailState(accounts = listOf(account), unreadByAccount = mapOf("account-1" to 3)),
        )
        assertTrue(text, text.contains("user@example.com"))
        assertTrue(text, text.contains("未读 3"))
        assertTrue(text, text.contains("mail_save_attachment"))
        assertTrue(text, text.contains("正文只写结论与下一步"))
        assertFalse(text, text.contains("不要再抄"))
    }

    @Test
    fun promptMentionsMissingAccountAndPresentInstruction() {
        val text = mailPromptBody(MailState())
        assertTrue(text, text.contains("没有已连接的邮箱账号"))
        assertTrue(text, text.contains("正文只写结论与下一步"))
        assertFalse(text, text.contains("不要再抄"))
    }
}
