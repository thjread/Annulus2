package thjread.annulus

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import com.squareup.moshi.JsonClass

interface WeatherService {
    @GET("forecast/{api_key}/{latitude},{longitude}?units=si")
    fun getWeatherData(
        @Path("api_key") api_key: String,
        @Path("latitude") latitude: Double,
        @Path("longitude") longitude: Double
    ): Call<WeatherData>

    @JsonClass(generateAdapter = true)
    data class DataBlock(
        val data: List<Datum>,
        val summary: String?,
        val icon: String?
    )

    @JsonClass(generateAdapter = true)
    data class Datum(
        val time: Long,
        val summary: String?,
        val icon: String?,
        val sunriseTime: Long?,
        val sunsetTime: Long?,
        val moonPhase: Double?,
        val precipAccumulation: Double?,
        val precipIntensity: Double?,
        val precipIntensityError: Double?,
        val precipIntensityMax: Double?,
        val precipIntensityMaxTime: Long?,
        val precipProbability: Double?,
        val precipType: String?,
        val temperature: Double?,
        val temperatureLow: Double?,
        val temperatureLowTime: Long?,
        val temperatureHigh: Double?,
        val temperatureHighTime: Long?,
        val apparentTemperatureLow: Double?,
        val apparentTemperatureLowTime: Long?,
        val apparentTemperatureHigh: Double?,
        val apparentTemperatureHighTime: Long?,
        val dewPoint: Double?,
        val humidity: Double?,
        val windSpeed: Double?,
        val windBearing: Double?,
        val windGust: Double?,
        val windGustTime: Long?,
        val visibility: Double?,
        val cloudCover: Double?,
        val pressure: Double?,
        val ozone: Double?,
        val nearestStormBearing: Double?,
        val nearestStormDistance: Double?,
        val uvIndex: Double?,
        val uvIndexTime: Long?
    )

    @JsonClass(generateAdapter = true)
    data class WeatherData(
        val latitude: Double,
        val longitude: Double,
        val timezone: String,
        val currently: Datum?,
        val minutely: DataBlock?,
        val hourly: DataBlock?,
        val daily: DataBlock?
    )
}
