package com.plaintextfiles.explorer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.DualPaneMode
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.PaneId
import com.plaintextfiles.explorer.model.PaneState
import com.plaintextfiles.explorer.model.SortField
import com.plaintextfiles.explorer.model.StorageRootInfo
import com.plaintextfiles.explorer.model.ThemeMode
import com.plaintextfiles.explorer.ui.ExplorerUiState
import com.plaintextfiles.explorer.ui.ExplorerViewModel
import com.plaintextfiles.explorer.ui.PlainDialogState
import com.plaintextfiles.explorer.ui.components.AsciiDivider
import com.plaintextfiles.explorer.ui.components.AsciiScoreBox
import com.plaintextfiles.explorer.ui.components.AsciiUsageBar
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. PlainTextSports-style Top Banner & Theme/Layout Controls
        PlainTextTopMasthead(
            state = state,
            onSelectTheme = { viewModel.setThemeMode(it) },
            onSelectDualPaneMode = { viewModel.setDualPaneMode(it) },
            onRefresh = { viewModel.refreshAllPanes() }
        )

        // 2. Storage Roots Bar (`Roots: [INTERNAL] [DOWNLOAD] [DOCS] [DCIM] [ROOT /]`)
        StorageRootsStrip(
            roots = state.storageRoots,
            activePane = state.getPane(state.activePaneId),
            onSelectRoot = { root ->
                viewModel.loadPaneDirectory(state.activePaneId, root.path)
            }
        )

        // 3. All Files Access Permission Banner if not yet granted on Android 11+
        if (!state.hasStoragePermission) {
            AllFilesPermissionAsciiBanner(onRequestPermission = onRequestAllFilesPermission)
        }

        // 4. Global Clipboard Banner if items are staged
        state.clipboard?.let { clip ->
            ClipboardAsciiBanner(
                clipboardCount = clip.entries.size,
                modeName = clip.mode.name,
                activePaneLabel = state.activePaneId.shortCode,
                onPasteToActive = { viewModel.pasteClipboardIntoPane(state.activePaneId) },
                onClearClipboard = {
                    viewModel.stageToClipboard(clip.sourcePane, emptyList(), clip.mode)
                }
            )
        }

        // 5. Dual-Pane Workspace Area (Supports BOTH Split-Screen and Swipeable Pager + Single)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (state.dualPaneMode) {
                DualPaneMode.SPLIT_VERTICAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FilePaneContainer(
                            paneState = state.paneA,
                            isFocused = state.activePaneId == PaneId.PANE_A,
                            highlightedKey = state.highlightedEntry?.uniqueKey,
                            viewModel = viewModel,
                            onOpenExternalFile = onOpenExternalFile,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                        DualPaneControlDivider(
                            activePaneId = state.activePaneId,
                            onSwapPanes = { viewModel.swapPanes() },
                            onSyncActiveToOpposite = { viewModel.syncOppositePaneTo(state.activePaneId) },
                            onSwitchFocus = {
                                viewModel.setActivePane(state.activePaneId.opposite())
                            }
                        )
                        FilePaneContainer(
                            paneState = state.paneB,
                            isFocused = state.activePaneId == PaneId.PANE_B,
                            highlightedKey = state.highlightedEntry?.uniqueKey,
                            viewModel = viewModel,
                            onOpenExternalFile = onOpenExternalFile,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }

                DualPaneMode.SPLIT_HORIZONTAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        DualPaneControlDivider(
                            activePaneId = state.activePaneId,
                            onSwapPanes = { viewModel.swapPanes() },
                            onSyncActiveToOpposite = { viewModel.syncOppositePaneTo(state.activePaneId) },
                            onSwitchFocus = {
                                viewModel.setActivePane(state.activePaneId.opposite())
                            }
                        )
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            FilePaneContainer(
                                paneState = state.paneA,
                                isFocused = state.activePaneId == PaneId.PANE_A,
                                highlightedKey = state.highlightedEntry?.uniqueKey,
                                viewModel = viewModel,
                                onOpenExternalFile = onOpenExternalFile,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(palette.purple)
                            )
                            FilePaneContainer(
                                paneState = state.paneB,
                                isFocused = state.activePaneId == PaneId.PANE_B,
                                highlightedKey = state.highlightedEntry?.uniqueKey,
                                viewModel = viewModel,
                                onOpenExternalFile = onOpenExternalFile,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
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
                        // PlainTextSports-style Scoreboard Cards for Pane 1 & Pane 2
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.backgroundDark)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsciiScoreBox(
                                title = if (state.activePaneId == PaneId.PANE_A) "* PANE 1 (ACTIVE)" else "  PANE 1",
                                lines = listOf(
                                    state.paneA.displayPathHeader.takeLast(22),
                                    "${state.paneA.filteredEntries.size} items | Sel: ${state.paneA.selectedKeys.size}"
                                ),
                                isFocused = state.activePaneId == PaneId.PANE_A,
                                onClick = {
                                    viewModel.setActivePane(PaneId.PANE_A)
                                    coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                }
                            )
                            AsciiScoreBox(
                                title = if (state.activePaneId == PaneId.PANE_B) "* PANE 2 (ACTIVE)" else "  PANE 2",
                                lines = listOf(
                                    state.paneB.displayPathHeader.takeLast(22),
                                    "${state.paneB.filteredEntries.size} items | Sel: ${state.paneB.selectedKeys.size}"
                                ),
                                isFocused = state.activePaneId == PaneId.PANE_B,
                                onClick = {
                                    viewModel.setActivePane(PaneId.PANE_B)
                                    coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                }
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PlainTextActionLink(
                                    label = "SWAP P1<->P2",
                                    accentColor = palette.orange,
                                    onClick = { viewModel.swapPanes() }
                                )
                                PlainTextActionLink(
                                    label = "SYNC -> OTHER",
                                    accentColor = palette.cyan,
                                    onClick = { viewModel.syncOppositePaneTo(state.activePaneId) }
                                )
                            }
                        }
                        AsciiDivider(char = '=')
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) { page ->
                            val paneState = if (page == 0) state.paneA else state.paneB
                            FilePaneContainer(
                                paneState = paneState,
                                isFocused = true,
                                highlightedKey = state.highlightedEntry?.uniqueKey,
                                viewModel = viewModel,
                                onOpenExternalFile = onOpenExternalFile,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                DualPaneMode.SINGLE_PANE -> {
                    val activePane = state.getPane(state.activePaneId)
                    FilePaneContainer(
                        paneState = activePane,
                        isFocused = true,
                        highlightedKey = state.highlightedEntry?.uniqueKey,
                        viewModel = viewModel,
                        onOpenExternalFile = onOpenExternalFile,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 6. Bottom Status & Hint Footer mirroring plaintextsports.com footer
        PlainTextStatusFooter(
            statusLog = state.statusLog,
            isBusy = state.isBusy,
            activePaneId = state.activePaneId
        )
    }

    // Long-Press Highlighted Item Details & Actions Modal
    val highlighted = state.highlightedEntry
    val highlightedPane = state.highlightedPaneId
    if (highlighted != null && highlightedPane != null) {
        LongPressFileDetailsModal(
            paneId = highlightedPane,
            entry = highlighted,
            details = state.highlightedDetails,
            isLoading = state.isLoadingDetails,
            viewModel = viewModel,
            onOpenExternal = onOpenExternalFile,
            onShareExternal = onShareExternalFile
        )
    }

    // Active Dialog Host
    state.activeDialog?.let { dlg ->
        ActivePlainDialogHost(dialogState = dlg, viewModel = viewModel)
    }
}

/**
 * Masthead inspired by https://plaintextsports.com/:
 * - App title + Loaded timestamp
 * - `Dracula Mode | Alucard Mode`
 * - `< Split-V | Split-H | Pager | Single >`
 */
@Composable
private fun PlainTextTopMasthead(
    state: ExplorerUiState,
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectDualPaneMode: (DualPaneMode) -> Unit,
    onRefresh: () -> Unit
) {
    val palette = LocalPlainTextPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "exp-txt",
                color = palette.purple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Loaded: ${state.lastRefreshTime}",
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                PlainTextActionLink(
                    label = "REFRESH",
                    accentColor = palette.green,
                    onClick = onRefresh
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Theme & Dual-Pane Design Switcher Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Theme:",
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                PlainTextActionLink(
                    label = "Dracula Mode",
                    isActive = state.themeMode == ThemeMode.DRACULA,
                    useBrackets = false,
                    underlineWhenInactive = true,
                    onClick = { onSelectTheme(ThemeMode.DRACULA) }
                )
                Text(
                    text = "|",
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                PlainTextActionLink(
                    label = "Alucard Mode",
                    isActive = state.themeMode == ThemeMode.ALUCARD,
                    useBrackets = false,
                    underlineWhenInactive = true,
                    onClick = { onSelectTheme(ThemeMode.ALUCARD) }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "<",
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                DualPaneMode.entries.forEach { mode ->
                    PlainTextActionLink(
                        label = mode.shortLabel,
                        isActive = state.dualPaneMode == mode,
                        onClick = { onSelectDualPaneMode(mode) }
                    )
                }
                Text(
                    text = ">",
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
    AsciiDivider(char = '=')
}

@Composable
private fun StorageRootsStrip(
    roots: List<StorageRootInfo>,
    activePane: PaneState,
    onSelectRoot: (StorageRootInfo) -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Roots (${activePane.paneId.shortCode}):",
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        roots.forEach { root ->
            val isCurrentRoot = activePane.currentPath.startsWith(root.path) && root.path != "/" ||
                (root.path == "/" && activePane.currentPath == "/")
            PlainTextActionLink(
                label = root.shortTag,
                isActive = isCurrentRoot,
                onClick = { onSelectRoot(root) }
            )
        }
    }
    AsciiDivider(char = '-')
}

@Composable
private fun AllFilesPermissionAsciiBanner(onRequestPermission: () -> Unit) {
    val palette = LocalPlainTextPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .border(1.dp, palette.orange)
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "! ALL FILES ACCESS REQUIRED FOR FULL STORAGE",
                color = palette.orange,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            PlainTextActionLink(
                label = "GRANT ACCESS ->",
                accentColor = palette.green,
                onClick = onRequestPermission
            )
        }
    }
}

@Composable
private fun ClipboardAsciiBanner(
    clipboardCount: Int,
    modeName: String,
    activePaneLabel: String,
    onPasteToActive: () -> Unit,
    onClearClipboard: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    if (clipboardCount <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.selection)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "CLIPBOARD [$modeName]: $clipboardCount item(s)",
            color = palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlainTextActionLink(
                label = "PASTE HERE ($activePaneLabel)",
                accentColor = palette.green,
                onClick = onPasteToActive
            )
            PlainTextActionLink(
                label = "CLEAR",
                accentColor = palette.red,
                onClick = onClearClipboard
            )
        }
    }
    AsciiDivider(char = '-')
}

@Composable
private fun DualPaneControlDivider(
    activePaneId: PaneId,
    onSwapPanes: () -> Unit,
    onSyncActiveToOpposite: () -> Unit,
    onSwitchFocus: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .border(1.dp, palette.purple)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlainTextActionLink(
            label = "FOCUS: ${activePaneId.label}",
            accentColor = palette.purple,
            onClick = onSwitchFocus
        )
        Text(
            text = "+=== DUAL PANE ===+",
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlainTextActionLink(
                label = "SWAP",
                accentColor = palette.orange,
                onClick = onSwapPanes
            )
            PlainTextActionLink(
                label = "SYNC->",
                accentColor = palette.cyan,
                onClick = onSyncActiveToOpposite
            )
        }
    }
}

