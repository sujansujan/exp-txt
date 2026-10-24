package com.plaintextfiles.explorer.model

enum class ThemeMode(val displayName: String) {
    SYSTEM("Follow System"),
    DRACULA("Dracula (Dark)"),
    ALUCARD("Alucard (Light)")
}

enum class DualPaneMode(val shortLabel: String, val description: String) {
    SPLIT_VERTICAL("SPLIT-V", "Top / Bottom Split Pane"),
    SPLIT_HORIZONTAL("SPLIT-H", "Left / Right Split Pane"),
    SWIPE_PAGER("PAGER", "Swipeable Pager Tabs"),
    SINGLE_PANE("SINGLE", "Single Pane View")
}

enum class RowDensity(val label: String, val verticalPaddingDp: Int) {
    COMPACT("Compact", 2),
    COZY("Cozy", 4),
    COMFORTABLE("Comfortable", 7)
}

enum class FontScalePreset(val label: String, val scaleFactor: Float) {
    SMALL("Small", 0.90f),
    NORMAL("Normal", 1.00f),
    LARGE("Large", 1.12f)
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dualPaneMode: DualPaneMode = DualPaneMode.SPLIT_VERTICAL,
    val rowDensity: RowDensity = RowDensity.COMPACT,
    val fontScale: FontScalePreset = FontScalePreset.NORMAL,
    val showFileIcons: Boolean = true,
    val showPermissionSubtitle: Boolean = false,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = false,
    val globalPinnedAtTop: Boolean = true,
    val globalSortAndFilter: Boolean = true,
    val rememberLastPaths: Boolean = true,
    val enableRowSwipeGesture: Boolean = true,
    val showFastScrollRail: Boolean = true,
    val useTrashBin: Boolean = true,
    val showDiffHighlights: Boolean = false
)

enum class PaneId(val index: Int, val label: String, val shortCode: String) {
    PANE_A(0, "PANE 1", "P1"),
    PANE_B(1, "PANE 2", "P2");

    fun opposite(): PaneId = if (this == PANE_A) PANE_B else PANE_A
}

enum class SortField(val label: String, val shortTag: String) {
    NAME("Name", "NAME"),
    SIZE("Size", "SIZE"),
    DATE("Modified Date", "DATE"),
    TYPE("File Type / Ext", "TYPE"),
    PERMISSIONS("Permissions", "PERM")
}

enum class SortOrder(val symbol: String, val label: String) {
    ASCENDING("^", "Ascending"),
    DESCENDING("v", "Descending");

    fun toggle(): SortOrder = if (this == ASCENDING) DESCENDING else ASCENDING
}

data class SortConfig(
    val field: SortField = SortField.NAME,
    val order: SortOrder = SortOrder.ASCENDING,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = false
)

enum class FilterTypeChip(val label: String, val shortTag: String) {
    FOLDERS("Folders", "DIR"),
    FILES_ONLY("Files Only", "FIL"),
    ARCHIVES("Archives", "ARC"),
    IMAGES("Images", "IMG"),
    VIDEO("Video", "VID"),
    AUDIO("Audio", "AUD"),
    DOCUMENTS("Docs/PDF", "DOC"),
    CODE_TEXT("Text/Code", "TXT"),
    PACKAGES("APK", "APK")
}

enum class SizeFilterPreset(val label: String, val minBytes: Long, val maxBytes: Long?) {
    ANY("Any Size", 0L, null),
    UNDER_1MB("< 1 MB", 0L, 1024L * 1024L),
    OVER_1MB("> 1 MB", 1024L * 1024L, null),
    OVER_10MB("> 10 MB", 10L * 1024L * 1024L, null),
    OVER_100MB("> 100 MB", 100L * 1024L * 1024L, null)
}

enum class DateFilterPreset(val label: String, val maxAgeMillis: Long?) {
    ANY("Any Time", null),
    LAST_24H("24 Hours", 24L * 3600L * 1000L),
    LAST_7D("7 Days", 7L * 24L * 3600L * 1000L),
    LAST_30D("30 Days", 30L * 24L * 3600L * 1000L)
}

