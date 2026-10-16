package dev.tomato.hongguo.tv

import android.content.Context
import android.os.Build
import android.view.KeyEvent.KEYCODE_BUTTON_A
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
import android.view.KeyEvent.KEYCODE_MENU
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import android.view.ViewGroup
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The player's single state holder: queue, current episode, overlay, loading and error. */
class PlayerState(context: Context, private val api: Api, private val store: Store, private val scope: CoroutineScope) {
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            // /stream decrypts on first hit, so the first byte can take a while.
            DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(60_000)),
        )
        .build()

    var queue: PlayQueue? by mutableStateOf(null)
        private set
    var meta: SeriesMeta? by mutableStateOf(null)
        private set
    var episodes: List<Episode> by mutableStateOf(emptyList())
        private set
    var epIdx by mutableIntStateOf(0)
        private set
    var overlay by mutableStateOf(Overlay.None)
    var loading by mutableStateOf(false)
    var error: String? by mutableStateOf(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set
    /** Width / height of the current clip; 漫剧 are often landscape. */
    var videoAspect by mutableFloatStateOf(9f / 16f)
        private set
    var toast: String? by mutableStateOf(null)
    var toastAt by mutableLongStateOf(0)
        private set
    var backArmedAt = 0L
    var loggedIn: () -> Boolean = { false }

    /** The 推荐 feed is kept while a series queue plays so 推荐 can return to it. */
    private var feed: PlayQueue.Feed? = null
    private val epCache = HashMap<String, EpisodesResp>()
    private val lastSync = HashMap<String, Long>()
    private var loadJob: Job? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) durationMs = exo.duration.coerceAtLeast(0)
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) saveNow()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onPlayerError(e: PlaybackException) {
                loading = false
                error = "播放失败（${e.errorCodeName}）· 按 OK 重试"
            }
        })
    }

    val current: Episode? get() = episodes.getOrNull(epIdx)

    fun showToast(text: String) { toast = text; toastAt = System.currentTimeMillis() }

    fun startFeed(cards: List<SeriesCard>, index: Int = 0) {
        if (cards.isEmpty()) return
        val f = PlayQueue.Feed(cards, index.coerceIn(0, cards.size - 1))
        feed = f
        queue = f
        loadSeries(f.series[f.index].seriesId, null, f.series[f.index])
    }

    /** Back to the 推荐 feed; false when there is none yet. */
    fun resumeFeed(): Boolean {
        val f = feed ?: return false
        if (queue !is PlayQueue.Feed) {
            queue = f
            loadSeries(f.series[f.index].seriesId, null, f.series[f.index])
        }
        return true
    }

    fun openSeries(seriesId: String, ep: Int? = null, card: SeriesCard? = null) {
        loadSeries(seriesId, ep, card, asSeries = true)
    }

    private fun loadSeries(seriesId: String, ep: Int?, card: SeriesCard?, asSeries: Boolean = false) {
        saveNow()
        loadJob?.cancel()
        exo.stop()
        error = null
        loading = true
        if (card != null) {
            meta = SeriesMeta(card.seriesId, card.title, card.intro, card.episodeCnt, cover = card.cover)
            episodes = emptyList()
        }
        loadJob = scope.launch {
            val resp = epCache[seriesId] ?: runCatching { api.episodes(seriesId) }.getOrNull()?.also { epCache[seriesId] = it }
            if (resp == null || resp.episodes.isEmpty()) {
                loading = false
                error = "加载剧集失败 · 按 OK 重试"
                return@launch
            }
            meta = resp.meta
            episodes = resp.episodes
            val saved = store.watched[seriesId]
            val wantEp = ep ?: saved?.ep ?: 1
            val idx = resp.episodes.indexOfFirst { it.index == wantEp }.coerceAtLeast(0)
            if (asSeries) {
                queue = PlayQueue.Series(seriesId, resp.episodes, idx)
            }
            val resumeMs = if (saved != null && saved.ep == resp.episodes[idx].index) saved.positionMs else 0L
            if (ep == null && saved != null && (saved.ep > 1 || resumeMs > 0)) {
                val next = if (queue is PlayQueue.Feed) "下一部" else "下一集"
                showToast("已为你接着播放第 ${saved.ep} 集 · ↓ $next")
            }
            play(idx, resumeMs)
        }
    }

    private fun play(idx: Int, startMs: Long) {
        val m = meta ?: return
        val e = episodes.getOrNull(idx) ?: return
        epIdx = idx
        (queue as? PlayQueue.Series)?.let { if (it.index != idx) queue = it.copy(index = idx) }
        error = null
        positionMs = startMs
        durationMs = (e.duration * 1000).toLong()
        exo.setMediaItem(MediaItem.fromUri(api.stream(e.vid)), startMs)
        exo.setPlaybackSpeed(store.speed)
        exo.prepare()
        exo.playWhenReady = true
        store.saveProgress(m, e.index, startMs)
        episodes.getOrNull(idx + 1)?.let { n -> scope.launch { runCatching { api.warm(m.seriesId, n.index) } } }
        sync(force = true)
    }

    fun playEpisode(idx: Int) {
        val saved = store.watched[meta?.seriesId]
        val e = episodes.getOrNull(idx) ?: return
        saveNow()
        play(idx, if (saved?.ep == e.index) saved.positionMs else 0L)
    }

    fun retry() {
        val m = meta ?: return
        if (episodes.isEmpty()) loadSeries(m.seriesId, null, null, asSeries = queue is PlayQueue.Series)
        else playEpisode(epIdx)
    }

    fun move(delta: Int) {
        val q = queue ?: return
        val next = q.moved(delta)
        if (next == null) {
            showToast(if (delta > 0) "已经是最后一${if (q is PlayQueue.Feed) "部" else "集"}了" else "已经是第一${if (q is PlayQueue.Feed) "部" else "集"}了")
            return
        }
        when (next) {
            is PlayQueue.Feed -> { feed = next; queue = next; loadSeries(next.series[next.index].seriesId, null, next.series[next.index]) }
            is PlayQueue.Series -> { queue = next; playEpisode(next.index) }
        }
    }

    private fun onEnded() {
        val q = queue ?: return
        when (val step = afterEnd(q, epIdx, episodes.size)) {
            is AfterEnd.NextEpisode -> play(step.epIdx, 0)
            is AfterEnd.NextQueue -> {
                val n = step.queue
                if (n is PlayQueue.Feed) { feed = n; queue = n; loadSeries(n.series[n.index].seriesId, null, n.series[n.index]) }
                else { queue = n; play(n.index, 0) }
            }
            AfterEnd.Stop -> showToast("已经看完了")
        }
    }

    fun seek(deltaMs: Long) {
        val dur = exo.duration.takeIf { it > 0 } ?: durationMs
        val to = (exo.currentPosition + deltaMs).coerceIn(0, (dur - 1000).coerceAtLeast(0))
        exo.seekTo(to)
        positionMs = to
        showToast("${if (deltaMs < 0) "◀◀" else "▶▶"}  ${fmtTime(to)} / ${fmtTime(dur)}")
    }

    fun togglePlay() { if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0); exo.play() } }

    fun setSpeed(v: Float) { store.speed = v; exo.setPlaybackSpeed(v) }

    /** Called every 500 ms while the player is on screen. */
    fun tick() {
        positionMs = exo.currentPosition
        if (exo.duration > 0) durationMs = exo.duration
    }

    fun saveNow() {
        val m = meta ?: return
        val e = current ?: return
        if (exo.currentMediaItem == null) return
        store.saveProgress(m, e.index, exo.currentPosition)
    }

    /** Pushes progress to the account at most every 30 s per series, or at once on an episode change. */
    fun sync(force: Boolean = false) {
        val m = meta ?: return
        val e = current ?: return
        if (!loggedIn()) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastSync[m.seriesId] ?: 0) < 30_000) return
        lastSync[m.seriesId] = now
        val pos = exo.currentPosition
        scope.launch { runCatching { api.syncHistory(m.seriesId, e.index, pos) } }
    }

    fun pause() { saveNow(); exo.pause() }
}

