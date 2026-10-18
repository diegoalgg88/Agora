package com.newoether.agora.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

internal object NativeBackupV5Writer {
    fun write(
        zip: ZipArchiveOutputStream,
        conversations: List<JsonObject>,
        runs: List<JsonObject>,
        messages: List<JsonObject>,
        tasks: List<JsonObject>,
        loops: List<JsonObject>,
        baseline: NativeBackupV5Baseline? = null,
        unchangedConversationIds: Set<String> = emptySet(),
    ) {
        val copiedMedia = linkedSetOf<String>()
        val index = conversations.map { conversation ->
            val id = conversation.getValue("id").jsonPrimitive.content
            val baselineEntry = baseline?.indexEntry(id)
                ?.takeIf { id in unchangedConversationIds }
            if (baselineEntry != null) {
                baseline.copyRaw(baselineEntry.entry, zip)
                baselineEntry.mediaEntries.forEach { media ->
                    if (copiedMedia.add(media)) baseline.copyRaw(media, zip)
                }
                return@map baselineEntry
            }
            val payload = JsonObject(
                mapOf(
                    "conversations" to JsonArray(listOf(conversation)),
                    "runs" to JsonArray(runs.filter { it["conversationId"]?.jsonPrimitive?.content == id }),
                    "messages" to JsonArray(messages.filter { it["conversationId"]?.jsonPrimitive?.content == id }),
                    "loops" to JsonArray(loops.filter { it["conversationId"]?.jsonPrimitive?.content == id }),
                )
            )
            val mediaEntries = linkedSetOf<String>()
            collectMediaEntries(payload, mediaEntries)
            val entry = NativeBackupFormat.conversationEntry(id)
            zip.putArchiveEntry(ZipArchiveEntry(entry))
            zip.write(Json.encodeToString(payload).encodeToByteArray())
            zip.closeArchiveEntry()
            NativeConversationIndexEntry(
                id = id,
                dataChangedAt = conversation["dataChangedAt"]?.jsonPrimitive?.long ?: 0L,
                entry = entry,
                mediaEntries = mediaEntries.sorted(),
            )
        }
        writeJsonEntry(
            zip,
            NativeBackupFormat.TASKS_ENTRY,
            JsonObject(mapOf("tasks" to JsonArray(tasks))),
        )
        writeJsonEntry(
            zip,
            NativeBackupFormat.CONVERSATION_INDEX_ENTRY,
            Json.encodeToString(NativeConversationIndex(index)),
        )
    }

    private fun collectMediaEntries(element: JsonElement, output: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.values.forEach { collectMediaEntries(it, output) }
            is JsonArray -> element.forEach { collectMediaEntries(it, output) }
            is JsonPrimitive -> if (element.isString) {
                val value = element.content
                if (
                    value.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) ||
                    value.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) ||
                    value.startsWith(NativeBackupFormat.DRAFT_MEDIA_PREFIX)
                ) output += value
            }
        }
    }

    private fun writeJsonEntry(zip: ZipArchiveOutputStream, name: String, value: JsonElement) =
        writeJsonEntry(zip, name, Json.encodeToString(value))

    private fun writeJsonEntry(zip: ZipArchiveOutputStream, name: String, value: String) {
        zip.putArchiveEntry(ZipArchiveEntry(name))
        zip.write(value.encodeToByteArray())
        zip.closeArchiveEntry()
    }
}