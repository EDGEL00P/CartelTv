@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.engine.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * Live progressive retry fix: when the loader re-opens the same URI after an error, a live
 * server answers 200 from "now"; honouring the reached byte position then means silently
 * discarding hundreds of MB of fresh data to reach that offset. A retry of the same URI is
 * therefore re-opened at position 0 instead. Same-URI reopening only happens on retry, and
 * for live MPEG-TS the byte offset carries no meaning, so this is safe. Not installed for
 * HLS/DASH, where loaders retry at segment level with position 0 anyway.
 */
class LiveRetryDataSource(private val inner: DataSource) : DataSource by inner {
    private var lastUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val retry = dataSpec.uri == lastUri
        lastUri = dataSpec.uri
        val spec = if (retry && dataSpec.position != 0L) {
            dataSpec.buildUpon().setPosition(0).build()
        } else {
            dataSpec
        }
        return inner.open(spec)
    }

    class Factory(private val inner: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveRetryDataSource(inner.createDataSource())
    }
}
