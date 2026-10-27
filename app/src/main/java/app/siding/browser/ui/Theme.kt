package app.siding.browser.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RoadYellow = Color(0xFFE2B340)
private val Ink = Color(0xFF1C1914)
private val Paper = Color(0xFFF6F3EA)
private val Night = Color(0xFF10140F)
private val NightSurface = Color(0xFF1A2118)
private val NightVariant = Color(0xFF2A3328)
private val Cream = Color(0xFFF4F1E6)
private val Moss = Color(0xFF8FBF9F)
private val Clay = Color(0xFFE07A5F)

private val DarkColors = darkColorScheme(
    primary = RoadYellow,
    onPrimary = Color(0xFF2A2108),
    secondary = Moss,
    onSecondary = Ink,
    background = Night,
    onBackground = Cream,
    surface = NightSurface,
    onSurface = Cream,
    surfaceVariant = NightVariant,
    onSurfaceVariant = Color(0xFFD5D0C4),
    error = Clay,
    onError = Ink,
)

private val BlackColors = DarkColors.copy(
    background = Color.Black,
    surface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF161616),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    secondary = Color(0xFF2F6B4F),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E1D2),
    onSurfaceVariant = Color(0xFF4E493F),
    error = Color(0xFF9C341F),
    onError = Color.White,
)

@Composable
fun SidingTheme(
    dark: Boolean,
    trueBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dark && trueBlack -> BlackColors
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
