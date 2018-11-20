package thjread.annulus2

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.ResultReceiver
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.text.format.DateUtils
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowInsets
import thjread.annulus.WeatherService

import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*
import kotlin.random.Random

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

/**
 * Colors and dimensions for the watchface.
 */
private const val BACKGROUND_COLOR = Color.BLACK
private val BACKGROUND_COLOR_LIGHT = Color.rgb(22, 22, 22)

private const val MAJOR_TICK_THICKNESS = 0.05f
private const val MINOR_TICK_THICKNESS = 0.01f
private const val OUTER_TICK_RADIUS = 8.5f/9f
private const val MAJOR_TICK_LENGTH = 1f/9f
private const val MINOR_TICK_LENGTH = 0.5f/9f
private const val RAIN_TICKS_THRESHOLD = 0.12
private const val RAIN_TICK_MAX_LENGTH = OUTER_TICK_RADIUS

private const val WATCH_HAND_COLOR = Color.WHITE
private val MAJOR_TICK_COLOR = Color.rgb(230, 230, 230)
private val MINOR_TICK_COLOR = Color.rgb(170, 170, 170)

private const val HOUR_LENGTH = 5f/9f
private const val HOUR_BORDER_THICKNESS = 0.015f
private const val HOUR_THICKNESS = 0.07f
private const val HOUR_TIP_THICKNESS = 0.04f
private const val HOUR_TIP_LENGTH = 0.04f

private const val MINUTE_LENGTH = 8f/9f
private const val MINUTE_BORDER_THICKNESS = 0.015f
private const val MINUTE_THICKNESS = 0.06f
private const val MINUTE_TIP_THICKNESS = 0.035f
private const val MINUTE_TIP_LENGTH = 0.04f

private const val SECOND_LENGTH = 8f/9f
private const val SECOND_THICKNESS = 0.02f

private const val CENTER_CIRCLE_RADIUS = 0.06f
private const val INNER_CIRCLE_RADIUS = CENTER_CIRCLE_RADIUS - MINUTE_BORDER_THICKNESS

private val GAUGE_BACKGROUND_COLOR = Color.rgb(50, 50, 50)
private val TEMPERATURE_COLOR = Color.rgb(211, 47, 47)
private val TEMPERATURE_FILL_COLOR = Color.argb(100, 230, 81, 0)
private val PRESSURE_COLOR = Color.rgb(0, 121, 107)
private val PRESSURE_FILL_COLOR = Color.argb(100, 0, 151, 167)
private val ZERO_DEGREES_COLOR = Color.rgb(127, 219, 255)
private val FIVE_DEGREES_COLOR = Color.rgb(170, 170, 170)
private const val ZERO_DEGREES_THICKNESS = 0.03f
private const val FIVE_DEGREES_THICKNESS = 0.02f

private const val CALENDAR_THICKNESS = 0.02f
private const val CALENDAR_RADIUS = 7f/9f
private const val CALENDAR_TEXT_SIZE = 0.18f
private val CALENDAR_TEXT_HEIGHTS = listOf(4f/9f, 2f/9f)

private val CALENDAR_COLORS = listOf(
    Color.rgb(33, 150, 243),
    Color.rgb(171, 71, 188),
    Color.rgb(255, 87, 34))
private const val CALENDAR_GAP_MINUTES = 1.5

private const val WEATHER_RING_THICKNESS = 0.04f
private const val WEATHER_RING_MAX_THICKNESS = 3f/9f
private const val WEATHER_RING_RADIUS = 4f/9f
private const val ARC_EPSILON = 0.8f
private const val MAX_RAIN = 8f
private const val MIN_DISPLAY_PRECIP = 0.09f
private const val MAX_STARS_CLOUD_COVER = 0.4f
private const val STAR_BORDER_EPSILON = 0.008f
private const val MAX_STARS = 15

private val RAIN_COLOR = Color.rgb(2, 136, 209)
private val CLEAR_COLOR = Color.rgb(255, 213, 79)
private const val CLOUD_COLOR = Color.WHITE
private val DARK_RAIN_COLOR = Color.rgb(13, 71, 161)
private val DARK_CLEAR_COLOR = Color.rgb(40, 40, 40)
private val DARK_CLOUD_COLOR = Color.rgb(130, 130, 130)
private val STAR_COLOR = CLEAR_COLOR

/*
 * Mean longitudes (in degrees) of the eight planets and pluto at epoch J2000
 * (data from https://ssd.jpl.nasa.gov/txt/p_elem_t1.txt).
 */
private val J2000_MEAN_LONGITUDES = arrayOf(
    252.25032350,
    181.97909950,
    100.46457166,
    -4.55343205,
    34.39644051,
    49.95424423,
    313.23810451,
    -55.12002969,
    238.92903833
)
/* Rate of change of mean longitude in degrees per Julian century. */
private val MEAN_LONGITUDE_RATES = arrayOf(
    149472.67411175,
    58517.81538729,
    35999.37244981,
    19140.30268499,
    3034.74612775,
    1222.49362201,
    428.48202785,
    218.45945325,
    145.20780515
)
private const val J2000 = 946727936L*DateUtils.SECOND_IN_MILLIS

