package com.newoether.agora.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

internal interface NativeGraphEntrySource {
    fun has(name: String): Boolean
    fun bytes(name: String): ByteArray?
    fun stream(name: String): InputStream?
}

internal class NativeConversationGraphSource private constructor(
    private val archive: NativeGraphEntrySource,
    private val legacyEntry: String?,
    private val spool: File?,
) : Closeable {
    private val handedOut = mutableListOf<InputStream>()

    fun open(): InputStream {
        val stream = legacyEntry?.let {
            checkNotNull(archive.stream(it))
        } ?: checkNotNull(spool).inputStream()
        synchronized(handedOut) { handedOut += stream }
        return stream
    }

    override fun close() {
        synchronized(handedOut) {
            handedOut.forEach { stream -> runCatching { stream.close() } }
            handedOut.clear()
        }
        if (spool != null && !spool.delete()) spool.deleteOnExit()
    }

    companion object {
        fun open(archive: NativeGraphEntrySource, version: Int, cacheDir: File): NativeConversationGraphSource {
            if (version < 5) {
                require(archive.has(NativeBackupFormat.CONVERSATIONS_ENTRY))
                return NativeConversationGraphSource(
                    archive, NativeBackupFormat.CONVERSATIONS_ENTRY, null,
                )
            }
            val index = archive.bytes(NativeBackupFormat.CONVERSATION_INDEX_ENTRY)
                ?.decodeToString()
                ?.let { Json.decodeFromString<NativeConversationIndex>(it) }
                ?: error("${NativeBackupFormat.CONVERSATION_INDEX_ENTRY} is missing")
            val spool = File.createTempFile("agora-import-v5-", ".json", cacheDir)
            try {
                val seenIds = mutableSetOf<String>()
                index.conversations.forEach { item ->
                    require(seenIds.add(item.id)) { "Duplicate conversation id in index" }
                    require(item.entry == NativeBackupFormat.conversationEntry(item.id)) {
                        "Index entry ${item.entry} does not match id ${item.id}"
                    }
                }
                JsonWriter(OutputStreamWriter(spool.outputStream(), Charsets.UTF_8)).use { writer ->
                    writer.beginObject()
                    listOf("conversations", "runs", "messages", "loops").forEach { field ->
                        writer.name(field).beginArray()
                        index.conversations.forEach { item ->
                            require(item.entry == NativeBackupFormat.conversationEntry(item.id))
                            archive.stream(item.entry)?.use { copyArrayField(it, field, writer) }
                                ?: error("${item.entry} is missing")
                        }
                        writer.endArray()
                    }
                    writer.name("tasks").beginArray()
                    archive.stream(NativeBackupFormat.TASKS_ENTRY)?.use {
                        copyArrayField(it, "tasks", writer)
                    } ?: error("${NativeBackupFormat.TASKS_ENTRY} is missing")
                    writer.endArray()
                    writer.endObject()
                }
                return NativeConversationGraphSource(archive, null, spool)
            } catch (error: Throwable) {
                spool.delete()
                throw error
            }
        }

        private fun copyArrayField(stream: InputStream, field: String, writer: JsonWriter) {
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == field) {
                        reader.beginArray()
                        while (reader.hasNext()) copyValue(reader, writer)
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        private fun copyValue(reader: JsonReader, writer: JsonWriter) {
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray(); writer.beginArray()
                    while (reader.hasNext()) copyValue(reader, writer)
                    reader.endArray(); writer.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject(); writer.beginObject()
                    while (reader.hasNext()) {
                        writer.name(reader.nextName())
                        copyValue(reader, writer)
                    }
                    reader.endObject(); writer.endObject()
                }
                JsonToken.STRING -> writer.value(reader.nextString())
                JsonToken.NUMBER -> {
                    val raw = reader.nextString()
                    val asLong = raw.toLongOrNull()
                    if (asLong != null) writer.value(asLong) else writer.value(raw.toDouble())
                }
                JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
                else -> error("Unexpected JSON token ${reader.peek()}")
            }
        }
    }
}