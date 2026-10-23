package com.plaintextfiles.explorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.plaintextfiles.explorer.data.FileSystemRepository
import com.plaintextfiles.explorer.model.ArchiveFormat
import com.plaintextfiles.explorer.model.ClipboardMode
import com.plaintextfiles.explorer.model.FileDetailedInfo
import com.plaintextfiles.explorer.model.FileEntry
import com.plaintextfiles.explorer.model.PaneId
import com.plaintextfiles.explorer.ui.ExplorerViewModel
import com.plaintextfiles.explorer.ui.PlainDialogState
import com.plaintextfiles.explorer.ui.components.AsciiDivider
import com.plaintextfiles.explorer.ui.components.AsciiFramedSection
import com.plaintextfiles.explorer.ui.components.PlainTextActionLink
import com.plaintextfiles.explorer.ui.theme.LocalPlainTextPalette
import java.io.File

/**
 * Long-Press ASCII File Details & Action Modal.
 * Mirrors https://plaintextsports.com/ ASCII box aesthetic (`+---+`, `|`, monospace key-value table)
 * and provides all file/folder/archive operations in plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LongPressFileDetailsModal(
    paneId: PaneId,
    entry: FileEntry,
    details: FileDetailedInfo?,
    isLoading: Boolean,
    viewModel: ExplorerViewModel,
    onOpenExternal: (File) -> Unit,
    onShareExternal: (File) -> Unit
) {
    val palette = LocalPlainTextPalette.current
    val clipboardManager = LocalClipboardManager.current
    val oppositePaneLabel = paneId.opposite().shortCode

    Dialog(
        onDismissRequest = { viewModel.dismissHighlightedDetails() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .background(palette.background)
                .border(2.dp, palette.purple)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "+-- [HIGHLIGHTED ITEM DETAILS & OPTIONS] --+",
                        color = palette.purple,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    PlainTextActionLink(
                        label = "X CLOSE",
                        accentColor = palette.red,
                        onClick = { viewModel.dismissHighlightedDetails() }
                    )
                }

                AsciiDivider(char = '=', color = palette.purple)
                Spacer(modifier = Modifier.height(4.dp))

                // Title & Badge
                Text(
                    text = "${entry.category.asciiBadge} ${entry.name}",
                    color = when {
                        entry.isDirectory -> palette.cyan
                        entry.isArchive -> palette.orange
                        else -> palette.green
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = entry.path,
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))
                AsciiDivider(char = '-', color = palette.comment)
                Spacer(modifier = Modifier.height(6.dp))

                // Quick Actions Section
                Text(
                    text = "ACTIONS (${paneId.label} -> $oppositePaneLabel):",
                    color = palette.yellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (entry.isDirectory) {
                        PlainTextActionLink(
                            label = "OPEN DIR",
                            accentColor = palette.cyan,
                            onClick = {
                                viewModel.dismissHighlightedDetails()
                                viewModel.onSingleTapEntry(paneId, entry, onOpenExternal)
                            }
                        )
                    } else if (entry.isArchive && !entry.isVirtualArchiveEntry) {
                        PlainTextActionLink(
                            label = "BROWSE ARCHIVE",
                            accentColor = palette.orange,
                            onClick = {
                                viewModel.dismissHighlightedDetails()
                                viewModel.loadVirtualArchive(paneId, entry.path)
                            }
                        )
                        PlainTextActionLink(
                            label = "EXTRACT...",
                            accentColor = palette.green,
                            onClick = {
                                viewModel.openExtractDialog(paneId, entry.path)
                            }
                        )
                        PlainTextActionLink(
                            label = "EXTRACT -> $oppositePaneLabel",
                            accentColor = palette.cyan,
                            onClick = {
                                val dest = viewModel.uiState.value.getPane(paneId.opposite()).currentPath
                                viewModel.executeExtractArchive(
                                    archivePath = entry.path,
                                    destinationDirPath = dest,
                                    createSubfolder = true
                                )
                            }
                        )
                        PlainTextActionLink(
                            label = "TEST ARCHIVE",
                            accentColor = palette.yellow,
                            onClick = {
                                viewModel.dismissHighlightedDetails()
                                viewModel.executeTestArchiveIntegrity(entry.path)
                            }
                        )
                    } else {
                        PlainTextActionLink(
                            label = "VIEW AS TEXT",
                            accentColor = palette.green,
                            onClick = {
                                viewModel.dismissHighlightedDetails()
                                viewModel.openTextViewerEditor(paneId, entry)
                            }
                        )
                        if (!entry.isVirtualArchiveEntry) {
                            PlainTextActionLink(
                                label = "OPEN EXTERNAL",
                                accentColor = palette.cyan,
                                onClick = {
                                    viewModel.dismissHighlightedDetails()
                                    onOpenExternal(File(entry.path))
                                }
                            )
                            PlainTextActionLink(
                                label = "SHARE",
                                accentColor = palette.purple,
                                onClick = {
                                    viewModel.dismissHighlightedDetails()
                                    onShareExternal(File(entry.path))
                                }
                            )
                        }
                    }

                    PlainTextActionLink(
                        label = if (entry.isVirtualArchiveEntry) "EXTRACT -> $oppositePaneLabel" else "COPY -> $oppositePaneLabel",
                        accentColor = palette.cyan,
                        onClick = {
                            viewModel.copyOrMoveToOppositePane(paneId, listOf(entry), move = false)
                        }
                    )

                    if (!entry.isVirtualArchiveEntry) {
                        PlainTextActionLink(
                            label = "MOVE -> $oppositePaneLabel",
                            accentColor = palette.orange,
                            onClick = {
                                viewModel.copyOrMoveToOppositePane(paneId, listOf(entry), move = true)
                            }
                        )
                        PlainTextActionLink(
                            label = "COMPRESS...",
                            accentColor = palette.pink,
                            onClick = {
                                viewModel.openCompressDialog(paneId, listOf(entry))
                            }
                        )
                        PlainTextActionLink(
                            label = "RENAME",
                            accentColor = palette.yellow,
                            onClick = {
                                viewModel.dismissHighlightedDetails()
                                viewModel.showDialog(PlainDialogState.RenameEntry(paneId, entry))
                            }
                        )
                        PlainTextActionLink(
                            label = "STAGE COPY",
                            accentColor = palette.cyan,
                            onClick = {
                                viewModel.stageToClipboard(paneId, listOf(entry), ClipboardMode.COPY)
                            }
                        )
                        PlainTextActionLink(
                            label = "STAGE CUT",
                            accentColor = palette.orange,
                            onClick = {
                                viewModel.stageToClipboard(paneId, listOf(entry), ClipboardMode.MOVE)
                            }
                        )
                        PlainTextActionLink(
                            label = "SELECT ITEM",
                            accentColor = palette.purple,
                            onClick = {
                                viewModel.toggleSelection(paneId, entry)
                                viewModel.dismissHighlightedDetails()
                            }
                        )
                        PlainTextActionLink(
                            label = "DELETE",
                            accentColor = palette.red,
                            onClick = {
                                viewModel.dismissHighlightedDetails()
                                viewModel.showDialog(PlainDialogState.DeleteConfirm(paneId, listOf(entry)))
                            }
                        )
                    } else if (entry.archiveHostPath != null && entry.archiveInternalPath != null) {
                        PlainTextActionLink(
                            label = "EXTRACT THIS ENTRY...",
                            accentColor = palette.green,
                            onClick = {
                                viewModel.openExtractDialog(
                                    paneId = paneId,
                                    archivePath = entry.archiveHostPath,
                                    selectedVirtualPaths = setOf(entry.archiveInternalPath)
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                AsciiDivider(char = '-', color = palette.comment)
                Spacer(modifier = Modifier.height(6.dp))

                // Detailed Properties Table
                Text(
                    text = "+-- [FILE METADATA & CHECKSUMS] --+",
                    color = palette.cyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (isLoading && details == null) {
                    Text(
                        text = "| Computing recursive size, permissions, and hashes (MD5 / SHA-256)...",
                        color = palette.yellow,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else if (details != null) {
                    AsciiMetadataRow("TYPE", if (entry.isDirectory) "DIRECTORY" else if (entry.isArchive) "ARCHIVE (${entry.archiveFormat?.displayName})" else "FILE")
                    AsciiMetadataRow("MIME TYPE", details.mimeType)
                    AsciiMetadataRow("SIZE", details.formattedSize)
                    if (details.recursiveFileCount != null && details.recursiveFolderCount != null) {
                        AsciiMetadataRow(
                            "CONTENTS",
                            "${details.recursiveFileCount} files, ${details.recursiveFolderCount} sub-folders"
                        )
                    }
                    AsciiMetadataRow("MODIFIED", FileSystemRepository.formatTimestamp(entry.lastModified))
                    AsciiMetadataRow("PERMISSIONS", "${details.readablePermissions} (${details.octalPermissions})")
                    AsciiMetadataRow("ACCESS FLAGS", details.ownerReadableWritableExecutable)

                    details.symlinkTarget?.let { target ->
                        AsciiMetadataRow("SYMLINK ->", target)
                    }

                    // Interactive permission bit toggles for local files
                    if (!entry.isVirtualArchiveEntry) {
                        val f = File(entry.path)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "| CHMOD TOGGLE:",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            PlainTextActionLink(
                                label = "R:${if (f.canRead()) "ON" else "OFF"}",
                                isActive = f.canRead(),
                                onClick = {
                                    viewModel.togglePermissionBit(paneId, entry, !f.canRead(), f.canWrite(), f.canExecute())
                                }
                            )
                            PlainTextActionLink(
                                label = "W:${if (f.canWrite()) "ON" else "OFF"}",
                                isActive = f.canWrite(),
                                onClick = {
                                    viewModel.togglePermissionBit(paneId, entry, f.canRead(), !f.canWrite(), f.canExecute())
                                }
                            )
                            PlainTextActionLink(
                                label = "X:${if (f.canExecute()) "ON" else "OFF"}",
                                isActive = f.canExecute(),
                                onClick = {
                                    viewModel.togglePermissionBit(paneId, entry, f.canRead(), f.canWrite(), !f.canExecute())
                                }
                            )
                        }
                    }

                    // Archive Inspector Section
                    details.archiveSummary?.let { arc ->
                        Spacer(modifier = Modifier.height(6.dp))
                        AsciiFramedSection(
                            title = "ARCHIVE STRUCTURE (${arc.formatName})",
                            rightHeaderText = "RATIO: ${arc.compressionRatioPercent}%",
                            titleColor = palette.orange
                        ) {
                            AsciiMetadataRow("TOTAL ENTRIES", "${arc.totalEntries} (${arc.totalFiles} files, ${arc.totalDirectories} dirs)")
                            AsciiMetadataRow("UNCOMPRESSED", FileSystemRepository.formatBytes(arc.totalUncompressedBytes))
                            AsciiMetadataRow("COMPRESSED", FileSystemRepository.formatBytes(arc.totalCompressedBytes))
                            AsciiMetadataRow("ENCRYPTED", if (arc.isEncrypted) "YES (Password Protected)" else "NO")
                        }
                    }

                    // Hashes Section
                    if (details.crc32Hex != null || details.md5 != null || details.sha256 != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        AsciiFramedSection(
                            title = "CRYPTOGRAPHIC CHECKSUMS",
                            rightHeaderText = "[TAP HASH TO COPY]",
                            titleColor = palette.green
                        ) {
                            details.crc32Hex?.let { crc ->
                                AsciiMetadataRow("CRC-32", crc) {
                                    clipboardManager.setText(AnnotatedString(crc))
                                }
                            }
                            details.md5?.let { md5 ->
                                AsciiMetadataRow("MD5", md5) {
                                    clipboardManager.setText(AnnotatedString(md5))
                                }
                            }
                            details.sha1?.let { sha1 ->
                                AsciiMetadataRow("SHA-1", sha1) {
                                    clipboardManager.setText(AnnotatedString(sha1))
                                }
                            }
                            details.sha256?.let { sha256 ->
                                AsciiMetadataRow("SHA-256", sha256) {
                                    clipboardManager.setText(AnnotatedString(sha256))
                                }
                            }
                        }
                    }

                    // Copy Path Button
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlainTextActionLink(
                            label = "COPY FULL PATH",
                            accentColor = palette.cyan,
                            onClick = { clipboardManager.setText(AnnotatedString(entry.path)) }
                        )
                        details.sha256?.let { sha ->
                            PlainTextActionLink(
                                label = "COPY SHA-256",
                                accentColor = palette.green,
                                onClick = { clipboardManager.setText(AnnotatedString(sha)) }
                            )
                        }
                    }

                    // Inline Plain-Text Preview if available
                    details.textPreview?.let { preview ->
                        Spacer(modifier = Modifier.height(8.dp))
                        AsciiFramedSection(
                            title = "PLAIN-TEXT HEAD PREVIEW",
                            rightHeaderText = "UTF-8",
                            titleColor = palette.yellow
                        ) {
                            Text(
                                text = preview,
                                color = palette.foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AsciiMetadataRow(
    key: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalPlainTextPalette.current
    val rowMod = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    }
    Row(modifier = rowMod) {
        Text(
            text = "| " + key.padEnd(13, ' ') + ": ",
            color = palette.comment,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = palette.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

/**
 * Plain-Text Input Field styled with ASCII brackets `[ input ]`
 */
