package com.carteltv.tv.data.weather

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline sun and moon, from the low-precision orbital elements in Schlyter's "How to compute planetary
 * positions". Sun events land within about a minute and the moon within a few, which is plenty for a TV.
 * Nothing here touches the network.
 */
object SunMoonTimes {
    private const val J2000_MS = 946_728_000_000L          // 2000-01-01T12:00:00Z
    private const val DEG = PI / 180.0
    const val SYNODIC_DAYS = 29.530588
    private const val ELONGATION_DEG_PER_DAY = 12.190749

    data class Pos(val altDeg: Double, val azDeg: Double)

    data class SunDay(
        val dawn: Int?, val sunrise: Int?, val noon: Int, val goldenAm: Int?, val goldenPm: Int?, val sunset: Int?, val dusk: Int?,
        val dayLengthMin: Double?, val dayDeltaSec: Int?, val maxAltDeg: Double, val polarNight: Boolean, val midnightSun: Boolean,
    )

    data class MoonDay(
        val phaseDeg: Double, val illumination: Double, val ageDays: Double, val name: String,
        val moonrise: Int?, val moonset: Int?, val altNowDeg: Double, val nextFullMs: Long, val nextNewMs: Long,
        val springTides: Boolean, val waxing: Boolean,
    )

    private fun days(ms: Long) = (ms - J2000_MS) / 86_400_000.0                // days since J2000.0
    private fun rev(x: Double) = ((x % 360) + 360) % 360

    private class Body(val lonDeg: Double, val latDeg: Double, val distance: Double)

    private fun obliquity(d: Double) = 23.4393 - 3.563e-7 * d

    private fun sunBody(d: Double): Body {
        val ds = d + 1.5                                                         // Schlyter counts from 1999-12-31
        val w = 282.9404 + 4.70935e-5 * ds
        val e = 0.016709 - 1.151e-9 * ds
        val m = rev(356.0470 + 0.9856002585 * ds)
        val ea = m + e / DEG * sin(m * DEG) * (1 + e * cos(m * DEG))
        val xv = cos(ea * DEG) - e
        val yv = sqrt(1 - e * e) * sin(ea * DEG)
        return Body(rev(atan2(yv, xv) / DEG + w), 0.0, sqrt(xv * xv + yv * yv))
    }

    private fun moonBody(d: Double): Body {
        val ds = d + 1.5
        val n = rev(125.1228 - 0.0529538083 * ds)
        val w = 318.0634 + 0.1643573223 * ds
        val e = 0.0549
        val m = rev(115.3654 + 13.0649929509 * ds)
        var ea = m + e / DEG * sin(m * DEG) * (1 + e * cos(m * DEG))
        ea -= (ea - e / DEG * sin(ea * DEG) - m) / (1 - e * cos(ea * DEG))
        val xv = 60.2666 * (cos(ea * DEG) - e)
        val yv = 60.2666 * sqrt(1 - e * e) * sin(ea * DEG)
        val v = atan2(yv, xv) / DEG
        var r = sqrt(xv * xv + yv * yv)
        val vw = (v + w) * DEG
        val i = 5.1454 * DEG
        val xh = r * (cos(n * DEG) * cos(vw) - sin(n * DEG) * sin(vw) * cos(i))
        val yh = r * (sin(n * DEG) * cos(vw) + cos(n * DEG) * sin(vw) * cos(i))
        val zh = r * sin(vw) * sin(i)
        var lon = atan2(yh, xh) / DEG
        var lat = atan2(zh, sqrt(xh * xh + yh * yh)) / DEG

        val ms = rev(356.0470 + 0.9856002585 * ds)
        val ws = 282.9404 + 4.70935e-5 * ds
        val ls = ms + ws
        val lm = m + w + n
        val dd = (lm - ls) * DEG
        val f = (lm - n) * DEG
        val mm = m * DEG
        val msr = ms * DEG
        lon += -1.274 * sin(mm - 2 * dd) + 0.658 * sin(2 * dd) - 0.186 * sin(msr) - 0.059 * sin(2 * mm - 2 * dd) -
            0.057 * sin(mm - 2 * dd + msr) + 0.053 * sin(mm + 2 * dd) + 0.046 * sin(2 * dd - msr) + 0.041 * sin(mm - msr) -
            0.035 * sin(dd) - 0.031 * sin(mm + msr) - 0.015 * sin(2 * f - 2 * dd) + 0.011 * sin(mm - 4 * dd)
        lat += -0.173 * sin(f - 2 * dd) - 0.055 * sin(mm - f - 2 * dd) - 0.046 * sin(mm + f - 2 * dd) +
            0.033 * sin(f + 2 * dd) + 0.017 * sin(2 * mm + f)
        r += -0.58 * cos(mm - 2 * dd) - 0.46 * cos(2 * dd)
        return Body(rev(lon), lat, r)
    }

