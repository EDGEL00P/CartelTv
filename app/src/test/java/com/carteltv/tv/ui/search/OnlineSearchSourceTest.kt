package com.carteltv.tv.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchSourceTest {
    @Test
    fun peopleAndIncompleteRowsAreLeftOut() {
        val json = """{"results":[
          {"media_type":"person","id":1,"name":"Someone"},
          {"media_type":"movie","id":603,"title":"The Matrix","release_date":"1999-03-31","poster_path":"/m.jpg"},
          {"media_type":"tv","id":1399,"name":"Game of Thrones","first_air_date":"2011-04-17"},
          {"media_type":"movie","id":0}
        ]}"""
        val out = TmdbOnline.parseSearch(json)
        assertEquals(2, out.size)
        assertEquals("tmdb:movie:603", out[0].url)
        assertEquals("MOVIE", out[0].kind)
        assertEquals(" (1999)", out[0].subtitle)
        assertEquals("tmdb:tv:1399", out[1].url)
        assertEquals("SERIES", out[1].kind)
    }

    @Test
    fun anUnparseableBodyIsEmpty() {
        assertTrue(TmdbOnline.parseSearch("not json").isEmpty())
    }
}
