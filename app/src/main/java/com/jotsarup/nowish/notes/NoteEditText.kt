package com.jotsarup.nowish.notes

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import com.jotsarup.nowish.R
import com.jotsarup.nowish.data.Block
import com.jotsarup.nowish.data.BlockType
import com.jotsarup.nowish.data.Inline
import com.jotsarup.nowish.data.NoteDoc
import com.jotsarup.nowish.data.Run

// Our own span types, so spans that keyboards add (suggestion underlines etc.) are never mistaken for formatting.
private class BoldMark : StyleSpan(Typeface.BOLD)
private class ItalicMark : StyleSpan(Typeface.ITALIC)
private class UnderlineMark : UnderlineSpan()
private class StrikeMark : StrikethroughSpan()

private class HighlightMark : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {
        tp.bgColor = 0xFFFFE35A.toInt()
        tp.color = 0xFF1B1B1F.toInt()
        tp.clearShadowLayer()
    }
}

private class CodeMark : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = Typeface.MONOSPACE }
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = Typeface.MONOSPACE
        tp.bgColor = (tp.color and 0x00FFFFFF) or 0x24000000
    }
}

private class LinkMark(val url: String) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) { tp.isUnderlineText = true }
}

private class HeadingMark(val level: Int) : MetricAffectingSpan() {
    private val scale = if (level == 1) 1.5f else 1.2f
    private fun apply(tp: TextPaint) {
        tp.textSize *= scale
        tp.typeface = Typeface.create(tp.typeface, Typeface.BOLD)
    }
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    override fun updateDrawState(tp: TextPaint) = apply(tp)
}

/** Marks the "• " or "3. " at the start of a list line. */
private class PrefixMark(val type: BlockType)

/** Lines of a list item wrap under the text, not under the bullet. */
private class ItemIndent(val rest: Int) : LeadingMarginSpan.Standard(0, rest)

data class FormatState(
    val styles: Set<Inline> = emptySet(),
    val heading: Int = 0,
    val list: BlockType? = null,
)

private const val INLINE_FLAGS = Spanned.SPAN_INCLUSIVE_INCLUSIVE

private fun markFor(style: Inline): Any = when (style) {
    Inline.BOLD -> BoldMark()
    Inline.ITALIC -> ItalicMark()
    Inline.UNDERLINE -> UnderlineMark()
    Inline.STRIKE -> StrikeMark()
    Inline.MARK -> HighlightMark()
    Inline.CODE -> CodeMark()
}

@Suppress("UNCHECKED_CAST")
private fun markClass(style: Inline): Class<Any> = when (style) {
    Inline.BOLD -> BoldMark::class.java
    Inline.ITALIC -> ItalicMark::class.java
    Inline.UNDERLINE -> UnderlineMark::class.java
    Inline.STRIKE -> StrikeMark::class.java
    Inline.MARK -> HighlightMark::class.java
    Inline.CODE -> CodeMark::class.java
} as Class<Any>

/**
 * The note body: Android's own text box, so tapping, selection and keyboards behave like every
 * other app. Lists are real text ("• ", "1. ") marked with a [PrefixMark]; after every edit
 * [tidy] keeps them correct, so the rest of the class never has to special-case them.
 */
class NoteEditText(context: Context) : EditText(context) {
    var onHtmlChanged: ((String) -> Unit)? = null
    var onFormatChanged: ((FormatState) -> Unit)? = null
    var onCaretMoved: ((top: Int, bottom: Int) -> Unit)? = null