private const val PLANET_ELLIPSE_THICKNESS = 2f/9f
private const val PLANET_COLOR_ALPHA = 100
private val PLANET_COLORS = arrayOf(
    Color.argb(PLANET_COLOR_ALPHA, 109, 76, 65),
    Color.argb(PLANET_COLOR_ALPHA, 255, 235, 59),
    Color.argb(PLANET_COLOR_ALPHA, 67, 160, 71),
    Color.argb(PLANET_COLOR_ALPHA, 183, 28, 28),
    Color.argb(PLANET_COLOR_ALPHA, 230, 74, 25),
    Color.argb(PLANET_COLOR_ALPHA, 255, 160, 0),
    Color.argb(PLANET_COLOR_ALPHA, 41, 182, 246),
    Color.argb(PLANET_COLOR_ALPHA, 21, 101, 192),
    Color.argb(PLANET_COLOR_ALPHA, 81, 45, 168)
)

/**
 * Code to tell ResultReceiver that calendar permission is granted, and key to pass ResultReceiver in Intent.
 */
private const val PERMISSION_RESULT_CODE = 0
private const val KEY_RECEIVER = "KEY_RECEIVER"
private const val KEY_PERMISSIONS_GRANTED = "KEY_PERMISSIONS_GRANTED"

/**
 * Need an Activity to request permissions. Communicates back to Service using a ResultReceiver passed in the Intent.
 */
class PermissionActivity : Activity() {
    private var mPermissionsGranted = BooleanArray(3)

    private val PERMISSIONS = arrayOf(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.INTERNET,
        android.Manifest.permission.ACCESS_COARSE_LOCATION)

    private val PERMISSION_CODE = 0

    override fun onStart() {
        super.onStart()

        Log.d("Annulus", "Requesting permissions")
        ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_CODE) {
            for (i in 0 until minOf(3, grantResults.size)) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    mPermissionsGranted[i] = true
                    Log.d("Annulus", "${PERMISSIONS[i]} permission granted")
                } else {
                    Log.d("Annulus", "${PERMISSIONS[i]} permission denied")
                }
            }
            finish()
        }
    }

    override fun finish() {
        val receiver: ResultReceiver = intent.getParcelableExtra(KEY_RECEIVER)
        receiver.send(PERMISSION_RESULT_CODE, Bundle().apply{putBooleanArray(KEY_PERMISSIONS_GRANTED, mPermissionsGranted)})

        super.finish()
    }
}

class Annulus : CanvasWatchFaceService() {

