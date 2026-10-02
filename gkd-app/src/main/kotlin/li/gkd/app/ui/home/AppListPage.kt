package li.gkd.app.ui.home

import li.gkd.app.ui.component.GkPageBottomSpaceDefaults
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.MainViewModel

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import li.gkd.app.text.UiStrings
import li.gkd.app.MainActivity
import li.gkd.app.data.AppInfo
import li.gkd.app.domain.rule.RuleSummary
import li.gkd.app.permission.PermissionStates
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.ui.AppConfigRoute
import li.gkd.app.ui.share.ListPlaceholder
import li.gkd.app.ui.share.noRippleClickable
import li.gkd.app.ui.style.CleanSuccess
import li.gkd.app.ui.style.appIconInGrid
import li.gkd.app.ui.style.cardCorner
import li.gkd.app.ui.style.cardGap
import li.gkd.app.ui.style.emptyIconSize
import li.gkd.app.ui.style.filterChipHeight
import li.gkd.app.ui.style.gridCellHeight
import li.gkd.app.ui.style.gridCellPadding
import li.gkd.app.ui.style.iconTextGap
import li.gkd.app.ui.style.inactiveDotColor
import li.gkd.app.ui.style.lineGap
import li.gkd.app.ui.style.pagePadding
import li.gkd.app.ui.style.pressDurationMs
import li.gkd.app.ui.style.pressScale
import li.gkd.app.ui.style.statusDotSize
import li.gkd.app.util.AppGroupOption
import li.gkd.app.util.AppSortOption
import li.gkd.app.util.findOption
import li.gkd.app.util.getUpDownTransform
import li.gkd.app.ui.share.launchUiAction
import li.gkd.app.util.TimeUtils.throttle
import li.gkd.app.ui.icon.GkSearchCloseIconButton
import li.gkd.app.ui.icon.GkBlockCloseIconButton
import li.gkd.app.ui.component.GkAppBarTextField
import li.gkd.app.ui.component.GkAppIcon
import li.gkd.app.ui.component.GkAppNameText
import li.gkd.app.ui.component.GkCheckbox
import li.gkd.app.ui.component.GkFilterIconButton
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkMenuGroupCard
import li.gkd.app.ui.component.GkMenuItemCheckbox
import li.gkd.app.ui.component.GkMenuItemRadioButton
import li.gkd.app.ui.component.GkQueryPkgAuthCard
import li.gkd.app.ui.component.GkRuleStatsData
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.autoFocus
import li.gkd.app.ui.component.rememberListScrollState

/**
 * 设计稿 §2.2 的过滤 Tab。
 *
 * - `All` 直接对应既有的 `AppListUiState.showAllApps` 语义（不过滤）。
 * - `Configured` / `Unconfigured` 是本次视觉重构新增的**展示层**派生筛选：判定依据是
 *   既有的 `RuleSummary`（全局组数量 > 0 或存在启用中的应用组），等价于 `GkRuleStatsData.hasRules`。
 *   它不改动 `AppListVm` / `AppFilter` 的任何状态。
 */
private enum class GridFilterTab(val label: String) {
    All("全部"),
    Configured("已配置"),
    Unconfigured("未配置"),
}

/** 某个包名在现有 `RuleSummary` 下是否存在规则，等价于 `GkRuleStatsData.hasRules`。 */
private fun appHasConfiguredRules(ruleSummary: RuleSummary, appId: String): Boolean {
    return (ruleSummary.appIdToGlobalGroupCount[appId] ?: 0) > 0 ||
        ruleSummary.appIdToAllGroups[appId].orEmpty().any { it.enable }
}

/**
 * `ListScrollState` 内部持有 `LazyListState`，双列网格需要 `LazyGridState`。
 * 这里沿用完全相同的「重置滚动 + 顶栏 scrollBehavior」语义，只把内部状态换成网格状态。
 */
@Stable
private class GridScrollState(
    val scrollBehavior: TopAppBarScrollBehavior,
    val gridState: LazyGridState,
    private val coroutineScope: CoroutineScope,
) {
    private var resetJob: Job? = null

    private fun requestScrollReset() {
        resetJob?.cancel()
        resetJob = null
        scrollBehavior.resetScrollState()
        gridState.requestScrollToItem(0)
    }

    fun resetScroll() {
        resetJob?.cancel()
        resetJob = null
        requestScrollReset()
    }

    suspend fun resetScrollAndAwait() {
        resetJob?.cancelAndJoin()
        performScrollReset()
    }

    private suspend fun performScrollReset() {
        scrollBehavior.resetScrollState()
        gridState.scrollToItem(0)
    }
}

