package thjread.annulus2

import android.content.ContentResolver
import android.content.Context
import android.location.Location
import android.text.format.DateUtils
import android.util.Log

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.squareup.moshi.JsonDataException
import retrofit2.converter.moshi.MoshiConverterFactory

import thjread.annulus.WeatherService
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

private const val WEATHER_UPDATE_FREQUENCY: Long = 10*DateUtils.MINUTE_IN_MILLIS
private const val WEATHER_RETRY_UPDATE_TIME: Long = 2*DateUtils.MINUTE_IN_MILLIS
// TODO Update more frequently when raining

class WeatherDataSource(private val context: Context) {

    var mWeatherData: WeatherService.WeatherData? = null

    var mLastUpdated: Long = 0
        private set

    private var mLastTriedUpdate: Long = 0

    private val mWeatherDataService = Retrofit.Builder()
        .baseUrl("https://api.forecast.io")
        .addConverterFactory(MoshiConverterFactory.create())
        .client(OkHttpClient())
        .build().create(WeatherService::class.java)

    private val mFusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private val sForecastAPIKey = context.resources.getString(R.string.forecast_api_key)

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
            val now = System.currentTimeMillis()
            /* Rate limit if updates are failing */
            if (now >= mLastTriedUpdate + WEATHER_RETRY_UPDATE_TIME) {
                mLastTriedUpdate = now
                fetchWeatherData()?.let{ data ->
                    mWeatherData = data
                    mLastUpdated = System.currentTimeMillis()
                }
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
    private suspend fun fetchWeatherData(): WeatherService.WeatherData? = withContext(Dispatchers.Default) {
        Log.d("Weather", "Fetching weather data")
        try {
            val location: Location? = Tasks.await(mFusedLocationClient.lastLocation, 30, TimeUnit.SECONDS)
            if (location != null) {
                val call = mWeatherDataService.getWeatherData(sForecastAPIKey, location.latitude, location.longitude)
                try {
                    val response = call.execute()
                    response.body()
                } catch (e: IOException) {
                    Log.e("Weather", "Failed to get a well-formed response from Forecast API: ${e.message}")
                    null
                } catch (e: JsonDataException) {
                    Log.e("Weather", "JSON data from Forecast API did not fit expected format: ${e.message}")
                    null
                }
            } else {
                Log.e("Weather", "Device has no last location")
                null
            }
        } catch (e: ExecutionException) {
            Log.e("Weather", "Failed to get last location: ${e.message}")
            null
        }
    }
}