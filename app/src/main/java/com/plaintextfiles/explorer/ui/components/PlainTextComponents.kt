package com.plaintextfiles.explorer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintextfiles.explorer.ui.theme.LocalPlainTextPalette

/**
 * Plain-text ASCII horizontal divider mirroring plaintextsports.com section breaks.
 */
@Composable
fun AsciiDivider(
    char: Char = '-',
    color: Color = LocalPlainTextPalette.current.comment,
    modifier: Modifier = Modifier
) {
    val line = char.toString().repeat(140)
    Text(
        text = line,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Clickable plain-text bracket link or underlined text link (no icons, pure text).
 */
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
        accentColor != null -> accentColor
        isActive -> palette.background
        else -> palette.cyan
    }
    val bgColor = if (isActive) (accentColor ?: palette.purple) else Color.Transparent
    val formattedText = if (useBrackets) "[$label]" else label

    Text(
        text = formattedText,
        color = fgColor,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
        textDecoration = if (!isActive && underlineWhenInactive) TextDecoration.Underline else TextDecoration.None,
        maxLines = 1,
        modifier = modifier
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp)
    )
}

/**
 * Plain-text scoreboard-style ASCII box mirroring https://plaintextsports.com/
 * Example:
 * +------------------------+
 * | PANE 1 (ACTIVE)        |
 * | /storage/emulated/0    |
 * | 24 items | 1.4 GB      |
 * +------------------------+
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AsciiScoreBox(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
    isFocused: Boolean = false,
    titleColor: Color = LocalPlainTextPalette.current.purple,
    borderColor: Color = if (isFocused) LocalPlainTextPalette.current.cyan else LocalPlainTextPalette.current.comment,
    backgroundColor: Color = LocalPlainTextPalette.current.backgroundDark,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val palette = LocalPlainTextPalette.current
    val maxLen = (listOf(title) + lines).maxOfOrNull { it.length }?.coerceIn(14, 38) ?: 18
    val horizontalBorder = "+" + "-".repeat(maxLen + 2) + "+"

    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = { onLongClick?.invoke() }
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .background(backgroundColor)
            .then(clickModifier)
            .padding(2.dp)
    ) {
        Text(
            text = horizontalBorder,
            color = borderColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "| ",
                color = borderColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 13.sp
            )
            Text(
                text = title.take(maxLen).padEnd(maxLen, ' '),
                color = titleColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1
            )
            Text(
                text = " |",
                color = borderColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 13.sp
            )
        }
        lines.forEach { rawLine ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "| ",
                    color = borderColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                )
                Text(
                    text = rawLine.take(maxLen).padEnd(maxLen, ' '),
                    color = palette.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = " |",
                    color = borderColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                )
            }
        }
        Text(
            text = horizontalBorder,
            color = borderColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1
        )
    }
}

/**
 * Full-width ASCII framed section container with `+--- TITLE ---+` header and bottom `+---+` border.
 */
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
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!rightHeaderText.isNullOrBlank()) {
                Text(
                    text = rightHeaderText,
                    color = palette.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
        AsciiDivider(char = '-', color = borderColor)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            content = content
        )
    }
}

/**
 * Pure text ASCII bar meter, e.g. `[########------------] 42%`
 */
@Composable
fun AsciiUsageBar(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    barWidthChars: Int = 18
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
