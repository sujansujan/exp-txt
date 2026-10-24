package com.plaintextfiles.explorer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plaintextfiles.explorer.archive.ArchiveEngine
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.AppSettings
import com.plaintextfiles.explorer.model.ArchiveFormat
import com.plaintextfiles.explorer.model.ClipboardBuffer
import com.plaintextfiles.explorer.model.ClipboardMode
import com.plaintextfiles.explorer.model.DateFilterPreset
import com.plaintextfiles.explorer.model.DiffStatus
import com.plaintextfiles.explorer.model.DualPaneMode
import com.plaintextfiles.explorer.model.FileDetailedInfo
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.FilterConfig
import com.plaintextfiles.explorer.model.FilterTypeChip
import com.plaintextfiles.explorer.model.FontScalePreset
import com.plaintextfiles.explorer.model.PaneComparisonSummary
import com.plaintextfiles.explorer.model.PaneId
import com.plaintextfiles.explorer.model.PaneState
import com.plaintextfiles.explorer.model.PlainFileCategory
import com.plaintextfiles.explorer.model.RowDensity
import com.plaintextfiles.explorer.model.SharedIncomingItem
import com.plaintextfiles.explorer.model.SizeFilterPreset
import com.plaintextfiles.explorer.model.SortConfig
import com.plaintextfiles.explorer.model.SortField
import com.plaintextfiles.explorer.model.SortOrder
import com.plaintextfiles.explorer.model.ThemeMode
import com.plaintextfiles.explorer.model.TransferProgress
import com.plaintextfiles.explorer.model.TrashUndoBatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface PlainDialogState {
    data class ItemActionMenu(val paneId: PaneId, val entries: List<FileEntry>) : PlainDialogState
    data class SortAndFilterModal(val paneId: PaneId) : PlainDialogState
    data class QuickCreateModal(val paneId: PaneId) : PlainDialogState
    data object ComparePanesModal : PlainDialogState
    data class IncomingShareModal(
        val items: List<SharedIncomingItem>,
        val sharedText: String?
    ) : PlainDialogState
    data class CreateFolder(val paneId: PaneId) : PlainDialogState
    data class CreateFile(val paneId: PaneId) : PlainDialogState
    data class RenameEntry(val paneId: PaneId, val entry: FileEntry) : PlainDialogState
    data class BatchRename(val paneId: PaneId, val entries: List<FileEntry>) : PlainDialogState
    data class DeleteConfirm(val paneId: PaneId, val entries: List<FileEntry>) : PlainDialogState
    data class CompressArchive(
        val paneId: PaneId,
        val entries: List<FileEntry>,
        val defaultBaseName: String,
        val currentDirPath: String,
        val oppositeDirPath: String
    ) : PlainDialogState
    data class ExtractArchive(
        val paneId: PaneId,
        val archivePath: String,
        val archiveName: String,
        val currentDirPath: String,
        val oppositeDirPath: String,
        val selectedVirtualPaths: Set<String>? = null
    ) : PlainDialogState
    data class PasswordPrompt(
        val paneId: PaneId,
        val archivePath: String
    ) : PlainDialogState
}

data class ExplorerUiState(
    val settings: AppSettings = AppSettings(),
    val isSettingsOpen: Boolean = false,
    val activePaneId: PaneId = PaneId.PANE_A,
    val paneA: PaneState = PaneState(paneId = PaneId.PANE_A, currentPath = "/storage/emulated/0"),
    val paneB: PaneState = PaneState(paneId = PaneId.PANE_B, currentPath = "/storage/emulated/0/Download"),
    val pinnedPaths: Set<String> = emptySet(),
    val diffBadges: Map<String, DiffStatus> = emptyMap(),
    val compareSummary: PaneComparisonSummary? = null,
    val trashUndoBatch: TrashUndoBatch? = null,
    val trashItemCount: Int = 0,
    val hasStoragePermission: Boolean = false,
    val detailsPaneId: PaneId? = null,
    val detailsEntry: FileEntry? = null,
    val detailsInfo: FileDetailedInfo? = null,
    val isLoadingDetails: Boolean = false,
    val activeDialog: PlainDialogState? = null,
    val clipboard: ClipboardBuffer? = null,
    val transferProgress: TransferProgress? = null,
    val toastMessage: String? = null
) {
    fun getPane(id: PaneId): PaneState = if (id == PaneId.PANE_A) paneA else paneB
}

class ExplorerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("exp_txt_prefs_v9", Context.MODE_PRIVATE)
    val fileRepo = FileSystemRepository(application.applicationContext)
    val archiveEngine = ArchiveEngine(fileRepo)

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    private var lastBackAtHomeMillis: Long = 0L

    init {
        val loadedSettings = loadSettingsFromPrefs()
        val loadedPins = prefs.getStringSet("pinned_paths", emptySet())?.toSet() ?: emptySet()

        val rootPath = fileRepo.internalStorageRoot()
        val defaultDownload = File(rootPath, "Download").takeIf { it.exists() }?.absolutePath ?: rootPath

        val startA = if (loadedSettings.rememberLastPaths) {
            prefs.getString("last_path_a", rootPath)?.takeIf { File(it).exists() } ?: rootPath
        } else {
            rootPath
        }
        val startB = if (loadedSettings.rememberLastPaths) {
            prefs.getString("last_path_b", defaultDownload)?.takeIf { File(it).exists() } ?: defaultDownload
        } else {
            defaultDownload
        }

        val savedSortField = prefs.getString("sort_field", SortField.NAME.name)
            ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME
        val savedSortOrder = prefs.getString("sort_order", SortOrder.ASCENDING.name)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.ASCENDING

        val initialSort = SortConfig(
            field = savedSortField,
            order = savedSortOrder,
            foldersFirst = loadedSettings.foldersFirst,
            showHidden = loadedSettings.showHidden
        )

        _uiState.update {
            it.copy(
                settings = loadedSettings,
                pinnedPaths = loadedPins,
                hasStoragePermission = fileRepo.hasAllFilesAccess(),
                trashItemCount = fileRepo.getTrashItemCount(),
                paneA = it.paneA.copy(currentPath = startA, sortConfig = initialSort),
                paneB = it.paneB.copy(currentPath = startB, sortConfig = initialSort)
            )
        }

        loadPaneDirectory(PaneId.PANE_A, startA, recordHistory = false)
        loadPaneDirectory(PaneId.PANE_B, startB, recordHistory = false)
    }

    private fun loadSettingsFromPrefs(): AppSettings {
        val theme = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
        val layout = prefs.getString("dual_pane_mode", DualPaneMode.SPLIT_VERTICAL.name)
            ?.let { runCatching { DualPaneMode.valueOf(it) }.getOrNull() } ?: DualPaneMode.SPLIT_VERTICAL
        val density = prefs.getString("row_density", RowDensity.COMPACT.name)
            ?.let { runCatching { RowDensity.valueOf(it) }.getOrNull() } ?: RowDensity.COMPACT
        val font = prefs.getString("font_scale", FontScalePreset.NORMAL.name)
            ?.let { runCatching { FontScalePreset.valueOf(it) }.getOrNull() } ?: FontScalePreset.NORMAL

        return AppSettings(
            themeMode = theme,
            dualPaneMode = layout,
            rowDensity = density,
            fontScale = font,
            showFileIcons = prefs.getBoolean("show_file_icons", true),
            showPermissionSubtitle = prefs.getBoolean("show_permission_subtitle", false),
            foldersFirst = prefs.getBoolean("folders_first", true),
            showHidden = prefs.getBoolean("show_hidden", false),
            globalPinnedAtTop = prefs.getBoolean("global_pinned_at_top", true),
            globalSortAndFilter = prefs.getBoolean("global_sort_and_filter", true),
            rememberLastPaths = prefs.getBoolean("remember_last_paths", true),
            enableRowSwipeGesture = prefs.getBoolean("enable_row_swipe", true),
            showFastScrollRail = prefs.getBoolean("show_fast_scroll_rail", true),
            useTrashBin = prefs.getBoolean("use_trash_bin", true),
            showDiffHighlights = prefs.getBoolean("show_diff_highlights", false)
        )
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_uiState.value.settings)
        prefs.edit()
            .putString("theme_mode", next.themeMode.name)
            .putString("dual_pane_mode", next.dualPaneMode.name)
            .putString("row_density", next.rowDensity.name)
            .putString("font_scale", next.fontScale.name)
            .putBoolean("show_file_icons", next.showFileIcons)
            .putBoolean("show_permission_subtitle", next.showPermissionSubtitle)
            .putBoolean("folders_first", next.foldersFirst)
            .putBoolean("show_hidden", next.showHidden)
            .putBoolean("global_pinned_at_top", next.globalPinnedAtTop)
            .putBoolean("global_sort_and_filter", next.globalSortAndFilter)
            .putBoolean("remember_last_paths", next.rememberLastPaths)
            .putBoolean("enable_row_swipe", next.enableRowSwipeGesture)
            .putBoolean("show_fast_scroll_rail", next.showFastScrollRail)
            .putBoolean("use_trash_bin", next.useTrashBin)
            .putBoolean("show_diff_highlights", next.showDiffHighlights)
            .apply()

        _uiState.update { state ->
            state.copy(
                settings = next,
                paneA = state.paneA.copy(
                    sortConfig = state.paneA.sortConfig.copy(
                        foldersFirst = next.foldersFirst,
                        showHidden = next.showHidden
                    )
                ),
                paneB = state.paneB.copy(
                    sortConfig = state.paneB.sortConfig.copy(
                        foldersFirst = next.foldersFirst,
                        showHidden = next.showHidden
                    )
                )
            )
        }
        reloadPane(PaneId.PANE_A)
        reloadPane(PaneId.PANE_B)
    }

    fun showFeedback(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun openSettingsScreen() {
        _uiState.update { it.copy(isSettingsOpen = true, trashItemCount = fileRepo.getTrashItemCount()) }
    }

    fun closeSettingsScreen() {
        _uiState.update { it.copy(isSettingsOpen = false) }
    }

    fun refreshPermissionState() {
        val granted = fileRepo.hasAllFilesAccess()
        val prev = _uiState.value.hasStoragePermission
        _uiState.update { it.copy(hasStoragePermission = granted) }
        if (granted && !prev) {
            refreshAllPanes()
        }
    }

    fun refreshAllPanes() {
        _uiState.update {
            it.copy(
                hasStoragePermission = fileRepo.hasAllFilesAccess(),
                trashItemCount = fileRepo.getTrashItemCount()
            )
        }
        reloadPane(PaneId.PANE_A)
        reloadPane(PaneId.PANE_B)
    }

    fun setActivePane(paneId: PaneId) {
        if (_uiState.value.activePaneId != paneId) {
            _uiState.update { it.copy(activePaneId = paneId) }
        }
    }

    fun toggleActivePane() {
        val next = _uiState.value.activePaneId.opposite()
        _uiState.update {
            it.copy(
                activePaneId = next,
                toastMessage = "Active pane: ${next.label} (${next.shortCode})"
            )
        }
    }

    // =========================================================================
    // Unified Multi-Filter & Multi-Sort Studio (With Global Option)
    // =========================================================================
    fun applySortAndFilterBatch(
        targetPaneId: PaneId,
        applyGlobally: Boolean,
        newSort: SortConfig,
        newFilter: FilterConfig
    ) {
        prefs.edit()
            .putBoolean("global_sort_and_filter", applyGlobally)
            .putString("sort_field", newSort.field.name)
            .putString("sort_order", newSort.order.name)
            .putBoolean("folders_first", newSort.foldersFirst)
            .putBoolean("show_hidden", newSort.showHidden)
            .apply()

        _uiState.update { state ->
            val updatedSettings = state.settings.copy(
                globalSortAndFilter = applyGlobally,
                foldersFirst = newSort.foldersFirst,
                showHidden = newSort.showHidden
            )
            if (applyGlobally) {
                state.copy(
                    settings = updatedSettings,
                    activeDialog = null,
                    paneA = state.paneA.copy(sortConfig = newSort, filterConfig = newFilter),
                    paneB = state.paneB.copy(sortConfig = newSort, filterConfig = newFilter),
                    toastMessage = "Applied Sort & Filter globally (P1 & P2)"
                )
            } else {
                val nextA = if (targetPaneId == PaneId.PANE_A) {
                    state.paneA.copy(sortConfig = newSort, filterConfig = newFilter)
                } else {
                    state.paneA
                }
                val nextB = if (targetPaneId == PaneId.PANE_B) {
                    state.paneB.copy(sortConfig = newSort, filterConfig = newFilter)
                } else {
                    state.paneB
                }
                state.copy(
                    settings = updatedSettings,
                    activeDialog = null,
                    paneA = nextA,
                    paneB = nextB,
                    toastMessage = "Applied Sort & Filter to ${targetPaneId.shortCode}"
                )
            }
        }

        if (applyGlobally) {
            reloadPane(PaneId.PANE_A)
            reloadPane(PaneId.PANE_B)
        } else {
            reloadPane(targetPaneId)
        }
    }

    fun clearFiltersForPane(paneId: PaneId) {
        val global = _uiState.value.settings.globalSortAndFilter
        if (global) {
            _uiState.update { state ->
                state.copy(
                    paneA = state.paneA.copy(
                        filterConfig = FilterConfig(),
                        searchQuery = "",
                        isSearchVisible = false,
                        filteredEntries = applyAllFilters(state.paneA.entries, "", FilterConfig())
                    ),
                    paneB = state.paneB.copy(
                        filterConfig = FilterConfig(),
                        searchQuery = "",
                        isSearchVisible = false,
                        filteredEntries = applyAllFilters(state.paneB.entries, "", FilterConfig())
                    ),
                    toastMessage = "Cleared all filters (P1 & P2)"
                )
            }
        } else {
            updatePaneState(paneId) { pane ->
                pane.copy(
                    filterConfig = FilterConfig(),
                    searchQuery = "",
                    isSearchVisible = false,
                    filteredEntries = applyAllFilters(pane.entries, "", FilterConfig())
                )
            }
            showFeedback("Cleared filters in ${paneId.shortCode}")
        }
    }

    // =========================================================================
    // Feature 1: Dual-Pane Compare & Sync (DIFF P1 <-> P2)
    // =========================================================================
    private fun refreshPaneComparison() {
        val s = _uiState.value
        val (summary, badgeMap) = fileRepo.comparePaneEntries(
            entriesA = s.paneA.entries,
            entriesB = s.paneB.entries,
            pathA = s.paneA.compactTitle,
            pathB = s.paneB.compactTitle
        )
        _uiState.update {
            it.copy(
                compareSummary = summary,
                diffBadges = badgeMap,
                trashItemCount = fileRepo.getTrashItemCount()
            )
        }
    }

    fun openComparePanesModal() {
        refreshPaneComparison()
        _uiState.update { it.copy(activeDialog = PlainDialogState.ComparePanesModal) }
    }

    fun toggleDiffHighlights() {
        updateSettings { it.copy(showDiffHighlights = !it.showDiffHighlights) }
    }

    fun selectUniqueInPane(paneId: PaneId) {
        refreshPaneComparison()
        val summary = _uiState.value.compareSummary ?: return
        val uniqueItems = if (paneId == PaneId.PANE_A) summary.onlyInA else summary.onlyInB
        val keys = uniqueItems.map { it.uniqueKey }.toSet()
        updatePaneState(paneId) { it.copy(selectedKeys = keys) }
        setActivePane(paneId)
        _uiState.update {
            it.copy(
                activeDialog = null,
                toastMessage = "Selected ${keys.size} unique item(s) in ${paneId.shortCode}"
            )
        }
    }

    fun syncMissingAndNewer(sourcePaneId: PaneId) {
        refreshPaneComparison()
        val summary = _uiState.value.compareSummary ?: return
        val toSync = if (sourcePaneId == PaneId.PANE_A) summary.syncAToBEntries else summary.syncBToAEntries
        if (toSync.isEmpty()) {
            _uiState.update { it.copy(activeDialog = null, toastMessage = "Panes are already in sync") }
            return
        }
        copyOrMoveToOppositePane(sourcePaneId, toSync, move = false)
    }

    // =========================================================================
    // Feature 4: Raw Path Editor & Trash / Undo Buffer
    // =========================================================================
    fun setPathEditorVisible(paneId: PaneId, visible: Boolean) {
        updatePaneState(paneId) { it.copy(isPathEditorVisible = visible) }
    }

    fun navigateToRawPath(paneId: PaneId, rawPath: String, createIfMissing: Boolean = false) {
        val cleaned = rawPath.trim()
        if (cleaned.isEmpty()) return
        val target = if (cleaned.startsWith("~")) {
            fileRepo.internalStorageRoot() + cleaned.removePrefix("~")
        } else {
            cleaned
        }
        val dir = File(target)
        if (!dir.exists() && createIfMissing) {
            dir.mkdirs()
        }
        if (dir.exists() && dir.isDirectory) {
            updatePaneState(paneId) { it.copy(isPathEditorVisible = false) }
            loadPaneDirectory(paneId, dir.absolutePath, recordHistory = true)
        } else if (dir.exists() && dir.isFile && ArchiveFormat.isArchiveFileName(dir.name)) {
            updatePaneState(paneId) { it.copy(isPathEditorVisible = false) }
            loadVirtualArchive(paneId, dir.absolutePath)
        } else {
            updatePaneState(paneId) {
                it.copy(errorMessage = "Path not found: $target")
            }
        }
    }

    fun openTrashInActivePane() {
        val activeId = _uiState.value.activePaneId
        val trashPath = fileRepo.trashDirectoryPath()
        closeSettingsScreen()
        loadPaneDirectory(activeId, trashPath, recordHistory = true)
    }

    fun undoLastTrash() {
        val batch = _uiState.value.trashUndoBatch ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    trashUndoBatch = null,
                    transferProgress = TransferProgress("Restoring from Trash...", 0, batch.records.size)
                )
            }
            fileRepo.restoreTrashRecords(batch.records) { cur, tot, msg ->
                _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
            }.fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            toastMessage = "Restored $count item(s) from Trash"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            toastMessage = "Restore failed: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun dismissTrashUndoBanner() {
        _uiState.update { it.copy(trashUndoBatch = null) }
    }

    fun emptyTrashBin() {
        viewModelScope.launch {
            fileRepo.emptyTrashBin().fold(
                onSuccess = { removed ->
                    _uiState.update {
                        it.copy(
                            trashUndoBatch = null,
                            trashItemCount = 0,
                            toastMessage = "Emptied Trash ($removed item(s) removed)"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(toastMessage = "Empty Trash Error: ${err.message}") }
                }
            )
        }
    }

    // =========================================================================
    // Feature 5: System "Share to exp-txt" Target
    // =========================================================================
    fun onReceiveExternalShare(uris: List<Uri>, sharedText: String?) {
        viewModelScope.launch {
            val inspected = uris.map { fileRepo.inspectIncomingUri(it) }
            _uiState.update {
                it.copy(
                    isSettingsOpen = false,
                    activeDialog = PlainDialogState.IncomingShareModal(
                        items = inspected,
                        sharedText = sharedText
                    )
                )
            }
        }
    }

    fun saveIncomingShare(
        targetPaneId: PaneId,
        items: List<SharedIncomingItem>,
        sharedText: String?,
        compressToZip: Boolean,
        zipBaseName: String = "shared_bundle"
    ) {
        val destDirPath = _uiState.value.getPane(targetPaneId).currentPath
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeDialog = null,
                    transferProgress = TransferProgress(
                        "Saving shared item(s) to ${targetPaneId.shortCode}...",
                        0,
                        items.size.coerceAtLeast(1)
                    )
                )
            }
            fileRepo.saveSharedItemsToDirectory(items, sharedText, destDirPath) { cur, tot, msg ->
                _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
            }.fold(
                onSuccess = { savedFiles ->
                    if (compressToZip && savedFiles.isNotEmpty()) {
                        val entriesToPack = savedFiles.map { fileRepo.fileToFileEntry(it) }
                        val cleanZipName = zipBaseName.trim().ifEmpty { "shared_bundle" }
                        archiveEngine.createArchive(
                            sourceEntries = entriesToPack,
                            destinationDirPath = destDirPath,
                            archiveBaseName = cleanZipName,
                            format = ArchiveFormat.ZIP,
                            password = null
                        ) { cur, tot, msg ->
                            _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
                        }.onSuccess {
                            savedFiles.forEach { f -> f.deleteRecursively() }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            activePaneId = targetPaneId,
                            toastMessage = "Saved ${savedFiles.size} shared item(s) to ${targetPaneId.shortCode}"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            toastMessage = "Share Save Error: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    // =========================================================================
    // Pinned Items Management (Always at Top of Main List)
    // =========================================================================
    fun togglePinEntry(entry: FileEntry) {
        if (entry.isVirtualArchiveEntry) return
        val currentPins = _uiState.value.pinnedPaths.toMutableSet()
        val added = if (entry.path in currentPins) {
            currentPins.remove(entry.path)
            false
        } else {
            currentPins.add(entry.path)
            true
        }
        prefs.edit().putStringSet("pinned_paths", currentPins).apply()
        _uiState.update {
            it.copy(
                pinnedPaths = currentPins,
                activeDialog = null,
                toastMessage = if (added) "★ Pinned '${entry.name}' to top" else "☆ Unpinned '${entry.name}'"
            )
        }
        reloadPane(PaneId.PANE_A)
        reloadPane(PaneId.PANE_B)
    }

    fun pinSelectedInPane(paneId: PaneId) {
        val pane = _uiState.value.getPane(paneId)
        val selectedEntries = pane.entries.filter { it.uniqueKey in pane.selectedKeys && !it.isVirtualArchiveEntry }
        if (selectedEntries.isEmpty()) return
        val currentPins = _uiState.value.pinnedPaths.toMutableSet()
        val allAlreadyPinned = selectedEntries.all { it.path in currentPins }
        if (allAlreadyPinned) {
            selectedEntries.forEach { currentPins.remove(it.path) }
        } else {
            selectedEntries.forEach { currentPins.add(it.path) }
        }
        prefs.edit().putStringSet("pinned_paths", currentPins).apply()
        _uiState.update {
            it.copy(
                pinnedPaths = currentPins,
                toastMessage = if (allAlreadyPinned) {
                    "☆ Unpinned ${selectedEntries.size} item(s)"
                } else {
                    "★ Pinned ${selectedEntries.size} item(s) to top"
                }
            )
        }
        clearSelection(paneId)
        reloadPane(PaneId.PANE_A)
        reloadPane(PaneId.PANE_B)
    }

    fun clearAllPinnedItems() {
        prefs.edit().putStringSet("pinned_paths", emptySet()).apply()
        _uiState.update {
            it.copy(
                pinnedPaths = emptySet(),
                toastMessage = "Cleared all pinned items"
            )
        }
        reloadPane(PaneId.PANE_A)
        reloadPane(PaneId.PANE_B)
    }

    fun reloadPane(paneId: PaneId) {
        val pane = _uiState.value.getPane(paneId)
        if (pane.activeArchivePath != null) {
            loadVirtualArchive(
                paneId = paneId,
                archivePath = pane.activeArchivePath,
                internalDir = pane.activeArchiveInternalDir,
                password = pane.activeArchivePassword,
                recordHistory = false
            )
        } else {
            loadPaneDirectory(paneId, pane.currentPath, recordHistory = false)
        }
    }

    fun loadPaneDirectory(
        paneId: PaneId,
        targetPath: String,
        recordHistory: Boolean = true
    ) {
        viewModelScope.launch {
            val stateSnapshot = _uiState.value
            val currentPane = stateSnapshot.getPane(paneId)
            val updatedHistory = if (recordHistory &&
                (currentPane.currentPath != targetPath || currentPane.activeArchivePath != null)
            ) {
                currentPane.historyBackStack + Pair(
                    currentPane.currentPath,
                    Pair(currentPane.activeArchivePath, currentPane.activeArchiveInternalDir)
                )
            } else {
                currentPane.historyBackStack
            }

            if (stateSnapshot.settings.rememberLastPaths && !fileRepo.isTrashPath(targetPath)) {
                val key = if (paneId == PaneId.PANE_A) "last_path_a" else "last_path_b"
                prefs.edit().putString(key, targetPath).apply()
            }

            updatePaneState(paneId) {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    activeArchivePath = null,
                    activeArchiveInternalDir = "",
                    activeArchivePassword = null
                )
            }

            val statPair = fileRepo.queryStatFs(File(targetPath))
            val result = fileRepo.listDirectory(
                dirPath = targetPath,
                sortConfig = _uiState.value.getPane(paneId).sortConfig,
                pinnedPaths = _uiState.value.pinnedPaths,
                globalPinnedAtTop = _uiState.value.settings.globalPinnedAtTop
            )

            result.fold(
                onSuccess = { items ->
                    updatePaneState(paneId) { pane ->
                        val filtered = applyAllFilters(items, pane.searchQuery, pane.filterConfig)
                        pane.copy(
                            currentPath = targetPath,
                            entries = items,
                            filteredEntries = filtered,
                            selectedKeys = emptySet(),
                            isLoading = false,
                            errorMessage = null,
                            historyBackStack = updatedHistory.takeLast(40),
                            totalCapacityBytes = statPair.first,
                            totalFreeBytes = statPair.second
                        )
                    }
                    refreshPaneComparison()
                },
                onFailure = { err ->
                    updatePaneState(paneId) { pane ->
                        pane.copy(
                            currentPath = targetPath,
                            entries = emptyList(),
                            filteredEntries = emptyList(),
                            isLoading = false,
                            errorMessage = err.message ?: "Unable to read folder",
                            historyBackStack = updatedHistory.takeLast(40)
                        )
                    }
                }
            )
        }
    }

    fun loadVirtualArchive(
        paneId: PaneId,
        archivePath: String,
        internalDir: String = "",
        password: String? = null,
        recordHistory: Boolean = true
    ) {
        viewModelScope.launch {
            val currentPane = _uiState.value.getPane(paneId)
            val effectivePassword = password ?: currentPane.activeArchivePassword
            val updatedHistory = if (recordHistory) {
                currentPane.historyBackStack + Pair(
                    currentPane.currentPath,
                    Pair(currentPane.activeArchivePath, currentPane.activeArchiveInternalDir)
                )
            } else {
                currentPane.historyBackStack
            }

            updatePaneState(paneId) { it.copy(isLoading = true, errorMessage = null) }

            val result = archiveEngine.listVirtualDirectory(
                archivePath = archivePath,
                internalDir = internalDir,
                sortConfig = currentPane.sortConfig,
                password = effectivePassword
            )

            result.fold(
                onSuccess = { virtualItems ->
                    updatePaneState(paneId) { pane ->
                        val filtered = applyAllFilters(virtualItems, pane.searchQuery, pane.filterConfig)
                        pane.copy(
                            activeArchivePath = archivePath,
                            activeArchiveInternalDir = internalDir.trim('/'),
                            activeArchivePassword = effectivePassword,
                            entries = virtualItems,
                            filteredEntries = filtered,
                            selectedKeys = emptySet(),
                            isLoading = false,
                            errorMessage = null,
                            historyBackStack = updatedHistory.takeLast(40)
                        )
                    }
                    refreshPaneComparison()
                },
                onFailure = { err ->
                    val msg = err.message.orEmpty()
                    updatePaneState(paneId) { it.copy(isLoading = false) }
                    if (msg.contains("Encrypted", ignoreCase = true) ||
                        msg.contains("Password", ignoreCase = true)
                    ) {
                        _uiState.update {
                            it.copy(
                                activeDialog = PlainDialogState.PasswordPrompt(
                                    paneId = paneId,
                                    archivePath = archivePath
                                )
                            )
                        }
                    } else {
                        updatePaneState(paneId) {
                            it.copy(errorMessage = "Archive Error: ${err.message}")
                        }
                    }
                }
            )
        }
    }

    fun navigateUpInPane(paneId: PaneId) {
        val pane = _uiState.value.getPane(paneId)
        if (pane.activeArchivePath != null) {
            val inner = pane.activeArchiveInternalDir.trim('/')
            if (inner.isEmpty()) {
                loadPaneDirectory(paneId, pane.currentPath, recordHistory = false)
            } else {
                val parentInner = inner.substringBeforeLast('/', "")
                loadVirtualArchive(
                    paneId = paneId,
                    archivePath = pane.activeArchivePath,
                    internalDir = parentInner,
                    password = pane.activeArchivePassword,
                    recordHistory = false
                )
            }
            return
        }

        val internalRoot = fileRepo.internalStorageRoot()
        val currentFile = File(pane.currentPath)
        val parent = currentFile.parentFile
        if (parent != null && parent.exists() && pane.currentPath != internalRoot) {
            val nextPath = if (parent.absolutePath.startsWith(internalRoot)) {
                parent.absolutePath
            } else {
                internalRoot
            }
            loadPaneDirectory(paneId, nextPath, recordHistory = true)
        }
    }

    /**
     * System Back Gesture:
     * - Operates strictly on whichever pane is currently active (`activePaneId`)
     * - Always navigates the active pane back to the Home section (`/storage/emulated/0`)
     *   before allowing the app to close.
     */
    fun navigateBackOnSystemPress(): Boolean {
        val s = _uiState.value
        if (s.detailsEntry != null) {
            dismissDetailsModal()
            return true
        }
        if (s.activeDialog != null) {
            dismissDialog()
            return true
        }
        if (s.isSettingsOpen) {
            closeSettingsScreen()
            return true
        }

        val activeId = s.activePaneId
        val pane = s.getPane(activeId)

        if (pane.selectedKeys.isNotEmpty()) {
            clearSelection(activeId)
            showFeedback("Cleared selection in ${activeId.shortCode}")
            return true
        }
        if (pane.isPathEditorVisible) {
            setPathEditorVisible(activeId, false)
            return true
        }
        if (pane.isSearchVisible || pane.searchQuery.isNotEmpty()) {
            setPaneSearchVisible(activeId, false)
            return true
        }
        if (pane.filterConfig.isActive) {
            clearFiltersForPane(activeId)
            return true
        }
        if (pane.activeArchivePath != null) {
            navigateUpInPane(activeId)
            return true
        }
        if (pane.historyBackStack.isNotEmpty()) {
            val last = pane.historyBackStack.last()
            val poppedStack = pane.historyBackStack.dropLast(1)
            updatePaneState(activeId) { it.copy(historyBackStack = poppedStack) }
            val (prevDir, arcPair) = last
            val (prevArcPath, prevArcInner) = arcPair
            if (prevArcPath != null) {
                loadVirtualArchive(activeId, prevArcPath, prevArcInner, recordHistory = false)
            } else {
                loadPaneDirectory(activeId, prevDir, recordHistory = false)
            }
            return true
        }

        // If back stack is empty, ensure the active pane returns to Home (~) before closing the app
        val internalRoot = fileRepo.internalStorageRoot()
        if (pane.currentPath != internalRoot) {
            val parent = File(pane.currentPath).parentFile
            val targetPath = if (parent != null &&
                parent.exists() &&
                parent.absolutePath.startsWith(internalRoot) &&
                parent.absolutePath != pane.currentPath
            ) {
                parent.absolutePath
            } else {
                internalRoot
            }
            loadPaneDirectory(activeId, targetPath, recordHistory = false)
            showFeedback("${activeId.shortCode}: Back to ${if (targetPath == internalRoot) "Home (~)" else targetPath.substringAfterLast('/')}")
            return true
        }

        // Active pane is now at Home (~). Require double-back within 2.2s to close app.
        val now = System.currentTimeMillis()
        if (now - lastBackAtHomeMillis > 2200L) {
            lastBackAtHomeMillis = now
            showFeedback("At Home (~) in ${activeId.shortCode} — press Back again to exit")
            return true
        }
        return false
    }

    fun onSingleTapEntry(
        paneId: PaneId,
        entry: FileEntry,
        cacheDir: File,
        onLaunchExternalFile: (File) -> Unit
    ) {
        setActivePane(paneId)
        val pane = _uiState.value.getPane(paneId)

        if (pane.selectedKeys.isNotEmpty()) {
            toggleSelection(paneId, entry)
            return
        }

        if (entry.isVirtualArchiveEntry) {
            val hostArc = entry.archiveHostPath ?: return
            val internalPath = entry.archiveInternalPath ?: return
            if (entry.isDirectory) {
                loadVirtualArchive(
                    paneId = paneId,
                    archivePath = hostArc,
                    internalDir = internalPath,
                    password = pane.activeArchivePassword
                )
            } else {
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(transferProgress = TransferProgress("Opening ${entry.name}...", 1, 1))
                    }
                    archiveEngine.extractSingleEntryToCache(
                        archivePath = hostArc,
                        internalPath = internalPath,
                        cacheDir = cacheDir,
                        password = pane.activeArchivePassword
                    ).fold(
                        onSuccess = { extractedFile ->
                            _uiState.update { it.copy(transferProgress = null) }
                            onLaunchExternalFile(extractedFile)
                        },
                        onFailure = { err ->
                            _uiState.update {
                                it.copy(
                                    transferProgress = null,
                                    toastMessage = "Extract error: ${err.message}"
                                )
                            }
                        }
                    )
                }
            }
            return
        }

        if (entry.isDirectory) {
            loadPaneDirectory(paneId, entry.path, recordHistory = true)
            return
        }

        if (entry.isArchive) {
            loadVirtualArchive(paneId, entry.path, internalDir = "")
            return
        }

        onLaunchExternalFile(File(entry.path))
    }

    fun onDoubleTapEntry(paneId: PaneId, entry: FileEntry) {
        setActivePane(paneId)
        val pane = _uiState.value.getPane(paneId)
        _uiState.update {
            it.copy(
                activeDialog = null,
                detailsPaneId = paneId,
                detailsEntry = entry,
                detailsInfo = null,
                isLoadingDetails = true,
                toastMessage = "Inspecting '${entry.name}'"
            )
        }
        viewModelScope.launch {
            val detailed = fileRepo.inspectFileDetails(
                entry = entry,
                archiveEngine = archiveEngine,
                password = pane.activeArchivePassword
            )
            _uiState.update {
                if (it.detailsEntry?.uniqueKey == entry.uniqueKey) {
                    it.copy(detailsInfo = detailed, isLoadingDetails = false)
                } else {
                    it
                }
            }
        }
    }

    fun dismissDetailsModal() {
        _uiState.update {
            it.copy(
                detailsPaneId = null,
                detailsEntry = null,
                detailsInfo = null,
                isLoadingDetails = false
            )
        }
    }

    fun onLongPressEntry(paneId: PaneId, entry: FileEntry) {
        setActivePane(paneId)
        val pane = _uiState.value.getPane(paneId)
        val currentSelectedKeys = pane.selectedKeys.toMutableSet()
        currentSelectedKeys.add(entry.uniqueKey)
        updatePaneState(paneId) { it.copy(selectedKeys = currentSelectedKeys) }

        val targetEntries = pane.entries.filter { it.uniqueKey in currentSelectedKeys }
            .ifEmpty { listOf(entry) }

        _uiState.update {
            it.copy(
                activeDialog = PlainDialogState.ItemActionMenu(
                    paneId = paneId,
                    entries = targetEntries
                )
            )
        }
    }

    fun openSelectionActionMenu(paneId: PaneId) {
        val pane = _uiState.value.getPane(paneId)
        val selected = pane.entries.filter { it.uniqueKey in pane.selectedKeys }
        if (selected.isNotEmpty()) {
            _uiState.update {
                it.copy(activeDialog = PlainDialogState.ItemActionMenu(paneId, selected))
            }
        }
    }

    fun toggleSelection(paneId: PaneId, entry: FileEntry, fromSwipe: Boolean = false) {
        var nowSelected = false
        updatePaneState(paneId) { pane ->
            val next = pane.selectedKeys.toMutableSet()
            if (next.add(entry.uniqueKey)) {
                nowSelected = true
            } else {
                next.remove(entry.uniqueKey)
                nowSelected = false
            }
            pane.copy(selectedKeys = next)
        }
        if (fromSwipe) {
            val totalSel = _uiState.value.getPane(paneId).selectedKeys.size
            showFeedback(
                if (nowSelected) {
                    "✓ Selected '${entry.name}' ($totalSel selected)"
                } else {
                    "Unselected '${entry.name}' ($totalSel selected)"
                }
            )
        }
    }

    fun selectAllInPane(paneId: PaneId) {
        updatePaneState(paneId) { pane ->
            val allKeys = pane.filteredEntries.map { it.uniqueKey }.toSet()
            val next = if (pane.selectedKeys.size == allKeys.size) emptySet() else allKeys
            pane.copy(selectedKeys = next)
        }
    }

    fun clearSelection(paneId: PaneId) {
        updatePaneState(paneId) { it.copy(selectedKeys = emptySet()) }
    }

    fun setPaneSearchVisible(paneId: PaneId, visible: Boolean) {
        val global = _uiState.value.settings.globalSortAndFilter
        if (global && !visible) {
            _uiState.update { state ->
                state.copy(
                    paneA = state.paneA.copy(
                        isSearchVisible = false,
                        searchQuery = "",
                        filteredEntries = applyAllFilters(state.paneA.entries, "", state.paneA.filterConfig)
                    ),
                    paneB = state.paneB.copy(
                        isSearchVisible = false,
                        searchQuery = "",
                        filteredEntries = applyAllFilters(state.paneB.entries, "", state.paneB.filterConfig)
                    )
                )
            }
        } else {
            updatePaneState(paneId) { pane ->
                val nextQuery = if (!visible) "" else pane.searchQuery
                pane.copy(
                    isSearchVisible = visible,
                    searchQuery = nextQuery,
                    filteredEntries = applyAllFilters(pane.entries, nextQuery, pane.filterConfig)
                )
            }
        }
    }

    fun updateSearchQuery(paneId: PaneId, query: String) {
        val global = _uiState.value.settings.globalSortAndFilter
        if (global) {
            _uiState.update { state ->
                state.copy(
                    paneA = state.paneA.copy(
                        searchQuery = query,
                        filteredEntries = applyAllFilters(state.paneA.entries, query, state.paneA.filterConfig)
                    ),
                    paneB = state.paneB.copy(
                        searchQuery = query,
                        filteredEntries = applyAllFilters(state.paneB.entries, query, state.paneB.filterConfig)
                    )
                )
            }
        } else {
            updatePaneState(paneId) { pane ->
                pane.copy(
                    searchQuery = query,
                    filteredEntries = applyAllFilters(pane.entries, query, pane.filterConfig)
                )
            }
        }
    }

    fun syncOppositePaneTo(sourcePaneId: PaneId) {
        val sourcePane = _uiState.value.getPane(sourcePaneId)
        val targetPaneId = sourcePaneId.opposite()
        if (sourcePane.activeArchivePath != null) {
            loadVirtualArchive(
                paneId = targetPaneId,
                archivePath = sourcePane.activeArchivePath,
                internalDir = sourcePane.activeArchiveInternalDir,
                password = sourcePane.activeArchivePassword
            )
        } else {
            loadPaneDirectory(targetPaneId, sourcePane.currentPath)
        }
        showFeedback("Mirrored ${sourcePaneId.shortCode} -> ${targetPaneId.shortCode}")
    }

    fun swapPanes() {
        val currentA = _uiState.value.paneA
        val currentB = _uiState.value.paneB
        _uiState.update {
            it.copy(
                paneA = currentB.copy(paneId = PaneId.PANE_A),
                paneB = currentA.copy(paneId = PaneId.PANE_B),
                toastMessage = "Swapped Pane 1 <-> Pane 2"
            )
        }
        refreshPaneComparison()
    }

    fun showDialog(dialog: PlainDialogState) {
        _uiState.update { it.copy(activeDialog = dialog) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = null) }
    }

    fun createNewFolder(paneId: PaneId, folderName: String) {
        val path = _uiState.value.getPane(paneId).currentPath
        viewModelScope.launch {
            fileRepo.createFolder(path, folderName).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            toastMessage = "Created folder '$folderName'"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(toastMessage = "Error: ${err.message}") }
                }
            )
        }
    }

    fun createNewFile(paneId: PaneId, fileName: String) {
        val path = _uiState.value.getPane(paneId).currentPath
        viewModelScope.launch {
            fileRepo.createEmptyFile(path, fileName).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            toastMessage = "Created file '$fileName'"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(toastMessage = "Error: ${err.message}") }
                }
            )
        }
    }

    fun renameFileEntry(paneId: PaneId, entry: FileEntry, newName: String) {
        viewModelScope.launch {
            fileRepo.renameEntry(entry, newName).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            toastMessage = "Renamed to '$newName'"
                        )
                    }
                    clearSelection(paneId)
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(toastMessage = "Rename Error: ${err.message}") }
                }
            )
        }
    }

    fun executeBatchRename(paneId: PaneId, entries: List<FileEntry>, prefix: String, startNumber: Int) {
        viewModelScope.launch {
            fileRepo.batchRenameEntries(entries, prefix, startNumber).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            toastMessage = "Batch renamed $count item(s)"
                        )
                    }
                    clearSelection(paneId)
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(toastMessage = "Batch Rename Error: ${err.message}") }
                }
            )
        }
    }

    fun deleteFileEntries(
        paneId: PaneId,
        entries: List<FileEntry>,
        forcePermanent: Boolean = false
    ) {
        val currentDir = _uiState.value.getPane(paneId).currentPath
        val shouldUseTrash = !forcePermanent &&
            _uiState.value.settings.useTrashBin &&
            !fileRepo.isTrashPath(currentDir)

        viewModelScope.launch {
            if (shouldUseTrash) {
                _uiState.update {
                    it.copy(
                        activeDialog = null,
                        transferProgress = TransferProgress("Moving to Trash...", 0, entries.size)
                    )
                }
                fileRepo.moveEntriesToTrash(entries) { cur, tot, msg ->
                    _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
                }.fold(
                    onSuccess = { records ->
                        val label = if (records.size == 1) {
                            "Trashed '${records.first().originalName}'"
                        } else {
                            "Trashed ${records.size} items"
                        }
                        _uiState.update {
                            it.copy(
                                transferProgress = null,
                                trashUndoBatch = if (records.isNotEmpty()) {
                                    TrashUndoBatch(records, paneId, label)
                                } else {
                                    null
                                },
                                trashItemCount = fileRepo.getTrashItemCount()
                            )
                        }
                        clearSelection(paneId)
                        reloadPane(PaneId.PANE_A)
                        reloadPane(PaneId.PANE_B)
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(transferProgress = null, toastMessage = "Trash Error: ${err.message}")
                        }
                    }
                )
            } else {
                _uiState.update {
                    it.copy(
                        activeDialog = null,
                        transferProgress = TransferProgress("Deleting permanently...", 0, entries.size)
                    )
                }
                fileRepo.deleteEntries(entries) { cur, tot, msg ->
                    _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
                }.fold(
                    onSuccess = { count ->
                        _uiState.update {
                            it.copy(
                                transferProgress = null,
                                trashItemCount = fileRepo.getTrashItemCount(),
                                toastMessage = "Deleted $count item(s) permanently"
                            )
                        }
                        clearSelection(paneId)
                        reloadPane(PaneId.PANE_A)
                        reloadPane(PaneId.PANE_B)
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(transferProgress = null, toastMessage = "Delete Error: ${err.message}")
                        }
                    }
                )
            }
        }
    }

    fun copyOrMoveToOppositePane(sourcePaneId: PaneId, entries: List<FileEntry>, move: Boolean) {
        val targetPaneId = sourcePaneId.opposite()
        val targetPane = _uiState.value.getPane(targetPaneId)

        if (entries.any { it.isVirtualArchiveEntry }) {
            val first = entries.first()
            val hostArc = first.archiveHostPath ?: return
            val selectedPaths = entries.mapNotNull { it.archiveInternalPath }.toSet()
            executeExtractArchive(
                archivePath = hostArc,
                destinationDirPath = targetPane.currentPath,
                createSubfolder = false,
                password = _uiState.value.getPane(sourcePaneId).activeArchivePassword,
                selectedVirtualPaths = selectedPaths
            )
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeDialog = null,
                    transferProgress = TransferProgress(
                        "${if (move) "Moving" else "Copying"} to ${targetPaneId.shortCode}...",
                        0,
                        entries.size
                    )
                )
            }
            fileRepo.copyOrMoveEntries(entries, targetPane.currentPath, move) { cur, tot, msg ->
                _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
            }.fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            toastMessage = "${if (move) "Moved" else "Copied"} $count item(s) -> ${targetPaneId.shortCode}"
                        )
                    }
                    clearSelection(sourcePaneId)
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(transferProgress = null, toastMessage = "Transfer Error: ${err.message}")
                    }
                }
            )
        }
    }

    fun stageToClipboard(paneId: PaneId, entries: List<FileEntry>, mode: ClipboardMode) {
        val realEntries = entries.filter { !it.isVirtualArchiveEntry }
        if (realEntries.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = ClipboardBuffer(entries = realEntries, sourcePane = paneId, mode = mode),
                activeDialog = null,
                toastMessage = "${if (mode == ClipboardMode.MOVE) "Cut" else "Copied"} ${realEntries.size} item(s) to clipboard"
            )
        }
        clearSelection(paneId)
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null, toastMessage = "Clipboard cleared") }
    }

    fun pasteClipboardIntoPane(targetPaneId: PaneId) {
        val clip = _uiState.value.clipboard ?: return
        val destPath = _uiState.value.getPane(targetPaneId).currentPath
        val isMove = clip.mode == ClipboardMode.MOVE

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeDialog = null,
                    transferProgress = TransferProgress("Pasting...", 0, clip.entries.size)
                )
            }
            fileRepo.copyOrMoveEntries(clip.entries, destPath, isMove) { cur, tot, msg ->
                _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
            }.fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            clipboard = if (isMove) null else it.clipboard,
                            toastMessage = "Pasted $count item(s) into ${targetPaneId.shortCode}"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(transferProgress = null, toastMessage = "Paste Error: ${err.message}")
                    }
                }
            )
        }
    }

    fun openCompressDialog(paneId: PaneId, entries: List<FileEntry>) {
        val realEntries = entries.filter { !it.isVirtualArchiveEntry }
        if (realEntries.isEmpty()) return
        val pane = _uiState.value.getPane(paneId)
        val oppositePane = _uiState.value.getPane(paneId.opposite())
        val defaultName = if (realEntries.size == 1) {
            ArchiveEngine.stripArchiveExtensions(realEntries.first().name)
        } else {
            pane.currentPath.substringAfterLast('/').ifEmpty { "archive" }
        }
        _uiState.update {
            it.copy(
                activeDialog = PlainDialogState.CompressArchive(
                    paneId = paneId,
                    entries = realEntries,
                    defaultBaseName = defaultName,
                    currentDirPath = pane.currentPath,
                    oppositeDirPath = oppositePane.currentPath
                )
            )
        }
    }

    fun openExtractDialog(
        paneId: PaneId,
        archivePath: String,
        selectedVirtualPaths: Set<String>? = null
    ) {
        val pane = _uiState.value.getPane(paneId)
        val oppositePane = _uiState.value.getPane(paneId.opposite())
        _uiState.update {
            it.copy(
                activeDialog = PlainDialogState.ExtractArchive(
                    paneId = paneId,
                    archivePath = archivePath,
                    archiveName = archivePath.substringAfterLast('/'),
                    currentDirPath = pane.currentPath,
                    oppositeDirPath = oppositePane.currentPath,
                    selectedVirtualPaths = selectedVirtualPaths
                )
            )
        }
    }

    fun executeCompressArchive(
        paneId: PaneId,
        entries: List<FileEntry>,
        destinationDirPath: String,
        archiveBaseName: String,
        format: ArchiveFormat,
        password: String?
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeDialog = null,
                    transferProgress = TransferProgress(
                        "Creating $archiveBaseName.${format.primaryExtension}...",
                        0,
                        entries.size
                    )
                )
            }
            archiveEngine.createArchive(
                sourceEntries = entries,
                destinationDirPath = destinationDirPath,
                archiveBaseName = archiveBaseName,
                format = format,
                password = password?.takeIf { it.isNotBlank() }
            ) { cur, tot, msg ->
                _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
            }.fold(
                onSuccess = { outFile ->
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            toastMessage = "Created ${outFile.name}"
                        )
                    }
                    clearSelection(paneId)
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(transferProgress = null, toastMessage = "Compress Error: ${err.message}")
                    }
                }
            )
        }
    }

    fun executeExtractArchive(
        archivePath: String,
        destinationDirPath: String,
        createSubfolder: Boolean,
        password: String? = null,
        selectedVirtualPaths: Set<String>? = null
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeDialog = null,
                    transferProgress = TransferProgress(
                        "Extracting ${archivePath.substringAfterLast('/')}...",
                        0,
                        1
                    )
                )
            }
            archiveEngine.extractArchive(
                archivePath = archivePath,
                destinationDirPath = destinationDirPath,
                createSubfolder = createSubfolder,
                password = password?.takeIf { it.isNotBlank() },
                selectedVirtualPaths = selectedVirtualPaths
            ) { cur, tot, msg ->
                _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
            }.fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            toastMessage = "Extracted $count item(s)"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(transferProgress = null, toastMessage = "Extract Error: ${err.message}")
                    }
                }
            )
        }
    }

    fun togglePermissionBit(
        paneId: PaneId,
        entry: FileEntry,
        readable: Boolean,
        writable: Boolean,
        executable: Boolean
    ) {
        viewModelScope.launch {
            fileRepo.updatePermissions(entry, readable, writable, executable).onSuccess {
                reloadPane(paneId)
                val updatedFile = File(entry.path)
                onDoubleTapEntry(
                    paneId,
                    fileRepo.fileToFileEntry(updatedFile, isPinned = entry.path in _uiState.value.pinnedPaths)
                )
            }
        }
    }

    /**
     * Applies both quick search query and multi-criteria FilterConfig to entries.
     */
    fun applyAllFilters(
        entries: List<FileEntry>,
        searchQuery: String,
        filterConfig: FilterConfig
    ): List<FileEntry> {
        val cleanSearch = searchQuery.trim()
        val cleanKeyword = filterConfig.extensionOrKeyword.trim()
        val now = System.currentTimeMillis()

        if (cleanSearch.isEmpty() && !filterConfig.isActive) {
            return entries
        }

        return entries.filter { entry ->
            if (cleanSearch.isNotEmpty() && !entry.name.contains(cleanSearch, ignoreCase = true)) {
                return@filter false
            }
            if (cleanKeyword.isNotEmpty() &&
                !entry.name.contains(cleanKeyword, ignoreCase = true) &&
                !entry.extension.equals(cleanKeyword.removePrefix("."), ignoreCase = true)
            ) {
                return@filter false
            }
            if (filterConfig.onlyPinned && !entry.isPinned) {
                return@filter false
            }
            if (filterConfig.selectedTypes.isNotEmpty()) {
                val matchesAnyType = filterConfig.selectedTypes.any { chip ->
                    when (chip) {
                        FilterTypeChip.FOLDERS -> entry.isDirectory
                        FilterTypeChip.FILES_ONLY -> !entry.isDirectory
                        FilterTypeChip.ARCHIVES -> entry.category == PlainFileCategory.ARCHIVE
                        FilterTypeChip.IMAGES -> entry.category == PlainFileCategory.IMAGE
                        FilterTypeChip.VIDEO -> entry.category == PlainFileCategory.VIDEO
                        FilterTypeChip.AUDIO -> entry.category == PlainFileCategory.AUDIO
                        FilterTypeChip.DOCUMENTS -> entry.category == PlainFileCategory.DOCUMENT
                        FilterTypeChip.CODE_TEXT -> entry.category == PlainFileCategory.TEXT_CODE
                        FilterTypeChip.PACKAGES -> entry.category == PlainFileCategory.PACKAGE
                    }
                }
                if (!matchesAnyType) return@filter false
            }
            if (filterConfig.sizePreset != SizeFilterPreset.ANY) {
                if (entry.isDirectory) return@filter false
                val minB = filterConfig.sizePreset.minBytes
                val maxB = filterConfig.sizePreset.maxBytes
                if (entry.size < minB) return@filter false
                if (maxB != null && entry.size > maxB) return@filter false
            }
            if (filterConfig.datePreset != DateFilterPreset.ANY) {
                val maxAge = filterConfig.datePreset.maxAgeMillis
                if (maxAge != null && (now - entry.lastModified) > maxAge) {
                    return@filter false
                }
            }
            true
        }
    }

    private fun updatePaneState(paneId: PaneId, transform: (PaneState) -> PaneState) {
        _uiState.update { state ->
            if (paneId == PaneId.PANE_A) {
                state.copy(paneA = transform(state.paneA))
            } else {
                state.copy(paneB = transform(state.paneB))
            }
        }
    }
}
