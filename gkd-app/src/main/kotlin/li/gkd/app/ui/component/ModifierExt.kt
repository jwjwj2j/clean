package li.gkd.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import li.gkd.app.ui.style.pressDurationMs
import li.gkd.app.ui.style.pressScale

inline fun Modifier.runIf(
    enabled: Boolean,
    block: Modifier.() -> Modifier
) = run {
    if (enabled) {
        block()
    } else {
        this
    }
}

/**
 * 点击时整块缩放到 [pressScale]（0.98），持续 [pressDurationMs]（120ms）—— 设计稿 §4.4。
 *
 * 为什么做成修饰符：首页的「触发记录」通栏卡片与应用网格格子都要这个反馈，
 * 两处各写一遍必然出现时长/倍率不一致，那正是设计稿要消灭的问题。
 *
 * **刻意不做的事**：不加临时禁用、不吞点击、不在动画期间锁定交互。
 * 动画只负责视觉过渡，交互始终按当前业务状态立即响应。
 *
 * 用法：`Modifier.pressAnimation(onClick = { ... })`，内部自己创建
 * [MutableInteractionSource]；若调用方已有交互源，用另一个重载传入即可。
 */
@Composable
fun Modifier.pressAnimation(
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressScale else 1f,
        animationSpec = tween(pressDurationMs),
        label = "pressScale",
    )
    return this.scale(scale)
}
