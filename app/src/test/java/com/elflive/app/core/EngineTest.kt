package com.elflive.app.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EngineTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val teamId = "aaaaaaaaaaaaaaaaaaaaaaaa"
    private val leagueId = "bbbbbbbbbbbbbbbbbbbbbbbb"

    private class MemStore : StateStore {
        var p = Persisted()
        override suspend fun load() = p
        override suspend fun save(p: Persisted) {
            this.p = p
        }
    }

    private inner class FakeApi : LiveApi {
        var scheduleJson = """{"data":[
          {"gameCode":10,"round":4,"utcDate":"2026-10-14T16:00:00Z","local":{"club":{"code":"ZAL"}},"road":{"club":{"code":"MAD"}}},
          {"gameCode":11,"round":4,"utcDate":"2026-10-14T18:30:00Z","local":{"club":{"code":"PAN"}},"road":{"club":{"code":"OLY"}}},
          {"gameCode":20,"round":5,"utcDate":"2026-10-21T16:00:00Z","local":{"club":{"code":"MAD"}},"road":{"club":{"code":"ZAL"}}}
        ]}"""
        val boxes = mutableMapOf<Int, String>()
        val lineups = mutableMapOf<Int, JsonObject>()
        var lineupCalls = 0
        var boxCalls = 0
        var failLineups = false

        override suspend fun schedule(season: String): JsonElement = Json.parseToJsonElement(scheduleJson)
        override suspend fun boxscore(season: String, gameCode: Int): JsonElement? {
            boxCalls++
            return boxes[gameCode]?.let { Json.parseToJsonElement(it) }
        }
        override suspend fun lineup(team: StoredTeam, round: Int): JsonObject {
            lineupCalls++
            if (failLineups) throw IllegalStateException("401")
            return lineups[round] ?: throw IllegalStateException("No lineup returned")
        }
    }

    private fun lineup(round: Int, gameAt: String, mode: String = "classic_mode") = Json.parseToJsonElement(
        """{"fantasyRound": $round,
            "fantasyTeam": {"title": "Kaunas Wolves", "wildcards": [], "gameMode": {"identifier": "$mode"}},
            "players": [
              {"cardIdentifier": "c-1", "captain": true,
               "player": {"firstName": "Azuolas", "lastName": "Tubelis",
                 "team": {"team": {"games": [{"originalGameAt": "$gameAt"}]}}}},
              {"cardIdentifier": "b-2", "captain": false,
               "player": {"firstName": "Walter", "lastName": "Tavares",
                 "team": {"team": {"games": [{"originalGameAt": "$gameAt"}]}}}}
            ]}""",
    ).jsonObject

    private fun box(live: Boolean, zal: Int, mad: Int, tubelisEff: Int, tavaresEff: Int) = """
        {"Live": $live, "Stats": [
          {"Team":"Zalgiris","totr":{"Points":$zal},"PlayersStats":[{"Player_ID":"1","Team":"ZAL","Player":"TUBELIS, AZUOLAS","Valuation":$tubelisEff,"IsPlaying":1,"Minutes":"10:00"}]},
          {"Team":"Real","totr":{"Points":$mad},"PlayersStats":[{"Player_ID":"2","Team":"MAD","Player":"TAVARES, WALTER","Valuation":$tavaresEff,"IsPlaying":0,"Minutes":"10:00"}]}
        ]}"""

    private fun vars(round: Int) = Json.parseToJsonElement(
        """{"locale":"lt","leagueId":"$leagueId","fantasyTeamId":"$teamId","fantasyRound":$round}""",
    ).jsonObject

    private fun data(l: JsonObject) = JsonObject(mapOf("fantasyTeamLineupRecordFromClient" to l))

    @Test
    fun liveGame_computesTotalsAndStaysActive() = runBlocking {
        var now = ms("2026-10-14T17:00:00Z")
        val api = FakeApi()
        val store = MemStore()
        val engine = Engine(api, store) { now }

        val l = lineup(7, "2026-10-14T16:00:00Z") // BasketNews round 7 == EuroLeague round 4
        assertTrue(engine.handleSeen(vars(7), data(l), "/en/fantasy/team"))
        api.boxes[10] = box(live = true, zal = 50, mad = 40, tubelisEff = 10, tavaresEff = 8)

        val s = engine.tick(force = true)
        assertNull(s.error)
        assertTrue(s.active)
        assertEquals(4, s.round)
        val t = s.teams.single()
        assertEquals("Kaunas Wolves", t.name)
        assertEquals(4, t.euroRound)
        assertEquals(7, t.fantasyRound)
        assertTrue(t.live)
        // captain 10 * 1.1 * 2 = 22, bench 8 * 0.9 * 0.5 = 3.6
        assertEquals(25.6, t.total, 1e-9)
        assertEquals(0, api.lineupCalls) // lineup seen in the page is fresh, no refetch
        assertEquals(listOf("live", "scheduled"), s.games.map { it.status })

        // A final flag before 110 minutes is not trusted.
        api.boxes[10] = box(live = false, zal = 80, mad = 70, tubelisEff = 20, tavaresEff = 10)
        now = ms("2026-10-14T17:30:00Z")
        val s2 = engine.tick()
        assertTrue(s2.teams.single().live)
        assertTrue(store.p.finals.isEmpty())

        // After 110 minutes it is final and cached.
        now = ms("2026-10-14T18:00:00Z")
        val s3 = engine.tick()
        assertFalse(s3.teams.single().live)
        assertEquals(44.0 + 4.5, s3.teams.single().total, 1e-9)
        assertNotNull(store.p.finals["E2026:10"])
        val calls = api.boxCalls
        now = ms("2026-10-14T18:01:00Z")
        engine.tick(force = true)
        assertEquals(calls, api.boxCalls) // final game came from the cache
    }

    @Test
    fun ignoresOtherLeaguesAndBadIds() = runBlocking {
        val engine = Engine(FakeApi(), MemStore()) { ms("2026-10-14T17:00:00Z") }
        val l = lineup(7, "2026-10-14T16:00:00Z")
        assertFalse(engine.handleSeen(vars(7), data(l), "/lkl/team"))
        assertFalse(engine.handleSeen(vars(7), data(l), "/BCL"))
        val bad = JsonObject(vars(7) + ("fantasyTeamId" to Json.parseToJsonElement("\"123\"")))
        assertFalse(engine.handleSeen(bad, data(l), "/"))
        assertFalse(engine.handleSeen(vars(7), JsonObject(emptyMap()), "/"))
    }

    @Test
    fun followsNextRoundWhenStoredRoundIsOver() = runBlocking {
        val now = ms("2026-10-21T08:00:00Z") // round 4 long over, round 5 starts in 8h
        val api = FakeApi()
        val store = MemStore()
        val engine = Engine(api, store) { now }
        val r7 = lineup(7, "2026-10-14T16:00:00Z")
        engine.handleSeen(vars(7), data(r7), "/")
        store.p = store.p.copy(teams = store.p.teams.map { it.copy(lineupFetchedAt = 0) }) // make it stale
        api.lineups[7] = r7
        api.lineups[8] = lineup(8, "2026-10-21T16:00:00Z")

        val s = engine.tick(force = true)
        val t = s.teams.single()
        assertEquals(8, t.fantasyRound)
        assertEquals(5, t.euroRound)
        assertEquals(8, store.p.teams.single().fantasyRound)
        assertFalse(s.active)
    }

    @Test
    fun lineupFailureKeepsLastLineup() = runBlocking {
        val api = FakeApi()
        val store = MemStore()
        val engine = Engine(api, store) { ms("2026-10-14T17:00:00Z") }
        engine.handleSeen(vars(7), data(lineup(7, "2026-10-14T16:00:00Z")), "/")
        api.failLineups = true
        api.boxes[10] = box(live = true, zal = 50, mad = 40, tubelisEff = 10, tavaresEff = 8)
        val s = engine.tick(force = true, refreshLineups = true)
        val t = s.teams.single()
        assertEquals(25.6, t.total, 1e-9)
        assertTrue(t.errors.single().startsWith("Using last saved lineup"))
    }

    @Test
    fun unsupportedGameMode() = runBlocking {
        val api = FakeApi()
        val engine = Engine(api, MemStore()) { ms("2026-10-14T17:00:00Z") }
        engine.handleSeen(vars(7), data(lineup(7, "2026-10-14T16:00:00Z", mode = "draft_mode")), "/")
        api.boxes[10] = box(live = true, zal = 50, mad = 40, tubelisEff = 10, tavaresEff = 8)
        val t = engine.tick(force = true).teams.single()
        assertFalse(t.supported)
        assertEquals("draft_mode", t.gameMode)
        assertTrue(t.players.isEmpty())
    }

    @Test
    fun keepsAtMostEightTeamsMostRecentFirst() = runBlocking {
        var now = ms("2026-10-14T10:00:00Z")
        val store = MemStore()
        val engine = Engine(FakeApi(), store) { now }
        for (i in 0 until 10) {
            now += 1000
            val id = i.toString().padStart(24, 'c')
            val v = JsonObject(vars(7) + ("fantasyTeamId" to Json.parseToJsonElement("\"$id\"")))
            engine.handleSeen(v, data(lineup(7, "2026-10-14T16:00:00Z")), "/")
        }
        assertEquals(8, store.p.teams.size)
        assertEquals("9".padStart(24, 'c'), store.p.teams.first().fantasyTeamId)
    }

    @Test
    fun olderRoundInPageDoesNotReplaceLineup() = runBlocking {
        val store = MemStore()
        val engine = Engine(FakeApi(), store) { ms("2026-10-14T10:00:00Z") }
        engine.handleSeen(vars(7), data(lineup(7, "2026-10-14T16:00:00Z")), "/")
        engine.handleSeen(vars(6), data(lineup(6, "2026-10-07T16:00:00Z")), "/")
        val t = store.p.teams.single()
        assertEquals(7, t.fantasyRound)
        assertEquals(7, t.lineupRound)
    }

    @Test
    fun noTeams_emptyState() = runBlocking {
        val s = Engine(FakeApi(), MemStore()) { 0L }.tick(force = true)
        assertTrue(s.teams.isEmpty())
        assertFalse(s.active)
    }
}
