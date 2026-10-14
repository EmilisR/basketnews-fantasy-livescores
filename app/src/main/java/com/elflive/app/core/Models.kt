package com.elflive.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Plain data types shared by the scoring code, the engine and the UI.
// Everything here is pure Kotlin (no Android), so it runs in JVM unit tests.

enum class Role { CAPTAIN, STARTER, SIXTH, BENCH }

enum class Outcome { WIN, LOSS, TIE }

enum class GameStatus { PENDING, LIVE, FINAL }

@Serializable
data class FeedPlayer(
    val id: String,
    val name: String,
    val eff: Double,
    val playing: Boolean,
    val minutes: String,
)

@Serializable
data class FeedTeam(
    val code: String,
    val name: String,
    val points: Double,
    val players: List<FeedPlayer>,
)

/** One EuroLeague box score, compacted. */
@Serializable
data class Game(
    val gameCode: Int,
    val status: GameStatus,
    val teams: List<FeedTeam>,
)

/** One game of the EuroLeague schedule. [utc] is epoch millis. */
@Serializable
data class ScheduleGame(
    val gameCode: Int,
    val round: Int,
    val utc: Long,
    val home: String? = null,
    val away: String? = null,
)

data class ScoringOptions(val tripleCaptain: Boolean = false, val benchBoost: Boolean = false)

@Serializable
data class OptionsState(val tripleCaptain: Boolean = false, val benchBoost: Boolean = false)

data class RosterEntry(
    val role: Role,
    val name: String,
    val fallback: String? = null,
    val official: Double? = null,
    val health: String? = null,
    /** First name as BasketNews has it; the surname-only fallback must agree on its initial. */
    val first: String = "",
    /** Start times (ms) of this player's team's games, from the lineup. Empty = unknown. */
    val gameTimes: List<Long> = emptyList(),
    /** BasketNews team abbreviation, e.g. "OLY"; used only when it equals a feed team code. */
    val teamCode: String? = null,
)

data class Roster(val players: List<RosterEntry>, val options: ScoringOptions = ScoringOptions())

@Serializable
data class PlayerResult(
    val input: String,
    val role: Role,
    val mult: Double,
    /** "waiting" (game not started / not found), "live" or "final". */
    val state: String,
    val matchedName: String?,
    val team: String?,
    val eff: Double,
    val adjusted: Double,
    val points: Double,
    val playing: Boolean,
    val ambiguous: Boolean,
    val outcome: Outcome?,
    val minutes: String,
    val official: Double?,
)

data class TeamResult(val total: Double, val live: Boolean, val players: List<PlayerResult>)

// ---------- persisted state ----------

/** A team the user opened on the site. [lineup] is the raw GraphQL lineup object. */
@Serializable
data class StoredTeam(
    val fantasyTeamId: String,
    val leagueId: String,
    val title: String,
    val fantasyRound: Int,
    val locale: String = "en",
    val lastSeen: Long = 0,
    val lineup: JsonObject? = null,
    val lineupRound: Int = 0,
    val lineupFetchedAt: Long = 0,
)

@Serializable
data class ScheduleCache(val season: String, val fetched: Long, val games: List<ScheduleGame>)

@Serializable
data class TeamState(
    val id: String,
    val name: String,
    val total: Double = 0.0,
    val live: Boolean = false,
    val players: List<PlayerResult> = emptyList(),
    val euroRound: Int? = null,
    val fantasyRound: Int? = null,
    val options: OptionsState = OptionsState(),
    val errors: List<String> = emptyList(),
    /** false for game modes other than classic (e.g. draft). */
    val supported: Boolean = true,
    val gameMode: String? = null,
)

@Serializable
data class ScoreLine(val code: String, val points: Double)

@Serializable
data class GameSummary(
    val gameCode: Int,
    val utc: Long,
    val home: String?,
    val away: String?,
    /** "scheduled", "pending", "live" or "final". */
    val status: String,
    val score: List<ScoreLine>? = null,
)

@Serializable
data class LiveState(
    val updated: Long,
    val key: String = "",
    val round: Int? = null,
    val teams: List<TeamState> = emptyList(),
    val games: List<GameSummary> = emptyList(),
    val error: String? = null,
    val errorAt: Long? = null,
    /** true while any game of a watched round is (about to be) in progress. */
    val active: Boolean = false,
)

@Serializable
data class Persisted(
    val teams: List<StoredTeam> = emptyList(),
    val schedule: ScheduleCache? = null,
    val finals: Map<String, Game> = emptyMap(),
    val state: LiveState? = null,
)