private fun TopAppBarScrollBehavior.resetScrollState() {
    state.heightOffset = 0f
    state.contentOffset = 0f
}

/** 保存网格的首个可见项与偏移，等价于 `LazyGridState.Saver` 的可保存子集。 */
private val GridStateSaver: Saver<LazyGridState, Any> = Saver(
    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
    restore = { saved ->
        val values = saved as List<*>
        LazyGridState(
            firstVisibleItemIndex = values[0] as Int,
            firstVisibleItemScrollOffset = values[1] as Int,
        )
    },
)

private class GridListChangeMarker<T>(
    var list: List<T>,
    var leadingItemKey: Any?,
)

/** 与 `ListScrollState.ResetOnListChange` 同语义，用于网格滚动状态。 */
@Composable
private fun <T> GridResetOnItemKeysChange(
    list: List<T>,
    key: (T) -> Any,
    leadingItemKey: Any? = null,
    onChange: () -> Unit,
) {
    val previous = remember { GridListChangeMarker(list, leadingItemKey) }
    SideEffect {
        val changed = previous.leadingItemKey != leadingItemKey ||
            (previous.list !== list && (
                previous.list.size != list.size ||
                    list.indices.any { index -> key(previous.list[index]) != key(list[index]) }
                ))
        previous.list = list
        previous.leadingItemKey = leadingItemKey
        if (changed) onChange()
    }
}

