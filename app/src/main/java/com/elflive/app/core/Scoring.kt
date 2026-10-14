package com.elflive.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// Port of the extension's scoring.js: scoring, name matching,
// lineup -> roster mapping and season/round helpers. No Android APIs in here.

object Scoring {

    // EuroLeague season code from the date: the season starts in July (E2026 = 2026-27).
    fun seasonCodeFor(now: Long): String {
        val d = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
        val year = if (d.monthValue >= 7) d.year else d.year - 1
        return "E$year"
    }

    // ---------- names ----------

    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM = Regex("[^A-Z0-9 ]+")
    private val SPACES = Regex("\\s+")

    fun norm(s: String?): String = Normalizer.normalize(s ?: "", Normalizer.Form.NFD)
        .replace(COMBINING, "")
        .uppercase(Locale.ROOT)
        .replace(NON_ALNUM, " ")
        .replace(SPACES, " ")
        .trim()

    // 0 = no match. Every word the user typed must equal, or be the start of,
    // a word in the feed name ("TUBELIS, AZUOLAS"). Exact words score higher.
    fun nameMatchScore(query: String?, feedName: String?): Int {
        val q = norm(query).split(' ').filter { it.isNotEmpty() }
        val f = norm(feedName).split(' ').filter { it.isNotEmpty() }
        if (q.isEmpty()) return 0
        var score = 0
        for (t in q) {
            score += when {
                t in f -> 2
                f.any { it.startsWith(t) } -> 1
                else -> return 0
            }
        }
        return score
    }

    // ---------- points ----------

    fun multiplier(role: Role, opts: ScoringOptions = ScoringOptions()): Double = when (role) {
        Role.CAPTAIN -> if (opts.tripleCaptain) 3.0 else 2.0
        Role.BENCH -> if (opts.benchBoost) 1.0 else 0.5
        else -> 1.0 // starter, sixth player
    }

    // Rule: EFF plus or minus 10% depending on whether the team wins or loses.
    // null / TIE => no adjustment. For a negative EFF the percentage is applied
    // literally (EFF * 1.1 on a win, EFF * 0.9 on a loss).
    fun adjust(eff: Double, outcome: Outcome?): Double = when (outcome) {
        Outcome.WIN -> eff + eff * 0.1
        Outcome.LOSS -> eff - eff * 0.1
        else -> eff
    }

    // Same as JS Math.round(x * 100) / 100 (rounds .5 towards +infinity).
    fun round2(x: Double): Double = Math.floor(x * 100 + 0.5) / 100

    // ---------- live feed ----------

    // Convert the live.euroleague.net Boxscore JSON into a compact game object.
    fun parseBoxscore(json: JsonElement?, gameCode: Int): Game {
        val stats = json.obj?.get("Stats").arr ?: emptyList()
        val teams = stats.map { t ->
            val ps = (t.obj?.get("PlayersStats").arr ?: emptyList())
                .mapNotNull { it.obj }
                .filter { it["Player"].str.isNotEmpty() }
            FeedTeam(
                code = (ps.firstOrNull()?.get("Team").str).trim(),
                name = t.obj?.get("Team").str,
                points = t.obj?.get("totr").obj?.get("Points").num ?: 0.0,
                players = ps.map { p ->
                    FeedPlayer(
                        id = p["Player_ID"].str.trim(),
                        name = p["Player"].str,
                        eff = p["Valuation"].num ?: 0.0,
                        playing = p["IsPlaying"].num == 1.0,
                        minutes = p["Minutes"].str,
                    )
                },
            )
        }
        val total = teams.sumOf { it.points }
        val live = json.obj?.get("Live").truthy
        val status = when {
            live -> GameStatus.LIVE
            teams.size == 2 && total > 0 -> GameStatus.FINAL
            else -> GameStatus.PENDING
        }
        return Game(gameCode, status, teams)
    }

    // WIN / LOSS / TIE for team index idx. While live this is provisional
    // (whoever leads right now).
    fun outcomeFor(game: Game, idx: Int): Outcome {
        val a = game.teams[idx].points
        val b = game.teams[1 - idx].points
        if (a == b) return Outcome.TIE
        return if (a > b) Outcome.WIN else Outcome.LOSS
    }

    // ---------- team totals ----------

    // Full name first; if that finds nothing, fall back to the surname alone
    // (lower score), so odd first names / nicknames ("Kostis" vs "KOSTAS") still match.
    // The fallback needs the same first initial, so "Wendell Moore" never takes
    // "MOORE, OMARI"'s points.
    private fun entryScore(entry: RosterEntry, feedName: String): Double {
        val s = nameMatchScore(entry.name, feedName)
        if (s > 0) return s.toDouble()
        if (!entry.fallback.isNullOrEmpty() && nameMatchScore(entry.fallback, feedName) > 0 &&
            sameInitial(entry, feedName)
        ) return 0.5
        return 0.0
    }

    // Feed names are "SURNAME, FIRST". Without a known first name on either side, allow it.
    private fun sameInitial(entry: RosterEntry, feedName: String): Boolean {
        val initial = norm(entry.first).firstOrNull() ?: return true
        if (!feedName.contains(',')) return true
        val feedFirst = norm(feedName.substringAfter(',')).firstOrNull() ?: return true
        return initial == feedFirst
    }

