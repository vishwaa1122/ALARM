package com.vaishnava.alarm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    minFontSize: TextUnit = 10.sp,
    maxFontSize: TextUnit = (if (style.fontSize == TextUnit.Unspecified) 18.sp else style.fontSize),
    fontWeight: FontWeight? = style.fontWeight,
    fontStyle: FontStyle? = style.fontStyle,
    fontFamily: FontFamily? = style.fontFamily,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Center,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val (currentSize, setCurrentSize) = remember(text) { mutableStateOf(maxFontSize) }

    Text(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
        textAlign = textAlign,
        style = style.copy(
            fontSize = currentSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontFamily = fontFamily,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        onTextLayout = { result ->
            // If it overflows width or lines, shrink and recompose until it fits or hits minFontSize
            val tooManyLines = result.lineCount > maxLines
            if ((result.didOverflowWidth || tooManyLines) && currentSize > minFontSize) {
                val next = (currentSize.value - 1f).coerceAtLeast(minFontSize.value).sp
                if (next != currentSize) setCurrentSize(next)
            } else {
                onTextLayout?.invoke(result)
            }
        }
    )
}
