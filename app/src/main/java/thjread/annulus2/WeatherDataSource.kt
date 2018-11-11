package thjread.annulus2

import android.content.ContentResolver
import android.text.format.DateUtils
import android.util.Log

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

private const val WEATHER_UPDATE_FREQUENCY: Long = 10*DateUtils.MINUTE_IN_MILLIS

/*data class CalendarData (val title: String, val begin: Long, val end: Long) {
    inline fun endsAfter(time: Long): Boolean {
        return this.end > time
    }

    inline fun beginsBefore(time: Long): Boolean {
        return this.begin < time
    }

    inline fun overlapsWith(start: Long, end: Long): Boolean {
        return this.endsAfter(start) && this.beginsBefore(end)
    }
}*/

class WeatherDataSource(private val contentResolver: ContentResolver) {// TODO do we need contentResolver?

    var mWeatherData: Unit = Unit

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
    private val mWeatherActor: SendChannel<Unit> = GlobalScope.actor(Dispatchers.Main, capacity = 0) {
        Log.d("Weather", "Initialized Weather actor")

        for (event in channel) {
            fetchWeatherData()?.let{
                mWeatherData = it
                mLastUpdated = System.currentTimeMillis()
            }
        }
    }

    /**
     * Asks the weather actor to update the weather data.
     * Has no effect if an update is already in progress.
     */
    fun updateWeatherData() {
        mWeatherActor.offer(Unit)
    }

    /**
     * Asks for a weather data update if data is more than WEATHER_UPDATE_FREQUENCY out of date
     */
    fun updateWeatherDataIfStale() {
        if (System.currentTimeMillis() - mLastUpdated > WEATHER_UPDATE_FREQUENCY) {
            updateWeatherData()
        }
    }

    /**
     * Fetches latest weather data, in a background thread to
     * avoid blocking the main (UI) thread.
     */
    private suspend fun fetchWeatherData(): Unit? = withContext(Dispatchers.Default) {
        Unit
    }
}