data class FilterConfig(
    val selectedTypes: Set<FilterTypeChip> = emptySet(),
    val sizePreset: SizeFilterPreset = SizeFilterPreset.ANY,
    val datePreset: DateFilterPreset = DateFilterPreset.ANY,
    val extensionOrKeyword: String = "",
    val onlyPinned: Boolean = false
) {
    val activeCount: Int
        get() = selectedTypes.size +
            (if (sizePreset != SizeFilterPreset.ANY) 1 else 0) +
            (if (datePreset != DateFilterPreset.ANY) 1 else 0) +
            (if (extensionOrKeyword.isNotBlank()) 1 else 0) +
            (if (onlyPinned) 1 else 0)

    val isActive: Boolean get() = activeCount > 0

    fun toSummaryString(): String {
        val parts = mutableListOf<String>()
        if (onlyPinned) parts.add("★PIN")
        selectedTypes.forEach { parts.add(it.shortTag) }
        if (sizePreset != SizeFilterPreset.ANY) parts.add(sizePreset.label.replace(" ", ""))
        if (datePreset != DateFilterPreset.ANY) parts.add(datePreset.label.replace(" ", ""))
        if (extensionOrKeyword.isNotBlank()) parts.add("'${extensionOrKeyword.trim()}'")
        return parts.joinToString("+")
    }
}

enum class ArchiveFormat(
    val displayName: String,
    val primaryExtension: String,
    val allExtensions: List<String>,
    val supportsCreation: Boolean,
    val supportsPassword: Boolean
) {
    ZIP("ZIP", "zip", listOf("zip", "jar", "cbz"), supportsCreation = true, supportsPassword = true),
    TAR("TAR", "tar", listOf("tar"), supportsCreation = true, supportsPassword = false),
    TAR_GZ("TAR.GZ", "tar.gz", listOf("tar.gz", "tgz"), supportsCreation = true, supportsPassword = false),
    TAR_BZ2("TAR.BZ2", "tar.bz2", listOf("tar.bz2", "tbz2", "tbz"), supportsCreation = true, supportsPassword = false),
    TAR_XZ("TAR.XZ", "tar.xz", listOf("tar.xz", "txz"), supportsCreation = true, supportsPassword = false),
    SEVEN_Z("7-ZIP", "7z", listOf("7z"), supportsCreation = true, supportsPassword = false),
    GZIP("GZIP", "gz", listOf("gz"), supportsCreation = true, supportsPassword = false),
    BZIP2("BZIP2", "bz2", listOf("bz2"), supportsCreation = true, supportsPassword = false),
    XZ("XZ", "xz", listOf("xz"), supportsCreation = true, supportsPassword = false);

    companion object {
        fun fromFileName(name: String): ArchiveFormat? {
            val lower = name.lowercase()
            return entries.firstOrNull { fmt ->
                fmt.allExtensions.any { ext -> lower.endsWith(".$ext") }
            }
        }

        fun isArchiveFileName(name: String): Boolean = fromFileName(name) != null
    }
}

enum class PlainFileCategory(val asciiBadge: String, val shortCode: String) {
    PARENT_DIR("[..]", ".."),
    DIRECTORY("[DIR]", "DIR"),
    SYMLINK("[LNK]", "LNK"),
    ARCHIVE("[ARC]", "ARC"),
    TEXT_CODE("[TXT]", "TXT"),
    AUDIO("[AUD]", "AUD"),
    VIDEO("[VID]", "VID"),
    IMAGE("[IMG]", "IMG"),
    PACKAGE("[APK]", "APK"),
    DOCUMENT("[DOC]", "DOC"),
    EXECUTABLE("[EXE]", "EXE"),
    BINARY("[FIL]", "FIL")
}

enum class DiffStatus(val badge: String) {
    NONE(""),
    ONLY_IN_P1("[+P1]"),
    ONLY_IN_P2("[+P2]"),
    NEWER_HERE("[~NEW]"),
    OLDER_HERE("[~OLD]"),
    SIZE_DIFF("[~SIZ]"),
    IDENTICAL("[=]")
}

