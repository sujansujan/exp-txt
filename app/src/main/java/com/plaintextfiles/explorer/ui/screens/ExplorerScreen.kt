package com.plaintextfiles.explorer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.AppSettings
import com.plaintextfiles.explorer.model.DualPaneMode
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.PaneId
import com.plaintextfiles.explorer.model.PaneState
import com.plaintextfiles.explorer.ui.ExplorerUiState
import com.plaintextfiles.explorer.ui.ExplorerViewModel
import com.plaintextfiles.explorer.ui.PlainDialogState
import com.plaintextfiles.explorer.ui.components.AsciiDivider
import com.plaintextfiles.explorer.ui.components.AsciiUsageBar
import com.plaintextfiles.explorer.ui.components.FileTypeIcon
import com.plaintextfiles.explorer.ui.components.PlainTextActionLink
import com.plaintextfiles.explorer.ui.theme.LocalPlainTextPalette
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ExplorerScreen(
    viewModel: ExplorerViewModel,
    onRequestAllFilesPermission: () -> Unit,
    onOpenExternalFile: (File) -> Unit,
    onShareExternalFile: (File) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val palette = LocalPlainTextPalette.current

    if (state.isSettingsOpen) {
        SettingsScreen(
            settings = state.settings,
            pinnedCount = state.pinnedPaths.size,
            viewModel = viewModel
        )
        return
    }

    val activePane = state.getPane(state.activePaneId)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Ultra-Compact Top Bar with 3-Dot Menu `[⋮]`
        CompactTopBarWithThreeDotMenu(
            state = state,
            activePane = activePane,
            viewModel = viewModel
        )

        // 2. Permission Banner (only shown if storage access is missing)
        if (!state.hasStoragePermission) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.backgroundDark)
                    .border(1.dp, palette.orange)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "! Internal Storage Permission Required",
                    color = palette.orange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                PlainTextActionLink(
                    label = "GRANT",
                    accentColor = palette.green,
                    onClick = onRequestAllFilesPermission
                )
            }
        }

        // 3. Inline Filter Bar (only visible when Filter is toggled on)
        if (activePane.isSearchVisible || activePane.searchQuery.isNotEmpty()) {
            InlineFilterBar(
                paneId = activePane.paneId,
                query = activePane.searchQuery,
                onQueryChange = { viewModel.updateSearchQuery(activePane.paneId, it) },
                onClose = { viewModel.setPaneSearchVisible(activePane.paneId, false) }
            )
        }

        // 4. Main Dual-Pane / Single-Pane File Workspace (Maximized Space)
        var totalHeightPx by remember { mutableFloatStateOf(1000f) }
        var totalWidthPx by remember { mutableFloatStateOf(1000f) }
        val splitRatio = state.settings.splitRatio

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { sz ->
                    totalWidthPx = sz.width.toFloat().coerceAtLeast(1f)
                    totalHeightPx = sz.height.toFloat().coerceAtLeast(1f)
                }
        ) {

            when (state.settings.dualPaneMode) {
                DualPaneMode.SPLIT_VERTICAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CompactFilePane(
                            paneState = state.paneA,
                            isFocused = state.activePaneId == PaneId.PANE_A,
                            settings = state.settings,
                            viewModel = viewModel,
                            onOpenExternalFile = onOpenExternalFile,
                            modifier = Modifier
                                .weight(splitRatio)
                                .fillMaxWidth()
                        )
                        DraggableHorizontalSplitBar(
                            activePaneId = state.activePaneId,
                            onDragDeltaPx = { dy ->
                                val nextRatio = splitRatio + (dy / totalHeightPx)
                                viewModel.updateSplitRatio(nextRatio)
                            },
                            onDoubleTapReset = { viewModel.updateSplitRatio(0.50f) },
                            onSwapPanes = { viewModel.swapPanes() }
                        )
                        CompactFilePane(
                            paneState = state.paneB,
                            isFocused = state.activePaneId == PaneId.PANE_B,
                            settings = state.settings,
                            viewModel = viewModel,
                            onOpenExternalFile = onOpenExternalFile,
                            modifier = Modifier
                                .weight(1f - splitRatio)
                                .fillMaxWidth()
                        )
                    }
                }

                DualPaneMode.SPLIT_HORIZONTAL -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        CompactFilePane(
                            paneState = state.paneA,
                            isFocused = state.activePaneId == PaneId.PANE_A,
                            settings = state.settings,
                            viewModel = viewModel,
                            onOpenExternalFile = onOpenExternalFile,
                            modifier = Modifier
                                .weight(splitRatio)
                                .fillMaxHeight()
                        )
                        DraggableVerticalSplitBar(
                            onDragDeltaPx = { dx ->
                                val nextRatio = splitRatio + (dx / totalWidthPx)
                                viewModel.updateSplitRatio(nextRatio)
                            },
                            onDoubleTapReset = { viewModel.updateSplitRatio(0.50f) }
                        )
                        CompactFilePane(
                            paneState = state.paneB,
                            isFocused = state.activePaneId == PaneId.PANE_B,
                            settings = state.settings,
                            viewModel = viewModel,
                            onOpenExternalFile = onOpenExternalFile,
                            modifier = Modifier
                                .weight(1f - splitRatio)
                                .fillMaxHeight()
                        )
                    }
                }

                DualPaneMode.SWIPE_PAGER -> {
                    val pagerState = rememberPagerState(
                        initialPage = state.activePaneId.index,
                        pageCount = { 2 }
                    )
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(pagerState.currentPage) {
                        val target = if (pagerState.currentPage == 0) PaneId.PANE_A else PaneId.PANE_B
                        viewModel.setActivePane(target)
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Ultra-compact 1-line Pager Tab Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.backgroundDark)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompactPagerTab(
                                paneState = state.paneA,
                                isActive = state.activePaneId == PaneId.PANE_A,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    viewModel.setActivePane(PaneId.PANE_A)
                                    coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                }
                            )
                            Text(
                                text = " | ",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            CompactPagerTab(
                                paneState = state.paneB,
                                isActive = state.activePaneId == PaneId.PANE_B,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    viewModel.setActivePane(PaneId.PANE_B)
                                    coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                }
                            )
                        }
                        AsciiDivider(char = '=')
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) { page ->
                            val paneState = if (page == 0) state.paneA else state.paneB
                            CompactFilePane(
                                paneState = paneState,
                                isFocused = true,
                                settings = state.settings,
                                viewModel = viewModel,
                                onOpenExternalFile = onOpenExternalFile,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                DualPaneMode.SINGLE_PANE -> {
                    CompactFilePane(
                        paneState = activePane,
                        isFocused = true,
                        settings = state.settings,
                        viewModel = viewModel,
                        onOpenExternalFile = onOpenExternalFile,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 5. Auto-Hiding 1-Line Bottom Progress Strip (Only visible during active file/archive operations)
        state.transferProgress?.let { prog ->
            AsciiDivider(char = '=')
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.backgroundDark)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsciiUsageBar(
                    fraction = prog.fraction,
                    label = prog.label,
                    barWidthChars = 12
                )
            }
        }
    }

    // Double-Tap File Details Modal
    val detailsEntry = state.detailsEntry
    val detailsPane = state.detailsPaneId
    if (detailsEntry != null && detailsPane != null) {
        DoubleTapFileDetailsModal(
            paneId = detailsPane,
            entry = detailsEntry,
            details = state.detailsInfo,
            isLoading = state.isLoadingDetails,
            viewModel = viewModel
        )
    }

    // Long-Press Operations Menu & Other Dialogs
    state.activeDialog?.let { dlg ->
        ActivePlainDialogHost(
            dialogState = dlg,
            hasClipboard = state.clipboard != null,
            viewModel = viewModel,
            onOpenExternal = onOpenExternalFile,
            onShareExternal = onShareExternalFile
        )
    }
}

