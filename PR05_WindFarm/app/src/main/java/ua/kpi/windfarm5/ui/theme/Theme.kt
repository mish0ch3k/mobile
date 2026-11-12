package ua.kpi.windfarm5.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Палітра «вітрової» тематики: небесно-синій, сталевий, зелений для «енергії»
val SkyBlue = Color(0xFF1565C0)
val SkyLight = Color(0xFFE3F2FD)
val Steel = Color(0xFF455A64)
val EnergyGreen = Color(0xFF2E7D32)
val StormRed = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = SkyBlue,
    secondary = Steel,
    tertiary = EnergyGreen,
    error = StormRed
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFFA5D6A7),
    error = Color(0xFFEF9A9A)
)

@Composable
fun WindFarmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // власна палітра важливіша за Material You
    content: @Composable () -> Unit
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
