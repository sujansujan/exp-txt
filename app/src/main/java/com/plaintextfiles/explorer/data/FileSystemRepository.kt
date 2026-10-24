package com.plaintextfiles.explorer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.plaintextfiles.explorer.archive.ArchiveEngine
import com.plaintextfiles.explorer.model.ArchiveFormat
import com.plaintextfiles.explorer.model.DiffStatus
import com.plaintextfiles.explorer.model.FileDetailedInfo
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.PaneComparisonSummary
import com.plaintextfiles.explorer.model.PlainFileCategory
import com.plaintextfiles.explorer.model.SharedIncomingItem
import com.plaintextfiles.explorer.model.SortConfig
import com.plaintextfiles.explorer.model.SortField
import com.plaintextfiles.explorer.model.SortOrder
import com.plaintextfiles.explorer.model.TrashRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

class FileSystemRepository(private val context: Context) {

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun internalStorageRoot(): String {
        val primary = Environment.getExternalStorageDirectory()
        return if (primary.exists()) {
            primary.absolutePath
        } else {
            context.filesDir.absolutePath
        }
    }

    fun trashDirectoryPath(): String {
        val dir = File(internalStorageRoot(), TRASH_FOLDER_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.absolutePath
    }

    fun isTrashPath(path: String): Boolean {
        return path.endsWith("/$TRASH_FOLDER_NAME") || path.contains("/$TRASH_FOLDER_NAME/")
    }

    fun getTrashItemCount(): Int {
        val dir = File(internalStorageRoot(), TRASH_FOLDER_NAME)
        if (!dir.exists() || !dir.isDirectory) return 0
        return dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
    }

    fun listSubdirectorySuggestions(rawInput: String): List<String> {
        return runCatching {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return emptyList()
            val asFile = File(trimmed)
            val parentDir: File
            val prefix: String
            if (trimmed.endsWith("/") && asFile.isDirectory) {
                parentDir = asFile
                prefix = ""
            } else {
                parentDir = asFile.parentFile ?: return emptyList()
                prefix = asFile.name
            }
            if (!parentDir.exists() || !parentDir.isDirectory) return emptyList()
            parentDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name.startsWith(prefix, ignoreCase = true) }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?.take(8)
                ?.map { it.absolutePath }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun queryStatFs(dir: File): Pair<Long, Long> {
        return runCatching {
            val target = if (dir.exists()) dir else Environment.getExternalStorageDirectory()
            val stat = StatFs(target.absolutePath)
            Pair(stat.totalBytes, stat.availableBytes)
        }.getOrDefault(Pair(0L, 0L))
    }

    suspend fun listDirectory(
        dirPath: String,
        sortConfig: SortConfig,
        pinnedPaths: Set<String>,
        globalPinnedAtTop: Boolean
    ): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(dirPath)
            if (!dir.exists()) {
                throw IllegalArgumentException("Folder does not exist: $dirPath")
            }
            if (!dir.isDirectory) {
                throw IllegalArgumentException("Not a folder: $dirPath")
            }

            val rawChildren = dir.listFiles()
                ?: throw IllegalStateException("ACCESS DENIED: Grant storage permission in system settings.")

            val localEntries = rawChildren.mapNotNull { file ->
                if (!sortConfig.showHidden && file.name.startsWith(".")) {
                    null
                } else {
                    fileToFileEntry(
                        file = file,
                        isPinned = file.absolutePath in pinnedPaths,
                        isPinnedShortcutFromOtherDir = false
                    )
                }
            }

            val localPathsSet = localEntries.map { it.path }.toSet()

            val externalPinnedEntries = if (globalPinnedAtTop && !isTrashPath(dirPath)) {
                pinnedPaths.mapNotNull { pinnedPath ->
                    if (pinnedPath in localPathsSet || pinnedPath == dir.absolutePath) {
                        null
                    } else {
                        val pf = File(pinnedPath)
                        if (pf.exists()) {
                            fileToFileEntry(
                                file = pf,
                                isPinned = true,
                                isPinnedShortcutFromOtherDir = true
                            )
                        } else {
                            null
                        }
                    }
                }
            } else {
                emptyList()
            }

            sortEntries(externalPinnedEntries + localEntries, sortConfig)
        }
    }

