package com.threadlog.core.model

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
