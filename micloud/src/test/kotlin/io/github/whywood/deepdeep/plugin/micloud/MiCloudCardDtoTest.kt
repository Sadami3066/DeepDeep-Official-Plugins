package io.github.whywood.deepdeep.plugin.micloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiCloudCardDtoTest {
    @Test
    fun accountCardRoundTripsQuotaEntries() {
        val data = MiCloudAccountCardData(
            mode = ACCOUNT_MODE_QUOTA,
            entries = listOf(
                MiCloudQuotaEntry(label = "Gift", totalBytes = 1073741824L, usedBytes = 0L, freeBytes = 1073741824L),
                MiCloudQuotaEntry(label = "FirstLevel", totalBytes = 21474836480L, usedBytes = 5242880L),
            ),
        )
        val decoded = MiCloudAccountCardData.fromJson(data.toJson())
        assertEquals(ACCOUNT_MODE_QUOTA, decoded.mode)
        assertEquals(2, decoded.entries.size)
        assertEquals("Gift", decoded.entries[0].label)
        assertEquals(1073741824L, decoded.entries[0].totalBytes)
        assertEquals("FirstLevel", decoded.entries[1].label)
        assertEquals(21474836480L, decoded.entries[1].totalBytes)
        assertEquals(5242880L, decoded.entries[1].usedBytes)
    }

    @Test
    fun accountCardRoundTripsProfile() {
        val data = MiCloudAccountCardData(
            mode = ACCOUNT_MODE_PROFILE,
            nickname = "小明",
            userId = "12345",
            email = "a@example.com",
            phone = "13800000000",
        )
        val decoded = MiCloudAccountCardData.fromJson(data.toJson())
        assertEquals(ACCOUNT_MODE_PROFILE, decoded.mode)
        assertEquals("小明", decoded.nickname)
        assertEquals("12345", decoded.userId)
        assertEquals("a@example.com", decoded.email)
        assertEquals("13800000000", decoded.phone)
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun notesCardRoundTripsListRowsAndCursor() {
        val data = MiCloudNotesCardData(
            action = NOTES_ACTION_LIST,
            total = 12,
            cursor = "tag-2",
            lastPage = false,
            rows = listOf(
                MiCloudNoteItem(id = "n1", subject = "想法", snippet = "摘要", modifyTime = 1763200000000L),
            ),
        )
        val decoded = MiCloudNotesCardData.fromJson(data.toJson())
        assertEquals(NOTES_ACTION_LIST, decoded.action)
        assertEquals(12, decoded.total)
        assertEquals("tag-2", decoded.cursor)
        assertEquals(1, decoded.rows.size)
        assertEquals("n1", decoded.rows.single().id)
        assertEquals("想法", decoded.rows.single().subject)
        assertEquals(1763200000000L, decoded.rows.single().modifyTime)
        assertFalse(decoded.lastPage)
    }

    @Test
    fun notesCardRoundTripsReadState() {
        val data = MiCloudNotesCardData(
            action = NOTES_ACTION_READ,
            total = 1,
            noteId = "n1",
            subject = "想法",
            preview = "第一段",
            modifyTime = 1763200000000L,
        )
        val decoded = MiCloudNotesCardData.fromJson(data.toJson())
        assertEquals(NOTES_ACTION_READ, decoded.action)
        assertEquals("n1", decoded.noteId)
        assertEquals("第一段", decoded.preview)
        assertEquals(1763200000000L, decoded.modifyTime)
    }

    @Test
    fun todoCardRoundTripsRowsDoneCountAndCursor() {
        val data = MiCloudTodoCardData(
            action = TODO_ACTION_LIST,
            total = 2,
            doneCount = 1,
            cursor = "cursor-2",
            hasMore = true,
            rows = listOf(
                MiCloudTodoItem(id = "t1", content = "买牛奶", remindTime = 1763200000000L),
                MiCloudTodoItem(id = "t2", content = "写周报", done = true),
            ),
        )
        val decoded = MiCloudTodoCardData.fromJson(data.toJson())
        assertEquals(TODO_ACTION_LIST, decoded.action)
        assertEquals(2, decoded.total)
        assertEquals(1, decoded.doneCount)
        assertEquals("cursor-2", decoded.cursor)
        assertTrue(decoded.hasMore)
        assertEquals("买牛奶", decoded.rows.first().content)
        assertEquals(1763200000000L, decoded.rows.first().remindTime)
        assertTrue(decoded.rows.last().done)
    }

    @Test
    fun todoCardRoundTripsWriteState() {
        val data = MiCloudTodoCardData(
            action = TODO_ACTION_UPDATE,
            total = 1,
            todoId = "t1",
            content = "买牛奶",
            done = true,
            remindTime = 1763200000000L,
            modifyTime = 1763210000000L,
        )
        val decoded = MiCloudTodoCardData.fromJson(data.toJson())
        assertEquals(TODO_ACTION_UPDATE, decoded.action)
        assertEquals("t1", decoded.todoId)
        assertEquals("买牛奶", decoded.content)
        assertTrue(decoded.done)
        assertEquals(1763200000000L, decoded.remindTime)
    }

    @Test
    fun decodeIgnoresUnknownKeysAndDefaultsMissingFields() {
        val account = MiCloudAccountCardData.fromJson(
            Json.parseToJsonElement("""{"mode":"quota","unknown":"x","entries":[{"label":"Gift","totalBytes":10}]}""")
                .jsonObject,
        )
        assertEquals(ACCOUNT_MODE_QUOTA, account.mode)
        assertEquals(1, account.entries.size)
        assertEquals(10L, account.entries.single().totalBytes)

        val emptyNotes = MiCloudNotesCardData.fromJson(Json.parseToJsonElement("""{"action":"list"}""").jsonObject)
        assertEquals(NOTES_ACTION_LIST, emptyNotes.action)
        assertEquals(0, emptyNotes.total)
        assertTrue(emptyNotes.rows.isEmpty())

        val emptyTodo = MiCloudTodoCardData.fromJson(Json.parseToJsonElement("""{}""").jsonObject)
        assertEquals(TODO_ACTION_LIST, emptyTodo.action)
        assertEquals(0, emptyTodo.total)
        assertFalse(emptyTodo.hasMore)
    }

    @Test
    fun decodeToleratesMalformedRows() {
        val data = MiCloudTodoCardData.fromJson(
            buildJsonObject {
                put("action", TODO_ACTION_LIST)
                put("rows", Json.parseToJsonElement("""[{"id":"t1","done":"true"},{"id":"t2","content":null}]"""))
            },
        )
        assertEquals(2, data.rows.size)
        assertTrue(data.rows.first().done)
        assertEquals("t2", data.rows.last().id)
    }
}
