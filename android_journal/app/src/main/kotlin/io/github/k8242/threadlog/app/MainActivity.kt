// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.k8242.threadlog.app.ui.ThreadLogApp
import io.github.k8242.threadlog.app.ui.theme.ThreadLogTheme
import io.github.k8242.threadlog.app.ui.theme.resolveDarkTheme
import io.github.k8242.threadlog.core.model.UserSettings
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val userSettings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = UserSettings(),
            )
            val isDark = resolveDarkTheme(userSettings.themeMode)

            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
                onDispose {}
            }

            ThreadLogTheme(
                appColorScheme = userSettings.colorScheme,
                darkTheme = isDark,
            ) {
                ThreadLogApp()
            }
        }
    }
}
