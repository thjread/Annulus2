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
import android.provider.CalendarContract
import android.support.v4.app.ActivityCompat
import android.support.v4.app.ActivityCompat.startActivityForResult
import android.support.v4.content.ContextCompat
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowInsets
import android.widget.Toast

import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.TimeZone
import java.util.jar.Manifest

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

private const val MAJOR_TICK_THICKNESS = 0.02f
private const val MINOR_TICK_THICKNESS = 0.01f
private const val OUTER_TICK_RADIUS = 0.9375f
private const val MAJOR_TICK_LENGTH = 0.1875f
private const val MINOR_TICK_LENGTH = 0.0625f

private const val HOUR_LENGTH = 0.5f
private const val HOUR_THICKNESS = 0.05f
private const val HOUR_TIP_THICKNESS = 0.04f
private const val HOUR_TIP_LENGTH = 0.04f

private const val MINUTE_LENGTH = 0.75f
private const val MINUTE_THICKNESS = 0.04f
private const val MINUTE_TIP_THICKNESS = 0.032f
private const val MINUTE_TIP_LENGTH = 0.04f

private const val SECOND_LENGTH = 0.875f
private const val SECOND_THICKNESS = 0.02f

private const val CENTER_CIRCLE_RADIUS = 0.04f

private const val WATCH_HAND_COLOR = Color.WHITE
private const val WATCH_HAND_HIGHLIGHT_COLOR = Color.WHITE // TODO: Do we actually want a highlight color?
private const val BACKGROUND_COLOR = Color.BLACK

private const val PERMISSIONS_CODE = 0
private const val CALENDAR_PERMISSION_GRANTED = 0
private const val KEY_RECEIVER = "KEY_RECEIVER"

/**
 * Need an Activity to request permissions. Communicates back to Service using a ResultReceiver passed in the Intent.
 */
class PermissionActivity : Activity() {// TODO better logging messages
    var mCalendarPermissionGranted = false

