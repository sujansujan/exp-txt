package com.plaintextfiles.explorer.archive

import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.ArchiveFormat
import com.plaintextfiles.explorer.model.ArchiveSummaryInfo
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.PlainFileCategory
import com.plaintextfiles.explorer.model.SortConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

class ArchiveEngine(private val fileSystemRepository: FileSystemRepository) {

    private data class RawArchiveNode(
        val internalPath: String,
        val isDirectory: Boolean,
        val uncompressedSize: Long,
        val compressedSize: Long?,
        val lastModified: Long,
        val crc32: Long?
    )

    suspend fun listVirtualDirectory(
        archivePath: String,
        internalDir: String,
        sortConfig: SortConfig,
        password: String? = null
    ): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val archiveFile = File(archivePath)
            require(archiveFile.exists() && archiveFile.isFile) { "Archive not found: $archivePath" }
            val format = ArchiveFormat.fromFileName(archiveFile.name)
                ?: throw IllegalArgumentException("Unsupported archive format: ${archiveFile.name}")

            val allNodes = readAllArchiveNodes(archiveFile, format, password)
            val normalizedPrefix = internalDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }

            val directMap = linkedMapOf<String, FileEntry>()
            val subDirChildCounts = mutableMapOf<String, MutableSet<String>>()

            for (node in allNodes) {
                val cleanPath = node.internalPath.trim('/')
                if (cleanPath.isEmpty()) continue
                if (normalizedPrefix.isNotEmpty() && !cleanPath.startsWith(normalizedPrefix)) continue

                val remainder = if (normalizedPrefix.isEmpty()) cleanPath else cleanPath.removePrefix(normalizedPrefix)
                if (remainder.isEmpty()) continue

                val slashIndex = remainder.indexOf('/')
                if (slashIndex < 0) {
                    val name = remainder
                    if (!sortConfig.showHidden && name.startsWith(".")) continue
                    val ext = if (node.isDirectory) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val nestedArcFmt = if (!node.isDirectory) ArchiveFormat.fromFileName(name) else null
                    val childInternalPath = if (normalizedPrefix.isEmpty()) name else "$normalizedPrefix$name"

                    directMap[name] = FileEntry(
                        name = name,
                        path = "$archivePath::$childInternalPath",
                        isDirectory = node.isDirectory,
                        isSymlink = false,
                        size = node.uncompressedSize.coerceAtLeast(0L),
                        compressedSize = node.compressedSize,
                        lastModified = node.lastModified,
                        permissions = if (node.isDirectory) "dr-xr-xr-x" else "-r--r--r--",
                        octalPermissions = if (node.isDirectory) "0555" else "0444",
                        extension = ext,
                        isHidden = name.startsWith("."),
                        isArchive = nestedArcFmt != null,
                        archiveFormat = nestedArcFmt,
                        isVirtualArchiveEntry = true,
                        archiveHostPath = archivePath,
                        archiveInternalPath = childInternalPath,
                        childCount = if (node.isDirectory) 0 else null,
                        crc32 = node.crc32,
                        category = FileSystemRepository.classifyFileCategory(
                            name = name,
                            ext = ext,
                            isDir = node.isDirectory,
                            isSymlink = false,
                            isArchive = nestedArcFmt != null,
                            isExecutable = false
                        )
                    )
                } else {
                    val dirName = remainder.substring(0, slashIndex)
                    val afterDir = remainder.substring(slashIndex + 1)
                    if (!sortConfig.showHidden && dirName.startsWith(".")) continue

                    val immediateGrandchild = afterDir.substringBefore('/')
                    if (immediateGrandchild.isNotEmpty()) {
                        subDirChildCounts.getOrPut(dirName) { mutableSetOf() }.add(immediateGrandchild)
                    }

                    if (!directMap.containsKey(dirName)) {
                        val childInternalPath = if (normalizedPrefix.isEmpty()) dirName else "$normalizedPrefix$dirName"
                        directMap[dirName] = FileEntry(
                            name = dirName,
                            path = "$archivePath::$childInternalPath",
                            isDirectory = true,
                            isSymlink = false,
                            size = 0L,
                            compressedSize = null,
                            lastModified = node.lastModified,
                            permissions = "dr-xr-xr-x",
                            octalPermissions = "0555",
                            extension = "",
                            isHidden = dirName.startsWith("."),
                            isArchive = false,
                            archiveFormat = null,
                            isVirtualArchiveEntry = true,
                            archiveHostPath = archivePath,
                            archiveInternalPath = childInternalPath,
                            childCount = 0,
                            crc32 = null,
                            category = PlainFileCategory.DIRECTORY
                        )
                    }
                }
            }

            val finalized = directMap.map { (name, entry) ->
                if (entry.isDirectory) {
                    entry.copy(childCount = subDirChildCounts[name]?.size ?: 0)
                } else {
                    entry
                }
            }

            fileSystemRepository.sortEntries(finalized, sortConfig)
        }
    }

    suspend fun inspectArchiveSummary(
        archivePath: String,
        password: String? = null
    ): Result<ArchiveSummaryInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val archiveFile = File(archivePath)
            val format = ArchiveFormat.fromFileName(archiveFile.name)
                ?: throw IllegalArgumentException("Not a supported archive: ${archiveFile.name}")

            var isEncrypted = false
            if (format == ArchiveFormat.ZIP) {
                runCatching {
                    val zf = ZipFile(archiveFile)
                    isEncrypted = zf.isEncrypted
                }
            }

            val nodes = readAllArchiveNodes(archiveFile, format, password)
            val dirs = nodes.count { it.isDirectory }
            val files = nodes.size - dirs
            val totalUncompressed = nodes.filter { !it.isDirectory }.sumOf { it.uncompressedSize.coerceAtLeast(0L) }
            val totalCompressed = archiveFile.length()
            val ratio = if (totalUncompressed > 0L) {
                ((totalCompressed.toDouble() / totalUncompressed.toDouble()) * 100.0).toInt().coerceIn(1, 100)
            } else {
                100
            }

            ArchiveSummaryInfo(
                formatName = format.displayName,
                totalEntries = nodes.size,
                totalFiles = files,
                totalDirectories = dirs,
                totalUncompressedBytes = totalUncompressed,
                totalCompressedBytes = totalCompressed,
                compressionRatioPercent = ratio,
                isEncrypted = isEncrypted
            )
        }
    }

    /**
     * Extracts a single virtual archive entry to a temporary cache file so it can be opened
     * immediately in its default Android app.
     */
    suspend fun extractSingleEntryToCache(
        archivePath: String,
        internalPath: String,
        cacheDir: File,
        password: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val tempRoot = File(cacheDir, "archive_open_cache").apply { mkdirs() }
            extractArchive(
                archivePath = archivePath,
                destinationDirPath = tempRoot.absolutePath,
                createSubfolder = false,
                password = password,
                selectedVirtualPaths = setOf(internalPath)
            ).getOrThrow()
            val target = File(tempRoot, internalPath.trimStart('/', '\\'))
            require(target.exists()) { "Could not extract entry: $internalPath" }
            target
        }
    }

    suspend fun extractArchive(
        archivePath: String,
        destinationDirPath: String,
        createSubfolder: Boolean = true,
        password: String? = null,
        selectedVirtualPaths: Set<String>? = null,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val archiveFile = File(archivePath)
            require(archiveFile.exists()) { "Archive does not exist: $archivePath" }
            val format = ArchiveFormat.fromFileName(archiveFile.name)
                ?: throw IllegalArgumentException("Unsupported archive format: ${archiveFile.name}")

            val baseDestDir = if (createSubfolder) {
                val cleanBase = stripArchiveExtensions(archiveFile.name)
                File(destinationDirPath, cleanBase)
            } else {
                File(destinationDirPath)
            }
            if (!baseDestDir.exists()) baseDestDir.mkdirs()
            val canonicalDestRoot = baseDestDir.canonicalPath

            val normalizedFilters = selectedVirtualPaths?.map { it.trim('/') }?.toSet()

            fun matchesFilter(entryPath: String): Boolean {
                if (normalizedFilters.isNullOrEmpty()) return true
                val clean = entryPath.trim('/')
                return normalizedFilters.any { filter ->
                    clean == filter || clean.startsWith("$filter/")
                }
            }

            var extractedCount = 0

            when (format) {
                ArchiveFormat.ZIP -> {
                    val zipFile = ZipFile(archiveFile)
                    if (zipFile.isEncrypted) {
                        if (password.isNullOrEmpty()) {
                            throw IllegalStateException("PASSWORD_REQUIRED: This ZIP archive is encrypted.")
                        }
                        zipFile.setPassword(password.toCharArray())
                    }
                    val headers = zipFile.fileHeaders.filter { matchesFilter(it.fileName) }
                    val total = headers.size.coerceAtLeast(1)
                    headers.forEachIndexed { idx, header ->
                        onProgress?.invoke(idx + 1, total, "Extracting ${header.fileName.substringAfterLast('/')}")
                        val outFile = resolveSafeOutputFile(baseDestDir, canonicalDestRoot, header.fileName)
                        if (header.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            zipFile.getInputStream(header).use { input ->
                                BufferedOutputStream(FileOutputStream(outFile)).use { output ->
                                    input.copyTo(output, 32 * 1024)
                                }
                            }
                            extractedCount++
                        }
                    }
                }

                ArchiveFormat.SEVEN_Z -> {
                    SevenZFile(archiveFile).use { sevenZ ->
                        val buffer = ByteArray(32 * 1024)
                        var entry = sevenZ.nextEntry
                        while (entry != null) {
                            if (matchesFilter(entry.name)) {
                                onProgress?.invoke(extractedCount + 1, extractedCount + 2, "Extracting ${entry.name.substringAfterLast('/')}")
                                val outFile = resolveSafeOutputFile(baseDestDir, canonicalDestRoot, entry.name)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                                        var read = sevenZ.read(buffer)
                                        while (read > 0) {
                                            out.write(buffer, 0, read)
                                            read = sevenZ.read(buffer)
                                        }
                                    }
                                    if (entry.hasLastModifiedDate) {
                                        outFile.setLastModified(entry.lastModifiedDate.time)
                                    }
                                    extractedCount++
                                }
                            }
                            entry = sevenZ.nextEntry
                        }
                    }
                }

                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> {
                    openTarInputStream(archiveFile, format).use { tis ->
                        var entry = tis.nextTarEntry
                        while (entry != null) {
                            if (matchesFilter(entry.name)) {
                                onProgress?.invoke(extractedCount + 1, extractedCount + 2, "Extracting ${entry.name.substringAfterLast('/')}")
                                val outFile = resolveSafeOutputFile(baseDestDir, canonicalDestRoot, entry.name)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                                        tis.copyTo(out, 32 * 1024)
                                    }
                                    outFile.setLastModified(entry.modTime.time)
                                    if ((entry.mode and 64) != 0) {
                                        outFile.setExecutable(true)
                                    }
                                    extractedCount++
                                }
                            }
                            entry = tis.nextTarEntry
                        }
                    }
                }

                ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ -> {
                    val outName = stripArchiveExtensions(archiveFile.name).ifEmpty { "decompressed_output" }
                    val outFile = resolveSafeOutputFile(baseDestDir, canonicalDestRoot, outName)
                    outFile.parentFile?.mkdirs()
                    onProgress?.invoke(1, 1, "Extracting $outName")
                    openSingleCompressorStream(archiveFile, format).use { cis ->
                        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                            cis.copyTo(out, 32 * 1024)
                        }
                    }
                    extractedCount = 1
                }
            }

            extractedCount
        }
    }

    suspend fun createArchive(
        sourceEntries: List<FileEntry>,
        destinationDirPath: String,
        archiveBaseName: String,
        format: ArchiveFormat,
        password: String? = null,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceEntries.isNotEmpty()) { "Select at least one file or folder to compress." }
            val cleanBase = stripArchiveExtensions(archiveBaseName.trim()).ifEmpty { "archive" }
            val finalFileName = "$cleanBase.${format.primaryExtension}"
            val destDir = File(destinationDirPath)
            if (!destDir.exists()) destDir.mkdirs()
            val outputFile = File(destDir, finalFileName)
            if (outputFile.exists()) outputFile.delete()

            val sourceFiles = sourceEntries.map { File(it.path) }.filter { it.exists() }
            require(sourceFiles.isNotEmpty()) { "No valid source files found on disk." }
            val totalSources = sourceFiles.size.coerceAtLeast(1)

            when (format) {
                ArchiveFormat.ZIP -> {
                    val params = ZipParameters().apply {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.NORMAL
                        if (!password.isNullOrEmpty()) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    val zipFile = if (!password.isNullOrEmpty()) {
                        ZipFile(outputFile, password.toCharArray())
                    } else {
                        ZipFile(outputFile)
                    }
                    sourceFiles.forEachIndexed { idx, src ->
                        onProgress?.invoke(idx + 1, totalSources, "Compressing ${src.name}")
                        if (src.isDirectory) {
                            zipFile.addFolder(src, params)
                        } else {
                            zipFile.addFile(src, params)
                        }
                    }
                }

                ArchiveFormat.SEVEN_Z -> {
                    SevenZOutputFile(outputFile).use { sevenZOut ->
                        sevenZOut.setContentCompression(SevenZMethod.LZMA2)
                        val buffer = ByteArray(32 * 1024)
                        fun addSevenZRecursive(file: File, relativePath: String) {
                            val entryName = if (file.isDirectory) "$relativePath/" else relativePath
                            val archiveEntry = sevenZOut.createArchiveEntry(file, entryName)
                            sevenZOut.putArchiveEntry(archiveEntry)
                            if (file.isFile) {
                                FileInputStream(file).use { fis ->
                                    var read = fis.read(buffer)
                                    while (read > 0) {
                                        sevenZOut.write(buffer, 0, read)
                                        read = fis.read(buffer)
                                    }
                                }
                            }
                            sevenZOut.closeArchiveEntry()
                            if (file.isDirectory) {
                                file.listFiles()?.forEach { child ->
                                    addSevenZRecursive(child, "$relativePath/${child.name}")
                                }
                            }
                        }
                        sourceFiles.forEachIndexed { idx, src ->
                            onProgress?.invoke(idx + 1, totalSources, "Compressing ${src.name}")
                            addSevenZRecursive(src, src.name)
                        }
                    }
                }

                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> {
                    val rawOut = BufferedOutputStream(FileOutputStream(outputFile))
                    val compressedOut = when (format) {
                        ArchiveFormat.TAR -> rawOut
                        ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(rawOut)
                        ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(rawOut)
                        ArchiveFormat.TAR_XZ -> XZCompressorOutputStream(rawOut)
                        else -> rawOut
                    }
                    TarArchiveOutputStream(compressedOut, "UTF-8").use { tos ->
                        tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                        fun addTarRecursive(file: File, relativePath: String) {
                            val entry = TarArchiveEntry(file, relativePath)
                            if (file.isFile && file.canExecute()) {
                                entry.mode = 493
                            }
                            tos.putArchiveEntry(entry)
                            if (file.isFile) {
                                BufferedInputStream(FileInputStream(file)).use { bis ->
                                    bis.copyTo(tos, 32 * 1024)
                                }
                            }
                            tos.closeArchiveEntry()
                            if (file.isDirectory) {
                                file.listFiles()?.forEach { child ->
                                    addTarRecursive(child, "$relativePath/${child.name}")
                                }
                            }
                        }

                        sourceFiles.forEachIndexed { idx, src ->
                            onProgress?.invoke(idx + 1, totalSources, "Compressing ${src.name}")
                            addTarRecursive(src, src.name)
                        }
                        tos.finish()
                    }
                }

                ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ -> {
                    val singleFile = sourceFiles.first()
                    require(singleFile.isFile) { "${format.displayName} compresses a single file." }
                    onProgress?.invoke(1, 1, "Compressing ${singleFile.name}")
                    val rawOut = BufferedOutputStream(FileOutputStream(outputFile))
                    val compOut = when (format) {
                        ArchiveFormat.GZIP -> GzipCompressorOutputStream(rawOut)
                        ArchiveFormat.BZIP2 -> BZip2CompressorOutputStream(rawOut)
                        ArchiveFormat.XZ -> XZCompressorOutputStream(rawOut)
                        else -> rawOut
                    }
                    compOut.use { out ->
                        BufferedInputStream(FileInputStream(singleFile)).use { input ->
                            input.copyTo(out, 32 * 1024)
                        }
                    }
                }
            }

            outputFile
        }
    }

    private fun readAllArchiveNodes(
        archiveFile: File,
        format: ArchiveFormat,
        password: String?
    ): List<RawArchiveNode> {
        val nodes = mutableListOf<RawArchiveNode>()
        when (format) {
            ArchiveFormat.ZIP -> {
                val zipFile = ZipFile(archiveFile)
                if (zipFile.isEncrypted && !password.isNullOrEmpty()) {
                    zipFile.setPassword(password.toCharArray())
                }
                for (header in zipFile.fileHeaders) {
                    nodes.add(
                        RawArchiveNode(
                            internalPath = header.fileName,
                            isDirectory = header.isDirectory,
                            uncompressedSize = header.uncompressedSize,
                            compressedSize = header.compressedSize,
                            lastModified = header.lastModifiedTimeEpoch,
                            crc32 = header.crc
                        )
                    )
                }
            }
            ArchiveFormat.SEVEN_Z -> {
                SevenZFile(archiveFile).use { sevenZ ->
                    for (entry in sevenZ.entries) {
                        nodes.add(
                            RawArchiveNode(
                                internalPath = entry.name,
                                isDirectory = entry.isDirectory,
                                uncompressedSize = entry.size,
                                compressedSize = null,
                                lastModified = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else archiveFile.lastModified(),
                                crc32 = if (entry.hasCrc) entry.crcValue else null
                            )
                        )
                    }
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> {
                openTarInputStream(archiveFile, format).use { tis ->
                    var entry: TarArchiveEntry? = tis.nextTarEntry
                    while (entry != null) {
                        nodes.add(
                            RawArchiveNode(
                                internalPath = entry.name,
                                isDirectory = entry.isDirectory,
                                uncompressedSize = entry.size.coerceAtLeast(0L),
                                compressedSize = null,
                                lastModified = entry.lastModifiedDate?.time ?: archiveFile.lastModified(),
                                crc32 = null
                            )
                        )
                        entry = tis.nextTarEntry
                    }
                }
            }
            ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ -> {
                val innerName = stripArchiveExtensions(archiveFile.name).ifEmpty { "content" }
                nodes.add(
                    RawArchiveNode(
                        internalPath = innerName,
                        isDirectory = false,
                        uncompressedSize = archiveFile.length(),
                        compressedSize = archiveFile.length(),
                        lastModified = archiveFile.lastModified(),
                        crc32 = null
                    )
                )
            }
        }
        return nodes
    }

    private fun openTarInputStream(archiveFile: File, format: ArchiveFormat): TarArchiveInputStream {
        val rawIn = BufferedInputStream(FileInputStream(archiveFile))
        val decompressedIn: InputStream = when (format) {
            ArchiveFormat.TAR -> rawIn
            ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(rawIn)
            ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(rawIn)
            ArchiveFormat.TAR_XZ -> XZCompressorInputStream(rawIn)
            else -> rawIn
        }
        return TarArchiveInputStream(decompressedIn, "UTF-8")
    }

    private fun openSingleCompressorStream(archiveFile: File, format: ArchiveFormat): InputStream {
        val rawIn = BufferedInputStream(FileInputStream(archiveFile))
        return when (format) {
            ArchiveFormat.GZIP -> GzipCompressorInputStream(rawIn)
            ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(rawIn)
            ArchiveFormat.XZ -> XZCompressorInputStream(rawIn)
            else -> rawIn
        }
    }

    private fun resolveSafeOutputFile(destRoot: File, canonicalDestRoot: String, entryName: String): File {
        val cleanEntry = entryName.trimStart('/', '\\')
        val target = File(destRoot, cleanEntry)
        val canonicalTarget = target.canonicalPath
        if (!canonicalTarget.startsWith(canonicalDestRoot)) {
            throw SecurityException("Blocked Zip-Slip path traversal entry: $entryName")
        }
        return target
    }

    companion object {
        fun stripArchiveExtensions(fileName: String): String {
            val lower = fileName.lowercase(Locale.ROOT)
            val compound = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz")
            for (ext in compound) {
                if (lower.endsWith(ext)) {
                    return fileName.dropLast(ext.length)
                }
            }
            return fileName.substringBeforeLast('.')
        }
    }
}
