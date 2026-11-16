package io.github.whywood.deepdeep.plugin.hotel

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNotNull
import org.junit.Test

class TempDecodeTest {
    @Test
    fun decodesGeneratedPayloads() {
        val files = File("/tmp/hotel-qa/sessions").listFiles()!!.sortedBy { it.name }
        var checked = 0
        files.forEach { file ->
            File(file.path).readLines().forEach { line ->
                if (!line.contains("deepdeep.card")) return@forEach
                val root = Json.parseToJsonElement(line).jsonObject
                val message = root["message"]?.jsonObject ?: return@forEach
                val details = message["details"]?.jsonObject ?: return@forEach
                val card = details["deepdeep.card"]?.jsonObject ?: return@forEach
                val payload = card["payload"]?.jsonObject ?: return@forEach
                when (card["title"]?.toString()?.contains("房型")) {
                    true -> {
                        val decoded = HotelJson.decodeFromJsonElement(HotelDetailDetails.serializer(), payload)
                        assertNotNull(decoded)
                        println("HOTEL DETAIL OK groups=" + decoded.roomGroups.size)
                        checked++
                    }
                    else -> Unit
                }
                if (card["title"]?.toString()?.contains("取消") == true) {
                    val decoded = HotelJson.decodeFromJsonElement(HotelCancelQuote.serializer(), payload)
                    println("CANCEL OK " + decoded.alreadyCancelled)
                    checked++
                }
            }
        }
        println("CHECKED=$checked")
    }
}