    private var mCalendarDataSource: CalendarDataSource? = null
    private var mWeatherDataSource: WeatherDataSource? = null

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: Annulus.Engine) : Handler() {
        private val mWeakReference: WeakReference<Annulus.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    companion object {
        /**
         * Utility function for interpolating between two colors.
         */
        private fun interpolateColor(first: Int, second:  Int, ratio: Float): Int {
            val r = (Color.red(first)*ratio + Color.red(second)*(1f-ratio)).toInt()
            val g = (Color.green(first)*ratio + Color.green(second)*(1f-ratio)).toInt()
            val b = (Color.blue(first)*ratio + Color.blue(second)*(1f-ratio)).toInt()
            return Color.rgb(r, g, b)
        }
    }

    /**
     * Weather data which is passed to the watchface drawing function.
     * tickParams contains a list of (length, color)
     */
    data class WatchfaceWeatherData (val currentTemperature: Double?, val currentPressure: Double?,
                                     val currentWindSpeed: Double?, val tickParams: List<Pair<Float, Int>>?) {
        companion object {
            fun fromWeatherData(data: WeatherService.WeatherData?, now: Long, calendar: Calendar): WatchfaceWeatherData {
                val tickParams = MutableList(60) { Pair(0f, FIVE_DEGREES_COLOR) }

                /* Only pass tickParams if there is sufficient rain in the next hour */
                var showRain = false
                var dataPoints = 0
                if (data?.minutely != null) {

                    calendar.timeInMillis = now
                    val currentMinute = calendar.get(Calendar.MINUTE)

                    for (datum in data.minutely.data) {
                        val time = datum.time*DateUtils.SECOND_IN_MILLIS
                        if (time <= now || time > now + DateUtils.HOUR_IN_MILLIS) {
                            continue
                        }

                        dataPoints += 1

                        calendar.timeInMillis = time
                        val minute = calendar.get(Calendar.MINUTE)
                        val precipProbability = datum.precipProbability ?: 0.0
                        val precipExpectation = datum.precipIntensity?.times(precipProbability) ?: 0.0

                        if (precipExpectation >= RAIN_TICKS_THRESHOLD) {
                            showRain = true
                        }
                        val length = minOf(MINOR_TICK_LENGTH +
                                RAIN_TICK_MAX_LENGTH * precipExpectation.toFloat() / MAX_RAIN,
                            OUTER_TICK_RADIUS)
                        val minutesFromNow = (minute+60-currentMinute) % 60
                        /* Shrink the last few minutes of the hour to show clearly that they represent the future and not
                         * the past.
                         */
                        val lengthMultiplier = when (minutesFromNow) {
                            57 -> 0.66f
                            58 -> 0.33f
                            59 -> 0f
                            0 -> 0f
                            else -> 1f
                        }
                        val color = Annulus.interpolateColor(RAIN_COLOR, FIVE_DEGREES_COLOR, precipProbability.toFloat())
                        tickParams[minute] = Pair(length*lengthMultiplier, color)
                    }
                }

                return WatchfaceWeatherData(data?.currently?.temperature, data?.currently?.pressure,
                    data?.currently?.windSpeed, if (showRain && dataPoints >= 40) { tickParams } else { null })
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var mCalendar: Calendar

        private var mRegisteredTimeZoneReceiver = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F
        private var mRadius: Float = 0F
        private var mChinSize: Float = 0F

        private lateinit var mFillPaint: Paint
        private lateinit var mLightenPaint: Paint
        private lateinit var mHandStrokePaint: Paint
        private lateinit var mTickPaint: Paint
        private lateinit var mCalendarPaint: Paint

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false

        /* Controls whether to display calendar data or weather data */
        private var mCalendarMode = false

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        private val mPermissionReceiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == PERMISSION_RESULT_CODE) {
                    resultData?.getBooleanArray(KEY_PERMISSIONS_GRANTED)?.let {permissionsGranted ->
                        if (permissionsGranted[0] && mCalendarDataSource == null) {
                            mCalendarDataSource = CalendarDataSource(contentResolver)
                        }
                        if (permissionsGranted[1] && permissionsGranted[2] && mWeatherDataSource == null) {
                            mWeatherDataSource = WeatherDataSource(applicationContext)
                        }
                    }
                }
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            /*
             * Ask for permissions if we don't already have them
             */
            var needPermissions = false
            if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED) {
                mCalendarDataSource = CalendarDataSource(contentResolver)
            } else {
                needPermissions = true
            }
            if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.INTERNET)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                mWeatherDataSource = WeatherDataSource(applicationContext)
            } else {
                needPermissions = true
            }

            if (needPermissions) {
                val permissionIntent = Intent(applicationContext, PermissionActivity::class.java)
                permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                permissionIntent.putExtra(KEY_RECEIVER, mPermissionReceiver)
                startActivity(permissionIntent)
            }

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@Annulus)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            mCalendar = Calendar.getInstance()

            initializeWatchFace()
        }

        private fun initializeWatchFace() {
            /* Set color before each use */
            mFillPaint = Paint().apply {
                strokeWidth = 0f
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            /* Set color before each use */
            mLightenPaint = Paint().apply {
                strokeWidth = 0f
                isAntiAlias = true
                style = Paint.Style.FILL
                xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
            }

            /* Set color, strokeWidth before each use */
            mHandStrokePaint = Paint().apply {
                isAntiAlias = true
                strokeCap = Paint.Cap.BUTT
                style = Paint.Style.STROKE
            }

            /* Should set strokeWidth before each use */
            mTickPaint = Paint().apply {
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }

            /* Should set color before each use */
            mCalendarPaint = Paint().apply {
                strokeWidth = CALENDAR_THICKNESS
                isAntiAlias = true
                strokeCap = Paint.Cap.BUTT
                style = Paint.Style.STROKE
            }

            updateWatchHandStyle()
        }

        private fun updateWatchHandStyle() {
            // TODO also change colors?

            if (mAmbient && mLowBitAmbient) {
                mFillPaint.isAntiAlias = false
                mLightenPaint.isAntiAlias = false
                mHandStrokePaint.isAntiAlias = false
                mTickPaint.isAntiAlias = false
                mCalendarPaint.isAntiAlias = false
            } else {
                mFillPaint.isAntiAlias = true
                mLightenPaint.isAntiAlias = true
                mHandStrokePaint.isAntiAlias = true
                mTickPaint.isAntiAlias = true
                mCalendarPaint.isAntiAlias = true
            }
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false
            )
            mBurnInProtection = properties.getBoolean(
                WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false
            )
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            updateWatchHandStyle()

            /* Check and trigger whether or not timer should be running (only
             * in active mode).
             */
            updateTimer()
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)

            /* Could change watchface appearance based on interruption filter here */
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f
            mCenterY = height / 2f
            mRadius = Math.min(mCenterX, mCenterY)

            mFillPaint.textSize = CALENDAR_TEXT_SIZE*mRadius
        }

        override fun onApplyWindowInsets(insets: WindowInsets?) {
            super.onApplyWindowInsets(insets)

            insets?.let{
                mChinSize = it.systemWindowInsetBottom.toFloat()
            }
        }

        /**
         * Captures tap event (and tap type).
         * Tapping manually triggers a calendar and weather data update.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP -> {
                    // The user has completed the tap gesture
                    // TODO do we want manual refresh every time?
                    mCalendarDataSource?.updateCalendarData()
                    mWeatherDataSource?.updateWeatherData()
                    mCalendarMode = !mCalendarMode
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {

            val now = System.currentTimeMillis()

            val nextHourResponse = mCalendarDataSource?.nextHourCalendarData(now)
            if (nextHourResponse?.visibilityChanged == true) {
                mCalendarMode = nextHourResponse.nextHourCalendarData.isNotEmpty()
            }

            canvas.drawRGB(Color.red(BACKGROUND_COLOR), Color.green(BACKGROUND_COLOR), Color.blue(BACKGROUND_COLOR))

            drawBackground(canvas, now)

            if (mCalendarMode) {
                if (nextHourResponse != null) {
                    drawCalendar(canvas, now, nextHourResponse.nextHourCalendarData)
                }
            } else {
                mWeatherDataSource?.mWeatherData?.run{
                    drawWeather(canvas, now, this)
                }
            }

            val watchfaceWeatherData =
                WatchfaceWeatherData.fromWeatherData(mWeatherDataSource?.mWeatherData, now, mCalendar)
            drawWatchFace(canvas, now, watchfaceWeatherData)

            mWeatherDataSource?.run{ updateWeatherDataIfStale() }

            mCalendarDataSource?.run{ updateCalendarDataIfStale() }
        }

        /**
         * In weather mode, concentric circles in the background (for decoration, and to show temperature scale).
         * In calendar mode, draw 30 degree sectors in the background, and ellipses representing the positions of the
         * planets (and Pluto).
         */
        private fun drawBackground (canvas: Canvas, now: Long) {
            fun annulusPath(innerRadius: Float, outerRadius: Float): Path {
                val p = Path()
                p.addCircle(0f, 0f, outerRadius, Path.Direction.CW)
                p.addCircle(0f, 0f, innerRadius, Path.Direction.CCW)
                return p
            }

            fun planetLongitude(now: Long, planet: Int): Double {
                assert(planet in 0 until 9)
                val centiYearsSinceJ2000 = (now-J2000)/(100.0*365.25*86400*DateUtils.SECOND_IN_MILLIS)
                val meanLongitude = J2000_MEAN_LONGITUDES[planet] + MEAN_LONGITUDE_RATES[planet]*centiYearsSinceJ2000
                return meanLongitude
            }

            /* Translate and scale canvas so that centre is 0, 0 and radius is 1. */
            canvas.save()
            canvas.translate(mCenterX, mCenterY)
            canvas.scale(mRadius, mRadius, 0f, 0f)

            if (mCalendarMode) {
                mFillPaint.color = BACKGROUND_COLOR_LIGHT
                for (i in 0 until 12 step 2) {
                    canvas.drawArc(RectF(-1f, -1f, 1f, 1f), i*30f, 30f, true, mFillPaint)
                }

                for (i in 8 downTo 0) {
                    /* Longitude is measured anticlockwise, angle is clockwise. */
                    val angle = -planetLongitude(now, i).toFloat()
                    canvas.rotate(angle)
                    mFillPaint.color = PLANET_COLORS[i]
                    /* Draw ellipse with foci at centre and (i+1)/9. */
                    val semiMinorAxis = PLANET_ELLIPSE_THICKNESS/2f
                    val centreToFocus = ((i+1)/9f)/2f
                    val semiMajorAxis = sqrt(semiMinorAxis*semiMinorAxis + centreToFocus*centreToFocus)
                    canvas.drawOval(
                        RectF(
                            -semiMinorAxis, -(centreToFocus+semiMajorAxis),
                            semiMinorAxis, -(centreToFocus-semiMajorAxis)
                        ),
                        mFillPaint
                    )
                    canvas.rotate(-angle)
                }
            } else {
                mFillPaint.color = BACKGROUND_COLOR_LIGHT
                for (i in 0 until 9 step 2) {
                    val annulusPath = annulusPath(i*1f/9f, (i+1)*1f/9f)
                    canvas.drawPath(annulusPath, mFillPaint)
                }
            }

            canvas.restore()
        }

        /**
         * Display pressures from 980 to 1040 hPa.
         */
        private fun pressureToRatio(pressure: Double, rangeMax: Float = 1f): Float =
            ((pressure.toFloat()-980f)/(1040f-980f)).coerceIn(0f, rangeMax)

        /**
         * Display temperatures from -10 to 30 degrees.
         */
        private fun temperatureToRatio(temperature: Double, rangeMax: Float = 1f): Float =
            ((temperature.toFloat() + 10f) / (10f + 30f)).coerceIn(0f, rangeMax)

        /**
         * Draw a ring representing the next 11 hours of weather
         */
        private fun drawWeather(canvas: Canvas, now: Long, weatherData: WeatherService.WeatherData) {

            /*
             * Process weather data to produce start angles, sweep angles, and weather data necessary for display
             * (precipitation, cloud cover, day/night).
             */
            val sunriseOrSunsets: MutableList<Pair<Long, Boolean>> = mutableListOf() // (time, isSunrise)
            if (weatherData.daily != null) {
                for (datum in weatherData.daily.data) {
                    datum.sunriseTime?.let {
                        sunriseOrSunsets.add(Pair(it*DateUtils.SECOND_IN_MILLIS, true))
                    }
                    datum.sunsetTime?.let {
                        sunriseOrSunsets.add(Pair(it*DateUtils.SECOND_IN_MILLIS, false))
                    }
                }
            }

            data class WeatherRingSegment (val startAngle: Float, val sweepAngle: Float, val precipExpectation: Float,
                                           val cloudCover: Float, val day: Boolean)

            fun createWeatherRingSegment(begin: Long, end: Long, datum: WeatherService.Datum, day: Boolean): WeatherRingSegment {
                val precipExpectation = if (datum.precipProbability != null && datum.precipIntensity != null) {
                    (datum.precipProbability * datum.precipIntensity).toFloat()
                } else {
                    0f
                }

                val cloudCover = datum.cloudCover?.toFloat() ?: 0.0f

                val startAngle = hourAngle(begin)
                val endAngle = hourAngle(end)
                val sweepAngle = (endAngle + 360 - startAngle) % 360

                return WeatherRingSegment(startAngle, sweepAngle, precipExpectation, cloudCover, day)
            }

            val segments: MutableList<WeatherRingSegment> = mutableListOf()
            val temperatures: MutableList<Pair<Float, Float>> = mutableListOf() // angle, ratio
            val pressures: MutableList<Pair<Float, Float>> = mutableListOf() // angle, ratio

            if (weatherData.hourly != null) {
                for (datum in weatherData.hourly.data) {
                    var begin = datum.time * DateUtils.SECOND_IN_MILLIS
                    var end = begin + DateUtils.HOUR_IN_MILLIS

                    /*
                     * Add temperature and pressure for the next 12 hours to lists.
                     */
                    val midTime = (begin+end)/2
                    if (midTime >= now && midTime < now + 12*DateUtils.HOUR_IN_MILLIS) {
                        val midpointAngle = hourAngle(midTime)
                        if (datum.temperature != null) {
                            temperatures.add(Pair(midpointAngle, temperatureToRatio(datum.temperature,
                                rangeMax=1f/ MINUTE_LENGTH)))
                        }
                        if (datum.pressure != null) {
                            pressures.add(Pair(midpointAngle, pressureToRatio(datum.pressure,
                                rangeMax=1f/HOUR_LENGTH)))
                        }
                    }

                    /*
                     * Only take data for the next 11 hours, and clip the segment edges to lie in this interval.
                     */
                    if (end < now || begin >= now + 11*DateUtils.HOUR_IN_MILLIS) {
                        continue
                    }
                    if (begin < now) {
                        begin = now
                    }
                    if (end > now + 11*DateUtils.HOUR_IN_MILLIS) {
                        end = now + 11*DateUtils.HOUR_IN_MILLIS
                    }

                    /*
                     * binarySearch returns the index of the element matching the key if it's found exactly, or
                     * otherwise -i-1 where i is the index at which the key could be inserted to maintain sorted
                     * order.
                     */
                    val sunIndex = sunriseOrSunsets.binarySearchBy(begin, selector={ p -> p.first })
                    val previousSun = sunriseOrSunsets.getOrNull(if (sunIndex >= 0) { sunIndex } else { -sunIndex-2 })
                    val nextSun = sunriseOrSunsets.getOrNull(if (sunIndex >= 0) { sunIndex + 1} else { -sunIndex-1 })

                    /*
                     * If day/night changes during the hour, create two segments.
                     * If not, decide whether it's day or night based on the previous change, or otherwise based on
                     * the next change, or otherwise assuming day if no data is available.
                     */
                    if (nextSun != null && nextSun.first <= end) {
                        val split = nextSun.first
                        segments.add(createWeatherRingSegment(begin, split, datum, !nextSun.second))
                        segments.add(createWeatherRingSegment(split, end, datum, nextSun.second))
                    } else {
                        val day = previousSun?.second ?: (nextSun?.second?.not() ?: true)
                        segments.add(createWeatherRingSegment(begin, end, datum, day))
                    }
                }
            }

            /*
             * Draw an arc starting at vertical and going clockwise by sweepAngle, between innerRadius and outerRadius.
             */
            fun arcPath(startAngle: Float, sweepAngle: Float, innerRadius: Float, outerRadius: Float): Path {
                val p = Path()
                /* Angle measured from x axis rather than y axis, so subtract 90 degrees */
                p.arcTo(RectF(-outerRadius, -outerRadius, outerRadius, outerRadius),
                    startAngle-90f, sweepAngle)
                p.arcTo(RectF(-innerRadius, -innerRadius, innerRadius, innerRadius),
                    startAngle+sweepAngle-90, -sweepAngle)
                p.close()
                return p
            }

            /* Translate and scale canvas so that centre is 0, 0 and radius is 1. */
            canvas.save()
            canvas.translate(mCenterX, mCenterY)
            canvas.scale(mRadius, mRadius, 0f, 0f)

            /*
             * Draw a circular graph of pressure / temperature data.
             */
            fun drawHourlyGraph(dataPoints: MutableList<Pair<Float, Float>>, maxLength: Float, color: Int) {
                if (dataPoints.isNotEmpty()) {
                    /* Avoid interpolating between first and last data points */
                    val nowAngle = hourAngle(now)
                    dataPoints.add(0, Pair(nowAngle, dataPoints[0].second))
                    dataPoints.add(Pair(nowAngle, dataPoints[dataPoints.size-1].second))

                    val path = Path()
                    for (i in 0 until dataPoints.size) {
                        val (angle, ratio) = dataPoints[i]

                        val xDir = sin(angle*PI.toFloat()/180)
                        val yDir = -cos(angle*PI.toFloat()/180)

                        /* For points after the first, interpolate with a line curved as if the graph were a perfect
                         * circle (i.e. constant pressure / temperature.
                         */
                        if (i == 0) {
                            path.moveTo(xDir * maxLength * ratio, yDir * maxLength * ratio)
                        } else {
                            val (prevAngle, prevRatio) = dataPoints[i-1]
                            val sweepAngle = (angle+360-prevAngle) % 360
                            val controlLen = maxLength / cos((sweepAngle/2f)*PI.toFloat()/180)

                            val midXDir = sin((angle-sweepAngle/2f)*PI.toFloat()/180)
                            val midYDir = -cos((angle-sweepAngle/2f)*PI.toFloat()/180)

                            val midRatio = (ratio+prevRatio)/2f

                            path.quadTo(midXDir * controlLen * midRatio, midYDir * controlLen * midRatio,
                                xDir * maxLength * ratio, yDir * maxLength * ratio)
                        }
                    }
                    path.close()

                    mLightenPaint.color = color
                    canvas.drawPath(path, mLightenPaint)
                }
            }

            drawHourlyGraph(temperatures, MINUTE_LENGTH, TEMPERATURE_FILL_COLOR)
            drawHourlyGraph(pressures, HOUR_LENGTH, PRESSURE_FILL_COLOR)

            /*
             * Draw weather data ring.
             */
            for (segment in segments) {
                var thickness = WEATHER_RING_THICKNESS
                var color: Int

                if (segment.precipExpectation >= MIN_DISPLAY_PRECIP) {
                    thickness = WEATHER_RING_THICKNESS +
                            (WEATHER_RING_MAX_THICKNESS-WEATHER_RING_THICKNESS) * segment.precipExpectation / MAX_RAIN
                    color = if (segment.day) { RAIN_COLOR } else { DARK_RAIN_COLOR }
                } else {
                    color = if (segment.day) {
                        interpolateColor(CLOUD_COLOR, CLEAR_COLOR, segment.cloudCover)
                    } else {
                        interpolateColor(DARK_CLOUD_COLOR, DARK_CLEAR_COLOR, segment.cloudCover)
                    }
                }

                /*
                 * Draw gold stars if the cloud cover at night is less than MAX_STARS_CLOUD_COVER.
                 */
                val stars: List<Pair<Float, Float>>? = if (!segment.day && segment.cloudCover < MAX_STARS_CLOUD_COVER) {
                    val stars: MutableList<Pair<Float, Float>> = mutableListOf()
                    val numStars = ceil((1f - segment.cloudCover/MAX_STARS_CLOUD_COVER) * MAX_STARS).toInt()

                    /* Stop stars sticking out at edges */
                    val spread = thickness - STAR_BORDER_EPSILON*2

                    /* Seed random number generator to keep stars from jumping each second, but ensure general variation. */
                    val random = Random(segment.startAngle.toInt()+numStars)

                    for (i in 0 until numStars) {
                        val pos = Pair(segment.startAngle + random.nextFloat()*segment.sweepAngle,
                            random.nextFloat()*spread - spread/2f)
                        stars.add(pos)
                    }
                    stars
                } else {
                    null
                }

                /*
                 * Add an extra ARC_EPSILON*WEATHER_RING_RADIUS to make sure segments overlap very slightly and avoid
                 * minor artefacts.
                 */
                val path = arcPath(
                    segment.startAngle-ARC_EPSILON*WEATHER_RING_RADIUS,
                    segment.sweepAngle+2*ARC_EPSILON*WEATHER_RING_RADIUS,
                    WEATHER_RING_RADIUS-thickness/2f,
                    WEATHER_RING_RADIUS+thickness/2f)
                mFillPaint.color = color
                canvas.drawPath(path, mFillPaint)

                if (stars != null) {
                    mFillPaint.color = STAR_COLOR
                    for (pos in stars) {
                        val xDir = sin(pos.first*PI.toFloat()/180f)
                        val yDir = -cos(pos.first*PI.toFloat()/180f)
                        val radius = WEATHER_RING_RADIUS + pos.second
                        canvas.drawCircle(xDir * radius, yDir * radius, 1f/mRadius, mFillPaint)
                    }
                }
            }

            canvas.restore()
        }

        /**
         * Calculate angle of the watch hands in degrees.
         */
        private fun secondAngle(timeInMillis: Long): Float {
            mCalendar.timeInMillis = timeInMillis
            val seconds = mCalendar.get(Calendar.SECOND)
            return seconds*6f
        }
        private fun minuteAngle(timeInMillis: Long): Float {
            mCalendar.timeInMillis = timeInMillis
            val seconds = mCalendar.get(Calendar.SECOND)
            val minutes = mCalendar.get(Calendar.MINUTE)
            val offset = seconds / 10f
            /* Face only updates once per minute in ambient mode, so want minute hand at a whole number of minutes. */
            return (minutes * 6f) +
                    if (mAmbient) 0f else offset
        }
        private fun hourAngle(timeInMillis: Long): Float {
            mCalendar.timeInMillis = timeInMillis
            val minutes = mCalendar.get(Calendar.MINUTE)
            val hours = mCalendar.get(Calendar.HOUR)
            val offset = minutes / 2f
            return hours * 30f + offset
        }

        private fun drawWatchFace(canvas: Canvas, now: Long, weatherData: WatchfaceWeatherData) {

            /* Translate and scale canvas so that centre is 0, 0 and radius is 1. */
            canvas.save()
            canvas.translate(mCenterX, mCenterY)
            canvas.scale(mRadius, mRadius, 0f, 0f)

            /*
             * Draw ticks.
             */
            for (tickIndex in 0..59) {
                var outerTickRadius = OUTER_TICK_RADIUS
                var tickLength: Float
                if (tickIndex % 5 == 0) {
                    tickLength = MAJOR_TICK_LENGTH
                    mTickPaint.color = weatherData.tickParams?.get(tickIndex)?.second ?: MAJOR_TICK_COLOR
                    mTickPaint.strokeWidth = MAJOR_TICK_THICKNESS
                } else {
                    tickLength = weatherData.tickParams?.get(tickIndex)?.first ?: MINOR_TICK_LENGTH
                    mTickPaint.color = weatherData.tickParams?.get(tickIndex)?.second ?: MINOR_TICK_COLOR
                    mTickPaint.strokeWidth = MINOR_TICK_THICKNESS
                }
                val tickRot = (tickIndex.toDouble() * Math.PI * 2.0 / 60)
                if (-Math.cos(tickRot)*OUTER_TICK_RADIUS > (mCenterY-mChinSize)/mRadius) {
                    val scale = (1f - mChinSize/mRadius) / (-Math.cos(tickRot)).toFloat()
                    outerTickRadius *= scale
                    tickLength *= scale
                }

                if (tickLength > 0) { /* Otherwise draws a dot even at length 0 */
                    val innerX = sin(tickRot).toFloat() * (outerTickRadius - tickLength)
                    val innerY = (-Math.cos(tickRot)).toFloat() * (outerTickRadius - tickLength)
                    val outerX = sin(tickRot).toFloat() * outerTickRadius
                    val outerY = (-Math.cos(tickRot)).toFloat() * outerTickRadius
                    canvas.drawLine(
                        innerX, innerY,
                        outerX, outerY, mTickPaint
                    )
                }
            }

            /*
             * Draw a tapering watch hand with a pointed tip
             */
            fun handPath(
                thickness: Float, tipThickness: Float,
                length: Float, tipLength: Float
            ): Path {
                val p = Path()
                p.moveTo(0f, 0f)
                p.lineTo(-thickness/2f, 0f)
                p.lineTo(-tipThickness/2f, -(length - tipLength))
                p.lineTo(0f, -length)
                p.lineTo(tipThickness/2f, -(length - tipLength))
                p.lineTo(thickness/2f, 0f)
                p.close()
                return p
            }

            /*
             * Calculate hand rotations.
             */
            val secondsRotation = secondAngle(now)
            val minutesRotation = minuteAngle(now)
            val hoursRotation = hourAngle(now)

            /*
             * Draw hour hand, with a barometer-style green bar showing the current pressure.
             */

            val hourHandBackgroundColor = if (weatherData.currentPressure != null)
                GAUGE_BACKGROUND_COLOR else WATCH_HAND_COLOR

            canvas.save()
            canvas.rotate(hoursRotation, 0f, 0f)

            val hourHandPath = handPath(
                HOUR_THICKNESS, HOUR_TIP_THICKNESS, HOUR_LENGTH, HOUR_TIP_LENGTH)

            /* Dark grey background, or fill in with white if no data available */
            mFillPaint.color = hourHandBackgroundColor
            canvas.drawPath(hourHandPath, mFillPaint)

            if (weatherData.currentPressure != null) {
                val hourPressureLength = HOUR_LENGTH * pressureToRatio(weatherData.currentPressure)

                /* Green to show the pressure. */
                mFillPaint.color = PRESSURE_COLOR
                canvas.drawPath(
                    handPath(
                        HOUR_THICKNESS, HOUR_TIP_THICKNESS, hourPressureLength, 0f),
                    mFillPaint
                )

                /* White border */
                mHandStrokePaint.color = WATCH_HAND_COLOR
                mHandStrokePaint.strokeWidth = HOUR_BORDER_THICKNESS
                canvas.drawPath(hourHandPath, mHandStrokePaint)
            }

            canvas.restore()

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                canvas.save()
                canvas.rotate(secondsRotation, 0f, 0f)
                mHandStrokePaint.color = WATCH_HAND_COLOR
                mHandStrokePaint.strokeWidth = SECOND_THICKNESS

                if (weatherData.currentWindSpeed != null) {
                    fun windSpeedToRatio(windSpeed: Double): Float =
                        (windSpeed.toFloat() / 20f).coerceIn(0f, 1f)
                    val displacement = windSpeedToRatio(weatherData.currentWindSpeed)/2f
                    val sweepAngle = 4*atan(2*displacement)
                    val radius = 1/(2*sin(0.5f*sweepAngle))
                    val sweepDegrees = sweepAngle * 180f/PI.toFloat()
                    canvas.drawArc(RectF((-2*radius+displacement)*SECOND_LENGTH, -(0.5f+radius)*SECOND_LENGTH,
                        displacement*SECOND_LENGTH, -(0.5f-radius)*SECOND_LENGTH),
                        -sweepDegrees/2f, sweepDegrees, false, mHandStrokePaint)
                } else {
                    canvas.drawLine(
                        0f, 0f,
                        0f, -SECOND_LENGTH,
                        mHandStrokePaint
                    )
                }
                canvas.restore()
            }

            /*
             * Draw minute hand, with a thermometer-style red bar showing the current temperature, and ticks at every
             * 5 degrees Celsius.
             */

            val minuteHandBackgroundColor = if (weatherData.currentTemperature != null)
                GAUGE_BACKGROUND_COLOR else WATCH_HAND_COLOR

            /* Draw central circle */
            mFillPaint.color = WATCH_HAND_COLOR
            canvas.drawCircle(
                0f, 0f,
                CENTER_CIRCLE_RADIUS,
                mFillPaint
            )

            canvas.save()
            canvas.rotate(minutesRotation, 0f, 0f)

            val minuteHandPath = handPath(
                MINUTE_THICKNESS, MINUTE_TIP_THICKNESS, MINUTE_LENGTH, MINUTE_TIP_LENGTH)

            /* Dark grey background. */
            mFillPaint.color = minuteHandBackgroundColor
            canvas.drawPath(minuteHandPath, mFillPaint)

            if (weatherData.currentTemperature != null) {
                val minuteTemperatureLength = MINUTE_LENGTH * temperatureToRatio(weatherData.currentTemperature)

                /* Red to show the temperature. */
                mFillPaint.color = TEMPERATURE_COLOR
                canvas.drawPath(
                    handPath(
                        MINUTE_THICKNESS, MINUTE_TIP_THICKNESS, minuteTemperatureLength, 0f),
                    mFillPaint
                )

                /* Ticks every 5 degrees, with a more obvious tick for 0 Celsius. */
                for (temperature in -5..25 step 5){
                    mHandStrokePaint.color = if (temperature == 0) ZERO_DEGREES_COLOR else FIVE_DEGREES_COLOR
                    mHandStrokePaint.strokeWidth = if (temperature == 0) ZERO_DEGREES_THICKNESS else FIVE_DEGREES_THICKNESS
                    val ratio = temperatureToRatio(temperature.toDouble())
                    val width = (1-ratio)*MINUTE_THICKNESS + ratio*MINUTE_TIP_THICKNESS + MINUTE_BORDER_THICKNESS
                    canvas.drawLine(-width/2f, -ratio*MINUTE_LENGTH,
                        width/2f, -ratio*MINUTE_LENGTH,
                        mHandStrokePaint)
                }

                /* White border */
                mHandStrokePaint.color = WATCH_HAND_COLOR
                mHandStrokePaint.strokeWidth = MINUTE_BORDER_THICKNESS
                canvas.drawPath(minuteHandPath, mHandStrokePaint)
            }

            canvas.restore()

            if (weatherData.currentTemperature != null) {
                /* Red central circle ("mercury reservoir") */
                mFillPaint.color = TEMPERATURE_COLOR
                canvas.drawCircle(
                    0f, 0f,
                    INNER_CIRCLE_RADIUS,
                    mFillPaint
                )
            }

            canvas.restore()
        }

        /**
         * Show names and durations of calendar events in the next hour.
         */
        private fun drawCalendar(canvas: Canvas, now: Long, nextHourData: List<CalendarData>) {
            val displayNumber = minOf(nextHourData.size, CALENDAR_COLORS.size)
            for (i in 0 until displayNumber) {
                val event = nextHourData[i]
                mFillPaint.color = CALENDAR_COLORS[i]
                mCalendarPaint.color = CALENDAR_COLORS[i]

                /*
                 * Draw an arc showing when the calendar event is happening.
                 */
                // TODO what about when events overlap

                /* Translate and scale canvas so that centre is 0, 0 and radius is 1. */
                canvas.save()
                canvas.translate(mCenterX, mCenterY)
                canvas.scale(mRadius, mRadius, 0f, 0f)

                val begin = maxOf(event.begin, now)
                val end = minOf(event.end,
                    now + DateUtils.HOUR_IN_MILLIS - (CALENDAR_GAP_MINUTES*DateUtils.MINUTE_IN_MILLIS).toLong())
                val startAngle = minuteAngle(begin)
                val endAngle = minuteAngle(end)

                val sweepAngle = (endAngle+360-startAngle) % 360

                /* Angle measured from x axis rather than y axis, so subtract 90 degrees */
                canvas.drawArc(RectF(-CALENDAR_RADIUS, -CALENDAR_RADIUS, CALENDAR_RADIUS, CALENDAR_RADIUS),
                    startAngle-90, sweepAngle, false, mCalendarPaint)

                /* Restore the canvas' original orientation. */
                canvas.restore()

                /*
                 * Display the title of the event.
                 */
                if (i < CALENDAR_TEXT_HEIGHTS.size) {
                    /*
                     * Heights are listed from bottom to top. Prefer text being placed as far down as possible,
                     * but events should be listed top to bottom.
                     */
                    val height = CALENDAR_TEXT_HEIGHTS[minOf(displayNumber, CALENDAR_TEXT_HEIGHTS.size)-1-i]*mRadius
                    val width = mFillPaint.measureText(event.title)
                    canvas.drawText(event.title,
                        mCenterX-width/2f,
                        mCenterY+height,
                        mFillPaint
                    )
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerTimeZoneReceiver()
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterTimeZoneReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerTimeZoneReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@Annulus.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterTimeZoneReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@Annulus.unregisterReceiver(mTimeZoneReceiver)
        }

        /**
         * Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !mAmbient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }
    }
}


