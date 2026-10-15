package dev.tomato.hongguo.tv

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.encodeToString

/** Local state in SharedPreferences; observable fields are Compose state so screens follow changes. */
class Store(private val prefs: SharedPreferences) {
    var serverUrl by mutableStateOf(prefs.getString("server", null) ?: BuildConfig.DEFAULT_SERVER)
        private set
    private var speedState by mutableStateOf(prefs.getFloat("speed", 1f))
    var speed: Float
        get() = speedState
        set(v) { speedState = v; prefs.edit().putFloat("speed", v).apply() }
    private var sidesState by mutableStateOf(Sides.entries.getOrElse(prefs.getInt("sides", 0)) { Sides.Blur })
    var sides: Sides
        get() = sidesState
        set(v) { sidesState = v; prefs.edit().putInt("sides", v.ordinal).apply() }
    var epDesc by mutableStateOf(prefs.getBoolean("epDesc", false))
        private set
    var watched: Map<String, Watched> by mutableStateOf(read("watched") ?: emptyMap())
        private set
    var favs: Map<String, Fav> by mutableStateOf(read("favs") ?: emptyMap())
        private set

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { ApiJson.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> write(key: String, value: T) =
        prefs.edit().putString(key, ApiJson.encodeToString(value)).apply()

    fun setServer(url: String) { serverUrl = url; prefs.edit().putString("server", url).apply() }
    fun toggleEpDesc() { epDesc = !epDesc; prefs.edit().putBoolean("epDesc", epDesc).apply() }

    fun saveProgress(meta: SeriesMeta, ep: Int, positionMs: Long) {
        watched = watched + (meta.seriesId to Watched(
            meta.seriesId, meta.title, meta.cover, meta.episodeCnt, ep, positionMs, System.currentTimeMillis(),
        ))
        write("watched", watched)
    }

    /** Adopt merged cloud records so cards resume where the other device stopped. */
    fun adopt(merged: List<Watched>) {
        val changed = merged.filter { watched[it.sid] != it }
        if (changed.isEmpty()) return
        watched = watched + changed.associateBy { it.sid }
        write("watched", watched)
    }

    fun clearWatched() { watched = emptyMap(); write("watched", watched) }

    fun history(): List<Watched> = watched.values.sortedByDescending { it.updatedAt }

    fun toggleFav(meta: SeriesMeta) {
        favs = if (meta.seriesId in favs) favs - meta.seriesId
        else favs + (meta.seriesId to Fav(meta.seriesId, meta.title, meta.cover, meta.episodeCnt, System.currentTimeMillis()))
        write("favs", favs)
    }
}
