package com.jotsarup.nowish

import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jotsarup.nowish.notes.NoteEditText
import com.jotsarup.nowish.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real app with real taps and key events, and saves screenshots for review. */
@RunWith(AndroidJUnit4::class)
class NoteFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = instrumentation.targetContext.packageName
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun shell(cmd: String): String = device.executeShellCommand(cmd)
    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(500)
        shell("screencap -p /data/local/tmp/shots/$name.png")
    }
    private fun type(text: String) {
        shell("input text " + text.replace(" ", "%s"))
        device.waitForIdle()
        Thread.sleep(300)
    }
    private fun key(code: Int) {
        shell("input keyevent $code")
        device.waitForIdle()
        Thread.sleep(200)
    }
    private fun enter() = key(66)
    private fun backspace() = key(67)
    private fun tapDesc(desc: String) {
        val node = device.wait(Until.findObject(By.desc(desc)), 5_000)
        assertNotNull("no control: $desc", node)
        node.click()
        device.waitForIdle()
        Thread.sleep(250)
    }
    private fun tapText(text: String) {
        val node = device.wait(Until.findObject(By.text(text)), 5_000)
        assertNotNull("no text: $text", node)
        node.click()
        device.waitForIdle()
        Thread.sleep(250)
    }

    private fun <T> onBox(block: (NoteEditText) -> T): T {
        var out: Result<T>? = null
        scenario.onActivity { activity -> out = runCatching { block(activity.findViewById(R.id.note_body)) } }
        return out!!.getOrThrow()
    }
    private fun bodyText() = onBox { it.text.toString() }
    private fun bodyHtml() = onBox { it.toDoc().toHtml() }
    private fun caret() = onBox { it.selectionStart }

    /** Taps a real screen position inside visual line [line]. */
    private fun tapLine(line: Int, fromRight: Int = -1, fromLeft: Int = -1) {
        val (x, y) = onBox { box ->
            val at = IntArray(2)
            box.getLocationOnScreen(at)
            val layout = box.layout
            val middle = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
            val px = if (fromLeft >= 0) fromLeft else box.width - fromRight
            (at[0] + px) to (at[1] + middle)
        }
        device.click(x, y)
        device.waitForIdle()
        Thread.sleep(350)
    }
    private fun lineEnd(line: Int) = onBox { box ->
        val layout = box.layout
        var end = layout.getLineEnd(line)
        if (end > 0 && end <= box.length() && box.text[end - 1] == '\n') end--
        end
    }
    private fun lineCount() = onBox { it.layout.lineCount }

    @Before fun start() {
        shell("mkdir -p /data/local/tmp/shots")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        tapText("Notes")
    }

    @After fun stop() {
        shell("cmd uimode night no")
        scenario.close()
    }

    private fun newNote(title: String) {
        tapDesc("New note")
        tapText("Title")
        type(title)
        val box = device.wait(Until.findObject(By.res(pkg, "note_body")), 5_000)
        val bounds: Rect = box.visibleBounds
        device.click(bounds.centerX(), bounds.top + 20)
        device.waitForIdle()
        Thread.sleep(500)
    }

    @Test fun titleThenBodyTypesIntoTheBody() {
        newNote("Focus check")
        type("in the body")
        assertEquals("in the body", bodyText())
        shot("01-title-and-body")
        device.pressBack() // keyboard
        device.pressBack() // note
    }

    @Test fun tappingTheEndOfALinePutsTheCursorThere() {
        newNote("Cursor")
        type("Short first line"); enter()
        type("Another"); enter()
        tapDesc("Bulleted list")
        type("bullet item"); enter()
        type("second bullet"); enter(); enter()
        tapDesc("Numbered list")
        type("numbered one"); enter()
        type("numbered two"); enter(); enter()
        type("a long paragraph that keeps going and going until it wraps across more than one line on the screen for sure")
        shot("02-mixed-content")
        assertEquals(
            "<p>Short first line</p><p>Another</p><ul><li>bullet item</li><li>second bullet</li></ul>" +
                "<ol><li>numbered one</li><li>numbered two</li></ol>" +
                "<p>a long paragraph that keeps going and going until it wraps across more than one line on the screen for sure</p>",
            bodyHtml(),
        )
        val lines = lineCount()
        assertTrue("paragraph should wrap", lines >= 8)
        // The bug: tapping past the end of a line jumped to the start of the next one.
        for (line in 0 until lines) {
            tapLine(line, fromRight = 12)
            assertEquals("tap past end of visual line $line", lineEnd(line), caret())
        }
        shot("03-caret-end-of-last-line")
    }

    @Test fun tappingLeftOfABulletLandsAfterTheBullet() {
        newNote("Prefix")
        tapDesc("Bulleted list")
        type("first"); enter()
        type("second")
        tapLine(1, fromLeft = 1)
        val text = bodyText()
        assertEquals(text.lastIndexOf("second"), caret())
        type("X")
        assertEquals("<ul><li>first</li><li>Xsecond</li></ul>", bodyHtml())
    }

    @Test fun realKeysBackspaceOutOfAList() {
        newNote("Backspace")
        tapDesc("Numbered list")
        type("one"); enter()
        type("two"); enter()
        assertEquals("1. one\n2. two\n3. ", bodyText())
        backspace()
        assertEquals("1. one\n2. two\n", bodyText())
        type("plain")
        assertEquals("<ol><li>one</li><li>two</li></ol><p>plain</p>", bodyHtml())
    }

    @Test fun notesSaveAndShowOnCards() {
        newNote("Saved")
        tapDesc("Bulleted list")
        type("milk"); enter()
        type("eggs"); enter(); enter()
        tapDesc("Bold")
        type("bold text")
        tapDesc("Bold")
        Thread.sleep(800) // autosave
        shot("04-editor-with-keyboard")
        device.pressBack()
        device.pressBack()
        device.wait(Until.findObject(By.text("Saved")), 5_000)
        shot("05-card-with-bullets")
        tapText("Saved")
        device.wait(Until.findObject(By.res(pkg, "note_body")), 5_000)
        assertEquals("<ul><li>milk</li><li>eggs</li></ul><p><b>bold text</b></p>", bodyHtml())
        shot("06-reopened")
        shell("cmd uimode night yes")
        Thread.sleep(1_500)
        shot("07-dark")
        device.pressBack()
    }
}
