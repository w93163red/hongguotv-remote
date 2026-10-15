package dev.tomato.hongguo.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---- hongguo-web response shapes (only the fields the TV client reads) ----

val ApiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** An item of /rank, /latest, /browse and /search/page. */
@Serializable
data class SeriesCard(
    @SerialName("series_id") val seriesId: String,
    val title: String = "",
    @SerialName("episode_cnt") val episodeCnt: Int = 0,
    val cover: String = "",
    val intro: String = "",
    val score: String = "",
    @SerialName("play_cnt") val playCnt: Long = 0,
    val hot: String = "",
)

@Serializable
data class CardList(val items: List<SeriesCard> = emptyList())

@Serializable
data class FilterItem(val id: String = "", val name: String = "")

@Serializable
data class FilterRow(val type: String = "", val items: List<FilterItem> = emptyList())

@Serializable
data class Filters(val rows: List<FilterRow> = emptyList()) {
    val themes: List<String>
        get() = rows.firstOrNull { it.type == "category_dim_theme" }?.items?.map { it.name }.orEmpty()
}

@Serializable
data class SeriesMeta(
    @SerialName("series_id") val seriesId: String,
    val title: String = "",
    val intro: String = "",
    @SerialName("episode_cnt") val episodeCnt: Int = 0,
    val status: String = "",
    @SerialName("play_cnt") val playCnt: Long = 0,
    val cover: String = "",
)

@Serializable
data class Episode(
    val index: Int,
    val vid: String,
    val title: String = "",
    val duration: Double = 0.0,
    val cover: String = "",
)

@Serializable
data class EpisodesResp(val meta: SeriesMeta, val episodes: List<Episode> = emptyList())

@Serializable
data class SearchHistory(val history: List<String> = emptyList())

@Serializable
data class HotWords(val hot: List<String> = emptyList())

@Serializable
data class QrCode(val token: String, val qrcode: String)

@Serializable
data class AuthUser(val name: String = "", val avatar: String = "")

@Serializable
data class AuthStatus(val status: String = "new", val user: AuthUser? = null)

@Serializable
data class Me(@SerialName("logged_in") val loggedIn: Boolean = false, val user: AuthUser? = null)

@Serializable
data class CloudItem(
    val sid: String,
    val ep: Int = 0,
    @SerialName("pos_ms") val posMs: Long = 0,
    val ts: Long = 0,
    val title: String = "",
    val cover: String = "",
)

@Serializable
data class CloudHistory(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    val items: List<CloudItem> = emptyList(),
)

// ---- persisted local state ----

/** Progress memory and history in one record per series; history is these sorted by [updatedAt]. */
@Serializable
data class Watched(
    val sid: String,
    val title: String = "",
    val cover: String = "",
    val episodeCnt: Int = 0,
    val ep: Int = 1,
    val positionMs: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class Fav(
    val sid: String,
    val title: String = "",
    val cover: String = "",
    val episodeCnt: Int = 0,
    val addedAt: Long = 0,
)

enum class Sides { Blur, Black }

val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 3f)

/** Local history wins per series unless the cloud record is newer; cloud titles fill local gaps. */
fun mergeHistory(local: Collection<Watched>, cloud: List<CloudItem>): List<Watched> {
    val bySid = local.associateBy { it.sid }.toMutableMap()
    for (c in cloud) {
        val l = bySid[c.sid]
        if (l == null || c.ts > l.updatedAt) {
            bySid[c.sid] = Watched(
                sid = c.sid,
                title = c.title.ifEmpty { l?.title.orEmpty() },
                cover = c.cover.ifEmpty { l?.cover.orEmpty() },
                episodeCnt = l?.episodeCnt ?: 0,
                ep = c.ep.coerceAtLeast(1),
                positionMs = c.posMs,
                updatedAt = c.ts,
            )
        }
    }
    return bySid.values.sortedByDescending { it.updatedAt }
}

// ---- playback model ----

sealed interface PlayQueue {
    val index: Int
    val size: Int

    /** The 推荐 home: UP/DOWN changes series. */
    data class Feed(val series: List<SeriesCard>, override val index: Int) : PlayQueue {
        override val size get() = series.size
    }

    /** One series opened from 剧场/搜索/我的/选集: UP/DOWN changes episode. */
    data class Series(val seriesId: String, val episodes: List<Episode>, override val index: Int) : PlayQueue {
        override val size get() = episodes.size
    }
}

