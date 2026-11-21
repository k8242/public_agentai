// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.k8242.threadlog.app.BuildConfig
import java.security.MessageDigest
import java.util.Locale

enum class BuildSignatureStatus(val label: String) {
    OFFICIAL("Official signed release"),
    DEBUG("Debug build (local / CI)"),
    UNOFFICIAL("Unofficial build (based on ThreadLog)"),
    UNVERIFIED("Community / self-signed build (based on ThreadLog)"),
}

object BuildProvenance {

    fun normalizeSha256(value: String): String = value
        .replace(":", "")
        .replace(" ", "")
        .trim()
        .lowercase(Locale.ROOT)

    fun classifyBuildSignature(
        isDebugBuild: Boolean,
        expectedSha256: String,
        actualSha256s: Set<String>,
    ): BuildSignatureStatus {
        val normalizedExpected = normalizeSha256(expectedSha256)
        if (normalizedExpected.isEmpty()) {
            return if (isDebugBuild) BuildSignatureStatus.DEBUG else BuildSignatureStatus.UNVERIFIED
        }
        val normalizedActual = actualSha256s.map(::normalizeSha256).toSet()
        return if (normalizedExpected in normalizedActual) {
            BuildSignatureStatus.OFFICIAL
        } else {
            BuildSignatureStatus.UNOFFICIAL
        }
    }

    fun inspectCurrentBuild(context: Context): BuildSignatureStatus = classifyBuildSignature(
        isDebugBuild = BuildConfig.DEBUG,
        expectedSha256 = BuildConfig.OFFICIAL_RELEASE_CERT_SHA256,
        actualSha256s = readSigningCertSha256s(context),
    )

    @Suppress("DEPRECATION")
    fun readSigningCertSha256s(context: Context): Set<String> = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signingInfo = info.signingInfo
            when {
                signingInfo == null -> emptyArray()
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } else {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
            info.signatures ?: emptyArray()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        signatures.mapTo(LinkedHashSet()) { sig ->
            digest.reset()
            digest.digest(sig.toByteArray()).joinToString(separator = "") { b ->
                "%02x".format(Locale.ROOT, b)
            }
        }
    }.getOrDefault(emptySet())

    fun readBundledLicenseAndNotice(context: Context): String {
        val notice = readAssetOrNull(context, "NOTICE.txt")
        val license = readAssetOrNull(context, "LICENSE.txt")
        return listOfNotNull(notice, license)
            .joinToString(separator = "\n\n---\n\n")
            .ifBlank { FALLBACK_LICENSE_SUMMARY }
    }

    private fun readAssetOrNull(context: Context, assetName: String): String? = runCatching {
        context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private const val FALLBACK_LICENSE_SUMMARY: String =
        "ThreadLog — Copyright (c) 2026 k8242\nLicensed under the MIT License.\nUpstream: https://github.com/k8242/public_agentai"
}