    fun fileToFileEntry(
        file: File,
        isPinned: Boolean = false,
        isPinnedShortcutFromOtherDir: Boolean = false
    ): FileEntry {
        val isSymlink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        val isDir = file.isDirectory
        val name = file.name.ifEmpty { "/" }
        val ext = if (isDir) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val archiveFmt = if (!isDir) ArchiveFormat.fromFileName(name) else null
        val isArc = archiveFmt != null
        val (permStr, octalStr) = buildPermissionStrings(file, isDir, isSymlink)
        val childCount = if (isDir) {
            runCatching { file.list()?.size }.getOrNull()
        } else {
            null
        }

        return FileEntry(
            name = name,
            path = file.absolutePath,
            isDirectory = isDir,
            isSymlink = isSymlink,
            size = if (isDir) 0L else file.length(),
            compressedSize = null,
            lastModified = file.lastModified(),
            permissions = permStr,
            octalPermissions = octalStr,
            extension = ext,
            isHidden = name.startsWith("."),
            isArchive = isArc,
            archiveFormat = archiveFmt,
            isVirtualArchiveEntry = false,
            archiveHostPath = null,
            archiveInternalPath = null,
            childCount = childCount,
            crc32 = null,
            category = classifyFileCategory(name, ext, isDir, isSymlink, isArc, file.canExecute()),
            isPinned = isPinned,
            isPinnedShortcutFromOtherDir = isPinnedShortcutFromOtherDir
        )
    }

    fun sortEntries(entries: List<FileEntry>, config: SortConfig): List<FileEntry> {
        val comparator = Comparator<FileEntry> { a, b ->
            if (a.isPinned != b.isPinned) {
                return@Comparator if (a.isPinned) -1 else 1
            }
            if (a.isPinned && b.isPinned && a.isPinnedShortcutFromOtherDir != b.isPinnedShortcutFromOtherDir) {
                return@Comparator if (!a.isPinnedShortcutFromOtherDir) -1 else 1
            }
            if (config.foldersFirst && a.isDirectory != b.isDirectory) {
                return@Comparator if (a.isDirectory) -1 else 1
            }

            val rawCompare = when (config.field) {
                SortField.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortField.SIZE -> {
                    val sizeA = if (a.isDirectory) (a.childCount?.toLong() ?: 0L) else a.size
                    val sizeB = if (b.isDirectory) (b.childCount?.toLong() ?: 0L) else b.size
                    sizeA.compareTo(sizeB).takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
                }
                SortField.DATE -> a.lastModified.compareTo(b.lastModified)
                    .takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
                SortField.TYPE -> a.extension.compareTo(b.extension, ignoreCase = true)
                    .takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
                SortField.PERMISSIONS -> a.permissions.compareTo(b.permissions)
                    .takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
            }

            if (config.order == SortOrder.ASCENDING) rawCompare else -rawCompare
        }
        return entries.sortedWith(comparator)
    }

