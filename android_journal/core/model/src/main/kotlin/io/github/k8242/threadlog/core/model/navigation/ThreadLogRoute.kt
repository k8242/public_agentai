// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.model.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface ThreadLogRoute {
    @Serializable
    data class Chat(
        val jumpToEntryId: String? = null,
        val jumpToLocalDate: String? = null,
        val jumpNonce: Long = 0L,
    ) : ThreadLogRoute

    @Serializable
    data object CompactWeekly : ThreadLogRoute

    @Serializable
    data object Calendar : ThreadLogRoute

    @Serializable
    data class Search(
        val initialQuery: String? = null,
    ) : ThreadLogRoute

    @Serializable
    data class ContextWindow(
        val anchorEntryId: String,
        val highlightQuery: String? = null,
    ) : ThreadLogRoute

    @Serializable
    data object Backup : ThreadLogRoute

    @Serializable
    data object AutoTagReview : ThreadLogRoute

    @Serializable
    data object Settings : ThreadLogRoute
}
