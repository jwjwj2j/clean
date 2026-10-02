package li.gkd.app.ui.app

import li.gkd.app.MainViewModel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.clean.click.activation.ActivationGate
import li.gkd.app.ui.style.AppTheme

@Composable
fun AppRoot() {
    val mainVm = MainViewModel.requireCurrent()
    // CLEAN：未激活时只渲染激活页；已激活用户看到的界面与原来完全一致。
    ActivationGate {
        AppTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                MainNavigation()
                AppOverlayHost()
                mainVm.permissionRequests.Render(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(1f),
                )
            }
        }
    }
}
