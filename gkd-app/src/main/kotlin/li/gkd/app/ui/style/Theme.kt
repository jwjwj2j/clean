package li.gkd.app.ui.style

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import li.gkd.app.app
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.ui.share.LocalDarkTheme
import li.gkd.app.ui.share.LocalIsTalkbackEnabled

private val LightColorScheme = lightColorScheme()
private val DarkColorScheme = darkColorScheme()

private fun createAppearanceFlow(scope: CoroutineScope) =
    storeFlow
        .map { it.enableDarkTheme }
        .distinctUntilChanged()
        .debounce(300)
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            storeFlow.value.enableDarkTheme,
        )

@Composable
fun AppTheme(
    invertedTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appearanceFlow = remember(scope) { createAppearanceFlow(scope) }
    val enableDarkTheme = appearanceFlow.collectAsStateWithLifecycle().value
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = (enableDarkTheme ?: systemInDarkTheme).let {
        if (invertedTheme) !it else it
    }
    // CLEAN：原「动态配色」开关已移除，固定使用 CLEAN 自己的配色方案，
    // 不再随壁纸取色（Material You），以保证品牌观感一致。
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val activity = LocalActivity.current
    if (activity != null) {
        LaunchedEffect(darkTheme) {
            // https://github.com/gkd-kit/gkd/pull/421
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
            }
        }
        val bg = colorScheme.background.toArgb()
        LaunchedEffect(darkTheme, bg) {
            activity.window.decorView.setBackgroundColor(bg)
        }
    }

    var isTalkbackEnabled by remember { mutableStateOf(app.a11yManager.isTouchExplorationEnabled) }
    DisposableEffect(null) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener {
            isTalkbackEnabled = it
        }
        app.a11yManager.addTouchExplorationStateChangeListener(listener)
        onDispose {
            app.a11yManager.removeTouchExplorationStateChangeListener(listener)
        }
    }
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalIsTalkbackEnabled provides isTalkbackEnabled
    ) {
        MaterialTheme(
            colorScheme = colorScheme.animation(),
            content = content,
        )
    }
}

@Composable
private fun Color.animation() = animateColorAsState(
    targetValue = this,
    animationSpec = tween(durationMillis = 500),
    label = "animation"
).value

@Composable
private fun ColorScheme.animation(): ColorScheme {
    return copy(
        primary = primary.animation(),
        onPrimary = onPrimary.animation(),
        primaryContainer = primaryContainer.animation(),
        onPrimaryContainer = onPrimaryContainer.animation(),
        inversePrimary = inversePrimary.animation(),
        secondary = secondary.animation(),
        onSecondary = onSecondary.animation(),
        secondaryContainer = secondaryContainer.animation(),
        onSecondaryContainer = onSecondaryContainer.animation(),
        tertiary = tertiary.animation(),
        onTertiary = onTertiary.animation(),
        tertiaryContainer = tertiaryContainer.animation(),
        onTertiaryContainer = onTertiaryContainer.animation(),
        background = background.animation(),
        onBackground = onBackground.animation(),
        surface = surface.animation(),
        onSurface = onSurface.animation(),
        surfaceVariant = surfaceVariant.animation(),
        onSurfaceVariant = onSurfaceVariant.animation(),
        surfaceTint = surfaceTint.animation(),
        inverseSurface = inverseSurface.animation(),
        inverseOnSurface = inverseOnSurface.animation(),
        error = error.animation(),
        onError = onError.animation(),
        errorContainer = errorContainer.animation(),
        onErrorContainer = onErrorContainer.animation(),
        outline = outline.animation(),
        outlineVariant = outlineVariant.animation(),
        scrim = scrim.animation(),
        surfaceBright = surfaceBright.animation(),
        surfaceDim = surfaceDim.animation(),
        surfaceContainer = surfaceContainer.animation(),
        surfaceContainerHigh = surfaceContainerHigh.animation(),
        surfaceContainerHighest = surfaceContainerHighest.animation(),
        surfaceContainerLow = surfaceContainerLow.animation(),
        surfaceContainerLowest = surfaceContainerLowest.animation(),
    )
}
