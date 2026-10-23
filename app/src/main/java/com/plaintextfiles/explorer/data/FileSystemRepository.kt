package com.plaintextfiles.explorer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.plaintextfiles.explorer.archive.ArchiveEngine
import com.plaintextfiles.explorer.model.ArchiveFormat
import com.plaintextfiles.explorer.model.FileDetailedInfo
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.PlainFileCategory
import com.plaintextfiles.explorer.model.SortConfig
import com.plaintextfiles.explorer.model.SortField
import com.plaintextfiles.explorer.model.SortOrder
import com.plaintextfiles.explorer.model.StorageRootInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
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
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            readGranted
        }
    }

    fun defaultStartDirectory(): String {
        val primary = Environment.getExternalStorageDirectory()
        return if (primary.exists() && primary.canRead()) {
            primary.absolutePath
        } else {
            context.filesDir.absolutePath
        }
    }

    fun discoverStorageRoots(): List<StorageRootInfo> {
        val roots = mutableListOf<StorageRootInfo>()
        val seenPaths = mutableSetOf<String>()

        fun addRootIfValid(label: String, shortTag: String, dir: File?) {
            if (dir == null || !dir.exists()) return
            val canonicalOrAbs = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!seenPaths.add(canonicalOrAbs)) return
            val (total, free) = queryStatFs(dir)
            roots.add(
                StorageRootInfo(
                    label = label,
                    shortTag = shortTag,
                    path = dir.absolutePath,
                    totalBytes = total,
                    freeBytes = free
                )
            )
        }

        val primaryExt = Environment.getExternalStorageDirectory()
        addRootIfValid("Internal Shared", "INTERNAL", primaryExt)
        addRootIfValid(
            "Downloads",
            "DOWNLOAD",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        addRootIfValid(
            "Documents",
            "DOCS",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        )
        addRootIfValid(
            "DCIM / Media",
            "DCIM",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        )

        // Check removable SD / USB OTG volumes
        val externalDirs = context.getExternalFilesDirs(null)
        externalDirs.forEachIndexed { idx, appExtDir ->
            if (appExtDir != null && idx > 0) {
                // Walk up from /storage/XXXX-XXXX/Android/data/... to /storage/XXXX-XXXX
                var cursor: File? = appExtDir
                repeat(4) { cursor = cursor?.parentFile }
                if (cursor != null && cursor!!.exists()) {
                    addRootIfValid("External Volume $idx", "EXT-$idx", cursor)
                }
            }
        }

        addRootIfValid("App Sandbox", "SANDBOX", context.filesDir)
        addRootIfValid("System Root /", "ROOT /", File("/"))

        return roots
    }

    fun queryStatFs(dir: File): Pair<Long, Long> {
        return runCatching {
            val stat = StatFs(dir.absolutePath)
            Pair(stat.totalBytes, stat.availableBytes)
        }.getOrDefault(Pair(0L, 0L))
    }

    suspend fun listDirectory(
        dirPath: String,
        sortConfig: SortConfig
    ): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(dirPath)
            if (!dir.exists()) {
                throw IllegalArgumentException("Path does not exist: $dirPath")
            }
            if (!dir.isDirectory) {
                throw IllegalArgumentException("Not a directory: $dirPath")
            }

            val rawChildren = dir.listFiles()
                ?: throw IllegalStateException("ACCESS DENIED: Grant All Files Access or check directory permissions.")

            val mapped = rawChildren.mapNotNull { file ->
                if (!sortConfig.showHidden && file.name.startsWith(".")) {
                    null
                } else {
                    fileToFileEntry(file)
                }
            }

            sortEntries(mapped, sortConfig)
        }
    }

    fun fileToFileEntry(file: File): FileEntry {
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
            category = classifyFileCategory(name, ext, isDir, isSymlink, isArc, file.canExecute())
        )
    }

    fun sortEntries(entries: List<FileEntry>, config: SortConfig): List<FileEntry> {
        val comparator = Comparator<FileEntry> { a, b ->
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

    suspend fun inspectFileDetails(
        entry: FileEntry,
        archiveEngine: ArchiveEngine,
        password: String? = null
    ): FileDetailedInfo = withContext(Dispatchers.IO) {
        if (entry.isVirtualArchiveEntry && entry.archiveHostPath != null && entry.archiveInternalPath != null) {
            val preview = if (!entry.isDirectory && isTextViewableExtension(entry.extension, entry.name)) {
                archiveEngine.readVirtualEntryPreview(
                    archivePath = entry.archiveHostPath,
                    internalPath = entry.archiveInternalPath,
                    password = password
                )
            } else {
                null
            }
            val crcHex = entry.crc32?.let { String.format(Locale.US, "%08X", it) }
            return@withContext FileDetailedInfo(
                entry = entry,
                mimeType = resolveMimeType(entry.name, entry.isDirectory),
                readablePermissions = entry.permissions,
                octalPermissions = entry.octalPermissions,
                ownerReadableWritableExecutable = "VIRTUAL ARCHIVE ENTRY (READ-ONLY)",
                exactBytes = entry.size,
                formattedSize = formatBytes(entry.size) + (entry.compressedSize?.let { " (Packed: ${formatBytes(it)})" } ?: ""),
                recursiveFileCount = null,
                recursiveFolderCount = null,
                symlinkTarget = null,
                md5 = null,
                sha1 = null,
                sha256 = null,
                crc32Hex = crcHex,
                archiveSummary = null,
                textPreview = preview
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

        // Compute MD5, SHA-1, SHA-256, and CRC32 for files up to 512 MB
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

        val textPreview = if (file.isFile && file.canRead() && isTextViewableExtension(entry.extension, entry.name)) {
            readTextPreview(file, maxChars = 2400)
        } else {
            null
        }

        val flags = buildList {
            if (file.canRead()) add("READABLE") else add("NO-READ")
            if (file.canWrite()) add("WRITABLE") else add("READ-ONLY")
            if (file.canExecute()) add("EXECUTABLE")
            if (file.isHidden) add("HIDDEN")
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
            archiveSummary = archiveSummary,
            textPreview = textPreview
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

    suspend fun createPlainTextFile(
        parentPath: String,
        name: String,
        initialContent: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = name.trim()
            require(clean.isNotEmpty() && !clean.contains("/")) { "Invalid file name." }
            val target = File(parentPath, clean)
            if (target.exists()) throw IllegalStateException("File '${clean}' already exists.")
            target.writeText(initialContent, Charsets.UTF_8)
            target
        }
    }

    suspend fun saveTextFile(filePath: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            File(filePath).writeText(content, Charsets.UTF_8)
        }
    }

    suspend fun readFullTextFile(filePath: String, maxBytes: Int = 512 * 1024): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(filePath)
            val bytes = FileInputStream(file).use { fis ->
                val len = file.length().coerceAtMost(maxBytes.toLong()).toInt()
                val buf = ByteArray(len)
                val read = fis.read(buf)
                if (read > 0) buf.copyOf(read) else ByteArray(0)
            }
            String(bytes, Charsets.UTF_8)
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

    suspend fun deleteEntries(entries: List<FileEntry>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var count = 0
            for (item in entries) {
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
        move: Boolean
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = File(destinationDirPath)
            if (!destDir.exists()) destDir.mkdirs()
            require(destDir.isDirectory) { "Destination is not a directory: $destinationDirPath" }

            var processed = 0
            for (entry in entries) {
                val src = File(entry.path)
                if (!src.exists()) continue
                var dest = File(destDir, src.name)
                if (src.canonicalPath == dest.canonicalPath) {
                    if (move) continue
                    val base = src.nameWithoutExtension
                    val ext = src.extension.let { if (it.isEmpty()) "" else ".$it" }
                    dest = File(destDir, "${base}_copy$ext")
                }

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
        }
    }

    private fun readTextPreview(file: File, maxChars: Int): String? {
        return runCatching {
            val buffer = ByteArray(maxChars.coerceAtMost(4096))
            val read = FileInputStream(file).use { it.read(buffer) }
            if (read <= 0) return@runCatching "(Empty File)"
            val slice = buffer.copyOf(read)
            // Check if binary (contains null bytes)
            if (slice.any { it == 0.toByte() }) return@runCatching null
            String(slice, Charsets.UTF_8)
                .lineSequence()
                .take(30)
                .joinToString("
")
        }.getOrNull()
    }

    companion object {
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
                isTextViewableExtension(ext, name) -> PlainFileCategory.TEXT_CODE
                ext in AUDIO_EXTENSIONS -> PlainFileCategory.AUDIO
                ext in VIDEO_EXTENSIONS -> PlainFileCategory.VIDEO
                ext in IMAGE_EXTENSIONS -> PlainFileCategory.IMAGE
                ext in DOC_EXTENSIONS -> PlainFileCategory.DOCUMENT
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
            if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
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
            if (ext.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (isTextViewableExtension(ext, fileName)) "text/plain" else "application/octet-stream"
        }
    }
}
