package com.elflive.app.data

import android.content.Context
import android.webkit.CookieManager
import com.elflive.app.core.LineupQuery
import com.elflive.app.core.LiveApi
import com.elflive.app.core.StoredTeam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SITE_ORIGIN = "https://fantasy.basketnews.com"

/** EuroLeague live feeds + BasketNews GraphQL, using the WebView's cookies for the latter. */
class HttpApi(private val context: Context) : LiveApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val userAgent: String by lazy {
        UserAgent.get(context)
    }

    override suspend fun schedule(season: String): JsonElement {
        val req = Request.Builder()
            .url("https://api-live.euroleague.net/v2/competitions/E/seasons/$season/games?limit=400")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).await().use { res ->
            if (!res.isSuccessful) throw IOException("Schedule request failed (${res.code})")
            return json.parseToJsonElement(res.body!!.string())
        }
    }

    override suspend fun boxscore(season: String, gameCode: Int): JsonElement? {
        val req = Request.Builder()
            .url("https://live.euroleague.net/api/Boxscore?gamecode=$gameCode&seasoncode=$season")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).await().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string().orEmpty()
            // Not published yet: empty body or not JSON.
            return runCatching { json.parseToJsonElement(body) }.getOrNull()
        }
    }

    override suspend fun lineup(team: StoredTeam, round: Int): JsonObject {
        val cookies = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(SITE_ORIGIN) }
        val variables = buildJsonObject {
            put("locale", team.locale.ifBlank { "en" })
            put("leagueId", team.leagueId)
            put("fantasyTeamId", team.fantasyTeamId)
            put("fantasyRound", round)
            put("teamGamesCurrentRound", true)
            put("pointCalcSystem", "classic")
        }
        val payload = buildJsonObject {
            put("operationName", LineupQuery.OPERATION)
            put("variables", variables)
            put("query", LineupQuery.QUERY)
        }
        val req = Request.Builder()
            .url("$SITE_ORIGIN/backend/graphql")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("Origin", SITE_ORIGIN)
            .header("Referer", "$SITE_ORIGIN/")
            .header("User-Agent", userAgent)
            .apply { if (!cookies.isNullOrEmpty()) header("Cookie", cookies) }
            .build()
        client.newCall(req).await().use { res ->
            if (!res.isSuccessful) throw IOException("BasketNews request failed (${res.code})")
            val root = json.parseToJsonElement(res.body!!.string())
            val obj = (if (root is JsonArray) root.firstOrNull() else root) as? JsonObject
            val lineup = (obj?.get("data") as? JsonObject)?.get("fantasyTeamLineupRecordFromClient") as? JsonObject
            val players = lineup?.get("players") as? JsonArray
            if (lineup == null || players.isNullOrEmpty()) {
                val why = runCatching {
                    (obj!!["errors"]!!.jsonArray[0].jsonObject["message"] as JsonPrimitive).content
                }.getOrNull()
                throw IOException(why ?: "No lineup returned")
            }
            return lineup
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
