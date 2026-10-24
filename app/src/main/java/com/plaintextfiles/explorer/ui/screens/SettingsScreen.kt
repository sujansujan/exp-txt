package com.plaintextfiles.explorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintextfiles.explorer.model.AppSettings
import com.plaintextfiles.explorer.model.DualPaneMode
import com.plaintextfiles.explorer.model.FontScalePreset
import com.plaintextfiles.explorer.model.RowDensity
import com.plaintextfiles.explorer.model.ThemeMode
import com.plaintextfiles.explorer.ui.ExplorerViewModel
import com.plaintextfiles.explorer.ui.components.AsciiDivider
import com.plaintextfiles.explorer.ui.components.AsciiFramedSection
import com.plaintextfiles.explorer.ui.components.PlainTextActionLink
import com.plaintextfiles.explorer.ui.theme.LocalPlainTextPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    pinnedCount: Int,
    trashCount: Int,
    viewModel: ExplorerViewModel
) {
    val palette = LocalPlainTextPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.backgroundDark)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlainTextActionLink(
                label = "< BACK",
                accentColor = palette.cyan,
                onClick = { viewModel.closeSettingsScreen() }
            )
            Text(
                text = "SETTINGS",
                color = palette.purple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                text = "v1.9",
                color = palette.comment,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
        AsciiDivider(char = '=')

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Theme & Appearance
            AsciiFramedSection(
                title = "THEME & APPEARANCE",
                titleColor = palette.purple
            ) {
                SettingLabel("Color Theme (Follow System / Dracula Dark / Alucard Light)")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        PlainTextActionLink(
                            label = mode.displayName,
                            isActive = settings.themeMode == mode,
                            onClick = {
                                viewModel.updateSettings { it.copy(themeMode = mode) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                SettingLabel("Row Spacing Density (Save Vertical Space)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RowDensity.entries.forEach { density ->
                        PlainTextActionLink(
                            label = density.label,
                            isActive = settings.rowDensity == density,
                            onClick = {
                                viewModel.updateSettings { it.copy(rowDensity = density) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                SettingLabel("Font Size Scale")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FontScalePreset.entries.forEach { scale ->
                        PlainTextActionLink(
                            label = scale.label,
                            isActive = settings.fontScale == scale,
                            onClick = {
                                viewModel.updateSettings { it.copy(fontScale = scale) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Show Format Icons ([DIR], [ARC], [AUD]...)",
                    checked = settings.showFileIcons,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(showFileIcons = !s.showFileIcons) }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                SettingToggleRow(
                    title = "Show Permissions & Date Subtitle on Rows",
                    checked = settings.showPermissionSubtitle,
                    onToggle = {
                        viewModel.updateSettings { s ->
                            s.copy(showPermissionSubtitle = !s.showPermissionSubtitle)
                        }
                    }
                )
            }

            // 2. Dual-Pane, Global Sort & Filter
            AsciiFramedSection(
                title = "DUAL-PANE & GLOBAL SORT / FILTER",
                titleColor = palette.cyan
            ) {
                SettingLabel("Default Pane Layout")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DualPaneMode.entries.forEach { mode ->
                        PlainTextActionLink(
                            label = "${mode.shortLabel} (${mode.description})",
                            isActive = settings.dualPaneMode == mode,
                            onClick = {
                                viewModel.updateSettings { it.copy(dualPaneMode = mode) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Global Sorts, Filters & Search Across Both Panes",
                    checked = settings.globalSortAndFilter,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(globalSortAndFilter = !s.globalSortAndFilter) }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                SettingToggleRow(
                    title = "Highlight P1 vs P2 Diff Badges ([+P1], [+P2], [~NEW])",
                    checked = settings.showDiffHighlights,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(showDiffHighlights = !s.showDiffHighlights) }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                SettingToggleRow(
                    title = "Remember Last Open Folder per Pane",
                    checked = settings.rememberLastPaths,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(rememberLastPaths = !s.rememberLastPaths) }
                    }
                )
            }

            // 3. Trash Bin, Pinned Items & Sorting
            AsciiFramedSection(
                title = "TRASH BIN, PINNED ITEMS & SORTING",
                titleColor = palette.yellow
            ) {
                SettingToggleRow(
                    title = "Use Trash Bin (.exp-txt-trash) with Instant Undo",
                    checked = settings.useTrashBin,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(useTrashBin = !s.useTrashBin) }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trash Bin Items: $trashCount",
                        color = palette.comment,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlainTextActionLink(
                            label = "OPEN TRASH",
                            accentColor = palette.cyan,
                            onClick = { viewModel.openTrashInActivePane() }
                        )
                        if (trashCount > 0) {
                            PlainTextActionLink(
                                label = "EMPTY TRASH",
                                accentColor = palette.red,
                                onClick = { viewModel.emptyTrashBin() }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                AsciiDivider()
                Spacer(modifier = Modifier.height(6.dp))

                SettingToggleRow(
                    title = "Keep Global Pinned Items at Top of Every Folder",
                    checked = settings.globalPinnedAtTop,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(globalPinnedAtTop = !s.globalPinnedAtTop) }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                SettingToggleRow(
                    title = "List Folders Before Files",
                    checked = settings.foldersFirst,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(foldersFirst = !s.foldersFirst) }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                SettingToggleRow(
                    title = "Show Hidden Dotfiles (.files)",
                    checked = settings.showHidden,
                    onToggle = {
                        viewModel.updateSettings { s -> s.copy(showHidden = !s.showHidden) }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pinned Items Count: $pinnedCount",
                        color = palette.comment,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp
                    )
                    if (pinnedCount > 0) {
                        PlainTextActionLink(
                            label = "CLEAR ALL PINNED",
                            accentColor = palette.red,
                            onClick = { viewModel.clearAllPinnedItems() }
                        )
                    }
                }
            }

            // 4. Navigation & Gestures
            AsciiFramedSection(
                title = "GESTURES & VISUAL FEEDBACK",
                titleColor = palette.green
            ) {
                SettingToggleRow(
                    title = "Show Fast-Scroll ASCII Index Rail (# A-Z)",
                    checked = settings.showFastScrollRail,
                    onToggle = {
                        viewModel.updateSettings { s ->
                            s.copy(showFastScrollRail = !s.showFastScrollRail)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                SettingToggleRow(
                    title = "Swipe Row Gesture with Live Visual & Haptic Feedback",
                    checked = settings.enableRowSwipeGesture,
                    onToggle = {
                        viewModel.updateSettings { s ->
                            s.copy(enableRowSwipeGesture = !s.enableRowSwipeGesture)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
                AsciiDivider()
                Spacer(modifier = Modifier.height(4.dp))
                SettingGuideLine("• Swipe Right : Slides row & reveals green [SELECT] feedback bar")
                SettingGuideLine("• Swipe Left  : Slides row & reveals yellow [★ PIN TOP] feedback bar")
                SettingGuideLine("• Single Tap  : Open folder / archive, or open file in default Android app")
                SettingGuideLine("• Double Tap  : Haptic pulse + File Details, Permissions & SHA/MD5 Hashes")
                SettingGuideLine("• Hold (Long) : Haptic pulse + Copy, Cut, Paste, Pin, Compress, Rename, Trash")
                SettingGuideLine("• Index Rail  : Touch or slide # A-Z rail on right edge to auto-scroll live")
                SettingGuideLine("• System Back : Works on active pane only and returns to Home (~) before exit")
            }
        }
    }
}

@Composable
private fun SettingGuideLine(text: String) {
    val palette = LocalPlainTextPalette.current
    Text(
        text = text,
        color = palette.comment,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
}

@Composable
private fun SettingLabel(text: String) {
    val palette = LocalPlainTextPalette.current
    Text(
        text = text,
        color = palette.comment,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 3.dp)
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = palette.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f)
        )
        PlainTextActionLink(
            label = if (checked) "ON" else "OFF",
            isActive = checked,
            accentColor = if (checked) palette.green else palette.comment,
            onClick = onToggle
        )
    }
}
