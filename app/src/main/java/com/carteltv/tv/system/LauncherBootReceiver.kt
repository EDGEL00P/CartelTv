package com.carteltv.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.carteltv.tv.AppScope
import com.carteltv.tv.MainActivity
import com.carteltv.tv.alert.EpisodeAlertScheduler
import com.carteltv.tv.alert.LiveScoreMonitor
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.remap.HomeOverride
import com.carteltv.tv.recording.RecordingScheduler
import kotlinx.coroutines.launch

/**
 * Single boot coordinator: re-arms scheduled DVR jobs, episode alerts, and live
 * game tracking on BOOT_COMPLETED, and relaunches CartelTV when [AppPreferences.startOnBoot] is enabled.
 */
class LauncherBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val appCtx = context.applicationContext
        AppPreferences.init(appCtx)

        // Re-arming does disk and alarm work; keep it off the main thread without letting the process die mid-way.
        val pending = goAsync()
        AppScope.io.launch {
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    runCatching { com.carteltv.tv.data.network.PrivateDnsController(appCtx).reconcilePause() }
                    runCatching { RecordingScheduler.rearmAll(appCtx) }
                    runCatching { EpisodeAlertScheduler.armDaily(appCtx) }
                    runCatching { LiveScoreMonitor.rearmOnBoot(appCtx) }
                    runCatching { com.carteltv.tv.alert.KickoffAlertScheduler.rearmAll(appCtx) }
                }
                if (AppPreferences.startOnBoot) launchHome(appCtx)
            } finally {
                pending.finish()
            }
        }
    }

    private fun launchHome(ctx: Context) {
        runCatching {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?: Intent(ctx, MainActivity::class.java).apply {
                    setAction(Intent.ACTION_MAIN)
                    addCategory(Intent.CATEGORY_HOME)
                }
            launch.addFlags(HomeOverride.BOOT_LAUNCH_FLAGS)
            ctx.startActivity(launch)
        }.onFailure { e ->
            Log.e(TAG, "Failed to launch CartelTV on boot: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LauncherBootReceiver"
    }
}
