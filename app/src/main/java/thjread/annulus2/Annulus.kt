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
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sin

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

private const val MAJOR_TICK_THICKNESS = 0.05f
private const val MINOR_TICK_THICKNESS = 0.01f
private const val OUTER_TICK_RADIUS = 7.5f/8f
private const val MAJOR_TICK_LENGTH = 1f/8f
private const val MINOR_TICK_LENGTH = 0.5f/8f

private const val HOUR_LENGTH = 5f/8f
private const val HOUR_BORDER_THICKNESS = 0.015f
private const val HOUR_THICKNESS = 0.07f
private const val HOUR_TIP_THICKNESS = 0.04f
private const val HOUR_TIP_LENGTH = 0.04f

private const val MINUTE_LENGTH = 7f/8f
private const val MINUTE_BORDER_THICKNESS = 0.015f
private const val MINUTE_THICKNESS = 0.06f
private const val MINUTE_TIP_THICKNESS = 0.035f
private const val MINUTE_TIP_LENGTH = 0.04f

private const val SECOND_LENGTH = 7f/8f
private const val SECOND_THICKNESS = 0.02f

private const val CENTER_CIRCLE_RADIUS = 0.06f
private const val INNER_CIRCLE_RADIUS = CENTER_CIRCLE_RADIUS - MINUTE_BORDER_THICKNESS

// TODO move these to a values file
private const val WATCH_HAND_COLOR = Color.WHITE
private val WATCH_HAND_THERMOMETER_COLOR = Color.rgb(255, 65, 54)
private val WATCH_HAND_THERMOMETER_BACKGROUND_COLOR = Color.rgb(50, 50, 50)
private val WATCH_HAND_BAROMETER_COLOR = Color.rgb(61, 153, 112)
private val ZERO_DEGREES_COLOR = Color.rgb(127, 219, 255)
private val FIVE_DEGREES_COLOR = Color.rgb(170, 170, 170)
private const val ZERO_DEGREES_THICKNESS = 0.03f
private const val FIVE_DEGREES_THICKNESS = 0.02f
private const val BACKGROUND_COLOR = Color.BLACK
private val CALENDAR_COLORS = listOf(
    Color.rgb(33, 150, 243),
    Color.rgb(171, 71, 188),
    Color.rgb(255, 87, 34))
private const val CALENDAR_GAP_MINUTES = 1.5

private const val CALENDAR_THICKNESS = 0.02f
private const val CALENDAR_RADIUS = 0.75f
private const val CALENDAR_TEXT_SIZE = 0.18f
private val CALENDAR_TEXT_HEIGHTS = listOf(0.5f, 0.25f)

/**
 * Code to tell ResultReceiver that calendar permission is granted, and key to pass ResultReceiver in Intent.
 */
private const val PERMISSION_RESULT_CODE = 0
private const val KEY_RECEIVER = "KEY_RECEIVER"
private const val KEY_PERMISSIONS_GRANTED = "KEY_PERMISSIONS_GRANTED"

/**
 * Need an Activity to request permissions. Communicates back to Service using a ResultReceiver passed in the Intent.
 */
class PermissionActivity() : Activity() {
    var mPermissionsGranted = BooleanArray(3)

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

data class WatchfaceWeatherData (val currentTemperature: Double?, val currentPressure: Double?, val currentWindSpeed: Double?) {
    constructor(data: WeatherService.WeatherData?) : this(data?.currently?.temperature, data?.currently?.pressure, data?.currently?.windSpeed)
}

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 *
 *
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
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

