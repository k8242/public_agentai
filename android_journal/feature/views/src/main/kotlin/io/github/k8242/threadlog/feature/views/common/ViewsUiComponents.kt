// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.views.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.k8242.threadlog.core.model.TaskPriority
import io.github.k8242.threadlog.core.model.TaskStatus
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

internal fun priorityTrimColor(priority: TaskPriority, isDark: Boolean): Color? = when (priority) {
    TaskPriority.HIGH -> if (isDark) Color(0xFFFFA726) else Color(0xFFEF6C00)
    TaskPriority.MEDIUM -> if (isDark) Color(0xFF64B5F6) else Color(0xFF1565C0)
    TaskPriority.LOW -> if (isDark) Color(0xFF81D4FA) else Color(0xFF29B6F6)
    TaskPriority.NONE -> null
}

internal fun highlightBubbleColor(isDark: Boolean): Color =
    if (isDark) Color(0xFF3D3518) else Color(0xFFFFF8D6)

internal fun taskPriorityShortLabel(priority: TaskPriority): String? = when (priority) {
    TaskPriority.HIGH -> "HIGH"
    TaskPriority.MEDIUM -> "MED"
    TaskPriority.LOW -> "LOW"
    TaskPriority.NONE -> null
}

@Composable
internal fun TaskStatusBadge(
    taskStatus: TaskStatus,
    taskPriority: TaskPriority = TaskPriority.NONE,
    modifier: Modifier = Modifier,
) {
    if (taskStatus == TaskStatus.NONE) return

    val isDark = isSystemInDarkTheme()
    val isDone = taskStatus == TaskStatus.DONE
    val priorityColor = priorityTrimColor(taskPriority, isDark)
    val priorityTag = taskPriorityShortLabel(taskPriority)
    val statusText = if (isDone) "DONE" else "TODO"
    val label = if (priorityTag != null) "$statusText \u00B7 $priorityTag" else statusText

    val containerColor = when {
        isDone -> MaterialTheme.colorScheme.surfaceVariant
        priorityColor != null -> priorityColor.copy(alpha = if (isDark) 0.22f else 0.14f)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        isDone -> MaterialTheme.colorScheme.onSurfaceVariant
        priorityColor != null -> priorityColor
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val border = if (!isDone && priorityColor != null) {
        BorderStroke(width = 0.75.dp, color = priorityColor.copy(alpha = 0.65f))
    } else {
        null
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (isDone) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = if (isDone) "Completed task" else "Open task",
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun CompactTaskIndicator(
    taskStatus: TaskStatus,
    taskPriority: TaskPriority = TaskPriority.NONE,
    modifier: Modifier = Modifier,
) {
    if (taskStatus == TaskStatus.NONE) return

    val isDark = isSystemInDarkTheme()
    val isDone = taskStatus == TaskStatus.DONE
    val priorityColor = priorityTrimColor(taskPriority, isDark)
    val iconTint = when {
        isDone -> MaterialTheme.colorScheme.onSurfaceVariant
        priorityColor != null -> priorityColor
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (isDone) {
                Icons.Outlined.CheckBox
            } else {
                Icons.Outlined.CheckBoxOutlineBlank
            },
            contentDescription = if (isDone) "Completed TODO" else "Open TODO",
            modifier = Modifier.size(16.dp),
            tint = iconTint,
        )
        if (priorityColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isDone) priorityColor.copy(alpha = 0.55f) else priorityColor),
            )
        }
    }
}

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
