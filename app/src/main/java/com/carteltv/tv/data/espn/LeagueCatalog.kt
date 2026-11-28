package com.carteltv.tv.data.espn

/** The leagues the app can show, as (label, ESPN path) pairs, plus the viewer's selection and regional presets. */
object LeagueCatalog {

    /** League keys this catalog shares with BroadcastNetworks — named so the two tables can never drift apart. */
    const val CHAMPIONS_LEAGUE = "ChampionsLeague"
    const val CONFERENCE_LEAGUE = "ConferenceLeague"
    const val SCOTTISH_CHAMPIONSHIP = "ScottishChampionship"
    const val SCOTTISH_LEAGUE_CUP = "ScottishLeagueCup"
    const val RUGBY_CHAMPIONSHIP = "RugbyChampionship"
    const val WORLD_BASEBALL_CLASSIC = "WorldBaseballClassic"

    /** Two-sided events only: racing and golf have a field, not a matchup, so they stay out. */
    val DEFAULT_LEAGUES = listOf(
        "NFL" to "football/nfl",
        "NBA" to "basketball/nba",
        "MLB" to "baseball/mlb",
        "NHL" to "hockey/nhl",
        "WNBA" to "basketball/wnba",
        "NCAAF" to "football/college-football",
        "NCAAB" to "basketball/mens-college-basketball",
        "MLS" to "soccer/usa.1",
        "PremierLeague" to "soccer/eng.1",
        CHAMPIONS_LEAGUE to "soccer/uefa.champions",
        "EuropaLeague" to "soccer/uefa.europa",
        "LaLiga" to "soccer/esp.1",
        "Bundesliga" to "soccer/ger.1",
        "SerieA" to "soccer/ita.1",
        "Ligue1" to "soccer/fra.1",
        "UFC" to "mma/ufc",
    )

    /** Leagues the viewer can switch on in the league manager; every path was checked against the live API. */
    val OPTIONAL_LEAGUES = listOf(
        "CFL" to "football/cfl",
        "PFL" to "mma/pfl",
        "NWSL" to "soccer/usa.nwsl",
        "Championship" to "soccer/eng.2",
        "FACup" to "soccer/eng.fa",
        "CarabaoCup" to "soccer/eng.league_cup",
        "ScottishPrem" to "soccer/sco.1",
        "Bundesliga2" to "soccer/ger.2",
        "Eredivisie" to "soccer/ned.1",
        "Primeira" to "soccer/por.1",
        "SuperLig" to "soccer/tur.1",
        "LigaMX" to "soccer/mex.1",
        "BrasilSerieA" to "soccer/bra.1",
        "LigaArgentina" to "soccer/arg.1",
        "ALeague" to "soccer/aus.1",
        CONFERENCE_LEAGUE to "soccer/uefa.europa.conf",
        "NationsLeague" to "soccer/uefa.nations",
        "Libertadores" to "soccer/conmebol.libertadores",
        "ConcacafCup" to "soccer/concacaf.champions",
        "WorldCup" to "soccer/fifa.world",
        "NCAAW" to "basketball/womens-college-basketball",
        "NCAAHockey" to "hockey/mens-college-hockey",
        "NCAABaseball" to "baseball/college-baseball",
        "AFL" to "australian-football/afl",
        "NRL" to "rugby-league/3",
        "NBL" to "basketball/nbl",
        "ALeagueW" to "soccer/aus.w.1",
        "SuperRugby" to "rugby/242041",
        "NPC" to "rugby/270563",
        "League1" to "soccer/eng.3",
        "League2" to "soccer/eng.4",
        "NationalLeague" to "soccer/eng.5",
        "EFLTrophy" to "soccer/eng.trophy",
        "WSL" to "soccer/eng.w.1",
        SCOTTISH_CHAMPIONSHIP to "soccer/sco.2",
        "ScottishCup" to "soccer/sco.tennents",
        SCOTTISH_LEAGUE_CUP to "soccer/sco.cis",
        "WelshPremier" to "soccer/wal.1",
        "IrishPremier" to "soccer/irl.1",
        "Premiership" to "rugby/267979",
        "SixNations" to "rugby/180659",
        "URC" to "rugby/270557",
        "ChampionsCup" to "rugby/271937",
        "ChallengeCup" to "rugby/272073",
        "Top14" to "rugby/270559",
        RUGBY_CHAMPIONSHIP to "rugby/244293",
        "RugbyWorldCup" to "rugby/164205",
        "LionsTour" to "rugby/268565",
        "MLR" to "rugby/289262",
        "IPL" to "cricket/8048",
        "BBL" to "cricket/8044",
        "T20WorldCup" to "cricket/8604",
        "PWHL" to "hockey/pwhl",
        "NLL" to "lacrosse/nll",
        "PLL" to "lacrosse/pll",
        "NSL" to "soccer/can.w.nsl",
        "GLeague" to "basketball/nba-development",
        "USL" to "soccer/usa.usl.1",
        "USLOne" to "soccer/usa.usl.l1",
        "USOpenCup" to "soccer/usa.open",
        "UFL" to "football/ufl",
        "NCAAMSoccer" to "soccer/usa.ncaa.m.1",
        "NCAAWSoccer" to "soccer/usa.ncaa.w.1",
        "FIBA" to "basketball/fiba",
        WORLD_BASEBALL_CLASSIC to "baseball/world-baseball-classic",
        "NCAASoftball" to "baseball/college-softball",
        "NCAAWVB" to "volleyball/womens-college-volleyball",
        "Bellator" to "mma/bellator",
        "Rizin" to "mma/rizin",
        "UEFAEuro" to "soccer/uefa.euro",
        "CopaAmerica" to "soccer/conmebol.america",
        "AFCON" to "soccer/caf.nations",
        "AsianCup" to "soccer/afc.asian.cup",
        "ClubWorldCup" to "soccer/fifa.cwc",
        "WomensWorldCup" to "soccer/fifa.wwc",
        "GoldCup" to "soccer/concacaf.gold",
    )