    // A player can only score in his own team's game: when the lineup tells us when that
    // game starts, ignore box scores of games starting at other times.
    private fun playsIn(entry: RosterEntry, g: Game, gameUtc: Map<Int, Long>): Boolean {
        if (entry.gameTimes.isEmpty()) return true
        val utc = gameUtc[g.gameCode] ?: return true
        return entry.gameTimes.any { abs(it - utc) <= 10 * MINUTE }
    }

    private class Match(val s: Double, val p: FeedPlayer, val t: FeedTeam, val ti: Int, val g: Game, var amb: Boolean = false)

    /** [gameUtc]: schedule start time per game code, used to keep each player to his own game. */
    fun computeTeam(
        roster: Roster,
        games: List<Game?>,
        opts: ScoringOptions = roster.options,
        gameUtc: Map<Int, Long> = emptyMap(),
    ): TeamResult {
        var total = 0.0
        var live = false

        val players = roster.players.map { entry ->
            val mult = multiplier(entry.role, opts)

            // best match per game, then pick the single best player id overall
            val perGame = mutableListOf<Match>()
            for (g in games) {
                if (g == null || g.status == GameStatus.PENDING || g.teams.size != 2) continue
                if (!playsIn(entry, g, gameUtc)) continue
                var best: Match? = null
                g.teams.forEachIndexed { ti, t ->
                    for (p in t.players) {
                        val s = entryScore(entry, p.name)
                        val b = best
                        if (s > 0 && (b == null || s > b.s)) best = Match(s, p, t, ti, g)
                        else if (s > 0 && b != null && s == b.s && p.id != b.p.id) b.amb = true
                    }
                }
                best?.let { perGame += it }
            }

            if (perGame.isEmpty()) {
                return@map PlayerResult(
                    input = entry.name, role = entry.role, mult = mult, state = "waiting",
                    matchedName = null, team = null, eff = 0.0, adjusted = 0.0, points = 0.0,
                    playing = false, ambiguous = false, outcome = null, minutes = "",
                    official = entry.official,
                )
            }

            val topScore = perGame.maxOf { it.s }
            val tops = perGame.filter { it.s == topScore }
            val chosenId = tops[0].p.id
            val ambiguous = tops.any { it.amb || it.p.id != chosenId }
            val used = perGame.filter { it.p.id == chosenId }

            var eff = 0.0
            var adjusted = 0.0
            var isLive = false
            var playing = false
            var outcome: Outcome? = null
            var minutes = ""
            for (m in used) {
                val o = outcomeFor(m.g, m.ti)
                eff += m.p.eff
                adjusted += adjust(m.p.eff, o)
                if (m.g.status == GameStatus.LIVE) isLive = true
                if (m.p.playing) playing = true
                outcome = o
                minutes = m.p.minutes
            }
            if (isLive) live = true

            val points = adjusted * mult
            total += points
            PlayerResult(
                input = entry.name, role = entry.role, mult = mult,
                state = if (isLive) "live" else "final",
                matchedName = used[0].p.name, team = used[0].t.code,
                eff = eff, adjusted = round2(adjusted), points = round2(points),
                playing = playing, ambiguous = ambiguous, outcome = outcome, minutes = minutes,
                official = entry.official,
            )
        }

        return TeamResult(round2(total), live, players)
    }

    // ---------- schedule helpers ----------

    fun parseSchedule(json: JsonElement?): List<ScheduleGame> =
        (json.obj?.get("data").arr ?: emptyList()).mapNotNull { e ->
            val g = e.obj ?: return@mapNotNull null
            val code = g["gameCode"].num?.toInt() ?: return@mapNotNull null
            val round = g["round"].num?.toInt() ?: return@mapNotNull null
            val utc = parseTime(g["utcDate"].str) ?: return@mapNotNull null
            if (code == 0 || round == 0) return@mapNotNull null
            ScheduleGame(
                gameCode = code,
                round = round,
                utc = utc,
                home = g["local"].obj?.get("club").obj?.get("code").strOrNull,
                away = g["road"].obj?.get("club").obj?.get("code").strOrNull,
            )
        }

    // games: schedule. A round "opens" 12h before its first game, so we keep
    // showing the finished round until the next one is near.
    fun pickRound(games: List<ScheduleGame>, now: Long): Int? {
        if (games.isEmpty()) return null
        val sorted = games.sortedBy { it.utc }
        var current = sorted[0].round
        for (g in sorted) if (g.utc <= now + 12 * HOUR) current = g.round
        return current
    }

    // ---------- BasketNews lineup -> roster ----------

    private val SUFFIX = Regex("\\s+(JR|SR|II|III|IV|V)\\.?$", RegexOption.IGNORE_CASE)
    private val TRIPLE = Regex("triple", RegexOption.IGNORE_CASE)
    private val BENCH = Regex("bench", RegexOption.IGNORE_CASE)

