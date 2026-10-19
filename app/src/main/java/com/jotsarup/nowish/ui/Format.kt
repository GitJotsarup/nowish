package com.jotsarup.nowish.ui

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val sameYear = DateTimeFormatter.ofPattern("EEE, MMM d")
private val otherYear = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val headerDate = DateTimeFormatter.ofPattern("EEEE, MMM d")

fun formatTime(context: Context, time: LocalTime): String =
    time.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))

fun friendlyDate(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(if (date.year == today.year) sameYear else otherYear)
}

fun headerDate(date: LocalDate): String = date.format(headerDate)

fun greeting(hour: Int): String = when (hour) {
    in 0..4 -> "Up late"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

private val cheers = listOf(
    "Nice! One less thing ✨",
    "Crushed it 💪",
    "Done and dusted 🎉",
    "Look at you go 🚀",
    "Cleared! ✅",
    "That felt good 😌",
    "Off the list 🙌",
)

fun randomCheer(): String = cheers.random()

// Material date pickers work in UTC midnight millis.
fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
fun Long.fromPickerMillis(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
