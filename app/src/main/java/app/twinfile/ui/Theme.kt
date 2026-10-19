package app.twinfile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** A user-selectable color theme: one accent per light/dark mode; neutrals are shared. */
enum class Palette(val label: String, val light: Long, val dark: Long) {
    GRAPHITE("石墨", 0xFF4A5566, 0xFFB9C3D1),
    INK("墨蓝", 0xFF2F4F8F, 0xFF9DB6EC),
    TEAL("松石", 0xFF1E6B67, 0xFF84CFC7),
    PLUM("暮紫", 0xFF6A4B80, 0xFFCDB3E0),
    OCHRE("赭石", 0xFF96591E, 0xFFE8B37E),
    ROSE("胭脂", 0xFF9E3A54, 0xFFF0A6B8),
    DYNAMIC("壁纸取色", 0, 0);

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: GRAPHITE
        val dynamicSupported get() = Build.VERSION.SDK_INT >= 31
    }
}

enum class DarkMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: SYSTEM }
}

/** Colors outside Material's scheme: pane chrome, selection and file-type tints. */
@Immutable
data class TwinColors(
    val paneFocused: Color,
    val pane: Color,
    val paneBorder: Color,
    val selection: Color,
    val muted: Color,
    val folder: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val archive: Color,
    val apk: Color,
    val text: Color,
    val other: Color,
    val pdf: Color,
    val doc: Color,
    val sheet: Color,
    val slide: Color,
    val code: Color,
)

val LocalTwin = staticCompositionLocalOf { twinColors(Color(0xFF4A5566), false) }

/** File-type tints are muted so the accent stays the only strong color on screen. */
private fun twinColors(accent: Color, dark: Boolean): TwinColors = if (dark) TwinColors(
    paneFocused = Color(0xFF1B1C1F), pane = Color(0xFF141517), paneBorder = accent,
    selection = accent.copy(alpha = 0.24f), muted = Color(0xFF8D9099),
    folder = accent, image = Color(0xFF7FB89A), video = Color(0xFFD99089),
    audio = Color(0xFFB9A1D6), archive = Color(0xFFD4B07A), apk = Color(0xFF8CC39B),
    text = Color(0xFF9FA8B4), other = Color(0xFF8F939B),
    pdf = Color(0xFFE08A84), doc = Color(0xFF8FB0E8), sheet = Color(0xFF83C79E), slide = Color(0xFFE3A57B),
    code = Color(0xFFADA4E0),
) else TwinColors(
    paneFocused = Color(0xFFFFFFFF), pane = Color(0xFFEFEFEC), paneBorder = accent,
    selection = accent.copy(alpha = 0.14f), muted = Color(0xFF7B7D84),
    folder = accent, image = Color(0xFF3F8A66), video = Color(0xFFB4554C),
    audio = Color(0xFF7D5FA6), archive = Color(0xFFA77A2C), apk = Color(0xFF4A8A5A),
    text = Color(0xFF5B6573), other = Color(0xFF878B93),
    pdf = Color(0xFFC2453F), doc = Color(0xFF3B6FC4), sheet = Color(0xFF2E8A57), slide = Color(0xFFC46A2E),
    code = Color(0xFF6A5FB0),
)

private fun schemeFor(accent: Color, dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = accent, onPrimary = Color(0xFF111215),
    primaryContainer = lerp(Color(0xFF1B1C1F), accent, 0.30f), onPrimaryContainer = Color(0xFFECEDF0),
    secondaryContainer = lerp(Color(0xFF1B1C1F), accent, 0.22f), onSecondaryContainer = Color(0xFFE4E6EA),
    background = Color(0xFF101113), surface = Color(0xFF101113),
    surfaceContainerLowest = Color(0xFF0C0D0E), surfaceContainerLow = Color(0xFF16171A),
    surfaceContainer = Color(0xFF1B1C1F), surfaceContainerHigh = Color(0xFF222327), surfaceContainerHighest = Color(0xFF2A2B30),
    onSurface = Color(0xFFE6E7EA), onSurfaceVariant = Color(0xFFB3B6BD), outlineVariant = Color(0xFF2E3035),
) else lightColorScheme(
    primary = accent, onPrimary = Color.White,
    primaryContainer = lerp(Color.White, accent, 0.16f), onPrimaryContainer = lerp(Color.Black, accent, 0.55f),
    secondaryContainer = lerp(Color.White, accent, 0.12f), onSecondaryContainer = lerp(Color.Black, accent, 0.5f),
    background = Color(0xFFF6F6F4), surface = Color(0xFFF6F6F4),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAF8),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF1F1EE), surfaceContainerHighest = Color(0xFFEAEAE6),
    onSurface = Color(0xFF1C1D20), onSurfaceVariant = Color(0xFF55585F), outlineVariant = Color(0xFFE2E2DE),
)

@Composable
fun isDark(mode: DarkMode): Boolean = when (mode) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

@Composable
fun TwinTheme(palette: Palette, darkMode: DarkMode, content: @Composable () -> Unit) {
    val dark = isDark(darkMode)
    val scheme: ColorScheme
    val accent: Color
    if (palette == Palette.DYNAMIC && Palette.dynamicSupported) {
        val ctx = LocalContext.current
        val dyn = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        accent = dyn.primary
        // Keep our neutral surfaces; take only the wallpaper accent family.
        scheme = schemeFor(accent, dark).copy(
            primaryContainer = dyn.primaryContainer, onPrimaryContainer = dyn.onPrimaryContainer,
            secondaryContainer = dyn.secondaryContainer, onSecondaryContainer = dyn.onSecondaryContainer,
        )
    } else {
        val p = if (palette == Palette.DYNAMIC) Palette.GRAPHITE else palette
        accent = Color(if (dark) p.dark else p.light)
        scheme = schemeFor(accent, dark)
    }
    CompositionLocalProvider(LocalTwin provides twinColors(accent, dark)) {
        MaterialTheme(colorScheme = scheme) {
            // Text and icons outside a Surface still need the theme's foreground color.
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}
