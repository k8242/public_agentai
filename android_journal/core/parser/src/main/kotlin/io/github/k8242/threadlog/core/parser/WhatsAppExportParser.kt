// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

enum class WhatsAppDateOrder(val shortLabel: String) {
    MDY("US (M/D/YY)"),
    DMY("Intl (D/M/YY)"),
}

data class WhatsAppParsedMessage(
    val index: Int,
    val createdAtMs: Long,
    val zoneId: String,
    val sender: String?,
    val messageBody: String,
    val isMediaOmitted: Boolean,
    val isSystemMessage: Boolean,
) {
    fun effectiveBody(includeSenderName: Boolean): String =
        if (includeSenderName && !sender.isNullOrBlank()) {
            "$sender: $messageBody"
        } else {
            messageBody
        }
}

data class WhatsAppParseResult(
    val messages: List<WhatsAppParsedMessage>,
    val dateOrder: WhatsAppDateOrder,
    val zoneId: String,
    val dateOrderMismatchCount: Int,
)

/**
 * Parses WhatsApp exported chat text (both Android `M/d/yy, h:mm PM - Sender: ...` and iOS
 * `[M/d/yy, h:mm:ss PM] Sender: ...` formats) into chronologically ordered messages anchored
 * in the user-specified IANA timezone.
 */
object WhatsAppExportParser {

    // Android format: `8/13/22, 2:11\u202FPM - Yutong: message` or 24-hour `13/08/2022, 14:11 - Yutong: message`
    private val ANDROID_HEADER_REGEX = Regex(
        pattern = """^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4}),?[ \t\u00A0\u202F]+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[ \t\u00A0\u202F]*([AaPp][Mm]))?[ \t\u00A0\u202F]*[-–—][ \t\u00A0\u202F]+(.*)$""",
    )

    // iOS format: `[8/13/22, 2:11:05 PM] Yutong: message`
    private val IOS_HEADER_REGEX = Regex(
        pattern = """^\[(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4}),?[ \t\u00A0\u202F]+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[ \t\u00A0\u202F]*([AaPp][Mm]))?\][ \t\u00A0\u202F]+(.*)$""",
    )

