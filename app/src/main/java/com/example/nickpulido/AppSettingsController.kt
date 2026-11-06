package com.nickpulido.rcrm

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object AppSettingsController {

    const val PREFS_NAME = "settings"
    const val PREFS_INTRO = "IntroPresets"
    const val PREFS_KEY = "saved_presets_list"
    const val PREFS_IGNORED = "ignored_contacts_list"
    const val NAME_TOKEN = "[NAME]"

    fun applyAppTheme(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val followSystem = prefs.getBoolean("follow_system_theme", true)
        val isDarkMode = prefs.getBoolean("dark_mode", false)

        if (followSystem) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    fun scheduleSideKickWorker(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val briefingHour = prefs.getInt("side_kick_briefing_hour", 8)
        val briefingMinute = prefs.getInt("side_kick_briefing_minute", 0)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, briefingHour)
            set(Calendar.MINUTE, briefingMinute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }
        val initialDelay = (calendar.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0)

        val request = PeriodicWorkRequestBuilder<SideKickWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "SideKickWorker",
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelSideKickWorker(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("SideKickWorker")
    }

    fun scheduleDailyDigestWorker(context: Context, replace: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val digestHour = prefs.getInt(DailyFollowUpWorker.KEY_HOUR, 8)
        val digestMinute = prefs.getInt(DailyFollowUpWorker.KEY_MINUTE, 0)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, digestHour)
            set(Calendar.MINUTE, digestMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }
        val initialDelay = (calendar.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0)

        val request = PeriodicWorkRequestBuilder<DailyFollowUpWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyFollowUpWorker.WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelDailyDigestWorker(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailyFollowUpWorker.WORK_NAME)
    }

    fun formatTime12Hour(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    fun persistSideKickTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt("side_kick_briefing_hour", hour)
            putInt("side_kick_briefing_minute", minute)
        }
        if (context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("side_kick_enabled", false)) {
            scheduleSideKickWorker(context)
        }
    }

    fun persistDigestTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(DailyFollowUpWorker.KEY_HOUR, hour)
            putInt(DailyFollowUpWorker.KEY_MINUTE, minute)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DailyFollowUpWorker.KEY_ENABLED, DailyFollowUpWorker.DEFAULT_ENABLED)) {
            scheduleDailyDigestWorker(context, replace = true)
        }
    }

    fun setSideKickEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("side_kick_enabled", enabled)
        }
        if (enabled) scheduleSideKickWorker(context) else cancelSideKickWorker(context)
    }

    fun setDailyDigestEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(DailyFollowUpWorker.KEY_ENABLED, enabled)
        }
        if (enabled) scheduleDailyDigestWorker(context, replace = true) else cancelDailyDigestWorker(context)
    }

    fun setThemePrefs(context: Context, followSystem: Boolean, darkMode: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("follow_system_theme", followSystem)
            putBoolean("dark_mode", darkMode)
        }
        applyAppTheme(context)
    }
}