data class PaneComparisonSummary(
    val paneAPath: String,
    val paneBPath: String,
    val onlyInA: List<FileEntry>,
    val onlyInB: List<FileEntry>,
    val newerInA: List<FileEntry>,
    val newerInB: List<FileEntry>,
    val identicalCount: Int
) {
    val syncAToBEntries: List<FileEntry> get() = onlyInA + newerInA
    val syncBToAEntries: List<FileEntry> get() = onlyInB + newerInB
}

data class TrashRecord(
    val trashedFilePath: String,
    val originalFilePath: String,
    val originalName: String,
    val deletedAtMillis: Long
)

data class TrashUndoBatch(
    val records: List<TrashRecord>,
    val sourcePaneId: PaneId,
    val summaryLabel: String
)

data class SharedIncomingItem(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val size: Long = 0L,
    val compressedSize: Long? = null,
    val lastModified: Long = 0L,
    val permissions: String = "---------",
    val octalPermissions: String = "0000",
    val extension: String = "",
    val isHidden: Boolean = false,
    val isArchive: Boolean = false,
    val archiveFormat: ArchiveFormat? = null,
    val isVirtualArchiveEntry: Boolean = false,
    val archiveHostPath: String? = null,
    val archiveInternalPath: String? = null,
    val childCount: Int? = null,
    val crc32: Long? = null,
    val category: PlainFileCategory = PlainFileCategory.BINARY,
    val isPinned: Boolean = false,
    val isPinnedShortcutFromOtherDir: Boolean = false
) {
    val uniqueKey: String
        get() = if (isVirtualArchiveEntry) {
            "${archiveHostPath}::${archiveInternalPath}"
        } else if (isPinnedShortcutFromOtherDir) {
            "PINNED::$path"
        } else {
            path
        }
}

data class ArchiveSummaryInfo(
    val formatName: String,
    val totalEntries: Int,
    val totalFiles: Int,
    val totalDirectories: Int,
    val totalUncompressedBytes: Long,
    val totalCompressedBytes: Long,
    val compressionRatioPercent: Int,
    val isEncrypted: Boolean,
    val comment: String? = null
)

data class FileDetailedInfo(
    val entry: FileEntry,
    val mimeType: String,
    val readablePermissions: String,
    val octalPermissions: String,
    val ownerReadableWritableExecutable: String,
    val exactBytes: Long,
    val formattedSize: String,
    val recursiveFileCount: Int?,
    val recursiveFolderCount: Int?,
    val symlinkTarget: String?,
    val md5: String?,
    val sha1: String?,
    val sha256: String?,
    val crc32Hex: String?,
    val archiveSummary: ArchiveSummaryInfo?
)

enum class ClipboardMode {
    COPY,
    MOVE
}

data class ClipboardBuffer(
    val entries: List<FileEntry>,
    val sourcePane: PaneId,
    val mode: ClipboardMode
)

data class TransferProgress(
    val label: String,
    val current: Int,
    val total: Int
) {
    val fraction: Float
        get() = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
}

data class PaneState(
    val paneId: PaneId,
    val currentPath: String,
    val activeArchivePath: String? = null,
    val activeArchiveInternalDir: String = "",
    val activeArchivePassword: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val filteredEntries: List<FileEntry> = emptyList(),
    val selectedKeys: Set<String> = emptySet(),
    val sortConfig: SortConfig = SortConfig(),
    val filterConfig: FilterConfig = FilterConfig(),
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
    val isPathEditorVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val historyBackStack: List<Pair<String, Pair<String?, String>>> = emptyList(),
    val totalFreeBytes: Long = 0L,
    val totalCapacityBytes: Long = 0L
) {
    val isInsideArchive: Boolean get() = activeArchivePath != null

    val compactTitle: String
        get() = if (activeArchivePath != null) {
            val arcName = activeArchivePath.substringAfterLast('/')
            val inner = if (activeArchiveInternalDir.isBlank()) "/" else "/$activeArchiveInternalDir"
            "[ARC] $arcName::$inner"
        } else {
            currentPath
        }
}
