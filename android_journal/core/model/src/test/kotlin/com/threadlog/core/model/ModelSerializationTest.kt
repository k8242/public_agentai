package com.threadlog.core.model

import com.google.common.truth.Truth.assertThat
import com.threadlog.core.model.navigation.ThreadLogRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class ModelSerializationTest {

    private val defaultJson = Json
    private val encodeDefaultsJson = Json { encodeDefaults = true }

    @Test
    fun threadLogRoute_concreteRoundTrip_allEightVariants() {
        for (json in listOf(defaultJson, encodeDefaultsJson)) {
            val chatDefault = ThreadLogRoute.Chat()
            assertThat(json.decodeFromString<ThreadLogRoute.Chat>(json.encodeToString(chatDefault)))
                .isEqualTo(chatDefault)

            val chatWithArgs = ThreadLogRoute.Chat(jumpToEntryId = "id-1", jumpToLocalDate = "2026-11-19")
            assertThat(json.decodeFromString<ThreadLogRoute.Chat>(json.encodeToString(chatWithArgs)))
                .isEqualTo(chatWithArgs)

            assertThat(
                json.decodeFromString<ThreadLogRoute.CompactWeekly>(
                    json.encodeToString(ThreadLogRoute.CompactWeekly),
                ),
            ).isSameInstanceAs(ThreadLogRoute.CompactWeekly)

            assertThat(
                json.decodeFromString<ThreadLogRoute.Calendar>(
                    json.encodeToString(ThreadLogRoute.Calendar),
                ),
            ).isSameInstanceAs(ThreadLogRoute.Calendar)

            val searchDefault = ThreadLogRoute.Search()
            assertThat(json.decodeFromString<ThreadLogRoute.Search>(json.encodeToString(searchDefault)))
                .isEqualTo(searchDefault)

            val searchWithQuery = ThreadLogRoute.Search(initialQuery = "Lex")
            assertThat(json.decodeFromString<ThreadLogRoute.Search>(json.encodeToString(searchWithQuery)))
                .isEqualTo(searchWithQuery)

            val ctxDefault = ThreadLogRoute.ContextWindow(anchorEntryId = "anchor-1")
            assertThat(json.decodeFromString<ThreadLogRoute.ContextWindow>(json.encodeToString(ctxDefault)))
                .isEqualTo(ctxDefault)

            val ctxWithQuery = ThreadLogRoute.ContextWindow(anchorEntryId = "anchor-1", highlightQuery = "q")
            assertThat(json.decodeFromString<ThreadLogRoute.ContextWindow>(json.encodeToString(ctxWithQuery)))
                .isEqualTo(ctxWithQuery)

            assertThat(
                json.decodeFromString<ThreadLogRoute.Backup>(
                    json.encodeToString(ThreadLogRoute.Backup),
                ),
            ).isSameInstanceAs(ThreadLogRoute.Backup)

            assertThat(
                json.decodeFromString<ThreadLogRoute.AutoTagReview>(
                    json.encodeToString(ThreadLogRoute.AutoTagReview),
                ),
            ).isSameInstanceAs(ThreadLogRoute.AutoTagReview)

            assertThat(
                json.decodeFromString<ThreadLogRoute.Settings>(
                    json.encodeToString(ThreadLogRoute.Settings),
                ),
            ).isSameInstanceAs(ThreadLogRoute.Settings)
        }
    }

    @Test
    fun threadLogRoute_polymorphicRoundTrip_allEightVariants() {
        val allRoutes: List<ThreadLogRoute> = listOf(
            ThreadLogRoute.Chat(jumpToEntryId = "e1", jumpToLocalDate = "2026-11-19"),
            ThreadLogRoute.CompactWeekly,
            ThreadLogRoute.Calendar,
            ThreadLogRoute.Search(initialQuery = "running"),
            ThreadLogRoute.ContextWindow(anchorEntryId = "e2", highlightQuery = "Lex"),
            ThreadLogRoute.Backup,
            ThreadLogRoute.AutoTagReview,
            ThreadLogRoute.Settings,
        )

        for (route in allRoutes) {
            val encoded = defaultJson.encodeToString<ThreadLogRoute>(route)
            val decoded = defaultJson.decodeFromString<ThreadLogRoute>(encoded)
            assertThat(decoded).isEqualTo(route)
        }

        val listEncoded = defaultJson.encodeToString<List<ThreadLogRoute>>(allRoutes)
        val listDecoded = defaultJson.decodeFromString<List<ThreadLogRoute>>(listEncoded)
        assertThat(listDecoded).containsExactlyElementsIn(allRoutes).inOrder()
    }

    @Test
    fun backupWorkbook_andSheetModels_roundTrip() {
        val normalRow = SheetEntryRow(
            entryId = "01934b10-0000-7000-8000-000000000001",
            createdAtIso = "2026-11-19T08:15:00-08:00",
            zoneId = "America/Los_Angeles",
            bodyRaw = "Morning run with @Lex",
            visibleTags = listOf("people/friends/Lex", "topics/health/running"),
            hiddenAutoTags = listOf("auto/mood/energized"),
            rejectedTags = listOf("auto/work"),
            bookmarksPipe = "https://example.com/a|Example A;;https://example.org/b|Example B",
            mediaDriveIds = listOf("drive123:sha256abc:image/webp"),
            replyToId = "01934b00-0000-7000-8000-000000000000",
            updatedAtIso = "2026-11-19T08:20:00-08:00",
            deleted = false,
        )
        val humanBlankIdRow = SheetEntryRow(
            entryId = "",
            createdAtIso = "2026-11-20T09:00:00Z",
            zoneId = "UTC",
            bodyRaw = "Added from laptop",
            visibleTags = listOf("projects/new-branch/idea"),
            hiddenAutoTags = emptyList(),
            rejectedTags = emptyList(),
            bookmarksPipe = "",
            mediaDriveIds = emptyList(),
            replyToId = null,
            updatedAtIso = null,
            deleted = false,
        )
        val deletedRow = humanBlankIdRow.copy(
            entryId = "01934b10-0000-7000-8000-000000000009",
            deleted = true,
        )

        val tagWithAliases = SheetTagRow(
            tagId = "tag-1",
            fullPath = "people/friends/Lex",
            leafName = "Lex",
            rootCategory = "people",
            colorToken = "people",
            aliases = listOf("Lexi", "Alexandra"),
        )
        val tagWithoutAliases = SheetTagRow(
            tagId = "tag-2",
            fullPath = "topics/health/running",
            leafName = "running",
            rootCategory = "topics",
            colorToken = "topics",
            aliases = emptyList(),
        )

        val meta = SyncMetaRecord(
            schemaVersion = 1,
            lastExportIso = "2026-11-20T12:00:00Z",
            deviceId = "pixel-9",
            entryCount = 3,
            checksum = "deadbeef",
        )

        val workbook = BackupWorkbook(
            entries = listOf(normalRow, humanBlankIdRow, deletedRow),
            tags = listOf(tagWithAliases, tagWithoutAliases),
            meta = meta,
        )

        assertThat(defaultJson.decodeFromString<BackupWorkbook>(defaultJson.encodeToString(workbook)))
            .isEqualTo(workbook)
        assertThat(defaultJson.decodeFromString<SheetEntryRow>(defaultJson.encodeToString(normalRow)))
            .isEqualTo(normalRow)
        assertThat(defaultJson.decodeFromString<SheetTagRow>(defaultJson.encodeToString(tagWithAliases)))
            .isEqualTo(tagWithAliases)
        assertThat(defaultJson.decodeFromString<SyncMetaRecord>(defaultJson.encodeToString(meta)))
            .isEqualTo(meta)

        // Sparse JSON omitting nullable optional fields (replyToId, updatedAtIso)
        val sparseJson = """
            {
              "entryId": "",
              "createdAtIso": "2026-11-20T09:00:00Z",
              "zoneId": "UTC",
              "bodyRaw": "Added from laptop",
              "visibleTags": ["projects/new-branch/idea"],
              "hiddenAutoTags": [],
              "rejectedTags": [],
              "bookmarksPipe": "",
              "mediaDriveIds": [],
              "deleted": false
            }
        """.trimIndent()
        assertThat(defaultJson.decodeFromString<SheetEntryRow>(sparseJson)).isEqualTo(humanBlankIdRow)
    }

    @Test
    fun coreDomainModels_roundTrip() {
        val defaultEntry = Entry(
            id = "e1",
            createdAtMs = 1_700_000_000_000L,
            zoneId = "America/Los_Angeles",
            localDate = "2026-11-19",
            isoWeek = "2026-W47",
            bodyRaw = "Simple entry",
            bodyPlain = "Simple entry",
            contentHash = "hash1",
        )
        val editedDeletedReplyEntry = defaultEntry.copy(
            id = "e2",
            replyToId = "e1",
            updatedAtMs = 1_700_000_060_000L,
            deletedAtMs = 1_700_000_120_000L,
        )
        assertThat(defaultJson.decodeFromString<Entry>(defaultJson.encodeToString(defaultEntry)))
            .isEqualTo(defaultEntry)
        assertThat(defaultJson.decodeFromString<Entry>(defaultJson.encodeToString(editedDeletedReplyEntry)))
            .isEqualTo(editedDeletedReplyEntry)

        val rootTag = Tag(
            id = "t-root",
            parentId = null,
            fullPath = "people",
            leafName = "people",
            rootCategory = TagRootCategory.PEOPLE,
            colorToken = "people",
        )
        val nestedTag = Tag(
            id = "t-lex",
            parentId = "t-root",
            fullPath = "people/friends/Lex",
            leafName = "Lex",
            rootCategory = TagRootCategory.PEOPLE,
            colorToken = "people",
            aliases = listOf("Lexi"),
        )
        assertThat(defaultJson.decodeFromString<Tag>(defaultJson.encodeToString(rootTag))).isEqualTo(rootTag)
        assertThat(defaultJson.decodeFromString<Tag>(defaultJson.encodeToString(nestedTag))).isEqualTo(nestedTag)

        for (state in TagState.entries) {
            val entryTag = EntryTag(
                entryId = "e1",
                tagId = nestedTag.id,
                tag = nestedTag,
                state = state,
                confidence = 0.92f,
                updatedAtMs = 1_700_000_000_000L,
            )
            assertThat(defaultJson.decodeFromString<EntryTag>(defaultJson.encodeToString(entryTag)))
                .isEqualTo(entryTag)
        }

        val collapsedBookmark = Bookmark(
            id = "b1",
            entryId = "e1",
            urlRaw = "https://example.com/very/long/path?x=1",
            domain = "example.com",
        )
        val expandedBookmark = collapsedBookmark.copy(
            id = "b2",
            title = "Example Page",
            note = "Read later",
            isCollapsed = false,
        )
        assertThat(defaultJson.decodeFromString<Bookmark>(defaultJson.encodeToString(collapsedBookmark)))
            .isEqualTo(collapsedBookmark)
        assertThat(defaultJson.decodeFromString<Bookmark>(defaultJson.encodeToString(expandedBookmark)))
            .isEqualTo(expandedBookmark)

        val localAttachment = Attachment(
            id = "a1",
            entryId = "e1",
            localFilename = "media/a1.webp",
            mimeType = "image/webp",
            byteSize = 120_000L,
            width = 1280,
            height = 720,
            sha256 = "sha256-local",
            driveFileId = null,
        )
        val syncedAttachment = localAttachment.copy(id = "a2", driveFileId = "drive-file-42")
        assertThat(defaultJson.decodeFromString<Attachment>(defaultJson.encodeToString(localAttachment)))
            .isEqualTo(localAttachment)
        assertThat(defaultJson.decodeFromString<Attachment>(defaultJson.encodeToString(syncedAttachment)))
            .isEqualTo(syncedAttachment)

        for (matchType in RuleMatchType.entries) {
            val rule = AutoTagRule(
                id = "r-${matchType.name}",
                pattern = "pattern-${matchType.name}",
                matchType = matchType,
                targetTagPath = "projects/journal-app",
                confidence = 0.88f,
                enabled = matchType != RuleMatchType.REGEX,
            )
            assertThat(defaultJson.decodeFromString<AutoTagRule>(defaultJson.encodeToString(rule)))
                .isEqualTo(rule)
        }

        val defaultSettings = UserSettings()
        val customSettings = UserSettings(
            weekStart = WeekStart.SUNDAY,
            offlineOnlyLinks = true,
            showHiddenAutoTagsInChat = true,
            scheduledBackupCadence = BackupCadence.WEEKLY,
            linkedSpreadsheetId = "sheet-123",
            linkedMediaFolderId = "folder-456",
            lastExportIso = "2026-11-19T08:00:00Z",
            deviceId = "dev-xyz",
        )
        assertThat(defaultJson.decodeFromString<UserSettings>(defaultJson.encodeToString(defaultSettings)))
            .isEqualTo(defaultSettings)
        assertThat(defaultJson.decodeFromString<UserSettings>(defaultJson.encodeToString(customSettings)))
            .isEqualTo(customSettings)
    }

    @Test
    fun serializableEnums_roundTripAllValues() {
        TagRootCategory.entries.forEach { value ->
            assertThat(defaultJson.decodeFromString<TagRootCategory>(defaultJson.encodeToString(value)))
                .isEqualTo(value)
        }
        TagState.entries.forEach { value ->
            assertThat(defaultJson.decodeFromString<TagState>(defaultJson.encodeToString(value)))
                .isEqualTo(value)
        }
        WeekStart.entries.forEach { value ->
            assertThat(defaultJson.decodeFromString<WeekStart>(defaultJson.encodeToString(value)))
                .isEqualTo(value)
        }
        BackupCadence.entries.forEach { value ->
            assertThat(defaultJson.decodeFromString<BackupCadence>(defaultJson.encodeToString(value)))
                .isEqualTo(value)
        }
        BackupFormat.entries.forEach { value ->
            assertThat(defaultJson.decodeFromString<BackupFormat>(defaultJson.encodeToString(value)))
                .isEqualTo(value)
        }
        RuleMatchType.entries.forEach { value ->
            assertThat(defaultJson.decodeFromString<RuleMatchType>(defaultJson.encodeToString(value)))
                .isEqualTo(value)
        }
    }
}
