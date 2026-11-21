// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app

import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSmokeTest {

    @Test
    fun buildConfig_googleWebClientId_defaultsToEmptyWhenUnconfigured() {
        assertThat(BuildConfig.GOOGLE_WEB_CLIENT_ID).isNotNull()
        if (System.getenv("GOOGLE_WEB_CLIENT_ID").isNullOrBlank()) {
            assertThat(BuildConfig.GOOGLE_WEB_CLIENT_ID).isEmpty()
        }
    }

    @Test
    fun application_implementsWorkManagerConfigurationProviderWithHiltWorkerFactory() {
        val app = RuntimeEnvironment.getApplication()
        assertThat(app).isInstanceOf(ThreadLogApplication::class.java)
        assertThat(app).isInstanceOf(Configuration.Provider::class.java)

        val config = (app as Configuration.Provider).workManagerConfiguration
        assertThat(config.workerFactory).isInstanceOf(HiltWorkerFactory::class.java)
    }

    @Test
    fun mainActivity_launchesUnderRobolectricSdk35() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertThat(activity).isNotNull()
        assertThat(activity.isFinishing).isFalse()
        controller.pause().stop().destroy()
    }
}
