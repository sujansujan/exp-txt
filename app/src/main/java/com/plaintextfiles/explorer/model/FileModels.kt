package com.plaintextfiles.explorer.model

enum class ThemeMode(val displayName: String) {
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
    val themeMode: ThemeMode = ThemeMode.DRACULA,
    val dualPaneMode: DualPaneMode = DualPaneMode.SPLIT_VERTICAL,
    val rowDensity: RowDensity = RowDensity.COMPACT,
    val fontScale: FontScalePreset = FontScalePreset.NORMAL,
    val showFileIcons: Boolean = true,
    val showPermissionSubtitle: Boolean = false,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = false,
    val globalPinnedAtTop: Boolean = true,
    val rememberLastPaths: Boolean = true,
    val enableRowSwipeGesture: Boolean = true,
    val splitRatio: Float = 0.50f
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
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
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
