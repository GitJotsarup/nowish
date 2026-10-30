package com.jotsarup.nowish.data

enum class BlockType { TEXT, H1, H2, BULLET, NUMBER }

enum class Inline { BOLD, ITALIC, UNDERLINE, STRIKE, MARK, CODE }

data class Run(val text: String, val styles: Set<Inline> = emptySet(), val href: String? = null)

data class Block(val type: BlockType = BlockType.TEXT, val runs: List<Run> = emptyList()) {
    val text: String get() = runs.joinToString("") { it.text }
}

/**
 * A note body as one block per line. This is what the Android editor and card previews work with.
 * On disk (and for the web app) notes stay HTML; [parse] and [toHtml] convert between the two.
 */
data class NoteDoc(val blocks: List<Block> = emptyList()) {
    val plainText: String get() = blocks.joinToString("\n") { it.text }.trim()

    fun toHtml(): String {
        if (blocks.all { it.text.isEmpty() && it.type == BlockType.TEXT }) return ""
        val out = StringBuilder()
        var open: BlockType? = null
        fun closeList() {
            when (open) {
                BlockType.BULLET -> out.append("</ul>")
                BlockType.NUMBER -> out.append("</ol>")
                else -> Unit
            }
            open = null
        }
        for (b in blocks) {
            val isList = b.type == BlockType.BULLET || b.type == BlockType.NUMBER
            if (open != b.type) closeList()
            if (isList && open == null) {
                out.append(if (b.type == BlockType.BULLET) "<ul>" else "<ol>")
                open = b.type
            }
            val inner = inlineHtml(b.runs)
            when (b.type) {
                BlockType.TEXT -> out.append("<p>").append(inner.ifEmpty { "<br>" }).append("</p>")
                BlockType.H1 -> out.append("<h2>").append(inner).append("</h2>")
                BlockType.H2 -> out.append("<h3>").append(inner).append("</h3>")
                else -> out.append("<li>").append(inner).append("</li>")
            }
        }
        closeList()
        return out.toString()
    }