@Composable
fun AsciiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    val palette = LocalPlainTextPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$label:",
            color = palette.cyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.backgroundDark)
                .border(1.dp, palette.currentLine)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = palette.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                cursorBrush = SolidColor(palette.pink),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Dispatcher for all Plain-Text Dialogs (Create Folder, Create File, Rename, Delete, Compress, Extract, Password, Text Editor)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivePlainDialogHost(
    dialogState: PlainDialogState,
    viewModel: ExplorerViewModel
) {
    val palette = LocalPlainTextPalette.current

    Dialog(
        onDismissRequest = { viewModel.dismissDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .background(palette.background)
                .border(2.dp, palette.cyan)
                .padding(10.dp)
        ) {
            when (dialogState) {
                is PlainDialogState.CreateFolder -> {
                    var folderName by remember { mutableStateOf("new_folder") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [CREATE NEW DIRECTORY IN ${dialogState.paneId.label}] --+",
                            color = palette.purple,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        AsciiTextField(
                            value = folderName,
                            onValueChange = { folderName = it },
                            label = "FOLDER NAME"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            PlainTextActionLink(
                                label = "CREATE DIR",
                                accentColor = palette.green,
                                onClick = { viewModel.createNewFolder(dialogState.paneId, folderName) }
                            )
                        }
                    }
                }

                is PlainDialogState.CreateTextFile -> {
                    var fileName by remember { mutableStateOf("notes.txt") }
                    var content by remember { mutableStateOf("") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [CREATE NEW PLAIN-TEXT FILE IN ${dialogState.paneId.label}] --+",
                            color = palette.purple,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        AsciiTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = "FILE NAME"
                        )
                        AsciiTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = "INITIAL CONTENT (UTF-8)",
                            singleLine = false,
                            modifier = Modifier.heightIn(min = 90.dp, max = 180.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            PlainTextActionLink(
                                label = "SAVE FILE",
                                accentColor = palette.green,
                                onClick = {
                                    viewModel.createNewPlainTextFile(dialogState.paneId, fileName, content)
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.RenameEntry -> {
                    var newName by remember { mutableStateOf(dialogState.entry.name) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [RENAME ITEM] --+",
                            color = palette.yellow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        Text(
                            text = "CURRENT: ${dialogState.entry.name}",
                            color = palette.comment,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        AsciiTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = "NEW NAME"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            PlainTextActionLink(
                                label = "RENAME",
                                accentColor = palette.green,
                                onClick = {
                                    viewModel.renameFileEntry(dialogState.paneId, dialogState.entry, newName)
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.DeleteConfirm -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [CONFIRM PERMANENT DELETION] --+",
                            color = palette.red,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider(color = palette.red)
                        Text(
                            text = "Delete ${dialogState.entries.size} item(s) permanently from disk?",
                            color = palette.foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        dialogState.entries.take(6).forEach { item ->
                            Text(
                                text = " - ${item.category.asciiBadge} ${item.name}",
                                color = palette.orange,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        if (dialogState.entries.size > 6) {
                            Text(
                                text = " ... and ${dialogState.entries.size - 6} more",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            PlainTextActionLink(
                                label = "CONFIRM DELETE",
                                accentColor = palette.red,
                                onClick = {
                                    viewModel.deleteFileEntries(dialogState.paneId, dialogState.entries)
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.CompressArchive -> {
                    var archiveBase by remember { mutableStateOf(dialogState.defaultBaseName) }
                    var selectedFormat by remember { mutableStateOf(ArchiveFormat.ZIP) }
                    var password by remember { mutableStateOf("") }
                    var outputToOpposite by remember { mutableStateOf(false) }

                    val creatableFormats = remember {
                        listOf(
                            ArchiveFormat.ZIP,
                            ArchiveFormat.TAR_GZ,
                            ArchiveFormat.TAR_XZ,
                            ArchiveFormat.TAR_BZ2,
                            ArchiveFormat.TAR,
                            ArchiveFormat.SEVEN_Z
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [COMPRESS ${dialogState.entries.size} ITEM(S) TO ARCHIVE] --+",
                            color = palette.pink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        AsciiTextField(
                            value = archiveBase,
                            onValueChange = { archiveBase = it },
                            label = "ARCHIVE BASE NAME (Output: $archiveBase.${selectedFormat.primaryExtension})"
                        )

                        Text(
                            text = "FORMAT:",
                            color = palette.cyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            creatableFormats.forEach { fmt ->
                                PlainTextActionLink(
                                    label = ".${fmt.primaryExtension.uppercase()}",
                                    isActive = selectedFormat == fmt,
                                    onClick = { selectedFormat = fmt }
                                )
                            }
                        }

                        Text(
                            text = "DESTINATION DIRECTORY:",
                            color = palette.cyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlainTextActionLink(
                                label = "CURRENT (${dialogState.paneId.shortCode})",
                                isActive = !outputToOpposite,
                                onClick = { outputToOpposite = false }
                            )
                            PlainTextActionLink(
                                label = "OPPOSITE (${dialogState.paneId.opposite().shortCode})",
                                isActive = outputToOpposite,
                                onClick = { outputToOpposite = true }
                            )
                        }
                        Text(
                            text = if (outputToOpposite) dialogState.oppositeDirPath else dialogState.currentDirPath,
                            color = palette.comment,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )

                        if (selectedFormat.supportsPassword) {
                            AsciiTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = "OPTIONAL AES-256 PASSWORD (LEAVE BLANK FOR NONE)"
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            PlainTextActionLink(
                                label = "START COMPRESSION",
                                accentColor = palette.green,
                                onClick = {
                                    val targetDir = if (outputToOpposite) {
                                        dialogState.oppositeDirPath
                                    } else {
                                        dialogState.currentDirPath
                                    }
                                    viewModel.executeCompressArchive(
                                        paneId = dialogState.paneId,
                                        entries = dialogState.entries,
                                        destinationDirPath = targetDir,
                                        archiveBaseName = archiveBase,
                                        format = selectedFormat,
                                        password = password
                                    )
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.ExtractArchive -> {
                    var extractToOpposite by remember { mutableStateOf(false) }
                    var createSubfolder by remember {
                        mutableStateOf(dialogState.selectedVirtualPaths == null)
                    }
                    var password by remember { mutableStateOf("") }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [EXTRACT ARCHIVE: ${dialogState.archiveName}] --+",
                            color = palette.green,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        if (dialogState.selectedVirtualPaths != null) {
                            Text(
                                text = "Selective Extraction: ${dialogState.selectedVirtualPaths.size} entry/entries",
                                color = palette.yellow,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = "TARGET PANE:",
                            color = palette.cyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlainTextActionLink(
                                label = "HERE (${dialogState.paneId.shortCode})",
                                isActive = !extractToOpposite,
                                onClick = { extractToOpposite = false }
                            )
                            PlainTextActionLink(
                                label = "OPPOSITE (${dialogState.paneId.opposite().shortCode})",
                                isActive = extractToOpposite,
                                onClick = { extractToOpposite = true }
                            )
                        }
                        Text(
                            text = if (extractToOpposite) dialogState.oppositeDirPath else dialogState.currentDirPath,
                            color = palette.comment,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "CREATE SUBFOLDER:",
                                color = palette.cyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            PlainTextActionLink(
                                label = if (createSubfolder) "YES" else "NO (DIRECT)",
                                isActive = createSubfolder,
                                onClick = { createSubfolder = !createSubfolder }
                            )
                        }

                        AsciiTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "PASSWORD (IF ENCRYPTED ZIP)"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            PlainTextActionLink(
                                label = "EXTRACT NOW",
                                accentColor = palette.green,
                                onClick = {
                                    val dest = if (extractToOpposite) {
                                        dialogState.oppositeDirPath
                                    } else {
                                        dialogState.currentDirPath
                                    }
                                    viewModel.executeExtractArchive(
                                        archivePath = dialogState.archivePath,
                                        destinationDirPath = dest,
                                        createSubfolder = createSubfolder,
                                        password = password,
                                        selectedVirtualPaths = dialogState.selectedVirtualPaths
                                    )
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.PasswordPrompt -> {
                    var password by remember { mutableStateOf("") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [ENCRYPTED ARCHIVE PASSWORD] --+",
                            color = palette.orange,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        Text(
                            text = "Archive: ${dialogState.archivePath.substringAfterLast('/')}",
                            color = palette.foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        AsciiTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "PASSWORD"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            PlainTextActionLink(
                                label = "UNLOCK & BROWSE",
                                accentColor = palette.green,
                                onClick = {
                                    viewModel.dismissDialog()
                                    viewModel.loadVirtualArchive(
                                        paneId = dialogState.paneId,
                                        archivePath = dialogState.archivePath,
                                        internalDir = "",
                                        password = password
                                    )
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.TextViewerEditor -> {
                    var textValue by remember { mutableStateOf(dialogState.content) }
                    val lineCount = remember(textValue) { textValue.lines().size }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 280.dp, max = 560.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "+-- [${if (dialogState.isReadOnly) "VIEW" else "EDIT"}: ${dialogState.entry.name}] --+",
                                color = palette.yellow,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "$lineCount lines",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        AsciiDivider()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(palette.backgroundDark)
                                .border(1.dp, palette.currentLine)
                                .padding(6.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (dialogState.isReadOnly) {
                                Text(
                                    text = textValue,
                                    color = palette.foreground,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            } else {
                                BasicTextField(
                                    value = textValue,
                                    onValueChange = { textValue = it },
                                    textStyle = TextStyle(
                                        color = palette.foreground,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    ),
                                    cursorBrush = SolidColor(palette.pink),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            PlainTextActionLink(
                                label = "CLOSE",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            if (!dialogState.isReadOnly) {
                                PlainTextActionLink(
                                    label = "SAVE CHANGES",
                                    accentColor = palette.green,
                                    onClick = {
                                        viewModel.saveEditedTextFile(
                                            paneId = dialogState.paneId,
                                            entry = dialogState.entry,
                                            newContent = textValue
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