private fun remoteKey(code: Int): RemoteKey = when (code) {
    KEYCODE_DPAD_UP -> RemoteKey.Up
    KEYCODE_DPAD_DOWN -> RemoteKey.Down
    KEYCODE_DPAD_LEFT -> RemoteKey.Left
    KEYCODE_DPAD_RIGHT -> RemoteKey.Right
    KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER, KEYCODE_BUTTON_A -> RemoteKey.Ok
    KEYCODE_BACK -> RemoteKey.Back
    KEYCODE_MENU -> RemoteKey.Menu
    KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE -> RemoteKey.PlayPause
    KEYCODE_MEDIA_NEXT -> RemoteKey.Next
    KEYCODE_MEDIA_PREVIOUS -> RemoteKey.Prev
    else -> RemoteKey.Other
}

@Composable
fun PlayerScreen(app: AppState) {
    val p = app.player
    val rootFocus = remember { FocusRequester() }
    var lastInput by remember { mutableLongStateOf(0L) }
    val consumed = remember { HashSet<Int>() }

    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(500)
            p.tick()
            if (++n % 10 == 0 && p.playing) { p.saveNow(); p.sync() }
        }
    }
    LaunchedEffect(p.overlay, lastInput) {
        if (p.overlay == Overlay.None) rootFocus.requestFocus()
        if (p.overlay == Overlay.Controls) { delay(4000); p.overlay = Overlay.None }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { ev ->
                val code = ev.key.nativeKeyCode
                if (ev.type == KeyEventType.KeyUp) return@onPreviewKeyEvent consumed.remove(code)
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                val rk = remoteKey(code)
                if (rk == RemoteKey.Ok && p.overlay == Overlay.None && p.error != null) {
                    consumed += code; p.retry(); return@onPreviewKeyEvent true
                }
                val action = playerAction(p.overlay, rk, ev.nativeKeyEvent.repeatCount, lastInput, p.backArmedAt)
                when (action) {
                    PlayerAction.Pass -> return@onPreviewKeyEvent false
                    PlayerAction.Prev -> p.move(-1)
                    PlayerAction.Next -> p.move(1)
                    PlayerAction.PlayPause -> p.togglePlay()
                    PlayerAction.ToggleOrder -> app.store.toggleEpDesc()
                    PlayerAction.Exit -> app.exit()
                    PlayerAction.CloseNavArmExit -> {
                        p.backArmedAt = lastInput
                        p.overlay = Overlay.None
                        p.showToast("再按一次返回键退出")
                    }
                    is PlayerAction.Seek -> p.seek(action.deltaMs)
                    is PlayerAction.Show -> p.overlay = action.overlay
                }
                consumed += code
                true
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        VideoLayer(app)
        when (p.overlay) {
            Overlay.None -> Immersive(p)
            Overlay.Controls, Overlay.Speed, Overlay.More -> ControlLayer(app)
            Overlay.Episodes -> { Immersive(p); EpisodePanel(app) }
            Overlay.SideNav -> SideNav(app)
        }
        Toast(p)
    }
}