    /**
     * One-tap league sets by country, for the league manager. Every label must exist in [ALL_LEAGUES]
     * (a test enforces it). Countries share leagues: NHL is in both Canada and the USA.
     */
    val REGIONS: List<Pair<String, List<String>>> = listOf(
        "Australia" to listOf("AFL", "NRL", "NBL", "ALeague", "ALeagueW", "SuperRugby", "BBL", "UFC"),
        "New Zealand" to listOf("SuperRugby", "NPC", "NRL", "NBL", "ALeague", RUGBY_CHAMPIONSHIP, "RugbyWorldCup", "BBL"),
        "United States" to listOf("NFL", "NBA", "MLB", "NHL", "WNBA", "NCAAF", "NCAAB", "MLS", "NWSL", "USL", "UFC", "UFL", "MLR"),
        "Canada" to listOf("NHL", "CFL", "NBA", "MLB", "MLS", "PWHL", "NLL", "NSL", "PremierLeague", CHAMPIONS_LEAGUE),
        "United Kingdom" to listOf(
            "PremierLeague", "Championship", "League1", "League2", "FACup", "CarabaoCup", "WSL",
            "ScottishPrem", SCOTTISH_CHAMPIONSHIP, "ScottishCup", "WelshPremier", CHAMPIONS_LEAGUE, "EuropaLeague",
            "Premiership", "SixNations", "URC", "ChampionsCup", "IPL", "UFC",
        ),
    )

    val ALL_LEAGUES: List<Pair<String, String>> get() = DEFAULT_LEAGUES + OPTIONAL_LEAGUES

    /** Leagues to fetch: the viewer's saved list (CSV of labels), or the built-in set when none is saved. */
    fun enabled(csv: String): List<Pair<String, String>> {
        val wanted = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (wanted.isEmpty()) DEFAULT_LEAGUES else ALL_LEAGUES.filter { it.first in wanted }.ifEmpty { DEFAULT_LEAGUES }
    }

    /** Resolves either a league label ("NBA") or raw ESPN path ("basketball/nba") to its API path. */
    fun pathFor(labelOrPath: String): String =
        ALL_LEAGUES.firstOrNull { it.first.equals(labelOrPath, ignoreCase = true) }?.second ?: labelOrPath

}

