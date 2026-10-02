package li.gkd.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import li.gkd.app.text.UiStrings
import li.gkd.app.ui.style.emptyIconSize

object GkEmptyStateDefaults {
    val TopPadding = 80.dp
    val HorizontalPadding = 24.dp
    val ActionSpacing = 16.dp

    /** 图标与文字之间（设计稿 §2.5）。 */
    val IconSpacing = 16.dp

    /** 主文字与副文字之间（设计稿 §2.5）。 */
    val DescriptionSpacing = 6.dp
}

/**
 * Place inside the content area, after Scaffold insets and any page controls.
 *
 * CLEAN：按设计稿 §2.5 扩展为「图标 + 主文字 + 副文字 + 可选操作」。
 * 新增参数都是可选且有默认值，既有调用点（只传 text）的行为不变，
 * 只是主文字字号随全局正文档位（15sp）。
 */
@Composable
fun GkEmptyState(
    text: String = UiStrings.data_empty,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GkEmptyStateDefaults.HorizontalPadding,
                top = GkEmptyStateDefaults.TopPadding,
                end = GkEmptyStateDefaults.HorizontalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            GkIcon(
                imageVector = icon,
                modifier = Modifier.size(emptyIconSize),
                // 「更浅的灰」：在次要文字色基础上再降一档，避免空状态抢视觉
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                contentDescription = null,
            )
            Spacer(Modifier.height(GkEmptyStateDefaults.IconSpacing))
        }
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (description != null) {
            Spacer(Modifier.height(GkEmptyStateDefaults.DescriptionSpacing))
            Text(
                text = description,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(GkEmptyStateDefaults.ActionSpacing))
            action()
        }
    }
}
