package com.carteltv.tv.data.espn
import com.carteltv.tv.data.espn.EspnScoreboardClient

/** Where a sport is played; the hero's backdrop draws it, as the turf behind a network's bug. */
internal enum class PlayingSurface { FOOTBALL, PITCH, DIAMOND, COURT, ICE, TENNIS, CAGE, RING, TRACK, GOLF, CRICKET, POOL }

/** The surface for an ESPN sport path such as "football/nfl" or "mma/ufc"; null when the sport has no stock look. */
internal fun surfaceForPath(path: String): PlayingSurface? = when (path.substringBefore('/')) {
    "football" -> PlayingSurface.FOOTBALL
    "soccer", "rugby", "rugby-league", "australian-football", "field-hockey", "lacrosse" -> PlayingSurface.PITCH
    "baseball" -> PlayingSurface.DIAMOND
    "basketball", "volleyball" -> PlayingSurface.COURT
    "hockey" -> PlayingSurface.ICE
    "tennis" -> PlayingSurface.TENNIS
    "mma" -> PlayingSurface.CAGE
    "golf" -> PlayingSurface.GOLF
    "racing" -> PlayingSurface.TRACK
    "cricket" -> PlayingSurface.CRICKET
    "water-polo" -> PlayingSurface.POOL
    else -> null
}

/** The surface for a scoreboard league label such as "NCAAF" or "Ligue1". */
internal fun surfaceForLeague(label: String): PlayingSurface? = surfaceForPath(LeagueCatalog.pathFor(label))

/** The surface for a sport name as `events.json` writes it ("UFC", "Boxing", "Motorsport", "NFL"...). */
internal fun surfaceForSport(sport: String): PlayingSurface? = when (sport.lowercase()) {
    "ufc", "mma" -> PlayingSurface.CAGE
    "boxing", "wrestling", "ppv" -> PlayingSurface.RING
    "motorsport" -> PlayingSurface.TRACK
    "golf" -> PlayingSurface.GOLF
    "tennis" -> PlayingSurface.TENNIS
    "soccer", "rugby", "australian" -> PlayingSurface.PITCH
    "nfl" -> PlayingSurface.FOOTBALL
    "nba" -> PlayingSurface.COURT
    "nhl" -> PlayingSurface.ICE
    "mlb" -> PlayingSurface.DIAMOND
    "cricket" -> PlayingSurface.CRICKET
    else -> null
}