    private val MEDIA_OMITTED_REGEX = Regex(
        pattern = """^(?:<media omitted>|media omitted|image omitted|video omitted|audio omitted|sticker omitted|gif omitted|document omitted|<attached:[^>]+>)$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val DIRECTIONAL_MARKS_REGEX = Regex("""[\uFEFF\u200E\u200F\u202A-\u202E]""")

    private data class MutableParsedBuilder(
        val epochMs: Long,
        val sender: String?,
        val body: StringBuilder,
        val isSystemMessage: Boolean,
    )

    fun parse(
        rawText: String,
        zoneId: String = ZoneId.systemDefault().id,
        dateOrder: WhatsAppDateOrder = WhatsAppDateOrder.MDY,
    ): WhatsAppParseResult {
        val resolvedZone = runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }
        val normalizedZoneId = resolvedZone.id
        if (rawText.isBlank()) {
            return WhatsAppParseResult(
                messages = emptyList(),
                dateOrder = dateOrder,
                zoneId = normalizedZoneId,
                dateOrderMismatchCount = 0,
            )
        }

        val lines = rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val builders = mutableListOf<MutableParsedBuilder>()
        var dateOrderMismatchCount = 0
        var lastBaseEpochMs: Long? = null
        var sameMinuteOffsetSec = 0

        for (rawLine in lines) {
            val cleanedForHeader = rawLine.trimStart('\uFEFF', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E')
            val match = ANDROID_HEADER_REGEX.matchEntire(cleanedForHeader)
                ?: IOS_HEADER_REGEX.matchEntire(cleanedForHeader)

            if (match != null) {
                val part1 = match.groupValues[1].toIntOrNull()
                val part2 = match.groupValues[2].toIntOrNull()
                val rawYear = match.groupValues[3].toIntOrNull()
                val rawHour = match.groupValues[4].toIntOrNull()
                val minute = match.groupValues[5].toIntOrNull()
                val explicitSecond = match.groupValues[6].toIntOrNull()
                val amPm = match.groupValues[7].takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
                val payload = match.groupValues[8]

                if (part1 != null && part2 != null && rawYear != null && rawHour != null && minute != null) {
                    val month = if (dateOrder == WhatsAppDateOrder.MDY) part1 else part2
                    val day = if (dateOrder == WhatsAppDateOrder.MDY) part2 else part1
                    val year = if (rawYear < 100) 2000 + rawYear else rawYear

                    if (month > 12 && day in 1..12) {
                        dateOrderMismatchCount++
                    }

                    val hour24 = to24Hour(rawHour, amPm)
                    val dateTime = if (hour24 != null && minute in 0..59) {
                        try {
                            val sec = (explicitSecond ?: 0).coerceIn(0, 59)
                            LocalDateTime.of(
                                LocalDate.of(year, month, day),
                                LocalTime.of(hour24, minute, sec),
                            )
                        } catch (_: DateTimeException) {
                            null
                        }
                    } else {
                        null
                    }

                    if (dateTime != null) {
                        val baseEpochMs = dateTime.atZone(resolvedZone).toInstant().toEpochMilli()
                        val effectiveEpochMs = if (explicitSecond == null) {
                            if (lastBaseEpochMs == baseEpochMs) {
                                sameMinuteOffsetSec++
                            } else {
                                lastBaseEpochMs = baseEpochMs
                                sameMinuteOffsetSec = 0
                            }
                            baseEpochMs + (sameMinuteOffsetSec * 1_000L)
                        } else {
                            lastBaseEpochMs = null
                            sameMinuteOffsetSec = 0
                            baseEpochMs
                        }

                        val (sender, bodyText, isSystem) = splitSenderAndBody(payload)
                        builders += MutableParsedBuilder(
                            epochMs = effectiveEpochMs,
                            sender = sender,
                            body = StringBuilder(bodyText),
                            isSystemMessage = isSystem,
                        )
                        continue
                    }
                }
            }

            // Continuation line of the current multi-line message.
            val current = builders.lastOrNull()
            if (current != null) {
                current.body.append('\n').append(rawLine)
            }
        }

        val parsed = builders.mapIndexed { idx, builder ->
            val trimmedBody = builder.body.toString().trimEnd()
            val cleanedForMediaCheck = DIRECTIONAL_MARKS_REGEX.replace(trimmedBody, "").trim()
            val isMedia = MEDIA_OMITTED_REGEX.matches(cleanedForMediaCheck)
            WhatsAppParsedMessage(
                index = idx,
                createdAtMs = builder.epochMs,
                zoneId = normalizedZoneId,
                sender = builder.sender,
                messageBody = trimmedBody,
                isMediaOmitted = isMedia,
                isSystemMessage = builder.isSystemMessage,
            )
        }

        return WhatsAppParseResult(
            messages = parsed,
            dateOrder = dateOrder,
            zoneId = normalizedZoneId,
            dateOrderMismatchCount = dateOrderMismatchCount,
        )
    }

    private fun to24Hour(rawHour: Int, amPm: String?): Int? = when (amPm) {
        null -> rawHour.takeIf { it in 0..23 }
        "am" -> when (rawHour) {
            12 -> 0
            in 1..11 -> rawHour
            else -> null
        }

        "pm" -> when (rawHour) {
            12 -> 12
            in 1..11 -> rawHour + 12
            else -> null
        }

        else -> null
    }

    private fun splitSenderAndBody(payload: String): Triple<String?, String, Boolean> {
        val colonIdx = payload.indexOf(": ")
        if (colonIdx > 0 && colonIdx <= 80) {
            val candidateSender = payload.substring(0, colonIdx).trim()
            if (
                candidateSender.isNotEmpty() &&
                !candidateSender.contains("://") &&
                !candidateSender.equals("http", ignoreCase = true) &&
                !candidateSender.equals("https", ignoreCase = true)
            ) {
                val body = payload.substring(colonIdx + 2)
                return Triple(candidateSender, body, false)
            }
        }
        // Handle empty body with trailing colon like `Yutong:`
        if (payload.endsWith(":") && payload.length in 2..81 && !payload.contains("://")) {
            val candidateSender = payload.dropLast(1).trim()
            if (candidateSender.isNotEmpty()) {
                return Triple(candidateSender, "", false)
            }
        }
        return Triple(null, payload, true)
    }
}
