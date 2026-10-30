package com.jotsarup.nowish.notes

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import com.jotsarup.nowish.data.BlockType
import com.jotsarup.nowish.data.Inline
import com.jotsarup.nowish.data.NoteDoc

/** Lets the toolbar reach the text box and read what's active at the cursor. */
@Stable
class NoteBodyController {
    internal var view: NoteEditText? = null
    var format by mutableStateOf(FormatState())
        internal set
    internal var caret by mutableStateOf<Pair<Int, Int>?>(null)

    fun toggle(style: Inline) { view?.toggle(style) }
    fun toggleList(type: BlockType) { view?.toggleList(type) }
    fun toggleHeading(level: Int) { view?.toggleHeading(level) }
    fun clearFormatting() { view?.clearFormatting() }

    fun focus() {
        val v = view ?: return
        v.requestFocus()
        v.setSelection(v.length())
        v.context.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
    }
}

/**
 * The note text. [html] is only read when the box is created, so wrap this in key(...) for the note.
 * Nothing is written back until the text actually changes, so opening a note never rewrites it.
 */
@Composable
fun NoteBody(
    html: String,
    ink: Color,
    shadow: Shadow?,
    controller: NoteBodyController,
    scroll: ScrollState,
    viewportHeight: Int,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnChange by rememberUpdatedState(onChange)
    var top by remember { mutableStateOf(0f) }
    val margin = with(LocalDensity.current) { 32.dp.toPx() }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { top = it.positionInParent().y },
        factory = { context ->
            NoteEditText(context).also { box ->
                box.setDoc(NoteDoc.parse(html))
                box.onHtmlChanged = { latestOnChange(it) }
                box.onFormatChanged = { controller.format = it }
                box.onCaretMoved = { caretTop, caretBottom -> controller.caret = caretTop to caretBottom }
                controller.view = box
            }
        },
        update = { box ->
            val density = box.resources.displayMetrics.density
            box.setInk(
                ink.toArgb(),
                (shadow?.blurRadius ?: 0f) / 2f,
                (shadow?.offset?.y ?: 0f) * density / 2f,
                shadow?.color?.toArgb() ?: 0,
            )
        },
        onRelease = { box -> if (controller.view === box) controller.view = null },
    )

    // The box isn't a Compose text field, so keep the cursor above the keyboard ourselves.
    LaunchedEffect(controller.caret, viewportHeight) {
        val (caretTop, caretBottom) = controller.caret ?: return@LaunchedEffect
        if (viewportHeight <= 0) return@LaunchedEffect
        withFrameNanos { } // let the box finish growing first
        val lineTop = top + caretTop
        val lineBottom = top + caretBottom
        when {
            lineBottom + margin > scroll.value + viewportHeight ->
                scroll.animateScrollTo((lineBottom + margin - viewportHeight).toInt())
            lineTop - margin < scroll.value ->
                scroll.animateScrollTo((lineTop - margin).toInt().coerceAtLeast(0))
        }
    }
}

/** A note's body for cards: keeps bullets, numbers, headings and inline styles. */
fun noteAnnotated(doc: NoteDoc): AnnotatedString = buildAnnotatedString {
    var number = 0
    doc.blocks.forEachIndexed { index, block ->
        if (index > 0) append('\n')
        number = if (block.type == BlockType.NUMBER) number + 1 else 0
        when (block.type) {
            BlockType.BULLET -> append("•  ")
            BlockType.NUMBER -> append("$number. ")
            else -> Unit
        }
        val base = when (block.type) {
            BlockType.H1 -> SpanStyle(fontSize = 1.3.em, fontWeight = FontWeight.Bold)
            BlockType.H2 -> SpanStyle(fontSize = 1.12.em, fontWeight = FontWeight.Bold)
            else -> SpanStyle()
        }
        withStyle(base) {
            for (run in block.runs) {
                withStyle(spanFor(run.styles)) { append(run.text) }
            }
        }
    }
}

private fun spanFor(styles: Set<Inline>): SpanStyle {
    val decorations = buildList {
        if (Inline.UNDERLINE in styles) add(TextDecoration.Underline)
        if (Inline.STRIKE in styles) add(TextDecoration.LineThrough)
    }
    val marked = Inline.MARK in styles
    return SpanStyle(
        fontWeight = if (Inline.BOLD in styles) FontWeight.Bold else null,
        fontStyle = if (Inline.ITALIC in styles) FontStyle.Italic else null,
        fontFamily = if (Inline.CODE in styles) FontFamily.Monospace else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        background = if (marked) Color(0xFFFFE35A) else Color.Unspecified,
        color = if (marked) Color(0xFF1B1B1F) else Color.Unspecified,
    )
}
