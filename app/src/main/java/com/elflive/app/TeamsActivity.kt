package com.elflive.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elflive.app.core.Engine
import com.elflive.app.core.GameSummary
import com.elflive.app.core.LiveState
import com.elflive.app.core.Outcome
import com.elflive.app.core.PlayerResult
import com.elflive.app.core.Role
import com.elflive.app.core.Scoring.fmt
import com.elflive.app.core.TeamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Native list of the watched teams: players with EFF, adjusted points and multiplier, plus the round's games. */
class TeamsActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private lateinit var refresh: Button

    private val green = Color.parseColor("#1A9850")
    private val red = Color.parseColor("#D73027")
    private val muted = Color.parseColor("#6B7280")
    private val line = Color.parseColor("#E5E7EB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.live_points_title)

        refresh = Button(this).apply {
            text = getString(R.string.refresh)
            setOnClickListener { doRefresh() }
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
            addView(refresh, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })
            addView(content)
            addView(about())
        }
        setContentView(ScrollView(this).apply { addView(page) })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.IO) { engine.loadState() }
                engine.state.collect { render(it) }
            }
        }
    }

    private fun doRefresh() {
        refresh.isEnabled = false
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { engine.tick(force = true, refreshLineups = true) }
            if (s.active) LiveService.start(this@TeamsActivity)
            refresh.isEnabled = true
        }
    }

    private fun render(s: LiveState?) {
        content.removeAllViews()
        if (s == null) {
            content.addView(text(getString(R.string.loading), color = muted))
            return
        }
        val head = buildString {
            if (s.round != null) append(getString(R.string.euroleague_round, s.round)).append("  ·  ")
            append(getString(R.string.updated, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.updated))))
        }
        content.addView(text(head, color = muted, size = 12f))
        s.error?.let { content.addView(errorBox(getString(R.string.last_update_failed, it))) }
        if (s.teams.isEmpty()) {
            content.addView(text(getString(R.string.no_teams), color = muted).apply { setPadding(0, dp(12), 0, dp(12)) })
        }
        if (s.games.isNotEmpty()) content.addView(games(s.games))
        s.teams.forEach { content.addView(team(it)) }
    }

    private fun games(games: List<GameSummary>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        box.addView(text(getString(R.string.games), bold = true))
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (g in games) {
            val score = g.score
            var t = if (score != null && score.size == 2) {
                "${score[0].code} ${fmt(score[0].points)} : ${fmt(score[1].points)} ${score[1].code}"
            } else {
                "${g.home ?: "?"} - ${g.away ?: "?"}   ${time.format(Date(g.utc))}"
            }
            when (g.status) {
                "live" -> t += "   LIVE"
                "final" -> t += "   " + getString(R.string.final_short)
            }
            box.addView(text(t, color = if (g.status == "live") green else Color.BLACK, size = 13f))
        }
        return box
    }

    private fun team(t: TeamState): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), line)
                setColor(Color.parseColor("#F9FAFB"))
            }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(text((if (t.live) "● " else "") + t.name, bold = true, size = 16f, color = if (t.live) green else Color.BLACK), weighted(1f))
        header.addView(text(if (t.supported) fmt(t.total) else "–", bold = true, size = 20f))
        card.addView(header)

        val sub = buildList {
            t.fantasyRound?.let { add(getString(R.string.fantasy_round, it)) }
            if (t.options.tripleCaptain) add(getString(R.string.triple_captain))
            if (t.options.benchBoost) add(getString(R.string.bench_boost))
        }
        if (sub.isNotEmpty()) card.addView(text(sub.joinToString("  ·  "), color = muted, size = 12f))

        if (!t.supported) {
            card.addView(text(Engine.UNSUPPORTED_MESSAGE, color = red).apply { setPadding(0, dp(8), 0, dp(4)) })
        } else if (t.players.isNotEmpty()) {
            card.addView(row(getString(R.string.col_player), getString(R.string.col_eff), getString(R.string.col_adj), getString(R.string.col_mult), getString(R.string.col_pts), header = true))
            t.players.forEach { card.addView(player(it)) }
        }
        t.errors.forEach { card.addView(errorBox(it)) }

        return card.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }
    }

    private fun roleLabel(r: Role) = when (r) {
        Role.CAPTAIN -> "C"
        Role.STARTER -> "S"
        Role.SIXTH -> "6"
        Role.BENCH -> "B"
    }

    private fun player(p: PlayerResult): View {
        val name = buildString {
            append(roleLabel(p.role)).append("  ")
            append(p.matchedName?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: p.input)
            if (p.playing) append(" ●")
            if (p.ambiguous) append(" ?")
        }
        val waiting = p.state == "waiting"
        val mult = "x" + fmt(p.mult)
        val r = row(
            name,
            if (waiting) "-" else fmt(p.eff),
            if (waiting) "-" else fmt(p.adjusted),
            mult,
            fmt(p.points),
            adjColor = when (p.outcome) {
                Outcome.WIN -> green
                Outcome.LOSS -> red
                else -> muted
            },
            dim = waiting,
        )
        if (p.official != null) r.contentDescription = "${p.input}: ${fmt(p.points)}; BasketNews ${fmt(p.official)}"
        return r
    }

    private fun row(
        name: String, eff: String, adj: String, mult: String, pts: String,
        header: Boolean = false, adjColor: Int = Color.BLACK, dim: Boolean = false,
    ): LinearLayout {
        val base = if (header || dim) muted else Color.BLACK
        val size = if (header) 11f else 13f
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            addView(text(name, color = base, size = size), weighted(3.2f))
            addView(text(eff, color = base, size = size, end = true), weighted(1f))
            addView(text(adj, color = if (header || dim) base else adjColor, size = size, end = true), weighted(1.2f))
            addView(text(mult, color = base, size = size, end = true), weighted(1f))
            addView(text(pts, color = base, size = size, bold = !header, end = true), weighted(1.2f))
        }
    }

    private fun about(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(24), 0, 0)
        addView(text(getString(R.string.about_title), bold = true))
        addView(text(getString(R.string.about_disclaimer), color = muted, size = 13f))
        addView(text(getString(R.string.about_rules), color = muted, size = 12f).apply { setPadding(0, dp(6), 0, 0) })
        addView(text(getString(R.string.about_version, BuildConfig.VERSION_NAME), color = muted, size = 12f))
    }

    private fun errorBox(msg: String) = text(msg, color = Color.parseColor("#991B1B"), size = 12f).apply {
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#FEE2E2"))
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun text(s: String, color: Int = Color.BLACK, size: Float = 14f, bold: Boolean = false, end: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextColor(color)
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (end) gravity = Gravity.END
        }

    private fun weighted(w: Float) = LinearLayout.LayoutParams(0, WRAP_CONTENT, w)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