    /**
     * Compares Pane 1 and Pane 2 entries by filename, returning both a summary for the
     * Compare & Sync Modal and a map of DiffStatus badges keyed by entry.uniqueKey.
     */
    fun comparePaneEntries(
        entriesA: List<FileEntry>,
        entriesB: List<FileEntry>,
        pathA: String,
        pathB: String
    ): Pair<PaneComparisonSummary, Map<String, DiffStatus>> {
        val localA = entriesA.filter { !it.isPinnedShortcutFromOtherDir }
        val localB = entriesB.filter { !it.isPinnedShortcutFromOtherDir }

        val mapA = localA.associateBy { it.name }
        val mapB = localB.associateBy { it.name }

        val onlyInA = mutableListOf<FileEntry>()
        val onlyInB = mutableListOf<FileEntry>()
        val newerInA = mutableListOf<FileEntry>()
        val newerInB = mutableListOf<FileEntry>()
        var identicalCount = 0
        val badgeMap = mutableMapOf<String, DiffStatus>()

        for (itemA in localA) {
            val matchB = mapB[itemA.name]
            if (matchB == null) {
                onlyInA.add(itemA)
                badgeMap[itemA.uniqueKey] = DiffStatus.ONLY_IN_P1
            } else {
                val timeDiff = itemA.lastModified - matchB.lastModified
                if (itemA.isDirectory && matchB.isDirectory) {
                    identicalCount++
                    badgeMap[itemA.uniqueKey] = DiffStatus.IDENTICAL
                    badgeMap[matchB.uniqueKey] = DiffStatus.IDENTICAL
                } else if (timeDiff > 2000L) {
                    newerInA.add(itemA)
                    badgeMap[itemA.uniqueKey] = DiffStatus.NEWER_HERE
                    badgeMap[matchB.uniqueKey] = DiffStatus.OLDER_HERE
                } else if (timeDiff < -2000L) {
                    newerInB.add(matchB)
                    badgeMap[itemA.uniqueKey] = DiffStatus.OLDER_HERE
                    badgeMap[matchB.uniqueKey] = DiffStatus.NEWER_HERE
                } else if (itemA.size != matchB.size) {
                    newerInA.add(itemA)
                    badgeMap[itemA.uniqueKey] = DiffStatus.SIZE_DIFF
                    badgeMap[matchB.uniqueKey] = DiffStatus.SIZE_DIFF
                } else {
                    identicalCount++
                    badgeMap[itemA.uniqueKey] = DiffStatus.IDENTICAL
                    badgeMap[matchB.uniqueKey] = DiffStatus.IDENTICAL
                }
            }
        }

        for (itemB in localB) {
            if (itemB.name !in mapA) {
                onlyInB.add(itemB)
                badgeMap[itemB.uniqueKey] = DiffStatus.ONLY_IN_P2
            }
        }

        val summary = PaneComparisonSummary(
            paneAPath = pathA,
            paneBPath = pathB,
            onlyInA = onlyInA,
            onlyInB = onlyInB,
            newerInA = newerInA,
            newerInB = newerInB,
            identicalCount = identicalCount
        )
        return Pair(summary, badgeMap)
    }

