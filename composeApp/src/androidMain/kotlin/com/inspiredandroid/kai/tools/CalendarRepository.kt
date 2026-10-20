package com.inspiredandroid.kai.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.TimeZone

private const val TAG = "CalendarRepository"

sealed class CalendarResult {
    data class Success(
        val eventId: Long,
        val title: String,
        val startTime: String,
        val calendarName: String = "",
        val accountName: String = "",
    ) : CalendarResult()
    data class Error(val message: String) : CalendarResult()
}

class CalendarRepository(
    private val context: Context,
    private val permissionController: PermissionController,
) {

    fun hasCalendarPermission(): Boolean {
        val hasRead = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        val hasWrite = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        return hasRead && hasWrite
    }

    data class TargetCalendar(val id: Long, val displayName: String, val accountName: String)

    /**
     * Picks the calendar to write to. Prefers a visible, synced Google calendar
     * (the primary one first), then any visible synced writable calendar, then any
     * writable calendar. The old logic took the first writable calendar, which on
     * Samsung devices is often a hidden or local-only calendar.
     */
    fun getTargetCalendar(): TargetCalendar? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        var best: TargetCalendar? = null
        var bestScore = -1
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val type = c.getString(1)
                val account = c.getString(2) ?: ""
                val name = c.getString(3) ?: account
                val primary = !c.isNull(4) && c.getInt(4) == 1
                val visible = !c.isNull(5) && c.getInt(5) == 1
                val synced = !c.isNull(6) && c.getInt(6) == 1
                val isGoogle = type == "com.google"
                var score = 0
                if (visible) score += 8
                if (synced) score += 4
                if (isGoogle) score += 2
                if (primary || (isGoogle && name.equals(account, ignoreCase = true))) score += 1
                Log.d(TAG, "Calendar candidate id=$id name=$name account=$account type=$type score=$score")
                if (score > bestScore) {
                    bestScore = score
                    best = TargetCalendar(id, name, account)
                }
            }
        }
        return best
    }

    fun getPrimaryCalendarId(): Long? = getTargetCalendar()?.id

    suspend fun createEvent(
        title: String,
        startTimeIso: String,
        endTimeIso: String?,
        description: String?,
        location: String?,
        allDay: Boolean,
        reminderMinutes: Int,
    ): CalendarResult {
        Log.d(TAG, "createEvent called: title=$title, startTime=$startTimeIso")

        // Request permission if not already granted
        if (!hasCalendarPermission()) {
            Log.d(TAG, "Permission not granted, requesting...")
            val granted = permissionController.requestPermission()
            Log.d(TAG, "Permission request result: $granted")
            if (!granted) {
                return CalendarResult.Error("Calendar permission denied. Please enable calendar access in Settings to create events.")
            }
        } else {
            Log.d(TAG, "Permission already granted")
        }

        val target = getTargetCalendar()
            ?: return CalendarResult.Error("No writable calendar found. Please set up a calendar account on your device.")

        val startMillis: Long
        val endMillis: Long
        val timeZone = TimeZone.getDefault().id

        try {
            startMillis = parseIsoDateTimeToEpochMs(startTimeIso)
            endMillis = if (endTimeIso != null) {
                parseIsoDateTimeToEpochMs(endTimeIso)
            } else {
                // Default to 1 hour after start
                startMillis + 60 * 60 * 1000
            }
        } catch (e: DateTimeParseException) {
            return CalendarResult.Error("Invalid date format. Please use ISO 8601 format (e.g., 2024-03-15T14:30:00)")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, target.id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()

            if (eventId != null) {
                // Add reminder if specified
                if (reminderMinutes > 0) {
                    addReminder(eventId, reminderMinutes)
                }

                val formattedStart = formatForDisplay(startMillis)
                CalendarResult.Success(eventId, title, formattedStart, target.displayName, target.accountName)
            } else {
                CalendarResult.Error("Failed to create calendar event")
            }
        } catch (e: Exception) {
            CalendarResult.Error("Error creating event: ${e.message}")
        }
    }

    private fun addReminder(eventId: Long, minutesBefore: Int) {
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
    }

    private fun parseIsoDateTimeToEpochMs(isoString: String): Long {
        val trimmed = isoString.trim()

        // Offset-qualified inputs (e.g. "2024-03-15T14:30:00+02:00" or "...Z") must
        // be converted directly to an instant. Going through LocalDateTime.parse with
        // a relaxed formatter silently drops the offset and re-anchors to system zone,
        // which surfaces as an off-by-N-hours bug whenever the AI sends an offset that
        // differs from the device's local offset.
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: DateTimeParseException) {
        }

        // Naive inputs: interpret in the device's current zone.
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        )
        for (formatter in formatters) {
            try {
                val ldt = LocalDateTime.parse(trimmed, formatter)
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }

        throw DateTimeParseException("Unable to parse date: $isoString", isoString, 0)
    }

    private fun formatForDisplay(millis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a")
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return dateTime.format(formatter)
    }
}
