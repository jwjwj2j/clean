package li.gkd.app.feature.inspect

import li.gkd.app.text.UiStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.gkd.app.a11y.A11yRuntime
import li.gkd.app.a11y.currentTopActivity
import li.gkd.app.data.NodeInfo
import li.gkd.app.data.info2nodeList
import li.gkd.app.domain.inspect.NodeTree
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.gkPageBottomSpace
import li.gkd.app.ui.component.rememberListScrollState
import li.gkd.app.ui.style.cardGap
import li.gkd.app.ui.style.iconSize
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.lineGap
import li.gkd.app.ui.style.pagePadding
import li.gkd.app.ui.style.scaffoldPadding

/**
 * 节点树审查页（CLEAN 恢复的能力，设计为**最小可用**版）。
 *
 * ## 为什么需要它
 * 规则匹配不上时，唯一能回答「为什么」的办法是**看清界面上到底有哪些节点**：
 * 节点树里有没有那个「跳过」按钮、它的 id/vid/text 是什么、是否 visibleToUser、
 * 是否 clickable。没有这个，写选择器就是猜。
 *
 * ## 与 GKD 原「快照」的区别（刻意收窄，符合 CLEAN 的产品边界）
 * 原快照体系包含**截图**、**文件落盘**、**分享/上传**、**磁贴/悬浮按钮/第三方触发**
 * 等一整套能力，已按 docs/08 决策 D3 整体下线。本页只保留其中
 * 「读取当前屏幕的节点树并在应用内查看」这一件事：
 * - **不截图**（不申请截图相关权限、不产生图片）
 * - **不落盘**（节点树只在内存中，退出即失）
 * - **不上传**（无网络行为）
 * - **无第三方入口**（不新增 exported 组件）
 *
 * 因此它不重新引入被移除的暴露面，只恢复诊断能力本身。
 */
@Serializable
data object NodeInspectRoute : NavKey

/** 抓取结果：节点列表 + 抓取时的应用/界面标识，便于判断抓的是哪一屏。 */
private data class NodeInspectSnapshot(
    val nodes: List<NodeInfo>,
    val appId: String,
    val activityId: String?,
    val message: String? = null,
)

@Composable
fun NodeInspectPage() {
    val pageScrollState = rememberListScrollState()
    val scrollBehavior = pageScrollState.scrollBehavior
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<NodeInspectSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val capture: () -> Unit = {
        if (!loading) {
            loading = true
            scope.launch {
                val appId = currentTopActivity.appId
                val activityId = currentTopActivity.activityId
                // info2nodeList 会遍历整棵无障碍节点树，属阻塞操作，放到 IO 线程。
                val nodes = withContext(Dispatchers.IO) {
                    runCatching { info2nodeList(A11yRuntime.getRoot()) }
                        .getOrElse { emptyList() }
                }
                snapshot = NodeInspectSnapshot(
                    nodes = nodes,
                    appId = appId,
                    activityId = activityId,
                    message = if (nodes.isEmpty()) UiStrings.node_inspect_empty else null,
                )
                // 默认只展开根节点，避免一屏几十个节点糊在一起
                expanded = nodes.filter { it.pid == -1 }.mapTo(mutableSetOf()) { it.id }
                loading = false
            }
        }
    }

    // 进入页面即抓一次：诊断场景下用户就是来看当前屏幕的
    androidx.compose.runtime.LaunchedEffect(Unit) { capture() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GkTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(text = UiStrings.node_inspect_title) },
                actions = {
                    GkIconButton(
                        imageVector = GkIcons.Autorenew,
                        onClick = capture,
                        contentDescription = UiStrings.node_inspect_refresh,
                    )
                },
            )
        },
    ) { contentPadding ->
        val current = snapshot
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (loading) UiStrings.node_inspect_loading else UiStrings.node_inspect_hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(modifier = Modifier.padding(contentPadding)) {
                NodeInspectHeader(current)
                val visible = NodeTree.flattenVisible(current.nodes, expanded)
                LazyColumn(
                    state = pageScrollState.listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(visible, key = { it.id }) { node ->
                        NodeInspectRow(
                            node = node,
                            expanded = node.id in expanded,
                            onToggle = {
                                expanded = if (node.id in expanded) {
                                    expanded - node.id
                                } else {
                                    expanded + node.id
                                }
                            },
                        )
                    }
                    gkPageBottomSpace()
                }
            }
        }
    }
}

/** 抓取信息头：抓的是哪个应用/界面、共多少节点。 */
@Composable
private fun NodeInspectHeader(snapshot: NodeInspectSnapshot) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pagePadding, vertical = cardGap),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(pagePadding)) {
            Text(
                text = snapshot.appId.ifEmpty { UiStrings.node_inspect_unknown_app },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(lineGap))
            Text(
                text = snapshot.activityId ?: UiStrings.action_log_activity_unknown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(lineGap))
            Text(
                text = UiStrings.node_inspect_count(snapshot.nodes.size.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.message != null) {
                Spacer(Modifier.height(lineGap))
                Text(
                    text = snapshot.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 单行节点。缩进体现层级；有子节点时可点击展开/收起。
 *
 * 只展示**写选择器真正会用到的**字段：name / text / id / vid / desc /
 * clickable / visibleToUser / childCount。bounds 与 index 一并给出便于定位。
 */
@Composable
private fun NodeInspectRow(
    node: NodeInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val attr = node.attr
    val hasChild = attr.childCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasChild, onClick = onToggle)
            .padding(
                start = itemHorizontalPadding + (attr.depth * 12).dp,
                end = itemHorizontalPadding,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            if (hasChild) {
                GkIcon(
                    imageVector = if (expanded) GkIcons.ExpandMore else GkIcons.KeyboardArrowRight,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
            }
        }
        Spacer(Modifier.width(lineGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${attr.name ?: "?"}  #${node.id}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val facts = buildList {
                attr.text?.takeIf { it.isNotEmpty() }?.let { add("text=$it") }
                attr.desc?.takeIf { it.isNotEmpty() }?.let { add("desc=$it") }
                attr.id?.let { add("id=$it") }
                attr.vid?.let { add("vid=$it") }
                if (attr.clickable) add("clickable")
                if (!attr.visibleToUser) add("invisible")
                if (hasChild) add("child=${attr.childCount}")
            }
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    // 等宽字体：id/vid 里的下划线与大小写更容易分辨，抄写不易出错
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "[${attr.left},${attr.top}][${attr.right},${attr.bottom}] " +
                        "${attr.width}x${attr.height}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

