package app.myeotbeonideun.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

/**
 * 큰 버튼. 주행 중에도 한 번에 누를 수 있게 넓고 높다.
 * 누르면 95%로 줄어들고 바탕색이 바뀐다(Pleos 인터랙션 가이드의 눌림 효과).
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    minHeight: Dp = 132.dp,
    textStyle: TextStyle = AppType.button,
) {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val container = when {
        !enabled -> colors.surfaceHigh
        primary -> colors.primary
        else -> colors.secondary
    }
    val content = when {
        !enabled -> colors.textSoft
        primary -> colors.onPrimary
        else -> colors.onSecondary
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(28.dp))
            .background(if (pressed) container.copy(alpha = 0.82f) else container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 28.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = textStyle, color = content, textAlign = TextAlign.Center, maxLines = 2)
            if (caption != null) {
                Text(
                    caption, style = AppType.label, color = content, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 여러 개 중 하나를 고르는 칩. 글자와 누르는 자리가 크다. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 64.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.primary else colors.surface,
        contentColor = if (selected) colors.onPrimary else colors.text,
        border = if (selected) null else BorderStroke(1.5.dp, colors.outline),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text(text, style = AppType.body, maxLines = 1)
        }
    }
}

/** 작은 알림표('체험 모드' 등). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (strong) colors.accent else Color.Transparent)
            .border(1.5.dp, colors.accent, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text, style = AppType.small, color = if (strong) colors.background else colors.accent, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppType.label, color = LocalAppColors.current.textSoft, modifier = modifier)
}

/** 내용을 담는 둥근 판. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(24.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = LocalAppColors.current.surface,
        contentColor = LocalAppColors.current.text,
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}
