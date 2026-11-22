// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import java.util.Locale

enum class CredentialKind(val label: String) {
    PASSWORD("password"),
    PIN("PIN"),
}

data class DetectedCredential(
    val kind: CredentialKind,
    val keyword: String,
    /** Character range of the secret value inside the original text (excluding surrounding quotes/trailing punctuation). */
    val valueRange: IntRange,
)

sealed interface CredentialCheckResult {
    data object Allowed : CredentialCheckResult

    data class Rejected(
        val detections: List<DetectedCredential>,
    ) : CredentialCheckResult {
        val message: String
            get() {
                val kinds = detections.map { it.kind }.distinct()
                val label = when {
                    kinds.size > 1 -> "passwords or PINs"
                    kinds.firstOrNull() == CredentialKind.PIN -> "PINs"
                    else -> "passwords"
                }
                return "Blocked: notes cannot contain $label. Replace with xxxx to save."
            }
    }
}

class SensitiveCredentialException(
    val detections: List<DetectedCredential>,
    message: String,
) : IllegalArgumentException(message)

/**
 * Prevents accidentally saving plaintext passwords, passcodes, or PINs in journal entries while
 * allowing masked `xxxx` (4+ `x`s, case-insensitive) placeholders.
 *
 * Intentionally excludes `pwd` so Unix `pwd` command notes are never blocked.
 */
object SensitiveCredentialGuard {

    private enum class KeywordGroup(val kind: CredentialKind) {
        PASSWORD_STRICT(CredentialKind.PASSWORD),
        PASSWORD_WORD(CredentialKind.PASSWORD),
        PIN_EXPLICIT(CredentialKind.PIN),
        PIN_BARE(CredentialKind.PIN),
    }

    private enum class SeparatorKind {
        EXPLICIT,
        CONNECTOR,
        BARE_SPACE,
    }

    // Matches keyword + separator + value on a single line (no newline crossing).
    // Excludes `pwd` intentionally (Unix print working directory command).
    private val CANDIDATE_REGEX = Regex(
        pattern = """(?i)(?<![A-Za-z0-9_])(?<kw>pass(?:word|wd|code|phrase)|p/w|pw|pin[ \t]*code|pincode|pin[ \t]*#|pin)(?![A-Za-z0-9_])""" +
            """(?:""" +
            """(?<sepExplicit>[ \t]*(?::|=|->|=>|[ \t]+[-–—][ \t]+)[ \t]*)""" +
            """|(?<sepConn>[ \t]+(?:is|was|(?:set|changed|updated)[ \t]+to)[ \t]+)""" +
            """|(?<sepSpace>[ \t]+)""" +
            """)""" +
            """(?<val>"[^"\r\n]+"|'[^'\r\n]+'|`[^`\r\n]+`|\S+)""",
    )

    private val MASKED_X_REGEX = Regex("^[xX]{4,}$")
    private val SHORT_X_REGEX = Regex("^[xX]{1,3}$")

    private val STATUS_STOPWORDS = setOf(
        "not", "expired", "locked", "blocked", "reset", "changed", "updated",
        "required", "needed", "missing", "forgotten", "lost", "wrong", "incorrect",
        "invalid", "unknown", "blank", "empty", "broken", "disabled", "enabled",
        "optional", "working", "fine", "ok", "okay", "saved", "stored", "safe",
        "strong", "weak", "old", "new", "same", "different", "too", "very", "so",
        "still", "already", "now", "in", "on", "at", "for", "to", "from", "with",
        "the", "a", "an", "my", "our", "your", "their", "here", "there", "no", "none",
    )

    private val PROSE_STOPWORDS = STATUS_STOPWORDS + setOf(
        "change", "update", "manager", "recovery", "forgot", "of", "and", "or",
        "issue", "problem", "help", "today", "tomorrow", "yesterday", "link",
        "email", "app", "prompt", "screen", "policy", "check", "file", "entry",
        "note", "please", "again", "later", "soon", "info", "hint", "reminder",
        "is", "was", "are", "set", "settings", "resetting", "changing", "updating",
        "pad", "number", "code", "login", "account", "vault", "safe", "sync",
        "works", "failed", "failing", "error", "alert", "warning", "security",
    )