/**
 * Single-row Compact Top Bar with contextual Paste/Selection badges and the `[⋮]` 3-dot menu.
 */
@Composable
private fun CompactTopBarWithThreeDotMenu(
    state: ExplorerUiState,
    activePane: PaneState,
    viewModel: ExplorerViewModel
) {
    val palette = LocalPlainTextPalette.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: App Title + Active Pane Indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "exp-txt",
                color = palette.purple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "[${activePane.paneId.shortCode}:${activePane.sortConfig.field.shortTag}${activePane.sortConfig.order.symbol}]",
                color = palette.comment,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                modifier = Modifier.clickable {
                    viewModel.showDialog(PlainDialogState.SortModal(activePane.paneId))
                }
            )
        }

        // Right: Contextual Selection / Paste + Filter + 3-Dot Menu `[⋮]`
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (activePane.selectedKeys.isNotEmpty()) {
                PlainTextActionLink(
                    label = "${activePane.selectedKeys.size} SEL",
                    accentColor = palette.yellow,
                    onClick = { viewModel.openSelectionActionMenu(activePane.paneId) }
                )
                PlainTextActionLink(
                    label = "X",
                    accentColor = palette.red,
                    onClick = { viewModel.clearSelection(activePane.paneId) }
                )
            }

            state.clipboard?.let { clip ->
                PlainTextActionLink(
                    label = "PASTE(${clip.entries.size})",
                    accentColor = palette.green,
                    onClick = { viewModel.pasteClipboardIntoPane(activePane.paneId) }
                )
                PlainTextActionLink(
                    label = "X",
                    accentColor = palette.comment,
                    onClick = { viewModel.clearClipboard() }
                )
            }

            // 3-Dot Menu Container
            Box {
                PlainTextActionLink(
                    label = "⋮",
                    accentColor = palette.cyan,
                    onClick = { menuExpanded = true }
                )

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier
                        .background(palette.backgroundDark)
                        .border(1.dp, palette.currentLine)
                ) {
                    ThreeDotMenuItem("Sort By... (${activePane.sortConfig.field.shortTag} ${activePane.sortConfig.order.symbol})") {
                        menuExpanded = false
                        viewModel.showDialog(PlainDialogState.SortModal(activePane.paneId))
                    }
                    ThreeDotMenuItem(if (activePane.isSearchVisible) "Hide Filter Bar" else "Filter / Search") {
                        menuExpanded = false
                        viewModel.setPaneSearchVisible(activePane.paneId, !activePane.isSearchVisible)
                    }
                    ThreeDotMenuItem("+ New Folder") {
                        menuExpanded = false
                        viewModel.showDialog(PlainDialogState.CreateFolder(activePane.paneId))
                    }
                    ThreeDotMenuItem("+ New File") {
                        menuExpanded = false
                        viewModel.showDialog(PlainDialogState.CreateFile(activePane.paneId))
                    }
                    ThreeDotMenuItem(
                        if (activePane.selectedKeys.size == activePane.filteredEntries.size && activePane.filteredEntries.isNotEmpty()) {
                            "Clear Selection"
                        } else {
                            "Select All (${activePane.filteredEntries.size})"
                        }
                    ) {
                        menuExpanded = false
                        viewModel.selectAllInPane(activePane.paneId)
                    }
                    if (activePane.selectedKeys.size > 1) {
                        ThreeDotMenuItem("Batch Rename (${activePane.selectedKeys.size})...") {
                            menuExpanded = false
                            val sel = activePane.entries.filter { it.uniqueKey in activePane.selectedKeys }
                            viewModel.showDialog(PlainDialogState.BatchRename(activePane.paneId, sel))
                        }
                    }
                    ThreeDotMenuItem("View: ${nextLayoutModeLabel(state.settings.dualPaneMode)}") {
                        menuExpanded = false
                        val modes = DualPaneMode.entries
                        val next = modes[(modes.indexOf(state.settings.dualPaneMode) + 1) % modes.size]
                        viewModel.updateSettings { it.copy(dualPaneMode = next) }
                    }
                    ThreeDotMenuItem("Swap Panes (P1 <-> P2)") {
                        menuExpanded = false
                        viewModel.swapPanes()
                    }
                    ThreeDotMenuItem("Sync Other Pane (${activePane.paneId.shortCode} -> ${activePane.paneId.opposite().shortCode})") {
                        menuExpanded = false
                        viewModel.syncOppositePaneTo(activePane.paneId)
                    }
                    ThreeDotMenuItem("Refresh") {
                        menuExpanded = false
                        viewModel.refreshAllPanes()
                    }
                    ThreeDotMenuItem("Settings...") {
                        menuExpanded = false
                        viewModel.openSettingsScreen()
                    }
                }
            }
        }
    }
    AsciiDivider(char = '=')
}

