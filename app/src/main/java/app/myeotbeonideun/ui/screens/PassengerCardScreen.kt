package app.myeotbeonideun.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myeotbeonideun.R
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

/**
 * 동승자 카드: 큰 글자 두 줄("큰딸 집에 가는 길" / "절반쯤 왔어요").
 * 잘 들리지 않는 분을 위한 '보이는 대답'이다. 움직이는 것이 없고 누를 것도 없다.
 */
@Composable
fun PassengerCardScreen(title: String?, stage: String?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        val width = maxWidth
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title == null || stage == null) {
                Text(
                    stringResource(R.string.card_idle),
                    style = AppType.hero, color = colors.textSoft, textAlign = TextAlign.Center,
                )
            } else {
                val density = LocalDensity.current
                Text(
                    title,
                    fontSize = fitFontSize(title, width.value, density.fontScale, max = 84f),
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    stage,
                    fontSize = fitFontSize(stage, width.value, density.fontScale, max = 104f),
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 한 줄에 다 들어가는 가장 큰 글자 크기. 한글 한 글자의 너비를 글자 크기와 같다고 보고 어림한다. */
private fun fitFontSize(text: String, widthDp: Float, fontScale: Float, max: Float): TextUnit {
    val units = text.sumOf { if (it.isWhitespace()) 0.35 else 1.0 }.coerceAtLeast(1.0)
    val fit = (widthDp / (units * 1.04) / fontScale).toFloat()
    return fit.coerceIn(36f, max).sp
}