    override fun onStart() {
        super.onStart()

        ActivityCompat.requestPermissions(this,
            arrayOf(android.Manifest.permission.READ_CALENDAR), PERMISSIONS_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSIONS_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mCalendarPermissionGranted = true
                Log.d("Annulus", "Activity received calendar permission")
            } else {
                Log.d("Annulus", "Activity failed to receive calendar permission")
            }
            finish()
        }
    }

    override fun finish() {
        val receiver: ResultReceiver = getIntent().getParcelableExtra(KEY_RECEIVER)

        if (mCalendarPermissionGranted) {
            receiver.send(CALENDAR_PERMISSION_GRANTED, null)
            Log.d("Annulus", "Activity sending that calendar permission is granted")
        } else {
            Log.d("Annulus", "Activity finishing without calendar permission")
        }

        super.finish()
    }
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

    private var mCalendarPermissionGranted: Boolean = false

    inner class MessageReceiver : ResultReceiver(null) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            super.onReceiveResult(resultCode, resultData)
            if (resultCode == CALENDAR_PERMISSION_GRANTED) {
                Log.d("Annulus", "Result receiver received that calendar permission is granted")
                mCalendarPermissionGranted = true
            } else {
                Log.d("Annulus", "Result receiver received different code")
            }
        }
    }

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

        private lateinit var mHourPaint: Paint
        private lateinit var mMinutePaint: Paint
        private lateinit var mSecondPaint: Paint
        private lateinit var mTickPaint: Paint
        private lateinit var mCirclePaint: Paint

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

        private lateinit var mCalendarDataSource: CalendarDataSource

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            /*
             * Ask for READ_CALENDAR permission if we don't already have it
             */
            if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED) {
                mCalendarPermissionGranted = true
            } else {
                val permissionIntent = Intent(applicationContext, PermissionActivity::class.java)
                permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                permissionIntent.putExtra(KEY_RECEIVER, MessageReceiver())
                startActivity(permissionIntent)
            }

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@Annulus)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            mCalendar = Calendar.getInstance()

            mCalendarDataSource = CalendarDataSource(contentResolver)
            initializeWatchFace()
        }

        private fun initializeWatchFace() {
            mHourPaint = Paint().apply {
                strokeWidth = 0F
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            mMinutePaint = Paint().apply {
                strokeWidth = 0F
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            mSecondPaint = Paint().apply {
                strokeWidth = SECOND_THICKNESS
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }

            mTickPaint = Paint().apply {
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }

            mCirclePaint = Paint().apply {
                strokeWidth = 0f
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            /* Set colors */
            updateWatchHandStyle()
        }

        private fun updateWatchHandStyle() {
            if (mAmbient) {
                mHourPaint.color = Color.WHITE
                mMinutePaint.color = Color.WHITE
                mSecondPaint.color = Color.WHITE
                mTickPaint.color = Color.WHITE
                mCirclePaint.color = Color.WHITE

                if (mLowBitAmbient) {
                    mHourPaint.isAntiAlias = false
                    mMinutePaint.isAntiAlias = false
                    mSecondPaint.isAntiAlias = false
                    mTickPaint.isAntiAlias = false
                    mCirclePaint.isAntiAlias = false
                }
            } else {
                mHourPaint.color = WATCH_HAND_COLOR
                mMinutePaint.color = WATCH_HAND_COLOR
                mSecondPaint.color = WATCH_HAND_HIGHLIGHT_COLOR
                mTickPaint.color = WATCH_HAND_COLOR
                mCirclePaint.color = WATCH_HAND_HIGHLIGHT_COLOR
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

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                mHourPaint.alpha = if (inMuteMode) 100 else 255
                mMinutePaint.alpha = if (inMuteMode) 100 else 255
                mSecondPaint.alpha = if (inMuteMode) 80 else 255
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
                    // The user has completed the tap gesture.
                    if (mCalendarPermissionGranted) {// TODO don't repeat this check, move into a function
                        mCalendarDataSource.updateCalendarData()
                    }
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            if (mCalendarPermissionGranted) {
                mCalendarDataSource.updateCalendarData()// TODO don't update every tick
                Log.d("Annulus", mCalendarDataSource.mCalendarData.toString())
            }

            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now

            canvas.drawRGB(Color.red(BACKGROUND_COLOR), Color.green(BACKGROUND_COLOR), Color.blue(BACKGROUND_COLOR))
            drawWatchFace(canvas)
        }

        private fun drawWatchFace(canvas: Canvas) {

            /*
             * Translate and scale canvas so that centre is 0, 0 and radius is 1
             */
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
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            val seconds = mCalendar.get(Calendar.SECOND)
            val secondsRotation = seconds * 6f

            val minutes = mCalendar.get(Calendar.MINUTE)
            val minuteHandOffset = seconds / 10f
            val minutesRotation = (minutes * 6f) + run {
                if (mAmbient) 0f else minuteHandOffset
            }

            val hours = mCalendar.get(Calendar.HOUR)
            val hourHandOffset = minutes / 2f
            val hoursRotation = hours * 30f + hourHandOffset

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

            canvas.save()
            canvas.rotate(hoursRotation, 0f, 0f)
            canvas.drawPath(
                handPath(
                    HOUR_THICKNESS, HOUR_TIP_THICKNESS, HOUR_LENGTH,
                    HOUR_TIP_LENGTH),
                mHourPaint)
            canvas.restore()

            canvas.save()
            canvas.rotate(minutesRotation, 0f, 0f)
            canvas.drawPath(
                handPath(
                    MINUTE_THICKNESS, MINUTE_TIP_THICKNESS, MINUTE_LENGTH,
                    MINUTE_TIP_LENGTH),
                mMinutePaint)
            canvas.restore()

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                canvas.save()
                canvas.rotate(secondsRotation, 0f, 0f)
                canvas.drawLine(
                    0f, 0f,
                    0f, -SECOND_LENGTH,
                    mSecondPaint
                )
                canvas.restore()
            }

            canvas.drawCircle(
                0f, 0f,
                CENTER_CIRCLE_RADIUS,
                mCirclePaint
            )

            /* Restore the canvas' original orientation. */
            canvas.restore()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@Annulus.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
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