    suspend fun inspectFileDetails(
        entry: FileEntry,
        archiveEngine: ArchiveEngine,
        password: String? = null
    ): FileDetailedInfo = withContext(Dispatchers.IO) {
        if (entry.isVirtualArchiveEntry && entry.archiveHostPath != null && entry.archiveInternalPath != null) {
            val crc32Hex = entry.crc32?.let { String.format(Locale.US, "%08X", it) }
            return@withContext FileDetailedInfo(
                entry = entry,
                mimeType = resolveMimeType(entry.name, entry.isDirectory),
                readablePermissions = entry.permissions,
                octalPermissions = entry.octalPermissions,
                ownerReadableWritableExecutable = "VIRTUAL ARCHIVE ENTRY",
                exactBytes = entry.size,
                formattedSize = formatBytes(entry.size) + (entry.compressedSize?.let { " (Packed: ${formatBytes(it)})" } ?: ""),
                recursiveFileCount = null,
                recursiveFolderCount = null,
                symlinkTarget = null,
                md5 = null,
                sha1 = null,
                sha256 = null,
                crc32Hex = crc32Hex,
                archiveSummary = null
            )
        }

        val file = File(entry.path)
        var recursiveFiles = 0
        var recursiveFolders = 0
        var totalBytes = 0L

        if (file.isDirectory) {
            fun walkDir(current: File, depth: Int) {
                if (depth > 14) return
                val list = current.listFiles() ?: return
                for (child in list) {
                    if (child.isDirectory) {
                        recursiveFolders++
                        walkDir(child, depth + 1)
                    } else {
                        recursiveFiles++
                        totalBytes += child.length()
                    }
                }
            }
            walkDir(file, 0)
        } else {
            totalBytes = file.length()
        }

        val symlinkTarget = if (entry.isSymlink) {
            runCatching { file.canonicalPath }.getOrNull()
        } else {
            null
        }

        var md5Hex: String? = null
        var sha1Hex: String? = null
        var sha256Hex: String? = null
        var crc32Hex: String? = null

        if (file.isFile && file.canRead() && totalBytes <= 512L * 1024L * 1024L) {
            runCatching {
                val md5Digest = MessageDigest.getInstance("MD5")
                val sha1Digest = MessageDigest.getInstance("SHA-1")
                val sha256Digest = MessageDigest.getInstance("SHA-256")
                val crc = CRC32()
                val buffer = ByteArray(64 * 1024)
                FileInputStream(file).use { fis ->
                    var read = fis.read(buffer)
                    while (read > 0) {
                        md5Digest.update(buffer, 0, read)
                        sha1Digest.update(buffer, 0, read)
                        sha256Digest.update(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        read = fis.read(buffer)
                    }
                }
                md5Hex = md5Digest.digest().joinToString("") { "%02x".format(it) }
                sha1Hex = sha1Digest.digest().joinToString("") { "%02x".format(it) }
                sha256Hex = sha256Digest.digest().joinToString("") { "%02x".format(it) }
                crc32Hex = String.format(Locale.US, "%08X", crc.value)
            }
        }

        val archiveSummary = if (entry.isArchive) {
            archiveEngine.inspectArchiveSummary(file.absolutePath, password).getOrNull()
        } else {
            null
        }

        val flags = buildList {
            if (file.canRead()) add("READ") else add("NO-READ")
            if (file.canWrite()) add("WRITE") else add("READ-ONLY")
            if (file.canExecute()) add("EXEC")
            if (file.isHidden) add("HIDDEN")
            if (entry.isPinned) add("PINNED")
        }.joinToString(" | ")

        FileDetailedInfo(
            entry = entry,
            mimeType = resolveMimeType(file.name, file.isDirectory),
            readablePermissions = entry.permissions,
            octalPermissions = entry.octalPermissions,
            ownerReadableWritableExecutable = flags,
            exactBytes = totalBytes,
            formattedSize = "${formatBytes(totalBytes)} (${String.format(Locale.US, "%,d", totalBytes)} B)",
            recursiveFileCount = if (file.isDirectory) recursiveFiles else null,
            recursiveFolderCount = if (file.isDirectory) recursiveFolders else null,
            symlinkTarget = symlinkTarget,
            md5 = md5Hex,
            sha1 = sha1Hex,
            sha256 = sha256Hex,
            crc32Hex = crc32Hex,
            archiveSummary = archiveSummary
        )
    }

    suspend fun createFolder(parentPath: String, name: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = name.trim()
            require(clean.isNotEmpty() && !clean.contains("/")) { "Invalid folder name." }
            val target = File(parentPath, clean)
            if (target.exists()) throw IllegalStateException("Item '${clean}' already exists.")
            if (!target.mkdirs()) throw IllegalStateException("Failed to create folder '${clean}'.")
            target
        }
    }

