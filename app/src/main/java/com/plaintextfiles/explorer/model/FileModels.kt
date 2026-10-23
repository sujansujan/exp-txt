package com.plaintextfiles.explorer.model

enum class ThemeMode {
    DRACULA,
    ALUCARD
}

/**
 * Supports both requested dual-pane designs plus single-pane focus mode:
 * - SPLIT_VERTICAL: Top & Bottom simultaneous dual panes (ideal for portrait phones)
 * - SPLIT_HORIZONTAL: Left & Right simultaneous dual panes (ideal for landscape/foldables)
 * - SWIPE_PAGER: Horizontal swipeable pager with plain-text scoreboard tabs (`< PANE 1 | PANE 2 >`)
 * - SINGLE_PANE: Full-screen active pane
 */
enum class DualPaneMode(val shortLabel: String, val description: String) {
    SPLIT_VERTICAL("SPLIT-V", "Top / Bottom Dual Pane"),
    SPLIT_HORIZONTAL("SPLIT-H", "Left / Right Dual Pane"),
    SWIPE_PAGER("PAGER", "Swipeable Tab Dual Pane"),
    SINGLE_PANE("SINGLE", "Single Pane View")
}

enum class PaneId(val index: Int, val label: String, val shortCode: String) {
    PANE_A(0, "PANE 1", "P1"),
    PANE_B(1, "PANE 2", "P2");

    fun opposite(): PaneId = if (this == PANE_A) PANE_B else PANE_A
}

enum class SortField(val label: String, val shortTag: String) {
    NAME("Name", "NAME"),
    SIZE("Size", "SIZE"),
    DATE("Modified", "DATE"),
    TYPE("Extension", "TYPE"),
    PERMISSIONS("Permissions", "PERM")
}

enum class SortOrder(val symbol: String, val label: String) {
    ASCENDING("^", "ASC"),
    DESCENDING("v", "DESC");

    fun toggle(): SortOrder = if (this == ASCENDING) DESCENDING else ASCENDING
}

data class SortConfig(
    val field: SortField = SortField.NAME,
    val order: SortOrder = SortOrder.ASCENDING,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = true
)

enum class ArchiveFormat(
    val displayName: String,
    val primaryExtension: String,
    val allExtensions: List<String>,
    val supportsCreation: Boolean,
    val supportsPassword: Boolean
) {
    ZIP("ZIP", "zip", listOf("zip", "jar", "apk", "cbz"), supportsCreation = true, supportsPassword = true),
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

enum class PlainFileCategory(val asciiBadge: String) {
    PARENT_DIR("[..]"),
    DIRECTORY("[DIR]"),
    SYMLINK("[LNK]"),
    ARCHIVE("[ARC]"),
    TEXT_CODE("[TXT]"),
    AUDIO("[AUD]"),
    VIDEO("[VID]"),
    IMAGE("[IMG]"),
    PACKAGE("[APK]"),
    DOCUMENT("[DOC]"),
    EXECUTABLE("[EXE]"),
    BINARY("[FIL]")
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
    val category: PlainFileCategory = PlainFileCategory.BINARY
) {
    val uniqueKey: String
        get() = if (isVirtualArchiveEntry) "${archiveHostPath}::${archiveInternalPath}" else path
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
    val archiveSummary: ArchiveSummaryInfo?,
    val textPreview: String?
)

data class StorageRootInfo(
    val label: String,
    val shortTag: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

enum class ClipboardMode {
    COPY,
    MOVE
}

data class ClipboardBuffer(
    val entries: List<FileEntry>,
    val sourcePane: PaneId,
    val mode: ClipboardMode
)

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
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val historyBackStack: List<Pair<String, Pair<String?, String>>> = emptyList(),
    val totalFreeBytes: Long = 0L,
    val totalCapacityBytes: Long = 0L
) {
    val isInsideArchive: Boolean get() = activeArchivePath != null

    val displayPathHeader: String
        get() = if (activeArchivePath != null) {
            val arcName = activeArchivePath.substringAfterLast('/')
            val inner = if (activeArchiveInternalDir.isBlank()) "/" else "/$activeArchiveInternalDir"
            "[ARC] $arcName :: $inner"
        } else {
            currentPath
        }
}
