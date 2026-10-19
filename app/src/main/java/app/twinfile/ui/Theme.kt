package app.twinfile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
)

private val Light = TwinColors(
    paneFocused = Color(0xFFFFFFFF), pane = Color(0xFFF1F1EE), paneBorder = Color(0xFF3E5BD8),
    selection = Color(0x333E5BD8), muted = Color(0xFF7A7A80),
    folder = Color(0xFF3E5BD8), image = Color(0xFF2E9E6B), video = Color(0xFFD0453A),
    audio = Color(0xFF9B51E0), archive = Color(0xFFC98A12), apk = Color(0xFF3DA35D),
    text = Color(0xFF55606E), other = Color(0xFF8A8F98),
)

private val Dark = TwinColors(
    paneFocused = Color(0xFF1C1D22), pane = Color(0xFF15161A), paneBorder = Color(0xFF7C93FF),
    selection = Color(0x447C93FF), muted = Color(0xFF8E9099),
    folder = Color(0xFF8A9EFF), image = Color(0xFF5CC495), video = Color(0xFFF07167),
    audio = Color(0xFFC38BFF), archive = Color(0xFFF0B44C), apk = Color(0xFF6CD28A),
    text = Color(0xFFA7B0BC), other = Color(0xFF9CA0A8),
)

val LocalTwin = staticCompositionLocalOf { Light }

@Composable
fun TwinTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF8A9EFF), onPrimary = Color(0xFF0E1440),
        secondaryContainer = Color(0xFF2A3156), onSecondaryContainer = Color(0xFFDCE1FF),
        background = Color(0xFF121316), surface = Color(0xFF121316),
        surfaceContainer = Color(0xFF1C1D22), surfaceContainerHigh = Color(0xFF24252B),
    ) else lightColorScheme(
        primary = Color(0xFF3E5BD8), onPrimary = Color.White,
        secondaryContainer = Color(0xFFDDE3FF), onSecondaryContainer = Color(0xFF14204F),
        background = Color(0xFFF6F6F3), surface = Color(0xFFF6F6F3),
        surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF0F0EC),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalTwin provides if (dark) Dark else Light) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