    fun validate(text: String): CredentialCheckResult {
        if (text.isBlank()) return CredentialCheckResult.Allowed

        val detections = mutableListOf<DetectedCredential>()
        for (match in CANDIDATE_REGEX.findAll(text)) {
            val kwGroup = match.groups["kw"] ?: continue
            val valGroup = match.groups["val"] ?: continue
            val rawKeyword = kwGroup.value
            val keywordGroup = classifyKeyword(rawKeyword)
            val separatorKind = when {
                match.groups["sepExplicit"] != null -> SeparatorKind.EXPLICIT
                match.groups["sepConn"] != null -> SeparatorKind.CONNECTOR
                else -> SeparatorKind.BARE_SPACE
            }

            // Inline tags like `#password` or `+pin` followed by ordinary prose should not trigger
            // on bare space, while explicit separators (`#password: hunter2`) still trigger.
            val precededByTagPrefix = kwGroup.range.first > 0 &&
                text[kwGroup.range.first - 1] in charArrayOf('#', '@', '+')
            if (precededByTagPrefix && separatorKind == SeparatorKind.BARE_SPACE) {
                continue
            }

            val rawValue = valGroup.value
            val isQuoted = isWrappedInQuotes(rawValue)
            val innerRange = trimValueRange(text, valGroup.range, isQuoted)
            if (innerRange.isEmpty()) continue
            val cleaned = text.substring(innerRange)
            if (cleaned.isEmpty()) continue

            // Always allow masked placeholder `xxxx` (4+ x's, case-insensitive).
            if (MASKED_X_REGEX.matches(cleaned)) continue

            // Strip a leading `#` from PIN values like `pin #4829` for content inspection
            // (valueRange still masks the digits/token cleanly).
            val inspected = if (
                (keywordGroup == KeywordGroup.PIN_BARE || keywordGroup == KeywordGroup.PIN_EXPLICIT) &&
                cleaned.startsWith('#') &&
                cleaned.length > 1
            ) {
                cleaned.substring(1)
            } else {
                cleaned
            }
            if (MASKED_X_REGEX.matches(inspected)) continue

            val connectorText = match.groups["sepConn"]?.value?.trim()?.lowercase(Locale.ROOT)
            val shouldReject = shouldRejectCandidate(
                keywordGroup = keywordGroup,
                separatorKind = separatorKind,
                connectorText = connectorText,
                cleanedValue = inspected,
                isQuoted = isQuoted,
            )

            if (shouldReject) {
                val maskRange = if (inspected !== cleaned) {
                    (innerRange.first + 1)..innerRange.last
                } else {
                    innerRange
                }
                detections += DetectedCredential(
                    kind = keywordGroup.kind,
                    keyword = rawKeyword,
                    valueRange = maskRange,
                )
            }
        }

        return if (detections.isEmpty()) {
            CredentialCheckResult.Allowed
        } else {
            CredentialCheckResult.Rejected(detections)
        }
    }

    /**
     * Throws [SensitiveCredentialException] if [text] contains any unmasked password or PIN.
     */
    fun requireAllowed(text: String) {
        val result = validate(text)
        if (result is CredentialCheckResult.Rejected) {
            throw SensitiveCredentialException(
                detections = result.detections,
                message = result.message,
            )
        }
    }

    /**
     * Replaces every detected unmasked password or PIN value in [text] with `xxxx`.
     */
    fun maskDetectedWithXxxx(text: String): String {
        val result = validate(text)
        if (result !is CredentialCheckResult.Rejected) return text

        val builder = StringBuilder(text)
        // Replace from back to front so earlier indices stay valid.
        result.detections
            .sortedByDescending { it.valueRange.first }
            .forEach { detection ->
                val start = detection.valueRange.first.coerceIn(0, builder.length)
                val endExclusive = (detection.valueRange.last + 1).coerceIn(start, builder.length)
                builder.replace(start, endExclusive, "xxxx")
            }
        return builder.toString()
    }

