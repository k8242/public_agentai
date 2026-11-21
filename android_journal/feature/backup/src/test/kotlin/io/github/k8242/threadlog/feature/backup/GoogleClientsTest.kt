// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.core.model.SheetEntryRow
import io.github.k8242.threadlog.core.model.SheetTagRow
import io.github.k8242.threadlog.core.model.SyncMetaRecord
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import io.github.k8242.threadlog.feature.backup.cloud.GoogleDriveClient
import io.github.k8242.threadlog.feature.backup.cloud.GoogleSheetsClient
import io.github.k8242.threadlog.feature.backup.sync.WorkbookMapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class GoogleClientsTest {

    private lateinit var server: MockWebServer
    private lateinit var sheetsClient: GoogleSheetsClient
    private lateinit var driveClient: GoogleDriveClient

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString()
        val okHttp = OkHttpClient()
        sheetsClient = GoogleSheetsClient(okHttpClient = okHttp, json = json, baseUrl = baseUrl)
        driveClient = GoogleDriveClient(okHttpClient = okHttp, json = json, baseUrl = baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun googleSheetsClient_createWriteAndReadThreeTabWorkbook() = runTest {
        // 1. createWorkbookSpreadsheet
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"spreadsheetId": "sheet-abc-123"}"""),
        )
        val createdId = sheetsClient.createWorkbookSpreadsheet("token-1", "My ThreadLog").getOrThrow()
        assertThat(createdId).isEqualTo("sheet-abc-123")

        val createReq = server.takeRequest()
        assertThat(createReq.method).isEqualTo("POST")
        assertThat(createReq.path).isEqualTo("/v4/spreadsheets")
        assertThat(createReq.getHeader("Authorization")).isEqualTo("Bearer token-1")
        val createBody = createReq.body.readUtf8()
        assertThat(createBody).contains("My ThreadLog")
        assertThat(createBody).contains("Entries")
        assertThat(createBody).contains("TagTree")
        assertThat(createBody).contains("SyncMeta")

        // 2. writeWorkbook (batchClear + batchUpdate)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"clearedRanges":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"totalUpdatedCells":20}"""))

        val workbook = sampleWorkbook()
        val writeResult = sheetsClient.writeWorkbook("token-1", createdId, workbook)
        assertThat(writeResult.isSuccess).isTrue()

        val clearReq = server.takeRequest()
        assertThat(clearReq.path).isEqualTo("/v4/spreadsheets/sheet-abc-123/values:batchClear")
        val updateReq = server.takeRequest()
        assertThat(updateReq.path).isEqualTo("/v4/spreadsheets/sheet-abc-123/values:batchUpdate")
        val updateBody = updateReq.body.readUtf8()
        assertThat(updateBody).contains("\"valueInputOption\":\"RAW\"")
        assertThat(updateBody).contains("Entries!A1")
        assertThat(updateBody).contains("TagTree!A1")
        assertThat(updateBody).contains("SyncMeta!A1")

        // 3. readWorkbook (batchGet)
        val batchGetResponse = """
            {
              "spreadsheetId": "sheet-abc-123",
              "valueRanges": [
                {
                  "range": "'Entries'!A1:L3",
                  "values": [
                    ["entry_id","created_at_iso","zone_id","body_raw","visible_tags","hidden_auto_tags","rejected_tags","bookmarks_pipe","media_drive_ids","reply_to_id","updated_at_iso","deleted"],
                    ["e-1","2026-11-20T08:00:00-08:00","America/Los_Angeles","Hello Sheets","people/Lex","","","https://example.com|Example","drv-9:sha123:image/webp","","","false"],
                    ["","2026-11-20T09:00:00Z","UTC","Human added row","","","","","","","2026-11-20T09:05:00Z","false"]
                  ]
                },
                {
                  "range": "'TagTree'!A1:F2",
                  "values": [
                    ["tag_id","full_path","leaf_name","root_category","color_token","aliases"],
                    ["t-1","people/Lex","Lex","people","people","Alexis"]
                  ]
                },
                {
                  "range": "'SyncMeta'!A1:E2",
                  "values": [
                    ["schema_version","last_export_iso","device_id","entry_count","checksum"],
                    ["1","2026-11-20T12:00:00Z","dev-1","2","abc999"]
                  ]
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(batchGetResponse))

        val readWorkbook = sheetsClient.readWorkbook("token-1", createdId).getOrThrow()
        val getReq = server.takeRequest()
        assertThat(getReq.method).isEqualTo("GET")
        assertThat(getReq.path).contains("/v4/spreadsheets/sheet-abc-123/values:batchGet")
        assertThat(readWorkbook.entries).hasSize(2)
        assertThat(readWorkbook.entries[0].entryId).isEqualTo("e-1")
        assertThat(readWorkbook.entries[0].visibleTags).containsExactly("people/Lex")
        assertThat(readWorkbook.entries[1].entryId).isEmpty()
        assertThat(readWorkbook.entries[1].bodyRaw).isEqualTo("Human added row")
        assertThat(readWorkbook.tags).hasSize(1)
        assertThat(readWorkbook.tags.single().aliases).containsExactly("Alexis")
        assertThat(readWorkbook.meta.deviceId).isEqualTo("dev-1")
        assertThat(readWorkbook.meta.checksum).isEqualTo("abc999")
    }

    @Test
    fun googleDriveClient_createFolderMultipartUploadAndDownloadWithSizeCeiling() = runTest {
        // 1. ensureMediaFolder when no existing folder -> creates ThreadLog_Media
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder-42"}"""))
        val folderId = driveClient.ensureMediaFolder("tok-drive", null).getOrThrow()
        assertThat(folderId).isEqualTo("folder-42")

        val folderReq = server.takeRequest()
        assertThat(folderReq.method).isEqualTo("POST")
        assertThat(folderReq.path).isEqualTo("/drive/v3/files?fields=id")
        val folderBody = folderReq.body.readUtf8()
        assertThat(folderBody).contains(GoogleDriveClient.MEDIA_FOLDER_NAME)
        assertThat(folderBody).contains("application/vnd.google-apps.folder")

        // 2. ensureMediaFolder when existing folder is valid -> reuses it
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder-42","trashed":false}"""))
        val verifiedId = driveClient.ensureMediaFolder("tok-drive", "folder-42").getOrThrow()
        assertThat(verifiedId).isEqualTo("folder-42")
        val verifyReq = server.takeRequest()
        assertThat(verifyReq.method).isEqualTo("GET")
        assertThat(verifyReq.requestUrl?.encodedPath).isEqualTo("/drive/v3/files/folder-42")
        assertThat(verifyReq.requestUrl?.queryParameter("fields")).isEqualTo("id,trashed")

        // 2b. ensureMediaFolder when existing folder is trashed -> recreates folder
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder-old","trashed":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder-new"}"""))
        val recreatedId = driveClient.ensureMediaFolder("tok-drive", "folder-old").getOrThrow()
        assertThat(recreatedId).isEqualTo("folder-new")
        server.takeRequest() // GET verify
        val recreateReq = server.takeRequest() // POST create
        assertThat(recreateReq.method).isEqualTo("POST")

        // 3. uploadMediaFile (multipart/related)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"file-77"}"""))
        val mediaBytes = byteArrayOf(10, 20, 30, 40, 50)
        val uploadedId = driveClient.uploadMediaFile(
            accessToken = "tok-drive",
            folderId = folderId,
            filename = "photo.webp",
            mimeType = "image/webp",
            bytes = mediaBytes,
        ).getOrThrow()
        assertThat(uploadedId).isEqualTo("file-77")

        val uploadReq = server.takeRequest()
        assertThat(uploadReq.method).isEqualTo("POST")
        assertThat(uploadReq.path).isEqualTo("/upload/drive/v3/files?uploadType=multipart&fields=id")
        assertThat(uploadReq.getHeader("Content-Type")).startsWith("multipart/related")
        val uploadPayload = uploadReq.body.readUtf8()
        assertThat(uploadPayload).contains("\"name\":\"photo.webp\"")
        assertThat(uploadPayload).contains("\"parents\":[\"folder-42\"]")

        // 3b. uploadMediaFile > 640 KiB -> rejected locally without HTTP call
        val oversized = ByteArray(MediaCompressor.MAX_ATTACHMENT_BYTES.toInt() + 1) { 1 }
        val oversizedUpload = driveClient.uploadMediaFile(
            accessToken = "tok-drive",
            folderId = folderId,
            filename = "huge.webp",
            mimeType = "image/webp",
            bytes = oversized,
        )
        assertThat(oversizedUpload.isFailure).isTrue()
        assertThat(oversizedUpload.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)

        // 4. downloadMediaFile within 640 KiB ceiling
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(mediaBytes)),
        )
        val downloaded = driveClient.downloadMediaFile("tok-drive", "file-77").getOrThrow()
        assertThat(downloaded).isEqualTo(mediaBytes)
        val dlReq = server.takeRequest()
        assertThat(dlReq.path).isEqualTo("/drive/v3/files/file-77?alt=media")

        // 5. downloadMediaFile > 640 KiB -> rejected!
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(oversized)),
        )
        val oversizedResult = driveClient.downloadMediaFile("tok-drive", "file-too-big")
        assertThat(oversizedResult.isFailure).isTrue()
        assertThat(oversizedResult.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun sampleWorkbook(): BackupWorkbook {
        val entry = SheetEntryRow(
            entryId = "e-1",
            createdAtIso = "2026-11-20T08:00:00-08:00",
            zoneId = "America/Los_Angeles",
            bodyRaw = "Hello Sheets",
            visibleTags = listOf("people/Lex"),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "https://example.com|Example",
            mediaDriveIds = listOf("drv-9:sha123:image/webp"),
            replyToId = null,
            updatedAtIso = null,
            deleted = false,
        )
        val tag = SheetTagRow(
            tagId = "t-1",
            fullPath = "people/Lex",
            leafName = "Lex",
            rootCategory = "people",
            colorToken = "people",
            aliases = listOf("Alexis"),
        )
        return BackupWorkbook(
            entries = listOf(entry),
            tags = listOf(tag),
            meta = SyncMetaRecord(
                schemaVersion = 1,
                lastExportIso = "2026-11-20T12:00:00Z",
                deviceId = "dev-1",
                entryCount = 1,
                checksum = WorkbookMapper.computeWorkbookChecksum(listOf(entry), listOf(tag)),
            ),
        )
    }
}
