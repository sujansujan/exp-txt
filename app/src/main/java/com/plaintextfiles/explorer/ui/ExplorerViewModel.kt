package com.plaintextfiles.explorer.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plaintextfiles.explorer.archive.ArchiveEngine
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.ArchiveFormat
import com.plaintextfiles.explorer.model.ClipboardBuffer
import com.plaintextfiles.explorer.model.ClipboardMode
import com.plaintextfiles.explorer.model.DualPaneMode
import com.plaintextfiles.explorer.model.FileDetailedInfo
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.PaneId
import com.plaintextfiles.explorer.model.PaneState
import com.plaintextfiles.explorer.model.SortConfig
import com.plaintextfiles.explorer.model.SortField
import com.plaintextfiles.explorer.model.StorageRootInfo
import com.plaintextfiles.explorer.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface PlainDialogState {
    data class CreateFolder(val paneId: PaneId) : PlainDialogState
    data class CreateTextFile(val paneId: PaneId) : PlainDialogState
    data class RenameEntry(val paneId: PaneId, val entry: FileEntry) : PlainDialogState
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
    data class TextViewerEditor(
        val paneId: PaneId,
        val entry: FileEntry,
        val content: String,
        val isReadOnly: Boolean
    ) : PlainDialogState
}

data class ExplorerUiState(
    val themeMode: ThemeMode = ThemeMode.DRACULA,
    val dualPaneMode: DualPaneMode = DualPaneMode.SPLIT_VERTICAL,
    val activePaneId: PaneId = PaneId.PANE_A,
    val paneA: PaneState = PaneState(paneId = PaneId.PANE_A, currentPath = "/storage/emulated/0"),
    val paneB: PaneState = PaneState(paneId = PaneId.PANE_B, currentPath = "/storage/emulated/0/Download"),
    val storageRoots: List<StorageRootInfo> = emptyList(),
    val hasStoragePermission: Boolean = false,
    val highlightedPaneId: PaneId? = null,
    val highlightedEntry: FileEntry? = null,
    val highlightedDetails: FileDetailedInfo? = null,
    val isLoadingDetails: Boolean = false,
    val activeDialog: PlainDialogState? = null,
    val clipboard: ClipboardBuffer? = null,
    val isBusy: Boolean = false,
    val statusLog: String = "Ready",
    val lastRefreshTime: String = "--:--:--"
) {
    fun getPane(id: PaneId): PaneState = if (id == PaneId.PANE_A) paneA else paneB
}

class ExplorerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("plaintextfiles_prefs", Context.MODE_PRIVATE)
    val fileRepo = FileSystemRepository(application.applicationContext)
    val archiveEngine = ArchiveEngine(fileRepo)

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    init {
        val savedTheme = prefs.getString("theme_mode", ThemeMode.DRACULA.name)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DRACULA

        val savedLayout = prefs.getString("dual_pane_mode", DualPaneMode.SPLIT_VERTICAL.name)
            ?.let { runCatching { DualPaneMode.valueOf(it) }.getOrNull() }
            ?: DualPaneMode.SPLIT_VERTICAL

        val startA = fileRepo.defaultStartDirectory()
        val downloadDir = File(startA, "Download").takeIf { it.exists() }?.absolutePath ?: startA

        _uiState.update {
            it.copy(
                themeMode = savedTheme,
                dualPaneMode = savedLayout,
                hasStoragePermission = fileRepo.hasAllFilesAccess(),
                paneA = it.paneA.copy(currentPath = startA),
                paneB = it.paneB.copy(currentPath = downloadDir),
                lastRefreshTime = currentClockString()
            )
        }

        refreshPermissionAndRoots()
        loadPaneDirectory(PaneId.PANE_A, startA, recordHistory = false)
        loadPaneDirectory(PaneId.PANE_B, downloadDir, recordHistory = false)
    }

    fun refreshPermissionAndRoots() {
        val granted = fileRepo.hasAllFilesAccess()
        val roots = fileRepo.discoverStorageRoots()
        _uiState.update {
            it.copy(
                hasStoragePermission = granted,
                storageRoots = roots,
                lastRefreshTime = currentClockString()
            )
        }
    }

    fun refreshAllPanes() {
        refreshPermissionAndRoots()
        reloadPane(PaneId.PANE_A)
        reloadPane(PaneId.PANE_B)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _uiState.update {
            it.copy(
                themeMode = mode,
                statusLog = "Theme set to ${mode.name} Classic"
            )
        }
    }

    fun toggleThemeMode() {
        val next = if (_uiState.value.themeMode == ThemeMode.DRACULA) {
            ThemeMode.ALUCARD
        } else {
            ThemeMode.DRACULA
        }
        setThemeMode(next)
    }

    fun setDualPaneMode(mode: DualPaneMode) {
        prefs.edit().putString("dual_pane_mode", mode.name).apply()
        _uiState.update {
            it.copy(
                dualPaneMode = mode,
                statusLog = "Layout: ${mode.description}"
            )
        }
    }

    fun cycleDualPaneMode() {
        val modes = DualPaneMode.entries
        val nextIdx = (modes.indexOf(_uiState.value.dualPaneMode) + 1) % modes.size
        setDualPaneMode(modes[nextIdx])
    }

    fun setActivePane(paneId: PaneId) {
        if (_uiState.value.activePaneId != paneId) {
            _uiState.update { it.copy(activePaneId = paneId) }
        }
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
            val currentPane = _uiState.value.getPane(paneId)
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
            val result = fileRepo.listDirectory(targetPath, currentPane.sortConfig)

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
                    _uiState.update {
                        it.copy(
                            lastRefreshTime = currentClockString(),
                            statusLog = "${paneId.shortCode}: Loaded ${items.size} items in ${targetPath.substringAfterLast('/').ifEmpty { "/" }}"
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
                            errorMessage = err.message ?: "Unable to read directory",
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

            updatePaneState(paneId) {
                it.copy(isLoading = true, errorMessage = null)
            }

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
                    _uiState.update {
                        it.copy(
                            lastRefreshTime = currentClockString(),
                            statusLog = "${paneId.shortCode}: Browsing archive ${archivePath.substringAfterLast('/')}"
                        )
                    }
                },
                onFailure = { err ->
                    val msg = err.message.orEmpty()
                    updatePaneState(paneId) { it.copy(isLoading = false) }
                    if (msg.contains("Encrypted", ignoreCase = true) ||
                        msg.contains("Password", ignoreCase = true) ||
                        msg.contains("Wrong Password", ignoreCase = true)
                    ) {
                        _uiState.update {
                            it.copy(
                                activeDialog = PlainDialogState.PasswordPrompt(
                                    paneId = paneId,
                                    archivePath = archivePath
                                ),
                                statusLog = "Password required for ${archivePath.substringAfterLast('/')}"
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
                // Exit virtual archive back to host directory
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

        val currentFile = File(pane.currentPath)
        val parent = currentFile.parentFile
        if (parent != null && parent.exists()) {
            loadPaneDirectory(paneId, parent.absolutePath, recordHistory = true)
        }
    }

    fun navigateBackOnSystemPress(): Boolean {
        if (_uiState.value.highlightedEntry != null) {
            dismissHighlightedDetails()
            return true
        }
        if (_uiState.value.activeDialog != null) {
            dismissDialog()
            return true
        }
        val activeId = _uiState.value.activePaneId
        val pane = _uiState.value.getPane(activeId)
        if (pane.selectedKeys.isNotEmpty()) {
            clearSelection(activeId)
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
     * Single tap handler:
     * - If pane has active multi-selection, toggles item selection
     * - If directory (real or virtual), navigates inside
     * - If archive on disk, opens virtual archive browser
     * - If text/code file, opens built-in Plain-Text Viewer/Editor
     * - Otherwise returns false so Activity can launch Android FileProvider intent
     */
    fun onSingleTapEntry(
        paneId: PaneId,
        entry: FileEntry,
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
                // Preview virtual file inside archive
                viewModelScope.launch {
                    val text = archiveEngine.readVirtualEntryPreview(
                        archivePath = hostArc,
                        internalPath = internalPath,
                        password = pane.activeArchivePassword
                    ) ?: "(Binary file inside archive. Long-press or use [EXTRACT] to unpack.)"
                    _uiState.update {
                        it.copy(
                            activeDialog = PlainDialogState.TextViewerEditor(
                                paneId = paneId,
                                entry = entry,
                                content = text,
                                isReadOnly = true
                            )
                        )
                    }
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

        if (FileSystemRepository.isTextViewableExtension(entry.extension, entry.name)) {
            openTextViewerEditor(paneId, entry)
            return
        }

        onLaunchExternalFile(File(entry.path))
    }

    /**
     * Long press handler (requested by user):
     * Highlights the item and opens the comprehensive ASCII File Details & Options modal.
     */
    fun onLongPressEntry(paneId: PaneId, entry: FileEntry) {
        setActivePane(paneId)
        val pane = _uiState.value.getPane(paneId)
        _uiState.update {
            it.copy(
                highlightedPaneId = paneId,
                highlightedEntry = entry,
                highlightedDetails = null,
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
                if (it.highlightedEntry?.uniqueKey == entry.uniqueKey) {
                    it.copy(
                        highlightedDetails = detailed,
                        isLoadingDetails = false
                    )
                } else {
                    it
                }
            }
        }
    }

    fun dismissHighlightedDetails() {
        _uiState.update {
            it.copy(
                highlightedPaneId = null,
                highlightedEntry = null,
                highlightedDetails = null,
                isLoadingDetails = false
            )
        }
    }

    fun openTextViewerEditor(paneId: PaneId, entry: FileEntry) {
        viewModelScope.launch {
            if (entry.isVirtualArchiveEntry && entry.archiveHostPath != null && entry.archiveInternalPath != null) {
                val content = archiveEngine.readVirtualEntryPreview(
                    archivePath = entry.archiveHostPath,
                    internalPath = entry.archiveInternalPath,
                    password = _uiState.value.getPane(paneId).activeArchivePassword
                ) ?: ""
                _uiState.update {
                    it.copy(
                        activeDialog = PlainDialogState.TextViewerEditor(
                            paneId = paneId,
                            entry = entry,
                            content = content,
                            isReadOnly = true
                        )
                    )
                }
            } else {
                val res = fileRepo.readFullTextFile(entry.path)
                res.onSuccess { text ->
                    val writable = File(entry.path).canWrite()
                    _uiState.update {
                        it.copy(
                            activeDialog = PlainDialogState.TextViewerEditor(
                                paneId = paneId,
                                entry = entry,
                                content = text,
                                isReadOnly = !writable
                            )
                        )
                    }
                }.onFailure { err ->
                    _uiState.update { it.copy(statusLog = "Read failed: ${err.message}") }
                }
            }
        }
    }

    fun saveEditedTextFile(paneId: PaneId, entry: FileEntry, newContent: String) {
        viewModelScope.launch {
            fileRepo.saveTextFile(entry.path, newContent).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            statusLog = "Saved ${entry.name} (${newContent.toByteArray().size} B)"
                        )
                    }
                    reloadPane(paneId)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusLog = "Save Error: ${err.message}") }
                }
            )
        }
    }

    // Selection & Sorting
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

    fun updateSearchQuery(paneId: PaneId, query: String) {
        updatePaneState(paneId) { pane ->
            pane.copy(
                searchQuery = query,
                filteredEntries = filterBySearch(pane.entries, query)
            )
        }
    }

    fun updateSortField(paneId: PaneId, field: SortField) {
        val pane = _uiState.value.getPane(paneId)
        val currentCfg = pane.sortConfig
        val nextCfg = if (currentCfg.field == field) {
            currentCfg.copy(order = currentCfg.order.toggle())
        } else {
            currentCfg.copy(field = field)
        }
        applySortConfig(paneId, nextCfg)
    }

    fun toggleFoldersFirst(paneId: PaneId) {
        val cfg = _uiState.value.getPane(paneId).sortConfig
        applySortConfig(paneId, cfg.copy(foldersFirst = !cfg.foldersFirst))
    }

    fun toggleShowHidden(paneId: PaneId) {
        val cfg = _uiState.value.getPane(paneId).sortConfig
        val next = cfg.copy(showHidden = !cfg.showHidden)
        updatePaneState(paneId) { it.copy(sortConfig = next) }
        reloadPane(paneId)
    }

    private fun applySortConfig(paneId: PaneId, newConfig: SortConfig) {
        updatePaneState(paneId) { pane ->
            val sorted = fileRepo.sortEntries(pane.entries, newConfig)
            pane.copy(
                sortConfig = newConfig,
                entries = sorted,
                filteredEntries = filterBySearch(sorted, pane.searchQuery)
            )
        }
    }

    // Dual-Pane Sync & Swap Operations
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
        _uiState.update { it.copy(statusLog = "Synced ${targetPaneId.shortCode} to ${sourcePane.displayPathHeader}") }
    }

    fun swapPanes() {
        val currentA = _uiState.value.paneA
        val currentB = _uiState.value.paneB
        _uiState.update {
            it.copy(
                paneA = currentB.copy(paneId = PaneId.PANE_A),
                paneB = currentA.copy(paneId = PaneId.PANE_B),
                statusLog = "Swapped Pane 1 <-> Pane 2"
            )
        }
    }

    // File & Folder Mutations
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
                onSuccess = { created ->
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            statusLog = "Created folder [${created.name}]"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusLog = "Error: ${err.message}") }
                }
            )
        }
    }

    fun createNewPlainTextFile(paneId: PaneId, fileName: String, content: String) {
        val path = _uiState.value.getPane(paneId).currentPath
        viewModelScope.launch {
            fileRepo.createPlainTextFile(path, fileName, content).fold(
                onSuccess = { created ->
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            statusLog = "Created file [${created.name}]"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusLog = "Error: ${err.message}") }
                }
            )
        }
    }

    fun renameFileEntry(paneId: PaneId, entry: FileEntry, newName: String) {
        viewModelScope.launch {
            fileRepo.renameEntry(entry, newName).fold(
                onSuccess = { renamed ->
                    _uiState.update {
                        it.copy(
                            activeDialog = null,
                            highlightedEntry = null,
                            highlightedDetails = null,
                            statusLog = "Renamed [${entry.name}] -> [${renamed.name}]"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(statusLog = "Rename Error: ${err.message}") }
                }
            )
        }
    }

    fun deleteFileEntries(paneId: PaneId, entries: List<FileEntry>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, activeDialog = null) }
            fileRepo.deleteEntries(entries).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            highlightedEntry = null,
                            highlightedDetails = null,
                            statusLog = "Deleted $count item(s)"
                        )
                    }
                    clearSelection(paneId)
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, statusLog = "Delete Error: ${err.message}")
                    }
                }
            )
        }
    }

    fun copyOrMoveToOppositePane(sourcePaneId: PaneId, entries: List<FileEntry>, move: Boolean) {
        val targetPaneId = sourcePaneId.opposite()
        val targetPane = _uiState.value.getPane(targetPaneId)

        if (entries.any { it.isVirtualArchiveEntry }) {
            // Virtual archive entries -> extract directly to opposite pane!
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
            val opLabel = if (move) "Moved" else "Copied"
            _uiState.update {
                it.copy(
                    isBusy = true,
                    highlightedEntry = null,
                    highlightedDetails = null,
                    statusLog = "${if (move) "Moving" else "Copying"} ${entries.size} item(s) -> ${targetPaneId.shortCode}..."
                )
            }
            fileRepo.copyOrMoveEntries(entries, targetPane.currentPath, move).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusLog = "$opLabel $count item(s) to ${targetPane.currentPath}"
                        )
                    }
                    clearSelection(sourcePaneId)
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, statusLog = "Transfer Error: ${err.message}")
                    }
                }
            )
        }
    }

    fun stageToClipboard(paneId: PaneId, entries: List<FileEntry>, mode: ClipboardMode) {
        _uiState.update {
            it.copy(
                clipboard = ClipboardBuffer(entries = entries, sourcePane = paneId, mode = mode),
                highlightedEntry = null,
                highlightedDetails = null,
                statusLog = "Clipboard: ${entries.size} item(s) staged for ${mode.name}"
            )
        }
    }

    fun pasteClipboardIntoPane(targetPaneId: PaneId) {
        val clip = _uiState.value.clipboard ?: return
        val destPath = _uiState.value.getPane(targetPaneId).currentPath
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusLog = "Pasting ${clip.entries.size} item(s)...") }
            val isMove = clip.mode == ClipboardMode.MOVE
            fileRepo.copyOrMoveEntries(clip.entries, destPath, isMove).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            clipboard = if (isMove) null else it.clipboard,
                            statusLog = "Pasted $count item(s) into ${targetPaneId.shortCode}"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isBusy = false, statusLog = "Paste Error: ${err.message}") }
                }
            )
        }
    }

    // Archive Operations
    fun openCompressDialog(paneId: PaneId, entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val pane = _uiState.value.getPane(paneId)
        val oppositePane = _uiState.value.getPane(paneId.opposite())
        val defaultName = if (entries.size == 1) {
            ArchiveEngine.stripArchiveExtensions(entries.first().name)
        } else {
            pane.currentPath.substringAfterLast('/').ifEmpty { "archive" }
        }
        _uiState.update {
            it.copy(
                highlightedEntry = null,
                highlightedDetails = null,
                activeDialog = PlainDialogState.CompressArchive(
                    paneId = paneId,
                    entries = entries,
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
                highlightedEntry = null,
                highlightedDetails = null,
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
                    isBusy = true,
                    activeDialog = null,
                    statusLog = "Compressing ${entries.size} item(s) -> $archiveBaseName.${format.primaryExtension}..."
                )
            }
            archiveEngine.createArchive(
                sourceEntries = entries,
                destinationDirPath = destinationDirPath,
                archiveBaseName = archiveBaseName,
                format = format,
                password = password?.takeIf { it.isNotBlank() }
            ).fold(
                onSuccess = { outFile ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusLog = "Created archive ${outFile.name} (${FileSystemRepository.formatBytes(outFile.length())})"
                        )
                    }
                    clearSelection(paneId)
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, statusLog = "Compression Error: ${err.message}")
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
                    isBusy = true,
                    activeDialog = null,
                    highlightedEntry = null,
                    highlightedDetails = null,
                    statusLog = "Extracting ${archivePath.substringAfterLast('/')}..."
                )
            }
            archiveEngine.extractArchive(
                archivePath = archivePath,
                destinationDirPath = destinationDirPath,
                createSubfolder = createSubfolder,
                password = password?.takeIf { it.isNotBlank() },
                selectedVirtualPaths = selectedVirtualPaths
            ).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusLog = "Extracted $count file(s) to $destinationDirPath"
                        )
                    }
                    reloadPane(PaneId.PANE_A)
                    reloadPane(PaneId.PANE_B)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, statusLog = "Extraction Error: ${err.message}")
                    }
                }
            )
        }
    }

    fun executeTestArchiveIntegrity(archivePath: String, password: String? = null) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, statusLog = "Testing archive integrity: ${archivePath.substringAfterLast('/')}...")
            }
            archiveEngine.testArchiveIntegrity(archivePath, password).fold(
                onSuccess = { report ->
                    _uiState.update { it.copy(isBusy = false, statusLog = report) }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isBusy = false, statusLog = "Archive Test Failed: ${err.message}") }
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
                onLongPressEntry(paneId, fileRepo.fileToFileEntry(File(entry.path)))
                _uiState.update { it.copy(statusLog = "Updated permissions for ${entry.name}") }
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

    private fun currentClockString(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }
}
