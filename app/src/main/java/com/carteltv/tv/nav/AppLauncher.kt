package com.carteltv.tv.nav

import android.content.Context
import android.content.Intent
import com.carteltv.tv.MainActivity
import com.carteltv.tv.PlayerHostActivity
import com.carteltv.tv.ui.youtube.YouTubeDeepLink

/** Activity and package launchers for TV (leanback-first) and full-screen media playback. */
object AppLauncher {
    fun mainIntent(ctx: Context, goto: String? = null): Intent =
        Intent(ctx, MainActivity::class.java).apply {
            if (!goto.isNullOrBlank() && goto != "home") putExtra("goto", goto)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }

    fun launchPackage(ctx: Context, packageName: String): Boolean =
        com.carteltv.tv.data.apps.AppsRepository.launchApp(ctx, packageName)

    fun player(
        ctx: Context,
        url: String,
        title: String,
        refId: String,
        alternates: List<com.carteltv.tv.engine.playback.AlternateSource> = emptyList(),
    ) {
        YouTubeDeepLink.requestFor(refId, title)?.let {
            YouTubeDeepLink.post(it)
            Nav.push(Route.YouTube)
            return
        }
        val intent = Intent(ctx, PlayerHostActivity::class.java)
            .putExtra("url", url)
            .putExtra("title", title)
            .putExtra("refId", refId)
            .putExtra("alternates", com.carteltv.tv.engine.playback.AlternateSourceSelector.encode(alternates))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (url.startsWith("content://")) {
            intent.data = android.net.Uri.parse(url)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}