    private var ready = false
    private var applying = false
    private var editStart = 0
    private var editBefore = 0
    private var editCount = 0

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (applying) return
            editStart = start
            editBefore = before
            editCount = count
        }

        override fun afterTextChanged(e: Editable) {
            if (applying) return
            applying = true
            try {
                val inserted = if (editCount > 0) e.subSequence(editStart, editStart + editCount).toString() else ""
                if ('\n' in inserted) {
                    continueList(e, inserted)
                } else if (inserted.endsWith(" ")) {
                    autoList(e)
                }
                tidy(e)
            } finally {
                applying = false
            }
            clampCaret()
            changed()
        }
    }

    init {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        minLines = 8
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(0f, 1.25f)
        hint = "Note"
        id = R.id.note_body
        // The note lives in the ViewModel. Android restoring the raw text after a rotation or
        // theme change would drop our list marks and corrupt the note.
        isSaveEnabled = false
        addTextChangedListener(watcher)
        ready = true
    }

    // ---------- loading and saving ----------

    fun setDoc(doc: NoteDoc) {
        applying = true
        try {
            val out = SpannableStringBuilder()
            var number = 0
            doc.blocks.forEachIndexed { index, block ->
                if (index > 0) out.append('\n')
                val lineStart = out.length
                number = if (block.type == BlockType.NUMBER) number + 1 else 0
                when (block.type) {
                    BlockType.BULLET -> appendPrefix(out, BULLET, BlockType.BULLET)
                    BlockType.NUMBER -> appendPrefix(out, "$number. ", BlockType.NUMBER)
                    else -> Unit
                }
                for (run in block.runs) {
                    val s = out.length
                    out.append(run.text)
                    run.styles.forEach { out.setSpan(markFor(it), s, out.length, INLINE_FLAGS) }
                    run.href?.let { out.setSpan(LinkMark(it), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                if (block.type == BlockType.H1 || block.type == BlockType.H2) {
                    out.setSpan(HeadingMark(if (block.type == BlockType.H1) 1 else 2), lineStart, out.length, INLINE_FLAGS)
                }
            }
            setText(out, BufferType.EDITABLE)
            tidy(text)
        } finally {
            applying = false
        }
        publishFormat()
    }

    fun toDoc(): NoteDoc {
        val e = text
        val blocks = ArrayList<Block>()
        var start = 0
        while (start <= e.length) {
            val end = lineEnd(e, start)
            val prefix = prefixAt(e, start)
            val contentStart = prefix?.let { e.getSpanEnd(it).coerceAtMost(end) } ?: start
            val heading = headingAt(e, start)?.level
            val type = when {
                prefix != null -> prefix.type
                heading == 1 -> BlockType.H1
                heading == 2 -> BlockType.H2
                else -> BlockType.TEXT
            }
            blocks.add(Block(type, runsIn(e, contentStart, end)))
            start = end + 1
        }
        return NoteDoc(blocks)
    }

    private fun runsIn(e: Editable, start: Int, end: Int): List<Run> {
        if (start >= end) return emptyList()
        val cuts = sortedSetOf(start, end)
        val marks = ArrayList<Pair<Inline, Any>>()
        for (style in Inline.entries) {
            for (span in e.getSpans(start, end, markClass(style))) {
                marks += style to span
                cuts += e.getSpanStart(span).coerceIn(start, end)
                cuts += e.getSpanEnd(span).coerceIn(start, end)
            }
        }
        val links = e.getSpans(start, end, LinkMark::class.java)
        links.forEach {
            cuts += e.getSpanStart(it).coerceIn(start, end)
            cuts += e.getSpanEnd(it).coerceIn(start, end)
        }
        val runs = ArrayList<Run>()
        val points = cuts.toList()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val styles = marks.filter { (_, m) -> e.getSpanStart(m) <= a && e.getSpanEnd(m) >= b }.mapTo(HashSet()) { it.first }
            val link = links.firstOrNull { e.getSpanStart(it) <= a && e.getSpanEnd(it) >= b }?.url
            val piece = e.substring(a, b)
            val last = runs.lastOrNull()
            if (last != null && last.styles == styles && last.href == link) {
                runs[runs.lastIndex] = last.copy(text = last.text + piece)
            } else {
                runs += Run(piece, styles, link)
            }
        }
        return runs
    }

    private fun changed() {
        publishFormat()
        reportCaret()
        onHtmlChanged?.invoke(toDoc().toHtml())
    }

    // ---------- lists ----------

    private fun appendPrefix(out: SpannableStringBuilder, prefix: String, type: BlockType) {
        val s = out.length
        out.append(prefix)
        out.setSpan(PrefixMark(type), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun insertPrefix(e: Editable, at: Int, type: BlockType) {
        val prefix = if (type == BlockType.BULLET) BULLET else "1. "
        e.insert(at, prefix)
        e.setSpan(PrefixMark(type), at, at + prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Enter in a list adds the next item; Enter on an empty item ends the list. */
    private fun continueList(e: Editable, inserted: String) {
        val lineStart = lineStart(e, editStart)
        val prefix = prefixAt(e, lineStart) ?: return
        val prefixEnd = e.getSpanEnd(prefix)
        val after = editStart + 1
        val itemEmpty = inserted == "\n" && editStart == prefixEnd && (after >= e.length || e[after] == '\n')
        if (itemEmpty) {
            e.removeSpan(prefix)
            e.delete(lineStart, editStart + 1)
            return
        }
        val newlines = (editStart until editStart + editCount).filter { e[it] == '\n' }
        newlines.asReversed().forEach { insertPrefix(e, it + 1, prefix.type) }
    }

    /** Typing "- ", "* " or "1. " at the start of a line starts a list. */
    private fun autoList(e: Editable) {
        val caret = selectionStart
        if (caret < 0) return
        val lineStart = lineStart(e, caret)
        if (prefixAt(e, lineStart) != null) return
        val typed = e.substring(lineStart, caret)
        val type = when {
            typed == "- " || typed == "* " -> BlockType.BULLET
            typed == "1. " -> BlockType.NUMBER
            else -> return
        }
        e.delete(lineStart, caret)
        insertPrefix(e, lineStart, type)
    }

    /** Runs after every edit: repairs prefixes and headings, renumbers, re-indents. */
    private fun tidy(e: Editable) {
        for (mark in e.getSpans(0, e.length, PrefixMark::class.java)) {
            val s = e.getSpanStart(mark)
            val end = e.getSpanEnd(mark)
            val body = e.substring(s, end)
            val atLineStart = s == 0 || e[s - 1] == '\n'
            val valid = if (mark.type == BlockType.BULLET) body == BULLET else NUMBER_PREFIX.matches(body)
            if (!atLineStart || !valid) {
                // Backspacing into a prefix (or merging lines) removes the whole thing.
                e.removeSpan(mark)
                e.delete(s, end)
            }
        }
        for (mark in e.getSpans(0, e.length, HeadingMark::class.java)) {
            val s = e.getSpanStart(mark)
            val lineStart = lineStart(e, s)
            val lineEnd = lineEnd(e, s)
            if (s != lineStart || e.getSpanEnd(mark) != lineEnd) e.setSpan(mark, lineStart, lineEnd, INLINE_FLAGS)
            if (e.getSpans(lineStart, lineEnd, HeadingMark::class.java).size > 1) e.removeSpan(mark)
        }

        val prefixes = e.getSpans(0, e.length, PrefixMark::class.java).sortedBy { e.getSpanStart(it) }
        var number = 0
        var expectedNext = -1
        for (mark in prefixes) {
            if (mark.type != BlockType.NUMBER) continue
            val s = e.getSpanStart(mark)
            val end = e.getSpanEnd(mark)
            number = if (s == expectedNext) number + 1 else 1
            val wanted = "$number. "
            if (e.substring(s, end) != wanted) {
                e.removeSpan(mark)
                e.replace(s, end, wanted)
                e.setSpan(mark, s, s + wanted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            expectedNext = lineEnd(e, s) + 1
        }

        e.getSpans(0, e.length, ItemIndent::class.java).forEach { e.removeSpan(it) }
        for (mark in e.getSpans(0, e.length, PrefixMark::class.java)) {
            val s = e.getSpanStart(mark)
            val rest = paint.measureText(e, s, e.getSpanEnd(mark)).toInt()
            e.setSpan(ItemIndent(rest), s, (lineEnd(e, s) + 1).coerceAtMost(e.length), Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    // ---------- toolbar actions ----------

    fun toggleList(type: BlockType) = editLines { e, lines ->
        val all = lines.all { prefixAt(e, it)?.type == type }
        for (line in lines.asReversed()) {
            prefixAt(e, line)?.let {
                val end = e.getSpanEnd(it)
                e.removeSpan(it)
                e.delete(line, end)
            }
            headingAt(e, line)?.let { e.removeSpan(it) }
            if (!all) insertPrefix(e, line, type)
        }
    }

    fun toggleHeading(level: Int) = editLines { e, lines ->
        val all = lines.all { headingAt(e, it)?.level == level }
        for (line in lines.asReversed()) {
            prefixAt(e, line)?.let {
                val end = e.getSpanEnd(it)
                e.removeSpan(it)
                e.delete(line, end)
            }
            headingAt(e, line)?.let { e.removeSpan(it) }
            if (!all) e.setSpan(HeadingMark(level), line, lineEnd(e, line), INLINE_FLAGS)
        }
    }

    private fun editLines(block: (Editable, List<Int>) -> Unit) {
        val e = text
        val from = selectionStart.coerceAtLeast(0)
        val to = selectionEnd.coerceAtLeast(from)
        val lines = ArrayList<Int>()
        var line = lineStart(e, from)
        while (true) {
            lines += line
            val end = lineEnd(e, line)
            if (end >= to || end >= e.length) break
            line = end + 1
        }
        applying = true
        try {
            block(e, lines)
            tidy(e)
        } finally {
            applying = false
        }
        clampCaret()
        changed()
    }

    fun toggle(style: Inline) {
        val e = text
        val a = selectionStart.coerceAtLeast(0)
        val b = selectionEnd.coerceAtLeast(a)
        applying = true
        try {
            if (a == b) {
                toggleAtCaret(e, a, style)
            } else {
                setStyle(e, a, b, style, !isFullyStyled(e, a, b, style))
            }
        } finally {
            applying = false
        }
        changed()
    }

    fun clearFormatting() {
        val e = text
        val a = selectionStart.coerceAtLeast(0)
        val b = selectionEnd.coerceAtLeast(a)
        val from = if (a == b) lineStart(e, a) else a
        val to = if (a == b) lineEnd(e, a) else b
        applying = true
        try {
            Inline.entries.forEach { setStyle(e, from, to, it, false) }
        } finally {
            applying = false
        }
        changed()
    }

    private fun toggleAtCaret(e: Editable, pos: Int, style: Inline) {
        if (!isActive(e, pos, style)) {
            // An empty span at the caret grows as you type.
            e.setSpan(markFor(style), pos, pos, INLINE_FLAGS)
            return
        }
        for (span in e.getSpans(pos, pos, markClass(style))) {
            val s = e.getSpanStart(span)
            val end = e.getSpanEnd(span)
            e.removeSpan(span)
            if (s < pos) e.setSpan(markFor(style), s, pos, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            if (end > pos) e.setSpan(markFor(style), pos, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
    }

    private fun setStyle(e: Editable, a: Int, b: Int, style: Inline, on: Boolean) {
        for (span in e.getSpans(a, b, markClass(style))) {
            val s = e.getSpanStart(span)
            val end = e.getSpanEnd(span)
            e.removeSpan(span)
            if (s < a) e.setSpan(markFor(style), s, a, INLINE_FLAGS)
            if (end > b) e.setSpan(markFor(style), b, end, INLINE_FLAGS)
        }
        if (on) e.setSpan(markFor(style), a, b, INLINE_FLAGS)
    }

    private fun isFullyStyled(e: Editable, a: Int, b: Int, style: Inline): Boolean {
        val spans = e.getSpans(a, b, markClass(style))
        val prefixes = e.getSpans(a, b, PrefixMark::class.java)
        for (i in a until b) {
            if (e[i] == '\n') continue
            if (prefixes.any { i >= e.getSpanStart(it) && i < e.getSpanEnd(it) }) continue
            if (spans.none { i >= e.getSpanStart(it) && i < e.getSpanEnd(it) }) return false
        }
        return true
    }

    private fun isActive(e: Editable, pos: Int, style: Inline): Boolean =
        e.getSpans(pos, pos, markClass(style)).any { span ->
            val s = e.getSpanStart(span)
            val end = e.getSpanEnd(span)
            val flags = e.getSpanFlags(span)
            when {
                s == end -> true
                pos in (s + 1) until end -> true
                pos == end -> flags == Spanned.SPAN_INCLUSIVE_INCLUSIVE || flags == Spanned.SPAN_EXCLUSIVE_INCLUSIVE
                else -> pos == lineStart(e, pos) && (flags == Spanned.SPAN_INCLUSIVE_INCLUSIVE || flags == Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }

    // ---------- caret ----------

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready || applying) return
        val e = text ?: return
        if (selStart == selEnd) dropEmptyMarks(e, selStart)
        if (!clampCaret()) {
            publishFormat()
            reportCaret()
        }
    }

    /** Keeps the caret out of a "• " prefix. Returns true if it moved. */
    private fun clampCaret(): Boolean {
        val e = text ?: return false
        val pos = selectionStart
        if (pos < 0 || pos != selectionEnd) return false
        val prefix = prefixAt(e, lineStart(e, pos)) ?: return false
        val end = e.getSpanEnd(prefix)
        if (pos >= end) return false
        setSelection(end)
        return true
    }

    private fun dropEmptyMarks(e: Editable, caret: Int) {
        for (style in Inline.entries) {
            for (span in e.getSpans(0, e.length, markClass(style))) {
                val s = e.getSpanStart(span)
                if (s == e.getSpanEnd(span) && s != caret) e.removeSpan(span)
            }
        }
    }

    private fun publishFormat() {
        val callback = onFormatChanged ?: return
        val e = text ?: return
        val pos = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(pos)
        val line = lineStart(e, pos)
        val styles = Inline.entries.filterTo(HashSet()) {
            if (pos == end) isActive(e, pos, it) else isFullyStyled(e, pos, end, it)
        }
        callback(FormatState(styles, headingAt(e, line)?.level ?: 0, prefixAt(e, line)?.type))
    }

    private fun reportCaret() {
        val callback = onCaretMoved ?: return
        val textLayout: Layout = this.layout ?: return
        if (!hasFocus()) return
        val line = textLayout.getLineForOffset(selectionEnd.coerceAtLeast(0))
        callback(textLayout.getLineTop(line), textLayout.getLineBottom(line))
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            // Capitalise the first word after a bullet, as you would at the start of a line.
            override fun getCursorCapsMode(reqModes: Int): Int {
                val e = text
                val pos = selectionStart
                val prefix = if (pos >= 0) prefixAt(e, lineStart(e, pos)) else null
                if (prefix != null && pos == e.getSpanEnd(prefix)) return reqModes and TextUtils.CAP_MODE_SENTENCES
                return super.getCursorCapsMode(reqModes)
            }
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        // Pasted text takes on the note's look.
        super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)

    fun setInk(color: Int, shadowRadius: Float, shadowDy: Float, shadowColor: Int) {
        setTextColor(color)
        setHintTextColor((color and 0x00FFFFFF) or 0x73000000)
        highlightColor = (color and 0x00FFFFFF) or 0x40000000
        setShadowLayer(shadowRadius, 0f, shadowDy, if (shadowRadius > 0f) shadowColor else AndroidColor.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(color)
                setSize((2 * resources.displayMetrics.density).toInt(), 1)
            }
        }
    }

    private fun lineStart(e: CharSequence, offset: Int): Int = TextUtils.lastIndexOf(e, '\n', offset - 1) + 1

    private fun lineEnd(e: CharSequence, offset: Int): Int {
        val i = TextUtils.indexOf(e, '\n', offset)
        return if (i < 0) e.length else i
    }

    private fun prefixAt(e: Spanned, line: Int): PrefixMark? =
        e.getSpans(line, line + 1, PrefixMark::class.java).firstOrNull { e.getSpanStart(it) == line }

    private fun headingAt(e: Spanned, line: Int): HeadingMark? =
        e.getSpans(line, line + 1, HeadingMark::class.java).firstOrNull { e.getSpanStart(it) == line }

    private companion object {
        const val BULLET = "•  "
        val NUMBER_PREFIX = Regex("\\d+\\. ")
    }
}
