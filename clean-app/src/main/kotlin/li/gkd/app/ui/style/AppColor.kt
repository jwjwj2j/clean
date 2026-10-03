package li.gkd.app.ui.style

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import li.gkd.app.ui.share.LocalDarkTheme

/**
 * CLEAN 的配色令牌（设计稿 §1.5）。
 *
 * 深色是主场景（工具类 App 多在夜间/长时间使用），浅色是对应推导出来的。
 * 页面一律通过 `MaterialTheme.colorScheme.*` 取色，**不要直接写死十六进制**；
 * 这里只有确实需要"非 Material 语义"的颜色（如状态圆点、强调青）才单独暴露常量。
 *
 * 注意：整机主题（深/浅）由用户在设置里选，这里的两套方案是它的落点。
 * 「动态配色」（材料 You）已按需求移除，因此配色是固定的品牌色，不随壁纸变化。
 */

// ---------------------------------------------------------------- 品牌色（与主题无关）
/** 强调青：主数字、选中态、运行中胶囊、主要图标。 */
val CleanAccent = Color(0xFF00E5FF)

/** 危险红：停止/异常/删除。 */
val CleanDanger = Color(0xFFFF5252)

/** 成功绿：已配置状态圆点。 */
val CleanSuccess = Color(0xFF4CAF50)

/** 未配置状态圆点（深色主题下）。 */
val CleanInactiveDotDark = Color(0xFF757575)

/** 未配置状态圆点（浅色主题下）。 */
val CleanInactiveDotLight = Color(0xFF9E9E9E)

// ---------------------------------------------------------------- 深色方案（设计稿原值）
private val CleanBackgroundDark = Color(0xFF121212)
private val CleanSurfaceDark = Color(0xFF1E1E1E)
private val CleanSurfaceVariantDark = Color(0xFF2A2A2A)
private val CleanOnDark = Color(0xFFE0E0E0)
private val CleanOnVariantDark = Color(0xFF9E9E9E)
private val CleanOutlineDark = Color(0xFF2A2A2A)

// ---------------------------------------------------------------- 浅色方案（对应推导）
private val CleanBackgroundLight = Color(0xFFF6F7F9)
private val CleanSurfaceLight = Color(0xFFFFFFFF)
private val CleanSurfaceVariantLight = Color(0xFFECEEF1)
private val CleanOnLight = Color(0xFF1A1C1E)
private val CleanOnVariantLight = Color(0xFF5F6368)
private val CleanOutlineLight = Color(0xFFDFE2E6)
private val CleanAccentLight = Color(0xFF0091A8)

/**
 * 深色方案：严格按设计稿 §1.5 的取值。
 *
 * - 背景 `#121212`、卡片 `#1E1E1E`、主文字 `#E0E0E0`、次要文字 `#9E9E9E`、强调 `#00E5FF`、危险 `#FF5252`
 * - `onPrimary` 用深色：强调青很亮，白色文字在其上对比度不足
 */
val CleanDarkColorScheme: ColorScheme = darkColorScheme(
    primary = CleanAccent,
    onPrimary = Color(0xFF00252B),
    primaryContainer = Color(0xFF00363F),
    onPrimaryContainer = CleanAccent,
    secondary = CleanOnVariantDark,
    onSecondary = Color(0xFF121212),
    secondaryContainer = CleanSurfaceVariantDark,
    onSecondaryContainer = CleanOnDark,
    tertiary = CleanAccent,
    onTertiary = Color(0xFF00252B),
    background = CleanBackgroundDark,
    onBackground = CleanOnDark,
    surface = CleanBackgroundDark,
    onSurface = CleanOnDark,
    surfaceVariant = CleanSurfaceVariantDark,
    onSurfaceVariant = CleanOnVariantDark,
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = CleanSurfaceDark,
    surfaceContainerHigh = Color(0xFF252525),
    surfaceContainerHighest = CleanSurfaceVariantDark,
    surfaceBright = Color(0xFF2A2A2A),
    surfaceDim = Color(0xFF0D0D0D),
    outline = CleanOutlineDark,
    outlineVariant = Color(0xFF3A3A3A),
    error = CleanDanger,
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = CleanOnDark,
    inverseOnSurface = CleanBackgroundDark,
    inversePrimary = CleanAccentLight,
    scrim = Color(0xFF000000),
)

/** 浅色方案：与深色一一对应，保证两套主题下层级关系一致。 */
val CleanLightColorScheme: ColorScheme = lightColorScheme(
    primary = CleanAccentLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8ECF5),
    onPrimaryContainer = Color(0xFF00363F),
    secondary = CleanOnVariantLight,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = CleanSurfaceVariantLight,
    onSecondaryContainer = CleanOnLight,
    tertiary = CleanAccentLight,
    onTertiary = Color(0xFFFFFFFF),
    background = CleanBackgroundLight,
    onBackground = CleanOnLight,
    surface = CleanBackgroundLight,
    onSurface = CleanOnLight,
    surfaceVariant = CleanSurfaceVariantLight,
    onSurfaceVariant = CleanOnVariantLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = CleanSurfaceLight,
    surfaceContainerHigh = Color(0xFFF1F3F5),
    surfaceContainerHighest = CleanSurfaceVariantLight,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE6E8EB),
    outline = CleanOutlineLight,
    outlineVariant = Color(0xFFC9CDD2),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = CleanOnLight,
    inverseOnSurface = CleanBackgroundLight,
    inversePrimary = CleanAccent,
    scrim = Color(0xFF000000),
)

/** 状态圆点用色：未配置时随主题取灰，已配置时统一用绿。 */
val inactiveDotColor: Color
    @Composable
    get() = if (LocalDarkTheme.current) CleanInactiveDotDark else CleanInactiveDotLight
