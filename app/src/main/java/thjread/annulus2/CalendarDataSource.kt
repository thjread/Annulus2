package thjread.annulus2

import android.util.Log

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

class CalendarData (val data: Int) {
    // TODO implement this
}

class CalendarDataSource {

    var mCalendarData: CalendarData? = null

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
    private val mCalendarActor: SendChannel<Unit> = GlobalScope.actor(Dispatchers.Main, capacity=0) {
        for (event in channel) {
            mCalendarData = fetchCalendarData()
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
     * Fetches latest calendar data, in a background thread to
     * avoid blocking the main (UI) thread.
     */
    suspend fun fetchCalendarData(): CalendarData = withContext(Dispatchers.Default) {
        // TODO implement this
        Thread.sleep(100)
        Log.d("ANNULUS", "fetched calendar data")
        CalendarData(10)
    }
}