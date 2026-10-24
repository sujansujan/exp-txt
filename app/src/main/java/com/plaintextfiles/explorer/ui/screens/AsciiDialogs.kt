package com.plaintextfiles.explorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.plaintextfiles.explorer.model.SortField
import com.plaintextfiles.explorer.model.SortOrder
import com.plaintextfiles.explorer.ui.ExplorerViewModel
import com.plaintextfiles.explorer.ui.PlainDialogState
import com.plaintextfiles.explorer.ui.components.AsciiDivider
import com.plaintextfiles.explorer.ui.components.AsciiFramedSection
import com.plaintextfiles.explorer.ui.components.PlainTextActionLink
import com.plaintextfiles.explorer.ui.theme.LocalPlainTextPalette
import java.io.File
import java.util.Locale

@Composable
fun DoubleTapFileDetailsModal(
    paneId: PaneId,
    entry: FileEntry,
    details: FileDetailedInfo?,
    isLoading: Boolean,
    viewModel: ExplorerViewModel
) {
    val palette = LocalPlainTextPalette.current
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = { viewModel.dismissDetailsModal() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .background(palette.background)
                .border(1.5.dp, palette.purple)
                .padding(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "+-- [FILE DETAILS] --+",
                        color = palette.purple,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    PlainTextActionLink(
                        label = "CLOSE",
                        accentColor = palette.red,
                        onClick = { viewModel.dismissDetailsModal() }
                    )
                }

                AsciiDivider(char = '=', color = palette.purple)
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${if (entry.isPinned) "[★ PIN] " else ""}${entry.category.asciiBadge} ${entry.name}",
                    color = if (entry.isDirectory) palette.cyan else palette.green,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = entry.path,
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp
                )

                Spacer(modifier = Modifier.height(6.dp))
                AsciiDivider()
                Spacer(modifier = Modifier.height(6.dp))

                if (isLoading && details == null) {
                    Text(
                        text = "| Computing size, permissions & SHA-256 / MD5 checksums...",
                        color = palette.yellow,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else if (details != null) {
                    AsciiMetadataRow("TYPE", if (entry.isDirectory) "DIRECTORY" else if (entry.isArchive) "ARCHIVE (${entry.archiveFormat?.displayName})" else "FILE")
                    AsciiMetadataRow("MIME", details.mimeType)
                    AsciiMetadataRow("SIZE", details.formattedSize)
                    if (details.recursiveFileCount != null && details.recursiveFolderCount != null) {
                        AsciiMetadataRow(
                            "CONTAINS",
                            "${details.recursiveFileCount} files, ${details.recursiveFolderCount} folders"
                        )
                    }
                    AsciiMetadataRow("MODIFIED", FileSystemRepository.formatTimestamp(entry.lastModified))
                    AsciiMetadataRow("MODE", "${details.readablePermissions} (${details.octalPermissions})")
                    AsciiMetadataRow("FLAGS", details.ownerReadableWritableExecutable)

                    details.symlinkTarget?.let { target ->
                        AsciiMetadataRow("TARGET ->", target)
                    }

                    if (!entry.isVirtualArchiveEntry) {
                        val f = File(entry.path)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "| CHMOD:",
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

                    details.archiveSummary?.let { arc ->
                        Spacer(modifier = Modifier.height(6.dp))
                        AsciiFramedSection(
                            title = "ARCHIVE INFO (${arc.formatName})",
                            rightHeaderText = "RATIO: ${arc.compressionRatioPercent}%",
                            titleColor = palette.orange
                        ) {
                            AsciiMetadataRow("ENTRIES", "${arc.totalEntries} (${arc.totalFiles} files, ${arc.totalDirectories} dirs)")
                            AsciiMetadataRow("UNPACKED", FileSystemRepository.formatBytes(arc.totalUncompressedBytes))
                            AsciiMetadataRow("PACKED", FileSystemRepository.formatBytes(arc.totalCompressedBytes))
                            AsciiMetadataRow("ENCRYPTED", if (arc.isEncrypted) "YES" else "NO")
                        }
                    }

                    if (details.crc32Hex != null || details.md5 != null || details.sha256 != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        AsciiFramedSection(
                            title = "CHECKSUMS (TAP TO COPY)",
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

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlainTextActionLink(
                            label = "COPY PATH",
                            accentColor = palette.cyan,
                            onClick = { clipboardManager.setText(AnnotatedString(entry.path)) }
                        )
                        PlainTextActionLink(
                            label = "ACTIONS...",
                            accentColor = palette.yellow,
                            onClick = {
                                viewModel.dismissDetailsModal()
                                viewModel.onLongPressEntry(paneId, entry)
                            }
                        )
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
            .padding(vertical = 1.5.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.5.dp)
    }
    Row(modifier = rowMod) {
        Text(
            text = "| " + key.padEnd(10, ' ') + ": ",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivePlainDialogHost(
    dialogState: PlainDialogState,
    hasClipboard: Boolean,
    viewModel: ExplorerViewModel,
    onOpenExternal: (File) -> Unit,
    onShareExternal: (File) -> Unit
) {
    val palette = LocalPlainTextPalette.current
    val uiState by viewModel.uiState.collectAsState()

    Dialog(
        onDismissRequest = { viewModel.dismissDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .background(palette.background)
                .border(1.5.dp, palette.cyan)
                .padding(10.dp)
        ) {
            when (dialogState) {
                is PlainDialogState.ItemActionMenu -> {
                    val entries = dialogState.entries
                    val first = entries.firstOrNull() ?: return@Box
                    val isSingle = entries.size == 1
                    val oppositeCode = dialogState.paneId.opposite().shortCode
                    val inTrash = viewModel.fileRepo.isTrashPath(first.path)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isSingle) "+-- [${first.name.take(24)}] --+" else "+-- [${entries.size} ITEMS SELECTED] --+",
                                color = palette.purple,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            PlainTextActionLink(
                                label = "X",
                                accentColor = palette.red,
                                onClick = { viewModel.dismissDialog() }
                            )
                        }
                        AsciiDivider()

                        if (isSingle && !first.isVirtualArchiveEntry && !inTrash) {
                            MenuOperationRow(
                                label = if (first.isPinned) "☆ Unpin from Top of List" else "★ Pin to Top of Main List",
                                color = palette.yellow
                            ) {
                                viewModel.togglePinEntry(first)
                            }
                        }

                        if (isSingle && !first.isDirectory && !first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Open in Default App",
                                color = palette.green
                            ) {
                                viewModel.dismissDialog()
                                viewModel.clearSelection(dialogState.paneId)
                                onOpenExternal(File(first.path))
                            }
                        }

                        if (!first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Copy (${entries.size})",
                                color = palette.cyan
                            ) {
                                viewModel.stageToClipboard(dialogState.paneId, entries, ClipboardMode.COPY)
                            }
                            MenuOperationRow(
                                label = "Cut / Move (${entries.size})",
                                color = palette.orange
                            ) {
                                viewModel.stageToClipboard(dialogState.paneId, entries, ClipboardMode.MOVE)
                            }
                        }

                        if (hasClipboard && !first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Paste Clipboard Into ${dialogState.paneId.shortCode}",
                                color = palette.green
                            ) {
                                viewModel.pasteClipboardIntoPane(dialogState.paneId)
                            }
                        }

                        MenuOperationRow(
                            label = if (first.isVirtualArchiveEntry) "Extract to Other Pane ($oppositeCode)" else "Copy to Other Pane ($oppositeCode)",
                            color = palette.cyan
                        ) {
                            viewModel.copyOrMoveToOppositePane(dialogState.paneId, entries, move = false)
                        }

                        if (!first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Move to Other Pane ($oppositeCode)",
                                color = palette.orange
                            ) {
                                viewModel.copyOrMoveToOppositePane(dialogState.paneId, entries, move = true)
                            }
                        }

                        if (isSingle && first.isArchive && !first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Extract Archive...",
                                color = palette.green
                            ) {
                                viewModel.openExtractDialog(dialogState.paneId, first.path)
                            }
                            MenuOperationRow(
                                label = "Browse Inside Archive",
                                color = palette.orange
                            ) {
                                viewModel.dismissDialog()
                                viewModel.clearSelection(dialogState.paneId)
                                viewModel.loadVirtualArchive(dialogState.paneId, first.path)
                            }
                        }

                        if (!first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Compress to Archive (.zip, .7z, .tar...)",
                                color = palette.pink
                            ) {
                                viewModel.openCompressDialog(dialogState.paneId, entries)
                            }
                        }

                        if (isSingle && !first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Rename",
                                color = palette.yellow
                            ) {
                                viewModel.showDialog(PlainDialogState.RenameEntry(dialogState.paneId, first))
                            }
                        } else if (entries.size > 1 && !first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Batch Rename (${entries.size} items)...",
                                color = palette.yellow
                            ) {
                                viewModel.showDialog(PlainDialogState.BatchRename(dialogState.paneId, entries))
                            }
                        }

                        MenuOperationRow(
                            label = "Keep Selected (Select More Items)",
                            color = palette.purple
                        ) {
                            viewModel.dismissDialog()
                        }

                        if (isSingle) {
                            MenuOperationRow(
                                label = "File Details & Checksums (Double-Tap)",
                                color = palette.cyan
                            ) {
                                viewModel.onDoubleTapEntry(dialogState.paneId, first)
                            }
                        }

                        if (isSingle && !first.isDirectory && !first.isVirtualArchiveEntry) {
                            MenuOperationRow(
                                label = "Share File",
                                color = palette.foreground
                            ) {
                                viewModel.dismissDialog()
                                viewModel.clearSelection(dialogState.paneId)
                                onShareExternal(File(first.path))
                            }
                        }

                        if (!first.isVirtualArchiveEntry) {
                            if (uiState.settings.useTrashBin && !inTrash) {
                                MenuOperationRow(
                                    label = "Move to Trash (${entries.size}) [Instant Undo]",
                                    color = palette.orange
                                ) {
                                    viewModel.deleteFileEntries(
                                        paneId = dialogState.paneId,
                                        entries = entries,
                                        forcePermanent = false
                                    )
                                }
                            }
                            MenuOperationRow(
                                label = if (inTrash) "Delete Forever (${entries.size})" else "Delete / Trash Options (${entries.size})...",
                                color = palette.red
                            ) {
                                viewModel.showDialog(PlainDialogState.DeleteConfirm(dialogState.paneId, entries))
                            }
                        }
                    }
                }

                is PlainDialogState.ComparePanesModal -> {
                    val summary = uiState.compareSummary
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "+-- [COMPARE & SYNC: P1 <-> P2] --+",
                                color = palette.cyan,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            PlainTextActionLink(
                                label = "CLOSE",
                                accentColor = palette.red,
                                onClick = { viewModel.dismissDialog() }
                            )
                        }
                        AsciiDivider(char = '=', color = palette.cyan)

                        if (summary == null) {
                            Text(
                                text = "Comparing panes...",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                text = "P1: ${summary.paneAPath}
P2: ${summary.paneBPath}",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                            AsciiDivider()

                            AsciiMetadataRow("ONLY IN P1", "+${summary.onlyInA.size} unique item(s)  [+P1]")
                            AsciiMetadataRow("ONLY IN P2", "+${summary.onlyInB.size} unique item(s)  [+P2]")
                            AsciiMetadataRow("NEWER P1", "~${summary.newerInA.size} modified item(s) [~NEW]")
                            AsciiMetadataRow("NEWER P2", "~${summary.newerInB.size} modified item(s) [~NEW]")
                            AsciiMetadataRow("IDENTICAL", "=${summary.identicalCount} matching item(s)")

                            val previewDiffs = buildList {
                                summary.onlyInA.take(3).forEach { add("[+P1] ${it.name}" to palette.green) }
                                summary.onlyInB.take(3).forEach { add("[+P2] ${it.name}" to palette.cyan) }
                                summary.newerInA.take(2).forEach { add("[~P1] ${it.name}" to palette.orange) }
                                summary.newerInB.take(2).forEach { add("[~P2] ${it.name}" to palette.yellow) }
                            }
                            if (previewDiffs.isNotEmpty()) {
                                AsciiDivider()
                                previewDiffs.take(6).forEach { (line, col) ->
                                    Text(
                                        text = " $line",
                                        color = col,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        maxLines = 1
                                    )
                                }
                            }

                            AsciiDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Show Inline Diff Badges on Main List",
                                    color = palette.foreground,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                                PlainTextActionLink(
                                    label = if (uiState.settings.showDiffHighlights) "ON" else "OFF",
                                    isActive = uiState.settings.showDiffHighlights,
                                    accentColor = palette.green,
                                    onClick = { viewModel.toggleDiffHighlights() }
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            MenuOperationRow(
                                label = "Sync P1 -> P2 (Copy ${summary.syncAToBEntries.size} Missing / Newer)",
                                color = palette.green
                            ) {
                                viewModel.syncMissingAndNewer(PaneId.PANE_A)
                            }
                            MenuOperationRow(
                                label = "Sync P2 -> P1 (Copy ${summary.syncBToAEntries.size} Missing / Newer)",
                                color = palette.cyan
                            ) {
                                viewModel.syncMissingAndNewer(PaneId.PANE_B)
                            }
                            if (summary.onlyInA.isNotEmpty()) {
                                MenuOperationRow(
                                    label = "Select ${summary.onlyInA.size} Unique Item(s) in P1",
                                    color = palette.yellow
                                ) {
                                    viewModel.selectUniqueInPane(PaneId.PANE_A)
                                }
                            }
                            if (summary.onlyInB.isNotEmpty()) {
                                MenuOperationRow(
                                    label = "Select ${summary.onlyInB.size} Unique Item(s) in P2",
                                    color = palette.yellow
                                ) {
                                    viewModel.selectUniqueInPane(PaneId.PANE_B)
                                }
                            }
                        }
                    }
                }

                is PlainDialogState.IncomingShareModal -> {
                    var zipBase by remember { mutableStateOf("shared_bundle") }
                    val paneADir = uiState.paneA.currentPath
                    val paneBDir = uiState.paneB.currentPath
                    val countLabel = if (dialogState.items.isNotEmpty()) {
                        "${dialogState.items.size} FILE(S)"
                    } else {
                        "SHARED TEXT"
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "+-- [SHARE TO exp-txt :: $countLabel] --+",
                                color = palette.green,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.red,
                                onClick = { viewModel.dismissDialog() }
                            )
                        }
                        AsciiDivider(char = '=', color = palette.green)

                        dialogState.items.take(5).forEach { item ->
                            Text(
                                text = " • ${item.displayName} (${FileSystemRepository.formatBytes(item.sizeBytes)})",
                                color = palette.foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        if (dialogState.items.size > 5) {
                            Text(
                                text = "   ...and ${dialogState.items.size - 5} more item(s)",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp
                            )
                        }
                        if (dialogState.items.isEmpty() && !dialogState.sharedText.isNullOrBlank()) {
                            Text(
                                text = " "${dialogState.sharedText.take(120)}"",
                                color = palette.yellow,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp
                            )
                        }

                        AsciiDivider()
                        AsciiTextField(
                            value = zipBase,
                            onValueChange = { zipBase = it },
                            label = "OPTIONAL .ZIP ARCHIVE NAME"
                        )
                        Spacer(modifier = Modifier.height(2.dp))

                        MenuOperationRow(
                            label = "Save Directly to P1 (${paneADir.substringAfterLast('/').ifEmpty { "/" }})",
                            color = palette.green
                        ) {
                            viewModel.saveIncomingShare(
                                targetPaneId = PaneId.PANE_A,
                                items = dialogState.items,
                                sharedText = dialogState.sharedText,
                                compressToZip = false
                            )
                        }
                        MenuOperationRow(
                            label = "Save Directly to P2 (${paneBDir.substringAfterLast('/').ifEmpty { "/" }})",
                            color = palette.cyan
                        ) {
                            viewModel.saveIncomingShare(
                                targetPaneId = PaneId.PANE_B,
                                items = dialogState.items,
                                sharedText = dialogState.sharedText,
                                compressToZip = false
                            )
                        }
                        MenuOperationRow(
                            label = "Compress into $zipBase.zip in P1",
                            color = palette.pink
                        ) {
                            viewModel.saveIncomingShare(
                                targetPaneId = PaneId.PANE_A,
                                items = dialogState.items,
                                sharedText = dialogState.sharedText,
                                compressToZip = true,
                                zipBaseName = zipBase
                            )
                        }
                        MenuOperationRow(
                            label = "Compress into $zipBase.zip in P2",
                            color = palette.orange
                        ) {
                            viewModel.saveIncomingShare(
                                targetPaneId = PaneId.PANE_B,
                                items = dialogState.items,
                                sharedText = dialogState.sharedText,
                                compressToZip = true,
                                zipBaseName = zipBase
                            )
                        }
                    }
                }

                is PlainDialogState.SortModal -> {
                    val pane = uiState.getPane(dialogState.paneId)
                    val currentSort = pane.sortConfig
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "+-- [SORT ${dialogState.paneId.label}] --+",
                                color = palette.yellow,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            PlainTextActionLink(
                                label = "CLOSE",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                        }
                        AsciiDivider()
                        Text(
                            text = "Pinned items are always kept at the top of the main list.",
                            color = palette.comment,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        )
                        SortField.entries.forEach { field ->
                            val isSelected = currentSort.field == field
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) palette.selection else Color.Transparent)
                                    .clickable {
                                        val nextOrder = if (isSelected) currentSort.order.toggle() else currentSort.order
                                        viewModel.updateSortConfig(dialogState.paneId, field, nextOrder)
                                        viewModel.dismissDialog()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${if (isSelected) "* " else "  "}${field.label}",
                                    color = if (isSelected) palette.yellow else palette.foreground,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                                if (isSelected) {
                                    Text(
                                        text = "${currentSort.order.label} (${currentSort.order.symbol})",
                                        color = palette.cyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                is PlainDialogState.BatchRename -> {
                    var prefix by remember { mutableStateOf("item_") }
                    var startNumText by remember { mutableStateOf("1") }
                    val startNum = startNumText.toIntOrNull() ?: 1

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [BATCH RENAME ${dialogState.entries.size} ITEMS] --+",
                            color = palette.yellow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        AsciiTextField(
                            value = prefix,
                            onValueChange = { prefix = it },
                            label = "NAME PREFIX"
                        )
                        AsciiTextField(
                            value = startNumText,
                            onValueChange = { startNumText = it.filter { c -> c.isDigit() } },
                            label = "START NUMBER"
                        )
                        Text(
                            text = "Preview:",
                            color = palette.comment,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        dialogState.entries.take(3).forEachIndexed { idx, item ->
                            val numStr = String.format(Locale.US, "%02d", startNum + idx)
                            val ext = if (!item.isDirectory && item.extension.isNotEmpty()) ".${item.extension}" else ""
                            Text(
                                text = " ${item.name} -> ${prefix.ifEmpty { "item_" }}$numStr$ext",
                                color = palette.green,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp
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
                                label = "RENAME ALL",
                                accentColor = palette.green,
                                onClick = {
                                    viewModel.executeBatchRename(
                                        paneId = dialogState.paneId,
                                        entries = dialogState.entries,
                                        prefix = prefix,
                                        startNumber = startNum
                                    )
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.CreateFolder -> {
                    var folderName by remember { mutableStateOf("new_folder") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [NEW FOLDER IN ${dialogState.paneId.label}] --+",
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
                                label = "CREATE",
                                accentColor = palette.green,
                                onClick = { viewModel.createNewFolder(dialogState.paneId, folderName) }
                            )
                        }
                    }
                }

                is PlainDialogState.CreateFile -> {
                    var fileName by remember { mutableStateOf("new_file.txt") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [NEW FILE IN ${dialogState.paneId.label}] --+",
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
                                label = "CREATE",
                                accentColor = palette.green,
                                onClick = { viewModel.createNewFile(dialogState.paneId, fileName) }
                            )
                        }
                    }
                }

                is PlainDialogState.RenameEntry -> {
                    var newName by remember { mutableStateOf(dialogState.entry.name) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [RENAME] --+",
                            color = palette.yellow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
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
                                label = "SAVE",
                                accentColor = palette.green,
                                onClick = {
                                    viewModel.renameFileEntry(dialogState.paneId, dialogState.entry, newName)
                                }
                            )
                        }
                    }
                }

                is PlainDialogState.DeleteConfirm -> {
                    val panePath = uiState.getPane(dialogState.paneId).currentPath
                    val alreadyInTrash = viewModel.fileRepo.isTrashPath(panePath)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+-- [DELETE / TRASH ${dialogState.entries.size} ITEM(S)] --+",
                            color = palette.red,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider(color = palette.red)
                        dialogState.entries.take(5).forEach { item ->
                            Text(
                                text = " - ${item.name}",
                                color = palette.orange,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlainTextActionLink(
                                label = "CANCEL",
                                accentColor = palette.comment,
                                onClick = { viewModel.dismissDialog() }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!alreadyInTrash) {
                                    PlainTextActionLink(
                                        label = "MOVE TO TRASH",
                                        accentColor = palette.yellow,
                                        onClick = {
                                            viewModel.deleteFileEntries(
                                                paneId = dialogState.paneId,
                                                entries = dialogState.entries,
                                                forcePermanent = false
                                            )
                                        }
                                    )
                                }
                                PlainTextActionLink(
                                    label = "DELETE FOREVER",
                                    accentColor = palette.red,
                                    onClick = {
                                        viewModel.deleteFileEntries(
                                            paneId = dialogState.paneId,
                                            entries = dialogState.entries,
                                            forcePermanent = true
                                        )
                                    }
                                )
                            }
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
                            text = "+-- [COMPRESS ${dialogState.entries.size} ITEM(S)] --+",
                            color = palette.pink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        AsciiTextField(
                            value = archiveBase,
                            onValueChange = { archiveBase = it },
                            label = "OUTPUT: $archiveBase.${selectedFormat.primaryExtension}"
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlainTextActionLink(
                                label = "HERE (${dialogState.paneId.shortCode})",
                                isActive = !outputToOpposite,
                                onClick = { outputToOpposite = false }
                            )
                            PlainTextActionLink(
                                label = "OTHER PANE (${dialogState.paneId.opposite().shortCode})",
                                isActive = outputToOpposite,
                                onClick = { outputToOpposite = true }
                            )
                        }
                        if (selectedFormat.supportsPassword) {
                            AsciiTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = "OPTIONAL AES-256 PASSWORD"
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
                                label = "COMPRESS",
                                accentColor = palette.green,
                                onClick = {
                                    val dest = if (outputToOpposite) dialogState.oppositeDirPath else dialogState.currentDirPath
                                    viewModel.executeCompressArchive(
                                        paneId = dialogState.paneId,
                                        entries = dialogState.entries,
                                        destinationDirPath = dest,
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
                            text = "+-- [EXTRACT: ${dialogState.archiveName}] --+",
                            color = palette.green,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlainTextActionLink(
                                label = "HERE (${dialogState.paneId.shortCode})",
                                isActive = !extractToOpposite,
                                onClick = { extractToOpposite = false }
                            )
                            PlainTextActionLink(
                                label = "OTHER PANE (${dialogState.paneId.opposite().shortCode})",
                                isActive = extractToOpposite,
                                onClick = { extractToOpposite = true }
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Subfolder:",
                                color = palette.comment,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            PlainTextActionLink(
                                label = if (createSubfolder) "YES" else "NO",
                                isActive = createSubfolder,
                                onClick = { createSubfolder = !createSubfolder }
                            )
                        }
                        AsciiTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "PASSWORD (IF ENCRYPTED)"
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
                                label = "EXTRACT",
                                accentColor = palette.green,
                                onClick = {
                                    val dest = if (extractToOpposite) dialogState.oppositeDirPath else dialogState.currentDirPath
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
                            text = "+-- [ARCHIVE PASSWORD] --+",
                            color = palette.orange,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        AsciiDivider()
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
                                label = "UNLOCK",
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
            }
        }
    }
}

@Composable
private fun MenuOperationRow(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.backgroundDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "> $label",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}
