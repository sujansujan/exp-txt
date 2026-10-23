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
import org.apache.commons.compress.archivers.ArchiveEntry
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Comprehensive Archive Engine supporting:
 * - Virtual Archive Directory Browsing (step inside ZIP, APK, JAR, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, 7Z, GZ, BZ2, XZ)
 * - Password-protected ZIP reading, extraction, and AES-256 creation (via Zip4j)
 * - Full Archive Extraction & Selective Virtual Entry Extraction with Zip-Slip protection
 * - Archive Creation in ZIP, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, and 7Z formats
 * - Archive Integrity Testing & Summary Inspection
 */
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

            // Synthesize direct children for normalizedPrefix
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
                    // Direct child of current virtual directory
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
                    // Implicit or explicit subdirectory
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

    suspend fun readVirtualEntryPreview(
        archivePath: String,
        internalPath: String,
        password: String? = null,
        maxBytes: Int = 32 * 1024
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val archiveFile = File(archivePath)
            val format = ArchiveFormat.fromFileName(archiveFile.name) ?: return@runCatching null
            val targetClean = internalPath.trim('/')

            val bytes = when (format) {
                ArchiveFormat.ZIP -> {
                    val zf = ZipFile(archiveFile)
                    if (zf.isEncrypted && !password.isNullOrEmpty()) {
                        zf.setPassword(password.toCharArray())
                    }
                    val header = zf.fileHeaders.firstOrNull { it.fileName.trim('/') == targetClean }
                        ?: return@runCatching null
                    zf.getInputStream(header).use { readCappedBytes(it, maxBytes) }
                }
                ArchiveFormat.SEVEN_Z -> {
                    SevenZFile(archiveFile).use { sz ->
                        var entry = sz.nextEntry
                        while (entry != null) {
                            if (entry.name.trim('/') == targetClean && !entry.isDirectory) {
                                val out = ByteArrayOutputStream()
                                val buf = ByteArray(4096)
                                var remaining = maxBytes
                                while (remaining > 0) {
                                    val r = sz.read(buf, 0, buf.size.coerceAtMost(remaining))
                                    if (r <= 0) break
                                    out.write(buf, 0, r)
                                    remaining -= r
                                }
                                return@use out.toByteArray()
                            }
                            entry = sz.nextEntry
                        }
                        null
                    }
                }
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> {
                    openTarInputStream(archiveFile, format).use { tis ->
                        var entry = tis.nextTarEntry
                        while (entry != null) {
                            if (entry.name.trim('/') == targetClean && !entry.isDirectory) {
                                return@use readCappedBytes(tis, maxBytes)
                            }
                            entry = tis.nextTarEntry
                        }
                        null
                    }
                }
                ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ -> {
                    openSingleCompressorStream(archiveFile, format).use { cis ->
                        readCappedBytes(cis, maxBytes)
                    }
                }
            } ?: return@runCatching null

            if (bytes.any { it == 0.toByte() }) return@runCatching "(Binary content inside archive)"
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    suspend fun extractArchive(
        archivePath: String,
        destinationDirPath: String,
        createSubfolder: Boolean = true,
        password: String? = null,
        selectedVirtualPaths: Set<String>? = null
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
                    for (header in zipFile.fileHeaders) {
                        if (!matchesFilter(header.fileName)) continue
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
        password: String? = null
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
                    for (src in sourceFiles) {
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
                        for (src in sourceFiles) {
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
                                entry.mode = 493 // 0755
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

                        for (src in sourceFiles) {
                            addTarRecursive(src, src.name)
                        }
                        tos.finish()
                    }
                }

                ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ -> {
                    val singleFile = sourceFiles.first()
                    require(singleFile.isFile) { "${format.displayName} compresses a single file. Use TAR.${format.primaryExtension.uppercase()} for folders." }
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

    suspend fun testArchiveIntegrity(
        archivePath: String,
        password: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val archiveFile = File(archivePath)
            val format = ArchiveFormat.fromFileName(archiveFile.name)
                ?: throw IllegalArgumentException("Unsupported archive format.")
            var verifiedFiles = 0
            var totalBytesRead = 0L
            val sink = ByteArray(32 * 1024)

            when (format) {
                ArchiveFormat.ZIP -> {
                    val zf = ZipFile(archiveFile)
                    if (zf.isEncrypted && !password.isNullOrEmpty()) {
                        zf.setPassword(password.toCharArray())
                    }
                    for (header in zf.fileHeaders) {
                        if (!header.isDirectory) {
                            zf.getInputStream(header).use { stream ->
                                var r = stream.read(sink)
                                while (r > 0) {
                                    totalBytesRead += r
                                    r = stream.read(sink)
                                }
                            }
                            verifiedFiles++
                        }
                    }
                }
                ArchiveFormat.SEVEN_Z -> {
                    SevenZFile(archiveFile).use { sz ->
                        var entry = sz.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                var r = sz.read(sink)
                                while (r > 0) {
                                    totalBytesRead += r
                                    r = sz.read(sink)
                                }
                                verifiedFiles++
                            }
                            entry = sz.nextEntry
                        }
                    }
                }
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> {
                    openTarInputStream(archiveFile, format).use { tis ->
                        var entry = tis.nextTarEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                var r = tis.read(sink)
                                while (r > 0) {
                                    totalBytesRead += r
                                    r = tis.read(sink)
                                }
                                verifiedFiles++
                            }
                            entry = tis.nextTarEntry
                        }
                    }
                }
                ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ -> {
                    openSingleCompressorStream(archiveFile, format).use { cis ->
                        var r = cis.read(sink)
                        while (r > 0) {
                            totalBytesRead += r
                            r = cis.read(sink)
                        }
                        verifiedFiles = 1
                    }
                }
            }

            "INTEGRITY OK: Verified $verifiedFiles files (${FileSystemRepository.formatBytes(totalBytesRead)} decompressed cleanly)."
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
                    var entry: ArchiveEntry? = tis.nextEntry
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

    private fun readCappedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = input.read(buf, 0, buf.size.coerceAtMost(remaining))
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
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