    fun stripSuffix(lastName: String): String = lastName.replace(SUFFIX, "").trim()

    /** gameMode.identifier of the lineup's team, e.g. "classic_mode". */
    fun gameModeOf(lineup: JsonObject?): String? =
        lineup?.get("fantasyTeam").obj?.get("gameMode").obj?.get("identifier").strOrNull

    // Convert the fantasyTeamLineupRecordFromClient GraphQL object into a roster.
    // Roles come from the card ids: c-/f-/g- = starting five, b-1 = sixth player,
    // b-2..b-5 = bench. The captain is flagged separately. The lineup that was
    // actually played (playedAs...) wins over the editable one when present.
    fun lineupToRoster(lineup: JsonObject): Roster {
        val round = lineup["fantasyRound"].num
        val wildcards = (lineup["fantasyTeam"].obj?.get("wildcards").arr ?: emptyList())
            .mapNotNull { it.obj }
            .filter { it["fantasyRound"].num == round }
            .map { it["identifier"].str }
        val options = ScoringOptions(
            tripleCaptain = wildcards.any { TRIPLE.containsMatchIn(it) },
            benchBoost = wildcards.any { BENCH.containsMatchIn(it) },
        )

        val players = (lineup["players"].arr ?: emptyList()).mapNotNull { it.obj }.map { lp ->
            val p = lp["player"].obj
            val card = lp["playedAsCardIdentifier"].strOrNull?.takeIf { it.isNotEmpty() }
                ?: lp["cardIdentifier"].str
            val playedAs = lp["playedAsCaptain"]
            val captain = if (playedAs != null && playedAs !is JsonNull) playedAs.truthy else lp["captain"].truthy
            val role = when {
                captain -> Role.CAPTAIN
                card.startsWith("b-") -> if (card == "b-1") Role.SIXTH else Role.BENCH
                else -> Role.STARTER
            }
            val first = p?.get("firstName").str.trim()
            val last = stripSuffix(p?.get("lastName").str)
            val pts = p?.get("fantasy_pts")
            RosterEntry(
                role = role,
                name = "$first $last".trim(),
                fallback = last,
                official = if (pts is JsonPrimitive && !pts.isString) pts.doubleOrNull else null,
                health = p?.get("health").strOrNull,
                first = first,
                gameTimes = gamesOfPlayer(p),
            )
        }
        return Roster(players, options)
    }

    // ---------- mapping a BasketNews round to a EuroLeague round ----------

    private fun gamesOfPlayer(p: JsonObject?): List<Long> =
        (p?.get("team").obj?.get("team").obj?.get("games").arr ?: emptyList())
            .mapNotNull { parseTime(it.obj?.get("originalGameAt").str) }
            .distinct()
            .sorted()

    // Start times (ms) of every game in the lineup's round, as BasketNews reports them.
    fun gameTimesOf(lineup: JsonObject?): List<Long> {
        val times = sortedSetOf<Long>()
        for (lp in lineup?.get("players").arr ?: emptyList()) {
            val t = lp.obj?.get("player").obj?.get("team").obj?.get("team").obj
            for (g in t?.get("games").arr ?: emptyList()) {
                parseTime(g.obj?.get("originalGameAt").str)?.let { times += it }
            }
        }
        return times.toList()
    }

    // Which EuroLeague round do these game times belong to? Majority vote over the
    // schedule, so it doesn't matter how either site numbers its rounds.
    fun mapRound(times: List<Long>, games: List<ScheduleGame>, toleranceMs: Long = 10 * MINUTE): Int? {
        val tally = LinkedHashMap<Int, Int>()
        for (t in times) {
            for (g in games) {
                if (abs(g.utc - t) <= toleranceMs) tally[g.round] = (tally[g.round] ?: 0) + 1
            }
        }
        var best: Int? = null
        var n = 0
        for ((round, c) in tally) {
            if (c > n) {
                best = round
                n = c
            }
        }
        return best
    }

    // ---------- misc ----------

    /** ISO date-time to epoch millis. Strings without an offset are read as UTC. */
    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** "12", "12.5", "-3.3" (like the extension's fmt). */
    fun fmt(n: Double): String {
        val r = round2(n)
        return if (r == Math.rint(r) && abs(r) < 1e15) r.roundToLong().toString() else r.toString()
    }

    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}

// ---------- tolerant JSON access (mirrors JS truthiness / Number()) ----------

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.arr: JsonArray? get() = this as? JsonArray

internal val JsonElement?.strOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal val JsonElement?.str: String get() = strOrNull ?: ""

internal val JsonElement?.num: Double?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.booleanOrNull?.takeIf { !p.isString }?.let { return if (it) 1.0 else 0.0 }
        return p.content.trim().toDoubleOrNull()
    }

internal val JsonElement?.truthy: Boolean
    get() {
        val p = this as? JsonPrimitive ?: return this != null && this !is JsonNull
        if (p is JsonNull) return false
        if (p.isString) return p.content.isNotEmpty()
        p.booleanOrNull?.let { return it }
        val d = p.content.toDoubleOrNull() ?: return false
        return d != 0.0 && !d.isNaN()
    }
