package com.threadlog.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.threadlog.core.model.BackupCadence
import com.threadlog.core.model.UserSettings
import com.threadlog.core.model.WeekStart
import com.threadlog.core.model.contract.BackupScheduler
import com.threadlog.core.model.contract.SettingsRepository
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val userSettings by viewModel.settings.collectAsStateWithLifecycle()

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