@Composable
private fun VideoLayer(app: AppState) {
    val p = app.player
    val cover = p.meta?.cover?.let { app.api.img(it) }
    Box(Modifier.fillMaxSize()) {
        if (app.store.sides == Sides.Blur && cover != null) {
            AsyncImage(
                cover, null,
                Modifier.fillMaxSize().let { if (Build.VERSION.SDK_INT >= 31) it.blur(40.dp) else it },
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        }
        Box(Modifier.align(Alignment.Center).aspectRatio(p.videoAspect).background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        player = p.exo
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (p.loading && p.error == null) Spinner(Modifier.align(Alignment.Center))
            p.error?.let {
                Box(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(16.dp, 10.dp)) {
                    T(it, 14.sp, maxLines = 3)
                }
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(36.dp)) {
            drawCircle(Color(0x40FFFFFF), style = Stroke(3.dp.toPx()))
            drawArc(Color.White, angle, 90f, false, style = Stroke(3.dp.toPx()))
        }
        T("加载中…", 13.sp)
    }
}

@Composable
private fun ProgressBar(p: PlayerState, height: Int, modifier: Modifier = Modifier) {
    val frac = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f
    Box(modifier.height(height.dp).background(Color(0x38FFFFFF), RoundedCornerShape(height.dp))) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(Accent, RoundedCornerShape(height.dp)))
    }
}

