package com.newoether.agora.data.localmodel

/**
 * Pure path/URL/header policy for Local Model downloads.
 *
 * Files live under `<storageRoot>/models/<catalogEntryId>/<commitHash>/` so a
 * re-published catalog entry (new commit hash) downloads to a fresh directory
 * and can never append onto a stale partial from a different revision.
 */
object LocalModelDownloadPaths {
    const val PARTIAL_SUFFIX = ".part"
    const val MODELS_DIR = "models"
    const val RANGE_HEADER = "Range"
    const val ACCEPT_ENCODING_HEADER = "Accept-Encoding"
    const val IDENTITY_ENCODING = "identity"

    fun partialFileName(fileName: String): String = "$fileName$PARTIAL_SUFFIX"

    fun finalFileNameFromPartial(fileName: String): String = if (fileName.endsWith(PARTIAL_SUFFIX)) {
        fileName.removeSuffix(PARTIAL_SUFFIX)
    } else {
        fileName
    }

    fun isPartialFile(fileName: String): Boolean = fileName.endsWith(PARTIAL_SUFFIX)

    fun isValidPathSegment(segment: String): Boolean = segment.isNotEmpty() &&
        segment != ".." &&
        '/' !in segment &&
        '\\' !in segment

    fun requireValidPathSegments(vararg segments: String) {
        segments.forEach { segment ->
            require(isValidPathSegment(segment)) { "Invalid Local Model path segment" }
        }
    }

    fun isCompleteDownload(tmpLength: Long, totalBytes: Long): Boolean = totalBytes <= 0L || tmpLength == totalBytes

    fun relativeDirectory(catalogEntryId: String, commitHash: String): String {
        requireValidPathSegments(catalogEntryId, commitHash)
        return listOf(MODELS_DIR, catalogEntryId, commitHash).joinToString("/")
    }

    fun relativeFilePath(catalogEntryId: String, commitHash: String, fileName: String): String {
        requireValidPathSegments(catalogEntryId, commitHash, fileName)
        return listOf(MODELS_DIR, catalogEntryId, commitHash, fileName).joinToString("/")
    }

    fun relativePartialFilePath(catalogEntryId: String, commitHash: String, fileName: String): String =
        relativeFilePath(catalogEntryId, commitHash, partialFileName(fileName))

    fun uniqueWorkName(catalogEntryId: String): String = "local_model_download_$catalogEntryId"

    fun commitHashFromUrl(url: String): String {
        val path = url.substringBefore('?')
        val parts = path.split('/')
        val resolveIndex = parts.indexOf("resolve")
        if (resolveIndex >= 0 && resolveIndex + 1 < parts.size) {
            return parts[resolveIndex + 1]
        }
        return ""
    }

    fun fileNameFromUrl(url: String): String {
        val path = url.substringBefore('?').trimEnd('/')
        return path.substringAfterLast('/')
    }

    fun rangeHeaderValue(partialLength: Long): String? = if (partialLength > 0L) {
        "bytes=$partialLength-"
    } else {
        null
    }

    fun resumeHeaders(partialLength: Long): Map<String, String> {
        val range = rangeHeaderValue(partialLength) ?: return emptyMap()
        return mapOf(
            RANGE_HEADER to range,
            ACCEPT_ENCODING_HEADER to IDENTITY_ENCODING
        )
    }

    fun shouldAppendToPartial(partialLength: Long, contentRangeHeader: String?): Boolean {
        if (partialLength <= 0L || contentRangeHeader == null) return false
        return contentRangeStart(contentRangeHeader) == partialLength
    }

    fun downloadedBytesAfterConnect(partialLength: Long, contentRangeHeader: String?): Long {
        if (contentRangeHeader == null) return 0L
        return contentRangeStart(contentRangeHeader) ?: partialLength
    }

    fun contentRangeStart(contentRangeHeader: String): Long? {
        val afterBytes = contentRangeHeader.substringAfter("bytes ", missingDelimiterValue = "")
        if (afterBytes.isEmpty()) return null
        return afterBytes.substringBefore('-').toLongOrNull()
    }

    /** Total size declared by a Content-Range header — both the 206 form ("bytes 0-99/100") and the 416 wildcard form. */
    fun contentRangeTotal(contentRangeHeader: String?): Long? {
        if (contentRangeHeader == null) return null
        val afterBytes = contentRangeHeader.substringAfter("bytes", missingDelimiterValue = "")
        if (afterBytes.isEmpty()) return null
        val total = afterBytes.trim().substringAfter('/', missingDelimiterValue = "")
        return total.toLongOrNull()?.takeIf { it > 0L }
    }

    fun catalogEntryIdFromRelativePath(relativePath: String): String? {
        val parts = relativePath.split('/')
        return if (parts.size >= 4 && parts[0] == MODELS_DIR) parts[1] else null
    }
}