    companion object {
        fun parse(html: String): NoteDoc = HtmlReader(html).read()

        private fun inlineHtml(runs: List<Run>): String = buildString {
            for (run in runs) {
                if (run.text.isEmpty()) continue
                val tags = buildList {
                    if (run.href != null) add("a")
                    if (Inline.BOLD in run.styles) add("b")
                    if (Inline.ITALIC in run.styles) add("i")
                    if (Inline.UNDERLINE in run.styles) add("u")
                    if (Inline.STRIKE in run.styles) add("s")
                    if (Inline.MARK in run.styles) add("mark")
                    if (Inline.CODE in run.styles) add("code")
                }
                tags.forEach { t ->
                    if (t == "a") append("<a href=\"").append(escape(run.href!!).replace("\"", "&quot;")).append("\">")
                    else append('<').append(t).append('>')
                }
                append(escape(run.text))
                tags.asReversed().forEach { append("</").append(it).append('>') }
            }
        }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** A small, forgiving HTML reader. It only needs to understand what notes contain. */
private class HtmlReader(private val html: String) {
    private class Frame(val tag: String, val styles: Set<Inline>, val href: String?, val size: Float?)

    private val blocks = ArrayList<Block>()
    private val frames = ArrayList<Frame>()
    private val listStack = ArrayList<BlockType>()

    private var runs = ArrayList<Run>()
    private var sizes = ArrayList<Float?>()
    private var type = BlockType.TEXT
    private var itemType: BlockType? = null // set while inside <li>
    private var inBlock = false
    private var blockHadBreak = false

    private val styles get() = frames.flatMapTo(HashSet()) { it.styles }
    private val href get() = frames.lastOrNull { it.href != null }?.href
    private val size get() = frames.lastOrNull { it.size != null }?.size

    fun read(): NoteDoc {
        if ('<' !in html) {
            // Plain text: every line is a paragraph.
            val text = HtmlEntities.decode(html).replace("\r\n", "\n")
            if (text.isEmpty()) return NoteDoc()
            return NoteDoc(text.split('\n').map { line -> Block(runs = if (line.isEmpty()) emptyList() else listOf(Run(line))) })
        }
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                addText(html.substring(i))
                break
            }
            if (lt > i) addText(html.substring(i, lt))
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt)
                i = if (end < 0) html.length else end + 3
                continue
            }
            val gt = tagEnd(lt)
            if (gt < 0) {
                addText(html.substring(lt))
                break
            }
            handleTag(html.substring(lt + 1, gt))
            i = gt + 1
        }
        flush(force = false)
        while (blocks.size > 1 && blocks.last().runs.isEmpty() && blocks.last().type == BlockType.TEXT &&
            blocks[blocks.size - 2].runs.isEmpty()
        ) {
            blocks.removeAt(blocks.lastIndex) // stray trailing blanks
        }
        return NoteDoc(blocks)
    }

    /** Finds the closing '>' of a tag, skipping over quoted attribute values. */
    private fun tagEnd(start: Int): Int {
        var quote = '\u0000'
        var i = start + 1
        while (i < html.length) {
            val c = html[i]
            when {
                quote != '\u0000' -> if (c == quote) quote = '\u0000'
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun addText(raw: String) {
        if (raw.isEmpty()) return
        // Whitespace between block tags isn't content.
        if (!inBlock && runs.isEmpty() && raw.isBlank()) return
        val text = HtmlEntities.decode(raw).replace("\u00A0", " ").replace(Regex("[\\r\\n\\t]+"), " ")
        if (text.isEmpty()) return
        val current = styles
        val link = href
        val last = runs.lastOrNull()
        if (last != null && last.styles == current && last.href == link && sizes.last() == size) {
            runs[runs.lastIndex] = last.copy(text = last.text + text)
        } else {
            runs.add(Run(text, current, link))
            sizes.add(size)
        }
    }

    private fun handleTag(rawTag: String) {
        val closing = rawTag.startsWith("/")
        val body = rawTag.removePrefix("/").trim()
        val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
        if (name.isEmpty()) return
        if (closing) closeTag(name) else openTag(name, body.drop(name.length))
    }

    private fun openTag(name: String, attrs: String) {
        when (name) {
            "br" -> {
                if (runs.isEmpty()) emit(Block(blockType(), emptyList())) else flush(force = false)
                blockHadBreak = true
                return
            }
            "p", "div", "blockquote" -> beginBlock(null)
            "h1", "h2" -> beginBlock(BlockType.H1)
            "h3", "h4", "h5", "h6" -> beginBlock(BlockType.H2)
            "ul" -> { flush(force = false); listStack.add(BlockType.BULLET); return }
            "ol" -> { flush(force = false); listStack.add(BlockType.NUMBER); return }
            "li" -> {
                flush(force = false)
                itemType = listStack.lastOrNull() ?: BlockType.BULLET
                inBlock = true
                blockHadBreak = false
                return
            }
        }
        if (name in VOID) return
        val css = styleOf(attrs)
        val added = HashSet<Inline>()
        when (name) {
            "b", "strong" -> added += Inline.BOLD
            "i", "em" -> added += Inline.ITALIC
            "u", "ins" -> added += Inline.UNDERLINE
            "s", "strike", "del" -> added += Inline.STRIKE
            "mark" -> added += Inline.MARK
            "code" -> added += Inline.CODE
        }
        added += css.styles
        val link = if (name == "a") attr(attrs, "href")?.let(HtmlEntities::decode) else null
        frames.add(Frame(name, added, link, css.size))
    }

    private fun closeTag(name: String) {
        when (name) {
            "p", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                popFrame(name)
                endBlock()
                return
            }
            "li" -> {
                endBlock()
                itemType = null
                return
            }
            "ul", "ol" -> {
                flush(force = false)
                listStack.removeLastOrNull()
                return
            }
        }
        popFrame(name)
    }

    private fun popFrame(name: String) {
        val index = frames.indexOfLast { it.tag == name }
        if (index >= 0) while (frames.size > index) frames.removeAt(frames.lastIndex)
    }

    private fun beginBlock(heading: BlockType?) {
        // A <p> inside <li> belongs to the item.
        if (itemType == null) {
            flush(force = false)
            type = heading ?: BlockType.TEXT
            inBlock = true
            blockHadBreak = false
        }
    }

    private fun endBlock() {
        val nothingYet = runs.isEmpty() && !blockHadBreak && inBlock
        flush(force = nothingYet)
        type = BlockType.TEXT
        inBlock = itemType != null
        blockHadBreak = false
    }

    private fun blockType() = itemType ?: type

    private fun flush(force: Boolean) {
        if (runs.isEmpty() && !force) return
        var t = blockType()
        var finalRuns: List<Run> = runs
        if (t == BlockType.TEXT) {
            // The old editor stored headings as big text rather than as heading tags.
            val visible = runs.indices.filter { runs[it].text.isNotBlank() }
            val smallest = visible.mapNotNull { sizes[it] }.minOrNull()
            if (visible.isNotEmpty() && visible.all { sizes[it] != null } && smallest != null) {
                if (smallest >= 1.4f) t = BlockType.H1 else if (smallest >= 1.1f) t = BlockType.H2
            }
        }
        if (t == BlockType.H1 || t == BlockType.H2) {
            finalRuns = finalRuns.map { it.copy(styles = it.styles - Inline.BOLD) } // headings are already bold
        }
        emit(Block(t, finalRuns.toList()))
        runs = ArrayList()
        sizes = ArrayList()
    }

    private fun emit(block: Block) {
        blocks.add(block)
    }

    private class Css(val styles: Set<Inline>, val size: Float?)

    private fun styleOf(attrs: String): Css {
        val style = attr(attrs, "style")?.lowercase() ?: return Css(emptySet(), null)
        val out = HashSet<Inline>()
        var size: Float? = null
        for (declaration in style.split(';')) {
            val key = declaration.substringBefore(':', "").trim()
            val value = declaration.substringAfter(':', "").trim()
            when (key) {
                "font-weight" -> {
                    val number = value.toIntOrNull()
                    if (value == "bold" || value == "bolder" || (number != null && number >= 600)) out += Inline.BOLD
                }
                "font-style" -> if (value == "italic" || value == "oblique") out += Inline.ITALIC
                "text-decoration", "text-decoration-line" -> {
                    if ("underline" in value) out += Inline.UNDERLINE
                    if ("line-through" in value) out += Inline.STRIKE
                }
                "background", "background-color" ->
                    if (value.isNotEmpty() && value != "transparent" && value != "none" && value != "inherit") out += Inline.MARK
                "font-size" -> size = when {
                    value.endsWith("em") -> value.removeSuffix("em").removeSuffix("r").toFloatOrNull()
                    value.endsWith("%") -> value.removeSuffix("%").toFloatOrNull()?.div(100f)
                    else -> null
                }
            }
        }
        return Css(out, size)
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("(?i)\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").find(attrs)?.let { m ->
            m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }
        }

    private companion object {
        val VOID = setOf("img", "hr", "input", "meta", "link", "col", "wbr", "area", "base", "embed", "source", "track")
    }
}
