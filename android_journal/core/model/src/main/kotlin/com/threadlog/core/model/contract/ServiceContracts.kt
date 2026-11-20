package com.threadlog.core.model.contract

import com.threadlog.core.model.AutoTagRule
import com.threadlog.core.model.AutoTagSuggestion
import com.threadlog.core.model.BackupCadence
import com.threadlog.core.model.Bookmark
import com.threadlog.core.model.ConflictResolution
import com.threadlog.core.model.Entry
import com.threadlog.core.model.EntryWithDetails
import com.threadlog.core.model.SyncDiffPreview
import com.threadlog.core.model.Tag
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
