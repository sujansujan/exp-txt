package com.plaintextfiles.explorer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintextfiles.explorer.model.PlainFileCategory
import com.plaintextfiles.explorer.ui.theme.LocalPlainTextPalette

/**
 * Custom theme-matched geometric/ASCII icon for common file formats and folders.
 * Designed to fit the Dracula & Alucard plain-text aesthetic while giving instant visual recognition.
 */
@Composable
fun FileTypeIcon(
    category: PlainFileCategory,
    extension: String,
    isPinned: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalPlainTextPalette.current

    val iconColor = when {
        isPinned && category == PlainFileCategory.DIRECTORY -> palette.yellow
        category == PlainFileCategory.PARENT_DIR -> palette.yellow
        category == PlainFileCategory.DIRECTORY -> palette.cyan
        category == PlainFileCategory.SYMLINK -> palette.pink
        category == PlainFileCategory.ARCHIVE -> palette.orange
        category == PlainFileCategory.AUDIO -> palette.purple
        category == PlainFileCategory.VIDEO -> palette.pink
        category == PlainFileCategory.IMAGE -> palette.green
        category == PlainFileCategory.PACKAGE -> palette.orange
        category == PlainFileCategory.DOCUMENT -> palette.yellow
        category == PlainFileCategory.TEXT_CODE -> palette.green
        category == PlainFileCategory.EXECUTABLE -> palette.red
        else -> palette.comment
    }

    val shortTag = when (category) {
        PlainFileCategory.PARENT_DIR -> ".."
        PlainFileCategory.DIRECTORY -> if (isPinned) "★D" else "DIR"
        PlainFileCategory.SYMLINK -> "LNK"
        PlainFileCategory.ARCHIVE -> extension.uppercase().take(3).ifEmpty { "ARC" }
        PlainFileCategory.AUDIO -> extension.uppercase().take(3).ifEmpty { "AUD" }
        PlainFileCategory.VIDEO -> extension.uppercase().take(3).ifEmpty { "VID" }
        PlainFileCategory.IMAGE -> extension.uppercase().take(3).ifEmpty { "IMG" }
        PlainFileCategory.PACKAGE -> "APK"
        PlainFileCategory.DOCUMENT -> extension.uppercase().take(3).ifEmpty { "DOC" }
        PlainFileCategory.TEXT_CODE -> extension.uppercase().take(3).ifEmpty { "TXT" }
        PlainFileCategory.EXECUTABLE -> "EXE"
        PlainFileCategory.BINARY -> extension.uppercase().take(3).ifEmpty { "FIL" }
    }

    Box(
        modifier = modifier
            .size(width = 30.dp, height = 20.dp)
            .background(palette.backgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 1.2.dp.toPx()
            val w = size.width
            val h = size.height

            when (category) {
                PlainFileCategory.DIRECTORY, PlainFileCategory.PARENT_DIR -> {
                    // Folder tab + box outline
                    val path = Path().apply {
                        moveTo(strokePx, h * 0.25f)
                        lineTo(w * 0.42f, h * 0.25f)
                        lineTo(w * 0.52f, h * 0.10f)
                        lineTo(w - strokePx, h * 0.10f)
                        lineTo(w - strokePx, h - strokePx)
                        lineTo(strokePx, h - strokePx)
                        close()
                    }
                    drawPath(path = path, color = iconColor, style = Stroke(width = strokePx))
                }
                PlainFileCategory.ARCHIVE -> {
                    // Box with vertical zipper track on left
                    drawRect(
                        color = iconColor,
                        topLeft = Offset(strokePx, strokePx),
                        size = Size(w - strokePx * 2, h - strokePx * 2),
                        style = Stroke(width = strokePx)
                    )
                    drawLine(
                        color = iconColor,
                        start = Offset(w * 0.18f, strokePx),
                        end = Offset(w * 0.18f, h - strokePx),
                        strokeWidth = strokePx
                    )
                }
                else -> {
                    // Dog-eared file outline on right corner
                    val cut = h * 0.28f
                    val path = Path().apply {
                        moveTo(strokePx, strokePx)
                        lineTo(w - cut, strokePx)
                        lineTo(w - strokePx, cut)
                        lineTo(w - strokePx, h - strokePx)
                        lineTo(strokePx, h - strokePx)
                        close()
                    }
                    drawPath(path = path, color = iconColor, style = Stroke(width = strokePx))
                }
            }
        }

        Text(
            text = shortTag,
            color = iconColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.5.sp,
            maxLines = 1
        )
    }
}

@Composable
fun AsciiDivider(
    char: Char = '-',
    color: Color = LocalPlainTextPalette.current.comment.copy(alpha = 0.45f),
    modifier: Modifier = Modifier
) {
    val line = char.toString().repeat(160)
    Text(
        text = line,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun PlainTextActionLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    accentColor: Color? = null,
    useBrackets: Boolean = true,
    underlineWhenInactive: Boolean = false
) {
    val palette = LocalPlainTextPalette.current
    val fgColor = when {
        isActive -> palette.background
        accentColor != null -> accentColor
        else -> palette.cyan
    }
    val bgColor = if (isActive) (accentColor ?: palette.purple) else Color.Transparent
    val formattedText = if (useBrackets) "[$label]" else label

    Text(
        text = formattedText,
        color = fgColor,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
        fontSize = 11.5.sp,
        textDecoration = if (!isActive && underlineWhenInactive) TextDecoration.Underline else TextDecoration.None,
        maxLines = 1,
        modifier = modifier
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
fun AsciiFramedSection(
    title: String,
    modifier: Modifier = Modifier,
    rightHeaderText: String? = null,
    borderColor: Color = LocalPlainTextPalette.current.comment,
    titleColor: Color = LocalPlainTextPalette.current.purple,
    backgroundColor: Color = LocalPlainTextPalette.current.background,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalPlainTextPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.backgroundDark)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "+-- $title --+",
                color = titleColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!rightHeaderText.isNullOrBlank()) {
                Text(
                    text = rightHeaderText,
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    maxLines = 1
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            content = content
        )
    }
}

@Composable
fun AsciiUsageBar(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    barWidthChars: Int = 14
) {
    val palette = LocalPlainTextPalette.current
    val clamped = fraction.coerceIn(0f, 1f)
    val filled = (clamped * barWidthChars).toInt()
    val empty = (barWidthChars - filled).coerceAtLeast(0)
    val barString = "[" + "#".repeat(filled) + "-".repeat(empty) + "]"
    val barColor = when {
        clamped > 0.88f -> palette.red
        clamped > 0.72f -> palette.orange
        else -> palette.green
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = barString,
            color = barColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Text(
            text = label,
            color = palette.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