private fun nextLayoutModeLabel(current: DualPaneMode): String {
    val modes = DualPaneMode.entries
    val next = modes[(modes.indexOf(current) + 1) % modes.size]
    return "${current.shortLabel} -> ${next.shortLabel}"
}

@Composable
private fun ThreeDotMenuItem(
    title: String,
    onClick: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    DropdownMenuItem(
        text = {
            Text(
                text = title,
                color = palette.foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        },
        onClick = onClick
    )
}

@Composable
private fun InlineFilterBar(
    paneId: PaneId,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "FILTER(${paneId.shortCode}):",
            color = palette.pink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .background(palette.background)
                .border(1.dp, palette.currentLine)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = palette.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp
                ),
                cursorBrush = SolidColor(palette.pink),
                modifier = Modifier.fillMaxWidth()
            )
        }
        PlainTextActionLink(
            label = "X",
            accentColor = palette.red,
            onClick = onClose
        )
    }
    AsciiDivider()
}

@Composable
private fun CompactPagerTab(
    paneState: PaneState,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    val folderShort = paneState.compactTitle.substringAfterLast('/').ifEmpty { "/" }
    val selSuffix = if (paneState.selectedKeys.isNotEmpty()) " [${paneState.selectedKeys.size}]" else ""

    Row(
        modifier = modifier
            .background(if (isActive) palette.lineHighlight else palette.backgroundDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${if (isActive) "*" else " "}${paneState.paneId.shortCode}: $folderShort$selSuffix",
            color = if (isActive) palette.purple else palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "(${paneState.filteredEntries.size})",
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

/**
 * Draggable horizontal divider between Top and Bottom panes in SPLIT_VERTICAL mode.
 * Double-tap resets split ratio to 50/50.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DraggableHorizontalSplitBar(
    activePaneId: PaneId,
    onDragDeltaPx: (Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    onSwapPanes: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .border(0.5.dp, palette.currentLine)
            .combinedClickable(
                onClick = {},
                onDoubleClick = onDoubleTapReset
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDeltaPx(dragAmount.y)
                }
            }
            .padding(horizontal = 8.dp, vertical = 1.5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ACTIVE: ${activePaneId.shortCode}",
            color = palette.purple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp
        )
        Text(
            text = "=== [ DRAG / 2xTAP=50:50 ] ===",
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp
        )
        Text(
            text = "[SWAP]",
            color = palette.orange,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            modifier = Modifier.clickable(onClick = onSwapPanes)
        )
    }
}

/**
 * Draggable vertical divider between Left and Right panes in SPLIT_HORIZONTAL mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DraggableVerticalSplitBar(
    onDragDeltaPx: (Float) -> Unit,
    onDoubleTapReset: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight()
            .background(palette.backgroundDark)
            .border(0.5.dp, palette.currentLine)
            .combinedClickable(
                onClick = {},
                onDoubleClick = onDoubleTapReset
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDeltaPx(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "|",
            color = palette.purple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

/**
 * Ultra-compact File Pane with 1-line breadcrumb header and high-density file list.
 */
@Composable
fun CompactFilePane(
    paneState: PaneState,
    isFocused: Boolean,
    settings: AppSettings,
    viewModel: ExplorerViewModel,
    onOpenExternalFile: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPlainTextPalette.current
    val context = LocalContext.current
    val paneId = paneState.paneId
    val internalRoot = remember { viewModel.fileRepo.internalStorageRoot() }
    val hasMultiSelect = paneState.selectedKeys.isNotEmpty()
    val listState = rememberLazyListState()
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    val pullTriggerPx = with(LocalDensity.current) { 72.dp.toPx() }

    val pullRefreshConnection = remember(paneId, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pullDistancePx > 0f && available.y < 0f) {
                    val consumed = available.y.coerceAtLeast(-pullDistancePx)
                    pullDistancePx += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 0f &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    pullDistancePx = (pullDistancePx + available.y * 0.5f).coerceAtMost(pullTriggerPx * 1.5f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullDistancePx >= pullTriggerPx) {
                    viewModel.reloadPane(paneId)
                }
                pullDistancePx = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = modifier
            .background(palette.background)
            .nestedScroll(pullRefreshConnection)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { viewModel.setActivePane(paneId) }
            )
    ) {
        // Single-line Compact Pane Header: `*P1 | [..] / > storage > Download | 64.2 GB`
        CompactPaneHeaderRow(
            paneState = paneState,
            isFocused = isFocused,
            internalRoot = internalRoot,
            onNavigateToPath = { path ->
                viewModel.setActivePane(paneId)
                viewModel.loadPaneDirectory(paneId, path)
            },
            onNavigateUp = {
                viewModel.setActivePane(paneId)
                viewModel.navigateUpInPane(paneId)
            },
            onExitArchive = {
                viewModel.setActivePane(paneId)
                viewModel.loadPaneDirectory(paneId, paneState.currentPath, recordHistory = false)
            }
        )
        AsciiDivider(
            char = '-',
            color = if (isFocused) palette.purple.copy(alpha = 0.7f) else palette.comment.copy(alpha = 0.4f)
        )

        if (pullDistancePx > 12f || paneState.isLoading) {
            Text(
                text = if (paneState.isLoading || pullDistancePx >= pullTriggerPx) {
                    "  [↻ REFRESHING ${paneId.shortCode}...]"
                } else {
                    "  [↓ PULL DOWN TO REFRESH]"
                },
                color = palette.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.backgroundDark)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // File & Folder List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Compact `[..]` parent row when inside a subfolder or virtual archive
            if (paneState.currentPath != internalRoot || paneState.isInsideArchive) {
                item(key = "..parent_row..") {
                    CompactParentRow(
                        isInsideArchive = paneState.isInsideArchive,
                        settings = settings,
                        onNavigateUp = {
                            viewModel.setActivePane(paneId)
                            viewModel.navigateUpInPane(paneId)
                        }
                    )
                    AsciiDivider()
                }
            }

            if (paneState.errorMessage != null) {
                item(key = "..error_row..") {
                    Text(
                        text = "! ${paneState.errorMessage}",
                        color = palette.red,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else if (!paneState.isLoading && paneState.filteredEntries.isEmpty()) {
                item(key = "..empty_row..") {
                    Text(
                        text = "  (Empty folder)",
                        color = palette.comment,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                items(
                    items = paneState.filteredEntries,
                    key = { it.uniqueKey }
                ) { entry ->
                    val isSelected = entry.uniqueKey in paneState.selectedKeys
                    CompactFileEntryRow(
                        entry = entry,
                        isSelected = isSelected,
                        showCheckbox = hasMultiSelect,
                        settings = settings,
                        onSingleTap = {
                            viewModel.onSingleTapEntry(
                                paneId = paneId,
                                entry = entry,
                                cacheDir = context.cacheDir,
                                onLaunchExternalFile = onOpenExternalFile
                            )
                        },
                        onDoubleTap = {
                            viewModel.onDoubleTapEntry(paneId, entry)
                        },
                        onLongPress = {
                            viewModel.onLongPressEntry(paneId, entry)
                        },
                        onSwipeRightSelect = {
                            viewModel.toggleSelection(paneId, entry)
                        },
                        onSwipeLeftPin = {
                            viewModel.togglePinEntry(entry)
                        }
                    )
                    AsciiDivider()
                }
            }
        }
    }
}

@Composable
private fun CompactPaneHeaderRow(
    paneState: PaneState,
    isFocused: Boolean,
    internalRoot: String,
    onNavigateToPath: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onExitArchive: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) palette.lineHighlight else palette.backgroundDark)
            .padding(horizontal = 6.dp, vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isFocused) "*${paneState.paneId.shortCode}" else " ${paneState.paneId.shortCode}",
            color = if (isFocused) palette.purple else palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 4.dp)
        )

        Text(
            text = "[..]",
            color = palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier
                .clickable(onClick = onNavigateUp)
                .padding(end = 4.dp)
        )

        // Scrollable 1-line breadcrumb path
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (paneState.isInsideArchive) {
                Text(
                    text = paneState.compactTitle,
                    color = palette.orange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[EXIT]",
                    color = palette.red,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    modifier = Modifier.clickable(onClick = onExitArchive)
                )
            } else {
                val relToInternal = if (paneState.currentPath.startsWith(internalRoot)) {
                    paneState.currentPath.removePrefix(internalRoot).trim('/')
                } else {
                    paneState.currentPath.trim('/')
                }

                Text(
                    text = "~",
                    color = palette.cyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { onNavigateToPath(internalRoot) }
                        .padding(horizontal = 2.dp)
                )

                val segments = relToInternal.split('/').filter { it.isNotEmpty() }
                var running = internalRoot
                segments.forEachIndexed { idx, seg ->
                    running = "$running/$seg"
                    val target = running
                    val isLast = idx == segments.lastIndex
                    Text(
                        text = "/",
                        color = palette.comment,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text(
                        text = seg,
                        color = if (isLast) palette.foreground else palette.cyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable { onNavigateToPath(target) }
                            .padding(horizontal = 1.5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = FileSystemRepository.formatBytes(paneState.totalFreeBytes),
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun CompactParentRow(
    isInsideArchive: Boolean,
    settings: AppSettings,
    onNavigateUp: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    val vPad = settings.rowDensity.verticalPaddingDp.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateUp)
            .padding(horizontal = 6.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (settings.showFileIcons) {
            FileTypeIcon(
                category = com.plaintextfiles.explorer.model.PlainFileCategory.PARENT_DIR,
                extension = "",
                isPinned = false,
                modifier = Modifier.padding(end = 6.dp)
            )
        } else {
            Text(
                text = "[..]",
                color = palette.yellow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (11.5f * settings.fontScale.scaleFactor).sp,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        Text(
            text = if (isInsideArchive) ".. (Up / Exit Archive)" else "..",
            color = palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = (12f * settings.fontScale.scaleFactor).sp
        )
    }
}

/**
 * Compact File / Folder Row:
 * - Pinned items show a gold `★` marker and appear at the very top of the main list
 * - Single tap: opens folder, enters archive, or opens file in default Android app
 * - Double tap: opens File Details & Checksums modal
 * - Long press (Hold): opens File Operations Menu (Copy, Cut, Paste, Pin, Compress, Rename, Delete...)
 * - Optional horizontal swipe: Right = Select, Left = Pin/Unpin
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactFileEntryRow(
    entry: FileEntry,
    isSelected: Boolean,
    showCheckbox: Boolean,
    settings: AppSettings,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeRightSelect: () -> Unit,
    onSwipeLeftPin: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    val density = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 56.dp.toPx() } }
    var dragAccumX by remember { mutableFloatStateOf(0f) }

    val rowBg = when {
        isSelected -> palette.selection
        entry.isPinned -> palette.lineHighlight.copy(alpha = 0.55f)
        else -> palette.background
    }

    val nameColor = when {
        entry.isPinned && entry.isDirectory -> palette.yellow
        entry.isDirectory -> palette.cyan
        entry.isArchive -> palette.orange
        entry.isPinned -> palette.yellow
        else -> palette.foreground
    }

    val sizeLabel = if (entry.isDirectory) {
        entry.childCount?.let { "<$it>" } ?: "<DIR>"
    } else {
        FileSystemRepository.formatBytes(entry.size)
    }

    val swipeModifier = if (settings.enableRowSwipeGesture) {
        Modifier.pointerInput(entry.uniqueKey) {
            detectHorizontalDragGestures(
                onDragStart = { dragAccumX = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    dragAccumX += dragAmount
                },
                onDragEnd = {
                    if (dragAccumX > swipeThresholdPx) {
                        onSwipeRightSelect()
                    } else if (dragAccumX < -swipeThresholdPx) {
                        onSwipeLeftPin()
                    }
                    dragAccumX = 0f
                }
            )
        }
    } else {
        Modifier
    }

    val vPad = settings.rowDensity.verticalPaddingDp.dp
    val fontScale = settings.fontScale.scaleFactor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .then(swipeModifier)
            .combinedClickable(
                onClick = onSingleTap,
                onDoubleClick = onDoubleTap,
                onLongClick = onLongPress
            )
            .padding(horizontal = 6.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Multi-select checkbox (only shown when multi-select is active)
        if (showCheckbox) {
            Text(
                text = if (isSelected) "[x]" else "[ ]",
                color = if (isSelected) palette.green else palette.comment,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (11f * fontScale).sp,
                modifier = Modifier
                    .clickable(onClick = onSwipeRightSelect)
                    .padding(end = 5.dp)
            )
        }

        // Theme-matched Format Icon or ASCII badge
        if (settings.showFileIcons) {
            FileTypeIcon(
                category = entry.category,
                extension = entry.extension,
                isPinned = entry.isPinned,
                modifier = Modifier.padding(end = 6.dp)
            )
        } else {
            Text(
                text = if (entry.isPinned) "[★]" else entry.category.asciiBadge,
                color = nameColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (10.5f * fontScale).sp,
                modifier = Modifier.padding(end = 5.dp)
            )
        }

        // File / Folder Name (+ optional subtitle if enabled in Settings)
        Column(modifier = Modifier.weight(1f)) {
            val pinPrefix = if (entry.isPinned) "★ " else ""
            val dirSuffix = if (entry.isDirectory) "/" else ""
            Text(
                text = "$pinPrefix${entry.name}$dirSuffix",
                color = nameColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (entry.isDirectory || entry.isPinned) FontWeight.Bold else FontWeight.Normal,
                fontSize = (12f * fontScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (settings.showPermissionSubtitle || entry.isPinnedShortcutFromOtherDir) {
                val subText = if (entry.isPinnedShortcutFromOtherDir) {
                    "PINNED :: ${entry.path.substringBeforeLast('/')}"
                } else {
                    "${entry.permissions} | ${FileSystemRepository.formatShortDate(entry.lastModified)}"
                }
                Text(
                    text = subText,
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (9.5f * fontScale).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Compact right-aligned size or folder item count
        Text(
            text = sizeLabel,
            color = if (entry.isDirectory) palette.comment else palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontSize = (10.5f * fontScale).sp,
            maxLines = 1
        )
    }
}
