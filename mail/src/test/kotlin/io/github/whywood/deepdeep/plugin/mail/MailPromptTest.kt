package io.github.whywood.deepdeep.plugin.mail

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
    fun promptDescribesRulesWithoutCardDisplayWording() {
        val text = mailPromptBody(
            MailState(accounts = listOf(account), unreadByAccount = mapOf("account-1" to 3)),
        )
        assertTrue(text, text.contains("user@example.com"))
        assertTrue(text, text.contains("未读 3"))
        assertTrue(text, text.contains("mail_save_attachment"))
        assertFalse(text, text.contains("卡片"))
        assertFalse(text, text.contains("不要再抄"))
        assertFalse(text, text.contains("card"))
        assertFalse(text, text.contains("Card"))
    }

    @Test
    fun promptMentionsMissingAccountWithoutCardWording() {
        val text = mailPromptBody(MailState())
        assertTrue(text, text.contains("没有已连接的邮箱账号"))
        assertFalse(text, text.contains("卡片"))
        assertFalse(text, text.contains("不要再抄"))
    }
}
