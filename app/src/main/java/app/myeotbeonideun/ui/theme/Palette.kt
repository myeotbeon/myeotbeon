package app.myeotbeonideun.ui.theme

import kotlin.math.pow

/**
 * 화면 색. 차분한 색만 쓰고, 글자와 바탕의 대비는 4.5:1 이상으로 둔다
 * (Pleos 디자인 가이드: 아이콘·텍스트 대비 4.5:1 이상). 대비는 단위 테스트(PaletteContrastTest)로 검사한다.
 * 값은 0xAARRGGBB.
 */
data class Palette(
    val background: Long,
    val surface: Long,
    val surfaceHigh: Long,
    val text: Long,
    val textSoft: Long,
    /** 주된 버튼의 바탕과 그 위의 글자. */
    val primary: Long,
    val onPrimary: Long,
    /** 둘째 버튼의 바탕과 그 위의 글자. */
    val secondary: Long,
    val onSecondary: Long,
    /** 강조 글자(구간 이름 등). */
    val accent: Long,
    /** 운전자에게 바로 알려야 하는 알림의 바탕과 글자. */
    val alert: Long,
    val onAlert: Long,
    val outline: Long,
) {
    /** 대비를 지켜야 하는 (글자, 바탕, 이름) 쌍. */
    fun textPairs(): List<Triple<Long, Long, String>> = listOf(
        Triple(text, background, "text/background"),
        Triple(text, surface, "text/surface"),
        Triple(text, surfaceHigh, "text/surfaceHigh"),
        Triple(textSoft, background, "textSoft/background"),
        Triple(textSoft, surface, "textSoft/surface"),
        Triple(textSoft, surfaceHigh, "textSoft/surfaceHigh"),
        Triple(onPrimary, primary, "onPrimary/primary"),
        Triple(onSecondary, secondary, "onSecondary/secondary"),
        Triple(accent, background, "accent/background"),
        Triple(accent, surface, "accent/surface"),
        Triple(onAlert, alert, "onAlert/alert"),
    )

    companion object {
        /** 밤(기본). 차 안에서 눈부시지 않은 어두운 바탕. */
        val Night = Palette(
            background = 0xFF0F1D24,
            surface = 0xFF17303A,
            surfaceHigh = 0xFF21414E,
            text = 0xFFF4EEE3,
            textSoft = 0xFFC3D0D4,
            primary = 0xFFF0CB85,
            onPrimary = 0xFF20180A,
            secondary = 0xFF2C5563,
            onSecondary = 0xFFF4EEE3,
            accent = 0xFF9ADBC9,
            alert = 0xFF8A3324,
            onAlert = 0xFFFFFFFF,
            outline = 0xFF5E7C87,
        )

        /** 낮. 따뜻한 종이색 바탕. */
        val Day = Palette(
            background = 0xFFF5F0E6,
            surface = 0xFFFFFFFF,
            surfaceHigh = 0xFFE9E2D3,
            text = 0xFF1A2A31,
            textSoft = 0xFF42555D,
            primary = 0xFF1F5C68,
            onPrimary = 0xFFFFFFFF,
            secondary = 0xFFD6E4E3,
            onSecondary = 0xFF1A2A31,
            accent = 0xFF14606B,
            alert = 0xFF9B2C1B,
            onAlert = 0xFFFFFFFF,
            outline = 0xFF7A8C92,
        )

        /** WCAG 2.x 상대 휘도. */
        fun luminance(argb: Long): Double {
            fun channel(v: Long): Double {
                val c = v / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            val r = channel((argb shr 16) and 0xFF)
            val g = channel((argb shr 8) and 0xFF)
            val b = channel(argb and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        /** WCAG 2.x 대비(1~21). */
        fun contrast(a: Long, b: Long): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
    }
}
