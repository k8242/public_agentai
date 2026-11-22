// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.k8242.threadlog.core.model.AppColorScheme
import io.github.k8242.threadlog.core.model.ThemeMode

data class ColorSchemeOption(
    val scheme: AppColorScheme,
    val label: String,
    val previewColor: Color,
)

val AvailableColorSchemes: List<ColorSchemeOption> = listOf(
    ColorSchemeOption(AppColorScheme.TEAL, "Teal", Color(0xFF006A60)),
    ColorSchemeOption(AppColorScheme.WHATSAPP, "Forest", Color(0xFF075E54)),
    ColorSchemeOption(AppColorScheme.OCEAN, "Ocean", Color(0xFF1565C0)),
    ColorSchemeOption(AppColorScheme.SUNSET, "Sunset", Color(0xFFBF4E24)),
    ColorSchemeOption(AppColorScheme.LAVENDER, "Lavender", Color(0xFF6750A4)),
    ColorSchemeOption(AppColorScheme.MONOCHROME, "Ink", Color(0xFF33363A)),
    ColorSchemeOption(AppColorScheme.DYNAMIC, "Dynamic", Color(0xFF5C7AEA)),
)

private val TealLightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF456179),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF6FAF8),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
)

private val TealDarkColors = darkColorScheme(
    primary = Color(0xFF82D5C8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9EF2E4),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFADC9E5),
    onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4961),
    onTertiaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
)

private val WhatsAppLightColors = lightColorScheme(
    primary = Color(0xFF075E54),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9FDD3),
    onPrimaryContainer = Color(0xFF042D23),
    secondary = Color(0xFF128C7E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2EFE9),
    onSecondaryContainer = Color(0xFF052B26),
    tertiary = Color(0xFF34789C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3ECFA),
    onTertiaryContainer = Color(0xFF062538),
    background = Color(0xFFEFEAE2),
    onBackground = Color(0xFF182220),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF182220),
    surfaceContainerLow = Color(0xFFEAFAE6),
    surfaceContainerHigh = Color(0xFFE4E0D8),
    surfaceContainerHighest = Color(0xFFDCD7CE),
    surfaceVariant = Color(0xFFDFE5DF),
    onSurfaceVariant = Color(0xFF475450),
)

private val WhatsAppDarkColors = darkColorScheme(
    primary = Color(0xFF25D366),
    onPrimary = Color(0xFF003919),
    primaryContainer = Color(0xFF005C4B),
    onPrimaryContainer = Color(0xFFD9FDD3),
    secondary = Color(0xFF66D4B8),
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF164A41),
    onSecondaryContainer = Color(0xFFC5F2E6),
    tertiary = Color(0xFF7CC6E8),
    onTertiary = Color(0xFF07344A),
    tertiaryContainer = Color(0xFF1D4B63),
    onTertiaryContainer = Color(0xFFD3ECFA),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE9EDEF),
    surface = Color(0xFF111B21),
    onSurface = Color(0xFFE9EDEF),
    surfaceContainerLow = Color(0xFF005C4B).copy(alpha = 0.42f),
    surfaceContainerHigh = Color(0xFF1F2C34),
    surfaceContainerHighest = Color(0xFF2A3942),
    surfaceVariant = Color(0xFF233138),
    onSurfaceVariant = Color(0xFF9EB0BA),
)

private val OceanLightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF4B5E7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF0C1E35),
    tertiary = Color(0xFF006971),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB3F0F7),
    onTertiaryContainer = Color(0xFF002023),
    background = Color(0xFFF5F8FD),
    onBackground = Color(0xFF161C24),
    surface = Color(0xFFF5F8FD),
    onSurface = Color(0xFF161C24),
    surfaceVariant = Color(0xFFDCE3F0),
    onSurfaceVariant = Color(0xFF434C5B),
)

