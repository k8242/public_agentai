package io.github.k8242.threadlog.feature.backup.cloud

/**
 * Configuration contract providing the optional Google OAuth Web Client ID.
 *
 * Bound by `:app` (e.g. from `BuildConfig.GOOGLE_WEB_CLIENT_ID`) so `:feature:backup` never
 * depends on `:app`. When [webClientId] is blank, Google Cloud Sync gracefully degrades to
 * `GoogleAuthState.NotConfigured` while offline SAF backups continue working immediately.
 */
interface GoogleOAuthConfig {
    val webClientId: String
}
