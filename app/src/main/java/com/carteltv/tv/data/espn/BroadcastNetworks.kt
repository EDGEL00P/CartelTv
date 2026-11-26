package com.carteltv.tv.data.espn

/**
 * Channels that usually carry each league, for every league the app lists, by country, including the league's own
 * networks (NFL Network, NHL Network, NBA TV, MLB Network, Big Ten, SEC and ACC Networks).
 *
 * Sources, checked November 2026:
 *  - US-market leagues: the broadcasters ESPN listed for every game across a three-week window
 *    (`tools/mine_league_networks.py`), so those entries follow what really aired.
 *  - Everywhere else ESPN lists nothing, so entries come from the leagues' and rights holders' published broadcast
 *    guides: AFL (afl.com.au broadcast guide), Premier League (premierleague.com broadcasters), Super Rugby Pacific
 *    (super.rugby broadcast guide), Six Nations / Premiership / URC / Champions Cup (UK rights listings).
 *
 * Rights change every few seasons, so an entry only makes a channel a *candidate*: it ranks below a real network match
 * and below any guide listing that names the game, and it only ever matches a channel the viewer's own provider has.
 * Streaming-only services (Kayo, Stan Sport, Fubo, Paramount+, Peacock ...) are deliberately absent: they are not channels.
 */
object BroadcastNetworks {

