package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.weather.Place

/**
 * Weather preferences: location, units, refresh, radar, places, air quality.
 *
 * Extracted from [AppPreferences] — weather settings have their own responsibility.
 */
class WeatherPreferences(private val sp: SharedPreferences) {

    var weatherLat: Double
        get() = Double.fromBits(sp.getLong("wlat", (-27.47).toRawBits()))
        set(v) = sp.edit().putLong("wlat", v.toRawBits()).apply()
    var weatherLon: Double
        get() = Double.fromBits(sp.getLong("wlon", (153.02).toRawBits()))
        set(v) = sp.edit().putLong("wlon", v.toRawBits()).apply()
    var cityName: String
        get() = sp.getString("city", "Brisbane") ?: "Brisbane"
        set(v) = sp.edit().putString("city", v).apply()

    /** Resolve the location from the network (IP) every time the Weather screen opens. */
    var weatherAuto: Boolean
        get() = sp.getBoolean("w_auto", false)
        set(v) = sp.edit().putBoolean("w_auto", v).apply()
    var weatherFahrenheit: Boolean
        get() = sp.getBoolean("w_f", false)
        set(v) = sp.edit().putBoolean("w_f", v).apply()
    /** One of [com.carteltv.tv.data.weather.WindUnit] names. */
    var weatherWindUnit: String
        get() = sp.getString("w_wind", "KMH") ?: "KMH"
        set(v) = sp.edit().putString("w_wind", v).apply()
    var weatherRefreshMin: Int
        get() = sp.getInt("w_refresh", 15)
        set(v) = sp.edit().putInt("w_refresh", v).apply()
    var weatherDecimals: Boolean
        get() = sp.getBoolean("w_dec", false)
        set(v) = sp.edit().putBoolean("w_dec", v).apply()
    /** Replace model "now" with the nearest airport observation when one is close and fresh. */
    var weatherUseStation: Boolean
        get() = sp.getBoolean("w_station", true)
        set(v) = sp.edit().putBoolean("w_station", v).apply()
    /** Opt-in: the Blitzortung community websocket. Its operators restrict app use, so it is never silent. */
    var lightningFeed: Boolean
        get() = sp.getBoolean("w_bolt_feed", false)
        set(v) = sp.edit().putBoolean("w_bolt_feed", v).apply()
    var weatherAmbient: Boolean
        get() = sp.getBoolean("w_ambient", true)
        set(v) = sp.edit().putBoolean("w_ambient", v).apply()
    var radarZoom: Int
        get() = sp.getInt("rad_zoom", 6).coerceIn(3, 7)
        set(v) = sp.edit().putInt("rad_zoom", v.coerceIn(3, 7)).apply()
    var radarFrameMs: Int
        get() = sp.getInt("rad_ms", 600).coerceIn(200, 1500)
        set(v) = sp.edit().putInt("rad_ms", v.coerceIn(200, 1500)).apply()
    var radarOpacity: Int
        get() = sp.getInt("rad_op", 80).coerceIn(30, 100)
        set(v) = sp.edit().putInt("rad_op", v.coerceIn(30, 100)).apply()
    var radarLabels: Boolean
        get() = sp.getBoolean("rad_lbl", true)
        set(v) = sp.edit().putBoolean("rad_lbl", v).apply()
    var radarLightning: Boolean
        get() = sp.getBoolean("rad_bolt", true)
        set(v) = sp.edit().putBoolean("rad_bolt", v).apply()
    var radarAutoPlay: Boolean
        get() = sp.getBoolean("rad_play", true)
        set(v) = sp.edit().putBoolean("rad_play", v).apply()
    /** Saved places, one `name|region|country|lat|lon` per line. */
    var weatherPlaces: List<Place>
        get() = (sp.getString("w_places", null) ?: "").lines().mapNotNull(Place::decode)
        set(v) = sp.edit().putString("w_places", v.joinToString("\n") { it.encode() }).apply()
    var weatherAqi: Boolean
        get() = sp.getBoolean("w_aqi", true)
        set(v) = sp.edit().putBoolean("w_aqi", v).apply()
}
