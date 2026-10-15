package dev.tomato.hongguo.tv

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Keeps the server's hg_sid cookie across launches; without it search history and login are lost. */
class PrefsCookieJar(private val prefs: SharedPreferences) : CookieJar {
    private fun key(url: HttpUrl) = "cookies:${url.host}:${url.port}"

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        val kept = load(url).filter { old -> cookies.none { it.name == old.name } } + cookies
        prefs.edit().putStringSet(key(url), kept.filter { it.expiresAt > now }.map { it.toString() }.toSet()).apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        load(url).filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }

    private fun load(url: HttpUrl) = prefs.getStringSet(key(url), emptySet())!!.mapNotNull { Cookie.parse(url, it) }
}

class ApiException(message: String) : IOException(message)

class Api(prefs: SharedPreferences) {
    @Volatile var base: String = ""

    private val jar = PrefsCookieJar(prefs)
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = base.trimEnd('/') + path

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun img(url: String): String? = url.takeIf { it.isNotEmpty() }?.let { url("/img?url=${enc(it)}") }

    fun stream(vid: String) = url("/stream?vid=${enc(vid)}")

    private suspend fun call(method: String, path: String, body: RequestBody? = null, server: String? = null): String =
        withContext(Dispatchers.IO) {
            val target = if (server != null) server.trimEnd('/') + path else url(path)
            val req = Request.Builder().url(target.toHttpUrl()).method(method, body).build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException("HTTP ${r.code}")
                text
            }
        }

    private suspend inline fun <reified T> get(path: String): T = ApiJson.decodeFromString(call("GET", path))

    private suspend fun postJson(path: String, vararg fields: Pair<String, Any>) {
        val obj = JsonObject(fields.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        call("POST", path, ApiJson.encodeToString(obj).toRequestBody("application/json".toMediaType()))
    }

    suspend fun ping(server: String) {
        ApiJson.decodeFromString<CardList>(call("GET", "/rank?board=hot&limit=1", server = server))
    }

    suspend fun rank(board: String, limit: Int = 60): List<SeriesCard> = get<CardList>("/rank?board=$board&limit=$limit").items
    suspend fun latest(): List<SeriesCard> = get<CardList>("/latest?genre=short_play&only_today=false").items
    suspend fun filters(genre: String): Filters = get("/filters?genre=$genre")
    suspend fun browse(genre: String, theme: String?): List<SeriesCard> =
        get<CardList>("/browse?genre=$genre${theme?.let { "&theme=${enc(it)}" }.orEmpty()}&sort=hot_score").items

    /** hongguo-web only hands out hg_sid with a QR code, and search history is keyed by it. */
    suspend fun ensureSid() {
        val url = url("/").toHttpUrl()
        if (jar.loadForRequest(url).none { it.name == "hg_sid" }) call("GET", "/auth/qrcode")
    }

    suspend fun search(q: String): List<SeriesCard> = get<CardList>("/search/page?q=${enc(q)}").items
    suspend fun searchHistory(): List<String> = get<SearchHistory>("/search/history").history
    suspend fun addSearchHistory(q: String) = postJson("/search/history", "q" to q)
    suspend fun clearSearchHistory() { call("DELETE", "/search/history") }
    suspend fun hotWords(): List<String> = get<HotWords>("/search/hot").hot

    suspend fun episodes(seriesId: String): EpisodesResp = get("/episodes?series_id=${enc(seriesId)}")
    suspend fun warm(seriesId: String, ep: Int) { call("GET", "/warm?series_id=${enc(seriesId)}&ep=$ep") }

    suspend fun qrcode(): QrCode = get("/auth/qrcode")
    suspend fun authStatus(token: String): AuthStatus = get("/auth/status?token=${enc(token)}")
    suspend fun me(): Me = get("/auth/me")
    suspend fun logout() { call("POST", "/auth/logout", ByteArray(0).toRequestBody()) }
    suspend fun cloudHistory(): CloudHistory = get("/auth/history")
    suspend fun syncHistory(seriesId: String, ep: Int, positionMs: Long) =
        postJson("/auth/sync_history", "series_id" to seriesId, "ep" to ep, "position_ms" to positionMs)
}
