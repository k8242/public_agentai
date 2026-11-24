// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.k8242.threadlog.core.model.AppColorScheme
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.ThemeMode
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import io.github.k8242.threadlog.core.parser.UuidV7
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.data
        .onStart { ensureDeviceId() }
        .map { prefs -> prefs.toUserSettings() }
        .distinctUntilChanged()

    override suspend fun getSettings(): UserSettings {
        ensureDeviceId()
        return dataStore.data.first().toUserSettings()
    }

    override suspend fun setWeekStart(weekStart: WeekStart) {
        dataStore.edit { prefs ->
            prefs[KEY_WEEK_START] = weekStart.name
        }
    }

    override suspend fun setOfflineOnlyLinks(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_OFFLINE_ONLY_LINKS] = enabled
        }
    }

    override suspend fun setShowHiddenAutoTagsInChat(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_HIDDEN_AUTO_TAGS] = show
        }
    }

    override suspend fun setShowHiddenPlaceholderMessages(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_HIDDEN_PLACEHOLDERS] = show
        }
    }

    override suspend fun setScheduledBackupCadence(cadence: BackupCadence) {
        dataStore.edit { prefs ->
            prefs[KEY_SCHEDULED_BACKUP_CADENCE] = cadence.name
        }
    }

    override suspend fun setColorScheme(colorScheme: AppColorScheme) {
        dataStore.edit { prefs ->
            prefs[KEY_COLOR_SCHEME] = colorScheme.name
        }
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = themeMode.name
        }
    }

    override suspend fun updateSyncLinks(
        spreadsheetId: String?,
        mediaFolderId: String?,
        lastExportIso: String?,
    ) {
        dataStore.edit { prefs ->
            if (spreadsheetId == null) {
                prefs.remove(KEY_LINKED_SPREADSHEET_ID)
            } else {
                prefs[KEY_LINKED_SPREADSHEET_ID] = spreadsheetId
            }

            if (mediaFolderId == null) {
                prefs.remove(KEY_LINKED_MEDIA_FOLDER_ID)
            } else {
                prefs[KEY_LINKED_MEDIA_FOLDER_ID] = mediaFolderId
            }

            if (lastExportIso == null) {
                prefs.remove(KEY_LAST_EXPORT_ISO)
            } else {
                prefs[KEY_LAST_EXPORT_ISO] = lastExportIso
            }
        }
    }

    private suspend fun ensureDeviceId() {
        val current = dataStore.data.first()[KEY_DEVICE_ID]
        if (!current.isNullOrBlank()) return
        dataStore.edit { prefs ->
            if (prefs[KEY_DEVICE_ID].isNullOrBlank()) {
                prefs[KEY_DEVICE_ID] = UuidV7.generate()
            }
        }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        val weekStart = this[KEY_WEEK_START]
            ?.let { raw -> runCatching { WeekStart.valueOf(raw) }.getOrNull() }
            ?: WeekStart.MONDAY
        val cadence = this[KEY_SCHEDULED_BACKUP_CADENCE]
            ?.let { raw -> runCatching { BackupCadence.valueOf(raw) }.getOrNull() }
            ?: BackupCadence.OFF
        val colorScheme = this[KEY_COLOR_SCHEME]
            ?.let { raw -> runCatching { AppColorScheme.valueOf(raw) }.getOrNull() }
            ?: AppColorScheme.TEAL
        val themeMode = this[KEY_THEME_MODE]
            ?.let { raw -> runCatching { ThemeMode.valueOf(raw) }.getOrNull() }
            ?: ThemeMode.SYSTEM

        return UserSettings(
            weekStart = weekStart,
            offlineOnlyLinks = this[KEY_OFFLINE_ONLY_LINKS] ?: false,
            showHiddenAutoTagsInChat = this[KEY_SHOW_HIDDEN_AUTO_TAGS] ?: false,
            showHiddenPlaceholderMessages = this[KEY_SHOW_HIDDEN_PLACEHOLDERS] ?: false,
            scheduledBackupCadence = cadence,
            linkedSpreadsheetId = this[KEY_LINKED_SPREADSHEET_ID],
            linkedMediaFolderId = this[KEY_LINKED_MEDIA_FOLDER_ID],
            lastExportIso = this[KEY_LAST_EXPORT_ISO],
            deviceId = this[KEY_DEVICE_ID].orEmpty(),
            colorScheme = colorScheme,
            themeMode = themeMode,
        )
    }

    companion object {
        const val SETTINGS_DATASTORE_NAME: String = "threadlog_settings"

        private val KEY_WEEK_START = stringPreferencesKey("week_start")
        private val KEY_OFFLINE_ONLY_LINKS = booleanPreferencesKey("offline_only_links")
        private val KEY_SHOW_HIDDEN_AUTO_TAGS = booleanPreferencesKey("show_hidden_auto_tags_in_chat")
        private val KEY_SHOW_HIDDEN_PLACEHOLDERS = booleanPreferencesKey("show_hidden_placeholder_messages")
        private val KEY_SCHEDULED_BACKUP_CADENCE = stringPreferencesKey("scheduled_backup_cadence")
        private val KEY_LINKED_SPREADSHEET_ID = stringPreferencesKey("linked_spreadsheet_id")
        private val KEY_LINKED_MEDIA_FOLDER_ID = stringPreferencesKey("linked_media_folder_id")
        private val KEY_LAST_EXPORT_ISO = stringPreferencesKey("last_export_iso")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_COLOR_SCHEME = stringPreferencesKey("color_scheme")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
