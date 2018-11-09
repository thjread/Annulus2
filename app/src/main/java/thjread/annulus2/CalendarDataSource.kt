package thjread.annulus2

import android.content.ContentResolver
import android.provider.CalendarContract
import android.support.wearable.provider.WearableCalendarContract
import android.text.format.DateUtils
import android.util.Log

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

private const val CALENDAR_UPDATE_FREQUENCY: Long = 10*DateUtils.MINUTE_IN_MILLIS

data class CalendarData (val title: String, val begin: Long, val end: Long) {
    inline fun endsAfter(time: Long): Boolean {
        return this.end > time
    }

    inline fun beginsBefore(time: Long): Boolean {
        return this.begin < time
    }

    inline fun overlapsWith(start: Long, end: Long): Boolean {
        return this.endsAfter(start) && this.beginsBefore(end)
    }
}

class CalendarDataSource(private val contentResolver: ContentResolver) {

    /** List of events in the next 24 hours with title and times, sorted by start time */
    var mCalendarData: List<CalendarData> = listOf()
        private set

    var mLastUpdated: Long = 0
        private set

    /**
     * Weather and calendar data are each updated from an actor that
     * runs on the main (UI) thread, to prevent updating a source
     * multiple times simultaneously, and to prevent data races.
     *
     * Dispatchers.Main runs the actor in the main thread.
     * capacity=0 creates a RendezvousChannel with no buffer
     * so actor.offer(Unit) will cause an update if the actor is idle,
     * and have no effect if an update is already in progress
     */
    private val mCalendarActor: SendChannel<Unit> = GlobalScope.actor(Dispatchers.Main, capacity = 0) {
        Log.d("Calendar", "Initialized Calendar actor")

        for (event in channel) {
            fetchCalendarData()?.let{
                mCalendarData = it
                mLastUpdated = System.currentTimeMillis()
            }
        }
    }

    fun nextHourCalendarData(time: Long): List<CalendarData> {
        return mCalendarData.filter {
            it.overlapsWith(time, time+DateUtils.HOUR_IN_MILLIS)
        }
    }

    /**
     * Asks the calendar actor to update the calendar data.
     * Has no effect if an update is already in progress.
     */
    fun updateCalendarData() {
        mCalendarActor.offer(Unit)
    }

    /**
     * Asks for a calendar data update if data is more than CALENDAR_UPDATE_FREQUENCY out of date
     */
    fun updateCalendarDataIfStale() {
        if (System.currentTimeMillis() - mLastUpdated > CALENDAR_UPDATE_FREQUENCY) {
            updateCalendarData()
        }
    }

    /**
     * Fetches latest calendar data, in a background thread to
     * avoid blocking the main (UI) thread.
     */
    private suspend fun fetchCalendarData(): List<CalendarData>? = withContext(Dispatchers.Default) {

        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID, // 0
            CalendarContract.Instances.TITLE,    // 1
            CalendarContract.Instances.BEGIN,    // 2
            CalendarContract.Instances.END,      // 3
            CalendarContract.Instances.ALL_DAY   // 4
        )
        val PROJECTION_EVENT_ID_INDEX: Int = 0
        val PROJECTION_TITLE_INDEX: Int = 1
        val PROJECTION_BEGIN_INDEX: Int = 2
        val PROJECTION_END_INDEX: Int = 3
        val PROJECTION_ALL_DAY_INDEX: Int = 4

        /*
         * Get all available calendar events (since WearableCalendarContract
         * only syncs the next 24 hours anyway)
         */
        val cursor = contentResolver.query(
            WearableCalendarContract.Instances.CONTENT_URI,
            INSTANCE_PROJECTION,
            null, null, null
        )

        if (cursor == null) {
            Log.d("Calendar", "Failed to resolve calendar data")
            null
        } else {
            val data = mutableListOf<CalendarData>()

            while (cursor.moveToNext()) {
                if (cursor.getString(PROJECTION_ALL_DAY_INDEX) != "1") {//if not all day event

                    val begin = cursor.getLong(PROJECTION_BEGIN_INDEX)
                    val end = cursor.getLong(PROJECTION_END_INDEX)
                    var title = cursor.getString(PROJECTION_TITLE_INDEX)

                    /*
                     * If title is of the form "Event name (Location)" then just
                     * extract the location for display
                     */
                    val regex = Regex("""\((.+)\)""")
                    regex.find(title)?.groupValues?.get(1)?.let{
                        title = it
                    }
                    data.add(CalendarData(title, begin, end))
                }
            }

            cursor.close()

            data.sortBy{it.begin}

            Log.d("Calendar", "Fetched calendar data")
            data
        }
    }
}