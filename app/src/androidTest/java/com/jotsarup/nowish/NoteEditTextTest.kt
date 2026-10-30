package com.jotsarup.nowish

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jotsarup.nowish.data.BlockType
import com.jotsarup.nowish.data.Inline
import com.jotsarup.nowish.data.NoteDoc
import com.jotsarup.nowish.notes.NoteEditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Typing goes through a real InputConnection, the same path a keyboard uses. */
@RunWith(AndroidJUnit4::class)
class NoteEditTextTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun editor(html: String = "", block: Session.() -> Unit) {
        instrumentation.runOnMainSync {
            val box = NoteEditText(instrumentation.targetContext)
            box.setDoc(NoteDoc.parse(html))
            box.setSelection(box.length())
            Session(box).block()
        }
    }

    private class Session(val box: NoteEditText) {
        private val ic: InputConnection = box.onCreateInputConnection(EditorInfo())!!
        val text get() = box.text.toString()
        val html get() = box.toDoc().toHtml()
        fun type(s: String) { ic.commitText(s, 1) }
        fun enter() = type("\n")
        fun backspace() { ic.deleteSurroundingText(1, 0) }
    }

    @Test fun listLoadsWithPrefixes() = editor("<ol><li>one</li><li>two</li></ol><ul><li>dot</li></ul>") {
        assertEquals("1. one\n2. two\n•  dot", text)
        assertEquals("<ol><li>one</li><li>two</li></ol><ul><li>dot</li></ul>", html)
    }

    @Test fun enterContinuesBullets() = editor("<ul><li>one</li></ul>") {
        enter()
        type("two")
        assertEquals("<ul><li>one</li><li>two</li></ul>", html)
        assertEquals(box.length(), box.selectionStart)
    }

    @Test fun enterContinuesAndCountsNumbers() = editor("<ol><li>a</li></ol>") {
        enter(); type("b"); enter(); type("c")
        assertEquals("1. a\n2. b\n3. c", text)
    }

    @Test fun enterOnEmptyItemEndsList() = editor("<ul><li>one</li></ul>") {
        enter()
        enter()
        assertEquals("•  one\n", text)
        type("plain")
        assertEquals("<ul><li>one</li></ul><p>plain</p>", html)
    }

    @Test fun enterInTheMiddleSplitsTheItem() = editor("<ol><li>hello world</li></ol>") {
        box.setSelection(text.indexOf(" world"))
        enter()
        assertEquals("1. hello\n2.  world", text)
    }

    @Test fun enterOnEmptyMiddleItemKeepsTheItemsBelow() = editor("<ul><li>a</li><li></li><li>c</li></ul>") {
        box.setSelection(text.indexOf("\n•  c"))
        enter()
        assertEquals("<ul><li>a</li></ul><p><br></p><ul><li>c</li></ul>", html)
    }

    @Test fun backspaceRemovesBulletFirstThenJoinsLines() = editor("<p>top</p><ul><li>x</li></ul>") {
        backspace() // the x
        assertEquals("top\n•  ", text)
        backspace() // the bullet
        assertEquals("top\n", text)
        backspace() // the line
        assertEquals("top", text)
    }

    @Test fun deletingANumberRenumbersTheRest() = editor("<ol><li>a</li><li>b</li><li>c</li></ol>") {
        box.setSelection(text.indexOf("b") + 1)
        backspace(); backspace()
        assertEquals("<ol><li>a</li></ol><p><br></p><ol><li>c</li></ol>", html)
        assertEquals("1. a\n\n1. c", text)
    }

    @Test fun caretCantSitInsideAPrefix() = editor("<ul><li>hello</li></ul>") {
        box.setSelection(0)
        assertEquals(3, box.selectionStart)
        box.setSelection(1)
        assertEquals(3, box.selectionStart)
    }

    @Test fun selectingAcrossPrefixIsAllowed() = editor("<ul><li>a</li><li>b</li></ul>") {
        box.setSelection(0, box.length())
        assertEquals(0, box.selectionStart)
        assertEquals(box.length(), box.selectionEnd)
    }

    @Test fun toolbarTurnsLinesIntoLists() = editor("<p>a</p><p>b</p><p>c</p>") {
        box.setSelection(0, text.indexOf("c") + 1)
        box.toggleList(BlockType.NUMBER)
        assertEquals("1. a\n2. b\n3. c", text)
        box.toggleList(BlockType.BULLET)
        assertEquals("•  a\n•  b\n•  c", text)
        box.toggleList(BlockType.BULLET)
        assertEquals("a\nb\nc", text)
    }

    @Test fun listOnEmptyNotePutsCaretAfterBullet() = editor {
        box.toggleList(BlockType.BULLET)
        type("x")
        assertEquals("<ul><li>x</li></ul>", html)
    }

    @Test fun typingDashStartsAList() = editor {
        type("- ")
        assertEquals("•  ", text)
        type("x")
        assertEquals("<ul><li>x</li></ul>", html)
    }

    @Test fun typingOneDotStartsANumberedList() = editor {
        type("1. ")
        type("x")
        assertEquals("<ol><li>x</li></ol>", html)
    }

    @Test fun dashInsideASentenceIsLeftAlone() = editor {
        type("a - b")
        assertEquals("<p>a - b</p>", html)
    }

    @Test fun boldSelectionAndToggleOff() = editor("<p>hello world</p>") {
        box.setSelection(0, 5)
        box.toggle(Inline.BOLD)
        assertEquals("<p><b>hello</b> world</p>", html)
        box.toggle(Inline.BOLD)
        assertEquals("<p>hello world</p>", html)
    }

    @Test fun boldAtCaretAppliesToWhatYouType() = editor("<p>plain </p>") {
        box.toggle(Inline.BOLD)
        type("bold")
        box.toggle(Inline.BOLD)
        type(" after")
        assertEquals("<p>plain <b>bold</b> after</p>", html)
    }

    @Test fun headingOnlyCoversItsLine() = editor("<p>title</p>") {
        box.toggleHeading(1)
        enter()
        type("body")
        assertEquals("<h2>title</h2><p>body</p>", html)
    }

    @Test fun headingAndListReplaceEachOther() = editor("<p>x</p>") {
        box.toggleHeading(1)
        box.toggleList(BlockType.BULLET)
        assertEquals("<ul><li>x</li></ul>", html)
        box.toggleHeading(2)
        assertEquals("<h3>x</h3>", html)
    }

    @Test fun openingANoteDoesntReportAChange() {
        var changes = 0
        instrumentation.runOnMainSync {
            val box = NoteEditText(instrumentation.targetContext)
            box.onHtmlChanged = { changes++ }
            box.setDoc(NoteDoc.parse("<ul><li>a</li></ul><p>b</p>"))
            box.setSelection(2)
        }
        assertEquals(0, changes)
    }

    @Test fun editsAreReported() {
        var latest = ""
        editor("<p>a</p>") {
            box.onHtmlChanged = { latest = it }
            type("b")
        }
        assertTrue(latest, latest == "<p>ab</p>")
    }
}
