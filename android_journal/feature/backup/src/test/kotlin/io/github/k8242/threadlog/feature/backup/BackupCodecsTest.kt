// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.Attachment
import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.SheetEntryRow
import io.github.k8242.threadlog.core.model.SheetTagRow
import io.github.k8242.threadlog.core.model.SyncMetaRecord
import io.github.k8242.threadlog.core.model.contract.MediaCompressor
import io.github.k8242.threadlog.core.model.contract.MediaStorage
import io.github.k8242.threadlog.core.model.contract.PendingAttachment
import io.github.k8242.threadlog.core.parser.ContentHasher
import io.github.k8242.threadlog.feature.backup.codec.CsvBackupCodec
import io.github.k8242.threadlog.feature.backup.codec.XlsxBackupCodec
import io.github.k8242.threadlog.feature.backup.codec.ZipJsonMediaCodec
import io.github.k8242.threadlog.feature.backup.sync.WorkbookMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecsTest {

    private val csvCodec = CsvBackupCodec()
    private val xlsxCodec = XlsxBackupCodec()
    private val zipCodec = ZipJsonMediaCodec()

    @Test
    fun csvCodec_roundTripsQuotesCommasMultilineAndSynthesizesTagTree() {
        val original = sampleWorkbook()

        val out = ByteArrayOutputStream()
        csvCodec.write(original, out)

        val decoded = csvCodec.read(ByteArrayInputStream(out.toByteArray()))

        assertThat(decoded.entries).isEqualTo(original.entries)
        assertThat(decoded.tags.map { it.fullPath }).containsAtLeast(
            "people",
            "people/friends",
            "people/friends/Lex",
            "projects",
            "projects/journal",
            "auto",
            "auto/travel",
        )
        assertThat(decoded.meta.entryCount).isEqualTo(original.entries.size)
    }

    @Test
    fun xlsxCodec_roundTripsThreeSheetsIncludingMultilineSpecialCharsAndBlankIdRow() {
        val original = sampleWorkbook()

        val out = ByteArrayOutputStream()
        xlsxCodec.write(original, out)

        val decoded = xlsxCodec.read(ByteArrayInputStream(out.toByteArray()))

        assertThat(decoded.entries).isEqualTo(original.entries)
        assertThat(decoded.tags).isEqualTo(original.tags)
        assertThat(decoded.meta).isEqualTo(original.meta)
    }

    @Test
    fun xlsxCodec_readsExternalExcelSharedStringsWorkbook() {
        // Simulate an Excel-edited workbook that stores strings in xl/sharedStrings.xml and omits empty cells.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            add(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Entries" sheetId="1" r:id="rId1"/>
                    <sheet name="TagTree" sheetId="2" r:id="rId2"/>
                    <sheet name="SyncMeta" sheetId="3" r:id="rId3"/>
                  </sheets>
                </workbook>""",
            )
            add(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
                </Relationships>""",
            )
            add(
                "xl/sharedStrings.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="7" uniqueCount="7">
                  <si><t>entry_id</t></si>
                  <si><t>created_at_iso</t></si>
                  <si><t>zone_id</t></si>
                  <si><t>body_raw</t></si>
                  <si><t>2026-11-20T10:00:00Z</t></si>
                  <si><t>UTC</t></si>
                  <si><t>Added in Excel!</t></si>
                </sst>""",
            )
            // Notice row 2 intentionally omits cell A2 (blank entry_id -> human-added row).
            add(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="s"><v>0</v></c>
                      <c r="B1" t="s"><v>1</v></c>
                      <c r="C1" t="s"><v>2</v></c>
                      <c r="D1" t="s"><v>3</v></c>
                    </row>
                    <row r="2">
                      <c r="B2" t="s"><v>4</v></c>
                      <c r="C2" t="s"><v>5</v></c>
                      <c r="D2" t="s"><v>6</v></c>
                      <c r="L2" t="b"><v>0</v></c>
                    </row>
                  </sheetData>
                </worksheet>""",
            )
            add(
                "xl/worksheets/sheet2.xml",
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>""",
            )
            add(
                "xl/worksheets/sheet3.xml",
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>""",
            )
        }

        val workbook = xlsxCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(workbook.entries).hasSize(1)
        val row = workbook.entries.single()
        assertThat(row.entryId).isEmpty()
        assertThat(row.createdAtIso).isEqualTo("2026-11-20T10:00:00Z")
        assertThat(row.zoneId).isEqualTo("UTC")
        assertThat(row.bodyRaw).isEqualTo("Added in Excel!")
        assertThat(row.deleted).isFalse()
    }

    @Test
    fun zipJsonMediaCodec_roundTripsWorkbookAndVerifiedMedia() = runTest {
        val imageBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x01, 0x02, 0x03, 0x04)
        val imageSha = ContentHasher.sha256Hex(imageBytes)
        val sourceStorage = InMemoryMediaStorage().apply {
            seed("media/img-001.webp", imageBytes)
        }

        val entryWithMedia = EntryWithDetails(
            entry = Entry(
                id = "e-zip-1",
                createdAtMs = 1_763_654_400_000L,
                zoneId = "UTC",
                localDate = "2025-11-20",
                isoWeek = "2025-W47",
                bodyRaw = "Photo archive test",
                bodyPlain = "Photo archive test",
                contentHash = "",
            ),
            tags = emptyList(),
            bookmarks = emptyList(),
            attachments = listOf(
                Attachment(
                    id = "att-1",
                    entryId = "e-zip-1",
                    localFilename = "media/img-001.webp",
                    mimeType = "image/webp",
                    byteSize = imageBytes.size.toLong(),
                    width = 100,
                    height = 100,
                    sha256 = imageSha,
                ),
            ),
        )
        val workbook = WorkbookMapper.toWorkbook(
            entries = listOf(entryWithMedia),
            tags = emptyList(),
            deviceId = "dev-1",
            exportIso = "2026-11-20T12:00:00Z",
        )

        val zipOut = ByteArrayOutputStream()
        zipCodec.write(
            workbook = workbook,
            entries = listOf(entryWithMedia),
            mediaStorage = sourceStorage,
            outputStream = zipOut,
        )

        val targetStorage = InMemoryMediaStorage()
        val payload = zipCodec.read(
            inputStream = ByteArrayInputStream(zipOut.toByteArray()),
            mediaStorage = targetStorage,
        )

        assertThat(payload.workbook).isEqualTo(workbook)
        assertThat(payload.extractedMedia[imageSha]).isNotNull()
        assertThat(payload.extractedMedia["media/img-001.webp"]?.sha256).isEqualTo(imageSha)
        assertThat(targetStorage.readBytes("media/img-001.webp")).isEqualTo(imageBytes)
    }

    @Test
    fun zipJsonMediaCodec_rejectsZipSlipTraversalPaths() {
        val maliciousZip = ByteArrayOutputStream()
        ZipOutputStream(maliciousZip).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
        }

        assertThrows(SecurityException::class.java) {
            kotlinx.coroutines.runBlocking {
                zipCodec.read(
                    inputStream = ByteArrayInputStream(maliciousZip.toByteArray()),
                    mediaStorage = InMemoryMediaStorage(),
                )
            }
        }
    }

    @Test
    fun zipJsonMediaCodec_rejectsOversizedMediaOver640KiB() {
        val oversizedZip = ByteArrayOutputStream()
        ZipOutputStream(oversizedZip).use { zip ->
            zip.putNextEntry(ZipEntry("media/too_big.webp"))
            zip.write(ByteArray(MediaCompressor.MAX_ATTACHMENT_BYTES.toInt() + 1))
            zip.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                zipCodec.read(
                    inputStream = ByteArrayInputStream(oversizedZip.toByteArray()),
                    mediaStorage = InMemoryMediaStorage(),
                )
            }
        }
    }

    @Test
    fun provenanceMetadata_isEmbeddedInSyncMetaXlsxDocPropsAndZipManifest() = runTest {
        val original = sampleWorkbook()

        // 1. SyncMeta rows include generator and upstream provenance columns.
        val metaRows = WorkbookMapper.metaToTableRows(original.meta)
        assertThat(metaRows[0]).containsAtLeast("generator", "upstream")
        assertThat(metaRows[1]).containsAtLeast(
            WorkbookMapper.PROVENANCE_GENERATOR,
            WorkbookMapper.PROVENANCE_UPSTREAM_URL,
        )

        // 2. XLSX output includes OOXML docProps/core.xml and docProps/app.xml with provenance.
        val xlsxOut = ByteArrayOutputStream()
        xlsxCodec.write(original, xlsxOut)
        val xlsxEntries = readZipTextEntries(xlsxOut.toByteArray())
        assertThat(xlsxEntries).containsKey("docProps/core.xml")
        assertThat(xlsxEntries).containsKey("docProps/app.xml")
        assertThat(xlsxEntries["docProps/core.xml"]).contains(WorkbookMapper.PROVENANCE_UPSTREAM_URL)
        assertThat(xlsxEntries["docProps/app.xml"]).contains(WorkbookMapper.PROVENANCE_GENERATOR)

        // 3. ZIP output includes manifest.json with generator, schemaOrigin, upstream, and license.
        val zipOut = ByteArrayOutputStream()
        zipCodec.write(original, emptyList(), InMemoryMediaStorage(), zipOut)
        val zipEntries = readZipTextEntries(zipOut.toByteArray())
        val manifestJson = zipEntries[ZipJsonMediaCodec.MANIFEST_JSON_ENTRY]
        assertThat(manifestJson).isNotNull()
        assertThat(manifestJson).contains(WorkbookMapper.PROVENANCE_GENERATOR)
        assertThat(manifestJson).contains(WorkbookMapper.PROVENANCE_SCHEMA_ORIGIN)
        assertThat(manifestJson).contains(WorkbookMapper.PROVENANCE_UPSTREAM_URL)
        assertThat(manifestJson).contains(WorkbookMapper.PROVENANCE_LICENSE)
    }

    private fun readZipTextEntries(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                result[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }
        return result
    }

    private fun sampleWorkbook(): BackupWorkbook {
        val entries = listOf(
            SheetEntryRow(
                entryId = "01950000-0000-7000-8000-000000000001",
                createdAtIso = "2026-11-20T08:30:00-08:00",
                zoneId = "America/Los_Angeles",
                bodyRaw = "Quoted \"hello, world\" & <xml>\nSecond line with *bold* & 'apostrophe'",
                visibleTags = listOf("people/friends/Lex", "projects/journal"),
                hiddenAutoTags = listOf("auto/travel"),
                rejectedTags = listOf("topics/spam"),
                bookmarksPipe = "https://example.com/a|Title A;;https://example.org/b|",
                mediaDriveIds = listOf("drv-1:deadbeef:image/webp"),
                replyToId = "01950000-0000-7000-8000-000000000000",
                updatedAtIso = "2026-11-20T09:00:00-08:00",
                deleted = false,
            ),
            SheetEntryRow(
                entryId = "",
                createdAtIso = "2026-11-20T10:15:00Z",
                zoneId = "UTC",
                bodyRaw = "Human-added row with blank entry_id",
                visibleTags = emptyList(),
                hiddenAutoTags = emptyList(),
                rejectedTags = emptyList(),
                bookmarksPipe = "",
                mediaDriveIds = emptyList(),
                replyToId = null,
                updatedAtIso = null,
                deleted = true,
            ),
        )
        val tags = listOf(
            SheetTagRow(
                tagId = "t-1",
                fullPath = "people/friends/Lex",
                leafName = "Lex",
                rootCategory = "people",
                colorToken = "people",
                aliases = listOf("Alexis", "L"),
            ),
            SheetTagRow(
                tagId = "t-2",
                fullPath = "projects/journal",
                leafName = "journal",
                rootCategory = "projects",
                colorToken = "projects",
                aliases = emptyList(),
            ),
        )
        val checksum = WorkbookMapper.computeWorkbookChecksum(entries, tags)
        return BackupWorkbook(
            entries = entries,
            tags = tags,
            meta = SyncMetaRecord(
                schemaVersion = 1,
                lastExportIso = "2026-11-20T18:00:00Z",
                deviceId = "pixel-test",
                entryCount = entries.size,
                checksum = checksum,
            ),
        )
    }

    private class InMemoryMediaStorage : MediaStorage {
        private val files = LinkedHashMap<String, ByteArray>()
        private val shaToPath = LinkedHashMap<String, String>()

        fun seed(localFilename: String, bytes: ByteArray) {
            files[localFilename] = bytes
            shaToPath[ContentHasher.sha256Hex(bytes)] = localFilename
        }

        override fun resolveFilePath(localFilename: String): String = "/virtual/$localFilename"

        override suspend fun readBytes(localFilename: String): ByteArray? = files[localFilename]

        override suspend fun writeVerifiedBytes(
            uuid: String,
            mimeType: String,
            bytes: ByteArray,
        ): PendingAttachment {
            require(bytes.size.toLong() <= MediaCompressor.MAX_ATTACHMENT_BYTES) {
                "Exceeds 640 KiB ceiling"
            }
            val ext = if (mimeType == "image/jpeg") "jpg" else "webp"
            val path = "media/$uuid.$ext"
            val sha = ContentHasher.sha256Hex(bytes)
            files[path] = bytes
            shaToPath[sha] = path
            return PendingAttachment(
                localFilename = path,
                mimeType = mimeType,
                byteSize = bytes.size.toLong(),
                width = 100,
                height = 100,
                sha256 = sha,
            )
        }

        override suspend fun findBySha256(sha256: String): String? = shaToPath[sha256.lowercase()]
    }
}