    private fun horizontal(b: Body, ms: Long, lat: Double, lon: Double): Pos {
        val d = days(ms)
        val ecl = obliquity(d) * DEG
        val xe = b.distance * cos(b.lonDeg * DEG) * cos(b.latDeg * DEG)
        val ye = b.distance * sin(b.lonDeg * DEG) * cos(b.latDeg * DEG)
        val ze = b.distance * sin(b.latDeg * DEG)
        val yq = ye * cos(ecl) - ze * sin(ecl)
        val zq = ye * sin(ecl) + ze * cos(ecl)
        val ra = atan2(yq, xe)
        val dec = atan2(zq, sqrt(xe * xe + yq * yq))
        val lst = (280.46061837 + 360.98564736629 * d + lon) * DEG
        val ha = lst - ra
        val la = lat * DEG
        val alt = asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha))
        val az = atan2(-sin(ha) * cos(dec), sin(dec) * cos(la) - cos(dec) * sin(la) * cos(ha))
        return Pos(alt / DEG, rev(az / DEG))
    }

    fun sunPos(ms: Long, lat: Double, lon: Double) = horizontal(sunBody(days(ms)), ms, lat, lon)
    fun moonPos(ms: Long, lat: Double, lon: Double) = horizontal(moonBody(days(ms)), ms, lat, lon)

    /** Elongation of the moon from the sun: 0 new, 90 first quarter, 180 full, 270 last quarter. */
    fun phaseDeg(ms: Long): Double {
        val d = days(ms)
        return rev(moonBody(d).lonDeg - sunBody(d).lonDeg)
    }

    /** Minute-of-local-day crossings of `thresholdDeg`, rising and falling, scanning the day minute by minute. */
    private fun crossings(dayStartMs: Long, thresholdDeg: Double, alt: (Long) -> Double): Pair<Int?, Int?> {
        var rise: Int? = null
        var set: Int? = null
        var prev = alt(dayStartMs) - thresholdDeg
        for (m in 1..1440) {
            val cur = alt(dayStartMs + m * 60_000L) - thresholdDeg
            if (prev < 0 && cur >= 0 && rise == null) rise = m.coerceAtMost(1439)
            if (prev >= 0 && cur < 0 && set == null) set = m.coerceAtMost(1439)
            prev = cur
        }
        return rise to set
    }

    fun sunDay(dayStartMs: Long, lat: Double, lon: Double): SunDay {
        val alt = { t: Long -> sunPos(t, lat, lon).altDeg }
        var noon = 0
        var best = -91.0
        for (m in 0..1439) {
            val a = alt(dayStartMs + m * 60_000L)
            if (a > best) { best = a; noon = m }
        }
        val (sunrise, sunset) = crossings(dayStartMs, -0.833, alt)
        val (dawn, dusk) = crossings(dayStartMs, -6.0, alt)
        val (goldenAm, goldenPm) = crossings(dayStartMs, 6.0, alt)
        fun length(start: Long): Double? {
            val (r, s) = crossings(start, -0.833, alt)
            return if (r != null && s != null && s > r) (s - r).toDouble() else null
        }
        val len = length(dayStartMs)
        val prevLen = length(dayStartMs - 86_400_000L)
        return SunDay(
            dawn, sunrise, noon, goldenAm, goldenPm, sunset, dusk, len,
            if (len != null && prevLen != null) ((len - prevLen) * 60).toInt() else null,
            best, polarNight = best < -0.833, midnightSun = sunrise == null && sunset == null && best > -0.833,
        )
    }

    fun moonDay(nowMs: Long, dayStartMs: Long, lat: Double, lon: Double): MoonDay {
        val d = phaseDeg(nowMs)
        val (rise, set) = crossings(dayStartMs, 0.125) { moonPos(it, lat, lon).altDeg }
        val toFull = nextPhase(nowMs, 180.0)
        val toNew = nextPhase(nowMs, 0.0)
        val nearSyzygy = minOf(abs(d - 180.0), if (d > 180) 360 - d else d).let { it < 24.0 }
        return MoonDay(
            phaseDeg = d, illumination = (1 - cos(d * DEG)) / 2, ageDays = d / 360.0 * SYNODIC_DAYS, name = phaseName(d),
            moonrise = rise, moonset = set, altNowDeg = moonPos(nowMs, lat, lon).altDeg,
            nextFullMs = toFull, nextNewMs = toNew, springTides = nearSyzygy, waxing = d < 180.0,
        )
    }

    private fun nextPhase(fromMs: Long, target: Double): Long {
        var t = fromMs + (((target - phaseDeg(fromMs) + 360) % 360) / ELONGATION_DEG_PER_DAY * 86_400_000.0).toLong()
        repeat(4) {
            val err = ((target - phaseDeg(t) + 540) % 360) - 180
            t += (err / ELONGATION_DEG_PER_DAY * 86_400_000.0).toLong()
        }
        return t
    }

    fun phaseName(d: Double) = when {
        d < 22.5 || d >= 337.5 -> "New Moon"
        d < 67.5 -> "Waxing Crescent"
        d < 112.5 -> "First Quarter"
        d < 157.5 -> "Waxing Gibbous"
        d < 202.5 -> "Full Moon"
        d < 247.5 -> "Waning Gibbous"
        d < 292.5 -> "Last Quarter"
        else -> "Waning Crescent"
    }
}
