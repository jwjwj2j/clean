package li.gkd.app.ui.style

import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 统一的尺寸令牌（设计稿 §4.1 圆角 / §4.2 间距 / §4.3 字号见 Typography.kt）。
 *
 * **目的**：消灭"同一个 App 里 8dp 与 20dp 混用"这类不一致。
 * 新代码一律引用这里的常量，不要再手写字面量。
 *
 * 命名刻意保持短且无歧义；这些是**设计值**而非组件，因此不带 Gk 前缀
 * （AGENTS.md 的 Gk 规则针对跨页面复用的 UI 组件）。
 */

// ---------------------------------------------------------------- 圆角（设计稿 §4.1）
/** 所有卡片。 */
val cardCorner = 16.dp

/** 所有按钮。 */
val buttonCorner = 12.dp

/** 所有 Chip / 胶囊标签。 */
val chipCorner = 20.dp

/** 所有输入框。 */
val fieldCorner = 12.dp

/** 网格里的小卡片（比通栏卡片略小，视觉上更紧凑）。 */
val gridCardCorner = 16.dp

/** 状态胶囊高度取一半即为完全圆角。 */
val pillCorner = 14.dp

/**
 * 交给 Material3 的 Shapes，让**所有** Material 组件（Card / Button / TextField /
 * AlertDialog / BottomSheet …）自动使用统一圆角，无需逐处指定。
 *
 * 映射依据设计稿：
 * - extraSmall / small → 按钮、输入框（12dp）
 * - medium / large → 卡片（16dp）
 * - extraLarge → Chip、对话框（20dp）
 */
val CleanShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(buttonCorner),
    small = androidx.compose.foundation.shape.RoundedCornerShape(fieldCorner),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(cardCorner),
    large = androidx.compose.foundation.shape.RoundedCornerShape(cardCorner),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(chipCorner),
)

// ---------------------------------------------------------------- 间距（设计稿 §4.2）
/** 页面左右边距。 */
val pagePadding = 16.dp

/** 卡片之间。 */
val cardGap = 12.dp

/** 通栏卡片内部。 */
val cardPadding = 16.dp

/** 网格小卡片内部（设计稿 §2.3 为 14dp）。 */
val gridCellPadding = 14.dp

/** 图标与文字之间。 */
val iconTextGap = 8.dp

/** 行与行之间。 */
val lineGap = 4.dp

// ---------------------------------------------------------------- 固定尺寸
/** 底部导航、工具栏图标。 */
val iconSize = 24.dp

/** 设置页分组图标（略小）。 */
val groupIconSize = 22.dp

/** 网格中应用图标。 */
val appIconInGrid = 48.dp

/** 列表/工具栏中的应用图标。 */
val appIconInList = 48.dp

/** 状态圆点。 */
val statusDotSize = 8.dp

/** 首页运行状态胶囊高度（设计稿 §1.1）。 */
val statusPillHeight = 28.dp

/** 首页开关尺寸（设计稿 §1.1 要求比默认开关大）。 */
val heroSwitchWidth = 52.dp
val heroSwitchHeight = 32.dp

/** 工具栏高度（设计稿 §2.1）。 */
val toolbarHeight = 56.dp

/** FilterChip 高度（设计稿 §2.2）。 */
val filterChipHeight = 36.dp

/** 应用网格卡片固定高度（设计稿 §2.3，保证网格整齐）。 */
val gridCellHeight = 140.dp

/** 「触发记录」通栏卡片高度（设计稿 §1.3）。 */
val recordCardHeight = 72.dp

/** 空状态图标。 */
val emptyIconSize = 64.dp

// ---------------------------------------------------------------- 动效（设计稿 §4.4）
/** 点击缩放的目标值与时长。 */
const val pressScale = 0.98f
const val pressDurationMs = 120

/** 开关颜色渐变时长。 */
const val switchColorDurationMs = 150

/** 页面切换时长。 */
const val pageTransitionDurationMs = 200
