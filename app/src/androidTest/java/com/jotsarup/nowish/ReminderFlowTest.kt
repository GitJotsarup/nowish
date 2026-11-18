package com.jotsarup.nowish

import android.text.format.DateFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Sets reminders through the real date and time dialogs, then follows each one all the way
 * through: what got saved, whether Android registered the alarm, and whether the notification shows.
 */
@RunWith(AndroidJUnit4::class)
class ReminderFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val pkg = context.packageName
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val report = StringBuilder()

    private fun note(line: String) { report.appendLine(line) }
    private fun shell(cmd: String): String = device.executeShellCommand(cmd)
    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(400)
        shell("screencap -p /data/local/tmp/shots/$name.png")
    }
    private fun failWithScreen(message: String): Nothing {
        val tag = "FAIL-" + message.replace(Regex("[^A-Za-z0-9]+"), "_").take(40)
        shell("screencap -p /data/local/tmp/shots/$tag.png")
        shell("uiautomator dump /data/local/tmp/shots/$tag.xml")
        throw AssertionError(message + "\nREPORT:\n" + report)
    }
    private fun check(ok: Boolean, what: String) { if (!ok) failWithScreen(what) }

    private fun pause(ms: Long = 400) { device.waitForIdle(); Thread.sleep(ms) }
    private fun clickDesc(desc: String): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), 4_000) ?: return false
        node.click(); pause(); return true
    }
    private fun clickText(text: String): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), 4_000) ?: return false
        node.click(); pause(); return true
    }
    private fun type(text: String) { shell("input text " + text.replace(" ", "%s")); pause(600) }

    /** Closes the add panel (keyboard first, then the panel) until the + button is reachable. */
    private fun closeSheet() {
        repeat(4) {
            if (!device.hasObject(By.desc("Add")) && device.hasObject(By.desc("Add task"))) return
            device.pressBack()
            pause(600)
        }
    }

    private fun tasks(): List<Task> = runBlocking { context.nowish.database.dao().openTasks() }
    private fun task(title: String) = tasks().firstOrNull { it.title == title }

    @Before fun start() {
        // CI emulators' launcher sometimes freezes; tap "Wait" whenever its popup shows up.
        device.registerWatcher("system-not-responding") {
            val wait = device.findObject(androidx.test.uiautomator.UiSelector().text("Wait"))
            wait.exists() && wait.click()
        }
        shell("mkdir -p /data/local/tmp/shots")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        repeat(6) {
            if (device.wait(Until.hasObject(By.desc("Add task")), 5_000)) return
            device.findObject(By.text("Wait"))?.click()
            device.findObject(By.text("Close app"))?.click()
        }
    }

    @After fun stop() {
        device.removeWatcher("system-not-responding")
        device.pressHome()
        scenario.close()
    }

    /** Picks [time] on the Material dial: hour (and AM/PM), then minutes. */
    private fun pickTime(time: LocalTime, label: String): Boolean {
        pause(600)
        shot("$label-time-dialog")
        val is24 = DateFormat.is24HourFormat(context)
        val hourOk = if (is24) clickDesc("${time.hour} hours")
        else clickDesc("${(time.hour % 12).let { if (it == 0) 12 else it }} o'clock")
        if (!is24) clickText(if (time.hour >= 12) "PM" else "AM")
        pause(500)
        // After an hour is chosen the dial switches to minutes; make sure, then choose.
        device.findObject(By.desc("Select minutes"))?.click()
        pause(400)
        val minuteOk = clickDesc("${time.minute} minutes")
        shot("$label-time-picked")
        note("[$label] dial: 24h=$is24 hourClicked=$hourOk minuteClicked=$minuteOk")
        return clickText("Set")
    }

    private fun alarmLines(): List<String> =
        shell("dumpsys alarm").lines().filter { pkg in it && ("RTC" in it || "ELAPSED" in it || "Alarm{" in it) }

    @Test fun dateAndTimeRemindersReachTheNotificationShade() {
        // A minute mark comfortably ahead, on the 5-minute grid the dial offers.
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        var target = now.plusMinutes(3)
        target = target.plusMinutes(((5 - target.minute % 5) % 5).toLong())
        val today = LocalDate.now()
        note("now=$now target=$target zone=${java.util.TimeZone.getDefault().id} exactAllowed=${
            context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()}")

        // 1) Add box: "Pick date" (today) then "Remind me".
        check(clickDesc("Add task"), "step: clickDesc(Add task)")
        type("Date then time")
        check(clickText("Pick date"), "date chip")
        shot("a-date-dialog")
        check(clickText("Set"), "date Set")
        check(clickText("Remind me"), "time chip")
        check(pickTime(target.toLocalTime(), "a"), "time Set")
        shot("a-sheet-before-add")
        check(clickDesc("Add"), "add")
        pause(800)

        // 2) Add box: "Remind me" first, then "Pick date".
        type("Time then date")
        check(clickText("Remind me"), "step: clickText(Remind me)")
        check(pickTime(target.toLocalTime(), "b"), "step: pickTime(target.toLocalTime(), b)")
        clickText("Pick date")
        clickText("Set")
        shot("b-sheet-before-add")
        clickDesc("Add")
        pause(800)
        closeSheet()

        // 3) Long-press menu on an existing task.
        check(clickDesc("Add task"), "step: clickDesc(Add task)")
        type("Menu reminder")
        clickDesc("Add")
        pause(800)
        closeSheet()
        shot("c-list")
        val row = device.wait(Until.findObject(By.text("Menu reminder")), 4_000)
        check(row != null, "row")
        row.longClick()
        pause(800)
        shot("c-options")
        clickText("Pick date")
        clickText("Set")
        check(clickText("Add a reminder"), "reminder chip")
        check(pickTime(target.toLocalTime(), "c"), "step: pickTime(target.toLocalTime(), c)")
        shot("c-options-after")
        device.pressBack()
        pause(800)

        // 4) A future date (tomorrow) should register an alarm for tomorrow.
        check(clickDesc("Add task"), "step: clickDesc(Add task)")
        type("Tomorrow reminder")
        clickText("Tomorrow")
        clickText("Remind me")
        pickTime(target.toLocalTime(), "d")
        clickDesc("Add")
        pause(800)
        closeSheet()

        for (title in listOf("Date then time", "Time then date", "Menu reminder", "Tomorrow reminder")) {
            val t = task(title)
            note("saved '$title': due=${t?.dueDate} time=${t?.reminderTime} sticky=${t?.sticky} reminderAt=${t?.reminderAt()}")
        }
        val alarms = alarmLines()
        note("alarms registered for app: ${alarms.size}")
        alarms.take(12).forEach { note("   ${it.trim()}") }
        shot("e-list")

        // Wait for the reminders to fire and look in the notification shade.
        val waitMs = java.time.Duration.between(LocalDateTime.now(), target).toMillis() + 90_000
        note("waiting ${waitMs / 1000}s for notifications")
        Thread.sleep(waitMs.coerceIn(0, 420_000))
        device.openNotification()
        pause(1_500)
        shot("f-shade")
        val shown = listOf("Date then time", "Time then date", "Menu reminder").map { title ->
            title to (device.wait(Until.hasObject(By.text(title)), 3_000))
        }
        shown.forEach { (title, ok) -> note("notification '$title': ${if (ok) "SHOWN" else "MISSING"}") }
        device.pressBack()

        val expectedToday = listOf("Date then time", "Time then date", "Menu reminder").all { title ->
            task(title)?.reminderAt() == today.atTime(target.toLocalTime()) || task(title)?.reminderAt()?.toLocalDate() == today
        }
        val tomorrowOk = task("Tomorrow reminder")?.dueDate == today.plusDays(1)
        val allShown = shown.all { it.second }
        println("REMINDER REPORT\n$report")
        check(expectedToday && tomorrowOk && allShown, "REPORT:\n$report")
    }
}
