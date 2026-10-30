package com.jotsarup.nowish

import com.jotsarup.nowish.data.BlockType
import com.jotsarup.nowish.data.Inline
import com.jotsarup.nowish.data.NoteDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HTML shapes below are the ones found in real notes (old editor + web app). */
class NoteDocTest {
    private fun lines(html: String) = NoteDoc.parse(html).blocks.map { it.text }

    @Test fun decodesOldEditorEntities() {
        assertEquals(listOf("Hello, world. (ok): 2*3"), lines("<p>Hello&comma; world&period; &lpar;ok&rpar;&colon; 2&midast;3</p>"))
        assertEquals(listOf("fj & <tag> '#"), lines("<p>&fjlig; &amp; &lt;tag&gt; &#39;&#x23;</p>"))
    }

    @Test fun unknownEntitiesStayAsTyped() {
        assertEquals(listOf("&notareal; x"), lines("<p>&notareal; x</p>"))
    }

    @Test fun paragraphsBreaksAndBlanks() {
        assertEquals(listOf("a", "b"), lines("<p>a</p><p>b</p>"))
        assertEquals(listOf("a", "b"), lines("<p>a<br>b</p>"))
        assertEquals(listOf("a", "", "b"), lines("<p>a</p><p><br></p><p>b</p>"))
        assertEquals(listOf("a", "", "b"), lines("<p>a<br><br>b</p>"))
        assertEquals(listOf("a", "", "b"), lines("<p>a</p><p></p><p>b</p>"))
        assertEquals(listOf("a"), lines("<p>a<br></p>"))
        assertEquals(listOf("web", "line"), lines("<div>web</div><div>line</div>"))
    }

    @Test fun textOutsideAnyBlock() {
        assertEquals(listOf("first", "second"), lines("first<div>second</div>"))
        assertEquals(listOf("one", "two"), lines("one\ntwo"))
    }

    @Test fun lists() {
        val doc = NoteDoc.parse("<p>x</p><ol><li>one</li><li>two</li></ol><ul><li>dot</li></ul><p>y</p>")
        assertEquals(
            listOf(BlockType.TEXT, BlockType.NUMBER, BlockType.NUMBER, BlockType.BULLET, BlockType.TEXT),
            doc.blocks.map { it.type },
        )
        assertEquals(listOf("x", "one", "two", "dot", "y"), doc.blocks.map { it.text })
        assertEquals(listOf("wrapped"), lines("<ul><li><p>wrapped</p></li></ul>"))
    }

    @Test fun inlineStyles() {
        val doc = NoteDoc.parse("<p><strong>b</strong><b>b</b> <i>i</i><u>u</u><s>s</s><mark>m</mark><code>c</code></p>")
        val runs = doc.blocks.single().runs
        assertEquals("bb", runs[0].text)
        assertEquals(setOf(Inline.BOLD), runs[0].styles)
        assertTrue(runs.any { Inline.ITALIC in it.styles && it.text == "i" })
        assertTrue(runs.any { Inline.MARK in it.styles && it.text == "m" })
        assertTrue(runs.any { Inline.CODE in it.styles && it.text == "c" })
    }

    @Test fun oldEditorCssAndHeadings() {
        val big = NoteDoc.parse("<p><span style=\"font-size: 1.5em; font-weight: bold;\">Title</span></p>").blocks.single()
        assertEquals(BlockType.H1, big.type)
        assertEquals(emptySet<Inline>(), big.runs.single().styles)
        assertEquals(BlockType.H1, NoteDoc.parse("<h2>Web</h2>").blocks.single().type)
        assertEquals(BlockType.H2, NoteDoc.parse("<h3>Sub</h3>").blocks.single().type)
        val hl = NoteDoc.parse("<p><span style=\"background-color: yellow;\">h</span></p>").blocks.single()
        assertEquals(setOf(Inline.MARK), hl.runs.single().styles)
        val none = NoteDoc.parse("<p><span style=\"background-color: transparent;\">n</span></p>").blocks.single()
        assertEquals(emptySet<Inline>(), none.runs.single().styles)
    }

    @Test fun unbalancedAndHostileMarkupDoesntBreak() {
        assertEquals(listOf("ok"), lines("<p><b>ok</p>"))
        assertEquals(listOf("a < b"), lines("<p>a &lt; b</p>"))
        assertEquals(listOf("kept"), lines("<!-- c --><p>kept</p>"))
        assertEquals(listOf("x"), lines("<p><img src=\"a>b\">x</p>"))
    }

    @Test fun savesCleanHtml() {
        val doc = NoteDoc.parse("<p>a &amp; b</p><p><br></p><ul><li>1</li><li>2</li></ul><ol><li>3</li></ol><h2>H</h2>")
        assertEquals("<p>a &amp; b</p><p><br></p><ul><li>1</li><li>2</li></ul><ol><li>3</li></ol><h2>H</h2>", doc.toHtml())
    }

    @Test fun roundTripIsStable() {
        val samples = listOf(
            "<p>Sat Sri Akal&period;</p><p><br></p><p><strong>Bold</strong> then text</p>",
            "<ol><li>one</li><li>two</li></ol><p>after</p><ul><li>x</li></ul>",
            "<p>ਸੋਚੈ ਸੋਚਿ ਨ ਹੋਵਈ — <b>ਗੁਰਮੁਖੀ</b> 🙏</p>",
            "<p>a<br>b<br><br>c</p><div>d</div>",
            "<h2>Goals</h2><p><mark>Ship</mark> <i><u>it</u></i></p>",
            "<p><a href=\"https://example.com/?a=1&amp;b=2\">link</a></p>",
        )
        for (html in samples) {
            val first = NoteDoc.parse(html)
            val again = NoteDoc.parse(first.toHtml())
            assertEquals(html, first.blocks, again.blocks)
            assertEquals(html, first.toHtml(), again.toHtml())
        }
    }

    @Test fun emptyNoteIsEmpty() {
        assertEquals("", NoteDoc.parse("").toHtml())
        assertEquals("", NoteDoc.parse("<p><br></p>").toHtml())
        assertFalse(NoteDoc.parse("<p>x</p>").toHtml().isEmpty())
        assertEquals("", NoteDoc.parse("<p><br></p><p><br></p>").plainText)
    }
}
