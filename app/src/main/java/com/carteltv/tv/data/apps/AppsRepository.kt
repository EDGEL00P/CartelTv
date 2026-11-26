package com.carteltv.tv.data.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.carteltv.tv.data.preferences.AppPreferences

/** Launchable apps on the device (leanback + standard launcher merged), for the home apps rail. */
data class LauncherApp(
    val label: String,
    val packageName: String,
    val iconPng: ByteArray, // ARGB bitmap png-encoded for stable Compose display
)

object AppsRepository {
    private const val TAG = "AppsRepository"

    private fun drawableToPng(d: Drawable, size: Int = 192): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        return out.toByteArray()
    }

    /**
     * Launch fallback chain:
     * 1. Leanback TV launch intent (fixes TV-only apps that return null for getLaunchIntentForPackage)
     * 2. Standard launcher intent (covers sideloaded phone/tablet apps)
     * 3. Explicit Settings action for com.android.settings
     */
    fun launchApp(ctx: Context, packageName: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: (if (packageName == "com.android.settings") Intent(Settings.ACTION_SETTINGS) else null)
            ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        }.onFailure { e ->
            Log.e(TAG, "Failed to launch $packageName", e)
        }.getOrDefault(false)
    }

    suspend fun load(ctx: Context): List<LauncherApp> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val results = LinkedHashMap<String, LauncherApp>()

        val activePack = AppPreferences.iconPack

        fun collect(category: String) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || pkg == "com.android.tv.settings") continue
                if (results.containsKey(pkg)) continue
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: pkg
                val comp = android.content.ComponentName(pkg, ri.activityInfo.name)
                val themedIcon = if (activePack.isNotBlank()) {
                    IconPackRegistry.loadIcon(ctx, activePack, pkg, comp)
                } else null
                val icon = themedIcon
                    ?: runCatching { ri.loadIcon(pm) }.getOrNull()
                    ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                    ?: continue
                results[pkg] = LauncherApp(label, pkg, drawableToPng(icon))
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)
        collect(Intent.CATEGORY_LAUNCHER)

        results.values.sortedBy { app ->
            val l = app.label.lowercase()
            val clutter = listOf("upgrade", "input viewer", "locator", "setup", "widget", "accessor", "diagnos", "feedback", "message", "registration", "welcome", "factory")
            when {
                "youtube" in l || "netflix" in l || "disney" in l || "prime" in l ||
                    "vesper" in l || "tizentube" in l || "downloader" in l || "plex" in l -> 0
                "iptv" in l || l.endsWith("tv") || "tv " in l || "play store" in l || "files" in l -> 1
                clutter.any { it in l } -> 9
                else -> 5
            }
        }
    }
}
