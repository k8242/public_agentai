package com.threadlog.core.data.mapper

import com.threadlog.core.data.local.dao.CalendarDayCountProjection
import com.threadlog.core.data.local.dao.CompactEntrySummaryProjection
import com.threadlog.core.data.local.entity.AttachmentEntity
import com.threadlog.core.data.local.entity.AutoTagRuleEntity
import com.threadlog.core.data.local.entity.BookmarkEntity
import com.threadlog.core.data.local.entity.EntryEntity
import com.threadlog.core.data.local.entity.EntryTagEntity
import com.threadlog.core.data.local.entity.TagEntity
import com.threadlog.core.model.Attachment
import com.threadlog.core.model.AutoTagRule
import com.threadlog.core.model.Bookmark
import com.threadlog.core.model.CalendarDayCount
import com.threadlog.core.model.CompactEntrySummary
import com.threadlog.core.model.Entry
import com.threadlog.core.model.EntryTag
import com.threadlog.core.model.RuleMatchType
import com.threadlog.core.model.Tag
import com.threadlog.core.model.TagRootCategory
import com.threadlog.core.model.TagState
import com.threadlog.core.parser.ContentHasher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object TagPathNormalizer {

    /**
     * Normalizes a hierarchical tag path by trimming whitespace/slashes and collapsing repeated
     * slashes (e.g. `" /people//friends/Lex/ "` -> `"people/friends/Lex"`).
     */
    fun normalize(rawPath: String): String =
        rawPath
            .trim()
            .trim('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    /**
     * Escapes SQLite `LIKE` wildcard characters (`\`, `%`, `_`) using `\` as the escape character.
     */
    fun escapeLike(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                if (ch == '\\' || ch == '%' || ch == '_') {
                    append('\\')
                }
                append(ch)
            }
        }
}

internal object AliasesJsonCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(aliases: List<String>): String =
        json.encodeToString(
            aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )

    fun decode(aliasesJson: String): List<String> {
        if (aliasesJson.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<String>>(aliasesJson)
        }.getOrDefault(emptyList())
    }
}

/**
 * Deterministic SHA-256 content hash builder for 3-way sync diffing.
 */
internal object EntryContentHasher {
    fun compute(
        bodyRaw: String,
        replyToId: String?,
        isDeleted: Boolean,
        visibleTagPaths: Collection<String>,
        hiddenTagPaths: Collection<String>,
        rejectedTagPaths: Collection<String>,
        bookmarkSignatures: Collection<String>,
        attachmentSha256s: Collection<String>,
    ): String {
        val canonical = buildString {
            append("v1\n")
            append("body=").append(bodyRaw).append('\n')
            append("reply=").append(replyToId.orEmpty()).append('\n')
            append("deleted=").append(isDeleted).append('\n')
            append("visible=").append(visibleTagPaths.sorted().joinToString(",")).append('\n')
            append("hidden=").append(hiddenTagPaths.sorted().joinToString(",")).append('\n')
            append("rejected=").append(rejectedTagPaths.sorted().joinToString(",")).append('\n')
            append("bookmarks=").append(bookmarkSignatures.sorted().joinToString(";;")).append('\n')
            append("media=").append(attachmentSha256s.sorted().joinToString(","))
        }
        return ContentHasher.sha256Hex(canonical)
    }
}

internal fun EntryEntity.toDomain(): Entry =
    Entry(
        id = id,
        createdAtMs = createdAtMs,
        zoneId = zoneId,
        localDate = localDate,
        isoWeek = isoWeek,
        bodyRaw = bodyRaw,
        bodyPlain = bodyPlain,
        replyToId = replyToId,
        contentHash = contentHash,
        updatedAtMs = updatedAtMs,
        deletedAtMs = deletedAtMs,
    )

internal fun TagEntity.toDomain(): Tag =
    Tag(
        id = id,
        parentId = parentId,
        fullPath = fullPath,
        leafName = leafName,
        rootCategory = runCatching { TagRootCategory.valueOf(rootCategory) }
            .getOrElse { TagRootCategory.fromPath(fullPath) },
        colorToken = colorToken,
        aliases = AliasesJsonCodec.decode(aliasesJson),
    )

internal fun EntryTagEntity.toDomain(tag: Tag): EntryTag =
    EntryTag(
        entryId = entryId,
        tagId = tagId,
        tag = tag,
        state = runCatching { TagState.valueOf(state) }.getOrDefault(TagState.USER),
        confidence = confidence,
        updatedAtMs = updatedAtMs,
    )

internal fun BookmarkEntity.toDomain(): Bookmark =
    Bookmark(
        id = id,
        entryId = entryId,
        urlRaw = urlRaw,
        domain = domain,
        title = title,
        note = note,
        isCollapsed = isCollapsed,
    )

internal fun AttachmentEntity.toDomain(): Attachment =
    Attachment(
        id = id,
        entryId = entryId,
        localFilename = localFilename,
        mimeType = mimeType,
        byteSize = byteSize,
        width = width,
        height = height,
        sha256 = sha256,
        driveFileId = driveFileId,
    )

internal fun AutoTagRuleEntity.toDomain(): AutoTagRule =
    AutoTagRule(
        id = id,
        pattern = pattern,
        matchType = runCatching { RuleMatchType.valueOf(matchType) }.getOrDefault(RuleMatchType.KEYWORD),
        targetTagPath = targetTagPath,
        confidence = confidence,
        enabled = enabled,
    )

internal fun AutoTagRule.toEntity(): AutoTagRuleEntity =
    AutoTagRuleEntity(
        id = id,
        pattern = pattern,
        matchType = matchType.name,
        targetTagPath = targetTagPath,
        confidence = confidence,
        enabled = enabled,
    )

internal fun CompactEntrySummaryProjection.toDomain(): CompactEntrySummary =
    CompactEntrySummary(
        id = id,
        createdAtMs = createdAtMs,
        zoneId = zoneId,
        localDate = localDate,
        preview140 = preview140,
        hasAttachments = hasAttachments,
        hasBookmarks = hasBookmarks,
        hasCodeBlock = hasCodeBlock,
    )

internal fun CalendarDayCountProjection.toDomain(): CalendarDayCount =
    CalendarDayCount(
        localDate = localDate,
        entryCount = entryCount,
    )
