package com.slideindex.app.widget

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 小组件面板的「操作提示条」（HUD）。
 *
 * 与快捷轮盘的 HUD 同一套视觉语言：屏幕上方固定一条深色胶囊，
 * 左边是当前操作图标，中间是名称 + 一句"松手会怎样"，
 * 还没按满时长按进度画成绕图标的一圈环。
 *
 * 为什么要有它：手指会挡住格子本身，长按/悬停时看不到自己操作的是哪一个；
 * 二级菜单移动选择时尤其需要"指着谁、松手会发生什么"的即时反馈。
 */
data class WidgetPanelHudState(
    val title: String,
    val subtitle: String? = null,
    val icon: Bitmap? = null,
    /** 变化即重启长按进度（换到另一项时要从头开始）。 */
    val progressKey: Any?,
    val longPressMs: Int,
    val showProgress: Boolean = true,
)

/** 面板 HUD 的状态持有点（单例：面板同一时刻只有一个 HUD）。 */
object WidgetPanelHud {
    var state by mutableStateOf<WidgetPanelHudState?>(null)
        private set

    fun show(next: WidgetPanelHudState) {
        state = next
    }

    fun clear() {
        state = null
    }
}

private val HudBackground = Color(0xE6202020)
private val HudTitleColor = Color.White
private val HudSubtitleColor = Color(0xB3FFFFFF)
private val HudRingTrack = Color(0x33FFFFFF)
private val HudRingFill = Color(0xFF64B5F6)
private val HudPillShape = RoundedCornerShape(16.dp)

@Composable
fun WidgetPanelHudHost(modifier: Modifier = Modifier) {
    val state = WidgetPanelHud.state ?: return
    Row(
        modifier = modifier
            .padding(top = 44.dp)
            .clip(HudPillShape)
            .background(HudBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudIcon(state)
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column {
            Text(
                text = state.title,
                color = HudTitleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            state.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = HudSubtitleColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
    }
}

/** 图标 + 绕它一圈的长按进度环（进度与长按计时同源，换项即重来）。 */
@Composable
private fun HudIcon(state: WidgetPanelHudState) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.progressKey, state.showProgress) {
        if (!state.showProgress) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = state.longPressMs.coerceAtLeast(1),
                easing = LinearEasing,
            ),
        )
    }
    val bitmap: ImageBitmap? = remember(state.icon) { state.icon?.asImageBitmap() }
    androidx.compose.foundation.Canvas(modifier = Modifier.size(34.dp)) {
        if (bitmap != null) {
            val side = size.minDimension * 0.72f
            drawImage(
                image = bitmap,
                dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    ((size.width - side) / 2f).toInt(),
                    ((size.height - side) / 2f).toInt(),
                ),
            )
        }
        if (state.showProgress) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = HudRingTrack,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = HudRingFill,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
    }
}
