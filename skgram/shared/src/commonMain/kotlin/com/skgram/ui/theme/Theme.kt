package com.skgram.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The look of the app: sky blue "academy city" UI with halo accents. */
enum class SkTheme { SYSTEM, SKY, NIGHT, SAKURA }

/** Extra colors Material doesn't have slots for. */
@Immutable
data class SkColors(
    val headerStart: Color,
    val headerEnd: Color,
    val bubbleOutStart: Color,
    val bubbleOutEnd: Color,
    val onBubbleOut: Color,
    val bubbleIn: Color,
    val bubbleInBorder: Color,
    val onBubbleIn: Color,
    val halo: Color,
    val stripe: Color,
    val badge: Color,
    val timeOut: Color,
    val timeIn: Color,
) {
    val header get() = Brush.linearGradient(listOf(headerStart, headerEnd))
    val bubbleOut get() = Brush.linearGradient(listOf(bubbleOutStart, bubbleOutEnd))
}

private val Sky = SkColors(
    headerStart = Color(0xFF128AFA), headerEnd = Color(0xFF3FC8F4),
    bubbleOutStart = Color(0xFF2B9BFF), bubbleOutEnd = Color(0xFF4FD0F7), onBubbleOut = Color.White,
    bubbleIn = Color.White, bubbleInBorder = Color(0xFFBFE2FF), onBubbleIn = Color(0xFF1B2A41),
    halo = Color(0xFFFFC94D), stripe = Color(0x1A128AFA), badge = Color(0xFFFF5C8A),
    timeOut = Color(0xCCFFFFFF), timeIn = Color(0xFF7D93B2),
)
private val Night = SkColors(
    headerStart = Color(0xFF0D2B57), headerEnd = Color(0xFF1565C0),
    bubbleOutStart = Color(0xFF1E6FD9), bubbleOutEnd = Color(0xFF1AA6D9), onBubbleOut = Color.White,
    bubbleIn = Color(0xFF16233A), bubbleInBorder = Color(0xFF26446E), onBubbleIn = Color(0xFFE3EEFF),
    halo = Color(0xFFFFD66B), stripe = Color(0x1440A0FF), badge = Color(0xFFFF6F9C),
    timeOut = Color(0xB3FFFFFF), timeIn = Color(0xFF8BA3C7),
)
private val Sakura = SkColors(
    headerStart = Color(0xFFFF7EB6), headerEnd = Color(0xFF9FB8FF),
    bubbleOutStart = Color(0xFFFF8CC0), bubbleOutEnd = Color(0xFFB59CFF), onBubbleOut = Color.White,
    bubbleIn = Color.White, bubbleInBorder = Color(0xFFFFD1E6), onBubbleIn = Color(0xFF3A2440),
    halo = Color(0xFFFFC94D), stripe = Color(0x1AFF7EB6), badge = Color(0xFF128AFA),
    timeOut = Color(0xCCFFFFFF), timeIn = Color(0xFFA2879F),
)

private val SkyScheme = lightColorScheme(
    primary = Color(0xFF128AFA), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7ECFF), onPrimaryContainer = Color(0xFF00315F),
    secondary = Color(0xFF2EC4E8), onSecondary = Color.White,
    tertiary = Color(0xFFFF5C8A),
    background = Color(0xFFF2F8FF), onBackground = Color(0xFF1B2A41),
    surface = Color(0xFFF8FBFF), onSurface = Color(0xFF1B2A41),
    surfaceVariant = Color(0xFFE3F0FD), onSurfaceVariant = Color(0xFF51627C),
    outline = Color(0xFFB6CCE6),
)
private val NightScheme = darkColorScheme(
    primary = Color(0xFF5CB3FF), onPrimary = Color(0xFF002F57),
    primaryContainer = Color(0xFF12406F), onPrimaryContainer = Color(0xFFD7ECFF),
    secondary = Color(0xFF58D6F5), onSecondary = Color(0xFF00363F),
    tertiary = Color(0xFFFF8CB0),
    background = Color(0xFF0A1424), onBackground = Color(0xFFE3EEFF),
    surface = Color(0xFF0E1A2E), onSurface = Color(0xFFE3EEFF),
    surfaceVariant = Color(0xFF1A2A44), onSurfaceVariant = Color(0xFFA9BCD9),
    outline = Color(0xFF34507A),
)
private val SakuraScheme = lightColorScheme(
    primary = Color(0xFFE85A9B), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDEB), onPrimaryContainer = Color(0xFF5B0F35),
    secondary = Color(0xFF8C7BFF), onSecondary = Color.White,
    tertiary = Color(0xFF128AFA),
    background = Color(0xFFFFF5FA), onBackground = Color(0xFF3A2440),
    surface = Color(0xFFFFF9FC), onSurface = Color(0xFF3A2440),
    surfaceVariant = Color(0xFFFBE6F1), onSurfaceVariant = Color(0xFF7A5D74),
    outline = Color(0xFFE8BDD3),
)

val LocalSkColors = staticCompositionLocalOf { Sky }

/** A parallelogram, like the slanted buttons and tags of the game's UI. */
class SkewShape(private val skew: Float = 0.22f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val dx = size.height * skew
        return Outline.Generic(Path().apply {
            moveTo(dx, 0f); lineTo(size.width, 0f); lineTo(size.width - dx, size.height); lineTo(0f, size.height); close()
        })
    }
}

val SkShapes = Shapes(
    extraSmall = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
    small = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val SkTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, letterSpacing = 0.5.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
    )
}

val TitleTag = TextStyle(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 20.sp, letterSpacing = 1.sp)

@Composable
fun SkgramTheme(theme: SkTheme, content: @Composable () -> Unit) {
    val resolved = if (theme == SkTheme.SYSTEM) (if (isSystemInDarkTheme()) SkTheme.NIGHT else SkTheme.SKY) else theme
    val (scheme: ColorScheme, colors) = when (resolved) {
        SkTheme.NIGHT -> NightScheme to Night
        SkTheme.SAKURA -> SakuraScheme to Sakura
        else -> SkyScheme to Sky
    }
    CompositionLocalProvider(LocalSkColors provides colors) {
        MaterialTheme(colorScheme = scheme, shapes = SkShapes, typography = SkTypography, content = content)
    }
}

fun SkTheme.isDark(systemDark: Boolean) = this == SkTheme.NIGHT || (this == SkTheme.SYSTEM && systemDark)