@Composable
fun useAppListPage(): ScaffoldExt {
    val mainVm = MainViewModel.requireCurrent()
    val context = LocalActivity.current as MainActivity

    val vm = viewModel { AppListVm(mainVm) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val store by storeFlow.collectAsStateWithLifecycle()
    val appInfos = state.appInfos
    val ruleSummary = state.ruleSummary

    val showSearchBar = state.showSearchBar
    val refreshing = state.refreshing
    val pullToRefreshState = rememberPullToRefreshState()
    val editWhiteListMode = state.editWhiteListMode
    var filterTab by remember { mutableStateOf(GridFilterTab.All) }
    // 顶栏的 scrollBehavior 仍由既有 hook 提供，只是列表状态换成网格状态。
    val scrollBehavior = rememberListScrollState().scrollBehavior
    val gridState = rememberSaveable(saver = GridStateSaver) { LazyGridState(0, 0) }
    val coroutineScope = rememberCoroutineScope()
    val pageScrollState = remember(scrollBehavior, gridState, coroutineScope) {
        GridScrollState(
            scrollBehavior = scrollBehavior,
            gridState = gridState,
            coroutineScope = coroutineScope,
        )
    }
    GridResetOnItemKeysChange(
        appInfos,
        key = { it.id },
        leadingItemKey = if (state.canQueryPackages) null else 1,
    ) {
        pageScrollState.resetScroll()
    }
    ResetPageScrollOnRequest(BottomNavItem.AppList, pageScrollState::resetScrollAndAwait)
    return ScaffoldExt(
        navItem = BottomNavItem.AppList,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DisposableEffect(null) {
                onDispose {
                    vm.onLeaveScreen()
                }
            }
            GkTopAppBar(scrollBehavior = scrollBehavior, title = {
                val firstShowSearchBar = remember { showSearchBar }
                if (showSearchBar) {
                    BackHandler {
                        if (!context.imeController.requestHide()) {
                            vm.closeSearch()
                        }
                    }
                    GkAppBarTextField(
                        value = state.searchText,
                        onValueChange = vm::setSearchText,
                        hint = UiStrings.app_name_id_input_hint,
                        modifier = if (firstShowSearchBar) Modifier else Modifier.autoFocus(),
                    )
                } else {
                    val titleModifier = Modifier
                        .noRippleClickable(
                            onClick = throttle {
                                pageScrollState.resetScroll()
                            }
                        )
                    if (editWhiteListMode) {
                        BackHandler(onBack = vm::closeEditWhiteListMode)
                    }
                    AnimatedContent(
                        targetState = editWhiteListMode,
                        transitionSpec = { getUpDownTransform() },
                    ) { localEditWhiteListMode ->
                        if (localEditWhiteListMode) {
                            Text(
                                modifier = titleModifier,
                                text = UiStrings.app_whitelist,
                            )
                        } else {
                            Text(
                                modifier = titleModifier,
                                text = BottomNavItem.AppList.label,
                            )
                        }
                    }
                }
            }, actions = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(pagePadding),
                ) {
                    if (state.queryPackagesAbnormal) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
                            GkIconButton(
                                imageVector = GkIcons.WarningAmber,
                                contentDescription = PermissionStates.queryPackages.name + UiStrings.error_label,
                                onClick = throttle(vm.scope.launchUiAction {
                                    mainVm.dialogRequests.showMessage(
                                        title = UiStrings.permission_error,
                                        text = UiStrings.app_list_permission_error_description(PermissionStates.queryPackages.name)
                                    )
                                }),
                            )
                        }
                    }
                    // 搜索：24dp 图标按钮，点击后仍走既有「展开顶栏输入框」交互。
                    GkSearchCloseIconButton(
                        onClick = throttle(vm::toggleSearch),
                        isSearchOpen = showSearchBar,
                        contentDescription = if (showSearchBar) UiStrings.search_close else UiStrings.app_list_search,
                    )
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        // 筛选：24dp 图标按钮，点击后仍弹出既有的排序/分组/筛选面板。
                        GkFilterIconButton(
                            filtered = !state.showAllApps,
                            contentDescription = UiStrings.sort_filter,
                            onClick = {
                                expanded = true
                            }
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            GkMenuGroupCard(inTop = true, title = UiStrings.sort_title) {
                                AppSortOption.objects.forEach { option ->
                                    GkMenuItemRadioButton(
                                        text = option.label,
                                        selected = AppSortOption.objects.findOption(store.appSort) == option,
                                        onClick = { vm.setSortType(option) },
                                    )
                                }
                            }
                            GkMenuGroupCard(title = UiStrings.group_title) {
                                AppGroupOption.normalObjects.forEach { option ->
                                    val newValue = option.invert(store.appGroupType)
                                    GkMenuItemCheckbox(
                                        enabled = newValue != 0,
                                        text = option.label,
                                        checked = option.include(store.appGroupType),
                                        onClick = { vm.setAppGroupType(newValue) },
                                    )
                                }
                            }
                            GkMenuGroupCard(title = UiStrings.filter_title) {
                                GkMenuItemCheckbox(
                                    text = UiStrings.whitelist_title,
                                    checked = store.showBlockApp,
                                    onClick = {
                                        vm.setShowBlockApp(!store.showBlockApp)
                                    },
                                )
                            }
                        }
                    }
                    // 多选（编辑白名单）入口：设计稿未规定，但既有功能必须保留。
                    GkBlockCloseIconButton(
                        isClose = editWhiteListMode,
                        contentDescription = UiStrings.whitelist_edit_mode_toggle,
                        onClickLabel = if (editWhiteListMode) UiStrings.edit_exit else UiStrings.edit_enter,
                        onClick = vm::toggleEditWhiteListMode,
                    )
                }
            })
        },
        floatingActionButton = {
            // CLEAN：原「编辑白名单」FAB 指向的页面已随局部禁用功能下线
        }
    ) { contentPadding ->
        val gridApps = remember(appInfos, ruleSummary, filterTab) {
            appInfos.filter { appInfo ->
                val hasRules = appHasConfiguredRules(ruleSummary, appInfo.id)
                when (filterTab) {
                    GridFilterTab.All -> true
                    GridFilterTab.Configured -> hasRules
                    GridFilterTab.Unconfigured -> !hasRules
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            // 设计稿 §2.2：工具栏下方一排可横向滚动的过滤 Chip。
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(filterChipHeight),
                contentPadding = PaddingValues(horizontal = pagePadding),
                horizontalArrangement = Arrangement.spacedBy(iconTextGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(GridFilterTab.entries.size, key = { GridFilterTab.entries[it].name }) { index ->
                    val tab = GridFilterTab.entries[index]
                    FilterChip(
                        selected = tab == filterTab,
                        onClick = { filterTab = tab },
                        label = { Text(text = tab.label) },
                        modifier = Modifier.height(filterChipHeight),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = FilterChipDefaults.filterChipColors(
                            // 设计稿 §2.2 的 Chip 显式取色，不在现有颜色令牌里。
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = null,
                        elevation = null,
                    )
                }
            }
            PullToRefreshBox(
                modifier = Modifier.fillMaxSize(),
                state = pullToRefreshState,
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = pagePadding,
                        end = pagePadding,
                        top = iconTextGap,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(cardGap),
                    verticalArrangement = Arrangement.spacedBy(cardGap),
                ) {
                    if (!state.canQueryPackages) {
                        item(key = 1, contentType = 1, span = { GridItemSpan(maxLineSpan) }) {
                            GkQueryPkgAuthCard()
                        }
                    }
                    items(gridApps, { it.id }) { appInfo ->
                        val stats = if (editWhiteListMode) null else GkRuleStatsData(
                            globalGroups = ruleSummary.appIdToGlobalGroupCount[appInfo.id] ?: 0,
                            appGroups = ruleSummary.appIdToAllGroups[appInfo.id]?.count { it.enable } ?: 0,
                            enabledOnly = true,
                        )
                        AppGridCell(
                            appInfo = appInfo,
                            stats = stats,
                            editWhiteListMode = editWhiteListMode,
                            inWhiteList = appInfo.id in state.whiteListAppIds,
                            onClick = {
                                if (editWhiteListMode) {
                                    vm.toggleWhiteList(appInfo.id)
                                } else {
                                    context.imeController.requestHide()
                                    mainVm.navigatePage(AppConfigRoute(appInfo.id))
                                }
                            },
                        )
                    }
                    item(key = ListPlaceholder.KEY, contentType = ListPlaceholder.TYPE, span = { GridItemSpan(maxLineSpan) }) {
                        if (gridApps.isEmpty()) {
                            AppListEmptyState(
                                hint = when {
                                    state.searchText.isNotEmpty() && state.showAllApps -> UiStrings.search_no_results
                                    state.searchText.isNotEmpty() -> UiStrings.search_no_results_filter_hint
                                    else -> null
                                }
                            )
                            GkPageBottomSpace(height = GkPageBottomSpaceDefaults.CompactHeight)
                        } else {
                            GkPageBottomSpace()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设计稿 §2.5 的空状态：64dp 灰色图标 + `暂无应用` + 副文字。
 *
 * 搜索/筛选导致的结果为空时沿用既有文案（`hint`），否则显示设计稿的默认副文字。
 */
@Composable
private fun AppListEmptyState(hint: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = pagePadding, top = pagePadding * 4, end = pagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GkIcon(
            imageVector = GkIcons.Apps,
            modifier = Modifier.size(emptyIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            modifier = Modifier.padding(top = cardCorner),
            text = "暂无应用",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = Modifier.padding(top = lineGap),
            text = hint ?: "去「全部」里配置规则吧",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/** 设计稿 §2.3 的双列网格格子；点击进入应用详情，格子内不放开关。 */
@Composable
private fun AppGridCell(
    appInfo: AppInfo,
    stats: GkRuleStatsData?,
    editWhiteListMode: Boolean,
    inWhiteList: Boolean,
    onClick: () -> Unit,
) {
    val hasRules = stats?.hasRules == true
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressScale else 1f,
        animationSpec = tween(pressDurationMs),
        label = "AppGridCellPress",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridCellHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = throttle(onClick),
            )
            .clearAndSetSemantics {
                contentDescription = if (editWhiteListMode) {
                    appInfo.name
                } else {
                    UiStrings.app_whitelist_state_description(appInfo.name, stats?.takeIf { it.hasRules }?.description ?: appInfo.id)
                }
                if (inWhiteList) {
                    stateDescription = UiStrings.whitelist_member
                } else if (editWhiteListMode) {
                    stateDescription = UiStrings.whitelist_not_member
                }
                onClick(
                    label = if (editWhiteListMode) if (inWhiteList) UiStrings.whitelist_remove else UiStrings.whitelist_add else UiStrings.rule_summary_open,
                    action = null
                )
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(gridCellPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(appIconInGrid)
            ) {
                GkAppIcon(appId = appInfo.id, size = appIconInGrid)
                if (editWhiteListMode) {
                    GkCheckbox(
                        modifier = Modifier.align(Alignment.TopEnd),
                        key = appInfo.id,
                        checked = inWhiteList,
                    )
                } else if (inWhiteList) {
                    GkIcon(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp),
                        imageVector = GkIcons.Block,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge)
            ) {
                GkAppNameText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = iconTextGap),
                    appInfo = appInfo,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = iconTextGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(statusDotSize)
                        .background(
                            color = if (hasRules) CleanSuccess else inactiveDotColor,
                            shape = CircleShape,
                        )
                )
                Text(
                    modifier = Modifier.padding(start = iconTextGap),
                    text = if (hasRules) GridFilterTab.Configured.label else GridFilterTab.Unconfigured.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
