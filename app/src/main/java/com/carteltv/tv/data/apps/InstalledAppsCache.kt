package com.carteltv.tv.data.apps

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide snapshot of installed launchable apps and hardware TV inputs.
 * Loaded at boot and refreshed live by [com.carteltv.tv.system.PackageChangedReceiver].
 *
 * Reloads run one at a time and a burst of requests collapses into a single follow-up load, so a
 * bulk app update cannot stack icon renders in memory or let an older load overwrite a newer one.
 */
object InstalledAppsCache {
    private const val BURST_SETTLE_MS = 300L

    var apps: List<LauncherApp> by mutableStateOf(emptyList())
    var tvInputs: List<TvInputItem> by mutableStateOf(emptyList())
    var version: Int by mutableIntStateOf(0)

    private val requests = Channel<Context>(Channel.CONFLATED)
    private val consumerStarted = AtomicBoolean(false)

    fun preload(ctx: Context) {
        if (consumerStarted.compareAndSet(false, true)) {
            com.carteltv.tv.AppScope.io.launch {
                var first = true
                for (appCtx in requests) {
                    if (!first) delay(BURST_SETTLE_MS)
                    first = false
                    try {
                        apps = AppsRepository.load(appCtx)
                        tvInputs = TvInputsRepository.load(appCtx)
                        version++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // keep the previous snapshot; the next package event retries
                    }
                }
            }
        }
        requests.trySend(ctx.applicationContext)
    }

    fun reload(ctx: Context) = preload(ctx)
}
