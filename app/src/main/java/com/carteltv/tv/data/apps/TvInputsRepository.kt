package com.carteltv.tv.data.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hardware TV/HDMI passthrough input discovered via [TvInputManager]. */
data class TvInputItem(
    val id: String,
    val label: String,
    val type: Int,
) {
    val badge: String
        get() = when (type) {
            TvInputInfo.TYPE_HDMI -> "HDMI"
            TvInputInfo.TYPE_DISPLAY_PORT -> "DP"
            TvInputInfo.TYPE_COMPOSITE -> "AV"
            TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_VGA -> "COMP"
            else -> "INPUT"
        }
}

/**
 * Discovers and switches hardware passthrough inputs (HDMI, DisplayPort, Composite, Component)
 * on Android TV sets and receivers. Returns empty cleanly on set-top boxes without passthrough inputs.
 */
object TvInputsRepository {

    private const val TAG = "TvInputsRepo"

    private val PASSTHROUGH_TYPES = setOf(
        TvInputInfo.TYPE_HDMI,         // 1007
        TvInputInfo.TYPE_COMPONENT,    // 1004
        TvInputInfo.TYPE_COMPOSITE,    // 1001
        TvInputInfo.TYPE_DISPLAY_PORT, // 1008
    )

    suspend fun load(ctx: Context): List<TvInputItem> = withContext(Dispatchers.IO) {
        val mgr = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return@withContext emptyList()
        runCatching {
            mgr.tvInputList
                .filter { it.type in PASSTHROUGH_TYPES }
                .sortedBy { it.loadLabel(ctx).toString().lowercase() }
                .map { info ->
                    TvInputItem(
                        id = info.id,
                        label = info.loadLabel(ctx).toString().ifBlank { info.id },
                        type = info.type,
                    )
                }
        }.onFailure { e ->
            Log.w(TAG, "Unable to read TV inputs", e)
        }.getOrDefault(emptyList())
    }

    fun launch(ctx: Context, inputId: String): Boolean {
        runCatching {
            val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(intent)
                return true
            }
        }.onFailure { e ->
            Log.w(TAG, "TvContract passthrough launch failed for $inputId", e)
        }

        return runCatching {
            val flat = inputId.substringBeforeLast('/')
            val cn = ComponentName.unflattenFromString(flat) ?: return false
            val intent = Intent(Intent.ACTION_MAIN)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        }.onFailure { e ->
            Log.w(TAG, "Direct component launch failed for $inputId", e)
        }.getOrDefault(false)
    }
}
