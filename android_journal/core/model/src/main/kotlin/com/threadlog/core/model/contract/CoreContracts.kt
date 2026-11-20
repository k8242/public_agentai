package com.threadlog.core.model.contract

import com.threadlog.core.model.Tag
import com.threadlog.core.model.WeekStart
import com.threadlog.core.model.parser.ActiveTagTrigger
import com.threadlog.core.model.parser.DateDividerMeta
import com.threadlog.core.model.parser.ParsedMessage

interface MessageParser {
    fun parse(bodyRaw: String): ParsedMessage
    fun detectTagTrigger(text: String, cursorPosition: Int): ActiveTagTrigger?
}

interface LeafDisambiguator {
    /** Maps each [Tag.id] to its minimal display label ("Lex", "Alex (work)", "Alex (a/x)"). */
    fun computeDisplayLabels(allActiveTags: List<Tag>): Map<String, String>
}

interface DateWeekCalculator {
    fun localDateAndIsoWeek(epochMs: Long, zoneId: String): Pair<String, String>
    fun dividerMeta(epochMs: Long, zoneId: String, weekStart: WeekStart): DateDividerMeta
    fun weekKeyForLocalDate(localDate: String, weekStart: WeekStart): String
    fun weekLabelForLocalDate(localDate: String, weekStart: WeekStart): String
}

data class PendingAttachment(
    val localFilename: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
)

interface MediaCompressor {
    companion object {
        const val MAX_ATTACHMENT_BYTES: Long = 655_360L // 640 KiB hard ceiling
    }
    suspend fun compressAndStore(rawBytes: ByteArray): Result<PendingAttachment>
}

interface MediaStorage {
    fun resolveFilePath(localFilename: String): String
    suspend fun readBytes(localFilename: String): ByteArray?
    suspend fun writeVerifiedBytes(uuid: String, mimeType: String, bytes: ByteArray): PendingAttachment
    suspend fun findBySha256(sha256: String): String?
}