    inner class Engine : CanvasWatchFaceService.Engine() {
        private lateinit var mCalendar: Calendar

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F
        private var mRadius: Float = 0F
        private var mChinSize: Float = 0F

        private lateinit var mHandFillPaint: Paint
        private lateinit var mHandStrokePaint: Paint
        private lateinit var mTickPaint: Paint
        private lateinit var mTextPaint: Paint
        private lateinit var mCalendarPaint: Paint

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false

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
            mHandFillPaint = Paint().apply {
                strokeWidth = 0F
                isAntiAlias = true
                style = Paint.Style.FILL
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

            mTextPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            mCalendarPaint = Paint().apply {
                strokeWidth = CALENDAR_THICKNESS
                isAntiAlias = true
                strokeCap = Paint.Cap.BUTT
                style = Paint.Style.STROKE
            }

            /* Set colors */
            updateWatchHandStyle()
        }

        private fun updateWatchHandStyle() {
            if (mAmbient) {
                mHandStrokePaint.color = Color.WHITE
                mTickPaint.color = Color.WHITE

                if (mLowBitAmbient) {
                    mHandFillPaint.isAntiAlias = false
                    mHandStrokePaint.isAntiAlias = false
                    mTickPaint.isAntiAlias = false
                    mTextPaint.isAntiAlias = false
                    mCalendarPaint.isAntiAlias = false
                }
            } else {
                mHandStrokePaint.color = WATCH_HAND_COLOR
                mTickPaint.color = WATCH_HAND_COLOR

                mHandFillPaint.isAntiAlias = true
                mHandStrokePaint.isAntiAlias = true
                mTickPaint.isAntiAlias = true
                mTextPaint.isAntiAlias = true
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
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                mHandFillPaint.alpha = if (inMuteMode) 100 else 255
                mHandStrokePaint.alpha = if (inMuteMode) 80 else 255
                invalidate()
            }
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

            mTextPaint.textSize = CALENDAR_TEXT_SIZE*mRadius
        }

        override fun onApplyWindowInsets(insets: WindowInsets?) {
            super.onApplyWindowInsets(insets)

            insets?.let{
                mChinSize = it.systemWindowInsetBottom.toFloat()
            }
        }

        /**
         * Captures tap event (and tap type).
         * Tapping manually triggers a calendar data update.
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
                    mCalendarDataSource?.updateCalendarData()
                    mWeatherDataSource?.updateWeatherData()
                    // TODO add feedback?
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {

            val now = System.currentTimeMillis()

            canvas.drawRGB(Color.red(BACKGROUND_COLOR), Color.green(BACKGROUND_COLOR), Color.blue(BACKGROUND_COLOR))

            val watchfaceWeatherData = WatchfaceWeatherData(mWeatherDataSource?.mWeatherData)
            drawWatchFace(canvas, now, watchfaceWeatherData)

            mWeatherDataSource?.run{
                Log.d("Annulus", mWeatherData.toString())
                updateWeatherDataIfStale()
            }

            mCalendarDataSource?.run{
                val nextHourCalendarData = nextHourCalendarData(now)
                Log.d("Annulus", nextHourCalendarData.toString())
                drawCalendar(canvas, now, nextHourCalendarData)
                updateCalendarDataIfStale()
            }
        }

        /**
         * Calculate angle of the watch hands in degrees.
         */
        private fun secondAngle(calendar: Calendar): Float {
            val seconds = calendar.get(Calendar.SECOND)
            return seconds*6f
        }
        private fun minuteAngle(calendar: Calendar): Float {
            val seconds = calendar.get(Calendar.SECOND)
            val minutes = calendar.get(Calendar.MINUTE)
            val offset = seconds / 10f
            /* Face only updates once per minute in ambient mode, so want minute hand at a whole number of minutes. */
            return (minutes * 6f) +
                    if (mAmbient) 0f else offset
        }
        private fun hourAngle(calendar: Calendar): Float {
            val minutes = calendar.get(Calendar.MINUTE)
            val hours = calendar.get(Calendar.HOUR)
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
                    mTickPaint.strokeWidth = MAJOR_TICK_THICKNESS
                } else {
                    tickLength = MINOR_TICK_LENGTH
                    mTickPaint.strokeWidth = MINOR_TICK_THICKNESS
                }
                val tickRot = (tickIndex.toDouble() * Math.PI * 2.0 / 60)
                if (-Math.cos(tickRot)*OUTER_TICK_RADIUS > (mCenterY-mChinSize)/mRadius) {
                    val scale = (1f - mChinSize/mRadius) / (-Math.cos(tickRot)).toFloat()
                    outerTickRadius *= scale
                    tickLength *= scale
                }

                val innerX = Math.sin(tickRot).toFloat() * (outerTickRadius-tickLength)
                val innerY = (-Math.cos(tickRot)).toFloat() * (outerTickRadius-tickLength)
                val outerX = Math.sin(tickRot).toFloat() * outerTickRadius
                val outerY = (-Math.cos(tickRot)).toFloat() * outerTickRadius
                canvas.drawLine(
                    innerX, innerY,
                    outerX, outerY, mTickPaint
                )
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

            mCalendar.timeInMillis = now
            val secondsRotation = secondAngle(mCalendar)
            val minutesRotation = minuteAngle(mCalendar)
            val hoursRotation = hourAngle(mCalendar)

            /*
             * Draw hour hand, with a barometer-style green bar showing the current pressure.
             */

            val hourHandBackgroundColor = if (weatherData.currentPressure != null)
                WATCH_HAND_THERMOMETER_BACKGROUND_COLOR else WATCH_HAND_COLOR

            canvas.save()
            canvas.rotate(hoursRotation, 0f, 0f)

            val hourHandPath = handPath(
                HOUR_THICKNESS, HOUR_TIP_THICKNESS, HOUR_LENGTH, HOUR_TIP_LENGTH)

            /* Dark grey background, or fill in with white if no data available */
            mHandFillPaint.color = hourHandBackgroundColor
            canvas.drawPath(hourHandPath, mHandFillPaint)

            if (weatherData.currentPressure != null) {
                /* Display pressures from 980 to 1040 hPa */
                fun pressureToRatio(pressure: Double): Float =
                    ((pressure.toFloat()-980f)/(1040f-980f)).coerceIn(0f, 1f)

                val hourPressureLength = HOUR_LENGTH * pressureToRatio(weatherData.currentPressure)

                /* Green to show the pressure. */
                mHandFillPaint.color = WATCH_HAND_BAROMETER_COLOR
                canvas.drawPath(
                    handPath(
                        HOUR_THICKNESS, HOUR_TIP_THICKNESS, hourPressureLength, 0f),
                    mHandFillPaint)

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
                    canvas.drawArc(RectF(-displacement*SECOND_LENGTH, -(0.5f+radius)*SECOND_LENGTH,
                        (2*radius-displacement)*SECOND_LENGTH, -(0.5f-radius)*SECOND_LENGTH),
                        180-sweepDegrees/2f, sweepDegrees, false, mHandStrokePaint)
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
                WATCH_HAND_THERMOMETER_BACKGROUND_COLOR else WATCH_HAND_COLOR

            /* Draw central circle */
            mHandFillPaint.color = WATCH_HAND_COLOR
            canvas.drawCircle(
                0f, 0f,
                CENTER_CIRCLE_RADIUS,
                mHandFillPaint
            )

            canvas.save()
            canvas.rotate(minutesRotation, 0f, 0f)

            val minuteHandPath = handPath(
                MINUTE_THICKNESS, MINUTE_TIP_THICKNESS, MINUTE_LENGTH, MINUTE_TIP_LENGTH)

            /* Dark grey background. */
            mHandFillPaint.color = minuteHandBackgroundColor
            canvas.drawPath(minuteHandPath, mHandFillPaint)

            if (weatherData.currentTemperature != null) {
                /* Display temperatures from -10 to 30 degrees */
                fun temperatureToRatio(temperature: Double): Float =
                    ((temperature.toFloat() + 10f) / (10f + 30f)).coerceIn(0f, 1f)

                val minuteTemperatureLength = MINUTE_LENGTH * temperatureToRatio(weatherData.currentTemperature)

                /* Red to show the temperature. */
                mHandFillPaint.color = WATCH_HAND_THERMOMETER_COLOR
                canvas.drawPath(
                    handPath(
                        MINUTE_THICKNESS, MINUTE_TIP_THICKNESS, minuteTemperatureLength, 0f),
                    mHandFillPaint)

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
                mHandFillPaint.color = WATCH_HAND_THERMOMETER_COLOR
                canvas.drawCircle(
                    0f, 0f,
                    INNER_CIRCLE_RADIUS,
                    mHandFillPaint
                )
            }

            /* Restore the canvas' original orientation. */
            canvas.restore()
        }

        /**
         * Show names and durations of calendar events in the next hour.
         */
        private fun drawCalendar(canvas: Canvas, now: Long, nextHourData: List<CalendarData>) {
            val displayNumber = minOf(nextHourData.size, CALENDAR_COLORS.size)
            for (i in 0 until displayNumber) {
                val event = nextHourData[i]
                mTextPaint.color = CALENDAR_COLORS[i]
                mCalendarPaint.color = CALENDAR_COLORS[i]

                /*
                 * Draw an arc showing when the calendar event is happening.
                 */
                // TODO what about when events overlap

                /* Translate and scale canvas so that centre is 0, 0 and radius is 1. */
                canvas.save()
                canvas.translate(mCenterX, mCenterY)
                canvas.scale(mRadius, mRadius, 0f, 0f)

                val start = maxOf(event.begin, now)
                val end = minOf(event.end,
                    now + DateUtils.HOUR_IN_MILLIS - (CALENDAR_GAP_MINUTES*DateUtils.MINUTE_IN_MILLIS).toLong())

                mCalendar.timeInMillis = start
                val startAngle = minuteAngle(mCalendar)

                mCalendar.timeInMillis = end
                val endAngle = minuteAngle(mCalendar)

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
                    val width = mTextPaint.measureText(event.title)
                    canvas.drawText(event.title,
                        mCenterX-width/2f,
                        mCenterY+height,
                        mTextPaint)
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


