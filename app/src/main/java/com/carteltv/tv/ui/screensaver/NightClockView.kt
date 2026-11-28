package com.carteltv.tv.ui.screensaver

import com.carteltv.tv.R
import com.carteltv.tv.data.preferences.AppPreferences

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.graphics.toArgb
import com.carteltv.tv.ui.theme.ThemeSemantics
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Burn-in guard: how often the drift position is re-evaluated. One redraw a second is enough for a glide this slow. */
private const val DRIFT_STEP_MS = 1_000L

/** One full wander of the scene, after which it is back where it started. */
private const val DRIFT_PERIOD_MS = 64_000L

/**
 * How far the scene wanders from centre, in dp. The house has no reduce-motion preference to switch this off, so the
 * wander stays tiny: enough that no edge holds one set of pixels for hours, too small and slow to read as movement.
 */
private const val DRIFT_DP = 3f

/** Draws the whole screensaver scene: felt gradient, clock, date, brand. */
class NightClockView(ctx: Context) : View(ctx) {

    private val felt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thinWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeSemantics.textPrimary.toArgb()
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jetbrains_mono)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeSemantics.textSecondary.toArgb()
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jetbrains_mono)
        letterSpacing = 0.14f
    }
    private val timeFmt12 = SimpleDateFormat("h:mm", Locale.getDefault())
    private val timeFmt24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val ampmFmt = SimpleDateFormat("a", Locale.getDefault()).apply { timeZone = timeFmt12.timeZone }
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    /** Off when the viewer has switched animations off system-wide; re-read every time the dream starts. */
    private var drift = false
    private var driftStart = 0L
    private val driftPx = DRIFT_DP * ctx.resources.displayMetrics.density
    private val driftTick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, DRIFT_STEP_MS)
        }
    }

    init {
        setBackgroundColor(ThemeSemantics.stage.toArgb())
    }

    fun updateClock() { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The same reduce-motion signal the radar reads: a scale of 0 means animations are off.
        drift = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        driftStart = SystemClock.elapsedRealtime()
        if (drift) postDelayed(driftTick, DRIFT_STEP_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(driftTick)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        felt.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), ThemeSemantics.surfaceRaised.toArgb(), ThemeSemantics.stage.toArgb(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // The felt stays put so the wander never exposes an edge; everything on it moves together.
        canvas.drawRect(0f, 0f, w, h, felt)

        // A figure-of-eight loop that starts and ends at centre, so the scene never jumps when the drift begins.
        var dx = 0f
        var dy = 0f
        if (drift) {
            val t = 2.0 * PI * ((SystemClock.elapsedRealtime() - driftStart) % DRIFT_PERIOD_MS) / DRIFT_PERIOD_MS
            dx = (driftPx * sin(t)).toFloat()
            dy = (driftPx * sin(2 * t)).toFloat()
        }
        canvas.save()
        canvas.translate(dx, dy)

        val now = Date()
        // The house clock setting governs the dream too: the AM/PM marker only exists in 12-hour time.
        val clock24 = AppPreferences.clock24
        val cx = w / 2f

        small.textSize = min(w * 0.020f, 26f)
        canvas.drawText("C A R T E L T V", cx - small.measureText("C A R T E L T V") / 2f, h * 0.26f, small)
        brassPaint.color = ThemeSemantics.accent.toArgb()
        canvas.drawCircle(cx - small.measureText("C A R T E L T V") / 2f - 26f, h * 0.255f, 5f, brassPaint)

        val time = (if (clock24) timeFmt24 else timeFmt12).format(now)
        thinWhite.textSize = min(w * 0.23f, 340f)
        val tw = thinWhite.measureText(time)
        canvas.drawText(time, cx - tw / 2f, h * 0.55f, thinWhite)

        small.textSize = min(w * 0.024f, 30f)
        val sub = (if (clock24) "" else ampmFmt.format(now) + "   ·   ") + dateFmt.format(now)
        canvas.drawText(sub, cx - small.measureText(sub) / 2f, h * 0.65f, small)

        canvas.restore()
    }
}
