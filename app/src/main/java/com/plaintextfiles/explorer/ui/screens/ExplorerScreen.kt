package com.plaintextfiles.explorer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.AppSettings
import com.plaintextfiles.explorer.model.DiffStatus
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

private val FAST_SCROLL_BUCKETS = listOf(
    '#', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
    'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'
)

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
            trashCount = state.trashItemCount,
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
                            diffBadges = state.diffBadges,
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
                            onComparePanes = { viewModel.openComparePanesModal() },
                            onSwapPanes = { viewModel.swapPanes() }
                        )
                        CompactFilePane(
                            paneState = state.paneB,
                            isFocused = state.activePaneId == PaneId.PANE_B,
                            settings = state.settings,
                            diffBadges = state.diffBadges,
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
                            diffBadges = state.diffBadges,
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
                            diffBadges = state.diffBadges,
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
                                diffBadges = state.diffBadges,
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
                        diffBadges = state.diffBadges,
                        viewModel = viewModel,
                        onOpenExternalFile = onOpenExternalFile,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 5. Auto-Hiding 1-Line Trash Undo Bar (Appears after moving items to Trash)
        state.trashUndoBatch?.let { batch ->
            AsciiDivider(char = '-')
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.backgroundDark)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = batch.summaryLabel,
                    color = palette.yellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlainTextActionLink(
                        label = "UNDO",
                        accentColor = palette.green,
                        onClick = { viewModel.undoLastTrash() }
                    )
                    PlainTextActionLink(
                        label = "TRASH",
                        accentColor = palette.cyan,
                        onClick = { viewModel.openTrashInActivePane() }
                    )
                    PlainTextActionLink(
                        label = "X",
                        accentColor = palette.comment,
                        onClick = { viewModel.dismissTrashUndoBanner() }
                    )
                }
            }
        }

        // 6. Auto-Hiding 1-Line Bottom Progress Strip (Only visible during active file/archive operations)
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

    // Long-Press Operations Menu, Compare & Sync, Share Target & Other Dialogs
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
            if (state.settings.showDiffHighlights) {
                Text(
                    text = "[DIFF]",
                    color = palette.cyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable {
                        viewModel.openComparePanesModal()
                    }
                )
            }
        }

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
                    ThreeDotMenuItem("Compare & Sync Panes (P1 <-> P2)...") {
                        menuExpanded = false
                        viewModel.openComparePanesModal()
                    }
                    ThreeDotMenuItem("Diff Badges: ${if (state.settings.showDiffHighlights) "ON (Hide)" else "OFF (Show)"}") {
                        menuExpanded = false
                        viewModel.toggleDiffHighlights()
                    }
                    ThreeDotMenuItem("Edit Raw Path (${activePane.paneId.shortCode}) [>_]") {
                        menuExpanded = false
                        viewModel.setPathEditorVisible(activePane.paneId, !activePane.isPathEditorVisible)
                    }
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
                    ThreeDotMenuItem("Mirror Folder (${activePane.paneId.shortCode} -> ${activePane.paneId.opposite().shortCode})") {
                        menuExpanded = false
                        viewModel.syncOppositePaneTo(activePane.paneId)
                    }
                    ThreeDotMenuItem("Trash Bin (${state.trashItemCount})") {
                        menuExpanded = false
                        viewModel.openTrashInActivePane()
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DraggableHorizontalSplitBar(
    activePaneId: PaneId,
    onDragDeltaPx: (Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    onComparePanes: () -> Unit,
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
            text = "ACTIVE:${activePaneId.shortCode}",
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "[DIFF]",
                color = palette.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                modifier = Modifier.clickable(onClick = onComparePanes)
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
}

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

@Composable
fun CompactFilePane(
    paneState: PaneState,
    isFocused: Boolean,
    settings: AppSettings,
    diffBadges: Map<String, DiffStatus>,
    viewModel: ExplorerViewModel,
    onOpenExternalFile: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPlainTextPalette.current
    val context = LocalContext.current
    val paneId = paneState.paneId
    val internalRoot = remember { viewModel.fileRepo.internalStorageRoot() }
    val isInTrash = viewModel.fileRepo.isTrashPath(paneState.currentPath)
    val hasMultiSelect = paneState.selectedKeys.isNotEmpty()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    val pullTriggerPx = with(LocalDensity.current) { 72.dp.toPx() }
    var activeFastScrollLetter by remember { mutableStateOf<Char?>(null) }
    val hasParentRow = paneState.currentPath != internalRoot || paneState.isInsideArchive

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
        CompactPaneHeaderRow(
            paneState = paneState,
            isFocused = isFocused,
            internalRoot = internalRoot,
            isInTrash = isInTrash,
            onNavigateToPath = { path ->
                viewModel.setActivePane(paneId)
                viewModel.loadPaneDirectory(paneId, path)
            },
            onNavigateUp = {
                viewModel.setActivePane(paneId)
                viewModel.navigateUpInPane(paneId)
            },
            onTogglePathEditor = {
                viewModel.setActivePane(paneId)
                viewModel.setPathEditorVisible(paneId, !paneState.isPathEditorVisible)
            },
            onEmptyTrash = {
                viewModel.emptyTrashBin()
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

        // Tap/Hold-to-Edit Raw Path Bar with Subfolder Auto-Complete
        if (paneState.isPathEditorVisible) {
            RawPathEditorBar(
                paneState = paneState,
                viewModel = viewModel
            )
        }

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

        // Main File List + Right-Edge Fast-Scroll ASCII Index Rail
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (hasParentRow) {
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
                                text = if (isInTrash) "  (Trash Bin is empty)" else "  (Empty folder)",
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
                            val diffStatus = if (settings.showDiffHighlights) {
                                diffBadges[entry.uniqueKey] ?: DiffStatus.NONE
                            } else {
                                DiffStatus.NONE
                            }
                            CompactFileEntryRow(
                                entry = entry,
                                isSelected = isSelected,
                                showCheckbox = hasMultiSelect,
                                diffStatus = diffStatus,
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

                // Razor-thin Fast-Scroll ASCII Index Rail (`# A-Z`)
                if (settings.showFastScrollRail && paneState.filteredEntries.size >= 8) {
                    FastScrollAsciiRail(
                        activeChar = activeFastScrollLetter,
                        onSelectChar = { targetChar ->
                            activeFastScrollLetter = targetChar
                            val offset = if (hasParentRow) 1 else 0
                            val targetIdx = findIndexForBucket(paneState.filteredEntries, targetChar)
                            if (targetIdx >= 0) {
                                coroutineScope.launch {
                                    listState.scrollToItem(targetIdx + offset)
                                }
                            }
                        },
                        onRelease = {
                            activeFastScrollLetter = null
                        }
                    )
                }
            }

            // Floating ASCII Fast-Scroll Letter Indicator (`+-- [ M ] --+`)
            activeFastScrollLetter?.let { ch ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(palette.backgroundDark)
                        .border(1.5.dp, palette.purple)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "+-- [ $ch ] --+",
                        color = palette.yellow,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

private fun findIndexForBucket(entries: List<FileEntry>, bucket: Char): Int {
    if (entries.isEmpty()) return -1
    if (bucket == '#') return 0
    val exactOrAfter = entries.indexOfFirst { entry ->
        val firstChar = entry.name.trimStart('.').firstOrNull()?.uppercaseChar() ?: '#'
        !entry.isPinned && firstChar >= bucket
    }
    return if (exactOrAfter >= 0) exactOrAfter else entries.lastIndex
}

@Composable
private fun FastScrollAsciiRail(
    activeChar: Char?,
    onSelectChar: (Char) -> Unit,
    onRelease: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    var railHeightPx by remember { mutableFloatStateOf(1f) }

    fun charAtY(y: Float): Char {
        val fraction = (y / railHeightPx).coerceIn(0f, 0.999f)
        val idx = (fraction * FAST_SCROLL_BUCKETS.size).toInt().coerceIn(0, FAST_SCROLL_BUCKETS.lastIndex)
        return FAST_SCROLL_BUCKETS[idx]
    }

    Column(
        modifier = Modifier
            .width(13.dp)
            .fillMaxHeight()
            .background(palette.backgroundDark)
            .onSizeChanged { sz ->
                railHeightPx = sz.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        onSelectChar(charAtY(offset.y))
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> onSelectChar(charAtY(offset.y)) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onSelectChar(charAtY(change.position.y))
                    },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() }
                )
            }
            .padding(vertical = 1.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FAST_SCROLL_BUCKETS.forEach { ch ->
            val isHighlighted = activeChar == ch
            Text(
                text = ch.toString(),
                color = if (isHighlighted) palette.yellow else palette.comment,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                fontSize = 7.5.sp,
                lineHeight = 8.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RawPathEditorBar(
    paneState: PaneState,
    viewModel: ExplorerViewModel
) {
    val palette = LocalPlainTextPalette.current
    var rawPathText by remember(paneState.currentPath) { mutableStateOf(paneState.currentPath) }
    val suggestions = remember(rawPathText) {
        viewModel.fileRepo.listSubdirectorySuggestions(rawPathText)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "PATH:",
                color = palette.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(palette.background)
                    .border(1.dp, palette.cyan)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                BasicTextField(
                    value = rawPathText,
                    onValueChange = { rawPathText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = palette.foreground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    cursorBrush = SolidColor(palette.cyan),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PlainTextActionLink(
                label = "GO",
                accentColor = palette.green,
                onClick = {
                    viewModel.navigateToRawPath(paneState.paneId, rawPathText, createIfMissing = false)
                }
            )
            PlainTextActionLink(
                label = "+MKDIR",
                accentColor = palette.yellow,
                onClick = {
                    viewModel.navigateToRawPath(paneState.paneId, rawPathText, createIfMissing = true)
                }
            )
            PlainTextActionLink(
                label = "X",
                accentColor = palette.red,
                onClick = {
                    viewModel.setPathEditorVisible(paneState.paneId, false)
                }
            )
        }

        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TAB:",
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp
                )
                suggestions.forEach { sugPath ->
                    val folderName = sugPath.substringAfterLast('/')
                    PlainTextActionLink(
                        label = "$folderName/",
                        accentColor = palette.cyan,
                        onClick = {
                            rawPathText = "$sugPath/"
                        }
                    )
                }
            }
        }
    }
    AsciiDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactPaneHeaderRow(
    paneState: PaneState,
    isFocused: Boolean,
    internalRoot: String,
    isInTrash: Boolean,
    onNavigateToPath: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onTogglePathEditor: () -> Unit,
    onEmptyTrash: () -> Unit,
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

        Text(
            text = "[>_]",
            color = if (paneState.isPathEditorVisible) palette.green else palette.cyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.5.sp,
            modifier = Modifier
                .clickable(onClick = onTogglePathEditor)
                .padding(end = 4.dp)
        )

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
                        .combinedClickable(
                            onClick = { onNavigateToPath(internalRoot) },
                            onLongClick = onTogglePathEditor
                        )
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
                            .combinedClickable(
                                onClick = { onNavigateToPath(target) },
                                onLongClick = onTogglePathEditor
                            )
                            .padding(horizontal = 1.5.dp)
                    )
                }
            }
        }

        if (isInTrash && paneState.filteredEntries.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "[EMPTY]",
                color = palette.red,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.clickable(onClick = onEmptyTrash)
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactFileEntryRow(
    entry: FileEntry,
    isSelected: Boolean,
    showCheckbox: Boolean,
    diffStatus: DiffStatus,
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

        if (diffStatus != DiffStatus.NONE && diffStatus != DiffStatus.IDENTICAL) {
            val badgeColor = when (diffStatus) {
                DiffStatus.ONLY_IN_P1 -> palette.green
                DiffStatus.ONLY_IN_P2 -> palette.cyan
                DiffStatus.NEWER_HERE -> palette.orange
                DiffStatus.OLDER_HERE -> palette.comment
                DiffStatus.SIZE_DIFF -> palette.yellow
                else -> palette.comment
            }
            Text(
                text = diffStatus.badge,
                color = badgeColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (9.5f * fontScale).sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = sizeLabel,
            color = if (entry.isDirectory) palette.comment else palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontSize = (10.5f * fontScale).sp,
            maxLines = 1
        )
    }
}
