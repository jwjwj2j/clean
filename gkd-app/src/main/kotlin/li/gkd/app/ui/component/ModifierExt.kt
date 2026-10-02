package li.gkd.app.ui.component

import androidx.compose.ui.Modifier

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

// CLEAN：点击缩放（0.98 / 120ms，设计稿 §4.4）目前由 DashboardPage 的私有
// Modifier.pressScaleEffect 与 AppListPage 的内联实现各自提供，二者语义一致但重复。
// 本文件曾加过一份共享的 Modifier.pressAnimation，因无人使用（死代码）已删除；
// 待下一步把两个页面统一收敛到同一份实现时再落在这里。
