package com.newoether.agora.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile

internal class NativeBackupV5Baseline private constructor(
    private val zip: ZipFile,
    val index: NativeConversationIndex,
) : Closeable {
    private val byId = index.conversations.associateBy { it.id }

    fun unchangedConversationIds(current: Map<String, Long>): Set<String> =
        current.mapNotNullTo(linkedSetOf()) { (id, changedAt) ->
            id.takeIf { byId[id]?.dataChangedAt == changedAt }
        }

    fun copyRaw(name: String, output: ZipArchiveOutputStream) {
        val entry = zip.getEntry(name) ?: throw IOException("Missing baseline entry: $name")
        zip.getRawInputStream(entry).use { raw ->
            output.addRawArchiveEntry(ZipArchiveEntry(entry), raw)
        }
    }

    fun indexEntry(id: String): NativeConversationIndexEntry? = byId[id]

    override fun close() = zip.close()

    companion object {
        private const val MAX_METADATA_BYTES = 16L * 1024L * 1024L

        fun openOrNull(file: File?): NativeBackupV5Baseline? {
            if (file?.isFile != true) return null
            return runCatching {
                val zip = ZipFile.builder().setFile(file).get()
                try {
                    val manifest = zip.readJson<BaselineManifest>(NativeBackupFormat.MANIFEST_ENTRY)
                    require(manifest.version == NativeBackupFormat.CURRENT_VERSION)
                    require("conversations" in manifest.categories)
                    val index = zip.readJson<NativeConversationIndex>(
                        NativeBackupFormat.CONVERSATION_INDEX_ENTRY,
                    )
                    require(index.conversations.map { it.id }.toSet().size == index.conversations.size)
                    index.conversations.forEach { item ->
                        require(item.entry == NativeBackupFormat.conversationEntry(item.id))
                        require(zip.getEntry(item.entry) != null)
                        item.mediaEntries.forEach { require(zip.getEntry(it) != null) }
                    }
                    NativeBackupV5Baseline(zip, index)
                } catch (error: Throwable) {
                    zip.close()
                    throw error
                }
            }.getOrNull()
        }

        private inline fun <reified T> ZipFile.readJson(name: String): T {
            val entry = getEntry(name) ?: throw IOException("Missing baseline entry: $name")
            require(entry.size in 0..MAX_METADATA_BYTES)
            val text = getInputStream(entry).bufferedReader().use { it.readText() }
            require(text.encodeToByteArray().size <= MAX_METADATA_BYTES)
            return Json.decodeFromString(text)
        }
    }
}

@Serializable
internal data class BaselineManifest(
    @SerialName("agora_export_version") val version: Int,
    val categories: List<String>,
)