    private val table: Map<String, List<String>> = mapOf(
        "NFL" to listOf("espn", "abc", "cbs", "fox", "nbc", "nfl network", "nfl redzone", "espn2", "tsn", "ctv", "cbc", "sky sports nfl", "sky sports main event", "fox sports"),
        "NBA" to listOf("espn", "abc", "nbc", "tnt", "nba tv", "tsn", "tsn 1", "tsn 2", "tsn 3", "tsn 4", "tsn 5", "sportsnet", "sportsnet one", "sportsnet 360", "sky sports nba", "sky sports main event", "fox sports"),
        "NHL" to listOf(
            "espn", "abc", "tnt", "tbs", "truTV", "ion", "nhl network", "msg", "nesn", "nbc sports", "scripps sports", "bally sports",
            "sportsnet", "sportsnet one", "sportsnet 360", "sportsnet east", "sportsnet ontario", "sportsnet west", "sportsnet pacific",
            "tsn 1", "tsn 2", "tsn 3", "tsn 4", "tsn 5", "cbc", "citytv", "tva sports", "tva sports 2", "rds", "premier sports 1", "premier sports 2",
        ),
        "MLB" to listOf("fox", "fs1", "fs2", "tbs", "espn", "abc", "mlb network", "nbc sports", "sportsnet", "sportsnet one", "sportsnet 360", "tsn", "sky sports mlb", "sky sports main event"),
        "WNBA" to listOf("espn", "espn2", "abc", "ion", "cbs sports network", "nba tv"),
        "NCAAF" to listOf(
            "espn", "espn2", "espnu", "abc", "fox", "fs1", "cbs", "cbs sports network", "nbc", "cw", "big ten network", "btn",
            "sec network", "acc network", "espn news",
        ),
        "NCAAB" to listOf("espn", "espn2", "espnu", "cbs", "cbs sports network", "fox", "fs1", "tnt", "tbs", "truTV", "big ten network", "btn", "sec network", "acc network"),
        "NCAAW" to listOf("espn", "espn2", "espnu", "abc", "fs1", "cbs sports network", "big ten network", "btn", "sec network", "acc network"),
        "NCAAHockey" to listOf("espn2", "espnu", "cbs sports network", "big ten network", "btn", "nbc sports", "midco sports", "altitude sports"),
        "NCAABaseball" to listOf("espn", "espn2", "espnu", "sec network", "acc network", "big ten network", "cbs sports network"),
        "NCAAMSoccer" to listOf("espnu", "espn2", "acc network", "big ten network", "sec network"),
        "NCAAWSoccer" to listOf("espnu", "espn2", "acc network", "big ten network", "sec network"),
        "UFL" to listOf("espn", "espn2", "abc", "fox", "fs1"),
        "MLS" to listOf("fs1", "fox sports 1", "fox", "telemundo", "universo", "sky sports"),
        "NWSL" to listOf("cbs", "cbs sports network", "espn", "espn2", "abc", "tsn", "sky sports"),
        "USL" to listOf("cbs sports network", "espn", "espn2", "tsn"),
        "USLOne" to listOf("cbs sports network", "espn", "espn2"),
        "USOpenCup" to listOf("cbs sports network", "espn", "fox", "fs1", "tsn"),
        "GLeague" to listOf("nba tv", "espn", "espn2"),
        "MLR" to listOf("fs1", "fox sports 1", "cbs sports network", "tnt sports 1"),
        "UFC" to listOf("espn", "espn2", "tnt sports 1", "tnt sports 2", "tnt sports box office", "main event", "fox sports", "tsn", "sportsnet"),
        "PFL" to listOf("espn", "espn2", "tnt sports 1", "fox sports", "sportsnet"),
        "PLL" to listOf("espn", "espn2", "abc", "tsn"),
        "NLL" to listOf("tsn", "tsn 1", "tsn 2", "tsn 3", "tsn 4", "tsn 5", "espn2"),

        "CFL" to listOf("tsn 1", "tsn 2", "tsn 3", "tsn 4", "tsn 5", "tsn", "rds", "espn2", "cbs sports network"),
        "PWHL" to listOf("sportsnet", "sportsnet one", "tsn", "tsn 1", "tsn 2", "cbc", "rds"),
        "NSL" to listOf("tsn", "tsn 1", "tsn 2", "tsn 3", "tsn 4", "tsn 5", "sportsnet"),

        "AFL" to listOf("fox footy", "seven", "channel 7", "7mate", "fox sports"),
        "NRL" to listOf("fox league", "nine", "channel 9", "fox sports"),
        "NBL" to listOf("espn", "fox sports", "sky sport"),
        "ALeague" to listOf("fox sports", "network 10", "channel 10", "sky sport"),
        "ALeagueW" to listOf("fox sports", "network 10", "channel 10"),
        "SuperRugby" to listOf(
            "sky sport 1", "sky sport 2", "sky sport 3", "sky sport 4", "sky sport 5", "sky sport 6", "sky sport 7", "sky sport 8", "sky sport 9",
            "sky sport select", "sky open", "sky sport", "sky sports", "supersport rugby", "supersport", "espn",
        ),
        "NPC" to listOf("sky sport 1", "sky sport 2", "sky sport 3", "sky sport 4", "sky sport 5", "sky sport", "sky open"),

        LeagueCatalog.RUGBY_CHAMPIONSHIP to listOf("sky sport 1", "sky sport 2", "sky sport 3", "sky sport", "nine", "channel 9", "itv", "supersport rugby", "supersport", "tnt sports 1"),
        "RugbyWorldCup" to listOf("itv", "itv1", "itv 1", "itv4", "tnt sports 1", "sky sport", "nine", "channel 9", "tsn", "supersport"),
        "SixNations" to listOf("itv", "itv1", "itv 1", "itv4", "bbc one", "bbc two", "s4c", "stv", "rte", "tsn", "tsn 1", "tsn 2", "sky sport"),
        "Premiership" to listOf("tnt sports 1", "tnt sports 2", "tnt sports 3", "tnt sports 4", "itv", "itv4"),
        "URC" to listOf("premier sports 1", "premier sports 2", "premier sports", "s4c", "tg4", "rte", "supersport rugby", "supersport", "sky sport"),
        "ChampionsCup" to listOf("premier sports 1", "premier sports 2", "premier sports", "supersport", "sky sport"),
        "ChallengeCup" to listOf("premier sports 1", "premier sports 2", "premier sports", "supersport", "sky sport"),
        "Top14" to listOf("premier sports 1", "premier sports 2", "premier sports", "sky sport"),
        "LionsTour" to listOf("sky sports main event", "sky sports", "sky sport", "itv", "nine", "channel 9", "supersport", "tnt sports 1"),

        "PremierLeague" to listOf(
            "sky sports premier league", "sky sports main event", "sky sports football", "tnt sports 1", "tnt sports 2", "tnt sports 3",
            "tnt sports 4", "usa network", "nbc", "telemundo", "universo", "sky sport", "supersport",
        ),
        "Championship" to listOf("sky sports football", "sky sports main event", "sky sports", "cbs sports network", "cbs sports golazo"),
        "League1" to listOf("sky sports football", "sky sports main event", "sky sports"),
        "League2" to listOf("sky sports football", "sky sports main event", "sky sports"),
        "NationalLeague" to listOf("sky sports football", "sky sports main event", "tnt sports", "bbc"),
        "FACup" to listOf("tnt sports 1", "tnt sports 2", "tnt sports 3", "tnt sports 4", "bbc one", "bbc two", "itv1", "itv 1", "espn"),
        "CarabaoCup" to listOf("sky sports main event", "sky sports football", "sky sports"),
        "EFLTrophy" to listOf("sky sports football", "sky sports", "itv4"),
        "WSL" to listOf("sky sports football", "sky sports main event", "bbc one", "bbc two", "bbc"),
        "ScottishPrem" to listOf("sky sports football", "sky sports main event", "sky sports", "bbc scotland", "bbc alba", "premier sports 1", "premier sports 2", "viaplay sports 1"),
        LeagueCatalog.SCOTTISH_CHAMPIONSHIP to listOf("sky sports football", "sky sports", "bbc scotland", "bbc alba", "premier sports 1"),
        "ScottishCup" to listOf("bbc scotland", "bbc alba", "bbc two", "sky sports", "premier sports 1", "viaplay sports 1"),
        LeagueCatalog.SCOTTISH_LEAGUE_CUP to listOf("sky sports football", "sky sports", "bbc scotland", "premier sports 1", "viaplay sports 1"),
        "WelshPremier" to listOf("s4c", "bbc wales"),
        "IrishPremier" to listOf("rte", "rte 2", "premier sports", "sky sports"),

        LeagueCatalog.CHAMPIONS_LEAGUE to listOf(
            "tnt sports 1", "tnt sports 2", "tnt sports 3", "tnt sports 4", "cbs sports network", "cbs sports golazo", "cbs",
            "tsn", "tsn 1", "sportsnet", "fox sports", "sky sport",
        ),
        "EuropaLeague" to listOf("tnt sports 1", "tnt sports 2", "tnt sports 3", "tnt sports 4", "cbs sports network", "cbs sports golazo", "tsn"),
        LeagueCatalog.CONFERENCE_LEAGUE to listOf("tnt sports 1", "tnt sports 2", "tnt sports 3", "tnt sports 4", "cbs sports network", "cbs sports golazo", "tsn"),
        "NationsLeague" to listOf("fs1", "fs2", "fox sports 1", "fox sports 2", "tnt sports 1", "tnt sports 2", "sky sports football", "itv"),
        "WorldCup" to listOf("fox", "fs1", "fox sports 1", "telemundo", "universo", "itv", "bbc one", "tsn", "tsn 1", "sbs", "nine", "channel 9", "sky sport"),
        "Libertadores" to listOf("bein sports", "espn", "fox sports", "fs1", "fs2", "tudn"),
        "ConcacafCup" to listOf("fox", "fs1", "fs2", "fox sports 1", "telemundo", "universo", "tudn", "tsn"),
        "LaLiga" to listOf("espn deportes", "espn", "espn2", "premier sports 1", "premier sports 2", "bein sports", "sky sports football"),
        "Bundesliga" to listOf("espn", "fs2", "fox sports 2", "sky sports football", "sky sports main event", "sky sports", "bein sports"),
        "Bundesliga2" to listOf("espn", "sky sports football", "bein sports"),
        "SerieA" to listOf("cbs sports network", "cbs sports golazo", "cbs", "tnt sports 1", "tnt sports 2", "premier sports 1", "bein sports"),
        "Ligue1" to listOf("bein sports", "bein sports xtra", "fox sports", "sky sports football"),
        "Eredivisie" to listOf("espn", "espn 1", "espn 2", "viaplay sports 1", "ziggo sport"),
        "Primeira" to listOf("sport tv", "sport tv 1", "sport tv 2", "bein sports", "espn"),
        "SuperLig" to listOf("bein sports", "bein sports 1", "bein sports 2", "trt spor"),
        "LigaMX" to listOf("tudn", "fox deportes", "univision", "unimas", "telemundo", "universo", "fs1", "tv azteca", "azteca"),
        "BrasilSerieA" to listOf("globo", "sportv", "premiere", "espn brasil", "espn"),
        "LigaArgentina" to listOf("espn", "tnt sports", "tyc sports", "fox sports"),

        "IPL" to listOf("sky sports cricket", "sky sports main event", "fox cricket", "sky sport", "willow", "star sports", "willow cricket"),
        "BBL" to listOf("fox cricket", "seven", "channel 7", "sky sports cricket", "sky sport", "willow"),
        "T20WorldCup" to listOf("sky sports cricket", "fox cricket", "seven", "channel 7", "sky sport", "willow", "star sports", "bbc"),

        "FIBA" to listOf("espn", "espn2", "espnu", "courtside", "tsn", "sportsnet"),
        LeagueCatalog.WORLD_BASEBALL_CLASSIC to listOf("espn", "fox", "fs1", "mlb network", "tbs", "tsn", "sportsnet"),
        "NCAASoftball" to listOf("espn", "espn2", "espnu", "abc", "sec network", "big ten network", "acc network", "cbs sports network"),
        "NCAAWVB" to listOf("espn", "espn2", "espnu", "big ten network", "sec network", "acc network", "cbs sports network"),
        "Bellator" to listOf("cbs sports network", "max", "espn", "fox sports"),
        "Rizin" to listOf("cbs sports network", "fox sports"),
        "UEFAEuro" to listOf("fox", "fs1", "bbc one", "bbc two", "itv", "itv1", "itv4", "rte", "tf1", "zdf", "tsn", "sportsnet"),
        "CopaAmerica" to listOf("fox", "fs1", "fs2", "telemundo", "universo", "tsn"),
        "AFCON" to listOf("bein sports", "bein sports 1", "bein sports 2", "supersport", "supersport football"),
        "AsianCup" to listOf("bein sports", "bein sports 1", "bein sports 2"),
        "ClubWorldCup" to listOf("espn", "espn2", "tnt sports 1"),
        "WomensWorldCup" to listOf("fox", "fs1", "telemundo", "universo", "bbc one", "itv", "itv1", "tsn", "sportsnet"),
        "GoldCup" to listOf("fox", "fs1", "fs2", "telemundo", "universo", "tsn"),
    )

    private val byKey: Map<String, List<String>> = table.mapKeys { it.key.lowercase() }

    /** Channel names that usually carry [leagueLabel] (a label from `LeagueCatalog.ALL_LEAGUES`), or none. */
    fun forLeague(leagueLabel: String?): List<String> =
        leagueLabel?.let { byKey[it.lowercase()] }?.distinct() ?: emptyList()

    /** Every league label that has a table, for tests. */
    internal fun labels(): Set<String> = table.keys
}
