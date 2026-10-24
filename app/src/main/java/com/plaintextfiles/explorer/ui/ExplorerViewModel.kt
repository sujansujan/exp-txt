package com.plaintextfiles.explorer.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plaintextfiles.explorer.archive.ArchiveEngine
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.AppSettings
import com.plaintextfiles.explorer.model.ArchiveFormat
import com.plaintextfiles.explorer.model.ClipboardBuffer
import com.plaintextfiles.explorer.model.ClipboardMode
import com.plaintextfiles.explorer.model.DualPaneMode
import com.plaintextfiles.explorer.model.FileDetailedInfo
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.FontScalePreset
import com.plaintextfiles.explorer.model.PaneId
import com.plaintextfiles.explorer.model.PaneState
import com.plaintextfiles.explorer.model.RowDensity
import com.plaintextfiles.explorer.model.SortConfig
import com.plaintextfiles.explorer.model.SortField
import com.plaintextfiles.explorer.model.SortOrder
import com.plaintextfiles.explorer.model.ThemeMode
import com.plaintextfiles.explorer.model.TransferProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface PlainDialogState {
    data class ItemActionMenu(val paneId: PaneId, val entries: List<FileEntry>) : PlainDialogState
    data class SortModal(val paneId: PaneId) : PlainDialogState
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

    private val prefs = application.getSharedPreferences("exp_txt_prefs_v4", Context.MODE_PRIVATE)
    val fileRepo = FileSystemRepository(application.applicationContext)
    val archiveEngine = ArchiveEngine(fileRepo)

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

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

        val initialSort = SortConfig(
            field = SortField.NAME,
            order = SortOrder.ASCENDING,
            foldersFirst = loadedSettings.foldersFirst,
            showHidden = loadedSettings.showHidden
        )

        _uiState.update {
            it.copy(
                settings = loadedSettings,
                pinnedPaths = loadedPins,
                hasStoragePermission = fileRepo.hasAllFilesAccess(),
                paneA = it.paneA.copy(currentPath = startA, sortConfig = initialSort),
                paneB = it.paneB.copy(currentPath = startB, sortConfig = initialSort)
            )
        }

        loadPaneDirectory(PaneId.PANE_A, startA, recordHistory = false)
        loadPaneDirectory(PaneId.PANE_B, startB, recordHistory = false)
    }

    private fun loadSettingsFromPrefs(): AppSettings {
        val theme = prefs.getString("theme_mode", ThemeMode.DRACULA.name)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DRACULA
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
            rememberLastPaths = prefs.getBoolean("remember_last_paths", true),
            enableRowSwipeGesture = prefs.getBoolean("enable_row_swipe", true),
            splitRatio = prefs.getFloat("split_ratio", 0.50f).coerceIn(0.22f, 0.78f)
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
            .putBoolean("remember_last_paths", next.rememberLastPaths)
            .putBoolean("enable_row_swipe", next.enableRowSwipeGesture)
            .putFloat("split_ratio", next.splitRatio.coerceIn(0.22f, 0.78f))
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

    fun updateSplitRatio(newRatio: Float) {
        val clamped = newRatio.coerceIn(0.22f, 0.78f)
        prefs.edit().putFloat("split_ratio", clamped).apply()
        _uiState.update { it.copy(settings = it.settings.copy(splitRatio = clamped)) }
    }

    fun openSettingsScreen() {
        _uiState.update { it.copy(isSettingsOpen = true) }
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
        _uiState.update { it.copy(hasStoragePermission = fileRepo.hasAllFilesAccess()) }
        reloadPane(PaneId.PANE_A)
        reloadPane(PaneId.PANE_B)
    }

    fun setActivePane(paneId: PaneId) {
        if (_uiState.value.activePaneId != paneId) {
            _uiState.update { it.copy(activePaneId = paneId) }
        }
    }

    // Pinned Items Management (Always at Top of Main List)
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
                toastMessage = if (added) "Pinned [${entry.name}] to top of list" else "Unpinned [${entry.name}]"
            )
        }
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

            if (stateSnapshot.settings.rememberLastPaths) {
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
                        val filtered = filterBySearch(items, pane.searchQuery)
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
                        val filtered = filterBySearch(virtualItems, pane.searchQuery)
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
            loadPaneDirectory(paneId, parent.absolutePath, recordHistory = true)
        }
    }

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
            return true
        }
        if (pane.isSearchVisible || pane.searchQuery.isNotEmpty()) {
            setPaneSearchVisible(activeId, false)
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
        return false
    }

    /**
     * Single Tap Handler:
     * - If multi-select is active in this pane, toggles selection
     * - If directory, enters directory
     * - If archive on disk, opens virtual archive browsing
     * - Otherwise ALWAYS opens the file in its default Android system app!
     */
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
                // Extract virtual file to cache and open in default Android app
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

        // Open file in its default Android app
        onLaunchExternalFile(File(entry.path))
    }

    /**
     * Double Tap Handler (requested by user):
     * Shows the detailed File Metadata, Permissions & Cryptographic Hashes Modal.
     */
    fun onDoubleTapEntry(paneId: PaneId, entry: FileEntry) {
        setActivePane(paneId)
        val pane = _uiState.value.getPane(paneId)
        _uiState.update {
            it.copy(
                activeDialog = null,
                detailsPaneId = paneId,
                detailsEntry = entry,
                detailsInfo = null,
                isLoadingDetails = true
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

    /**
     * Long Press Handler (requested by user):
     * Opens natural file management operations (Copy, Cut, Paste, Pin, Compress, Extract, Rename, Delete, Multi-select).
     */
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

    // Selection, Search & Sort
    fun toggleSelection(paneId: PaneId, entry: FileEntry) {
        updatePaneState(paneId) { pane ->
            val next = pane.selectedKeys.toMutableSet()
            if (!next.add(entry.uniqueKey)) {
                next.remove(entry.uniqueKey)
            }
            pane.copy(selectedKeys = next)
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
        updatePaneState(paneId) { pane ->
            val nextQuery = if (!visible) "" else pane.searchQuery
            pane.copy(
                isSearchVisible = visible,
                searchQuery = nextQuery,
                filteredEntries = filterBySearch(pane.entries, nextQuery)
            )
        }
    }

    fun updateSearchQuery(paneId: PaneId, query: String) {
        updatePaneState(paneId) { pane ->
            pane.copy(
                searchQuery = query,
                filteredEntries = filterBySearch(pane.entries, query)
            )
        }
    }

    fun updateSortConfig(paneId: PaneId, field: SortField, order: SortOrder) {
        updatePaneState(paneId) { pane ->
            val nextCfg = pane.sortConfig.copy(field = field, order = order)
            val sorted = fileRepo.sortEntries(pane.entries, nextCfg)
            pane.copy(
                sortConfig = nextCfg,
                entries = sorted,
                filteredEntries = filterBySearch(sorted, pane.searchQuery)
            )
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
    }

    fun swapPanes() {
        val currentA = _uiState.value.paneA
        val currentB = _uiState.value.paneB
        _uiState.update {
            it.copy(
                paneA = currentB.copy(paneId = PaneId.PANE_A),
                paneB = currentA.copy(paneId = PaneId.PANE_B)
            )
        }
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
                    _uiState.update { it.copy(activeDialog = null) }
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
                    _uiState.update { it.copy(activeDialog = null) }
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
                    _uiState.update { it.copy(activeDialog = null) }
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

    fun deleteFileEntries(paneId: PaneId, entries: List<FileEntry>) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeDialog = null,
                    transferProgress = TransferProgress("Deleting...", 0, entries.size)
                )
            }
            fileRepo.deleteEntries(entries) { cur, tot, msg ->
                _uiState.update { it.copy(transferProgress = TransferProgress(msg, cur, tot)) }
            }.fold(
                onSuccess = {
                    _uiState.update { it.copy(transferProgress = null) }
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
                onSuccess = {
                    _uiState.update { it.copy(transferProgress = null) }
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
                activeDialog = null
            )
        }
        clearSelection(paneId)
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
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
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            transferProgress = null,
                            clipboard = if (isMove) null else it.clipboard
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
                onSuccess = {
                    _uiState.update { it.copy(transferProgress = null) }
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
                onSuccess = {
                    _uiState.update { it.copy(transferProgress = null) }
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

    private fun filterBySearch(entries: List<FileEntry>, query: String): List<FileEntry> {
        val clean = query.trim()
        if (clean.isEmpty()) return entries
        return entries.filter { it.name.contains(clean, ignoreCase = true) }
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
