package com.threadlog.feature.views.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHITESPACE_REGEX = Regex("\\s+")
private val DAY_LABEL_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val MONTH_LABEL_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/**
 * Collapses line breaks, tabs, and repeated spaces into single spaces for compact 1-line rows.
 */
internal fun sanitizeSingleLinePreview(raw: String): String =
    raw.replace(WHITESPACE_REGEX, " ").trim()

/**
 * Formats an ISO local date string (`"YYYY-MM-DD"`) into a compact label such as `"Thu, Nov 19"`.
 */
internal fun formatDayLabel(localDate: String): String =
    runCatching {
        LocalDate.parse(localDate, DateTimeFormatter.ISO_LOCAL_DATE).format(DAY_LABEL_FORMATTER)
    }.getOrDefault(localDate)

/**
 * Formats a [YearMonth] into a header label such as `"November 2026"`.
 */
internal fun formatMonthLabel(yearMonth: YearMonth): String =
    yearMonth.format(MONTH_LABEL_FORMATTER)

internal data class TagChipPalette(
    val containerColor: Color,
    val contentColor: Color,
)

internal fun resolveTagChipPalette(
    colorToken: String,
    darkTheme: Boolean,
): TagChipPalette = when (colorToken.lowercase(Locale.ROOT)) {
    "people" -> if (darkTheme) {
        TagChipPalette(containerColor = Color(0xFF1E3A5F), contentColor = Color(0xFFBBD9FF))
    } else {
        TagChipPalette(containerColor = Color(0xFFE3F0FF), contentColor = Color(0xFF0E4A8A))
    }

    "projects" -> if (darkTheme) {
        TagChipPalette(containerColor = Color(0xFF1D4032), contentColor = Color(0xFFB3EDD2))
    } else {
        TagChipPalette(containerColor = Color(0xFFE2F6EC), contentColor = Color(0xFF11593C))
    }

    "topics" -> if (darkTheme) {
        TagChipPalette(containerColor = Color(0xFF3D2C56), contentColor = Color(0xFFE0C8FF))
    } else {
        TagChipPalette(containerColor = Color(0xFFF1E8FF), contentColor = Color(0xFF542891))
    }

    "auto" -> if (darkTheme) {
        TagChipPalette(containerColor = Color(0xFF4B3719), contentColor = Color(0xFFFFDFAA))
    } else {
        TagChipPalette(containerColor = Color(0xFFFFF1D6), contentColor = Color(0xFF7A4B00))
    }

    else -> if (darkTheme) {
        TagChipPalette(containerColor = Color(0xFF33373E), contentColor = Color(0xFFD5DAE2))
    } else {
        TagChipPalette(containerColor = Color(0xFFECEFF3), contentColor = Color(0xFF374151))
    }
}

@Composable
internal fun LeafTagChip(
    label: String,
    colorToken: String,
    modifier: Modifier = Modifier,
) {
    val palette = resolveTagChipPalette(
        colorToken = colorToken,
        darkTheme = isSystemInDarkTheme(),
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = palette.containerColor,
        contentColor = palette.contentColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun BookmarkDomainPill(
    domain: String,
    title: String?,
    modifier: Modifier = Modifier,
) {
    val label = if (!title.isNullOrBlank()) {
        "$domain \u00B7 ${title.trim()}"
    } else {
        domain
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
