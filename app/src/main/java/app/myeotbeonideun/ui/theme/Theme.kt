package app.myeotbeonideun.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 화면에서 쓰는 색(Compose Color). */
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val textSoft: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val accent: Color,
    val alert: Color,
    val onAlert: Color,
    val outline: Color,
)

private fun Palette.toColors() = AppColors(
    background = Color(background),
    surface = Color(surface),
    surfaceHigh = Color(surfaceHigh),
    text = Color(text),
    textSoft = Color(textSoft),
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    accent = Color(accent),
    alert = Color(alert),
    onAlert = Color(onAlert),
    outline = Color(outline),
)

val LocalAppColors = staticCompositionLocalOf { Palette.Night.toColors() }

/**
 * 글자 크기. 차 안에서 1~2초 안에 읽을 수 있게 크게 잡았다.
 *
 * Pleos Connect 에뮬레이터 화면은 2560x1440, 160dpi 라 1sp 가 1px 이고, 17인치 화면에서 1px 은 약 0.15mm 다.
 * 가이드의 글자 높이(중요 정보 5mm, 보조 3mm 이상)에 맞춰 중요 정보는 38sp(약 5.6mm) 이상,
 * 가장 작은 글자도 22sp(약 3.2mm) 이상으로 두었다.
 */
object AppType {
    /** 동승자 카드의 큰 글자. */
    val card = TextStyle(fontSize = 84.sp, lineHeight = 100.sp, fontWeight = FontWeight.Bold)
    /** 주행 중 화면의 가운데 줄(어디·어느 구간). */
    val hero = TextStyle(fontSize = 54.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold)
    /** 큰 버튼. */
    val button = TextStyle(fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
    val line = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 26.sp, lineHeight = 36.sp)
    val label = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 22.sp, lineHeight = 30.sp)
}

private fun AppColors.toScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = textSoft,
        outline = outline,
        error = alert,
        onError = onAlert,
    )
}

@Composable
fun MyeotbeonTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = (if (dark) Palette.Night else Palette.Day).toColors()
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(dark),
            typography = Typography(bodyLarge = AppType.body, bodyMedium = AppType.body, labelLarge = AppType.label),
            content = content,
        )
    }
}
