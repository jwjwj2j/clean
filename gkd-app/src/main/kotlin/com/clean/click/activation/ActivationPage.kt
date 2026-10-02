package com.clean.click.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.gkd.app.text.UiStrings

/**
 * 激活门。已激活时渲染 [content]，否则渲染激活页。
 *
 * 用法（`ui/app/AppRoot.kt`）：
 * ```
 * ActivationGate { AppTheme { ... 原有内容 ... } }
 * ```
 */
@Composable
fun ActivationGate(content: @Composable () -> Unit) {
    val activated by ActivationManager.isActivatedFlow.collectAsStateWithLifecycle()
    if (activated) content() else ActivationPage()
}

/**
 * 激活页。
 *
 * v2 变更（取消设备绑定）：本页**不再展示设备码**。用户只需把卖家给的激活码粘进来。
 * 界面只做一件事：提示「激活码需在生成后 10 分钟内使用」，然后提交。
 * 倒计时显示在卖家的 keygen 页面上，App 端不做倒计时（避免用户改系统时间造成误导）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivationPage() {
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        busy = true
        message = null
        scope.launch {
            val outcome = withContext(Dispatchers.Default) { ActivationManager.activate(input) }
            message = when (outcome) {
                is ActivationManager.Outcome.Activated -> UiStrings.activation_success
                is ActivationManager.Outcome.InvalidInput -> outcome.message
                is ActivationManager.Outcome.Rejected -> activationErrorText(outcome)
            }
            busy = false
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = UiStrings.activation_title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = UiStrings.activation_subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { raw ->
                    input = raw.uppercase()
                    message = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(UiStrings.activation_input_label) },
                placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                isError = message != null,
                supportingText = {
                    message?.let {
                        Text(
                            text = it,
                            color = if (it == UiStrings.activation_success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                },
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = ::submit,
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(UiStrings.activation_submit)
            }

            Spacer(Modifier.height(20.dp))

            // 10 分钟窗口提示：放在提交按钮下方，用户失败时最容易回看这里
            Text(
                text = UiStrings.activation_window_hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = UiStrings.activation_terms_notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 到期/时间异常时的独立提示条。
 * 当前产品策略恒为永久码，因此 `needsRenewNotice` 不会触发；
 * 保留它是为了让将来若改回限时码时无需重建这层 UI。
 */
@Composable
fun ActivationRenewNotice(onDismiss: () -> Unit) {
    val snapshot by ActivationManager.snapshotFlow.collectAsStateWithLifecycle()
    if (!snapshot.needsRenewNotice) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = snapshot.daysRemaining?.let { UiStrings.activation_expiring_soon(it) }
                ?: UiStrings.activation_expiring_soon_unknown,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) { Text(UiStrings.activation_got_it) }
    }
}

private fun activationErrorText(outcome: ActivationManager.Outcome.Rejected): String =
    when (outcome.error) {
        // 对伪造类失败一律给同一句话，避免成为算法探测器（spec.md §8.1）
        ActivationError.Malformed,
        ActivationError.UnsupportedVersion,
        ActivationError.BadSignature,
        -> UiStrings.activation_invalid

        // 新增：签发后 10 分钟内未激活
        ActivationError.WindowExpired -> UiStrings.activation_window_expired

        ActivationError.Expired -> outcome.expiredAt
            ?.let { UiStrings.activation_expired_on(it.toString()) }
            ?: UiStrings.activation_expired

        ActivationError.ClockAnomaly -> UiStrings.activation_clock_anomaly
    }