@Composable
private fun Immersive(p: PlayerState) {
    val m = p.meta ?: return
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(m.title, 15.sp, Fg.copy(alpha = .85f), FontWeight.Bold)
            p.current?.let { T("第 ${it.index} 集 / 共 ${p.episodes.size} 集", 12.sp, Muted.copy(alpha = .85f)) }
        }
        ProgressBar(p, 2, Modifier.fillMaxWidth().align(Alignment.BottomStart))
    }
}

@Composable
private fun Toast(p: PlayerState) {
    val text = p.toast ?: return
    LaunchedEffect(p.toastAt) { delay(3000); p.toast = null }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.Center).offset(y = 190.dp).background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(16.dp, 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (text.contains("↓")) Icon(Icons.Down, null, Modifier.size(14.dp), tint = Fg)
            T(text, 13.sp)
        }
    }
}

@Composable
private fun ControlLayer(app: AppState) {
    val p = app.player
    val m = p.meta ?: return
    val first = remember { FocusRequester() }
    val speedBtn = remember { FocusRequester() }
    val moreBtn = remember { FocusRequester() }
    val followed = m.seriesId in app.store.favs
    var prev by remember { mutableStateOf(p.overlay) }
    LaunchedEffect(p.overlay) {
        if (p.overlay == Overlay.Controls) {
            runCatching { (if (prev == Overlay.Speed) speedBtn else if (prev == Overlay.More) moreBtn else first).requestFocus() }
        }
        prev = p.overlay
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(350.dp).align(Alignment.BottomStart).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 56.dp).width(270.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.status.isNotEmpty()) {
                    Box(Modifier.background(Accent, RoundedCornerShape(3.dp)).padding(5.dp, 1.dp)) { T(m.status, 11.sp, Color.White, FontWeight.Bold) }
                }
                T("${if (m.status == "完结") "已完结 · " else ""}共 ${m.episodeCnt.takeIf { it > 0 } ?: p.episodes.size} 集", 13.sp, Muted)
            }
            T(m.title, 22.sp, weight = FontWeight.Bold, maxLines = 2)
            p.current?.let { T("第 ${it.index} 集", 16.sp, weight = FontWeight.Medium) }
            if (m.intro.isNotEmpty()) T(m.intro, 13.sp, Muted, maxLines = 2)
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 56.dp), verticalAlignment = Alignment.Bottom) {
            RoundButton("选集", { p.overlay = Overlay.Episodes }) { Icon(Icons.Episodes, null, Modifier.size(18.dp)) }
            RoundButton(if (p.playing) "暂停" else "播放", { p.togglePlay() }, Modifier.focusRequester(first)) {
                Icon(if (p.playing) Icons.Pause else Icons.Play, null, Modifier.size(18.dp))
            }
            RoundButton(if (followed) "已追剧" else "追剧", { app.store.toggleFav(m) }) {
                Icon(if (followed) Icons.Star else Icons.StarLine, null, Modifier.size(19.dp), tint = if (followed) Color(0xFFFFC53D) else Fg)
            }
            Box(contentAlignment = Alignment.TopCenter) {
                RoundButton("倍速", { p.overlay = Overlay.Speed }, Modifier.focusRequester(speedBtn)) {
                    T("${app.store.speed.let { if (it % 1f == 0f) "%.1f".format(it) else "$it" }}x", 12.sp, weight = FontWeight.Bold)
                }
                if (p.overlay == Overlay.Speed) SpeedMenu(app)
            }
            Box(contentAlignment = Alignment.TopEnd) {
                RoundButton("更多", { p.overlay = Overlay.More }, Modifier.focusRequester(moreBtn)) { Icon(Icons.More, null, Modifier.size(18.dp)) }
                if (p.overlay == Overlay.More) MoreMenu(app)
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T(fmtTime(p.positionMs), 11.sp, Muted)
            ProgressBar(p, 3, Modifier.weight(1f))
            T(fmtTime(p.durationMs), 11.sp, Muted)
        }
    }
}

