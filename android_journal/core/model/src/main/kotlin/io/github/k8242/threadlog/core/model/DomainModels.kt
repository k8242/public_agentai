package io.github.k8242.threadlog.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Entry(
    val id: String,                    // UUID v7 (time-sortable)
    val createdAtMs: Long,             // Immutable original capture epoch ms
    val zoneId: String,                // IANA ZoneId captured at write time (e.g. "America/Los_Angeles")
    val localDate: String,             // "YYYY-MM-DD" anchored in zoneId
    val isoWeek: String,               // "YYYY-Www" e.g. "2026-W47" (ISO Monday-start canonical key)
    val bodyRaw: String,               // Raw WhatsApp-formatted text
    val bodyPlain: String,             // Stripped plain text for FTS5 & 1-line previews
    val replyToId: String? = null,     // Nullable FK to quoted entry
    val contentHash: String,           // SHA-256 hex for 3-way sync diffing
    val updatedAtMs: Long? = null,     // Set on edit; null if never edited
    val deletedAtMs: Long? = null,     // Non-null = soft-deleted tombstone
) {
    val isEdited: Boolean get() = updatedAtMs != null && updatedAtMs > createdAtMs
    val isDeleted: Boolean get() = deletedAtMs != null
}

@Serializable
data class Tag(
    val id: String,
    val parentId: String? = null,
    val fullPath: String,              // Unique normalized path, e.g. "people/friends/Lex"
    val leafName: String,              // e.g. "Lex"
    val rootCategory: TagRootCategory,
    val colorToken: String,            // Semantic token: "people" | "topics" | "projects" | "auto" | "other"
    val aliases: List<String> = emptyList(),
)

@Serializable
data class EntryTag(
    val entryId: String,
    val tagId: String,
    val tag: Tag,
    val state: TagState,
    val confidence: Float = 1.0f,
    val updatedAtMs: Long,
)

@Serializable
data class Bookmark(
    val id: String,
    val entryId: String,
    val urlRaw: String,
    val domain: String,
    val title: String? = null,
    val note: String? = null,
    val isCollapsed: Boolean = true,
)

@Serializable
data class Attachment(
    val id: String,
    val entryId: String,
    val localFilename: String,         // Relative path under filesDir, e.g. "media/<uuid>.webp"
    val mimeType: String,              // "image/webp" or "image/jpeg"
    val byteSize: Long,                // Guaranteed <= 655_360L
    val width: Int,
    val height: Int,
    val sha256: String,
    val driveFileId: String? = null,
)

data class ReplyPreview(
    val entryId: String,
    val snippet: String,
    val createdAtMs: Long,
    val zoneId: String,
    val isDeleted: Boolean,
)

data class EntryWithDetails(
    val entry: Entry,
    val tags: List<EntryTag>,
    val bookmarks: List<Bookmark>,
    val attachments: List<Attachment>,
    val replyPreview: ReplyPreview? = null,
) {
    val visibleTags: List<EntryTag>
        get() = tags.filter { it.state == TagState.USER || it.state == TagState.AUTO_PROMOTED }
    val hiddenAutoTags: List<EntryTag>
        get() = tags.filter { it.state == TagState.AUTO_HIDDEN }
    val rejectedTags: List<EntryTag>
        get() = tags.filter { it.state == TagState.AUTO_REJECTED }
}

/** Fast SQLite projection for Compact Weekly View (`substr(body_plain, 1, 140)`). */
data class CompactEntrySummary(
    val id: String,
    val createdAtMs: Long,
    val zoneId: String,
    val localDate: String,
    val preview140: String,
    val hasAttachments: Boolean,
    val hasBookmarks: Boolean,
    val hasCodeBlock: Boolean,
)

data class CalendarDayCount(
    val localDate: String,             // "YYYY-MM-DD"
    val entryCount: Int,
)

@Serializable
data class AutoTagRule(
    val id: String,
    val pattern: String,               // Keyword, alias, or domain (e.g. "github.com")
    val matchType: RuleMatchType,
    val targetTagPath: String,         // e.g. "projects/journal-app"
    val confidence: Float = 0.85f,
    val enabled: Boolean = true,
)

data class AutoTagSuggestion(
    val tagPath: String,
    val confidence: Float,
    val reason: String,
)

@Serializable
data class UserSettings(
    val weekStart: WeekStart = WeekStart.MONDAY,
    val offlineOnlyLinks: Boolean = false,
    val showHiddenAutoTagsInChat: Boolean = false,
    val scheduledBackupCadence: BackupCadence = BackupCadence.OFF,
    val linkedSpreadsheetId: String? = null,
    val linkedMediaFolderId: String? = null,
    val lastExportIso: String? = null,
    val deviceId: String = "",
)
