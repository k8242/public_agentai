// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.feature.backup.ui

import io.github.k8242.threadlog.core.model.TaskStatus
import io.github.k8242.threadlog.core.parser.WhatsAppDateOrder

data class WhatsAppReviewItem(
    val index: Int,
    val createdAtMs: Long,
    val zoneId: String,
    val sender: String?,
    val rawMessageBody: String,
    val editedBody: String? = null,
    val selected: Boolean = true,
    val isMediaOmitted: Boolean = false,
    val isSystemMessage: Boolean = false,
    val isDuplicate: Boolean = false,
    val taskStatus: TaskStatus = TaskStatus.NONE,
    val credentialWarning: String? = null,
) {
    fun resolvedBody(includeSenderName: Boolean): String =
        editedBody ?: if (includeSenderName && !sender.isNullOrBlank()) {
            "$sender: $rawMessageBody"
        } else {
            rawMessageBody
        }
}

data class WhatsAppImportDraft(
    val rawText: String,
    val sourceLabel: String,
    val zoneId: String,
    val dateOrder: WhatsAppDateOrder = WhatsAppDateOrder.MDY,
    val includeSenderName: Boolean = false,
    val skipMediaOmitted: Boolean = false,
    val dateOrderMismatchCount: Int = 0,
    val items: List<WhatsAppReviewItem> = emptyList(),
) {
    val visibleItems: List<WhatsAppReviewItem>
        get() = if (skipMediaOmitted) {
            items.filterNot { it.isMediaOmitted }
        } else {
            items
        }

    val selectedItems: List<WhatsAppReviewItem>
        get() = visibleItems.filter { it.selected }

    val selectedCount: Int
        get() = selectedItems.size

    val duplicateCount: Int
        get() = visibleItems.count { it.isDuplicate }

    val mediaOmittedCount: Int
        get() = items.count { it.isMediaOmitted }

    val blockedSelectedCount: Int
        get() = selectedItems.count { it.credentialWarning != null }

    val blockedAnyCount: Int
        get() = visibleItems.count { it.credentialWarning != null }
}
