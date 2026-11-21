plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

subprojects {
    // Forward custom JVM TLS trustStore (if configured on the build JVM, e.g. sandboxed VMs)
    // into forked Test JVMs (needed when Robolectric fetches android-all jars). Clean no-op on CI.
    tasks.withType<Test>().configureEach {
        val trustStore = System.getProperty("javax.net.ssl.trustStore")
        if (!trustStore.isNullOrBlank()) {
            systemProperty("javax.net.ssl.trustStore", trustStore)
        }
        val trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword")
        if (!trustStorePassword.isNullOrBlank()) {
            systemProperty("javax.net.ssl.trustStorePassword", trustStorePassword)
        }
    }
}

tasks.register("checkLicenseHeaders") {
    group = "verification"
    description = "Verifies that all Kotlin source files carry the SPDX MIT copyright header."
    val ktFiles = fileTree(rootDir) {
        include("**/src/**/*.kt")
        exclude("**/build/**")
    }
    val rootPath = rootDir.toPath()
    inputs.files(ktFiles)
    doLast {
        val copyrightPattern = Regex("^// Copyright \\(c\\) 202\\d(-\\d{4})? k8242$")
        val spdxLine = "// SPDX-License-Identifier: MIT"
        val missing = mutableListOf<String>()
        ktFiles.files.sorted().forEach { file ->
            val lines = file.readLines()
            val valid = lines.size >= 2 &&
                copyrightPattern.matches(lines[0]) &&
                lines[1] == spdxLine
            if (!valid) {
                missing += rootPath.relativize(file.toPath()).toString()
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing or invalid SPDX MIT header in ${missing.size} file(s):\n" +
                    missing.joinToString(separator = "\n") { "  - $it" } +
                    "\nExpected lines 1-2:\n  // Copyright (c) 2026 k8242\n  // SPDX-License-Identifier: MIT",
            )
        }
    }
}

