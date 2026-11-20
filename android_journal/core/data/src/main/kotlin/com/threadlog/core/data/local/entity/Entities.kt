package com.threadlog.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entries",
    indices = [
        Index(value = ["created_at_ms"]),
        Index(value = ["local_date"]),
        Index(value = ["iso_week"]),
        Index(value = ["deleted_at_ms"]),
    ],
)
data class EntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "zone_id")
    val zoneId: String,
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "iso_week")
    val isoWeek: String,
    @ColumnInfo(name = "body_raw")
    val bodyRaw: String,
    @ColumnInfo(name = "body_plain")
    val bodyPlain: String,
    @ColumnInfo(name = "reply_to_id")
    val replyToId: String? = null,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long? = null,
    @ColumnInfo(name = "deleted_at_ms")
    val deletedAtMs: Long? = null,
)

@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["full_path"], unique = true),
    ],
)
data class TagEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,
    @ColumnInfo(name = "full_path")
    val fullPath: String,
    @ColumnInfo(name = "leaf_name")
    val leafName: String,
    @ColumnInfo(name = "root_category")
    val rootCategory: String,
    @ColumnInfo(name = "color_token")
    val colorToken: String,
    @ColumnInfo(name = "aliases_json")
    val aliasesJson: String = "[]",
)

@Entity(
    tableName = "entry_tags",
    primaryKeys = ["entry_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entry_id"]),
        Index(value = ["tag_id"]),
        Index(value = ["state"]),
    ],
)
data class EntryTagEntity(
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entry_id"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "url_raw")
    val urlRaw: String,
    @ColumnInfo(name = "domain")
    val domain: String,
    @ColumnInfo(name = "title")
    val title: String? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "is_collapsed")
    val isCollapsed: Boolean = true,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entry_id"]),
        Index(value = ["sha256"]),
    ],
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "local_filename")
    val localFilename: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
    @ColumnInfo(name = "width")
    val width: Int,
    @ColumnInfo(name = "height")
    val height: Int,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,
)

@Entity(tableName = "auto_tag_rules")
data class AutoTagRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "pattern")
    val pattern: String,
    @ColumnInfo(name = "match_type")
    val matchType: String,
    @ColumnInfo(name = "target_tag_path")
    val targetTagPath: String,
    @ColumnInfo(name = "confidence")
    val confidence: Float = 0.85f,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
)

@Entity(tableName = "sync_entry_hashes")
data class SyncEntryHashEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "last_synced_content_hash")
    val lastSyncedContentHash: String,
    @ColumnInfo(name = "synced_at_ms")
    val syncedAtMs: Long,
)
