package com.carteltv.tv.data.apps

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Decoded launcher icons, so rebuilding a shelf does not re-decode every PNG. Byte-budgeted. */
object AppIconLoader {
    private const val BUDGET_KB = 16 * 1024

    private val lru = object : LruCache<String, ImageBitmap>(BUDGET_KB) {
        override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    fun get(app: LauncherApp): ImageBitmap? {
        if (app.iconPng.isEmpty()) return null
        val key = "${app.packageName}:${app.iconPng.size}"
        synchronized(lru) { lru.get(key) }?.let { return it }
        val bmp = BitmapFactory.decodeByteArray(app.iconPng, 0, app.iconPng.size)?.asImageBitmap() ?: return null
        synchronized(lru) { lru.put(key, bmp) }
        return bmp
    }
}
