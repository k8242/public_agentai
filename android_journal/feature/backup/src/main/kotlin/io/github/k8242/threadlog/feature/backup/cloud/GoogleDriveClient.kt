// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup.cloud

import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Pure-OkHttp Google Drive v3 REST client operating strictly under `drive.file` scope
 * for `ThreadLog_Media` folder management and `<= 640 KiB` attachment upload/download.
 */
class GoogleDriveClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun ensureMediaFolder(
        accessToken: String,
        existingFolderId: String?,
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            if (!existingFolderId.isNullOrBlank()) {
                val verifyUrl = normalizedBaseUrl().newBuilder()
                    .addPathSegments("drive/v3/files")
                    .addPathSegment(existingFolderId)
                    .addQueryParameter("fields", "id,trashed")
                    .build()

                val verifyRequest = Request.Builder()
                    .url(verifyUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                val verified = runCatching {
                    okHttpClient.newCall(verifyRequest).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val dto = json.decodeFromString<DriveFileInfoResponse>(
                            response.body?.string().orEmpty(),
                        )
                        if (dto.id.isNotBlank() && !dto.trashed) dto.id else null
                    }
                }.getOrNull()

                if (verified != null) {
                    return@runCatching verified
                }
            }

            // Create a fresh ThreadLog_Media folder under drive.file scope.
            val createUrl = normalizedBaseUrl().newBuilder()
                .addPathSegments("drive/v3/files")
                .addQueryParameter("fields", "id")
                .build()

            val payload = CreateFolderRequest(
                name = MEDIA_FOLDER_NAME,
                mimeType = FOLDER_MIME_TYPE,
            )
            val createRequest = Request.Builder()
                .url(createUrl)
                .header("Authorization", "Bearer $accessToken")
                .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            okHttpClient.newCall(createRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Google Drive create folder failed ${response.code}: ${body.take(300)}")
                }
                val dto = json.decodeFromString<DriveFileInfoResponse>(body)
                dto.id.takeIf { it.isNotBlank() }
                    ?: throw IOException("Google Drive API did not return folder id")
            }
        }
    }

    suspend fun uploadMediaFile(
        accessToken: String,
        folderId: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            require(folderId.isNotBlank()) { "folderId must not be blank" }
            require(filename.isNotBlank()) { "filename must not be blank" }
            require(bytes.isNotEmpty()) { "Media bytes must not be empty" }
            require(bytes.size.toLong() <= MAX_MEDIA_BYTES) {
                "Media upload size ${bytes.size} exceeds 640 KiB ceiling ($MAX_MEDIA_BYTES bytes)"
            }

            val metadataJson = json.encodeToString(
                UploadFileMetadata(
                    name = filename,
                    parents = listOf(folderId),
                ),
            )
            val multipartBody = MultipartBody.Builder()
                .setType(MULTIPART_RELATED)
                .addPart(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
                .addPart(bytes.toRequestBody(mimeType.toMediaType()))
                .build()

            val uploadUrl = normalizedBaseUrl().newBuilder()
                .addPathSegments("upload/drive/v3/files")
                .addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", "id")
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $accessToken")
                .post(multipartBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Google Drive upload failed ${response.code}: ${body.take(300)}")
                }
                val dto = json.decodeFromString<DriveFileInfoResponse>(body)
                dto.id.takeIf { it.isNotBlank() }
                    ?: throw IOException("Google Drive upload response missing file id")
            }
        }
    }

    suspend fun downloadMediaFile(
        accessToken: String,
        driveFileId: String,
    ): Result<ByteArray> = withContext(ioDispatcher) {
        runCatching {
            require(driveFileId.isNotBlank()) { "driveFileId must not be blank" }

            val downloadUrl = normalizedBaseUrl().newBuilder()
                .addPathSegments("drive/v3/files")
                .addPathSegment(driveFileId)
                .addQueryParameter("alt", "media")
                .build()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(300)
                    throw IOException("Google Drive download failed ${response.code}: $errorBody")
                }

                val responseBody = response.body
                    ?: throw IOException("Google Drive download returned empty body")

                val declaredLength = responseBody.contentLength()
                if (declaredLength > MAX_MEDIA_BYTES) {
                    throw IllegalArgumentException(
                        "Remote media size $declaredLength exceeds 640 KiB ceiling ($MAX_MEDIA_BYTES bytes)",
                    )
                }

                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalRead = 0L
                responseBody.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        totalRead += read
                        if (totalRead > MAX_MEDIA_BYTES) {
                            throw IllegalArgumentException(
                                "Remote media stream exceeds 640 KiB ceiling ($MAX_MEDIA_BYTES bytes)",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            }
        }
    }

    private fun normalizedBaseUrl() = baseUrl.trimEnd('/').toHttpUrl()

    @Serializable
    private data class CreateFolderRequest(
        val name: String,
        val mimeType: String,
    )

    @Serializable
    private data class UploadFileMetadata(
        val name: String,
        val parents: List<String>,
    )

    @Serializable
    private data class DriveFileInfoResponse(
        val id: String = "",
        val trashed: Boolean = false,
    )

    companion object {
        const val DEFAULT_BASE_URL: String = "https://www.googleapis.com"
        const val MEDIA_FOLDER_NAME: String = "ThreadLog_Media"
        const val MAX_MEDIA_BYTES: Long = MediaCompressor.MAX_ATTACHMENT_BYTES // 655_360L (640 KiB)

        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()
    }
}
