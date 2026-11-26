package com.carteltv.tv.ui.screensaver

import android.service.dreams.DreamService
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * "Night Clock" dream — big thin clock, brand dot, date. Custom-drawn so it
 * needs no Compose scaffolding inside a DreamService (which has no composition).
 */
class CartelScreensaver : DreamService() {

    private val updateTick = object : Runnable {
        override fun run() {
            rootView?.updateClock()
            rootView?.postDelayed(this, 30_000)
        }
    }
    private var rootView: NightClockView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false

        val view = NightClockView(this)
        rootView = view
        setContentView(view, FrameLayout.LayoutParams(-1, -1))
        view.post(updateTick)
    }

    override fun onDetachedFromWindow() {
        rootView?.removeCallbacks(updateTick)
        rootView = null
        super.onDetachedFromWindow()
    }
}