    suspend fun createEmptyFile(parentPath: String, name: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = name.trim()
            require(clean.isNotEmpty() && !clean.contains("/")) { "Invalid file name." }
            val target = File(parentPath, clean)
            if (target.exists()) throw IllegalStateException("File '${clean}' already exists.")
            target.writeText("", Charsets.UTF_8)
            target
        }
    }

    suspend fun renameEntry(entry: FileEntry, newName: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = newName.trim()
            require(clean.isNotEmpty() && !clean.contains("/")) { "Invalid new name." }
            val source = File(entry.path)
            val dest = File(source.parentFile, clean)
            if (dest.exists()) throw IllegalStateException("Target '${clean}' already exists.")
            if (!source.renameTo(dest)) throw IllegalStateException("Rename failed for '${entry.name}'.")
            dest
        }
    }

    suspend fun batchRenameEntries(
        entries: List<FileEntry>,
        prefix: String,
        startNumber: Int = 1
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPrefix = prefix.trim().ifEmpty { "item_" }
            var renamedCount = 0
            entries.forEachIndexed { idx, item ->
                val src = File(item.path)
                if (src.exists()) {
                    val numStr = String.format(Locale.US, "%02d", startNumber + idx)
                    val extPart = if (!src.isDirectory && src.extension.isNotEmpty()) ".${src.extension}" else ""
                    val newName = "$cleanPrefix$numStr$extPart"
                    val dest = File(src.parentFile, newName)
                    if (!dest.exists() && src.renameTo(dest)) {
                        renamedCount++
                    }
                }
            }
            renamedCount
        }
    }

    /**
     * Moves entries to the internal `.exp-txt-trash` folder and returns a list of TrashRecords
     * so the user can undo the deletion in one tap.
     */
    suspend fun moveEntriesToTrash(
        entries: List<FileEntry>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Result<List<TrashRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            val trashDir = File(trashDirectoryPath())
            if (!trashDir.exists()) trashDir.mkdirs()

            val records = mutableListOf<TrashRecord>()
            val total = entries.size
            val now = System.currentTimeMillis()

            entries.forEachIndexed { idx, item ->
                onProgress?.invoke(idx + 1, total, "Moving ${item.name} to Trash")
                val src = File(item.path)
                if (src.exists()) {
                    var dest = File(trashDir, src.name)
                    if (dest.exists()) {
                        val base = src.nameWithoutExtension
                        val ext = src.extension.let { if (it.isEmpty()) "" else ".$it" }
                        dest = File(trashDir, "${base}_${now % 100000}$ext")
                    }
                    val moved = if (src.renameTo(dest)) {
                        true
                    } else {
                        val copied = src.copyRecursively(dest, overwrite = true)
                        if (copied) src.deleteRecursively() else false
                    }
                    if (moved) {
                        records.add(
                            TrashRecord(
                                trashedFilePath = dest.absolutePath,
                                originalFilePath = src.absolutePath,
                                originalName = src.name,
                                deletedAtMillis = now
                            )
                        )
                    }
                }
            }
            records
        }
    }

    suspend fun restoreTrashRecords(
        records: List<TrashRecord>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var restored = 0
            val total = records.size
            records.forEachIndexed { idx, rec ->
                onProgress?.invoke(idx + 1, total, "Restoring ${rec.originalName}")
                val trashed = File(rec.trashedFilePath)
                val orig = File(rec.originalFilePath)
                if (trashed.exists()) {
                    orig.parentFile?.mkdirs()
                    val ok = if (trashed.renameTo(orig)) {
                        true
                    } else {
                        val copied = trashed.copyRecursively(orig, overwrite = true)
                        if (copied) trashed.deleteRecursively() else false
                    }
                    if (ok) restored++
                }
            }
            restored
        }
    }

    suspend fun emptyTrashBin(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val trashDir = File(trashDirectoryPath())
            val children = trashDir.listFiles() ?: emptyArray()
            var removed = 0
            for (child in children) {
                if (child.deleteRecursively()) {
                    removed++
                }
            }
            removed
        }
    }

    suspend fun deleteEntries(
        entries: List<FileEntry>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var count = 0
            val total = entries.size
            entries.forEachIndexed { idx, item ->
                onProgress?.invoke(idx + 1, total, "Deleting ${item.name}")
                val f = File(item.path)
                if (f.exists() && f.deleteRecursively()) {
                    count++
                }
            }
            count
        }
    }

    suspend fun copyOrMoveEntries(
        entries: List<FileEntry>,
        destinationDirPath: String,
        move: Boolean,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = File(destinationDirPath)
            if (!destDir.exists()) destDir.mkdirs()
            require(destDir.isDirectory) { "Destination is not a directory: $destinationDirPath" }

            var processed = 0
            val total = entries.size
            entries.forEachIndexed { idx, entry ->
                val src = File(entry.path)
                if (src.exists()) {
                    onProgress?.invoke(
                        idx + 1,
                        total,
                        "${if (move) "Moving" else "Copying"} ${src.name}"
                    )
                    var dest = File(destDir, src.name)
                    if (src.canonicalPath == dest.canonicalPath) {
                        if (!move) {
                            val base = src.nameWithoutExtension
                            val ext = src.extension.let { if (it.isEmpty()) "" else ".$it" }
                            dest = File(destDir, "${base}_copy$ext")
                            src.copyRecursively(dest, overwrite = true)
                            processed++
                        }
                    } else {
                        if (move) {
                            if (!src.renameTo(dest)) {
                                src.copyRecursively(dest, overwrite = true)
                                src.deleteRecursively()
                            }
                        } else {
                            src.copyRecursively(dest, overwrite = true)
                        }
                        processed++
                    }
                }
            }
            processed
        }
    }

    suspend fun updatePermissions(
        entry: FileEntry,
        readable: Boolean,
        writable: Boolean,
        executable: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(entry.path)
            f.setReadable(readable, false)
            f.setWritable(writable, false)
            f.setExecutable(executable, false)
            Unit
        }
    }

    /**
     * Inspects a shared content:// or file:// Uri from an external Android app.
     */
    fun inspectIncomingUri(uri: Uri): SharedIncomingItem {
        var displayName: String? = null
        var sizeBytes = 0L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
        }

        val resolvedName = displayName
            ?.trim()
            ?.replace('/', '_')
            ?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?: "shared_${System.currentTimeMillis()}"

        return SharedIncomingItem(
            uriString = uri.toString(),
            displayName = resolvedName,
            sizeBytes = sizeBytes
        )
    }

    /**
     * Copies shared Uris and/or shared plain text from another Android app into destinationDirPath.
     */
    suspend fun saveSharedItemsToDirectory(
        items: List<SharedIncomingItem>,
        sharedText: String?,
        destinationDirPath: String,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = File(destinationDirPath)
            if (!destDir.exists()) destDir.mkdirs()
            require(destDir.isDirectory) { "Invalid destination folder: $destinationDirPath" }

            val savedFiles = mutableListOf<File>()
            val total = items.size + (if (!sharedText.isNullOrBlank() && items.isEmpty()) 1 else 0)

            items.forEachIndexed { idx, item ->
                onProgress?.invoke(idx + 1, total.coerceAtLeast(1), "Saving ${item.displayName}")
                val uri = Uri.parse(item.uriString)
                val outFile = uniqueDestinationFile(destDir, item.displayName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedOutputStream(FileOutputStream(outFile)).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                } ?: throw IllegalStateException("Cannot read shared stream: ${item.displayName}")
                savedFiles.add(outFile)
            }

            if (!sharedText.isNullOrBlank() && items.isEmpty()) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val noteFile = uniqueDestinationFile(destDir, "shared_text_$stamp.txt")
                noteFile.writeText(sharedText, Charsets.UTF_8)
                savedFiles.add(noteFile)
            }

            savedFiles
        }
    }

    private fun uniqueDestinationFile(destDir: File, desiredName: String): File {
        var candidate = File(destDir, desiredName)
        if (!candidate.exists()) return candidate
        val dotIdx = desiredName.lastIndexOf('.')
        val base = if (dotIdx > 0) desiredName.substring(0, dotIdx) else desiredName
        val ext = if (dotIdx > 0) desiredName.substring(dotIdx) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(destDir, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }

    companion object {
        const val TRASH_FOLDER_NAME = ".exp-txt-trash"

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "rst", "log", "json", "xml", "yml", "yaml", "toml",
            "ini", "cfg", "conf", "properties", "env", "csv", "tsv", "sql",
            "kt", "kts", "java", "py", "rb", "js", "ts", "jsx", "tsx", "c", "cpp", "h",
            "hpp", "rs", "go", "sh", "bash", "zsh", "fish", "html", "htm", "css", "scss",
            "gradle", "pro", "gitignore", "dockerfile", "makefile", "tex", "bib"
        )

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "flac", "wav", "ogg", "opus", "aac", "wma", "aiff"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v", "3gp"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "heic", "raw"
        )

        private val DOC_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "epub", "mobi"
        )

        fun isTextViewableExtension(ext: String, fileName: String = ""): Boolean {
            val lowerExt = ext.lowercase(Locale.ROOT)
            val lowerName = fileName.lowercase(Locale.ROOT)
            return lowerExt in TEXT_EXTENSIONS ||
                lowerName in setOf("dockerfile", "makefile", "license", "readme", ".gitignore", ".env")
        }

        fun classifyFileCategory(
            name: String,
            ext: String,
            isDir: Boolean,
            isSymlink: Boolean,
            isArchive: Boolean,
            isExecutable: Boolean
        ): PlainFileCategory {
            return when {
                name == ".." -> PlainFileCategory.PARENT_DIR
                isSymlink -> PlainFileCategory.SYMLINK
                isDir -> PlainFileCategory.DIRECTORY
                ext == "apk" || ext == "aab" -> PlainFileCategory.PACKAGE
                isArchive -> PlainFileCategory.ARCHIVE
                ext in AUDIO_EXTENSIONS -> PlainFileCategory.AUDIO
                ext in VIDEO_EXTENSIONS -> PlainFileCategory.VIDEO
                ext in IMAGE_EXTENSIONS -> PlainFileCategory.IMAGE
                ext in DOC_EXTENSIONS -> PlainFileCategory.DOCUMENT
                isTextViewableExtension(ext, name) -> PlainFileCategory.TEXT_CODE
                isExecutable -> PlainFileCategory.EXECUTABLE
                else -> PlainFileCategory.BINARY
            }
        }

        fun buildPermissionStrings(file: File, isDir: Boolean, isSymlink: Boolean): Pair<String, String> {
            val typeChar = when {
                isSymlink -> 'l'
                isDir -> 'd'
                else -> '-'
            }
            val r = if (file.canRead()) 'r' else '-'
            val w = if (file.canWrite()) 'w' else '-'
            val x = if (file.canExecute() || isDir) 'x' else '-'
            val perm = "$typeChar$r$w$x$r-$x$r-$x"

            val ownerBit = (if (file.canRead()) 4 else 0) +
                (if (file.canWrite()) 2 else 0) +
                (if (file.canExecute() || isDir) 1 else 0)
            val groupBit = (if (file.canRead()) 4 else 0) + (if (file.canExecute() || isDir) 1 else 0)
            val octal = "0$ownerBit$groupBit$groupBit"
            return Pair(perm, octal)
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 0L) return "0 B"
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.2f GB", gb)
        }

        fun formatTimestamp(epochMillis: Long): String {
            if (epochMillis <= 0L) return "----/--/-- --:--"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            return sdf.format(Date(epochMillis))
        }

        fun formatShortDate(epochMillis: Long): String {
            if (epochMillis <= 0L) return "----------"
            val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)
            return sdf.format(Date(epochMillis))
        }

        fun resolveMimeType(fileName: String, isDirectory: Boolean): String {
            if (isDirectory) return "inode/directory"
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext.isEmpty()) return "*/*"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (isTextViewableExtension(ext, fileName)) "text/plain" else "*/*"
        }
    }
}
