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