private val OceanDarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00478D),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB8C7E0),
    onSecondary = Color(0xFF223249),
    secondaryContainer = Color(0xFF394962),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFF7FD5DF),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF004F56),
    onTertiaryContainer = Color(0xFFB3F0F7),
    background = Color(0xFF0D131C),
    onBackground = Color(0xFFE0E5EE),
    surface = Color(0xFF101722),
    onSurface = Color(0xFFE0E5EE),
    surfaceVariant = Color(0xFF2A3444),
    onSurfaceVariant = Color(0xFFB5C0D0),
)

private val SunsetLightColors = lightColorScheme(
    primary = Color(0xFFBF4E24),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = Color(0xFF3C0E00),
    secondary = Color(0xFF77574B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF6B5D2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E1A7),
    onTertiaryContainer = Color(0xFF231B00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A17),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A17),
    surfaceVariant = Color(0xFFF4DED6),
    onSurfaceVariant = Color(0xFF53433E),
)

private val SunsetDarkColors = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5F1E04),
    primaryContainer = Color(0xFF8B3210),
    onPrimaryContainer = Color(0xFFFFDBCE),
    secondary = Color(0xFFE7BDB0),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFFD8C58D),
    onTertiary = Color(0xFF3A2F05),
    tertiaryContainer = Color(0xFF52451A),
    onTertiaryContainer = Color(0xFFF5E1A7),
    background = Color(0xFF191210),
    onBackground = Color(0xFFF0DFDA),
    surface = Color(0xFF1D1512),
    onSurface = Color(0xFFF0DFDA),
    surfaceVariant = Color(0xFF42332E),
    onSurfaceVariant = Color(0xFFD5C2BC),
)

private val LavenderLightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFAF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFAF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
)

private val LavenderDarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF17151C),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF3B3645),
    onSurfaceVariant = Color(0xFFCAC4D0),
)

private val MonochromeLightColors = lightColorScheme(
    primary = Color(0xFF262A2E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E4E8),
    onPrimaryContainer = Color(0xFF14171A),
    secondary = Color(0xFF525860),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8EBEE),
    onSecondaryContainer = Color(0xFF1E2226),
    tertiary = Color(0xFF3B4D5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE5EE),
    onTertiaryContainer = Color(0xFF101D29),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF141618),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141618),
    surfaceVariant = Color(0xFFE6E8EB),
    onSurfaceVariant = Color(0xFF4C5259),
)

private val MonochromeDarkColors = darkColorScheme(
    primary = Color(0xFFE0E3E7),
    onPrimary = Color(0xFF171A1D),
    primaryContainer = Color(0xFF383D42),
    onPrimaryContainer = Color(0xFFF0F2F5),
    secondary = Color(0xFFBDC2C8),
    onSecondary = Color(0xFF25292E),
    secondaryContainer = Color(0xFF3A3F45),
    onSecondaryContainer = Color(0xFFE6E8EB),
    tertiary = Color(0xFFAEC2D6),
    onTertiary = Color(0xFF1A2B3B),
    tertiaryContainer = Color(0xFF304354),
    onTertiaryContainer = Color(0xFFDCE5EE),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFE7E9EC),
    surface = Color(0xFF131518),
    onSurface = Color(0xFFE7E9EC),
    surfaceVariant = Color(0xFF2E3237),
    onSurfaceVariant = Color(0xFFB2B8BF),
)

@Composable
fun resolveDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun ThreadLogTheme(
    appColorScheme: AppColorScheme = AppColorScheme.TEAL,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when (appColorScheme) {
        AppColorScheme.DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) TealDarkColors else TealLightColors
            }
        }
        AppColorScheme.TEAL -> if (darkTheme) TealDarkColors else TealLightColors
        AppColorScheme.WHATSAPP -> if (darkTheme) WhatsAppDarkColors else WhatsAppLightColors
        AppColorScheme.OCEAN -> if (darkTheme) OceanDarkColors else OceanLightColors
        AppColorScheme.SUNSET -> if (darkTheme) SunsetDarkColors else SunsetLightColors
        AppColorScheme.LAVENDER -> if (darkTheme) LavenderDarkColors else LavenderLightColors
        AppColorScheme.MONOCHROME -> if (darkTheme) MonochromeDarkColors else MonochromeLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
