// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TagRootCategory(val key: String) {
    PEOPLE("people"),
    TOPICS("topics"),
    PROJECTS("projects"),
    AUTO("auto"),
    OTHER("other");

    companion object {
        fun fromPath(fullPath: String): TagRootCategory =
            when (fullPath.trim().trim('/').substringBefore('/').lowercase()) {
                "people" -> PEOPLE
                "topics" -> TOPICS
                "projects" -> PROJECTS
                "auto" -> AUTO
                else -> OTHER
            }
    }
}

@Serializable
enum class TagState {
    USER,
    AUTO_HIDDEN,
    AUTO_PROMOTED,
    AUTO_REJECTED,
}

@Serializable
enum class WeekStart {
    MONDAY,
    SUNDAY,
}

@Serializable
enum class BackupCadence {
    OFF,
    DAILY,
    WEEKLY,
}

@Serializable
enum class BackupFormat {
    GOOGLE_SHEETS,
    CSV,
    XLSX,
    ZIP_JSON_MEDIA,
}

@Serializable
enum class RuleMatchType {
    KEYWORD,
    DOMAIN,
    REGEX,
}

@Serializable
enum class TaskStatus {
    NONE,
    TODO,
    DONE,
}

@Serializable
enum class TodoFilterMode {
    OPEN,
    DONE,
    ALL,
}

@Serializable
enum class TaskPriority(val sortRank: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
}

@Serializable
enum class TodoSortOrder {
    NEWEST,
    PRIORITY,
}

@Serializable
enum class AppColorScheme {
    TEAL,
    WHATSAPP,
    OCEAN,
    SUNSET,
    LAVENDER,
    MONOCHROME,
    DYNAMIC,
}

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class TagSortOrder(val label: String) {
    RECENT("Recent"),
    NAME_ASC("A–Z"),
    MOST_USED("Most used"),
    COLOR("Color"),
}

enum class TagColorPreset(
    val token: String,
    val label: String,
    val swatchArgb: Long,
    val lightContainerArgb: Long,
    val lightOnContainerArgb: Long,
    val darkContainerArgb: Long,
    val darkOnContainerArgb: Long,
) {
    DEFAULT(
        token = "default",
        label = "Default",
        swatchArgb = 0xFF6B7280L,
        lightContainerArgb = 0xFFE5E7EBL,
        lightOnContainerArgb = 0xFF1F2937L,
        darkContainerArgb = 0xFF374151L,
        darkOnContainerArgb = 0xFFE5E7EBL,
    ),
    CORAL(
        token = "coral",
        label = "Coral",
        swatchArgb = 0xFFEA580CL,
        lightContainerArgb = 0xFFFFEDD5L,
        lightOnContainerArgb = 0xFF9A3412L,
        darkContainerArgb = 0xFF7C2D12L,
        darkOnContainerArgb = 0xFFFED7AAL,
    ),
    AMBER(
        token = "amber",
        label = "Amber",
        swatchArgb = 0xFFD97706L,
        lightContainerArgb = 0xFFFEF3C7L,
        lightOnContainerArgb = 0xFF92400EL,
        darkContainerArgb = 0xFF78350FL,
        darkOnContainerArgb = 0xFFFDE68AL,
    ),
    EMERALD(
        token = "emerald",
        label = "Emerald",
        swatchArgb = 0xFF059669L,
        lightContainerArgb = 0xFFD1FAE5L,
        lightOnContainerArgb = 0xFF065F46L,
        darkContainerArgb = 0xFF064E3BL,
        darkOnContainerArgb = 0xFFA7F3D0L,
    ),
    TEAL(
        token = "teal",
        label = "Teal",
        swatchArgb = 0xFF0D9488L,
        lightContainerArgb = 0xFFCCFBF1L,
        lightOnContainerArgb = 0xFF115E59L,
        darkContainerArgb = 0xFF134E4AL,
        darkOnContainerArgb = 0xFF99F6E4L,
    ),
    SKY(
        token = "sky",
        label = "Sky",
        swatchArgb = 0xFF0284C7L,
        lightContainerArgb = 0xFFE0F2FEL,
        lightOnContainerArgb = 0xFF075985L,
        darkContainerArgb = 0xFF0C4A6EL,
        darkOnContainerArgb = 0xFFBAE6FDL,
    ),
    INDIGO(
        token = "indigo",
        label = "Indigo",
        swatchArgb = 0xFF4F46E5L,
        lightContainerArgb = 0xFFE0E7FFL,
        lightOnContainerArgb = 0xFF3730A3L,
        darkContainerArgb = 0xFF312E81L,
        darkOnContainerArgb = 0xFFC7D2FEL,
    ),
    VIOLET(
        token = "violet",
        label = "Violet",
        swatchArgb = 0xFF7C3AEDL,
        lightContainerArgb = 0xFFEDE9FEL,
        lightOnContainerArgb = 0xFF5B21B6L,
        darkContainerArgb = 0xFF4C1D95L,
        darkOnContainerArgb = 0xFFDDD6FEL,
    ),
    PINK(
        token = "pink",
        label = "Pink",
        swatchArgb = 0xFFDB2777L,
        lightContainerArgb = 0xFFFCE7F3L,
        lightOnContainerArgb = 0xFF9D174DL,
        darkContainerArgb = 0xFF831843L,
        darkOnContainerArgb = 0xFFFBCFE8L,
    ),
    ROSE(
        token = "rose",
        label = "Rose",
        swatchArgb = 0xFFE11D48L,
        lightContainerArgb = 0xFFFFE4E6L,
        lightOnContainerArgb = 0xFF9F1239L,
        darkContainerArgb = 0xFF881337L,
        darkOnContainerArgb = 0xFFFECDD3L,
    ),
    SLATE(
        token = "slate",
        label = "Slate",
        swatchArgb = 0xFF475569L,
        lightContainerArgb = 0xFFE2E8F0L,
        lightOnContainerArgb = 0xFF1E293BL,
        darkContainerArgb = 0xFF334155L,
        darkOnContainerArgb = 0xFFCBD5E1L,
    );

    companion object {
        fun fromToken(token: String?): TagColorPreset? {
            val normalized = token?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (normalized == DEFAULT.token) return null
            return entries.firstOrNull { it != DEFAULT && it.token == normalized }
        }
    }
}
