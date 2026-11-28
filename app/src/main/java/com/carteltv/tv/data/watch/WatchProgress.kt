package com.carteltv.tv.data.watch

import androidx.compose.ui.graphics.ImageBitmap

data class WatchProgress(
    val isMovie: Boolean = false,
    val title: String,
    val traktId: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeTitle: String? = null,
    val progress: Float = 0f,
    val runtime: Int? = null,
    val upNext: Boolean = false,
    val sortTime: Long = 0,
    val backdrop: String? = null,
    val thumb: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val historyId: String? = null,
    val pkg: String? = null,
    val appName: String? = null,
    val subtitle: String? = null,
    val isNew: Boolean = false,
    val credit: String? = null,
)
