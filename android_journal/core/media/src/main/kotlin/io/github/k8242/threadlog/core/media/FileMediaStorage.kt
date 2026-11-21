// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.media

import android.content.Context
import android.graphics.BitmapFactory
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import io.github.k8242.threadlog.core.model.contract.MediaStorage
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * File-backed implementation of [MediaStorage] storing verified attachments under `<baseDir>/media/`.
 *
 * Provides safe relative path resolution, atomic temp-file writes with `fsync`, and SHA-256
 * content deduplication across both in-memory lookups and cold restarts.
 */
@Singleton
class FileMediaStorage(
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaStorage {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        baseDir = context.filesDir,
        ioDispatcher = Dispatchers.IO,
    )

    private val mediaDir: File = File(baseDir, MEDIA_DIR_NAME)
    private val sha256Index: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    private val writeMutex = Mutex()

    override fun resolveFilePath(localFilename: String): String {
        return resolveValidatedFile(localFilename).canonicalPath
    }

    override suspend fun readBytes(localFilename: String): ByteArray? = withContext(ioDispatcher) {
        val file = resolveValidatedFile(localFilename)
        if (file.exists() && file.isFile) {
            file.readBytes()
        } else {
            null
        }
    }

    override suspend fun writeVerifiedBytes(
        uuid: String,
        mimeType: String,
        bytes: ByteArray,
    ): PendingAttachment = withContext(ioDispatcher) {
        validateWriteInputs(uuid = uuid, mimeType = mimeType, bytes = bytes)

        val sha256 = sha256Hex(bytes)
        val (width, height) = decodeDimensions(bytes)

        writeMutex.withLock {
            val existingFilename = findBySha256Internal(sha256)
            if (existingFilename != null) {
                val existingMimeType = mimeTypeForFilename(existingFilename) ?: mimeType
                return@withLock PendingAttachment(
                    localFilename = existingFilename,
                    mimeType = existingMimeType,
                    byteSize = bytes.size.toLong(),
                    width = width,
                    height = height,
                    sha256 = sha256,
                )
            }

            ensureMediaDirExists()

            val extension = extensionForMimeType(mimeType)
            val localFilename = "$MEDIA_DIR_NAME/$uuid.$extension"
            val targetFile = resolveValidatedFile(localFilename)
            val tempFile = File(mediaDir, "$TEMP_FILE_PREFIX$uuid-${UUID.randomUUID()}")

            try {
                FileOutputStream(tempFile).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                moveAtomically(tempFile = tempFile, targetFile = targetFile)
            } catch (t: Throwable) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                throw t
            }

            sha256Index.entries.removeIf { it.value == localFilename && it.key != sha256 }
            sha256Index[sha256] = localFilename

            PendingAttachment(
                localFilename = localFilename,
                mimeType = mimeType,
                byteSize = bytes.size.toLong(),
                width = width,
                height = height,
                sha256 = sha256,
            )
        }
    }

    override suspend fun findBySha256(sha256: String): String? = withContext(ioDispatcher) {
        findBySha256Internal(sha256)
    }

    private fun findBySha256Internal(sha256: String): String? {
        val normalized = sha256.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) {
            return null
        }

        val cached = sha256Index[normalized]
        if (cached != null) {
            val cachedFile = runCatching { resolveValidatedFile(cached) }.getOrNull()
            if (cachedFile != null && cachedFile.exists() && cachedFile.isFile) {
                return cached
            }
            sha256Index.remove(normalized, cached)
        }

        if (!mediaDir.exists() || !mediaDir.isDirectory) {
            return null
        }

        val candidates = mediaDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()

        for (file in candidates) {
            val relativePath = "$MEDIA_DIR_NAME/${file.name}"
            val fileSha256 = sha256Hex(file)
            sha256Index.putIfAbsent(fileSha256, relativePath)
        }

        return sha256Index[normalized]
    }

    private fun resolveValidatedFile(localFilename: String): File {
        require(localFilename.isNotBlank()) { "localFilename must not be blank" }
        require(!localFilename.contains('\u0000')) { "localFilename contains illegal null character" }
        require(!localFilename.contains('\\')) { "localFilename must use '/' path separators" }
        require(!localFilename.startsWith('/') && !File(localFilename).isAbsolute) {
            "localFilename must be a relative path under '$MEDIA_DIR_NAME/': $localFilename"
        }
        require(localFilename.startsWith("$MEDIA_DIR_NAME/")) {
            "localFilename must reside under '$MEDIA_DIR_NAME/': $localFilename"
        }

        val relativePart = localFilename.removePrefix("$MEDIA_DIR_NAME/")
        require(relativePart.isNotEmpty()) {
            "localFilename must specify a file inside '$MEDIA_DIR_NAME/'"
        }

        val segments = relativePart.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "localFilename contains illegal path segment: $localFilename"
        }

        val canonicalMediaDir = mediaDir.canonicalFile
        val candidate = File(baseDir, localFilename).canonicalFile
        val expectedPrefix = canonicalMediaDir.path + File.separator
        require(candidate.path.startsWith(expectedPrefix)) {
            "localFilename escapes media directory: $localFilename"
        }
        return candidate
    }

    private fun validateWriteInputs(uuid: String, mimeType: String, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Attachment bytes must not be empty" }
        require(bytes.size.toLong() <= MediaCompressor.MAX_ATTACHMENT_BYTES) {
            "Attachment size ${bytes.size} exceeds max limit of ${MediaCompressor.MAX_ATTACHMENT_BYTES} bytes"
        }
        require(isValidUuidToken(uuid)) {
            "Invalid attachment uuid token: '$uuid'"
        }
        require(mimeType == MIME_WEBP || mimeType == MIME_JPEG) {
            "Unsupported attachment mimeType: '$mimeType'"
        }
    }

    private fun isValidUuidToken(uuid: String): Boolean {
        if (uuid.isBlank()) return false
        if (uuid.startsWith('.')) return false
        if (uuid.contains("..")) return false
        if (uuid.contains('/') || uuid.contains('\\') || uuid.contains('\u0000')) return false
        if (uuid.any { it.isWhitespace() }) return false
        return true
    }

    private fun ensureMediaDirExists() {
        if (!mediaDir.exists() && !mediaDir.mkdirs() && !mediaDir.isDirectory) {
            throw IllegalStateException("Failed to create media directory: ${mediaDir.absolutePath}")
        }
        if (!mediaDir.isDirectory) {
            throw IllegalStateException("Media storage path is not a directory: ${mediaDir.absolutePath}")
        }
    }

    private fun moveAtomically(tempFile: File, targetFile: File) {
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun decodeDimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }

    private fun extensionForMimeType(mimeType: String): String = when (mimeType) {
        MIME_WEBP -> EXT_WEBP
        MIME_JPEG -> EXT_JPEG
        else -> throw IllegalArgumentException("Unsupported attachment mimeType: '$mimeType'")
    }

    private fun mimeTypeForFilename(localFilename: String): String? {
        val lower = localFilename.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".$EXT_WEBP") -> MIME_WEBP
            lower.endsWith(".$EXT_JPEG") || lower.endsWith(".jpeg") -> MIME_JPEG
            else -> null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.toLowerHex()
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String {
        val chars = CharArray(size * 2)
        for (i in indices) {
            val value = this[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[value ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(chars)
    }

    companion object {
        const val MEDIA_DIR_NAME: String = "media"
        const val MIME_WEBP: String = "image/webp"
        const val MIME_JPEG: String = "image/jpeg"

        private const val EXT_WEBP: String = "webp"
        private const val EXT_JPEG: String = "jpg"
        private const val TEMP_FILE_PREFIX: String = ".tmp-"
        private const val HEX_CHARS: String = "0123456789abcdef"
    }
}
