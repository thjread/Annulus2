package thjread.annulus

import java.util.ArrayList

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

interface WeatherService {
    @GET("forecast/{api_key}/{latitude},{longitude}?units=si")
    fun getWeatherData(
        @Path("api_key") api_key: String,
        @Path("latitude") latitude: Double,
        @Path("longitude") longitude: Double
    ): Call<WeatherData>

    data class Daily(
        val summary: String?,
        val icon: String?,
        val data: List<Datum>
    )

    data class Hourly(
        val summary: String?,
        val icon: String?,
        val data: List<Datum>
    )

    data class Minutely(
        val summary: String?,
        val icon: String?,
        val data: List<Datum>
    )

    data class Datum(
        val time: Int?,
        val summary: String?,
        val icon: String?,
        val sunriseTime: Int?,
        val sunsetTime: Int?,
        val moonPhase: Double?,
        val precipIntensity: Double?,
        val precipIntensityMax: Double?,
        val precipIntensityMaxTime: Int?,
        val precipProbability: Double?,
        val precipType: String?,
        val temperature: Double?,
        val temperatureMin: Double?,
        val temperatureMinTime: Int?,
        val temperatureMax: Double?,
        val temperatureMaxTime: Int?,
        val apparentTemperatureMin: Double?,
        val apparentTemperatureMinTime: Int?,
        val apparentTemperatureMax: Double?,
        val apparentTemperatureMaxTime: Int?,
        val dewPoint: Double?,
        val humidity: Double?,
        val windSpeed: Double?,
        val windBearing: Int?,
        val visibility: Double?,
        val cloudCover: Double?,
        val pressure: Double?,
        val ozone: Double?
    )

    data class Flags(
        val sources: List<String>,
        val darkskyStations: List<String>,
        val datapointStations: List<String>,
        val metnoLicense: String?,
        val isdStations: List<String>,
        val madisStations: List<String>,
        val units: String?
    )

    data class WeatherData(
        val latitude: Double?,
        val longitude: Double?,
        val timezone: String?,
        val offset: Int?,
        val currently: Datum?,
        val minutely: Minutely?,
        val hourly: Hourly?,
        val daily: Daily?,
        val flags: Flags?
    )
}
