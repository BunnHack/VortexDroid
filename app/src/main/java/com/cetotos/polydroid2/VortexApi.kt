package com.cetotos.polydroid2

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for the playvortex.io backend (flow mirrors the community
 * "tempest" launcher):
 *
 *   POST /login (username, password, fingerprint, fp_token)
 *     -> Set-Cookie: session_token=...      (30-day web session)
 *   GET  /games/<id>/play (Cookie: session_token=...)
 *     -> HTML containing a fresh vortex://play?game=<id>&token=<ticket>
 *
 * The ticket is short-lived, so we only fetch it right before launching the
 * client - never cache it.
 */
object VortexApi {
    private const val TAG = "PolyDroid2"
    private const val BASE = "https://playvortex.io"

    fun getSessionToken(ctx: Context): String? =
        ctx.getSharedPreferences("vortex", Context.MODE_PRIVATE).getString("session_token", null)

    fun isLoggedIn(ctx: Context): Boolean = !getSessionToken(ctx).isNullOrBlank()

    class Game(val id: Int, val name: String, val creator: String)

    class ApiException(msg: String) : Exception(msg)

    /** POST /login and store the session_token cookie. Throws on bad credentials. */
    fun login(ctx: Context, username: String, password: String) {
        val conn = URL("$BASE/login").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            val form = mapOf(
                "username" to username,
                "password" to password,
                "fingerprint" to "",
                "fp_token" to "",
            ).map { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
                .joinToString("&")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            DataOutputStream(conn.outputStream).use { it.writeBytes(form) }

            val code = conn.responseCode
            if (code != 200 && code != 303 && code != 302) {
                val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                val detail = try { JSONObject(body).optString("detail") } catch (_: Exception) { "" }
                throw ApiException(detail.ifBlank { "login failed (HTTP $code)" })
            }

            // two session_token cookies come back: an explicit clear + the real one
            val token = conn.headerFields["Set-Cookie"]
                ?.filter { it.startsWith("session_token=") }
                ?.map { it.substringAfter("session_token=").substringBefore(';').trim('"') }
                ?.firstOrNull { it.isNotBlank() }
                ?: throw ApiException("server accepted login but set no session_token")

            ctx.getSharedPreferences("vortex", Context.MODE_PRIVATE)
                .edit().putString("session_token", token).apply()
            Log.i(TAG, "vortex login ok")
        } finally {
            conn.disconnect()
        }
    }

    fun logout(ctx: Context) {
        ctx.getSharedPreferences("vortex", Context.MODE_PRIVATE)
            .edit().remove("session_token").apply()
    }

    /** GET /games/<id>/play and extract a fresh vortex:// launch URI from the HTML. */
    fun getPlayUri(ctx: Context, gameId: Int): String {
        val token = getSessionToken(ctx) ?: throw ApiException("not logged in")
        val conn = URL("$BASE/games/$gameId/play").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Cookie", "session_token=$token")
            val code = conn.responseCode
            if (code == 302 || code == 301) {
                // back to login page -> session expired
                throw ApiException("session expired, please log in again")
            }
            if (code != 200) throw ApiException("play page failed (HTTP $code)")
            val html = conn.inputStream.bufferedReader().readText()
            val start = html.indexOf("vortex://")
            if (start < 0) throw ApiException("no launch ticket in play page")
            var end = start
            while (end < html.length) {
                val c = html[end]
                if (c == '"' || c == '\'' || c == ' ' || c == '<') break
                end++
            }
            val uri = html.substring(start, end)
                .replace("\\u0026", "&")
            if (!uri.startsWith("vortex://play")) throw ApiException("unexpected launch URI")
            return uri
        } finally {
            conn.disconnect()
        }
    }

    /** GET /api/games - public catalog listing (top-level JSON array). */
    fun listGames(): List<Game> {
        val conn = URL("$BASE/api/games").openConnection() as HttpURLConnection
        try {
            if (conn.responseCode != 200) throw ApiException("catalog failed (HTTP ${conn.responseCode})")
            val body = conn.inputStream.bufferedReader().readText()
            val arr = org.json.JSONArray(body)
            val out = mutableListOf<Game>()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val id = g.optInt("id")
                if (id > 0) out.add(Game(id, g.optString("name", "#$id"), g.optString("creator_name", "")))
            }
            return out.sortedBy { it.id }
        } finally {
            conn.disconnect()
        }
    }
}
