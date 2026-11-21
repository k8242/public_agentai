// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuildProvenanceTest {

    @Test
    fun classifyBuildSignature_returnsOfficialWhenNormalizedFingerprintMatches() {
        val status = BuildProvenance.classifyBuildSignature(
            isDebugBuild = false,
            expectedSha256 = "AA:BB:CC:DD:EE:FF",
            actualSha256s = setOf("aabbccddeeff"),
        )
        assertThat(status).isEqualTo(BuildSignatureStatus.OFFICIAL)
    }

    @Test
    fun classifyBuildSignature_returnsUnofficialWhenExpectedFingerprintDoesNotMatch() {
        val status = BuildProvenance.classifyBuildSignature(
            isDebugBuild = false,
            expectedSha256 = "AA:BB:CC:DD:EE:FF",
            actualSha256s = setOf("112233445566"),
        )
        assertThat(status).isEqualTo(BuildSignatureStatus.UNOFFICIAL)
    }

    @Test
    fun classifyBuildSignature_returnsDebugOrUnverifiedWhenExpectedFingerprintIsBlank() {
        assertThat(
            BuildProvenance.classifyBuildSignature(
                isDebugBuild = true,
                expectedSha256 = "",
                actualSha256s = emptySet(),
            ),
        ).isEqualTo(BuildSignatureStatus.DEBUG)

        assertThat(
            BuildProvenance.classifyBuildSignature(
                isDebugBuild = false,
                expectedSha256 = "   ",
                actualSha256s = emptySet(),
            ),
        ).isEqualTo(BuildSignatureStatus.UNVERIFIED)
    }

    @Test
    fun bundledLicenseAndNoticeAssets_arePackagedAndReadable() {
        val context = RuntimeEnvironment.getApplication()
        val combined = BuildProvenance.readBundledLicenseAndNotice(context)

        assertThat(combined).contains("Copyright (c) 2026 k8242")
        assertThat(combined).contains("Permission is hereby granted, free of charge")
        assertThat(combined).contains("https://github.com/k8242/public_agentai")
        assertThat(combined).contains("io.github.k8242.threadlog")
    }
}