fun PlayQueue.moved(delta: Int): PlayQueue? {
    val i = index + delta
    if (i !in 0 until size) return null
    return when (this) {
        is PlayQueue.Feed -> copy(index = i)
        is PlayQueue.Series -> copy(index = i)
    }
}

sealed interface AfterEnd {
    data class NextEpisode(val epIdx: Int) : AfterEnd
    data class NextQueue(val queue: PlayQueue) : AfterEnd
    data object Stop : AfterEnd
}

/** At the end of an episode both queues go to the next episode; the feed moves on to the next series after the last. */
fun afterEnd(queue: PlayQueue, epIdx: Int, epCount: Int): AfterEnd = when (queue) {
    is PlayQueue.Series -> queue.moved(1)?.let { AfterEnd.NextQueue(it) } ?: AfterEnd.Stop
    is PlayQueue.Feed ->
        if (epIdx + 1 < epCount) AfterEnd.NextEpisode(epIdx + 1)
        else queue.moved(1)?.let { AfterEnd.NextQueue(it) } ?: AfterEnd.Stop
}

enum class Overlay { None, Controls, Episodes, Speed, More, SideNav }

enum class RemoteKey { Up, Down, Left, Right, Ok, Back, Menu, PlayPause, Next, Prev, Other }

sealed interface PlayerAction {
    data object Prev : PlayerAction
    data object Next : PlayerAction
    data object PlayPause : PlayerAction
    data object ToggleOrder : PlayerAction
    data object Exit : PlayerAction
    /** Close the side nav and arm a second BACK to exit. */
    data object CloseNavArmExit : PlayerAction
    /** Let the focus system handle the key. */
    data object Pass : PlayerAction
    data class Seek(val deltaMs: Long) : PlayerAction
    data class Show(val overlay: Overlay) : PlayerAction
}

const val EXIT_WINDOW_MS = 2000L

/** Holding LEFT/RIGHT seeks 10 s per step at first, growing by 10 s every 4 repeats up to 60 s. */
fun seekStepMs(repeat: Int): Long = 10_000L * (1 + repeat / 4).coerceAtMost(6)

fun playerAction(overlay: Overlay, key: RemoteKey, repeat: Int, now: Long, backArmedAt: Long): PlayerAction {
    when (key) {
        RemoteKey.PlayPause -> return PlayerAction.PlayPause
        RemoteKey.Next -> return PlayerAction.Next
        RemoteKey.Prev -> return PlayerAction.Prev
        else -> {}
    }
    if (key == RemoteKey.Back && repeat > 0) return PlayerAction.Pass
    return when (overlay) {
        Overlay.None -> when (key) {
            RemoteKey.Up -> PlayerAction.Prev
            RemoteKey.Down -> PlayerAction.Next
            RemoteKey.Left -> PlayerAction.Seek(-seekStepMs(repeat))
            RemoteKey.Right -> PlayerAction.Seek(seekStepMs(repeat))
            RemoteKey.Ok -> PlayerAction.Show(Overlay.Controls)
            RemoteKey.Menu -> PlayerAction.Show(Overlay.Episodes)
            RemoteKey.Back ->
                if (now - backArmedAt < EXIT_WINDOW_MS) PlayerAction.Exit else PlayerAction.Show(Overlay.SideNav)
            else -> PlayerAction.Pass
        }
        Overlay.Controls -> when (key) {
            RemoteKey.Back -> PlayerAction.Show(Overlay.None)
            RemoteKey.Menu -> PlayerAction.Show(Overlay.Episodes)
            else -> PlayerAction.Pass
        }
        Overlay.Episodes -> when (key) {
            RemoteKey.Back -> PlayerAction.Show(Overlay.None)
            RemoteKey.Menu -> PlayerAction.ToggleOrder
            else -> PlayerAction.Pass
        }
        Overlay.Speed, Overlay.More -> when (key) {
            RemoteKey.Back, RemoteKey.Menu -> PlayerAction.Show(Overlay.Controls)
            else -> PlayerAction.Pass
        }
        Overlay.SideNav -> when (key) {
            RemoteKey.Back -> PlayerAction.CloseNavArmExit
            RemoteKey.Right -> PlayerAction.Show(Overlay.None)
            else -> PlayerAction.Pass
        }
    }
}

/** Range tabs of 30 episodes: "1-30", "31-60", "61-80". */
fun episodeRanges(count: Int): List<IntRange> =
    (0 until count step 30).map { it until minOf(it + 30, count) }

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

fun fmtCount(n: Long): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 1e8)
    n >= 10_000 -> "%.1f万".format(n / 1e4)
    else -> n.toString()
}