@Composable
private fun PopupMenu(items: List<Pair<String, Boolean>>, initial: Int, alignEnd: Boolean = false, onPick: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Zero-size layout so the menu floats above its button without resizing the button row.
    Box(Modifier.layout { measurable, _ ->
        val menu = measurable.measure(Constraints())
        layout(0, 0) { menu.place(if (alignEnd) -menu.width else -menu.width / 2, -menu.height - 12.dp.roundToPx()) }
    }) {
        Column(
            Modifier.background(Color(0xF51C1C20), RoundedCornerShape(10.dp)).padding(6.dp).width(150.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { i, (label, sel) ->
                Pill(
                    label, { onPick(i) }, Modifier.fillMaxWidth().let { if (i == initial) it.focusRequester(focus) else it },
                    selected = sel, content = if (sel) Accent else Fg, container = Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun SpeedMenu(app: AppState) {
    val cur = SPEEDS.indexOf(app.store.speed).coerceAtLeast(0)
    PopupMenu(SPEEDS.mapIndexed { i, s -> "${s}x" to (i == cur) }, cur) {
        app.player.setSpeed(SPEEDS[it])
        app.player.overlay = Overlay.Controls
    }
}

@Composable
private fun MoreMenu(app: AppState) {
    val sides = app.store.sides
    PopupMenu(listOf("两侧：${if (sides == Sides.Blur) "模糊填满" else "纯黑"}" to false, "设置" to false), 0, alignEnd = true) {
        if (it == 0) app.store.sides = if (sides == Sides.Blur) Sides.Black else Sides.Blur
        else app.go(Screen.Settings)
    }
}

@Composable
private fun EpisodePanel(app: AppState) {
    val p = app.player
    val m = p.meta ?: return
    val desc = app.store.epDesc
    val ranges = episodeRanges(p.episodes.size).let { if (desc) it.reversed() else it }
    var range by remember(desc, p.episodes) { mutableIntStateOf(ranges.indexOfFirst { p.epIdx in it }.coerceAtLeast(0)) }
    val savedEp = app.store.watched[m.seriesId]?.ep ?: 0
    val followed = m.seriesId in app.store.favs
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(632f / 960f).background(Color(0x59000000)))
        Column(
            Modifier.align(Alignment.CenterEnd).padding(20.dp).width(310.dp).fillMaxHeight()
                .background(Color(0xF51C1C20), RoundedCornerShape(12.dp)).padding(start = 18.dp, end = 18.dp, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(app.api.img(m.cover), null, Modifier.size(48.dp, 64.dp).background(Color(0xFF6A4B55), RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    T(m.title, 16.sp, weight = FontWeight.Bold)
                    T("${if (m.status == "完结") "已完结" else m.status} · 共 ${p.episodes.size} 集", 12.sp, Color(0xFFA9AEB6))
                }
                Pill(
                    if (followed) "已追剧" else "追剧", { app.store.toggleFav(m) }, icon = Icons.Star, height = 28.dp, radius = 14.dp, size = 12.sp,
                    container = if (followed) Accent else Color(0x1FFFFFFF), content = Color.White,
                )
            }
            if (m.intro.isNotEmpty()) T(m.intro, 12.sp, Color(0xFFA9AEB6), maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowItemsIndexed(ranges) { i, r ->
                        Pill(
                            p.episodes[if (desc) r.last else r.first].index.toString() + "-" + p.episodes[if (desc) r.first else r.last].index, { range = i },
                            Modifier.onFocusChanged { if (it.isFocused) range = i }, selected = i == range, height = 28.dp, size = 12.sp,
                            container = if (i == range) Color(0xFF3A3B42) else Color.Transparent, radius = 5.dp, padH = 9.dp,
                        )
                    }
                }
                Pill(if (desc) "倒序 ⇅" else "正序 ⇅", { app.store.toggleEpDesc() }, height = 28.dp, size = 11.sp, container = Color.Transparent, radius = 5.dp, padH = 7.dp)
                Pill("最新", { p.overlay = Overlay.None; p.playEpisode(p.episodes.lastIndex) }, height = 28.dp, size = 11.sp, container = Color.Transparent, radius = 5.dp, padH = 7.dp)
            }
            val r = ranges.getOrNull(range) ?: return@Column
            val inRange = p.episodes.subList(r.first, r.last + 1).let { if (desc) it.asReversed() else it }
            val focusAt = inRange.indexOfFirst { it.index == p.current?.index }.coerceAtLeast(0)
            key(range, desc) {
                val grid = rememberLazyGridState(initialFirstVisibleItemIndex = focusAt / 3 * 3)
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.weight(1f), grid,
                    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(inRange, key = { _, e -> e.index }) { i, e ->
                        val cur = e.index == p.current?.index
                        val seen = e.index < savedEp
                        EpisodeButton(e.index, cur, seen, if (i == focusAt) Modifier.focusRequester(focus) else Modifier) {
                            p.overlay = Overlay.None
                            p.playEpisode(p.episodes.indexOf(e))
                        }
                    }
                }
            }
            LaunchedEffect(desc) { runCatching { focus.requestFocus() } }
            T("↑↓←→ 选集 · OK 播放 · 菜单键 正倒序 · 返回 关闭", 11.sp, Color(0xFF80868F), modifier = Modifier.padding(bottom = 14.dp))
        }
    }
}

@Composable
private fun EpisodeButton(n: Int, current: Boolean, seen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(39.dp).let { if (current) it.border(1.5.dp, Accent, shape) else it },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) Color(0x2EFF3B5C) else Color(0xFF2A2A30),
            contentColor = if (current) Color(0xFFFF5C78) else if (seen) Color(0xFF6D727A) else Color(0xFFE6E8EC),
            focusedContainerColor = FocusBg, focusedContentColor = FocusFg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.tv.material3.Text("第${n}集", fontSize = 13.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

enum class Nav(val label: String) { Feed("推荐"), Theater("剧场"), Search("搜索"), Follow("追剧"), History("历史") }

@Composable
private fun SideNav(app: AppState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val icons = mapOf(Nav.Feed to Icons.Home, Nav.Theater to Icons.Theater, Nav.Search to Icons.Search, Nav.Follow to Icons.Follow, Nav.History to Icons.History)
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Color(0xF2000000), 0.25f to Color(0xD9000000), 0.47f to Color(0x59000000), 1f to Color(0x33000000)),
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 30.dp, bottom = 30.dp).width(212.dp).fillMaxHeight()) {
            val user = app.user
            NavItem(
                if (user != null) "${user.name.ifEmpty { "已登录" }} · 已同步" else "未登录 · 扫码登录",
                selected = false, onClick = { if (user != null) app.go(Screen.Mine(0)) else app.go(Screen.Login) },
                leading = { Box(Modifier.size(26.dp).background(Color(0xFF5B6270), CircleShape), contentAlignment = Alignment.Center) { T((user?.name ?: "?").take(1), 12.sp) } },
                dim = true,
            )
            Spacer(Modifier.height(18.dp))
            Nav.entries.forEach { n ->
                NavItem(n.label, n == app.navSel, { app.nav(n) }, if (n == app.navSel) Modifier.focusRequester(focus) else Modifier, leading = { Icon(icons.getValue(n), null, Modifier.size(18.dp)) })
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.weight(1f))
            NavItem("设置", false, { app.go(Screen.Settings) }, leading = { Icon(Icons.Settings, null, Modifier.size(17.dp)) }, dim = true)
        }
    }
}

@Composable
fun NavItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dim: Boolean = false, leading: @Composable () -> Unit) {
    Box {
        if (selected) Box(Modifier.offset(x = (-8).dp, y = 3.dp).size(4.dp, 32.dp).background(Accent, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        Surface(
            onClick = onClick,
            modifier = modifier.size(192.dp, 38.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) Fg else if (dim) Color(0x80EBF0F6) else Color(0x99EBF0F6),
                focusedContainerColor = FocusBg, focusedContentColor = FocusFg,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.fillMaxSize().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leading()
                androidx.tv.material3.Text(label, fontSize = if (dim) 14.sp else 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}
