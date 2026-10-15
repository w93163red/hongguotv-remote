package dev.tomato.hongguo.tv

import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** LEFT on a grid's first column stays put instead of escaping to the tab row above. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.firstColumn(i: Int, columns: Int) =
    if (i % columns == 0) focusProperties { left = FocusRequester.Cancel } else this

private val GENRES = listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 短剧")

/** A row of underlined tabs that switch on focus; entering the row lands on the selected tab. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, focus: FocusRequester, big: Boolean = true) {
    Row(
        Modifier.focusProperties { enter = { focus } },
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom,
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.let { if (sel) it.focusRequester(focus) else it }.onFocusChanged { if (it.isFocused) onSelect(i) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent, contentColor = if (sel) Fg else FgDim,
                    focusedContainerColor = FocusBg, focusedContentColor = FocusFg,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label, fontSize = if (big) (if (sel) 22.sp else 19.sp) else (if (sel) 20.sp else 18.sp),
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    )
                    Box(Modifier.padding(top = 4.dp).size(20.dp, 3.dp).background(if (sel) Accent else Color.Transparent, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
private fun CardGrid(
    app: AppState,
    items: List<SeriesCard>?,
    columns: Int,
    error: String?,
    modifier: Modifier = Modifier,
    posterHeight: Dp = 190.dp,
    ranked: Boolean = false,
    empty: String = "暂无内容",
    onOpen: () -> Unit = {},
) {
    when {
        error != null -> T(error, 15.sp, Accent, modifier = modifier.padding(top = 30.dp))
        items == null -> T("加载中…", 15.sp, FgDim, modifier = modifier.padding(top = 30.dp))
        items.isEmpty() -> T(empty, 15.sp, FgDim, modifier = modifier.padding(top = 30.dp))
        else -> LazyVerticalGrid(
            GridCells.Fixed(columns), modifier,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(items, key = { _, c -> c.seriesId }) { i, c ->
                val w = app.store.watched[c.seriesId]
                PosterCard(
                    c.title, app.api.img(c.cover), { onOpen(); app.open(c.seriesId, c) }, Modifier.firstColumn(i, columns), posterHeight = posterHeight,
                    meta = if (w != null) "看到第 ${w.ep} 集" else if (c.episodeCnt > 0) "共 ${c.episodeCnt} 集" else "",
                    rank = if (ranked) i + 1 else null,
                )
            }
        }
    }
}

@Composable
fun TheaterScreen(app: AppState) {
    BackHandler { app.backToNav() }
    val tabs = listOf("热播榜", "推荐榜", "新剧榜", "最新上线", "分类")
    var items by remember { mutableStateOf<List<SeriesCard>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var themes by remember { mutableStateOf(emptyList<String>()) }
    val tabFocus = remember { FocusRequester() }
    val browse = app.theaterTab == 4
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }
    LaunchedEffect(app.theaterTab, app.genre, app.theme) {
        items = null
        error = null
        delay(150) // focus sweeps across tabs; only load the one it stops on
        runCatching {
            when (app.theaterTab) {
                0 -> app.api.rank("hot")
                1 -> app.api.rank("recommend")
                2 -> app.api.rank("new")
                3 -> app.api.latest()
                else -> app.api.browse(app.genre, app.theme)
            }
        }.onSuccess { items = it }.onFailure { error = "加载失败：${it.message}" }
    }
    LaunchedEffect(browse, app.genre) {
        if (browse) themes = runCatching { app.api.filters(app.genre).themes }.getOrDefault(emptyList())
    }
    Box(Modifier.fillMaxSize().background(PageBg)) {
        Box(Modifier.width(4.dp).fillMaxSize().background(Color(0x0FFFFFFF)))
        Column(Modifier.padding(start = 30.dp, top = 22.dp, end = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tabs(tabs, app.theaterTab, { app.theaterTab = it }, tabFocus)
            if (browse) {
                Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    GENRES.forEach { (id, name) ->
                        Pill(name, { if (app.genre != id) { app.genre = id; app.theme = null } }, selected = app.genre == id, height = 30.dp, radius = 15.dp, size = 12.sp)
                    }
                }
                LazyRow(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item { Pill("全部主题", { app.theme = null }, selected = app.theme == null, height = 30.dp, radius = 15.dp, size = 12.sp) }
                    itemsIndexed(themes) { _, t ->
                        Pill(t, { app.theme = t }, selected = app.theme == t, height = 30.dp, radius = 15.dp, size = 12.sp)
                    }
                }
            }
            CardGrid(app, items, 6, error, ranked = app.theaterTab < 3)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(app: AppState) {
    BackHandler { app.backToNav() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var history by remember { mutableStateOf(emptyList<String>()) }
    var hot by remember { mutableStateOf(emptyList<String>()) }
    var hotRank by remember { mutableStateOf(emptyList<SeriesCard>()) }
    var results by remember { mutableStateOf<List<SeriesCard>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
    }
    val voiceAvailable = remember { voiceIntent.resolveActivity(ctx.packageManager) != null }

    fun submit(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        app.query = text
        scope.launch {
            runCatching { app.api.addSearchHistory(text) }
            history = runCatching { app.api.searchHistory() }.getOrDefault(history)
        }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { submit(it) }
    }

    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        runCatching { app.api.ensureSid() }
        history = runCatching { app.api.searchHistory() }.getOrDefault(emptyList())
        hot = runCatching { app.api.hotWords() }.getOrDefault(emptyList())
        hotRank = runCatching { app.api.rank("hot", 8) }.getOrDefault(emptyList())
    }
    // Results follow typing; the query is recorded in 搜索历史 when submitted or picked.
    LaunchedEffect(app.query) {
        error = null
        if (app.query.isBlank()) { results = null; return@LaunchedEffect }
        delay(500)
        runCatching { app.api.search(app.query.trim()) }.onSuccess { results = it }.onFailure { error = "搜索失败：${it.message}" }
    }

    Row(Modifier.fillMaxSize().background(PageBg)) {
        Column(Modifier.width(420.dp).padding(start = 64.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T("搜索", 35.sp, FgDim, FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                androidx.tv.material3.Icon(Icons.Search, null, Modifier.size(22.dp), tint = Fg)
                TvTextField(app.query, { app.query = it }, "按 OK 输入剧名", Modifier.focusRequester(field), width = 280.dp, onSubmit = { submit(app.query) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("拼音输入", { runCatching { field.requestFocus() } }, icon = Icons.Keyboard, height = 40.dp, size = 16.sp)
                if (voiceAvailable) Pill("语音", { voice.launch(voiceIntent) }, icon = Icons.Mic, height = 40.dp, size = 16.sp)
            }
            if (history.isNotEmpty()) {
                Row(Modifier.width(310.dp).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("搜索历史", 16.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Pill("清空", {
                        scope.launch { runCatching { app.api.clearSearchHistory() }; history = emptyList() }
                    }, height = 28.dp, size = 13.sp, container = Color.Transparent, content = FgDim)
                }
                FlowRow(Modifier.width(310.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.forEach { h -> Pill(h, { submit(h) }, height = 32.dp, size = 14.sp) }
                }
            }
            if (hot.isNotEmpty()) {
                T("正在热搜", 16.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                FlowRow(Modifier.width(310.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hot.forEach { h -> Pill(h, { submit(h) }, height = 32.dp, size = 14.sp) }
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 32.dp, end = 36.dp)) {
            if (app.query.isBlank()) {
                T("热搜短剧榜", 18.sp, weight = FontWeight.Bold, modifier = Modifier.height(42.dp).padding(top = 8.dp))
                LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(hotRank, key = { _, c -> c.seriesId }) { i, c -> HotRow(app, i + 1, c) }
                }
            } else {
                T("「${app.query.trim()}」的搜索结果${results?.let { " · ${it.size}" }.orEmpty()}", 18.sp, weight = FontWeight.Bold, modifier = Modifier.height(42.dp).padding(top = 8.dp))
                CardGrid(app, results, 4, error, posterHeight = 170.dp, empty = "没有找到相关短剧", onOpen = { submit(app.query) })
            }
        }
    }
}

@Composable
private fun HotRow(app: AppState, rank: Int, c: SeriesCard) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { app.open(c.seriesId, c) },
        modifier = Modifier.fillMaxWidth().height(80.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0AFFFFFF), contentColor = Fg, focusedContainerColor = FocusBg, focusedContentColor = FocusFg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "$rank", Modifier.width(20.dp), fontSize = 18.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                color = if (rank <= 3) (if (focused) Color(0xFFE0294A) else Color(0xFFFF8A3D)) else (if (focused) FocusFg else Color(0x66F1F1F1)),
            )
            AsyncImage(app.api.img(c.cover), null, Modifier.size(48.dp, 64.dp).background(Color(0xFF2A2A30), RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(c.title, fontSize = 15.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                Text(c.hot.ifEmpty { "${fmtCount(c.playCnt)}播放" }, fontSize = 12.sp, color = if (focused) Color(0xFF4A4F57) else FgDim, maxLines = 1)
            }
        }
    }
}

@Composable
fun MineScreen(app: AppState, initialTab: Int) {
    BackHandler { app.backToNav() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab) }
    var syncedAt by remember { mutableStateOf<Long?>(null) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    val tabFocus = remember { FocusRequester() }
    val user = app.user

    suspend fun pullCloud() {
        runCatching { app.api.cloudHistory() }
            .onSuccess { h -> if (h.loggedIn) { app.store.adopt(mergeHistory(app.store.watched.values, h.items)); syncedAt = System.currentTimeMillis() } }
            .onFailure { cloudError = "同步失败：${it.message}" }
    }
    LaunchedEffect(user) { if (user != null) pullCloud() }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }

    val favs = app.store.favs.values.sortedByDescending { it.addedAt }
    val history = app.store.history()
    Column(Modifier.fillMaxSize().background(PageBg).padding(start = 40.dp, top = 32.dp, end = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(60.dp).background(Color(0xFF5B6270), CircleShape), contentAlignment = Alignment.Center) {
                T((user?.name ?: "?").take(1), 26.sp, weight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                T(user?.let { it.name.ifEmpty { "已登录" } } ?: "未登录", 22.sp, weight = FontWeight.Bold)
                T(
                    cloudError ?: if (user == null) "登录后同步追剧和观看记录" else if (syncedAt != null) "已与手机同步 · 刚刚" else "正在同步观看记录…",
                    13.sp, Color(0x8CF1F1F1),
                )
            }
            Spacer(Modifier.weight(1f))
            if (user != null) {
                Pill("立即同步", { scope.launch { cloudError = null; pullCloud() } }, height = 36.dp, size = 14.sp)
                Pill("退出登录", { scope.launch { runCatching { app.api.logout() }; app.user = null; syncedAt = null } }, height = 36.dp, size = 14.sp)
            } else {
                Pill("扫码登录", { app.go(Screen.Login) }, height = 36.dp, size = 14.sp)
            }
        }
        Box(Modifier.padding(top = 20.dp)) {
            Tabs(listOf("追剧 · ${favs.size}", "观看历史"), tab, { tab = it; app.navSel = if (it == 0) Nav.Follow else Nav.History }, tabFocus, big = false)
        }
        val cards: List<Triple<String, String, Watched?>> =
            if (tab == 0) favs.map { Triple(it.sid, it.title, app.store.watched[it.sid]) }
            else history.map { Triple(it.sid, it.title, it) }
        val covers = if (tab == 0) favs.associate { it.sid to it.cover } else history.associate { it.sid to it.cover }
        val totals = if (tab == 0) favs.associate { it.sid to it.episodeCnt } else history.associate { it.sid to it.episodeCnt }
        if (cards.isEmpty()) {
            T(if (tab == 0) "还没有追剧，播放时按 OK 点「追剧」" else "暂无观看历史", 15.sp, FgDim, modifier = Modifier.padding(top = 30.dp, start = 10.dp))
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(6), Modifier.padding(top = 8.dp), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(cards, key = { _, c -> c.first }) { i, (sid, title, w) ->
                    val total = totals[sid] ?: 0
                    PosterCard(
                        title.ifEmpty { "剧集 $sid" }, app.api.img(covers[sid].orEmpty()), { app.open(sid) }, Modifier.firstColumn(i, 6), posterHeight = 205.dp,
                        meta = when {
                            w == null -> if (total > 0) "共 $total 集" else ""
                            total > 0 && w.ep >= total -> "已看完"
                            total > 0 -> "看到第 ${w.ep} 集 / $total"
                            else -> "看到第 ${w.ep} 集"
                        },
                        progress = if (w != null && total > 0) w.ep.toFloat() / total else null,
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(app: AppState) {
    BackHandler { app.backToNav() }
    var qr by remember { mutableStateOf<QrCode?>(null) }
    var status by remember { mutableStateOf("二维码加载中…") }
    var reload by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(reload) {
        qr = null
        status = "二维码加载中…"
        val code = runCatching { app.api.qrcode() }.getOrElse { status = "二维码加载失败：${it.message} · 按 OK 重试"; return@LaunchedEffect }
        qr = code
        status = "等待扫码…"
        while (true) {
            delay(2000)
            val s = runCatching { app.api.authStatus(code.token) }.getOrNull() ?: continue
            when (s.status) {
                "scanned" -> status = "已扫码，请在手机上确认"
                "confirmed" -> {
                    app.user = s.user ?: AuthUser()
                    status = "登录成功${s.user?.name?.let { "：$it" }.orEmpty()}"
                    delay(1200)
                    app.navSel = Nav.Follow
                    app.go(Screen.Mine(0))
                    return@LaunchedEffect
                }
                "expired" -> { reload++; return@LaunchedEffect }
            }
        }
    }
    val bitmap = remember(qr) {
        qr?.let { runCatching { Base64.decode(it.qrcode, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }.asImageBitmap() }.getOrNull() }
    }
    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.width(220.dp).fillMaxSize().background(Color.Black).padding(start = 16.dp, top = 60.dp, bottom = 30.dp)) {
            NavItem("扫码登录", true, { reload++ }, Modifier.focusRequester(focus)) {}
            Spacer(Modifier.weight(1f))
            NavItem("设置", false, { app.go(Screen.Settings) }, dim = true) { androidx.tv.material3.Icon(Icons.Settings, null, Modifier.size(17.dp)) }
        }
        Box(Modifier.width(1.dp).fillMaxSize().background(Color(0x14F1F1F1)))
        Column(Modifier.weight(1f).padding(top = 52.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T("用番茄小说 App「扫一扫」扫码登录", 28.sp, weight = FontWeight.Bold)
            Box(
                Modifier.padding(top = 30.dp).size(200.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) Image(bitmap, "登录二维码", Modifier.fillMaxSize())
                else T("…", 14.sp, Color(0xFF6D727A))
            }
            T(status, 17.sp, if (status.startsWith("已扫码") || status.startsWith("登录成功")) Accent else Fg, modifier = Modifier.padding(top = 22.dp))
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                T("1. 打开手机上的番茄小说 App", 15.sp, Color(0xBFF1F1F1))
                T("2. 点「扫一扫」，对准二维码", 15.sp, Color(0xBFF1F1F1))
                T("3. 在手机上确认登录", 15.sp, Color(0xBFF1F1F1))
            }
            Spacer(Modifier.weight(1f))
            T("与红果同一账号体系 · 登录后同步追剧和观看记录", 13.sp, Color(0x73F1F1F1))
        }
    }
}
