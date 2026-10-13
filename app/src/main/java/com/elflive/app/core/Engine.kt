package com.elflive.app.core

import com.elflive.app.core.Scoring.DAY
import com.elflive.app.core.Scoring.HOUR
import com.elflive.app.core.Scoring.MINUTE
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/** Network access the engine needs. Implemented with OkHttp in the app, faked in tests. */
interface LiveApi {
    suspend fun schedule(season: String): JsonElement

    /** null when the box score is not published yet (or the request failed). */
    suspend fun boxscore(season: String, gameCode: Int): JsonElement?

    /** The lineup object (data.fantasyTeamLineupRecordFromClient). Throws on failure. */
    suspend fun lineup(team: StoredTeam, round: Int): JsonObject
}

interface StateStore {
    suspend fun load(): Persisted
    suspend fun save(p: Persisted)
}

/**
 * Port of the extension's background.js: remembers the teams opened on the site,
 * follows their rounds, polls EuroLeague box scores and computes live totals.
 */
class Engine(
    private val api: LiveApi,
    private val store: StateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<LiveState?>(null)
    val state: StateFlow<LiveState?> = _state.asStateFlow()

    suspend fun loadState(): LiveState? {
        if (_state.value == null) _state.value = mutex.withLock { store.load().state }
        return _state.value
    }

    // ---------- teams opened on the site ----------

    /**
     * The site's lineup request (seen by the injected script) tells us everything:
     * team id, league id, round, locale and the lineup itself.
     * Returns true when the team was stored.
     */
    suspend fun handleSeen(variables: JsonObject?, data: JsonObject?, path: String?): Boolean {
        if (variables == null) return false
        val teamId = variables["fantasyTeamId"].strOrNull ?: return false
        val leagueId = variables["leagueId"].strOrNull ?: return false
        val lineup = data?.get("fantasyTeamLineupRecordFromClient").obj ?: return false
        if (!ID_RE.matches(teamId) || !ID_RE.matches(leagueId)) return false
        // Only EuroLeague is supported; the LKL and BCL games live under their own paths.
        if (OTHER_LEAGUES.containsMatchIn(path ?: "")) return false

        mutex.withLock {
            val now = clock()
            val p = store.load()
            val prev = p.teams.find { it.fantasyTeamId == teamId }
            val viewed = (lineup["fantasyRound"].num ?: variables["fantasyRound"].num ?: 0.0).toInt()
            val title = lineup["fantasyTeam"].obj?.get("title").strOrNull?.takeIf { it.isNotBlank() }
            var entry = StoredTeam(
                fantasyTeamId = teamId,
                leagueId = leagueId,
                title = title ?: prev?.title ?: teamId,
                // never move backwards when someone browses an older round
                fantasyRound = maxOf(viewed, prev?.fantasyRound ?: 0),
                locale = variables["locale"].strOrNull?.take(5) ?: "en",
                lastSeen = now,
                lineup = prev?.lineup,
                lineupRound = prev?.lineupRound ?: 0,
                lineupFetchedAt = prev?.lineupFetchedAt ?: 0,
            )
            // Use the lineup the page just loaded unless it is for an older round than the one we follow.
            if (prev?.lineup == null || viewed >= prev.lineupRound) {
                entry = entry.copy(lineup = lineup, lineupRound = viewed, lineupFetchedAt = now)
            }
            val teams = pruneTeams(p.teams.filter { it.fantasyTeamId != teamId } + entry, now)
            store.save(p.copy(teams = teams))
        }
        return true
    }

    // ---------- EuroLeague schedule + box scores ----------

    private suspend fun getSchedule(season: String, force: Boolean, p: Persisted): Pair<List<ScheduleGame>, ScheduleCache?> {
        val cache = p.schedule
        val sameSeason = cache != null && cache.season == season
        if (!force && sameSeason && clock() - cache!!.fetched < SCHEDULE_TTL) return cache.games to cache
        return try {
            val games = Scoring.parseSchedule(api.schedule(season))
            if (games.isEmpty()) throw IllegalStateException("Schedule came back empty")
            val fresh = ScheduleCache(season, clock(), games)
            games to fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (sameSeason) cache!!.games to cache // use the stale copy rather than fail
            else throw e
        }
    }

    private suspend fun fetchGame(g: ScheduleGame, season: String, now: Long, finals: MutableMap<String, Game>): Game? {
        val key = "$season:${g.gameCode}"
        finals[key]?.let { return it }
        val json = api.boxscore(season, g.gameCode) ?: return null
        var game = Scoring.parseBoxscore(json, g.gameCode)
        // The "Live" flag could flicker; don't trust "final" too early.
        if (game.status == GameStatus.FINAL && now < g.utc + MIN_GAME_LENGTH) game = game.copy(status = GameStatus.LIVE)
        if (game.status == GameStatus.FINAL) synchronized(finals) { finals[key] = game }
        return game
    }

    // ---------- BasketNews lineups ----------

    // Fetch the team's current lineup. Once every game of the stored round is over,
    // look for the next round and follow it as soon as it is about to start.
    private suspend fun syncOne(team: StoredTeam, now: Long): JsonObject {
        var round = team.fantasyRound
        var lineup = api.lineup(team, round)
        repeat(4) {
            val times = Scoring.gameTimesOf(lineup)
            if (times.isEmpty() || times.max() > now - 6 * HOUR) return lineup // round still going
            val next = try {
                api.lineup(team, round + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return lineup
            }
            val nt = Scoring.gameTimesOf(next)
            if (nt.isEmpty() || nt.min() > now + 12 * HOUR) return lineup // next round not open yet
            lineup = next
            round += 1
        }
        return lineup
    }

    // ---------- which EuroLeague round does each team play in? ----------

    private fun roundsFor(teams: List<StoredTeam>, games: List<ScheduleGame>, now: Long): Map<String, Int?> {
        val fallback = Scoring.pickRound(games, now)
        return teams.associate { t ->
            t.fantasyTeamId to if (t.lineup == null) null else Scoring.mapRound(Scoring.gameTimesOf(t.lineup), games) ?: fallback
        }
    }

    private fun isActive(roundGames: List<ScheduleGame>, season: String, finals: Map<String, Game>, now: Long) =
        roundGames.any { g ->
            !finals.containsKey("$season:${g.gameCode}") && now >= g.utc - 5 * MINUTE && now <= g.utc + 210 * MINUTE
        }

    // ---------- main tick ----------

    /**
     * One polling step. [force] skips the idle shortcut and the schedule cache;
     * [refreshLineups] also re-reads every lineup from BasketNews right away.
     */
    suspend fun tick(force: Boolean = false, refreshLineups: Boolean = false): LiveState = mutex.withLock {
        val now = clock()
        val p = store.load()
        val prev = p.state
        val result = try {
            runTick(p, now, force, refreshLineups)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val s = (prev ?: LiveState(updated = now)).copy(error = e.message ?: e.toString(), errorAt = now)
            store.save(p.copy(state = s))
            s
        }
        _state.value = result
        result
    }

    private suspend fun runTick(p0: Persisted, now: Long, force: Boolean, refreshLineups: Boolean): LiveState {
        val season = Scoring.seasonCodeFor(now)
        val teams0 = pruneTeams(p0.teams, now)
        val prev = p0.state

        if (teams0.isEmpty()) {
            val s = LiveState(updated = now)
            store.save(p0.copy(teams = teams0, state = s))
            return s
        }

        val (games, schedule) = getSchedule(season, force, p0)
        // drop finals of other seasons
        val finals = p0.finals.filterKeys { it.startsWith("$season:") }.toMutableMap()
        val ids = teams0.map { it.fantasyTeamId }

        // Idle shortcut: nothing is being played, and we refreshed recently.
        val before = roundsFor(teams0, games, now)
        val beforeRounds = before.values.filterNotNull().toSet()
        val key = beforeRounds.sorted().joinToString(",") + "|" + ids.joinToString(",")
        if (!force && !refreshLineups && prev != null && prev.key == key && prev.error == null &&
            !isActive(games.filter { it.round in beforeRounds }, season, finals, now) &&
            now - prev.updated < IDLE_REFRESH
        ) {
            return prev.copy(active = false)
        }

        // Fresh lineups (every ROSTER_TTL), then box scores for every round in play.
        val problems = mutableMapOf<String, String>()
        val teams = coroutineScope {
            teams0.map { t ->
                async {
                    if (!refreshLineups && t.lineup != null && now - t.lineupFetchedAt < ROSTER_TTL) return@async t
                    try {
                        val lineup = syncOne(t, now)
                        val r = (lineup["fantasyRound"].num ?: t.fantasyRound.toDouble()).toInt()
                        t.copy(lineup = lineup, lineupRound = r, fantasyRound = maxOf(r, t.fantasyRound), lineupFetchedAt = now)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        synchronized(problems) { problems[t.fantasyTeamId] = e.message ?: e.toString() }
                        t
                    }
                }
            }.awaitAll()
        }

        val perTeam = roundsFor(teams, games, now)
        val rounds = perTeam.values.filterNotNull().toSet()
        val wanted = games.filter { it.round in rounds && it.utc <= now + 5 * MINUTE }
        val gameData = coroutineScope {
            wanted.map { g ->
                async {
                    try {
                        fetchGame(g, season, now, finals)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val stateTeams = teams.map { t -> teamState(t, perTeam[t.fantasyTeamId], games, gameData, problems[t.fantasyTeamId]) }

        // The game strip follows the most recently opened team.
        val primaryRound = perTeam[ids[0]]
        val summaries = games.filter { it.round == primaryRound }.sortedBy { it.utc }.map { g ->
            val d = gameData.find { it.gameCode == g.gameCode }
            GameSummary(
                gameCode = g.gameCode,
                utc = g.utc,
                home = g.home,
                away = g.away,
                status = d?.status?.name?.lowercase() ?: "scheduled",
                score = d?.takeIf { it.teams.size == 2 }?.teams?.map { ScoreLine(it.code, it.points) },
            )
        }

        val active = isActive(games.filter { it.round in rounds }, season, finals, now)
        val state = LiveState(
            updated = now,
            key = rounds.sorted().joinToString(",") + "|" + ids.joinToString(","),
            round = primaryRound,
            teams = stateTeams,
            games = summaries,
            error = null,
            active = active,
        )
        store.save(Persisted(teams = teams, schedule = schedule, finals = finals, state = state))
        return state
    }

    private fun teamState(t: StoredTeam, euroRound: Int?, games: List<ScheduleGame>, gameData: List<Game>, problem: String?): TeamState {
        val lineup = t.lineup
            ?: return TeamState(id = t.fantasyTeamId, name = t.title, errors = listOf(problem ?: "Lineup not loaded yet"))
        val name = lineup["fantasyTeam"].obj?.get("title").strOrNull?.takeIf { it.isNotBlank() } ?: t.title
        val errors = if (problem != null) listOf("Using last saved lineup ($problem)") else emptyList()
        val mode = Scoring.gameModeOf(lineup)
        if (mode != null && mode != CLASSIC_MODE) {
            return TeamState(
                id = t.fantasyTeamId, name = name, euroRound = euroRound, fantasyRound = t.lineupRound,
                errors = errors, supported = false, gameMode = mode,
            )
        }
        val roster = Scoring.lineupToRoster(lineup)
        val codes = games.filter { it.round == euroRound }.map { it.gameCode }.toSet()
        val calc = Scoring.computeTeam(roster, gameData.filter { it.gameCode in codes }, roster.options)
        return TeamState(
            id = t.fantasyTeamId,
            name = name,
            total = calc.total,
            live = calc.live,
            players = calc.players,
            euroRound = euroRound,
            fantasyRound = t.lineupRound,
            options = OptionsState(roster.options.tripleCaptain, roster.options.benchBoost),
            errors = errors,
            gameMode = mode,
        )
    }

    companion object {
        const val CLASSIC_MODE = "classic_mode"
        const val UNSUPPORTED_MESSAGE = "This game mode is not supported yet"
        val SCHEDULE_TTL = 6 * HOUR // refresh the EuroLeague schedule every 6 hours
        val ROSTER_TTL = 10 * MINUTE // re-read lineups from BasketNews every 10 minutes
        val IDLE_REFRESH = 5 * MINUTE // when no game is on, refresh at most every 5 min
        val MIN_GAME_LENGTH = 110 * MINUTE // never treat a game as final before this
        const val MAX_TEAMS = 8 // most recently opened teams that are kept
        val FORGET_AFTER = 21 * DAY // teams not opened for this long are dropped
        val ID_RE = Regex("^[a-f0-9]{24}$")
        private val OTHER_LEAGUES = Regex("/(lkl|bcl)(/|$)", RegexOption.IGNORE_CASE)

        fun pruneTeams(teams: List<StoredTeam>, now: Long): List<StoredTeam> = teams
            .filter { now - it.lastSeen < FORGET_AFTER }
            .sortedByDescending { it.lastSeen }
            .take(MAX_TEAMS)
    }
}
