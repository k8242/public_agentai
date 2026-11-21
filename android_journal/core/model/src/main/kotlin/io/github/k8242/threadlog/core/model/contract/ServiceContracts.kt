// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.model.contract

import io.github.k8242.threadlog.core.model.AutoTagRule
import io.github.k8242.threadlog.core.model.AutoTagSuggestion
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.Bookmark
import io.github.k8242.threadlog.core.model.ConflictResolution
import io.github.k8242.threadlog.core.model.Entry
import io.github.k8242.threadlog.core.model.EntryWithDetails
import io.github.k8242.threadlog.core.model.SyncDiffPreview
import io.github.k8242.threadlog.core.model.Tag
import kotlinx.coroutines.flow.Flow

/** Pluggable SPI for offline rules (v1) or future on-device/cloud LLM providers. */
interface AutoTagProvider {
    val providerId: String
    suspend fun suggestTags(
        entry: Entry,
        bookmarks: List<Bookmark>,
        allTags: List<Tag>,
        rules: List<AutoTagRule>,
    ): List<AutoTagSuggestion>
}

/** Decouples feature:chat / feature:search from feature:autotag & WorkManager internals. */
interface AutoTagScheduler {
    fun enqueueAutoTag(entryId: String)
}

interface BookmarkTitleScheduler {
    fun enqueueTitleFetch(entryId: String)
}

interface BackupScheduler {
    fun updateSchedule(cadence: BackupCadence)
}

/** Gateway owned by :core:data for atomic backup export & transactional merge restore. */
interface BackupDataGateway {
    suspend fun exportSnapshot(): Pair<List<EntryWithDetails>, List<Tag>>
    suspend fun getLastSyncedContentHashes(): Map<String, String>
    suspend fun markSynced(entryHashes: Map<String, String>, driveFileIdsByAttachmentId: Map<String, String>)
    suspend fun applyMergeRestore(
        diff: SyncDiffPreview,
        resolutions: Map<String, ConflictResolution>,
        downloadedMedia: Map<String, PendingAttachment>,
    )
}

sealed interface GoogleAuthState {
    data object NotConfigured : GoogleAuthState
    data object SignedOut : GoogleAuthState
    data class Connected(val email: String?) : GoogleAuthState
}

interface GoogleAuthProvider {
    fun isConfigured(): Boolean
    fun observeAuthState(): Flow<GoogleAuthState>
    suspend fun getAccessToken(): Result<String>
    suspend fun signOut()
}
