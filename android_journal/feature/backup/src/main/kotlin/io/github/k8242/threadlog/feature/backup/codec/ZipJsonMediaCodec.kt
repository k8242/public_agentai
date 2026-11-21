// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup.codec

import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import io.github.k8242.threadlog.core.model.contract.MediaStorage
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.parser.UuidV7
import io.github.k8242.threadlog.feature.backup.BackupJson
import io.github.k8242.threadlog.feature.backup.sync.WorkbookMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupArchiveManifest(
    val generator: String = WorkbookMapper.PROVENANCE_GENERATOR,
    val schemaOrigin: String = WorkbookMapper.PROVENANCE_SCHEMA_ORIGIN,
    val upstream: String = WorkbookMapper.PROVENANCE_UPSTREAM_URL,
    val license: String = WorkbookMapper.PROVENANCE_LICENSE,
    val schemaVersion: Int = WorkbookMapper.SCHEMA_VERSION,
)

/**
 * Result of reading an offline `.zip` archive containing `workbook.json` and `media/<filename>` files.
 */
data class ZipBackupPayload(
    val workbook: BackupWorkbook,
    val extractedMedia: Map<String, PendingAttachment>,
)

/**
 * Offline `.zip` archive codec bundling `manifest.json` (provenance metadata), `workbook.json`
 * (Kotlinx Serialization [BackupWorkbook]), and compressed media attachments under `media/<filename>`.
 *
 * Enforces strict zip-slip path rejection and the hard `<= 640 KiB` attachment ceiling via
 * [MediaStorage.writeVerifiedBytes] on import.
 */
@Singleton
class ZipJsonMediaCodec @Inject constructor(
    @param:BackupJson private val json: Json,
) {
    /** Convenience constructor for unit tests outside Hilt. */
    constructor() : this(
        json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = true
        },
    )

    suspend fun write(
        workbook: BackupWorkbook,
        entries: List<EntryWithDetails>,
        mediaStorage: MediaStorage,
        outputStream: OutputStream,
    ) {
        val zipOut = ZipOutputStream(outputStream)

        // 1. Write manifest.json with provenance metadata.
        val manifest = BackupArchiveManifest(schemaVersion = workbook.meta.schemaVersion)
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        zipOut.putNextEntry(ZipEntry(MANIFEST_JSON_ENTRY))
        zipOut.write(manifestBytes)
        zipOut.closeEntry()

        // 2. Write workbook.json.
        val workbookJsonBytes = json.encodeToString(workbook).toByteArray(Charsets.UTF_8)
        zipOut.putNextEntry(ZipEntry(WORKBOOK_JSON_ENTRY))
        zipOut.write(workbookJsonBytes)
        zipOut.closeEntry()

        // 3. Write distinct media files under media/<basename>.
        val writtenEntryNames = HashSet<String>()
        for (entry in entries) {
            for (attachment in entry.attachments) {
                val basename = attachment.localFilename.substringAfterLast('/')
                if (basename.isBlank()) continue
                val zipEntryName = "$MEDIA_DIR_PREFIX$basename"
                if (!writtenEntryNames.add(zipEntryName)) continue

                val bytes = mediaStorage.readBytes(attachment.localFilename) ?: continue
                zipOut.putNextEntry(ZipEntry(zipEntryName))
                zipOut.write(bytes)
                zipOut.closeEntry()
            }
        }

        zipOut.finish()
        zipOut.flush()
    }

    suspend fun read(
        inputStream: InputStream,
        mediaStorage: MediaStorage,
    ): ZipBackupPayload {
        val zipIn = ZipInputStream(inputStream)
        var workbook: BackupWorkbook? = null
        val extractedMedia = LinkedHashMap<String, PendingAttachment>()

        while (true) {
            val entry = zipIn.nextEntry ?: break
            val name = entry.name
            validateSafeZipEntryName(name)

            if (!entry.isDirectory) {
                when {
                    name == WORKBOOK_JSON_ENTRY -> {
                        val jsonBytes = readBoundedBytes(zipIn, MAX_WORKBOOK_JSON_BYTES, name)
                        workbook = json.decodeFromString<BackupWorkbook>(jsonBytes.toString(Charsets.UTF_8))
                    }

                    name.startsWith(MEDIA_DIR_PREFIX) -> {
                        val relativeFilename = name.removePrefix(MEDIA_DIR_PREFIX)
                        if (relativeFilename.isEmpty() || relativeFilename.contains('/')) {
                            throw SecurityException("Invalid media entry path in backup archive: $name")
                        }
                        val bytes = readBoundedBytes(
                            input = zipIn,
                            maxBytes = MediaCompressor.MAX_ATTACHMENT_BYTES,
                            entryName = name,
                        )
                        val mimeType = inferSupportedMimeType(relativeFilename)
                            ?: throw IllegalArgumentException("Unsupported media file type in archive: $name")
                        val uuidToken = extractUuidToken(relativeFilename)
                        val pending = mediaStorage.writeVerifiedBytes(
                            uuid = uuidToken,
                            mimeType = mimeType,
                            bytes = bytes,
                        )
                        // Register under multiple keys so BackupDataGateway resolves by sha256,
                        // relative path ("media/foo.webp"), or basename ("foo.webp").
                        extractedMedia[pending.sha256] = pending
                        extractedMedia[name] = pending
                        extractedMedia[relativeFilename] = pending
                    }
                }
            }

            zipIn.closeEntry()
        }

        val parsedWorkbook = workbook
            ?: throw IllegalArgumentException("Backup archive is missing '$WORKBOOK_JSON_ENTRY'")

        return ZipBackupPayload(
            workbook = parsedWorkbook,
            extractedMedia = extractedMedia,
        )
    }

    private fun validateSafeZipEntryName(name: String) {
        if (name.isBlank() ||
            name.contains('\u0000') ||
            name.contains('\\') ||
            name.startsWith('/')
        ) {
            throw SecurityException("Unsafe zip entry path: $name")
        }
        val segments = name.split('/')
        if (segments.any { it == ".." || it == "." }) {
            throw SecurityException("Zip-slip path traversal detected: $name")
        }
    }

    private fun readBoundedBytes(
        input: InputStream,
        maxBytes: Long,
        entryName: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException(
                    "Archive entry '$entryName' exceeds maximum allowed size of $maxBytes bytes",
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun inferSupportedMimeType(filename: String): String? {
        val lower = filename.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> null
        }
    }

    private fun extractUuidToken(filename: String): String {
        val stem = filename.substringBeforeLast('.', filename).trim()
        val sanitized = stem.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return sanitized.takeIf { it.isNotEmpty() && !it.startsWith('.') } ?: UuidV7.generate()
    }

    companion object {
        const val MANIFEST_JSON_ENTRY: String = "manifest.json"
        const val WORKBOOK_JSON_ENTRY: String = "workbook.json"
        const val MEDIA_DIR_PREFIX: String = "media/"
        private const val MAX_WORKBOOK_JSON_BYTES: Long = 50L * 1024L * 1024L // 50 MiB
    }
}
