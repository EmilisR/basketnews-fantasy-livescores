package com.elflive.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScoringTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun player(id: String, name: String, eff: Double, playing: Boolean = false) =
        FeedPlayer(id, name, eff, playing, "20:00")

    private fun game(code: Int, status: GameStatus, a: Pair<String, Double>, b: Pair<String, Double>, ap: List<FeedPlayer>, bp: List<FeedPlayer>) =
        Game(code, status, listOf(FeedTeam(a.first, a.first, a.second, ap), FeedTeam(b.first, b.first, b.second, bp)))

    // ---------- season ----------

    @Test
    fun seasonCode_switchesInJuly() {
        assertEquals("E2026", Scoring.seasonCodeFor(ms("2026-10-13T12:00:00Z")))
        assertEquals("E2026", Scoring.seasonCodeFor(ms("2026-07-01T00:00:00Z")))
        assertEquals("E2025", Scoring.seasonCodeFor(ms("2026-06-30T23:59:59Z")))
        assertEquals("E2025", Scoring.seasonCodeFor(ms("2026-01-15T00:00:00Z")))
    }

    // ---------- names ----------

    @Test
    fun norm_stripsDiacriticsAndPunctuation() {
        assertEquals("TUBELIS AZUOLAS", Scoring.norm("Tubelis, Ąžuolas"))
        assertEquals("DONCIC LUKA", Scoring.norm("  Dončić,   Luka "))
        assertEquals("O NEALE ROYCE", Scoring.norm("O'Neale, Royce"))
        assertEquals("", Scoring.norm(null))
    }

    @Test
    fun nameMatch_exactPrefixAndMiss() {
        assertEquals(4, Scoring.nameMatchScore("Azuolas Tubelis", "TUBELIS, AZUOLAS"))
        assertEquals(3, Scoring.nameMatchScore("Az Tubelis", "TUBELIS, AZUOLAS"))
        assertEquals(0, Scoring.nameMatchScore("Marius Tubelis", "TUBELIS, AZUOLAS"))
        assertEquals(0, Scoring.nameMatchScore("", "TUBELIS, AZUOLAS"))
        assertEquals(4, Scoring.nameMatchScore("Ąžuolas Tubelis", "TUBELIS, AZUOLAS"))
    }

    @Test
    fun suffixIsDroppedFromLastName() {
        assertEquals("Hayes", Scoring.stripSuffix("Hayes Jr."))
        assertEquals("Moneke", Scoring.stripSuffix("Moneke II"))
        assertEquals("Davis", Scoring.stripSuffix("Davis"))
        assertEquals("Ivanov", Scoring.stripSuffix("Ivanov"))
    }

    // ---------- points ----------

    @Test
    fun multipliers() {
        assertEquals(2.0, Scoring.multiplier(Role.CAPTAIN), 0.0)
        assertEquals(3.0, Scoring.multiplier(Role.CAPTAIN, ScoringOptions(tripleCaptain = true)), 0.0)
        assertEquals(1.0, Scoring.multiplier(Role.STARTER), 0.0)
        assertEquals(1.0, Scoring.multiplier(Role.SIXTH), 0.0)
        assertEquals(0.5, Scoring.multiplier(Role.BENCH), 0.0)
        assertEquals(1.0, Scoring.multiplier(Role.BENCH, ScoringOptions(benchBoost = true)), 0.0)
    }

    @Test
    fun adjust_appliesPercentLiterally() {
        assertEquals(22.0, Scoring.adjust(20.0, Outcome.WIN), 1e-9)
        assertEquals(18.0, Scoring.adjust(20.0, Outcome.LOSS), 1e-9)
        assertEquals(20.0, Scoring.adjust(20.0, Outcome.TIE), 1e-9)
        assertEquals(20.0, Scoring.adjust(20.0, null), 1e-9)
        assertEquals(-5.5, Scoring.adjust(-5.0, Outcome.WIN), 1e-9)
        assertEquals(-4.5, Scoring.adjust(-5.0, Outcome.LOSS), 1e-9)
    }

    @Test
    fun round2_likeJsMathRound() {
        assertEquals(1.23, Scoring.round2(1.234), 0.0)
        assertEquals(1.24, Scoring.round2(1.235000001), 0.0)
        assertEquals(-1.2, Scoring.round2(-1.2), 0.0)
        assertEquals("12", Scoring.fmt(12.0))
        assertEquals("12.5", Scoring.fmt(12.5))
        assertEquals("-3.3", Scoring.fmt(-3.3))
    }

    // ---------- box score ----------

    private val boxJson = """
        {"Live": true, "Stats": [
          {"Team": "Zalgiris Kaunas", "totr": {"Points": 70},
           "PlayersStats": [
             {"Player_ID": "P001 ", "Team": "ZAL ", "Player": "TUBELIS, AZUOLAS", "Valuation": 15, "IsPlaying": 1, "Minutes": "22:10"},
             {"Player_ID": "P002", "Team": "ZAL", "Player": "BUTKEVICIUS, DOVYDAS", "Valuation": -2, "IsPlaying": 0, "Minutes": "05:00"}
           ]},
          {"Team": "Real Madrid", "totr": {"Points": 65},
           "PlayersStats": [
             {"Player_ID": "P003", "Team": "MAD", "Player": "TAVARES, WALTER", "Valuation": 20, "IsPlaying": 0, "Minutes": "25:00"},
             {"Player_ID": "", "Team": "MAD", "Player": null, "Valuation": 0}
           ]}
        ]}
    """.trimIndent()

    @Test
    fun parseBoxscore_live() {
        val g = Scoring.parseBoxscore(Json.parseToJsonElement(boxJson), 7)
        assertEquals(GameStatus.LIVE, g.status)
        assertEquals(7, g.gameCode)
        assertEquals("ZAL", g.teams[0].code)
        assertEquals(70.0, g.teams[0].points, 0.0)
        assertEquals("P001", g.teams[0].players[0].id)
        assertTrue(g.teams[0].players[0].playing)
        assertFalse(g.teams[0].players[1].playing)
        assertEquals(1, g.teams[1].players.size) // row without a name is dropped
        assertEquals(Outcome.WIN, Scoring.outcomeFor(g, 0))
        assertEquals(Outcome.LOSS, Scoring.outcomeFor(g, 1))
    }

    @Test
    fun parseBoxscore_finalAndPending() {
        val fin = Scoring.parseBoxscore(Json.parseToJsonElement(boxJson.replace("\"Live\": true", "\"Live\": false")), 1)
        assertEquals(GameStatus.FINAL, fin.status)
        val pending = Scoring.parseBoxscore(
            Json.parseToJsonElement("""{"Live": false, "Stats": [{"Team":"A","totr":{"Points":0},"PlayersStats":[]},{"Team":"B","totr":{"Points":0},"PlayersStats":[]}]}"""),
            1,
        )
        assertEquals(GameStatus.PENDING, pending.status)
        assertEquals(GameStatus.PENDING, Scoring.parseBoxscore(null, 1).status)
    }

    // ---------- team totals ----------

    private val liveGame = game(
        1, GameStatus.LIVE, "ZAL" to 70.0, "MAD" to 65.0,
        listOf(player("P1", "TUBELIS, AZUOLAS", 15.0, playing = true), player("P2", "BUTKEVICIUS, DOVYDAS", -2.0)),
        listOf(player("P3", "TAVARES, WALTER", 20.0), player("P4", "HEZONJA, MARIO", 10.0)),
    )

    @Test
    fun computeTeam_rolesOutcomesAndTotals() {
        val roster = Roster(
            listOf(
                RosterEntry(Role.CAPTAIN, "Azuolas Tubelis", "Tubelis"), // 15 * 1.1 * 2 = 33
                RosterEntry(Role.STARTER, "Walter Tavares", "Tavares"), // 20 * 0.9 = 18
                RosterEntry(Role.SIXTH, "Mario Hezonja", "Hezonja"), // 10 * 0.9 = 9
                RosterEntry(Role.BENCH, "Dovydas Butkevicius", "Butkevicius"), // -2 * 1.1 * 0.5 = -1.1
                RosterEntry(Role.STARTER, "Nobody Here", "Here"), // not playing in these games
            ),
        )
        val r = Scoring.computeTeam(roster, listOf(liveGame))
        assertTrue(r.live)
        assertEquals(58.9, r.total, 1e-9)
        val byName = r.players.associateBy { it.input }
        assertEquals(33.0, byName["Azuolas Tubelis"]!!.points, 1e-9)
        assertEquals(16.5, byName["Azuolas Tubelis"]!!.adjusted, 1e-9)
        assertEquals(Outcome.WIN, byName["Azuolas Tubelis"]!!.outcome)
        assertTrue(byName["Azuolas Tubelis"]!!.playing)
        assertEquals("ZAL", byName["Azuolas Tubelis"]!!.team)
        assertEquals(18.0, byName["Walter Tavares"]!!.points, 1e-9)
        assertEquals(Outcome.LOSS, byName["Walter Tavares"]!!.outcome)
        assertEquals(9.0, byName["Mario Hezonja"]!!.points, 1e-9)
        assertEquals(-1.1, byName["Dovydas Butkevicius"]!!.points, 1e-9)
        val missing = byName["Nobody Here"]!!
        assertEquals("waiting", missing.state)
        assertEquals(0.0, missing.points, 0.0)
    }

    @Test
    fun computeTeam_wildcards() {
        val roster = Roster(
            listOf(RosterEntry(Role.CAPTAIN, "Azuolas Tubelis"), RosterEntry(Role.BENCH, "Walter Tavares")),
            ScoringOptions(tripleCaptain = true, benchBoost = true),
        )
        val r = Scoring.computeTeam(roster, listOf(liveGame))
        assertEquals(15 * 1.1 * 3 + 20 * 0.9, r.total, 1e-9)
    }

    @Test
    fun computeTeam_tieNoAdjustment_pendingGameIgnored() {
        val tie = liveGame.copy(teams = liveGame.teams.mapIndexed { i, t -> t.copy(points = 60.0) })
        val r = Scoring.computeTeam(Roster(listOf(RosterEntry(Role.STARTER, "Walter Tavares"))), listOf(tie))
        assertEquals(20.0, r.total, 1e-9)
        val pending = liveGame.copy(status = GameStatus.PENDING)
        val r2 = Scoring.computeTeam(Roster(listOf(RosterEntry(Role.STARTER, "Walter Tavares"))), listOf(pending, null))
        assertEquals(0.0, r2.total, 0.0)
        assertFalse(r2.live)
        assertEquals("waiting", r2.players[0].state)
    }

    @Test
    fun computeTeam_finalGameIsNotLive() {
        val fin = liveGame.copy(status = GameStatus.FINAL)
        val r = Scoring.computeTeam(Roster(listOf(RosterEntry(Role.STARTER, "Walter Tavares"))), listOf(fin))
        assertFalse(r.live)
        assertEquals("final", r.players[0].state)
    }

    @Test
    fun computeTeam_surnameFallbackAndAmbiguity() {
        val g = game(
            2, GameStatus.LIVE, "PAN" to 80.0, "OLY" to 70.0,
            listOf(player("A", "SLOUKAS, KOSTAS", 12.0)),
            listOf(player("B", "PAPANIKOLAOU, KOSTAS", 4.0), player("C", "PAPANIKOLAOU, GIORGOS", 6.0)),
        )
        // nickname that doesn't match the feed's first name -> surname fallback
        val r = Scoring.computeTeam(Roster(listOf(RosterEntry(Role.STARTER, "Kostis Sloukas", "Sloukas"))), listOf(g))
        assertEquals(13.2, r.total, 1e-9)
        assertFalse(r.players[0].ambiguous)

        // surname only, two players share it -> flagged
        val amb = Scoring.computeTeam(Roster(listOf(RosterEntry(Role.STARTER, "Papanikolaou"))), listOf(g))
        assertTrue(amb.players[0].ambiguous)

        // full name resolves it
        val ok = Scoring.computeTeam(Roster(listOf(RosterEntry(Role.STARTER, "Kostas Papanikolaou"))), listOf(g))
        assertFalse(ok.players[0].ambiguous)
        assertEquals(3.6, ok.total, 1e-9)
    }

    @Test
    fun computeTeam_surnameFallbackNeedsSameInitial() {
        // Wendell's game hasn't started; Omari Moore is playing in another game.
        val other = game(
            4, GameStatus.LIVE, "PAR" to 50.0, "BAR" to 40.0,
            listOf(player("OM", "MOORE, OMARI", 9.0)), emptyList(),
        )
        val wendell = RosterEntry(Role.STARTER, "Wendell Moore", "Moore", first = "Wendell")
        val r = Scoring.computeTeam(Roster(listOf(wendell)), listOf(other))
        assertEquals("waiting", r.players[0].state)
        assertEquals(0.0, r.total, 0.0)

        // ...and once his own game is on, he gets his own points (feed spells "MOORE JR., WENDELL").
        val his = game(
            5, GameStatus.LIVE, "MAD" to 60.0, "OLY" to 50.0,
            listOf(player("WM", "MOORE JR., WENDELL", 7.0)), emptyList(),
        )
        val r2 = Scoring.computeTeam(Roster(listOf(wendell)), listOf(other, his))
        assertEquals("MOORE JR., WENDELL", r2.players[0].matchedName)
        assertEquals(7.7, r2.total, 1e-9)

        // Nicknames with the same initial still fall back to the surname.
        val g = game(6, GameStatus.LIVE, "PAN" to 80.0, "OLY" to 70.0, listOf(player("A", "SLOUKAS, KOSTAS", 10.0)), emptyList())
        val kostis = RosterEntry(Role.STARTER, "Kostis Sloukas", "Sloukas", first = "Kostis")
        assertEquals(11.0, Scoring.computeTeam(Roster(listOf(kostis)), listOf(g)).total, 1e-9)
    }

    @Test
    fun computeTeam_onlyCountsThePlayersOwnGame() {
        val t1 = ms("2026-10-14T16:00:00Z")
        val t2 = ms("2026-10-14T18:30:00Z")
        val early = game(7, GameStatus.LIVE, "ZAL" to 50.0, "MAD" to 40.0, listOf(player("X1", "SMITH, JOHN", 12.0)), emptyList())
        val late = game(8, GameStatus.LIVE, "PAN" to 30.0, "OLY" to 40.0, listOf(player("X2", "SMITH, JOHN", 5.0)), emptyList())
        val entry = RosterEntry(Role.STARTER, "John Smith", "Smith", first = "John", gameTimes = listOf(t2 + 3 * 60_000))
        val r = Scoring.computeTeam(Roster(listOf(entry)), listOf(early, late), gameUtc = mapOf(7 to t1, 8 to t2))
        assertEquals(4.5, r.total, 1e-9) // only the 18:30 game, which his team is losing
        assertFalse(r.players[0].ambiguous)
    }

    @Test
    fun computeTeam_roundsTotalToTwoDecimals() {
        val g = game(3, GameStatus.LIVE, "A" to 10.0, "B" to 5.0, listOf(player("X", "DOE, JOHN", 7.0)), emptyList())
        val r = Scoring.computeTeam(Roster(listOf(RosterEntry(Role.BENCH, "John Doe"))), listOf(g))
        assertEquals(3.85, r.total, 0.0)
    }

    // ---------- lineup -> roster ----------

    private fun lineupJson(wildcards: String = "[]", mode: String = "classic_mode") = Json.parseToJsonElement(
        """
        {"fantasyRound": 5,
         "fantasyTeam": {"title": "My Team", "wildcards": $wildcards, "gameMode": {"identifier": "$mode"}},
         "players": [
           {"cardIdentifier": "c-1", "captain": true, "playedAsCaptain": null,
            "player": {"firstName": "Azuolas", "lastName": "Tubelis", "fantasy_pts": 12.5,
              "team": {"team": {"games": [{"originalGameAt": "2026-10-14T16:00:00.000Z"}]}}}},
           {"cardIdentifier": "f-1", "captain": false, "playedAsCaptain": true, "playedAsCardIdentifier": "f-2",
            "player": {"firstName": "Walter", "lastName": "Tavares",
              "team": {"team": {"games": [{"originalGameAt": "2026-10-14T18:30:00Z"}]}}}},
           {"cardIdentifier": "b-1", "captain": false,
            "player": {"firstName": "Mario", "lastName": "Hezonja", "fantasy_pts": "7"}},
           {"cardIdentifier": "b-3", "playedAsCardIdentifier": "", "captain": false,
            "player": {"firstName": "Nigel", "lastName": "Hayes-Davis Jr.", "health": "injured"}},
           {"cardIdentifier": "g-1", "playedAsCardIdentifier": "b-4", "captain": false,
            "player": {"firstName": "Kendrick", "lastName": "Nunn",
              "team": {"team": {"games": [{"originalGameAt": "2026-10-14T16:00:00Z"}, {"originalGameAt": "bogus"}]}}}}
         ]}
        """.trimIndent(),
    ).jsonObject

    @Test
    fun lineupToRoster_rolesNamesAndOfficial() {
        val roster = Scoring.lineupToRoster(lineupJson())
        assertEquals(
            listOf("Azuolas Tubelis", "Walter Tavares", "Mario Hezonja", "Nigel Hayes-Davis", "Kendrick Nunn"),
            roster.players.map { it.name },
        )
        // playedAsCaptain null falls back to captain=true
        assertEquals(Role.CAPTAIN, roster.players[0].role)
        assertEquals(Role.CAPTAIN, roster.players[1].role)
        assertEquals(Role.SIXTH, roster.players[2].role)
        assertEquals(Role.BENCH, roster.players[3].role) // empty playedAs card -> cardIdentifier b-3
        assertEquals(Role.BENCH, roster.players[4].role) // played as b-4
        assertEquals("Hayes-Davis", roster.players[3].fallback)
        assertEquals(12.5, roster.players[0].official!!, 0.0)
        assertNull(roster.players[2].official) // string, not a number
        assertEquals("injured", roster.players[3].health)
        assertEquals("Azuolas", roster.players[0].first)
        assertEquals(listOf(ms("2026-10-14T16:00:00Z")), roster.players[0].gameTimes)
        assertEquals(listOf(ms("2026-10-14T16:00:00Z")), roster.players[4].gameTimes) // "bogus" time skipped
        assertTrue(roster.players[2].gameTimes.isEmpty())
        assertFalse(roster.options.tripleCaptain)
        assertFalse(roster.options.benchBoost)
    }

    @Test
    fun lineupToRoster_wildcardsOnlyForThisRound() {
        val r = Scoring.lineupToRoster(
            lineupJson("""[{"identifier":"triple_captain","fantasyRound":5},{"identifier":"bench_boost","fantasyRound":4}]"""),
        )
        assertTrue(r.options.tripleCaptain)
        assertFalse(r.options.benchBoost)
        val b = Scoring.lineupToRoster(lineupJson("""[{"identifier":"BENCH_BOOST","fantasyRound":5}]"""))
        assertTrue(b.options.benchBoost)
    }

    @Test
    fun gameMode() {
        assertEquals("classic_mode", Scoring.gameModeOf(lineupJson()))
        assertEquals("draft_mode", Scoring.gameModeOf(lineupJson(mode = "draft_mode")))
        assertNull(Scoring.gameModeOf(JsonObject(emptyMap())))
    }

    // ---------- rounds ----------

    @Test
    fun gameTimesOf_uniqueSorted() {
        assertEquals(
            listOf(ms("2026-10-14T16:00:00Z"), ms("2026-10-14T18:30:00Z")),
            Scoring.gameTimesOf(lineupJson()),
        )
    }

    private val schedule = listOf(
        ScheduleGame(1, 3, ms("2026-10-07T16:00:00Z"), "ZAL", "MAD"),
        ScheduleGame(2, 3, ms("2026-10-07T18:30:00Z"), "PAN", "OLY"),
        ScheduleGame(10, 4, ms("2026-10-14T16:05:00Z"), "ZAL", "PAN"),
        ScheduleGame(11, 4, ms("2026-10-14T18:30:00Z"), "MAD", "OLY"),
        ScheduleGame(20, 5, ms("2026-10-21T16:00:00Z"), "MAD", "ZAL"),
    )

    @Test
    fun mapRound_majorityWithTolerance() {
        // BasketNews calls it round 5, EuroLeague round 4: mapping uses times, not numbers.
        assertEquals(4, Scoring.mapRound(Scoring.gameTimesOf(lineupJson()), schedule))
        assertNull(Scoring.mapRound(listOf(ms("2026-10-14T17:00:00Z")), schedule))
        assertNull(Scoring.mapRound(emptyList(), schedule))
        // 11 minutes off is outside the window
        assertNull(Scoring.mapRound(listOf(ms("2026-10-14T16:16:00Z")), schedule))
    }

    @Test
    fun pickRound_opens12hBefore() {
        assertEquals(3, Scoring.pickRound(schedule, ms("2026-10-10T00:00:00Z")))
        assertEquals(4, Scoring.pickRound(schedule, ms("2026-10-14T05:00:00Z")))
        assertEquals(3, Scoring.pickRound(schedule, ms("2026-10-01T00:00:00Z")))
        assertNull(Scoring.pickRound(emptyList(), 0))
    }

    @Test
    fun parseSchedule() {
        val json = Json.parseToJsonElement(
            """{"data":[
              {"gameCode":1,"round":1,"utcDate":"2026-10-01T16:00:00Z","local":{"club":{"code":"ZAL"}},"road":{"club":{"code":"MAD"}}},
              {"gameCode":2,"round":1,"utcDate":"2026-10-01T18:30:00"},
              {"gameCode":3,"round":1,"utcDate":"not a date"},
              {"round":1,"utcDate":"2026-10-01T18:30:00Z"}
            ]}""",
        )
        val games = Scoring.parseSchedule(json)
        assertEquals(2, games.size)
        assertEquals("ZAL", games[0].home)
        assertEquals("MAD", games[0].away)
        assertEquals(ms("2026-10-01T18:30:00Z"), games[1].utc) // no offset -> UTC
        assertTrue(Scoring.parseSchedule(null).isEmpty())
    }
}
