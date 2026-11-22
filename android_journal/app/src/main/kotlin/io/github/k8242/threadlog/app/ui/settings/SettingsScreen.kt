// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.k8242.threadlog.app.BuildConfig
import io.github.k8242.threadlog.app.ui.theme.AvailableColorSchemes
import io.github.k8242.threadlog.core.model.AppColorScheme
import io.github.k8242.threadlog.core.model.BackupCadence
import io.github.k8242.threadlog.core.model.ThemeMode
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.WeekStart
import io.github.k8242.threadlog.core.model.contract.BackupScheduler
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backupScheduler: BackupScheduler,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = UserSettings(),
    )

    fun setWeekStart(weekStart: WeekStart) {
        viewModelScope.launch {
            settingsRepository.setWeekStart(weekStart)
        }
    }

    fun setOfflineOnlyLinks(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOfflineOnlyLinks(enabled)
        }
    }

    fun setShowHiddenAutoTagsInChat(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowHiddenAutoTagsInChat(show)
        }
    }

    fun setScheduledBackupCadence(cadence: BackupCadence) {
        viewModelScope.launch {
            settingsRepository.setScheduledBackupCadence(cadence)
            backupScheduler.updateSchedule(cadence)
        }
    }

    fun setColorScheme(colorScheme: AppColorScheme) {
        viewModelScope.launch {
            settingsRepository.setColorScheme(colorScheme)
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(themeMode)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val userSettings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val signatureStatus = remember(context) { BuildProvenance.inspectCurrentBuild(context) }
    val bundledLicenseText = remember(context) {
        BuildProvenance.readBundledLicenseAndNotice(context)
    }
    var showLicenseDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Appearance & Color Scheme",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = userSettings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AvailableColorSchemes.forEach { option ->
                    val isSelected = userSettings.colorScheme == option.scheme
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setColorScheme(option.scheme) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(option.previewColor)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                        },
                        label = { Text(option.label) },
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Calendar & Week Start",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = userSettings.weekStart == WeekStart.MONDAY,
                    onClick = { viewModel.setWeekStart(WeekStart.MONDAY) },
                    label = { Text("Monday (ISO)") },
                )
                FilterChip(
                    selected = userSettings.weekStart == WeekStart.SUNDAY,
                    onClick = { viewModel.setWeekStart(WeekStart.SUNDAY) },
                    label = { Text("Sunday") },
                )
            }

            HorizontalDivider()

            SettingsToggleRow(
                title = "Offline-only links",
                subtitle = "Disable background page title fetching for URLs in journal entries.",
                checked = userSettings.offlineOnlyLinks,
                onCheckedChange = viewModel::setOfflineOnlyLinks,
            )

            SettingsToggleRow(
                title = "Show hidden auto-tags in Chat",
                subtitle = "Surface unreviewed offline auto-tag suggestions inline beneath bubbles.",
                checked = userSettings.showHiddenAutoTagsInChat,
                onCheckedChange = viewModel::setShowHiddenAutoTagsInChat,
            )

            HorizontalDivider()

            Text(
                text = "Scheduled Backup Cadence",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupCadence.entries.forEach { cadence ->
                    FilterChip(
                        selected = userSettings.scheduledBackupCadence == cadence,
                        onClick = { viewModel.setScheduledBackupCadence(cadence) },
                        label = {
                            Text(
                                when (cadence) {
                                    BackupCadence.OFF -> "Off"
                                    BackupCadence.DAILY -> "Daily"
                                    BackupCadence.WEEKLY -> "Weekly"
                                },
                            )
                        },
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "About & Open Source License",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "ThreadLog v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Copyright © 2026 k8242 · MIT License",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Package: ${BuildConfig.APPLICATION_ID}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Build: ${signatureStatus.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showLicenseDialog = true }) {
                    Text("MIT License")
                }
                OutlinedButton(
                    onClick = {
                        runCatching { uriHandler.openUri(BuildConfig.UPSTREAM_REPO_URL) }
                    },
                ) {
                    Text("Source Repo")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (userSettings.deviceId.isNotBlank()) {
                Text(
                    text = "Device ID: ${userSettings.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("ThreadLog — MIT License") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = bundledLicenseText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