/**
 * Individual Pane Container (used in Split-V, Split-H, Swipe-Pager, and Single-Pane modes)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilePaneContainer(
    paneState: PaneState,
    isFocused: Boolean,
    highlightedKey: String?,
    viewModel: ExplorerViewModel,
    onOpenExternalFile: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPlainTextPalette.current
    val paneId = paneState.paneId
    val oppositeCode = paneId.opposite().shortCode
    var showSearchBar by remember { mutableStateOf(false) }
    var showSortStrip by remember { mutableStateOf(false) }

    val usedFraction = if (paneState.totalCapacityBytes > 0L) {
        ((paneState.totalCapacityBytes - paneState.totalFreeBytes).toFloat() / paneState.totalCapacityBytes.toFloat())
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier
            .background(palette.background)
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = { viewModel.setActivePane(paneId) }
            )
    ) {
        // 1. Pane Status & Free Space Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isFocused) palette.lineHighlight else palette.backgroundDark)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (isFocused) "* [${paneId.label}]" else "  [${paneId.label}]",
                    color = if (isFocused) palette.purple else palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                if (paneState.isInsideArchive) {
                    PlainTextActionLink(
                        label = "EXIT ARCHIVE",
                        accentColor = palette.orange,
                        onClick = {
                            viewModel.loadPaneDirectory(paneId, paneState.currentPath, recordHistory = false)
                        }
                    )
                }
            }

            AsciiUsageBar(
                fraction = usedFraction,
                label = "${FileSystemRepository.formatBytes(paneState.totalFreeBytes)} free",
                barWidthChars = 10
            )
        }

        // 2. Clickable Plain-Text Breadcrumb Bar
        BreadcrumbStrip(
            paneState = paneState,
            onNavigateToPath = { path -> viewModel.loadPaneDirectory(paneId, path) },
            onNavigateUp = { viewModel.navigateUpInPane(paneId) }
        )

        // 3. Plain-Text Action & Sort Command Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.backgroundDark)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!paneState.isInsideArchive) {
                PlainTextActionLink(
                    label = "+DIR",
                    accentColor = palette.cyan,
                    onClick = {
                        viewModel.setActivePane(paneId)
                        viewModel.showDialog(PlainDialogState.CreateFolder(paneId))
                    }
                )
                PlainTextActionLink(
                    label = "+TXT",
                    accentColor = palette.green,
                    onClick = {
                        viewModel.setActivePane(paneId)
                        viewModel.showDialog(PlainDialogState.CreateTextFile(paneId))
                    }
                )
            } else {
                PlainTextActionLink(
                    label = "EXTRACT ALL",
                    accentColor = palette.green,
                    onClick = {
                        paneState.activeArchivePath?.let { arcPath ->
                            viewModel.openExtractDialog(paneId, arcPath, null)
                        }
                    }
                )
            }

            PlainTextActionLink(
                label = "SORT: ${paneState.sortConfig.field.shortTag} ${paneState.sortConfig.order.symbol}",
                isActive = showSortStrip,
                accentColor = palette.yellow,
                onClick = { showSortStrip = !showSortStrip }
            )

            PlainTextActionLink(
                label = if (paneState.searchQuery.isEmpty()) "FILTER" else "FILTER(${paneState.searchQuery})",
                isActive = showSearchBar || paneState.searchQuery.isNotEmpty(),
                accentColor = palette.pink,
                onClick = { showSearchBar = !showSearchBar }
            )

            PlainTextActionLink(
                label = if (paneState.sortConfig.showHidden) ".HID:ON" else ".HID:OFF",
                accentColor = palette.comment,
                onClick = { viewModel.toggleShowHidden(paneId) }
            )

            PlainTextActionLink(
                label = "ALL(${paneState.filteredEntries.size})",
                accentColor = palette.purple,
                onClick = { viewModel.selectAllInPane(paneId) }
            )
        }

        // Expandable Sort Options Strip
        if (showSortStrip) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.selection)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Sort By:",
                    color = palette.yellow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                SortField.entries.forEach { field ->
                    val active = paneState.sortConfig.field == field
                    val suffix = if (active) " ${paneState.sortConfig.order.symbol}" else ""
                    PlainTextActionLink(
                        label = "${field.shortTag}$suffix",
                        isActive = active,
                        onClick = { viewModel.updateSortField(paneId, field) }
                    )
                }
                PlainTextActionLink(
                    label = if (paneState.sortConfig.foldersFirst) "DIRS-FIRST:ON" else "DIRS-FIRST:OFF",
                    isActive = paneState.sortConfig.foldersFirst,
                    onClick = { viewModel.toggleFoldersFirst(paneId) }
                )
            }
        }

        // Expandable Filter / Search Input Bar
        if (showSearchBar || paneState.searchQuery.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.backgroundDark)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "GREP/FILTER:",
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
                        value = paneState.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(paneId, it) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = palette.foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        cursorBrush = SolidColor(palette.pink),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                PlainTextActionLink(
                    label = "CLEAR",
                    accentColor = palette.red,
                    onClick = {
                        viewModel.updateSearchQuery(paneId, "")
                        showSearchBar = false
                    }
                )
            }
        }

        // Multi-Selection Batch Action Strip
        if (paneState.selectedKeys.isNotEmpty()) {
            val selectedItems = remember(paneState.entries, paneState.selectedKeys) {
                paneState.entries.filter { it.uniqueKey in paneState.selectedKeys }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.selection)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "[${selectedItems.size} SEL]:",
                    color = palette.yellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                PlainTextActionLink(
                    label = if (paneState.isInsideArchive) "EXTRACT->$oppositeCode" else "COPY->$oppositeCode",
                    accentColor = palette.cyan,
                    onClick = {
                        viewModel.copyOrMoveToOppositePane(paneId, selectedItems, move = false)
                    }
                )
                if (!paneState.isInsideArchive) {
                    PlainTextActionLink(
                        label = "MOVE->$oppositeCode",
                        accentColor = palette.orange,
                        onClick = {
                            viewModel.copyOrMoveToOppositePane(paneId, selectedItems, move = true)
                        }
                    )
                    PlainTextActionLink(
                        label = "COMPRESS",
                        accentColor = palette.pink,
                        onClick = {
                            viewModel.openCompressDialog(paneId, selectedItems)
                        }
                    )
                    PlainTextActionLink(
                        label = "DEL",
                        accentColor = palette.red,
                        onClick = {
                            viewModel.showDialog(PlainDialogState.DeleteConfirm(paneId, selectedItems))
                        }
                    )
                }
                PlainTextActionLink(
                    label = "NONE",
                    accentColor = palette.comment,
                    onClick = { viewModel.clearSelection(paneId) }
                )
            }
        }

        // 4. ASCII Table Header (`TYPE | NAME | SIZE | MODIFIED | PERM`)
        AsciiTableHeaderRow(
            sortConfig = paneState.sortConfig,
            onSortClick = { field -> viewModel.updateSortField(paneId, field) }
        )
        AsciiDivider(char = '-')

        // 5. Directory / Virtual Archive Listing
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Parent Directory `[..]` row
            if (paneState.currentPath != "/" || paneState.isInsideArchive) {
                item(key = "..parent_row..") {
                    ParentDirectoryAsciiRow(
                        isInsideArchive = paneState.isInsideArchive,
                        onNavigateUp = {
                            viewModel.setActivePane(paneId)
                            viewModel.navigateUpInPane(paneId)
                        }
                    )
                    AsciiDivider(char = '-', color = palette.backgroundLight)
                }
            }

            if (paneState.errorMessage != null) {
                item(key = "..error_row..") {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "+-- [ERROR READING PATH] --+",
                            color = palette.red,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = paneState.errorMessage,
                            color = palette.orange,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            } else if (!paneState.isLoading && paneState.filteredEntries.isEmpty()) {
                item(key = "..empty_row..") {
                    Text(
                        text = "  (Empty directory — 0 matching items)",
                        color = palette.comment,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else {
                items(
                    items = paneState.filteredEntries,
                    key = { it.uniqueKey }
                ) { entry ->
                    val isSelected = entry.uniqueKey in paneState.selectedKeys
                    val isHighlighted = entry.uniqueKey == highlightedKey
                    FileEntryAsciiRow(
                        entry = entry,
                        isSelected = isSelected,
                        isHighlighted = isHighlighted,
                        onSingleTap = {
                            viewModel.onSingleTapEntry(paneId, entry, onOpenExternalFile)
                        },
                        onLongPress = {
                            viewModel.onLongPressEntry(paneId, entry)
                        },
                        onToggleSelect = {
                            viewModel.toggleSelection(paneId, entry)
                        }
                    )
                    AsciiDivider(char = '-', color = palette.backgroundLight)
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbStrip(
    paneState: PaneState,
    onNavigateToPath: (String) -> Unit,
    onNavigateUp: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PlainTextActionLink(
            label = "..",
            accentColor = palette.yellow,
            onClick = onNavigateUp
        )

        if (paneState.isInsideArchive) {
            Text(
                text = paneState.displayPathHeader,
                color = palette.orange,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        } else {
            val parts = paneState.currentPath.trim('/').split('/').filter { it.isNotEmpty() }
            Text(
                text = "/",
                color = palette.cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable { onNavigateToPath("/") }
                    .padding(horizontal = 2.dp)
            )
            var runningPath = ""
            parts.forEachIndexed { idx, segment ->
                runningPath += "/$segment"
                val target = runningPath
                val isLast = idx == parts.lastIndex
                Text(
                    text = segment,
                    color = if (isLast) palette.foreground else palette.cyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (!isLast) TextDecoration.Underline else TextDecoration.None,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onNavigateToPath(target) }
                        .padding(horizontal = 2.dp)
                )
                if (!isLast) {
                    Text(
                        text = "/",
                        color = palette.comment,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
    AsciiDivider(char = '-')
}

@Composable
private fun AsciiTableHeaderRow(
    sortConfig: com.plaintextfiles.explorer.model.SortConfig,
    onSortClick: (SortField) -> Unit
) {
    val palette = LocalPlainTextPalette.current
    fun indicator(field: SortField): String =
        if (sortConfig.field == field) sortConfig.order.symbol else " "

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SEL|TYPE ",
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.clickable { onSortClick(SortField.TYPE) }
        )
        Text(
            text = "| NAME ${indicator(SortField.NAME)}",
            color = if (sortConfig.field == SortField.NAME) palette.yellow else palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier
                .weight(1f)
                .clickable { onSortClick(SortField.NAME) }
        )
        Text(
            text = "| SIZE ${indicator(SortField.SIZE)}   ",
            color = if (sortConfig.field == SortField.SIZE) palette.yellow else palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.clickable { onSortClick(SortField.SIZE) }
        )
        Text(
            text = "| MODIFIED ${indicator(SortField.DATE)} ",
            color = if (sortConfig.field == SortField.DATE) palette.yellow else palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.clickable { onSortClick(SortField.DATE) }
        )
    }
}

@Composable
private fun ParentDirectoryAsciiRow(
    isInsideArchive: Boolean,
    onNavigateUp: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateUp)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "[ ] [..] ",
            color = palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        Text(
            text = if (isInsideArchive) ".. (Up / Exit Virtual Archive)" else ".. (Parent Directory)",
            color = palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

/**
 * Pure Plain-Text File/Folder Row:
 * - Single tap: opens directory, virtual archive, or file
 * - Long press: highlights row and opens ASCII File Details & Action Modal
 * - Tap on `[ ]` / `[x]` prefix: toggles multi-select
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryAsciiRow(
    entry: FileEntry,
    isSelected: Boolean,
    isHighlighted: Boolean,
    onSingleTap: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val palette = LocalPlainTextPalette.current

    val rowBg = when {
        isHighlighted -> palette.purple.copy(alpha = 0.28f)
        isSelected -> palette.selection
        else -> palette.background
    }

    val badgeColor = when {
        entry.isDirectory -> palette.cyan
        entry.isArchive -> palette.orange
        entry.isSymlink -> palette.pink
        entry.category == com.plaintextfiles.explorer.model.PlainFileCategory.TEXT_CODE -> palette.green
        entry.category == com.plaintextfiles.explorer.model.PlainFileCategory.AUDIO -> palette.purple
        entry.category == com.plaintextfiles.explorer.model.PlainFileCategory.PACKAGE -> palette.yellow
        else -> palette.foreground
    }

    val sizeColumnText = if (entry.isDirectory) {
        val count = entry.childCount
        if (count != null) "<$count item>" else "<DIR>"
    } else {
        FileSystemRepository.formatBytes(entry.size)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = onSingleTap,
                onLongClick = onLongPress
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection check box in plain ASCII `[x]` or `[ ]`
        Text(
            text = if (isSelected) "[x]" else "[ ]",
            color = if (isSelected) palette.green else palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier
                .clickable(onClick = onToggleSelect)
                .padding(end = 4.dp)
        )

        // Category ASCII Badge (`[DIR]`, `[ARC]`, `[TXT]`, etc.)
        Text(
            text = entry.category.asciiBadge,
            color = badgeColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 5.dp)
        )

        // Name + Permissions subtitle on compact screens
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                color = if (entry.isDirectory) palette.cyan else if (entry.isArchive) palette.orange else palette.foreground,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (entry.isDirectory || entry.isArchive) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entry.permissions} | ${FileSystemRepository.formatShortDate(entry.lastModified)}",
                color = palette.comment,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Right-aligned Size / Count column
        Text(
            text = sizeColumnText.padStart(9, ' '),
            color = if (entry.isDirectory) palette.comment else palette.yellow,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun PlainTextStatusFooter(
    statusLog: String,
    isBusy: Boolean,
    activePaneId: PaneId
) {
    val palette = LocalPlainTextPalette.current
    AsciiDivider(char = '=')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isBusy) "[WORKING...] $statusLog" else "> $statusLog",
            color = if (isBusy) palette.yellow else palette.green,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "HOLD=DETAILS | ${activePaneId.shortCode}",
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}
