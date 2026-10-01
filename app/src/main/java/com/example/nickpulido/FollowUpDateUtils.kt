package com.nickpulido.rcrm

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

fun dayKeyFor(date: Date): String = dayKeyFormat.format(date)

fun dayKeyForToday(): String = dayKeyFor(Date())

fun Timestamp.isSameCalendarDay(dayKey: String): Boolean =
    dayKeyFor(toDate()) == dayKey
