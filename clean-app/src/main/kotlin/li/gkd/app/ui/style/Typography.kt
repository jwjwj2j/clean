package li.gkd.app.ui.style

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

const val TABULAR_NUMBERS_FONT_FEATURE = "tnum"

/**
 * CLEAN 的字号层级（设计稿 §4.3）。
 *
 * | 用途 | 字号 | 字重 |
 * | --- | --- | --- |
 * | 首页大数字 | 48sp | Bold |
 * | 统计卡片数字 | 22sp | Bold |
 * | 页面标题 | 20sp | Bold |
 * | 卡片标题 | 16sp | Medium |
 * | 正文 | 14–15sp | Normal |
 * | 辅助文字 | 12sp | Normal |
 *
 * 通过覆盖 `MaterialTheme.typography` 让**全项目**自动统一，
 * 页面里直接用 `MaterialTheme.typography.titleLarge` 等语义样式即可，
 * 不要再手写 `fontSize = 15.sp` —— 那正是"13sp / 17sp 乱入"的来源。
 */
val CleanTypography = Typography().let { base ->
    base.copy(
        // 48sp：首页主数字
        displayLarge = base.displayLarge.copy(
            fontSize = 48.sp,
            lineHeight = 56.sp,
            fontWeight = FontWeight.Bold,
        ),
        // 22sp：统计卡片数字
        headlineMedium = base.headlineMedium.copy(
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
        ),
        // 20sp：页面标题
        titleLarge = base.titleLarge.copy(
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        ),
        // 16sp：卡片标题
        titleMedium = base.titleMedium.copy(
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
        ),
        // 14sp：次级标题
        titleSmall = base.titleSmall.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        ),
        // 15sp：正文（设计稿 §2.3 应用名、§1.4 最近触发）
        bodyLarge = base.bodyLarge.copy(
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Normal,
        ),
        // 14sp：正文
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
        ),
        // 12sp：辅助文字
        bodySmall = base.bodySmall.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
        ),
        // 14sp：按钮/标签
        labelLarge = base.labelLarge.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        ),
        // 12sp：小标签（设计稿 §1.1「今日触发」、§1.2 卡片标签）
        labelMedium = base.labelMedium.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
        ),
        labelSmall = base.labelSmall.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
        ),
    )
}
