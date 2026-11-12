package ua.kpi.travel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ocean = Color(0xFF00658F)
val Sunset = Color(0xFFE8743B)
val Ok = Color(0xFF2E7D32)
val Warn = Color(0xFFF9A825)
val Bad = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Ocean, onPrimary = Color.White, primaryContainer = Color(0xFFC7E7FF), onPrimaryContainer = Color(0xFF001E2E),
    secondary = Sunset, onSecondary = Color.White, secondaryContainer = Color(0xFFFFDBCB), onSecondaryContainer = Color(0xFF341100),
    tertiary = Color(0xFF52634F), background = Color(0xFFF8FAFC), surface = Color(0xFFF8FAFC)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF85CFFF), onPrimary = Color(0xFF00344C), primaryContainer = Color(0xFF004C6C),
    secondary = Color(0xFFFFB693), secondaryContainer = Color(0xFF6E2F0C)
)

@Composable
fun TravelTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