    private fun classifyKeyword(rawKeyword: String): KeywordGroup {
        val lower = rawKeyword.lowercase(Locale.ROOT).replace(Regex("[ \t]+"), " ").trim()
        return when {
            lower == "pw" || lower == "p/w" || lower == "passwd" -> KeywordGroup.PASSWORD_STRICT
            lower == "password" || lower == "passphrase" -> KeywordGroup.PASSWORD_WORD
            lower == "pin" -> KeywordGroup.PIN_BARE
            else -> KeywordGroup.PIN_EXPLICIT // pincode, pin code, pin#, pin #, passcode
        }
    }

    private fun shouldRejectCandidate(
        keywordGroup: KeywordGroup,
        separatorKind: SeparatorKind,
        connectorText: String?,
        cleanedValue: String,
        isQuoted: Boolean,
    ): Boolean {
        val lower = cleanedValue.lowercase(Locale.ROOT)
        return when (separatorKind) {
            SeparatorKind.EXPLICIT -> true

            SeparatorKind.CONNECTOR -> {
                if (isQuoted) return true
                val isStatusConnector = connectorText == "is" || connectorText == "was"
                if (isStatusConnector && lower in STATUS_STOPWORDS) {
                    false
                } else {
                    true
                }
            }

            SeparatorKind.BARE_SPACE -> {
                if (isQuoted) return true
                if (SHORT_X_REGEX.matches(cleanedValue)) return true
                when (keywordGroup) {
                    KeywordGroup.PIN_BARE -> {
                        // Reject numeric or alphanumeric PIN codes (`pin 4829`, `pin #0451`, `pin 48a9`),
                        // while allowing ordinary prose (`pin this note`, `pin to top`, `safety pin`).
                        cleanedValue.any { it.isDigit() }
                    }

                    KeywordGroup.PIN_EXPLICIT -> {
                        // `passcode`, `pincode`, `pin code`, `pin#`
                        cleanedValue.any { it.isDigit() } || lower !in PROSE_STOPWORDS
                    }

                    KeywordGroup.PASSWORD_STRICT -> {
                        // `pw`, `p/w`, `passwd`
                        lower !in PROSE_STOPWORDS
                    }

                    KeywordGroup.PASSWORD_WORD -> {
                        // `password`, `passphrase`: reject digit/symbol/mixed-case credentials on bare space,
                        // while allowing normal prose (`change password tomorrow`, `password manager`).
                        val hasDigit = cleanedValue.any { it.isDigit() }
                        val hasPasswordSymbol = cleanedValue.any { it in "!@#$%^&*_+=?/~\\|" }
                        val hasMixedCase = cleanedValue.any { it.isUpperCase() } &&
                            cleanedValue.any { it.isLowerCase() } &&
                            !cleanedValue.first().isUpperCase()
                        val hasInternalUpper = cleanedValue.length > 1 &&
                            cleanedValue.drop(1).any { it.isUpperCase() }
                        hasDigit || hasPasswordSymbol || hasMixedCase || hasInternalUpper
                    }
                }
            }
        }
    }

    private fun isWrappedInQuotes(raw: String): Boolean {
        if (raw.length < 2) return false
        val first = raw.first()
        val last = raw.last()
        return (first == '"' && last == '"') ||
            (first == '\'' && last == '\'') ||
            (first == '`' && last == '`')
    }

    private fun trimValueRange(text: String, rawRange: IntRange, isQuoted: Boolean): IntRange {
        var start = rawRange.first
        var end = rawRange.last
        if (isQuoted) {
            start += 1
            end -= 1
            return start..end
        }
        // Strip leading opening brackets/quotes.
        while (start <= end && text[start] in charArrayOf('(', '[', '{', '<', '"', '\'', '`')) {
            start++
        }
        // Strip trailing sentence punctuation & closing brackets.
        while (end >= start && text[end] in charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'', '`')) {
            end--
        }
        return if (start <= end) start..end else rawRange
    }
}
